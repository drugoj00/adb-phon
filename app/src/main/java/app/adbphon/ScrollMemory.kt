package app.adbphon

import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Запоминание положения прокрутки блоков, списков и строк значков: после поворота, смены вкладок, сворачивания и перезапуска блок стоит на прежнем месте.
 * Положения лежат в настройках «fm» (ключи «sc:…» и «lz:…»), поэтому входят в полную резервную копию.
 */
object ScrollMem {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE)

    fun getInt(ctx: Context, key: String): Int = sp(ctx).getInt("sc:$key", 0).coerceAtLeast(0)
    fun putInt(ctx: Context, key: String, v: Int) { sp(ctx).edit().putInt("sc:$key", v.coerceAtLeast(0)).apply() }

    /** Список: «индекс:смещение». */
    fun getList(ctx: Context, key: String): Pair<Int, Int> {
        val s = sp(ctx).getString("lz:$key", null) ?: return 0 to 0
        val a = s.split(':')
        return (a.getOrNull(0)?.toIntOrNull() ?: 0).coerceAtLeast(0) to (a.getOrNull(1)?.toIntOrNull() ?: 0).coerceAtLeast(0)
    }
    fun putList(ctx: Context, key: String, i: Int, o: Int) { sp(ctx).edit().putString("lz:$key", "$i:$o").apply() }
    /** Ключ первой видимой строки списка (для списков, где строки подгружаются после запуска). */
    fun getKey(ctx: Context, key: String): String? = sp(ctx).getString("lk:$key", null)
    fun putKey(ctx: Context, key: String, rowKey: String?) { sp(ctx).edit().apply { if (rowKey == null) remove("lk:$key") else putString("lk:$key", rowKey) }.apply() }

    /** Положения папок по панелям: до 40 последних, строка «путь<TAB>индекс<TAB>смещение». */
    fun getFolder(ctx: Context, pane: Int, src: String, path: String): Pair<Int, Int>? {
        val k = "$src|$path"
        for (l in (sp(ctx).getString("lzp:$pane", "") ?: "").split('\n')) {
            val a = l.split('\t'); if (a.size == 3 && a[0] == k) return (a[1].toIntOrNull() ?: 0).coerceAtLeast(0) to (a[2].toIntOrNull() ?: 0).coerceAtLeast(0)
        }
        return null
    }
    fun putFolder(ctx: Context, pane: Int, src: String, path: String, i: Int, o: Int) {
        val k = "$src|$path"
        val old = (sp(ctx).getString("lzp:$pane", "") ?: "").split('\n').filter { it.isNotBlank() && !it.startsWith("$k\t") }
        val n = (listOf("$k\t$i\t$o") + old).take(40)
        sp(ctx).edit().putString("lzp:$pane", n.joinToString("\n")).apply()
    }
}

/** ScrollState, который помнит положение (прокрутка колонок, строк значков). */
@Composable
fun rememberSavedScroll(key: String): ScrollState {
    val ctx = LocalContext.current
    val state = remember(key) { ScrollState(ScrollMem.getInt(ctx, key)) }
    fun now() = if (state.maxValue == Int.MAX_VALUE) state.value else minOf(state.value, state.maxValue)
    LaunchedEffect(state) {
        snapshotFlow { state.value }.collectLatest { delay(400); ScrollMem.putInt(ctx, key, now()) }
    }
    DisposableEffect(state) { onDispose { ScrollMem.putInt(ctx, key, now()) } }
    return state
}

/** LazyListState, который помнит положение (списки вкладок). */
@Composable
fun rememberSavedLazyList(key: String): LazyListState {
    val ctx = LocalContext.current
    val (i, o) = remember(key) { ScrollMem.getList(ctx, key) }
    val state = rememberLazyListState(i, o)
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }.collectLatest { (a, b) -> delay(400); ScrollMem.putList(ctx, key, a, b) }
    }
    DisposableEffect(state) { onDispose { ScrollMem.putList(ctx, key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) } }
    return state
}

/**
 * Дерево «Файлов» (7.5): положение прокрутки восстанавливается ПОСЛЕ того, как строки загрузились (раньше список в первый момент короче, сохранённое
 * положение зажималось, и после выхода из программы дерево стояло сверху, а открытая папка внизу). Сначала ищется первая видимая строка по её ключу,
 * иначе по индексу; затем, если выделенная/открытая строка [selKey] не видна, список прокручивается к ней. Сохранение включается после восстановления.
 */
@Composable
fun rememberSavedTreeList(key: String, rowKeys: List<String>, selKey: String?): LazyListState {
    val ctx = LocalContext.current
    val (i, o) = remember(key) { ScrollMem.getList(ctx, key) }
    val savedKey = remember(key) { ScrollMem.getKey(ctx, key) }
    val state = rememberLazyListState()
    val keys = androidx.compose.runtime.rememberUpdatedState(rowKeys)
    val sel = androidx.compose.runtime.rememberUpdatedState(selKey)
    var restored by remember(key) { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(key) {
        var waited = 0
        while (waited < 6000) {
            val ks = keys.value
            val idx = savedKey?.let { ks.indexOf(it) } ?: -1
            if (idx >= 0) { state.scrollToItem(idx, o); break }
            if (i == 0 && o == 0 && ks.isNotEmpty()) break
            if (ks.size > i + 3) { state.scrollToItem(i, o); break }
            delay(200); waited += 200
        }
        // выделенная/открытая строка должна быть видна (выбор мог восстановиться чуть позже строк: ждём до двух секунд)
        var w2 = 0
        while (sel.value == null && w2 < 2000) { delay(100); w2 += 100 }
        delay(150)
        val ks = keys.value; val si = sel.value?.let { ks.indexOf(it) } ?: -1
        val needScroll = si >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == si }
        if (needScroll) state.scrollToItem((si - 2).coerceAtLeast(0))
        // 7.6: строки дерева подгружаются ещё несколько секунд (облака, устройства, сеть): когда их число перестало меняться, выбранная строка проверяется ещё раз
        // (раньше при выборе нижней строки «Показать/Скрыть» после загрузки облачных строк она уходила за нижний край, а дерево оставалось не там)
        var lastN = ks.size; var stable = 0; var w3 = 0
        while (stable < 6 && w3 < 5000 && !state.isScrollInProgress) {
            delay(150); w3 += 150
            val n = keys.value.size
            if (n != lastN) { lastN = n; stable = 0 } else stable++
        }
        if (!state.isScrollInProgress) {
            val ks2 = keys.value; val si2 = sel.value?.let { ks2.indexOf(it) } ?: -1
            if (si2 >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == si2 }) state.scrollToItem((si2 - 2).coerceAtLeast(0))
        }
        android.util.Log.i("FocusRestore", "tree restored: keyMode=" + FocusRestore.keyMode(ctx) + " sel=" + sel.value + " si=" + si + " n=" + ks.size + "->" + keys.value.size + " first=" + state.firstVisibleItemIndex)
        if (FocusRestore.keyMode(ctx) && sel.value != null) FocusRestore.key = sel.value
        restored = true
    }
    LaunchedEffect(state, restored) {
        if (!restored) return@LaunchedEffect
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }.collectLatest { (a, b) ->
            delay(400); ScrollMem.putList(ctx, key, a, b); ScrollMem.putKey(ctx, key, keys.value.getOrNull(a))
        }
    }
    DisposableEffect(state, restored) { onDispose { if (restored) { ScrollMem.putList(ctx, key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset); ScrollMem.putKey(ctx, key, keys.value.getOrNull(state.firstVisibleItemIndex)) } } }
    return state
}

/**
 * Фокус пульта (D-pad) после возврата в программу: дерево «Файлов» после восстановления положения просит фокус для выбранной/открытой строки,
 * если устройство управляется пультом (ТВ, приставка, джойстик) или стрелки/ОК уже использовались на этом устройстве.
 */
object FocusRestore {
    /** Ключ строки, которой нужно передать фокус (сбрасывается строкой после запроса). */
    var key by androidx.compose.runtime.mutableStateOf<String?>(null)
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE)
    fun keyMode(ctx: Context): Boolean = Marq.tv || ctx.resources.configuration.navigation == android.content.res.Configuration.NAVIGATION_DPAD || sp(ctx).getBoolean("kbd_used", false)
    fun markKeys(ctx: Context) { if (!sp(ctx).getBoolean("kbd_used", false)) sp(ctx).edit().putBoolean("kbd_used", true).apply() }
    fun isDpad(code: Int) = code == android.view.KeyEvent.KEYCODE_DPAD_UP || code == android.view.KeyEvent.KEYCODE_DPAD_DOWN || code == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
        code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT || code == android.view.KeyEvent.KEYCODE_DPAD_CENTER
}
