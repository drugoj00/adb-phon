package app.adbphon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Пароль архива: запоминается на время работы приложения. */
@Composable
fun ArcPassDialog(d: FmDlg.ArcPass, close: () -> Unit) {
    var t by remember { mutableStateOf("") }
    LAlertDialog(
        onDismissRequest = close, title = { Text(stringResource(R.string.arc_password_title), maxLines = 1) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(d.file.name, fontSize = 13.sp, maxLines = 1)
                val pw = rememberPw()   // 9.0: глазик в поле пароля
                OutlinedTextField(t, { t = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.arc_password_hint), maxLines = 1) },
                    visualTransformation = pw.transform, trailingIcon = { PwEyeIcon(pw.shown) { pw.shown = !pw.shown } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        },
        confirmButton = { TextButton({ close(); val c = t.toCharArray(); Fm.arcPass[d.file.absolutePath] = c; d.arc?.password = c; d.arc?.invalidate(); d.retry() }) { Text(stringResource(R.string.btn_yes)) } },
        dismissButton = { TextButton(close) { Text(stringResource(R.string.btn_cancel)) } },
    )
}

/** Совпадение имён при извлечении: заменить, пропустить или переименовать новые файлы. */
@Composable
fun ArcConflictDialog(d: FmDlg.ArcConflict, close: () -> Unit) {
    LAlertDialog(
        onDismissRequest = close, title = { Text(stringResource(R.string.arc_conflict_title)) },
        text = { Text(stringResource(R.string.arc_conflict_text), fontSize = 14.sp) },
        // ровно три действия: «Переименовать», «Заменить», «Отменить» (ничего не извлекается)
        confirmButton = { Row { TextButton({ close(); d.choose(2) }) { Text(stringResource(R.string.fm_rename)) }; TextButton({ close(); d.choose(0) }) { Text(stringResource(R.string.fm_replace)) }; TextButton(close) { Text(stringResource(R.string.cf_b_cancel)) } } },
    )
}

/** Кодировка имён в архиве: авто, UTF-8, CP866, CP1251. */
@Composable
fun ArcCharsetDialog(d: FmDlg.ArcCharset, close: () -> Unit) {
    val opts = listOf(null to R.string.arc_auto, "UTF-8" to R.string.arc_utf8, "IBM866" to R.string.arc_cp866, "windows-1251" to R.string.arc_cp1251)
    LAlertDialog(
        onDismissRequest = close, title = { Text(stringResource(R.string.arc_charset_btn)) },
        text = {
            Column {
                opts.forEach { (cs, l) ->
                    Text(stringResource(l), Modifier.fillMaxWidth().clickable { close(); d.arc.charset = cs; d.arc.invalidate(); Fm.load(d.pane, "/", remember = false) }.padding(vertical = 10.dp), fontSize = 16.sp)
                }
            }
        },
        confirmButton = { TextButton(close) { Text(stringResource(R.string.btn_close)) } },
    )
}

/** Создание архива: формат, имя, уровень сжатия, пароль (только zip, AES-256). */
@Composable
fun MakeArcDialog(d: FmDlg.MakeArchive, close: () -> Unit) {
    val sp = LocalContext.current.applicationContext.getSharedPreferences("fm", android.content.Context.MODE_PRIVATE)
    var fmt by remember { mutableStateOf(sp.getString("arc_fmt", "zip") ?: "zip") }
    var level by remember { mutableStateOf(sp.getInt("arc_level", 6)) }
    val base = d.names.singleOrNull()?.substringBeforeLast('.', d.names[0]) ?: d.pane.path.trimEnd('/').substringAfterLast('/').ifEmpty { "archive" }
    var name by remember { mutableStateOf(base) }
    var pass by remember { mutableStateOf("") }
    LAlertDialog(
        onDismissRequest = close, title = { Text(stringResource(R.string.fm_make_archive), maxLines = 1) },
        text = {
            Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth().androidxHScroll(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ArcCreate.formats.forEach { f -> FilterChip(fmt == f, { fmt = f }, { Text(f, fontSize = 12.sp) }) }
                }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.fm_name), maxLines = 1) })
                Text(stringResource(R.string.arc_level_label), fontSize = 12.sp)
                Row(Modifier.fillMaxWidth().androidxHScroll(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to R.string.arc_lv_store, 1 to R.string.arc_lv_fast, 6 to R.string.arc_lv_normal, 9 to R.string.arc_lv_max).forEach { (v, l) -> FilterChip(level == v, { level = v }, { Text(stringResource(l), fontSize = 12.sp) }) }
                }
                val pw = rememberPw()   // 9.0: глазик в поле пароля
                if (fmt == "zip") OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.arc_password_optional), maxLines = 1) },
                    visualTransformation = pw.transform, trailingIcon = { PwEyeIcon(pw.shown) { pw.shown = !pw.shown } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        },
        confirmButton = { TextButton({ close(); Fm.makeArc(d.pane, d.names, name.trim().ifEmpty { base } + "." + fmt, fmt, level, if (fmt == "zip" && pass.isNotEmpty()) pass.toCharArray() else null) }) { Text(stringResource(R.string.btn_yes)) } },
        dismissButton = { TextButton(close) { Text(stringResource(R.string.btn_cancel)) } },
    )
}

@Composable
private fun Modifier.androidxHScroll(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))
