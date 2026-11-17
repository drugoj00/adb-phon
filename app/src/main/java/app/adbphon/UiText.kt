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
 * 11.0: бегущих строк в программе больше нет (решение владелицы): длинный текст переносится или обрезается многоточием.
 * Объект остался только ради признака [tv] (ТВ и приставки: UiMode TELEVISION или нет сенсорного экрана), который используют другие файлы.
 */
object Marq {
    var tv = false

    fun init(ctx: Context) {
        tv = runCatching { (ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION }.getOrDefault(false) ||
            !ctx.packageManager.hasSystemFeature("android.hardware.touchscreen")
    }
}

/**
 * Замена material3.Text (подмена по имени, чтобы TvFont поднимал мелкий размер на ТВ); бегущих строк нет.
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
    androidx.compose.material3.Text(
        text, modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration, textAlign, lineHeight,
        if (maxLines == 1 && overflow == TextOverflow.Clip) TextOverflow.Ellipsis else overflow, softWrap, maxLines, minLines,
        emptyMap(), onTextLayout ?: {}, style,
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
