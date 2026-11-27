package app.adbphon

import androidx.compose.material3.Text
import kotlinx.coroutines.launch

/**
 * 27.11.2026: одна кнопка «Включить клавиатуру Папирус» на устройстве, которое под управлением ADB (в том числе на этом).
 * Нужна там, где в настройках трудно или нельзя выбрать клавиатуру (приставки).
 */
object PapirusIme {
    private const val PKG = "app.papirus"
    private const val ID = "app.papirus/.KeyboardService"
    /** Команды выполняются одной строкой в оболочке устройства; без Папируса клавиатуру не трогаем. */
    const val CMD = "if pm path $PKG 2>/dev/null | grep -q '^package:'; then ime enable $ID 2>&1; ime set $ID 2>&1; " +
        "echo PAPIRUS_DEF=\$(settings get secure default_input_method); else echo PAPIRUS_NONE; fi"

    const val TMP = "/data/local/tmp/papirus.apk"
    const val INSTALL_CMD = "pm install -r $TMP 2>&1; rm -f $TMP"

    enum class Result { ON, NOT_INSTALLED, FAILED }

    fun parse(out: String): Result {
        val lines = out.lines().map { it.trim() }
        if (lines.any { it == "PAPIRUS_NONE" }) return Result.NOT_INSTALLED
        val def = lines.lastOrNull { it.startsWith("PAPIRUS_DEF=") }?.substringAfter('=').orEmpty()
        return if (def.startsWith("$PKG/")) Result.ON else Result.FAILED
    }

    private const val REPO = "drugoj00/papirus"
    /** Отпечаток нашей подписи Папируса: чужой файл под этим именем не установится. */
    private const val SIGNER = "7f9c988e14fa6ad7beb7526d6b50d65b5972220417641e1a2a9dc0bc87b6198e"

    fun sameSigner(sig: String?): Boolean = sig != null && sig.replace(":", "").trim().equals(SIGNER, ignoreCase = true)

    /** Адрес APK в ответе GitHub о выпуске; null, если файла в выпуске нет. */
    fun apkUrl(release: org.json.JSONObject): String? {
        val a = release.optJSONArray("assets") ?: return null
        return (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }?.optString("browser_download_url")?.takeIf { it.isNotEmpty() }
    }

    private fun open(url: String): java.net.HttpURLConnection {
        val c = java.net.URL(GhFeed.map(url)).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "ADB-phon")
        if (c.responseCode != 200) { val code = c.responseCode; c.disconnect(); throw java.io.IOException("HTTP $code") }
        return c
    }

    /** Скачивает последний выпуск Папируса и проверяет, что это он и подписан нами. Блокирующий вызов: не в главном потоке. */
    fun download(ctx: android.content.Context): java.io.File {
        // API GitHub может не ответить (лимит запросов): тогда тот же список читается из ленты выпусков, как при обновлении самой программы
        val release = runCatching {
            val c = open("https://api.github.com/repos/$REPO/releases/latest")
            try { org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
        }.getOrElse { GhFeed.releases(REPO, 3, 1).firstOrNull() ?: throw it }
        val url = apkUrl(release) ?: throw java.io.IOException("no apk in release")
        val file = java.io.File(ctx.cacheDir, "papirus.apk")
        val c = open(url)
        try { c.inputStream.use { i -> file.outputStream().use { o -> i.copyTo(o) } } } finally { c.disconnect() }
        val pkg = ctx.packageManager.getPackageArchiveInfo(file.path, 0)?.packageName
        if (pkg != PKG || !sameSigner(BkData.signerOfApk(ctx, file))) { file.delete(); throw java.io.IOException(ctx.getString(R.string.papirus_wrong_file)) }
        return file
    }

    /** Установка на устройстве по отдельной связи (меню устройства в «Файлах»). Возвращает ответ системы. */
    suspend fun installOver(link: AdbLink, apk: java.io.File): String {
        link.push(apk, TMP, null)
        return link.shell(INSTALL_CMD, 180_000)
    }

    fun installed(out: String): Boolean = out.lines().any { it.trim() == "Success" }

    fun lastLine(out: String): String = out.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty().take(140)

    /** Что сказать человеку: строка и подстановки к ней; при неудаче это последняя строка ответа устройства. */
    fun message(out: String): Pair<Int, Array<Any>> = when (parse(out)) {
        Result.ON -> R.string.papirus_done to emptyArray<Any>()
        Result.NOT_INSTALLED -> R.string.papirus_none to emptyArray<Any>()
        Result.FAILED -> R.string.papirus_fail to arrayOf<Any>(out.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("PAPIRUS_") }.orEmpty())
    }
}

/** Окно: Папируса на устройстве нет, поставить его? */
@androidx.compose.runtime.Composable
fun PapirusAskDialog(device: String, close: () -> Unit, install: () -> Unit) {
    LAlertDialog(
        onDismissRequest = close,
        title = { androidx.compose.material3.Text(if (device.isEmpty()) androidx.compose.ui.res.stringResource(R.string.papirus_on) else device) },
        text = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.papirus_ask)) },
        confirmButton = { Button({ close(); install() }) { Text(androidx.compose.ui.res.stringResource(R.string.papirus_install)) } },
        dismissButton = { TextButton(close) { Text(androidx.compose.ui.res.stringResource(R.string.btn_cancel)) } },
    )
}

/** Скачать Папирус, поставить на [d] и включить; ход и итог внизу экрана «Файлов». */
fun fmPapirusInstall(app: android.content.Context, d: AdbDev) {
    Fm.scope.launch {
        Fm.say(d.title + ": " + Fm.str(R.string.papirus_loading))
        val result = runCatching {
            val apk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PapirusIme.download(Fm.sctx) }
            val link = adbLinkFor(d.addrs)
            val r = PapirusIme.installOver(link, apk)
            apk.delete()
            if (!PapirusIme.installed(r)) error(PapirusIme.lastLine(r))
            link.shell(PapirusIme.CMD)
        }
        Fm.say(d.title + ": " + result.fold({ val (id, args) = PapirusIme.message(it); Fm.str(id, *args) }, { Fm.str(R.string.papirus_install_fail, it.message ?: it.javaClass.simpleName) }))
    }
}
