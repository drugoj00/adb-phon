package app.adbphon

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Постоянная root-оболочка (7.6): вместо запуска `su -c` на каждую команду (каждый запуск su системный менеджер root показывает
 * уведомлением «права суперпользователя предоставлены») root запрашивается один раз на сеанс. Команды без входных данных идут
 * в этот процесс по очереди; конец ответа определяется меткой с кодом возврата. Каждая команда выполняется в подоболочке
 * (cd, переменные и exit не влияют на остальные), стандартный ввод команды закрыт (/dev/null), чтобы она не съела чужие команды.
 * Процесс запускается лениво, при первом действии, которому нужен root; при зависании команды процесс убивается и поднимается заново.
 * Длинные операции (установка пакетов, копии) идут во второй оболочке, чтобы не задерживать короткие.
 */
class RootSession(private val name: String) {
    private val lock = Any()
    private var proc: Process? = null
    private var stdin: OutputStream? = null
    private val buf = ByteArrayOutputStream()
    private val seq = AtomicLong(0)

    private fun alive(): Boolean = proc?.let { p -> runCatching { p.exitValue() }.isFailure } == true && stdin != null

    private fun spawn(mm: Boolean): Boolean {
        val p = runCatching { ProcessBuilder(if (mm) listOf("su", "-mm") else listOf("su")).redirectErrorStream(true).start() }
            .onFailure { AppLog.i("ADBphon", "root[$name]: su не запустился: ${it.message}") }.getOrNull() ?: return false
        synchronized(buf) { buf.reset() }
        proc = p
        stdin = p.outputStream
        val inp = p.inputStream
        Thread {
            runCatching {
                val b = ByteArray(8192)
                while (true) {
                    val n = inp.read(b)
                    if (n < 0) break
                    synchronized(buf) { buf.write(b, 0, n); (buf as Object).notifyAll() }
                }
            }
            synchronized(buf) { (buf as Object).notifyAll() }
        }.also { it.isDaemon = true; it.name = "root-reader-$name" }.start()
        return true
    }

    /** Запускает оболочку, если её ещё нет, и проверяет, что это root (uid=0). */
    fun ensure(timeoutMs: Long = 30_000): Boolean = synchronized(lock) {
        if (alive()) return true
        close()
        val order = when (RootShell.mountMaster) { false -> listOf(false); else -> listOf(true, false) }
        for (mm in order) {
            if (!spawn(mm)) continue
            val out = runCatching { execLocked("id", timeoutMs) }.getOrDefault("")
            if (out.contains("uid=0")) {
                if (RootShell.mountMaster == null || RootShell.mountMaster != mm) RootShell.setMountMaster(mm)
                AppLog.i("ADBphon", "root[$name]: оболочка запущена (su${if (mm) " -mm" else ""})")
                return true
            }
            close()
        }
        false
    }

    fun exec(cmd: String, timeoutMs: Long): String = synchronized(lock) {
        if (!ensure()) throw IOException("root недоступен")
        execLocked(cmd, timeoutMs)
    }

    private fun execLocked(cmd: String, timeoutMs: Long): String {
        val out = stdin ?: throw IOException("root-оболочка закрыта")
        val mark = "__ADBPHON_END_" + seq.incrementAndGet() + "__"
        synchronized(buf) { buf.reset() }
        try {
            out.write(("(\n" + cmd + "\n) </dev/null 2>&1\necho \"" + mark + "\$?\"\n").toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: Exception) { close(); throw IOException("root-оболочка не отвечает: ${e.message}") }
        val t0 = System.currentTimeMillis()
        while (true) {
            val s = synchronized(buf) { buf.toString("UTF-8") }
            val i = s.lastIndexOf(mark)
            if (i >= 0 && s.indexOf('\n', i) >= 0) return s.substring(0, i).trim()
            if (proc?.let { p -> runCatching { p.exitValue() }.isSuccess } == true) {
                val partial = s.trim()
                close()
                return partial
            }
            if (System.currentTimeMillis() - t0 > timeoutMs) {
                AppLog.w("ADBphon", "timeout root[$name]: ${cmd.take(100)}")
                val partial = s.trim()
                close()
                return partial
            }
            synchronized(buf) { (buf as Object).wait(100) }
        }
    }

    fun close() {
        runCatching { stdin?.close() }
        runCatching { proc?.destroy() }
        proc = null
        stdin = null
    }
}

object RootShell {
    /** Оболочка для коротких команд и оболочка для длинных (установка, копии). */
    val short = RootSession("short")
    val long = RootSession("long")

    /** null: ещё не определяли; true: su знает ключ -mm (общее пространство имён, Magisk); false: обычный su. */
    @Volatile var mountMaster: Boolean? = null
        private set
    private var store: ((Boolean) -> Unit)? = null

    fun init(initial: Boolean?, save: (Boolean) -> Unit) { mountMaster = initial; store = save }
    fun setMountMaster(v: Boolean) { mountMaster = v; runCatching { store?.invoke(v) } }

    fun exec(cmd: String, timeoutMs: Long): String = (if (timeoutMs > 60_000L) long else short).exec(cmd, timeoutMs)

    fun closeAll() { short.close(); long.close() }
}
