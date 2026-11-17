@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package app.adbphon

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableIntStateOf

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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
    /** Сколько строк бежит одновременно: на ТВ две, на телефоне (названия бегут сами у видимых строк) четыре. */
    val MAX: Int get() = 1
    /** Телефон: код единственной бегущей строки (по ней нажали), -1: ничего не бежит. */
    var activeId by mutableIntStateOf(-1)
    fun stop() { activeId = -1 }
    var mode by mutableStateOf(1)
    var tv = false
    var touching by mutableStateOf(false)
    val running = mutableStateListOf<Int>()
    private var seq = 0
    fun nextId() = ++seq
    val effective: Int get() = if (mode == 0 && tv) 1 else mode

    fun init(ctx: Context) {
        // 7.5: два варианта, «Вкл» (бежит строка в фокусе/под мышью/выбранная) и «Выкл»; прежний режим 0 («для всех переполненных») стал «Вкл» (бывший «Только в фокусе»)
        // 10.5: настройки «Прокрутка» больше нет: названия всегда переносятся, нижняя строка бежит, если не помещается; старое сохранённое значение игнорируется
        mode = 1
        tv = runCatching { (ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION }.getOrDefault(false) ||
            !ctx.packageManager.hasSystemFeature("android.hardware.touchscreen")
    }


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
) = Text(androidx.compose.ui.text.AnnotatedString(text), modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration, textAlign, lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout, style)

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
) {
    // 10.0 (tvfont): в окнах на ТВ мелкий размер поднимается до минимума роли (TvFont.kt); вне окон ТВ размер тот же
    val fontSize = tvFontSize(fontSize, style)
    val lineHeight = tvLineHeight(fontSize, lineHeight, style)
    if (maxLines != 1 || Marq.effective == 2) {
        androidx.compose.material3.Text(text, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration, textAlign, lineHeight, overflow, softWrap, maxLines, minLines, emptyMap(), onTextLayout ?: {}, style)
        return
    }
    val id = remember { Marq.nextId() }
    var over by remember { mutableStateOf(false) }
    val rowActive = LocalRowActive.current
    val onlyActive = LocalMarqOnlyActive.current
    // 10.5: никакого автобега. Телефон: бежит только одна строка, та, по которой нажали (повторное нажатие, нажатие на другую строку, прокрутка и уход из окна останавливают).
    // ТВ: бежит одна строка под фокусом. Строка, которая помещается, ничего не запускает и нажатие отдаёт строке списка.
    val want = if (Marq.tv) over && rowActive && !Marq.touching else over && Marq.activeId == id
    LaunchedEffect(want) { if (Marq.tv) { if (want) Marq.request(id, true) else Marq.release(id) } }
    DisposableEffect(Unit) { onDispose { Marq.release(id); if (Marq.activeId == id) Marq.activeId = -1 } }
    val on = want && (!Marq.tv || id in Marq.running)
    // нажатие по нижней (информационной) строке всегда её, строке списка не передаётся (ничего не раскрывает и не выбирает); бег запускается, если строка не помещается
    val tapMod = if (!Marq.tv && onlyActive) Modifier.pointerInput(id) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val up = waitForUpOrCancellation()
            if (up != null && up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                up.consume()
                if (over) Marq.activeId = if (Marq.activeId == id) -1 else id
            }
        }
    } else Modifier
    androidx.compose.material3.Text(
        text,
        modifier.then(tapMod).liteMarquee(on),
        color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration, textAlign, lineHeight,
        if (on) TextOverflow.Clip else if (overflow == TextOverflow.Clip) TextOverflow.Ellipsis else overflow,
        if (on) false else softWrap, 1, 1,
        emptyMap(),
        { r -> if (!on && r.hasVisualOverflow != over) over = r.hasVisualOverflow; onTextLayout?.invoke(r) },
        style,
    )
}


// ------------------------------------------------------------------ сворачиваемые пояснения в настройках

/** Растёт при нажатии «Свернуть все»: все раскрытые пояснения на странице закрываются. */
val LocalCollapseKey = compositionLocalOf { 0 }

/** 9.0: счётчик «свернуть все разделы»: растёт, когда пользователь уходит из программы (MainActivity.onStop), поэтому при следующем открытии всё свёрнуто. */
object CollapseAll { var key by mutableStateOf(0); fun bump() { key++ } }

/** Название настройки со стрелкой; по нажатию (или ОК пультом) раскрывается пояснение. Состояние только на время просмотра страницы. */
@Composable
fun Collapsible(title: String, modifier: Modifier = Modifier, bold: Boolean = false, titleContent: (@Composable () -> Unit)? = null, body: @Composable () -> Unit) {
    var open by rememberCollapseOpen(title)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (titleContent != null) titleContent() else FloorText(title, Modifier.weight(1f), fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { body() } }
    }
}

/** Переключатель с названием; set2: строка как в «Меню приложений» (название, ⓘ с пояснением, переключатель цветов темы справа), SettingsItems.kt. */
@Composable
fun SettingSwitch(checked: Boolean, onChange: (Boolean) -> Unit, title: String, hint: String) = SetSwitchRow(checked, onChange, title, hint)

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
        FloorText(title, color = titleColor, fontSize = titleSize, fontWeight = titleWeight, style = titleStyle ?: LocalTextStyle.current)
    }) {
        val dim = if (hintColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface.copy(alpha = .6f) else hintColor
        FloorText(hint, color = dim, fontSize = if (hintStyle != null) TextUnit.Unspecified else hintSize, style = hintStyle ?: LocalTextStyle.current)
    }
}

/** Кнопка действия, у которой пояснение сворачивается стрелкой справа (правило: пояснение не лежит открытым текстом). */
@Composable
fun ActionWithHint(label: String, hint: String, onClick: () -> Unit) {
    var open by rememberCollapseOpen(label)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedButton(onClick, Modifier.weight(1f)) { FloorText(label) }
            androidx.compose.material3.IconButton({ open = !open }) {
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            }
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            FloorText(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
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
    val stepPx = with(androidx.compose.ui.platform.LocalDensity.current) { 30.dp.toPx() * 0.09f }
    LaunchedEffect(Unit) {
        while (true) {
            offset.floatValue = 0f
            delay(700)
            val max = (st.content - st.view).toFloat()
            if (max <= 0f) { delay(700); continue }
            while (offset.floatValue < max) { offset.floatValue = minOf(max, offset.floatValue + stepPx); delay(90) }
            delay(1200)
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
