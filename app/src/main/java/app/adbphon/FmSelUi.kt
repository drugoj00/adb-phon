@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.adbphon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import app.adbphon.FloorText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 10.0 (chk): SelBox, SelZone и галочка с зоной нажатия перенесены в BigCheck.kt (одна общая крупная галочка).

/** Крупная кнопка-значок без слов; название читается вслух и показывается по долгому нажатию, у фокуса пульта рамка. */
@Composable
internal fun DropIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, color: Color, h: androidx.compose.ui.unit.Dp, modifier: Modifier, shortLabel: String? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val sh = RoundedCornerShape(16.dp)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(
        modifier.height(h).clip(sh).background(color.copy(alpha = .16f))
            .then(if (focused) Modifier.border(3.dp, color, sh) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(onClick = onClick, onLongClick = { android.widget.Toast.makeText(ctx, name, android.widget.Toast.LENGTH_SHORT).show() })
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(if (Marq.tv) 40.dp else 30.dp), tint = color)
            if (shortLabel != null) Text(shortLabel, fontSize = if (Marq.tv) 13.sp else 11.sp, lineHeight = if (Marq.tv) 16.sp else 13.sp, maxLines = 1, softWrap = false, fontFamily = UiPrefs.listFont, fontWeight = FontWeight.Medium, color = color)
            else if (Marq.tv && focused) Text(name, fontSize = 13.sp, color = color)
        }
    }
}

/** Длинный путь сокращается серединой: начало и конец видны. */
fun shortMiddle(s: String, max: Int): String = if (s.length <= max) s else s.take(max / 3) + "…" + s.takeLast(max - max / 3 - 1)

/** «N элементов» с правильным окончанием на языке приложения. */
fun fmItemsText(n: Int): String {
    val lang = runCatching { Fm.sctx.resources.configuration.locales[0].language }.getOrDefault("ru")
    val word = when (lang) {
        "ru" -> if (n % 10 == 1 && n % 100 != 11) "элемент" else if (n % 10 in 2..4 && n % 100 !in 12..14) "элемента" else "элементов"
        "uk" -> if (n % 10 == 1 && n % 100 != 11) "елемент" else if (n % 10 in 2..4 && n % 100 !in 12..14) "елементи" else "елементів"
        else -> if (n == 1) "item" else "items"
    }
    return "$n $word"
}

/**
 * 10.0 (fm1, владелица: на приставке при крупном «Размере шрифта» слова в шапке над окнами обрезались): содержимое без множителей настроек
 * «Размер шрифта» и «Размер элементов» (плотность и масштаб шрифта как у системы при «Обычный»).
 */
@Composable
internal fun NoAppScale(content: @Composable () -> Unit) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    val ui = AppScale.densMul().takeIf { it > 0f } ?: 1f
    val fo = AppScale.fontMul().takeIf { it > 0f } ?: 1f
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density / ui, d.fontScale / fo), content = content)
}

/**
 * Текст, который не обрезается: шрифт уменьшается от [max] до [min] sp, пока каждое слово помещается в ширину, а весь текст в [lines] строк
 * (перенос только между словами).
 */
@Composable
internal fun FitText(text: androidx.compose.ui.text.AnnotatedString, max: Float, min: Float, color: Color, weight: FontWeight? = null, modifier: Modifier = Modifier, lines: Int = 2) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val dens = androidx.compose.ui.platform.LocalDensity.current
        val availPx = with(dens) { maxWidth.toPx() }
        val avail = with(dens) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val style = androidx.compose.material3.LocalTextStyle.current
        val words = text.text.split(' ').filter { it.isNotEmpty() }
        var fs = max
        fun fits(f: Float): Boolean {
            val st = style.copy(fontSize = f.sp, fontWeight = weight)
            if (words.any { w -> measurer.measure(androidx.compose.ui.text.AnnotatedString(w), st, maxLines = 1, softWrap = false).size.width > availPx }) return false
            val r = measurer.measure(text, st, maxLines = lines, softWrap = true, constraints = androidx.compose.ui.unit.Constraints(maxWidth = avail))
            return !r.hasVisualOverflow
        }
        while (fs > min && !fits(fs)) fs -= 0.5f
        Text(text, Modifier.fillMaxWidth(), fontSize = fs.sp, lineHeight = (fs * 1.15f).sp, fontWeight = weight, color = color, maxLines = lines, softWrap = true,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
    }
}

/**
 * Шапка колонки кнопок на приставке (между окнами): «Левая · Правая» (активная жирнее), «Объектов: N», «Выбрано: M».
 * 10.0 (fm1): не масштабируется настройками «Размер шрифта» и «Размер элементов» (как при «Обычный»), текст 18 sp и мельче только если иначе не влезет.
 */
@Composable
internal fun FmTvHead(p: FmPane) {
    val pc = MaterialTheme.colorScheme.primary
    val left = androidx.compose.ui.res.stringResource(R.string.fm_pane_left)
    val right = androidx.compose.ui.res.stringResource(R.string.fm_pane_right)
    val nSel = if (p.root) FmRootCfg.sel.size else SelTree.count(p.selected)
    val cnt = androidx.compose.ui.res.stringResource(R.string.fm_count_n, Fm.shown(p).size)
    val selT = androidx.compose.ui.res.stringResource(R.string.fm_count_sel, nSel)
    NoAppScale {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val head = androidx.compose.ui.text.buildAnnotatedString {
                fun part(t: String, on: Boolean) { pushStyle(androidx.compose.ui.text.SpanStyle(color = if (on) pc else pc.copy(alpha = .55f), fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)); append(t); pop() }
                part(left, Fm.active == 0); append(" · "); part(right, Fm.active == 1)
            }
            FitText(head, 18f, 10f, pc.copy(alpha = .55f), modifier = Modifier.fillMaxWidth())
            FitText(androidx.compose.ui.text.AnnotatedString(cnt), 18f, 10f, pc, FontWeight.Medium, Modifier.fillMaxWidth())
            FitText(androidx.compose.ui.text.AnnotatedString(selT), 18f, 10f, pc, FontWeight.Medium, Modifier.fillMaxWidth())
        }
    }
}
