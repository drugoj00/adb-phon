@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.adbphon

import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.drawscope.withTransform
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.foundation.layout.height
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
import kotlinx.coroutines.flow.flowOn
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
    /** 14.0: индекс отметки (собирается один раз на каждое изменение набора): строки спрашивают у него, отмечены ли они, за O(глубина пути), а не просмотром всего набора. */
    val selIdx: androidx.compose.runtime.State<SelIndex> = androidx.compose.runtime.derivedStateOf { SelIndex(selected) }
    var menuFor by mutableStateOf<String?>(null)
    var scrollToName by mutableStateOf<String?>(null)
    /** 10.2 (58): место прокрутки списка (папка, первая видимая строка, сдвиг): возвращается при смене вкладок и повороте. */
    @Volatile var scrollPath = ""
    @Volatile var scrollIdx = 0
    @Volatile var scrollOff = 0
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
    /** Счётчик нажатий «Дом»: корневой список по его изменению прокручивается наверх и отдаёт фокус первой строке. */
    var homeTick by androidx.compose.runtime.mutableStateOf(0)
    /** Запомненные между запусками сетевые узлы и ADB: раскрываются лениво, когда есть связь. */
    val rootPending = HashSet<String>()
    /** 12.0: источник (локальный, ADB или конкретное облако/сеть), которому принадлежат раскрытые папки дерева; при смене дерево и кэш очищаются. */
    var treeSrc = ""
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
    /** 10.2 (56): «Нужен доступ к карте памяти»: выдача доступа к съёмному тому через системное окно выбора папки. [done] получает итог. */
    class SafNeed(val uuid: String, val label: String, val done: (Boolean) -> Unit) : FmDlg()
    class Mask : FmDlg()
    class Search : FmDlg()
    /** [where]: где искали (подпись в окне); [dst]: источник, если поиск шёл из корня «Источники» (иначе из текущей панели). */
    class SearchResult(val list: List<String>, val where: String = "", val dst: FmRootCfg.Dst? = null) : FmDlg()
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
    class OpenAs(val pane: FmPane, val item: FmItem) : FmDlg()
    class Bookmarks : FmDlg()
    class History : FmDlg()
    class Tree : FmDlg()
    /** 9.3: «Куда?» — когда копирование или перенос начаты с корневого экрана, а другая панель тоже корень: выбор локальной папки-приёмника. */
    /** 20.11.2026 (владелица: «когда нажимаю «Копировать», копируется сразу без вопросов»): подтверждение копирования и переноса: откуда, куда, сколько, галочка «Переместить», что делать с совпадающими именами. [go] получает итоговое «перенос?» и выбор для совпадений (null: спрашивать). */
    class TransferConfirm(val move: Boolean, val from: String, val to: String, val count: Int, val names: List<String>, val sizeBytes: Long, val dst: FmPane?, val src: FmPane?, val go: (Boolean, CAct?) -> Unit) : FmDlg()
    /** 20.11.2026: «⋯» на боковой панели: список кнопок, которых сейчас нет на панели; нажатие добавляет кнопку. */
    class AddButton : FmDlg()
    class PickTarget(val move: Boolean, val jobs: List<Pair<FmPane, List<String>>>, val after: suspend () -> Unit = {}) : FmDlg()
    class Storage : FmDlg()
    class WifiServer(val dir: String) : FmDlg()
    class WifiDev(val conn: NetConn?, val host: String, val name: String, val authFailed: Boolean = false) : FmDlg()
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
    class More(val items: List<FmAction>, val excl: Boolean = false) : FmDlg()
    class Props(val title: String, val body: String) : FmDlg()
    class Perms(val pane: FmPane, val names: List<String>) : FmDlg()
    class TextView(val title: String, val body: String) : FmDlg()
    class Archive(val pane: FmPane, val name: String, val lines: List<String>) : FmDlg()
    class Repack(val spec: RepackSpec) : FmDlg()
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
    /** 10.2 (58): когда экран «Файлы» показан последний раз; в первые полсекунды фокус, пришедший сам, не меняет активное окно. */
    @Volatile var screenShownAt = 0L
    /** Счётчик изменений сортировки и дочитанных тегов: по нему списки пересортировываются. */
    var sortTick by mutableStateOf(0)
    var showHidden by mutableStateOf(false)
    /** Нажатие на папку в режиме «Дерево»: true раскрывает в списке, false входит в папку. */
    var dirTapExpand by mutableStateOf(true)
    /** 12.0: «Держать раскрытой только одну папку» (по умолчанию включено): раскрыл другую — прежняя сворачивается, а её список выгружается из памяти. */
    var treeOne by mutableStateOf(true)
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
    /** 10.0: сколько заданий было запущено за всё время; нужен, чтобы заметить даже очень быстрое задание, которое началось и закончилось между проверками. */
    @Volatile var jobSeq = 0
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
            val prevPath = p.path
            var fromCache = false
            // 26.11.2026: облачная папка сначала показывается из прошлого чтения, свежий список заменит её, когда придёт
            (p.smb as? CloudFs)?.takeIf { p.src == "smb" }?.cached(path)?.let { old ->
                if (myGen == p.loadGen && (p.path != path || p.items.isEmpty())) { p.path = path; p.items = old.map { FmItem(it.name, it.isDir, it.size, it.date) }; p.error = null; p.selected = emptySet(); fromCache = true }
            }
            while (true) {
                try { list = FmIo.list(p, path); break }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: SafAccess.NeedGrant) {
                    // съёмный том без доступа: окно «Нужен доступ к карте памяти», после выбора список читается через дерево
                    p.status = str(R.string.saf_waiting)
                    val ok = SafAccess.ensure(path)
                    p.status = null
                    if (ok) continue
                    err = str(R.string.saf_not_granted); break
                }
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
            if (remember && prevPath != path) { p.back.add(prevPath); if (p.back.size > 50) p.back.removeAt(0) }
            p.path = path; p.items = list; p.error = null; // отметки сбрасываются; кроме отмеченного, пока на экране был тот же список из прошлого чтения
            p.selected = if (fromCache) p.selected.filter { n -> list.any { it.name == n } }.toSet() else emptySet(); p.lastName = pick
            if (AppLog.enabled) Thumbs.logList("список(" + p.src + ")", path, list.count { !it.isDir && (it.ext == "apk" || it.ext == "apks" || it.ext == "xapk" || it.ext == "apkm") })
            if (remember) fmLeaveRoot(p)
            pick?.let { p.scrollToName = it }
            if (p.src != "smb") addHistory(path)
            // открытая папка запоминается при КАЖДОМ переходе (в том числе внутри сетевых источников)
            runCatching {
                val key = if (p.src == "smb") p.net?.takeIf { p.smb !is ArchiveFs }?.let { "net:" + it.kind + "|" + it.host + "|" + it.user } else p.src
                if (key != null && p.parked == null) { Startup.saveLast(sctx, p.id, key, path); if (p.src == "local" || p.src == "smb") Startup.saveSrc(sctx, p.id, key) }
            }
            p.kids.clear()
            // 12.0: сменился источник (вышли из облака в память и наоборот) или ушли в другую ветку: раскрытые папки прежнего места и кэш облака сбрасываются
            val srcKey = p.src + "|" + (if (p.src == "smb") p.net?.let { it.kind + "|" + it.host + "|" + it.user }.orEmpty() else "")
            if (srcKey != p.treeSrc) {
                if (p.treeSrc.isNotEmpty() && p.expanded.isNotEmpty()) { p.expanded.clear(); saveExpanded(p) }
                p.treeSrc = srcKey
                FmRootCfg.purgeClosedCloud()
            } else if (treeOne) {
                val b = path.trimEnd('/') + "/"
                if (p.expanded.any { !it.startsWith(b) }) { val keep = p.expanded.filter { it.startsWith(b) }; p.expanded.clear(); p.expanded.addAll(keep); saveExpanded(p) }
            }
            // 10.0: после перечитывания раскрытые папки дерева подгружаются заново (ключ эффекта мог не измениться, и они оставались пустыми)
            if (treeMode) { val base = path.trimEnd('/') + "/"; for (e in p.expanded.toList()) if (e.startsWith(base)) scope.launch { loadKids(p, e) } }
        }
    }

    fun init(ctx: Context) {
        sctx = ctx; FmIo.app = ctx.applicationContext
        if (inited) return
        inited = true
        panes.forEach { it.sortCfg = SortStore.loadGlobal(it.id) }
        showHidden = prefs().getBoolean("hidden", false)
        // 10.2 (58): активное окно (левое или правое) помнится и после перезапуска программы
        active = prefs().getInt("active_pane", 0).coerceIn(0, 1)
        scope.launch { androidx.compose.runtime.snapshotFlow { active }.collect { a -> prefs().edit().putInt("active_pane", a).apply() } }
        dirTapExpand = prefs().getString("dir_tap", "expand") != "open"
        treeOne = prefs().getBoolean("tree_one", true)
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
                val items = withContext(Dispatchers.IO) { runCatching { sortList(p, dir, if (showHidden) items0 else items0.filter { !it.hidden }, treeCfg(p, dir)) }.getOrDefault(items0) }
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
                // 26.11.2026: облако, корень которого уже читали, открывается без отдельной проверки: она читала корень второй раз
                runCatching { (fs as? KeepFs)?.connect() ?: (fs as? CloudFs)?.let { cl -> if (parked != null || fallbackLocal || cl.cached("/") == null) cl.connect() } }.exceptionOrNull()
            } }
            p.status = null
            if (err != null) {
                if (parked != null) { p.parked = parked; p.parkedErr = str(R.string.fm_reconnect_fail, FmIo.nice(err)); return@launch }
                if (fallbackLocal) { say(str(R.string.fm_net_lost, c.title)); load(p, remember = false); return@launch }
                fmLeaveRoot(p); p.error = FmIo.nice(err); p.src = "smb"; p.smb = fs; p.net = c; p.items = emptyList(); p.path = fs.root; return@launch
            }
            p.net = c
            p.cloudView = "top"; FmRootCfg.sel.clear()
            // T3 (19.11.2026): возврат к сохранённому месту облака или сети (запуск программы, «Переподключиться»): раскрытые папки панели не сбрасываются как при смене источника (12.0)
            if (parked != null) p.treeSrc = "smb|" + c.kind + "|" + c.host + "|" + c.user
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
                        // 11.0: флешка с собственной меткой («KINGSTON») в названии слова USB не имеет: тип берётся ещё и по номеру устройства тома (старший номер 8 = USB-накопитель, 179 = карта SD)
                        val usb = desc?.contains("USB", true) == true || usbByMajor(mounts, uuid, uuid?.let { ids[it] })
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

    /** Том подключён по USB: у виртуальных томов vold в строке монтирования или в списке томов «public:8,N» (старший номер 8, диск SCSI). */
    private fun usbByMajor(mounts: String, uuid: String?, volId: String?): Boolean {
        if (volId != null && volId.startsWith("public:8,")) return true
        if (uuid == null) return false
        return mounts.lines().any { it.contains(uuid, true) && it.contains("public:8,") }
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
    /**
     * 10.0 (chk): настройка для вложенного уровня раскрытого дерева: своя у этой папки, иначе та же, что у открытой папки панели.
     * Раньше вложенные уровни брали общую настройку панели, и при «Только эта папка» верхний уровень и раскрытые папки сортировались по-разному.
     */
    fun treeCfg(p: FmPane, dir: String): SortCfg = SortStore.folder(p, dir) ?: if (SortEngine.isInside(p.path, dir) || dir.trimEnd('/') == p.path.trimEnd('/')) cfgFor(p, p.path) else p.sortCfg
    fun setHidden(v: Boolean) {
        showHidden = v; prefs().edit().putBoolean("hidden", v).apply()
        // дерево «Источники» кеширует списки папок: пересобрать, иначе показ скрытых никак не виден
        if (panes.any { it.root }) runCatching { FmRootCfg.refreshAll(sctx) }
        scope.launch { delay(1300); say(str(if (v) R.string.fm_hidden_on else R.string.fm_hidden_off)) }    // после «Обновлено» от пересборки дерева
    }

    fun shown(p: FmPane): List<FmItem> = sortList(p, p.path, if (showHidden) p.items else p.items.filter { !it.hidden })

    /**
     * 22.11.2026 (18.0, тормоза раскрытия): отсортированное содержимое раскрытой папки дерева. Раньше сортировка (с таблицами тегов/цифр/цветов на каждый вызов)
     * шла в главном потоке при каждой пересборке плоского списка дерева (раскрытие папки в 3000 файлов: кадр ~370 мс на приставке S905X, прокрутка ~10 кадров/с).
     * Теперь результат запоминается по (панель, путь) и признакам: тот же список, «скрытые», настройка сортировки, [sortTick]; первый раз считается в фоне сразу после чтения папки ([loadKids]).
     */
    private class SortedKids(val src: List<FmItem>, val hidden: Boolean, val cfg: SortCfg, val tick: Int, val out: List<FmItem>)
    private val kidsSorted = java.util.concurrent.ConcurrentHashMap<String, SortedKids>()
    fun sortedKids(p: FmPane, path: String, k: List<FmItem>): List<FmItem> {
        val cfg = treeCfg(p, path); val key = p.id.toString() + "|" + path; val hid = showHidden; val tick = sortTick
        kidsSorted[key]?.let { c -> if (c.src === k && c.hidden == hid && c.cfg == cfg && c.tick == tick) return c.out }
        val out = sortList(p, path, if (hid) k else k.filter { !it.hidden }, cfg)
        if (kidsSorted.size > 64) kidsSorted.clear()
        kidsSorted[key] = SortedKids(k, hid, cfg, tick, out)
        return out
    }

    /** Сортировка списка папки [dir] по группам (папки, файлы, фото, видео, музыка, документы), у каждой группы свой критерий. */
    fun sortList(p: FmPane, dir: String, base: List<FmItem>, cfg0: SortCfg? = null): List<FmItem> {
        val cfg = cfg0 ?: cfgFor(p, dir)
        // теги, число элементов и цвета выбираются заранее: в самой сортировке только быстрые обращения к таблицам
        val meta = HashMap<FmItem, SMeta>(); val cnt = HashMap<FmItem, Int>(); val col = HashMap<FmItem, Int>()
        val needColor = cfg.keyOf(SGroup.DIR) == SKey.COLOR
        for (f in base) {
            val g = cfg.groupOf(f.isDir, f.ext)
            if (!cfg.needsMeta(g)) { if (g == SGroup.DIR && needColor) col[f] = UiPrefs.folderColors[folderKey(p, join(dir, f.name))] ?: UiPrefs.folderDefault; continue }
            if (g == SGroup.DIR) cnt[f] = SortMeta.count(p, join(dir, f.name), f)
            else SortMeta.get(join(dir, f.name), f)?.let { meta[f] = it }
        }
        return SortEngine.sort(base, cfg, SortCtx({ meta[it] }, { cnt[it] ?: -1 }, { col[it] ?: 0 }))
    }

    // ---------- режим «Дерево»: раскрытие папок внутри списка ----------
    fun setShowSize(ctx: Context, v: Boolean) { showSize = v; ctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE).edit().putBoolean("show_size", v).apply() }
    fun setTree(v: Boolean) { treeMode = v; prefs().edit().putBoolean("tree", v).apply() }

    private fun saveExpanded(p: FmPane) { prefs().edit().putString("exp_p${p.id}", p.expanded.takeLast(200).joinToString("\n")).apply() }

    /** Подгрузить содержимое раскрытой папки (подпапки подгружаются только при раскрытии). */
    /** 22.11.2026: папки, читающиеся сейчас (панель + путь): пока список идёт по сети, повторные нажатия не запускают второе чтение той же папки. */
    private val kidsLoading = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    suspend fun loadKids(p: FmPane, path: String) {
        if (p.kids.containsKey(path)) return
        val key = p.id.toString() + "|" + path
        if (kidsLoading.putIfAbsent(key, true) != null) return
        try {
            val t0 = System.nanoTime()
            val list = runCatching { FmIo.list(p, path) }.getOrNull()
            if (AppLog.enabled) AppLog.i("ADBphon", "Раскрытие папки [${p.src}] $path: ${list?.size ?: -1} эл., чтение списка ${(System.nanoTime() - t0) / 1_000_000} мс")
            if (list == null) return      // временная ошибка (доступ, сеть): раскрытие остаётся в памяти, подгрузится при следующем обновлении
            // папку свернули, пока список читался: в память не кладём (при новом раскрытии прочитается заново)
            if (path in p.expanded) {
                // сортировка большой папки заранее и в фоне: плоский список дерева берёт готовый результат ([sortedKids]) и не сортирует в главном потоке
                if (list.size > 200) withContext(Dispatchers.Default) { sortedKids(p, path, list) }
                if (path in p.expanded) p.kids[path] = list
            }
        } finally { kidsLoading.remove(key) }
    }

    /** 26.11.2026 (слово владелицы): отметки живут только внутри раскрытых папок; раскрыли другую папку, а прежняя свернулась: её отметки снимаются. */
    private fun pruneSel(p: FmPane) {
        p.selected = p.selected.filter { key -> val par = key.substringBeforeLast('/', ""); par.isEmpty() || FmIo.join(p.path, par) in p.expanded }.toSet()
    }

    fun toggleExpand(p: FmPane, path: String) {
        val opening = path !in p.expanded
        // 20.11.2026 (слово владелицы): свернули папку: раскрытое внутри забывается, повторное нажатие раскрывает только эту папку
        if (path in p.expanded) { val inner = path.trimEnd('/') + "/"; p.expanded.removeAll { it.startsWith(inner) }; p.expanded.remove(path); saveExpanded(p) }
        else {
            // раскрыта только цепочка к последней открытой папке: остальные сворачиваются (если выключено «Держать раскрытой только одну папку», остаются)
            if (treeOne) {
                val stay = p.expanded.filter { o -> path.startsWith(o.trimEnd('/') + "/") }
                p.expanded.clear(); p.expanded.addAll(stay)
            }
            p.expanded.add(path); saveExpanded(p)
            if (p.smb is CloudFs && p.kids.containsKey(path)) scope.launch { val fresh = runCatching { FmIo.list(p, path) }.getOrNull(); if (fresh != null && p.kids.containsKey(path) && p.kids[path] != fresh) p.kids[path] = fresh }
            else scope.launch { loadKids(p, path) }
        }
        dropClosedKids(p)
        if (opening) pruneSel(p)
    }

    /** 12.0: списки свёрнутых папок выгружаются из памяти (в облаке повторное раскрытие читает папку заново и показывает свежее). Только при «Держать раскрытой только одну папку». */
    fun dropClosedKids(p: FmPane) {
        if (!treeOne) return
        val open = p.expanded.toSet()
        val closed = p.kids.keys.toList().filter { it !in open }
        // 26.11.2026: у облаков прочитанные списки не выбрасываются (повторное раскрытие сразу, свежее подчитывается в фоне)
        if (p.smb is CloudFs) { if (closed.size > 120) closed.take(closed.size - 120).forEach { p.kids.remove(it) }; return }
        closed.forEach { p.kids.remove(it) }
    }

    fun chooseTreeOne(v: Boolean) {
        treeOne = v; prefs().edit().putBoolean("tree_one", v).apply()
        if (v) panes.forEach { p ->
            // оставляем раскрытой цепочку к последней раскрытой папке
            val last = p.expanded.lastOrNull()
            if (last != null) { val stay = p.expanded.filter { o -> o == last || last.startsWith(o.trimEnd('/') + "/") }; p.expanded.clear(); p.expanded.addAll(stay); saveExpanded(p) }
            dropClosedKids(p)
            if (p.rootOpen.isNotEmpty()) FmRootCfg.purgeClosedCloud()
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
        "smb" -> {
            lastFetchMsg = null
            val t = java.io.File(arcTmp(), "open_" + System.nanoTime().toString(36) + "_" + name.substringAfterLast('/'))
            if (fetchWithProgress(p, join(p.path, name), name.substringAfterLast('/'), sizeInPane(p, name), t)) t else null
        }
        else -> null
    }

    /** Размер файла из уже показанных списков панели (для полоски загрузки); -1: неизвестен. */
    private fun sizeInPane(p: FmPane, name: String): Long {
        val n = name.substringAfterLast('/')
        p.items.firstOrNull { it.name == name || it.name == n }?.let { if (!it.isDir) return it.size }
        p.kids[p.path.trimEnd('/')]?.firstOrNull { it.name == n }?.let { if (!it.isDir) return it.size }
        return -1L
    }

    /** Пояснение, почему файл не получен (отмена, ошибка сети, уже загружается); null: источник не поддерживается. */
    @Volatile private var lastFetchMsg: String? = null
    private fun failMsg(): String { val m = lastFetchMsg; lastFetchMsg = null; return m ?: str(R.string.fm_err_unsupported) }
    /** Загрузки из сети, идущие сейчас (источник + путь): повторный клик по тому же файлу не запускает вторую копию. */
    private val fetching = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * 22.11.2026 (18.0, тормоза LAN): скачивание файла из сети во временную копию с видимым ходом: карточка в панели операций
     * (имя, %, скорость, остаток, «Отмена»), не блокирует экран; повторный клик по тому же файлу, пока он грузится, игнорируется.
     * true: файл готов ([dest]); false: ошибка или отмена (текст в [lastFetchMsg], недокачанный файл удалён).
     */
    suspend fun fetchWithProgress(p: FmPane, path: String, title: String, size: Long, dest: java.io.File): Boolean {
        val key = System.identityHashCode(p.smb).toString() + "|" + path
        if (fetching.putIfAbsent(key, true) != null) { lastFetchMsg = str(R.string.fm_already_loading); say(lastFetchMsg!!); return false }
        val j = FmJob(str(R.string.fm_downloading), 1, true)
        j.queued = false; j.current = title; j.totalUnits = size
        jobs.add(j); jobSeq++
        FmOpService.start(sctx)
        val t0 = System.currentTimeMillis()
        var ok = false; var err: String? = null
        try {
            withContext(Dispatchers.IO) { p.smb!!.copyTo(path, dest, j) }
            ok = true; j.publish(true)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: LocalOps.Cancelled) { lastFetchMsg = str(R.string.fm_cancelled)
        } catch (e: Throwable) { err = FmIo.nice(e); lastFetchMsg = str(R.string.fm_error, err!!) }
        finally {
            fetching.remove(key)
            if (!ok) runCatching { dest.delete() }
            j.error = err; j.finished = true
            if (j.cancelled || (!ok && err == null)) jobs.remove(j) else scope.launch { delay(if (err != null) 4000 else 800); jobs.remove(j) }
            AppLog.i("ADBphon", "Загрузка из сети: $title ${if (ok) "готово" else "не вышло"} за ${System.currentTimeMillis() - t0} мс, ${j.doneUnits} Б")
        }
        if (j.cancelled && !ok) lastFetchMsg = str(R.string.fm_cancelled)
        return ok
    }

    /** Открыть архив как папку в панели. */
    fun openArchive(p: FmPane, name: String): kotlinx.coroutines.Job = scope.launch {
        val file = localFileOf(p, name) ?: run { say(failMsg()); return@launch }
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
        val file = localFileOf(p, name) ?: run { say(failMsg()); return@launch }
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
        val file = localFileOf(p, name) ?: run { say(failMsg()); return@launch }
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
        // 26.11.2026: на карте памяти без ADB и root архив собирается в кэше и переносится через выданный доступ
        val saf = SafAccess.needs(out.path)
        if (saf && !SafAccess.ensure(out.path)) { say(str(R.string.saf_not_granted)); return@launch }
        runJob(str(R.string.fm_packing), listOf(outName), true, { srcs.sumOf { LocalOps.size(it) } }, { srcs.sumOf { LocalOps.fileCount(it) } }) { j, _ ->
            val stage = if (saf) java.io.File(java.io.File(sctx.cacheDir, "fmsaf"), System.nanoTime().toString(36)).apply { mkdirs() } else null
            try {
                val target = if (stage != null) java.io.File(stage, outName) else out
                val from = if (stage == null) srcs else srcs.map { src -> if (src.canRead() || !SafAccess.needs(src.path)) src else java.io.File(java.io.File(stage, "src"), src.name).also { SafAccess.copyTree(src.path, it.path, false, j) } }
                ArcCreate.create(format, target, from, level, pass, arcTmp(), j)
                if (stage != null) SafAccess.copyTree(target.path, out.path, false, j)
                null
            } catch (e: ArcUnsupported) { e.message } finally { stage?.deleteRecursively() }
        }
        load(p, remember = false, pick = outName)
    }

    /**
     * «Назад» во вкладке «Файлы»: сначала закрыть открытое окно и отменить перетаскивание, затем снять выделение активной панели, затем «Вверх» в активной панели
     * (с корня источника сети, облака или памяти на «домик»). Вторая панель не трогается. false: делать нечего (панель на «домике»), решает вкладка.
     */
    fun onBack(): Boolean {
        if (dlg != null) { dlg = null; return true }
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
        if (Arc.kindOf(item.name) != null && Arc.opensAsFolder(item.name) && item.ext !in setOf("apk", "apks", "xapk", "apkm")) {
            val p = cur
            scope.launch {
                if (c == null) fmOpenLocal(p, parent) else { fmLeaveRoot(p); connectNet(p, c, parent) }
                delay(700); openArchive(p, item.name)
            }
            return
        }
        val sp = FmPane(9, parent)
        if (c == null) sp.src = "local" else { sp.src = "smb"; sp.smb = FmRootCfg.fsFor(c); sp.net = c }
        if (openCat(item.ext) == null) { openFile(sp, item); return }
        // 10.1: видео и музыка из дерева открываются со списком своей папки, чтобы в плеере работали «следующий/предыдущий»
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    sp.items = if (c == null) java.io.File(parent).listFiles().orEmpty().map { f -> FmItem(f.name, f.isDirectory, if (f.isDirectory) 0L else f.length(), "") }
                    else sp.smb!!.list(parent).map { e -> FmItem(e.name, e.isDir, e.size, "") }
                }
            }
            openFile(sp, item)
        }
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

    /** 10.0 (fm1): содержимое раскрытой папки дерева (ключи «папка/имя» от текущей папки панели); null: папка свёрнута. */
    fun selKids(p: FmPane): (String) -> List<String>? = { rel ->
        val full = FmIo.join(p.path, rel)
        if (treeMode && full in p.expanded) p.kids[full]?.let { l -> (if (showHidden) l else l.filter { !it.hidden }).map { rel + "/" + it.name } } else null
    }
    /** Отмечено само или через отмеченную папку выше (раскрытое дерево). */
    fun isSel(p: FmPane, key: String): Boolean = SelTree.covered(p.selected, key)
    /** 10.0 (fm1): отметка папки = всё её содержимое; снятие вложенного оставляет остальное отмеченным (SelTree). */
    fun toggleSel(p: FmPane, name: String) {
        p.lastName = name.substringAfterLast('/')
        // 26.11.2026 (как в X-plore): отмеченная папка (или папка, где отмечены только её файлы) по повторному нажатию меняет отметку: папка, затем только файлы в ней, затем ничего
        // слово владелицы: круг только у РАСКРЫТОЙ папки; у свёрнутой отметка просто включается и выключается
        if (treeMode && FmIo.join(p.path, name) in p.expanded && (name in p.selected || SelTree.partial(p.selected, name)) && isDirKey(p, name)) { cycleFolder(p, name); return }
        p.selected = SelTree.toggle(p.selected, name, selKids(p))
    }
    private fun isDirKey(p: FmPane, rel: String): Boolean {
        val par = rel.substringBeforeLast('/', ""); val nm = rel.substringAfterLast('/')
        val l = if (par.isEmpty()) p.items else p.kids[FmIo.join(p.path, par)]
        return l?.firstOrNull { it.name == nm }?.isDir == true
    }
    private fun cycleFolder(p: FmPane, name: String) {
        val full = FmIo.join(p.path, name)
        val go = { l: List<FmItem>? ->
            p.selected = if (l == null) SelTree.toggle(p.selected, name, selKids(p))
            else SelTree.cycle(p.selected, name, l.filter { showHidden || !it.hidden }.map { "$name/${it.name}" }, selKids(p))
        }
        val cached = p.kids[full]
        if (cached != null) go(cached)
        else scope.launch { go(withContext(Dispatchers.IO) { runCatching { FmIo.list(p, full) }.getOrNull() }) }
    }
    fun selectAll(p: FmPane) { if (p.root) { FmRootCfg.selectAllVisible(p.id); return }; if (virt(p)) return; p.selected = SelTree.selectAll(p.selected.filter { '/' in it }, shown(p).map { it.name }) }
    fun invert(p: FmPane) { if (p.root) { FmRootCfg.invertVisible(p.id); return }; if (virt(p)) return; p.selected = SelTree.invert(p.selected, shown(p).map { it.name }, selKids(p)) }
    fun mask(p: FmPane, glob: String) {
        if (virt(p)) return
        val re = Regex("^" + glob.split('*').joinToString(".*") { Regex.escape(it) }.replace("\\?", ".") + "$", RegexOption.IGNORE_CASE)
        // и вложенные: содержимое раскрытых папок дерева (ключ «папка/имя» от текущей папки, как у ручного выбора)
        val base = p.path.trimEnd('/') + "/"
        val nested = p.expanded.toList().filter { it.startsWith(base) }.flatMap { d -> (p.kids[d] ?: emptyList()).filter { re.matches(it.name) }.map { d.removePrefix(base) + "/" + it.name } }
        p.selected = SelTree.normalize(shown(p).filter { re.matches(it.name) }.map { it.name } + nested)
    }
    /** С чем работают кнопки: выделенное (верхний уровень по порядку списка, затем отмеченное глубже в раскрытых папках дерева, «папка/файл»), иначе ничего. */
    fun targets(p: FmPane): List<String> { if (virt(p)) return emptyList(); val s = SelTree.normalize(p.selected); return shown(p).map { it.name }.filter { it in s } + s.filter { '/' in it } }

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
        jobs.add(j); jobSeq++
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
        } finally {
            // 20.11.2026 (правило владелицы по полоскам): полоска остаётся заполненной с подписью «Готово» (ошибка: текст ошибки рядом), потом карточка уходит
            j.error = err; j.finished = true
            if (j.cancelled) jobs.remove(j)
            else scope.launch { delay(if (err != null) 4000 else 1500); jobs.remove(j) }
        }
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
    /**
     * 20.11.2026: ворота перед копированием и переносом: окно подтверждения показывается ВСЕГДА (слово владелицы, отключить нельзя), [go] вызывается после «Выполнить».
     */
    fun askTransfer(move: Boolean, from: String, to: String, names: List<String>, sizeBytes: Long, dst: FmPane? = null, src: FmPane? = null, go: (Boolean, CAct?) -> Unit) {
        dlg = FmDlg.TransferConfirm(move, from, to, names.size, names, sizeBytes, dst, src, go)
    }

    fun copyMove(move: Boolean, src: FmPane, dst: FmPane, names: List<String>, acts: Map<String, CAct>? = null, newName: String? = null, noRepack: Boolean = false, policy: CAct? = null) {
        if (names.isEmpty()) return
        // 10.0: набор APK-частей (base + config.*), например папка из /data/app: сначала спросить формат результата
        if (!noRepack && !move && acts == null && newName == null && names.none { '/' in it } && repackAsk(src, dst, names) { copyMove(move, src, dst, names, acts, newName, true, policy) }) return
        if (names.any { '/' in it }) {
            // отмечено в разных раскрытых папках дерева: для каждой исходной папки своя операция (очередь заданий общая, идут по одной)
            val groups = names.groupBy { it.substringBeforeLast('/', "") }
            scope.launch {
                for ((parent, list) in groups) {
                    val ps = if (parent.isEmpty()) src else FmPane(9, join(src.path, parent)).also { it.src = src.src; it.smb = src.smb; it.net = src.net; it.auto = src.auto }
                    copyMove(move, ps, dst, list.map { it.substringAfterLast('/') }, acts, policy = policy)
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
                    // 20.11.2026: выбор из окна подтверждения («Пропустить», «Заменить», «Оставить оба») применяется ко всем совпадениям сразу, без второго окна
                    if (rep.top.isNotEmpty() && policy != null) decided = rep.top.associate { it.rel to policy }
                    else if (rep.top.isNotEmpty()) { dlg = FmDlg.Conflict(move, src, dst, ok, rep); return@launch }
                    else decided = emptyMap()
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
        // 11.0: папки на сети, облаке и ADB: объём считается по спискам (до 4000 записей), иначе полоса шла бы бегущей
        val budget = intArrayOf(4000)
        suspend fun rsize(dir: String): Long {
            if (budget[0] <= 0) return -1L
            val pp = FmPane(9, dir).also { it.src = src.src; it.smb = src.smb; it.net = src.net; it.auto = src.auto }
            val l = try { FmIo.list(pp, dir) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { return -1L }
            var t = 0L
            for (e in l) { budget[0]--; if (e.isDir) { val s = rsize(join(dir, e.name)); if (s < 0) return -1L; t += s } else t += e.size }
            return t
        }
        val total: suspend () -> Long = total@{
            var t = 0L
            for (n in list) {
                val item = src.items.firstOrNull { it.name == n }
                t += if (src.src == "local") LocalOps.size(java.io.File(join(src.path, n))) else if (srcArc != null) srcArc.sizeOf(join(src.path, n)) else if (item != null && !item.isDir) item.size else if (item != null) { val s = rsize(join(src.path, n)); if (s < 0) return@total -1L; s } else return@total -1L
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

    /** Свойства по полному пути на телефоне (строки «домика»). */
    fun propsPath(path: String) = scope.launch {
        val lp = FmPane(-8, "/sdcard")
        dlg = FmDlg.Props(str(R.string.fm_props_title), runCatching { FmIo.props(lp, path) }.getOrElse { FmIo.nice(it) })
    }

    fun props(p: FmPane, names: List<String>) = scope.launch {
        val sb = StringBuilder()
        for (n in names.take(5)) {
            sb.append(runCatching { FmIo.props(p, if (n.isEmpty()) p.path else join(p.path, n)) }.getOrElse { FmIo.nice(it) }).append("\n\n")
        }
        dlg = FmDlg.Props(str(R.string.fm_props_title), sb.toString().trim())
    }

    /**
     * 19.11.2026: поиск идёт внутри папки и всех вложенных (глубина до 8; в сети и облаке до 5). Из корня «Источники» (там у панели нет своей папки,
     * раньше искалось по давно забытому пути и всегда «0») это последняя раскрытая папка дерева, а если ничего не раскрыто, память телефона.
     * В окне результата подписано, где именно искали.
     */
    fun search(q0: String) = scope.launch {
        if (q0.isBlank()) return@launch
        val p0 = cur
        val dst = if (p0.root) FmRootCfg.destFor(p0) else null
        val sp = if (p0.root) (dst?.pane() ?: FmPane(9, "/sdcard")) else p0
        val sroot = if (p0.root) (dst?.path ?: "/sdcard") else p0.path
        val where = if (p0.root) (dst?.label() ?: "/sdcard") else pathLabel(p0, p0.src, p0.path)
        say(str(R.string.fm_search_busy, where))
        val r = runCatching { FmIo.search(sp, sroot, q0) }
        r.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it; say(FmIo.nice(it)); return@launch }
        dlg = FmDlg.SearchResult(r.getOrDefault(emptyList()), where, if (p0.root) (dst ?: FmRootCfg.Dst("local", null, "/sdcard", "L|/sdcard")) else null)
    }

    /** Открыть найденное: из обычной панели как раньше; из корня «Источники» сначала выйти из корня и открыть нужный источник (телефон, ADB, сеть, облако). */
    fun openFound(d: FmDlg.SearchResult, path: String) {
        val p = cur
        val dst = d.dst
        if (dst == null) { goTo(p, path, false); return }
        val dir = (if (dst.net != null) runCatching { dst.pane().smb?.parent(path.trimEnd('/')) }.getOrNull() else null) ?: path.trimEnd('/').substringBeforeLast('/', "/").ifEmpty { "/" }
        val name = path.trimEnd('/').substringAfterLast('/')
        when {
            dst.net != null -> { fmLeaveRoot(p); connectNet(p, dst.net, dir) }
            dst.src == "adb" -> { fmLeaveRoot(p); if (p.src != "adb") setSource(p, "adb"); load(p, dir, pick = name) }
            else -> { fmLeaveRoot(p); if (p.src != "local") setSource(p, "local"); load(p, dir, remember = false, pick = name) }
        }
    }

    fun goTo(p: FmPane, fullPath: String, isDir: Boolean) {
        if (isDir) load(p, fullPath) else load(p, fullPath.substringBeforeLast('/', "/").ifEmpty { "/" }, pick = fullPath.substringAfterLast('/'))
    }

    private fun mimeOf(ext: String): String = FileMime.of(ext)

    private fun viewLocal(f: java.io.File, ext: String, pkg: String? = null, mime: String? = null): Boolean {
        // 26.11.2026: файл на карте памяти, который программе напрямую не читается: отдаётся через выданный доступ к карте
        val uri = if (!f.canRead() && SafAccess.needs(f.path) && SafAccess.covered(f.path)) SafAccess.documentUri(f.path)
            else androidx.core.content.FileProvider.getUriForFile(sctx, sctx.packageName + ".files", f)
        val m = mime ?: if (ext == "apk") "application/vnd.android.package-archive" else FileMime.ofName(f.name, "*/*")
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, m)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        pkg?.let { if (it.contains(Char(47))) i.setClassName(it.substringBefore(Char(47)), it.substringAfter(Char(47))) else i.setPackage(it) }
        val ok = runCatching { sctx.startActivity(i) }.isSuccess
        if (!ok && pkg == null) say(str(R.string.fm_no_app))
        return ok
    }

    /**
     * 10.1: открыть видео или музыку из сетевого источника или облака без копирования: файл отдаётся плееру по адресу http://127.0.0.1:порт/токен/имя ([StreamServer]).
     * null: потоковая отдача невозможна (не видео и не звук, источник не умеет читать с середины, размер неизвестен); тогда файл копируется, как раньше.
     */
    private suspend fun viewStream(p: FmPane, it: FmItem, f: String, pkg: String?, mime: String?): Boolean? {
        val fs = p.smb ?: return null
        if (!fs.rangeRead) return null
        val cat = openCat(it.ext)?.takeIf { c -> c == "video" || c == "audio" } ?: return null
        if (mime != null && mime != "*/*" && !mime.startsWith("video/") && !mime.startsWith("audio/")) return null
        val (m, httpM) = StreamServer.mimeFor(it.ext, cat)
        val intentMime = if (mime != null && mime != "*/*") mime else m
        val size = if (it.size > 0) it.size else withContext(Dispatchers.IO) { runCatching { fs.stat(f)?.size }.getOrNull() } ?: 0L
        if (size <= 0) return null
        val url = StreamServer.register(sctx, fs, f, size, m, it.name, httpM)
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(url), intentMime)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("title", it.name)
        pkg?.let { k -> if (k.contains(Char(47))) i.setClassName(k.substringBefore(Char(47)), k.substringAfter(Char(47))) else i.setPackage(k) }
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
            "smb" -> {
                // 10.1: видео и музыка из сети играют сразу (потоковая отдача по Range), без копирования целиком; остальное и источники без Range: копия во временную папку
                viewStream(p, it, f, pkg, mime)?.let { r -> return r }
                lastFetchMsg = null
                val tmp = tmpCopy(p, it.name); val ok = fetchWithProgress(p, f, it.name, it.size, tmp); ok && viewLocal(tmp, it.ext, pkg, mime)
            }
            else -> false
        }
    }

    /** «Открыть в системе»: выбранное приложение или (pkg = null) системное окно выбора. */
    fun openWith(p: FmPane, it: FmItem, pkg: String?, mime: String? = null): kotlinx.coroutines.Job = scope.launch { if (!openWithPkg(p, it, pkg, mime)) say(str(R.string.fm_no_app)) }

    fun openArchiveFor(p: FmPane, it: FmItem) { openArchive(p, it.name) }

    /** Установка пакета из нескольких частей (APKS, APKM, XAPK, ZIP с APK и OBB) через ADB/root: части и OBB подбираются под устройство. */
    fun installBundleViaAdb(p: FmPane, it: FmItem, quiet: Boolean = true, confirmed: Boolean = false): kotlinx.coroutines.Job = scope.launch {
        val silent = quiet && Adb.isConnected && !FmIo.remote
        if (!silent && !confirmed) {
            // 10.2: без ADB и root то же окно «Установка» со сведениями о версиях, затем сессия с подтверждением системы
            val srcC = fileForInstall(p, it.name) ?: run { say(failMsg()); return@launch }
            val listC = withContext(Dispatchers.IO) { runCatching { Archives.extractApks(sctx, android.net.Uri.fromFile(srcC)) }.getOrDefault(emptyList()) }
            if (listC.isEmpty()) { dlg = FmDlg.InstallFail(str(R.string.inst_failed)); return@launch }
            val metasC = withContext(Dispatchers.IO) { listC.associate { a -> a.name to runCatching { ApkInfo.readFor(sctx, a) }.getOrNull() } }
            dlg = FmDlg.InstallConfirm(listC, metasC) { _ -> installBundleViaAdb(p, it, quiet, confirmed = true) }
            return@launch
        }
        if (!silent && !ensureInstallPermission { installBundleViaAdb(p, it, quiet, confirmed = true) }) return@launch
        if (silent) {
            // тихая установка по ADB/root: сначала подтверждение с версиями, потом окно с полоской хода (части и OBB, «n из N»), итог внутри окна
            val srcF = fileForInstall(p, it.name) ?: run { say(failMsg()); return@launch }
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
        val srcF2 = fileForInstall(p, it.name) ?: run { say(failMsg()); return@launch }
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
        else if (r != null && r.busy) say(str(R.string.ii_busy))   // 22.11.2026: тот же пакет уже устанавливается, второе окно не открывается
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
    /** Наборы файлов, для которых сессионная установка уже запущена (до конца установки): повторный вызов игнорируется сразу, без ожидания. */
    private val sessionStarting = java.util.Collections.synchronizedSet(HashSet<String>())

    fun installFilesViaSession(files: List<java.io.File>) {
        if (files.isEmpty()) return
        val fk = files.joinToString("|") { it.path }
        if (!sessionStarting.add(fk)) { say(str(R.string.ii_busy)); return }
        scope.launch {
          try {
            if (!ensureInstallPermission { installFilesViaSession(files) }) return@launch
            // 22.11.2026 (18.0): тот же пакет и файл уже устанавливается: второй сессии и второго системного окна «Установить» нет
            val key = withContext(Dispatchers.IO) { SessionInstall.guardKey(sctx, files) }
            if (InstallGuard.isActive(key)) { say(str(R.string.ii_busy)); return@launch }
            // сразу окно хода «Идёт установка…» (передача файла, ожидание ответа в системном окне, итог): повторно нажимать не нужно
            val run = InstRun()
            run.total = 1; run.index = 1; run.running = true; run.phase = 1
            run.all = files.sumOf { it.length() }.coerceAtLeast(1L)
            run.name = withContext(Dispatchers.IO) {
                runCatching {
                    val pm = sctx.packageManager
                    val ai = pm.getPackageArchiveInfo(files.first().path, 0)?.applicationInfo?.also { it.sourceDir = files.first().path; it.publicSourceDir = files.first().path }
                    ai?.let { pm.getApplicationLabel(it).toString() }
                }.getOrNull()
            } ?: files.first().name
            InstBg.launch(sctx, run) { dlg = FmDlg.InstallRunDlg(it) }
            val r = SessionInstall.install(sctx, files) { ph, d, a -> run.phase = ph; run.done = d; run.all = a.coerceAtLeast(1L) }
            if (r.busy) { if (dlg is FmDlg.InstallRunDlg && (dlg as FmDlg.InstallRunDlg).run === run) dlg = null; run.finished = true; run.running = false; say(str(R.string.ii_busy)); return@launch }
            if (r.ok) {
                // 23.11.2026 (18.0): итог с названием и версией (раньше у установки сессией версии не было)
                val vi = withContext(Dispatchers.IO) { runCatching { sctx.packageManager.getPackageArchiveInfo(files.first().path, 0) }.getOrNull() }
                val vc = vi?.let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() } ?: 0L
                run.ok = 1; run.okItems.add(InstRun.OkItem(run.name, vi?.versionName.orEmpty(), vc))
            }
            else { run.bad = 1; run.fails.add(run.name + ": " + (if (r.cancelled) str(R.string.fm_cancelled) else r.message)) }
            run.done = run.all; run.running = false; run.finished = true
          } finally { sessionStarting.remove(fk) }
        }
    }

    /** Наш способ установки APK: тихо через ADB/root, а без них сессией PackageInstaller с подтверждением системы. */
    fun installViaAdb(p: FmPane, it: FmItem, quiet: Boolean = true): kotlinx.coroutines.Job = scope.launch {
        val f = join(p.path, it.name)
        // 17.0 (код 83): рядом лежат другие части того же пакета (base.apk и split_*.apk): ставится набор одной сессией, а не один файл
        if (p.src == "local" && it.ext == "apk") {
            val set = withContext(Dispatchers.IO) { runCatching { ApkSets.setAround(sctx, java.io.File(f)) }.getOrNull() }
            if (set != null) { installSet(set, quiet); return@launch }
        }
        if (quiet && Adb.isConnected && !FmIo.remote && p.src == "local") {
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
        if (p.src != "smb") say(str(R.string.inst_installing))   // из сети карточка «Загрузка» с процентами сама появляется
        val file = fileForInstall(p, it.name) ?: run { say(failMsg()); return@launch }
        val af = ApkFile(it.name, android.net.Uri.fromFile(file), file.length(), file)
        val meta = withContext(Dispatchers.IO) { runCatching { ApkInfo.readFor(sctx, af) }.getOrNull() }
        dlg = FmDlg.InstallConfirm(listOf(af), mapOf(af.name to meta)) { wipe ->
            scope.launch {
                if (quiet && Adb.isConnected && !FmIo.remote) {
                    val run = InstRun()
                    InstBg.launch(sctx, run) { dlg = FmDlg.InstallRunDlg(it) }
                    installWithProgress(sctx, listOf(af), mapOf(af.name to meta), run, wipe)
                } else installFilesViaSession(listOf(file))
            }
        }
    }

    /** Установка набора из частей APK одной папки: окно «Установка» с названием, версиями и числом частей, затем ход (ADB/root) или сессия системы. */
    private suspend fun installSet(set: ApkFile, quiet: Boolean) {
        val meta = withContext(Dispatchers.IO) { runCatching { ApkInfo.readFor(sctx, set) }.getOrNull() }
        val metas = mapOf(set.name to meta)
        dlg = FmDlg.InstallConfirm(listOf(set), metas) { wipe ->
            scope.launch {
                if (quiet && Adb.isConnected && !FmIo.remote) {
                    val run = InstRun()
                    InstBg.launch(sctx, run) { dlg = FmDlg.InstallRunDlg(it) }
                    installWithProgress(sctx, listOf(set), metas, run, wipe)
                } else installFilesViaSession(set.parts.orEmpty())
            }
        }
    }

    /** Встроенный просмотрщик или редактор для типа файла независимо от запомненного приложения. */
    fun openBuiltinForce(p: FmPane, it: FmItem) {
        val f = join(p.path, it.name)
        DocKind.of(it.ext)?.let { k -> openDoc(sctx, p, it, k); return }
        if (it.ext.isEmpty()) { openDoc(sctx, p, it, "text"); return }   // 9.3: файл без расширения открывается в Блокноте
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
            if (it.ext == "apk" || ApkInstall.isBundle(it.ext)) { ApkInstall.start(p, it); return@launch }
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

    // ---------- перепаковка APK (10.0) ----------
    internal suspend fun fetchTo(p: FmPane, path: String, dest: File) {
        when (p.src) {
            "local" -> File(path).copyTo(dest, true)
            "adb" -> Adb.pull(path, dest)
            else -> (p.smb ?: error(str(R.string.fm_err_unsupported))).copyTo(path, dest)
        }
    }

    /** Папка результата: локальная папка другой панели, иначе папка этой панели, иначе «Загрузки» телефона. Второе значение: панель, которую нужно обновить. */
    private fun repackDest(p: FmPane, o: FmPane): Pair<File, FmPane?> {
        return when {
            o.src == "local" && !o.root && o.path.startsWith("/") -> File(o.path) to o
            p.src == "local" && p.path.startsWith("/") -> File(p.path) to p
            else -> File("/sdcard/Download") to null
        }
    }

    /** Есть части: окно выбора формата; один APK: копия как есть без вопросов (подпись цела). */
    fun repackStart(spec: RepackSpec) { if (spec.hasSplits) dlg = FmDlg.Repack(spec) else repackRun(spec, RFormat.AS_IS, RSign.OWN) }

    fun repackRun(spec: RepackSpec, fmt: RFormat, sign: RSign) = scope.launch {
        var out: File? = null
        val ok = runJob(str(R.string.rp_job), listOf(spec.title)) { _, _ ->
            try { out = Repack.build(sctx, spec.load(), spec.title, spec.destDir, fmt, sign); null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { e.message ?: e.javaClass.simpleName }
            finally { spec.cleanup() }
        }
        if (ok) out?.let { say(str(R.string.rp_saved, it.name)) }
        spec.after()
    }

    private fun tmpDir() = File(sctx.cacheDir, "repack_" + System.nanoTime()).also { it.mkdirs() }

    /** Пункт «Перепаковать…» для файла .apks/.xapk/.apkm: части достаются из архива без изменений. */
    fun repackFile(p: FmPane, f: FmItem, other: FmPane = panes[1 - p.id]) {
        val (dd, dp) = repackDest(p, other)
        val full = join(p.path, f.name)
        val tmp = tmpDir()
        repackStart(RepackSpec(f.name.substringBeforeLast('.'), dd, dd.path, true,
            load = {
                val z = if (p.src == "local") File(full) else File(tmp, f.name).also { fetchTo(p, full, it) }
                Repack.extractParts(z, File(tmp, "parts"))
            },
            cleanup = { tmp.deleteRecursively() },
            after = { dp?.let { load(it, remember = false) } }))
    }

    /** Установленная программа из списка приложений панели: части берутся из PackageManager (читаются на месте). */
    fun repackApp(p: FmPane, other: FmPane, pkg: String, label: String) {
        val (dd, dp) = repackDest(p, other)
        val a = runCatching { sctx.packageManager.getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES) }.getOrNull() ?: return
        val files = (listOfNotNull(a.sourceDir) + a.splitSourceDirs.orEmpty()).map { File(it) }.filter { it.isFile }
        repackStart(RepackSpec(label.ifBlank { pkg }, dd, dd.path, files.size > 1, load = { files }, after = { dp?.let { load(it, remember = false) } }))
    }

    /** Копирование набора APK-частей (base + config.*) из папки: возвращает название и пути частей; null, если это не набор. */
    private suspend fun repackSet(src: FmPane, names: List<String>): Pair<String, List<String>>? {
        val folder: String
        val apks: List<String>
        if (names.size == 1) {
            val item = FmIo.list(src, src.path).firstOrNull { it.name == names[0] } ?: return null
            if (!item.isDir) return null
            folder = join(src.path, names[0])
            apks = FmIo.list(src, folder).filter { !it.isDir && it.name.lowercase().endsWith(".apk") }.map { it.name }
        } else {
            if (names.any { !it.lowercase().endsWith(".apk") }) return null
            folder = src.path; apks = names
        }
        if (apks.size < 2 || apks.none { Bundles.isBasePart(it) } || apks.none { Bundles.isConfigPart(it) }) return null
        // у папок /data/app/~~хэш/пакет-хэш== имя пакета перед последним тире
        val last = folder.trimEnd('/').substringAfterLast('/')
        val title = if (Regex(".+-[A-Za-z0-9_\\-]{22}==").matches(last)) last.substringBeforeLast('-') else last.ifBlank { "app" }
        return title to apks.map { join(folder, it) }
    }

    /** true: проверка набора APK-частей запущена; окно выбора формата или обычное копирование ([rerun]) начнётся после неё. */
    private fun repackAsk(src: FmPane, dst: FmPane, names: List<String>, rerun: () -> Unit): Boolean {
        if (dst.src != "local" || !dst.path.startsWith("/")) return false
        scope.launch {
            val set = runCatching { repackSet(src, names) }.getOrNull()
            if (set == null) { rerun(); return@launch }
            val tmp = tmpDir()
            val dd = File(dst.path)
            dlg = FmDlg.Repack(RepackSpec(set.first, dd, dd.path, true,
                load = { set.second.map { path -> File(tmp, path.substringAfterLast('/')).also { fetchTo(src, path, it) } } },
                cleanup = { tmp.deleteRecursively() },
                after = { load(dst, remember = false) }))
        }
        return true
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
/**
 * 18.11.2026 (владелица): системная настройка «Масштаб анимации» у неё урезана (`animator_duration_scale`), и Compose по умолчанию
 * её слушает — обычный `tween`/`AnimatedContent` в таком случае доигрывает почти мгновенно. Переключение панелей «Файлов» оказалось
 * слишком похоже на «эффекта нет» именно из-за этого (другие переходы в программе тяжелее по отрисовке, поэтому там смена кадра всё
 * равно заметна даже при урезанном масштабе). [FmFullMotion] — фиксированный масштаб 1 (игнорирует системную настройку), используется
 * через `withContext(FmFullMotion) { ... }` вокруг вызова `animateTo` — трогает только эту конкретную анимацию, не весь телефон.
 * Не private: тот же приём понадобился и для других переходов программы (не только «Файлов») — см. MainActivity.kt, FmRoot.kt.
 */
object FmFullMotion : androidx.compose.ui.MotionDurationScale {
    override val scaleFactor: Float = 1f
}
object FmMetrics {
    val OUTER_PAD = 2.dp          // отступ раскладки от краёв окна
    val STRIP_END = 6.dp          // дополнительный отступ колонки от правого края (кнопки не лезут под жесты)
    val STRIP_W = 54.dp           // ширина колонки (10.11.2026: было 52, кнопки уже; значок и подпись внутри не менялись)
    val BTN_W = 52.dp             // ширина квадратной кнопки (прижата к левому краю колонки; было 50)
    /** Расстояние от правого края окна до оси кнопок правой колонки. */
    val stripCenterFromRight get() = OUTER_PAD + STRIP_END + (STRIP_W - BTN_W) + BTN_W / 2
}

@Composable
fun FileManagerScreen(vm: MainViewModel, onConnectTab: () -> Unit = {}) {
    val ctx = LocalContext.current
    Fm.goConnect = onConnectTab
    // T3 (19.11.2026): запомненная активная панель известна ещё до первого кадра (Fm.init идёт позже): при запуске нет «переезда» с левой панели на правую
    if (!Fm.inited) Fm.active = ctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE).getInt("active_pane", 0).coerceIn(0, 1)
    val reorderCtx = LocalContext.current
    androidx.activity.compose.BackHandler(enabled = FmButtons.reorder != null) { FmButtons.cancelReorder(reorderCtx) }
    FmIo.remote = vm.remoteHost != null
    LaunchedEffect(Unit) { Fm.init(ctx); Fm.panes.forEach { if (it.error != null) Fm.load(it, remember = false) } }
    // 6.0: стоит старый модуль облаков (1.0), есть новый (1.1, меньше места): один раз за запуск предлагаем обновить обычной установкой (окно системы подтверждает установку)
    // 7.6: вопрос об обновлении модуля и само обновление теперь в корне экрана (ModuleHost), при запуске программы на любой вкладке
    Fm.sctx = ctx
    remember { Fm.screenShownAt = System.currentTimeMillis(); FocusRestore.keysAtShow = FocusRestore.keys; 0 }
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
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
        // пульт: «Меню» или «Info» при отмеченном переводит фокус на нижнюю панель значков (с неё «Назад» возвращает на список)
        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && FmRootCfg.sel.isNotEmpty() && (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU || e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_INFO)) runCatching { FmRootCfg.barFocus.requestFocus() }.isSuccess else false
    }.onPreviewKeyEvent { e ->
        // Tab или Ctrl+стрелки влево/вправо: переключение панелей (пульт, клавиатура)
        val k = e.key
        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && (k == Key.Tab || (e.isCtrlPressed && (k == Key.DirectionLeft || k == Key.DirectionRight)))) {
            Fm.active = if (k == Key.DirectionLeft) 0 else if (k == Key.DirectionRight) 1 else 1 - Fm.active; true
        } else if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && (k == Key.F5 || k == Key.F6)) {
            // как в двухпанельных менеджерах: F5 копировать, F6 переместить выделенное в другую панель
            val names = Fm.targets(Fm.cur)
            if (names.isEmpty()) Fm.say(Fm.str(R.string.fm_select_first)) else RootSelOps.toOther(Fm.cur, names, k == Key.F6)
            true
        } else false
    }) {
        val land = isLandscapeBox(maxWidth, maxHeight)
        val actions = rememberFmActions()
        if (land) {
            // 12.0 (владелица, 18.11.2026): «сделай так, как на приставке, полностью» — ландшафт телефона теперь один в один раскладка
            // приставки (сетка кнопок в два столбца, тот же FmNarrow, тот же расчёт размера), без отдельной реализации FmSideColumn.
            Row(Modifier.fillMaxSize().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // колонка кнопок всегда посередине между окнами, настройка «Справа/Слева» не действует (как раньше было на приставке)
                FmPaneView(Fm.panes[0], Modifier.weight(1f).fillMaxHeight(), volsRow = false)
                // кнопки квадратные; размер подбирается по высоте экрана так, чтобы колонка заполняла её без пустоты снизу
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxHeight()) {
                    // 19.11.2026: если даже при минимальной кнопке (40 dp) все ряды не помещаются, лишние кнопки уходят в «Ещё» (maxMain), «Ещё» видна при любой высоте
                    val scaleF = FmSideScale.factor()
                    val fitRows = ((maxHeight.value - 39f) / (44f * scaleF)).toInt().coerceAtLeast(1)
                    val mainAll = actions.size
                    val maxMain = 2 * fitRows - 1
                    val gridRows = (mainAll + 1) / 2 + 1   // кнопки панели по две в ряд и «Ещё» отдельным рядом на две колонки (24.11.2026, слово владелицы)
                    // 20.11.2026: кнопки не мельчают ниже обычного размера (до 8 рядов, как на приставке по умолчанию): если кнопок больше, панель прокручивается, «⋯» закреплена внизу
                    val maxRows = minOf(fitRows, 8)
                    val moreInGrid = gridRows <= maxRows
                    val rows = if (moreInGrid) gridRows else maxRows   // размер кнопок считается по полному набору: скрытые кнопки не растягивают остальные
                    val cap = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.07f).dp
                    // 18.11.2026 (владелица, телефон-ландшафт обрезало низ сетки и терялась «Ещё»): точный расчёт вместо прежней
                    // оценки под приставку — переключатель (своя высота sz) + отступ 3dp + rows рядов сетки по sz + зазоры 4dp между
                    // ними должны ровно уместиться в доступную высоту ЭТОГО экрана, какие бы ни были пропорции (не приставки).
                    val sz = ((maxHeight - 3.dp - 4.dp * (rows - 1)) / (rows + 1)).coerceIn(40.dp, 78.dp * scaleF).let { if (it > cap * scaleF) cap * scaleF else it }
                    // 18.11.2026 (владелица: «кнопки должны быть одинаковыми, а не маленькими — можно менять размер кнопки со стрелкой»):
                    // высота переключателя больше не часть той же формулы — она просто донимает остаток места под сеткой (с полом,
                    // чтобы не исчезла), а сама сетка всегда получает честный sz, даже если из-за crop/min он не совпал бы с «остатком».
                    val gridH = sz * rows + 4.dp * (rows - 1)
                    val switchH = (maxHeight - 3.dp - gridH).coerceAtLeast(32.dp)
                    FmNarrow(actions, Modifier.width(sz * 2 + 4.dp).fillMaxHeight(), sz, switchH, maxMain, moreInGrid)
                }
                FmPaneView(Fm.panes[1], Modifier.weight(1f).fillMaxHeight(), volsRow = false)
            }
        } else {
            // портрет: на экране одна панель; вкладки сверху и узкая полоса слева переключают панели мгновенно, свайп по списку тоже
            Column(Modifier.fillMaxSize().padding(horizontal = FmMetrics.OUTER_PAD), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                // 12.0 (владелица, 18.11.2026): убрана отдельная верхняя строка «окошко + стрелка» — окно «Источники» (его собственная
                // шапка в FmPaneView) поднято до самого верха экрана, а кнопка-стрелка переехала в бок-панель FmStrip сверху.
                // 10.0 (fm1, владелица): в портрете колонка кнопок у края активной панели: правая панель — справа, левая — слева (на приставке как было, слева);
                // у края справа отступ от системного жеста «назад»
                val stripRight = !Marq.tv && Fm.active == 0   // 10.2 (слово владелицы): зеркально, активно левое окно: колонка справа, активно правое: слева
                // 26.11.2026 (слово владелицы 19.11): панель едет за пальцем. panePos: 0 видна левая панель, 1 правая, между ними обе частично
                val panePos = remember { androidx.compose.animation.core.Animatable(Fm.active.toFloat()) }
                var paneDrag by remember { mutableStateOf(false) }
                val paneScope = androidx.compose.runtime.rememberCoroutineScope()
                // 19.11.2026: свайп влево/вправо переключает панели из ЛЮБОГО места ряда (список, «Источники», боковая полоса), а не только с
                // обычного списка файлов (раньше с экрана «Источники» свайпа не было — «обратно только кнопкой, а она наверху»). Порядок «панель/полоса»
                // меняется через key(): полоса переезжает на другую сторону без пересоздания; отступ справа постоянный — ширина панели не прыгает.
                Row(Modifier.weight(1f).fillMaxWidth().padding(end = FmMetrics.STRIP_END).fmPaneSwipe(panePos, paneScope) { paneDrag = it }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (if (stripRight) listOf(0, 1) else listOf(1, 0)).forEach { slot ->
                        androidx.compose.runtime.key(slot) {
                            if (slot == 1) FmStripBox(actions, Modifier.fillMaxHeight()) else {
                    run {
                        // 19.11.2026: обе панели существуют ВСЕГДА, каждая на своём фиксированном месте (key): состояние (прокрутка, список, фокус)
                        // не теряется и ничто не пересоздаётся при переключении — раньше после конца анимации панель создавалась заново и «передёргивалась».
                        // Скрытая панель уезжает за край (touch и рисование ей не достаются); при переходе — прежняя анимация переезда.
                        LaunchedEffect(Fm.active, paneDrag) {
                            if (paneDrag) return@LaunchedEffect   // палец держит панель: прежняя доводка этим отменена
                            val to = Fm.active.toFloat()
                            if (panePos.value != to) {
                                // 18.11.2026 (владелица): общий выключатель «Анимация переходов»: выключен, переход мгновенный, без анимации вообще.
                                if (UiAnim.on) kotlinx.coroutines.withContext(FmFullMotion) {
                                    panePos.animateTo(to, tween((280 * kotlin.math.abs(to - panePos.value)).toInt().coerceAtLeast(90), easing = FastOutSlowInEasing))
                                } else panePos.snapTo(to)
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                            for (k in 0..1) androidx.compose.runtime.key(k) {
                                val visible by remember { androidx.compose.runtime.derivedStateOf { kotlin.math.abs(k - panePos.value) < 0.999f } }
                                Box(
                                    Modifier.fillMaxSize()
                                        .layout { m, c ->
                                            val pl = m.measure(c)
                                            layout(pl.width, pl.height) { if (visible) pl.place(0, 0) else pl.place(-100000, 0) }
                                        }
                                        .graphicsLayer { translationX = (k - panePos.value) * size.width },
                                ) { FmPaneView(Fm.panes[k], Modifier.fillMaxSize()) }
                            }
                        }
                    }
                            }
                        }
                    }
                }
            }
        }
        MiniPlayer(Modifier.align(Alignment.BottomCenter))
        FmOverlay(Modifier.align(Alignment.BottomCenter).padding(bottom = if (AudioState.active) 84.dp else 0.dp))
    }
    FmDialogs()
}

class FmAction(val icon: ImageVector, val label: Int, val enabled: Boolean = true, val tint: Color? = null, val run: () -> Unit)

/** Какие кнопки боковой панели скрыты (7.0): настройка пользователя «Кнопки боковой панели...»; ключ кнопки: имя строки её названия. */
object FmButtons {
    var hidden by mutableStateOf(setOf<String>())
    private var loaded = false
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE)
    // 18.11.2026: ручной размер кнопок боковой колонки (sideSize/sideDp) убран — везде (портрет, ландшафт, приставка) размер
    // считается автоматически по месту, как было на приставке.
    fun load(ctx: Context) { if (loaded) return; loaded = true; hidden = sp(ctx).getStringSet("hidden_btns", emptySet())?.toSet() ?: emptySet(); loadOrder(ctx); migrate = !sp(ctx).getBoolean("panel_v69", false) }
    /**
     * 20.11.2026 (слово владелицы): на панели ровно те кнопки, которых нет в [hidden]; остальные добавляются через «⋯» → «Добавить кнопку».
     * По умолчанию на панели основные кнопки (как было при установке); первый запуск 15.0: всё, что раньше жило под «Ещё», уходит в список «Добавить кнопку».
     */
    private var migrate = false
    fun applyDefaults(ctx: Context, allKeys: List<String>, mainKeys: Set<String>) {
        if (!migrate) return
        migrate = false
        hidden = hidden + allKeys.filter { it !in mainKeys }
        sp(ctx).edit().putStringSet("hidden_btns", hidden.toSet()).putBoolean("panel_v69", true).apply()
    }
    /** 24.11.2026: после восстановления копии перечитать кнопки из настроек (иначе в памяти оставался старый набор и перетирал восстановленный). */
    fun reload(ctx: Context) { loaded = false; load(ctx) }
    fun hide(ctx: Context, key: String) { if (key !in hidden) toggle(ctx, key) }
    fun show(ctx: Context, key: String) { if (key in hidden) toggle(ctx, key) }
    fun key(ctx: Context, label: Int): String {
        val n = runCatching { ctx.resources.getResourceEntryName(label) }.getOrDefault(label.toString())
        return if (n == "fm_mode_tree" || n == "fm_mode_list") "fm_mode" else n
    }
    fun toggle(ctx: Context, key: String) { hidden = if (key in hidden) hidden - key else hidden + key; sp(ctx).edit().putStringSet("hidden_btns", hidden.toSet()).apply() }
    fun reset(ctx: Context) { hidden = emptySet(); order = emptyList(); migrate = true; sp(ctx).edit().remove("hidden_btns").remove("btn_order").remove("panel_v69").apply() }

    // ---- порядок кнопок и перестановка (7.5) ----
    /** Сохранённый порядок ключей кнопок; кнопки, которых в нём нет, идут следом в обычном порядке. */
    var order by mutableStateOf(listOf<String>())
    /** Ключ кнопки, которую сейчас переставляют (режим включается долгим нажатием; на пульте удержанием ОК). */
    var reorder by mutableStateOf<String?>(null)
    private var snapshot: List<String> = emptyList()
    var startedAt = 0L
    private var lastAll: List<String> = emptyList()
    fun loadOrder(ctx: Context) {
        var l = sp(ctx).getString("btn_order", "")?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()
        // 13.0: порядок, сохранённый до 13.0, шёл по полному списку и перемешивал основные кнопки с остальными; теперь первые N в колонке видны, поэтому один раз
        // переносим основные кнопки (в их прежнем порядке) вперёд, остальные следом: колонка выглядит так же, как раньше
        if (l.isNotEmpty() && !sp(ctx).getBoolean("btn_order_v13", false)) {
            val mk = fmMainLabels.map { key(ctx, it) }.toSet()
            l = l.filter { it in mk } + l.filter { it !in mk }
            sp(ctx).edit().putString("btn_order", l.joinToString(",")).putBoolean("btn_order_v13", true).apply()
        }
        order = l
    }
    /** Применяет порядок к списку действий (стабильно). */
    fun ordered(ctx: Context, all: List<FmAction>): List<FmAction> {
        val keys = all.map { key(ctx, it.label) }
        // 13.0: порядок по умолчанию: сначала основные кнопки колонки, потом остальные («Ещё»); сохранённый порядок владельца сильнее
        val idx = all.indices.sortedBy { if (all[it].label in fmMainLabels) 0 else 1 }
        val keysD = idx.map { keys[it] }
        lastAll = keysD
        val pos = order.withIndex().associate { it.value to it.index }
        return idx.withIndex().sortedBy { pos[keysD[it.index]] ?: (1000 + it.index) }.map { all[it.value] }
    }
    /** 13.0: сколько кнопок сейчас видно в колонке (остальные в «Ещё»); считает колонка, окно «Боковая панель» рисует по нему черту. 0: колонка ещё не рисовалась. */
    var shownCount by mutableStateOf(0)
    /** 13.0: «Сбросить порядок»: порядок кнопок по умолчанию (показ/скрытие кнопок не трогается). */
    fun resetOrder(ctx: Context) { order = emptyList(); sp(ctx).edit().remove("btn_order").apply() }
    /** 13.0: границы кнопок на экране (для перетаскивания): колонка и окно «Ещё» ведут свои карты. */
    val bounds = HashMap<String, androidx.compose.ui.geometry.Rect>()
    val dlgBounds = HashMap<String, androidx.compose.ui.geometry.Rect>()
    /** Когда закончилось перетаскивание: нажатие сразу после него не запускает действие кнопки. */
    @Volatile var dragEndAt = 0L
    /** Все кнопки колонки по порядку (видимые и под «Ещё»); колонка обновляет при рисовании. */
    @Volatile var stripKeys: List<String> = emptyList()
    private fun insertNear(ctx: Context, key: String, target: String, after: Boolean) {
        val l = fullOrder().toMutableList()
        if (key !in l || target !in l) return
        l.remove(key)
        l.add(l.indexOf(target) + (if (after) 1 else 0), key)
        save(ctx, l)
    }
    /** Кнопку из колонки в «Ещё»: она становится первой под «Ещё», следующая кнопка занимает её место в колонке. [ks]: видимые ключи по порядку, [nShow]: сколько видно в колонке. */
    fun toRestStart(ctx: Context, key: String, ks: List<String>, nShow: Int) {
        val k2 = ks.filter { it != key }
        if (key !in ks || nShow < 1 || k2.size < nShow) return
        if (ks.indexOf(key) >= nShow) return
        val l = fullOrder().toMutableList()
        if (key !in l) return
        l.remove(key)
        l.add(l.indexOf(k2[nShow - 1]) + 1, key)
        save(ctx, l)
    }
    /**
     * 13.0 (слово владелицы: «менять местами кнопки удержанием и перетаскиванием»): палец над кнопкой с ключом T:
     * в одной области (обе в колонке или обе в «Ещё») кнопка встаёт на место T, остальные сдвигаются; между колонкой и «Ещё» кнопки меняются местами
     * (вытесненная уходит в другую область); на кнопку «Ещё» кнопка из колонки уходит под «Ещё».
     */
    fun dragOver(ctx: Context, key: String, p: androidx.compose.ui.geometry.Offset, ks: List<String>, nShow: Int, bnds: Map<String, androidx.compose.ui.geometry.Rect>) {
        val hit = bnds.entries.firstOrNull { it.value.contains(p) }?.key ?: return
        if (hit == key) { dropTarget = null; return }
        // «Ещё» и кнопка другой области (колонка/«Ещё»): действие выполняется, только когда палец отпущен над ней ([drop]), чтобы можно было выбрать нужную, не задевая соседние
        if (hit == "fm_more") { dropTarget = hit; return }
        val i = ks.indexOf(key); val j = ks.indexOf(hit)
        if (i < 0 || j < 0) return
        if ((i < nShow) != (j < nShow)) dropTarget = hit else { dropTarget = null; insertNear(ctx, key, hit, i < j) }
    }
    /** Над какой кнопкой другой области сейчас палец (выполняется при отпускании). */
    @Volatile var dropTarget: String? = null
    fun drop(ctx: Context, key: String, ks: List<String>, nShow: Int) {
        val t = dropTarget; dropTarget = null
        if (t == null) return
        if (t == "fm_more") hide(ctx, key) else swap(ctx, key, t)   // 20.11.2026: кнопку бросили на «⋯»: убрана с панели (вернуть: «⋯» → «Добавить кнопку»)
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
    fun startReorder(key: String) { dropTarget = null; snapshot = fullOrder(); reorder = key; startedAt = System.currentTimeMillis() }
    fun finishReorder() { reorder = null }
    fun cancelReorder(ctx: Context) { if (reorder != null) { save(ctx, snapshot); reorder = null } }
}



@Composable
private fun rememberFmActions(): List<FmAction> {
    val c = LocalContext.current
    remember { FmButtons.load(c); 0 }
    val allA = rememberFmActionsAll()
    FmButtons.applyDefaults(c, allA.map { FmButtons.key(c, it.label) }, fmMainLabels.map { FmButtons.key(c, it) }.toSet())
    val hid = FmButtons.hidden
    return FmButtons.ordered(c, allA).filter { FmButtons.key(c, it.label) !in hid }
}

@Composable
fun rememberFmActionsAll(): List<FmAction> {
    val p = Fm.cur; val o = Fm.other
    // 9.3: в режиме «Дерево» отмеченное лежит в FmRootCfg.sel, а не в p.selected: кнопки колонки («Копия», «Перенос», «Удалить», «Снять») были серыми
    // 14.0: признаки «что-то отмечено» через derivedStateOf: колонка кнопок и обе панели перерисовываются, только когда признак изменился (первая отметка, снятие последней),
    // а не при КАЖДОЙ галочке (раньше набор читался здесь напрямую, и каждая галочка пересоздавала всю колонку кнопок и экран «Файлов»)
    val rootSel by remember(p) { androidx.compose.runtime.derivedStateOf { p.root && FmRootCfg.sel.values.any { it.ti == null && it.fi == null } } }
    val selAny by remember(p) { androidx.compose.runtime.derivedStateOf { p.selected.isNotEmpty() } }
    val renameOk by remember(p) { androidx.compose.runtime.derivedStateOf { p.selected.size == 1 && p.selected.none { '/' in it } } }
    val sel = selAny || rootSel
    val sctx = LocalContext.current
    return listOf(
        FmAction(Icons.Filled.ArrowUpward, R.string.fm_up) { Fm.up(p) },
        FmAction(Icons.Filled.Refresh, R.string.fm_refresh) { if (p.root) FmRootCfg.refreshAll(Fm.sctx) else Fm.load(p, remember = false) },
        FmAction(Icons.Filled.ContentCopy, R.string.fm_b_copy, sel) { if (rootSel) RootSelOps.transfer(p, false) else RootSelOps.fromList(p, false) },
        FmAction(Icons.Filled.DriveFileMove, R.string.fm_b_move, sel) { if (rootSel) RootSelOps.transfer(p, true) else RootSelOps.fromList(p, true) },
        FmAction(Icons.Filled.DeleteOutline, R.string.fm_delete, sel, tint = deleteRed()) { if (rootSel) RootSelOps.deleteFiles() else Fm.askDelete(p, Fm.targets(p)) },
        FmAction(Icons.Filled.Edit, R.string.fm_b_rename, renameOk) { Fm.dlg = FmDlg.Rename(p, Fm.targets(p).first()) },
        FmAction(Icons.Filled.CreateNewFolder, R.string.fm_new_folder, Fm.canCreate(p) || p.smb is CloudFs) { Fm.dlg = FmDlg.NewFolder() },
        FmAction(Icons.Filled.Description, R.string.fm_new_file, Fm.canCreate(p)) { Fm.dlg = FmDlg.NewFile() },
        FmAction(Icons.Filled.FolderZip, R.string.fm_new_zip, Fm.canCreateZip(p)) { Fm.dlg = FmDlg.NewZip() },
        FmAction(Icons.Filled.SelectAll, R.string.fm_select_all) { Fm.selectAll(p) },
        FmAction(Icons.Filled.Deselect, R.string.fm_select_none, sel) { p.selected = emptySet(); if (rootSel) FmRootCfg.sel.clear() },
        FmAction(Icons.Filled.FlipToBack, R.string.fm_select_invert) { Fm.invert(p) },
        FmAction(Icons.Filled.FilterAlt, R.string.fm_select_mask) { Fm.dlg = FmDlg.Mask() },
        FmAction(Icons.Filled.Sort, R.string.fm_sort) { Fm.dlg = FmDlg.View() },
        FmAction(Icons.Filled.SwapVert, R.string.sort_title) { Fm.active = p.id; Fm.dlg = FmDlg.Sort(p) },
        FmAction(if (Fm.showHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, R.string.fm_hidden, tint = if (Fm.showHidden) Color(0xFF2E9E4F) else Color(0xFFF57C00)) { Fm.setHidden(!Fm.showHidden) },
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

/**
 * Название или строка сведений в списках: переносится максимум на три строки, дальше многоточие; бегущей строки нет (11.0).
 * Полный текст: долгое нажатие, меню действий, «Свойства». Параметры [active], [wrap], [names], [tapRun] оставлены для совместимости вызовов.
 */
@Composable
fun MqText(text: String, active: Boolean, modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
           lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified, fontWeight: FontWeight? = null,
           color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified, fontFamily: FontFamily? = null, wrap: Boolean = false, names: Boolean = false, tapRun: Boolean = true) {
    Text(if (names) NameWrap.fmt(text) else text, modifier, fontSize = fontSize, lineHeight = lineHeight, fontWeight = fontWeight, color = color, fontFamily = fontFamily, maxLines = 3, overflow = TextOverflow.Ellipsis)
}

/** 14.0: набор считается один раз для телефона и один раз для приставки (раньше каждое обращение строило пять множеств, а обращений на каждую перекомпоновку колонки кнопок десятки). */
private val fmMainLabelsCache = arrayOfNulls<Set<Int>>(2)
/** Главные кнопки узкой колонки (остальные в «Ещё»). */
private val fmMainLabels: Set<Int> get() = fmMainLabelsCache[if (Marq.tv) 1 else 0] ?: ((setOf(R.string.fm_up, R.string.fm_b_copy, R.string.fm_b_move, R.string.fm_delete, R.string.fm_b_rename,
    R.string.fm_new_folder, R.string.fm_new_file, R.string.fm_select_all, R.string.fm_sort, R.string.fm_search, R.string.fm_storage) -
    // 9.2: на приставке шапка панели в три строки, поэтому «Перенести» и «Места» уходят в «Ещё» (функции остаются там)
    (if (Marq.tv) setOf(R.string.fm_b_move, R.string.fm_storage, R.string.fm_b_copy) else emptySet())) +
    // 24.11.2026: по умолчанию на приставке 14 кнопок + «Ещё» на всю ширину = 8 рядов без прокрутки (Копия, ZIP-архив и Инверсия остаются в «Ещё»: слово владелицы про Копию)
    // на приставке экран высокий и колонка внизу пустовала: добавляем ещё две частые кнопки (семь рядов помещаются без прокрутки)
    (if (Marq.tv) setOf(R.string.fm_refresh, R.string.fm_play, R.string.fm_wifi_server, R.string.fm_hidden, R.string.sort_title, R.string.fm_select_none) else emptySet())).also { fmMainLabelsCache[if (Marq.tv) 1 else 0] = it }

/** Телефон (не ТВ) в ландшафте: места мало, поэтому всё уплотняется. */
@Composable
fun rememberLandPhone(): Boolean {
    val c = androidx.compose.ui.platform.LocalConfiguration.current
    return !Marq.tv && c.screenWidthDp > c.screenHeightDp
}

/**
 * 12.0 (владелица: «сложно переключаться между панелями в ландшафте и на приставке, иконка маленькая, нет кнопки») — одна крупная
 * кнопка-переключатель активной панели вместо текста с названием панели и числами («Левая/Правая», «Объектов», «Выбрано» убраны отовсюду,
 * это дублировало рамку вокруг активной панели). Стрелка разворачивается туда, куда переключит нажатие; подложка — нейтральная, чуть темнее фона.
 */
@Composable
internal fun FmSwitchPaneButton(modifier: Modifier = Modifier, iconSize: androidx.compose.ui.unit.Dp = 21.dp) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    // не .luminance() — в Port.kt своя приватная версия того же имени, компилятор путает её с androidx; считаем яркость сами той же формулой
    val bg = MaterialTheme.colorScheme.background
    val dark = (0.2126f * bg.red + 0.7152f * bg.green + 0.0722f * bg.blue) < 0.5f
    val purple = if (dark) Color(0xFFC4B5FD) else Color(0xFF6D28D9)
    // 21.0: на панели своего цвета стрелка получает тонкую контрастную обводку (чёрную у светлой, белую у тёмной)
    val panelSlot = LocalPanelSlot.current
    val outlineArrow = panelSlot >= 0 && PanelStyle.mode(panelSlot) != PanelStyle.NOW
    val outlineC = if (dark) Color.Black else Color.White
    val shape = RoundedCornerShape(10.dp)
    val desc = stringResource(R.string.fm_switch_pane)
    Box(
        // обводка как у окон-панелей (FmPaneView): тонкая и тусклая в покое, ярче и толще при фокусе пульта/клавиатуры
        modifier.clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .14f))
            .border(if (focused) 3.dp else 1.dp, if (focused) purple else MaterialTheme.colorScheme.outline.copy(alpha = .5f), shape)
            .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, onClickLabel = desc) { Fm.active = 1 - Fm.active }
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        // 24.11.2026 (владелица: «сделай стрелку жирнее, а то сильно тонкая»): стрелка рисуется сама, штрих в 14% размера (у значка Material около 8%)
        val toRight = Fm.active == 0
        androidx.compose.foundation.Canvas(Modifier.size(iconSize)) {
            val w = size.width; val h = size.height; val sw = w * 0.15f; val cy = h / 2f
            val tail = if (toRight) w * 0.10f else w * 0.90f
            val tip = if (toRight) w * 0.90f else w * 0.10f
            val back = if (toRight) -w * 0.34f else w * 0.34f
            if (outlineArrow) {
                val ow = sw + w * 0.07f
                drawLine(outlineC, androidx.compose.ui.geometry.Offset(tail, cy), androidx.compose.ui.geometry.Offset(tip, cy), strokeWidth = ow, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(outlineC, androidx.compose.ui.geometry.Offset(tip, cy), androidx.compose.ui.geometry.Offset(tip + back, cy - h * 0.34f), strokeWidth = ow, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(outlineC, androidx.compose.ui.geometry.Offset(tip, cy), androidx.compose.ui.geometry.Offset(tip + back, cy + h * 0.34f), strokeWidth = ow, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            drawLine(purple, androidx.compose.ui.geometry.Offset(tail, cy), androidx.compose.ui.geometry.Offset(tip, cy), strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(purple, androidx.compose.ui.geometry.Offset(tip, cy), androidx.compose.ui.geometry.Offset(tip + back, cy - h * 0.34f), strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(purple, androidx.compose.ui.geometry.Offset(tip, cy), androidx.compose.ui.geometry.Offset(tip + back, cy + h * 0.34f), strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/** 18.11.2026: FmSideColumn удалён — ландшафт телефона теперь использует FmNarrow (как на приставке), см. вызов isLandscapeBox выше. */

/** Квадратная кнопка действия: значок по центру, подпись под ним (до двух строк, узкий шрифт) внутри квадрата. На ТВ у фокуса рамка. */
@Composable
private fun FmSquareButton(a: FmAction, size: androidx.compose.ui.unit.Dp, icon: androidx.compose.ui.unit.Dp, label: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier, reorderKeys: List<String>? = null, cols: Int = 1) {
    val alpha = if (a.enabled) 1f else .38f
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    // 18.11.2026 (владелица: «на приставке норм, на телефоне сильно скруглённые»): радиус был фиксированным (10dp) независимо
    // от размера кнопки — на крупной кнопке приставки (~85dp) это выглядело тонко, на мелкой телефонной (~48dp) непропорционально
    // круглым. Радиус теперь в доле от стороны кнопки, той же пропорции, что уже хорошо смотрелась на приставке (10/85 ≈ 12%).
    val sh = RoundedCornerShape(size * 0.12f)
    val bctx = androidx.compose.ui.platform.LocalContext.current
    val reorderFocus = remember { FocusRequester() }
    val bkey = FmButtons.key(bctx, a.label)
    val moving = FmButtons.reorder == bkey
    var menuOpen by remember { mutableStateOf(false) }
    var moved by remember { mutableStateOf(false) }
    val reorderOn = FmButtons.reorder != null
    val canReorder = reorderKeys != null && bkey in reorderKeys
    val hint = stringResource(R.string.sb_reorder_hint)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val keysNow = androidx.compose.runtime.rememberUpdatedState(reorderKeys)
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
                // 20.11.2026: пульт: кнопка «Меню» на кнопке панели открывает то же меню, что долгое нажатие («Убрать с панели»)
                if (nk.keyCode == android.view.KeyEvent.KEYCODE_MENU && nk.action == android.view.KeyEvent.ACTION_DOWN && FmButtons.reorder == null) { menuOpen = true; return@onPreviewKeyEvent true }
                if (FmButtons.reorder == bkey) {
                    // режим перестановки с пульта: стрелки двигают кнопку, ОК подтверждает
                    if (nk.action == android.view.KeyEvent.ACTION_DOWN) when (nk.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> { FmButtons.move(bctx, bkey, reorderKeys!!, -cols); return@onPreviewKeyEvent true }
                        // 13.0: с последней видимой кнопки вниз (на «Ещё») кнопка уходит под «Ещё»
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { val ks = reorderKeys!!.filter { it != "fm_more" }; if (ks.lastOrNull() == bkey) { FmButtons.toRestStart(bctx, bkey, FmButtons.stripKeys, FmButtons.shownCount); FmButtons.finishReorder() } else FmButtons.move(bctx, bkey, reorderKeys, cols); return@onPreviewKeyEvent true }
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { if (cols > 1) FmButtons.move(bctx, bkey, reorderKeys!!, -1); return@onPreviewKeyEvent true }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { if (cols > 1) FmButtons.move(bctx, bkey, reorderKeys!!, 1); return@onPreviewKeyEvent true }
                    }
                    if (ok) { if (nk.action == android.view.KeyEvent.ACTION_UP && System.currentTimeMillis() - FmButtons.startedAt > 700) FmButtons.finishReorder(); return@onPreviewKeyEvent true }
                    false
                } else if (ok && FmButtons.reorder == null && nk.action == android.view.KeyEvent.ACTION_DOWN && nk.repeatCount >= 1) {
                    FmButtons.startReorder(bkey); Fm.say(hint); true      // удержание ОК на кнопке
                } else false
            }
            .onGloballyPositioned { FmButtons.bounds[bkey] = it.boundsInRoot() }
            // 13.0: удержание кнопки «берёт» её (подсветка и вибрация), палец тянет её на другое место, отпустил: порядок сохранён
            .pointerInput(bkey, canReorder) {
                if (canReorder) detectDragGesturesAfterLongPress(
                    onDragStart = { FmButtons.startReorder(bkey); moved = false; haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) },
                    onDrag = { change, _ ->
                        change.consume()
                        if (!moved && (change.position - change.previousPosition).getDistance() >= 0f && FmButtons.bounds[bkey]?.let { !it.contains(it.topLeft + change.position) } == true) moved = true
                        val me = FmButtons.bounds[bkey]
                        if (me != null) FmButtons.dragOver(bctx, bkey, me.topLeft + change.position, FmButtons.stripKeys, FmButtons.shownCount, FmButtons.bounds)
                    },
                    onDragEnd = { FmButtons.drop(bctx, bkey, FmButtons.stripKeys, FmButtons.shownCount); FmButtons.dragEndAt = System.currentTimeMillis(); FmButtons.finishReorder(); if (!moved) menuOpen = true },
                    onDragCancel = { FmButtons.dropTarget = null; FmButtons.dragEndAt = System.currentTimeMillis(); FmButtons.finishReorder() },
                )
            }
            .hoverable(hover).combinedClickable(enabled = true, onClick = {
                if (System.currentTimeMillis() - FmButtons.dragEndAt < 500) return@combinedClickable
                if (FmButtons.reorder != null && reorderKeys != null) {
                    if (FmButtons.reorder == bkey) { if (System.currentTimeMillis() - FmButtons.startedAt > 700) FmButtons.finishReorder() }
                    else if (bkey in reorderKeys) { FmButtons.swap(bctx, FmButtons.reorder!!, bkey); FmButtons.finishReorder() }
                } else if (a.enabled) a.run()
            }).padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (menuOpen) LDropdownMenu(true, { menuOpen = false }) {
            DropdownMenuItem({ Text(stringResource(R.string.sb_remove), fontSize = 14.sp) }, { menuOpen = false; FmButtons.hide(bctx, bkey) })
            DropdownMenuItem({ Text(stringResource(R.string.sb_reposition), fontSize = 14.sp) }, { menuOpen = false; FmButtons.startReorder(bkey); Fm.say(hint) })
        }
        val cs = CustomIcons.spec("s:" + FmButtons.key(androidx.compose.ui.platform.LocalContext.current, a.label))
        val tintC = (a.tint ?: MaterialTheme.colorScheme.primary).copy(alpha = alpha)
        // 21.0 (владелица: «иконки на кнопках боковой панели маленькие, увеличь, но не за счёт текста»): значок рисуется крупнее (x1,3) в том же месте кнопки,
        // слот под значок и расчёт шрифта подписи остаются прежними, поэтому текст не меняется; лишнее уходит вверх в свободное поле кнопки
        val bigIcon = icon * 1.3f
        Box(Modifier.size(icon), contentAlignment = Alignment.Center) {
            Box(Modifier.requiredSize(bigIcon).offset(y = -(icon * 0.10f)), contentAlignment = Alignment.Center) {
                if (cs != null) CustomIconGlyph(cs, bigIcon, tintC) else PanelIcon(a.icon, null, tintC, bigIcon)
            }
        }
        // 10.11.2026: длинное слово («Сортировка», «Инверсия») не должно рваться посреди слова: шрифт слегка уменьшается так, чтобы слово влезло (9 букв при 7,5 sp в кнопке 44 dp влезают)
        val txt = stringResource(a.label)
        // подпись всегда по центру и без разрыва слова: шрифт подбирается по самому длинному слову (измерение, а не оценка по числу букв)
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val dens = androidx.compose.ui.platform.LocalDensity.current
        val baseStyle = androidx.compose.material3.LocalTextStyle.current
        val tvNow = Marq.tv
        // 14.0: размер подписи считается один раз для сочетания (текст, размеры, плотность, стиль), а не на КАЖДУЮ перекомпоновку кнопки: подбор шрифта это десятки измерений текста
        // на кнопку (на приставке ~140 мс на каждую галочку, пока колонка кнопок перерисовывалась вместе со списком)
        val lblSize = remember(txt, size, icon, label, dens.density, dens.fontScale, tvNow, baseStyle) {
            val availPx = with(dens) { (size - (if (tvNow) 12.dp else 8.dp)).toPx() }
            val words = txt.split(' ').filter { it.isNotEmpty() }
            // 10.2 (58): подпись одного физического размера на всех экранах и при любом масштабе шрифта (как при масштабе 1,3: на A14 она «нормальная»)
            var fs = label.value * 1.3f / dens.fontScale
            val minFs = fs * (if (tvNow) 0.3f else 0.6f)   // 20.11.2026: при шрифте 1,3 «Текстовый» рвался посреди слова: нижняя граница ниже
            while (fs > minFs && words.any { w -> measurer.measure(androidx.compose.ui.text.AnnotatedString(w), baseStyle.copy(fontSize = fs.sp, fontFamily = CondFont)).size.width > availPx * (if (tvNow) 0.8f else 0.9f) }) fs -= 0.25f
            // 18.11.2026 (владелица): предыдущая правка межстрочного интервала убрала некрасивый зазор, но при переносе в 2 строки («Wi-Fi
            // сервер» и подобные) нижняя строка физически обрезалась квадратной кнопкой (у неё свой clip) — ширинного подбора fs было
            // недостаточно, нужна ещё проверка по ВЫСОТЕ: значок + обе строки подписи должны реально поместиться в кнопку.
            val vBudgetPx = with(dens) { (size - 4.dp).toPx() }
            val iconPx = with(dens) { icon.toPx() }
            val wMaxPx = availPx.toInt().coerceAtLeast(1)
            fun measuredTextHeightPx(v: Float): Float = measurer.measure(
                androidx.compose.ui.text.AnnotatedString(txt),
                baseStyle.copy(fontSize = v.sp, lineHeight = v.sp, fontFamily = CondFont),
                constraints = androidx.compose.ui.unit.Constraints(maxWidth = wMaxPx),
            ).size.height.toFloat()
            while (fs > minFs && iconPx + measuredTextHeightPx(fs) > vBudgetPx) fs -= 0.25f
            fs.sp
        }
        Text(txt, Modifier.fillMaxWidth(), fontSize = lblSize, lineHeight = lblSize, maxLines = 2, overflow = TextOverflow.Clip, fontFamily = CondFont,
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

/**
 * 13.0: окно «Ещё»: сверху кнопки, которые сейчас на панели, ниже кнопки под «Ещё»; удержание кнопки и перетаскивание меняют порядок
 * (в своей области кнопка встаёт на место, остальные сдвигаются; между панелью и «Ещё» кнопки меняются местами), порядок сохраняется сразу.
 */
@Composable
private fun FmMoreDialog(d: FmDlg.More, close: () -> Unit) {
    val ctx = LocalContext.current
    remember { FmButtons.load(ctx); 0 }
    val all = FmButtons.ordered(ctx, rememberFmActionsAll()).filter {
        FmButtons.key(ctx, it.label) !in FmButtons.hidden && (!d.excl || (it.label != R.string.ft_menu && it.label != R.string.fm_history && it.label != R.string.fm_swap && it.label != R.string.fm_same))
    }
    val n = FmButtons.shownCount.coerceIn(0, all.size)
    val keys = all.map { FmButtons.key(ctx, it.label) }
    val keysNow = androidx.compose.runtime.rememberUpdatedState(keys)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { FmButtons.dlgBounds.clear(); FmButtons.finishReorder() } }
    @Composable
    fun Modifier.moreDrag(k: String): Modifier = this
        .onGloballyPositioned { FmButtons.dlgBounds[k] = it.boundsInRoot() }
        .pointerInput(k) {
            detectDragGesturesAfterLongPress(
                onDragStart = { FmButtons.startReorder(k); haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) },
                onDrag = { change, _ ->
                    change.consume()
                    val me = FmButtons.dlgBounds[k]
                    if (me != null) FmButtons.dragOver(ctx, k, me.topLeft + change.position, keysNow.value, FmButtons.shownCount, FmButtons.dlgBounds)
                },
                onDragEnd = { FmButtons.drop(ctx, k, keysNow.value, FmButtons.shownCount); FmButtons.dragEndAt = System.currentTimeMillis(); FmButtons.finishReorder() },
                onDragCancel = { FmButtons.dropTarget = null; FmButtons.dragEndAt = System.currentTimeMillis(); FmButtons.finishReorder() },
            )
        }
    val prim = MaterialTheme.colorScheme.primary
    LAlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.fm_more)) },
        text = {
            // выделение текста (долгое нажатие в окнах) отключено: удержание нужно для перетаскивания кнопок
            androidx.compose.foundation.text.selection.DisableSelection {
            Column(Modifier.heightIn(max = 460.dp).barScroll(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.sb_more_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                if (n > 0) {
                    Text(stringResource(R.string.sb_on_panel), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = prim)
                    all.take(n).chunked(5).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { a ->
                                val k = FmButtons.key(ctx, a.label)
                                androidx.compose.runtime.key(k) {
                                    val moving = FmButtons.reorder == k
                                    val csM = CustomIcons.spec("s:" + k)
                                    val tintM = (a.tint ?: prim).copy(alpha = if (a.enabled) 1f else .38f)
                                    Box(
                                        Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(prim.copy(alpha = if (moving) .38f else .12f))
                                            .then(if (moving) Modifier.border(2.dp, prim, RoundedCornerShape(8.dp)) else Modifier)
                                            .moreDrag(k)
                                            .clickable(enabled = a.enabled) { if (System.currentTimeMillis() - FmButtons.dragEndAt > 500) { close(); a.run() } }
                                            .semantics { contentDescription = ctx.getString(a.label) },
                                        contentAlignment = Alignment.Center,
                                    ) { if (csM != null) CustomIconGlyph(csM, 24.dp, tintM) else Icon(a.icon, null, Modifier.size(24.dp), tint = tintM) }
                                }
                            }
                        }
                    }
                    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp))
                }
                Text(stringResource(R.string.sb_more_below), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = prim)
                all.drop(n).forEach { a ->
                    val k = FmButtons.key(ctx, a.label)
                    androidx.compose.runtime.key(k) {
                        val moving = FmButtons.reorder == k
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (moving) prim.copy(alpha = .30f) else Color.Transparent)
                                .moreDrag(k)
                                .clickable(enabled = a.enabled) { if (System.currentTimeMillis() - FmButtons.dragEndAt > 500) { close(); a.run() } }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val csM = CustomIcons.spec("s:" + k)
                            val tintM = (a.tint ?: prim).copy(alpha = if (a.enabled) 1f else .38f)
                            if (csM != null) CustomIconGlyph(csM, 24.dp, tintM) else Icon(a.icon, null, Modifier.size(24.dp), tint = tintM)
                            Text(stringResource(a.label), Modifier.padding(start = 14.dp), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (a.enabled) 1f else .38f))
                        }
                    }
                }
                TextButton({ FmButtons.resetOrder(ctx) }) { Text(stringResource(R.string.sb_reset_order)) }
            }
            }
        },
        confirmButton = { TextButton(close) { Text(stringResource(R.string.fm_close)) } })
}

/** Узкая колонка действий в ландшафте: тянется на всю высоту, крупные кнопки в три столбца поровну по высоте, остальное в «Ещё». */
@Composable
private fun FmNarrow(actions: List<FmAction>, modifier: Modifier, tvSize: androidx.compose.ui.unit.Dp? = null, switchH: androidx.compose.ui.unit.Dp? = null, maxMain: Int = Int.MAX_VALUE, moreInGrid: Boolean = false) {
    // 13.0 (просьба владелицы и mikolkavod): видны первые N кнопок в порядке владельца (N не больше числа основных и того, что влезает по высоте), остальные в «Ещё» в том же порядке
    val ctx0n = androidx.compose.ui.platform.LocalContext.current
    // 20.11.2026 (4PDA, wad72: «не лучше ли оставить боковую панель с прокруткой?»): настройка «Боковая панель: прокрутка / «Ещё»»; при прокрутке все кнопки в колонке, «Ещё» нет
    val nShow = actions.size
    val main = actions   // 24.11.2026: «Сортировка» стоит внизу рядом с «Ещё» (заполняет пустую клетку)
    val rest = emptyList<FmAction>()
    androidx.compose.runtime.SideEffect { FmButtons.shownCount = nShow; FmButtons.stripKeys = actions.map { FmButtons.key(ctx0n, it.label) } }
    val moreAct = FmAction(Icons.Filled.MoreHoriz, R.string.fm_more) { Fm.dlg = FmDlg.AddButton() }
    val items = if (moreInGrid) main + moreAct else main   // кнопок немного и всё влезает: «⋯» стоит в сетке следом за ними; иначе закреплена внизу
    val narrowScroll = androidx.compose.foundation.rememberScrollState()
    PanelColumn(1, modifier, Arrangement.spacedBy(3.dp)) {
        // 12.0 (владелица, приставка/пульт): вместо текстовой шапки «Левая/Правая», «Объектов», «Выбрано» — крупная кнопка-переключатель.
        // Владелица уточнила: кнопка ОТДЕЛЬНЫЙ зафиксированный элемент сверху, не часть прокручиваемой колонки ниже (не должна ехать
        // вместе с кнопками при прокрутке). 18.11.2026: высота кнопки больше НЕ привязана жёстко к размеру обычных кнопок — её может
        // подвинуть вызывающая сторона (switchH), чтобы сетка обычных квадратных кнопок не мельчала ради неё.
        val switchSize = tvSize ?: if (Marq.tv) 72.dp else 78.dp
        val switchIcon = tvSize?.let { it * 0.46f } ?: if (Marq.tv) 34.dp else 37.dp
        FmSwitchPaneButton(Modifier.width(switchSize * 2 + 4.dp).height(switchH ?: switchSize), iconSize = switchIcon)
        // 18.11.2026 (владелица): без прокрутки вообще (ни полосой, ни свайпом/колесом) — лишние кнопки уходят только через «Ещё»,
        // колонка статична; размер sz (tvSize) подобран снаружи так, чтобы вся сетка без остатка заполняла высоту.
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(narrowScroll), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            (if (moreInGrid) main.chunked(2) + listOf(listOf(moreAct)) else main.chunked(2)).forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { r.forEach { a -> androidx.compose.runtime.key(FmButtons.key(androidx.compose.ui.platform.LocalContext.current, a.label)) { val rk = if (a.label == R.string.fm_more) null else (main + rest).map { FmButtons.key(androidx.compose.ui.platform.LocalContext.current, it.label) }; val wide = r.size == 1 && a.label == R.string.fm_more   /* 24.11.2026: «Ещё» одна в последнем ряду сетки на приставке растянута на две колонки; телефон без изменений */
                    if (tvSize != null) FmSquareButton(a, tvSize, tvSize * 0.46f, (tvSize.value * 0.165f).coerceIn(9f, 12f).sp, if (wide) Modifier.width(tvSize * 2 + 4.dp) else Modifier, reorderKeys = rk, cols = 2) else if (Marq.tv) FmSquareButton(a, 72.dp, 34.dp, 11.5.sp, if (wide) Modifier.width(148.dp) else Modifier, reorderKeys = rk, cols = 2) else FmSquareButton(a, 78.dp, 37.dp, 12.sp, reorderKeys = rk, cols = 2) } } }
            }
          }
          // 20.11.2026: «⋯» (добавить кнопку) закреплена под прокручиваемыми кнопками и всегда видна
          // 24.11.2026 (владелица: «как прокручивать пультом кнопки, не переходя на «Ещё»? переходить только когда прокрутил до конца»): закреплённая «Ещё» получает фокус, только когда список кнопок уже прокручен до конца
          if (!moreInGrid) Row(Modifier.focusProperties { canFocus = !narrowScroll.canScrollForward }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              // 24.11.2026 (владелица): на приставке закреплённая «Ещё» растянута на две колонки, без пустой клетки рядом; на телефоне как была
              if (tvSize != null) FmSquareButton(moreAct, tvSize, tvSize * 0.46f, (tvSize.value * 0.165f).coerceIn(9f, 12f).sp, Modifier.width(tvSize * 2 + 4.dp), reorderKeys = null, cols = 2)
              else if (Marq.tv) FmSquareButton(moreAct, 72.dp, 34.dp, 11.5.sp, Modifier.width(148.dp), reorderKeys = null, cols = 2)
              else FmSquareButton(moreAct, 78.dp, 37.dp, 12.sp, reorderKeys = null, cols = 2)
          }
        }
    }
}

@Composable
private fun FmChip(text: String, click: () -> Unit) {
    Text(text, Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer).clickable { click() }.padding(horizontal = 8.dp, vertical = 2.dp),
        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSecondaryContainer)
}

/**
 * Портрет: узкая полоса действий (справа или слева по настройке «Панели управления») и активная панель. 18.11.2026 (владелица,
 * «сделай как на приставке»): без прокрутки вообще (ни полосой, ни свайпом/колесом) — лишние кнопки уходят только через «Ещё»;
 * кнопки квадратные и заполняют колонку без остатка (размер [sz] считается снаружи, в [FmStripBox], по доступной высоте);
 * кнопка-переключатель панели отдельным зафиксированным элементом сверху, высотой в две обычные кнопки (с тем же шагом).
 * Выбор набора кнопок (показывать/скрывать) и перестановка перетаскиванием не тронуты: actions уже отфильтрован и упорядочен
 * вызывающей стороной (rememberFmActions), а reorderKeys считается так же, как в [FmNarrow].
 */
@Composable
private fun FmStrip(actions: List<FmAction>, modifier: Modifier, sz: androidx.compose.ui.unit.Dp, switchH: androidx.compose.ui.unit.Dp, maxMain: Int = Int.MAX_VALUE) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FmSwitchPaneButton(Modifier.fillMaxWidth().height(switchH), iconSize = sz * 0.44f)
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val ctx0 = androidx.compose.ui.platform.LocalContext.current
            // 10.11.2026 (владелица): «Корзина файлов» и «История» убраны из полосы: обе есть в «домике» (корзина и «Недавние файлы»); «Обмен» и «Как здесь» тоже убраны («не нужны»)
            val visible = actions.filter { it.label != R.string.ft_menu && it.label != R.string.fm_history && it.label != R.string.fm_swap && it.label != R.string.fm_same }
            // 20.11.2026 (слово владелицы): на панели только выбранные кнопки; их больше, чем влезает: панель сама прокручивается, а «⋯» (добавить кнопку) закреплена под ними
            androidx.compose.runtime.SideEffect { FmButtons.shownCount = visible.size; FmButtons.stripKeys = visible.map { FmButtons.key(ctx0, it.label) } }
            val rk = visible.map { FmButtons.key(ctx0, it.label) }
            val stripScroll = androidx.compose.foundation.rememberScrollState()
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(stripScroll), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    visible.forEach { a -> androidx.compose.runtime.key(FmButtons.key(ctx0, a.label)) { FmSquareButton(a, sz, sz * 0.4f, (sz.value * 0.155f).sp, reorderKeys = rk) } }
                }
                FmSquareButton(FmAction(Icons.Filled.MoreHoriz, R.string.fm_more) { Fm.dlg = FmDlg.AddButton() }, sz, sz * 0.4f, (sz.value * 0.155f).sp, reorderKeys = null)
            }
        }
    }
}

/** 20.11.2026: масштаб кнопок боковой панели по настройке. */
object FmSideScale { fun factor(): Float = when (UiPrefs.sideScale) { 1 -> 1.15f; 2 -> 1.3f; else -> 1f } }

/** Считает размер квадратной кнопки снаружи FmStrip (по доступной высоте, как на приставке) и отдаёт ей готовую ширину/sz. */
@Composable
private fun FmStripBox(actions: List<FmAction>, modifier: Modifier) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        // 19.11.2026: если даже при минимальной кнопке (40 dp) все кнопки не помещаются (S9 720x1480: «Ещё» обрезалась), лишние уходят в «Ещё» (maxMain)
        // 20.11.2026: «Масштаб кнопок панели» (авто / +15% / +30%) и «Компактные отступы» (освободилось место: верхняя граница размера кнопки 64 -> 72 dp при авто)
        val scaleF = FmSideScale.factor()
        val fitRows = ((maxHeight.value - 84f) / (44f * scaleF)).toInt().coerceAtLeast(1)
        val onPanel = rememberFmActions().count { it.label != R.string.ft_menu && it.label != R.string.fm_history && it.label != R.string.fm_swap && it.label != R.string.fm_same }
        val rows = minOf(onPanel + 1, fitRows, 11)   // +1 «⋯»; один столбец — без деления на 2; больше 11 кнопок: прокрутка (размер кнопок не мельчает)
        val gap = 4.dp
        // высота = переключатель (2×sz + gap) + отступ + rows×sz + (rows-1) gap, без остатка снизу
        val sz = ((maxHeight - gap * (rows + 1)) / (rows + 2)).coerceIn(40.dp, if (UiPrefs.compactTree && UiPrefs.sideScale == 0) 72.dp else 64.dp * scaleF)
        // 18.11.2026 (владелица: «кнопки должны быть одинаковыми, не мелкими — можно менять кнопку со стрелкой»): высота
        // переключателя — честный остаток места (обычно ровно 2×sz+gap, как просила владелица), а не жёсткая формула, из-за
        // которой sz пришлось бы ужимать, если арифметика где-то не сойдётся день в день.
        val gridH = sz * rows + gap * (rows - 1)
        val switchH = (maxHeight - gap - gridH).coerceAtLeast(sz)
        PanelBox(1, Modifier.width(sz).fillMaxHeight()) { FmStrip(actions, Modifier.width(sz).fillMaxHeight(), sz, switchH, rows - 1) }
    }
}

/** 10.0: крупная яркая стрелка вниз с чёрной обводкой, слегка пульсирует; общая для подсказок. */
@Composable
internal fun HintArrow(sizeDp: Int, modifier: Modifier = Modifier) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "hint")
    val s by t.animateFloat(0.88f, 1.14f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(550), androidx.compose.animation.core.RepeatMode.Reverse), label = "s")
    androidx.compose.foundation.Canvas(modifier.size(sizeDp.dp)) {
        val w = size.width; val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * .5f, h); lineTo(w, h * .52f); lineTo(w * .7f, h * .52f); lineTo(w * .7f, 0f); lineTo(w * .3f, 0f); lineTo(w * .3f, h * .52f); lineTo(0f, h * .52f); close()
        }
        withTransform({ scale(s, s, androidx.compose.ui.geometry.Offset(w / 2, h / 2)) }) {
            drawPath(path, androidx.compose.ui.graphics.Color.Black, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 7.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
            drawPath(path, androidx.compose.ui.graphics.Color(0xFFFFEA00))
        }
    }
}

/** Свайп влево/вправо переключает панели: влево — вторая, вправо — первая; не мешает вертикальной прокрутке и нажатиям (работает, пока дочерние элементы не забрали жест). */
private fun Modifier.fmPaneSwipe(
    pos: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    scope: kotlinx.coroutines.CoroutineScope, dragging: (Boolean) -> Unit,
): Modifier = this.pointerInput(Unit) {
    var total = 0f
    var cur = 0f
    val speed = androidx.compose.ui.input.pointer.util.VelocityTracker()
    detectHorizontalDragGestures(
        onDragStart = { total = 0f; cur = pos.value; speed.resetTracking(); dragging(true) },
        onDragEnd = {
            val vx = speed.calculateVelocity().x
            val fling = 500.dp.toPx()
            // анимация выключена: как раньше, по пройденному пути; включена: быстрый взмах решает сам, иначе ближняя сторона
            val to = when {
                !UiAnim.on -> if (total < -110f) 1 else if (total > 110f) 0 else Fm.active
                vx < -fling && total < 0f -> 1
                vx > fling && total > 0f -> 0
                else -> if (cur >= 0.5f) 1 else 0
            }
            if (to != Fm.active) Fm.active = to
            dragging(false)
        },
        onDragCancel = { dragging(false) },
    ) { change, dx ->
        total += dx
        speed.addPosition(change.uptimeMillis, androidx.compose.ui.geometry.Offset(total, 0f))
        if (UiAnim.on) {
            cur = (cur - dx / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
            val v = cur
            scope.launch { pos.snapTo(v) }
        }
    }
}

@Composable
private fun FmPaneView(p: FmPane, modifier: Modifier, volsRow: Boolean = true) {
    val active = Fm.active == p.id
    val prim = MaterialTheme.colorScheme.primary
    val sameSpot = p.scrollPath == p.path
    val state = rememberLazyListState(if (sameSpot) p.scrollIdx else 0, if (sameSpot) p.scrollOff else 0)
    val rvSt = remember { RvState() }
    val rvOn = FmPaneRv.ON
    fun curFirst() = if (rvOn) rvSt.first else state.firstVisibleItemIndex
    fun curOff() = if (rvOn) rvSt.firstOffset else state.firstVisibleItemScrollOffset
    suspend fun goTo(i: Int, o: Int = 0) { if (rvOn) rvSt.scrollToItem(i, o) else state.scrollToItem(i, o) }
    if (rvOn) LaunchedEffect(Unit) {
        var t = 0
        while (t < 20 && (rvSt.rv?.adapter?.itemCount ?: 0) == 0) { androidx.compose.runtime.withFrameNanos { }; t++ }
        if (sameSpot) rvSt.scrollToItem(p.scrollIdx, p.scrollOff)
    }
    LaunchedEffect(state) {
        (if (rvOn) kotlinx.coroutines.flow.flow { while (true) { emit(Triple(p.path, rvSt.first, rvSt.firstOffset)); delay(150) } }.distinctUntilChanged() else androidx.compose.runtime.snapshotFlow { Triple(p.path, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }).collect { (pa, i, o) ->
            if (pa != p.scrollPath) { p.scrollPath = pa; p.scrollIdx = 0; p.scrollOff = 0 } else { p.scrollIdx = i; p.scrollOff = o }
        }
    }
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
    // 14.0: плоский список дерева и строки считаются один раз на изменение раскрытых папок, их содержимого, сортировки и скрытых; раньше flattenTree (с сортировкой каждой раскрытой папки)
    // и buildRows выполнялись на каждую перекомпоновку панели (каждая галочка, каждая пачка цифр папок)
    val treeModeNow = Fm.treeMode
    // 23.11.2026 (владелица: «фоновая сборка строк как у X-plore»): плоский список дерева, строки и строки списка (PaneRvItem) собираются в фоновом потоке по версии состояний (раскрытые папки, их содержимое, сортировка);
    // на главный поток попадает готовый результат. Пока результат для этой папки и настроек ещё не готов (вход в папку, смена сортировки), строки один раз собираются сразу, как раньше.
    val pcState = androidx.compose.runtime.produceState<PaneComputed?>(null, p, items, treeModeNow, cfgNow) {
        androidx.compose.runtime.snapshotFlow { runCatching { computePane(p, items, treeModeNow, cfgNow) }.getOrElse { e -> android.util.Log.w("PaneCompute", "фоновая сборка списка: " + e.javaClass.simpleName + " " + e.message); PaneComputed(p, p.path, treeModeNow, cfgNow, null, emptyList(), emptyList(), true) } }.flowOn(Dispatchers.Default).collect { value = it }
    }
    val pcA = pcState.value
    val pcOk = pcA != null && !pcA.failed && pcA.pane === p && pcA.path == p.path && pcA.tree == treeModeNow && pcA.cfg == cfgNow
    val pcTracked = remember(p, items, treeModeNow, cfgNow) { androidx.compose.runtime.derivedStateOf { computePane(p, items, treeModeNow, cfgNow) } }   // запасной путь на случай сбоя фоновой сборки: как раньше, в главном потоке
    val pcSync = if (pcOk || pcA?.failed == true) null else remember(items, p.path, treeModeNow, cfgNow) { androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { computePane(p, items, treeModeNow, cfgNow) } }
    val comp: PaneComputed = if (pcOk) pcA!! else if (pcA?.failed == true) pcTracked.value else pcSync!!
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
        else -> comp.rows
    }
    // список у самого верха: после дочитывания тегов он остаётся у верха (иначе строка, вставшая выше, оказалась бы за краем экрана)
    val topAtTick = remember(tick) { androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { curFirst() == 0 && curOff() == 0 } }
    LaunchedEffect(tick) { if (topAtTick) goTo(0) }
    // после смены настроек сортировки список показывается с начала или с выбранного элемента (при дочитывании тегов положение не меняется)
    // положение списка запоминается для каждой папки каждой панели: при возврате в папку (и после перезапуска) список стоит там же
    val sctx = LocalContext.current
    val posReady = remember { booleanArrayOf(false) }
    val hasItems = p.items.isNotEmpty()
    LaunchedEffect(p.path, p.src, hasItems) {
        // пока содержимое не загружено, ничего не восстанавливается и не записывается (иначе перезапишется сохранённое положение)
        if (!hasItems) { posReady[0] = false; return@LaunchedEffect }
        // 19.11.2026: список уже создан с живой позицией (sameSpot) — не перематывать на сохранённую с задержкой 400 мс, иначе при переходе на панель список «дёргается»
        if (sameSpot && (p.scrollIdx != 0 || p.scrollOff != 0)) { posReady[0] = true; return@LaunchedEffect }
        val saved = ScrollMem.getFolder(sctx, p.id, p.src, p.path)
        goTo(saved?.first ?: 0, saved?.second ?: 0)
        posReady[0] = true
    }
    LaunchedEffect(p.path, p.src) {
        (if (rvOn) kotlinx.coroutines.flow.flow { while (true) { emit(rvSt.first to rvSt.firstOffset); delay(200) } }.distinctUntilChanged() else snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }).collectLatest { (a, b) ->
            delay(400); if (posReady[0]) ScrollMem.putFolder(sctx, p.id, p.src, p.path, a, b)
        }
    }
    val seenPath = remember { arrayOf(p.path) }
    // смена настроек сортировки: список с начала или от выбранного элемента (при смене папки не вмешивается)
    LaunchedEffect(cfgNow) {
        if (seenPath[0] != p.path) { seenPath[0] = p.path; return@LaunchedEffect }
        val sel = p.selected.firstOrNull()
        val i = if (sel != null) rows.indexOfFirst { it is ItemRow && it.item.name == sel } else 0
        goTo(maxOf(i, 0))
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
            if (i >= 0) goTo(i); p.selected = setOf(n); p.scrollToName = null
        }
    }
    // 18.11.2026 (владелица: «на приставке анимация переходов не работает»): на приставке/в ландшафте обе панели показаны разом,
    // переключение не подменяет содержимое (подменять там нечего) — единственное, что видно при переключении, это рамка активной
    // панели, и она раньше просто щёлкала мгновенно. Теперь рамка (толщина и цвет) плавно переезжает тем же способом (своя анимация,
    // не зависит от системного масштаба, слушает общий выключатель «Анимация переходов»).
    val activeProgress = remember { androidx.compose.animation.core.Animatable(if (active) 1f else 0f) }
    LaunchedEffect(active) {
        val target = if (active) 1f else 0f
        if (UiAnim.on) kotlinx.coroutines.withContext(FmFullMotion) { activeProgress.animateTo(target, tween(200, easing = FastOutSlowInEasing)) }
        else activeProgress.snapTo(target)
    }
    val borderW = (1f + activeProgress.value).dp
    val borderC = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.outline.copy(alpha = .5f), prim, activeProgress.value)
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).border(borderW, borderC, RoundedCornerShape(10.dp))
            .pointerInput(p.id) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); Fm.active = p.id } }
            .onFocusChanged { if (it.hasFocus && System.currentTimeMillis() - Fm.screenShownAt > 700L && FocusRestore.keys != FocusRestore.keysAtShow) Fm.active = p.id },   // T3: фокус, пришедший сам при запуске, активную панель не меняет
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
        // 20.11.2026: чтение папки без известного объёма: маленький индикатор вместо бегущей полоски
        if (p.loading && p.items.isEmpty()) Box(Modifier.fillMaxWidth().padding(4.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
        p.error?.let { e ->
            Text(e, Modifier.padding(8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            val nc = p.net
            if (nc != null && nc.kind == "cloud" && CloudErr.offersPublish(LocalContext.current, e)) {
                val cctx = LocalContext.current
                OutlinedButton({ Fm.dlg = FmDlg.OwnKey(CloudIcons.typeOf(cctx, nc) ?: "drive", OwnKeyWizard.PUBLISH_STEP) }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.cl_publish_btn)) }
            } else if (nc != null && nc.kind == "cloud" && CloudErr.offersOwnKey(LocalContext.current, e)) {
                val cctx = LocalContext.current
                OutlinedButton({ Fm.dlg = FmDlg.OwnKey(CloudIcons.typeOf(cctx, nc) ?: "drive") }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.ok_connect_own)) }
            }
        }
        if (view == "drive" && items.isEmpty() && p.error == null && !p.loading && p.parked == null) Text(stringResource(R.string.fm_empty), Modifier.padding(12.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        // режим «Дерево»: раскрытые папки подгружаются при раскрытии
        if (Fm.treeMode) LaunchedEffect(p.path, p.expanded.toList(), p.items) {
            for (e in p.expanded.toList()) if (!p.kids.containsKey(e) && (e.startsWith(p.path.trimEnd('/') + "/"))) Fm.loadKids(p, e)
        }
        p.tagNote?.let { Text(it, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), maxLines = 1) }
        val emptyKey = "\u0000empty"
        if (p.menuFor == emptyKey) LDropdownMenu(true, { p.menuFor = null }) { CreateMenu(p, p.path) { p.menuFor = null } }
        if (p.parked == null && !(p.src == "local" && !Fm.granted) && FmPaneRv.ON) {
            val rvItems = if (view != "top" && view != "trash") comp.rv else remember(rows) { rowsToRv(p, rows) }
            FmPaneRvList(p, rvItems, Marq.tv, rvSt, { it2 -> FmItemMenu(p, it2.item!!, it2.node as TNode?) }, { Fm.active = p.id; p.menuFor = emptyKey }, Modifier.weight(1f).fillMaxWidth())
        } else
        if (p.parked == null && !(p.src == "local" && !Fm.granted)) Box(Modifier.weight(1f).fillMaxWidth()) { LazyColumn(Modifier.fillMaxSize().combinedClickable(onClick = {}, onLongClick = { Fm.active = p.id; p.menuFor = emptyKey }).verticalScrollbar(state), state = state, contentPadding = sbPadding(), verticalArrangement = Arrangement.spacedBy(if (Marq.tv) 3.dp else 0.dp)) {
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
        FmSelCount(p, prim)
        }
    }
}

/** 14.0: строка «Выбрано: N» под списком. Набор отмеченного читается здесь, а не в самой панели: панель не перерисовывается целиком при каждой галочке. */
@Composable
private fun FmSelCount(p: FmPane, prim: Color) {
    if (p.selected.isNotEmpty()) {
        Text(stringResource(R.string.fm_selected, p.selected.size), Modifier.fillMaxWidth().background(prim.copy(alpha = .12f)).padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 11.sp)
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
                Text(pk.conn.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = UiPrefs.listFontBold, maxLines = 1)
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
    val selText = if (!p.root && p.selected.isNotEmpty()) { val ks = p.selected.sorted(); val base = p.path.trimEnd('/'); (if (base.isEmpty() && !p.path.contains("://")) "/" + ks.first() else base + "/" + ks.first()) + (if (ks.size > 1) "  (+" + (ks.size - 1) + ")" else "") } else null
    val h = if (Marq.tv) 48.dp else 40.dp
    Row(Modifier.fillMaxWidth().height(h).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxHeight().widthIn(min = 52.dp).clickable { Fm.active = p.id; fmHome(p) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Home, stringResource(R.string.fm_root_title), Modifier.size(if (Marq.tv) 36.dp else 31.dp), tint = if (p.root) prim else MaterialTheme.colorScheme.onSurface.copy(alpha = .75f))
        }
        p.net?.takeIf { p.src == "smb" && !p.root }?.let { NetBrandIcon(it, if (Marq.tv) 24.dp else 20.dp, Modifier.padding(end = 4.dp)) }
        val pkc = p.parked?.takeIf { !p.root }
        pkc?.let { NetBrandIcon(it.conn, if (Marq.tv) 24.dp else 20.dp, Modifier.padding(end = 4.dp)) }
        if (p.src != "local" && !p.root) Text((if (p.src == "smb" && p.net?.kind == "cloud") (p.net?.let { CloudIcons.serviceCaption(LocalContext.current, it) } ?: "cloud") else if (p.src == "smb") p.smb?.label?.substringBefore("://") ?: "smb" else p.src).uppercase(), Modifier.padding(end = 4.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 5.dp, vertical = 0.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
        (p.smb as? KeepFs)?.takeIf { p.src == "smb" && !p.root }?.let { NetDot(it) }
        // заголовок корня: статичный, по центру всей ширины справа от кнопки «дом»; обычный Text без бегущей строки и без реакции на нажатие и фокус
        if (p.root) {
            // 24.11.2026 (владелица): вместо слова «Источники» путь отмеченного элемента или строки под фокусом (как в X-plore); пока пути нет, остаётся «Источники»
            val hp = FmRootCfg.headText(p.id)
            if (hp.isEmpty()) androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Text(stringResource(R.string.fm_root_title), Modifier.fillMaxWidth(), fontSize = fs, fontWeight = fw, fontFamily = UiPrefs.listFont, color = prim, maxLines = 1, softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            } else {
                LaunchedEffect(hp) { scroll.scrollTo(scroll.maxValue) }
                Row(Modifier.weight(1f).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) { Text(hp, fontFamily = UiPrefs.listFont, fontSize = fs, fontWeight = fw, maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.onSurface) }
            }
        }
        // 24.11.2026 (владелица: «отметил папку F, а вверху путь не такой»): в обычной панели при отмеченном элементе вверху его полный путь, а не путь панели
        else if (selText != null) { LaunchedEffect(selText) { scroll.scrollTo(scroll.maxValue) }; Row(Modifier.weight(1f).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) { Text(selText, fontFamily = UiPrefs.listFont, fontSize = fs, fontWeight = fw, maxLines = 1, softWrap = false) } }
        else if (pkc != null) Row(Modifier.weight(1f).horizontalScroll(scroll)) { Text(pkc.path.ifBlank { pkc.conn.title }, fontFamily = UiPrefs.listFont, fontSize = fs, fontWeight = fw, maxLines = 1) }
        else if (p.src == "smb") Row(Modifier.weight(1f).horizontalScroll(scroll)) { Text(p.path, fontFamily = UiPrefs.listFont, fontSize = fs, fontWeight = fw, maxLines = 1) }
        else Row(Modifier.weight(1f).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
            val parts = p.path.trim('/').split('/').filter { it.isNotEmpty() }
            Text("/", Modifier.clickable { Fm.load(p, "/") }.padding(horizontal = 4.dp, vertical = 2.dp),
                fontFamily = UiPrefs.listFont, fontSize = fs, fontWeight = fw, color = prim, lineHeight = fs * 1.05f)
            parts.forEachIndexed { i, s ->
                val to = "/" + parts.take(i + 1).joinToString("/")
                Text(s, Modifier.clickable { Fm.load(p, to) }.padding(horizontal = 3.dp, vertical = 2.dp),
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

/** 23.11.2026: результат фоновой сборки списка панели: плоский список дерева, строки и строки RecyclerView. */
private class PaneComputed(val pane: FmPane, val path: String, val tree: Boolean, val cfg: SortCfg, val nodes: List<TNode>?, val rows: List<PRow>, val rv: List<PaneRvItem>, val failed: Boolean = false)

private fun computePane(p: FmPane, items: List<FmItem>, tree: Boolean, cfg: SortCfg): PaneComputed {
    val t = Fm.sortTick
    val nodes = if (tree && t >= 0) flattenTree(p, items) else null
    val rows = buildRows(p, items, nodes, cfg)
    return PaneComputed(p, p.path, tree, cfg, nodes, rows, rowsToRv(p, rows))
}

private fun rowsToRv(p: FmPane, rows: List<PRow>): List<PaneRvItem> {
    // блок (фон, контур, тень) только у «листовых» раскрытых папок вместе с их строками, как в X-plore
    val grps = IntArray(rows.size)
    val openF = BooleanArray(rows.size) { ix -> val r = rows[ix]; r is ItemRow && r.node != null && r.item.isDir && r.node.path in p.expanded }
    for (ix in rows.indices) {
        if (!openF[ix]) continue
        val d = (rows[ix] as ItemRow).node!!.depth
        var e = ix + 1; var leaf = true
        while (e < rows.size) { val rr = rows[e]; if (rr !is ItemRow || rr.node == null || rr.node.depth <= d) break; if (openF[e]) { leaf = false; break }; e++ }
        if (!leaf) continue
        for (j in ix until e) grps[j] = 1
    }
    return rows.mapIndexed { ix, r -> when (r) {
    is ItemRow -> PaneRvItem(r.key, r.item, r.node, r.node?.depth ?: 0, r.node?.path ?: "", r.node?.last ?: emptyList(), null).also { x ->
        val g = grps[ix]; x.grp = g
        val pg = if (ix > 0) grps[ix - 1] else 0; val ng = if (ix + 1 < grps.size) grps[ix + 1] else 0
        x.gTop = if (g > 0 && pg == 0) 2 else 0; x.gBot = if (g > 0 && ng == 0) 2 else 0 }
    is HeadRow -> PaneRvItem(r.key, null, null, 0, "", emptyList()) { FmGroupHeader(r.g, r.count, r.closed) { val c = r.g.ch.toString(); if (c in p.collapsed) p.collapsed.remove(c) else p.collapsed.add(c) } }
    is TrashRow -> PaneRvItem(r.key, null, null, 0, "", emptyList()) { FmTrashRow(p) }
    is DriveRow -> PaneRvItem(r.key, null, null, 0, "", emptyList()) { FmDriveRow(p) }
    is MsgRow -> PaneRvItem(r.key, null, null, 0, "", emptyList()) { Text(r.text, Modifier.padding(12.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f)) }
    is TItemRow -> PaneRvItem(r.key, null, null, 0, "", emptyList()) { FmTrashItemRow(p, r.ti) }
    else -> PaneRvItem(r.key, null, null, 0, "", emptyList(), null)
            } }
}

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
/** 14.0: неизменяемая запись со сравнением по значению: строка списка пропускается при перекомпоновке, если её узел не изменился (раньше новый TNode каждый раз заставлял перерисовать все видимые строки). */
@androidx.compose.runtime.Immutable
private data class TNode(val item: FmItem, val path: String, val depth: Int, val last: List<Boolean>)

private fun flattenTree(p: FmPane, top: List<FmItem>): List<TNode> {
    val out = ArrayList<TNode>()
    fun walk(dir: String, list: List<FmItem>, depth: Int, anc: List<Boolean>) {
        list.forEachIndexed { i, f ->
            val path = FmIo.join(dir, f.name)
            val chain = anc + (i == list.lastIndex)
            out.add(TNode(f, path, depth, chain))
            if (f.isDir && path in p.expanded && depth < 12) p.kids[path]?.let { k -> walk(path, Fm.sortedKids(p, path, k), depth + 1, chain) }
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
            Text(listOf(dirTxt, nbBlock(dateTxt)).filter { it.isNotEmpty() }.joinToString(SUB_SEP), fontSize = 11.sp, fontFamily = UiPrefs.listFont, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
        }
        if (ti.size >= 0 && !ti.isDir) Text(fmSize(ti.size), fontSize = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
        BigCheck(sel, tv) { toggle() }
    }
}

@Composable
private fun FmRow(p: FmPane, f: FmItem, node: TNode? = null) {
    val dense = Fm.dense
    val depth = node?.depth ?: 0
    val treeOn = node != null
    val nodePath = node?.path ?: ""
    // 14.0: раскрыта ли строка, отмечена ли она и «частично» ли: значения через derivedStateOf. Раньше каждая строка читала p.expanded и p.selected прямо в композиции,
    // и любое раскрытие или галочка пересоздавали ВСЕ видимые строки (десятки строк по несколько мс на слабой приставке); теперь перерисовывается только строка, у которой значение изменилось
    val isOpen by remember(nodePath, treeOn, f.isDir) { androidx.compose.runtime.derivedStateOf { treeOn && f.isDir && nodePath in p.expanded } }
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .28f)
    val menuKey = if (depth == 0) f.name else nodePath
    val tvRow = Marq.tv
    val sepColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .14f)
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
    // 14.0 (владелица: «раскрыть и отметить: очень долгая реакция, у X-plore моментально»): с onDoubleClick Compose откладывает КАЖДОЕ одиночное нажатие на папку
    // на ~300 мс (ждёт второго касания). Теперь одиночное нажатие срабатывает сразу, а второе касание той же папки в пределах времени двойного нажатия входит в неё.
    // Второе нажатие считается двойным только для касания и мыши (ОК пульта и Enter идут как обычные нажатия).
    val ptrDownAt = remember { longArrayOf(0L) }
    val prevTapAt = remember { longArrayOf(0L) }
    val clickOrEnter: () -> Unit = {
        val t = android.os.SystemClock.uptimeMillis()
        val viaPointer = t - ptrDownAt[0] < 800L
        if (viaPointer && enterDir != null && prevTapAt[0] != 0L && t - prevTapAt[0] <= android.view.ViewConfiguration.getDoubleTapTimeout()) { prevTapAt[0] = 0L; enterDir.invoke() }
        else { prevTapAt[0] = if (viaPointer) t else 0L; rowClick() }
    }
    val full = node?.path ?: FmIo.join(p.path, f.name)
    val selKey = if (depth > 0) nodePath.removePrefix(p.path.trimEnd('/') + "/") else f.name
    val sel by remember(selKey) { androidx.compose.runtime.derivedStateOf { p.selIdx.value.direct(selKey) } }
    val part = false   // 26.11.2026 (слово владелицы): значок «частично» (минус) убран, как в X-plore
    val rowFr = remember { FocusRequester() }
    val chkFr = remember { FocusRequester() }
    val sctx = androidx.compose.ui.platform.LocalContext.current
    // 10.2: у папки «N папок, N файлов»: дата сразу, цифры в фоне (FolderCounts), для архивов только дата
    val cKey = if (f.isDir && FmSub.mode != FmSub.NONE && p.net?.kind != "cloud" && !(p.src == "smb" && p.smb is ArchiveFs)) "P|" + p.src + "|" + (p.net?.let { it.kind + "|" + it.host + "|" + it.user } ?: "") + "|" + full + "|" + f.date else null
    // 14.0: цифры папки читаются через derivedStateOf: раньше каждая строка без цифр читала FolderCounts.ver и перерисовывалась при КАЖДОЙ пачке цифр (раз в ~150 мс, пока считаются десятки папок)
    val cntNow: Pair<Int, Int>? = if (cKey == null) null else remember(cKey) { androidx.compose.runtime.derivedStateOf { val t = FolderCounts.ver; if (t >= 0) FolderCounts.peek(cKey) else null } }.value
    if (cKey != null && cntNow == null) LaunchedEffect(cKey) {   // 14.0: эффект только пока цифр нет
        if (!FolderCounts.has(cKey)) FolderCounts.load(sctx, cKey, if (p.src == "local") FolderCounts.LOCAL else FolderCounts.clsOf(p.net ?: NetConn("smb", "", 0, "", "")), false) {
            // 14.0: локальная папка: цифры считаются сразу на фоновом потоке (listFiles + isDirectory), без полного списка с датами; если так не читается (Android/data, съёмный том без доступа), прежний путь
            (if (p.src == "local") FolderCounts.localCount(java.io.File(full)) else null)
                ?: run { val l = FmIo.list(p, full); l.count { it.isDir && it.name != FmTrash.DIR } to l.count { !it.isDir } }
        }
    }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val pressed = false   // 14.0: нажатие строки не собирается отдельной корутиной (значение шло только в неиспользуемый параметр MqText)
    var focused by remember { mutableStateOf(false) }
    val prim = MaterialTheme.colorScheme.primary
    run {
    Box {
        Row(
            Modifier.fillMaxWidth()
                .then(if (f.hidden) Modifier.graphicsLayer { alpha = 0.6f } else Modifier).heightIn(min = if (tvRow) 54.dp else if (dense) 0.dp else 46.dp)
                .background(if (sel) prim.copy(alpha = .20f) else if (hovered) prim.copy(alpha = .08f) else if (treeOn && depth > 0) androidx.compose.ui.graphics.Color(0xFF3F8FD2).copy(alpha = (0.08f + 0.05f * (depth - 1)).coerceAtMost(0.28f)) else androidx.compose.ui.graphics.Color.Transparent)
                .hoverable(hover)
                .then(if (treeOn && depth > 0) Modifier.drawBehind {
                    // вертикальные линии ветвей и «колено» к значку папки
                    val ind = 22.dp.toPx(); val x0 = 24.dp.toPx(); val w = 1.2.dp.toPx(); val mid = size.height / 2
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
                    // пульт и клавиатура: вправо раскрывает папку, влево сворачивает; 10.0 (fm1): «вправо» на файле или раскрытой папке переходит на галочку
                    if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && ev.key == Key.DirectionRight && !(treeOn && f.isDir && !isOpen)) return@onKeyEvent runCatching { chkFr.requestFocus() }.isSuccess
                    if (treeOn && f.isDir && ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) when (ev.key) {
                        Key.DirectionRight -> { Fm.expand(p, nodePath); true }
                        Key.DirectionLeft -> if (isOpen) { Fm.collapse(p, nodePath); true } else false
                        else -> false
                    } else false
                }
                .pointerInput(f.name, depth) {
                    // правая кнопка мыши: меню; сенсор: долгое касание открывает меню файла (10.0, fm1: перетаскивания между панелями больше нет, прокрутка не перехватывается)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        ptrDownAt[0] = android.os.SystemClock.uptimeMillis()
                        val mouse = down.type == androidx.compose.ui.input.pointer.PointerType.Mouse
                        if (mouse && currentEvent.buttons.isSecondaryPressed) { down.consume(); Fm.active = p.id; p.menuFor = menuKey; return@awaitEachGesture }   // 10.0 (mouse): правая кнопка не отмечает и не открывает строку
                        if (mouse) { if (fmMouseHoldRelease(down)) { Fm.active = p.id; p.menuFor = menuKey }; return@awaitEachGesture }   // 10.0 (mouse)
                        // долгое касание считается здесь же, в первом проходе: дочерние обработчики нажатий не успевают «съесть» событие; сдвиг пальца = прокрутка, меню нет
                        val holdSlop = viewConfiguration.touchSlop * 2f
                        var early = false
                        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (!early) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                val ch = ev.changes.firstOrNull { it.id == down.id }
                                if (ch == null || !ch.pressed || (ch.position - down.position).getDistance() > holdSlop) early = true
                            }
                        }
                        if (early) return@awaitEachGesture
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        down.consume()
                        Fm.active = p.id
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            ch.consume()
                            if (!ch.pressed) break
                        }
                        p.menuFor = menuKey
                    }
                }
                .focusRequester(rowFr)
                .combinedClickable(
                    interactionSource = hover, indication = androidx.compose.foundation.LocalIndication.current,
                    onClick = clickOrEnter,
                )
                .then(if (tvRow) Modifier.drawBehind { drawLine(sepColor, androidx.compose.ui.geometry.Offset(12.dp.toPx(), size.height - 0.5f), androidx.compose.ui.geometry.Offset(size.width - 12.dp.toPx(), size.height - 0.5f), 1.dp.toPx()) } else Modifier)
                .padding(start = TreeDim.pad.dp, end = if (dense) 0.dp else 2.dp, top = if (tvRow) 4.dp else if (dense) 0.dp else 4.dp, bottom = if (tvRow) 4.dp else if (dense) 0.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 10.2 (58, как в X-plore): слева от папок и файлов маленькая точка: нажатие открывает свойства, строка не выделяется
            // 14.0: точка «свойства» и стрелка раскрытия скрыты из дерева доступности (то же делает нажатие на саму строку и пункт «Свойства» меню): меньше узлов семантики на строку,
            // а проверка семантики Compose при включённой службе спецвозможностей на слабой приставке считает узлы десятки миллисекунд на каждый
            Box(Modifier.size(TreeDim.dotW.dp, 32.dp).tapOnly { Fm.active = p.id; Fm.props(p, listOf(selKey)) }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .3f), androidx.compose.foundation.shape.CircleShape))
            }
            if (treeOn) {
                TreeLog.Note("список «Файлов» (дерево)", depth)
                val tlDm = androidx.compose.ui.platform.LocalDensity.current
                Spacer(Modifier.width((TreeDim.ind * depth).dp))
                // треугольник только у папок, в которых что-то есть (пустым стрелки нет); цвет по настройке (20.11.2026: по умолчанию в цвет текста)
                val arrowCol = treeArrowColor()
                val nonEmpty = cntNow == null || cntNow.first + cntNow.second > 0
                if (f.isDir && nonEmpty) Box(Modifier.size(TreeDim.fmArrow.dp).onGloballyPositioned { c -> TreeLog.measured("список «Файлов» (дерево)", depth, tlDm.density, tlDm.fontScale, "поле стрелки", c.positionInParent().x, c.size.width.toFloat()) }
                    .tapOnly { Fm.active = p.id; Fm.toggleExpand(p, nodePath) }, contentAlignment = Alignment.CenterStart) {   // 14.0: стрелка без узла семантики и ряби (tapOnly), как в «Источниках»; 16.0 код 79: треугольник с отступом слева, промежутки точка–стрелка и стрелка–значок равны
                    androidx.compose.foundation.Canvas(Modifier.padding(start = TreeDim.paneArrowInk(isOpen).dp).size(12.dp)) {
                        val c = arrowCol
                        val path = androidx.compose.ui.graphics.Path().apply {
                            if (isOpen) { moveTo(0f, size.height * .2f); lineTo(size.width, size.height * .2f); lineTo(size.width / 2, size.height * .9f) }
                            else { moveTo(size.width * .15f, 0f); lineTo(size.width * .15f, size.height); lineTo(size.width, size.height / 2) }
                            close()
                        }
                        drawPath(path, c)
                    }
                } else Spacer(Modifier.width(TreeDim.fmArrow.dp))
            }
            // «Плотно»: размеры как у строки в «Софте» (значок 32, шрифты 15/17 и 11/13, без вертикальных отступов)
            // значок того же размера, что в списке «Софт» (32 dp); долгое нажатие по папке: цвет папки
            if (f.isDir) {
                val key = folderKey(p, full)
                FolderOrAppIcon(full, UiPrefs.folderColors[key] ?: UiPrefs.folderDefault, isOpen, 32.dp,
                    Modifier, customKey = "f:" + key)
            } else if (p.src == "smb" && p.smb is AppsFs) AppsRowIcon(f.name, 32.dp) else FileTypeIcon(f.name, f.ext, if (p.src == "local") full else null, 32.dp,
                remote = if (p.src != "local" && !f.isDir && f.size > 0 && (f.ext == "apk" || f.ext == "apks" || f.ext == "xapk" || f.ext == "apkm"))
                    RemoteApk(p.src + "|" + (p.net?.let { it.kind + "|" + it.host + "|" + it.user } ?: "") + "|" + full + "|" + f.size + "|" + f.date, f.size,
                        p.src == "adb" || (p.src == "smb" && p.net?.kind != "cloud" && p.smb !is ArchiveFs), if (p.src == "adb") full else null) { dest -> Fm.fetchTo(p, full, dest) } else null)
            Column(Modifier.padding(start = if (dense) 8.dp else 10.dp).weight(1f)) {
                MqText(f.name, active = hovered || pressed || focused || sel, fontSize = if (dense) 15.sp else 14.sp, lineHeight = if (dense) 17.sp else 18.sp, fontWeight = FontWeight.Bold, fontFamily = UiPrefs.listFontBold, names = true)
                val narrowSub = !tvRow && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 420
                val cntTxt = cntNow?.let { nbBlock(stringResource(if (narrowSub) R.string.rt_counts_s else R.string.rt_counts, it.first, it.second)) }
                val sub = (if (f.isDir) FmSub.fileLine(sctx, f.date, cntTxt, narrowSub) else FmSub.fileLine(sctx, f.date, if (Fm.showSize) fmSize(f.size) else null, narrowSub)) ?: ""
                if (sub.isNotEmpty()) MqText(sub, active = hovered || pressed || focused || sel, fontSize = if (dense) 11.sp else 10.sp, lineHeight = if (dense) 13.sp else 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), fontFamily = UiPrefs.listFont)
            }
            // 10.0 (fm1): квадратик крупнее (26 dp, приставка 32), нажимается вся правая часть строки (SelZone)
            Box(Modifier.padding(start = 4.dp, end = 4.dp)) { SelBox(sel, part, tvRow) }
        }
        SelZone(tvRow, chkFr, rowFr, stringResource(if (sel) R.string.fm_unselect else R.string.fm_select), onLong = { Fm.active = p.id; p.menuFor = menuKey }) { Fm.active = p.id; Fm.toggleSel(p, selKey) }
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
    val names = if (Fm.isSel(p, rel)) Fm.targets(p) else listOf(rel)
    val multi = names.size > 1
    val full = FmIo.join(p.path, rel)
    val createDir = if (f.isDir) full else full.substringBeforeLast(Char(47)).ifEmpty { Char(47).toString() }
    LDropdownMenu(true, { p.menuFor = null }) {
        @Composable fun item(l: Int, on: Boolean = true, run: () -> Unit) = DropdownMenuItem({ Text(stringResource(l), fontSize = 14.sp, color = if (l == R.string.fm_delete) deleteRed() else androidx.compose.ui.graphics.Color.Unspecified) }, { p.menuFor = null; run() }, enabled = on)
        if (p.src == "smb" && p.smb is AppsFs && !f.isDir && !multi) { AppMenuItems(p, f, o) { p.menuFor = null }; item(R.string.copy_name) { Fm.copyNames(listOf(f.name)) }; return@LDropdownMenu }
        if (p.src == "smb" && p.smb is AppsFs && multi) { AppBatchMenuItems(p, names.filter { n -> p.items.firstOrNull { it.name == n }?.isDir == false }, o) { p.menuFor = null }; return@LDropdownMenu }
        if (multi) Text(stringResource(R.string.fm_selected, names.size), Modifier.padding(horizontal = 12.dp, vertical = 2.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        if (!multi) {
            item(R.string.fm_open) { if (f.isDir) { if (nested) Fm.load(p, node!!.path) else Fm.open(p, f.name) } else Fm.openFile(p, f) }
            if (!f.isDir) item(R.string.fm_open_in_system) { ApkInstall.showPicker(p, f) }
        }
        item(R.string.fm_share, p.src != "adb") { Fm.share(p, names) }
        item(R.string.fm_copy) { RootSelOps.toOther(p, names, false) }
        item(R.string.fm_move) { RootSelOps.toOther(p, names, true) }
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
        if (!multi && !f.isDir && (f.ext == "apk" || ApkInstall.isBundle(f.ext)) && p.src == "local" && ApkInstall.installedStores(Fm.sctx).isNotEmpty()) item(R.string.inst_market) { ApkInstall.openMarket(p, f) }
        if (!multi && !f.isDir && f.ext in setOf("apks", "xapk", "apkm")) item(R.string.rp_menu) { Fm.repackFile(p, f) }
        if (!multi && !f.isDir && Fm.isArchive(f.name)) {
            item(if (ApkInstall.isBundle(f.ext)) R.string.arc_open_as_folder else R.string.arc_open_as_archive) { Fm.openArchive(p, f.name) }
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
        DropdownMenuItem({ Text(stringResource(R.string.fm_new_folder), fontSize = 14.sp) }, { close(); Fm.dlg = FmDlg.NewFolder(dir) }, enabled = ok || p.smb is CloudFs,
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
        is FmDlg.SafNeed -> SafNeedDialog(d, close)
        is FmDlg.Conflict -> ConflictDialog(d, close)
        is FmDlg.TransferConfirm -> TransferConfirmDialog(d, close)
        is FmDlg.AddButton -> FmAddButtonDialog(close)
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
                        BigCheckbox(Fm.privOn, { Fm.choosePriv(it) }); Text(stringResource(R.string.fm_priv_opt), fontSize = 13.sp)
                    }
                    Row(Modifier.fillMaxWidth().clickable { Fm.chooseTreeOne(!Fm.treeOne) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        BigCheckbox(Fm.treeOne, { Fm.chooseTreeOne(it) }); Text(stringResource(R.string.fm_tree_one), fontSize = 13.sp)
                    }
                }
            },
            confirmButton = { TextButton(close) { Text(stringResource(R.string.fm_apply)) } },
        )
        is FmDlg.Bookmarks -> PathListDlg(R.string.fm_bookmarks, Fm.bookmarks(), R.string.fm_bm_empty, close,
            extra = { TextButton({ Fm.toggleBookmark(Fm.cur.path); Fm.dlg = FmDlg.Bookmarks() }) { Text(stringResource(R.string.fm_bm_add)) } }, iconKey = { MiscIconKeys.bookmark(it) }) { Fm.load(Fm.cur, it) }
        is FmDlg.History -> PathListDlg(R.string.fm_history, Fm.history(), R.string.fm_hist_empty, close) { Fm.load(Fm.cur, it) }
        is FmDlg.Storage -> StorageDlg(close)
        is FmDlg.WifiServer -> WifiServerDialog(d.dir, close)
        is FmDlg.WifiDev -> WifiDeviceDialog(d.conn, d.host, d.name, close, d.authFailed)
        is FmDlg.PcAdd -> PcAddDialog(d.host, close)
        is FmDlg.PcScreen -> PcScreenDialog(d.host, close)
        is FmDlg.DevAdd -> DevAddDialog(d.pc, d.adb, close)
        is FmDlg.AndroidScreen -> AndroidScreenDialog(d.dev, close)
        is FmDlg.CallCode -> CallCodeDialog(d.name, d.ip, d.voice, close)
        is FmDlg.AndroidConsole -> AndroidConsoleDialog(d.dev, close)
        is FmDlg.SearchResult -> PathListDlg(R.string.fm_search, d.list, R.string.fm_nothing, close, subtitle = stringResource(R.string.fm_found, d.list.size) + (if (d.where.isNotEmpty()) "\n" + stringResource(R.string.fm_search_in, d.where) else "")) {
            Fm.openFound(d, it)
        }
        is FmDlg.PickTarget -> PickTargetDlg(d, close)
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
        is FmDlg.More -> FmMoreDialog(d, close)
        is FmDlg.Perms -> FmPermsDialog(d.pane, d.names, close)
        is FmDlg.MarketPick -> LAlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.inst_market_title)) },
            text = { Column { d.stores.forEach { (pk, label) ->
                Row(Modifier.fillMaxWidth().clickable { close(); ApkInstall.launchStore(pk, d.pkg) }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AppIcon(pk, 40.dp); Text(label, fontSize = 17.sp)
                }
            } } },
            confirmButton = { TextButton(close) { Text(stringResource(R.string.btn_cancel)) } })
        is FmDlg.Props -> PropsDialog(d.title, d.body, close)
        is FmDlg.TextView -> LAlertDialog(onDismissRequest = close, title = { Text(d.title, maxLines = 1) },
            text = { Column(Modifier.heightIn(max = 420.dp).barScroll()) { Text(d.body, fontSize = 12.sp, fontFamily = FontFamily.Monospace) } },
            confirmButton = { TextButton(close) { Text(stringResource(R.string.fm_close)) } })
        is FmDlg.Repack -> RepackDialog(d.spec, { f, sg -> Fm.repackRun(d.spec, f, sg) }, close)
        is FmDlg.Archive -> LAlertDialog(onDismissRequest = close, title = { Text(d.name, maxLines = 1) },
            text = { BarLazyColumn(Modifier.heightIn(max = 380.dp)) { items(d.lines) { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) } } },
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
private fun PathListDlg(title: Int, list: List<String>, emptyText: Int, close: () -> Unit, subtitle: String? = null, extra: (@Composable () -> Unit)? = null, iconKey: ((String) -> String)? = null, pick: (String) -> Unit) {
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
                        val rowText: @Composable (Modifier) -> Unit = { m -> MqText(path, active = act || foc || hov, modifier = m.hoverable(src).clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current) { close(); pick(path) }.padding(vertical = 9.dp),
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                        // 10.0: закладки: «Изменить значок…» по долгому нажатию и свой значок слева (MiscUi.kt)
                        val ik = iconKey?.invoke(path)
                        if (ik != null) MiscIconRow(ik, path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }) { rowText(Modifier.weight(1f)) } else rowText(Modifier.fillMaxWidth())
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

private data class PickRow(val path: String, val depth: Int, val open: Boolean, val label: String)

/** 9.3: выбор папки-приёмника для копирования и переноса с корневого экрана (когда другая панель тоже корень). Папка выбирается нажатием на имя, стрелка раскрывает. */
@Composable
private fun PickTargetDlg(d: FmDlg.PickTarget, close: () -> Unit) {
    val base = remember { FmPane(9, "/").also { it.src = "local" } }
    val scope = rememberCoroutineScope()
    val internal = stringResource(R.string.fm_internal)
    LaunchedEffect(Unit) { Fm.refreshVols() }
    val roots = listOf(PickRow("/sdcard", 0, false, internal)) + Fm.vols.filter { it.ok }.map { PickRow(it.path, 0, false, it.label) }
    var open by remember { mutableStateOf(setOf<String>()) }
    val kids = remember { androidx.compose.runtime.mutableStateMapOf<String, List<String>>() }
    var sel by remember { mutableStateOf<String?>(null) }
    fun toggle(path: String) {
        if (path in open) { open = open - path; return }
        scope.launch { kids[path] = runCatching { FmIo.subdirs(base, path) }.getOrDefault(emptyList()).filter { !it.trimEnd('/').substringAfterLast('/').startsWith('.') }; open = open + path }
    }
    val rows = ArrayList<PickRow>()
    fun walk(path: String, depth: Int, label: String) {
        rows.add(PickRow(path, depth, path in open, label))
        if (path in open) kids[path].orEmpty().forEach { walk(it, depth + 1, it.trimEnd('/').substringAfterLast('/')) }
    }
    roots.forEach { walk(it.path, 0, it.label) }
    LAlertDialog(
        onDismissRequest = close, title = { Text(stringResource(if (d.move) R.string.pk_move_to else R.string.pk_copy_to)) },
        text = {
            Column {
                Text(sel ?: stringResource(R.string.pk_pick), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                BarLazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(rows.size, key = { rows[it].path }) { i ->
                        val r = rows[i]
                        val on = r.path == sel
                        Row(Modifier.fillMaxWidth().padding(start = (r.depth * 14).dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.IconButton({ toggle(r.path) }, Modifier.size(34.dp)) {
                                Icon(if (r.open) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null, Modifier.size(20.dp))
                            }
                            // 10.0 (chk): выбранная папка отмечается крупной галочкой справа, нажимается вся строка (CheckRow, BigCheck.kt)
                            CheckRow(on, Modifier.weight(1f), onClick = { sel = r.path }) {
                                Text(r.label, Modifier.weight(1f), fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                val to = sel ?: return@TextButton
                close()
                Fm.scope.launch {
                    for ((sp, names) in d.jobs) { Fm.copyMoveTo(d.move, sp, base, "local", to, names); kotlinx.coroutines.delay(700); while (Fm.dlg is FmDlg.Conflict) kotlinx.coroutines.delay(300) }
                    d.after()
                }
            }, enabled = sel != null) { Text(stringResource(R.string.pk_here)) }
        },
        dismissButton = { TextButton(close) { Text(stringResource(R.string.cancel)) } },
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


/**
 * 20.11.2026 (слова владелицы): «⋯» на боковой панели открывает окно со списком ВСЕХ кнопок: слева галочка «кнопка на панели», в середине значок и название
 * (нажатие на название сразу выполняет действие, окно закрывается; нажатие на значок меняет его на свой), справа ↑ и ↓ (порядок кнопки на панели).
 * Закреплённые сверху в порядке панели, остальные ниже. Пульт: стрелки вверх и вниз по списку, вправо и влево между галочкой, названием и стрелками, ОК по фокусу.
 * Внизу «По умолчанию» и «Готово». Убрать кнопку можно и долгим нажатием на кнопке самой панели.
 */
@Composable
fun FmAddButtonDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    remember { FmButtons.load(ctx); 0 }
    val skip = setOf(R.string.ft_menu, R.string.fm_history, R.string.fm_swap, R.string.fm_same)
    val all = FmButtons.ordered(ctx, rememberFmActionsAll()).filter { it.label !in skip }.distinctBy { FmButtons.key(ctx, it.label) }
    val on = all.filter { FmButtons.key(ctx, it.label) !in FmButtons.hidden }
    val off = all.filter { FmButtons.key(ctx, it.label) in FmButtons.hidden }
    val onKeys = on.map { FmButtons.key(ctx, it.label) }
    var pickBtn by remember { mutableStateOf<Pair<String, String>?>(null) }
    pickBtn?.let { (k, t) -> CustomIconPicker(t, "s:" + k) { pickBtn = null } }
    @Composable fun ActRow(a: FmAction, idx: Int, pinned: Boolean) {
        val k = FmButtons.key(ctx, a.label)
        val lbl = stringResource(if (k == "fm_mode") R.string.fm_mode_list else a.label)
        val alpha = if (a.enabled) 1f else .55f
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            BigCheckbox(pinned, { if (pinned) FmButtons.hide(ctx, k) else FmButtons.show(ctx, k) })
            Row(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { if (a.enabled) { onClose(); a.run() } }.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val csB = CustomIcons.spec("s:" + k)
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .12f)).clickable { pickBtn = k to lbl }, contentAlignment = Alignment.Center) {
                    if (csB != null) CustomIconGlyph(csB, 24.dp, a.tint ?: MaterialTheme.colorScheme.primary) else Icon(a.icon, null, Modifier.size(22.dp), tint = (a.tint ?: MaterialTheme.colorScheme.primary).copy(alpha = alpha))
                }
                Spacer(Modifier.width(8.dp))
                Text(lbl, Modifier.weight(1f), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            }
            androidx.compose.material3.IconButton(onClick = { FmButtons.move(ctx, k, onKeys, -1) }, enabled = pinned && idx > 0, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.sb_up))
            }
            androidx.compose.material3.IconButton(onClick = { FmButtons.move(ctx, k, onKeys, 1) }, enabled = pinned && idx < onKeys.lastIndex, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.sb_down))
            }
        }
    }
    LAlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.sb_menu_title), fontSize = 18.sp) },
        text = {
            Column(Modifier.barScroll()) {
                Text(stringResource(R.string.sb_menu_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f), modifier = Modifier.padding(bottom = 6.dp))
                on.forEachIndexed { i, a -> androidx.compose.runtime.key(FmButtons.key(ctx, a.label)) { ActRow(a, i, true) } }
                if (off.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                off.forEach { a -> androidx.compose.runtime.key(FmButtons.key(ctx, a.label)) { ActRow(a, -1, false) } }
                TextButton({ CustomIcons.clearPrefix(ctx, "s:") }) { Text(stringResource(R.string.sb_reset_icons), fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.sb_done)) } },
        dismissButton = { TextButton({ FmButtons.reset(ctx) }) { Text(stringResource(R.string.sb_reset)) } },
    )
}


