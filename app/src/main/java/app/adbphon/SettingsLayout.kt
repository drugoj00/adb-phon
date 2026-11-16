@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package app.adbphon

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 10.0 (set), set2: экран «Настройки» по группам ([SetGroups]) по образцу «Меню приложений» 8.0 (SettingsScreen.kt).
 * ТВ, приставка (Marq.tv) и ландшафт телефона: две половины. Слева меню групп: значок и название в одну строку, ширина по самому длинному названию;
 * справа поиск и пункты выбранной группы по центру, не шире [SET_MAX_W]. Пульт: ← → между меню и пунктами (фокус в меню встаёт на выбранную группу),
 * «Назад» из пунктов возвращает в меню, следующий «Назад» закрывает настройки (обработчик в MainActivity).
 * Портрет: одна колонка: сверху поиск, ниже заголовки групп; нажатие раскрывает группу на месте и сворачивает остальные.
 */
@Composable
fun SettingsGrouped(vm: MainViewModel) {
    // set2: нейтральные служебные цвета (переключатели, ползунки, рамки) вместо сиреневых по умолчанию Material
    val cs0 = MaterialTheme.colorScheme
    val cs = remember(cs0, AppColors.dynamic) { setNeutralScheme(cs0) }
    MaterialTheme(colorScheme = cs, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) { SettingsGroupedBody(vm) }
}

@Composable
private fun SettingsGroupedBody(vm: MainViewModel) {
    val ctx = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    // выбранная группа (две половины) и раскрытая (одна колонка; пусто: все свёрнуты)
    var group by rememberSaveable { mutableStateOf(SetGroups.UPD) }
    var hl by remember { mutableStateOf<String?>(null) }                  // подсвеченный пункт после поиска или большой кнопки
    var hlTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(hl, hlTick) { if (hl != null) { delay(4000); hl = null } }
    val titles = remember(vm.lang, UiPrefs.openAs.size) { setTitles(ctx) }
    val results = remember(query, titles) { SetGroups.search(query, titles) }
    val jump: (String) -> Unit = { k -> SetGroups.groupOf(k)?.let { g -> group = g; query = ""; hl = k; hlTick++ } }

    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    CompositionLocalProvider(LocalSetJump provides jump) {
        if (Marq.tv || landscape) {   // слово владелицы 16.11.2026: ландшафт телефона тоже две половины
            val sel = group.ifEmpty { SetGroups.UPD }
            val frs = remember { SetGroups.ORDER.associateWith { FocusRequester() } }
            var inContent by remember { mutableStateOf(false) }
            BackHandler(enabled = inContent) { runCatching { frs[sel]?.requestFocus() } }
            BackHandler(enabled = !inContent && query.isNotEmpty()) { query = "" }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val cap = maxWidth * 0.45f
                Row(Modifier.fillMaxSize()) {
                    SetGroupsPane(sel, frs, cap) { group = it }
                    VerticalDivider()
                    Column(Modifier.weight(1f).fillMaxHeight().onFocusChanged { inContent = it.hasFocus }) {
                        SetSearchField(query, Modifier.align(Alignment.CenterHorizontally).widthIn(max = SET_MAX_W + 24.dp).fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp + 10.dp, top = 8.dp)) { query = it }
                        if (query.isNotBlank()) {
                            TabColumn("tab.settings.search", Modifier.weight(1f).fillMaxWidth(), 12.dp) { SetCentered { SetResults(results, titles, query, jump) } }
                        } else key(sel) {
                            TabColumn("tab.settings.$sel", Modifier.weight(1f).fillMaxWidth(), 12.dp) { SetCentered { SetGroupBody(sel, vm, hl, hlTick, focusOnHl = true) } }
                        }
                    }
                }
            }
        } else {
            BackHandler(enabled = query.isNotEmpty()) { query = "" }
            val bring = remember { SetGroups.ORDER.associateWith { BringIntoViewRequester() } }
            val firstRun = remember { booleanArrayOf(true) }   // при входе не сбивать запомненную прокрутку
            LaunchedEffect(group) {
                if (firstRun[0]) { firstRun[0] = false; return@LaunchedEffect }
                if (group.isNotEmpty()) { delay(200); runCatching { bring.getValue(group).bringIntoView() } }
            }
            TabColumn("tab.settings", Modifier.fillMaxSize(), 12.dp) {
                SetCentered(8.dp) {
                    SetSearchField(query, Modifier.fillMaxWidth()) { query = it }
                    if (query.isNotBlank()) SetResults(results, titles, query, jump)
                    else SetGroups.ORDER.forEach { g ->
                        SetGroupHead(g, open = g == group, Modifier.bringIntoViewRequester(bring.getValue(g))) { group = if (group == g) "" else g }
                        if (g == group) key(g) {
                            Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SetGroupBody(g, vm, hl, hlTick, focusOnHl = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Содержимое по центру панели, не шире [SET_MAX_W] (как в «Меню приложений»). */
@Composable
private fun ColumnScope.SetCentered(gap: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = SET_MAX_W).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap), content = content)
}

/** Пункты группы по порядку; найденный пункт подсвечен рамкой и прокручен в поле зрения (на ТВ и в ландшафте ещё и получает фокус). */
@Composable
private fun ColumnScope.SetGroupBody(g: String, vm: MainViewModel, hl: String?, hlTick: Int, focusOnHl: Boolean) {
    SetGroups.itemsOf(g).forEach { k ->
        key(k) { SetItemFrame(hl == k, hlTick, focusOnHl) { SetItem(k, vm) } }
    }
}

@Composable
private fun SetItemFrame(on: Boolean, tick: Int, focusOnHl: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val bring = remember { BringIntoViewRequester() }
    val fr = remember { FocusRequester() }
    LaunchedEffect(on, tick) {
        if (on) {
            delay(250); runCatching { bring.bringIntoView() }
            if (focusOnHl) { delay(100); runCatching { fr.requestFocus() } }
        }
    }
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().bringIntoViewRequester(bring).focusRequester(fr)
            .then(if (on) Modifier.background(cs.primary.copy(alpha = .08f), shape).border(2.dp, cs.primary, shape) else Modifier),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
    )
}

/** Строка поиска по настройкам (на ТВ клавиатура только по ОК: ImeGate на корне экрана). */
@Composable
private fun SetSearchField(query: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        query, onChange, modifier, singleLine = true,
        placeholder = { FloorText(stringResource(R.string.set_search), maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f)) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton({ onChange("") }) { Icon(Icons.Filled.Close, stringResource(R.string.btn_close)) } },
    )
}

/** Найденные пункты: название, которое подошло к запросу, и группа; нажатие ведёт к пункту. */
@Composable
private fun SetResults(results: List<String>, titles: Map<String, List<String>>, query: String, jump: (String) -> Unit) {
    if (results.isEmpty()) FloorText(stringResource(R.string.set_search_none), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f), modifier = Modifier.padding(4.dp))
    results.forEach { k ->
        val m = setGroupMeta(SetGroups.groupOf(k) ?: SetGroups.SYS)
        SetAction(m.icon, SetGroups.bestTitle(query, titles[k].orEmpty()) ?: k, stringResource(m.title)) { jump(k) }
    }
}

/**
 * Левое меню групп (две половины): ширина по самому длинному названию (не больше [cap]), строки в одну строку: значок и название.
 * На пульте группа выбирается при наведении фокуса, касанием и мышью по нажатию; при входе в меню стрелкой фокус встаёт на выбранную группу.
 */
@Composable
private fun SetGroupsPane(current: String, frs: Map<String, FocusRequester>, cap: Dp, onPick: (String) -> Unit) {
    val tv = Marq.tv
    val short = setShort() && !tv
    val fontSp = if (tv) 18.sp else if (short) 14.sp else 16.sp
    val icon = if (tv) 28.dp else if (short) 20.dp else 24.dp
    val padH = if (tv) 16.dp else 12.dp
    val names = SetGroups.ORDER.map { stringResource(setGroupMeta(it).title) }
    val base = LocalTextStyle.current
    val style = base.copy(fontSize = tvFontSize(fontSp, base), fontWeight = FontWeight.Bold)
    val tm = rememberTextMeasurer()
    val d = LocalDensity.current
    val textW = remember(names, style, d) { with(d) { names.maxOf { tm.measure(it, style, maxLines = 1, softWrap = false, density = d).size.width }.toDp() } }
    // текст + значок + зазор + отступы строки + рамка фокуса + отступы колонки + место полосы прокрутки
    val paneW = (textW + icon + 12.dp + padH * 2 + 6.dp + 16.dp + 10.dp + 4.dp).coerceAtMost(cap)
    TabColumn("tab.settings.menu",
        Modifier.width(paneW).fillMaxHeight().focusProperties { enter = { frs[current] ?: FocusRequester.Default } }.focusGroup(),
        8.dp, Arrangement.spacedBy(if (short) 4.dp else 6.dp)) {
        SetGroups.ORDER.forEachIndexed { i, g ->
            SetMenuRow(g, names[i], g == current, Modifier.focusRequester(frs.getValue(g)), fontSp, icon, padH, short, onFocus = { onPick(g) }) { onPick(g) }
        }
    }
}

/** Строка меню групп: значок и название в одну строку; выбранная залита цветом, фокус пульта: рамка 3 dp. Первая группа чуть выделена всегда. */
@Composable
private fun SetMenuRow(g: String, title: String, selected: Boolean, modifier: Modifier, fontSp: androidx.compose.ui.unit.TextUnit, icon: Dp, padH: Dp, short: Boolean,
                       onFocus: () -> Unit, onClick: () -> Unit) {
    val m = setGroupMeta(g)
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }
    val bg = when {
        selected -> cs.primary.copy(alpha = .22f)
        focused -> cs.primary.copy(alpha = .14f)
        g == SetGroups.UPD -> cs.primary.copy(alpha = .08f)
        else -> cs.surfaceVariant.copy(alpha = .35f)
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = if (Marq.tv) 56.dp else if (short) 40.dp else 48.dp).clip(shape).background(bg)
            .border(if (focused) 3.dp else 1.dp, if (focused || selected) cs.primary else cs.outline.copy(alpha = .25f), shape)
            .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Tab, onClick = onClick)
            .padding(horizontal = padH, vertical = if (short) 2.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(m.icon, null, Modifier.size(icon), tint = cs.primary)
        FloorText(title, fontSize = fontSp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/** Заголовок группы в одной колонке (портрет): значок, название в одну строку, стрелка; нажатие раскрывает группу и сворачивает остальные. */
@Composable
private fun SetGroupHead(g: String, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val m = setGroupMeta(g)
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape)
            .background(if (open) cs.primary.copy(alpha = .22f) else if (g == SetGroups.UPD) cs.primary.copy(alpha = .08f) else cs.surfaceVariant.copy(alpha = .35f))
            .border(if (focused) 3.dp else 1.dp, if (focused || open) cs.primary else cs.outline.copy(alpha = .25f), shape)
            .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(m.icon, null, Modifier.size(24.dp), tint = cs.primary)
        FloorText(stringResource(m.title), Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(24.dp), tint = cs.primary)
    }
}
