package app.adbphon

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 10.0 (cnt, правило 19): общий счётчик «− число +». Кнопки − и + меняют значение на [step] за нажатие (постоянный темп, без ускорения при удержании),
 * на границах [range] гаснут. Нажатие на число (на ТВ: OK на числе) открывает окно ввода цифр [NumberInputDialog].
 * Значение хранит вызывающий код: счётчик только сообщает новое через [onChange].
 *
 * @param label название настройки: заголовок окна ввода; при [labelInRow] ещё и подпись слева в строке.
 * @param unit единица («с», «%», «шт.»): видна у числа и в подсказке диапазона.
 */
@Composable
fun NumberStepper(
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 1,
    unit: String = "",
    label: String = "",
    labelInRow: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val tv = Marq.tv
    var edit by remember { mutableStateOf(false) }
    val fs = when { tv -> 18.sp; compact -> 13.sp; else -> 15.sp }
    val btnFs = when { tv -> 22.sp; compact -> 16.sp; else -> 20.sp }
    val minH = when { tv -> 44.dp; compact -> 32.dp; else -> 36.dp }
    val prim = MaterialTheme.colorScheme.primary
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val v = value.coerceIn(range.first, range.last)
    val shown = NumberInputRules.withUnit(v, unit)
    val editCd = stringResource(R.string.cnt_edit_cd)
    val decCd = stringResource(R.string.cnt_dec_cd)
    val incCd = stringResource(R.string.cnt_inc_cd)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (labelInRow && label.isNotEmpty()) Text(label, Modifier.weight(1f), fontSize = fs)
        TextButton(
            { onChange(NumberInputRules.step(v, -1, step, range.first, range.last)) },
            Modifier.heightIn(min = minH).semantics { contentDescription = decCd },
            enabled = enabled && NumberInputRules.canDec(v, range.first),
        ) { Text("−", fontSize = btnFs, fontWeight = FontWeight.Bold) }
        Box(
            Modifier
                .heightIn(min = minH)
                .widthIn(min = if (compact) 44.dp else 56.dp)
                .border(if (focused) 2.dp else 1.dp, if (focused) prim else MaterialTheme.colorScheme.outline.copy(alpha = .5f), RoundedCornerShape(8.dp))
                .clickable(interactionSource = src, indication = null, enabled = enabled) { edit = true }
                .semantics { contentDescription = "$editCd: $shown" }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) { Text(shown, fontSize = fs, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1, softWrap = false) }
        TextButton(
            { onChange(NumberInputRules.step(v, 1, step, range.first, range.last)) },
            Modifier.heightIn(min = minH).semantics { contentDescription = incCd },
            enabled = enabled && NumberInputRules.canInc(v, range.last),
        ) { Text("+", fontSize = btnFs, fontWeight = FontWeight.Bold) }
    }
    if (edit) NumberInputDialog(v, range, unit, label, onDismiss = { edit = false }) { n -> edit = false; if (n != v) onChange(n) }
}

/**
 * Окно ввода числа: цифровая клавиатура, подсказка диапазона с единицей («от 1 до 300 с»), «Применить» и «Отмена».
 * Число вне диапазона не принимается: подсказка краснеет, «Применить» неактивна. На ТВ клавиатура открывается только по OK в поле (ImeGate).
 */
@Composable
fun NumberInputDialog(value: Int, range: IntRange, unit: String, title: String, onDismiss: () -> Unit, onApply: (Int) -> Unit) {
    val tv = Marq.tv
    val start = value.toString()
    var tf by remember { mutableStateOf(TextFieldValue(start, TextRange(0, start.length))) }
    val fr = remember { FocusRequester() }
    val (lo, hi) = NumberInputRules.rangeArgs(range.first, range.last, unit)
    val hint = stringResource(R.string.cnt_range, lo, hi)
    val bad = NumberInputRules.isError(tf.text, range.first, range.last)
    val ok = NumberInputRules.canApply(tf.text, range.first, range.last)
    val mainFs = if (tv) 20.sp else 16.sp
    val hintFs = if (tv) 18.sp else 13.sp
    fun apply() { (NumberInputRules.check(tf.text, range.first, range.last) as? NumberInputRules.Check.Ok)?.let { onApply(it.value) } }
    LAlertDialog(
        onDismissRequest = onDismiss, ime = true,
        title = { Text(title.ifEmpty { stringResource(R.string.cnt_title) }, fontSize = if (tv) 20.sp else 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    tf, { n -> tf = n.copy(text = NumberInputRules.clean(n.text, range.first, range.last)) },
                    Modifier.fillMaxWidth().focusRequester(fr),
                    singleLine = true, isError = bad,
                    textStyle = TextStyle(fontSize = mainFs, color = MaterialTheme.colorScheme.onSurface),
                    suffix = if (unit.isNotEmpty()) { { Text(unit, fontSize = mainFs) } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ok) apply() }),
                )
                Text(hint, fontSize = hintFs, color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
            }
        },
        confirmButton = { TextButton({ apply() }, enabled = ok) { Text(stringResource(R.string.cnt_apply), fontSize = if (tv) 18.sp else 14.sp) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cnt_cancel), fontSize = if (tv) 18.sp else 14.sp) } },
    )
    // фокус в поле (на ТВ клавиатура всё равно ждёт OK); окно строится чуть позже, поэтому небольшая пауза
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(120); runCatching { fr.requestFocus() } }
}
