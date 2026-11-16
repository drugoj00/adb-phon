package app.adbphon

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 10.0 (tvfont): крупный текст во всех окнах на ТВ и приставках (признак [Marq.tv]: UiMode TELEVISION или нет сенсорного экрана).
 * На телефоне (в том числе набок) и магнитоле ничего не меняется: всё ниже включается только при Marq.tv.
 *
 * Центральная точка: [TvFontScope]. Она стоит в LangScope (все LAlertDialog, LDialog, LDropdownMenu, LangScopePublic) и в «Настройках».
 * Внутри неё на ТВ: масштаб шрифта окна = «Размер шрифта» (окна Compose сбрасывают масштаб программы на системный), основной стиль текста
 * не мельче 18 sp, а обёртки Text (UiText.kt) и [FloorText] поднимают мелкие заданные размеры до минимума роли (см. [TvFontRules]).
 */
object TvFont {
    /** Давать окнам (диалогам, меню) настройку «Размер шрифта» и на телефоне. Выключено: правило 17 (телефон и магнитола без изменений); включить одной строкой. */
    const val PHONES_DIALOG_FONT = false
}

/** Множитель шрифта окна на ТВ; 0: подъём мелкого текста выключен (телефон, основной экран). */
val LocalTvFont = compositionLocalOf { 0f }

/** Область окна: на ТВ крупный шрифт и минимум размеров (см. [TvFont]). Повторное вложение ничего не меняет. */
@Composable
fun TvFontScope(content: @Composable () -> Unit) {
    val tv = Marq.tv
    if (!tv && !TvFont.PHONES_DIALOG_FONT) { content(); return }
    val d = LocalDensity.current
    val sys = LocalConfiguration.current.fontScale
    val fs = TvFontRules.fontScale(sys, d.fontScale, AppScale.font, tv, TvFont.PHONES_DIALOG_FONT)
    val dens = if (fs == d.fontScale) d else Density(d.density, fs)
    if (!tv) { CompositionLocalProvider(LocalDensity provides dens) { content() }; return }
    val mult = if (sys > 0f) fs / sys else 1f
    val st = LocalTextStyle.current
    CompositionLocalProvider(LocalDensity provides dens, LocalTvFont provides mult, LocalTextStyle provides tvStyle(st, mult)) { content() }
}

private fun spOrNaN(u: TextUnit): Float = if (u.isSp) u.value else Float.NaN

/** Стиль с поднятым до минимума размером (для полей ввода и текста без явного размера). */
internal fun tvStyle(st: TextStyle, mult: Float): TextStyle {
    val req = spOrNaN(st.fontSize)
    val size = TvFontRules.sizeSp(req, mult)
    if (size == req) return st
    val lh = TvFontRules.lineSp(size, spOrNaN(st.lineHeight))
    return st.copy(fontSize = size.sp, lineHeight = if (lh.isNaN()) st.lineHeight else lh.sp)
}

/** Размер для Text в окне на ТВ: заданный (или из стиля), поднятый до минимума роли; вне окон ТВ без изменений. */
@Composable
@ReadOnlyComposable
internal fun tvFontSize(fontSize: TextUnit, style: TextStyle): TextUnit {
    val mult = LocalTvFont.current
    if (mult <= 0f) return fontSize
    val req = if (fontSize.isSp) fontSize.value else spOrNaN(style.fontSize)
    val size = TvFontRules.sizeSp(req, mult)
    return if (size == req) fontSize else size.sp
}

/** Межстрочный интервал под новый размер [size] (если заданный стал тесен). */
@Composable
@ReadOnlyComposable
internal fun tvLineHeight(size: TextUnit, lineHeight: TextUnit, style: TextStyle): TextUnit {
    if (LocalTvFont.current <= 0f) return lineHeight
    val s = if (size.isSp) size.value else spOrNaN(style.fontSize)
    if (s.isNaN()) return lineHeight
    val lh = if (lineHeight.isSp) lineHeight.value else spOrNaN(style.lineHeight)
    val out = TvFontRules.lineSp(s, lh)
    return if (out.isNaN() || out == lh) lineHeight else out.sp
}

/**
 * material3.Text с минимумом размера на ТВ и без других отличий (бегущей строки нет). Файлы, где раньше был import androidx.compose.material3.Text,
 * берут её как `import app.adbphon.FloorText as Text`; на телефоне поведение то же самое.
 */
@Composable
fun FloorText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    val fs = tvFontSize(fontSize, style)
    val lh = tvLineHeight(fs, lineHeight, style)
    androidx.compose.material3.Text(text, modifier, color, fs, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration, textAlign, lh, overflow, softWrap, maxLines, minLines, onTextLayout ?: {}, style)
}

@Composable
fun FloorText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    val fs = tvFontSize(fontSize, style)
    val lh = tvLineHeight(fs, lineHeight, style)
    androidx.compose.material3.Text(text, modifier, color, fs, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration, textAlign, lh, overflow, softWrap, maxLines, minLines, inlineContent, onTextLayout ?: {}, style)
}

/** Наименьший масштаб, до которого [tvFit] ужимает окно; ниже: не ужимаем (лучше обрезать край, чем сделать текст снова мелким). */
private const val FIT_MIN = 0.7f

/**
 * Страховка окон без прокрутки на ТВ: если после увеличения шрифта содержимое выше места, оно целиком уменьшается (как картинка) ровно настолько,
 * чтобы поместилось, и ничего не обрезается. Вне ТВ и когда всё помещается: обычное размещение без изменений.
 * Только для содержимого без прокрутки и без ленивых списков (высота считается через intrinsic-измерение).
 */
fun Modifier.tvFit(): Modifier = if (!Marq.tv) this else this.layout { m, c ->
    if (!c.hasBoundedHeight || !c.hasBoundedWidth) {
        val p = m.measure(c)
        return@layout layout(p.width, p.height) { p.place(0, 0) }
    }
    val h0 = m.maxIntrinsicHeight(c.maxWidth)
    if (h0 <= c.maxHeight) {
        val p = m.measure(c)
        return@layout layout(p.width, p.height) { p.place(0, 0) }
    }
    // шире в 1/s раз, затем уменьшить в s раз: ширина та же, высота помещается; второе приближение учитывает перенос строк на большей ширине
    var s = (c.maxHeight.toFloat() / h0).coerceIn(FIT_MIN, 1f)
    val h1 = m.maxIntrinsicHeight((c.maxWidth / s).roundToInt())
    s = min(1f, c.maxHeight.toFloat() / h1).coerceIn(FIT_MIN, 1f)
    val w = (c.maxWidth / s).roundToInt()
    val p = m.measure(Constraints(minWidth = (c.minWidth / s).roundToInt().coerceAtMost(w), maxWidth = w, minHeight = 0, maxHeight = Constraints.Infinity))
    val outW = (p.width * s).roundToInt().coerceIn(c.minWidth, c.maxWidth)
    val outH = (p.height * s).roundToInt().coerceIn(c.minHeight, c.maxHeight)
    layout(outW, outH) {
        p.placeWithLayer(0, 0) { scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0f, 0f) }
    }
}
