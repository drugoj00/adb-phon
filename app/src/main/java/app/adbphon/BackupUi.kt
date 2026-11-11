package app.adbphon

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Источник файла копии в списке восстановления. */
private class BkFile(val name: String, val time: Long, val read: () -> ByteArray)

private sealed class BkState {
    object Idle : BkState()
    object Create : BkState()
    class Created(val text: String, val file: File?) : BkState()
    class List(val items: kotlin.collections.List<BkFile>) : BkState()
    class Pass(val data: ByteArray, val name: String, val wrong: Boolean) : BkState()
    class Confirm(val json: JSONObject, val name: String) : BkState()
    class Done(val text: String) : BkState()
    class Error(val text: String) : BkState()
}

private fun listBackups(ctx: Context): List<BkFile> {
    val out = ArrayList<BkFile>()
    // выбранная папка (SAF)
    SettingsBackup.folder(ctx)?.let { tree ->
        runCatching {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            ctx.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(1) ?: continue
                    if (n.endsWith("." + AppBackup.EXT)) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                        out += BkFile(n, c.getLong(2)) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
                    }
                }
            }
        }
    }
    // Загрузки
    runCatching {
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).listFiles { f -> f.name.startsWith("ADB-phon-backup-") && f.name.endsWith("." + AppBackup.EXT) }
            ?.forEach { f -> if (out.none { it.name == f.name }) out += BkFile(f.name, f.lastModified()) { f.readBytes() } }
    }
    return out.sortedByDescending { it.time }
}

private fun sumText(ctx: Context, id: Int, s: AppBackup.Summary) = ctx.getString(id, s.settings, s.links, s.clouds, s.devices)

/** Раздел «Резервная копия» в настройках: полная копия состояния программы с шифрованием парольной фразой и транзакционным восстановлением. */
@Composable
fun BackupSectionV2(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf<BkState>(BkState.Idle) }
    var folder by remember { mutableStateOf(SettingsBackup.folder(ctx)) }
    var auto by remember { mutableStateOf(ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getInt("backup_auto", 0)) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) { runCatching { SettingsBackup.setFolder(ctx, uri); folder = uri } } }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val data = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull() }
            if (data == null) st = BkState.Error(ctx.getString(R.string.bk2_bad_file, "read")) else prepare(ctx, data, uri.lastPathSegment ?: "", null) { st = it }
        }
    }
    var lastFile by remember { mutableStateOf<File?>(null) }

    Collapsible(stringResource(R.string.bk2_title)) {
        Text(stringResource(R.string.bk2_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        Text(stringResource(R.string.bk_folder, folder?.let { SettingsBackup.folderLabel(it) } ?: stringResource(R.string.bk2_default_folder)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
    }
    EqButtons(listOf(EqBtn(stringResource(R.string.bk2_make)) { st = BkState.Create }, EqBtn(stringResource(R.string.bk2_restore)) {
        scope.launch { st = BkState.List(withContext(Dispatchers.IO) { listBackups(ctx) }) }
    }))
    EqButtons(listOf(EqBtn(stringResource(R.string.bk_pick)) { pickFolder.launch(folder) }, EqBtn(stringResource(R.string.bk2_share), lastFile != null) { lastFile?.let { shareFile(ctx, it) } }))
    Text(stringResource(R.string.bk2_auto), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, fontSize = 13.sp)
    EqChoices(listOf(
        EqChoice(stringResource(R.string.bk2_auto_off), auto == 0) { auto = 0; ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE).edit().putInt("backup_auto", 0).apply() },
        EqChoice(stringResource(R.string.bk2_auto_day), auto == 1) { auto = 1; ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE).edit().putInt("backup_auto", 1).apply() },
        EqChoice(stringResource(R.string.bk2_auto_week), auto == 2) { auto = 2; ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE).edit().putInt("backup_auto", 2).apply() },
    ))
    if (AppBackup.hasRollback(ctx)) TextButton({
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { AppBackup.rollback(ctx) } }
            st = if (r.isSuccess) BkState.Done(ctx.getString(R.string.bk2_rolled_back)) else BkState.Error(ctx.getString(R.string.bk2_bad_file, r.exceptionOrNull()?.message ?: "?"))
        }
    }) { Text(stringResource(R.string.bk2_rollback), fontSize = 13.sp) }

    when (val s = st) {
        BkState.Idle -> {}
        BkState.Create -> {
            // 7.0: без вопросов и фраз: сразу полная копия (настройки, подключения, облачные диски, пароли, ключ ADB)
            androidx.compose.runtime.LaunchedEffect(Unit) {
                st = BkState.Idle
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        val (data, sum) = AppBackup.create(ctx, null)
                        val (name, file) = AppBackup.saveAnywhere(ctx, data)
                        Triple(name, file, sum)
                    }
                }
                r.onSuccess { (name, file, sum) ->
                    lastFile = file
                    st = BkState.Created(sumText(ctx, R.string.bk2_created, sum) + "\n" + ctx.getString(R.string.bk2_saved_to, name), file)
                }.onFailure { AppLog.e("ADBphon", "backup", it); st = BkState.Error(ctx.getString(R.string.bk_failed, it.message ?: "error")) }
            }
        }
        is BkState.Created -> LAlertDialog(
            onDismissRequest = { st = BkState.Idle }, title = { Text(stringResource(R.string.bk2_title)) }, text = { Text(s.text, fontSize = 14.sp) },
            confirmButton = { TextButton({ st = BkState.Idle }) { Text(stringResource(R.string.btn_close)) } },
            dismissButton = { if (s.file != null) TextButton({ shareFile(ctx, s.file) }) { Text(stringResource(R.string.bk2_share)) } },
        )
        is BkState.List -> LAlertDialog(
            onDismissRequest = { st = BkState.Idle }, title = { Text(stringResource(R.string.bk2_restore_title)) },
            text = {
                Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton({ st = BkState.Idle; pickFile.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.bk2_pick_file)) }
                    if (s.items.isEmpty()) Text(stringResource(R.string.bk2_none), fontSize = 13.sp)
                    s.items.forEach { f ->
                        Text(f.name, Modifier.fillMaxWidth().clickable { scope.launch {
                            val data = withContext(Dispatchers.IO) { runCatching { f.read() }.getOrNull() }
                            if (data == null) st = BkState.Error(ctx.getString(R.string.bk2_bad_file, "read")) else prepare(ctx, data, f.name, null) { st = it }
                        } }.padding(vertical = 8.dp), fontSize = 13.sp, maxLines = 1)
                    }
                }
            },
            confirmButton = { TextButton({ st = BkState.Idle }) { Text(stringResource(R.string.btn_close)) } },
        )
        is BkState.Pass -> {
            var p by remember { mutableStateOf("") }
            LAlertDialog(
                onDismissRequest = { st = BkState.Idle }, title = { Text(stringResource(R.string.bk2_enter_pass)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(p, { p = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.bk2_pass1b), maxLines = 1) }, visualTransformation = PasswordVisualTransformation())
                        if (s.wrong) Text(stringResource(R.string.bk2_wrong_pass), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = { TextButton({ val data = s.data; val n = s.name; scope.launch { prepare(ctx, data, n, p.toCharArray()) { st = it } } }) { Text(stringResource(R.string.btn_yes)) } },
                dismissButton = { TextButton({ st = BkState.Idle }) { Text(stringResource(R.string.btn_cancel)) } },
            )
        }
        is BkState.Confirm -> LAlertDialog(
            onDismissRequest = { st = BkState.Idle }, title = { Text(stringResource(R.string.bk2_restore_title)) },
            text = { Text(sumText(ctx, R.string.bk2_confirm, AppBackup.summarize(s.json)), fontSize = 14.sp) },
            confirmButton = {
                TextButton({
                    val j = s.json; st = BkState.Idle
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { AppBackup.restore(ctx, j) } }
                        r.onSuccess { st = BkState.Done(sumText(ctx, R.string.bk2_restored, it) + "\n" + ctx.getString(R.string.bk2_restart_hint)) }
                            .onFailure { AppLog.e("ADBphon", "restore", it); st = BkState.Error(ctx.getString(R.string.bk2_bad_file, it.message ?: "?")) }
                    }
                }) { Text(stringResource(R.string.btn_yes)) }
            },
            dismissButton = { TextButton({ st = BkState.Idle }) { Text(stringResource(R.string.btn_cancel)) } },
        )
        is BkState.Done -> LAlertDialog(
            onDismissRequest = { st = BkState.Idle }, title = { Text(stringResource(R.string.bk2_title)) }, text = { Text(s.text, fontSize = 14.sp) },
            confirmButton = { TextButton({ restartApp(ctx) }) { Text(stringResource(R.string.bk2_restart)) } },
            dismissButton = { TextButton({ st = BkState.Idle }) { Text(stringResource(R.string.btn_close)) } },
        )
        is BkState.Error -> LAlertDialog(
            onDismissRequest = { st = BkState.Idle }, title = { Text(stringResource(R.string.bk2_title)) }, text = { Text(s.text, fontSize = 14.sp, color = MaterialTheme.colorScheme.error) },
            confirmButton = { TextButton({ st = BkState.Idle }) { Text(stringResource(R.string.btn_close)) } },
        )
    }
}

/** Расшифровка и проверка файла без изменения состояния; дальше окно подтверждения, запрос фразы или понятная ошибка. */
private suspend fun prepare(ctx: Context, data: ByteArray, name: String, pass: CharArray?, set: (BkState) -> Unit) {
    val r = withContext(Dispatchers.Default) { runCatching { AppBackup.parse(data, pass) } }
    r.onSuccess { set(BkState.Confirm(it, name)) }.onFailure { e ->
        when (e) {
            is AppBackup.NeedPass -> set(BkState.Pass(data, name, false))
            is AppBackup.WrongPass -> set(BkState.Pass(data, name, true))
            else -> set(BkState.Error(ctx.getString(R.string.bk2_bad_file, e.message ?: "?")))
        }
    }
}

private fun shareFile(ctx: Context, f: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** После восстановления: перезапуск, чтобы все настройки применились. */
fun restartApp(ctx: Context) {
    val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    ctx.startActivity(i)
    Runtime.getRuntime().exit(0)
}
