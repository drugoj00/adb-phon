package app.adbphon

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 10.2 (правило 25): «настройки помнят, где ты был». Состояние экрана «Настройки» живёт на уровне программы, а не внутри экрана:
 * выбранная группа, текст поиска, раскрытые пояснения, где был фокус (ТВ) переживают смену нижних вкладок, поворот и пересоздание окна.
 * Прокрутка хранится по ключам «tab.settings…» ([ScrollMem]). Сбрасывается всё только при новом запуске программы ([initOnce]).
 */
object SettingsMemory {
    /** Выбранная (две половины) или раскрытая (одна колонка) группа; пусто: в портрете все свёрнуты, в двух половинах первая по списку. */
    var group by mutableStateOf("")
    var query by mutableStateOf("")
    /** Где был фокус пульта: 0 меню групп, 1 пункты группы. */
    var zone = 0
    /** Группа → ключ пункта, в котором был фокус. */
    val focusItem = HashMap<String, String>()
    private var inited = false

    /** Новый запуск процесса: прежние положения прокрутки настроек стираются (прокрутка лежит в prefs и иначе пережила бы закрытие). */
    fun initOnce(ctx: Context) {
        if (inited) return
        inited = true
        val sp = ctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE)
        val old = sp.all.keys.filter { it.startsWith("sc:tab.settings") }
        if (old.isNotEmpty()) sp.edit().apply { old.forEach { remove(it) } }.apply()
    }
}

/** Область запоминания раскрытых пояснений: пусто = как раньше (состояние живёт, пока экран на месте), иначе ключ области (например «settings.files»). */
val LocalCollapseScope = compositionLocalOf { "" }

/** Раскрытые пояснения в областях [LocalCollapseScope]: ключ «область|название». Порядок открытия нужен кнопке «Назад» (сворачивает последнее раскрытое). */
object CollapseMemory {
    private val map = mutableStateMapOf<String, Boolean>()
    private val order = ArrayList<String>()

    fun isOpen(full: String): Boolean = map[full] == true

    fun put(full: String, v: Boolean) {
        map[full] = v
        order.remove(full)
        if (v) order.add(full)
    }

    /** Последний раскрытый пункт области (по префиксу ключа), либо null. */
    fun lastOpen(prefix: String): String? = order.lastOrNull { it.startsWith(prefix) && map[it] == true }

    fun state(full: String): MutableState<Boolean> = object : MutableState<Boolean> {
        override var value: Boolean
            get() = map[full] == true
            set(v) { put(full, v) }
        override fun component1(): Boolean = value
        override fun component2(): (Boolean) -> Unit = { value = it }
    }
}

/** Состояние «раскрыто» для пояснения [id]: в области настроек оно общее для программы, вне её прежнее (сбрасывается при выходе из программы). */
@Composable
fun rememberCollapseOpen(id: String): MutableState<Boolean> {
    val scope = LocalCollapseScope.current
    val key = LocalCollapseKey.current + CollapseAll.key
    if (scope.isEmpty()) return remember(key) { mutableStateOf(false) }
    val full = "$scope|$id"
    return remember(full) { CollapseMemory.state(full) }
}
