package app.adbphon

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 14.0: форма кнопок в одном месте.
 *
 * У Material3 кнопки Button, OutlinedButton, FilledTonalButton, ElevatedButton и TextButton по умолчанию «таблетки» (полный овал, форма не берётся
 * из MaterialTheme.shapes). В программе все они вызываются через обёртки из Haptics.kt (Button, OutlinedButton, TextButton, FilledTonalButton) и этого
 * файла (ElevatedButton): форма по умолчанию там [AppUi.BTN_SHAPE], прямоугольник с малым скруглением [AppUi.BTN_RADIUS]. Радиус правится одним числом.
 *
 * Чтобы обёртки работали, в файлах НЕ должно быть `import androidx.compose.material3.OutlinedButton` (и Button, TextButton, FilledTonalButton,
 * ElevatedButton): явный импорт приоритетнее одноимённой функции этого пакета. Не вызывать их и по полному имени (`androidx.compose.material3.TextButton(...)`).
 * Проверка по исходникам: ButtonStyleTest. Кнопка с явным `shape = ...` остаётся как задана. IconButton, плавающие и круглые кнопки-значки не затрагиваются.
 */
object AppUi {
    /** Радиус скругления кнопок. Было полное скругление (овал). */
    val BTN_RADIUS: Dp = 8.dp

    /** Форма кнопок по умолчанию (подходит и для SegmentedButtonDefaults.itemShape: это CornerBasedShape). */
    val BTN_SHAPE: RoundedCornerShape = RoundedCornerShape(BTN_RADIUS)
}

/** Кнопка-переключатель (правило для всех программ, 25.11.2026): включено залита бирюзой, выключено контурная. */
@Composable
fun StateButton(on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, contentPadding: PaddingValues = ButtonDefaults.ContentPadding, content: @Composable RowScope.() -> Unit) {
    if (on) Button(onClick = onClick, modifier = modifier, contentPadding = contentPadding, content = content)
    else OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = contentPadding, content = content)
}

/** 26.11.2026 (ТОДО 29.10): у включённого переключателя в кружке видна галочка, а не только цвет. */
fun swCheck(on: Boolean): (@Composable () -> Unit)? =
    if (on) ({ androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Check, null, Modifier.size(androidx.compose.material3.SwitchDefaults.IconSize)) }) else null

/** Приподнятая кнопка: то же, что у Material3, но с формой [AppUi.BTN_SHAPE] и откликом на нажатие, как у остальных кнопок (см. Haptics.kt). */
@Composable
fun ElevatedButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = AppUi.BTN_SHAPE,
    colors: ButtonColors = ButtonDefaults.elevatedButtonColors(), elevation: ButtonElevation? = ButtonDefaults.elevatedButtonElevation(),
    border: BorderStroke? = null, contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) {
    val ctx = LocalContext.current
    val fb = LocalFeedbackOn.current
    androidx.compose.material3.ElevatedButton({ if (fb) Feedback.tick(ctx); onClick() }, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
}

/**
 * Ряд кнопок одинаковой высоты. Если подпись одной кнопки переносится на вторую строку, соседние кнопки растягиваются до той же высоты
 * (IntrinsicSize.Min), независимо от того, сколько строк у какой. Кнопкам в ряду задавать `Modifier.weight(1f).fillMaxHeight()`
 * (кнопке с короткой подписью можно `weight(1f, fill = false)`); подпись по центру (`textAlign = TextAlign.Center`).
 *
 * Зона нажатия 48 dp у Material достаётся кнопке невидимым отступом по 4 dp сверху и снизу; здесь он задан явно, а встроенный выключен:
 * иначе растягивание по высоте сделало бы однострочные кнопки 48 dp вместо привычных 40 dp. Касание по-прежнему ловится в зоне 48 dp (Compose сам).
 */
@Composable
fun EqBtnRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(6.dp),
    content: @Composable RowScope.() -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Row(
            modifier.fillMaxWidth().padding(vertical = 4.dp).height(IntrinsicSize.Min),
            horizontalArrangement = horizontalArrangement, verticalAlignment = Alignment.CenterVertically, content = content,
        )
    }
}
