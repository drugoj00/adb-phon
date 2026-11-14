@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.adbphon

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Usb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Запись списка файлов панели. */
data class FmItem(val name: String, val isDir: Boolean, val size: Long, val date: String) {
    val hidden get() = name.startsWith(".")
    val ext get() = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
}

/** Одна панель двухпанельного менеджера: путь, список, выделение, возврат назад. */
/** Естественное сравнение имён: числа сравниваются как числа (1, 2, 10), регистр не важен. */
fun naturalCmp(a: String, b: String): Int {
    val x = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList(); val y = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(x.size, y.size)) {
        val c = if (x[i][0].isDigit() && y[i][0].isDigit()) (x[i].trimStart('0').padStart(20, '0')).compareTo(y[i].trimStart('0').padStart(20, '0')) else x[i].compareTo(y[i])
        if (c != 0) return c
    }
    return x.size - y.size
}

/** Сохранённое место облака или сети: подключение, папка, вид корня облака и имя элемента с фокусом. */
class FmParked(val conn: NetConn, val path: String, val view: String, val name: String)

class FmPane(val id: Int, start: String) {
    /** Номер последней загрузки списка: ответ более старой загрузки (например корня источника) не затирает более новую (папку из подключения). */
    @Volatile var loadGen = 0
    /** Источник: «local» (напрямую, без ADB), «adb» (оболочка ADB), «smb» (сетевая папка). */
    var src by mutableStateOf("local")
    var smb: RemoteFs? = null
    /** Панель сама перешла в режим ADB/root из-за ограниченной папки; из обычной папки вернётся в «local». */
    var auto = false
    /** Короткий статус в строке панели («подключаюсь…»). */
    var status by mutableStateOf<String?>(null)
    /** Единственный шаг, если автоподключение не вышло: «wifi» или «pair». */
    var fix by mutableStateOf<String?>(null)
    var path by mutableStateOf(start)
    var items by mutableStateOf<List<FmItem>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var selected by mutableStateOf<Set<String>>(emptySet())
    var menuFor by mutableStateOf<String?>(null)
    var scrollToName by mutableStateOf<String?>(null)
    /** Облако или сеть, которые не удалось пересканировать при запуске (или ещё сканируются): панель показывает сохранённое место и подпись «не подключено», долгое нажатие даёт «Переподключиться». */
    var parked by mutableStateOf<FmParked?>(null)
    var parkedErr by mutableStateOf<String?>(null)
    /** Последний выбранный или нажатый элемент верхнего уровня (запоминается для облаков и сетей: после перезапуска фокус возвращается на него). */
    var lastName by mutableStateOf<String?>(null)
    val back = ArrayList<String>()
    /** Сетевое подключение панели (для запоминания и подсветки значка). */
    var net: NetConn? = null
    /** Режим «Дерево»: раскрытые папки (полные пути) и подгруженное содержимое. */
    val expanded = androidx.compose.runtime.mutableStateListOf<String>()
    val kids = androidx.compose.runtime.mutableStateMapOf<String, List<FmItem>>()
    /** Общая настройка сортировки этой панели (у левой и правой своя); для отдельной папки может быть своя, см. SortStore. */
    var sortCfg by mutableStateOf(SortCfg())
    /** Свёрнутые блоки в режиме «Группировать по типу» (коды групп). */
    val collapsed = androidx.compose.runtime.mutableStateListOf<String>()
    /** Панель показывает корневой экран (список памятей, сети, облаков) вместо папки. */
    var root by mutableStateOf(false)
    /** Корень облака с корзиной: «top» (две строки «Корзина» и «Облачный диск»), «trash» (список удалённого), «drive» (обычная папка). */
    var cloudView by mutableStateOf("top")
    val trashItems = androidx.compose.runtime.mutableStateListOf<CloudTrash.TItem>()
    var trashLoading by mutableStateOf(false)
    var trashErr by mutableStateOf("")
    /** Раскрытые строки корневого экрана («lan», «cloud»). */
    val rootOpen = androidx.compose.runtime.mutableStateListOf<String>()
    /** Запомненные между запусками сетевые узлы и ADB: раскрываются лениво, когда есть связь. */
    val rootPending = HashSet<String>()
    /** Выбранная строка корневого экрана (последняя нажатая; подсвечивается плашкой и полоской слева). */
    var rootSel by androidx.compose.runtime.mutableStateOf<String?>(null)
    /** Короткая заметка «Читаются теги: N из M», пока теги музыки, видео и фото дочитываются в фоне. */
    var tagNote by mutableStateOf<String?>(null)
}

/** Диалоги менеджера. */
sealed class FmDlg {
    class FolderColor(val key: String, val name: String) : FmDlg()
    class FileIcon(val key: String, val name: String) : FmDlg()
    class NewFolder(val dir: String? = null) : FmDlg()
    class NewFile(val dir: String? = null) : FmDlg()
    class NewZip(val dir: String? = null) : FmDlg()
    class Rename(val pane: FmPane, val name: String) : FmDlg()
    class Delete(val pane: FmPane, val names: List<String>, val plan: DelPlan? = null, val note: String? = null, val force: Boolean = false) : FmDlg()
    class FTrash(val confirm: String? = null) : FmDlg()
    class NeedAccess : FmDlg()
    class Mask : FmDlg()
    class Search : FmDlg()
    class SearchResult(val list: List<String>) : FmDlg()
    class View : FmDlg()
    class Sort(val pane: FmPane) : FmDlg()
    class OpenWith(val pane: FmPane, val item: FmItem) : FmDlg()
    class RootShow : FmDlg()
    class InstallFail(val message: String) : FmDlg()
    /** Ход тихой установки: полоска, «n из N», итог. */
    class InstallRunDlg(val run: InstRun) : FmDlg()
    /** Подтверждение после выбора ADB-phon установщиком: версии, подпись, разрешения; «Установить» запускает установку. */
    class InstallConfirm(val files: List<ApkFile>, val metas: Map<String, ApkMeta?>, val go: (Boolean) -> Unit) : FmDlg()
    class InstallPick(val pane: FmPane, val item: FmItem, val opts: List<InstOpt>) : FmDlg()
    class MarketPick(val pkg: String, val stores: List<Pair<String, String>>) : FmDlg()
    class ArcPass(val file: java.io.File, val arc: ArchiveFs?, val retry: () -> Unit) : FmDlg()
    class ArcConflict(val choose: (Int) -> Unit) : FmDlg()
    class ArcCharset(val arc: ArchiveFs, val pane: FmPane) : FmDlg()
    class Drop(val src: FmPane, val names: List<String>, val dst: FmPane, val dstSrc: String, val toPath: String, val label: String) : FmDlg()
    class OpenAs(val pane: FmPane, val item: FmItem) : FmDlg()
    class Bookmarks : FmDlg()
    class History : FmDlg()
    class Tree : FmDlg()
    class Storage : FmDlg()
    class WifiServer(val dir: String) : FmDlg()
    class WifiDev(val conn: NetConn?, val host: String, val name: String) : FmDlg()
    /** «Компьютеры» (10.11.2026): добавить или изменить ПК; экран ПК во весь экран. */
    class PcAdd(val host: PcHost?) : FmDlg()
    class PcScreen(val host: PcHost) : FmDlg()
    /** Группа «Устройства»: добавить или изменить (ПК или Android), экран и консоль Android-устройства. */
    class DevAdd(val pc: PcHost?, val adb: AdbDev?) : FmDlg()
    class AndroidScreen(val dev: AdbDev) : FmDlg()
    /** Звонок устройству без известного кода программы: спрашиваем код (7.6). */
    class CallCode(val name: String, val ip: String, val voice: Boolean) : FmDlg()
    class AndroidConsole(val dev: AdbDev) : FmDlg()
    class Source(val prefill: NetHost? = null) : FmDlg()
    class CloudAdd : FmDlg()
    /** Нужен модуль «ADB-phon Облака»: скачать и установить; после установки выполняется [then]. */
    class CloudModule(val upgrade: Boolean = false, val then: (() -> Unit)? = null) : FmDlg()
    class MegaAdd : FmDlg()
    class CloudForm(val entry: CEntry) : FmDlg()
    class CloudAll : FmDlg()
    /** confirm: сразу показать подтверждение (пусто: очистка всей корзины, иначе id элемента). */
    class Trash(val c: NetConn, val confirm: String? = null) : FmDlg()
    /** Мастер «Свой ключ»; [step] — с какого шага открыть (шаг «Опубликовать приложение» у Google: [OwnKeyWizard.PUBLISH_STEP]). */
    class OwnKey(val type: String, val step: Int = 0, val sharedClosed: Boolean = false) : FmDlg()
    class OwnKeyPick : FmDlg()
    class EditNet(val conn: NetConn) : FmDlg()
    class AppConfirm(val text: String, val pkg: String) : FmDlg()
    class AppAsk(val title: Int, val text: String, val ok: Int, val run: suspend () -> String) : FmDlg()
    class AppBatch(val action: String, val label: Int, val names: List<String>, val other: FmPane) : FmDlg()
    class More(val items: List<FmAction>) : FmDlg()
    class Props(val title: String, val body: String) : FmDlg()
    class Perms(val pane: FmPane, val names: List<String>) : FmDlg()
    class TextView(val title: String, val body: String) : FmDlg()
    class Archive(val pane: FmPane, val name: String, val lines: List<String>) : FmDlg()
    class MakeArchive(val pane: FmPane, val names: List<String>) : FmDlg()
    class Conflict(val move: Boolean, val src: FmPane, val dst: FmPane, val names: List<String>, val report: ClashReport) : FmDlg()
}

/** Внешний том: SD-карта или USB-накопитель (OTG). [id] нужен команде «sm unmount» для безопасного извлечения. */
data class FmVol(val path: String, val label: String, val id: String?, val total: Long, val free: Long, val usb: Boolean,
                 /** Файловая система тома (FAT32, exFAT, NTFS, ext4…), пусто если система не сообщает. */
                 val fs: String = "", val ro: Boolean = false,
                 /** false: система не смонтировала том (неподдерживаемая файловая система, например NTFS без драйвера). */
                 val ok: Boolean = true, val state: String = "")

/** Общее состояние менеджера: переживает смену вкладок и поворот. Работает без ADB: по умолчанию панели читают файлы напрямую. */
object Fm {
    val panes = listOf(FmPane(0, "/sdcard"), FmPane(1, "/sdcard/Download"))
    var active by mutableStateOf(0)
    /** Счётчик изменений сортировки и дочитанных тегов: по нему списки пересортировываются. */
    var sortTick by mutableStateOf(0)
    var showHidden by mutableStateOf(false)
    /** Нажатие на папку в режиме «Дерево»: true раскрывает в списке, false входит в папку. */
    var dirTapExpand by mutableStateOf(true)
    var dense by mutableStateOf(true)        // «Плотно» по умолчанию, как список в «Софте»
    var showSize by mutableStateOf(true)     // размер файлов под названием (по умолчанию включено)
    @Volatile var backgrounded = false
    var treeMode by mutableStateOf(true)     // «Дерево»: папки раскрываются прямо в списке (по умолчанию), иначе обычный «Список»
    var vols by mutableStateOf<List<FmVol>>(emptyList())
    var dlg by mutableStateOf<FmDlg?>(null)
    /**
     * 7.6: простые окна «Файлов» (Показать/Скрыть, меню, сортировка, поиск, маска, закладки, история и т.п.) не запоминаются: при выходе из программы
     * они закрываются, и после возврата открыт основной экран. Положение дерева и фокус запоминаются отдельно (ScrollMem). Окна, которые ведут во внешние экраны,
     * вводят пароли/ключи или показывают ход работы, не трогаются.
     */
    fun closeSimpleDialogsOnLeave() {
        val d = dlg ?: return
        val simple = d is FmDlg.RootShow || d is FmDlg.Bookmarks || d is FmDlg.History || d is FmDlg.Tree || d is FmDlg.Storage || d is FmDlg.Sort || d is FmDlg.View ||
            d is FmDlg.Mask || d is FmDlg.Search || d is FmDlg.SearchResult || d is FmDlg.More || d is FmDlg.Props || d is FmDlg.Perms || d is FmDlg.OpenAs ||
            d is FmDlg.NewFolder || d is FmDlg.NewFile || d is FmDlg.NewZip || d is FmDlg.Rename || d is FmDlg.CloudAll
        if (simple) dlg = null
    }
    /** Очередь длительных операций (копирование, перенос, удаление, упаковка): выполняются по одной, у каждой своя полоска. */
    val jobs = androidx.compose.runtime.mutableStateListOf<FmJob>()
    val job: FmJob? get() = jobs.firstOrNull()
    var opsCollapsed by mutableStateOf(false)
    private val opLock = kotlinx.coroutines.sync.Mutex()
    var note by mutableStateOf<String?>(null)
    var inited by mutableStateOf(false)
    /** Есть ли доступ ко всем файлам (Android 11+) или к памяти (Android 6–10). */
    var granted by mutableStateOf(true)
    /** Запрос прав на старых версиях Android (ставится из экрана). */
    var askLegacy: (() -> Unit)? = null
    lateinit var sctx: Context
    /** Переход на вкладку «Связь» (ввод кода сопряжения). */
    var goConnect: (() -> Unit)? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val cur get() = panes[active]
    val other get() = panes[1 - active]

    fun str(id: Int, vararg a: Any): String = runCatching { sctx.getString(id, *a) }.getOrDefault("")

    private fun join(dir: String, name: String) = FmIo.join(dir, name)
    private fun prefs() = sctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE)

    fun say(s: String) { note = s; scope.launch { delay(3500); if (note == s) note = null } }

    // ---------- доступ к файлам ----------
    fun hasFull(ctx: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
        else ctx.checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Можно ли записывать и удалять в общей памяти: Android 11+ «доступ ко всем файлам», Android 8–10 обычное разрешение на запись. */
    fun hasWrite(ctx: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
        else ctx.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Запрос доступа одним нажатием: на Android 11+ экран «Доступ ко всем файлам» именно этого приложения, на старых системное окно. */
    fun askAccess(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val pkg = android.net.Uri.parse("package:" + ctx.packageName)
            val i = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(i) }.isFailure)
                runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } else askLegacy?.invoke()
    }

    /** Проверка доступа (при возврате из настроек). Если появился, списки перечитываются сами. Запасной путь: выдать права по ADB, когда связь уже есть. */
    fun recheck(ctx: Context) {
        val was = granted
        granted = hasFull(ctx)
        if (!granted && Adb.isConnected && !FmIo.remote) scope.launch {
            runCatching { Adb.shell("pm grant ${ctx.packageName} android.permission.READ_EXTERNAL_STORAGE") }
            runCatching { Adb.shell("pm grant ${ctx.packageName} android.permission.WRITE_EXTERNAL_STORAGE") }
            runCatching { Adb.shell("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow") }
            granted = hasFull(ctx)
            if (granted) panes.forEach { if (it.src == "local") load(it, remember = false) }
        }
        if (granted && !was) { panes.forEach { if (it.src == "local") load(it, remember = false) }; refreshVols() }
    }

    // ---------- загрузка ----------
    fun load(p: FmPane, path: String = p.path, remember: Boolean = true, pick: String? = null) {
        if (!atTrashRoot(p, path)) p.cloudView = "drive"
        p.parked = null; p.parkedErr = null
        val myGen = ++p.loadGen
        scope.launch {
            p.loading = true
            var tries = 0
            var list: List<FmItem>? = null
            var err: String? = null
            p.fix = null
            // обычная папка: панель возвращается из режима ADB/root в прямой доступ
            if (p.auto && p.src == "adb" && !FmIo.restricted(path)) { p.src = "local"; p.auto = false }
            while (true) {
                try { list = FmIo.list(p, path); break }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: FmIo.NeedPriv) {
                    if (!FmIo.privEnabled) { err = str(R.string.fm_err_denied); break }
                    p.status = str(R.string.fm_connecting)
                    val fix = FmIo.ensurePriv()
                    p.status = null
                    if (fix != null) { p.fix = fix; err = str(if (fix == "wifi") R.string.fm_fix_wifi_text else R.string.fm_fix_pair_text); if (fix == "pair" || fix == "wifi") autoPairOnce(); break }
                    p.src = "adb"; p.auto = true
                    continue
                }
                catch (e: ArcNeedPassword) { (p.smb as? ArchiveFs)?.let { a -> dlg = FmDlg.ArcPass(a.file, a) { a.invalidate(); load(p, path, remember, pick) } }; err = str(R.string.arc_need_password); break }
                catch (e: Throwable) {
                    // соединение ADB ещё поднимается: подождать и повторить
                    if (++tries <= 4 && p.src == "adb" && e.message?.contains("connect()") == true) { delay(600); continue }
                    err = FmIo.nice(e); break
                }
            }
            if (myGen != p.loadGen) return@launch
            p.loading = false
            if (list == null) { p.error = err; return@launch }
            if (remember && p.path != path) { p.back.add(p.path); if (p.back.size > 50) p.back.removeAt(0) }
            p.path = path; p.items = list; p.error = null; p.selected = emptySet(); p.lastName = pick
            if (remember) fmLeaveRoot(p)
            pick?.let { p.scrollToName = it }
            if (p.src != "smb") addHistory(path)
            // открытая папка запоминается при КАЖДОМ переходе (в том числе внутри сетевых источников)
            runCatching {
                val key = if (p.src == "smb") p.net?.takeIf { p.smb !is ArchiveFs }?.let { "net:" + it.kind + "|" + it.host + "|" + it.user } else p.src
                if (key != null && p.parked == null) { Startup.saveLast(sctx, p.id, key, path); if (p.src == "local" || p.src == "smb") Startup.saveSrc(sctx, p.id, key) }
            }
            p.kids.clear()
        }
    }

    fun init(ctx: Context) {
        sctx = ctx; FmIo.app = ctx.applicationContext
        if (inited) return
        inited = true
        panes.forEach { it.sortCfg = SortStore.loadGlobal(it.id) }
        showHidden = prefs().getBoolean("hidden", false)
        FmDnd.on = true   // 08.11.2026 (владелица): перетаскивание всегда включено, переключатель в настройках убран
        dirTapExpand = prefs().getString("dir_tap", "expand") != "open"
        FmRootCfg.load(ctx)
        FmDel.init(ctx)
        CloudModule.cleanup(ctx)   // скачанный файл модуля не должен оставаться в кэше
        CloudModule.watch(ctx)     // установка и удаление модуля видны сразу
        FmTrash.startAutoClean()
        // самый первый запуск (нет ни запомненных папок, ни корневого экрана): обе панели открываются на корневом экране «домик» с памятью, сетью и облаками
        val fresh = prefs().all.keys.none { it.startsWith("root_p") || it.startsWith("last_") || it.startsWith("exp_p") }
        panes.forEach { it.root = prefs().getBoolean("root_p${it.id}", fresh) }
        // раскрытые узлы «домика» помнятся между запусками; самый первый запуск: сразу видно «Облачные диски» и «Добавить облако…»
        panes.forEach { if (!FmRootCfg.restoreOpen(ctx, it) && fresh) it.rootOpen.add("cloud") }
        dense = prefs().getBoolean("dense", true); treeMode = prefs().getBoolean("tree", true); showSize = true
        FmIo.privEnabled = prefs().getBoolean("priv", true); privOn = FmIo.privEnabled
        granted = hasFull(ctx)
        // папка при запуске: последняя / внутренняя память / Загрузки / DCIM / выбранная (для каждой панели отдельно); нет папки: внутренняя память
        panes.forEach { p ->
            p.expanded.addAll(prefs().getString("exp_p${p.id}", "").orEmpty().split('\n').filter { it.isNotBlank() }.take(200))
            p.path = Startup.startPath(ctx, p.id, p.path)
            // «Последняя»: если панель была на сетевом источнике из избранного, он открывается снова (иначе обычная память)
            val sv = Startup.savedSrc(ctx, p.id)
            val c = if (Startup.folderMode(ctx) == "last" && sv != null && sv.startsWith("net:")) NetStore.list(ctx).firstOrNull { "net:" + it.kind + "|" + it.host + "|" + it.user == sv } else null
            val foc = Startup.loadFocus(ctx, p.id)
            if (c != null && !p.root) {
                // облако и сеть (только те, что были открыты в панели): при запуске список перечитывается по сохранённой записи (без нового входа), по одному и с задержкой; не вышло: «не подключено»
                val pk = FmParked(c, Startup.lastFor(ctx, p.id, sv!!).orEmpty(), foc?.takeIf { it.key == sv }?.view ?: "drive", foc?.takeIf { it.key == sv }?.name.orEmpty())
                p.path = Startup.internalRoot(); load(p, remember = false)
                p.parked = pk
                connectNet(p, c, pk.path.takeIf { it.isNotBlank() && pk.view != "top" }, parked = pk, restore = foc?.takeIf { it.key == sv }, delayMs = 1500L + 2500L * p.id)
            } else load(p, remember = false, pick = foc?.takeIf { it.key == p.src && it.path == p.path && it.name.isNotEmpty() }?.name)
            prefs().getString("root_sel_p${p.id}", null)?.let { p.rootSel = it }
        }
        refreshVols()
        recheck(ctx)
        scope.launch(Dispatchers.IO) { NetTrash.ensureAll(ctx) }
    }

    fun choosePriv(v: Boolean) { FmIo.privEnabled = v; privOn = v; prefs().edit().putBoolean("priv", v).apply() }
    var privOn by mutableStateOf(true)

    /** «Включить отладку по Wi-Fi»: пробуем сами, иначе открываем настройки для разработчиков. */
    /** Единый шаг исправления для подсказок «нужна связь ADB»: «wifi» включает отладку, «pair» запускает автоматическое сопряжение (Android 11+, служба включена) или открывает «Связь». */
    private var lastAutoPair = 0L
    /** Нет связи ADB и нужно сопряжение: запускаем автоматический путь сами (раз в минуту, пока служба спецвозможностей включена, Android 11+). */
    fun autoPairOnce() {
        val now = System.currentTimeMillis()
        if (android.os.Build.VERSION.SDK_INT >= 30 && !AutoPairService.isEnabled(sctx)) { if (!WifiDebug.userOff(sctx)) Setup.need(sctx, "acc"); return }
        if (android.os.Build.VERSION.SDK_INT < 30 || !AutoPairService.isEnabled(sctx) || Pairing.autoWanted || now - lastAutoPair < 60_000 || WifiDebug.userOff(sctx)) return
        lastAutoPair = now
        // первый раз коротко объясняем и спрашиваем, дальше подключаемся сами
        if (!Setup.sp0(sctx).getBoolean("adb_auto_ok", false)) { Setup.adbAsk = true; return }
        Pairing.startAutoFlow(sctx)
    }

    /** Связь ADB появилась (автосопряжение закончилось): обновить подсказки «нужна связь ADB» в дереве и в панелях. */
    fun onAdbReady() {
        scope.launch {
            FmRootCfg.kidErr.entries.filter { it.value.startsWith("FIX:") }.map { it.key }.forEach { FmRootCfg.kidErr.remove(it); FmRootCfg.kids.remove(it) }
            CloudTrash.ver++
            panes.filter { it.fix != null }.forEach { it.fix = null; it.error = null; load(it, remember = false) }
        }
    }

    fun fixAction(p: FmPane, fix: String) {
        if (fix == "wifi") { enableWifiDebug(p); return }
        WifiDebug.setUserOff(sctx, false)
        if (android.os.Build.VERSION.SDK_INT >= 30 && AutoPairService.isEnabled(sctx)) { Setup.sp0(sctx).edit().putBoolean("adb_auto_ok", true).apply(); Pairing.startAutoFlow(sctx) } else goConnect?.invoke()
    }

    fun enableWifiDebug(p: FmPane) {
        WifiDebug.setUserOff(sctx, false)
        runCatching { android.provider.Settings.Global.putInt(sctx.contentResolver, "adb_wifi_enabled", 1) }
        if (runCatching { android.provider.Settings.Global.getInt(sctx.contentResolver, "adb_wifi_enabled", 0) == 1 }.getOrDefault(false)) load(p, remember = false)
        else runCatching { sctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun chooseDense(v: Boolean) { dense = v; prefs().edit().putBoolean("dense", v).apply() }

    /** Сменить источник панели: память (напрямую), ADB (Android/data и системные папки) или сетевая папка SMB. */
    fun setSource(p: FmPane, src: String, smb: RemoteFs? = null) {
        if (src == "adb" && !Adb.isConnected) {
            // подключение само: сначала root, затем беспроводная отладка
            scope.launch {
                p.status = str(R.string.fm_connecting)
                val fix = FmIo.ensurePriv()
                p.status = null
                if (fix != null) { p.error = str(if (fix == "wifi") R.string.fm_fix_wifi_text else R.string.fm_fix_pair_text); p.fix = fix; if (fix == "pair" || fix == "wifi") autoPairOnce() } else setSource(p, "adb")
            }
            return
        }
        p.auto = false; p.fix = null
        if (src != "smb") WifiAuto.maybeRelease(sctx)
        p.src = src; p.smb = smb; if (src != "smb") p.net = null; p.back.clear(); p.items = emptyList(); p.error = null; p.selected = emptySet(); p.parked = null; p.parkedErr = null
        load(p, when (src) { "smb" -> smb?.root.orEmpty(); else -> Startup.lastFor(sctx, p.id, src)?.takeIf { src != "local" || java.io.File(it).isDirectory } ?: "/sdcard" }, remember = false)
    }

    /** Подключение к сетевому источнику (SMB, SFTP, FTP/FTPS, WebDAV): проверка входа и переход в стартовую папку; ошибка показывается в панели. */
    /**
     * Подпись панели в верхней кнопке: что панель показывает сейчас. На корневом экране «Устройства», в архиве его имя, на сетевом диске или облаке
     * его название (в подпапке имя папки), на локальной папке имя папки.
     */
    fun paneLabel(p: FmPane): String {
        if (p.root) return str(R.string.fm_root_title)
        if (p.src == "smb") {
            (p.smb as? ArchiveFs)?.let { return it.display }
            if (p.smb is AppsFs) { val t = p.path.removePrefix("apps://").trim('/'); return if (t.isEmpty()) str(R.string.rt_apps) else t }
            val c = p.net
            if (c != null) {
                val rootPath = (p.smb?.root ?: "").trimEnd('/')
                val atRoot = p.path.trimEnd('/').let { it.isEmpty() || it == rootPath }
                return if (atRoot) c.title else p.path.trimEnd('/').substringAfterLast('/').ifEmpty { c.title }
            }
        }
        return p.path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
    }

    /**
     * «Играть папку»: вся музыка и видео папки (и вложенных, до 4 уровней, до 800 файлов) подряд в том же порядке, что в панели
     * (сортировка панели или самой папки: имя, дата, размер, тип, возрастание или убывание, место папок среди файлов); подпапка играет на своём месте списка. Только музыка: служба с уведомлением (экран может быть выключен); есть видео или смесь: полноэкранный плеер.
     */
    fun playFolder(p: FmPane, rel: String) {
        scope.launch {
            val out = ArrayList<MediaEntry>()
            suspend fun walk(dir: String, depth: Int) {
                if (out.size >= 800) return
                val items0 = runCatching { FmIo.list(p, dir) }.getOrDefault(emptyList())
                // порядок как в панели: та же сортировка (общая или своя у этой папки), те же скрытые; папка раскрывается на своём месте списка
                val items = withContext(Dispatchers.IO) { runCatching { sortList(p, dir, if (showHidden) items0 else items0.filter { !it.hidden }) }.getOrDefault(items0) }
                for (f in items) {
                    if (out.size >= 800) return
                    if (f.isDir) { if (depth < 4) walk(FmIo.join(dir, f.name), depth + 1) }
                    else if (openCat(f.ext).let { it == "audio" || it == "video" }) out.add(MediaEntry(f.name, FmIo.join(dir, f.name)))
                }
            }
            walk(FmIo.join(p.path, rel), 0)
            if (out.isEmpty()) { say(str(R.string.fm_play_none)); return@launch }
            MediaSrc.fs = p.smb; MediaSrc.local = p.src != "smb"; MediaSrc.src = p.src; MediaSrc.entries = out; MediaSrc.index = 0
            val hasVideo = out.any { openCat(it.name.substringAfterLast('.', "").lowercase()) == "video" }
            if (hasVideo) sctx.startActivity(android.content.Intent(sctx, VideoPlayerActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) else AudioService.play(sctx)
        }
    }

    /** Открыть группу «Приложения» (папка [key]: installed, system, disabled, removed; null: список папок). */
    fun openApps(p: FmPane, key: String?) {
        fmLeaveRoot(p)
        setSource(p, "smb", AppsFs())
        val fs = p.smb as AppsFs
        load(p, if (key == null) fs.root else "apps://" + fs.dirName(key))
    }

    /** Правка подключения на месте: запись, избранное и бэкап обновляются, панели с этим подключением переподключаются. */
    fun applyNetEdit(ctx: Context, old: NetConn, new: NetConn) {
        NetStore.update(ctx, old, new)
        // 14.11.2026: дерево «Устройства» перечитывается (новая папка из поля «Общий ресурс или папка»)
        for (k in (FmRootCfg.kids.keys + FmRootCfg.kidErr.keys).toList()) if (k.startsWith("N|" + old.kind + "|" + old.host + "|" + old.user + "|") || k.startsWith("N|" + new.kind + "|" + new.host + "|" + new.user + "|")) { FmRootCfg.kids.remove(k); FmRootCfg.kidErr.remove(k) }
        FmRootCfg.ver++
        panes.forEach { pp ->
            val n = pp.net
            if (pp.src == "smb" && n != null && n.kind == old.kind && n.host == old.host && n.user == old.user) {
                val keep = if (pp.path.isNotBlank() && n.host == new.host && n.kind == new.kind) pp.path else null
                pp.smb?.close()
                // панель сначала возвращается на память телефона: если новые данные не подошли, остаётся она, а не устаревший список со старого адреса
                pp.src = "local"; pp.smb = null; pp.net = null; pp.path = Startup.internalRoot(); pp.items = emptyList(); pp.error = null; pp.selected = emptySet()
                connectNet(pp, new, keep, fallbackLocal = true)
            }
        }
    }

    val scanLock = kotlinx.coroutines.sync.Mutex()

    /** «Переподключиться» (долгое нажатие на панели с облаком или сетью): новое подключение, пересканирование, возврат папки и фокуса. */
    fun reconnect(p: FmPane) {
        val pk = p.parked ?: return
        if (p.status != null) return
        connectNet(p, pk.conn, pk.path.takeIf { it.isNotBlank() && pk.view != "top" }, parked = pk, restore = Startup.Focus("net:" + pk.conn.kind + "|" + pk.conn.host + "|" + pk.conn.user, pk.path, pk.view, pk.name))
    }

    /** 14.11.2026 (владелица): нажатие на найденное по LAN устройство сразу открывает его в панели: общий ресурс из подключения или корень (список ресурсов). Протокол: выбранный или SMB, при неудаче FTP, WebDAV, SFTP, без вопросов. */
    fun openLanHost(p: FmPane, h: NetHost) {
        scope.launch {
            val pref = listOf("smb", "ftp", "dav", "sftp")
            val order = (listOf(FmRootCfg.lanConn(h).kind) + h.kinds.sortedBy { k -> pref.indexOf(k).let { if (it < 0) 9 else it } }).distinct()
            for ((i, k) in order.withIndex()) {
                p.error = null
                connectNet(p, FmRootCfg.lanConn(h, k), fresh = true)
                delay(250)
                var waited = 0L
                while (p.status != null && waited < 30_000L) { delay(150); waited += 150 }   // не больше 30 с на одну попытку
                if (p.error == null || i == order.lastIndex) break
            }
        }
    }
    fun connectNet(p: FmPane, c: NetConn, startPath: String? = null, fallbackLocal: Boolean = false, restore: Startup.Focus? = null, parked: FmParked? = null, delayMs: Long = 0L, fresh: Boolean = false) {
        // облако без модуля «ADB-phon Облака»: предложить скачать и установить (при запуске программы ничего не предлагается и в сеть не ходит)
        if (c.kind == "cloud" && !CloudModule.ok(sctx) && delayMs == 0L && parked == null && !fallbackLocal && CloudModule.supportedDevice()) { dlg = FmDlg.CloudModule { connectNet(p, c, startPath, fallbackLocal, restore) }; return }
        if (parked == null) { p.parked = null; p.parkedErr = null } else p.parkedErr = null
        scope.launch {
            p.status = str(R.string.fm_connecting)
            if (delayMs > 0) delay(delayMs)
            val fs = c.fs()
            WifiAuto.onNetOpen(sctx)
            val err = scanLock.withLock { withContext(Dispatchers.IO) {
                runCatching { (fs as? KeepFs)?.connect() ?: (fs as? CloudFs)?.connect() }.exceptionOrNull()
            } }
            p.status = null
            if (err != null) {
                if (parked != null) { p.parked = parked; p.parkedErr = str(R.string.fm_reconnect_fail, FmIo.nice(err)); return@launch }
                if (fallbackLocal) { say(str(R.string.fm_net_lost, c.title)); load(p, remember = false); return@launch }
                fmLeaveRoot(p); p.error = FmIo.nice(err); p.src = "smb"; p.smb = fs; p.net = c; p.items = emptyList(); p.path = fs.root; return@launch
            }
            p.net = c
            p.cloudView = "top"; FmRootCfg.sel.clear()
            val key = "net:" + c.kind + "|" + c.host + "|" + c.user
            // fresh (14.11.2026): нажатие на устройство в LAN открывает общий ресурс из подключения или корень, а не прежнюю папку
            val rf = if (fresh) null else restore?.takeIf { it.key == key } ?: if (startPath.isNullOrBlank()) Startup.loadFocus(sctx, p.id)?.takeIf { it.key == key } else null
            setSource(p, "smb", fs)
            // начальная папка: запомненная при запуске, иначе путь из записи подключения (для SMB: общий ресурс или папка в нём)
            val own = c.path.trim().trim('/').takeIf { it.isNotEmpty() }?.let { if (c.kind == "smb") "smb://" + c.host.trim().trim('/') + "/" + it + "/" else if (c.kind == "cloud") null else "/" + it }
            val sp = if (!startPath.isNullOrBlank()) startPath else if (rf != null) rf.path.takeIf { rf.view != "top" && it.isNotBlank() } else own
            if (sp != null && sp.isNotBlank()) load(p, sp, remember = false, pick = rf?.takeIf { it.path == sp }?.name?.takeIf { it.isNotEmpty() })
        }
    }

    // ---------- внешние носители ----------
    /** Подключённые SD-карты и USB-накопители: через StorageManager, без ADB. Показываются и тома, которые система не смогла смонтировать (неподдерживаемая ФС). */
    fun refreshVols() {
        scope.launch {
            val ids = HashMap<String, String>()
            var mountsAdb = ""
            if (Adb.isConnected && !FmIo.remote) {
                runCatching { Adb.shell("sm list-volumes public 2>/dev/null") }.getOrDefault("").lines().forEach { l ->
                    val t = l.trim().split(Regex("\\s+"))
                    if (t.size >= 3 && t[0].startsWith("public:") && t[1] == "mounted") ids[t[2]] = t[0]
                }
                mountsAdb = runCatching { Adb.shell("cat /proc/mounts 2>/dev/null") }.getOrDefault("")
            }
            val res = withContext(Dispatchers.IO) {
                val out = mutableListOf<FmVol>()
                val mounts = mountsAdb.ifBlank { runCatching { java.io.File("/proc/mounts").readText() }.getOrDefault("") }
                runCatching {
                    val sm = sctx.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
                    for (v in sm.storageVolumes) {
                        if (v.isPrimary) continue
                        val st0 = v.state
                        val mounted = st0 == android.os.Environment.MEDIA_MOUNTED || st0 == android.os.Environment.MEDIA_MOUNTED_READ_ONLY
                        val bad = st0 == android.os.Environment.MEDIA_UNMOUNTABLE || st0 == "nofs"
                        if (!mounted && !bad) continue
                        val uuid = v.uuid
                        val desc = v.getDescription(sctx)
                        val usb = desc?.contains("USB", true) == true
                        if (!mounted) { out.add(FmVol("", desc ?: uuid ?: "USB", null, 0L, 0L, usb, "", false, false, st0)); continue }
                        val dir = (if (android.os.Build.VERSION.SDK_INT >= 30) v.directory?.absolutePath else null)
                            ?: runCatching { v.javaClass.getMethod("getPath").invoke(v) as String }.getOrNull()
                            ?: uuid?.let { "/storage/$it" } ?: continue
                        val st = runCatching { android.os.StatFs(dir) }.getOrNull()
                        val (fs, ro) = fsInfo(mounts, uuid)
                        out.add(FmVol(dir, desc ?: uuid.orEmpty(), uuid?.let { ids[it] }, st?.let { it.blockCountLong * it.blockSizeLong } ?: 0L,
                            st?.let { it.availableBlocksLong * it.blockSizeLong } ?: 0L, usb, fs, ro || st0 == android.os.Environment.MEDIA_MOUNTED_READ_ONLY, true, st0))
                    }
                }
                out
            }
            vols = res
            // панель стояла на извлечённом носителе: вернуть во внутреннюю память
            panes.forEach { p -> if (p.src != "smb" && p.path.startsWith("/storage/") && !p.path.startsWith("/storage/emulated") && res.none { it.ok && (p.path == it.path || p.path.startsWith(it.path + "/")) }) load(p, "/sdcard", remember = false) }
        }
    }

    /** Файловая система и признак «только чтение» тома по /proc/mounts (строки с идентификатором тома; fuse и sdcardfs пропускаются). */
    private fun fsInfo(mounts: String, uuid: String?): Pair<String, Boolean> {
        if (uuid == null) return "" to false
        var fs = ""; var ro = false
        for (l in mounts.lines()) {
            if (!l.contains(uuid, true)) continue
            val p = l.split(' ')
            if (p.size < 4) continue
            var t = p[2].lowercase(); val opts = p[3]
            if (t == "fuse" || t == "sdcardfs" || t == "esdfs" || t == "9p") continue
            val real = Regex("fs=(\\w+)").find(opts)?.groupValues?.get(1)?.lowercase()
            if (real != null) t = real
            fs = when (t) { "vfat", "msdos" -> "FAT32"; "exfat", "texfat" -> "exFAT"; "ntfs", "ntfs3", "tntfs", "ufsd", "fuseblk" -> "NTFS"; else -> t.uppercase() }
            ro = opts.split(',').contains("ro")
            break
        }
        return fs to ro
    }

    fun eject(v: FmVol) = scope.launch {
        val id = v.id
        if (id == null) {
            // без ADB безопасное извлечение делает система: открываем настройки хранилища
            runCatching { sctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            say(str(R.string.fm_eject_manual)); return@launch
        }
        val out = runCatching { Adb.shell("sm unmount $id 2>&1") }.getOrDefault("")
        say(if (out.contains("rror") || out.contains("xception")) str(R.string.fm_error, out.lines().firstOrNull { it.isNotBlank() }.orEmpty()) else str(R.string.fm_ejected, v.label))
        delay(700); refreshVols()
    }

    fun setPaneCfg(p: FmPane, c: SortCfg) { p.sortCfg = c; SortStore.saveGlobal(p.id, c); sortTick++ }
    /** Настройка сортировки для папки [dir] панели [p]: своя у этой папки, иначе общая у панели. */
    fun cfgFor(p: FmPane, dir: String): SortCfg = SortStore.folder(p, dir) ?: p.sortCfg
    fun setHidden(v: Boolean) { showHidden = v; prefs().edit().putBoolean("hidden", v).apply() }

    fun shown(p: FmPane): List<FmItem> = sortList(p, p.path, if (showHidden) p.items else p.items.filter { !it.hidden })

    /** Сортировка списка папки [dir] по группам (папки, файлы, фото, видео, музыка, документы), у каждой группы свой критерий. */
    fun sortList(p: FmPane, dir: String, base: List<FmItem>): List<FmItem> {
        val cfg = cfgFor(p, dir)
        // теги, число элементов и цвета выбираются заранее: в самой сортировке только быстрые обращения к таблицам
        val meta = HashMap<FmItem, SMeta>(); val cnt = HashMap<FmItem, Int>(); val col = HashMap<FmItem, Int>()
        val needColor = cfg.keyOf(SGroup.DIR) == SKey.COLOR
        for (f in base) {
            val g = cfg.groupOf(f.isDir, f.ext)
            if (!cfg.needsMeta(g)) { if (g == SGroup.DIR && needColor) col[f] = UiPrefs.folderColors[folderKey(p, join(dir, f.name))] ?: UiPrefs.folderDefault; continue }
            if (g == SGroup.DIR) cnt[f] = SortMeta.count(p, join(dir, f.name))
            else SortMeta.get(join(dir, f.name), f)?.let { meta[f] = it }
        }
        return SortEngine.sort(base, cfg, SortCtx({ meta[it] }, { cnt[it] ?: -1 }, { col[it] ?: 0 }))
    }

    // ---------- режим «Дерево»: раскрытие папок внутри списка ----------
    fun setShowSize(ctx: Context, v: Boolean) { showSize = v; ctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE).edit().putBoolean("show_size", v).apply() }
    fun setTree(v: Boolean) { treeMode = v; prefs().edit().putBoolean("tree", v).apply() }

    private fun saveExpanded(p: FmPane) { prefs().edit().putString("exp_p${p.id}", p.expanded.takeLast(200).joinToString("\n")).apply() }

    /** Подгрузить содержимое раскрытой папки (подпапки подгружаются только при раскрытии). */
    suspend fun loadKids(p: FmPane, path: String) {
        if (p.kids.containsKey(path)) return
        val list = runCatching { FmIo.list(p, path) }.getOrNull()
        if (list == null) return      // временная ошибка (доступ, сеть): раскрытие остаётся в памяти, подгрузится при следующем обновлении
        p.kids[path] = list
    }

    fun toggleExpand(p: FmPane, path: String) {
        if (path in p.expanded) { p.expanded.remove(path); saveExpanded(p) }
        else {
            // раскрыта только цепочка к последней открытой папке: остальные сворачиваются
            val stay = p.expanded.filter { o -> path.startsWith(o.trimEnd('/') + "/") }
            p.expanded.clear(); p.expanded.addAll(stay)
            p.expanded.add(path); saveExpanded(p); scope.launch { loadKids(p, path) }
        }
    }

    fun expand(p: FmPane, path: String) { if (path !in p.expanded) toggleExpand(p, path) }
    fun collapse(p: FmPane, path: String) { if (path in p.expanded) toggleExpand(p, path) }

    fun open(p: FmPane, name: String) = load(p, join(p.path, name))
    /** Архив в панели: выйти в папку, где он лежит. */
    fun exitArchive(p: FmPane) {
        val a = p.smb as? ArchiveFs ?: return
        val pv = a.prev
        p.src = pv.src; p.smb = pv.smb; p.net = pv.net; p.back.clear(); p.items = emptyList(); p.error = null; p.selected = emptySet()
        a.invalidate()
        load(p, pv.path, remember = false, pick = pv.pick)
    }

    val arcPass = HashMap<String, CharArray>()
    private fun arcTmp(): java.io.File = java.io.File(sctx.cacheDir, "arc").apply { mkdirs() }

    /** Локальный файл по имени в панели: свой файл или копия из сетевого источника/архива во временной папке. */
    private suspend fun localFileOf(p: FmPane, name: String): java.io.File? = when (p.src) {
        "local" -> java.io.File(join(p.path, name))
        "smb" -> withContext(Dispatchers.IO) {
            val t = java.io.File(arcTmp(), "open_" + System.nanoTime().toString(36) + "_" + name.substringAfterLast('/'))
            runCatching { p.smb!!.copyTo(join(p.path, name), t) }.map { t }.getOrNull()
        }
        else -> null
    }

    /** Открыть архив как папку в панели. */
    fun openArchive(p: FmPane, name: String): kotlinx.coroutines.Job = scope.launch {
        val file = localFileOf(p, name) ?: run { say(str(R.string.fm_err_unsupported)); return@launch }
        val a = ArchiveFs(file, name, ArchiveFs.PrevState(p.src, p.smb, p.net, p.path, name), arcTmp())
        arcPass[file.absolutePath]?.let { a.password = it }
        try { withContext(Dispatchers.IO) { a.reader() } }
        catch (e: ArcNeedPassword) { dlg = FmDlg.ArcPass(file, a) { a.invalidate(); openArchiveWith(p, a) }; return@launch }
        catch (e: Throwable) { say(str(R.string.fm_error, FmIo.nice(e))); return@launch }
        openArchiveWith(p, a)
    }

    private fun openArchiveWith(p: FmPane, a: ArchiveFs) {
        fmLeaveRoot(p)
        p.src = "smb"; p.smb = a; p.net = null; p.back.clear(); p.items = emptyList(); p.error = null; p.selected = emptySet()
        load(p, "/", remember = false)
    }

    /** Извлечь архив: [mode] 0 в эту папку, 1 в папку с именем архива, 2 в папку другой панели. */
    fun extractArc(p: FmPane, name: String, mode: Int, policy: Int = -1): kotlinx.coroutines.Job = scope.launch {
        val dstPane = if (mode == 2) other else p
        if (dstPane.src != "local") { say(str(R.string.fm_err_unsupported)); return@launch }
        val file = localFileOf(p, name) ?: run { say(str(R.string.fm_err_unsupported)); return@launch }
        val destDir = java.io.File(if (mode == 1) join(dstPane.path, name.substringBeforeLast('.').removeSuffix(".tar")) else dstPane.path)
        val rd = try { withContext(Dispatchers.IO) { Arc.open(file, null, arcPass[file.absolutePath]) } }
        catch (e: ArcNeedPassword) { dlg = FmDlg.ArcPass(file, null) { extractArc(p, name, mode, policy) }; return@launch }
        catch (e: Throwable) { say(str(R.string.fm_error, FmIo.nice(e))); return@launch }
        var pol = policy
        if (pol < 0) {
            val clash = withContext(Dispatchers.IO) { rd.entries.map { it.path.substringBefore('/') }.distinct().any { java.io.File(destDir, it).exists() } }
            if (clash) { rd.close(); dlg = FmDlg.ArcConflict { c -> extractArc(p, name, mode, c) }; return@launch }
            pol = 0
        }
        runJob(str(R.string.fm_extracting), listOf(name), true, { rd.totalSize }, { rd.entries.count { !it.isDir } }) { j, _ ->
            try { Arc.extractAll(rd, destDir, j, pol); null }
            catch (e: ArcNeedPassword) { dlg = FmDlg.ArcPass(file, null) { extractArc(p, name, mode, pol) }; str(R.string.arc_need_password) }
            catch (e: ArcBadPassword) { dlg = FmDlg.ArcPass(file, null) { extractArc(p, name, mode, pol) }; str(R.string.arc_bad_password) }
            finally { rd.close() }
        }
        load(dstPane, remember = false)
    }

    /** Проверка целостности архива. */
    fun testArc(p: FmPane, name: String): kotlinx.coroutines.Job = scope.launch {
        val file = localFileOf(p, name) ?: run { say(str(R.string.fm_err_unsupported)); return@launch }
        val rd = try { withContext(Dispatchers.IO) { Arc.open(file, null, arcPass[file.absolutePath]) } }
        catch (e: ArcNeedPassword) { dlg = FmDlg.ArcPass(file, null) { testArc(p, name) }; return@launch }
        catch (e: Throwable) { say(str(R.string.fm_error, FmIo.nice(e))); return@launch }
        var okCount = 0
        val ok = runJob(str(R.string.arc_testing), listOf(name), true, { rd.totalSize }, { rd.entries.count { !it.isDir } }) { j, _ ->
            try { okCount = Arc.test(rd, j); null }
            catch (e: ArcNeedPassword) { dlg = FmDlg.ArcPass(file, null) { testArc(p, name) }; str(R.string.arc_need_password) }
            catch (e: ArcBadPassword) { dlg = FmDlg.ArcPass(file, null) { testArc(p, name) }; str(R.string.arc_bad_password) }
            finally { rd.close() }
        }
        if (ok) say(str(R.string.arc_test_ok, okCount))
    }

    /** Создать архив из выбранного в папке панели [p]. */
    fun makeArc(p: FmPane, names: List<String>, outName: String, format: String, level: Int, pass: CharArray?): kotlinx.coroutines.Job = scope.launch {
        if (p.src != "local") { say(str(R.string.fm_err_unsupported)); return@launch }
        val out = java.io.File(join(p.path, outName))
        val srcs = names.map { java.io.File(join(p.path, it)) }
        prefs().edit().putString("arc_fmt", format).putInt("arc_level", level).apply()
        runJob(str(R.string.fm_packing), listOf(outName), true, { srcs.sumOf { LocalOps.size(it) } }, { srcs.sumOf { LocalOps.fileCount(it) } }) { j, _ ->
            try { ArcCreate.create(format, out, srcs, level, pass, arcTmp(), j); null } catch (e: ArcUnsupported) { e.message }
        }
        load(p, remember = false, pick = outName)
    }

    /**
     * «Назад» во вкладке «Файлы»: сначала закрыть открытое окно и отменить перетаскивание, затем снять выделение активной панели, затем «Вверх» в активной панели
     * (с корня источника сети, облака или памяти на «домик»). Вторая панель не трогается. false: делать нечего (панель на «домике»), решает вкладка.
     */
    fun onBack(): Boolean {
        if (dlg != null) { dlg = null; return true }
        if (FmDnd.active) { FmDnd.cancel(); return true }
        val p = cur
        if (p.selected.isNotEmpty()) { p.selected = emptySet(); return true }
        if (p.root) return FmRootCfg.treeOn && collapseLastTreeNode(p)
        up(p)
        return true
    }

    /**
     * Облако добавлено (любым способом): навигацию не меняем и панель на облако не переключаем. Остаёмся где были;
     * на «домике» запись появляется в «Облачных дисках», подсвечена и раскрыта (папки грузятся там же, в дереве).
     */
    fun cloudAdded(c: NetConn) {
        val p = cur
        if ("cloud" !in FmRootCfg.shown) FmRootCfg.set(sctx, "cloud", true)
        if ("tree" !in FmRootCfg.shown) FmRootCfg.set(sctx, "tree", true)
        val tid = "N|" + c.kind + "|" + c.host + "|" + c.user + "|"
        if ("cloud" !in p.rootOpen) p.rootOpen.add("cloud")
        p.rootPending.remove(tid)
        FmRootCfg.kids.remove(tid); FmRootCfg.kidErr.remove(tid); FmRootCfg.kidErrOwnKey.remove(tid)
        if (tid !in p.rootOpen) p.rootOpen.add(tid)
        p.rootSel = "net:" + c.kind + c.host + c.user
        FmRootCfg.saveOpen(sctx, p)
        FmRootCfg.ver++
        say(str(R.string.cl_done_notif_title))
    }

    /** Файл из дерева «домика»: открывается как в панели (просмотр, установка, воспроизведение), но без переключения панели на другой экран. */
    fun openFromTree(c: NetConn?, path: String, item: FmItem) {
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        // архив открывается как папка: это уже просмотр внутри панели, поэтому панель переходит к архиву (явное действие владельца)
        if (Arc.kindOf(item.name) != null && Arc.opensAsFolder(item.name) && item.ext !in setOf("apk", "apks", "xapk")) {
            val p = cur
            scope.launch {
                if (c == null) fmOpenLocal(p, parent) else { fmLeaveRoot(p); connectNet(p, c, parent) }
                delay(700); openArchive(p, item.name)
            }
            return
        }
        val sp = FmPane(9, parent)
        if (c == null) sp.src = "local" else { sp.src = "smb"; sp.smb = FmRootCfg.fsFor(c); sp.net = c }
        openFile(sp, item)
    }

    /** «Назад» на «домике»: сначала свернуть последнюю раскрытую ветку дерева; false: сворачивать нечего. */
    fun collapseLastTreeNode(p: FmPane): Boolean {
        val last = p.rootOpen.lastOrNull { it.length > 2 && it[1] == '|' } ?: return false
        p.rootOpen.remove(last); return true
    }

    /** Корень облака с корзиной: панель показывает две строки-папки «Корзина» и «Облачный диск». */
    fun cloudRoot(p: FmPane): Boolean {
        val c = p.net ?: return false
        val fs = p.smb ?: return false
        if (p.src != "smb" || fs is ArchiveFs) return false
        return if (c.kind == "cloud") fs is CloudFs && p.path.trim('/').isEmpty() && CloudTrash.supported(CloudIcons.typeOf(sctx, c))
        else false   // 14.11.2026: у LAN и прочих сетевых подключений корзины нет, корень виден как обычная папка
    }
    /** Сетевое подключение панели, у которого есть корзина (облако или сетевой источник); null: корзины нет. */
    fun trashConn(p: FmPane): NetConn? = p.net?.takeIf { p.src == "smb" && p.smb !is ArchiveFs && CloudTrash.supported(CloudTrash.typeFor(sctx, it)) }
    private fun atTrashRoot(p: FmPane, path: String): Boolean {
        val c = p.net ?: return false
        return c.kind == "cloud" && path.trim('/').isEmpty()
    }
    /** Виртуальный вид («Корзина» или две строки): настоящие файлы корня не видны, поэтому действия над ними запрещены. */
    fun virt(p: FmPane): Boolean = cloudRoot(p) && p.cloudView != "drive"
    fun up(p: FmPane) {
        if (p.root) return
        if (cloudRoot(p) && p.cloudView != "top") { p.cloudView = "top"; p.selected = emptySet(); FmRootCfg.sel.clear(); return }
        if (p.smb is ArchiveFs && p.src == "smb" && p.path.trim('/').isEmpty()) { exitArchive(p); return }
        if (fmIsSourceRoot(p, p.path)) { fmShowRoot(p); return }
        if (p.src == "smb") { p.smb?.parent(p.path)?.let { load(p, it) }; return }
        val up = p.path.trimEnd('/').substringBeforeLast('/', "")
        load(p, if (up.isEmpty()) "/" else up)
    }
    fun goBack(p: FmPane) { if (p.back.isNotEmpty()) load(p, p.back.removeAt(p.back.size - 1), remember = false) }

    fun toggleSel(p: FmPane, name: String) { p.lastName = name.substringAfterLast('/'); p.selected = if (name in p.selected) p.selected - name else p.selected + name }
    fun selectAll(p: FmPane) { if (virt(p)) return; p.selected = shown(p).map { it.name }.toSet() }
    fun invert(p: FmPane) { if (virt(p)) return; p.selected = (shown(p).map { it.name }.toSet() - p.selected) + p.selected.filter { '/' in it } }
    fun mask(p: FmPane, glob: String) {
        if (virt(p)) return
        val re = Regex("^" + glob.split('*').joinToString(".*") { Regex.escape(it) }.replace("\\?", ".") + "$", RegexOption.IGNORE_CASE)
        // и вложенные: содержимое раскрытых папок дерева (ключ «папка/имя» от текущей папки, как у ручного выбора)
        val base = p.path.trimEnd('/') + "/"
        val nested = p.expanded.toList().filter { it.startsWith(base) }.flatMap { d -> (p.kids[d] ?: emptyList()).filter { re.matches(it.name) }.map { d.removePrefix(base) + "/" + it.name } }
        p.selected = shown(p).filter { re.matches(it.name) }.map { it.name }.toSet() + nested
    }
    /** С чем работают кнопки: выделенное (верхний уровень по порядку списка, затем отмеченное глубже в раскрытых папках дерева, «папка/файл»), иначе ничего. */
    fun targets(p: FmPane): List<String> = if (virt(p)) emptyList() else shown(p).map { it.name }.filter { it in p.selected } + p.selected.filter { '/' in it }

    // ---------- закладки и история ----------
    fun bookmarks(): List<String> = prefs().getStringSet("bm", emptySet()).orEmpty().sorted()
    fun toggleBookmark(path: String) {
        val s = prefs().getStringSet("bm", emptySet()).orEmpty().toMutableSet()
        if (!s.add(path)) s.remove(path)
        prefs().edit().putStringSet("bm", s).apply()
    }
    fun history(): List<String> = prefs().getString("hist", "").orEmpty().split('\n').filter { it.isNotBlank() && !it.contains("/" + FmTrash.DIR) }
    private fun addHistory(path: String) {
        val l = history().toMutableList(); l.remove(path); l.add(0, path)
        prefs().edit().putString("hist", l.take(30).joinToString("\n")).apply()
    }

    // ---------- операции ----------
    /** Выполнить операцию над списком имён в очереди с полоской хода. [units]: сколько всего байт (или элементов), если известно заранее; -1 если нет. */
    private suspend fun runJob(title: String, names: List<String>, bytes: Boolean = true, units: suspend () -> Long = { -1L }, files: suspend () -> Int = { -1 }, op: suspend (FmJob, String) -> String?): Boolean {
        val j = FmJob(title, names.size, bytes)
        jobs.add(j)
        // копирование идёт в фоне: нужны уведомления и работа без ограничений батареи (если пропущено при первом запуске, спрашиваем сейчас)
        if (Setup.need(sctx, "notif")) Setup.need(sctx, "battery")
        FmOpService.start(sctx)
        var err: String? = null
        try {
            opLock.withLock {
                j.queued = false
                j.totalUnits = withContext(Dispatchers.IO) { runCatching { units() }.getOrDefault(-1L) }
                j.filesTotal = withContext(Dispatchers.IO) { runCatching { files() }.getOrDefault(-1) }
                for (n in names) {
                    if (j.cancelled) break
                    j.current = n
                    err = try { op(j, n) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: LocalOps.Cancelled) { null } catch (e: Throwable) { FmIo.nice(e) }
                    if (err != null) break
                    j.done++
                }
                j.publish(true)
            }
        } finally { jobs.remove(j) }
        say(err?.let { str(R.string.fm_error, it) } ?: if (j.cancelled) str(R.string.fm_cancelled) else str(R.string.fm_done))
        return err == null
    }

    private suspend fun runJob(title: String, names: List<String>, op: suspend (String) -> String?): Boolean =
        runJob(title, names, bytes = true) { _, n -> op(n) }

    /** Длительное действие над элементами по одному с полоской хода (удаление навсегда из корзин); [op] получает имя очередного элемента. */
    suspend fun stepJob(title: String, names: List<String>, total: Int, op: suspend (FmJob, String) -> String?): Boolean = runJob(title, names, bytes = false, units = { total.toLong() }) { j, n -> op(j, n) }

    /** Путь с названием источника: «SMB: //хост/папка», «ADB: путь», для телефона просто путь. */
    fun pathLabel(p: FmPane, srcName: String, path: String): String = when (srcName) {
        "smb" -> if (p.smb is ArchiveFs) (p.smb as ArchiveFs).label + path.trimEnd('/') else {
            val kind = p.net?.kind?.let { if (it == "dav") "WebDAV" else if (it == "cloud") "Cloud" else it.uppercase() } ?: path.substringBefore("://", "SMB").uppercase()
            "$kind: //" + path.substringAfter("://")
        }
        "adb" -> "ADB: $path"
        else -> path
    }

    /** Копирование или перенос в произвольную папку [toPath] источника [dstSrc] (перетаскивание). */
    fun copyMoveTo(move: Boolean, src: FmPane, dstPane: FmPane, dstSrc: String, toPath: String, names: List<String>, newName: String? = null) {
        val dst = if (dstSrc == dstPane.src && toPath == dstPane.path) dstPane else FmPane(9, toPath).also {
            it.src = dstSrc
            if (dstSrc == dstPane.src) { it.smb = dstPane.smb; it.net = dstPane.net; it.auto = dstPane.auto }
        }
        copyMove(move, src, dst, names, null, newName)
    }

    /** Свободное имя в папке: «имя (1).расширение». */
    /** Первое свободное «имя (N).расширение» (для папок «имя (N)») по настоящему списку целевой папки, а не по кэшу. */
    private suspend fun uniqueName(dst: FmPane, name: String, isDir: Boolean = false): String = ClashLogic.freeName(name, isDir) { FmIo.exists(dst, join(dst.path, it)) }
    suspend fun uniqueNameFor(dst: FmPane, name: String, isDir: Boolean = false): String = uniqueName(dst, name, isDir)

    /**
     * Копирование/перенос выделенного из панели [src] в панель [dst]; единственный вход для кнопок, F5/F6, меню, перетаскивания.
     * Сначала проверяется, что уже лежит в приёмнике (в том числе внутри папок с одним именем): если есть совпадения и решений [acts] ещё нет,
     * показывается окно «Заменить / Пропустить / Переименовать». Копия в ту же папку получает имя «имя (1)».
     */
    fun copyMove(move: Boolean, src: FmPane, dst: FmPane, names: List<String>, acts: Map<String, CAct>? = null, newName: String? = null) {
        if (names.isEmpty()) return
        if (names.any { '/' in it }) {
            // отмечено в разных раскрытых папках дерева: для каждой исходной папки своя операция (очередь заданий общая, идут по одной)
            val groups = names.groupBy { it.substringBeforeLast('/', "") }
            scope.launch {
                for ((parent, list) in groups) {
                    val ps = if (parent.isEmpty()) src else FmPane(9, join(src.path, parent)).also { it.src = src.src; it.smb = src.smb; it.net = src.net; it.auto = src.auto }
                    copyMove(move, ps, dst, list.map { it.substringAfterLast('/') }, acts)
                    delay(500)
                    while (dlg is FmDlg.Conflict) delay(300)
                }
                delay(1500)
                load(src, remember = false)
            }
            return
        }
        val sameFolder = src.src == dst.src && src.path.trimEnd('/') == dst.path.trimEnd('/')
        if (sameFolder && move) { say(str(R.string.fm_same_folder)); return }
        val ok = names.filter { n -> src.src != dst.src || !(join(src.path, n).let { s -> dst.path == s || dst.path.startsWith("$s/") }) }
        if (ok.isEmpty()) { say(str(R.string.fm_into_itself)); return }
        scope.launch {
            val st = PaneTree(src); val dt = PaneTree(dst)
            // 08.11.2026 (владелица: «карандашик»): один элемент можно скопировать или перенести под другим именем; если такое имя уже есть, добавляется « (1)»
            val renameOne = newName?.takeIf { ok.size == 1 && it.isNotBlank() && it != ok[0] }
            if (renameOne != null) {
                val isD = st.stat(join(src.path, ok[0]))?.isDir == true
                var t = renameOne
                if (FmIo.exists(dst, join(dst.path, t))) t = uniqueName(dst, t, isD)
                runCopy(move, src, dst, ok, emptyMap(), st, dt, ok[0] to t); return@launch
            }
            var decided = acts
            if (decided == null) {
                if (sameFolder) decided = ok.associateWith { CAct.RENAME }
                else {
                    val rep = try { ClashLogic.scan(st, src.path, dt, dst.path, ok) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { say(str(R.string.fm_error, FmIo.nice(e))); return@launch }
                    if (rep.top.isNotEmpty()) { dlg = FmDlg.Conflict(move, src, dst, ok, rep); return@launch }
                    decided = emptyMap()
                }
            }
            runCopy(move, src, dst, ok, decided, st, dt)
        }
    }

    private suspend fun runCopy(move: Boolean, src: FmPane, dst: FmPane, ok: List<String>, acts: Map<String, CAct>, st: CTree, dt: CTree, renameTo: Pair<String, String>? = null) {
        val plan = try { if (renameTo != null) CopyPlan(listOf(CopyOp(renameTo.first, renameTo.second, st.stat(join(src.path, renameTo.first))?.isDir == true)), emptyList()) else ClashLogic.plan(st, src.path, dt, dst.path, ok, acts) { n, dir -> uniqueName(dst, n, dir) } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { say(str(R.string.fm_error, FmIo.nice(e))); return }
        val ops = plan.ops
        if (ops.isEmpty()) { say(str(R.string.fm_nothing_to_do)); return }
        val byFrom = ops.associateBy { it.from }
        val list = ops.map { it.from }
        // общий объём заранее: у локальных источников обходом, у остальных по размерам из списка; неизвестен: полоска бегущая
        val srcArc = (src.smb as? ArchiveFs)?.takeIf { src.src == "smb" }
        val total: suspend () -> Long = total@{
            var t = 0L
            for (n in list) {
                val item = src.items.firstOrNull { it.name == n }
                t += if (src.src == "local") LocalOps.size(java.io.File(join(src.path, n))) else if (srcArc != null) srcArc.sizeOf(join(src.path, n)) else if (item != null && !item.isDir) item.size else return@total -1L
            }
            t
        }
        val fcount: suspend () -> Int = { if (src.src == "local") list.sumOf { LocalOps.fileCount(java.io.File(join(src.path, it))) } else -1 }
        val dstArc = (dst.smb as? ArchiveFs)?.takeIf { dst.src == "smb" }
        if (dstArc != null && src.src == "local") {
            // много файлов в архив: одна перезапись архива, а не по одной на файл
            runJob(str(R.string.arc_adding), listOf(list.joinToString(", ")), true, total, fcount) { j, _ ->
                try { withContext(Dispatchers.IO) { dstArc.addFiles(ops.map { o -> java.io.File(join(src.path, o.from)) to join(dst.path, o.to) }, j); if (move) list.forEach { n -> java.io.File(join(src.path, n)).deleteRecursively() } }; null }
                catch (e: Throwable) { if (e is LocalOps.Cancelled) null else FmIo.nice(e) }
            }
        } else {
            runJob(str(if (move) R.string.fm_moving else R.string.fm_copying), list, true, total, fcount) { j, n ->
                val o = byFrom.getValue(n)
                FmIo.copyMove(src, dst, o.from, o.isDir, move, j, o.to, o.deleteFirst)
            }
        }
        if (move) for (m in plan.merged) FmIo.pruneEmpty(src, join(src.path, m))
        // обновить все панели, которые показывают папку-приёмник или папку-источник
        panes.forEach { pp -> if (pp.src == dst.src && pp.path == dst.path || move && pp.src == src.src && pp.path == src.path) load(pp, remember = false) }
    }

    /** Отметка в окне удаления для облака: удалить навсегда, минуя корзину облака (только на время одной операции). */
    @Volatile var deletePermanent = false

    /** После восстановления из корзины облака: панели, открытые на этом облаке, перечитываются. */
    fun reloadCloud(host: String) { scope.launch { panes.forEach { pp -> if (pp.src == "smb" && pp.smb !is ArchiveFs && pp.net?.let { NetTrash.rid(it) } == host) load(pp, remember = false) } } }

    fun delete(p: FmPane, names: List<String>) = scope.launch {
        (p.smb as? ArchiveFs)?.takeIf { p.src == "smb" }?.let { a ->
            runJob(str(R.string.fm_deleting), listOf(names.joinToString(", ")), false) { _, _ -> try { withContext(Dispatchers.IO) { a.delete(names.map { join(p.path, it) }) }; null } catch (e: Throwable) { FmIo.nice(e) } }
            load(p, remember = false); return@launch
        }
        FmTrash.failed.clear()
        val isCloud = p.src == "smb" && p.smb is CloudFs
        val toTrash = p.src == "local" && !deletePermanent && FmDel.trashOn
        runJob(str(R.string.fm_deleting), names, false, { if (p.src == "local") names.sumOf { LocalOps.count(java.io.File(join(p.path, it))).toLong() } else -1L }) { j, n -> FmIo.delete(p, join(p.path, n), j) }
        load(p, remember = false)
        // облако отвечает со сдвигом: список перечитывается ещё раз, чтобы удалённое не висело
        if (isCloud) { delay(2500); load(p, remember = false) }
        if (p.src == "local") {
            FmTrash.bump(false, p.path)
            FmTrash.refreshSum()
            // часть элементов не удалось перенести в корзину: честная ошибка и предложение удалить навсегда
            val bad = FmTrash.failed.toList()
            if (toTrash && bad.isNotEmpty()) {
                val paths = bad.map { it.first }.toSet()
                val left = names.filter { join(p.path, it) in paths }
                if (left.isNotEmpty()) dlg = FmDlg.Delete(p, left, DelPlan(true), str(R.string.ft_failed, bad.first().second), true)
            }
        }
    }

    /** Как будет удалено: в корзину или навсегда (для окна подтверждения и решения, спрашивать ли вообще). */
    suspend fun planDelete(p: FmPane, names: List<String>): DelPlan {
        if (p.src == "local" && p.smb !is ArchiveFs) {
            if (!FmDel.trashOn) return DelPlan(perm = true)
            return withContext(Dispatchers.IO) {
                var ok = 0; var big = 0; var none = 0
                names.forEach { n ->
                    when (FmTrash.why(java.io.File(join(p.path, n)))) { FmTrash.Why.OK -> ok++; FmTrash.Why.BIG -> big++; else -> none++ }
                }
                DelPlan(perm = big > 0 || none > 0 || ok == 0, big = big, none = none)
            }
        }
        if (p.src == "smb" && p.smb !is ArchiveFs && p.net != null) {
            val ty = CloudTrash.typeFor(sctx, p.net!!)
            return DelPlan(perm = ty == null || !CloudTrash.supported(ty))
        }
        return DelPlan(perm = true)
    }

    /** Запрос на удаление: по настройке «Подтверждение удаления» показывает окно или удаляет сразу. */
    fun askDelete(p: FmPane, names: List<String>) {
        // без права записи ни корзина, ни удаление не сработают: сразу понятное окно с кнопкой в настройки
        if (p.src == "local" && !hasWrite(sctx)) { dlg = FmDlg.NeedAccess(); return }
        scope.launch {
            val plan = planDelete(p, names)
            if (FmDel.needAsk(plan.perm)) dlg = FmDlg.Delete(p, names, plan)
            else delete(p, names)
        }
    }

    fun rename(p: FmPane, name: String, newName: String) = scope.launch {
        if (newName.isBlank() || '/' in newName || newName == name) return@launch
        // регистр: переименование «a.txt» в «A.TXT» на FAT указывает на тот же файл, это допустимо; любое другое существующее имя занято
        if (!newName.equals(name, ignoreCase = true) && FmIo.exists(p, join(p.path, newName))) { say(str(R.string.fm_error, str(R.string.fm_err_exists))); return@launch }
        FmIo.rename(p, join(p.path, name), join(p.path, newName))?.let { say(str(R.string.fm_error, it)) }
        load(p, remember = false, pick = newName)
    }

    fun mkdir(p: FmPane, name: String) = mkdirIn(p, p.path, name)

    /** После создания внутри [dir]: текущая папка перечитывается с выбором нового элемента, вложенная раскрывается и подгружается. */
    private suspend fun afterCreate(p: FmPane, dir: String, pick: String) {
        if (dir.trimEnd('/') == p.path.trimEnd('/')) { load(p, remember = false, pick = pick); return }
        p.kids.remove(dir)
        if (dir !in p.expanded) { p.expanded.add(dir); saveExpanded(p) }
        loadKids(p, dir)
    }

    fun mkdirIn(p: FmPane, dir: String, name: String): kotlinx.coroutines.Job = scope.launch {
        if (name.isBlank() || '/' in name) { say(str(R.string.fm_name_invalid)); return@launch }
        FmIo.mkdir(p, join(dir, name))?.let { say(str(R.string.fm_error, it)); return@launch }
        afterCreate(p, dir, name)
    }

    /** Можно ли создавать в этом источнике (сеть без записи: нельзя; открытый ZIP: можно). */
    fun canCreate(p: FmPane): Boolean = p.src != "smb" || (p.smb as? ArchiveFs)?.canWrite == true || p.smb is KeepFs
    /** Пустой ZIP: на локальном диске, по ADB и внутри открытого ZIP (запись в архив нашей правкой ZIP). */
    fun canCreateZip(p: FmPane): Boolean = p.src == "local" || p.src == "adb" || (p.src == "smb" && ((p.smb as? ArchiveFs)?.canWrite == true || p.smb is KeepFs))
    /** Открыт архив, который нельзя менять (7z, tar, rar…): пункты «Создать» серые с пояснением. */
    fun arcReadOnly(p: FmPane): Boolean = p.src == "smb" && (p.smb as? ArchiveFs)?.canWrite == false

    /** Свободное имя «Новый архив.zip», «Новый архив (1).zip» и т.д. */
    suspend fun freeZipName(p: FmPane, dir: String, base: String): String {
        var n = "$base.zip"; var i = 1
        while (FmIo.exists(p, join(dir, n))) { n = "$base ($i).zip"; i++ }
        return n
    }

    /** Создать пустой ZIP-архив в папке [dir]. Возвращает текст ошибки или null. */
    suspend fun newZip(p: FmPane, dir: String, raw: String): String? {
        var n = raw.trim()
        if (n.isEmpty() || '/' in n || n.contains(Char(92))) return str(R.string.fm_name_invalid)
        if (!n.lowercase().endsWith(".zip")) n += ".zip"
        val name = freeZipName(p, dir, n.removeSuffix(".zip"))
        val tmp = java.io.File(sctx.cacheDir, "newzip_" + System.nanoTime() + ".zip")
        val err = withContext(Dispatchers.IO) {
            runCatching {
                // пустой ZIP-архив = 22 байта: только запись конца каталога
                tmp.writeBytes(byteArrayOf(0x50, 0x4B, 0x05, 0x06) + ByteArray(18))
                when {
                    p.src == "adb" -> { tmp.inputStream().use { Adb.pushStream(it, tmp.length(), join(dir, name)) }; null }
                    p.src == "smb" && p.smb is KeepFs -> { (p.smb as KeepFs).upload(tmp, join(dir, name), false); null }
                    p.src == "smb" -> { (p.smb as ArchiveFs).addFiles(listOf(tmp to join(dir, name)), null); null }
                    else -> { tmp.copyTo(java.io.File(join(dir, name)), overwrite = false); null }
                }
            }.getOrElse { FmIo.nice(it) }.also { tmp.delete() }
        }
        if (err != null) return err
        afterCreate(p, dir, name)
        return null
    }

    /** Имя нового текстового файла: «.txt» добавляется, если расширение не указано. Пусто, если имя недопустимо. */
    fun textFileName(raw: String): String? {
        val n = raw.trim()
        if (n.isEmpty() || '/' in n || n.contains(Char(92)) || n == "." || n == "..") return null
        return if (n.lastIndexOf('.') > 0 && !n.endsWith(".")) n else n.trimEnd('.') + ".txt"
    }

    /** Свободное имя «Новый текстовый файл.txt», «Новый текстовый файл (1).txt» и т.д. */
    suspend fun freeTextName(p: FmPane, wanted: String, dir: String = p.path): String {
        val dot = wanted.lastIndexOf('.'); val base = if (dot > 0) wanted.substring(0, dot) else wanted; val ext = if (dot > 0) wanted.substring(dot) else ""
        var n = wanted; var i = 1
        while (FmIo.exists(p, join(dir, n))) { n = "$base ($i)$ext"; i++ }
        return n
    }

    /** Создать пустой текстовый файл (UTF-8, без BOM) и открыть его во встроенном редакторе. Возвращает текст ошибки или null. */
    suspend fun newTextFile(p: FmPane, raw: String, dir: String = p.path): String? {
        val name0 = textFileName(raw) ?: return str(R.string.fm_name_invalid)
        val name = freeTextName(p, name0, dir)
        FmIo.touch(p, join(dir, name))?.let { return it }
        afterCreate(p, dir, name)
        if ((p.src == "local" || p.smb is KeepFs) && dir.trimEnd('/') == p.path.trimEnd('/')) { delay(250); openDoc(sctx, p, FmItem(name, false, 0, ""), "text") }
        return null
    }

    fun props(p: FmPane, names: List<String>) = scope.launch {
        val sb = StringBuilder()
        for (n in names.take(5)) {
            sb.append(runCatching { FmIo.props(p, if (n.isEmpty()) p.path else join(p.path, n)) }.getOrElse { FmIo.nice(it) }).append("\n\n")
        }
        dlg = FmDlg.Props(str(R.string.fm_props_title), sb.toString().trim())
    }

    fun search(q0: String) = scope.launch {
        if (q0.isBlank()) return@launch
        val l = runCatching { FmIo.search(cur, cur.path, q0) }.getOrDefault(emptyList())
        dlg = FmDlg.SearchResult(l)
    }

    fun goTo(p: FmPane, fullPath: String, isDir: Boolean) {
        if (isDir) load(p, fullPath) else load(p, fullPath.substringBeforeLast('/', "/").ifEmpty { "/" }, pick = fullPath.substringAfterLast('/'))
    }

    private fun mimeOf(ext: String): String = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"

    private fun viewLocal(f: java.io.File, ext: String, pkg: String? = null, mime: String? = null): Boolean {
        val uri = androidx.core.content.FileProvider.getUriForFile(sctx, sctx.packageName + ".files", f)
        val m = mime ?: if (ext == "apk") "application/vnd.android.package-archive" else mimeOf(ext)
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, m)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        pkg?.let { if (it.contains(Char(47))) i.setClassName(it.substringBefore(Char(47)), it.substringAfter(Char(47))) else i.setPackage(it) }
        val ok = runCatching { sctx.startActivity(i) }.isSuccess
        if (!ok && pkg == null) say(str(R.string.fm_no_app))
        return ok
    }

    private fun tmpCopy(p: FmPane, name: String): java.io.File = java.io.File(java.io.File(sctx.cacheDir, "fmopen").apply { mkdirs() }, name.substringAfterLast('/'))

    /** Открыть выбранным приложением (пакет [pkg]); для сетевого источника файл сначала копируется во временную папку. */
    suspend fun openWithPkg(p: FmPane, it: FmItem, pkg: String?, mime: String? = null): Boolean {
        val f = join(p.path, it.name)
        return when (p.src) {
            "local" -> viewLocal(java.io.File(f), it.ext, pkg, mime)
            "smb" -> { val tmp = tmpCopy(p, it.name); val ok = withContext(Dispatchers.IO) { runCatching { p.smb!!.copyTo(f, tmp) }.isSuccess }; ok && viewLocal(tmp, it.ext, pkg, mime) }
            else -> false
        }
    }

    /** «Открыть в системе»: выбранное приложение или (pkg = null) системное окно выбора. */
    fun openWith(p: FmPane, it: FmItem, pkg: String?, mime: String? = null): kotlinx.coroutines.Job = scope.launch { if (!openWithPkg(p, it, pkg, mime)) say(str(R.string.fm_no_app)) }

    fun openArchiveFor(p: FmPane, it: FmItem) { openArchive(p, it.name) }

    /** Установка пакета из нескольких частей (APKS, APKM, XAPK, ZIP с APK и OBB) через ADB/root: части и OBB подбираются под устройство. */
    fun installBundleViaAdb(p: FmPane, it: FmItem): kotlinx.coroutines.Job = scope.launch {
        val silent = Adb.isConnected && !FmIo.remote
        if (!silent && !ensureInstallPermission { installBundleViaAdb(p, it) }) return@launch
        if (silent) {
            // тихая установка по ADB/root: сначала подтверждение с версиями, потом окно с полоской хода (части и OBB, «n из N»), итог внутри окна
            val srcF = fileForInstall(p, it.name) ?: run { say(str(R.string.fm_err_unsupported)); return@launch }
            val list = withContext(Dispatchers.IO) { runCatching { Archives.extractApks(sctx, android.net.Uri.fromFile(srcF)) }.getOrDefault(emptyList()) }
            if (list.isEmpty()) { dlg = FmDlg.InstallFail(str(R.string.inst_failed)); return@launch }
            val metas = withContext(Dispatchers.IO) { list.associate { a -> a.name to runCatching { ApkInfo.readFor(sctx, a) }.getOrNull() } }
            dlg = FmDlg.InstallConfirm(list, metas) { wipe ->
                scope.launch {
                    val run = InstRun()
                    InstBg.launch(sctx, run) { dlg = FmDlg.InstallRunDlg(it) }
                    installWithProgress(sctx, list, metas, run, wipe)
                }
            }
            return@launch
        }
        say(str(R.string.inst_installing))
        var last: SessionInstall.Result? = null
        val srcF2 = fileForInstall(p, it.name) ?: run { say(str(R.string.fm_err_unsupported)); return@launch }
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val f = srcF2
                val list = Archives.extractApks(sctx, android.net.Uri.fromFile(f))
                list.isNotEmpty() && list.all { a ->
                    val parts = a.parts ?: listOfNotNull(a.file)
                    if (silent) Bundles.installAdb(sctx, a) || (a.parts == null && Adb.installFiles(parts, "-r").contains("Success", true))
                    else {
                        // без ADB/root: OBB копируются заранее, части ставятся сессией с подтверждением системы
                        if (a.obb.isNotEmpty() && Bundles.copyObbAny(a.obb) < 0) false
                        else SessionInstall.install(sctx, parts).also { r -> last = r; if (!r.ok) Bundles.deleteObb(a.obb) }.ok
                    }
                }
            }.getOrDefault(false)
        }
        val r = last
        if (ok) say(str(R.string.fm_installed))
        else if (r != null && r.cancelled) say(str(R.string.fm_cancelled))
        else dlg = FmDlg.InstallFail(r?.message.orEmpty())
    }

    /** Файл пакета для установки из любого источника: локальный, сетевая папка, облако, архив или оболочка ADB (забирается во временную папку). */
    private suspend fun fileForInstall(p: FmPane, name: String): java.io.File? = when (p.src) {
        "local" -> java.io.File(join(p.path, name))
        "adb" -> withContext(Dispatchers.IO) {
            val t = java.io.File(arcTmp(), "inst_" + System.nanoTime().toString(36) + "_" + name.substringAfterLast('/').replace(Regex("[^\\w.\\- ]"), "_"))
            if (runCatching { Adb.pull(join(p.path, name), t); t.exists() && t.length() > 0 }.getOrDefault(false)) t else null
        }
        else -> localFileOf(p, name)
    }

    /** Что доделать после возврата с экрана «Установка неизвестных приложений». */
    private var pendingInstall: (() -> Unit)? = null

    fun resumePendingInstall() {
        val job = pendingInstall ?: return
        pendingInstall = null
        if (sctx.packageManager.canRequestPackageInstalls()) job()
    }

    /** Разрешение «Установка неизвестных приложений» для ADB-phon: если нет, открывается нужный экран настроек, установка продолжится после возврата. */
    fun ensureInstallPermission(next: () -> Unit): Boolean {
        if (sctx.packageManager.canRequestPackageInstalls()) return true
        pendingInstall = next
        Setup.askKey = "install"
        if (false) runCatching { sctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + sctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return false
    }

    /** Установка набора APK собственной сессией (без ADB/root): при необходимости сначала выдаётся разрешение, затем системное подтверждение. */
    fun installFilesViaSession(files: List<java.io.File>) {
        scope.launch {
            if (!ensureInstallPermission { installFilesViaSession(files) }) return@launch
            reportSession(SessionInstall.install(sctx, files))
        }
    }

    private fun reportSession(r: SessionInstall.Result) {
        when {
            r.ok -> say(str(R.string.fm_installed))
            r.cancelled -> say(str(R.string.fm_cancelled))
            else -> dlg = FmDlg.InstallFail(r.message)
        }
    }

    /** Наш способ установки APK: тихо через ADB/root, а без них сессией PackageInstaller с подтверждением системы. */
    fun installViaAdb(p: FmPane, it: FmItem): kotlinx.coroutines.Job = scope.launch {
        val f = join(p.path, it.name)
        if (Adb.isConnected && !FmIo.remote && p.src == "local") {
            val lf = java.io.File(f)
            val af = ApkFile(it.name, android.net.Uri.fromFile(lf), lf.length(), lf)
            val meta = withContext(Dispatchers.IO) { ApkInfo.readFor(sctx, af) }
            dlg = FmDlg.InstallConfirm(listOf(af), mapOf(af.name to meta)) { wipe ->
                scope.launch {
                    val run = InstRun()
                    InstBg.launch(sctx, run) { dlg = FmDlg.InstallRunDlg(it) }
                    installWithProgress(sctx, listOf(af), mapOf(af.name to meta), run, wipe)
                }
            }
            return@launch
        }
        // сеть, облако, архив, оболочка ADB: файл забирается во временную папку, дальше то же окно «Установка» с версиями и ход установки
        say(str(R.string.inst_installing))
        val file = fileForInstall(p, it.name) ?: run { say(str(R.string.fm_err_unsupported)); return@launch }
        val af = ApkFile(it.name, android.net.Uri.fromFile(file), file.length(), file)
        val meta = withContext(Dispatchers.IO) { runCatching { ApkInfo.readFor(sctx, af) }.getOrNull() }
        dlg = FmDlg.InstallConfirm(listOf(af), mapOf(af.name to meta)) { wipe ->
            scope.launch {
                if (Adb.isConnected && !FmIo.remote) {
                    val run = InstRun()
                    InstBg.launch(sctx, run) { dlg = FmDlg.InstallRunDlg(it) }
                    installWithProgress(sctx, listOf(af), mapOf(af.name to meta), run, wipe)
                } else installFilesViaSession(listOf(file))
            }
        }
    }

    /** Встроенный просмотрщик или редактор для типа файла независимо от запомненного приложения. */
    fun openBuiltinForce(p: FmPane, it: FmItem) {
        val f = join(p.path, it.name)
        DocKind.of(it.ext)?.let { k -> openDoc(sctx, p, it, k); return }
        if (p.src == "adb") { say(str(R.string.fm_err_unsupported)); return }
        openCat(it.ext)?.let { cat -> openBuiltin(sctx, p, it, cat, f); return }
        openWith(p, it, null)
    }

    /** «Открыть как…»: тип выбирается вручную. */
    fun openAs(p: FmPane, it: FmItem, type: String) = scope.launch {
        val f = join(p.path, it.name)
        if (p.src == "adb" && type != "apk" && type != "text") { say(str(R.string.fm_err_unsupported)); return@launch }
        when (type) {
            "text" -> openDoc(sctx, p, it, "text")
            "image" -> openBuiltin(sctx, p, it, "photo", f)
            "audio" -> openBuiltin(sctx, p, it, "audio", f)
            "video" -> openBuiltin(sctx, p, it, "video", f)
            "archive" -> openArchive(p, it.name)
            "apk" -> if (p.src == "adb" && it.ext == "apk") ApkInstall.start(p, it) else if (p.src == "adb") {
                val out = runCatching { Adb.shell(pmInstallCmd(f), 120_000L) }.getOrDefault("")
                say(if (out.contains("Success")) str(R.string.fm_installed) else str(R.string.fm_error, out.lines().firstOrNull { l -> l.isNotBlank() }.orEmpty()))
            } else if (!openWithPkg(p, it, null, "application/vnd.android.package-archive")) say(str(R.string.fm_no_app))
            else -> if (!openWithPkg(p, it, null, "*/*")) say(str(R.string.fm_no_app))
        }
    }

    /** Отправить: файлы через системное «Поделиться»; если среди выбранного есть папка, всё упаковывается в один zip. */
    fun share(p: FmPane, names: List<String>) = scope.launch {
        if (p.src == "adb") { say(str(R.string.fm_err_unsupported)); return@launch }
        val dir = java.io.File(sctx.cacheDir, "fmshare").apply { deleteRecursively(); mkdirs() }
        say(str(R.string.fm_sharing))
        val files: List<java.io.File>? = withContext(Dispatchers.IO) {
            runCatching {
                val paths = names.map { join(p.path, it) }
                if (p.src == "local") {
                    val fs = paths.map { java.io.File(it) }.filter { it.exists() }
                    if (fs.none { it.isDirectory }) fs else {
                        val zip = java.io.File(dir, (if (fs.size == 1) fs[0].name else "files") + ".zip")
                        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zip).buffered()).use { z ->
                            fun add(f: java.io.File, rel: String) {
                                if (f.isDirectory) { z.putNextEntry(java.util.zip.ZipEntry("$rel/")); z.closeEntry(); f.listFiles()?.sortedBy { it.name }?.forEach { add(it, "$rel/${it.name}") } }
                                else { z.putNextEntry(java.util.zip.ZipEntry(rel)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
                            }
                            fs.forEach { add(it, it.name) }
                        }
                        listOf(zip)
                    }
                } else {
                    // сетевой источник: файлы копируются в кэш, папки не отправляются
                    names.mapIndexedNotNull { i, n -> val t = java.io.File(dir, n.substringAfterLast('/')); if (runCatching { p.smb!!.copyTo(paths[i], t) }.isSuccess) t else null }
                }
            }.getOrNull()
        }
        if (files.isNullOrEmpty()) { say(str(R.string.fm_share_none)); return@launch }
        val uris = ArrayList(files.map { androidx.core.content.FileProvider.getUriForFile(sctx, sctx.packageName + ".files", it) })
        val i = if (uris.size == 1) android.content.Intent(android.content.Intent.ACTION_SEND).setType(mimeOf(files[0].extension.lowercase())).putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
        else android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
        val clip = android.content.ClipData.newRawUri("", uris[0]); uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
        i.clipData = clip
        i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { sctx.startActivity(android.content.Intent.createChooser(i, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { say(str(R.string.fm_no_app)) }
    }

    /** 9.0: в начале файла нет нулевых байтов и почти нет «мусорных» знаков: это текст (любое расширение). Читаем не больше 4 КБ. */
    private suspend fun looksLikeText(p: FmPane, it: FmItem): Boolean {
        if (it.size > 20L * 1024 * 1024) return false
        val head = runCatching { withContext(Dispatchers.IO) {
            val path = join(p.path, it.name)
            when (p.src) {
                "adb" -> FmIo.readText(p, path).take(4000).toByteArray(Charsets.UTF_8)
                "smb" -> p.smb?.head(path, 4096) ?: ByteArray(0)
                else -> java.io.File(path).inputStream().use { i -> val b = ByteArray(4096); val n = i.read(b); if (n <= 0) ByteArray(0) else b.copyOf(n) }
            }
        } }.getOrDefault(ByteArray(0))
        if (head.isEmpty()) return it.size == 0L
        if (head.any { b -> b.toInt() == 0 }) return false
        val s = String(head, Charsets.UTF_8)
        val bad = s.count { c -> c == '\uFFFD' || (c.code < 32 && c != '\n' && c != '\r' && c != '\t' && c != '\u000C' && c != '\u001B') }
        return bad * 20 <= s.length
    }

    fun openFile(p: FmPane, it: FmItem) = scope.launch {
        val f = join(p.path, it.name)
        if (p.src == "adb" && (it.ext == "apk" || ApkInstall.isBundle(it.ext))) { ApkInstall.start(p, it); return@launch }
        if (p.src != "adb" && Arc.kindOf(it.name) != null) {
            if (it.ext == "apk" || it.ext == "apks" || it.ext == "xapk") { ApkInstall.start(p, it); return@launch }
            if (Arc.opensAsFolder(it.name)) { openArchive(p, it.name); return@launch }
        }
        if (p.src != "adb" && it.ext == "apkm") { ApkInstall.start(p, it); return@launch }
        // выбранное приложение для этого типа (меню «Открыть в системе» → «По умолчанию») или встроенные просмотрщики и редакторы
        val saved = UiPrefs.openAs[openKey(it.ext)]
        if (saved == null && p.src != "adb" && ApkInstall.chooseOpen(p, it)) return@launch
        val how = saved ?: "builtin"
        if (how != "builtin" && p.src != "adb" && openWithPkg(p, it, how)) return@launch
        if (p.src != "adb" && how == "builtin") {
            DocKind.of(it.ext)?.let { k -> openDoc(sctx, p, it, k); return@launch }
            openCat(it.ext)?.let { cat -> openBuiltin(sctx, p, it, cat, f); return@launch }
        }
        // 9.0: документы и текст открываются встроенными просмотрщиками и на источнике «ADB/root» (файл копируется в кэш и читается там)
        if (p.src == "adb" && how == "builtin") DocKind.of(it.ext)?.let { k -> openDoc(sctx, p, it, k); return@launch }
        // 9.0: файл с любым расширением (или без него), внутри которого обычный текст, открывается во встроенном редакторе, а не уходит в выбор приложения
        if (how == "builtin" && p.src != "smb" && DocKind.of(it.ext) == null && openCat(it.ext) == null && Arc.kindOf(it.name) == null && it.ext != "apk" && looksLikeText(p, it)) { openDoc(sctx, p, it, "text"); return@launch }
        when (p.src) {
            "adb" -> if (it.ext == "apk") {
                val out = runCatching { Adb.shell(pmInstallCmd(f), 120_000L) }.getOrDefault("")
                say(if (out.contains("Success")) str(R.string.fm_installed) else str(R.string.fm_error, out.lines().firstOrNull { l -> l.isNotBlank() }.orEmpty()))
            } else {
                val t = mimeOf(it.ext)
                runCatching { Adb.shell("am start -a android.intent.action.VIEW -d 'file://${f.replace("'", "'\\''")}' -t '$t' 2>&1") }
            }
            else -> if (!openWithPkg(p, it, null)) say(str(R.string.fm_no_app))
        }
    }

    fun viewText(p: FmPane, name: String) = scope.launch {
        val out = runCatching { FmIo.readText(p, join(p.path, name)) }.getOrElse { FmIo.nice(it) }
        dlg = FmDlg.TextView(name, out)
    }

    /** 9.0: имена (последнее звено пути) выбранных элементов в буфер, по строке на каждый. */
    fun copyNames(names: List<String>) = copyText(sctx, names.joinToString("\n") { it.substringAfterLast('/') })
    fun copyPaths(p: FmPane, names: List<String>) = copyText(sctx, names.joinToString("\n") { join(p.path, it) })

    fun copyPath(p: FmPane, name: String?) {
        val full = if (name == null) p.path else join(p.path, name)
        (sctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("path", full))
        say(str(R.string.fm_copied))
    }

    // ---------- архивы ----------
    fun isArchive(n: String) = FmIo.isArchive(n)

    fun archiveList(p: FmPane, name: String) = scope.launch {
        val l = runCatching { FmIo.archiveList(p, join(p.path, name)) }.getOrElse { listOf(FmIo.nice(it)) }
        dlg = FmDlg.Archive(p, name, l)
    }

    fun extract(p: FmPane, name: String, to: FmPane) = scope.launch {
        runJob(str(R.string.fm_extracting), listOf(name)) { FmIo.extract(p, name, to) }
        load(to, remember = false)
    }

    fun makeArchive(p: FmPane, names: List<String>, outName: String) = scope.launch {
        val out = if (outName.endsWith(".tar.gz") || outName.endsWith(".tgz")) outName else "$outName.tar.gz"
        runJob(str(R.string.fm_packing), listOf(out)) { FmIo.makeTar(p, names, out) }
        load(p, remember = false, pick = out)
    }

    val quick = listOf("/sdcard", "/sdcard/Download", "/sdcard/DCIM", "/sdcard/Android/data", "/data/local/tmp")
}

fun fmSize(b: Long): String {
    fun u(id: Int, fb: String) = runCatching { Fm.str(id) }.getOrDefault(fb).ifBlank { fb }
    val loc = runCatching { Fm.sctx.resources.configuration.locales[0] }.getOrDefault(java.util.Locale.getDefault())
    return when {
        b < 1024 -> "$b " + u(R.string.unit_b, "B")
        b < 1024 * 1024 -> "%.0f %s".format(loc, b / 1024.0, u(R.string.unit_kb, "KB"))
        b < 1024L * 1024 * 1024 -> "%.1f %s".format(loc, b / 1048576.0, u(R.string.unit_mb, "MB"))
        else -> "%.2f %s".format(loc, b / 1073741824.0, u(R.string.unit_gb, "GB"))
    }
}

fun iconFor(it: FmItem): ImageVector = if (it.isDir) Icons.Filled.Folder else when (it.ext) {
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> Icons.Filled.Image
    "mp4", "mkv", "avi", "mov", "webm", "3gp" -> Icons.Filled.Movie
    "mp3", "wav", "flac", "ogg", "m4a", "aac", "opus" -> Icons.Filled.MusicNote
    "apk", "apks", "xapk" -> Icons.Filled.Android
    "zip", "7z", "rar", "tar", "gz", "tgz", "jar" -> Icons.Filled.FolderZip
    "txt", "log", "md", "json", "xml", "html", "csv", "ini", "conf" -> Icons.Filled.Description
    else -> Icons.Filled.InsertDriveFile
}

// ================================================================= экран

/** Вкладка «Файлы»: двухпанельный менеджер. Ландшафт: две панели рядом и узкая колонка действий (для левши зеркально). Портрет: активная панель и узкая полоса слева. */
/**
 * Размеры портретной раскладки «Файлов», от которых зависит выравнивание нижней панели: шестерёнка внизу справа ставится на одну вертикальную линию
 * с осью правой колонки кнопок (см. [stripCenterFromRight] и MainActivity).
 */
object FmMetrics {
    val OUTER_PAD = 2.dp          // отступ раскладки от краёв окна
    val STRIP_END = 6.dp          // дополнительный отступ колонки от правого края (кнопки не лезут под жесты)
    val STRIP_W = 46.dp           // ширина колонки (10.11.2026: было 52, кнопки уже; значок и подпись внутри не менялись)
    val BTN_W = 44.dp             // ширина квадратной кнопки (прижата к левому краю колонки; было 50)
    /** Расстояние от правого края окна до оси кнопок правой колонки. */
    val stripCenterFromRight get() = OUTER_PAD + STRIP_END + (STRIP_W - BTN_W) + BTN_W / 2
}

@Composable
fun FileManagerScreen(vm: MainViewModel, onConnectTab: () -> Unit = {}) {
    val ctx = LocalContext.current
    Fm.goConnect = onConnectTab
    val reorderCtx = LocalContext.current
    androidx.activity.compose.BackHandler(enabled = FmButtons.reorder != null) { FmButtons.cancelReorder(reorderCtx) }
    FmIo.remote = vm.remoteHost != null
    LaunchedEffect(Unit) { Fm.init(ctx); Fm.panes.forEach { if (it.error != null) Fm.load(it, remember = false) } }
    // 6.0: стоит старый модуль облаков (1.0), есть новый (1.1, меньше места): один раз за запуск предлагаем обновить обычной установкой (окно системы подтверждает установку)
    // 7.6: вопрос об обновлении модуля и само обновление теперь в корне экрана (ModuleHost), при запуске программы на любой вкладке
    Fm.sctx = ctx
    // на Android 6-10 доступ просится обычным системным окном
    val legacy = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { Fm.recheck(ctx) }
    Fm.askLegacy = { legacy.launch(arrayOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE")) }
    // возврат из настроек: список обновляется сам
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val ob = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { Fm.recheck(ctx); Fm.refreshVols(); Fm.resumePendingInstall() } }
        owner.lifecycle.addObserver(ob)
        onDispose { owner.lifecycle.removeObserver(ob) }
    }
    // подключение и извлечение SD/USB обновляют список мест сразу
    androidx.compose.runtime.DisposableEffect(Unit) {
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: android.content.Intent?) { Fm.refreshVols() }
        }
        val f = android.content.IntentFilter().apply {
            listOf(android.content.Intent.ACTION_MEDIA_MOUNTED, android.content.Intent.ACTION_MEDIA_UNMOUNTED, android.content.Intent.ACTION_MEDIA_EJECT,
                android.content.Intent.ACTION_MEDIA_REMOVED, android.content.Intent.ACTION_MEDIA_BAD_REMOVAL).forEach { addAction(it) }
            addDataScheme("file")
        }
        runCatching { ctx.registerReceiver(rx, f) }
        Fm.refreshVols()
        onDispose { runCatching { ctx.unregisterReceiver(rx) } }
    }
    // перетаскивание не должно «застревать»: сброс при сворачивании приложения и при уходе с вкладки
    androidx.compose.runtime.DisposableEffect(owner) {
        val ob = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || e == androidx.lifecycle.Lifecycle.Event.ON_STOP) FmDnd.cancel() }
        owner.lifecycle.addObserver(ob)
        onDispose { owner.lifecycle.removeObserver(ob); FmDnd.cancel() }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
        // пульт: «Меню» или «Info» при отмеченном переводит фокус на нижнюю панель значков (с неё «Назад» возвращает на список)
        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && FmRootCfg.sel.isNotEmpty() && (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU || e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_INFO)) runCatching { FmRootCfg.barFocus.requestFocus() }.isSuccess else false
    }.onGloballyPositioned { FmDnd.rootOrigin = it.positionInRoot() }.pointerInput(Unit) {
        // ведущий слой перетаскивания: события указателя приходят сюда независимо от того, какая панель сейчас на экране
        awaitPointerEventScope {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                if (!FmDnd.active) continue
                FmDnd.lastEvent = System.nanoTime() / 1_000_000
                val mine = ev.changes.firstOrNull { it.id.value == FmDnd.pointerId }
                // новое нажатие другим пальцем при незавершённом перетаскивании, потеря указателя или отпускание: сброс либо завершение
                if (mine == null || ev.changes.any { it.pressed && !it.previousPressed && it.id.value != FmDnd.pointerId }) { FmDnd.cancel(); continue }
                ev.changes.forEach { it.consume() }
                if (!mine.pressed) FmDnd.end() else FmDnd.move(mine.position + FmDnd.rootOrigin)
            }
        }
    }.onPreviewKeyEvent { e ->
        // Tab или Ctrl+стрелки влево/вправо: переключение панелей (пульт, клавиатура)
        val k = e.key
        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && (k == Key.Tab || (e.isCtrlPressed && (k == Key.DirectionLeft || k == Key.DirectionRight)))) {
            Fm.active = if (k == Key.DirectionLeft) 0 else if (k == Key.DirectionRight) 1 else 1 - Fm.active; true
        } else if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && k == Key.Escape && FmDnd.active) { FmDnd.cancel(); true }
        else if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && (k == Key.F5 || k == Key.F6)) {
            // как в двухпанельных менеджерах: F5 копировать, F6 переместить выделенное в другую панель
            val names = Fm.targets(Fm.cur)
            if (names.isEmpty()) Fm.say(Fm.str(R.string.fm_select_first)) else Fm.copyMove(k == Key.F6, Fm.cur, Fm.other, names)
            true
        } else false
    }) {
        val land = isLandscapeBox(maxWidth, maxHeight)
        val actions = rememberFmActions()
        if (land) {
            val right = Cfg.rightHanded(ctx)
            val tv = Marq.tv
            Row(Modifier.fillMaxSize().padding(if (tv) 4.dp else 2.dp), horizontalArrangement = Arrangement.spacedBy(if (tv) 6.dp else 3.dp)) {
                // телефон: все кнопки одним столбцом шириной 76 dp с прокруткой, остальное место панелям; приставка: прежняя сетка кнопок
                if (tv) {
                    // приставка: колонка кнопок всегда посередине между окнами, настройка «Справа/Слева» не действует
                    FmPaneView(Fm.panes[0], Modifier.weight(1f).fillMaxHeight(), volsRow = false)
                    // кнопки квадратные; размер подбирается по высоте экрана так, чтобы колонка заполняла её без пустоты снизу
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxHeight()) {
                        val rows = (rememberFmActionsAll().count { it.label in fmMainLabels } + 1 + 1) / 2   // размер кнопок считается по полному набору: скрытые кнопки не растягивают остальные
                        val cap = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.07f).dp
                        val sz = ((maxHeight - 30.dp - 4.dp * (rows - 1)) / rows).coerceIn(44.dp, 78.dp).let { if (it > cap) cap else it }
                        FmNarrow(actions, Modifier.width(sz * 2 + 4.dp).fillMaxHeight(), sz)
                    }
                    FmPaneView(Fm.panes[1], Modifier.weight(1f).fillMaxHeight(), volsRow = false)
                } else {
                if (!right) FmSideColumn(actions, Modifier.width(48.dp).fillMaxHeight())
                Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    FmPaneView(Fm.panes[0], Modifier.weight(1f).fillMaxHeight(), volsRow = false)
                    FmPaneView(Fm.panes[1], Modifier.weight(1f).fillMaxHeight(), volsRow = false)
                }
                if (right) FmSideColumn(actions, Modifier.width(48.dp).fillMaxHeight())
                }
            }
        } else {
            // портрет: на экране одна панель; вкладки сверху и узкая полоса слева переключают панели мгновенно, свайп по списку тоже
            Column(Modifier.fillMaxSize().padding(horizontal = FmMetrics.OUTER_PAD), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                FmTabs()
                // полоса кнопок с той же стороны, что и в ландшафте (настройка «Панели управления»); у края справа отступ от системного жеста «назад»
                val stripRight = Cfg.rightHanded(ctx) && !Marq.tv
                Row(Modifier.weight(1f).fillMaxWidth().padding(end = if (stripRight) FmMetrics.STRIP_END else 0.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!stripRight) FmStrip(actions, Modifier.width(FmMetrics.STRIP_W).fillMaxHeight())
                    FmPaneView(Fm.cur, Modifier.weight(1f).fillMaxHeight(), swipe = true)
                    if (stripRight) FmStrip(actions, Modifier.width(FmMetrics.STRIP_W).fillMaxHeight())
                }
            }
        }
        MiniPlayer(Modifier.align(Alignment.BottomCenter))
        FmOverlay(Modifier.align(Alignment.BottomCenter).padding(bottom = if (AudioState.active) 84.dp else 0.dp))
        FmDndOverlay()
    }
    FmDialogs()
}

class FmAction(val icon: ImageVector, val label: Int, val enabled: Boolean = true, val tint: Color? = null, val run: () -> Unit)

/** Какие кнопки боковой панели скрыты (7.0): настройка пользователя «Кнопки боковой панели...»; ключ кнопки: имя строки её названия. */
object FmButtons {
    var hidden by mutableStateOf(setOf<String>())
    private var loaded = false
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE)
    fun load(ctx: Context) { if (loaded) return; loaded = true; hidden = sp(ctx).getStringSet("hidden_btns", emptySet())?.toSet() ?: emptySet(); loadOrder(ctx) }
    fun key(ctx: Context, label: Int): String {
        val n = runCatching { ctx.resources.getResourceEntryName(label) }.getOrDefault(label.toString())
        return if (n == "fm_mode_tree" || n == "fm_mode_list") "fm_mode" else n
    }
    fun toggle(ctx: Context, key: String) { hidden = if (key in hidden) hidden - key else hidden + key; sp(ctx).edit().putStringSet("hidden_btns", hidden.toSet()).apply() }
    fun reset(ctx: Context) { hidden = emptySet(); order = emptyList(); sp(ctx).edit().remove("hidden_btns").remove("btn_order").apply() }

    // ---- порядок кнопок и перестановка (7.5) ----
    /** Сохранённый порядок ключей кнопок; кнопки, которых в нём нет, идут следом в обычном порядке. */
    var order by mutableStateOf(listOf<String>())
    /** Ключ кнопки, которую сейчас переставляют (режим включается долгим нажатием; на пульте удержанием ОК). */
    var reorder by mutableStateOf<String?>(null)
    private var snapshot: List<String> = emptyList()
    var startedAt = 0L
    private var lastAll: List<String> = emptyList()
    fun loadOrder(ctx: Context) { order = sp(ctx).getString("btn_order", "")?.split(',')?.filter { it.isNotEmpty() } ?: emptyList() }
    /** Применяет порядок к списку действий (стабильно). */
    fun ordered(ctx: Context, all: List<FmAction>): List<FmAction> {
        val keys = all.map { key(ctx, it.label) }
        lastAll = keys
        val pos = order.withIndex().associate { it.value to it.index }
        return all.withIndex().sortedBy { pos[keys[it.index]] ?: (1000 + it.index) }.map { it.value }
    }
    private fun fullOrder(): List<String> { val pos = order.withIndex().associate { it.value to it.index }; return lastAll.withIndex().sortedBy { pos[it.value] ?: (1000 + it.index) }.map { it.value } }
    private fun save(ctx: Context, l: List<String>) { order = l; sp(ctx).edit().putString("btn_order", l.joinToString(",")).apply() }
    fun swap(ctx: Context, a: String, b: String) {
        val l = fullOrder().toMutableList(); val i = l.indexOf(a); val j = l.indexOf(b)
        if (i < 0 || j < 0 || i == j) return
        java.util.Collections.swap(l, i, j); save(ctx, l)
    }
    /** Сдвиг кнопки на соседнюю видимую (delta кнопок в списке visible), для пульта. */
    fun move(ctx: Context, key: String, visible: List<String>, delta: Int) {
        val i = visible.indexOf(key); val j = i + delta
        if (i < 0 || j !in visible.indices) return
        swap(ctx, key, visible[j])
    }
    fun startReorder(key: String) { snapshot = fullOrder(); reorder = key; startedAt = System.currentTimeMillis() }
    fun finishReorder() { reorder = null }
    fun cancelReorder(ctx: Context) { if (reorder != null) { save(ctx, snapshot); reorder = null } }
}

/** Окно выбора кнопок боковой панели: убрать лишние и вернуть нужные. */
@Composable
fun FmButtonsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    remember { FmButtons.load(ctx); 0 }
    val all = FmButtons.ordered(ctx, rememberFmActionsAll()).distinctBy { FmButtons.key(ctx, it.label) }
    var pickBtn by remember { mutableStateOf<Pair<String, String>?>(null) }
    pickBtn?.let { (k, t) -> CustomIconPicker(t, "s:" + k) { pickBtn = null } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { HintTitle(stringResource(R.string.sb_title), stringResource(R.string.sb_reorder_note) + "\n\n" + stringResource(R.string.sb_icon_hint)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                all.forEach { a ->
                    val k = FmButtons.key(ctx, a.label)
                    Row(Modifier.fillMaxWidth().clickable { FmButtons.toggle(ctx, k) }, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(k !in FmButtons.hidden, { FmButtons.toggle(ctx, k) })
                        val csB = CustomIcons.spec("s:" + k)
                        val lbl = stringResource(if (k == "fm_mode") R.string.fm_mode_list else a.label)
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .12f)).clickable { pickBtn = k to lbl }, contentAlignment = Alignment.Center) {
                            if (csB != null) CustomIconGlyph(csB, 24.dp, MaterialTheme.colorScheme.primary) else Icon(a.icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(lbl, fontSize = 15.sp)
                    }
                }
                androidx.compose.material3.TextButton({ CustomIcons.clearPrefix(ctx, "s:") }) { Text(stringResource(R.string.sb_reset_icons)) }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClose) { Text(stringResource(R.string.btn_close)) } },
        dismissButton = { androidx.compose.material3.TextButton({ FmButtons.reset(ctx) }) { Text(stringResource(R.string.sb_reset)) } },
    )
}

@Composable
private fun rememberFmActions(): List<FmAction> {
    val c = LocalContext.current
    remember { FmButtons.load(c); 0 }
    val hid = FmButtons.hidden
    val allA = rememberFmActionsAll()
    return FmButtons.ordered(c, allA).filter { FmButtons.key(c, it.label) !in hid }
}

@Composable
fun rememberFmActionsAll(): List<FmAction> {
    val p = Fm.cur; val o = Fm.other
    val sel = p.selected.isNotEmpty()
    val sctx = LocalContext.current
    return listOf(
        FmAction(Icons.Filled.ArrowUpward, R.string.fm_up) { Fm.up(p) },
        FmAction(Icons.Filled.Refresh, R.string.fm_refresh) { if (p.root) FmRootCfg.refreshAll(Fm.sctx) else Fm.load(p, remember = false) },
        FmAction(Icons.Filled.ContentCopy, R.string.fm_b_copy, sel) { Fm.copyMove(false, p, o, Fm.targets(p)) },
        FmAction(Icons.Filled.DriveFileMove, R.string.fm_b_move, sel) { Fm.copyMove(true, p, o, Fm.targets(p)) },
        FmAction(Icons.Filled.DeleteOutline, R.string.fm_delete, sel) { Fm.askDelete(p, Fm.targets(p)) },
        FmAction(Icons.Filled.Edit, R.string.fm_b_rename, p.selected.size == 1 && p.selected.none { '/' in it }) { Fm.dlg = FmDlg.Rename(p, Fm.targets(p).first()) },
        FmAction(Icons.Filled.CreateNewFolder, R.string.fm_new_folder, Fm.canCreate(p)) { Fm.dlg = FmDlg.NewFolder() },
        FmAction(Icons.Filled.Description, R.string.fm_new_file, Fm.canCreate(p)) { Fm.dlg = FmDlg.NewFile() },
        FmAction(Icons.Filled.FolderZip, R.string.fm_new_zip, Fm.canCreateZip(p)) { Fm.dlg = FmDlg.NewZip() },
        FmAction(Icons.Filled.SelectAll, R.string.fm_select_all) { Fm.selectAll(p) },
        FmAction(Icons.Filled.Deselect, R.string.fm_select_none, sel) { p.selected = emptySet() },
        FmAction(Icons.Filled.FlipToBack, R.string.fm_select_invert) { Fm.invert(p) },
        FmAction(Icons.Filled.FilterAlt, R.string.fm_select_mask) { Fm.dlg = FmDlg.Mask() },
        FmAction(Icons.Filled.Sort, R.string.fm_sort) { Fm.dlg = FmDlg.View() },
        FmAction(Icons.Filled.SwapVert, R.string.sort_title) { Fm.active = p.id; Fm.dlg = FmDlg.Sort(p) },
        FmAction(Icons.Filled.Visibility, R.string.fm_hidden) { Fm.setHidden(!Fm.showHidden) },
        FmAction(Icons.Filled.Search, R.string.fm_search) { Fm.dlg = FmDlg.Search() },
        FmAction(Icons.Filled.Bookmarks, R.string.fm_bookmarks) { Fm.dlg = FmDlg.Bookmarks() },
        FmAction(Icons.Filled.History, R.string.fm_history) { Fm.dlg = FmDlg.History() },
        FmAction(if (Fm.treeMode) Icons.Filled.ViewList else Icons.Filled.AccountTree, if (Fm.treeMode) R.string.fm_mode_list else R.string.fm_mode_tree) { val v = !Fm.treeMode; Fm.setTree(v); FmRootCfg.set(Fm.sctx, "tree", v) },   // кнопка «Список/Дерево» переключает и обычные папки, и корень «Устройства» (пост wad72 №14, 10.11.2026)
        FmAction(Icons.Filled.Info, R.string.fm_props, true) { Fm.props(p, if (sel) Fm.targets(p) else listOf("")) },
        FmAction(Icons.Filled.Lock, R.string.perm_title, sel && (p.src == "local" || p.src == "adb")) { Fm.dlg = FmDlg.Perms(p, Fm.targets(p)) },
        FmAction(Icons.Filled.FolderZip, R.string.fm_make_archive, sel) { Fm.dlg = FmDlg.MakeArchive(p, Fm.targets(p)) },
        FmAction(Icons.Filled.SwapHoriz, R.string.fm_swap) { val a = Fm.panes[0].path; val b = Fm.panes[1].path; Fm.load(Fm.panes[0], b, remember = false); Fm.load(Fm.panes[1], a, remember = false) },
        FmAction(Icons.Filled.CompareArrows, R.string.fm_same) { Fm.load(o, p.path) },
        FmAction(Icons.Filled.SdStorage, R.string.fm_storage) { Fm.dlg = FmDlg.Storage() },
        FmAction(Icons.Filled.DeleteOutline, R.string.ft_menu) { Fm.active = p.id; Fm.dlg = FmDlg.FTrash() },
        FmAction(Icons.Filled.Dns, R.string.fm_source) { Fm.dlg = FmDlg.Source() },
        FmAction(Icons.Filled.MusicNote, R.string.fm_play) {
            // музыка выбранного файла (или первая в папке) во встроенном плеере, дальше по списку папки
            val list = Fm.shown(p).filter { !it.isDir && openCat(it.ext) == "audio" }
            val item = p.selected.firstOrNull()?.let { n -> list.firstOrNull { it.name == n } } ?: list.firstOrNull()
            if (item == null) Fm.say(Fm.str(R.string.fm_play_none)) else openBuiltin(sctx, p, item, "audio", FmIo.join(p.path, item.name))
        },
        FmAction(Icons.Filled.Wifi, R.string.fm_wifi_server, true, if (WifiShare.running) Color(0xFF2E9E4F) else Color(0xFFF57C00)) { WifiExplorer.toggleServer(sctx) },
    ).also { sctx.hashCode() }
}

@Composable
private fun FmActionButton(a: FmAction, modifier: Modifier = Modifier, big: Boolean = false) {
    val alpha = if (a.enabled) 1f else .38f
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.defaultMinSize(minWidth = 60.dp, minHeight = if (big) 92.dp else 48.dp).clip(RoundedCornerShape(if (big) 14.dp else 10.dp))
            .then(if (big && focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .background(if (hovered && a.enabled) MaterialTheme.colorScheme.primary.copy(alpha = .22f) else MaterialTheme.colorScheme.primary.copy(alpha = .10f))
            .hoverable(hover)
            .clickable(enabled = a.enabled) { a.run() }.padding(horizontal = 1.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(a.icon, null, Modifier.size(if (big) 40.dp else 22.dp), tint = (a.tint ?: MaterialTheme.colorScheme.primary).copy(alpha = alpha))
        Text(stringResource(a.label), fontSize = if (big) 17.sp else 10.sp, lineHeight = if (big) 20.sp else 12.sp, maxLines = if (big) 3 else 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
    }
}

/** Вкладки двух панелей (портрет): активная подсвечена, видно путь каждой; нажатие переключает сразу. */
@Composable
private fun FmTabs() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Fm.panes.forEach { p ->
            val on = Fm.active == p.id
            val name = Fm.paneLabel(p)
            Row(
                Modifier.weight(1f).dndTarget(p, p.path, 5, name).clip(RoundedCornerShape(8.dp))
                    .background(if (on) MaterialTheme.colorScheme.primary else if (dndHot(p, p.path, 5)) MaterialTheme.colorScheme.primary.copy(alpha = .35f) else MaterialTheme.colorScheme.primary.copy(alpha = .10f))
                    .clickable { Fm.active = p.id }.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val fg = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                val pc = p.net
                if (p.src == "smb" && !p.root && pc != null) NetBrandIcon(pc, if (Marq.tv) 26.dp else 20.dp, tint = fg) else Icon(Icons.Filled.Folder, null, Modifier.size(16.dp), tint = fg)
                Column(Modifier.padding(start = 6.dp)) {
                    Text(stringResource(if (p.id == 0) R.string.fm_pane_left else R.string.fm_pane_right), fontSize = 9.sp, lineHeight = 10.sp, color = fg.copy(alpha = .8f), maxLines = 1)
                    MqText(name, active = on, fontSize = 13.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, color = fg)
                }
            }
        }
    }
}

/** Строка в одну строку: многоточие, а для активной (под пальцем, мышью, фокусом, выделенной) бегущая строка с паузами. */
@Composable
fun MqText(text: String, active: Boolean, modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
           lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified, fontWeight: FontWeight? = null,
           color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified, fontFamily: FontFamily? = null, wrap: Boolean = false) {
    androidx.compose.runtime.CompositionLocalProvider(LocalRowActive provides (active || LocalRowActive.current), LocalMarqOnlyActive provides true) {
        // wrap: перенос на несколько строк строками списков больше не используется (7.5: при «Вкл» одна строка и бегущая строка, при «Выкл» одна строка с многоточием)
        Text(text, modifier, fontSize = fontSize, lineHeight = lineHeight, fontWeight = fontWeight, color = color, fontFamily = fontFamily, maxLines = if (wrap) 4 else 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Главные кнопки узкой колонки (остальные в «Ещё»). */
private val fmMainLabels get() = setOf(R.string.fm_up, R.string.fm_b_copy, R.string.fm_b_move, R.string.fm_delete, R.string.fm_b_rename,
    R.string.fm_new_folder, R.string.fm_new_file, R.string.fm_select_all, R.string.fm_sort, R.string.fm_search, R.string.fm_storage) +
    // на приставке экран высокий и колонка внизу пустовала: добавляем ещё две частые кнопки (семь рядов помещаются без прокрутки)
    (if (Marq.tv) setOf(R.string.fm_refresh, R.string.fm_new_zip, R.string.fm_play, R.string.fm_wifi_server, R.string.fm_select_none, R.string.fm_select_invert) else emptySet())

/** Телефон (не ТВ) в ландшафте: места мало, поэтому всё уплотняется. */
@Composable
fun rememberLandPhone(): Boolean {
    val c = androidx.compose.ui.platform.LocalConfiguration.current
    return !Marq.tv && c.screenWidthDp > c.screenHeightDp
}

/** Телефон в ландшафте: все действия одним столбцом (прокручивается), сверху имя активной панели и числа. */
@Composable
private fun FmSideColumn(actions: List<FmAction>, modifier: Modifier) {
    val p = Fm.cur
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(if (Fm.active == 0) R.string.fm_pane_left else R.string.fm_pane_right) + "\n" + Fm.shown(p).size + "·" + p.selected.size,
            fontSize = 9.sp, lineHeight = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberSavedScroll("files.side")), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val ks = actions.map { FmButtons.key(androidx.compose.ui.platform.LocalContext.current, it.label) }
            actions.forEach { a -> androidx.compose.runtime.key(FmButtons.key(androidx.compose.ui.platform.LocalContext.current, a.label)) { FmSquareButton(a, 44.dp, 21.dp, 8.sp, reorderKeys = ks) } }
        }
    }
}

/** Квадратная кнопка действия: значок по центру, подпись под ним (до двух строк, узкий шрифт) внутри квадрата. На ТВ у фокуса рамка. */
@Composable
private fun FmSquareButton(a: FmAction, size: androidx.compose.ui.unit.Dp, icon: androidx.compose.ui.unit.Dp, label: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier, reorderKeys: List<String>? = null, cols: Int = 1) {
    val alpha = if (a.enabled) 1f else .38f
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val sh = RoundedCornerShape(10.dp)
    val bctx = androidx.compose.ui.platform.LocalContext.current
    val reorderFocus = remember { FocusRequester() }
    val bkey = FmButtons.key(bctx, a.label)
    val moving = FmButtons.reorder == bkey
    val reorderOn = FmButtons.reorder != null
    val canReorder = reorderKeys != null && bkey in reorderKeys
    val hint = stringResource(R.string.sb_reorder_hint)
    // 11.11.2026: после каждого сдвига кнопка меняет место в списке, фокус при этом теряется и следующие стрелки пульта не доходят: возвращаем фокус переставляемой кнопке
    LaunchedEffect(moving, FmButtons.order) { if (moving) { kotlinx.coroutines.delay(60); runCatching { reorderFocus.requestFocus() } } }
    Column(
        modifier.size(size).clip(sh)
            .background(if (moving) MaterialTheme.colorScheme.primary.copy(alpha = .38f) else if (hovered && a.enabled) MaterialTheme.colorScheme.primary.copy(alpha = .22f) else MaterialTheme.colorScheme.primary.copy(alpha = .10f))
            .then(if (moving) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, sh) else if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, sh) else Modifier)
            .focusRequester(reorderFocus)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                val nk = e.nativeKeyEvent
                val ok = nk.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || nk.keyCode == android.view.KeyEvent.KEYCODE_ENTER || nk.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                if (!canReorder) return@onPreviewKeyEvent false
                if (FmButtons.reorder == bkey) {
                    // режим перестановки с пульта: стрелки двигают кнопку, ОК подтверждает
                    if (nk.action == android.view.KeyEvent.ACTION_DOWN) when (nk.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> { FmButtons.move(bctx, bkey, reorderKeys!!, -cols); return@onPreviewKeyEvent true }
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { FmButtons.move(bctx, bkey, reorderKeys!!, cols); return@onPreviewKeyEvent true }
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { if (cols > 1) FmButtons.move(bctx, bkey, reorderKeys!!, -1); return@onPreviewKeyEvent true }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { if (cols > 1) FmButtons.move(bctx, bkey, reorderKeys!!, 1); return@onPreviewKeyEvent true }
                    }
                    if (ok) { if (nk.action == android.view.KeyEvent.ACTION_UP && System.currentTimeMillis() - FmButtons.startedAt > 700) FmButtons.finishReorder(); return@onPreviewKeyEvent true }
                    false
                } else if (ok && FmButtons.reorder == null && nk.action == android.view.KeyEvent.ACTION_DOWN && nk.repeatCount >= 1) {
                    FmButtons.startReorder(bkey); Fm.say(hint); true      // удержание ОК на кнопке
                } else false
            }
            .hoverable(hover).combinedClickable(enabled = true, onClick = {
                if (FmButtons.reorder != null && reorderKeys != null) {
                    if (FmButtons.reorder == bkey) { if (System.currentTimeMillis() - FmButtons.startedAt > 700) FmButtons.finishReorder() }
                    else if (bkey in reorderKeys) { FmButtons.swap(bctx, FmButtons.reorder!!, bkey); FmButtons.finishReorder() }
                } else if (a.enabled) a.run()
            }, onLongClick = { if (canReorder && FmButtons.reorder == null) { FmButtons.startReorder(bkey); Fm.say(hint) } }).padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        val cs = CustomIcons.spec("s:" + FmButtons.key(androidx.compose.ui.platform.LocalContext.current, a.label))
        val tintC = (a.tint ?: MaterialTheme.colorScheme.primary).copy(alpha = alpha)
        if (cs != null) CustomIconGlyph(cs, icon, tintC) else Icon(a.icon, null, Modifier.size(icon), tint = tintC)
        // 10.11.2026: длинное слово («Сортировка», «Инверсия») не должно рваться посреди слова: шрифт слегка уменьшается так, чтобы слово влезло (9 букв при 7,5 sp в кнопке 44 dp влезают)
        val txt = stringResource(a.label)
        // подпись всегда по центру и без разрыва слова: шрифт подбирается по самому длинному слову (измерение, а не оценка по числу букв)
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val dens = androidx.compose.ui.platform.LocalDensity.current
        val availPx = with(dens) { (size - 8.dp).toPx() }
        val words = txt.split(' ').filter { it.isNotEmpty() }
        var fs = label.value
        val minFs = label.value * 0.5f
        while (fs > minFs && words.any { w -> measurer.measure(androidx.compose.ui.text.AnnotatedString(w), androidx.compose.material3.LocalTextStyle.current.copy(fontSize = fs.sp, fontFamily = CondFont)).size.width > availPx }) fs -= 0.25f
        val lblSize = fs.sp
        Text(txt, Modifier.fillMaxWidth(), fontSize = lblSize, lineHeight = lblSize * 1.12f, maxLines = 2, overflow = TextOverflow.Clip, fontFamily = CondFont,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
    }
}

/** Кнопка только со значком (цель касания 40 dp); название по долгому нажатию. */
@Composable
private fun FmActionIcon(a: FmAction, modifier: Modifier = Modifier) {
    val alpha = if (a.enabled) 1f else .38f
    val name = stringResource(a.label)
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .10f))
            .combinedClickable(enabled = true, onClick = { if (a.enabled) a.run() }, onLongClick = { Fm.say(name) })
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        val cs = CustomIcons.spec("s:" + FmButtons.key(androidx.compose.ui.platform.LocalContext.current, a.label))
        val tintC = (a.tint ?: MaterialTheme.colorScheme.primary).copy(alpha = alpha)
        if (cs != null) CustomIconGlyph(cs, 22.dp, tintC) else Icon(a.icon, null, Modifier.size(22.dp), tint = tintC)
    }
}

/** Узкая колонка действий в ландшафте: тянется на всю высоту, крупные кнопки в три столбца поровну по высоте, остальное в «Ещё». */
@Composable
private fun FmNarrow(actions: List<FmAction>, modifier: Modifier, tvSize: androidx.compose.ui.unit.Dp? = null) {
    val p = Fm.cur
    val main = actions.filter { it.label in fmMainLabels }
    val rest = actions.filter { it.label !in fmMainLabels }
    val items = main + FmAction(Icons.Filled.MoreHoriz, R.string.fm_more) { Fm.dlg = FmDlg.More(rest) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(stringResource(if (Fm.active == 0) R.string.fm_pane_left else R.string.fm_pane_right) + "  ·  " + stringResource(R.string.fm_count, Fm.shown(p).size, p.selected.size),
            fontSize = 11.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberSavedScroll("files.narrow")), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.chunked(2).forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { r.forEach { a -> androidx.compose.runtime.key(FmButtons.key(androidx.compose.ui.platform.LocalContext.current, a.label)) { val rk = if (a.label == R.string.fm_more) null else (main + rest).map { FmButtons.key(androidx.compose.ui.platform.LocalContext.current, it.label) }; if (tvSize != null) FmSquareButton(a, tvSize, tvSize * 0.46f, (tvSize.value * 0.165f).coerceIn(9f, 12f).sp, reorderKeys = rk, cols = 2) else if (Marq.tv) FmSquareButton(a, 72.dp, 34.dp, 11.5.sp, reorderKeys = rk, cols = 2) else FmSquareButton(a, 78.dp, 37.dp, 12.sp, reorderKeys = rk, cols = 2) } } }
            }
        }
    }
}

@Composable
private fun FmChip(text: String, click: () -> Unit) {
    Text(text, Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer).clickable { click() }.padding(horizontal = 8.dp, vertical = 2.dp),
        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSecondaryContainer)
}

/** Портрет: узкая полоса действий (справа или слева по настройке «Панели управления») и активная панель. */
@Composable
private fun FmStrip(actions: List<FmAction>, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberSavedScroll("files.strip")), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 10.11.2026 (владелица): «Корзина файлов» и «История» убраны из полосы: обе есть в «домике» (корзина и «Недавние файлы»); «Обмен» и «Как здесь» тоже убраны («не нужны»)
        actions.filter { it.label != R.string.ft_menu && it.label != R.string.fm_history && it.label != R.string.fm_swap && it.label != R.string.fm_same }.let { vis -> val ks = vis.map { FmButtons.key(androidx.compose.ui.platform.LocalContext.current, it.label) }; vis.forEach { a -> androidx.compose.runtime.key(FmButtons.key(androidx.compose.ui.platform.LocalContext.current, a.label)) { FmSquareButton(a, FmMetrics.BTN_W, 21.dp, 7.5.sp, reorderKeys = ks) } } }
    }
}

@Composable
private fun FmPaneView(p: FmPane, modifier: Modifier, swipe: Boolean = false, volsRow: Boolean = true) {
    val active = Fm.active == p.id
    val prim = MaterialTheme.colorScheme.primary
    val state = rememberLazyListState()
    // место (источник, папка, вид, элемент с фокусом) запоминается через полсекунды после каждого заметного изменения: не только при выходе
    val saveCtx = LocalContext.current
    LaunchedEffect(p.id) {
        androidx.compose.runtime.snapshotFlow { listOf<Any?>(p.src, p.path, p.lastName, p.cloudView, p.root, p.parked != null, p.net, p.loading, p.status) }.collectLatest {
            delay(500)
            if (p.root || p.parked != null || p.loading || p.status != null) return@collectLatest
            val key = if (p.src == "smb") p.net?.takeIf { p.smb !is ArchiveFs }?.let { "net:" + it.kind + "|" + it.host + "|" + it.user } else if (p.src == "local") "local" else null
            if (key != null) Startup.saveFocus(saveCtx, p.id, Startup.Focus(key, p.path, if (p.src == "smb") p.cloudView else "drive", p.lastName.orEmpty()))
        }
    }
    // сортировка по группам; большие папки (тысячи записей) сортируются в фоне, малые сразу
    val tick = Fm.sortTick
    val cfgNow = Fm.cfgFor(p, p.path)
    val hiddenNow = Fm.showHidden
    val src = p.items
    val items: List<FmItem> = if (src.size <= 3000) remember(src, p.path, cfgNow, hiddenNow, tick, p.kids.size) { Fm.shown(p) } else {
        val res by produceState<Pair<List<FmItem>, List<FmItem>>>(emptyList<FmItem>() to emptyList<FmItem>(), src, p.path, cfgNow, hiddenNow, tick) {
            val sorted = withContext(Dispatchers.Default) { Fm.shown(p) }
            value = src to sorted
        }
        if (res.first === src) res.second else emptyList()
    }
    // теги музыки, видео и фото, число элементов папок: дочитываются в фоне, потом список один раз пересортировывается
    LaunchedEffect(src, p.path, cfgNow, p.kids.size, p.expanded.size) { SortMeta.ensure(p) }
    val nodes = if (Fm.treeMode) flattenTree(p, items) else null
    val tctx = LocalContext.current
    val cloudTop = Fm.cloudRoot(p)
    val view = if (cloudTop) p.cloudView else "drive"
    // «Корзина» облака в панели: список удалённого грузится при входе и после каждого изменения корзины
    LaunchedEffect(view, CloudTrash.ver, p.net?.let { NetTrash.rid(it) }) {
        val c = p.net
        if (view == "trash" && c != null) {
            p.trashLoading = true; p.trashErr = ""
            try {
                val rid = NetTrash.rid(c)
                if (c.kind != "cloud") NetTrash.ensure(tctx, c)
                val l = if (CloudTrash.mode(CloudTrash.typeFor(tctx, c)) == CloudTrash.OWN) CloudTrash.listOwn(tctx, rid) else CloudTrash.Listing(CloudTrash.list(tctx, rid), -1L)
                CloudTrash.sums[rid] = CloudTrash.sumOf(rid, l.items, if (l.total >= 0) l.total else l.items.sumOf { maxOf(it.size, 0L) })
                p.trashItems.clear(); p.trashItems.addAll(l.items)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { p.trashErr = e.message ?: e.javaClass.simpleName }
            p.trashLoading = false
        }
    }
    val rows: List<PRow> = when (view) {
        "top" -> listOf<PRow>(TrashRow(), DriveRow())
        "trash" -> if (p.trashItems.isEmpty()) listOf<PRow>(MsgRow(if (p.trashLoading) tctx.getString(R.string.tr_counting) else p.trashErr.ifEmpty { tctx.getString(R.string.tr_empty) })) else p.trashItems.map<CloudTrash.TItem, PRow> { TItemRow(it) }
        else -> buildRows(p, items, nodes, cfgNow)
    }
    // список у самого верха: после дочитывания тегов он остаётся у верха (иначе строка, вставшая выше, оказалась бы за краем экрана)
    val topAtTick = remember(tick) { androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 } }
    LaunchedEffect(tick) { if (topAtTick) state.scrollToItem(0) }
    // после смены настроек сортировки список показывается с начала или с выбранного элемента (при дочитывании тегов положение не меняется)
    // положение списка запоминается для каждой папки каждой панели: при возврате в папку (и после перезапуска) список стоит там же
    val sctx = LocalContext.current
    val posReady = remember { booleanArrayOf(false) }
    val hasItems = p.items.isNotEmpty()
    LaunchedEffect(p.path, p.src, hasItems) {
        // пока содержимое не загружено, ничего не восстанавливается и не записывается (иначе перезапишется сохранённое положение)
        if (!hasItems) { posReady[0] = false; return@LaunchedEffect }
        val saved = ScrollMem.getFolder(sctx, p.id, p.src, p.path)
        state.scrollToItem(saved?.first ?: 0, saved?.second ?: 0)
        posReady[0] = true
    }
    LaunchedEffect(p.path, p.src) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }.collectLatest { (a, b) ->
            delay(400); if (posReady[0]) ScrollMem.putFolder(sctx, p.id, p.src, p.path, a, b)
        }
    }
    val seenPath = remember { arrayOf(p.path) }
    // смена настроек сортировки: список с начала или от выбранного элемента (при смене папки не вмешивается)
    LaunchedEffect(cfgNow) {
        if (seenPath[0] != p.path) { seenPath[0] = p.path; return@LaunchedEffect }
        val sel = p.selected.firstOrNull()
        val i = if (sel != null) rows.indexOfFirst { it is ItemRow && it.item.name == sel } else 0
        state.scrollToItem(maxOf(i, 0))
    }
    LaunchedEffect(p.scrollToName, rows) {
        val n = p.scrollToName
        if (n != null) {
            // в дереве раскрытые папки смещают строки, а блоки могут быть свёрнуты: индекс считается по строкам списка
            var i = rows.indexOfFirst { it is ItemRow && (it.node?.depth ?: 0) == 0 && it.item.name == n }
            if (i < 0) {
                val g = items.firstOrNull { it.name == n }?.let { cfgNow.groupOf(it.isDir, it.ext).ch.toString() }
                if (g != null && g in p.collapsed) { p.collapsed.remove(g); return@LaunchedEffect }
            }
            if (i >= 0) state.scrollToItem(i); p.selected = setOf(n); p.scrollToName = null
        }
    }
    Column(
        modifier.then(if (p.root) Modifier else Modifier.dndTarget(p, p.path, 0)).clip(RoundedCornerShape(10.dp)).border(if (active || dndHot(p, p.path, 0)) 2.dp else 1.dp,
            if (dndHot(p, p.path, 0)) MaterialTheme.colorScheme.tertiary else if (active) prim else MaterialTheme.colorScheme.outline.copy(alpha = .5f), RoundedCornerShape(10.dp))
            .pointerInput(p.id) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); Fm.active = p.id } }
            .onFocusChanged { if (it.hasFocus) Fm.active = p.id },
    ) {
        val landPhone = rememberLandPhone()
        FmCrumbs(p, landPhone)
        HorizontalDivider()
        if (p.root) FmRootList(p, Modifier.weight(1f).fillMaxWidth()) else Column(Modifier.weight(1f).fillMaxWidth()) {
        p.status?.let { st -> Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(st, Modifier.padding(start = 8.dp), fontSize = 12.sp) } }
        p.parked?.let { pk -> FmParkedBody(p, pk, Modifier.weight(1f).fillMaxWidth()) }
        p.fix?.let { fx -> Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button({ Fm.fixAction(p, fx) }) { Text(stringResource(if (fx == "wifi") R.string.fm_fix_wifi_btn else R.string.fm_fix_pair_btn), fontSize = 13.sp) } } }
        if (p.src == "local" && !Fm.granted && p.parked == null) FmAccessBanner(Modifier.weight(1f))
        if (p.loading && p.items.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        p.error?.let { e ->
            Text(e, Modifier.padding(8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            val nc = p.net
            if (nc != null && nc.kind == "cloud" && CloudErr.offersPublish(LocalContext.current, e)) {
                val cctx = LocalContext.current
                androidx.compose.material3.OutlinedButton({ Fm.dlg = FmDlg.OwnKey(CloudIcons.typeOf(cctx, nc) ?: "drive", OwnKeyWizard.PUBLISH_STEP) }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.cl_publish_btn)) }
            } else if (nc != null && nc.kind == "cloud" && CloudErr.offersOwnKey(LocalContext.current, e)) {
                val cctx = LocalContext.current
                androidx.compose.material3.OutlinedButton({ Fm.dlg = FmDlg.OwnKey(CloudIcons.typeOf(cctx, nc) ?: "drive") }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.ok_connect_own)) }
            }
        }
        if (view == "drive" && items.isEmpty() && p.error == null && !p.loading && p.parked == null) Text(stringResource(R.string.fm_empty), Modifier.padding(12.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        var drag by remember { mutableStateOf(0f) }
        val swipeMod = if (swipe) Modifier.pointerInput(Unit) {
            // свайп влево/вправо по списку переключает панель
            detectHorizontalDragGestures(
                onDragStart = { drag = 0f },
                onDragEnd = { if (drag < -110f) Fm.active = 1 else if (drag > 110f) Fm.active = 0; drag = 0f },
                onDragCancel = { drag = 0f },
            ) { _, dx -> drag += dx }
        } else Modifier
        // режим «Дерево»: раскрытые папки подгружаются при раскрытии
        if (Fm.treeMode) LaunchedEffect(p.path, p.expanded.toList(), p.items) {
            for (e in p.expanded.toList()) if (!p.kids.containsKey(e) && (e.startsWith(p.path.trimEnd('/') + "/"))) Fm.loadKids(p, e)
        }
        p.tagNote?.let { Text(it, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), maxLines = 1) }
        val emptyKey = "\u0000empty"
        if (p.menuFor == emptyKey) LDropdownMenu(true, { p.menuFor = null }) { CreateMenu(p, p.path) { p.menuFor = null } }
        if (p.parked == null && !(p.src == "local" && !Fm.granted)) Box(Modifier.weight(1f).fillMaxWidth()) { LazyColumn(Modifier.fillMaxSize().then(swipeMod).combinedClickable(onClick = {}, onLongClick = { Fm.active = p.id; p.menuFor = emptyKey }).onGloballyPositioned { FmDnd.lists[p.id] = DndList(state, it.boundsInRoot()) }.verticalScrollbar(state), state = state, contentPadding = sbPadding(), verticalArrangement = Arrangement.spacedBy(if (Marq.tv) 3.dp else 0.dp)) {
            items(rows, key = { it.key }) { r ->
                when (r) {
                    is HeadRow -> FmGroupHeader(r.g, r.count, r.closed) { val c = r.g.ch.toString(); if (c in p.collapsed) p.collapsed.remove(c) else p.collapsed.add(c) }
                    is ItemRow -> FmRow(p, r.item, r.node)
                    is TrashRow -> FmTrashRow(p)
                    is DriveRow -> FmDriveRow(p)
                    is MsgRow -> Text(r.text, Modifier.padding(12.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
                    is TItemRow -> FmTrashItemRow(p, r.ti)
                }
            }
        }
        ScrollbarHandle(state, Modifier.align(if (scrollbarLeft()) Alignment.CenterStart else Alignment.CenterEnd))
        }
        if (view == "trash" && FmRootCfg.sel.isNotEmpty()) RootSelBar(p)
        if (p.selected.isNotEmpty()) {
            Text(stringResource(R.string.fm_selected, p.selected.size), Modifier.fillMaxWidth().background(prim.copy(alpha = .12f)).padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 11.sp)
        }
        }
    }
}

/** Нет доступа ко всем файлам: одно нажатие открывает системный экран именно для этого приложения. */
@Composable
private fun FmAccessBanner(modifier: Modifier) {
    val ctx = LocalContext.current
    Column(modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.fm_perm_text), fontSize = 13.sp)
        androidx.compose.runtime.LaunchedEffect(Unit) { Setup.need(ctx, "files") }
        Button({ Fm.askAccess(ctx) }) { Text(stringResource(R.string.fm_perm_grant)) }
    }
}

/**
 * Путь цепочкой: только хлебные крошки, без кнопок (нажатие на элемент ведёт в эту папку). Первый значок ведёт на корневой экран.
 * Кнопки «вверх», «скрытые» и «сортировка» есть в боковой панели. Шрифт как у названий папок в списке; тонкая строка за счёт малых отступов.
 */
/** Облако или сеть, не пересканированные при запуске: сохранённое место, серая подпись и меню «Переподключиться» по долгому нажатию (удержание ОК на пульте). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FmParkedBody(p: FmPane, pk: FmParked, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier.combinedClickable(onClick = { Fm.active = p.id }, onLongClick = { Fm.active = p.id; menu = true })) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NetBrandIcon(pk.conn, if (Marq.tv) 36.dp else 28.dp)
                Text(pk.conn.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = UiPrefs.listFont, maxLines = 1)
            }
            if (pk.path.isNotBlank()) Text(pk.path, fontSize = 12.sp, fontFamily = UiPrefs.listFont, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f))
            if (pk.name.isNotBlank()) Text(pk.name, fontSize = 12.sp, fontFamily = UiPrefs.listFont, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f), maxLines = 1)
            if (p.status == null) Text(stringResource(R.string.rt_parked), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            p.parkedErr?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        }
        LDropdownMenu(menu, { menu = false }) { DropdownMenuItem({ Text(stringResource(R.string.rt_reconnect), fontSize = 14.sp) }, { menu = false; Fm.reconnect(p) }) }
    }
}

@Composable
private fun FmCrumbs(p: FmPane, compact: Boolean = false) {
    val scroll = rememberScrollState()
    LaunchedEffect(p.path, p.root) { scroll.scrollTo(scroll.maxValue) }
    val prim = MaterialTheme.colorScheme.primary
    val fs = if (Fm.dense) 15.sp else 14.sp
    val fw = if (Fm.dense) FontWeight.Medium else FontWeight.Normal
    // 9.0: полоса выше, «домик» и «обновить» крупнее (размер строится от значка «домика», а не от текста), всё растёт вместе с «Размером элементов и значков»
    val h = if (Marq.tv) 48.dp else 40.dp
    Row(Modifier.fillMaxWidth().height(h).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxHeight().widthIn(min = 46.dp).clickable { Fm.active = p.id; fmShowRoot(p) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Home, stringResource(R.string.fm_root_title), Modifier.size(if (Marq.tv) 30.dp else 26.dp), tint = if (p.root) prim else MaterialTheme.colorScheme.onSurface.copy(alpha = .75f))
        }
        p.net?.takeIf { p.src == "smb" && !p.root }?.let { NetBrandIcon(it, if (Marq.tv) 24.dp else 20.dp, Modifier.padding(end = 4.dp)) }
        val pkc = p.parked?.takeIf { !p.root }
        pkc?.let { NetBrandIcon(it.conn, if (Marq.tv) 24.dp else 20.dp, Modifier.padding(end = 4.dp)) }
        if (p.src != "local" && !p.root) Text((if (p.src == "smb" && p.net?.kind == "cloud") (p.net?.let { CloudIcons.serviceCaption(LocalContext.current, it) } ?: "cloud") else if (p.src == "smb") p.smb?.label?.substringBefore("://") ?: "smb" else p.src).uppercase(), Modifier.padding(end = 4.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 5.dp, vertical = 0.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
        (p.smb as? KeepFs)?.takeIf { p.src == "smb" && !p.root }?.let { NetDot(it) }
        // заголовок корня: статичный, по центру всей ширины справа от кнопки «дом»; обычный Text без бегущей строки и без реакции на нажатие и фокус
        if (p.root) androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Text(stringResource(R.string.fm_root_title), Modifier.fillMaxWidth(), fontSize = fs, fontWeight = fw, fontFamily = UiPrefs.listFont, color = prim, maxLines = 1, softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        else if (pkc != null) Row(Modifier.weight(1f).horizontalScroll(scroll)) { Text(pkc.path.ifBlank { pkc.conn.title }, fontFamily = UiPrefs.listFont, fontSize = fs, fontWeight = fw, maxLines = 1) }
        else if (p.src == "smb") Row(Modifier.weight(1f).horizontalScroll(scroll)) { Text(p.path, fontFamily = UiPrefs.listFont, fontSize = fs, fontWeight = fw, maxLines = 1) }
        else Row(Modifier.weight(1f).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
            val parts = p.path.trim('/').split('/').filter { it.isNotEmpty() }
            val hot = MaterialTheme.colorScheme.primary.copy(alpha = .3f)
            Text("/", Modifier.clickable { Fm.load(p, "/") }.dndTarget(p, "/", 2).background(if (dndHot(p, "/", 2)) hot else androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 4.dp, vertical = 2.dp),
                fontFamily = UiPrefs.listFont, fontSize = fs, fontWeight = fw, color = prim, lineHeight = fs * 1.05f)
            parts.forEachIndexed { i, s ->
                val to = "/" + parts.take(i + 1).joinToString("/")
                Text(s, Modifier.clickable { Fm.load(p, to) }.dndTarget(p, to, 2, s).background(if (dndHot(p, to, 2)) hot else androidx.compose.ui.graphics.Color.Transparent).padding(horizontal = 3.dp, vertical = 2.dp),
                    fontFamily = UiPrefs.listFont, fontSize = fs, fontWeight = fw, lineHeight = fs * 1.05f, maxLines = 1,
                    color = if (i == parts.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .8f))
                if (i < parts.lastIndex) Text("›", fontFamily = UiPrefs.listFont, fontSize = fs, fontWeight = fw, lineHeight = fs * 1.05f, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
            }
        }
        // 10.11.2026 (владелица: «надо кнопка обновить, чтобы можно было и самому обновлять»): на экране «Устройства» справа в заголовке кнопка «Обновить» (перечитывает всё раскрытое: диски, облака, корзины)
        if (p.root) androidx.compose.foundation.layout.Box(Modifier.fillMaxHeight().widthIn(min = 46.dp).clickable { Fm.active = p.id; FmRootCfg.refreshAll(Fm.sctx) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Refresh, stringResource(R.string.fm_refresh), Modifier.size(if (Marq.tv) 28.dp else 24.dp), tint = prim)
        }
    }
}

/** Строка списка: заголовок группы (режим «Группировать по типу») или запись. */
private sealed interface PRow { val key: String }
private class HeadRow(val g: SGroup, val count: Int, val closed: Boolean) : PRow { override val key = "h:" + g.ch }
private class TrashRow : PRow { override val key = "TRASHROW" }
private class DriveRow : PRow { override val key = "DRIVEROW" }
private class MsgRow(val text: String) : PRow { override val key = "MSGROW" }
private class TItemRow(val ti: CloudTrash.TItem) : PRow { override val key = "Ti:" + ti.id }
private class ItemRow(val item: FmItem, val node: TNode?) : PRow { override val key = node?.path ?: item.name }

private fun buildRows(p: FmPane, items: List<FmItem>, nodes: List<TNode>?, cfg: SortCfg): List<PRow> {
    val base: List<PRow> = nodes?.map { ItemRow(it.item, it) } ?: items.map { ItemRow(it, null) }
    if (!cfg.grouped) return base
    val counts = HashMap<SGroup, Int>()
    for (r in base) if (r is ItemRow && (r.node?.depth ?: 0) == 0) counts.merge(cfg.groupOf(r.item.isDir, r.item.ext), 1, Int::plus)
    val out = ArrayList<PRow>(base.size + 6)
    var cur: SGroup? = null; var skip = false
    for (r in base) {
        r as ItemRow
        if ((r.node?.depth ?: 0) == 0) {
            val g = cfg.groupOf(r.item.isDir, r.item.ext)
            if (g != cur) { cur = g; out.add(HeadRow(g, counts[g] ?: 0, g.ch.toString() in p.collapsed)) }
            skip = g.ch.toString() in p.collapsed
        }
        if (!skip) out.add(r)
    }
    return out
}

/** Описание для служб доступности: у значка без подписи название читается вслух. */
private fun Modifier.androidx_semantics(label: String): Modifier = this.semantics { contentDescription = label }

fun folderKey(p: FmPane, full: String): String = (if (p.src == "local") "" else p.src + ":") + full

/** Строка плоского списка дерева: элемент, полный путь, уровень и признаки «последний среди соседей» по цепочке предков (для линий ветвей). */
private class TNode(val item: FmItem, val path: String, val depth: Int, val last: List<Boolean>)

private fun flattenTree(p: FmPane, top: List<FmItem>): List<TNode> {
    val out = ArrayList<TNode>()
    fun walk(dir: String, list: List<FmItem>, depth: Int, anc: List<Boolean>) {
        list.forEachIndexed { i, f ->
            val path = FmIo.join(dir, f.name)
            val chain = anc + (i == list.lastIndex)
            out.add(TNode(f, path, depth, chain))
            if (f.isDir && path in p.expanded && depth < 12) p.kids[path]?.let { k -> walk(path, Fm.sortList(p, path, if (Fm.showHidden) k else k.filter { !it.hidden }), depth + 1, chain) }
        }
    }
    walk(p.path, top, 0, listOf(true))
    return out
}

/** Первая строка корня облака: «Корзина» (не файл) с числом и размером; нажатие открывает окно корзины. */
@Composable
private fun FmTrashRow(p: FmPane) {
    val c = p.net ?: return
    val ctx = LocalContext.current
    val rid = NetTrash.rid(c)
    val sm = CloudTrash.sums[rid]
    LaunchedEffect(rid, sm == null) { if (sm == null) { if (c.kind != "cloud") NetTrash.ensure(ctx, c); CloudTrash.refreshSum(ctx, rid) } }
    val tv = Marq.tv
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (tv) 54.dp else if (Fm.dense) 0.dp else 46.dp).combinedClickable(onClick = { Fm.active = p.id; p.selected = emptySet(); p.cloudView = "trash" }, onLongClick = { Fm.active = p.id; Fm.dlg = FmDlg.Trash(c) }).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
        Icon(Icons.Filled.DeleteOutline, null, Modifier.size(32.dp), tint = androidx.compose.ui.graphics.Color(0xFFEF5350))
        Text(stringResource(R.string.tr_row), Modifier.weight(1f), fontSize = 18.sp, fontFamily = UiPrefs.listFont, maxLines = 1)
        Text(CloudTrash.sumText(sm), fontSize = 13.sp, maxLines = 1, fontFamily = UiPrefs.listFont, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
    }
}

/** «Облачный диск»: обычная жёлтая папка, открывает корень облака. */
@Composable
private fun FmDriveRow(p: FmPane) {
    val tv = Marq.tv
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (tv) 54.dp else if (Fm.dense) 0.dp else 46.dp).clickable { Fm.active = p.id; p.selected = emptySet(); p.cloudView = "drive" }.padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
        val drvKey = p.net?.let { c0 -> val tid0 = "N|" + c0.kind + "|" + c0.host + "|" + c0.user + "|"; "d:t:" + (if (c0.kind == "cloud") tid0 + "/" else tid0 + NetTrash.ROOT_MARK) + ":drv" }
        val drvSpec = if (drvKey != null) CustomIcons.spec(drvKey) else null
        if (drvSpec != null) CustomIconView(drvSpec, 32.dp) else FolderIcon(UiPrefs.folderDefault, false, 32.dp)
        Text(stringResource(if (p.net?.kind == "cloud") R.string.tr_drive else R.string.nt_drive), Modifier.weight(1f), fontSize = 18.sp, fontFamily = UiPrefs.listFont, maxLines = 1)
    }
}

/** Элемент корзины облака в панели: квадратик выбора (групповые «Восстановить» и «Удалить навсегда» в нижней панели значков). */
@Composable
private fun FmTrashItemRow(p: FmPane, ti: CloudTrash.TItem) {
    val c = p.net ?: return
    val tv = Marq.tv
    val rid = NetTrash.rid(c)
    val key = "Ti|" + rid + "|" + ti.id
    val dateTxt = if (ti.deletedAt > 0) java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ti.deletedAt)) else ""
    val dirTxt = "/" + ti.origPath.trim('/').substringBeforeLast('/', "")
    val sel = FmRootCfg.sel.containsKey(key)
    val toggle = { Fm.active = p.id; FmRootCfg.toggleSel(FmRootCfg.SelE(key, c, "T|" + rid, "", ti.name, ti.origPath, ti.isDir, ti)) }
    Row(
        Modifier.fillMaxWidth().background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else androidx.compose.ui.graphics.Color.Transparent).heightIn(min = if (tv) 54.dp else 46.dp).clickable { toggle() }.padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (ti.isDir) FolderIcon(UiPrefs.folderDefault, false, 30.dp, customKey = "f:" + ti.origPath) else FileTypeIcon(ti.name, ti.name.substringAfterLast('.', "").lowercase(), null, 30.dp)
        Column(Modifier.weight(1f)) {
            Text(ti.name, fontSize = 15.sp, fontFamily = UiPrefs.listFont, maxLines = 1)
            Text(listOf(dirTxt, dateTxt).filter { it.isNotEmpty() }.joinToString("  ·  "), fontSize = 11.sp, fontFamily = UiPrefs.listFont, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
        }
        if (ti.size >= 0 && !ti.isDir) Text(fmSize(ti.size), fontSize = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
        SmallCheck(sel, tv) { toggle() }
    }
}

@Composable
private fun FmRow(p: FmPane, f: FmItem, node: TNode? = null) {
    val dense = Fm.dense
    val depth = node?.depth ?: 0
    val treeOn = node != null
    val nodePath = node?.path ?: ""
    val isOpen = treeOn && f.isDir && nodePath in p.expanded
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .28f)
    val menuKey = if (depth == 0) f.name else nodePath
    val tvRow = Marq.tv
    val sepColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .14f)
    val rc = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val longKey = remember { booleanArrayOf(false) }
    val rowClick: () -> Unit = {
        Fm.active = p.id
        if (depth == 0) p.lastName = f.name
        if (longKey[0]) longKey[0] = false
        else if (p.selected.isNotEmpty() && depth > 0) Fm.toggleSel(p, nodePath.removePrefix(p.path.trimEnd('/') + "/"))
        else if (depth > 0) {
            // вложенная строка: папка раскрывается (как у верхнего уровня) или открывается по настройке, файл выделяется в своей папке
            if (f.isDir) { if (Fm.treeMode && Fm.dirTapExpand) Fm.toggleExpand(p, nodePath) else Fm.load(p, nodePath) } else Fm.load(p, nodePath.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }, pick = f.name)
        } else if (p.selected.isNotEmpty()) Fm.toggleSel(p, f.name) else if (f.isDir) { if (Fm.treeMode && Fm.dirTapExpand) Fm.toggleExpand(p, FmIo.join(p.path, f.name)) else Fm.open(p, f.name) } else Fm.openFile(p, f)
    }
    // двойное нажатие на папку в дереве входит в неё (делает корнем панели)
    val enterDir: (() -> Unit)? = if (f.isDir && Fm.treeMode && Fm.dirTapExpand) { { Fm.active = p.id; Fm.load(p, FmIo.join(p.path, f.name).takeIf { depth == 0 } ?: nodePath) } } else null
    val full = node?.path ?: FmIo.join(p.path, f.name)
    val selKey = if (depth > 0) nodePath.removePrefix(p.path.trimEnd('/') + "/") else f.name
    val sel = selKey in p.selected
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val pressed by hover.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val prim = MaterialTheme.colorScheme.primary
    androidx.compose.runtime.CompositionLocalProvider(LocalRowActive provides (sel || hovered || pressed || focused)) {
    Box {
        Row(
            Modifier.fillMaxWidth().then(if (f.isDir) Modifier.dndTarget(p, full, 1, f.name) else Modifier)
                .onGloballyPositioned { rc[0] = it }
                .then(if (f.hidden) Modifier.graphicsLayer { alpha = 0.6f } else Modifier).heightIn(min = if (tvRow) 54.dp else if (dense) 0.dp else 46.dp)
                .background(if (f.isDir && dndHot(p, full, 1)) prim.copy(alpha = .35f) else if (sel) prim.copy(alpha = .20f) else if (hovered) prim.copy(alpha = .08f) else androidx.compose.ui.graphics.Color.Transparent)
                .hoverable(hover)
                .then(if (treeOn && depth > 0) Modifier.drawBehind {
                    // вертикальные линии ветвей и «колено» к значку папки
                    val ind = 22.dp.toPx(); val x0 = 8.dp.toPx(); val w = 1.2.dp.toPx(); val mid = size.height / 2
                    val last = node!!.last
                    for (k in 0 until depth) {
                        val cx = x0 + k * ind + ind / 2
                        if (k < depth - 1) { if (!last[k + 2]) drawLine(lineColor, androidx.compose.ui.geometry.Offset(cx, 0f), androidx.compose.ui.geometry.Offset(cx, size.height), w) }
                        else {
                            drawLine(lineColor, androidx.compose.ui.geometry.Offset(cx, 0f), androidx.compose.ui.geometry.Offset(cx, if (last[k + 2]) mid else size.height), w)
                            drawLine(lineColor, androidx.compose.ui.geometry.Offset(cx, mid), androidx.compose.ui.geometry.Offset(x0 + depth * ind + 4.dp.toPx(), mid), w)
                        }
                    }
                } else Modifier)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused && depth == 0) p.lastName = f.name }
                .onKeyEvent { ev ->
                    // пульт и клавиатура: кнопка «Меню» или долгое ОК открывают меню файла
                    if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                        if (ev.key == Key.Menu) { Fm.active = p.id; p.menuFor = menuKey; return@onKeyEvent true }
                        if ((ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter) && ev.nativeKeyEvent.repeatCount == 1) {
                            longKey[0] = true; Fm.active = p.id; p.menuFor = menuKey; return@onKeyEvent true
                        }
                    }
                    // пульт и клавиатура: вправо раскрывает папку, влево сворачивает
                    if (treeOn && f.isDir && ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) when (ev.key) {
                        Key.DirectionRight -> { Fm.expand(p, nodePath); true }
                        Key.DirectionLeft -> if (isOpen) { Fm.collapse(p, nodePath); true } else false
                        else -> false
                    } else false
                }
                .pointerInput(f.name, depth, FmDnd.on) {
                    // правая кнопка мыши: меню; мышь: зажать левую и тянуть; сенсор: долгое касание, затем тянуть (перетаскивание) или отпустить (меню)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val mouse = down.type == androidx.compose.ui.input.pointer.PointerType.Mouse
                        if (mouse && currentEvent.buttons.isSecondaryPressed) { Fm.active = p.id; p.menuFor = menuKey; return@awaitEachGesture }
                        fun root(o: androidx.compose.ui.geometry.Offset) = rc[0]?.localToRoot(o) ?: o
                        val slop = viewConfiguration.touchSlop
                        var dragging = false
                        if (!mouse) {
                            // долгое касание считается здесь же, в первом проходе: дочерние обработчики нажатий не успевают «съесть» событие
                            var early = false
                            // допуск на дрожание пальца при удержании: у живого пальца на большом экране сдвиг за полсекунды превышает один touchSlop, и жест обрывался (перетаскивание «не работало»)
                            val holdSlop = slop * 3f
                            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                while (!early) {
                                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                                    val ch = ev.changes.firstOrNull { it.id == down.id }
                                    if (ch == null || !ch.pressed || (ch.position - down.position).getDistance() > holdSlop) early = true
                                }
                            }
                            if (early) return@awaitEachGesture
                            // отклик на долгое касание: короткая вибрация, дальше можно тянуть
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            down.consume()
                            Fm.active = p.id
                            val start = down.position
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                ch.consume()
                                if (!ch.pressed) { p.menuFor = menuKey; break }
                                // перетаскивание дальше ведёт корневой слой экрана: он переживает смену панели и пересоздание строки
                                if (FmDnd.on && (ch.position - start).getDistance() > slop) { FmDnd.begin(p, f, root(ch.position), down.id.value, selKey); return@awaitEachGesture }
                            }
                        } else if (FmDnd.on && currentEvent.buttons.isPrimaryPressed) {
                            val start = down.position
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                if ((ch.position - start).getDistance() > slop) { Fm.active = p.id; ch.consume(); FmDnd.begin(p, f, root(ch.position), down.id.value, selKey); return@awaitEachGesture }
                            }
                        }
                    }
                }
                .combinedClickable(
                    interactionSource = hover, indication = androidx.compose.foundation.LocalIndication.current,
                    onClick = rowClick,
                    onDoubleClick = enterDir,
                )
                .then(if (tvRow) Modifier.drawBehind { drawLine(sepColor, androidx.compose.ui.geometry.Offset(12.dp.toPx(), size.height - 0.5f), androidx.compose.ui.geometry.Offset(size.width - 12.dp.toPx(), size.height - 0.5f), 1.dp.toPx()) } else Modifier)
                .padding(start = 8.dp, end = if (dense) 0.dp else 2.dp, top = if (tvRow) 4.dp else if (dense) 0.dp else 4.dp, bottom = if (tvRow) 4.dp else if (dense) 0.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (treeOn) {
                Spacer(Modifier.width((TREE_IND * depth).dp))
                if (f.isDir) androidx.compose.material3.IconButton({ Fm.active = p.id; Fm.toggleExpand(p, nodePath) }, Modifier.size(22.dp)) {
                    Icon(if (isOpen) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
                } else Spacer(Modifier.width(22.dp))
            }
            // «Плотно»: размеры как у строки в «Софте» (значок 32, шрифты 15/17 и 11/13, без вертикальных отступов)
            // значок того же размера, что в списке «Софт» (32 dp); долгое нажатие по папке: цвет папки
            if (f.isDir) {
                val key = folderKey(p, full)
                FolderOrAppIcon(full, UiPrefs.folderColors[key] ?: UiPrefs.folderDefault, isOpen, 32.dp,
                    Modifier, customKey = "f:" + key)
            } else if (p.src == "smb" && p.smb is AppsFs) AppsRowIcon(f.name, 32.dp) else FileTypeIcon(f.name, f.ext, if (p.src == "local") full else null, 32.dp)
            Column(Modifier.padding(start = if (dense) 8.dp else 10.dp).weight(1f)) {
                MqText(f.name, active = hovered || pressed || focused || sel, fontSize = if (dense) 15.sp else 14.sp, lineHeight = if (dense) 17.sp else 18.sp, fontWeight = if (dense) FontWeight.Medium else FontWeight.Normal, fontFamily = UiPrefs.listFont)
                val sub = listOfNotNull(if (!f.isDir && Fm.showSize) fmSize(f.size) else null, f.date.takeIf { it.isNotBlank() }?.let { fmDate(it) }).joinToString("  ·  ")
                if (sub.isNotEmpty()) Text(sub, fontFamily = UiPrefs.listFont, fontSize = if (dense) 11.sp else 10.sp, lineHeight = if (dense) 13.sp else 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            }
            SmallCheck(sel, false) { Fm.active = p.id; Fm.toggleSel(p, selKey) }   // 10.11.2026: маленькая галочка у края, как в «домике»
        }
        FmItemMenu(p, f, node)
    }
    }
}

/**
 * Меню файла или папки (долгое нажатие, долгое ОК или «Меню» на пульте, правая кнопка мыши). Порядок как в X-plore: самое частое сверху.
 * Если файл входит в выделенное, действия применяются ко всем выделенным. У строки, вложенной в раскрытую папку дерева, часть действий скрыта.
 */
@Composable
private fun FmItemMenu(p: FmPane, f0: FmItem, node: TNode?) {
    val nested = (node?.depth ?: 0) > 0
    val key = if (nested) node!!.path else f0.name
    if (p.menuFor != key) return
    val rel = if (nested) node!!.path.removePrefix(p.path.trimEnd('/') + "/") else f0.name
    val f = if (nested) f0.copy(name = rel) else f0
    val o = Fm.panes[1 - p.id]
    val names = if (rel in p.selected) Fm.targets(p) else listOf(rel)
    val multi = names.size > 1
    val full = FmIo.join(p.path, rel)
    val createDir = if (f.isDir) full else full.substringBeforeLast(Char(47)).ifEmpty { Char(47).toString() }
    LDropdownMenu(true, { p.menuFor = null }) {
        @Composable fun item(l: Int, on: Boolean = true, run: () -> Unit) = DropdownMenuItem({ Text(stringResource(l), fontSize = 14.sp) }, { p.menuFor = null; run() }, enabled = on)
        if (p.src == "smb" && p.smb is AppsFs && !f.isDir && !multi) { AppMenuItems(p, f, o) { p.menuFor = null }; item(R.string.copy_name) { Fm.copyNames(listOf(f.name)) }; return@LDropdownMenu }
        if (p.src == "smb" && p.smb is AppsFs && multi) { AppBatchMenuItems(p, names.filter { n -> p.items.firstOrNull { it.name == n }?.isDir == false }, o) { p.menuFor = null }; return@LDropdownMenu }
        if (multi) Text(stringResource(R.string.fm_selected, names.size), Modifier.padding(horizontal = 12.dp, vertical = 2.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        if (!multi) {
            item(R.string.fm_open) { if (f.isDir) { if (nested) Fm.load(p, node!!.path) else Fm.open(p, f.name) } else Fm.openFile(p, f) }
            if (!f.isDir) item(R.string.fm_open_in_system) { ApkInstall.showPicker(p, f) }
        }
        item(R.string.fm_share, p.src != "adb") { Fm.share(p, names) }
        item(R.string.fm_copy) { Fm.copyMove(false, p, o, names) }
        item(R.string.fm_move) { Fm.copyMove(true, p, o, names) }
        item(R.string.fm_delete) { Fm.askDelete(p, names) }
        if (!multi && !nested) item(R.string.fm_rename) { Fm.dlg = FmDlg.Rename(p, f.name) }
        if (!multi && !nested && (p.src == "local" || p.src == "adb")) item(R.string.fm_duplicate) {
            val dot = f.name.lastIndexOf('.')
            val nn = if (dot > 0 && !f.isDir) f.name.substring(0, dot) + " (1)" + f.name.substring(dot) else f.name + " (1)"
            Fm.copyMoveTo(false, p, p, p.src, p.path, listOf(f.name), nn)
        }
        item(R.string.fm_props) { Fm.props(p, names) }
        if (p.src == "local" || p.src == "adb") item(R.string.perm_title) { Fm.dlg = FmDlg.Perms(p, names) }
        HorizontalDivider()
        if (!multi && !f.isDir) item(R.string.fm_open_as) { Fm.dlg = FmDlg.OpenAs(p, f) }
        if (!multi && !f.isDir && f.ext == "apk") item(R.string.fm_install) { Fm.openFile(p, f) }
        if (!multi && !f.isDir && f.ext == "apk" && p.src == "local" && ApkInstall.installedStores(Fm.sctx).isNotEmpty()) item(R.string.inst_market) { ApkInstall.openMarket(p, f) }
        if (!multi && !f.isDir && Fm.isArchive(f.name)) {
            item(R.string.arc_open_as_archive) { Fm.openArchive(p, f.name) }
            if (!nested) {
                item(R.string.fm_extract_here) { Fm.extractArc(p, f.name, 0) }
                item(R.string.arc_extract_named) { Fm.extractArc(p, f.name, 1) }
                item(R.string.fm_extract_other) { Fm.extractArc(p, f.name, 2) }
            }
            item(R.string.arc_test) { Fm.testArc(p, f.name) }
        }
        if (!nested && p.src == "local") item(R.string.fm_make_archive) { Fm.dlg = FmDlg.MakeArchive(p, names) }
        item(if (rel in p.selected) R.string.fm_unselect else R.string.fm_select) { Fm.toggleSel(p, rel) }
        if (!multi && !f.isDir && p.src == "local") item(R.string.ic_item) { Fm.dlg = FmDlg.FileIcon("p:" + full, f.name) }
        if (!multi && f.isDir) {
            item(R.string.fm_play_folder) { Fm.playFolder(p, rel) }
            item(R.string.fc_title) { Fm.dlg = FmDlg.FolderColor(folderKey(p, full), f.name) }
            item(R.string.fm_to_bookmarks) { Fm.toggleBookmark(full) }
        }
        // 9.0: «Копировать имя» и «Копировать путь» есть в меню любого файла и папки в любой панели и источнике (для нескольких выбранных: по строке на каждый)
        item(R.string.copy_name) { Fm.copyNames(names) }
        item(R.string.fm_copy_path) { Fm.copyPaths(p, names) }
        HorizontalDivider()
        CreateMenu(p, createDir) { p.menuFor = null }
    }
}

/** Пункт «Создать» со списком: текстовый файл, папка, ZIP-архив. Создаётся внутри папки [dir]. */
@Composable
private fun CreateMenu(p: FmPane, dir: String, close: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val ok = Fm.canCreate(p)
    DropdownMenuItem({ Text(stringResource(R.string.fm_create), fontSize = 14.sp, fontWeight = FontWeight.Medium) }, { open = !open },
        leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(20.dp)) }, trailingIcon = { Icon(if (open) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null, Modifier.size(18.dp)) })
    if (open) {
        DropdownMenuItem({ Text(stringResource(R.string.fm_new_file), fontSize = 14.sp) }, { close(); Fm.dlg = FmDlg.NewFile(dir) }, enabled = ok,
            leadingIcon = { Icon(Icons.Filled.Description, null, Modifier.size(20.dp)) }, modifier = Modifier.padding(start = 12.dp))
        DropdownMenuItem({ Text(stringResource(R.string.fm_new_folder), fontSize = 14.sp) }, { close(); Fm.dlg = FmDlg.NewFolder(dir) }, enabled = ok,
            leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null, Modifier.size(20.dp)) }, modifier = Modifier.padding(start = 12.dp))
        DropdownMenuItem({ Text(stringResource(R.string.fm_new_zip), fontSize = 14.sp) }, { close(); Fm.dlg = FmDlg.NewZip(dir) }, enabled = Fm.canCreateZip(p),
            leadingIcon = { Icon(Icons.Filled.FolderZip, null, Modifier.size(20.dp)) }, modifier = Modifier.padding(start = 12.dp))
        if (Fm.arcReadOnly(p)) Text(stringResource(R.string.fm_arc_readonly), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 28.dp, end = 12.dp, bottom = 6.dp))
    }
}

@Composable
private fun FmOverlay(modifier: Modifier) {
    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FmOpsPanel()
        Fm.note?.let { n ->
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.inverseSurface) {
                Text(n, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
    }
}

// ================================================================= диалоги

@Composable
private fun FmDialogs() {
    val d = Fm.dlg ?: return
    val dctx = LocalContext.current.applicationContext
    val close = { Fm.dlg = null }
    when (d) {
        is FmDlg.FileIcon -> CustomIconPicker(d.name, d.key, close)
        is FmDlg.FolderColor -> FolderColorDialog(d.name, UiPrefs.folderColors[d.key], true, { i -> UiPrefs.setFolderColor(dctx, d.key, i); close() }, close, "f:" + d.key)
        is FmDlg.NewFolder -> TextDlg(R.string.fm_new_folder, "", R.string.fm_name, close) { Fm.mkdirIn(Fm.cur, d.dir ?: Fm.cur.path, it) }
        is FmDlg.NewFile -> NewTextFileDialog(d.dir, close)
        is FmDlg.NewZip -> NewZipDialog(d.dir, close)
        is FmDlg.Rename -> TextDlg(R.string.fm_rename, d.name, R.string.fm_name, close) { Fm.rename(d.pane, d.name, it) }
        is FmDlg.Mask -> TextDlg(R.string.fm_select_mask, "*", R.string.fm_mask_hint, close) { Fm.mask(Fm.cur, it) }
        is FmDlg.Search -> TextDlg(R.string.fm_search, "", R.string.fm_search_hint, close) { Fm.search(it) }
        is FmDlg.MakeArchive -> MakeArcDialog(d, close)
        is FmDlg.Delete -> DeleteDialog(d, close)
        is FmDlg.FTrash -> FTrashDlg(close, d.confirm)
        is FmDlg.NeedAccess -> LAlertDialog(
            onDismissRequest = close, title = { Text(stringResource(R.string.acc_title)) },
            text = { Text(stringResource(R.string.acc_text), fontSize = 14.sp) },
            confirmButton = { TextButton({ close(); Fm.askAccess(dctx) }) { Text(stringResource(R.string.acc_open)) } },
            dismissButton = { TextButton(close) { Text(stringResource(R.string.btn_cancel)) } },
        )
        is FmDlg.Conflict -> ConflictDialog(d, close)
        is FmDlg.Sort -> SortDialog(d.pane, close)
        is FmDlg.OpenWith -> OpenWithDialog(d.pane, d.item, close)
        is FmDlg.RootShow -> RootShowDialog(close)
        is FmDlg.InstallFail -> LAlertDialog(
            onDismissRequest = close, title = { Text(stringResource(R.string.inst_failed_title)) },
            text = { Text(d.message.ifBlank { stringResource(R.string.inst_failed) }, fontSize = 14.sp) },
            confirmButton = { TextButton({ close(); Fm.goConnect?.invoke() }) { Text(stringResource(R.string.inst_connect_btn)) } },
            dismissButton = { TextButton(close) { Text(stringResource(R.string.btn_close)) } },
        )
        is FmDlg.InstallPick -> InstallPickDialog(d, close)
        is FmDlg.InstallRunDlg -> InstallRunDialog(d, close)
        is FmDlg.InstallConfirm -> InstallConfirmDialog(d, close)
        is FmDlg.ArcPass -> ArcPassDialog(d, close)
        is FmDlg.ArcConflict -> ArcConflictDialog(d, close)
        is FmDlg.ArcCharset -> ArcCharsetDialog(d, close)
        is FmDlg.Drop -> DropDialog(d, close)
        is FmDlg.OpenAs -> OpenAsDialog(d.pane, d.item, close)
        is FmDlg.View -> LAlertDialog(
            onDismissRequest = close, title = { Text(stringResource(R.string.fm_sort)) },
            text = {
                Column {
                    TextButton({ Fm.dlg = FmDlg.Sort(Fm.cur) }) { Text(stringResource(R.string.sort_view_btn)) }
                    (Fm.cur.smb as? ArchiveFs)?.takeIf { Fm.cur.src == "smb" }?.let { a -> TextButton({ Fm.dlg = FmDlg.ArcCharset(a, Fm.cur) }) { Text(stringResource(R.string.arc_charset_btn)) } }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().clickable { Fm.chooseDense(true) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(Fm.dense, { Fm.chooseDense(true) }); Text(stringResource(R.string.fm_dense))
                    }
                    Row(Modifier.fillMaxWidth().clickable { Fm.chooseDense(false) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(!Fm.dense, { Fm.chooseDense(false) }); Text(stringResource(R.string.fm_normal))
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().clickable { Fm.choosePriv(!Fm.privOn) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(Fm.privOn, { Fm.choosePriv(it) }); Text(stringResource(R.string.fm_priv_opt), fontSize = 13.sp)
                    }
                }
            },
            confirmButton = { TextButton(close) { Text(stringResource(R.string.fm_apply)) } },
        )
        is FmDlg.Bookmarks -> PathListDlg(R.string.fm_bookmarks, Fm.bookmarks(), R.string.fm_bm_empty, close,
            extra = { TextButton({ Fm.toggleBookmark(Fm.cur.path); Fm.dlg = FmDlg.Bookmarks() }) { Text(stringResource(R.string.fm_bm_add)) } }) { Fm.load(Fm.cur, it) }
        is FmDlg.History -> PathListDlg(R.string.fm_history, Fm.history(), R.string.fm_hist_empty, close) { Fm.load(Fm.cur, it) }
        is FmDlg.Storage -> StorageDlg(close)
        is FmDlg.WifiServer -> WifiServerDialog(d.dir, close)
        is FmDlg.WifiDev -> WifiDeviceDialog(d.conn, d.host, d.name, close)
        is FmDlg.PcAdd -> PcAddDialog(d.host, close)
        is FmDlg.PcScreen -> PcScreenDialog(d.host, close)
        is FmDlg.DevAdd -> DevAddDialog(d.pc, d.adb, close)
        is FmDlg.AndroidScreen -> AndroidScreenDialog(d.dev, close)
        is FmDlg.CallCode -> CallCodeDialog(d.name, d.ip, d.voice, close)
        is FmDlg.AndroidConsole -> AndroidConsoleDialog(d.dev, close)
        is FmDlg.SearchResult -> PathListDlg(R.string.fm_search, d.list, R.string.fm_nothing, close, subtitle = stringResource(R.string.fm_found, d.list.size)) {
            Fm.goTo(Fm.cur, it, false)
        }
        is FmDlg.Tree -> if (Fm.cur.src == "smb") { close(); Fm.say(Fm.str(R.string.fm_err_unsupported)) } else TreeDlg(close)
        is FmDlg.Source -> SourceDlg(close, prefill = d.prefill)
        is FmDlg.CloudModule -> CloudModuleDialog(d, close)
        is FmDlg.CloudAdd, is FmDlg.MegaAdd, is FmDlg.CloudForm, is FmDlg.CloudAll, is FmDlg.OwnKey, is FmDlg.OwnKeyPick, is FmDlg.Trash -> {
            val cx = LocalContext.current
            val modVer = CloudModule.ver   // перерисовка после установки модуля
            val cloudOnly = d !is FmDlg.Trash || d.c.kind == "cloud"
            if (cloudOnly && !CloudModule.ok(cx) && CloudModule.supportedDevice()) CloudModuleDialog(FmDlg.CloudModule { Fm.dlg = d }, close)
            else when (d) {
                is FmDlg.CloudAdd -> CloudAddDlg(close)
                is FmDlg.MegaAdd -> MegaAddDlg(close)
                is FmDlg.CloudForm -> CloudFormDlg(d.entry, close)
                is FmDlg.CloudAll -> CloudAllDlg(close)
                is FmDlg.Trash -> CloudTrashDlg(d.c, close, d.confirm)
                is FmDlg.OwnKey -> OwnKeyDlg(d.type, close, d.step, d.sharedClosed)
                is FmDlg.OwnKeyPick -> OwnKeyPickDlg(close)
                else -> {}
            }
        }
        is FmDlg.AppConfirm -> AppConfirmDlg(d, close)
        is FmDlg.AppAsk -> AppAskDlg(d, close)
        is FmDlg.AppBatch -> AppBatchDlg(d, close)
        is FmDlg.EditNet -> if (d.conn.kind == "cloud") CloudRenameDlg(d.conn, close) else SourceDlg(close, d.conn)
        is FmDlg.More -> LAlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.fm_more)) },
            text = {
                BarLazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(d.items) { a ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = a.enabled) { close(); a.run() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            val csM = CustomIcons.spec("s:" + FmButtons.key(androidx.compose.ui.platform.LocalContext.current, a.label))
                            val tintM = (a.tint ?: MaterialTheme.colorScheme.primary).copy(alpha = if (a.enabled) 1f else .38f)
                            if (csM != null) CustomIconGlyph(csM, 24.dp, tintM) else Icon(a.icon, null, Modifier.size(24.dp), tint = tintM)
                            Text(stringResource(a.label), Modifier.padding(start = 14.dp), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (a.enabled) 1f else .38f))
                        }
                    }
                }
            },
            confirmButton = { TextButton(close) { Text(stringResource(R.string.fm_close)) } })
        is FmDlg.Perms -> FmPermsDialog(d.pane, d.names, close)
        is FmDlg.MarketPick -> LAlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.inst_market_title)) },
            text = { Column { d.stores.forEach { (pk, label) ->
                Row(Modifier.fillMaxWidth().clickable { close(); ApkInstall.launchStore(pk, d.pkg) }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AppIcon(pk, 40.dp); Text(label, fontSize = 17.sp)
                }
            } } },
            confirmButton = { TextButton(close) { Text(stringResource(R.string.btn_cancel)) } })
        is FmDlg.Props -> LAlertDialog(onDismissRequest = close, title = { Text(d.title) }, text = { Column(Modifier.barScroll()) { PropsBody(d.body) } },
            confirmButton = { TextButton(close) { Text(stringResource(R.string.fm_close)) } })
        is FmDlg.TextView -> LAlertDialog(onDismissRequest = close, title = { Text(d.title, maxLines = 1) },
            text = { Column(Modifier.heightIn(max = 420.dp).barScroll()) { Text(d.body, fontSize = 12.sp, fontFamily = FontFamily.Monospace) } },
            confirmButton = { TextButton(close) { Text(stringResource(R.string.fm_close)) } })
        is FmDlg.Archive -> LAlertDialog(onDismissRequest = close, title = { Text(d.name, maxLines = 1) },
            text = { LazyColumn(Modifier.heightIn(max = 380.dp)) { items(d.lines) { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) } } },
            confirmButton = { TextButton({ close(); Fm.extract(d.pane, d.name, Fm.panes[1 - d.pane.id]) }) { Text(stringResource(R.string.fm_extract_other)) } },
            dismissButton = { Row { TextButton({ close(); Fm.extract(d.pane, d.name, d.pane) }) { Text(stringResource(R.string.fm_extract_here)) }; TextButton(close) { Text(stringResource(R.string.fm_close)) } } })
    }
}

@Composable
private fun TextDlg(title: Int, init: String, hint: Int, close: () -> Unit, ok: (String) -> Unit) {
    var t by remember { mutableStateOf(init) }
    LAlertDialog(
        onDismissRequest = close, title = { Text(stringResource(title)) },
        text = { OutlinedTextField(t, { t = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(hint), maxLines = 1) }) },
        confirmButton = { TextButton({ close(); ok(t.trim()) }) { Text(stringResource(R.string.btn_yes)) } },
        dismissButton = { TextButton(close) { Text(stringResource(R.string.btn_cancel)) } },
    )
}

@Composable
private fun PathListDlg(title: Int, list: List<String>, emptyText: Int, close: () -> Unit, subtitle: String? = null, extra: (@Composable () -> Unit)? = null, pick: (String) -> Unit) {
    LAlertDialog(
        onDismissRequest = close, title = { Text(stringResource(title)) },
        text = {
            Column {
                subtitle?.let { Text(it, fontSize = 12.sp) }
                extra?.invoke()
                if (list.isEmpty()) Text(stringResource(emptyText), fontSize = 13.sp)
                BarLazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(list) { path ->
                        val src = remember { MutableInteractionSource() }
                        val act by src.collectIsPressedAsState()
                        val foc by src.collectIsFocusedAsState()
                        val hov by src.collectIsHoveredAsState()
                        MqText(path, active = act || foc || hov, modifier = Modifier.fillMaxWidth().hoverable(src).clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current) { close(); pick(path) }.padding(vertical = 9.dp),
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(close) { Text(stringResource(R.string.fm_close)) } },
    )
}

private data class TreeRow(val path: String, val depth: Int, val open: Boolean)

private suspend fun treeKids(path: String): List<String> = runCatching { FmIo.subdirs(Fm.cur, path) }.getOrDefault(emptyList())

/** Дерево папок: раскрывается по нажатию на стрелку, нажатие на имя открывает папку в активной панели. */
@Composable
private fun TreeDlg(close: () -> Unit) {
    var rows by remember { mutableStateOf(listOf(TreeRow("/", 0, false))) }
    val scope = rememberCoroutineScope()
    fun toggle(i: Int) {
        val r = rows[i]
        scope.launch {
            if (r.open) {
                var j = i + 1; while (j < rows.size && rows[j].depth > r.depth) j++
                rows = rows.take(i) + r.copy(open = false) + rows.drop(j)
            } else {
                val k = treeKids(r.path).map { TreeRow(it, r.depth + 1, false) }
                rows = rows.take(i) + r.copy(open = true) + k + rows.drop(i + 1)
            }
        }
    }
    LaunchedEffect(Unit) {
        // сразу раскрыть цепочку до текущей папки
        val parts = Fm.cur.path.trim('/').split('/').filter { it.isNotEmpty() }
        val list = mutableListOf(TreeRow("/", 0, true))
        list.addAll(1, treeKids("/").map { TreeRow(it, 1, false) })
        var acc = ""
        for (seg in parts) {
            acc += "/$seg"
            val idx = list.indexOfFirst { it.path == acc }
            if (idx < 0) break
            list[idx] = list[idx].copy(open = true)
            list.addAll(idx + 1, treeKids(acc).map { TreeRow(it, list[idx].depth + 1, false) })
        }
        rows = list.toList()
    }
    LAlertDialog(
        onDismissRequest = close, title = { Text(stringResource(R.string.fm_tree)) },
        text = {
            BarLazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(rows.size) { i ->
                    val r = rows[i]
                    Row(Modifier.fillMaxWidth().padding(start = (r.depth * 14).dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.IconButton({ toggle(i) }, Modifier.size(30.dp)) {
                            Icon(if (r.open) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null, Modifier.size(18.dp))
                        }
                        Text(r.path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }, Modifier.weight(1f).clickable { close(); Fm.load(Fm.cur, r.path) }.padding(vertical = 8.dp),
                            fontSize = 13.sp, fontWeight = if (r.open) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                    }
                }
            }
        },
        confirmButton = { TextButton(close) { Text(stringResource(R.string.fm_close)) } },
    )
}

/** «Места»: внутренняя память, быстрые папки и подключённые SD/USB с объёмом, свободным местом и кнопкой «Извлечь». */
@Composable
private fun StorageDlg(close: () -> Unit) {
    LaunchedEffect(Unit) { Fm.refreshVols() }
    LAlertDialog(
        onDismissRequest = close, title = { Text(stringResource(R.string.fm_storage)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).barScroll(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                @Composable fun place(icon: ImageVector, title: String, sub: String?, path: String, eject: FmVol? = null, ckey: String? = null) {
                    Row(Modifier.fillMaxWidth().clickable { close(); Fm.load(Fm.cur, path) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val cs = if (ckey != null) CustomIcons.spec(ckey) else null
                        if (cs != null) CustomIconView(cs, 26.dp) else Icon(icon, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(title, fontSize = 14.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (sub != null) Text(sub, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                        }
                        if (eject != null) TextButton({ Fm.eject(eject) }) { Text(stringResource(R.string.fm_eject), fontSize = 12.sp) }
                    }
                }
                place(Icons.Filled.SdStorage, stringResource(R.string.fm_internal), "/sdcard", "/sdcard", ckey = "d:dev")
                Fm.vols.filter { it.ok }.forEach { v ->
                    val sub = (if (v.total > 0) stringResource(R.string.fm_free_of, fmSize(v.free), fmSize(v.total)) + "\n" else "") + v.path
                    place(if (v.usb) Icons.Filled.Usb else Icons.Filled.SdCard, v.label, sub, v.path, v, ckey = "d:vol:" + v.path)
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Fm.quick.drop(1).forEach { q ->
                    val lock = FmIo.restricted(q)
                    place(if (lock) Icons.Filled.Lock else Icons.Filled.Folder, q.substringAfterLast('/'), if (lock) q + "  ·  ADB" else q, q, ckey = "f:" + q)
                }
                place(Icons.Filled.Folder, "/", null, "/", ckey = "d:root")
            }
        },
        confirmButton = { TextButton(close) { Text(stringResource(R.string.fm_close)) } },
    )
}

/** Источник панели: память напрямую (по умолчанию), ADB для Android/data и системных папок, сетевые источники (SMB, SFTP, FTP/FTPS, WebDAV), избранное и поиск в сети. */
/** «Текстовый файл»: имя по умолчанию «Новый текстовый файл», «.txt» добавляется само; ошибки показываются в окне. */
@Composable
private fun NewTextFileDialog(dir: String?, close: () -> Unit) {
    val p = Fm.cur
    val def = stringResource(R.string.fm_new_text_default)
    var t by remember { mutableStateOf(def) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LAlertDialog(
        onDismissRequest = close, title = { Text(stringResource(R.string.fm_new_file)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(t, { t = it; err = null }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.fm_name), maxLines = 1) }, isError = err != null)
                err?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton({
                if (busy) return@TextButton
                busy = true
                scope.launch { val e = Fm.newTextFile(p, t, dir ?: p.path); busy = false; if (e == null) close() else err = e }
            }) { Text(stringResource(R.string.btn_yes)) }
        },
        dismissButton = { TextButton(close) { Text(stringResource(R.string.btn_cancel)) } },
    )
}


/** Новый пустой ZIP-архив: имя можно изменить сразу. */
@Composable
private fun NewZipDialog(dir: String?, close: () -> Unit) {
    val p = Fm.cur
    val def = stringResource(R.string.fm_new_zip_default)
    var t by remember { mutableStateOf(def) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LAlertDialog(
        onDismissRequest = close, title = { Text(stringResource(R.string.fm_new_zip)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(t, { t = it; err = null }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.fm_name), maxLines = 1) }, isError = err != null)
                err?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton({
                if (busy) return@TextButton
                busy = true
                scope.launch { val e = Fm.newZip(p, dir ?: p.path, t); busy = false; if (e == null) close() else err = e }
            }) { Text(stringResource(R.string.btn_yes)) }
        },
        dismissButton = { TextButton(close) { Text(stringResource(R.string.btn_cancel)) } },
    )
}
