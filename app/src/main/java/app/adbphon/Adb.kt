package app.adbphon

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

    fun init(context: Context) {
        mgr = AdbConn(context.applicationContext)
    }

    val isConnected: Boolean get() = ::mgr.isInitialized && mgr.isConnected

    suspend fun connectLocal(port: Int, host: String = "127.0.0.1"): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Первое подключение к новому устройству: пока человек нажимает «Разрешить», попытка обрывается; вторая проходит сразу
            var last: Throwable? = null
            var done = false
            for (attempt in 1..2) {
                runCatching { mgr.disconnect() }
                AppLog.i("ADBphon", "connect $host:$port (попытка $attempt)")
                val r = runCatching { withTimeout(25_000) { runInterruptible { mgr.connect(host, port) } } }
                AppLog.i("ADBphon", "connect result=${r.getOrNull()}")
                if (r.getOrNull() == true) { done = true; break }
                last = r.exceptionOrNull() ?: IllegalStateException("connect failed")
            }
            if (!done) throw last ?: IllegalStateException("connect failed")
        }.onFailure { AppLog.e("ADBphon", "connect error", it) }
    }

    suspend fun pair(port: Int, code: String, host: String = "127.0.0.1"): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { mgr.pair(host, port, code) }.map { }.onFailure { AppLog.e("ADBphon", "pair error", it) }
    }

    /** Беспроводная отладка: порт ищется сам (mDNS), при неудаче используется порт, введённый вручную. */
    suspend fun connectWireless(context: Context, manualPort: Int?, host: String = "127.0.0.1"): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            runCatching { mgr.disconnect() }
            var ok = false
            if (host == "127.0.0.1") {
                ok = runCatching { mgr.autoConnect(context.applicationContext, 7000) }
                    .onFailure { AppLog.e("ADBphon", "autoConnect error", it) }.getOrDefault(false)
            }
            if (!ok && manualPort != null) ok = mgr.connect(host, manualPort)
            if (!ok) error("connect failed")
        }.onFailure { AppLog.e("ADBphon", "wireless connect error", it) }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) { runCatching { mgr.disconnect() } }

    /** Выполнить команду оболочки и вернуть её вывод. */
    suspend fun shell(cmd: String, timeoutMs: Long = 25_000): String = withContext(Dispatchers.IO) {
        AppLog.i("ADBphon", "shell> ${cmd.take(300)}")
        val t0 = System.currentTimeMillis()
        val res = runCatching { readAll(mgr.openStream("shell:$cmd"), timeoutMs, cmd) }
        AppLog.i("ADBphon", "shell< ${System.currentTimeMillis() - t0} мс, ${res.getOrNull()?.length ?: -1} симв: ${(res.getOrNull() ?: res.exceptionOrNull()?.message).orEmpty().take(300).replace("\n", " ⏎ ")}")
        res.getOrThrow()
    }

    /**
     * Читает поток до конца с ограничением по времени. Библиотека иногда бросает «Stream closed» вместо конца потока
     * или не закрывает поток: прочитанное считаем ответом, зависший поток закрываем сами.
     */
    private fun readAll(s: io.github.muntashirakon.adb.AdbStream, timeoutMs: Long, what: String): String {
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
        val text = synchronized(out) { out.toString("UTF-8") }.trim()
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

    /** Установить APK потоком (cmd package install -S). flags: например "-r" или "-r -d" (понижение версии). */
    suspend fun installApk(input: InputStream, size: Long, flags: String = "-r"): String = withContext(Dispatchers.IO) {
        val s = mgr.openStream("exec:cmd package install ${withUser(flags)} -S $size")
        val out = s.openOutputStream()
        input.copyTo(out)
        out.flush()
        readAll(s, 120_000, "install")
    }

    /** Ничего не ждёт: в ADB-phon менеджер создаётся сразу (нужно перенесённым модулям). */
    suspend fun awaitReady() {}

    /** Копирует файл с устройства через оболочку ADB (так читаются флешки, которые система приложению не показывает). */
    suspend fun pull(path: String, dest: java.io.File) = withContext(Dispatchers.IO) {
        val s = mgr.openStream("exec:cat '" + path.replace("'", "'\\''") + "'")
        try {
            s.openInputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        } finally { runCatching { s.close() } }
    }

    /** Записывает [size] байт из потока в файл на устройстве от имени shell (туда, куда обычному приложению писать нельзя, например Android/obb). */
    suspend fun pushStream(input: InputStream, size: Long, dest: String) = withContext(Dispatchers.IO) {
        fun q(p: String) = "'" + p.replace("'", "'\\''") + "'"
        shell("mkdir -p " + q(dest.substringBeforeLast('/')))
        val s = mgr.openStream("exec:head -c $size > " + q(dest))
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
        for (f in files) {
            val res = withContext(Dispatchers.IO) {
                val s = mgr.openStream("exec:cmd package install-write -S ${f.length()} $id ${f.name} -")
                val out = s.openOutputStream()
                f.inputStream().use { it.copyTo(out) }
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

    suspend fun helperStart(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            helperStopNow()
            val bytes = context.assets.open("helper.jar").use { it.readBytes() }
            val up = mgr.openStream("exec:cat > /data/local/tmp/adbphon-helper.jar")
            up.openOutputStream().apply { write(bytes); flush() }
            runCatching { up.close() }
            kotlinx.coroutines.delay(500)
            val s = mgr.openStream("exec:CLASSPATH=/data/local/tmp/adbphon-helper.jar app_process / Helper")
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
            if (!line.startsWith("ready")) {
                // старый помощник без строки «ready» или медленный запуск: считаем, что мышь есть, касание — нет
                helperMouse = true
                helperTouch = false
            }
            AppLog.i("ADBphon", "helper started: ${line.ifBlank { "(без ответа)" }}")
            Unit
        }.onFailure { AppLog.e("ADBphon", "helper error", it); helperStopNow() }
    }

    private fun helperStopNow() {
        runCatching { helperOut?.apply { write("q\n".toByteArray()); flush() } }
        runCatching { helperStream?.close() }
        helperOut = null
        helperStream = null
        helperMouse = false
        helperTouch = false
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
