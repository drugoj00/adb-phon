@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package app.adbphon

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch

/** Настройки корневого экрана: какие необязательные строки видны (по умолчанию только сеть и облака, лишнее скрыто). */
object FmRootCfg {
    /** Когда запущен файловый менеджер: запомненные облака и сеть перечитываются не раньше чем через ~1,5 с и по одному. */
    val t0 = System.currentTimeMillis()

    /** Повторное раскрытие узла: старый список виден сразу, но поверх всегда читается заново (так же для раскрытых внутри него узлов). */
    fun refresh(p: FmPane, id: String, favs: List<NetConn>) {
        val virt = favs.filter { CloudTrash.supported(CloudTrash.typeFor(Fm.sctx, it)) }.map { "N|" + it.kind + "|" + it.host + "|" + it.user + "|" }.toSet()
        for (x in (p.rootOpen.toList() + id).distinct()) if (x.startsWith(id) && x !in virt && kids[x] != null) Fm.scope.launch { loadKids(x, favs) }
    }
    val optional = listOf("root", "recent", "lan", "cloud", "wifi", "apps", "tree", "adb")
    /** Первый запуск (выбор в «Показать/Скрыть» ещё не сохранён): видны все пункты, чтобы сразу было видно возможности. Сохранённый выбор не трогается. */
    var shown by mutableStateOf(optional.toSet())
    /** Число папок и файлов в корне тома, считается в фоне. */
    val counts = mutableStateMapOf<String, Pair<Int, Int>>()
    var ver by mutableStateOf(0)
    /** Фокус пульта: первый значок нижней панели выбора и ключ последней видимой строки списка (стрелка «вниз» на ней уходит на панель). */
    val barFocus = androidx.compose.ui.focus.FocusRequester()
    @Volatile var lastVisKey: String? = null

    /** Дерево папок на «домике»: дочерние папки узла (по идентификатору узла), ошибки и кэш подключений. Загружаются лениво при раскрытии. */
    class TK(val name: String, val id: String, val path: String, val isDir: Boolean = true, val size: Long = 0L)

    /** Отмеченная строка дерева «домика»: ключ = id узла (путь целиком), источник (net = null: память телефона), папка-родитель, для «Корзины» ещё элемент корзины. */
    class SelE(val key: String, val net: NetConn?, val parentId: String, val parentPath: String, val name: String, val path: String, val isDir: Boolean, val ti: CloudTrash.TItem?, val fi: FmTrash.FItem? = null)
    val sel = androidx.compose.runtime.mutableStateMapOf<String, SelE>()
    fun toggleSel(e: SelE) { if (sel.containsKey(e.key)) sel.remove(e.key) else sel[e.key] = e }
    val kids = mutableStateMapOf<String, List<TK>>()
    val kidErr = mutableStateMapOf<String, String>()
    /** Тип облака у узла, ошибку которого лечит «свой ключ» (лимит общего ключа, API выключен, не тот scope). */
    val kidErrOwnKey = mutableStateMapOf<String, String>()
    private val autoRetry = HashMap<String, Int>()
    /** Сколько элементов узла показывать сразу (остальное счётчиком): быстрая прокрутка длинных папок. */
    const val LIMIT = 200
    fun fsFor(c: NetConn): RemoteFs = fsCache.getOrPut(c.kind + "|" + c.host + "|" + c.user) { c.fs() }
    val appCount = mutableStateMapOf<String, Int>()
    private val fsCache = java.util.concurrent.ConcurrentHashMap<String, RemoteFs>()
    /** Подключения раскрытых в дереве узлов (для постоянной связи и блокировок Wi-Fi). */
    fun openFs(): List<KeepFs> = runCatching {
        Fm.panes.flatMap { it.rootOpen.toList() }.filter { it.startsWith("N|") }.mapNotNull { id -> val a = id.split('|', limit = 5); if (a.size < 4) null else fsCache[a[1] + "|" + a[2] + "|" + a[3]] as? KeepFs }.distinct()
    }.getOrDefault(emptyList())
    val treeOn get() = "tree" in shown

    /** Идентификаторы: «L|путь» (память телефона) и «N|вид|адрес|логин|путь» (сеть, облако). */
    suspend fun loadKids(id: String, favs: List<NetConn>) {
        kidErr.remove(id); kidErrOwnKey.remove(id)
        // облачные узлы без модуля «ADB-phon Облака»: сеть не трогаем, узел покажет «Нужен модуль облаков»
        if ((id.startsWith("N|cloud|") || (id.startsWith("T|") && !NetTrash.isRid(id.removePrefix("T|")))) && !CloudModule.ok(Fm.sctx)) { kidErr[id] = CloudModule.MARK; return }
        try {
            val list: List<TK> = if (id == "H|") {
                // «Недавние»: недавние папки (только существующие на телефоне)
                withContext(Dispatchers.IO) { Fm.history().filter { File(it).isDirectory }.take(30).map { TK(it.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }, "L|$it", it) } }
            } else if (id == "K|") {
                // «Корзина» файлового менеджера: удалённое с телефона и карты памяти (все тома)
                val l = FmTrash.list()
                FmTrash.count = l.size; FmTrash.total = l.sumOf { maxOf(it.size, 0L) }
                l.take(LIMIT).map { fi -> val kid = "Ki|" + fi.vol + "|" + fi.id; FmTrash.tk[kid] = fi; TK(fi.name, kid, fi.origPath, fi.isDir, fi.size) }
            } else if (id.startsWith("T|")) {
                // «Корзина» облака: удалённые элементы (родная корзина Google Диска или корзина программы)
                val host = id.removePrefix("T|"); val ctx = Fm.sctx
                if (NetTrash.isRid(host)) NetTrash.ensureAll(ctx)
                val l = if (CloudTrash.mode(CloudTrash.typeOfRid(ctx, host)) == CloudTrash.OWN) CloudTrash.listOwn(ctx, host)
                    else CloudTrash.Listing(CloudTrash.list(ctx, host), -1L)
                CloudTrash.sums[host] = CloudTrash.Sum(l.items.size, if (l.total >= 0) l.total else l.items.sumOf { maxOf(it.size, 0L) })
                if (l.items.isEmpty()) listOf(TK(Fm.str(R.string.tr_none), "Te|" + host, "", false, 0))
                else l.items.take(LIMIT).map { ti -> val kid = "Ti|" + host + "|" + ti.id; CloudTrash.tkItems[kid] = ti; TK(ti.name, kid, ti.origPath, ti.isDir, ti.size) }
            } else if (id.startsWith("A|")) {
                // «Приложения»: папка группы раскрывается в список приложений (первые 150, остальное счётчиком)
                val key = id.removePrefix("A|")
                val fs = AppsFs()
                val all = fs.list("apps://" + fs.dirName(key))
                appCount[id] = all.size
                all.take(150).map { TK(it.name, "Aitem|$key|" + it.path.substringAfterLast('/'), it.path) }
            } else if (id.startsWith("D|")) {
                // «ADB (оболочка)»: папки по ADB (нужна связь)
                FmIo.ensurePriv()?.let { if (it == "pair" || it == "wifi") Fm.autoPairOnce(); error("FIX:" + it) }
                val path = id.removePrefix("D|").ifEmpty { "/" }
                AdbFs.list(path).filter { it.isDir }.take(300).map { TK(it.name, "D|" + it.path, it.path) }
            } else if (id.startsWith("L|")) {
                val path = id.removePrefix("L|")
                val plain = withContext(Dispatchers.IO) { File(path).listFiles() }
                if ((plain == null || plain.isEmpty()) && FmIo.privEnabled && (plain == null || FmIo.restricted(path) || path.trimEnd('/').isEmpty())) {
                    // /data и другие закрытые места: список читает root (или ADB), как и в обычном списке «Файлов»
                    FmIo.ensurePriv()?.let { if (it == "pair" || it == "wifi") Fm.autoPairOnce(); error("FIX:" + it) }
                    val all = AdbFs.list(path).filter { !it.name.startsWith(".") }.sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })
                    appCount[id] = all.size
                    all.take(LIMIT).map { if (it.isDir) TK(it.name, "L|" + it.path, it.path) else TK(it.name, "F|" + it.path, it.path, false, it.size) }
                } else
                withContext(Dispatchers.IO) {
                    val all = File(path).listFiles().orEmpty().filter { !it.name.startsWith(".") }.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                    appCount[id] = all.size
                    all.take(LIMIT).map { if (it.isDirectory) TK(it.name, "L|" + it.absolutePath, it.absolutePath) else TK(it.name, "F|" + it.absolutePath, it.absolutePath, false, it.length()) }
                }
            } else {
                val a = id.split('|', limit = 5)
                val c = favs.firstOrNull { it.kind == a[1] && it.host == a[2] && it.user == a[3] } ?: error("?")
                val fs = fsCache.getOrPut(a[1] + "|" + a[2] + "|" + a[3]) { c.fs() }
                (fs as? KeepFs)?.connect(); (fs as? CloudFs)?.connect()
                val path = if (a.getOrNull(4) == NetTrash.ROOT_MARK) NetTrash.rootPath(c, fs) else a.getOrNull(4)?.takeIf { it.isNotEmpty() } ?: fs.root
                withContext(Dispatchers.IO) {
                    val all = fs.list(path)
                    appCount[id] = all.size
                    all.take(LIMIT).map { if (it.isDir) TK(it.name, "N|" + a[1] + "|" + a[2] + "|" + a[3] + "|" + it.path, it.path) else TK(it.name, "f|" + a[1] + "|" + a[2] + "|" + a[3] + "|" + it.path, it.path, false, it.size) }
                }
            }
            kids[id] = list; autoRetry.remove(id)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { kidErr[id] = FmIo.nice(e); if (e is CloudApiError && CloudErr.retryable(e.kind) && (autoRetry[id] ?: 0) < 3) { autoRetry[id] = (autoRetry[id] ?: 0) + 1; Fm.scope.launch { delay(15_000); if (kidErr[id] != null) kidErr.remove(id) } } else if (e !is CloudApiError) autoRetry.remove(id); if (e is CloudApiError && e.kind in setOf("quota", "disabled", "scope")) kidErrOwnKey[id] = e.type ?: "drive" else if (e is CloudApiError && e.kind == "expired") kidErrOwnKey[id] = (e.type ?: "drive") + "@publish" }
    }

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE)

    /** Сколько раскрытых узлов «домика» помнить между запусками (на панель). */
    const val MAX_OPEN = 60

    /** Восстановление раскрытых узлов панели. Сетевые, облачные узлы и ADB только запоминаются (rootPending) и раскрываются лениво, когда есть связь. false: ничего не сохранено. */
    fun restoreOpen(ctx: Context, p: FmPane): Boolean {
        val raw = sp(ctx).getString("ro_p${p.id}", null) ?: return false
        raw.split('\n').filter { it.isNotBlank() }.takeLast(MAX_OPEN).forEach { id -> if (id.startsWith("N|") || id.startsWith("D|") || id.startsWith("T|")) p.rootPending.add(id) else if (id !in p.rootOpen) p.rootOpen.add(id) }
        return true
    }

    fun saveOpen(ctx: Context, p: FmPane) {
        val all = (p.rootOpen.toList() + p.rootPending.filter { it !in p.rootOpen }).distinct()
        sp(ctx).edit().putString("ro_p${p.id}", all.takeLast(MAX_OPEN).joinToString("\n")).apply()
    }

    fun hasNetwork(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))
    }.getOrDefault(false)

    fun load(ctx: Context) {
        val saved = sp(ctx).getStringSet("root_show", null)?.toSet()
        shown = saved ?: optional.toSet()
        // новая группа «Приложения» видна по умолчанию и у тех, кто уже настраивал корень: один раз добавляется, дальше решает «Показать/Скрыть»
        if (saved != null && !sp(ctx).getBoolean("root_apps_added", false)) { shown = shown + "apps"; sp(ctx).edit().putStringSet("root_show", shown.toSet()).putBoolean("root_apps_added", true).apply() }
        else if (saved == null) sp(ctx).edit().putBoolean("root_apps_added", true).apply()
        // новая группа «Wi-Fi проводник»: один раз добавляется и у тех, кто уже настраивал корень
        if (saved != null && !sp(ctx).getBoolean("root_wifi_added", false)) { shown = shown + "wifi"; sp(ctx).edit().putStringSet("root_show", shown.toSet()).putBoolean("root_wifi_added", true).apply() }
        else if (saved == null) sp(ctx).edit().putBoolean("root_wifi_added", true).apply()
        // «Дерево на домике» включено по умолчанию и у тех, кто уже настраивал корень (один раз)
        if (saved != null && !sp(ctx).getBoolean("root_tree_added", false)) { shown = shown + "tree"; sp(ctx).edit().putStringSet("root_show", shown.toSet()).putBoolean("root_tree_added", true).apply() }
        else if (saved == null) sp(ctx).edit().putBoolean("root_tree_added", true).apply()
    }
    fun set(ctx: Context, key: String, on: Boolean) {
        shown = if (on) shown + key else shown - key
        sp(ctx).edit().putStringSet("root_show", shown.toSet()).apply()
    }
}

/** Показать корневой экран панели (как в X-plore: список памятей, сети, облаков). */
fun fmShowRoot(p: FmPane) {
    p.root = true; p.selected = emptySet()
    Fm.sctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE).edit().putBoolean("root_p${p.id}", true).apply()
}

fun fmLeaveRoot(p: FmPane) {
    if (!p.root) return
    p.root = false
    Fm.sctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE).edit().putBoolean("root_p${p.id}", false).apply()
}

/** Это корень источника: выше только корневой экран. */
fun fmIsSourceRoot(p: FmPane, path: String): Boolean {
    val t = path.trimEnd('/').ifEmpty { "/" }
    return when (p.src) {
        "local" -> t == "/sdcard" || t == "/storage/emulated/0" || t == "/" || Fm.vols.any { it.path.trimEnd('/') == t }
        "adb" -> t == "/"
        "smb" -> { val up = runCatching { p.smb?.parent(path) }.getOrNull(); up == null || up == path }
        else -> false
    }
}

/** Открыть папку на телефоне из корневого экрана. */
fun fmOpenLocal(p: FmPane, path: String) {
    fmLeaveRoot(p)
    if (p.src != "local") Fm.setSource(p, "local")
    Fm.load(p, path, remember = false)
}

private class RR(
    val key: String, val title: String, val icon: ImageVector, val tint: Color, val path: String? = null,
    val total: Long = 0, val free: Long = 0, val ro: Boolean = false, val fs: String = "", val warn: Boolean = false,
    val vol: FmVol? = null, val net: NetConn? = null, val level: Int = 0, val expandKey: String? = null, val header: Int = 0, val act: () -> Unit = {},
    val treeId: String? = null, val busy: Boolean = false, val appName: String? = null, val err: Boolean = false, val file: FmItem? = null, val side: String? = null,
    val tnet: NetConn? = null, val titem: CloudTrash.TItem? = null, val subText: String? = null, val drive: Boolean = false, val selE: FmRootCfg.SelE? = null,
    val fitem: FmTrash.FItem? = null, val ftrash: Boolean = false, val retry: (() -> Unit)? = null, val wifi: Boolean = false, val tog: Boolean? = null, val lpath: String? = null,
) {
    /** Ветви дерева: для уровней 1..level true, если ниже есть ещё элемент того же уровня; lx: позиция вертикали уровня (dp). */
    var more: BooleanArray = BooleanArray(0)
    var lx: FloatArray = FloatArray(0)
}

private fun statOf(path: String): Pair<Long, Long> = runCatching {
    val st = android.os.StatFs(path); (st.blockCountLong * st.blockSizeLong) to (st.availableBlocksLong * st.blockSizeLong)
}.getOrDefault(0L to 0L)

/** Корневой экран панели: строки-«папки» памятей, сети, облаков и прочих источников, как в X-plore. */
@Composable
fun FmRootList(p: FmPane, modifier: Modifier) {
    val ctx = LocalContext.current
    val shown = FmRootCfg.shown
    val favs = remember(Fm.dlg, p.root, FmRootCfg.ver, WifiExplorer.ver) { NetStore.list(ctx) + WifiExplorer.found.toList() }
    val tv = Marq.tv
    val prim = MaterialTheme.colorScheme.primary
    val (devTotal, devFree) = remember(p.root) { statOf("/sdcard") }
    val rows = ArrayList<RR>()
    rows += RR("h:mem", stringResource(R.string.rt_group_storage), Icons.Filled.PhoneAndroid, prim, header = 1)
    val treeOn = FmRootCfg.treeOn
    val openIds = p.rootOpen.toList()
    // облака с корзиной показывают две виртуальные папки («Корзина» и «Облачный диск»), сам узел облака папок не грузит
    val virtIds = favs.filter { CloudTrash.supported(CloudTrash.typeFor(ctx, it)) }.map { "N|" + it.kind + "|" + it.host + "|" + it.user + "|" }.toSet()
    val trRowTxt = stringResource(R.string.tr_row); val trDriveTxt = stringResource(R.string.tr_drive); val ntDriveTxt = stringResource(R.string.nt_drive)
    LaunchedEffect(openIds.joinToString(",") + "#" + CloudTrash.ver + "#" + FmTrash.ver, favs) {
        for (id in openIds) if ((id.startsWith("L|") || id == "K|" || id.startsWith("N|") || id.startsWith("A|") || id.startsWith("D|") || id.startsWith("T|") || id == "H|") && id !in virtIds && FmRootCfg.kids[id] == null && FmRootCfg.kidErr[id] == null) launch { FmRootCfg.loadKids(id, favs) }
    }
    // запомненные сетевые и ADB-узлы раскрываются лениво: только когда строка уже видна (родитель раскрыт) и есть связь
    val online = remember(p.root, favs) { FmRootCfg.hasNetwork(ctx) }
    val activate = ArrayList<String>()
    fun pend(id: String?) { if (id != null && id in p.rootPending && (if (id.startsWith("D|")) Adb.isConnected else online)) activate += id }
    LaunchedEffect(Unit) { androidx.compose.runtime.snapshotFlow { p.rootOpen.toList() }.collect { FmRootCfg.saveOpen(ctx, p) } }
    val loadingTxt = stringResource(R.string.rt_loading)
    /** Квадратик выбора для файла или папки узла [parent]: путь родителя берётся из идентификатора узла. */
    fun selOf(k: FmRootCfg.TK, parent: String, c: NetConn?): FmRootCfg.SelE? {
        val pp = if (parent.startsWith("L|")) parent.removePrefix("L|") else if (parent.startsWith("N|")) parent.split('|', limit = 5).getOrNull(4).orEmpty().let { if (it == NetTrash.ROOT_MARK && c != null) NetTrash.rootPath(c, FmRootCfg.fsFor(c)) else it }.ifEmpty { "/" } else return null
        return FmRootCfg.SelE(k.id, if (parent.startsWith("N|")) c else null, parent, pp, k.name, k.path, k.isDir, null)
    }
    fun addKids(id: String, level: Int, c: NetConn?) {
        if (!treeOn || id !in p.rootOpen) return
        val ks = FmRootCfg.kids[id]
        if (ks == null) {
            val er0 = FmRootCfg.kidErr[id]; val ok = FmRootCfg.kidErrOwnKey[id]
            // «отказано в доступе» (нет root): спокойная подсказка вместо красной ошибки
            if (er0 == CloudModule.MARK) {
                rows += RR("t:" + id + ":cm", Fm.str(R.string.cm_need), Icons.Filled.Cloud, Color(0xFF9E9E9E), level = level, subText = Fm.str(R.string.cm_tap_install),
                    act = { Fm.dlg = FmDlg.CloudModule { FmRootCfg.kidErr.remove(id); FmRootCfg.kids.remove(id); CloudTrash.ver++ } })
                return
            }
            // нет root и нет связи ADB: один понятный шаг (включить отладку по Wi-Fi или ввести код сопряжения) вместо слов «нужен root»
            if (er0 != null && er0.startsWith("FIX:")) {
                val wifi = er0 == "FIX:wifi"
                rows += RR("t:" + id + ":fix", Fm.str(if (wifi) R.string.fm_fix_wifi_text else R.string.fm_fix_pair_text), Icons.Filled.Folder, Color(0xFF9E9E9E), level = level,
                    subText = Fm.str(if (wifi) R.string.fm_fix_wifi_btn else R.string.fm_fix_pair_btn),
                    act = { FmRootCfg.kidErr.remove(id); FmRootCfg.kids.remove(id); CloudTrash.ver++; Fm.fixAction(p, if (wifi) "wifi" else "pair") })
                return
            }
            val noRoot = er0 != null && (er0.contains("denied", true) || er0.contains("отказано", true))
            val er = if (noRoot) Fm.str(R.string.ap_need_root) else er0
            val retry = { FmRootCfg.kidErr.remove(id); FmRootCfg.kids.remove(id); CloudTrash.ver++; Unit }
            val netFail = er != null && !noRoot && c != null
            rows += RR("t:" + id + ":st", if (netFail) Fm.str(R.string.fm_reconnect_fail, er) else er ?: loadingTxt, Icons.Filled.Folder, Color(0xFF9E9E9E), level = level, busy = er == null, err = er != null && !noRoot && !netFail,
                subText = if (netFail) Fm.str(R.string.rt_parked) else null, retry = if (er != null) retry else null,
                act = { if (ok != null) Fm.dlg = FmDlg.OwnKey(ok.substringBefore('@'), if (ok.endsWith("@publish")) OwnKeyWizard.PUBLISH_STEP else 0) else retry() })
            return
        }
        if (ks.isEmpty()) { rows += RR("t:" + id + ":empty", Fm.str(R.string.tr_none), Icons.Filled.Folder, Color(0xFF9E9E9E), level = level); return }
        ks.forEach { k ->
            if (k.id.startsWith("Ti|")) {
                val ti = CloudTrash.tkItems[k.id]
                val dirTxt = ti?.let { "/" + it.origPath.trim('/').substringBeforeLast('/', "") }
                val whenTxt = ti?.takeIf { it.deletedAt > 0 }?.let { java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it.deletedAt)) }
                rows += RR("t:" + k.id, k.name, if (k.isDir) Icons.Filled.Folder else iconFor(FmItem(k.name, false, 0, "")), if (k.isDir) Color(0xFFFFB74D) else Color(0xFF90A4AE), level = level, tnet = c, titem = ti,
                    side = if (k.size >= 0) fmSize(k.size) else null, subText = listOfNotNull(dirTxt, whenTxt).joinToString("  ·  "),
                    selE = ti?.let { FmRootCfg.SelE(k.id, c, id, "", k.name, k.path, k.isDir, it) })
                return@forEach
            }
            if (k.id.startsWith("Ki|")) {
                val fi = FmTrash.tk[k.id]
                val whenTxt = fi?.takeIf { it.deletedAt > 0 }?.let { java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it.deletedAt)) }
                rows += RR("t:" + k.id, k.name, if (k.isDir) Icons.Filled.Folder else iconFor(FmItem(k.name, false, 0, "")), if (k.isDir) Color(0xFFFFB74D) else Color(0xFF90A4AE), level = level, fitem = fi,
                    side = if (k.size >= 0) fmSize(k.size) else null, subText = listOfNotNull(fi?.folder?.let { shortMiddle(it, 24) }, whenTxt, fi?.volLabel).joinToString("  ·  "),
                    selE = fi?.let { FmRootCfg.SelE(k.id, null, id, "", k.name, k.path, k.isDir, null, it) })
                return@forEach
            }
            if (k.id.startsWith("Te|")) { rows += RR("t:" + k.id, k.name, Icons.Filled.Folder, Color(0xFF9E9E9E), level = level); return@forEach }
            if (!k.isDir) {
                val item = FmItem(k.name, false, k.size, "")
                rows += RR("t:" + k.id, k.name, iconFor(item), Color(0xFF90A4AE), level = level, file = item, lpath = if (id.startsWith("L|") && item.ext == "apk") k.path else null, act = { Fm.openFromTree(c, k.path, item) }, selE = selOf(k, id, c))
            } else if (k.id.startsWith("Aitem|")) {
                val key = k.id.split('|')[1]
                rows += RR("t:" + k.id, k.name, Icons.Filled.Android, Color(0xFF9CCC65), level = level, appName = k.name, act = { Fm.openApps(p, key) })
            } else if (k.id.startsWith("D|")) {
                rows += RR("t:" + k.id, k.name, Icons.Filled.Folder, Color(0xFF9CCC65), level = level, treeId = k.id,
                    act = { fmLeaveRoot(p); Fm.setSource(p, "adb"); Fm.load(p, k.path, remember = false) })
                pend(k.id)
                addKids(k.id, level + 1, c)
            } else {
                rows += RR("t:" + k.id, k.name, Icons.Filled.Folder, Color(0xFFFFB74D), level = level, treeId = k.id, selE = selOf(k, id, c),
                    act = { if (c == null) fmOpenLocal(p, k.path) else { fmLeaveRoot(p); Fm.connectNet(p, c, k.path) } })
                pend(k.id)
                addKids(k.id, level + 1, c)
            }
        }
        FmRootCfg.appCount[id]?.takeIf { it > ks.size }?.let { rows += RR("t:" + id + ":more", "… " + it, Icons.Filled.Folder, Color(0xFF9E9E9E), level = level) }
    }
    /** Раскрытое облако или сетевой источник с корзиной: две папки, «Корзина» первой и «диск» (корень источника) второй. */
    fun addVirt(c: NetConn, tid: String) {
        if (!(treeOn && tid in p.rootOpen)) return
        val rid = NetTrash.rid(c)
        val ttid = "T|" + rid
        rows += RR("t:" + ttid, trRowTxt, Icons.Filled.DeleteOutline, Color(0xFFEF5350), level = 2, treeId = ttid, tnet = c, side = CloudTrash.sumText(CloudTrash.sums[rid]), act = { Fm.active = p.id; Fm.dlg = FmDlg.Trash(c) })
        pend(ttid)
        addKids(ttid, 3, c)
        val did = if (c.kind == "cloud") tid + "/" else tid + NetTrash.ROOT_MARK
        rows += RR("t:" + did + ":drv", if (c.kind == "cloud") trDriveTxt else ntDriveTxt, Icons.Filled.Cloud, Color(0xFF4FC3F7), net = c, level = 2, treeId = did, drive = true, act = { fmLeaveRoot(p); Fm.connectNet(p, c) })
        pend(did)
        addKids(did, 3, c)
    }
    rows += RR("dev", stringResource(R.string.rt_internal), Icons.Filled.PhoneAndroid, Color(0xFF26A69A), "/sdcard", devTotal, devFree, act = { fmOpenLocal(p, "/sdcard") }, treeId = "L|/sdcard")
    addKids("L|/sdcard", 1, null)
    Fm.vols.forEach { v ->
        rows += RR("vol:" + v.path, v.label, if (v.usb) Icons.Filled.Usb else Icons.Filled.SdCard, Color(0xFF5C6BC0), if (v.ok) v.path else null, v.total, v.free, v.ro, v.fs, !v.ok, v,
            act = { if (v.ok) fmOpenLocal(p, v.path) else Fm.say(Fm.str(R.string.fm_vol_unsupported_short)) }, treeId = if (v.ok) "L|" + v.path else null)
        if (v.ok) addKids("L|" + v.path, 1, null)
    }
    if ("root" in shown) { rows += RR("root", "Root", Icons.Filled.PhoneAndroid, Color(0xFF8D6E63), "/", devTotal, devFree, act = { fmOpenLocal(p, "/") }, treeId = "L|/"); addKids("L|/", 1, null) }
    if ("recent" in shown) { rows += RR("recent", stringResource(R.string.rt_recent), Icons.Filled.History, Color(0xFFFFA726), act = { Fm.dlg = FmDlg.History() }, treeId = "H|"); addKids("H|", 1, null) }
    // «Корзина» файлового менеджера: рядом с «Недавними», число и размер по всем томам; раскрывается в список удалённого
    if (FmDel.trashOn || FmTrash.count > 0) {
        rows += RR("ftrash", stringResource(R.string.tr_row), Icons.Filled.DeleteOutline, Color(0xFFEF5350), side = FmTrash.sumText(), treeId = "K|", ftrash = true, act = { Fm.active = p.id; Fm.dlg = FmDlg.FTrash() })
        addKids("K|", 1, null)
    }
    LaunchedEffect(FmTrash.ver, FmDel.trashOn, Fm.vols.size) { FmTrash.refreshSum() }
    val lan = favs.filter { it.kind != "dav" && it.kind != "cloud" }
    val dav = favs.filter { (it.kind == "dav" && !WifiExplorer.isWifi(it)) || it.kind == "cloud" }
    val wifiSaved = favs.filter { WifiExplorer.isWifi(it) && it !in WifiExplorer.found }
    if ("lan" in shown || "cloud" in shown || "wifi" in shown || "adb" in shown) rows += RR("h:net", stringResource(R.string.rt_group_net), Icons.Filled.Lan, prim, header = 1)
    if ("lan" in shown) {
        rows += RR("lan", stringResource(R.string.rt_lan), Icons.Filled.Lan, Color(0xFF42A5F5), expandKey = "lan")
        if ("lan" in p.rootOpen) {
            lan.forEach { c ->
                val tid = "N|" + c.kind + "|" + c.host + "|" + c.user + "|"
                rows += RR("net:" + c.kind + c.host + c.user, c.title, Icons.Filled.Dns, Color(0xFF42A5F5), net = c, level = 1, act = { fmLeaveRoot(p); Fm.connectNet(p, c) }, treeId = tid)
                pend(tid)
                if (tid in virtIds) addVirt(c, tid) else addKids(tid, 2, c)
            }
            rows += RR("lan+", stringResource(R.string.rt_add_net), Icons.Filled.Add, prim, level = 1, act = { Fm.dlg = FmDlg.Source() })
        }
    }
    if ("cloud" in shown) {
        rows += RR("cloud", stringResource(R.string.rt_cloud), Icons.Filled.Cloud, Color(0xFF4FC3F7), expandKey = "cloud")
        if ("cloud" in p.rootOpen) {
            dav.forEach { c ->
                val tid = "N|" + c.kind + "|" + c.host + "|" + c.user + "|"
                rows += RR("net:" + c.kind + c.host + c.user, c.title, Icons.Filled.Cloud, Color(0xFF4FC3F7), net = c, level = 1, act = { fmLeaveRoot(p); Fm.connectNet(p, c) }, treeId = tid)
                pend(tid)
                if (tid in virtIds) addVirt(c, tid) else addKids(tid, 2, c)
            }
            rows += RR("cloud+", stringResource(R.string.rt_add_cloud), Icons.Filled.Add, prim, level = 1, act = { Fm.dlg = FmDlg.CloudAdd() })
        }
    }
    // «Wi-Fi проводник»: найденные поиском, свой сервер (галочка), сохранённые устройства, «Добавить устройство» (запускает поиск)
    if ("wifi" in shown) {
        val wfTint = if (WifiShare.running) Color(0xFF2E9E4F) else Color(0xFFF57C00)   // сервер включён: зелёный, выключен: оранжевый
        LaunchedEffect(Unit) { WifiExplorer.load(ctx) }
        rows += RR("wifi", stringResource(R.string.rt_wifi), Icons.Filled.Wifi, wfTint, expandKey = "wifi",
            subText = stringResource(R.string.wf_mode_label, stringResource(when (WifiExplorer.mode) { "lan" -> R.string.wf_mode_lan; "direct" -> R.string.wf_mode_direct; else -> R.string.wf_mode_auto })))
        if ("wifi" in p.rootOpen) {
            val needKeyTxt = stringResource(R.string.wf_need_key)
            val srvOn = stringResource(R.string.wf_on_sub, WifiShare.url); val srvOff = stringResource(R.string.wf_off)
            if (WifiExplorer.scanning) rows += RR("wifi:scan", stringResource(R.string.wf_scanning, WifiExplorer.progress), Icons.Filled.Search, prim, level = 1, busy = true, subText = stringResource(R.string.wf_cancel_hint), act = { WifiExplorer.cancel() })
            fun devRow(c: NetConn, saved: Boolean) {
                val tid = "N|" + c.kind + "|" + c.host + "|" + c.user + "|"
                rows += RR("wifi:" + (if (saved) "s:" else "f:") + c.host, c.name.ifBlank { WifiExplorer.hostText(c) }, Icons.Filled.PhoneAndroid, if (saved) Color(0xFF26A69A) else Color(0xFFFFC107), net = c, level = 1, wifi = true,
                    subText = WifiExplorer.hostText(c) + (if (!saved && WifiExplorer.needKey[c.host] == true) "  ·  " + needKeyTxt else ""), treeId = tid, act = { fmLeaveRoot(p); Fm.connectNet(p, c) })
                if (saved) pend(tid)
                addKids(tid, 2, c)
            }
            WifiExplorer.found.toList().forEach { devRow(it, false) }
            rows += RR("wifi:srv", stringResource(R.string.wf_server_row), Icons.Filled.Wifi, Color(0xFF29B6F6), level = 1, tog = WifiShare.running, subText = if (WifiShare.running) srvOn else srvOff, act = { WifiExplorer.toggleServer(ctx) })
            wifiSaved.forEach { devRow(it, true) }
            rows += RR("wifi+", stringResource(R.string.wf_add), Icons.Filled.Add, prim, level = 1, act = { WifiExplorer.scan(ctx) })
        }
    }
    // число в строках «Корзина»: когда папки облака уже показаны, по одному облаку за раз
    val sumTodo = if (treeOn) ((if ("cloud" in shown && "cloud" in p.rootOpen) dav else emptyList()) + (if ("lan" in shown && "lan" in p.rootOpen) lan else emptyList())).filter { c ->
        val tid = "N|" + c.kind + "|" + c.host + "|" + c.user + "|"
        tid in p.rootOpen && CloudTrash.sums[NetTrash.rid(c)] == null && CloudTrash.supported(CloudTrash.typeFor(ctx, c))
    }.map { NetTrash.rid(it) } else emptyList()
    LaunchedEffect(sumTodo.joinToString(",")) { if (sumTodo.any { NetTrash.isRid(it) }) NetTrash.ensureAll(ctx); for (h in sumTodo) { CloudTrash.refreshSum(ctx, h); kotlinx.coroutines.delay(800) } }
    if ("apps" in shown) {
        rows += RR("apps", stringResource(R.string.rt_apps), Icons.Filled.Android, Color(0xFF9CCC65), expandKey = "apps")
        if ("apps" in p.rootOpen) AppsFs.keys.forEach { (k, res) -> rows += RR("apps:" + k, stringResource(res), Icons.Filled.Folder, Color(0xFF9CCC65), level = 1, act = { Fm.openApps(p, k) }, treeId = "A|" + k); addKids("A|" + k, 2, null) }
    }
    if ("adb" in shown) { rows += RR("adb", stringResource(R.string.rt_adb), Icons.Filled.Android, Color(0xFF9CCC65), act = { fmLeaveRoot(p); Fm.setSource(p, "adb") }, treeId = "D|/"); pend("D|/"); addKids("D|/", 1, null) }
    rows += RR("showhide", stringResource(R.string.rt_showhide), Icons.Filled.Settings, Color(0xFF78909C), act = { Fm.dlg = FmDlg.RootShow() })

    // число папок и файлов в корнях томов: в фоне
    LaunchedEffect(Fm.vols.size, p.root) {
        val paths = listOf("/sdcard") + Fm.vols.filter { it.ok }.map { it.path } + (if ("root" in shown) listOf("/") else emptyList())
        for (pa in paths) {
            if (FmRootCfg.counts.containsKey(pa)) continue
            val c = withContext(Dispatchers.IO) { runCatching { File(pa).listFiles()?.let { l -> l.count { it.isDirectory && it.name != FmTrash.DIR } to l.count { it.isFile } } }.getOrNull() }
            if (c != null) FmRootCfg.counts[pa] = c
        }
    }
    LaunchedEffect(activate.joinToString(",")) {
        for (id in activate.filter { it.startsWith("D|") }) { p.rootPending.remove(id); if (id !in p.rootOpen) p.rootOpen.add(id) }
        // облака и сеть, бывшие раскрытыми: перечитываются по одному (MEGA не любит параллельные запросы) и не сразу после запуска
        for (id in activate.filter { !it.startsWith("D|") }) {
            delay((FmRootCfg.t0 + 1500L - System.currentTimeMillis()).coerceAtLeast(300L))
            p.rootPending.remove(id); if (id !in p.rootOpen) p.rootOpen.add(id)
            kotlinx.coroutines.withTimeoutOrNull(20000L) { while (id !in virtIds && FmRootCfg.kids[id] == null && FmRootCfg.kidErr[id] == null) delay(250) }
        }
    }
    LaunchedEffect(Unit) { androidx.compose.runtime.snapshotFlow { p.rootSel }.collect { s -> if (s != null) ctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE).edit().putString("root_sel_p${p.id}", s).apply() } }
    for (i in rows.indices) {
        val lv = rows[i].level
        if (lv <= 0 || rows[i].header == 1) continue
        rows[i].more = BooleanArray(lv) { idx ->
            val k = idx + 1
            var j = i + 1
            var r = false
            while (j < rows.size) {
                val rj = rows[j]
                if (rj.header == 1 || rj.level < k) break
                if (rj.level == k) { r = true; break }
                j++
            }
            r
        }
        rows[i].lx = FloatArray(lv) { idx ->
            val k = idx + 1
            var j = i - 1
            while (j >= 0 && !(rows[j].level == k - 1 && rows[j].header == 0)) j--
            val groupParent = j >= 0 && rows[j].expandKey != null
            (8 + 22 * (k - 1) + (if (groupParent) 9 else if (tv) 15 else 13)).toFloat()
        }
    }
    Column(modifier.then(Modifier.fillMaxWidth()).onPreviewKeyEvent { e ->
        // пульт: «Меню» или «Info» при отмеченном переводит фокус на нижнюю панель значков
        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && FmRootCfg.sel.isNotEmpty() && (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU || e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_INFO)) { runCatching { FmRootCfg.barFocus.requestFocus() }.isSuccess } else false
    }) {
        val rootState = rememberSavedLazyList("files.root." + p.id)
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight().verticalScrollbar(rootState)
            // пульт: «вниз» с последней строки списка уходит на нижнюю панель значков (если есть отмеченное), а не на нижнюю навигацию
            .focusProperties { exit = { d -> if (d == androidx.compose.ui.focus.FocusDirection.Down && FmRootCfg.sel.isNotEmpty()) FmRootCfg.barFocus else androidx.compose.ui.focus.FocusRequester.Default } }.focusGroup(), state = rootState, contentPadding = sbPadding(), verticalArrangement = Arrangement.spacedBy(if (tv) 2.dp else 0.dp)) {
            items(rows, key = { it.key }) { r -> FmRootRowView(p, r, tv) }
        }
        ScrollbarHandle(rootState, Modifier.align(if (scrollbarLeft()) Alignment.CenterStart else Alignment.CenterEnd))
        }
        androidx.compose.runtime.SideEffect { FmRootCfg.lastVisKey = rootState.layoutInfo.visibleItemsInfo.lastOrNull()?.key as? String }
        if (FmRootCfg.sel.isNotEmpty()) RootSelBar(p)
    }
}

/**
 * Панель групповых действий над отмеченным в дереве «домика»: ОДНА строка из значков (подпись по долгому нажатию и в описании для чтения с экрана).
 * Слева кружок с числом отмеченного и «Снять выбор»; справа (прокручивается, если не помещается): для обычных файлов «Копировать», «Перенести»,
 * «Удалить»; для элементов «Корзины» «Восстановить» и «Удалить навсегда» (красные значки у удаления).
 */
@Composable
internal fun RootSelBar(p: FmPane) {
    val ctx = LocalContext.current
    val focusManager = LocalFocusManager.current
    val items = FmRootCfg.sel.values.toList()
    val trash = items.filter { it.ti != null || it.fi != null }
    val files = items.filter { it.ti == null && it.fi == null }
    val red = Color(0xFFD84343)
    var confirmForever by remember { mutableStateOf(false) }
    fun pseudo(e: FmRootCfg.SelE, path: String): FmPane = FmPane(9, path).also { ps ->
        if (e.net != null) { ps.src = "smb"; ps.smb = FmRootCfg.fsFor(e.net); ps.net = e.net }
    }
    /** После заданий дерево перечитывается: подождать начала и конца задания и сбросить родительские узлы. */
    fun refreshLater(parents: List<String>, hosts: List<String>) {
        Fm.scope.launch {
            var started = false; var n = 0
            while (n < 800) { kotlinx.coroutines.delay(150); n++; if (Fm.jobs.isNotEmpty()) started = true else if (started) break }
            kotlinx.coroutines.delay(800)
            parents.distinct().forEach { FmRootCfg.kids.remove(it); FmRootCfg.kidErr.remove(it) }
            hosts.distinct().forEach { CloudTrash.invalidate(it) }
            CloudTrash.ver++
        }
    }
    fun transfer(move: Boolean) {
        val o = Fm.panes[1 - p.id]
        if (o.root) { Fm.say(Fm.str(R.string.sel_need_target)); return }
        val groups = files.groupBy { (it.net?.let { n -> n.kind + "|" + n.host + "|" + n.user } ?: "L") + "#" + it.parentPath }
        val par = files.map { it.parentId }
        Fm.scope.launch {
            for ((_, g) in groups) { Fm.copyMove(move, pseudo(g[0], g[0].parentPath), o, g.map { it.name }); kotlinx.coroutines.delay(700); while (Fm.dlg is FmDlg.Conflict) kotlinx.coroutines.delay(300) }
        }
        FmRootCfg.sel.clear(); refreshLater(par, emptyList())
    }
    fun deleteFiles() {
        val srcs = files.map { it.net?.let { n -> n.kind + "|" + n.host + "|" + n.user } ?: "L" }.distinct()
        if (srcs.size != 1) { Fm.say(Fm.str(R.string.sel_one_source)); return }
        val first = files[0]
        if (first.net != null && first.net.kind != "cloud" && !NetTrash.supported(first.net)) { Fm.say(Fm.str(R.string.nt_none)); return }
        // одна «тень» панели на корне источника, имена от корня: так удаляется отмеченное из разных папок разом
        val par = files.map { it.parentId }
        Fm.askDelete(pseudo(first, "/"), files.map { it.path.trimStart('/') })
        FmRootCfg.sel.clear(); refreshLater(par, emptyList())
    }
    fun doForever() {
        val list = trash.toList(); val hosts = list.mapNotNull { it.net?.let { n -> NetTrash.rid(n) } }
        FmRootCfg.sel.clear()
        Fm.scope.launch {
            for (e in list) { if (e.fi != null) FmTrash.purge(e.fi)?.let { err -> Fm.say(err) } else e.net?.let { n -> CloudTrash.purgeAny(ctx, NetTrash.rid(n), e.ti!!)?.let { err -> Fm.say(err) } } }
            hosts.distinct().forEach { CloudTrash.invalidate(it) }
            if (list.any { it.fi != null }) { FmTrash.bump(); FmTrash.refreshSum() }
        }
    }
    val foreverOk = trash.isNotEmpty() && files.isEmpty()
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary.copy(alpha = .14f)).padding(horizontal = 4.dp).height(48.dp).onPreviewKeyEvent { e ->
            // пульт: «Назад» на панели возвращает фокус на список
            if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.Back) { focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up); true } else false
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // кружок с числом и «Снять выбор»
        Box(Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Text(items.size.toString(), fontSize = if (items.size > 99) 10.sp else 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary, maxLines = 1)
        }
        SelIcon(Icons.Filled.Deselect, R.string.sb_clear, MaterialTheme.colorScheme.primary, FmRootCfg.barFocus) { FmRootCfg.sel.clear() }
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (files.isNotEmpty() && trash.isEmpty()) {
                SelIcon(Icons.Filled.ContentCopy, R.string.sb_copy, MaterialTheme.colorScheme.primary) { transfer(false) }
                SelIcon(Icons.Filled.DriveFileMove, R.string.sb_move, MaterialTheme.colorScheme.primary) { transfer(true) }
                if (files.all { it.net == null }) SelIcon(Icons.Filled.Lock, R.string.perm_title, MaterialTheme.colorScheme.primary) {
                    Fm.dlg = FmDlg.Perms(FmPane(9, "/").also { it.src = "local" }, files.map { it.path.removePrefix("/") })
                }
                SelIcon(Icons.Filled.DeleteOutline, R.string.sb_delete, red) { deleteFiles() }
            }
            if (trash.isNotEmpty() && files.isEmpty()) {
                SelIcon(Icons.Filled.RestoreFromTrash, R.string.sb_restore, MaterialTheme.colorScheme.primary) {
                    val hosts = trash.mapNotNull { it.net?.let { n -> NetTrash.rid(n) } }
                    trash.forEach { e -> if (e.ti != null) e.net?.let { n -> CloudTrash.restoreFromTree(ctx, n, e.ti) } }
                    val locals = trash.mapNotNull { it.fi }
                    if (locals.isNotEmpty()) Fm.scope.launch {
                        var ok = 0
                        for (fi in locals) { val er = FmTrash.restore(fi); if (er != null) Fm.say(er) else ok++ }
                        if (ok > 0) Fm.say(Fm.str(R.string.tr_restored))
                        FmTrash.bump(true, *locals.map { File(it.origPath).parent.orEmpty() }.toTypedArray()); FmTrash.refreshSum()
                    }
                    FmRootCfg.sel.clear(); refreshLater(emptyList(), hosts)
                }
                if (foreverOk) SelIcon(Icons.Filled.DeleteForever, R.string.sb_forever, red) { if (FmDel.needAsk(true)) confirmForever = true else doForever() }
            }
        }
    }
    if (confirmForever) LAlertDialog(
        onDismissRequest = { confirmForever = false },
        title = { Text(stringResource(R.string.dd_t_perm, if (trash.size == 1) trash[0].name else stringResource(R.string.dd_items, trash.size))) },
        text = { Text(stringResource(R.string.dd_s_perm), color = red, fontWeight = FontWeight.SemiBold) },
        confirmButton = {
            androidx.compose.material3.TextButton({ confirmForever = false; doForever() }) { Text(stringResource(R.string.sb_forever), color = red) }
        },
        dismissButton = { androidx.compose.material3.TextButton({ confirmForever = false }) { Text(stringResource(R.string.btn_cancel)) } },
    )
}

/**
 * Значок-кнопка панели выбора: цель касания 44 dp, значок 26 dp. Короткий тап выполняет действие; пока значок держат пальцем, на нём мышь или на нём фокус пульта,
 * НАД значком висит облачко с названием действия (удержание действие не выполняет, чтобы не удалить случайно); Enter/ОК на сфокусированном значке выполняет действие.
 */
@Composable
private fun SelIcon(icon: ImageVector, label: Int, tint: Color, focusReq: androidx.compose.ui.focus.FocusRequester? = null, onClick: () -> Unit) {
    val name = stringResource(label)
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val focused by src.collectIsFocusedAsState()
    var held by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val show = held || hovered || focused
    Box(
        Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = .22f) else Color.Transparent)
            .hoverable(src).then(if (focusReq != null) Modifier.focusRequester(focusReq) else Modifier).focusable(interactionSource = src)
            .onKeyEvent { e ->
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp && (e.key == androidx.compose.ui.input.key.Key.DirectionCenter || e.key == androidx.compose.ui.input.key.Key.Enter || e.key == androidx.compose.ui.input.key.Key.NumPadEnter)) { onClick(); true } else false
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { val job = scope.launch { kotlinx.coroutines.delay(250); held = true }; try { tryAwaitRelease() } finally { job.cancel(); held = false } },
                    onLongPress = { },           // удержание только показывает подсказку
                    onTap = { onClick() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, name, Modifier.size(26.dp), tint = tint)
        if (show) androidx.compose.ui.window.Popup(
            popupPositionProvider = object : androidx.compose.ui.window.PopupPositionProvider {
                override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize, layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize): androidx.compose.ui.unit.IntOffset {
                    val m = 8
                    val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(m, maxOf(m, windowSize.width - popupContentSize.width - m))
                    val above = anchorBounds.top - popupContentSize.height - 6
                    val y = if (above >= m) above else anchorBounds.bottom + 6
                    return androidx.compose.ui.unit.IntOffset(x, y)
                }
            },
            properties = androidx.compose.ui.window.PopupProperties(focusable = false),
        ) {
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.inverseSurface).padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(name, fontSize = 13.sp, color = MaterialTheme.colorScheme.inverseOnSurface, maxLines = 1)
            }
        }
    }
}

/** Перетаскивание из дерева корневого экрана: отмеченные локальные строки (или только эта) берутся от общей папки, имена относительно неё. */
/** Раскрытой остаётся только цепочка к последней открытой папке; открыл другой источник (память, карта, сеть, облако и др.): прежний сворачивается. */
private fun rootAccordion(p: FmPane, id: String) {
    fun kind(o: String) = o.take(2)
    fun src(o: String) = when (kind(o)) { "N|" -> o.split('|').take(4).joinToString("|"); "L|", "D|" -> kind(o); else -> o }
    fun path(o: String) = when (kind(o)) { "N|" -> o.split('|', limit = 5).getOrNull(4).orEmpty(); "L|", "D|" -> o.drop(2); else -> "" }
    fun isAncestor(o: String): Boolean {
        if (o == id || src(o) != src(id) || (kind(o) != "L|" && kind(o) != "N|" && kind(o) != "D|")) return false
        val po = path(o).trimEnd('/')
        if (kind(o) == "N|" && (po.isEmpty() || po == NetTrash.ROOT_MARK)) return true     // корень сетевого источника: предок всего внутри
        if (po.isEmpty()) {
            // строка «Root» (путь «/»): предок всего, что внутри неё; память устройства и тома живут своими строками, их открытие Root сворачивает
            if (kind(o) != "L|" || kind(id) != "L|") return false
            val pid = path(id).trimEnd('/')
            val own = listOf("/sdcard") + Fm.vols.filter { it.ok }.map { it.path.trimEnd('/') }
            return own.none { pid == it || pid.startsWith("$it/") }
        }
        return path(id).trimEnd('/').startsWith("$po/")
    }
    val drop = p.rootOpen.filter { o -> o != id && !isAncestor(o) }
    p.rootOpen.removeAll(drop.toSet())
}

private fun sameNet(a: NetConn?, b: NetConn?): Boolean = if (a == null || b == null) a == null && b == null else a.kind == b.kind && a.host == b.host && a.user == b.user

private fun rootDragStart(p: FmPane, e: FmRootCfg.SelE, at: androidx.compose.ui.geometry.Offset, pid: Long) {
    val chosen = FmRootCfg.sel.values.filter { it.ti == null && it.fi == null && sameNet(it.net, e.net) }
    val list = if (e.key in FmRootCfg.sel && chosen.isNotEmpty()) chosen else listOf(e)
    val parents = list.map { it.path.substringBeforeLast('/', "").ifEmpty { "/" } }
    var base = parents.first().trimEnd('/').split('/')
    for (pp in parents.drop(1)) { val seg = pp.trimEnd('/').split('/'); base = base.zip(seg).takeWhile { it.first == it.second }.map { it.first } }
    val baseP = base.joinToString("/").ifEmpty { "/" }
    val names = list.map { it.path.removePrefix(baseP.trimEnd('/') + "/") }
    val vp = FmPane(8, baseP).also { if (e.net == null) it.src = "local" else { it.src = "smb"; it.net = e.net; it.smb = e.net.fs() } }
    FmDnd.beginNames(vp, FmItem(e.name, e.isDir, 0, ""), at, pid, names)
}

@Composable
private fun FmRootRowView(p: FmPane, r: RR, tv: Boolean) {
    val prim = MaterialTheme.colorScheme.primary
    if (r.header == 1) {
        Text(r.title, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 1.dp), fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold, color = prim)
        return
    }
    val ctx = LocalContext.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    var menu by remember { mutableStateOf(false) }
    // перетаскивание строк дерева корневого экрана (локальные файлы и папки): источник и цель-папка
    val e0 = r.selE
    val dragSrc = if (e0 != null && e0.ti == null && e0.fi == null && r.fitem == null) e0 else null
    val treeDropPath = if (dragSrc != null && dragSrc.isDir) dragSrc.path else null
    // сетевой источник или облако: цель сброса и источник тянутся через панель-«двойник» с файловой системой этого подключения
    val dropPane = remember(dragSrc?.net) { val nc = dragSrc?.net; if (nc == null) p else FmPane(7, dragSrc.path).also { it.src = "smb"; it.net = nc; it.smb = nc.fs() } }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var rowCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val hot = (r.path != null && r.net == null && dndHot(p, r.path, 3)) || (treeDropPath != null && dndHot(dropPane, treeDropPath, 6))
    val selected = p.rootSel == r.key
    val hoveredRow by src.collectIsHoveredAsState()
    val pressedRow by src.collectIsPressedAsState()
    val rowActive = selected || focused || hoveredRow || pressedRow
    val expanded = r.expandKey != null && r.expandKey in p.rootOpen
    val cnt = r.path?.let { FmRootCfg.counts[it] }
    val bigFont = if (tv) 1.15f else 1f
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .22f)
    val treeToggle = { id: String -> p.rootPending.remove(id); if (id in p.rootOpen) p.rootOpen.remove(id) else { rootAccordion(p, id); p.rootOpen.add(id); FmRootCfg.refresh(p, id, NetStore.list(ctx)) } }
    Box2(
        Modifier.fillMaxWidth().onKeyEvent { e ->
            val id = r.treeId
            if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.DirectionDown && FmRootCfg.sel.isNotEmpty() && r.key == FmRootCfg.lastVisKey) { runCatching { FmRootCfg.barFocus.requestFocus() }.isSuccess }
            else if (id == null || !FmRootCfg.treeOn || e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false
            else if (e.key == androidx.compose.ui.input.key.Key.DirectionRight && id !in p.rootOpen) { treeToggle(id); true }
            else if (e.key == androidx.compose.ui.input.key.Key.DirectionLeft && id in p.rootOpen) { treeToggle(id); true }
            else false
        }.then(if (r.path != null && r.vol?.ok != false) Modifier.dndTarget(p, r.path, 3, r.title, "local") else Modifier)
            .then(if (treeDropPath != null) Modifier.dndTarget(dropPane, treeDropPath, 6, r.title, if (dragSrc?.net != null) "smb" else "local") else Modifier)
            .onGloballyPositioned { rowCoords = it }
            // выбор: плашка цвета интерфейса и полоска слева; фокус пульта или клавиатуры: рамка (выбор и фокус различаются)
            .drawBehind {
                // линии ветвей дерева: вертикали предков и соединитель к своей строке (тонкие, низкая непрозрачность)
                val lv = r.level
                if (lv > 0 && r.more.size == lv && r.lx.size == lv) {
                    val w = 1.dp.toPx()
                    val ext = if (tv) 2.dp.toPx() else 0f
                    for (k in 1..lv) {
                        val x = r.lx[k - 1].dp.toPx()
                        if (k < lv) {
                            if (r.more[k - 1]) drawLine(lineColor, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height + ext), w)
                        } else {
                            drawLine(lineColor, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, if (r.more[k - 1]) size.height + ext else size.height / 2), w)
                            val hasChev = r.treeId != null && FmRootCfg.treeOn
                            drawLine(lineColor, androidx.compose.ui.geometry.Offset(x, size.height / 2), androidx.compose.ui.geometry.Offset((8 + 22 * lv + (if (hasChev) 4 else 0) - 1).dp.toPx(), size.height / 2), w)
                        }
                    }
                }
            }
            .background(if (hot) prim.copy(alpha = .2f) else if (selected) prim.copy(alpha = .22f) else Color.Transparent, RoundedCornerShape(8.dp))
            .drawBehind { if (selected) drawRect(prim, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)) }
            .then(if (focused) Modifier.border((if (tv) 3 else 2).dp, prim, RoundedCornerShape(8.dp)) else Modifier)
            .then(if (dragSrc != null) Modifier.pointerInput(r.key, FmDnd.on) {
                // перетаскивание: сенсор (долгое касание, затем тянуть; отпустить без движения = меню), мышь (зажать левую и тянуть)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val mouse = down.type == androidx.compose.ui.input.pointer.PointerType.Mouse
                    if (mouse && currentEvent.buttons.isSecondaryPressed) return@awaitEachGesture
                    val slop = viewConfiguration.touchSlop
                    fun beginDrag(pos: androidx.compose.ui.geometry.Offset) {
                        Fm.active = p.id; p.rootSel = r.key
                        rootDragStart(p, dragSrc, rowCoords?.localToRoot(pos) ?: pos, down.id.value)
                    }
                    if (!mouse) {
                        var early = false
                        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (!early) {
                                val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                val ch = ev.changes.firstOrNull { it.id == down.id }
                                if (ch == null || !ch.pressed || (ch.position - down.position).getDistance() > slop * 3f) early = true
                            }
                        }
                        if (early) return@awaitEachGesture
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        val start = down.position
                        while (true) {
                            val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            ch.consume()
                            if (!ch.pressed) { Fm.active = p.id; p.rootSel = r.key; menu = true; break }
                            if (FmDnd.on && (ch.position - start).getDistance() > slop) { beginDrag(ch.position); return@awaitEachGesture }
                        }
                    } else if (FmDnd.on && currentEvent.buttons.isPrimaryPressed) {
                        val start = down.position
                        while (true) {
                            val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if ((ch.position - start).getDistance() > slop) { ch.consume(); beginDrag(ch.position); return@awaitEachGesture }
                        }
                    }
                }
            } else Modifier)
            .hoverable(src).combinedClickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current,
                onClick = { Fm.active = p.id; p.rootSel = r.key; if (r.titem != null || r.fitem != null) menu = true else if (r.expandKey != null) { if (r.expandKey in p.rootOpen) p.rootOpen.remove(r.expandKey) else p.rootOpen.add(r.expandKey) } else if (r.treeId != null && FmRootCfg.treeOn && r.file == null) treeToggle(r.treeId) else r.act() },
                onLongClick = { Fm.active = p.id; p.rootSel = r.key; menu = true })
            .heightIn(min = if (tv) 52.dp else 38.dp).padding(start = 8.dp + (r.level * 22).dp, end = 8.dp, top = if (tv) 3.dp else 2.dp, bottom = if (tv) 3.dp else 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.expandKey != null) Icon(if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
            else if (r.treeId != null && FmRootCfg.treeOn) Icon(if (r.treeId in p.rootOpen) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null, Modifier.size(if (tv) 30.dp else 26.dp).clickable { treeToggle(r.treeId) }.padding(4.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .8f))
            else if (FmRootCfg.treeOn && r.level > 0 && r.header == 0) Spacer(Modifier.size(if (tv) 30.dp else 26.dp))      // место под шеврон: значок и подпись встают правее значка родителя
            if (r.busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            if (r.appName != null) AppsRowIcon(r.appName, if (tv) 32.dp else 28.dp) else if (r.drive) FolderIcon(UiPrefs.folderDefault, r.treeId != null && r.treeId in p.rootOpen, if (tv) 32.dp else 28.dp) else if (r.file != null && r.lpath != null) FileTypeIcon(r.file.name, r.file.ext, r.lpath, if (tv) 32.dp else 28.dp) else if (r.net != null && !r.wifi) NetBrandIcon(r.net, if (CloudCatalog.bigRows) (if (tv) 44.dp else 40.dp) else if (tv) 32.dp else 28.dp) else Icon(if (r.warn || r.err) Icons.Filled.Warning else r.icon, null, Modifier.size(if (tv) 32.dp else 28.dp), tint = if (r.warn || r.err) MaterialTheme.colorScheme.error else r.tint)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (r.err) Text(r.title, Modifier.weight(1f), fontSize = (13f * bigFont).sp, fontWeight = FontWeight.Normal, maxLines = 9, softWrap = true, fontFamily = UiPrefs.listFont, color = MaterialTheme.colorScheme.error)
                    else MqText(r.title, active = rowActive, wrap = true, modifier = Modifier.weight(1f), fontSize = (15f * bigFont).sp, fontWeight = FontWeight.Bold, fontFamily = UiPrefs.listFont)
                    r.net?.let { nc -> Fm.panes.firstNotNullOfOrNull { pp -> (pp.smb as? KeepFs)?.takeIf { pp.src == "smb" && pp.net == nc } }?.let { NetDot(it) } }
                }
                // у томов первым свободное место (то, что нужно видеть), затем файловая система, счётчики папок и файлов в конце
                val svc = r.net?.takeIf { !r.drive && r.tnet == null }?.let { CloudIcons.serviceCaption(ctx, it) }
                val offlinePending = r.treeId != null && (r.net != null || r.tnet != null) && r.treeId in p.rootPending && r.treeId !in p.rootOpen && !remember { FmRootCfg.hasNetwork(ctx) }
                val sub = listOfNotNull(
                    svc, if (offlinePending) stringResource(R.string.rt_parked) else null,
                    if (r.total > 0) stringResource(R.string.rt_free, fmGb(r.free), fmGb(r.total)) else null,
                    r.fs.takeIf { it.isNotBlank() }, if (r.ro) stringResource(R.string.rt_ro) else null, if (r.warn) stringResource(R.string.fm_vol_unsupported_short) else null,
                    r.file?.let { fmSize(it.size) }, r.subText?.takeIf { it.isNotEmpty() }, r.path?.takeIf { r.net == null && r.file == null && r.treeId == null },
                    cnt?.let { stringResource(R.string.rt_counts, it.first, it.second) },
                ).joinToString("  ·  ")
                if (sub.isNotEmpty()) MqText(sub, active = rowActive, modifier = Modifier.fillMaxWidth(), fontSize = (11 * bigFont).sp, lineHeight = (13 * bigFont).sp, fontFamily = UiPrefs.listFont, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                if (r.total > 0) FmSpaceBar(1f - r.free.toFloat() / r.total, Modifier.fillMaxWidth().padding(top = 1.dp))
            }
            if (r.ro) Icon(Icons.Filled.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
            r.tog?.let { on -> androidx.compose.material3.Checkbox(on, { r.act() }, Modifier.size(if (tv) 40.dp else 32.dp)) }
            r.side?.let { Text(it, fontSize = (13 * bigFont).sp, maxLines = 1, fontFamily = UiPrefs.listFont, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f)) }
            r.selE?.let { e -> androidx.compose.material3.Checkbox(FmRootCfg.sel.containsKey(e.key), { Fm.active = p.id; FmRootCfg.toggleSel(e) }, Modifier.size(if (tv) 40.dp else 32.dp)) }
        }
        LDropdownMenu(menu, { menu = false }) {
            @Composable fun item(l: Int, run: () -> Unit) = DropdownMenuItem({ Text(stringResource(l), fontSize = 14.sp) }, { menu = false; run() })
            r.retry?.let { rt -> item(R.string.rt_reconnect) { rt() } }
            if ((r.net != null || r.tnet != null) && r.treeId != null && r.treeId in p.rootPending && r.treeId !in p.rootOpen) item(R.string.rt_reconnect) { treeToggle(r.treeId) }
            if (r.appName != null) AppMenuItems(p, FmItem(r.appName, false, 0, ""), Fm.panes[1 - p.id], copy = false) { menu = false }
            else if (r.expandKey == null && r.header == 0 && r.tnet == null && !r.ftrash && r.fitem == null) item(if (r.treeId != null && FmRootCfg.treeOn && r.file == null) R.string.fm_open_panel else R.string.fm_open) { r.act() }
            // полное меню файла и папки на корневом экране (как в списке панели): отметка, копирование, права доступа и так далее
            r.selE?.takeIf { it.net == null && it.ti == null && it.fi == null && r.appName == null }?.let { e ->
                val multiSel = e.key in FmRootCfg.sel && FmRootCfg.sel.size > 1
                val tg = if (multiSel) FmRootCfg.sel.values.filter { it.net == null && it.ti == null && it.fi == null } else listOf(e)
                fun pane(path: String): FmPane = FmPane(9, path).also { it.src = "local" }
                val rel = tg.map { it.path.trimStart('/') }
                val dir = if (e.isDir) e.path else e.parentPath
                fun refresh() { val ids = tg.map { it.parentId } + (if (e.isDir) listOf(e.key) else emptyList()); Fm.scope.launch { kotlinx.coroutines.delay(2500); ids.distinct().forEach { FmRootCfg.kids.remove(it); FmRootCfg.kidErr.remove(it) }; CloudTrash.ver++ } }
                fun transfer(move: Boolean) {
                    val o = Fm.panes[1 - p.id]
                    if (o.root) { Fm.say(Fm.str(R.string.sel_need_target)); return }
                    Fm.scope.launch {
                        for ((pp, g) in tg.groupBy { it.parentPath }) { Fm.copyMove(move, pane(pp), o, g.map { it.name }); kotlinx.coroutines.delay(700); while (Fm.dlg is FmDlg.Conflict) kotlinx.coroutines.delay(300) }
                        refresh()
                    }
                }
                item(if (e.key in FmRootCfg.sel) R.string.fm_unselect else R.string.fm_select) { FmRootCfg.toggleSel(e) }
                item(R.string.fm_copy) { transfer(false) }
                item(R.string.fm_move) { transfer(true) }
                item(R.string.fm_delete) { Fm.askDelete(pane("/"), rel); refresh() }
                if (!multiSel) item(R.string.fm_rename) { Fm.dlg = FmDlg.Rename(pane(e.parentPath), e.name); refresh() }
                if (!multiSel) item(R.string.fm_duplicate) {
                    val dot = e.name.lastIndexOf('.')
                    val nn = if (dot > 0 && !e.isDir) e.name.substring(0, dot) + " (1)" + e.name.substring(dot) else e.name + " (1)"
                    Fm.copyMoveTo(false, pane(e.parentPath), pane(e.parentPath), "local", e.parentPath, listOf(e.name), nn); refresh()
                }
                if (!multiSel && !e.isDir && e.name.endsWith(".apk", true) && ApkInstall.installedStores(Fm.sctx).isNotEmpty()) item(R.string.inst_market) { ApkInstall.openMarket(pane(e.parentPath), FmItem(e.name, false, 0, "")) }
                item(R.string.fm_props) { Fm.props(pane("/"), rel) }
                item(R.string.perm_title) { Fm.dlg = FmDlg.Perms(pane("/"), rel) }
                item(R.string.fm_make_archive) { Fm.dlg = FmDlg.MakeArchive(pane(e.parentPath), tg.filter { it.parentPath == e.parentPath }.map { it.name }) }
                if (!multiSel) item(R.string.fm_copy_path) { Fm.copyPath(pane(e.parentPath), e.name) }
                item(R.string.fm_new_folder) { Fm.dlg = FmDlg.NewFolder(dir); refresh() }
                item(R.string.fm_new_file) { Fm.dlg = FmDlg.NewFile(dir); refresh() }
                item(R.string.fm_new_zip) { Fm.dlg = FmDlg.NewZip(dir); refresh() }
            }
            r.tnet?.let { nc ->
                val ti = r.titem
                if (ti == null) { item(R.string.tr_open_dlg) { Fm.dlg = FmDlg.Trash(nc) }; item(R.string.tr_clean) { Fm.dlg = FmDlg.Trash(nc, "") } }
                else { item(R.string.tr_restore) { CloudTrash.restoreFromTree(ctx, nc, ti) }; item(R.string.tr_perm) { Fm.dlg = FmDlg.Trash(nc, ti.id) } }
            }
            if (r.ftrash) { item(R.string.tr_open_dlg) { Fm.dlg = FmDlg.FTrash() }; item(R.string.tr_clean) { Fm.dlg = FmDlg.FTrash("") } }
            r.fitem?.let { fi ->
                item(R.string.tr_restore) {
                    Fm.scope.launch {
                        val er = FmTrash.restore(fi)
                        if (er != null) Fm.say(er) else { Fm.say(Fm.str(R.string.tr_restored)); FmTrash.bump(true, File(fi.origPath).parent.orEmpty()); FmTrash.refreshSum() }
                    }
                }
                item(R.string.tr_perm) { Fm.dlg = FmDlg.FTrash(fi.id) }
            }
            r.net?.takeIf { !r.drive && CloudTrash.supported(CloudTrash.typeFor(ctx, it)) }?.let { nc -> item(R.string.tr_menu) { Fm.dlg = FmDlg.Trash(nc) } }
            r.vol?.let { v ->
                item(R.string.rt_details) {
                    val fsTxt = (v.fs.ifBlank { "?" }) + if (v.ro) " · " + Fm.str(R.string.fm_vol_ro) else ""
                    Fm.dlg = if (v.ok) FmDlg.Props(v.label, Fm.str(R.string.fm_vol_body, v.path, if (v.total > 0) Fm.str(R.string.fm_free_of, fmSize(v.free), fmSize(v.total)) else "-", fsTxt, if (v.usb) "USB" else "SD"))
                    else FmDlg.Props(v.label, Fm.str(R.string.fm_vol_unsupported))
                }
                item(R.string.rt_eject) { Fm.eject(v) }
            }
            if (r.key == "dev") item(R.string.rt_details) {
                Fm.dlg = FmDlg.Props(Fm.str(R.string.fm_internal), Fm.str(R.string.fm_vol_body, "/sdcard", if (r.total > 0) Fm.str(R.string.fm_free_of, fmSize(r.free), fmSize(r.total)) else "-", "-", Fm.str(R.string.fm_src_phone)))
            }
            r.net?.takeIf { !r.drive }?.let { c ->
                if (r.wifi) {
                    if (c in WifiExplorer.found) item(R.string.wf_add) { Fm.dlg = FmDlg.WifiDev(null, WifiExplorer.hostText(c), c.name) }
                    else {
                        item(R.string.rt_edit) { Fm.dlg = FmDlg.WifiDev(c, "", "") }
                        item(R.string.rt_forget) { NetStore.remove(ctx, c); FmRootCfg.ver++; WifiExplorer.ver++ }
                    }
                } else {
                    item(R.string.rt_details) { Fm.dlg = FmDlg.Props(c.title, Fm.str(R.string.fm_net_body, c.kind.uppercase(), c.host, c.user.ifBlank { "-" })) }
                    item(R.string.rt_edit) { Fm.dlg = FmDlg.EditNet(c) }
                    item(R.string.rt_forget) { NetStore.remove(ctx, c); FmRootCfg.ver++ }
                }
            }
            if (r.wifi && r.net?.host?.startsWith("http://192.168.49.") == true) item(R.string.wd_leave) { Fm.scope.launch { Fm.say(Fm.str(if (WifiDirect.leave()) R.string.wd_left else R.string.wd_left_no)) } }
            if (r.key == "wifi") {
                // выбор способа связи с другим устройством
                for ((m, res) in listOf("auto" to R.string.wf_mode_auto, "lan" to R.string.wf_mode_lan, "direct" to R.string.wf_mode_direct))
                    DropdownMenuItem({ Text((if (WifiExplorer.mode == m) "✓ " else "") + stringResource(res), fontSize = 14.sp) }, { menu = false; WifiExplorer.setMode(ctx, m) })
            }
            if (r.key == "wifi:srv") item(R.string.wf_link) { Fm.dlg = FmDlg.WifiServer("/sdcard") }
            if (r.key == "wifi+") item(R.string.wf_manual) { Fm.dlg = FmDlg.WifiDev(null, "", "") }
            if (r.key in setOf("root", "recent", "adb", "lan", "cloud", "wifi", "apps")) item(R.string.rt_hide) { FmRootCfg.set(ctx, r.key, false) }
        }
    }
}

/** Окно «Показать/Скрыть»: какие необязательные строки видны на корневом экране. */
@Composable
fun RootShowDialog(close: () -> Unit) {
    val ctx = LocalContext.current
    val names = mapOf("root" to "Root", "recent" to stringResource(R.string.rt_recent), "lan" to stringResource(R.string.rt_lan), "cloud" to stringResource(R.string.rt_cloud), "wifi" to stringResource(R.string.rt_wifi), "apps" to stringResource(R.string.rt_apps), "tree" to stringResource(R.string.rt_tree), "adb" to stringResource(R.string.rt_adb))
    LAlertDialog(
        onDismissRequest = close, title = { Text(stringResource(R.string.rt_showhide)) },
        text = {
            Column {
                FmRootCfg.optional.forEach { k ->
                    Row(Modifier.fillMaxWidth().clickable { FmRootCfg.set(ctx, k, k !in FmRootCfg.shown) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(k in FmRootCfg.shown, { FmRootCfg.set(ctx, k, it) }); Text(names[k] ?: k, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(close) { Text(stringResource(R.string.btn_close)) } },
    )
}

/** Контейнер без лишних зависимостей: обычный Column с общим модификатором. */
@Composable
private fun Box2(modifier: Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(modifier) { content() }
}

/** «22GB»: целые гигабайты без пробела, как в X-plore. */
fun fmGb(b: Long): String {
    val loc = runCatching { Fm.sctx.resources.configuration.locales[0] }.getOrDefault(java.util.Locale.getDefault())
    return if (b >= 1073741824L) { val g = b / 1073741824.0; (if (g < 100) "%.1f" else "%.0f").format(loc, g) + " " + Fm.str(R.string.unit_gb) }
    else "%.0f %s".format(loc, b / 1048576.0, Fm.str(R.string.unit_mb))
}

/** Плоская полоска занятого места: занятое насыщенно-бирюзовое слева, свободное светло-бирюзовое справа; больше 90 % занято: красноватое. Без обводки, градиентов и процентов. */
@Composable
fun FmSpaceBar(used: Float, modifier: Modifier = Modifier) {
    val u = used.coerceIn(0f, 1f)
    val h = if (Marq.tv) 6.dp else 4.dp
    val usedColor = if (u > .9f) Color(0xFFDC2626) else Color(0xFF0F766E)
    Row(modifier.height(h).background(Color(0xFF5EEAD4).copy(alpha = .4f), RoundedCornerShape(1.dp))) {
        androidx.compose.foundation.layout.Box(Modifier.weight(u.coerceAtLeast(0.01f)).fillMaxHeight().background(usedColor, RoundedCornerShape(1.dp)))
        if (u < 1f) Spacer(Modifier.weight(1f - u))
    }
}
