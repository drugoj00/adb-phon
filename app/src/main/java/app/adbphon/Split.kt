package app.adbphon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Единый контейнер «две половины» для ландшафта: «остальное» (список, поле текста, подписи) и «управление» (стрелки, окно для пальца,
 * блоки кнопок). Для правши управление справа, для левши зеркально слева. Порядок фокуса следует визуальному порядку (Row).
 * Внутри каждой половины вызывающий сам задаёт Modifier.weight(1f).
 */
@Composable
fun SplitHalves(
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    rest: @Composable RowScope.() -> Unit,
    controls: @Composable RowScope.() -> Unit,
) {
    val right = Cfg.rightHanded(LocalContext.current)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing)) {
        if (right) { rest(); controls() } else { controls(); rest() }
    }
}

/** Настройка «Панели управления»: две кнопки, «Справа» или «Слева». Применяется сразу. */
@Composable
fun HandednessSection() {
    val ctx = LocalContext.current
    var right by remember { mutableStateOf(Cfg.rightHanded(ctx)) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.set_hand), fontWeight = FontWeight.Medium)
        EqChoices(listOf(
            EqChoice(stringResource(R.string.hand_left), !right) { right = false; Cfg.setRightHanded(ctx, false) },
            EqChoice(stringResource(R.string.hand_right), right) { right = true; Cfg.setRightHanded(ctx, true) },
        ))
    }
}

/**
 * «Вписать по высоте»: содержимое правой (управляющей) половины в ландшафте измеряется в натуральную высоту и, если не помещается,
 * равномерно уменьшается (масштаб не ниже [minScale]) так, чтобы ничего не обрезалось и не прокручивалось. Нажатия работают в масштабе
 * (graphicsLayer учитывается при поиске цели касания). Внутри нельзя использовать вертикальную прокрутку и weight по высоте.
 */
@Composable
fun FitHeight(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(10.dp),
    minScale: Float = 0.55f,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.ui.layout.SubcomposeLayout(modifier) { constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        fun measureAt(id: Int, width: Int): androidx.compose.ui.layout.Placeable {
            val m = subcompose(id) { Column(Modifier.fillMaxWidth(), verticalArrangement = verticalArrangement, content = content) }.first()
            return m.measure(androidx.compose.ui.unit.Constraints(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
        }
        val p1 = measureAt(0, w)
        val bounded = constraints.hasBoundedHeight && h > 0
        if (!bounded || p1.height <= h) {
            // помещается: занимаем всю доступную высоту и прижимаем содержимое к верху (без центрирования)
            layout(w, if (bounded) h else p1.height) { p1.place(0, 0) }
        } else {
            var s = (h.toFloat() / p1.height).coerceAtLeast(minScale)
            val p2 = measureAt(1, (w / s).toInt().coerceAtLeast(1))
            if (p2.height * s > h) s = (h.toFloat() / p2.height).coerceAtLeast(minScale)
            layout(w, h) {
                p2.placeWithLayer(0, 0) {
                    scaleX = s; scaleY = s
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                }
            }
        }
    }
}
