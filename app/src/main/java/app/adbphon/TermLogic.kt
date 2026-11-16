package app.adbphon

/**
 * 10.0 (wad72, 4PDA: «…/sdcard $», «…/ $», «[сеанс завершён]», «…/ $»): чистая логика сеанса «Ещё → Консоль» без Android,
 * проверяется юнит-тестом TermLogicTest.
 *  - почему закончился сеанс: команда exit или Ctrl+D (ожидаемо), обрыв связи ADB, оболочка закрылась сама, сеанс не открылся;
 *  - переподключаться ли самим (не больше [MAX_AUTO] раз в минуту, с нарастающей паузой) или ждать кнопку «Переподключить»;
 *  - текущая папка по приглашению оболочки («a14xm:/sdcard $ »), чтобы после переподключения вернуться в неё.
 */
object TermLogic {
    enum class End { USER_EXIT, LINK_LOST, SHELL_DIED, OPEN_FAILED }

    /** [showEnded]: писать «[сеанс завершён]»; [reconnect]: переподключиться самим через [delayMs]; [button]: показать кнопку «Переподключить». */
    data class Decision(val showEnded: Boolean, val reconnect: Boolean, val delayMs: Long, val button: Boolean)

    const val MAX_AUTO = 3
    const val WINDOW_MS = 60_000L
    /** Сколько после exit или Ctrl+D конец сеанса считается ожидаемым. */
    const val EXIT_GRACE_MS = 8_000L

    private val exitRe = Regex("^(exit|logout)(\\s+-?\\d+)?\\s*;?$")

    /** Команда завершает оболочку: exit, exit 0, logout (у вставки из нескольких строк смотрится последняя). */
    fun isExitCmd(line: String): Boolean = line.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.let { exitRe.matches(it) } ?: false

    fun endReason(exitAt: Long, eofAt: Long, now: Long, opened: Boolean, mode: String, linkAlive: Boolean): End = when {
        !opened -> End.OPEN_FAILED
        (exitAt > 0 && now - exitAt in 0..EXIT_GRACE_MS) || (eofAt > 0 && now - eofAt in 0..EXIT_GRACE_MS) -> End.USER_EXIT
        mode == "adb" && !linkAlive -> End.LINK_LOST
        else -> End.SHELL_DIED
    }

    /** [recentAttempts]: сколько самостоятельных переподключений было за последнюю минуту; [canReconnect]: есть чем выполнять команды (root, ADB или помощник). */
    fun decide(end: End, recentAttempts: Int, canReconnect: Boolean): Decision = when {
        end == End.USER_EXIT -> Decision(showEnded = true, reconnect = false, delayMs = 0, button = true)
        !canReconnect || recentAttempts >= MAX_AUTO -> Decision(showEnded = false, reconnect = false, delayMs = 0, button = true)
        else -> Decision(showEnded = false, reconnect = true, delayMs = 1_500L * (recentAttempts + 1), button = false)
    }

    fun recent(attempts: List<Long>, now: Long): Int = attempts.count { now - it in 0..WINDOW_MS }

    private val promptRe = Regex("(?m)^[A-Za-z0-9_.@-]+:(/[^\\n]*?) ?[$#] ?$")

    /** Текущая папка по последнему приглашению оболочки в выводе («RE6079L1:/sdcard/Download $ » -> /sdcard/Download); null, если приглашения нет. */
    fun cwdFromText(tail: String): String? = promptRe.findAll(tail).lastOrNull()?.groupValues?.get(1)?.trimEnd()?.ifEmpty { null }

    /** Команда возврата в папку (кавычки экранируются). */
    fun cdCmd(cwd: String): String = "cd '" + cwd.replace("'", "'\\''") + "'"
}
