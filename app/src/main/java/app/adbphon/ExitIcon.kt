package app.adbphon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/**
 * Значок «Выход»: красный скруглённый квадрат (#D32F2F) с белой пиктограммой аварийного выхода (как таблички ISO 7010 E001):
 * человечек БЕЖИТ вправо (корпус наклонён вперёд, рука вперёд-вверх и рука назад, шаг широкий, задняя нога согнута), справа ОТКРЫТАЯ дверь (проём с тёмной пустотой и распахнутой белой створкой), Рисуется на холсте в сетке 100×100; на светлой и тёмной теме читается одинаково.
 */
@Composable
fun ExitSignIcon(size: Dp, modifier: Modifier = Modifier, color: Color = Color(0xFFD32F2F), focused: Boolean = false) {
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 100f
        val white = Color.White
        val sw = 6.6f * k
        val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun path(vararg p: Pair<Float, Float>) = Path().apply { p.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * k, y * k) else lineTo(x * k, y * k) } }
        drawRoundRect(color, Offset.Zero, Size(this.size.minDimension, this.size.minDimension), CornerRadius(19f * k))
        // открытая дверь справа: проём с тёмной пустотой, створка распахнута (петли у правого косяка, в перспективе: свободный край ближе и короче)
        val st2 = Stroke(width = 4.4f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRect(Color(0xFF6E1414), Offset(64f * k, 14f * k), Size(28f * k, 74f * k))
        drawPath(Path().apply { moveTo(89.8f * k, 16.2f * k); lineTo(76f * k, 25f * k); lineTo(76f * k, 77f * k); lineTo(89.8f * k, 85.8f * k); close() }, white)
        drawCircle(Color(0xFF6E1414), radius = 1.2f * k, center = Offset(78.4f * k, 51f * k))
        drawPath(path(64f to 88f, 64f to 14f, 92f to 14f, 92f to 88f), white, style = st2)   // косяк
        drawPath(path(62f to 88f, 94f to 88f), white, style = st2)                           // порог
        // бегущий человек
        drawCircle(white, radius = 7.2f * k, center = Offset(45f * k, 27f * k))
        drawPath(path(41f to 37f, 31f to 60f), white, style = st)                          // корпус с наклоном вперёд
        drawPath(path(40f to 40f, 51f to 46f, 60f to 38f), white, style = st)             // рука вперёд-вверх
        drawPath(path(39f to 40f, 29f to 45f, 21f to 55f), white, style = st)             // рука назад
        drawPath(path(31f to 60f, 47f to 67f, 52f to 83f), white, style = st)             // нога вперёд
        drawPath(path(31f to 60f, 22f to 76f, 9f to 72f), white, style = st)              // нога назад, согнута
    }
}
