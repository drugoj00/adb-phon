package app.adbphon

import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runInterruptible
import android.util.Log
import java.io.InputStream

/** Единая точка работы с ADB этого же телефона. */
object Adb {
    private lateinit var mgr: AdbConn

    private lateinit var appCtx: Context

    fun init(context: Context) {
        appCtx = context.applicationContext
        mgr = AdbConn(context.applicationContext)
        autoOff = appCtx.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getBoolean("auto_off", false)   // отключение помнится и после перезапуска
        AdbServer.init(appCtx)
        srvScope.launch { AdbServer.ping() }   // сервер мог остаться с прошлого запуска (7.0)
    }

    private val srvScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Команды этого телефона идут через root (Magisk), а не через ADB: компьютер и отладка по Wi-Fi не нужны. */
    @Volatile private var rootOn = false
    /** Пользователь сам отключился: сами к root не возвращаемся, пока он не нажмёт «Подключить через root». */
    @Volatile var rootSuppressed = false
    /** Пользователь сам отключил устройство: фоновое автоподключение молчит, пока он не подключит что-либо вручную. */
    /** Адрес устройства, к которому подключены сейчас ("" — этот телефон). Нужен, чтобы фоновое обновление не принимало чужое устройство за этот телефон. */
    @Volatile var currentHost = ""
    @Volatile var autoOff = false
        set(v) {
            field = v
            if (::appCtx.isInitialized) appCtx.getSharedPreferences("adbphon", Context.MODE_PRIVATE).edit().putBoolean("auto_off", v).apply()
        }
    val isRoot: Boolean get() = rootOn

    /** На устройстве есть программа su (root). Проверка файлов без запуска su: на телефоне без root переключатель root делаем неактивным. */
    val suPresent: Boolean by lazy {
        val dirs = (System.getenv("PATH") ?: "").split(':').filter { it.isNotBlank() } +
            listOf("/system/bin", "/system/xbin", "/sbin", "/su/bin", "/system/sbin", "/vendor/bin", "/product/bin", "/odm/bin", "/apex/com.android.runtime/bin", "/debug_ramdisk")
        // файлы su могут быть скрыты, а root есть: root уже работал раньше, или su просто запускается (ошибка запуска = su нет)
        dirs.any { runCatching { java.io.File(it, "su").exists() }.getOrDefault(false) } ||
            (::appCtx.isInitialized && appCtx.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getBoolean("root_ok", false)) ||
            runCatching { ProcessBuilder("su", "-c", "true").redirectErrorStream(true).start().also { it.destroy() }; true }.getOrDefault(false)
    }

    /** Идёт подключение или отключение: библиотека держит блокировку, и опрос состояния из интерфейса вешал его на десятки секунд (приставка, 08.11.2026). */
    @Volatile private var busyConn = 0
    @Volatile private var lastConn = false
    val isConnected: Boolean get() = rootOn || (::mgr.isInitialized && (if (busyConn > 0) lastConn else mgr.isConnected.also { lastConn = it })) || (currentHost.isEmpty() && AdbServer.up)

    /** Команды этого телефона идут через «ADB-phon Server»: ни root, ни ADB нет, зато сервер жив (7.0). */
    private fun useServer(): Boolean = !rootOn && currentHost.isEmpty() && AdbServer.up && !(::mgr.isInitialized && (if (busyConn > 0) lastConn else mgr.isConnected))
    private inline fun <T> guarded(block: () -> T): T { busyConn++; try { return block() } finally { busyConn-- } }

    /**
     * Проверяет, дал ли Magisk (или другой su) права root этому приложению. При первом запуске появится запрос Magisk:
     * ждём ответа до 30 секунд. Успех включает режим root для всех команд на этом телефоне.
     */
    suspend fun tryRoot(manual: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (manual) rootSuppressed = false
        if (rootSuppressed) return@withContext false
        val ok = runCatching { rootRun("id", null, 30_000).contains("uid=0") }
            .onFailure { AppLog.i("ADBphon", "root недоступен: ${it.message}") }.getOrDefault(false)
        if (ok) { runCatching { guarded { mgr.disconnect() } }; lastConn = false; rootOn = true; currentHost = ""; if (::appCtx.isInitialized) appCtx.getSharedPreferences("adbphon", Context.MODE_PRIVATE).edit().putBoolean("root_ok", true).apply() }
        AppLog.i("ADBphon", "root=$ok")
        ok
    }

    fun disableRoot() { rootOn = false }

    /**
     * Magisk: `su -mm` запускает команду в общем (главном) пространстве файловой системы. Без него на Android 11 и новее
     * приложение видит только свою папку в /data/data (изоляция данных приложений), и полные копии других приложений пустые.
     * null: ещё не проверяли; false: su не знает этот ключ (другой su), используется обычный `su -c`.
     */
    @Volatile private var suMountMaster: Boolean? = null

    private fun suArgs(cmd: String): List<String> {
        var mm = suMountMaster
        if (mm == null) {
            mm = runCatching {
                val pr = ProcessBuilder("su", "-mm", "-c", "id").redirectErrorStream(true).start()
                val txt = pr.inputStream.bufferedReader().readText()
                pr.waitFor()
                txt.contains("uid=0")
            }.getOrDefault(false)
            suMountMaster = mm
            AppLog.i("ADBphon", "su -mm: $mm")
        }
        return if (mm) listOf("su", "-mm", "-c", cmd) else listOf("su", "-c", cmd)
    }

    private fun q(p: String) = "'" + p.replace("'", "'\\''") + "'"

    /**
     * Выполняет команду от root: `su -c`. [input] (если есть) подаётся в стандартный ввод команды (установка пакета, запись файла).
     * Возвращает объединённый вывод; по тайм-ауту процесс убивается.
     */
    private fun rootRun(cmd: String, input: InputStream?, timeoutMs: Long): String {
        val p = ProcessBuilder(suArgs(cmd)).redirectErrorStream(true).start()
        val out = java.io.ByteArrayOutputStream()
        val reader = Thread { runCatching { p.inputStream.copyTo(out) } }
        reader.start()
        val writer = Thread { runCatching { p.outputStream.use { o -> input?.copyTo(o) } } }
        writer.start()
        val t0 = System.currentTimeMillis()
        var finished = false
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (runCatching { p.exitValue() }.isSuccess) { finished = true; break }
            Thread.sleep(50)
        }
        if (!finished) { AppLog.w("ADBphon", "timeout root: ${cmd.take(100)}"); p.destroy() }
        reader.join(2000)
        writer.join(500)
        return synchronized(out) { out.toString("UTF-8") }.trim()
    }

    /** Подключение к ЧУЖОМУ устройству отменяет режим root: команды должны идти туда, а не на этот телефон. */
    private fun leaveRoot() { rootOn = false }

    suspend fun connectLocal(port: Int, host: String = "127.0.0.1"): Result<Unit> = withContext(Dispatchers.IO) {
        leaveRoot()   // выбран ADB: команды идут по ADB, а не через root
        runCatching {
            // Первое подключение к новому устройству: пока человек нажимает «Разрешить», попытка обрывается; вторая проходит сразу
            var last: Throwable? = null
            var done = false
            for (attempt in 1..2) {
                lastConn = false
                runCatching { guarded { mgr.disconnect() } }
                AppLog.i("ADBphon", "connect $host:$port (попытка $attempt)")
                val r = runCatching { withTimeout(25_000) { runInterruptible { guarded { mgr.connect(host, port) } } } }.also { lastConn = it.getOrNull() == true }
                AppLog.i("ADBphon", "connect result=${r.getOrNull()}")
                if (r.getOrNull() == true) { done = true; break }
                last = r.exceptionOrNull() ?: IllegalStateException("connect failed")
            }
            if (!done) throw last ?: IllegalStateException("connect failed")
            currentHost = if (host == "127.0.0.1") "" else host
            if (currentHost.isEmpty() && AdbServer.auto && !rootOn) srvScope.launch { kotlinx.coroutines.delay(4000); if (!rootOn) serverStart(appCtx) }   // пауза: на устройстве с root режим root включается чуть позже и помощник не нужен
        }.onFailure { AppLog.e("ADBphon", "connect error", it) }
    }

    suspend fun pair(port: Int, code: String, host: String = "127.0.0.1"): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { mgr.pair(host, port, code) }.map { }.onFailure { AppLog.e("ADBphon", "pair error", it) }
    }

    /** Беспроводная отладка: порт ищется сам (mDNS), при неудаче используется порт, введённый вручную. */
    suspend fun connectWireless(context: Context, manualPort: Int?, host: String = "127.0.0.1"): Result<Unit> = withContext(Dispatchers.IO) {
        leaveRoot()   // выбран ADB: команды идут по ADB, а не через root
        runCatching {
            lastConn = false
            runCatching { guarded { mgr.disconnect() } }
            var ok = false
            if (host == "127.0.0.1") {
                ok = runCatching { guarded { mgr.autoConnect(context.applicationContext, 7000) } }
                    .onFailure { AppLog.e("ADBphon", "autoConnect error", it) }.getOrDefault(false)
            }
            if (!ok && manualPort != null) ok = guarded { mgr.connect(host, manualPort) }
            lastConn = ok
            if (!ok) error("connect failed")
            currentHost = if (host == "127.0.0.1") "" else host
            if (currentHost.isEmpty() && AdbServer.auto && !rootOn) srvScope.launch { kotlinx.coroutines.delay(4000); if (!rootOn) serverStart(context.applicationContext) }
        }.onFailure { AppLog.e("ADBphon", "wireless connect error", it) }
    }

    /**
     * Запускает «ADB-phon Server» на этом телефоне через ADB (нужна живая связь ADB). После этого приложение работает и без отладки по Wi-Fi.
     * Если сервер уже отвечает, ничего не делает.
     */
    private val srvMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun serverStart(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        srvMutex.withLock { runCatching {
            if (AdbServer.ping() || rootOn) return@runCatching   // с root помощник не нужен: команды и так идут от root
            if (rootOn || currentHost.isNotEmpty() || !(::mgr.isInitialized && mgr.isConnected)) error("нужна связь ADB с этим телефоном")
            val bytes = context.assets.open("server.jar").use { it.readBytes() }
            runCatching { shell("pkill -f 'app_process / Server' 2>/dev/null; true") }   // сервер со старым токеном (данные приложения очищались)
            val up = mgr.openStream("exec:cat > /data/local/tmp/adbphon-server.jar")
            up.openOutputStream().apply { write(bytes); flush() }
            runCatching { up.close() }
            kotlinx.coroutines.delay(500)
            // Android 9 (приставки): служба «exec:» может не создать файл. Проверяем размер и при расхождении кладём файл кусками через base64 в оболочке
            val have = runCatching { shell("wc -c < /data/local/tmp/adbphon-server.jar 2>/dev/null", 8_000L, fast = true).trim().toLongOrNull() }.getOrNull()
            if (have != bytes.size.toLong()) {
                AppLog.w("ADBphon", "сервер: файл через exec не лёг (${have ?: -1} из ${bytes.size}), кладу кусками")
                shell("rm -f /data/local/tmp/adbphon-server.jar; true")
                bytes.toList().chunked(1200).forEach { part ->
                    val b64 = android.util.Base64.encodeToString(part.toByteArray(), android.util.Base64.NO_WRAP)
                    shell("echo '" + b64 + "' | base64 -d >> /data/local/tmp/adbphon-server.jar")
                }
                val after = runCatching { shell("wc -c < /data/local/tmp/adbphon-server.jar 2>/dev/null", 8_000L, fast = true).trim().toLongOrNull() }.getOrNull()
                if (after != bytes.size.toLong()) error("не удалось записать файл помощника (${after ?: -1} из ${bytes.size})")
            }
            shell("CLASSPATH=/data/local/tmp/adbphon-server.jar nohup setsid app_process / Server " + AdbServer.PORT + " " + AdbServer.token() + " >/dev/null 2>&1 </dev/null & sleep 2", 12000)   // пауза: пока shell жив, дочерний процесс успевает отделиться (setsid), иначе его убьёт закрытие потока ADB
            repeat(24) { if (AdbServer.ping()) { AppLog.i("ADBphon", "сервер запущен"); return@runCatching }; kotlinx.coroutines.delay(250) }
            error("сервер не ответил")
        }.recoverCatching { if (rootOn) Unit else throw it }.onFailure { AppLog.e("ADBphon", "serverStart", it) } }
    }

    // ---- живой сеанс оболочки для «Консоли» (7.0) ----
    /** Настоящая связь ADB (libadb) с этим телефоном или с другим устройством по сети; помощник и root не считаются. */
    val hasLink: Boolean get() = ::mgr.isInitialized && (if (busyConn > 0) lastConn else mgr.isConnected)

    class ShellSession(private val s: io.github.muntashirakon.adb.AdbStream) {
        private val out = s.openOutputStream()
        fun write(b: ByteArray) { out.write(b); out.flush() }
        fun close() { runCatching { s.close() } }
    }

    /** Открывает интерактивную оболочку («shell:» без команды: с терминалом, работают Ctrl+C и Tab). Вывод приходит кусками в UTF-8. */
    fun openShell(onData: (String) -> Unit, onEnd: () -> Unit): ShellSession? {
        if (!hasLink) return null
        val st = runCatching { mgr.openStream("shell:") }.getOrNull() ?: return null
        val ses = ShellSession(st)
        Thread {
            try {
                val r = java.io.InputStreamReader(st.openInputStream(), Charsets.UTF_8); val cb = CharArray(2048)
                while (true) { val n = r.read(cb); if (n < 0) break; onData(String(cb, 0, n)) }
            } catch (_: Throwable) { }
            onEnd()
        }.apply { isDaemon = true }.start()
        return ses
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) { if (rootOn) rootSuppressed = true; rootOn = false; currentHost = ""; lastConn = false; runCatching { guarded { mgr.disconnect() } } }

    /** Выполнить команду оболочки и вернуть её вывод. */
    private const val END_MARK = "@@ADBPHON_END@@"

    /**
     * [fast]: в конец команды добавляется метка, и ответ считается полученным, как только метка пришла (не ждём закрытия потока:
     * библиотека ADB иногда держит его до тайм-аута, и пакетные действия тогда шли по 30 секунд на приложение).
     */
    suspend fun shell(cmd: String, timeoutMs: Long = 25_000, fast: Boolean = false): String = withContext(Dispatchers.IO) {
        AppLog.i("ADBphon", "shell> ${cmd.take(300)}")
        val t0 = System.currentTimeMillis()
        val res = runCatching { if (rootOn) rootRun(cmd, null, timeoutMs) else if (useServer()) AdbServer.run(cmd, null, 0, timeoutMs) else if (fast) readAll(mgr.openStream(AdbText.safe("shell:$cmd; echo $END_MARK")), timeoutMs, cmd, END_MARK) else readAll(mgr.openStream(AdbText.safe("shell:$cmd")), timeoutMs, cmd) }
        AppLog.i("ADBphon", "shell< ${System.currentTimeMillis() - t0} мс, ${res.getOrNull()?.length ?: -1} симв: ${(res.getOrNull() ?: res.exceptionOrNull()?.message).orEmpty().take(300).replace("\n", " ⏎ ")}")
        res.getOrThrow()
    }

    /**
     * Читает поток до конца с ограничением по времени. Библиотека иногда бросает «Stream closed» вместо конца потока
     * или не закрывает поток: прочитанное считаем ответом, зависший поток закрываем сами.
     */
    private fun readAll(s: io.github.muntashirakon.adb.AdbStream, timeoutMs: Long, what: String, mark: String? = null): String {
        val out = java.io.ByteArrayOutputStream()
        var error: Throwable? = null
        val reader = Thread {
            try {
                val input = s.openInputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    synchronized(out) { out.write(buf, 0, n) }
                    if (mark != null && synchronized(out) { out.toString("UTF-8") }.trimEnd().endsWith(mark)) break
                }
            } catch (e: Throwable) {
                error = e
            }
        }
        reader.start()
        reader.join(timeoutMs)
        if (reader.isAlive) {
            AppLog.w("ADBphon", "timeout reading: $what")
            runCatching { s.close() }
            reader.join(2000)
        } else {
            runCatching { s.close() }
        }
        val text = synchronized(out) { out.toString("UTF-8") }.let { if (mark != null) it.trimEnd().removeSuffix(mark) else it }.trim()
        val e = error
        if (e != null && text.isEmpty() && e.message?.contains("closed", ignoreCase = true) != true) throw e
        return text
    }

    /**
     * Ставим только текущему пользователю: без этого Samsung кладёт копию ещё и в «Двойное приложение» (DUAL_APP),
     * и значки в меню телефона задваиваются.
     */
    private suspend fun withUser(flags: String): String {
        if (flags.contains("--user")) return flags
        val u = runCatching { shell("am get-current-user").trim().toInt() }.getOrDefault(0)
        return "$flags --user $u"
    }

    /** Для полосы установки: сколько байт APK уже передано в текущем вызове установки (null — не считается). */
    @Volatile var onInstallBytes: ((Long) -> Unit)? = null

    private class CountIn(val src: InputStream, val acc: java.util.concurrent.atomic.AtomicLong, val cb: (Long) -> Unit) : InputStream() {
        override fun read(): Int { val r = src.read(); if (r >= 0) cb(acc.incrementAndGet()); return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int { val n = src.read(b, off, len); if (n > 0) cb(acc.addAndGet(n.toLong())); return n }
        override fun close() = src.close()
        override fun available() = src.available()
    }

    private fun counted(i: InputStream, acc: java.util.concurrent.atomic.AtomicLong): InputStream {
        val cb = onInstallBytes ?: return i
        return CountIn(i, acc, cb)
    }

    /** Установить APK потоком (cmd package install -S). flags: например "-r" или "-r -d" (понижение версии). */
    suspend fun installApk(input0: InputStream, size: Long, flags: String = "-r"): String = withContext(Dispatchers.IO) {
        val input = counted(input0, java.util.concurrent.atomic.AtomicLong())
        if (rootOn) {
            val tmp = java.io.File(appCtx.cacheDir, "root-install-" + System.nanoTime() + ".apk")
            try {
                tmp.outputStream().use { input.copyTo(it) }
                return@withContext rootRun("cmd package install ${withUser(flags)} -S ${tmp.length()} < " + q(tmp.absolutePath), null, 600_000)
            } finally { tmp.delete() }
        }
        if (useServer()) return@withContext AdbServer.run("cmd package install ${withUser(flags)} -S $size", input, size, 120_000)
        val s = mgr.openStream(AdbText.safe("exec:cmd package install ${withUser(flags)} -S $size"))
        val out = s.openOutputStream()
        input.copyTo(out)
        out.flush()
        readAll(s, 120_000, "install")
    }

    /** Ничего не ждёт: в ADB-phon менеджер создаётся сразу (нужно перенесённым модулям). */
    suspend fun awaitReady() {}

    /** Копирует файл с устройства через оболочку ADB (так читаются флешки, которые система приложению не показывает). */
    suspend fun pull(path: String, dest: java.io.File) = withContext(Dispatchers.IO) {
        if (rootOn) {
            val p = ProcessBuilder(suArgs("cat " + q(path))).start()
            runCatching { p.outputStream.close() }
            Thread { runCatching { p.errorStream.copyTo(java.io.ByteArrayOutputStream()) } }.start()
            p.inputStream.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            p.waitFor()
            return@withContext
        }
        if (useServer()) { AdbServer.pullTo("cat " + q(path), dest); return@withContext }
        val s = mgr.openStream(AdbText.safe("exec:cat '" + path.replace("'", "'\\''") + "'"))
        try {
            s.openInputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        } finally { runCatching { s.close() } }
    }

    /** Записывает [size] байт из потока в файл на устройстве от имени shell (туда, куда обычному приложению писать нельзя, например Android/obb). */
    suspend fun pushStream(input: InputStream, size: Long, dest: String) = withContext(Dispatchers.IO) {
        shell("mkdir -p " + q(dest.substringBeforeLast('/')))
        if (rootOn) {
            rootRun("head -c $size > " + q(dest), input, 1_800_000)
            val m = Regex("^(.*/Android/(?:obb|data))/([^/]+)/").find(dest)
            if (m != null) runCatching { rootRun("chown -R \$(stat -c %u:%g " + q(m.groupValues[1]) + ") " + q(m.groupValues[1] + "/" + m.groupValues[2]), null, 60_000) }
            return@withContext Unit
        }
        if (useServer()) { AdbServer.run("head -c $size > " + q(dest), input, size, 1_800_000); return@withContext Unit }
        val s = mgr.openStream(AdbText.safe("exec:head -c $size > " + q(dest)))
        val out = s.openOutputStream()
        input.copyTo(out)
        out.flush()
        readAll(s, 600_000, "push")
        Unit
    }

    /** Установка одного APK или набора частей одного приложения (сессия установки). flags: например "-r" или "-r -d". */
    suspend fun installFiles(files: List<java.io.File>, flags: String = "-r"): String {
        if (files.isEmpty()) return "no files"
        if (files.size == 1) return files[0].inputStream().use { installApk(it, files[0].length(), flags) }
        val made = shell("cmd package install-create ${withUser(flags)} -S ${files.sumOf { it.length() }}")
        val id = Regex("\\[(\\d+)\\]").find(made)?.groupValues?.get(1) ?: return made
        val acc = java.util.concurrent.atomic.AtomicLong()
        for (f in files) {
            val res = withContext(Dispatchers.IO) {
                if (rootOn) return@withContext f.inputStream().use { rootRun("cmd package install-write -S ${f.length()} $id ${q(f.name)} - < " + q(f.absolutePath), null, 600_000) }.also { onInstallBytes?.invoke(acc.addAndGet(f.length())) }
                val s = mgr.openStream(AdbText.safe("exec:cmd package install-write -S ${f.length()} $id ${f.name} -"))
                val out = s.openOutputStream()
                f.inputStream().use { counted(it, acc).copyTo(out) }
                out.flush()
                readAll(s, 120_000, "install-write")
            }
            if (!res.contains("Success", true)) { runCatching { shell("cmd package install-abandon $id") }; return res }
        }
        return shell("cmd package install-commit $id", 120_000)
    }

    // ---------- помощник: виртуальная мышь на целевом устройстве (UHID) ----------

    private var helperStream: io.github.muntashirakon.adb.AdbStream? = null
    private var helperOut: java.io.OutputStream? = null
    private val helperExec = java.util.concurrent.Executors.newSingleThreadExecutor()

    val helperRunning: Boolean get() = helperOut != null

    /** Что умеет запущенный помощник на этом устройстве: виртуальная мышь (UHID) и настоящее касание (InputManager). */
    @Volatile var helperMouse = false
    @Volatile var helperTouch = false
    /** Помощник умеет быстро нажимать клавиши (команда «k код»): миллисекунды вместо ~1 с на «input keyevent». */
    @Volatile var helperKeys = false

    suspend fun helperStart(context: Context, keysOnly: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            helperStopNow()
            val bytes = context.assets.open("helper.jar").use { it.readBytes() }
            if (rootOn) { helperStartRoot(bytes, keysOnly); return@runCatching }
            val up = mgr.openStream("exec:cat > /data/local/tmp/adbphon-helper.jar")
            up.openOutputStream().apply { write(bytes); flush() }
            runCatching { up.close() }
            kotlinx.coroutines.delay(500)
            val s = mgr.openStream("exec:CLASSPATH=/data/local/tmp/adbphon-helper.jar app_process / Helper${if (keysOnly) " keys" else ""}")
            helperStream = s
            helperOut = s.openOutputStream()
            // помощник первой строкой сообщает «ready mouse=1 touch=1»; на слабых приставках запуск занимает больше секунды
            val ready = StringBuilder()
            val reader = Thread {
                runCatching {
                    val input = s.openInputStream()
                    while (true) {
                        val ch = input.read()
                        if (ch < 0 || ch == '\n'.code) break
                        ready.append(ch.toChar())
                    }
                }
            }
            reader.isDaemon = true
            reader.start()
            reader.join(6000)
            val line = ready.toString()
            helperMouse = line.contains("mouse=1")
            helperTouch = line.contains("touch=1")
            helperKeys = line.contains("key=1")
            if (!line.startsWith("ready")) {
                // старый помощник без строки «ready» или медленный запуск: считаем, что мышь есть, касание и клавиши — нет
                helperMouse = !keysOnly
                helperTouch = false
                helperKeys = false
            }
            AppLog.i("ADBphon", "helper started: ${line.ifBlank { "(без ответа)" }}")
            Unit
        }.onFailure { AppLog.e("ADBphon", "helper error", it); helperStopNow() }
    }

    private var helperProc: Process? = null

    /** Помощник на этом телефоне от root: тот же Helper, но процесс запускается через su. */
    private fun helperStartRoot(bytes: ByteArray, keysOnly: Boolean = false) {
        rootRun("cat > /data/local/tmp/adbphon-helper.jar", java.io.ByteArrayInputStream(bytes), 30_000)
        val p = ProcessBuilder("su", "-c", "CLASSPATH=/data/local/tmp/adbphon-helper.jar app_process / Helper" + (if (keysOnly) " keys" else "")).redirectErrorStream(true).start()
        helperProc = p
        helperOut = p.outputStream
        val ready = StringBuilder()
        val reader = Thread {
            runCatching {
                val input = p.inputStream
                while (true) {
                    val ch = input.read()
                    if (ch < 0 || ch == '\n'.code) break
                    ready.append(ch.toChar())
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        reader.join(6000)
        val line = ready.toString()
        helperMouse = line.contains("mouse=1")
        helperTouch = line.contains("touch=1")
        helperKeys = line.contains("key=1")
        if (!line.startsWith("ready")) { helperMouse = !keysOnly; helperTouch = false; helperKeys = false }
        AppLog.i("ADBphon", "helper (root) started: ${line.ifBlank { "(без ответа)" }}")
    }

    private fun helperStopNow() {
        runCatching { helperOut?.apply { write("q\n".toByteArray()); flush() } }
        runCatching { helperProc?.destroy() }
        helperProc = null
        runCatching { helperStream?.close() }
        helperOut = null
        helperStream = null
        helperMouse = false
        helperTouch = false
        helperKeys = false
    }

    suspend fun helperStop() = withContext(Dispatchers.IO) { helperStopNow() }

    /** Команда помощнику: "m dx dy", "b маска", "w шаг". Запись идёт в одном потоке, чтобы порядок сохранялся. */
    fun helperSend(line: String) {
        val out = helperOut ?: return
        helperExec.execute {
            runCatching { out.write((line + "\n").toByteArray()); out.flush() }
        }
    }
}
