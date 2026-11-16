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
import app.adbphon.FloorText as Text
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
            // общий ключ входа отклонён облаком (или права ключа не те): лечится своим ключом; для облаков без своего ключа fromText вернёт это в "auth"/"other"
            "unauthorized_client" in t || "invalid_client" in t || "access_denied" in t || "error 403" in t || "403 forbidden" in t || "status code 403" in t || "forbidden" in t -> "key"
            "invalid_grant" in t || "expired or revoked" in t || "empty token" in t || "error 401" in t -> "auth"
            "unexpected end of json" in t || "eagain" in t || "try again" in t || "temporarily" in t || "bad gateway" in t || "service unavailable" in t || "server busy" in t -> "transient"
            "lookup" in t || "dial tcp" in t || "timeout" in t || "no such host" in t || "connection refused" in t -> "net"
            else -> "other"
        }
    }

    /** Временные сбои: стоит повторить без участия пользователя. */
    fun retryable(kind: String) = kind == "quota" || kind == "transient" || kind == "net"

    /** Названия облаков (10.0): из строк ресурсов misc_cloud_* (ru/uk/en; одинаковые на всех языках лежат только в values). */
    private val nameRes = mapOf(
        "drive" to R.string.misc_cloud_drive, "google photos" to R.string.misc_cloud_google_photos, "yandex" to R.string.misc_cloud_yandex,
        "dropbox" to R.string.misc_cloud_dropbox, "box" to R.string.misc_cloud_box, "onedrive" to R.string.misc_cloud_onedrive,
        "pcloud" to R.string.misc_cloud_pcloud, "zoho" to R.string.misc_cloud_zoho, "mega" to R.string.misc_cloud_mega,
        "hidrive" to R.string.misc_cloud_hidrive, "putio" to R.string.misc_cloud_putio,
    )

    fun providerName(ctx: Context, type: String?): String =
        nameRes[type]?.let { ctx.getString(it) } ?: type?.replaceFirstChar { it.uppercase() } ?: ctx.getString(R.string.cl_account)

    /** Из результата движка: понятная причина вместо куска JSON-ответа. Токены и адреса в текст не попадают. */
    fun fromText(ctx: Context, raw: String, type: String?): CloudApiError {
        var kind = classify(raw)
        // вход Google слетел у СВОЕГО ключа: чаще всего ключ создан в режиме «Тестирование» (вход держится 7 дней); подсказка ведёт на шаг «Опубликовать приложение»
        // «key» (отказ общего ключа) осмыслен только для облаков со своим ключом, где своего ещё нет; иначе прежние виды ошибок
        if (kind == "key" && (type == null || type == "drive" || type == "google photos" || type !in OwnKeys.types || OwnKeys.get(ctx, type) != null)) kind = if (raw.lowercase().let { "unauthorized_client" in it || "invalid_client" in it }) "auth" else "other"
        if (kind == "auth" && (type == "drive" || type == "google photos") && OwnKeys.get(ctx, type) != null) kind = "expired"
        // 10.0: 402 (WebDAV Яндекса на бесплатном тарифе), место, TLS, 404, 5xx и прочее, что раньше показывалось сырым английским текстом
        val nk = NetErrMap.classify(raw)
        var shownType = type
        var nkCause: String? = null
        if (nk == NetErrMap.Kind.YANDEX_WEBDAV_FREE) { kind = "yfree"; shownType = "yandex"; nkCause = NetErrText.say(ctx, nk) }
        else if (nk == NetErrMap.Kind.PAYMENT || nk == NetErrMap.Kind.SPACE) { kind = if (nk == NetErrMap.Kind.PAYMENT) "pay" else "space"; nkCause = NetErrText.say(ctx, nk) }
        else if (kind == "other" && nk != null) {
            kind = when (nk) { NetErrMap.Kind.AUTH -> "auth"; NetErrMap.Kind.NO_NET, NetErrMap.Kind.TIMEOUT -> "net"; NetErrMap.Kind.RATE -> "transient"; else -> nk.name.lowercase() }
            nkCause = NetErrText.say(ctx, nk)
        }
        if (nk != null) AppLog.i("ADBphon", "облако: ошибка $kind ($nk), тип $type: ${raw.replace('\n', ' ').replace(Regex("(?i)(token|bearer)[^ ]*\\s*[^ ]+"), "$1 ***").take(240)}")
        val cause = if (nkCause != null) nkCause else when (kind) {
            "expired" -> ctx.getString(R.string.cl_e_g_expired)
            "quota" -> ctx.getString(R.string.cl_e_quota)
            "disabled" -> ctx.getString(R.string.cl_e_disabled)
            "scope" -> ctx.getString(R.string.cl_e_scope)
            "auth" -> ctx.getString(R.string.cl_e_auth)
            "key" -> ctx.getString(R.string.cl_e_key)
            "net" -> ctx.getString(R.string.cl_err_net)
            "transient" -> ctx.getString(R.string.cl_e_transient)
            else -> {
                // только осмысленная строка: «message»/«Error N», не служебные поля JSON
                // 10.0: без английского заголовка команды («Failed to lsd with 2 errors: last error was:»)
                NetErrMap.cleanRaw(raw.lines().filter { !it.trim().startsWith("\"reason") }.joinToString("\n"))
            }
        }
        val own = if (kind in setOf("quota", "disabled", "scope")) " " + ctx.getString(R.string.cl_e_own_key_tap) else if (kind == "key") " " + ctx.getString(R.string.cl_e_key_tap) else ""
        return CloudApiError(kind, ctx.getString(R.string.cl_e_head, providerName(ctx, shownType)) + ": " + cause + own, shownType)
    }

    /** Сообщение про слетевший вход Google у своего ключа (даёт кнопку на шаг «Опубликовать приложение»). */
    fun offersPublish(ctx: Context, msg: String) = msg.contains(ctx.getString(R.string.cl_e_g_expired))

    /** Сообщение содержит предложение «свой ключ». */
    fun offersOwnKey(ctx: Context, msg: String) = msg.contains(ctx.getString(R.string.cl_e_own_key_tap)) || msg.contains(ctx.getString(R.string.cl_e_key_tap)) || msg.contains(ctx.getString(R.string.cl_e_key_login))

    /** Виды ошибок, при которых программа предлагает подключить свой ключ. */
    val ownKeyKinds = setOf("quota", "disabled", "scope", "key", "yfree")
}

/**
 * Только для стенда: [fakeAdd] подменяет результат успешного добавления облака (без создания записи);
 * [fakeErr] (10.0) подменяет ответ движка при проверке входа в форме облака и при открытии облака: сырой текст ошибки, как его пишет движок.
 */
object CloudTestHook { @Volatile var fakeAdd: NetConn? = null; @Volatile var fakeErr: String? = null }

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
