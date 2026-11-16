package app.adbphon

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.adbphon.NumberSliderRules.NumUnit
import app.adbphon.NumberSliderRules.Spec

/** 10.0 (num): единица у числа на языке программы. */
@Composable
fun numUnit(u: NumUnit): String = when (u) {
    NumUnit.NONE -> ""
    NumUnit.PCT -> "%"
    NumUnit.DEG -> "°"
    NumUnit.DAYS -> stringResource(R.string.num_u_days)
    NumUnit.MB -> stringResource(R.string.num_u_mb)
    NumUnit.MIN -> stringResource(R.string.num_u_min)
    NumUnit.SEC -> stringResource(R.string.num_u_sec)
}

/**
 * 10.0 (num, правило 19): показанное число с единицей в рамке. Нажатие (на ТВ: OK на числе) открывает [NumberInputDialog]
 * с диапазоном [spec], «Применить» и «Отмена»; число вне диапазона не принимается. [shown] заменяет подпись (например, время «1:05»).
 */
@Composable
fun NumberValueTap(value: Int, spec: Spec, label: String, onApply: (Int) -> Unit, modifier: Modifier = Modifier, shown: String? = null, enabled: Boolean = true) {
    val tv = Marq.tv
    var edit by remember { mutableStateOf(false) }
    val unit = numUnit(spec.unit)
    val v = value.coerceIn(spec.min, spec.max)
    val txt = shown ?: NumberInputRules.withUnit(v, unit)
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val prim = MaterialTheme.colorScheme.primary
    val editCd = stringResource(R.string.cnt_edit_cd)
    Box(
        modifier
            .heightIn(min = if (tv) 40.dp else 30.dp)
            .widthIn(min = if (tv) 64.dp else 48.dp)
            .border(if (focused) 2.dp else 1.dp, if (focused) prim else MaterialTheme.colorScheme.outline.copy(alpha = .5f), RoundedCornerShape(8.dp))
            .clickable(interactionSource = src, indication = null, enabled = enabled) { edit = true }
            .semantics { contentDescription = "$editCd: $label $txt" }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) { Text(txt, fontSize = if (tv) 18.sp else 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1, softWrap = false) }
    if (edit) NumberInputDialog(v, spec.range, unit, label, onDismiss = { edit = false }) { n -> edit = false; if (n != v) onApply(n) }
}

/**
 * 10.0 (num, правило 19): ползунок и справа его значение ([NumberValueTap]). Ползунок работает как раньше (в хранимых единицах,
 * диапазон из [spec]), введённое число переводится в хранимое значение и сохраняется через [onValueChange] и [onValueChangeFinished].
 */
@Composable
fun NumberSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    spec: Spec,
    label: String,
    modifier: Modifier = Modifier,
    sliderModifier: Modifier = Modifier,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
    enabled: Boolean = true,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Slider(value, onValueChange, sliderModifier.weight(1f), enabled = enabled, valueRange = spec.sliderRange, steps = steps, onValueChangeFinished = onValueChangeFinished, colors = colors)
        NumberValueTap(NumberSliderRules.toShown(value, spec), spec, label, { n -> onValueChange(NumberSliderRules.toStored(n, spec)); onValueChangeFinished?.invoke() }, enabled = enabled)
    }
}

/** 10.0 (num): полоса оттенка [HueBar] и справа число в градусах; ввод числа = выбор оттенка ([onChange], затем [onDone]). */
@Composable
fun HueBarNum(hue: Float, active: Boolean, onChange: (Float) -> Unit, onDone: () -> Unit, label: String, themeBtn: androidx.compose.ui.focus.FocusRequester? = null, themeBtnEnabled: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HueBar(hue, active, onChange, onDone, Modifier.weight(1f), themeBtn = themeBtn, themeBtnEnabled = themeBtnEnabled)
        NumberValueTap(NumberSliderRules.toShown(hue, NumberSliderRules.HUE), NumberSliderRules.HUE, label, { n -> onChange(n.toFloat()); onDone() })
    }
}

/**
 * 10.0 (num, правило 19): последний вариант «Своё значение…» для [EqChoices] с числами. Выбран, когда [value] не совпадает с [presets];
 * тогда подпись «Своё: 14 дн.». Нажатие открывает окно ввода числа с диапазоном [spec].
 */
@Composable
fun numCustomChoice(value: Int, presets: Collection<Int>, spec: Spec, title: String, onApply: (Int) -> Unit, enabled: Boolean = true): EqChoice {
    var dlg by remember { mutableStateOf(false) }
    val custom = NumberSliderRules.isCustom(value, presets)
    val unit = numUnit(spec.unit)
    val label = if (custom) stringResource(R.string.num_custom_val, NumberInputRules.withUnit(value, unit)) else stringResource(R.string.num_custom)
    if (dlg) NumberInputDialog(NumberSliderRules.startValue(value, spec), spec.range, unit, title, onDismiss = { dlg = false }) { n -> dlg = false; onApply(n) }
    return EqChoice(label, custom) { if (enabled) dlg = true }
}
