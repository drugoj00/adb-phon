package app.adbphon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * 22.11.2026 (для 18.0): защита от повторного запуска установки. Пока установка пакета идёт (запись в сессию, ожидание ответа в системном окне),
 * второй запуск того же пакета и того же файла не создаёт новую сессию и не открывает второе системное окно «Установить», а окно программы
 * показывает «Устанавливаю…». Ключ «пакет + число частей + общий размер» не зависит от пути, поэтому повторно созданная копия файла
 * (окно пересоздано системой на слабом устройстве) считается тем же файлом. Запись старше [STALE_MS] считается потерянной и не мешает.
 */
object InstallGuard {
    /** WRITE: файл передаётся в сессию; SYSTEM: системное окно подтверждения уже показано, ответ ждёт пользователь. */
    enum class Phase { WRITE, SYSTEM }
    class Item(val phase: Phase, val since: Long)
    /** Итог, оставленный для окна, которое открылось заново: [at] время конца установки. */
    class Done(val ok: Boolean, val cancelled: Boolean, val text: String, val at: Long)

    const val STALE_MS = 11 * 60_000L
    /** Сколько после удачной установки тот же пакет и файл считаются установленными. */
    const val RECENT_MS = 60_000L

    private val _active = MutableStateFlow<Map<String, Item>>(emptyMap())
    val active: StateFlow<Map<String, Item>> get() = _active
    private val _done = MutableStateFlow<Map<String, Done>>(emptyMap())

    /** Общий поток для установок: окно, которое их запустило, может быть закрыто или пересоздано системой, установка при этом не обрывается. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun key(pkg: String?, parts: List<File>): String = (pkg ?: "?") + "|" + parts.size + "|" + parts.sumOf { it.length() }

    /** true, если установка этого ключа началась сейчас; false, если такая уже идёт (запускать вторую нельзя). */
    fun tryBegin(key: String, now: Long = System.currentTimeMillis()): Boolean {
        var ok = false
        _active.update { m ->
            val cur = m[key]
            if (cur != null && now - cur.since < STALE_MS) { ok = false; m } else { ok = true; m + (key to Item(Phase.WRITE, now)) }
        }
        return ok
    }

    fun setPhase(key: String, phase: Phase) { _active.update { m -> m[key]?.let { m + (key to Item(phase, it.since)) } ?: m } }

    /** Конец установки; [done] запоминается для окна, открывшегося заново. */
    fun end(key: String, done: Done? = null) {
        if (done != null) _done.update { it + (key to done) }
        _active.update { it - key }
    }

    fun isActive(key: String, now: Long = System.currentTimeMillis()): Boolean = isFresh(_active.value[key], now)

    fun anyActive(keys: Collection<String>, now: Long = System.currentTimeMillis()): Boolean = keys.any { isActive(it, now) }

    fun phaseOf(key: String, now: Long = System.currentTimeMillis()): Phase? = _active.value[key]?.takeIf { isFresh(it, now) }?.phase

    /** Итог установки, закончившейся не раньше [since]. */
    fun finished(keys: Collection<String>, since: Long): Done? = keys.firstNotNullOfOrNull { _done.value[it]?.takeIf { d -> d.at >= since } }

    /** Удачная установка этого пакета и файла за последние [window] мс: повторный запрос (то же окно, пересозданное системой, повторный Intent) не должен снова предлагать «Установить». */
    fun recentSuccess(keys: Collection<String>, now: Long = System.currentTimeMillis(), window: Long = RECENT_MS): Done? =
        keys.firstNotNullOfOrNull { _done.value[it]?.takeIf { d -> d.ok && now - d.at in 0..window } }

    /**
     * 23.11.2026 (18.0): то же, но «уже установлено» считается, только если пакет установлен СЕЙЧАС (приложение могли удалить сразу после установки:
     * повторная установка того же файла в первую минуту не должна упираться в защиту). Пакет неизвестен («?») или не найден: защита не срабатывает.
     */
    fun recentSuccess(ctx: android.content.Context, keys: Collection<String>): Done? {
        val d = recentSuccess(keys) ?: return null
        val pm = ctx.packageManager
        val live = keys.any { k ->
            val pkg = k.substringBefore('|')
            pkg != "?" && pkg.isNotEmpty() && _done.value[k]?.let { x -> x.ok && System.currentTimeMillis() - x.at in 0..RECENT_MS } == true &&
                runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        }
        return if (live) d else null
    }

    private fun isFresh(i: Item?, now: Long) = i != null && now - i.since < STALE_MS

    internal fun resetForTest() { _active.value = emptyMap(); _done.value = emptyMap() }
}
