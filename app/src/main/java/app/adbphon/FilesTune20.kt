package app.adbphon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 20.11.2026 (4PDA, wad72, пост #47; слово владелицы «сделай всё»): настройки «Файлов»:
 * значок приложения у .apk, цвет стрелок у папок, компактные отступы дерева, режим боковой панели кнопок и масштаб её кнопок.
 */

@Composable
fun FilesApkIconSetting() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    SettingSwitch(UiPrefs.apkIcons, { UiPrefs.setApkIcons(ctx, it) }, stringResource(R.string.set_apk_icon), stringResource(R.string.set_apk_icon_hint))
}

@Composable
fun FilesCompactTreeSetting() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    SettingSwitch(UiPrefs.compactTree, { UiPrefs.setCompactTree(ctx, it) }, stringResource(R.string.set_compact_tree), stringResource(R.string.set_compact_tree_hint))
}

/** Палитра для своего цвета стрелок: нейтральные и цвета папок. */
val ARROW_PALETTE: List<Color> = listOf(Color(0xFFFFFFFF), Color(0xFFB0BEC5), Color(0xFF212121)) + FOLDER_COLORS.map { it.second }

@Composable
fun FilesArrowColorSetting() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var dlg by remember { mutableStateOf(false) }
    HintTitle(stringResource(R.string.set_arrow_color), stringResource(R.string.set_arrow_color_hint))
    EqChoices(listOf(
        EqChoice(stringResource(R.string.arrow_auto), UiPrefs.arrowMode == 0) { UiPrefs.setArrowMode(ctx, 0) },
        EqChoice(stringResource(R.string.arrow_accent), UiPrefs.arrowMode == 1) { UiPrefs.setArrowMode(ctx, 1) },
        EqChoice(stringResource(R.string.arrow_blue), UiPrefs.arrowMode == 2) { UiPrefs.setArrowMode(ctx, 2) },
        EqChoice(stringResource(R.string.arrow_custom), UiPrefs.arrowMode == 3) { dlg = true },
    ), perRow = 2)
    if (dlg) PaletteDialog(stringResource(R.string.arrow_custom_title), UiPrefs.arrowCustom.takeIf { UiPrefs.arrowMode == 3 }, { UiPrefs.setArrowCustom(ctx, it) }) { dlg = false }
}

@Composable
private fun PaletteDialog(title: String, current: Int?, onPick: (Int) -> Unit, onClose: () -> Unit) {
    LAlertDialog(
        onDismissRequest = onClose,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ARROW_PALETTE.chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { c ->
                            val sel = current == c.toArgb()
                            Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(c)
                                .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                                .clickable { onPick(c.toArgb()); onClose() })
                        }
                        repeat(5 - row.size) { Box(Modifier.size(46.dp)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.btn_close)) } },
    )
}

/** 26.11.2026 (слово владелицы): цвет закрашенного квадратика отметки. У родительской папки всегда оранжевый. */
@Composable
fun FilesCheckColorSetting() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var dlg by remember { mutableStateOf(false) }
    HintTitle(stringResource(R.string.set_check_color), stringResource(R.string.set_check_color_hint))
    EqChoices(listOf(
        EqChoice(stringResource(R.string.check_theme), UiPrefs.checkMode == 0) { UiPrefs.setCheckTheme(ctx) },
        EqChoice(stringResource(R.string.arrow_custom), UiPrefs.checkMode == 1) { dlg = true },
    ), perRow = 2)
    // 27.11.2026 (4PDA wad72, пост 146236023): образец без подписей принимали за кнопки, поэтому у каждого квадратика своя подпись
    Text(stringResource(R.string.check_ex_title), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CheckSample(R.string.check_ex_on) { SelBox(true) }
        CheckSample(R.string.check_ex_parent) { SelBox(true, parent = true) }
        CheckSample(R.string.check_ex_partial) { SelBox(false, partial = true) }
        CheckSample(R.string.check_ex_off) { SelBox(false) }
    }
    if (dlg) PaletteDialog(stringResource(R.string.set_check_color), UiPrefs.checkCustom.takeIf { UiPrefs.checkMode == 1 }, { UiPrefs.setCheckCustom(ctx, it) }) { dlg = false }
}

/** Строка образца: квадратик и что он значит. Не нажимается. */
@Composable
private fun CheckSample(text: Int, box: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        box()
        Text(stringResource(text), fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
fun SideModeSetting() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    HintTitle(stringResource(R.string.set_side_mode), stringResource(R.string.set_side_mode_hint))
    Text(stringResource(R.string.set_side_scale), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
    EqChoices(listOf(
        EqChoice(stringResource(R.string.side_scale_auto), UiPrefs.sideScale == 0) { UiPrefs.setSideScale(ctx, 0) },
        EqChoice("+15%", UiPrefs.sideScale == 1) { UiPrefs.setSideScale(ctx, 1) },
        EqChoice("+30%", UiPrefs.sideScale == 2) { UiPrefs.setSideScale(ctx, 2) },
    ), perRow = 3)
}
