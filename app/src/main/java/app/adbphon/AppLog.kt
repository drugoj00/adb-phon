package app.adbphon

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал для поиска ошибок. Обычный журнал пишется в файл только когда включён в настройках;
 * последний сбой (необработанное исключение) сохраняется всегда.
 */
object AppLog {
    @Volatile var enabled = false
    private var dir: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private const val MAX = 1_000_000L

    fun init(ctx: Context) {
        dir = (ctx.getExternalFilesDir("logs") ?: File(ctx.filesDir, "logs")).also { it.mkdirs() }
        enabled = ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getBoolean("log_on", false)
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { crashFile()?.writeText("${fmt.format(Date())} поток ${t.name}\n" + Log.getStackTraceString(e)) }
            runCatching { write("F", "СБОЙ в потоке ${t.name}: ${e.javaClass.simpleName}: ${e.message}", force = true) }
            old?.uncaughtException(t, e)
        }
        write("I", "журнал: запуск, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}", force = false)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE).edit().putBoolean("log_on", on).apply()
        enabled = on
        write("I", if (on) "журнал включён" else "журнал выключен", force = true)
    }

    fun file(): File? = dir?.let { File(it, "adbphon.log") }
    private fun crashFile(): File? = dir?.let { File(it, "crash.txt") }

    @Synchronized
    private fun write(level: String, msg: String, force: Boolean) {
        if (!enabled && !force) return
        val f = file() ?: return
        runCatching {
            if (f.length() > MAX) f.renameTo(File(f.parentFile, "adbphon.log.1"))
            f.appendText("${fmt.format(Date())} $level $msg\n")
        }
    }

    fun i(tag: String, msg: String): Int { write("I", msg, false); return Log.i(tag, msg) }
    fun w(tag: String, msg: String): Int { write("W", msg, false); return Log.w(tag, msg) }
    fun e(tag: String, msg: String, t: Throwable? = null): Int {
        write("E", msg + if (t != null) " | ${t.javaClass.simpleName}: ${t.message}" else "", false)
        return Log.e(tag, msg, t)
    }

    /** Последние строки журнала (для показа и копирования). */
    fun tail(lines: Int = 300): String {
        val f = file()
        if (f == null || !f.exists()) return ""
        return runCatching { f.readLines().takeLast(lines).joinToString("\n") }.getOrDefault("")
    }

    fun lastCrash(): String = runCatching { crashFile()?.takeIf { it.exists() }?.readText().orEmpty() }.getOrDefault("")

    fun clear() {
        runCatching { file()?.delete(); File(dir, "adbphon.log.1").delete(); crashFile()?.delete() }
    }

    fun path(): String = file()?.absolutePath.orEmpty()
}
