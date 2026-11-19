package app.adbphon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import app.adbphon.FloorText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Что перепаковывать: [title] — имя результата без расширения; [parts] достаются в фоне ([load]); [destDir] — папка результата, [destLabel] — как она показана в окне.
 * [hasSplits]: у программы есть части (base + config.*); если нет, это один APK и он просто копируется как есть, без вопросов (подпись цела).
 */
class RepackSpec(
    val title: String,
    val destDir: File,
    val destLabel: String,
    val hasSplits: Boolean,
    val load: suspend () -> List<File>,
    val cleanup: () -> Unit = {},
    val after: () -> Unit = {},
)

/**
 * Окно выбора формата перепаковки без прокрутки: страница 1 — формат (в ландшафте слева список, справа пояснение), страница 2 — подпись для «Один APK».
 * «Назад» и «Отмена» на каждом шаге; BACK пульта = «Назад», на первой странице «Отмена».
 */
@Composable
fun RepackDialog(spec: RepackSpec, run: (RFormat, RSign) -> Unit, close: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    var fmt by remember { mutableStateOf(RFormat.APKS) }
    var sign by remember { mutableStateOf(RSign.OWN) }
    val land = isLandscapeUi()

    @Composable fun option(label: Int, on: Boolean, pick: () -> Unit) {
        Row(Modifier.fillMaxWidth().selectable(on, onClick = pick, role = Role.RadioButton).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(on, null)
            Text(stringResource(label), Modifier.padding(start = 10.dp), fontSize = 15.sp)
        }
    }
    val hint = when (fmt) { RFormat.AS_IS -> R.string.rp_h_asis; RFormat.ZIP -> R.string.rp_h_zip; RFormat.APK -> R.string.rp_h_apk; RFormat.APKS -> R.string.rp_h_apks; RFormat.XAPK -> R.string.rp_h_xapk }
    @Composable fun formats() {
        option(R.string.rp_fmt_apks, fmt == RFormat.APKS) { fmt = RFormat.APKS }
        option(R.string.rp_fmt_xapk, fmt == RFormat.XAPK) { fmt = RFormat.XAPK }
        option(R.string.rp_fmt_zip, fmt == RFormat.ZIP) { fmt = RFormat.ZIP }
        option(R.string.rp_fmt_asis, fmt == RFormat.AS_IS) { fmt = RFormat.AS_IS }
        option(R.string.rp_fmt_apk, fmt == RFormat.APK) { fmt = RFormat.APK }
    }
    @Composable fun head() { Text(spec.title, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
    @Composable fun note() {
        Text(stringResource(hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .85f))
        Text(stringResource(R.string.rp_to, spec.destLabel), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 2)
    }
    fun back() { if (page > 0) page-- else close() }

    LAlertDialog(
        wide = land,
        onDismissRequest = { back() },
        title = { Text(stringResource(R.string.rp_menu).trimEnd('\u2026')) },
        text = { Column(Modifier.tvFit()) {   // 10.0 (tvfont): на ТВ крупный текст ужимается, если не помещается
            if (page == 0) {
                if (land) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) { head(); formats() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { note() }
                } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { head(); formats(); note() }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    head()
                    Text(stringResource(R.string.rp_sign_title), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    option(R.string.rp_sign_own, sign == RSign.OWN) { sign = RSign.OWN }
                    option(R.string.rp_sign_none, sign == RSign.NONE) { sign = RSign.NONE }
                    Text(stringResource(R.string.rp_sign_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .85f))
                }
            }
        } },
        confirmButton = {
            if (page == 0 && fmt == RFormat.APK) TextButton({ page = 1 }) { Text(stringResource(R.string.okw_next)) }
            else TextButton({ close(); run(fmt, sign) }) { Text(stringResource(R.string.rp_go)) }
        },
        dismissButton = {
            Row {
                if (page > 0) TextButton({ back() }) { Text(stringResource(R.string.okw_back)) }
                TextButton(close) { Text(stringResource(R.string.btn_cancel)) }
            }
        },
    )
}
