package app.adbphon

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Строка верхнего уровня окна выбора: память, SD-карта, USB, сетевой источник, облако. */
private class BkRoot(val title: String, val sub: String, val icon: ImageVector, val place: BkPlace)

/** Подключённые SD-карты и USB-накопители (как «Места» в «Файлах»), без ADB. */
fun bkVolumes(ctx: Context): List<Triple<String, String, Boolean>> = runCatching {
    val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
    sm.storageVolumes.filter { !it.isPrimary && (it.state == android.os.Environment.MEDIA_MOUNTED || it.state == android.os.Environment.MEDIA_MOUNTED_READ_ONLY) }.mapNotNull { v ->
        val dir = (if (android.os.Build.VERSION.SDK_INT >= 30) v.directory?.absolutePath else null)
            ?: runCatching { v.javaClass.getMethod("getPath").invoke(v) as String }.getOrNull() ?: v.uuid?.let { "/storage/$it" } ?: return@mapNotNull null
        val desc = v.getDescription(ctx) ?: v.uuid.orEmpty()
        Triple(desc, dir, desc.contains("USB", true))
    }
}.getOrDefault(emptyList())


/** Адреса системного окна выбора (Storage Access Framework) → путь файловой системы: память «primary», SD-карта и USB «XXXX-XXXX», «raw:». null: не файловое место (облачный провайдер и т.п.). */
private fun bkSafIdPath(id: String): String? {
    if (id.startsWith("raw:")) return id.removePrefix("raw:").takeIf { it.startsWith("/") }
    val i = id.indexOf(':'); if (i < 0) return null
    val vol = id.substring(0, i); val rel = id.substring(i + 1).trim('/')
    val base = if (vol == "primary") "/storage/emulated/0" else "/storage/$vol"
    return if (rel.isEmpty()) base else "$base/$rel"
}
fun bkSafTreePath(uri: android.net.Uri): String? = runCatching { bkSafIdPath(android.provider.DocumentsContract.getTreeDocumentId(uri)) }.getOrNull()
fun bkSafDocPath(uri: android.net.Uri): String? = runCatching { bkSafIdPath(android.provider.DocumentsContract.getDocumentId(uri)) }.getOrNull()

/** Начальная папка сетевого подключения: общий ресурс или папка из записи подключения, иначе корень источника. */
private fun netStart(c: NetConn, fs: RemoteFs): String =
    c.path.trim().trim('/').takeIf { it.isNotEmpty() }?.let { if (c.kind == "smb") "smb://" + c.host.trim().trim('/') + "/" + it + "/" else if (c.kind == "cloud") null else "/$it" } ?: fs.root

/**
 * Выбор места для копии или файла копии, как в файловом менеджере ADB-phon: память устройства, SD-карта, USB, сохранённые сетевые источники
 * (SMB, SFTP, FTP, WebDAV, ПК) и облака. [ext] null: выбор папки («Выбрать эту папку»); иначе показываются файлы с этим расширением, нажатие выбирает файл.
 * Окно открывается в [start] (последнее выбранное место). Пульт: все строки в фокусе, «Назад» закрывает.
 */
@Composable
fun BkPickDialog(title: String, start: BkPlace?, ext: String?, onPick: (BkPlace, String?) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cur by remember { mutableStateOf(start) }
    var open by remember { mutableStateOf<BkStore.Open?>(null) }
    var items by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    var newDir by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { open?.close() } }

    val roots = remember {
        val l = ArrayList<BkRoot>()
        l += BkRoot(ctx.getString(R.string.fm_internal), "/sdcard", Icons.Filled.SdStorage, BkPlace(true, "/sdcard"))
        bkVolumes(ctx).forEach { (t, p, usb) -> l += BkRoot(t, p, if (usb) Icons.Filled.Usb else Icons.Filled.SdCard, BkPlace(true, p)) }
        NetStore.list(ctx).filter { it.kind in BkStore.NET_KINDS }.sortedBy { if (it.kind == "cloud") 1 else 0 }.forEach { c ->
            l += BkRoot(c.title, if (c.kind == "cloud") ctx.getString(R.string.bkp_cloud) else c.kind.uppercase(), if (c.kind == "cloud") Icons.Filled.Cloud else Icons.Filled.Lan, BkPlace(false, "", c.kind, c.host, c.user))
        }
        l
    }

    // обычное системное окно выбора Android: папка (создание копии) или файл (восстановление); путь берётся из него, доступ запоминается
    val sysTree = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            val path = bkSafTreePath(uri)
            if (path == null) err = ctx.getString(R.string.bkp_saf_unsupported)
            else { runCatching { SettingsBackup.setFolder(ctx, uri) }; onPick(BkPlace(true, path), null) }
        }
    }
    val sysFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val doc = bkSafDocPath(uri)
            val parent = doc?.let { File(it).parent }
            if (doc == null || parent == null) err = ctx.getString(R.string.bkp_saf_unsupported)
            else onPick(BkPlace(true, parent), File(doc).name)
        }
    }

    LaunchedEffect(cur, tick) {
        val p = cur ?: run { items = emptyList(); err = null; return@LaunchedEffect }
        loading = true; err = null
        try {
            var o = open
            if (o == null || o.place.local != p.local || o.place.kind != p.kind || o.place.host != p.host || o.place.user != p.user) {
                o?.close(); open = null
                o = BkStore.open(ctx, p); open = o
            }
            if (!p.local && p.path.isEmpty()) {
                val c = BkStore.conn(ctx, p)
                val sp = if (c != null && o.fs != null) netStart(c, o.fs) else "/"
                cur = p.copy(path = sp); return@LaunchedEffect
            }
            items = BkStore.list(BkStore.Open(ctx, p, o.fs)).filter { !it.name.startsWith(".") && (it.isDir || (ext != null && it.name.lowercase().endsWith(ext))) }
                .sortedWith(compareByDescending<FsEntry> { it.isDir }.thenByDescending { if (it.isDir) "" else it.name }.thenBy { it.name.lowercase() })
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { items = emptyList(); err = e.message ?: e.javaClass.simpleName }
        loading = false
    }

    fun up() {
        val p = cur ?: return
        if (p.local) {
            val isRoot = roots.any { it.place.local && it.place.path.trimEnd('/') == p.path.trimEnd('/') }
            cur = if (isRoot) null else File(p.path).parent?.let { p.copy(path = it) }
        } else {
            val par = open?.fs?.parent(p.path)
            cur = if (par == null || par == p.path) null else p.copy(path = par)
        }
    }

    LAlertDialog(
        onDismissRequest = onClose,
        title = { Text(title, fontSize = 17.sp) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val p = cur
                Text(if (p == null) stringResource(R.string.bkp_pick_where) else BkStore.display(ctx, p), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (loading) Text(stringResource(R.string.bkp_working), fontSize = 12.sp)
                err?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                BarLazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    @Composable
                    fun row(icon: ImageVector, t: String, sub: String?, onClick: () -> Unit) {
                        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                                Text(t, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!sub.isNullOrEmpty()) Text(sub, fontSize = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                            }
                        }
                    }
                    if (p == null) {
                        item { row(Icons.Filled.Folder, stringResource(if (ext == null) R.string.bkp_sys_pick else R.string.bkp_sys_pick_file), stringResource(R.string.bkp_sys_pick_sub)) { if (ext == null) sysTree.launch(null) else sysFile.launch(arrayOf("*/*")) } }
                        items(roots.size) { i -> val r = roots[i]; row(r.icon, r.title, r.sub) { cur = r.place } }
                    } else {
                        item { row(Icons.Filled.ArrowUpward, ".. " + stringResource(R.string.start_up), null) { up() } }
                        items(items.size) { i ->
                            val e = items[i]
                            if (e.isDir) row(Icons.Filled.Folder, e.name, null) { cur = p.copy(path = p.child(e.name)) }
                            else row(Icons.Filled.Description, e.name, BkStore.size(ctx, e.size) + if (e.date.isNotEmpty()) "  ·  " + e.date else "") { onPick(p, e.name) }
                        }
                        if (!loading && err == null && items.isEmpty()) item { Text(stringResource(if (ext != null) R.string.bkp_pick_no_files else R.string.start_no_subdirs), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), modifier = Modifier.padding(vertical = 8.dp)) }
                    }
                }
                if (p != null && ext == null) TextButton({ newDir = true }) { Text(stringResource(R.string.bkp_new_folder), fontSize = 13.sp) }
            }
        },
        confirmButton = {
            if (ext == null) TextButton({ cur?.let { onPick(it, null) } }, enabled = cur != null && !loading && err == null) { Text(stringResource(R.string.start_pick_this)) }
            else TextButton(onClose) { Text(stringResource(R.string.btn_cancel)) }
        },
        dismissButton = if (ext == null) ({ TextButton(onClose) { Text(stringResource(R.string.btn_cancel)) } }) else null,
    )

    if (newDir) {
        var name by remember { mutableStateOf("ADB-phon") }
        var nerr by remember { mutableStateOf<String?>(null) }
        LAlertDialog(
            onDismissRequest = { newDir = false },
            title = { Text(stringResource(R.string.bkp_new_folder)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(name, { name = it; nerr = null }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.fm_name), maxLines = 1) })
                    nerr?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton({
                    val p = cur ?: return@TextButton
                    val n = BkNames.safe(name)
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                val fs = open?.fs
                                when {
                                    p.local -> { val d = File(p.path, n); if (!d.mkdirs() && !d.isDirectory) throw java.io.IOException(ctx.getString(R.string.fm_err_denied)) }
                                    fs is KeepFs -> fs.mkdirRemote(p.child(n))
                                    else -> throw java.io.IOException(ctx.getString(R.string.fm_err_unsupported))
                                }
                            }
                        }
                        r.onSuccess { newDir = false; cur = p.copy(path = p.child(n)) }.onFailure { nerr = it.message ?: it.javaClass.simpleName }
                    }
                }) { Text(stringResource(R.string.btn_yes)) }
            },
            dismissButton = { TextButton({ newDir = false }) { Text(stringResource(R.string.btn_cancel)) } },
        )
    }
}
