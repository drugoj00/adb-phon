@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package app.adbphon

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Бегущая строка для длинных названий (вместо многоточия) во всей программе.
 * Режим в «Настройки»: «Вкл» (1) = только в строке в фокусе / под мышью / выбранной, «Выкл» (2) = выключено; прежний режим 0 («для всех переполненных») больше не предлагается и читается как 1 (не больше [MAX] одновременно, 15 кадров в секунду).
 * На ТВ и приставках (экран без касания) режим 0 работает как 1. Анимацию не запускаем: пока палец на экране (прокрутка списков), для строк, которые
 * помещаются, и для лишних (сверх лимита). Сдвиг строки меняется только на этапе размещения (без перекомпозиции), шаг раз в ~66 мс.
 */
object Marq {
    const val MAX = 2
    var mode by mutableStateOf(1)
    var tv = false
    var touching by mutableStateOf(false)
    val running = mutableStateListOf<Int>()
    private var seq = 0
    fun nextId() = ++seq
    val effective: Int get() = if (mode == 0 && tv) 1 else mode

    fun init(ctx: Context) {
        // 7.5: два варианта, «Вкл» (бежит строка в фокусе/под мышью/выбранная) и «Выкл»; прежний режим 0 («для всех переполненных») стал «Вкл» (бывший «Только в фокусе»)
        val saved = ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getInt("marq_mode", 1)
        mode = if (saved == 2) 2 else 1
        if (saved == 0) ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE).edit().putInt("marq_mode", 1).apply()
        tv = runCatching { (ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION }.getOrDefault(false) ||
            !ctx.packageManager.hasSystemFeature("android.hardware.touchscreen")
    }

    fun setMode(ctx: Context, m: Int) { mode = m; running.clear(); ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE).edit().putInt("marq_mode", m).apply() }

    fun request(id: Int, priority: Boolean) {
        if (id in running) return
        if (running.size < MAX) running.add(id) else if (priority) { running.removeAt(0); running.add(id) }
    }
    fun release(id: Int) { running.remove(id) }
}

/** Строка (фокус, мышь, выбор) сообщает вложенным текстам, что она «активна» (для режима «только в фокусе»). */
val LocalRowActive = compositionLocalOf { false }
/** Внутри строк «Файлов» длинные названия бегут только у активной строки (выбранной, под мышью, в фокусе), у остальных многоточие. */
val LocalMarqOnlyActive = compositionLocalOf { false }

/**
 * Замена material3.Text для строк в одну линию: переполненный текст бежит (см. [Marq]); остальные случаи без изменений.
 * Подмена по имени: файлы пакета не импортируют material3.Text, а берут эту функцию.
 */
@Composable
fun Text(
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
    if (maxLines != 1 || Marq.effective == 2) {
        androidx.compose.material3.Text(text, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration, textAlign, lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout ?: {}, style)
        return
    }
    val id = remember { Marq.nextId() }
    var over by remember { mutableStateOf(false) }
    val rowActive = LocalRowActive.current
    val onlyActive = LocalMarqOnlyActive.current
    val want = over && !Marq.touching && (rowActive || (Marq.effective == 0 && !onlyActive))
    LaunchedEffect(want, rowActive) { if (want) Marq.request(id, rowActive) else Marq.release(id) }
    DisposableEffect(Unit) { onDispose { Marq.release(id) } }
    val on = want && id in Marq.running
    androidx.compose.material3.Text(
        text,
        modifier.liteMarquee(on),
        color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration, textAlign, lineHeight,
        if (on) TextOverflow.Clip else if (overflow == TextOverflow.Clip) TextOverflow.Ellipsis else overflow,
        if (on) false else softWrap, 1, 1,
        { r -> if (!on && r.hasVisualOverflow != over) over = r.hasVisualOverflow; onTextLayout?.invoke(r) },
        style,
    )
}

@Composable
fun Text(
    text: androidx.compose.ui.text.AnnotatedString,
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
) = androidx.compose.material3.Text(text, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration, textAlign, lineHeight, overflow, softWrap, maxLines, minLines, emptyMap(), onTextLayout ?: {}, style)

// ------------------------------------------------------------------ сворачиваемые пояснения в настройках

/** Растёт при нажатии «Свернуть все»: все раскрытые пояснения на странице закрываются. */
val LocalCollapseKey = compositionLocalOf { 0 }

/** 9.0: счётчик «свернуть все разделы»: растёт, когда пользователь уходит из программы (MainActivity.onStop), поэтому при следующем открытии всё свёрнуто. */
object CollapseAll { var key by mutableStateOf(0); fun bump() { key++ } }

/** Название настройки со стрелкой; по нажатию (или ОК пультом) раскрывается пояснение. Состояние только на время просмотра страницы. */
@Composable
fun Collapsible(title: String, modifier: Modifier = Modifier, bold: Boolean = false, titleContent: (@Composable () -> Unit)? = null, body: @Composable () -> Unit) {
    val key = LocalCollapseKey.current + CollapseAll.key
    var open by remember(key) { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (titleContent != null) titleContent() else androidx.compose.material3.Text(title, Modifier.weight(1f), fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { body() } }
    }
}

/** Переключатель с названием; пояснение раскрывается по нажатию на название. */
@Composable
fun SettingSwitch(checked: Boolean, onChange: (Boolean) -> Unit, title: String, hint: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.material3.Switch(checked, onChange)
        Collapsible(title, Modifier.weight(1f)) {
            androidx.compose.material3.Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        }
    }
}

/**
 * Название пункта с пояснением внутри него (правило владелицы): виден только заголовок, по нажатию на название под ним раскрывается пояснение,
 * повторное нажатие сворачивает. Отдельных строк «Пояснение» нет. «Свернуть все» (LocalCollapseKey) закрывает и эти пояснения.
 */
@Composable
fun HintTitle(
    title: String, hint: String, modifier: Modifier = Modifier,
    titleColor: Color = Color.Unspecified, titleSize: TextUnit = TextUnit.Unspecified, titleWeight: FontWeight? = FontWeight.Medium, titleStyle: TextStyle? = null,
    hintColor: Color = Color.Unspecified, hintSize: TextUnit = 12.sp, hintStyle: TextStyle? = null,
) {
    Collapsible(title, modifier, titleContent = {
        androidx.compose.material3.Text(title, color = titleColor, fontSize = titleSize, fontWeight = titleWeight, style = titleStyle ?: LocalTextStyle.current)
    }) {
        val dim = if (hintColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface.copy(alpha = .6f) else hintColor
        androidx.compose.material3.Text(hint, color = dim, fontSize = if (hintStyle != null) TextUnit.Unspecified else hintSize, style = hintStyle ?: LocalTextStyle.current)
    }
}

/** Кнопка действия, у которой пояснение сворачивается стрелкой справа (правило: пояснение не лежит открытым текстом). */
@Composable
fun ActionWithHint(label: String, hint: String, onClick: () -> Unit) {
    val key = LocalCollapseKey.current + CollapseAll.key
    var open by remember(key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedButton(onClick, Modifier.weight(1f)) { androidx.compose.material3.Text(label) }
            androidx.compose.material3.IconButton({ open = !open }) {
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            }
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            androidx.compose.material3.Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        }
    }
}

private class MarqState { var content = 0; var view = 0 }

/**
 * Лёгкая бегущая строка: после паузы ~1 с строка плавно уходит влево до конца, пауза, возврат в начало. Сдвиг хранится в состоянии, читаемом только
 * при размещении, поэтому перекомпозиции нет; обновление раз в 66 мс (≈15 кадров/с) вместо кадра на каждый vsync.
 */
@Composable
fun Modifier.liteMarquee(on: Boolean): Modifier {
    if (!on) return this
    val st = remember { MarqState() }
    val offset = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val stepPx = with(androidx.compose.ui.platform.LocalDensity.current) { 30.dp.toPx() * 0.066f }
    LaunchedEffect(Unit) {
        while (true) {
            offset.floatValue = 0f
            delay(1000)
            val max = (st.content - st.view).toFloat()
            if (max <= 0f) { delay(700); continue }
            while (offset.floatValue < max) { offset.floatValue = minOf(max, offset.floatValue + stepPx); delay(66) }
            delay(1500)
        }
    }
    return this.clipToBounds().layout { m, c ->
        val p = m.measure(c.copy(minWidth = 0, maxWidth = Constraints.Infinity))
        val w = minOf(p.width, c.maxWidth)
        st.content = p.width; st.view = w
        layout(w, p.height) { p.placeRelative(-offset.floatValue.toInt(), 0) }
    }
}


// ------------------------------------------------------------------ глазик в полях паролей (9.0)

/** Состояние поля пароля: по умолчанию скрыт; значок «глазик» справа в полосе поля переключает показ. */
class PwState { var shown by mutableStateOf(false); val transform: VisualTransformation get() = if (shown) VisualTransformation.None else PasswordVisualTransformation() }

@Composable
fun rememberPw(): PwState = remember { PwState() }

/** Компактный значок «показать/скрыть пароль» цветом интерфейса. */
@Composable
fun PwEyeIcon(shown: Boolean, onToggle: () -> Unit) {
    Icon(if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, androidx.compose.ui.res.stringResource(if (shown) R.string.pw_hide else R.string.pw_show),
        Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable { onToggle() }.padding(5.dp), tint = MaterialTheme.colorScheme.primary)
}
