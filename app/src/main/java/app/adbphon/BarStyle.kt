package app.adbphon

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged

/**
 * Полоски (7.5). «Контраст полосок»: насколько свободная часть отличается от занятой: при «слабо» вид как до ползунка (прежний бирюзовый цвет с прозрачностью),
 * при «сильно» свободная часть полностью прозрачная; вокруг всей полоски всегда тонкая обводка (см. [barFrame]), чтобы границы были видны.
 * «Цвет полосок»: цвет занятой части на всех полосках сразу (память, карта, root, облака, ход работы и установки): «Как у темы» (по умолчанию)
 * или свой оттенок (полоса спектра [HueBar]). Одинаково в светлой и тёмной темах.
 */
object BarStyle {
    const val DEFAULT = 60
    /** Не выбран цвет: занятая часть цвета темы. */
    const val THEME = -1
    /** Свой оттенок (ползунок [hue]). */
    const val CUSTOM = 100
    /** 0 (слабо) ... 100 (сильно). */
    var contrast by mutableIntStateOf(DEFAULT)
    /** [THEME], индекс цвета палитры или [CUSTOM]. */
    var colorIdx by mutableIntStateOf(THEME)
    /** Оттенок своего цвета, 0..360. */
    var hue by mutableFloatStateOf(200f)
    private var loaded = false
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        contrast = sp(ctx).getInt("bar_contrast", DEFAULT).coerceIn(0, 100)
        colorIdx = sp(ctx).getInt("bar_color", THEME).let { if (it == CUSTOM) CUSTOM else THEME }
        hue = sp(ctx).getFloat("bar_hue", 200f).coerceIn(0f, 360f)
    }

    fun set(ctx: Context, v: Int) { contrast = v.coerceIn(0, 100); sp(ctx).edit().putInt("bar_contrast", contrast).apply() }
    fun setColor(ctx: Context, i: Int) { colorIdx = i; sp(ctx).edit().putInt("bar_color", i).apply() }
    fun setHue(ctx: Context, h: Float) { hue = h.coerceIn(0f, 360f); sp(ctx).edit().putFloat("bar_hue", hue).apply() }

    /** Доля прежнего вида свободной части, которая остаётся: 1 при «слабо», 0 при «сильно» (полностью прозрачная). */
    private val keep: Float get() = 1f - contrast / 100f

    @Composable
    private fun isDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

    /** Цвет занятой части: выбранный в «Цвет полосок» или [default] (цвет темы), если выбрано «Как у темы». */
    @Composable
    fun used(default: Color = MaterialTheme.colorScheme.primary): Color {
        val dark = isDark()
        return when {
            colorIdx == CUSTOM -> Color.hsv(hue, 0.65f, if (dark) 0.95f else 0.62f)
            else -> default
        }
    }

    /** Свободная часть полоски памяти/карты/root: при минимуме прежний вид (бирюзовый, прозрачность 0.4), при максимуме прозрачная; при выбранном цвете берётся он. */
    @Composable
    fun freeSpace(): Color = (if (colorIdx == THEME) Color(0xFF5EEAD4) else used()).copy(alpha = 0.4f * keep)

    /** Свободная часть полоски хода работы: при минимуме прежний вид (прозрачность 0.2), при максимуме прозрачная. */
    @Composable
    fun freeProgress(): Color = (if (colorIdx == THEME) Color(0xFF0F766E) else used()).copy(alpha = 0.2f * keep)

    /** Совместимость: свободная часть полоски хода работы. */
    @Composable
    fun free(@Suppress("UNUSED_PARAMETER") usedColor: Color): Color = freeProgress()
}

/** Тонкая обводка по всему контуру полоски (весь прямоугольник со скруглениями, независимо от заполнения): границы видны и при прозрачной свободной части. */
fun Modifier.barFrame(color: Color, shape: androidx.compose.ui.graphics.Shape, width: androidx.compose.ui.unit.Dp = 0.8.dp): Modifier =
    this.border(width, color.copy(alpha = 0.75f), shape)

/** Полоска хода работы в цветах темы с настраиваемым контрастом и цветом (вместо стандартной бледной). */
@Composable
fun ThemedProgress(progress: () -> Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val c = BarStyle.used(color)
    LinearProgressIndicator(progress = progress, modifier = modifier.barFrame(c, androidx.compose.foundation.shape.RoundedCornerShape(50)), color = c, trackColor = BarStyle.freeProgress())
}

/** Цвет по оттенку для полосок и основного цвета: насыщенный и тёмный в светлой теме, светлее в тёмной. */
fun hueColor(hue: Float, dark: Boolean): Color = if (dark) Color.hsv(hue, 0.55f, 0.95f) else Color.hsv(hue, 0.75f, 0.55f)

/**
 * Одна полоса выбора цвета (спектр оттенков) с ползунком-кружком. Касание и перетаскивание меняют оттенок, на пульте стрелки влево/вправо.
 * [onChange] вызывается при каждом сдвиге, [onDone] когда палец отпущен (сохранение).
 */
@Composable
fun HueBar(hue: Float, active: Boolean, onChange: (Float) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier, themeBtn: androidx.compose.ui.focus.FocusRequester? = null, themeBtnEnabled: Boolean = false) {
    var w by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var foc by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val stops = androidx.compose.runtime.remember { (0..12).map { Color.hsv(it * 30f, 0.8f, 0.9f) } }
    val ringColor = MaterialTheme.colorScheme.onSurface
    val prim = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.layout.Box(
        modifier.androidx_height(30.dp)
            .androidx_onSize { w = it.width.toFloat().coerceAtLeast(1f) }
            .androidx_clip(androidx.compose.foundation.shape.RoundedCornerShape(15.dp))
            // стрелки пульта: клавиши обрабатывает внешний узел, фокус берёт внутренний (порядок важен)
            .androidx_hueKeys(onChange = { d -> onChange((hue + d).coerceIn(0f, 360f)); onDone() }, onUp = { if (themeBtn != null && themeBtnEnabled) { runCatching { themeBtn.requestFocus() }.isSuccess } else false })
            .onFocusChanged { foc = it.isFocused }
            .focusable()
            .androidx_hueInput(onChange = { x -> onChange((x / w * 360f).coerceIn(0f, 360f)) }, onDone = onDone)
            .androidx_draw {
                drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(stops))
                val x = (hue / 360f * size.width).coerceIn(14.dp.toPx(), size.width - 14.dp.toPx())
                drawCircle(Color.White, 13.dp.toPx(), androidx.compose.ui.geometry.Offset(x, size.height / 2))
                drawCircle(if (active) Color.hsv(hue, 0.8f, 0.9f) else ringColor.copy(alpha = .35f), 10.dp.toPx(), androidx.compose.ui.geometry.Offset(x, size.height / 2))
                if (foc) drawRoundRect(prim, cornerRadius = androidx.compose.ui.geometry.CornerRadius(15.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
            },
    ) {}
}

private fun Modifier.androidx_height(h: androidx.compose.ui.unit.Dp) = this.then(androidx.compose.ui.Modifier.height(h).fillMaxWidth())
private fun Modifier.androidx_onSize(f: (androidx.compose.ui.unit.IntSize) -> Unit) = this.onSizeChanged(f)
private fun Modifier.androidx_clip(s: androidx.compose.ui.graphics.Shape) = this.clip(s)
private fun Modifier.androidx_draw(f: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) = this.drawBehind(f)
private fun Modifier.androidx_hueInput(onChange: (Float) -> Unit, onDone: () -> Unit): Modifier =
    this.pointerInput(Unit) { detectTapGestures { o -> onChange(o.x); onDone() } }
        .pointerInput(Unit) { detectHorizontalDragGestures(onDragEnd = onDone, onDragCancel = onDone) { c, _ -> onChange(c.position.x) } }
private fun Modifier.androidx_hueKeys(onChange: (Float) -> Unit, onUp: () -> Boolean = { false }): Modifier = this.onKeyEvent { e ->
    val nk = e.nativeKeyEvent
    if (nk.action != android.view.KeyEvent.ACTION_DOWN) false
    else when (nk.keyCode) {
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { onChange(-6f); true }
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { onChange(6f); true }
        // 11.11.2026: стрелка вверх с полосы ведёт к кнопке «Как у темы» (без этого фокус перескакивал на широкую кнопку выше)
        android.view.KeyEvent.KEYCODE_DPAD_UP -> onUp()
        else -> false
    }
}
