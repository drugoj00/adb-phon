package app.adbphon

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Выбранный файл (APK, APKS, APKM, XAPK, ZIP, 7z, RAR, TAR…) -> список того, что можно установить. */
suspend fun pickedToApks(ctx: android.content.Context, uri: Uri): List<ApkFile> = withContext(Dispatchers.IO) {
    val name = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    val dir = File(ctx.cacheDir, "pick").apply { deleteRecursively(); mkdirs() }
    val f = File(dir, name.replace(Regex("[^\\w.\\- ]"), "_"))
    ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } } ?: return@withContext emptyList()
    val isApk = runCatching { java.util.zip.ZipFile(f).use { it.getEntry("AndroidManifest.xml") != null } }.getOrDefault(false)
    if (isApk) {
        val keep = File(File(ctx.cacheDir, "batch").apply { mkdirs() }, "pick_" + f.name)
        f.copyTo(keep, true)
        listOf(ApkFile(f.name, Uri.fromFile(keep), keep.length(), keep))
    } else Archives.extractApks(ctx, Uri.fromFile(f))
}

/** Выбор, откуда ставить: файловый менеджер, папка, файл, приём по Wi‑Fi, интернет. Результат уходит в окно установки. */
@Composable
fun InstallHub(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    val vm: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var stage by remember { mutableStateOf("menu") }
    var files by remember { mutableStateOf<List<ApkFile>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) files = Batch.listApks(ctx, uri)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { busy = true; files = runCatching { pickedToApks(ctx, uri) }.getOrDefault(emptyList()); busy = false }
    }
    when {
        files != null -> InstallDialog(files!!) { files = null; onClose() }
        stage == "browser" -> FileBrowser(onApks = { files = it; stage = "menu" }, onClose = { stage = "menu" })
        stage == "wifi" -> UploadDialog(onApks = { files = it; stage = "menu" }, onClose = { stage = "menu" })
        stage == "net" -> NetDialog { stage = "menu" }
        else -> HDialog(onDismissRequest = onClose) {
            Column(
                Modifier.clip(RoundedCornerShape(20.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(18.dp).fillMaxWidth()
                    .heightIn(max = 640.dp).barScroll(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HintTitle(stringResource(R.string.inst_hub_title), stringResource(R.string.inst_hub_hint), titleColor = t.text, titleSize = 16.sp, titleWeight = FontWeight.Bold, hintColor = t.hint)
                if (busy) Text(stringResource(R.string.working), color = t.accent, fontSize = 13.sp)
                Chip(stringResource(R.string.inst_pick_file), true, Modifier.fillMaxWidth()) {
                    runCatching { pickFile.launch(arrayOf("*/*")) }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.no_picker), Toast.LENGTH_LONG).show() }
                }
                Chip(stringResource(R.string.inst_pick_folder), false, Modifier.fillMaxWidth()) {
                    runCatching { pickTree.launch(null) }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.no_picker), Toast.LENGTH_LONG).show() }
                }
                Chip(stringResource(R.string.fb_open), false, Modifier.fillMaxWidth()) { stage = "browser" }
                Chip(stringResource(R.string.wifi_open), false, Modifier.fillMaxWidth()) { stage = "wifi" }
                Chip(stringResource(R.string.net_open), false, Modifier.fillMaxWidth()) { stage = "net" }
                Chip(stringResource(R.string.inst_menu_app), false, Modifier.fillMaxWidth()) { vm.installMenuApp(); onClose() }
                Chip(stringResource(R.string.btn_cancel), false) { onClose() }
            }
        }
    }
}

/**
 * Список найденных пакетов с отметками: у каждого значок, название, версии «было → стало», вид установки
 * (новая / обновление / та же / понижение), подпись и совместимость; для одного пакета ещё разрешения.
 * Установка тихо по ADB с полосой прогресса и итогом.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun InstallDialog(files: List<ApkFile>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf(files.map { it.name }.toSet()) }
    var busy by remember { mutableStateOf(false) }
    val run = remember { InstRun() }
    var metas by remember { mutableStateOf<Map<String, ApkMeta?>>(emptyMap()) }
    androidx.compose.runtime.LaunchedEffect(files) {
        metas = withContext(Dispatchers.IO) { files.take(60).associate { it.name to runCatching { ApkInfo.readFor(ctx, it) }.getOrNull() } }
    }
    val showProgress = busy || run.running || run.finished
    HDialog(onDismissRequest = { if (!busy && !run.running) { onClose() } }) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(18.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!showProgress) {
                Text(stringResource(R.string.install_title, files.size), color = t.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (files.isEmpty()) Text(stringResource(R.string.install_none), color = t.sub, fontSize = 14.sp)
                Column(Modifier.heightIn(max = 420.dp).barScroll()) {
                    files.forEach { f ->
                        val on = f.name in chosen
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp))
                                .toggleable(on, onValueChange = { v -> chosen = if (v) chosen + f.name else chosen - f.name })
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ApkInfoBlock(f, metas[f.name], full = files.size == 1, modifier = Modifier.weight(1f))
                            Switch(on, null)
                        }
                    }
                }
                Text(stringResource(if (Adb.isConnected) R.string.install_silent else R.string.conn_need), color = t.hint, fontSize = 12.sp)
            } else {
                InstProgressView(run)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (run.finished) {
                    Chip(stringResource(R.string.ii_close), true) { Hooks.reloadApps(); onClose() }
                } else {
                    if (!showProgress) Chip(stringResource(R.string.btn_cancel), false) { onClose() }
                    if (!showProgress) Chip(stringResource(R.string.install_go, chosen.size), true) {
                        val picked = files.filter { it.name in chosen }
                        if (picked.isEmpty() || busy) return@Chip
                        busy = true
                        scope.launch {
                            if (!AutoAdb.ensure()) { busy = false; Toast.makeText(ctx, AutoAdb.hint(), Toast.LENGTH_LONG).show(); return@launch }
                            installWithProgress(ctx, picked, metas, run)
                            busy = false
                        }
                    }
                }
            }
        }
    }
}
