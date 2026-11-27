@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.adbphon

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp

/**
 * 10.0 (chk, владелица: «в телефоне да и на приставке попасть в квадратик, чтобы отметить галочкой, трудно, сделай крупнее»).
 * ЕДИНАЯ крупная галочка программы. Все места с отметкой галочкой берут её отсюда:
 *  - [SelBox]: сам квадратик (телефон 26 dp, приставка 32 dp), [partial]: внутри папки отмечена часть;
 *  - [BoxScope.SelZone]: зона нажатия поверх правой части строки (вся высота строки, ширина 64 dp, на приставке 76) для строк «Файлов» и «домика»;
 *  - [BigCheck]: квадратик со своей зоной нажатия 48×48 dp (приставка 56) и рамкой фокуса пульта;
 *  - [BigCheckbox]: замена material3 Checkbox с той же подписью вызова (checked, onCheckedChange, modifier);
 *  - [CheckRow]: строка списка с отметкой: нажатие на всю строку отмечает, квадратик справа.
 */

/** Размер квадратика: телефон 26 dp, приставка 32 dp. */
internal fun selBoxSize(tv: Boolean) = if (tv) 29.dp else 23.5.dp

/** 14.0: нажатие без узла семантики, без фокуса и без ряби: для служебных значков внутри строки (стрелка раскрытия, точка «свойства»). Строка целиком и зона галочки остаются доступными. */
@Composable
internal fun Modifier.tapOnly(onTap: () -> Unit): Modifier {
    val cur = androidx.compose.runtime.rememberUpdatedState(onTap)
    return this.pointerInput(Unit) { detectTapGestures { cur.value() } }
}

/** Цвет квадратика родительской папки (внутри неё отмечено). */
internal const val CHECK_PARENT = 0xFFFF9800.toInt()

/** Доля скругления углов квадратика: слегка, чтобы не резало глаз. */
internal const val CHECK_ROUND = 0.2f

/**
 * Квадратик отметки, без галочки (26.11.2026, слово владелицы): отмеченный закрашен, рамка при этом меняется на контрастную.
 * [parent]: раскрытая отмеченная папка (оранжевый). [partial]: сама папка не отмечена, отмечено что-то внутри (оранжевый квадратик в середине).
 */
@Composable
internal fun SelBox(on: Boolean, partial: Boolean = false, tv: Boolean = Marq.tv, enabled: Boolean = true, parent: Boolean = false) {
    val sz = selBoxSize(tv)
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(sz * CHECK_ROUND)
    val fill = if (parent) Color(CHECK_PARENT) else if (UiPrefs.checkMode == 1) Color(UiPrefs.checkCustom) else cs.primary
    Box(
        Modifier.size(sz).then(if (enabled) Modifier else Modifier.alpha(.45f))
            .background(if (on) fill else Color.Transparent, shape)
            .border(2.dp, if (on || partial) cs.onSurface else cs.onSurface.copy(alpha = .7f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (!on && partial) Box(Modifier.size(sz * 0.5f).background(Color(CHECK_PARENT), RoundedCornerShape(sz * 0.5f * CHECK_ROUND)))
    }
}

/**
 * Зона нажатия галочки поверх правой части строки: вся высота строки, ширина 64 dp (приставка 76 dp). Для пульта отдельная цель фокуса с рамкой:
 * «вправо» со строки приходит сюда ([chk]), «влево» возвращает на строку ([row]). Долгое нажатие здесь открывает то же меню, что и у строки.
 */
@Composable
internal fun BoxScope.SelZone(tv: Boolean, chk: FocusRequester, row: FocusRequester?, label: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val prim = MaterialTheme.colorScheme.primary
    Box(Modifier.matchParentSize()) {
        Box(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(if (tv) 76.dp else 64.dp)
                .focusRequester(chk)
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { e ->
                    if (row != null && e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) runCatching { row.requestFocus() }.isSuccess else false
                }
                .then(if (focused) Modifier.border(3.dp, prim, RoundedCornerShape(8.dp)) else Modifier)
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLong)
                .semantics { contentDescription = label },
        )
    }
}

/** Галочка со своей зоной нажатия (не меньше 48×48 dp, на приставке 56) и рамкой фокуса пульта. ОК на пульте и Enter переключают. */
@Composable
internal fun BigCheck(on: Boolean, tv: Boolean = Marq.tv, partial: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val prim = MaterialTheme.colorScheme.primary
    Box(
        modifier.size(if (tv) 56.dp else 48.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(3.dp, prim, RoundedCornerShape(8.dp)) else Modifier)
            .triStateToggleable(
                state = if (on) ToggleableState.On else if (partial) ToggleableState.Indeterminate else ToggleableState.Off,
                interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Checkbox, onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) { SelBox(on, partial, tv, enabled) }
}

/**
 * Замена material3 `Checkbox(checked, onCheckedChange, modifier)` той же формы вызова, но с крупным квадратиком.
 * [onCheckedChange] = null: только рисунок (строку переключает сама строка, например через toggleable), без своей цели фокуса.
 */
@Composable
internal fun BigCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val tv = Marq.tv
    if (onCheckedChange == null) Box(modifier.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) { SelBox(checked, false, tv, enabled) }
    else BigCheck(checked, tv, enabled = enabled, modifier = modifier) { onCheckedChange(!checked) }
}

/**
 * Строка списка с отметкой: нажатие на ВСЮ строку отмечает (и снимает), квадратик справа ([SelBox]), отмеченная строка подсвечена,
 * фокус пульта виден рамкой, ОК на пульте отмечает. Высота не меньше 48 dp (приставка 56): попасть легко и пальцем, и пультом.
 */
@Composable
internal fun CheckRow(on: Boolean, modifier: Modifier = Modifier, partial: Boolean = false, enabled: Boolean = true, tv: Boolean = Marq.tv, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val prim = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = if (tv) 56.dp else 48.dp).clip(shape)
            .background(if (on) prim.copy(alpha = .16f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(3.dp, prim, shape) else Modifier)
            .triStateToggleable(
                state = if (on) ToggleableState.On else if (partial) ToggleableState.Indeterminate else ToggleableState.Off,
                interactionSource = remember { MutableInteractionSource() }, indication = androidx.compose.foundation.LocalIndication.current,
                enabled = enabled, role = Role.Checkbox, onClick = onClick,
            )
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
        // правая часть строки под квадратик (вся строка и так нажимается): квадратик по центру полосы 48 dp
        Box(Modifier.width(if (tv) 56.dp else 48.dp), contentAlignment = Alignment.Center) { SelBox(on, partial, tv, enabled) }
    }
}
