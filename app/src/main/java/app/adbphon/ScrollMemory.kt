package app.adbphon

import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
