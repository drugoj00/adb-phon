package app.adbphon

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/**
 * Облачные диски через rclone (официальный Linux-arm64 бинарник лежит в jniLibs как librclone.so и запускается из каталога
 * нативных библиотек). Две особенности Go-сборки на Android и обходы:
 *  1) DNS: у Go нет /etc/resolv.conf → имена разрешает [CloudProxy] (маленький HTTP-CONNECT-прокси на 127.0.0.1, системный DNS Android);
 *     rclone запускается с HTTPS_PROXY/HTTP_PROXY на него;
 *  2) сертификаты: SSL_CERT_DIR=/system/etc/security/cacerts.
 * Настройки rclone: filesDir/rclone.conf (имя входит в состав резервной копии программы). Токены владельца программа не видит:
 * вход идёт в браузере, где пользователь вводит логин сам; rclone получает код на 127.0.0.1:53682.
 */
object Rclone {
    class Result(val code: Int, val out: String, val err: String) { val ok get() = code == 0 }

    /** Движок облаков лежит в отдельном модуле «ADB-phon Облака» (см. [CloudModule]); null: модуля нет, он чужой, устарел или не подходит устройству. */
    fun bin(ctx: Context): File? = CloudModule.engine(ctx)
    fun available(ctx: Context) = bin(ctx) != null
    fun conf(ctx: Context) = File(ctx.filesDir, "clouds.conf")

    private fun env(ctx: Context, pb: ProcessBuilder) {
        val e = pb.environment()
        val px = "http://127.0.0.1:${CloudProxy.port()}"
        e["HTTPS_PROXY"] = px; e["HTTP_PROXY"] = px; e["https_proxy"] = px; e["http_proxy"] = px
        e["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
        e["HOME"] = ctx.filesDir.absolutePath
        e["TMPDIR"] = ctx.cacheDir.absolutePath
        e["TZ"] = java.util.TimeZone.getDefault().id
    }

    /** Запуск rclone; [onLine] получает строки обоих потоков по мере появления. Отмена корутины или таймаут убивают процесс. */
    suspend fun run(ctx: Context, args: List<String>, timeoutMs: Long = 120_000, stdin: String? = null, onLine: ((String) -> Unit)? = null): Result = withContext(Dispatchers.IO) {
        // сетевые источники (SMB, SFTP, FTP, WebDAV): корзина и перенос выполняются своими клиентами, движок облаков им не нужен
        if (LanEngine.handles(args)) return@withContext LanEngine.run(ctx, args)
        val b = bin(ctx) ?: throw CloudModuleMissing(CloudModule.missingText(ctx))
        val app = ctx.applicationContext
        val pb = ProcessBuilder(listOf(b.absolutePath, "--config", conf(app).absolutePath) + args)
        env(app, pb)
        // движок не для этой архитектуры (установлен не тот вариант модуля): «Exec format error»; предлагаем поставить нужный
        val p = try { pb.start() } catch (e: java.io.IOException) { if ((e.message ?: "").let { it.contains("error=8") || it.contains("Exec format", true) }) throw CloudModuleMissing(app.getString(R.string.cm_wrong_abi)) else throw e }
        val so = StringBuffer(); val se = StringBuffer()
        fun reader(s: java.io.InputStream, sb: StringBuffer) = Thread {
            runCatching { s.bufferedReader().forEachLine { l -> sb.append(l).append('\n'); onLine?.invoke(l) } }
        }.apply { isDaemon = true; start() }
        val t1 = reader(p.inputStream, so); val t2 = reader(p.errorStream, se)
        if (stdin != null) runCatching { p.outputStream.use { it.write(stdin.toByteArray()) } } else runCatching { p.outputStream.close() }
        val t0 = System.currentTimeMillis()
        try {
            while (p.isAlive) {
                if (System.currentTimeMillis() - t0 > timeoutMs) { p.destroyForcibly(); throw NetTimeout("облако > ${timeoutMs / 1000} с") }
                delay(40)
            }
        } finally { if (p.isAlive) p.destroyForcibly() }
        t1.join(500); t2.join(500)
        Result(p.exitValue(), so.toString(), se.toString())
    }

    /** Короткая человекочитаемая причина из журнала rclone. */
    fun reason(r: Result): String {
        val lines = (r.err + "\n" + r.out).lines().map { it.trim() }.filter { it.isNotEmpty() }
        val e = lines.lastOrNull { it.contains("ERROR", true) || it.contains("Failed", true) || it.contains("error", true) } ?: lines.lastOrNull() ?: "код ${r.code}"
        return e.replace(Regex("(?i)rclone"), "ADB-phon").replace(Regex("^\\d{4}/\\d\\d/\\d\\d \\d\\d:\\d\\d:\\d\\d\\s*"), "").replace(Regex("^(ERROR|NOTICE|CRITICAL)\\s*:\\s*"), "").take(300)
    }

    val common = listOf("--contimeout", "15s", "--timeout", "60s", "--retries", "1", "--low-level-retries", "2")

    fun remotes(ctx: Context): List<String> = runCatching {
        Regex("(?m)^\\[([^\\]]+)\\]").findAll(conf(ctx).readText()).map { it.groupValues[1] }.toList()
    }.getOrDefault(emptyList())

    fun deleteRemote(ctx: Context, name: String) { runCatching { RcloneConf.remove(ctx, name) } }
}

/** Запись настроек облаков прямо в rclone.conf (тот же формат, что пишет сам rclone): без интерактивных вопросов rclone (OneDrive и др.). */
object RcloneConf {
    private const val NL = "\n"
    private fun cut(text: String, name: String) = text.replace(Regex("(?ms)^[\\[]" + Regex.escape(name) + "[\\]][^\\n]*\\n.*?(?=^[\\[]|\\z)"), "")

    @Synchronized private fun save(ctx: Context, text: String) {
        val f = Rclone.conf(ctx); val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    @Synchronized fun put(ctx: Context, name: String, type: String, token: String?, extra: List<Pair<String, String>>) {
        val f = Rclone.conf(ctx)
        val old = if (f.exists()) f.readText() else ""
        val sec = buildString {
            append("[$name]").append(NL).append("type = $type").append(NL)
            if (token != null) append("token = ").append(token.replace(NL, "")).append(NL)
            extra.forEach { (k, v) -> append("$k = $v").append(NL) }
            append(NL)
        }
        save(ctx, (cut(old, name).trimEnd() + NL + NL + sec).trimStart())
    }

    @Synchronized fun remove(ctx: Context, name: String) {
        val f = Rclone.conf(ctx); if (!f.exists()) return
        save(ctx, cut(f.readText(), name).trim().let { if (it.isEmpty()) "" else it + NL })
    }
}

/**
 * Локальный прокси для rclone: CONNECT (HTTPS) и обычный HTTP через абсолютный URI. Слушает только 127.0.0.1, порт выбирает система.
 * Имена разрешает системный DNS Android; ничего не журналируется и не сохраняется.
 */
object CloudProxy {
    @Volatile private var server: ServerSocket? = null

    @Synchronized fun port(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                Thread { runCatching { handle(c) }.onFailure { runCatching { c.close() } } }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; name = "cloud-proxy"; start() }
        return s.localPort
    }

    private fun readHead(c: Socket): ByteArray? {
        val i = c.getInputStream(); var a = ByteArray(1024); var n = 0
        while (n < 65536) {
            val b = i.read(); if (b < 0) return null
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = b.toByte()
            if (n >= 4 && a[n - 4] == 13.toByte() && a[n - 3] == 10.toByte() && a[n - 2] == 13.toByte() && a[n - 1] == 10.toByte()) return a.copyOf(n)
        }
        return null
    }

    private fun pipe(a: Socket, b: Socket) {
        runCatching { val buf = ByteArray(32768); val i = a.getInputStream(); val o = b.getOutputStream(); while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); o.flush() } }
        runCatching { b.shutdownOutput() }
    }

    private fun handle(c: Socket) {
        c.soTimeout = 0
        val head = readHead(c) ?: run { c.close(); return }
        val text = String(head, Charsets.ISO_8859_1)
        val first = text.substringBefore("\r\n")
        val parts = first.split(' ')
        if (parts.size < 3) { c.close(); return }
        if (parts[0].equals("CONNECT", true)) {
            val hp = parts[1]; val host = hp.substringBeforeLast(':'); val port = hp.substringAfterLast(':').toIntOrNull() ?: 443
            val u = Socket()
            try { u.connect(java.net.InetSocketAddress(host, port), 15_000) } catch (e: Exception) { c.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()); c.close(); return }
            c.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); c.getOutputStream().flush()
            val t = Thread { pipe(u, c); runCatching { c.close() } }.apply { isDaemon = true; start() }
            pipe(c, u); t.join(2000); runCatching { u.close() }; runCatching { c.close() }
        } else {
            val uri = runCatching { java.net.URI(parts[1]) }.getOrNull()
            if (uri == null || uri.host == null) { c.close(); return }
            val port = if (uri.port > 0) uri.port else 80
            val u = Socket()
            try { u.connect(java.net.InetSocketAddress(uri.host, port), 15_000) } catch (e: Exception) { c.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()); c.close(); return }
            val path = (uri.rawPath.ifEmpty { "/" }) + (uri.rawQuery?.let { "?$it" } ?: "")
            val rest = text.substringAfter("\r\n").lines().filterNot { it.startsWith("Proxy-", true) || it.startsWith("Connection:", true) }.joinToString("\r\n")
            u.getOutputStream().write("${parts[0]} $path ${parts[2]}\r\n${rest.trimEnd()}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); u.getOutputStream().flush()
            val t = Thread { pipe(c, u) }.apply { isDaemon = true; start() }
            pipe(u, c); runCatching { u.close() }; runCatching { c.close() }; t.interrupt()
        }
    }
}

/** Облачный диск в панели «Файлы»: чтение папок и копирование в память через rclone. Пути вида «/папка/файл». */
class CloudFs(private val remote: String, private val display: String) : RemoteFs {
    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
        /** Один запрос к облаку за раз (общий замок для списков и подсчёта корзины). */
        fun lockOf(remote: String) = locks.getOrPut(remote) { kotlinx.coroutines.sync.Mutex() }
    }
    private val ctx get() = FmIo.app
    override val root = "/"
    override val label get() = display
    private fun spec(path: String) = "$remote:" + path.trimStart('/')

    override suspend fun list(path: String): List<FsEntry> {
        val type = CloudIcons.typeOfRemote(ctx, remote)
        if (CloudErr.fakeQuota) throw CloudErr.fromText(ctx, CloudErr.FAKE_BODY, type)
        val lock = locks.getOrPut(remote) { kotlinx.coroutines.sync.Mutex() }
        // один запрос к облаку за раз: MEGA при параллельных входах отвечает пустым ответом («unexpected end of JSON input»)
        var r: Rclone.Result = lock.withLock { Rclone.run(ctx, listOf("lsjson", spec(path), "--no-mimetype", "--no-modtime") + Rclone.common, 150_000) }
        // лимит общего ключа и временные сбои облака бывают кратковременными: до трёх повторов с паузой, затем понятная ошибка
        var tries = 0
        while (!r.ok && CloudErr.retryable(CloudErr.classify(r.err + r.out)) && tries < 3) { tries++; kotlinx.coroutines.delay(3_000L * tries); r = lock.withLock { Rclone.run(ctx, listOf("lsjson", spec(path), "--no-mimetype", "--no-modtime") + Rclone.common, 150_000) } }
        if (!r.ok) throw CloudErr.fromText(ctx, r.err + "\n" + r.out, type)
        val a = JSONArray(r.out.trim().ifEmpty { "[]" })
        val base = path.trimEnd('/')
        // корзина программы лежит в корне облака и в обычных списках не показывается
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { o -> !(base.isEmpty() && o.getString("Name") == CloudTrash.DIR) }.map { o ->
            val n = o.getString("Name"); val dir = o.optBoolean("IsDir")
            FsEntry(n, "$base/$n", dir, if (dir) 0 else o.optLong("Size", 0).coerceAtLeast(0))
        }.sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override suspend fun copyTo(path: String, dest: File) {
        dest.parentFile?.mkdirs()
        val r = Rclone.run(ctx, listOf("copyto", spec(path), dest.absolutePath) + Rclone.common, 60 * 60_000L)
        if (!r.ok) throw java.io.IOException(Rclone.reason(r))
    }

    override suspend fun head(path: String, n: Int): ByteArray {
        val f = File(ctx.cacheDir, "cloud_head_${System.nanoTime()}")
        try {
            val r = Rclone.run(ctx, listOf("copyto", spec(path), f.absolutePath, "--max-transfer", "${n}B", "--cutoff-mode", "soft") + Rclone.common, 120_000)
            if (!f.exists()) throw java.io.IOException(Rclone.reason(r))
            return f.inputStream().use { it.readNBytes(n) }
        } finally { f.delete() }
    }

    /** Загрузка из телефона в облако: файл — copyto, папка — copy её содержимого в папку назначения (создаётся). Существующие файлы перезаписываются. */
    suspend fun upload(src: File, destPath: String, isDir: Boolean) {
        val r = Rclone.run(ctx, listOf(if (isDir) "copy" else "copyto", src.absolutePath, spec(destPath)) + Rclone.common, 6 * 60 * 60_000L)
        if (!r.ok) throw java.io.IOException(Rclone.reason(r))
    }

    /**
     * Удаление: Google Диск без отметки «навсегда» уходит в родную корзину облака; остальные облака без отметки переносятся в корзину программы
     * (скрытая папка .adbphon-trash в облаке), не удалось перенести: ошибка и ничего не удалено. permanent (и всё, что уже внутри корзины программы): навсегда,
     * там где у облака есть такой ключ. Возвращает текст ошибки или null.
     */
    suspend fun deletePath(path: String, isDir: Boolean, permanent: Boolean, size: Long = -1L): String? {
        val type = CloudIcons.typeOfRemote(ctx, remote)
        val t = path.trim('/')
        val inTrash = t == CloudTrash.DIR || t.startsWith(CloudTrash.DIR + "/")
        val res = deleteInner(path, isDir, permanent, size, type, inTrash)
        if (res == null) CloudTrash.invalidate(remote) // число и список «Корзины» в дереве пересчитаются
        return res
    }

    private suspend fun deleteInner(path: String, isDir: Boolean, permanent: Boolean, size: Long, type: String?, inTrash: Boolean): String? {
        if (!permanent && !inTrash && CloudTrash.mode(type) == CloudTrash.OWN) return CloudTrash.moveIn(ctx, remote, path, isDir, size)
        val flag = if (permanent || inTrash) CloudTrash.hardFlag(type) else null
        val r = Rclone.run(ctx, listOf(if (isDir) "purge" else "deletefile", spec(path)) + listOfNotNull(flag) + Rclone.common, 60 * 60_000L)
        return if (r.ok) null else CloudErr.fromText(ctx, r.err + "\n" + r.out, CloudIcons.typeOfRemote(ctx, remote)).message
    }

    /** Проверка доступа при подключении: чтение корня. */
    suspend fun connect() { list("/") }
    override suspend fun ping(path: String) {}
    override fun parent(path: String): String? { val t = path.trimEnd('/'); return if (t.isEmpty()) null else t.substringBeforeLast('/', "").ifEmpty { "/" } }
    override suspend fun roots(): List<Pair<String, String>> = listOf(display to "/")
}

/**
 * Собственные идентификаторы приложения (client_id и client_secret) для входа в облака: на странице согласия облако показывает имя приложения,
 * зарегистрированного под этим client_id. Пока таблица пуста, используются общие идентификаторы движка. Регистрирует владелица (см. ТОДО\облака_регистрация.md).
 */
object CloudClients {
    private val BUILTIN: Map<String, Pair<String, String>> = mapOf(
        // Собственный ключ ADB-phon (проект Google Cloud «adb-phon», тип «Приложение для ПК»): общий ключ движка упирается в лимит запросов
        // "drive": общий ключ программы в публичный исходный код не входит; соберите со своим ключом (мастер «Свой ключ») или подставьте свой client_id и client_secret.
        // "dropbox" to ..., "onedrive" to ..., "yandex" to ..., "box" to ...
    )
    /** Свой ключ пользователя (расширенно) важнее общего ключа движка. */
    fun of(type: String): Pair<String, String>? = runCatching { OwnKeys.get(FmIo.app, type) }.getOrNull() ?: BUILTIN[type]
}

/** Добавление облака: вход в браузере (rclone authorize), затем запись настройки и имя «почта (облако)». Состояние живёт в объекте, окно может закрываться и открываться. */
object CloudAuth {
    class Provider(val type: String, val titleRes: Int, val extra: List<String> = emptyList(), val title: String? = null)
    val providers = listOf(
        Provider("drive", R.string.cl_drive, listOf("scope", "drive")),
        Provider("onedrive", R.string.cl_onedrive),
        Provider("dropbox", R.string.cl_dropbox),
        Provider("yandex", R.string.cl_yandex),
        Provider("box", R.string.cl_box),
    )

    const val IDLE = 0; const val WAIT = 1; const val SAVING = 2; const val DONE = 3; const val FAIL = 4
    var phase by mutableIntStateOf(IDLE)
    var url by mutableStateOf("")
    var message by mutableStateOf("")
    var added by mutableStateOf<NetConn?>(null)
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var gen = 0
    private var appCtx: Context? = null

    /** Отмена или сброс: процесс входа убивается, служба и блокировка сна снимаются. Страница «Готово» (LoginEcho) живёт сама до своего срока. */
    fun reset() {
        gen++
        job?.cancel(); job = null; phase = IDLE; url = ""; message = ""; added = null
        appCtx?.let { CloudLoginService.stop(it) }
    }

    /**
     * Страница входа открывается Chrome явным интентом (минуя системный выбор приложений для ссылок: на A14 он стоит у LinkSheet, а страницы
     * accounts.google.com могли уходить приложениям Google и почте). Chrome нет: браузер по умолчанию через CATEGORY_APP_BROWSER, тоже без выбора приложений.
     */
    fun openBrowser(ctx: Context) { if (url.isNotBlank()) openUrl(ctx, url) }

    /** Любая ссылка: Chrome явным интентом, иначе браузер по умолчанию. */
    fun openUrl(ctx: Context, link: String) {
        val uri = Uri.parse(link)
        if (chromeInstalled(ctx) && runCatching {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).setPackage("com.android.chrome").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess) return
        val ok = runCatching {
            ctx.startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER).setData(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!ok) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun chromeInstalled(ctx: Context): Boolean = runCatching { ctx.packageManager.getPackageInfo("com.android.chrome", 0); true }.getOrDefault(false)

    /** Запасной путь: выбор браузера, из которого исключены почтовые приложения (всё, что умеет mailto:). */
    fun openChooser(ctx: Context) {
        if (url.isBlank()) return
        val pm = ctx.packageManager
        val mail = runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), 0).map { android.content.ComponentName(it.activityInfo.packageName, it.activityInfo.name) } }.getOrDefault(emptyList())
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        val ch = Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (mail.isNotEmpty()) ch.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, mail.toTypedArray())
        runCatching { ctx.startActivity(ch) }
    }

    fun copyLink(ctx: Context) {
        if (url.isBlank()) return
        runCatching { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("url", url)) }
        android.widget.Toast.makeText(ctx, ctx.getString(R.string.cl_link_copied), android.widget.Toast.LENGTH_SHORT).show()
    }

    fun start(ctx0: Context, pr: Provider) {
        val ctx = ctx0.applicationContext
        appCtx = ctx
        job?.cancel()
        val my = ++gen
        phase = WAIT; url = ""; message = ""; added = null
        LoginEcho.stop()
        // служба переднего плана и блокировка сна: вход идёт минуты (пароль, 2FA), пока приложение свёрнуто и браузер на экране
        CloudLoginService.start(ctx)
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { LoginEcho.freePort() }
                var token: String? = null
                val r = Rclone.run(ctx, listOf("authorize", pr.type) + (CloudClients.of(pr.type)?.let { listOf(it.first, it.second) } ?: emptyList()) + "--auth-no-open-browser", 20 * 60_000L) { l ->
                    val m = Regex("https?://127\\.0\\.0\\.1:\\d+/auth\\?state=\\S+").find(l)
                    if (m != null && url.isEmpty()) { url = m.value; scope.launch { openBrowser(ctx) } }
                }
                val all = r.out + "\n" + r.err
                val m = Regex("--->\\s*(\\{.*?\\})\\s*<---End paste", RegexOption.DOT_MATCHES_ALL).find(all)
                token = m?.groupValues?.get(1)?.replace(Regex("\\s*\\n\\s*"), "")
                // движок закрыл порт: сразу отвечаем сами, чтобы повторный запрос браузера (favicon, обновление, «назад») не получал отказ
                if (r.ok && token != null) LoginEcho.start(ctx, true)
                if (!r.ok || token == null) { phase = FAIL; message = Rclone.reason(r); return@launch }
                phase = SAVING
                val extras = pr.extra.chunked(2).map { it[0] to it[1] } + (CloudClients.of(pr.type)?.let { listOf("client_id" to it.first, "client_secret" to it.second) } ?: emptyList())
                var email: String? = null
                var cfg = extras
                if (pr.type == "onedrive") {
                    val g = oneDriveInfo(token) ?: run { phase = FAIL; message = ctx.getString(R.string.cl_onedrive_fail); return@launch }
                    email = g.third; cfg = extras + listOf("drive_id" to g.first, "drive_type" to g.second)
                } else {
                    val tmp = "tmp_" + System.nanoTime().toString(36)
                    RcloneConf.put(ctx, tmp, pr.type, token, extras)
                    email = userEmail(ctx, tmp)
                    RcloneConf.remove(ctx, tmp)
                    // Google Диск: если движок не отдал почту (лимит запросов, сбой), берём её прямо у Google по только что полученному токену
                    if (email == null && pr.type == "drive") email = googleDriveEmail(token)
                }
                val name = "acc_" + sha1(pr.type + ":" + (email ?: token.take(80))).take(10)
                RcloneConf.put(ctx, name, pr.type, token, cfg)
                val title = (email ?: ctx.getString(R.string.cl_account)) + " (" + (pr.title ?: ctx.getString(pr.titleRes)) + ")"
                val conn = NetConn("cloud", name, 0, title, "")
                NetStore.save(ctx, conn)
                added = conn; phase = DONE
                // автоматический возврат в приложение (если система позволяет); иначе уведомление и ссылка на странице «Готово»
                CloudReturn.toFront(ctx)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { phase = FAIL; message = e.message ?: e.toString() }
            finally {
                if (gen == my) {
                    // вход закончен (успех или ошибка): служба не нужна; если браузер ещё стучится на 127.0.0.1, он увидит понятную страницу, а не «сайт недоступен»
                    CloudLoginService.stop(ctx)
                    if (phase == FAIL) LoginEcho.start(ctx, false)
                }
            }
        }
    }

    private suspend fun userEmail(ctx: Context, remote: String): String? {
        val r = runCatching { Rclone.run(ctx, listOf("config", "userinfo", "$remote:", "--json") + Rclone.common, 60_000) }.getOrNull() ?: return null
        if (!r.ok) return null
        val o = runCatching { JSONObject(r.out.trim()) }.getOrNull() ?: return null
        for (k in listOf("email", "mail", "userPrincipalName", "login", "display_name", "name", "displayName")) {
            val key = o.keys().asSequence().firstOrNull { it.equals(k, true) } ?: continue
            val v = o.optString(key).trim(); if (v.isNotEmpty() && v != "null") return v
        }
        return null
    }

    /** Почта владельца Google Диска напрямую: запрос «о диске» с токеном входа (нужна для названия «почта (Google Диск)», как у остальных облаков). */
    private suspend fun googleDriveEmail(token: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val at = JSONObject(token).getString("access_token")
            val c = java.net.URL("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 20_000; c.setRequestProperty("Authorization", "Bearer $at")
            try {
                if (c.responseCode !in 200..299) return@runCatching null
                JSONObject(c.inputStream.bufferedReader().readText()).optJSONObject("user")?.optString("emailAddress")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            } finally { c.disconnect() }
        }.getOrNull()
    }

    /** OneDrive: идентификатор диска, тип (personal/business) и почта через Microsoft Graph с только что полученным токеном. */
    private suspend fun oneDriveInfo(token: String): Triple<String, String, String?>? = withContext(Dispatchers.IO) {
        runCatching {
            val at = JSONObject(token).getString("access_token")
            fun get(u: String): JSONObject {
                val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 20_000; c.setRequestProperty("Authorization", "Bearer $at")
                try { if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}"); return JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
            }
            val d = get("https://graph.microsoft.com/v1.0/me/drive?\$select=id,driveType")
            val me = runCatching { get("https://graph.microsoft.com/v1.0/me?\$select=mail,userPrincipalName") }.getOrNull()
            val mail = me?.optString("mail")?.takeIf { it.isNotBlank() && it != "null" } ?: me?.optString("userPrincipalName")?.takeIf { it.isNotBlank() && it != "null" }
            Triple(d.getString("id"), d.optString("driveType", "personal").ifBlank { "personal" }, mail)
        }.getOrNull()
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * MEGA: входа через браузер нет, нужны почта и пароль. Пароль сразу превращается в обфусцированный вид движка (obscure, через стандартный ввод:
 * в списке процессов и в журналах его нет) и пишется в настройки облаков (в резервную копию они попадают только зашифрованными парольной фразой).
 * Проверка: вход и список корня. Двухфакторную защиту MEGA постоянное подключение не поддерживает (код одноразовый): честная подсказка.
 */
object MegaAuth {
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")
    var added by mutableStateOf<NetConn?>(null)

    fun reset() { busy = false; error = ""; added = null }

    private fun nameFor(email: String) = "acc_" + sha1("mega:" + email.trim().lowercase()).take(10)

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Короткое понятное сообщение по ответу движка (текст пароля в нём не бывает). */
    fun explain(ctx: Context, text: String): String {
        val t = text.lowercase()
        return when {
            "multi-factor" in t || "2fa" in t || "two-factor" in t || "mfa" in t || "-26" in t -> ctx.getString(R.string.cl_mega_err_2fa)
            "not found" in t || "wrong" in t || "credential" in t || "-9)" in t || "enoent" in t || "incorrect" in t || "invalid" in t || "bad login" in t || "-2)" in t -> ctx.getString(R.string.cl_mega_err_bad)
            "rate" in t || "limit" in t || "try again" in t || "-3)" in t || "-4)" in t || "-6)" in t || "-17)" in t || "quota" in t || "blocked" in t || "too many" in t -> ctx.getString(R.string.cl_mega_err_limit)
            "lookup" in t || "dial" in t || "timeout" in t || "timed out" in t || "no such host" in t || "connection" in t || "network" in t -> ctx.getString(R.string.cl_mega_err_net)
            else -> ctx.getString(R.string.cl_mega_err_other, text.lines().lastOrNull { it.isNotBlank() }?.take(160).orEmpty())
        }
    }

    suspend fun add(ctx0: Context, email0: String, pass: String): NetConn? {
        val ctx = ctx0.applicationContext
        val email = email0.trim()
        CloudTestHook.fakeAdd?.let { busy = true; kotlinx.coroutines.delay(700); busy = false; return it }
        if (email.isEmpty() || pass.isEmpty()) { error = ctx.getString(R.string.cl_mega_err_empty); return null }
        busy = true; error = ""
        val name = nameFor(email)
        val hadBefore = Rclone.remotes(ctx).contains(name)
        try {
            val ob = Rclone.run(ctx, listOf("obscure", "-"), 30_000, stdin = pass)
            val obscured = ob.out.trim().lines().lastOrNull().orEmpty()
            if (!ob.ok || obscured.isEmpty()) { error = ctx.getString(R.string.cl_mega_err_other, "obscure"); return null }
            // проверка под временным именем: неудачная попытка не затрёт уже работающую запись
            val tmp = "tmp_" + System.nanoTime().toString(36)
            RcloneConf.put(ctx, tmp, "mega", null, listOf("user" to email, "pass" to obscured))
            val r = try { Rclone.run(ctx, listOf("lsd", "$tmp:") + Rclone.common, 90_000) } finally { RcloneConf.remove(ctx, tmp) }
            if (!r.ok) { error = explain(ctx, Rclone.reason(r)); return null }
            RcloneConf.put(ctx, name, "mega", null, listOf("user" to email, "pass" to obscured))
            val conn = NetConn("cloud", name, 0, email + " (MEGA)", "")
            NetStore.save(ctx, conn)
            added = conn
            return conn
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = explain(ctx, e.message ?: e.toString()); return null }
        finally { busy = false }
    }
}
