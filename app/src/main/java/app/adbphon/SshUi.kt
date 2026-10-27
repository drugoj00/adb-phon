package app.adbphon

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Состояние вкладки SSH (живёт, пока открыто приложение). */
object SshModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var connected by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var title by mutableStateOf("")
    var host by mutableStateOf("")
    var port by mutableStateOf("22")
    var user by mutableStateOf("")
    var password by mutableStateOf("")
    var mode by mutableStateOf(0)           // 0 терминал, 1 файлы
    val lines = mutableStateListOf<String>()
    var cwd by mutableStateOf("")
    var win = false                         // удалённая оболочка — cmd.exe (Windows)
    var path by mutableStateOf("")
    val files = mutableStateListOf<SshEntry>()

    private const val PREFS = "adbphon_ssh"

    fun loadSaved(ctx: Context) {
        if (host.isNotEmpty() || user.isNotEmpty()) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        host = p.getString("host", "").orEmpty()
        port = p.getString("port", "22").orEmpty().ifBlank { "22" }
        user = p.getString("user", "").orEmpty()
    }

    fun connect(ctx: Context) {
        val h = host.trim()
        val prt = port.trim().toIntOrNull() ?: 22
        if (h.isEmpty() || user.trim().isEmpty() || busy) return
        scope.launch {
            busy = true
            error = null
            Ssh.connect(ctx, h, prt, user.trim(), password).onSuccess {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("host", h).putString("port", prt.toString()).putString("user", user.trim()).apply()
                connected = true
                title = "${user.trim()}@$h" + if (prt != 22) ":$prt" else ""
                lines.clear()
                cwd = ""
                win = runCatching { Ssh.exec("echo %OS%", 8_000).contains("Windows_NT") }.getOrDefault(false)
                runCatching { path = Ssh.home() }
                refreshFiles()
            }.onFailure { error = it.message ?: it.javaClass.simpleName }
            busy = false
        }
    }

    fun disconnect() {
        scope.launch {
            Ssh.disconnect()
            connected = false
            password = ""
            files.clear()
            lines.clear()
        }
    }

    fun forget(ctx: Context) {
        Ssh.forgetHostKey(ctx, host.trim(), port.trim().toIntOrNull() ?: 22)
        error = null
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Команда выполняется отдельно; текущая папка запоминается («cd» работает между командами). */
    fun run(cmd: String) {
        if (cmd.isBlank() || busy) return
        scope.launch {
            busy = true
            lines.add("$ $cmd")
            val wrapped = if (win) {
                (if (cwd.isNotEmpty()) "cd /d \"" + cwd + "\" 2>nul & " else "") + cmd + " & echo __ADBPWD__%cd%"
            } else {
                (if (cwd.isNotEmpty()) "cd " + q(cwd) + " 2>/dev/null\n" else "") + cmd + "\n__rc=\$?\necho \"__ADBPWD__\$(pwd)\"\n"
            }
            val out = runCatching { Ssh.exec(wrapped) }.getOrElse { "! " + (it.message ?: it.javaClass.simpleName) }
            val rows = out.trimEnd().split("\n")
            val marker = rows.lastOrNull { it.startsWith("__ADBPWD__") }
            if (marker != null) cwd = marker.removePrefix("__ADBPWD__").trim()
            val text = rows.filter { !it.startsWith("__ADBPWD__") }.joinToString("\n").trimEnd()
            if (text.isNotEmpty()) lines.add(text)
            busy = false
        }
    }

    fun refreshFiles(to: String = path) {
        scope.launch {
            busy = true
            runCatching { Ssh.list(to.ifBlank { "." }) }.onSuccess { (real, list) ->
                path = real
                files.clear()
                files.addAll(list)
            }.onFailure { lines.add("! " + (it.message ?: it.javaClass.simpleName)) }
            busy = false
        }
    }

    fun up() {
        val parent = path.trimEnd('/').substringBeforeLast('/', "/").ifEmpty { "/" }
        refreshFiles(parent)
    }

    fun open(e: SshEntry) = refreshFiles(path.trimEnd('/') + "/" + e.name)

    private fun remote(e: SshEntry) = path.trimEnd('/') + "/" + e.name

    fun download(ctx: Context, e: SshEntry, uri: Uri) {
        scope.launch {
            busy = true
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)!!.use { Ssh.download(remote(e), it) }
                }
            }
            Toast.makeText(ctx, if (r.isSuccess) ctx.getString(R.string.ssh_done) else (r.exceptionOrNull()?.message ?: "error"), Toast.LENGTH_LONG).show()
            busy = false
        }
    }

    fun upload(ctx: Context, uri: Uri) {
        scope.launch {
            busy = true
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "file"
            val r = runCatching {
                withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { Ssh.upload(it, path.trimEnd('/') + "/" + name) } }
            }
            Toast.makeText(ctx, if (r.isSuccess) ctx.getString(R.string.ssh_done) else (r.exceptionOrNull()?.message ?: "error"), Toast.LENGTH_LONG).show()
            busy = false
            refreshFiles()
        }
    }

    fun delete(e: SshEntry) {
        scope.launch {
            busy = true
            runCatching { Ssh.delete(remote(e), e.isDir) }.onFailure { lines.add("! " + (it.message ?: it.javaClass.simpleName)) }
            busy = false
            refreshFiles()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SshScreen() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { SshModel.loadSaved(ctx) }
    if (!SshModel.connected) {
        SshLogin(ctx)
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(SshModel.title, Modifier.weight(1f), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton({ SshModel.disconnect() }) { Text(stringResource(R.string.ssh_disconnect), fontSize = 12.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(SshModel.mode == 0, { SshModel.mode = 0 }, { Text(stringResource(R.string.ssh_term)) })
            FilterChip(SshModel.mode == 1, { SshModel.mode = 1 }, { Text(stringResource(R.string.ssh_files)) })
        }
        if (SshModel.mode == 0) SshTerminal() else SshFiles(ctx)
    }
}

@Composable
private fun SshLogin(ctx: Context) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ssh_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
        OutlinedTextField(SshModel.host, { SshModel.host = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.ssh_host)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(SshModel.user, { SshModel.user = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.ssh_user)) })
            OutlinedTextField(SshModel.port, { SshModel.port = it.filter { c -> c.isDigit() }.take(5) }, Modifier.weight(0.5f), singleLine = true,
                label = { Text(stringResource(R.string.ssh_port)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        OutlinedTextField(SshModel.password, { SshModel.password = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.ssh_pass)) },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Button({ SshModel.connect(ctx) }, enabled = !SshModel.busy && SshModel.host.isNotBlank() && SshModel.user.isNotBlank()) {
            Text(stringResource(if (SshModel.busy) R.string.ssh_connecting else R.string.ssh_connect))
        }
        SshModel.error?.let { e ->
            Text(e, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            if (e.contains("HostKey", true) || e.contains("host key", true) || e.contains("IDENTIFICATION", true)) {
                Text(stringResource(R.string.ssh_changed), fontSize = 12.sp)
                OutlinedButton({ SshModel.forget(ctx) }) { Text(stringResource(R.string.ssh_forget)) }
            }
        }
    }
}

@Composable
private fun SshTerminal() {
    var cmd by remember { mutableStateOf("") }
    val state = rememberLazyListState()
    LaunchedEffect(SshModel.lines.size) { if (SshModel.lines.isNotEmpty()) state.animateScrollToItem(SshModel.lines.size - 1) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (SshModel.cwd.isNotEmpty()) Text(SshModel.cwd, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state) {
            items(SshModel.lines.size) { i -> Text(SshModel.lines[i], fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(cmd, { cmd = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text(stringResource(R.string.ssh_cmd_hint)) })
            Button({ SshModel.run(cmd); cmd = "" }, enabled = !SshModel.busy && cmd.isNotBlank()) { Text(stringResource(R.string.ssh_run)) }
        }
        TextButton({ SshModel.lines.clear() }) { Text(stringResource(R.string.ssh_clear), fontSize = 12.sp) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SshFiles(ctx: Context) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    var deleteFor by remember { mutableStateOf<SshEntry?>(null) }
    var pending by remember { mutableStateOf<SshEntry?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val e = pending
        if (uri != null && e != null) SshModel.download(ctx, e, uri)
        pending = null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) SshModel.upload(ctx, uri) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ SshModel.up() }) { Icon(Icons.Filled.ArrowUpward, stringResource(R.string.files_up)) }
            Text(SshModel.path, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton({ picker.launch(arrayOf("*/*")) }, enabled = !SshModel.busy) { Text(stringResource(R.string.ssh_upload), fontSize = 12.sp) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(SshModel.files, key = { it.name }) { f ->
                Box {
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(onClick = { if (f.isDir) SshModel.open(f) else menuFor = f.name }, onLongClick = { menuFor = f.name })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (f.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(f.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!f.isDir) Text(sizeText(f.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                        }
                    }
                    DropdownMenu(menuFor == f.name, { menuFor = null }) {
                        if (!f.isDir) DropdownMenuItem({ Text(stringResource(R.string.ssh_download)) }, { menuFor = null; pending = f; saver.launch(f.name) })
                        DropdownMenuItem({ Text(stringResource(R.string.ssh_delete)) }, { menuFor = null; deleteFor = f })
                    }
                }
            }
        }
    }
    deleteFor?.let { f ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text(stringResource(R.string.ssh_delete)) },
            text = { Text(stringResource(R.string.ssh_confirm_delete, f.name)) },
            confirmButton = { TextButton({ SshModel.delete(f); deleteFor = null }) { Text(stringResource(R.string.ssh_delete)) } },
            dismissButton = { TextButton({ deleteFor = null }) { Text(stringResource(R.string.btn_cancel)) } },
        )
    }
}

private fun sizeText(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}
