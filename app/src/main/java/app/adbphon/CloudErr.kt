package app.adbphon

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Ошибка облака с понятной причиной. [kind]: quota (лимит общего ключа), disabled, scope, auth, net, other; [type]: тип облака в движке. */
class CloudApiError(val kind: String, message: String, val type: String?) : java.io.IOException(message)

object CloudErr {
    /** Включается стендом для проверки экрана ошибки без настоящего сбоя Google. */
    @Volatile var fakeQuota = false

    const val FAKE_BODY = "Failed to create file system: couldn't find root directory ID: googleapi: Error 403: Quota exceeded for quota metric 'Queries' and limit 'Requests per minute' | \"@type\": \"type.googleapis.com/google.rpc.ErrorInfo\", | \"domain\": \"googleapis.com\", | \"reason\": \"RATE_LIMIT_EXCEEDED\""

    fun classify(text: String): String {
        val t = text.lowercase()
        return when {
            "rate_limit_exceeded" in t || "quota exceeded" in t || "ratelimitexceeded" in t || "userratelimitexceeded" in t || "too many requests" in t || " 429" in t -> "quota"
            "service_disabled" in t || "has not been used in project" in t || "accessnotconfigured" in t -> "disabled"
            "access_token_scope_insufficient" in t || "insufficient authentication scopes" in t || "insufficientpermissions" in t -> "scope"
            "invalid_grant" in t || "expired or revoked" in t || "empty token" in t || "unauthorized_client" in t || "error 401" in t || "invalid_client" in t -> "auth"
            "unexpected end of json" in t || "eagain" in t || "try again" in t || "temporarily" in t || "bad gateway" in t || "service unavailable" in t || "server busy" in t -> "transient"
            "lookup" in t || "dial tcp" in t || "timeout" in t || "no such host" in t || "connection refused" in t -> "net"
            else -> "other"
        }
    }

    /** Временные сбои: стоит повторить без участия пользователя. */
    fun retryable(kind: String) = kind == "quota" || kind == "transient" || kind == "net"

    private fun isEn(ctx: Context) = runCatching { ctx.resources.configuration.locales[0].language == "en" }.getOrDefault(false)

    fun providerName(ctx: Context, type: String?): String = when (type) {
        "drive" -> if (isEn(ctx)) "Google Drive" else "Google Диск"; "dropbox" -> "Dropbox"; "yandex" -> if (isEn(ctx)) "Yandex Disk" else "Яндекс Диск"; "box" -> "Box"; "onedrive" -> "OneDrive"; "pcloud" -> "pCloud"
        "google photos" -> if (isEn(ctx)) "Google Photos" else "Google Фото"; "zoho" -> "Zoho"; "mega" -> "MEGA"
        else -> type?.replaceFirstChar { it.uppercase() } ?: ctx.getString(R.string.cl_account)
    }

    /** Из результата движка: понятная причина вместо куска JSON-ответа. Токены и адреса в текст не попадают. */
    fun fromText(ctx: Context, raw: String, type: String?): CloudApiError {
        var kind = classify(raw)
        // вход Google слетел у СВОЕГО ключа: чаще всего ключ создан в режиме «Тестирование» (вход держится 7 дней); подсказка ведёт на шаг «Опубликовать приложение»
        if (kind == "auth" && (type == "drive" || type == "google photos") && OwnKeys.get(ctx, type) != null) kind = "expired"
        val cause = when (kind) {
            "expired" -> ctx.getString(R.string.cl_e_g_expired)
            "quota" -> ctx.getString(R.string.cl_e_quota)
            "disabled" -> ctx.getString(R.string.cl_e_disabled)
            "scope" -> ctx.getString(R.string.cl_e_scope)
            "auth" -> ctx.getString(R.string.cl_e_auth)
            "net" -> ctx.getString(R.string.cl_err_net)
            "transient" -> ctx.getString(R.string.cl_e_transient)
            else -> {
                // только осмысленная строка: «message»/«Error N», не служебные поля JSON
                val line = raw.lines().map { it.trim().trim(',') }.lastOrNull { it.isNotBlank() && !it.startsWith("\"@type") && !it.startsWith("\"domain") && !it.startsWith("\"reason") && !it.startsWith("{") && !it.startsWith("}") && !it.startsWith("]") }.orEmpty()
                line.replace(Regex("(?i)rclone"), "ADB-phon").replace(Regex("^\\d{4}/\\d\\d/\\d\\d \\d\\d:\\d\\d:\\d\\d\\s*"), "").replace(Regex("^(ERROR|NOTICE|CRITICAL)\\s*:\\s*"), "").take(160)
            }
        }
        val own = if (kind in setOf("quota", "disabled", "scope")) " " + ctx.getString(R.string.cl_e_own_key_tap) else ""
        return CloudApiError(kind, ctx.getString(R.string.cl_e_head, providerName(ctx, type)) + ": " + cause + own, type)
    }

    /** Сообщение про слетевший вход Google у своего ключа (даёт кнопку на шаг «Опубликовать приложение»). */
    fun offersPublish(ctx: Context, msg: String) = msg.contains(ctx.getString(R.string.cl_e_g_expired))

    /** Сообщение содержит предложение «свой ключ». */
    fun offersOwnKey(ctx: Context, msg: String) = msg.contains(ctx.getString(R.string.cl_e_own_key_tap))
}

/** Только для стенда: подменяет результат успешного добавления облака (без создания записи). */
object CloudTestHook { @Volatile var fakeAdd: NetConn? = null }

/** Свои ключи (client_id и client_secret) пользователя для входа в облака: необязательный расширенный вариант против лимитов общего ключа. */
object OwnKeys {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("cloudkeys", Context.MODE_PRIVATE)
    fun get(ctx: Context, type: String): Pair<String, String>? {
        val s = sp(ctx); val id = s.getString("id_$type", null)?.takeIf { it.isNotBlank() } ?: return null
        val sec = s.getString("secret_$type", null)?.takeIf { it.isNotBlank() } ?: return null
        return id to sec
    }
    fun set(ctx: Context, type: String, id: String, secret: String) { sp(ctx).edit().putString("id_$type", id.trim()).putString("secret_$type", secret.trim()).apply() }
    fun clear(ctx: Context, type: String) { sp(ctx).edit().remove("id_$type").remove("secret_$type").apply() }

    /** Где пользователь регистрирует приложение (страница разработчика облака); шаги в справке. */
    fun consoleUrl(type: String): String = when (type) {
        "drive", "google photos", "google cloud storage" -> "https://console.cloud.google.com/apis/credentials"
        "dropbox" -> "https://www.dropbox.com/developers/apps"
        "yandex" -> "https://oauth.yandex.com/client/new"
        "box" -> "https://app.box.com/developers/console"
        "onedrive" -> "https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade"
        "pcloud" -> "https://docs.pcloud.com/my_apps/"
        "zoho" -> "https://api-console.zoho.com/"
        "hidrive" -> "https://developer.hidrive.com/"
        "putio" -> "https://put.io/oauth/apps"
        else -> "https://rclone.org/docs/#configure"
    }

    val types = listOf("drive", "dropbox", "onedrive", "yandex", "box", "pcloud", "zoho", "hidrive", "putio")
}

/** Выбор облака для «Свой ключ» (расширенно). */
@Composable
fun OwnKeyPickDlg(close: () -> Unit) {
    val ctx = LocalContext.current
    LAlertDialog(
        wide = true, onDismissRequest = close,
        title = { HintTitle(stringResource(R.string.ok_pick_title), stringResource(R.string.ok_why)) },
        text = {
            Column(Modifier.barScroll()) {
                CloudTileGrid(OwnKeys.types.map { t -> CloudTile(CloudErr.providerName(ctx, t), t) { close(); Fm.dlg = FmDlg.OwnKey(t) } })
            }
        },
        confirmButton = { TextButton({ close(); Fm.dlg = FmDlg.CloudAdd() }) { Text(stringResource(R.string.cl_mega_back)) } },
    )
}
