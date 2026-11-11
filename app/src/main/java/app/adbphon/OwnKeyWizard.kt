package app.adbphon

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Мастер «Свой ключ»: ключ создаётся на сайте провайдера под своим аккаунтом, всё остальное делает программа. Страницы консоли открываются прямыми
 * ссылками на нужный раздел, нужные тексты (название проекта, адрес возврата) программа сама кладёт в буфер обмена, а когда пользователь возвращается в
 * программу, она читает буфер (это разрешено только в переднем плане), узнаёт идентификатор клиента и секрет по шаблонам, подставляет их, проверяет
 * формат, сохраняет и сама запускает вход. Сам сайт не автоматизируется: ни скриптов в страницах, ни WebView, ни службы доступности.
 * Значения из буфера нигде не записываются в журнал; после чтения буфер очищается.
 */
object OwnKeyWizard {
    private val gId = Regex("\\b\\d{6,}-[a-z0-9]{8,}\\.apps\\.googleusercontent\\.com\\b")
    private val gSecret = Regex("\\bGOCSPX-[A-Za-z0-9_-]{16,}\\b")
    private val token = Regex("^[A-Za-z0-9_.~-]{16,120}$")

    /** Форма значения по провайдеру: проверка идентификатора и секрета. */
    fun validId(type: String, s: String): Boolean = when (type) {
        "drive", "google photos" -> gId.matches(s)
        "dropbox" -> Regex("^[a-z0-9]{15}$").matches(s)
        "yandex" -> Regex("^[0-9a-f]{32}$").matches(s)
        "box" -> Regex("^[A-Za-z0-9]{32}$").matches(s)
        "onedrive" -> Regex("^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$").matches(s)
        else -> token.matches(s)
    }

    fun validSecret(type: String, s: String): Boolean = when (type) {
        "drive", "google photos" -> gSecret.matches(s) || Regex("^[A-Za-z0-9_-]{24}$").matches(s)
        "dropbox" -> Regex("^[a-z0-9]{15}$").matches(s)
        "yandex" -> Regex("^[0-9a-f]{32}$").matches(s)
        "box" -> Regex("^[A-Za-z0-9]{32}$").matches(s)
        "onedrive" -> Regex("^[A-Za-z0-9_.~-]{20,120}$").matches(s)
        else -> token.matches(s)
    }

    /** Что нашли в тексте буфера: идентификатор, секрет (любой из них может отсутствовать). */
    class Found(val id: String?, val secret: String?)

    fun scan(type: String, text: String): Found {
        val t = text.trim()
        if (t.isEmpty() || t.length > 4000) return Found(null, null)
        if (type == "drive" || type == "google photos") {
            val id = gId.find(t)?.value
            val sec = gSecret.find(t)?.value
            // старые секреты без префикса: одно значение без пробелов длиной 24
            val old = if (sec == null && id == null && Regex("^[A-Za-z0-9_-]{24}$").matches(t)) t else null
            return Found(id, sec ?: old)
        }
        // остальные: одно значение без пробелов; какое это (идентификатор или секрет) решает мастер по порядку; здесь возвращаем кандидата как идентификатор
        val parts = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val toks = parts.map { it.trim('"', '\'', ',', ';', ':') }
        return when {
            toks.size == 1 && validId(type, toks[0]) -> Found(toks[0], null)
            toks.size >= 2 && validId(type, toks[0]) && validSecret(type, toks[1]) -> Found(toks[0], toks[1])
            else -> Found(null, null)
        }
    }

    /** Адреса разделов консоли с готовыми параметрами (там, где консоль их принимает). */
    fun stepUrl(type: String, step: Int): String = when (type) {
        "drive", "google photos" -> when (step) {
            0 -> "https://console.cloud.google.com/projectcreate"
            1 -> "https://console.cloud.google.com/apis/library/" + (if (type == "drive") "drive.googleapis.com" else "photoslibrary.googleapis.com")
            2, 3 -> "https://console.cloud.google.com/apis/credentials/consent"
            else -> "https://console.cloud.google.com/apis/credentials/oauthclient"
        }
        "dropbox" -> "https://www.dropbox.com/developers/apps/create"
        "yandex" -> "https://oauth.yandex.com/client/new"
        "box" -> "https://app.box.com/developers/console/newapp"
        "onedrive" -> "https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/CreateApplicationBlade/isMSAApp~/false"
        else -> OwnKeys.consoleUrl(type)
    }

    const val APP_NAME = "ADB-phon"
    /** Номер шага «Опубликовать приложение» у Google (с нуля): без него вход ключа в режиме «Тестирование» слетает через 7 дней. */
    const val PUBLISH_STEP = 3
    const val REDIRECT = "http://127.0.0.1:53682/"

    fun setClip(ctx: Context, text: String) {
        runCatching { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ADB-phon", text)) }
    }

    fun readClip(ctx: Context): String? = runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
    }.getOrNull()

    fun clearClip(ctx: Context) {
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}

/** Мастер «Свой ключ»: на каждом шаге одна короткая строка, прогресс «шаг N из M», автоподстановка из буфера обмена. */
@Composable
fun OwnKeyDlg(type: String, close: () -> Unit, startStep: Int = 0, sharedClosed: Boolean = false) {
    val ctx = LocalContext.current
    var id by remember { mutableStateOf(OwnKeys.get(ctx, type)?.first.orEmpty()) }
    var secret by remember { mutableStateOf(OwnKeys.get(ctx, type)?.second.orEmpty()) }
    val google0 = type == "drive" || type == "google photos"
    var step by remember { mutableIntStateOf(if (google0) startStep.coerceIn(0, 4) else startStep.coerceIn(0, 2)) }
    var note by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }
    val google = type == "drive" || type == "google photos"
    val lines = if (google) listOf(R.string.okw_g1, R.string.okw_g2, R.string.okw_g3, R.string.okw_gp, R.string.okw_g4) else listOf(R.string.okw_x1, R.string.okw_x2, R.string.okw_x3)
    val total = lines.size
    val last = step == total - 1

    fun startLogin(i: String, s: String) {
        if (started) return
        started = true
        OwnKeys.set(ctx, type, i, s)
        OwnKeyWizard.clearClip(ctx)
        close()
        val pr = CloudAuth.providers.firstOrNull { it.type == type }
        PermFlow.forCloudLogin(ctx) { CloudAuth.start(ctx, pr ?: CloudAuth.Provider(type, 0, emptyList(), CloudErr.providerName(ctx, type))) }
        Fm.dlg = FmDlg.CloudAdd()
    }

    // возвращение в программу: читаем буфер (в переднем плане), подставляем найденное, при двух значениях сохраняем и входим
    fun check() {
        val text = OwnKeyWizard.readClip(ctx) ?: return
        val f = OwnKeyWizard.scan(type, text)
        var got = false
        if (google) {
            if (f.id != null) { id = f.id; got = true }
            if (f.secret != null) { secret = f.secret; got = true }
        } else if (f.id != null) {
            // первое значение: идентификатор; уже есть идентификатор и пришло другое значение: это секрет
            if (f.secret != null) { id = f.id; secret = f.secret; got = true }
            else if (id.isBlank() || id == f.id) { id = f.id; got = true }
            else if (OwnKeyWizard.validSecret(type, f.id) && f.id != id) { secret = f.id; got = true }
        } else {
            val t = text.trim().trim('"', '\'')
            if (id.isNotBlank() && secret.isBlank() && OwnKeyWizard.validSecret(type, t) && t != id) { secret = t; got = true }
        }
        if (got) {
            OwnKeyWizard.clearClip(ctx)
            note = ctx.getString(if (id.isNotBlank() && secret.isNotBlank()) R.string.okw_got_both else R.string.okw_got_one)
            if (OwnKeyWizard.validId(type, id) && OwnKeyWizard.validSecret(type, secret)) startLogin(id, secret)
            else if (id.isNotBlank() && secret.isNotBlank()) note = ctx.getString(R.string.okw_bad)
        }
    }

    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val tick = resumeTick
    LaunchedEffect(tick) {
        // окно получает фокус не мгновенно: читать буфер до этого нельзя, поэтому две попытки
        delay(350); check(); delay(700); check()
    }

    // нужный текст кладётся в буфер в нужный момент
    // название проекта и адрес возврата кладутся в буфер при показе шага (запись в буфер разрешена и в переднем плане)
    fun openStep(copy: String?) {
        if (copy != null) { OwnKeyWizard.setClip(ctx, copy); note = ctx.getString(R.string.okw_copied) } else note = ""
        CloudAuth.openUrl(ctx, OwnKeyWizard.stepUrl(type, if (google) step else 0))
    }
    val copyFor: String? = when {
        google && step == 0 -> OwnKeyWizard.APP_NAME
        google && step == 2 -> OwnKeyWizard.APP_NAME
        !google && step == 0 -> OwnKeyWizard.APP_NAME
        !google && step == 1 -> OwnKeyWizard.REDIRECT
        else -> null
    }

    LaunchedEffect(step) { copyFor?.let { OwnKeyWizard.setClip(ctx, it) } }
    LAlertDialog(
        ime = true, wide = true,
        onDismissRequest = close,
        title = { Text(stringResource(R.string.ok_title, CloudErr.providerName(ctx, type))) },
        text = {
            Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sharedClosed) Text(stringResource(R.string.okw_shared_closed), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.okw_progress, step + 1, total), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                ThemedProgress({ (step + 1) / total.toFloat() }, Modifier.fillMaxWidth())
                Text(stringResource(lines[step]), fontSize = 15.sp)
                if (!last) OutlinedButton({ openStep(copyFor) }, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { Text(stringResource(R.string.okw_open)) }
                else OutlinedButton({ CloudAuth.openUrl(ctx, OwnKeyWizard.stepUrl(type, if (google) step else 0)) }, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { Text(stringResource(R.string.okw_open)) }
                if (google && step == OwnKeyWizard.PUBLISH_STEP) Text(stringResource(R.string.okw_gp_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f))
                if (note.isNotEmpty()) Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                if (last) {
                    // запасной ручной ввод: поля остаются
                    CField("Client ID", id, { id = it; note = "" })
                    CField("Client Secret", secret, { secret = it; note = "" }, password = true)
                    if (id.isNotBlank() && !OwnKeyWizard.validId(type, id)) Text(stringResource(R.string.okw_bad_id), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    if (secret.isNotBlank() && !OwnKeyWizard.validSecret(type, secret)) Text(stringResource(R.string.okw_bad_secret), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (last) TextButton({ if (OwnKeyWizard.validId(type, id.trim()) && OwnKeyWizard.validSecret(type, secret.trim())) startLogin(id.trim(), secret.trim()) },
                enabled = id.isNotBlank() && secret.isNotBlank()) { Text(stringResource(R.string.ok_save)) }
            else TextButton({ step++; note = "" }) { Text(stringResource(R.string.okw_next)) }
        },
        dismissButton = {
            Row {
                if (step > 0) TextButton({ step--; note = "" }) { Text(stringResource(R.string.okw_back)) }
                if (OwnKeys.get(ctx, type) != null) TextButton({ OwnKeys.clear(ctx, type); close() }) { Text(stringResource(R.string.ok_clear)) }
                TextButton(close) { Text(stringResource(R.string.btn_cancel)) }
            }
        },
    )
}

/** Счётчик возвращений в программу (ON_RESUME): по нему мастер перечитывает буфер обмена. */
private var resumeTick by mutableIntStateOf(0)
