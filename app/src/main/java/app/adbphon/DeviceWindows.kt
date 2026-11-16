package app.adbphon

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import app.adbphon.FloorText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 10.0: «Файлы устройства» и «Видеоокно устройства» открываются отдельными окнами в «Недавних» Android (отдельные задачи).
 * По одному окну на устройство и вид: повторное открытие возвращает уже открытое (documentLaunchMode=intoExisting и адрес устройства в data намерения).
 * В «Недавних» подпись «Имя устройства · Файлы» или «Имя устройства · Экран».
 */
object DeviceWin {
    const val SCREEN = "screen"
    const val FILES = "files"

    fun open(ctx: Context, d: AdbDev, kind: String) {
        val cls = if (kind == SCREEN) DeviceScreenActivity::class.java else DeviceFilesActivity::class.java
        val i = Intent(ctx, cls).setData(Uri.parse("adbphon-device://$kind/" + Uri.encode(d.addr)))
            .putExtra("addr", d.addr).putExtra("title", d.title).putStringArrayListExtra("addrs", ArrayList(d.addrs))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }

    /** Подпись и значок окна в «Недавних». */
    @Suppress("DEPRECATION")
    fun describe(a: android.app.Activity, label: String) {
        runCatching { a.setTaskDescription(ActivityManager.TaskDescription(label, R.mipmap.ic_launcher)) }
    }
}

/** Тема, язык и язык ввода, как у главного окна (из настроек программы). */
@Composable
internal fun DeviceWinHost(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val prefs = remember { base.getSharedPreferences("adbphon", Context.MODE_PRIVATE) }
    val lang = prefs.getString("lang", "auto") ?: "auto"
    val theme = prefs.getString("theme", "auto") ?: "auto"
    val ui = remember(lang, base) { Lang.wrap(base, lang) }
    remember(base) { AppColors.load(base); AppScale.load(base); 0 }
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    CompositionLocalProvider(LocalContext provides ui, LocalLangCode provides lang) {
        ImeGateHost {
            MaterialTheme(colorScheme = AppColors.scheme(dark, base)) {
                Surface(Modifier.fillMaxSize().imeGateKeys(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
    }
}

/** Окно экрана устройства: видео, звук, касания (то же, что окно «Видеоокно» раньше, но отдельной задачей). */
class DeviceScreenActivity : ComponentActivity() {
    private var client: AndroidScreenClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FmIo.app = applicationContext; Fm.sctx = Lang.wrap(applicationContext, getSharedPreferences("adbphon", MODE_PRIVATE).getString("lang", "auto") ?: "auto")
        val addrs = intent.getStringArrayListExtra("addrs")?.takeIf { it.isNotEmpty() } ?: intent.getStringExtra("addr")?.let { listOf(it) }
        if (addrs == null) { finishAndRemoveTask(); return }
        val title = intent.getStringExtra("title") ?: addrs[0]
        DeviceWin.describe(this, title + " · " + getString(R.string.dw_screen))
        val c = AndroidScreenClient(addrs.map { AdbDev.norm(it) }); client = c
        setContent {
            DisposableEffect(Unit) { c.start(); onDispose { c.stop() } }
            DeviceWinHost { AndroidScreenLive(c, title) { finishAndRemoveTask() } }
        }
    }

    override fun onDestroy() { client?.stop(); super.onDestroy() }
}

/** Окно файлов устройства: свой простой обзор папок (список, вверх, обновить, скачать на телефон, новая папка, удалить). */
class DeviceFilesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FmIo.app = applicationContext; Fm.sctx = Lang.wrap(applicationContext, getSharedPreferences("adbphon", MODE_PRIVATE).getString("lang", "auto") ?: "auto")
        val addr = intent.getStringExtra("addr") ?: run { finishAndRemoveTask(); return }
        val title = intent.getStringExtra("title") ?: addr
        DeviceWin.describe(this, title + " · " + getString(R.string.dw_files))
        val a = AdbDev.norm(addr)
        val fs = NetConn("andr", a.substringBefore(':'), a.substringAfter(':', "5555").toIntOrNull() ?: 5555, "", "").fs()
        setContent { DeviceWinHost { DeviceFilesScreen(fs, title) { finishAndRemoveTask() } } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DeviceFilesScreen(fs: RemoteFs, title: String, close: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("/sdcard") }
    var items by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var act by remember { mutableStateOf<FsEntry?>(null) }
    var newDir by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(path, reload) {
        loading = true; error = ""
        val r = runCatching { withContext(Dispatchers.IO) { fs.list(path) } }
        loading = false
        r.onSuccess { l -> items = l.sortedWith(compareBy<FsEntry> { !it.isDir }.then { a, b -> SortEngine.nameCmp(a.name, b.name, true, true) }) }   // 10.0 (chk): имена как в «Файлах» (file2 перед file10, кириллица по алфавиту)
            .onFailure { items = emptyList(); error = it.message ?: it.javaClass.simpleName }
    }
    fun up() { val p = path.trimEnd('/').substringBeforeLast('/', ""); path = if (p.isEmpty()) "/" else p }
    BackHandler { if (path != "/" && path != "/sdcard") up() else close() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton({ close() }) { Text(stringResource(R.string.btn_close), fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton({ up() }, enabled = path != "/") { Text(stringResource(R.string.dw_up), fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton({ reload++ }) { Text(stringResource(R.string.dw_refresh), fontSize = 12.sp, maxLines = 1, softWrap = false) }
            OutlinedButton({ newDir = true }) { Text(stringResource(R.string.fm_new_folder), fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
        Text(title + "  ·  " + path, Modifier.padding(horizontal = 12.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (note.isNotEmpty()) Text(note, Modifier.padding(horizontal = 12.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .8f))
        if (error.isNotEmpty()) Text(error, Modifier.padding(12.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        if (loading) Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Text("  " + stringResource(R.string.spc_connecting), fontSize = 13.sp) }
        else if (items.isEmpty() && error.isEmpty()) Text(stringResource(R.string.dw_empty), Modifier.padding(12.dp), fontSize = 13.sp)
        // 10.0 (mouse): полоса прокрутки и с ней перетаскивание мышью и аэромышью (раньше список листался только пальцем и колесом)
        val dfState = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(path) { runCatching { dfState.scrollToItem(0) } }
        LazyColumn(Modifier.fillMaxSize().verticalScrollbar(dfState), state = dfState, contentPadding = sbPadding()) {
            items(items, key = { it.path }) { e ->
                Row(Modifier.fillMaxWidth().mouseSecondary { act = e }.combinedClickable(onClick = { if (e.isDir) path = e.path else act = e }, onLongClick = { act = e }).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(if (e.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile, null, Modifier.size(26.dp), tint = if (e.isDir) androidx.compose.ui.graphics.Color(0xFFFFB74D) else MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!e.isDir) Text(android.text.format.Formatter.formatShortFileSize(ctx, e.size), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                    }
                }
            }
        }
    }

    act?.let { e ->
        LAlertDialog(onDismissRequest = { act = null }, title = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { Text(e.path, fontSize = 12.sp) },
            confirmButton = {
                if (!e.isDir) TextButton({
                    act = null
                    scope.launch {
                        note = ctx.getString(R.string.spc_connecting)
                        val r = runCatching {
                            withContext(Dispatchers.IO) {
                                val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).also { it.mkdirs() }
                                var out = File(dir, e.name); var n = 2
                                while (out.exists()) { out = File(dir, e.name.substringBeforeLast('.', e.name) + " ($n)" + (e.name.substringAfterLast('.', "").let { x -> if (x.isEmpty()) "" else ".$x" })); n++ }
                                fs.copyTo(e.path, out); out.name
                            }
                        }
                        note = r.fold({ ctx.getString(R.string.dw_saved, it) }, { ctx.getString(R.string.fm_error, it.message ?: "?") })
                    }
                }) { Text(stringResource(R.string.dw_download)) }
            },
            dismissButton = {
                Row {
                    TextButton({
                        act = null
                        scope.launch {
                            val r = runCatching { withContext(Dispatchers.IO) { fs.removeRemote(e.path, e.isDir) } }
                            note = r.fold({ "" }, { ctx.getString(R.string.fm_error, it.message ?: "?") }); reload++
                        }
                    }) { Text(stringResource(R.string.fm_delete)) }
                    TextButton({ act = null }) { Text(stringResource(R.string.btn_cancel)) }
                }
            })
    }
    if (newDir) {
        var name by remember { mutableStateOf("") }
        LAlertDialog(ime = true, onDismissRequest = { newDir = false }, title = { Text(stringResource(R.string.fm_new_folder)) },
            text = { androidx.compose.material3.OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true) },
            confirmButton = {
                TextButton({
                    val n = name.trim().takeIf { it.isNotEmpty() && '/' !in it }
                    newDir = false
                    if (n != null) scope.launch {
                        val r = runCatching { withContext(Dispatchers.IO) { fs.mkdirRemote(path.trimEnd('/') + "/" + n) } }
                        note = r.fold({ "" }, { ctx.getString(R.string.fm_error, it.message ?: "?") }); reload++
                    }
                }) { Text(stringResource(R.string.dw_create)) }
            },
            dismissButton = { TextButton({ newDir = false }) { Text(stringResource(R.string.btn_cancel)) } })
    }
}
