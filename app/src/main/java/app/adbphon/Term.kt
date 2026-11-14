package app.adbphon

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * Сеанс терминала для «Ещё → Консоль» (7.0, по образцу консоли Pulsar): одна живая оболочка, которая помнит каталог,
 * переменные и историю, пока открыто приложение. Способ выбирается сам, как работают остальные команды программы:
 *  - "root"   этот телефон с root (процесс su);
 *  - "adb"    настоящая связь ADB (этот телефон по Wi-Fi/USB или другое устройство по сети): оболочка с терминалом, работают Ctrl+C и Tab;
 *  - "helper" «помощник без отладки» (сервер программы, shell): команды идут по одной, каталог помнится, Ctrl+C и Tab недоступны.
 * Полноэкранные программы (vi, top без -n 1) не поддерживаются: терминал построчный.
 */
object Term {
    /** Видимый вывод (последние ~30 тысяч знаков); обновляется не чаще десяти раз в секунду. */
    val text = mutableStateOf("")
    val running = mutableStateOf(false)
    /** Чем идёт сеанс: "root", "adb", "helper" или "" (нет сеанса). */
    val mode = mutableStateOf("")

    private const val MAX = 30_000
    private val sb = StringBuilder()
    private val ui = Handler(Looper.getMainLooper())
    private var dirty = false
    private var proc: Process? = null
    private var rootOut: OutputStream? = null
    private var adbSession: Adb.ShellSession? = null
    private var gen = 0
    private var helperCwd = "/sdcard"
    @Volatile private var helperBusy = false

    private val promptMark = Regex("@@TVOPS@@([^\n]*)\n")
    private const val PS_ECHO = "echo \"@@TVOPS@@\$(pwd)\"\n"
    /** Приглашение как у adb shell: «a14xm:/sdcard $ » (у root «#»). */
    private fun prompt(cwd: String): String = android.os.Build.DEVICE + ":" + cwd + (if (mode.value == "root") " # " else " $ ")

    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007|\u001B[()][A-Za-z0-9]")

    /** Какой способ нужен сейчас, либо "" — команд выполнять нечем. */
    fun wanted(): String = when {
        Adb.isRoot -> "root"
        Adb.hasLink -> "adb"
        Adb.currentHost.isEmpty() && AdbServer.up -> "helper"
        else -> ""
    }

    private val flush = Runnable {
        dirty = false
        synchronized(sb) { text.value = sb.toString() }
    }

    private fun append(s: String) {
        var clean = ansi.replace(s, "").replace("\r\n", "\n").replace("\r", "")
        // 9.0: root-оболочка без терминала приглашения не печатает: после каждой команды она выводит маркер с текущим каталогом, здесь он превращается в строку-приглашение
        if (mode.value == "root") clean = promptMark.replace(clean) { m -> prompt(m.groupValues[1].trim().ifEmpty { "/" }) }
        if (clean.isEmpty()) return
        synchronized(sb) {
            sb.append(clean)
            if (sb.length > MAX) { val cut = sb.length - MAX; sb.delete(0, cut); markPos = (markPos - cut).coerceAtLeast(0) }
        }
        if (!dirty) { dirty = true; ui.postDelayed(flush, 100) }
    }

    /** Служебная строка в окне терминала (не от оболочки). */
    private fun note(s: String) = append("\n[$s]\n")

    fun clear() {
        synchronized(sb) { sb.setLength(0) }; ui.post(flush)
        // как в обычном терминале: после очистки сразу новое приглашение
        if (running.value) when (mode.value) { "helper" -> append(prompt(helperCwd)); "root" -> write(PS_ECHO.toByteArray()); else -> write("\n".toByteArray()) }
    }

    /** Запустить сеанс, если он не идёт или идёт не тем способом, который нужен сейчас. */
    fun ensure(ctx: Context) {
        val w = wanted()
        if (running.value && mode.value == w) return
        if (running.value) stop(quiet = true)
        if (w.isEmpty()) { running.value = false; mode.value = ""; return }
        start(ctx, w)
    }

    private fun start(ctx: Context, w: String) {
        val my = ++gen
        running.value = true; mode.value = w
        when (w) {
            "root" -> {
                thread(isDaemon = true) {
                    runCatching {
                        val p = Runtime.getRuntime().exec(arrayOf("su"))
                        proc = p; rootOut = p.outputStream
                        fun pump(s: java.io.InputStream) = thread(isDaemon = true) {
                            val r = java.io.InputStreamReader(s, Charsets.UTF_8); val cb = CharArray(2048)
                            while (gen == my) { val n = runCatching { r.read(cb) }.getOrDefault(-1); if (n < 0) break; append(String(cb, 0, n)) }
                        }
                        pump(p.inputStream); pump(p.errorStream)
                        runCatching { p.outputStream.write(PS_ECHO.toByteArray()); p.outputStream.flush() }   // первое приглашение
                        p.waitFor()
                    }
                    if (gen == my) { running.value = false; note(ctx.getString(R.string.tc_ended)) }
                }
            }
            "helper" -> {
                helperCwd = "/sdcard"; helperBusy = false
                append(prompt(helperCwd))
            }
            else -> {
                // открытие потока ADB — сетевая операция: только не в главном потоке
                thread(isDaemon = true) {
                    val s = Adb.openShell({ if (gen == my) append(it) }, { if (gen == my) { running.value = false; note(ctx.getString(R.string.tc_ended)) } })
                    if (gen != my) { s?.close(); return@thread }
                    adbSession = s
                    if (s == null) { running.value = false; note(ctx.getString(R.string.tc_no_access)) }
                }
            }
        }
    }

    fun stop(quiet: Boolean = false) {
        gen++
        runCatching { adbSession?.close() }; adbSession = null
        runCatching { proc?.destroy() }; proc = null; rootOut = null
        running.value = false
        if (!quiet) append("\n")
    }

    /** Запись в оболочку — тоже сетевая операция (ADB) или канал процесса: только в фоновом потоке. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
    private fun write(b: ByteArray) {
        writer.execute {
            runCatching {
                if (mode.value == "root") { rootOut?.write(b); rootOut?.flush() } else adbSession?.write(b)
            }
        }
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Построчный режим «помощник»: каталог помнится (cd в начале, pwd в конце), вывод приходит целиком после команды. */
    private fun helperRun(line: String) {
        if (helperBusy) { append("[busy]\n"); return }
        helperBusy = true
        val my = gen
        val cwd = helperCwd
        thread(isDaemon = true) {
            val mark = "@@TVOCWD@@"
            val out = runCatching { AdbServer.run("cd " + q(cwd) + " 2>/dev/null\n" + line + "\necho; echo " + mark + "\"\$(pwd)\"", null, 0, 120_000L) }
                .getOrElse { "[" + (it.message ?: it.javaClass.simpleName) + "]" }
            if (gen == my) {
                val i = out.lastIndexOf(mark)
                var shown = out
                if (i >= 0) {
                    helperCwd = out.substring(i + mark.length).trim().ifEmpty { cwd }
                    shown = out.substring(0, i).trimEnd('\n', ' ')
                }
                append((if (shown.isEmpty()) "" else shown + "\n") + prompt(helperCwd))
            }
            helperBusy = false
        }
    }

    /** Отправить введённую строку. На root и в режиме помощника ввод не печатается оболочкой — показываем его здесь; у ADB-терминала оболочка печатает его сама. */
    fun sendLine(line: String) {
        if (!running.value) return
        // приглашение уже на экране; у root и помощника набранную строку добавляем сами (у adb shell её печатает оболочка)
        if (mode.value == "root" || mode.value == "helper") append(line + "\n")
        synchronized(sb) { markPos = sb.length }          // с этого места начинается вывод последней команды
        if (mode.value == "helper") helperRun(line) else if (mode.value == "root") write((line + "\n" + PS_ECHO).toByteArray()) else write((line + "\n").toByteArray())
    }

    /** Начало вывода последней введённой команды в буфере. */
    @Volatile private var markPos = 0

    /**
     * Вывод последней команды без самой команды и без приглашения оболочки. У ADB-терминала оболочка сама печатает
     * введённую строку (первая строка) и новое приглашение (последняя, без перевода строки) — обе отбрасываются.
     */
    fun lastOutput(): String {
        var s = synchronized(sb) { sb.substring(markPos.coerceIn(0, sb.length)) }
        if (mode.value == "adb") {
            val nl = s.indexOf('\n')
            s = if (nl >= 0) s.substring(nl + 1) else ""
            val last = s.lastIndexOf('\n')
            s = if (last >= 0) s.substring(0, last + 1) else if (s.isNotEmpty() && !s.endsWith("\n")) "" else s
        }
        if (mode.value != "adb") { val tail = s.substringAfterLast('\n'); if (tail.endsWith("$ ") || tail.endsWith("# ")) s = s.dropLast(tail.length) }
        return s.trim('\n', '\r', ' ')
    }

    /** Служебная клавиша: Tab, Esc, Ctrl+D и т. п. (в виде байта). В режиме помощника недоступна. */
    fun sendKey(code: Int) { if (running.value && mode.value != "helper") write(byteArrayOf(code.toByte())) }

    /**
     * Ctrl+C. У ADB-терминала это настоящее прерывание; у root-оболочки терминала нет, поэтому прерывание
     * сделано перезапуском сеанса (каталог и переменные сбрасываются). В режиме помощника команду не прервать.
     */
    fun interrupt(ctx: Context) {
        when (mode.value) {
            "root" -> { stop(quiet = true); append("^C\n"); ensure(ctx) }
            "helper" -> append("^C (" + ctx.getString(R.string.tc_helper_nointr) + ")\n")
            else -> sendKey(3)
        }
    }

    // ---- настройки окна терминала (как у Pulsar: размер шрифта и два цвета) ----
    const val BG_DEFAULT = 0xFF1B3F72.toInt()
    const val FG_DEFAULT = 0xFFFFFFFF.toInt()
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE)
    fun bg(ctx: Context): Int = p(ctx).getInt("term_bg", BG_DEFAULT)
    fun fg(ctx: Context): Int = p(ctx).getInt("term_fg", FG_DEFAULT)
    fun fontSp(ctx: Context): Int = p(ctx).getInt("term_fs", 13)
    fun setBg(ctx: Context, v: Int) { p(ctx).edit().putInt("term_bg", v).apply() }
    fun setFg(ctx: Context, v: Int) { p(ctx).edit().putInt("term_fg", v).apply() }
    fun setFontSp(ctx: Context, v: Int) { p(ctx).edit().putInt("term_fs", v).apply() }
}
