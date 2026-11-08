package app.adbphon

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Положение и размер ползунка и пересчёт перетаскивания (все числа в px). */
object SbMath {
    class Thumb(val top: Float, val height: Float)

    fun thumb(offset: Float, viewport: Float, content: Float, minThumb: Float): Thumb? {
        if (viewport <= 0f || content <= viewport + 1f) return null
        val maxOff = content - viewport
        val h = maxOf(viewport * viewport / content, minOf(minThumb, viewport)).coerceAtMost(viewport)
        return Thumb((offset.coerceIn(0f, maxOff) / maxOff) * (viewport - h), h)
    }

    /** Поле справа под полосу (dp): содержимое стоит левее, поэтому полоса не закрывает квадратики и значки. */
    const val RESERVE_DP = 12f
    /** Зона нажатия и перетаскивания у самого правого края (dp). */
    const val ZONE_DP = 18f

    fun contentDelta(thumbDelta: Float, viewport: Float, content: Float, thumbHeight: Float): Float {
        val track = viewport - thumbHeight
        if (track <= 0f || content <= viewport) return 0f
        return thumbDelta * (content - viewport) / track
    }

    fun pageDelta(y: Float, thumb: Thumb, viewport: Float): Float = when {
        y < thumb.top -> -0.9f * viewport
        y > thumb.top + thumb.height -> 0.9f * viewport
        else -> 0f
    }
}

/**
 * Полоса прокрутки списков «Файлов»: видна всегда, пока есть что прокручивать; цвет темы, 6 dp в покое и 8 dp при наведении, касании и перетаскивании.
 * Ползунок тянется пальцем и мышью, нажатие на дорожку прокручивает на страницу. Фокус пульта не берёт. Ставится ПЕРЕД verticalScroll / на сам список.
 */
fun Modifier.verticalScrollbar(state: ScrollState): Modifier =
    sbar(state) { vp -> Triple(state.value.toFloat(), vp, vp + state.maxValue) }

fun Modifier.verticalScrollbar(state: LazyListState): Modifier =
    sbar(state) { _ ->
        val info = state.layoutInfo
        val vis = info.visibleItemsInfo
        if (vis.isEmpty() || info.totalItemsCount == 0) null
        else {
            val avg = vis.sumOf { it.size }.toFloat() / vis.size
            Triple(state.firstVisibleItemIndex * avg + state.firstVisibleItemScrollOffset, info.viewportSize.height.toFloat(), avg * info.totalItemsCount)
        }
    }

fun Modifier.verticalScrollbar(state: LazyGridState): Modifier =
    sbar(state) { _ ->
        val info = state.layoutInfo
        val vis = info.visibleItemsInfo
        if (vis.isEmpty() || info.totalItemsCount == 0) null
        else {
            val y0 = vis.first().offset.y
            val cols = vis.takeWhile { it.offset.y == y0 }.size.coerceAtLeast(1)
            val second = vis.firstOrNull { it.offset.y > y0 }
            val pitch = if (second != null) (second.offset.y - y0).toFloat() else vis.first().size.height.toFloat()
            val rows = (info.totalItemsCount + cols - 1) / cols
            Triple((state.firstVisibleItemIndex / cols) * pitch + state.firstVisibleItemScrollOffset, info.viewportSize.height.toFloat(), rows * pitch)
        }
    }

private fun Modifier.sbar(state: ScrollableState, metrics: (Float) -> Triple<Float, Float, Float>?): Modifier = composed {
    val color = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf(false) }
    val widthDp by animateFloatAsState(if (active) 8f else 6f, label = "sbW")
    val alpha by animateFloatAsState(if (active) 1f else 0.7f, label = "sbA")
    this
        .pointerInput(state) {
            val zone = SbMath.ZONE_DP.dp.toPx()
            val minThumb = 40.dp.toPx()
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val ch = ev.changes.firstOrNull() ?: continue
                    val vp = size.height.toFloat()
                    val th = metrics(vp)?.let { SbMath.thumb(it.first, it.second, it.third, minThumb) }
                    val inZone = th != null && ch.position.x >= size.width - zone
                    if (ev.type == PointerEventType.Move || ev.type == PointerEventType.Enter) active = inZone
                    if (ev.type == PointerEventType.Exit) active = false
                    if (th == null || !inZone || !(ch.pressed && !ch.previousPressed)) continue
                    ch.consume()
                    active = true
                    val onThumb = ch.position.y >= th.top && ch.position.y <= th.top + th.height
                    if (!onThumb) scope.launch { runCatching { state.animateScrollBy(SbMath.pageDelta(ch.position.y, th, vp)) } }
                    var lastY = ch.position.y
                    while (true) {
                        val e2 = awaitPointerEvent(PointerEventPass.Initial)
                        val c2 = e2.changes.firstOrNull { it.id == ch.id } ?: break
                        c2.consume()
                        if (!c2.pressed) break
                        if (onThumb) {
                            val mm = metrics(vp)
                            val tt = mm?.let { SbMath.thumb(it.first, it.second, it.third, minThumb) }
                            if (mm != null && tt != null) state.dispatchRawDelta(SbMath.contentDelta(c2.position.y - lastY, mm.second, mm.third, tt.height))
                        }
                        lastY = c2.position.y
                    }
                    active = false
                }
            }
        }
        .drawWithContent {
            drawContent()
            val m = metrics(size.height) ?: return@drawWithContent
            val th = SbMath.thumb(m.first, m.second, m.third, 40.dp.toPx()) ?: return@drawWithContent
            val w = widthDp.dp.toPx()
            val x = size.width - w - 1.dp.toPx()
            drawRoundRect(color.copy(alpha = 0.14f * alpha), Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2))
            drawRoundRect(color.copy(alpha = alpha), Offset(x, th.top), Size(w, th.height), CornerRadius(w / 2))
        }
}

/** Вертикальная прокрутка с полосой справа (вместо verticalScroll(rememberScrollState())). Место под полосу оставляет сам: правый отступ. */
fun Modifier.barScroll(): Modifier = composed {
    val s = rememberScrollState()
    this.verticalScrollbar(s).verticalScroll(s).padding(end = 10.dp)
}

/** Список окна с общей полосой прокрутки. */
@Composable
fun BarLazyColumn(modifier: Modifier = Modifier, verticalArrangement: Arrangement.Vertical = Arrangement.Top, content: LazyListScope.() -> Unit) {
    val st = rememberLazyListState()
    LazyColumn(modifier.verticalScrollbar(st), state = st, contentPadding = PaddingValues(end = SbMath.RESERVE_DP.dp), verticalArrangement = verticalArrangement, content = content)
}
