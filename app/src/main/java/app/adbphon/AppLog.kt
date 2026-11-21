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
            // известный сбой библиотеки поиска ADB в сети во втором пространстве (user 95): поток поиска закрывается, приложение живёт дальше
            if (isKnownMdnsCrash(e)) { runCatching { write("W", "поиск ADB в сети остановлен: ${e.message}", force = true) }; return@setDefaultUncaughtExceptionHandler }
            runCatching { crashFile()?.writeText("${fmt.format(Date())} поток ${t.name}\n" + Log.getStackTraceString(e)) }
            runCatching { write("F", "СБОЙ в потоке ${t.name}: ${e.javaClass.simpleName}: ${e.message}", force = true) }
            old?.uncaughtException(t, e)
        }
        write("I", "журнал: запуск, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}", force = false)
    }

    /** Сбой «Ssaid settings not accessible» внутри поиска ADB по mDNS (AdbMdns): во втором пространстве (user 95) идентификатор устройства недоступен. */
    fun isKnownMdnsCrash(e: Throwable): Boolean {
        var c: Throwable? = e
        while (c != null) {
            if (c is IllegalStateException && c.message?.contains("Ssaid", ignoreCase = true) == true && e.stackTrace.any { it.className.contains("AdbMdns") || it.className.contains("AndroidUtils") }) return true
            c = c.cause
        }
        return false
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE).edit().putBoolean("log_on", on).apply()
        enabled = on
        write("I", if (on) "журнал включён" else "журнал выключен", force = true)
        if (on) header(ctx)
    }

    /**
     * 16.2: шапка журнала (при включении и при каждом запуске с включённым журналом): версия и код программы, Android, устройство, настройки значков, доступ к файлам.
     * Слово ApkIcon есть в строке, чтобы поиск по нему всегда что-то находил и было видно, какая сборка пишет журнал.
     */
    fun header(ctx: Context) {
        if (!enabled) return
        runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val allFiles = if (Build.VERSION.SDK_INT >= 30) (if (android.os.Environment.isExternalStorageManager()) "да" else "нет") else "н/д"
            write("I", "ApkIcon: шапка: ADB-phon версия=${pi.versionName} код=${pi.longVersionCode} Android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) устройство=${Build.MANUFACTURER} ${Build.MODEL} " +
                "| значки: у .apk=${if (UiPrefs.apkIcons) "вкл" else "выкл"} миниатюры=${if (UiPrefs.thumbs) "вкл" else "выкл"} дерево=${if (Fm.treeMode) "вкл" else "выкл"} " +
                "| доступ ко всем файлам=$allFiles root=${if (Adb.isRoot) "да" else "нет"} канал оболочки=${Adb.bytesChannel() ?: "нет"}", force = true)
        }
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

    /** Запись о важной операции (корзина облака): пишется всегда, файл небольшой (ротация по размеру). */
    fun note(msg: String) { write("T", msg, true) }

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
