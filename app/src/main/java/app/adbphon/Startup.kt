package app.adbphon

import android.content.Context
import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Что открывать при запуске. Вкладка: «last» (последняя использованная) или номер 0 Связь, 1 Софт, 2 Файлы, 3 Пульт, 4 Ещё; по умолчанию 2 «Файлы».
 * Папка в «Файлах» (для каждой панели отдельно): «last» (по умолчанию), «internal», «download», «dcim», «custom» (своя папка панели).
 */
object Startup {
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE)
    private fun fm(ctx: Context) = ctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE)

    fun tabMode(ctx: Context): String = p(ctx).getString("start_tab", "2") ?: "2"
    fun setTabMode(ctx: Context, v: String) { p(ctx).edit().putString("start_tab", v).apply() }
    fun saveLastTab(ctx: Context, tab: Int) { p(ctx).edit().putInt("last_tab", tab).apply() }
    fun initialTab(ctx: Context): Int {
        val m = tabMode(ctx)
        return (if (m == "last") p(ctx).getInt("last_tab", 2) else m.toIntOrNull() ?: 2).coerceIn(0, 4)
    }

    fun folderMode(ctx: Context): String = p(ctx).getString("start_folder", "last") ?: "last"
    fun setFolderMode(ctx: Context, v: String) { p(ctx).edit().putString("start_folder", v).apply() }
    fun customPath(ctx: Context, pane: Int): String = p(ctx).getString("start_folder_$pane", "").orEmpty()
    fun setCustomPath(ctx: Context, pane: Int, path: String) { p(ctx).edit().putString("start_folder_$pane", path).apply() }

    fun internalRoot(): String = "/sdcard"

    /** Запомнить последнюю папку панели для источника (local, adb, smb…). */
    fun saveLast(ctx: Context, pane: Int, src: String, path: String) { fm(ctx).edit().putString("last_p${pane}_$src", path).apply() }
    fun lastFor(ctx: Context, pane: Int, src: String): String? = fm(ctx).getString("last_p${pane}_$src", null)
    /** Какой источник был у панели: «local» или «net:вид|хост|пользователь». ADB не запоминается (нужна связь). */
    fun saveSrc(ctx: Context, pane: Int, src: String) { fm(ctx).edit().putString("last_src_p$pane", src).apply() }
    fun savedSrc(ctx: Context, pane: Int): String? = fm(ctx).getString("last_src_p$pane", null)

    /** Где был фокус в сетевом источнике или облаке: источник, папка, вид корня облака («top», «trash», «drive») и имя последнего выбранного элемента. */
    class Focus(val key: String, val path: String, val view: String, val name: String)
    fun saveFocus(ctx: Context, pane: Int, f: Focus) { fm(ctx).edit().putString("focus_p$pane", listOf(f.key, f.path, f.view, f.name).joinToString("\t")).apply() }
    fun loadFocus(ctx: Context, pane: Int): Focus? {
        val a = (fm(ctx).getString("focus_p$pane", null) ?: return null).split('\t')
        return if (a.size >= 4) Focus(a[0], a[1], a[2], a[3]) else null
    }

    /** Папка панели при запуске (источник «local»). Если сохранённая папка исчезла, откат к внутренней памяти. */
    fun startPath(ctx: Context, pane: Int, default: String): String {
        val root = internalRoot()
        val want = when (folderMode(ctx)) {
            "internal" -> root
            "download" -> "$root/Download"
            "dcim" -> "$root/DCIM"
            "custom" -> customPath(ctx, pane).ifBlank { default }
            else -> lastFor(ctx, pane, "local") ?: default
        }
        return if (runCatching { File(want).isDirectory }.getOrDefault(false)) want else root
    }
}

/** Блок «Запуск» в настройках приложения. */
@Composable
fun StartupSection(ctx: Context) {
    var tab by remember { mutableStateOf(Startup.tabMode(ctx)) }
    var mode by remember { mutableStateOf(Startup.folderMode(ctx)) }
    var pick by remember { mutableStateOf<Int?>(null) }
    var l by remember { mutableStateOf(Startup.customPath(ctx, 0)) }
    var r by remember { mutableStateOf(Startup.customPath(ctx, 1)) }
    var showSz by remember { mutableStateOf(Fm.showSize) }
    LaunchedEffect(Unit) { showSz = ctx.getSharedPreferences("fm", Context.MODE_PRIVATE).getBoolean("show_size", true) }
    SettingSwitch(showSz, { showSz = it; Fm.setShowSize(ctx, it) }, stringResource(R.string.set_show_size), stringResource(R.string.set_show_size_hint))
    Collapsible(stringResource(R.string.start_title), bold = true) {
        Text(stringResource(R.string.start_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
    }
    Text(stringResource(R.string.start_tab), fontWeight = FontWeight.Medium, fontSize = 13.sp)
    val tabs = listOf("last" to R.string.start_last, "0" to R.string.tab_connect, "1" to R.string.tab_apps, "2" to R.string.tab_files, "3" to R.string.tab_remote, "4" to R.string.tab_more)
    EqChoices(tabs.map { (k, s) -> EqChoice(stringResource(s), tab == k) { tab = k; Startup.setTabMode(ctx, k) } }, perRow = 3)
    Text(stringResource(R.string.start_folder), fontWeight = FontWeight.Medium, fontSize = 13.sp)
    val folders = listOf("last" to R.string.start_f_last, "internal" to R.string.start_f_internal, "download" to R.string.start_f_download, "dcim" to R.string.start_f_dcim, "custom" to R.string.start_f_pick)
    EqChoices(folders.map { (k, s) -> EqChoice(stringResource(s), mode == k) { mode = k; Startup.setFolderMode(ctx, k); if (k == "custom" && Startup.customPath(ctx, 0).isBlank()) pick = 0 } }, perRow = 3)
    if (mode == "custom") {
        EqButtons(listOf(
            EqBtn(stringResource(R.string.start_left, l.ifBlank { "/" }.substringAfterLast('/').ifBlank { "/" })) { pick = 0 },
            EqBtn(stringResource(R.string.start_right, r.ifBlank { "/" }.substringAfterLast('/').ifBlank { "/" })) { pick = 1 },
        ))
    }
    pick?.let { pane ->
        FolderPickDialog(Startup.customPath(ctx, pane).ifBlank { Startup.internalRoot() }, { path ->
            Startup.setCustomPath(ctx, pane, path); if (pane == 0) l = path else r = path; pick = null
        }) { pick = null }
    }
}

/** Выбор папки из дерева внутренней памяти: вход в папку нажатием, «Вверх», «Выбрать эту папку». */
@Composable
fun FolderPickDialog(start: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    val root = remember { Startup.internalRoot() }
    var cur by remember { mutableStateOf(if (File(start).isDirectory) start else root) }
    val dirs = remember(cur) { runCatching { File(cur).listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() }.orEmpty() }.getOrDefault(emptyList()) }
    LAlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.start_pick_title), fontSize = 17.sp) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(cur, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 2)
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).barScroll()) {
                    if (cur != "/" && cur != root) Row(Modifier.fillMaxWidth().clickable { cur = File(cur).parent ?: root }.padding(vertical = 10.dp)) { Text("..  " + stringResource(R.string.start_up), fontSize = 14.sp) }
                    dirs.forEach { d -> Row(Modifier.fillMaxWidth().clickable { cur = d.path }.padding(vertical = 10.dp)) { Text(d.name, fontSize = 14.sp, maxLines = 1) } }
                    if (dirs.isEmpty()) Text(stringResource(R.string.start_no_subdirs), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        },
        confirmButton = { TextButton({ onPick(cur) }) { Text(stringResource(R.string.start_pick_this)) } },
        dismissButton = { TextButton(onClose) { Text(stringResource(R.string.btn_cancel)) } },
    )
}
