package app.adbphon

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** set2: наибольшая ширина содержимого настроек (как в «Меню приложений»): на широком экране строки не растягиваются от края до края. */
val SET_MAX_W = 900.dp

/** set2: переход к пункту по ключу (прокрутка, подсветка, на ТВ фокус); задаёт экран «Настройки» (SettingsLayout.kt). */
val LocalSetJump = compositionLocalOf<(String) -> Unit> { {} }

/** Низкий экран (ландшафт телефона, магнитола): строки ниже, как в «Меню приложений». */
@Composable
internal fun setShort(): Boolean = LocalConfiguration.current.screenHeightDp < 500

/**
 * 10.0 (set), set2: строка-действие как ActionRow в «Меню приложений»: значок слева, короткое название, под ним одна-две строки пояснения
 * с малым межстрочным интервалом (1.1). Ширина панели (не больше [SET_MAX_W]), высота не меньше 56 dp (48 на низком экране),
 * фокус пульта: рамка 3 dp цвета акцента. [big]: крупная кнопка первой группы. На ТВ размеры текста поднимает TvFontScope.
 */
@Composable
fun SetAction(icon: ImageVector, title: String, hint: String? = null, enabled: Boolean = true, danger: Boolean = false, modifier: Modifier = Modifier, big: Boolean = false, onClick: () -> Unit) {
    val tv = Marq.tv
    val short = setShort()
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val accent = if (danger) cs.error else cs.primary
    val minH = when {
        big -> if (tv) 72.dp else if (short) 52.dp else 64.dp
        short && !tv -> 48.dp
        else -> 56.dp
    }
    Row(
        modifier.fillMaxWidth().widthIn(max = SET_MAX_W).heightIn(min = minH).clip(shape)
            .background(if (focused) accent.copy(alpha = .16f) else if (big) cs.primary.copy(alpha = .08f) else cs.surfaceVariant.copy(alpha = .35f))
            .border(if (focused) 3.dp else 1.dp, if (focused) accent else if (big) cs.primary.copy(alpha = .45f) else cs.outline.copy(alpha = .35f), shape)
            .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else .45f)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(if (big) (if (tv) 34.dp else 30.dp) else 26.dp), tint = accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            // межстрочный зазор мал (1.1): название и пояснение стоят почти вплотную, как в «Меню приложений»
            FloorText(title, fontWeight = if (big) FontWeight.Bold else FontWeight.Medium, fontSize = if (big) 17.sp else 15.sp, lineHeight = 1.1.em,
                color = if (danger) cs.error else Color.Unspecified)
            if (!hint.isNullOrBlank()) FloorText(hint, fontSize = if (big) 13.sp else 12.sp, lineHeight = 1.1.em, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = cs.onSurface.copy(alpha = .65f))
        }
    }
}

/**
 * set2: цвета переключателя из темы ADB-phon (зелёно-бирюзовый акцент), а не фиолетовые цвета Material по умолчанию:
 * включено: дорожка цвета акцента; выключено: нейтральная серая дорожка и ручка.
 */
@Composable
fun setSwitchColors(): SwitchColors {
    val cs = MaterialTheme.colorScheme
    val n = cs.onSurface
    return SwitchDefaults.colors(
        checkedThumbColor = cs.onPrimary, checkedTrackColor = cs.primary, checkedBorderColor = cs.primary, checkedIconColor = cs.primary,
        uncheckedThumbColor = n.copy(alpha = .6f), uncheckedTrackColor = n.copy(alpha = .10f), uncheckedBorderColor = n.copy(alpha = .45f), uncheckedIconColor = n.copy(alpha = .10f),
        disabledCheckedThumbColor = cs.surface, disabledCheckedTrackColor = cs.primary.copy(alpha = .38f), disabledCheckedBorderColor = Color.Transparent,
        disabledUncheckedThumbColor = n.copy(alpha = .30f), disabledUncheckedTrackColor = n.copy(alpha = .06f), disabledUncheckedBorderColor = n.copy(alpha = .20f),
    )
}

/**
 * set2: нейтральные служебные цвета для экрана «Настройки»: в схеме ADB-phon не заданы outline и surfaceContainerHighest, и Material подставлял
 * сиреневые оттенки (дорожки переключателей и ползунков, рамки полей). Цвет акцента и динамическая схема (Android 12+) не меняются.
 */
fun setNeutralScheme(cs: androidx.compose.material3.ColorScheme): androidx.compose.material3.ColorScheme {
    if (AppColors.dynamic && android.os.Build.VERSION.SDK_INT >= 31) return cs
    val n = cs.onSurface
    val bg = cs.surface
    return cs.copy(
        outline = n.copy(alpha = .50f).compositeOver(bg), outlineVariant = n.copy(alpha = .20f).compositeOver(bg),
        surfaceContainerHighest = n.copy(alpha = .14f).compositeOver(bg),
    )
}

/**
 * set2: строка-переключатель как SwitchRow в «Меню приложений»: название слева, значок ⓘ (если есть пояснение), переключатель справа;
 * нажимается вся строка (пульт: ОК), пояснение раскрывается значком ⓘ (пульт: стрелка вправо на значок, ОК).
 */
@Composable
fun SetSwitchRow(checked: Boolean, onChange: (Boolean) -> Unit, title: String, hint: String = "", enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    var open by rememberCollapseOpen(title)
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().widthIn(max = SET_MAX_W)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (setShort() && !Marq.tv) 48.dp else 56.dp).clip(shape)
                .background(if (focused) cs.primary.copy(alpha = .16f) else cs.surfaceVariant.copy(alpha = .35f))
                .border(if (focused) 3.dp else 1.dp, if (focused) cs.primary else cs.outline.copy(alpha = .35f), shape)
                .toggleable(checked, interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                .alpha(if (enabled) 1f else .45f)
                .padding(start = 14.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FloorText(title, Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 1.1.em)
            if (hint.isNotBlank()) SetInfoButton(open) { open = !open }
            androidx.compose.material3.Switch(checked, null, enabled = enabled, colors = setSwitchColors())
        }
        AnimatedVisibility(open && hint.isNotBlank(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            FloorText(hint, Modifier.padding(horizontal = 14.dp, vertical = 4.dp), fontSize = 12.sp, color = cs.onSurface.copy(alpha = .7f))
        }
    }
}

/** set2: значок ⓘ: раскрывает и сворачивает длинное пояснение; свой фокус пульта (рамка). */
@Composable
fun SetInfoButton(open: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val label = stringResource(R.string.set2_info)
    Box(
        Modifier.size(if (Marq.tv) 44.dp else 40.dp).clip(CircleShape)
            .border(if (focused) 3.dp else 0.dp, if (focused) cs.primary else Color.Transparent, CircleShape)
            .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Outlined.Info, label, Modifier.size(if (Marq.tv) 26.dp else 22.dp), tint = cs.primary)
    }
}

/**
 * set2: просьба больших кнопок первой группы к разделу «Резервная копия» (BackupUi.kt): создать копию или открыть список копий.
 * Раздел забирает её через [take]; без него кнопки только переводят к разделу. Запрос старше 5 с не выполняется.
 */
object SetHero {
    const val MAKE = "make"
    const val RESTORE = "restore"
    var req by mutableStateOf<String?>(null); private set
    private var at = 0L
    fun ask(r: String) { at = android.os.SystemClock.uptimeMillis(); req = r }
    fun take(): String? { val r = req ?: return null; req = null; return r.takeIf { android.os.SystemClock.uptimeMillis() - at < 5000 } }
}

/** set2: первая группа: четыре большие кнопки по центру, видны сразу без прокрутки (как в «Меню приложений»). */
@Composable
private fun SetHeroButtons(vm: MainViewModel) {
    val jump = LocalSetJump.current
    var about by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SetAction(Icons.Filled.SystemUpdate, stringResource(R.string.set2_hero_upd), stringResource(R.string.set_h_upd, vm.appVersion), big = true) { vm.checkUpdate(true) }
        // 20.11.2026 (слово владелицы): «Создать копию» и «Восстановить из копии» лежат в группе «Копии и обновления» (раньше стояли в корне настроек, вне групп)
        SetAction(SetIcons.backup, stringResource(R.string.bk2_make_now), stringResource(R.string.set2_hero_bk_h), big = true) { BkSimple.create = true }
        SetAction(SetIcons.restore, stringResource(R.string.set2_hero_restore), stringResource(R.string.set2_hero_restore_h), big = true) { BkSimple.restore = true }
        SetAction(Icons.Filled.Info, stringResource(R.string.set2_hero_about), stringResource(R.string.set2_hero_about_h), big = true) { about = true }
    }
    if (about) LAlertDialog(
        onDismissRequest = { about = false },
        confirmButton = { TextButton({ about = false }) { Text(stringResource(R.string.btn_close)) } },
        text = { Column(Modifier.barScroll()) { AboutScreen(vm, embedded = true) } },
    )
}

/** Значки строк-действий для разделов в других файлах (там не нужно импортировать значки). */
object SetIcons {
    val copies: ImageVector get() = Icons.Filled.Inventory2
    val download: ImageVector get() = Icons.Filled.Download
    val delete: ImageVector get() = Icons.Filled.Delete
    val backup: ImageVector get() = Icons.Filled.Backup
    val restore: ImageVector get() = Icons.Filled.Restore
    val run: ImageVector get() = Icons.Filled.PlayArrow
    val undo: ImageVector get() = Icons.Filled.Undo
    val start: ImageVector get() = Icons.Filled.PlayArrow
    val stop: ImageVector get() = Icons.Filled.Stop
    val apps: ImageVector get() = Icons.Filled.Apps
}

/** Оформление группы: название, подпись (что внутри) и значок. */
class SetGroupMeta(val title: Int, val sub: Int, val icon: ImageVector)

fun setGroupMeta(g: String): SetGroupMeta = when (g) {
    SetGroups.UPD -> SetGroupMeta(R.string.set_grp_upd, R.string.set_grp_upd_sub, Icons.Filled.SystemUpdate)
    SetGroups.UI -> SetGroupMeta(R.string.set_grp_ui, R.string.set_grp_ui_sub, Icons.Filled.Palette)
    SetGroups.FILES -> SetGroupMeta(R.string.set_grp_files, R.string.set_grp_files_sub, Icons.Filled.Folder)
    SetGroups.PLAYER -> SetGroupMeta(R.string.set_grp_player, R.string.set_grp_player_sub, Icons.Filled.PlayArrow)
    SetGroups.ADB -> SetGroupMeta(R.string.set_grp_adb, R.string.set_grp_adb_sub, Icons.Filled.Usb)
    SetGroups.CLOUD -> SetGroupMeta(R.string.set_grp_cloud, R.string.set_grp_cloud_sub, Icons.Filled.Cloud)
    SetGroups.PANELS -> SetGroupMeta(R.string.set_grp_panels, R.string.set_grp_panels_sub, Icons.Filled.Tune)
    else -> SetGroupMeta(R.string.set_grp_sys, R.string.set_grp_sys_sub, Icons.Filled.Settings)
}

/** Названия пунктов для поиска (заголовок и подпункты); ключи как в [SetGroups.ITEMS]. */
private val SET_TITLES: Map<String, List<Int>> = mapOf(
    SetGroups.HERO to listOf(R.string.set2_hero_upd, R.string.upd_check, R.string.bk2_make_now, R.string.set2_hero_bk, R.string.set2_hero_restore, R.string.set2_hero_about, R.string.set_about,
        R.string.about_github, R.string.about_licenses),
    "upd_auto" to listOf(R.string.upd_auto),
    "versions" to listOf(R.string.ver_btn, R.string.set_h_versions),
    "ver_clear" to listOf(R.string.ver_clear_btn),
    "backup" to listOf(R.string.bk2_title, R.string.bk2_make, R.string.bk2_restore, R.string.bk2_auto, R.string.bk2_rollback),   // 10.0 (bk3): одна пара «Создать копию…» / «Восстановить…»
    "copies" to listOf(R.string.set_backups, R.string.backups_open),
    "theme" to listOf(R.string.set_theme),
    "lang" to listOf(R.string.set_lang),
    "font" to listOf(R.string.sc_font),
    "ui_size" to listOf(R.string.sc_ui),
    "full" to listOf(R.string.fs_title),
    "colors" to listOf(R.string.col_dynamic, R.string.col_accent),
    "bars" to listOf(R.string.bar_contrast, R.string.bar_color),
    "panel_colors" to listOf(R.string.panel_title, R.string.panel_bottom, R.string.panel_side),
    "tapguard" to listOf(R.string.tg_title),
    "anim" to listOf(R.string.anim_title),
    "dir_tap" to listOf(R.string.set_dir_tap),
    "folder_color" to listOf(R.string.set_folder_color),
    "type_icons" to listOf(R.string.ic_types),
    "thumbs" to listOf(R.string.set_thumbs),
    "apk_icon" to listOf(R.string.set_apk_icon),
    "arrow_color" to listOf(R.string.set_arrow_color),
    "check_color" to listOf(R.string.set_check_color),
    "compact_tree" to listOf(R.string.set_compact_tree),
    "fm_sub" to listOf(R.string.set_fm_sub),
    "delete" to listOf(R.string.ds_confirm, R.string.ft_switch, R.string.ft_keep, R.string.ft_max),
    "open_files" to listOf(R.string.set_open_files),
    "installer" to listOf(R.string.inst_u_default),
    "bg_music" to listOf(R.string.set_bg_music, R.string.set_grp_player),
    "vp_hide" to listOf(R.string.set_vp_hide, R.string.set_grp_player),
    "vp_keep" to listOf(R.string.set_vp_keep, R.string.set_grp_player),
    "vp_gest" to listOf(R.string.set_vp_gest_vol, R.string.set_vp_gest_bri, R.string.set_vp_side, R.string.set_vp_gest_seek, R.string.set_vp_gest_fling, R.string.set_vp_sens, R.string.set_grp_player),
    "vp_reset" to listOf(R.string.set_vp_reset, R.string.set_grp_player),
    "wifi_auto" to listOf(R.string.set_wifi_auto),
    "saf" to listOf(R.string.set_saf_title),
    "helper" to listOf(R.string.srv_auto, R.string.srv_off, R.string.srv_stop),
    "shizuku" to listOf(R.string.shz_title, R.string.shz_auto, R.string.shz_apps),
    "perms" to listOf(R.string.su_open),
    "term" to listOf(R.string.tc_set_title),
    "cmod" to listOf(R.string.set_cmod_title, R.string.set_cmod_remove),
    "cmod_auto" to listOf(R.string.set_cmod_auto),
    "cloud_bg" to listOf(R.string.set_cloud_bg),
    "narrow_font" to listOf(R.string.set_narrow_font),
    "hand" to listOf(R.string.set_hand),
    "side_mode" to listOf(R.string.set_side_mode, R.string.set_side_scale),
    "startup" to listOf(R.string.start_tab, R.string.start_folder),
    "keep_on" to listOf(R.string.set_keep_on),
    "exit_back" to listOf(R.string.set_exit_back),
    "log" to listOf(R.string.log_switch, R.string.log_show, R.string.log_copy, R.string.log_save, R.string.log_clear),
)

/** Главное название пункта (для строки результата поиска). */
fun setItemTitle(ctx: Context, key: String): String = SET_TITLES[key]?.firstOrNull()?.let { ctx.getString(it) } ?: key

/** Названия всех пунктов на текущем языке; «Открытие файлов» только когда пункт виден (есть запомненный выбор). */
fun setTitles(ctx: Context): Map<String, List<String>> {
    val openAsShown = UiPrefs.openAs.keys.any { it.startsWith("ext:") }
    return SET_TITLES.filterKeys { it != "open_files" || openAsShown }.mapValues { (_, ids) -> ids.map { ctx.getString(it) } }
}

/** Один пункт настроек по ключу (содержимое как было в прежнем длинном экране, кнопки стали строками-действиями). */
@Composable
fun SetItem(key: String, vm: MainViewModel) {
    val ctx = LocalContext.current
    when (key) {
        // ---- Обновления и копии
        SetGroups.HERO -> SetHeroButtons(vm)
        "upd_auto" -> SetSwitchRow(vm.autoUpdate, { vm.changeAutoUpdate(it) }, stringResource(R.string.upd_auto))
        "versions" -> SetAction(Icons.Filled.History, stringResource(R.string.ver_btn), stringResource(R.string.set_h_versions)) { vm.openVersions() }
        "ver_clear" -> {
            var clearVer by remember { mutableStateOf<Pair<Int, Long>?>(null) }
            val noneMsg = stringResource(R.string.ver_none)
            val clearedFmt = stringResource(R.string.ver_cleared)
            SetAction(Icons.Filled.DeleteSweep, stringResource(R.string.ver_clear_btn), stringResource(R.string.set_h_ver_clear)) {
                val files = VersionKeeper.oldFiles(ctx)
                if (files.isEmpty()) Toast.makeText(ctx, noneMsg, Toast.LENGTH_SHORT).show()
                else clearVer = files.size to files.sumOf { it.length() }
            }
            clearVer?.let { (n, bytes) ->
                LAlertDialog(
                    onDismissRequest = { clearVer = null },
                    title = { Text(stringResource(R.string.ver_clear_btn)) },
                    text = { Text(stringResource(R.string.ver_clear_confirm, n, bytes / 1048576)) },
                    confirmButton = { TextButton({ val k = VersionKeeper.clearOld(ctx); clearVer = null; Toast.makeText(ctx, String.format(clearedFmt, k), Toast.LENGTH_SHORT).show() }) { Text(stringResource(R.string.ssh_delete)) } },
                    dismissButton = { TextButton({ clearVer = null }) { Text(stringResource(R.string.btn_cancel)) } },
                )
            }
        }
        "backup" -> BackupSectionV2(vm)
        "copies" -> CopiesSection()

        // ---- Интерфейс
        "theme" -> {
            Text(stringResource(R.string.set_theme), fontWeight = FontWeight.Medium)
            EqChoices(listOf("auto" to R.string.theme_auto, "light" to R.string.theme_light, "dark" to R.string.theme_dark).map { (code, label) ->
                EqChoice(stringResource(label), vm.theme == code) { vm.changeTheme(code) }
            })
        }
        "lang" -> {
            Text(stringResource(R.string.set_lang), fontWeight = FontWeight.Medium)
            EqChoices(
                listOf(EqChoice(stringResource(R.string.lang_auto), vm.lang == "auto") { vm.changeLang("auto") }) +
                    listOf("ru" to "Русский", "uk" to "Українська", "en" to "English").map { (code, name) -> EqChoice(name, vm.lang == code) { vm.changeLang(code) } },
                perRow = 2,
            )
        }
        "font", "ui_size" -> {
            val scNames = listOf(R.string.sc_small, R.string.sc_normal, R.string.sc_large, R.string.sc_xlarge)
            val font = key == "font"
            Text(stringResource(if (font) R.string.sc_font else R.string.sc_ui), fontWeight = FontWeight.Medium)
            EqChoices((if (font) AppScale.steps else AppScale.uiSteps).mapIndexed { i, v ->
                // 11.0: «Размер интерфейса»: средний пункт «Авто (по экрану)»: на автомагнитолах и планшетах с низкой плотностью интерфейс укрупняется сам; остальные пункты работают поверх
                EqChoice(stringResource(if (!font && i == 1) R.string.sc_auto else scNames[i]), (if (font) AppScale.font else AppScale.ui) == v) { if (font) AppScale.setFont(ctx, v) else { AppScale.setUi(ctx, v) } }
            }, perRow = 2)
            if (!font) {
                Text(stringResource(R.string.sc_total, "%.2f".format(AppScale.total())), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                // 14.0: «−», «+» и «По умолчанию» одной высоты, даже если подпись последней переносится (EqBtnRow, Btn.kt)
                EqBtnRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ AppScale.setFine(ctx, AppScale.fine - 0.05f) }, Modifier.fillMaxHeight()) { Text("−") }
                    Text("%.2f".format(AppScale.fine), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    OutlinedButton({ AppScale.setFine(ctx, AppScale.fine + 0.05f) }, Modifier.fillMaxHeight()) { Text("+") }
                    OutlinedButton({ AppScale.resetUi(ctx) }, Modifier.weight(1f, fill = false).fillMaxHeight()) { Text("↺ " + stringResource(R.string.sc_default), fontSize = 12.sp, textAlign = TextAlign.Center) }   // без maxLines: подпись не обрезается на узком экране и в uk
                }
            }
            if (!font && AppScale.autoF > 1.001f) Text(stringResource(R.string.sc_auto_now, "%.2f".format(AppScale.autoF)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
        }
        "full" -> SettingSwitch(AppFull.on, { AppFull.set(ctx, it) }, stringResource(R.string.fs_title), stringResource(R.string.fs_hint))
        "colors" -> {
            if (android.os.Build.VERSION.SDK_INT >= 31) SettingSwitch(AppColors.dynamic, { AppColors.setDynamic(ctx, it) }, stringResource(R.string.col_dynamic), stringResource(R.string.col_dynamic_hint))
            if (!AppColors.dynamic) {
                val accentThemeFr = remember { androidx.compose.ui.focus.FocusRequester() }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.col_accent), Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    TextButton({ AppColors.resetColor(ctx) }, Modifier.focusRequester(accentThemeFr), enabled = AppColors.hue >= 0f || AppColors.accent != 0) { Text(stringResource(R.string.bar_color_theme)) }
                }
                var mh by remember { mutableStateOf(if (AppColors.hue >= 0f) AppColors.hue else 175f) }
                HueBarNum(mh, AppColors.hue >= 0f, { mh = it; AppColors.hue = it }, { AppColors.setHue(ctx, mh) }, stringResource(R.string.col_accent), themeBtn = accentThemeFr, themeBtnEnabled = AppColors.hue >= 0f || AppColors.accent != 0)
            }
        }
        "bars" -> BarContrastSetting()
        "panel_colors" -> PanelColorsSetting()
        "tapguard" -> SettingSwitch(TapGuard.on, { TapGuard.set(ctx, it) }, stringResource(R.string.tg_title), stringResource(R.string.tg_hint))
        "anim" -> SettingSwitch(UiAnim.on, { UiAnim.set(ctx, it) }, stringResource(R.string.anim_title), stringResource(R.string.anim_hint))

        // ---- Файлы
        "dir_tap" -> FilesDirTapSetting()
        "folder_color" -> FilesFolderColorSetting()
        "type_icons" -> FilesTypeIconsSetting()
        "thumbs" -> FilesThumbsSetting()
        "apk_icon" -> FilesApkIconSetting()
        "arrow_color" -> FilesArrowColorSetting()
        "check_color" -> FilesCheckColorSetting()
        "compact_tree" -> FilesCompactTreeSetting()
        "fm_sub" -> FilesSubLineSetting()
        "delete" -> FilesDeleteSection()
        "open_files" -> FilesOpenAsSetting()
        "installer" -> InstallerDefaultSetting()
        "bg_music" -> FilesBgMusicSetting()
        "vp_hide" -> PlayerPanelHideSetting()
        "vp_keep" -> PlayerKeepSizeSetting()
        "vp_gest" -> PlayerGesturesSetting()
        "vp_reset" -> PlayerResetSetting()
        "wifi_auto" -> FilesWifiAutoSetting()
        "saf" -> SafAccessSetting()

        // ---- Связь и ADB
        "helper" -> {
            var srvAuto by remember { mutableStateOf(AdbServer.auto) }
            var srvOff by remember { mutableStateOf(AdbServer.offAfter) }
            SettingSwitch(srvAuto, { srvAuto = it; AdbServer.auto = it }, stringResource(R.string.srv_auto), stringResource(R.string.srv_auto_hint))
            SettingSwitch(srvOff, { srvOff = it; AdbServer.offAfter = it }, stringResource(R.string.srv_off), stringResource(R.string.srv_off_hint))
            SetAction(Icons.Filled.Stop, stringResource(R.string.srv_stop)) { Thread { AdbServer.stop() }.start() }
        }
        "shizuku" -> ShizukuSection()
        "perms" -> SetAction(Icons.Filled.VerifiedUser, stringResource(R.string.su_open), stringResource(R.string.su_open_hint)) { Setup.only = null; Setup.open = true }
        "term" -> Collapsible(stringResource(R.string.tc_set_title)) { TermSettings() }

        // ---- Облака
        "cmod" -> CloudModuleSetting()
        "cmod_auto" -> CloudModuleAutoSetting()
        "cloud_bg" -> CloudBgSetting()
        "narrow_font" -> NarrowFontSetting()

        // ---- Панели
        "hand" -> HandednessSection()
        "side_mode" -> SideModeSetting()

        // ---- Система
        "startup" -> StartupSection(ctx)
        "keep_on" -> SettingSwitch(vm.keepScreenOn, { vm.changeKeepScreenOn(it) }, stringResource(R.string.set_keep_on), stringResource(R.string.set_keep_hint))
        "exit_back" -> SettingSwitch(vm.exitOnBack, { vm.changeExitOnBack(it) }, stringResource(R.string.set_exit_back), stringResource(R.string.set_exit_hint))
        "log" -> {
            val copied = stringResource(R.string.copied)
            SettingSwitch(vm.logOn, { vm.changeLog(it) }, stringResource(R.string.log_switch), stringResource(R.string.log_hint))
            SetAction(Icons.Filled.Description, stringResource(R.string.log_show)) { vm.showLog() }
            SetAction(Icons.Filled.ContentCopy, stringResource(R.string.log_copy)) {
                val text = (AppLog.lastCrash().takeIf { it.isNotBlank() }?.let { "== СБОЙ ==\n$it\n\n" }.orEmpty()) + AppLog.tail(300)
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ADB-phon log", text))
                Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show()
            }
            SetAction(Icons.Filled.Save, stringResource(R.string.log_save), enabled = vm.connected) { vm.saveLog() }
            SetAction(Icons.Filled.Delete, stringResource(R.string.log_clear), danger = true) { AppLog.clear(); vm.hideLog() }
        }
    }
}
