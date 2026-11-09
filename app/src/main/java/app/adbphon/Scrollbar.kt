package app.adbphon

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Какие полосы сейчас держит пульт (опорная точка ScrollbarHandle): ползунок такой полосы подсвечивается. */
object SbFocus { val focused = androidx.compose.runtime.mutableStateMapOf<Any, Boolean>() }

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
fun Modifier.verticalScrollbar(state: ScrollState, left: Boolean = false): Modifier =
    sbar(state, left) { vp -> Triple(state.value.toFloat(), vp, vp + state.maxValue) }

fun Modifier.verticalScrollbar(state: LazyListState, left: Boolean = false): Modifier =
    sbar(state, left) { _ ->
        val info = state.layoutInfo
        val vis = info.visibleItemsInfo
        if (vis.isEmpty() || info.totalItemsCount == 0) null
        else {
            val avg = vis.sumOf { it.size }.toFloat() / vis.size
            Triple(state.firstVisibleItemIndex * avg + state.firstVisibleItemScrollOffset, info.viewportSize.height.toFloat(), avg * info.totalItemsCount)
        }
    }

fun Modifier.verticalScrollbar(state: LazyGridState, left: Boolean = false): Modifier =
    sbar(state, left) { _ ->
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

private fun Modifier.sbar(state: ScrollableState, leftIgnored: Boolean, metrics: (Float) -> Triple<Float, Float, Float>?): Modifier = composed {
    val left = scrollbarLeft()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var edgeFocus by remember { mutableStateOf(false) }
    val barFocus = edgeFocus || SbFocus.focused[state] == true
    val color = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf(false) }
    val widthDp by animateFloatAsState(if (barFocus) 16f else if (active) 8f else 6f, label = "sbW")
    val alpha by animateFloatAsState(if (active || barFocus) 1f else 0.7f, label = "sbA")
    val step = with(density) { 220.dp.toPx() }
    this
        // пульт: стрелка в сторону полосы, когда дальше двигать фокус некуда, «берёт» полосу; дальше стрелки вверх/вниз прокручивают, влево/вправо/ОК/Назад отпускают
        .onPreviewKeyEvent { e ->
            if (!edgeFocus) return@onPreviewKeyEvent false
            when (e.key) {
                androidx.compose.ui.input.key.Key.DirectionUp, androidx.compose.ui.input.key.Key.PageUp -> { if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) scope.launch { runCatching { state.animateScrollBy(-step) } }; true }
                androidx.compose.ui.input.key.Key.DirectionDown, androidx.compose.ui.input.key.Key.PageDown -> { if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) scope.launch { runCatching { state.animateScrollBy(step) } }; true }
                androidx.compose.ui.input.key.Key.DirectionLeft, androidx.compose.ui.input.key.Key.DirectionRight, androidx.compose.ui.input.key.Key.DirectionCenter, androidx.compose.ui.input.key.Key.Enter, androidx.compose.ui.input.key.Key.Back, androidx.compose.ui.input.key.Key.Escape -> { if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) edgeFocus = false; true }
                else -> { edgeFocus = false; false }
            }
        }
        .onKeyEvent { e ->
            if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onKeyEvent false
            val edge = if (left) androidx.compose.ui.input.key.Key.DirectionLeft else androidx.compose.ui.input.key.Key.DirectionRight
            when (e.key) {
                edge -> {
                    val dir = if (left) androidx.compose.ui.focus.FocusDirection.Left else androidx.compose.ui.focus.FocusDirection.Right
                    if (!focusManager.moveFocus(dir) && metrics(1000f)?.let { SbMath.thumb(it.first, it.second, it.third, 1f) } != null) edgeFocus = true
                    true
                }
                androidx.compose.ui.input.key.Key.PageUp -> { scope.launch { runCatching { state.animateScrollBy(-step * 3) } }; true }
                androidx.compose.ui.input.key.Key.PageDown -> { scope.launch { runCatching { state.animateScrollBy(step * 3) } }; true }
                else -> false
            }
        }
        .pointerInput(state) {
            val zone = SbMath.ZONE_DP.dp.toPx()
            val minThumb = 40.dp.toPx()
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val ch = ev.changes.firstOrNull() ?: continue
                    val vp = size.height.toFloat()
                    val th = metrics(vp)?.let { SbMath.thumb(it.first, it.second, it.third, minThumb) }
                    val inZone = th != null && (if (left) ch.position.x <= zone else ch.position.x >= size.width - zone)
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
            val x = if (left) 1.dp.toPx() else size.width - w - 1.dp.toPx()
            drawRoundRect(color.copy(alpha = 0.14f * alpha), Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2))
            drawRoundRect(color.copy(alpha = alpha), Offset(x, th.top), Size(w, th.height), CornerRadius(w / 2))
            // пульт держит полосу: рамка только вокруг ползунка
            if (barFocus) drawRoundRect(color, Offset(x - 3.dp.toPx(), th.top - 3.dp.toPx()), Size(w + 6.dp.toPx(), th.height + 6.dp.toPx()), CornerRadius(w / 2 + 3.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
        }
}

/**
 * Опорная точка для пульта у полосы прокрутки: узкая полоса у края списка, на которую можно перевести фокус стрелкой (вправо у правши, влево у левши).
 * Пока на ней фокус, стрелки вверх и вниз прокручивают список, влево и вправо уводят фокус дальше. Подсвечивается, когда на ней фокус.
 */
@Composable
fun ScrollbarHandle(state: ScrollableState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val step = with(density) { 220.dp.toPx() }
    val color = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.layout.Box(
        modifier.width(16.dp).fillMaxHeight()
            .onFocusChanged { SbFocus.focused[state] = it.isFocused }
            .onKeyEvent { e ->
                val down = e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown
                when (e.key) {
                    androidx.compose.ui.input.key.Key.DirectionUp, androidx.compose.ui.input.key.Key.PageUp -> { if (down) scope.launch { runCatching { state.animateScrollBy(-step) } }; true }
                    androidx.compose.ui.input.key.Key.DirectionDown, androidx.compose.ui.input.key.Key.PageDown -> { if (down) scope.launch { runCatching { state.animateScrollBy(step) } }; true }
                    else -> false
                }
            }
            .focusable(),
    )
}

/** Отступ под полосу со стороны ведущей руки для списков с contentPadding. */
@Composable
fun sbPadding(): PaddingValues { val left = scrollbarLeft(); return PaddingValues(start = if (left) SbMath.RESERVE_DP.dp else 0.dp, end = if (left) 0.dp else SbMath.RESERVE_DP.dp) }

/** Полоса прокрутки стоит со стороны ведущей руки: правша слева направо (полоса справа), левша слева. */
@Composable
fun scrollbarLeft(): Boolean = !Marq.tv && !Cfg.rightHanded(androidx.compose.ui.platform.LocalContext.current)   // на приставках сторона не меняется: всегда справа

/** Вертикальная прокрутка с полосой со стороны ведущей руки (вместо verticalScroll(rememberScrollState())). Место под полосу оставляет сам. */
fun Modifier.barScroll(): Modifier = composed {
    val s = rememberScrollState()
    val left = scrollbarLeft()
    this.verticalScrollbar(s, left).verticalScroll(s).padding(start = if (left) 10.dp else 0.dp, end = if (left) 0.dp else 10.dp)
}

/** То же для вкладок с запоминаемой прокруткой (rememberSavedScroll). */
fun Modifier.tabScroll(state: ScrollState): Modifier = composed {
    val left = scrollbarLeft()
    this.verticalScrollbar(state, left).verticalScroll(state).padding(start = if (left) 10.dp else 0.dp, end = if (left) 0.dp else 10.dp)
}

/** Список окна с общей полосой прокрутки. */
@Composable
fun BarLazyColumn(modifier: Modifier = Modifier, verticalArrangement: Arrangement.Vertical = Arrangement.Top, content: LazyListScope.() -> Unit) {
    val st = rememberLazyListState()
    val left = scrollbarLeft()
    LazyColumn(modifier.verticalScrollbar(st, left), state = st, contentPadding = PaddingValues(start = if (left) SbMath.RESERVE_DP.dp else 0.dp, end = if (left) 0.dp else SbMath.RESERVE_DP.dp), verticalArrangement = verticalArrangement, content = content)
}


/** Вкладка с вертикальной прокруткой, полосой со стороны ведущей руки и опорной точкой для пульта (стрелкой на полосу, дальше вверх и вниз). */
@Composable
fun TabColumn(key: String, modifier: Modifier = Modifier.fillMaxSize(), contentPadding: androidx.compose.ui.unit.Dp = 12.dp,
              verticalArrangement: Arrangement.Vertical = Arrangement.Top, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val st = rememberSavedScroll(key)
    val left = scrollbarLeft()
    androidx.compose.foundation.layout.Box(modifier) {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().tabScroll(st).padding(contentPadding), verticalArrangement = verticalArrangement, content = content)
        ScrollbarHandle(st, Modifier.align(if (left) androidx.compose.ui.Alignment.CenterStart else androidx.compose.ui.Alignment.CenterEnd))
    }
}
