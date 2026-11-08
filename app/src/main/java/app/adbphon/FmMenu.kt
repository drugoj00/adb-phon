package app.adbphon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Ключ запоминаемого выбора приложения для типа файла: фото, аудио, видео, текст, pdf, office или «ext:расширение». */
fun openKey(ext: String): String = "ext:$ext"

/**
 * «Открыть в системе»: список приложений, которые Android предлагает для этого типа файла, и встроенный просмотрщик (если он есть для типа).
 * Галочка «По умолчанию для этого типа» запоминает выбор: файлы этого типа дальше открываются этим приложением, а значок файла становится его значком.
 */
@Composable
fun OpenWithDialog(p: FmPane, f: FmItem, close: () -> Unit) {
    val ctx = LocalContext.current
    val key = openKey(f.ext)
    var always by remember { mutableStateOf(false) }
    val mime = remember(f.ext) { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.ext) ?: "*/*" }
    val apps = remember(f.ext) {
        runCatching {
            ctx.packageManager.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse("file:///x"), mime), 0)
                .map { it.activityInfo.packageName to it.loadLabel(ctx.packageManager).toString() }.filter { it.first != ctx.packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        }.getOrDefault(emptyList())
    }
    val builtin = openCat(f.ext) != null || DocKind.of(f.ext) != null
    val cur = UiPrefs.openAs[key] ?: "builtin"
    LAlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.fm_open_in_system), maxLines = 1) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Column(Modifier.heightIn(max = 320.dp).barScroll(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (builtin) Row(Modifier.fillMaxWidth().clickable { if (always) UiPrefs.setOpenAs(ctx, key, "builtin"); close(); Fm.openBuiltinForce(p, f) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.oa_builtin) + if (cur == "builtin") "  ✓" else "", fontSize = 15.sp)
                    }
                    apps.forEach { (pkg, label) ->
                        Row(Modifier.fillMaxWidth().clickable { if (always) UiPrefs.setOpenAs(ctx, key, pkg); close(); Fm.openWith(p, f, pkg) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AppIcon(pkg, 32.dp); Text(label + if (cur == pkg) "  ✓" else "", maxLines = 1, fontSize = 15.sp)
                        }
                    }
                    if (apps.isEmpty() && !builtin) Text(stringResource(R.string.fm_no_app), fontSize = 13.sp)
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(Modifier.fillMaxWidth().clickable { always = !always }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(always, { always = it }, Modifier.size(36.dp)); Text(stringResource(R.string.fm_use_default), fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
                }
            }
        },
        confirmButton = { TextButton(close) { Text(stringResource(R.string.btn_close)) } },
    )
}

/** «Открыть как…»: файл открывается как выбранный тип, что бы ни стояло в расширении. */
@Composable
fun OpenAsDialog(p: FmPane, f: FmItem, close: () -> Unit) {
    val types = listOf("text" to R.string.ot_text, "image" to R.string.ot_image, "audio" to R.string.ot_audio, "video" to R.string.ot_video,
        "archive" to R.string.ot_archive, "apk" to R.string.ot_apk, "any" to R.string.ot_any)
    LAlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.fm_open_as), maxLines = 1) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).barScroll()) {
                types.forEach { (t, l) ->
                    Text(stringResource(l), Modifier.fillMaxWidth().clickable { close(); Fm.openAs(p, f, t) }.padding(vertical = 10.dp), fontSize = 16.sp)
                }
            }
        },
        confirmButton = { TextButton(close) { Text(stringResource(R.string.btn_close)) } },
    )
}
