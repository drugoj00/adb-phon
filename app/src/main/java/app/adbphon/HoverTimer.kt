package app.adbphon

/**
 * Таймер наведения при перетаскивании: срабатывает, когда над одной и той же целью указатель удерживается дольше [delay] мс;
 * после срабатывания отсчёт начинается заново (так папка раскрывается «вглубь», пока держишь). Без Android: проверяется на JVM.
 */
class HoverTimer {
    private var key = ""
    private var since = 0L

    /** [key] пустой: нет цели. Возвращает true один раз за каждый период удержания. */
    fun tick(key: String, now: Long, delay: Long): Boolean {
        if (key != this.key) { this.key = key; since = now; return false }
        if (key.isEmpty()) return false
        if (now - since >= delay) { since = now; return true }
        return false
    }

    fun reset() { key = ""; since = 0L }
}

/** Сброс «застрявшего» перетаскивания: нет событий дольше [timeoutMs]. */
fun dndStale(lastEventMs: Long, now: Long, timeoutMs: Long = 20_000L): Boolean = now - lastEventMs > timeoutMs
