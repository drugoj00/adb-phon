package app.adbphon

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** Файловый менеджер (приложение), в котором можно выбрать картинку. */
private class PickerApp(val label: String, val comp: ComponentName, val icon: androidx.compose.ui.graphics.ImageBitmap?)

private fun findPickerApps(ctx: Context): List<PickerApp> {
    val pm = ctx.packageManager
    val out = LinkedHashMap<String, PickerApp>()
    for (action in listOf(Intent.ACTION_GET_CONTENT, Intent.ACTION_PICK)) {
        val i = Intent(action).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).let { if (action == Intent.ACTION_PICK) Intent(action).setType("image/*") else it }
        val list = runCatching { pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY) }.getOrDefault(emptyList())
        for (r in list) {
            val ai = r.activityInfo ?: continue
            if (!ai.exported) continue
            val key = ai.packageName + "/" + ai.name
            if (out.containsKey(key)) continue
            val bmp = runCatching {
                val d = r.loadIcon(pm)
                val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                val c = Canvas(b); d.setBounds(0, 0, 96, 96); d.draw(c)
                b.asImageBitmap()
            }.getOrNull()
            out[key] = PickerApp(runCatching { r.loadLabel(pm).toString() }.getOrDefault(ai.packageName), ComponentName(ai.packageName, ai.name), bmp)
        }
    }
    return out.values.sortedBy { it.label.lowercase() }
}

private val imgExt = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic")

/**
 * Выбор картинки без системного окна «Выбор файла» (на части устройств оно отвечает «нет доступа»):
 * встроенный обзор папок или любой установленный файловый менеджер на выбор.
 */
@Composable
fun ImageSourceDialog(onPicked: (Uri) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var own by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<PickerApp?>(null) }
    val apps = remember { findPickerApps(ctx) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val u = r.data?.data
        if (u != null) onPicked(u) else onClose()
    }
    if (own) { OwnImageBrowser(onPicked = onPicked, onBack = { own = false }); return }
    LAlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.imgsrc_title), fontSize = 16.sp, maxLines = 1) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().clickable { own = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Folder, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(stringResource(R.string.imgsrc_own), fontSize = 15.sp)
                        Text(stringResource(R.string.imgsrc_own_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (apps.isNotEmpty()) Text(stringResource(R.string.imgsrc_apps), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                apps.forEach { a ->
                    Row(Modifier.fillMaxWidth().clickable {
                        target = a
                        runCatching { launcher.launch(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).setComponent(a.comp)) }
                            .onFailure { Toast.makeText(ctx, ctx.getString(R.string.no_picker), Toast.LENGTH_LONG).show() }
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (a.icon != null) Image(a.icon, null, Modifier.size(32.dp)) else Icon(Icons.Filled.Image, null, Modifier.size(32.dp))
                        Text(a.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.btn_close)) } },
    )
}

/** Простой обзор папок: показывает папки и картинки, читает файлы напрямую (нужен доступ к файлам, как у «Файлов»). */
@Composable
private fun OwnImageBrowser(onPicked: (Uri) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val root = remember { Environment.getExternalStorageDirectory() }
    var dir by remember {
        mutableStateOf(listOf("Pictures", "DCIM", "Download").map { File(root, it) }.firstOrNull { it.isDirectory } ?: root)
    }
    var granted by remember { mutableStateOf(Storage.hasAccess(ctx)) }
    LaunchedEffect(Unit) { Storage.grant(ctx); granted = Storage.hasAccess(ctx) }
    val roots = remember { Storage.roots() }
    val items = remember(dir, granted) {
        val all = runCatching { dir.listFiles()?.toList() }.getOrNull().orEmpty()
        all.filter { !it.name.startsWith(".") && (it.isDirectory || it.extension.lowercase() in imgExt) }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
    }
    LAlertDialog(
        onDismissRequest = onBack,
        wide = true,
        title = { Text(dir.absolutePath, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (roots.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    roots.forEach { r -> TextButton({ dir = r }) { Text(if (r == root) stringResource(R.string.imgsrc_device) else r.name, fontSize = 12.sp) } }
                }
                if (dir.absolutePath != "/" && dir.parentFile != null) Row(Modifier.fillMaxWidth().clickable { dir = dir.parentFile!! }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.ArrowUpward, null, Modifier.size(28.dp)); Text("..", fontSize = 15.sp)
                }
                if (items.isEmpty()) Text(stringResource(if (granted) R.string.imgsrc_empty else R.string.imgsrc_noaccess), fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
                items.forEach { f ->
                    Row(Modifier.fillMaxWidth().clickable { if (f.isDirectory) dir = f else onPicked(Uri.fromFile(f)) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(if (f.isDirectory) Icons.Filled.Folder else Icons.Filled.Image, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(f.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onBack) { Text(stringResource(R.string.imgsrc_back)) } },
    )
}
