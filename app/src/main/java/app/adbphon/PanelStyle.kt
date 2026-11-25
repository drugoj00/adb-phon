package app.adbphon

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 21.0 (пожелание wad72 на 4PDA и слово владелицы): цвет нижней панели вкладок (слот 0) и боковой панели кнопок «Файлов» (слот 1).
 * Варианты у каждой: как раньше, как у боковых кнопок (оттенок акцента), чёрная, свой цвет (оттенок и яркость).
 * На тёмной панели текст и значки светлые, на светлой тёмные.
 */
object PanelStyle {
    const val NOW = 0
    const val LIKE_BUTTONS = 1
    const val BLACK = 2
    const val CUSTOM = 3
    var bottom by mutableIntStateOf(NOW)
    var side by mutableIntStateOf(NOW)
    var bottomHue by mutableFloatStateOf(200f)
    var sideHue by mutableFloatStateOf(200f)
    var bottomVal by mutableFloatStateOf(0.30f)
    var sideVal by mutableFloatStateOf(0.30f)
    private var loaded = false
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = sp(ctx)
        bottom = p.getInt("panel_bottom", NOW).coerceIn(0, 3).let { if (it == BLACK) CUSTOM else it }
        side = p.getInt("panel_side", NOW).coerceIn(0, 3).let { if (it == BLACK) CUSTOM else it }
        bottomHue = p.getFloat("panel_bottom_hue", 200f).coerceIn(0f, 360f)
        sideHue = p.getFloat("panel_side_hue", 200f).coerceIn(0f, 360f)
        bottomVal = p.getFloat("panel_bottom_val", 0.30f).coerceIn(0f, 0.97f)
        sideVal = p.getFloat("panel_side_val", 0.30f).coerceIn(0f, 0.97f)
    }

    fun mode(slot: Int) = if (slot == 0) bottom else side
    fun hue(slot: Int) = if (slot == 0) bottomHue else sideHue
    fun bright(slot: Int) = if (slot == 0) bottomVal else sideVal
    fun custom(slot: Int): Color = Color.hsv(hue(slot), 0.5f, bright(slot))

    fun setMode(ctx: Context, slot: Int, v: Int) {
        val m = v.coerceIn(0, 3)
        if (slot == 0) bottom = m else side = m
        sp(ctx).edit().putInt(if (slot == 0) "panel_bottom" else "panel_side", m).apply()
    }

    fun setHue(slot: Int, h: Float) { if (slot == 0) bottomHue = h.coerceIn(0f, 360f) else sideHue = h.coerceIn(0f, 360f) }
    fun saveHue(ctx: Context, slot: Int) { sp(ctx).edit().putFloat(if (slot == 0) "panel_bottom_hue" else "panel_side_hue", hue(slot)).apply() }
    fun setBright(ctx: Context, slot: Int, v: Float) {
        val x = v.coerceIn(0f, 0.97f)
        if (slot == 0) bottomVal = x else sideVal = x
        sp(ctx).edit().putFloat(if (slot == 0) "panel_bottom_val" else "panel_side_val", x).apply()
    }

    /** Цвет фона панели в выбранном режиме; [normal] это прежний цвет. */
    @Composable
    fun bg(slot: Int, normal: Color): Color = when (mode(slot)) {
        LIKE_BUTTONS -> MaterialTheme.colorScheme.primary.copy(alpha = .10f).compositeOver(MaterialTheme.colorScheme.background)
        BLACK -> Color.Black
        CUSTOM -> custom(slot)
        else -> normal
    }

    /** На чёрной и на своего цвета панели цвета темы заменяются так, чтобы подписи и значки читались. */
    @Composable
    fun Themed(slot: Int, content: @Composable () -> Unit) {
        val m = mode(slot)
        if (m != BLACK && m != CUSTOM) { content(); return }
        val base = if (m == BLACK) Color.Black else custom(slot)
        val on = if (base.luminance() > 0.18f) Color(0xFF111111) else Color.White
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                surface = base, onSurface = on, background = base, onBackground = on,
                // акцент на панели подстраивается под фон: на тёмной светлее, на светлой темнее, чтобы выбранная вкладка читалась
                primary = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.primary, if (base.luminance() > 0.18f) Color.Black else Color.White, if (base.luminance() > 0.18f) 0.35f else 0.6f),
                surfaceVariant = base.copy(alpha = 1f), onSurfaceVariant = on.copy(alpha = .8f), outline = on.copy(alpha = .5f),
            ),
            content = content,
        )
    }
}

/** Номер панели, внутри которой рисуются значки (0 нижняя, 1 боковая, -1 никакой). */
val LocalPanelSlot = androidx.compose.runtime.compositionLocalOf { -1 }

/**
 * Значок на панели. При выборе цвета панели (не «По умолчанию») вокруг значка рисуется тонкая контрастная обводка (чёрная у светлого
 * значка, белая у тёмного), чтобы значок не сливался с фоном.
 */
@Composable
fun PanelIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String?, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    val slot = LocalPanelSlot.current
    if (slot < 0 || PanelStyle.mode(slot) == PanelStyle.NOW || tint == Color.Unspecified) {
        Icon(icon, desc, modifier.size(size), tint = tint)
        return
    }
    val outline = if (tint.luminance() > 0.5f) Color.Black else Color.White
    val o = 0.9f
    Box(modifier.size(size)) {
        for ((dx, dy) in listOf(-o to 0f, o to 0f, 0f to -o, 0f to o, -o to -o, o to -o, -o to o, o to o)) {
            Icon(icon, null, Modifier.size(size).offset(dx.dp, dy.dp), tint = outline.copy(alpha = .95f))
        }
        Icon(icon, desc, Modifier.size(size), tint = tint)
    }
}

/** Нижняя панель вкладок: тот же [Surface], цвет по настройке «Цвет панелей». */
@Composable
fun PanelSurface(slot: Int, elevation: Dp = 3.dp, content: @Composable () -> Unit) {
    PanelStyle.Themed(slot) {
        CompositionLocalProvider(LocalPanelSlot provides slot) {
            Surface(color = PanelStyle.bg(slot, MaterialTheme.colorScheme.surface), tonalElevation = if (PanelStyle.mode(slot) == PanelStyle.NOW) elevation else 0.dp, content = content)
        }
    }
}

/** Колонка боковой панели (ландшафт телефона и приставка): тот же фон и обводка значков, что у [PanelBox]. */
@Composable
fun PanelColumn(slot: Int, modifier: Modifier, verticalArrangement: Arrangement.Vertical, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    PanelStyle.Themed(slot) {
        CompositionLocalProvider(LocalPanelSlot provides slot) {
            Column(if (PanelStyle.mode(slot) == PanelStyle.NOW) modifier else modifier.background(PanelStyle.bg(slot, Color.Transparent)), verticalArrangement = verticalArrangement, content = content)
        }
    }
}

/** Боковая панель кнопок: фон колонки по настройке «Цвет панелей». */
@Composable
fun PanelBox(slot: Int, modifier: Modifier, content: @Composable () -> Unit) {
    PanelStyle.Themed(slot) {
        CompositionLocalProvider(LocalPanelSlot provides slot) {
            Box(if (PanelStyle.mode(slot) == PanelStyle.NOW) modifier else modifier.background(PanelStyle.bg(slot, Color.Transparent))) { content() }
        }
    }
}

@Composable
private fun PanelSlotSetting(slot: Int, title: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val m = PanelStyle.mode(slot)
    Text(title, Modifier.padding(top = 4.dp), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    EqChoices(listOf(
        EqChoice(stringResource(R.string.panel_now), m == PanelStyle.NOW) { PanelStyle.setMode(ctx, slot, PanelStyle.NOW) },
        EqChoice(stringResource(R.string.panel_btn), m == PanelStyle.LIKE_BUTTONS) { PanelStyle.setMode(ctx, slot, PanelStyle.LIKE_BUTTONS) },
        EqChoice(stringResource(R.string.panel_custom), m == PanelStyle.CUSTOM) { PanelStyle.setMode(ctx, slot, PanelStyle.CUSTOM) },
    ), perRow = 2)
    if (m == PanelStyle.CUSTOM) {
        HueBarNum(PanelStyle.hue(slot), true, { PanelStyle.setHue(slot, it); PanelStyle.saveHue(ctx, slot) }, { PanelStyle.saveHue(ctx, slot) }, title)
        Text(stringResource(R.string.panel_bright), Modifier.padding(top = 2.dp), fontSize = 13.sp)
        Slider(PanelStyle.bright(slot), { PanelStyle.setBright(ctx, slot, it) }, Modifier.fillMaxWidth(), valueRange = 0f..0.97f)
    }
}

/** Пункт настроек «Цвет панелей». */
@Composable
fun PanelColorsSetting() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HintTitle(stringResource(R.string.panel_title), stringResource(R.string.panel_hint))
        PanelSlotSetting(0, stringResource(R.string.panel_bottom))
        PanelSlotSetting(1, stringResource(R.string.panel_side))
    }
}
