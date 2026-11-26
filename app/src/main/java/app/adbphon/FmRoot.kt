@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package app.adbphon

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Devices
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn

/** Настройки корневого экрана: какие необязательные строки видны (по умолчанию только сеть и облака, лишнее скрыто). */
object FmRootCfg {
    /** Когда запущен файловый менеджер: запомненные облака и сеть перечитываются не раньше чем через ~1,5 с и по одному. */
    val t0 = System.currentTimeMillis()

    /** Повторное раскрытие узла: старый список виден сразу, но поверх всегда читается заново (так же для раскрытых внутри него узлов). */
    fun refresh(p: FmPane, id: String, favs: List<NetConn>) {
        // 14.0: первое чтение папки телефона начинается сразу при нажатии, а не после перекомпоновки списка и запуска эффекта (на слабой приставке это лишние кадры)
        if (id.startsWith("L|") && kids[id] == null && kidErr[id] == null) Fm.scope.launch { loadKids(id, favs) }
        val virt = favs.filter { it.kind == "cloud" && CloudTrash.supported(CloudTrash.typeFor(Fm.sctx, it)) }.map { "N|" + it.kind + "|" + it.host + "|" + it.user + "|" }.toSet()
        for (x in (p.rootOpen.toList() + id).distinct()) if (x.startsWith(id) && x !in virt && kids[x] != null) Fm.scope.launch { loadKids(x, favs) }
    }
    val optional = listOf("root", "recent", "lan", "cloud", "wifi", "pc", "apps", "tree", "adb")
    /** Первый запуск (выбор в «Показать/Скрыть» ещё не сохранён): видны все пункты, чтобы сразу было видно возможности. Сохранённый выбор не трогается. */
    var shown by mutableStateOf(optional.toSet())
    /** Число папок и файлов в корне тома, считается в фоне. */
    val counts = PerKeyMap<String, Pair<Int, Int>>()
    var ver by mutableStateOf(0)
    /** Поиск устройств по LAN (SMB, SFTP, FTP, WebDAV) прямо в группе «LAN» (10.11.2026: раньше он был спрятан в окне «Добавить подключение» → «Сеть»). */
    val lanFound = androidx.compose.runtime.mutableStateListOf<NetHost>()
    var lanScanning by mutableStateOf(false)
    var lanNone by mutableStateOf(false)
    private var lanJob: kotlinx.coroutines.Job? = null
    /** Узлы, у которых нажали галочку «отметить всё», пока они были свёрнуты: после загрузки содержимого отмечается всё. */
    val selAllPending = HashSet<String>()
    private fun selOfKid(k: TK, parent: String, c: NetConn?, rootPath: String? = null): SelE? {
        val pp = if (parent.startsWith("L|")) parent.removePrefix("L|") else if (parent.startsWith("N|")) (rootPath ?: parent.split('|', limit = 5).getOrNull(4).orEmpty().ifEmpty { "/" }) else return null
        return SelE(k.id, if (parent.startsWith("N|")) c else null, parent, pp, k.name, k.path, k.isDir, null)
    }
    /** Тип, выбранный в окне «Источник» (smb, sftp, ftp, dav): показываются только устройства с этим протоколом; null: все найденные. */
    var lanKind: String? = null
    fun lanScan(ctx: Context, kind: String? = null) {
        if (lanScanning) return
        lanKind = kind
        lanFound.clear(); lanNone = false; lanScanning = true
        lanJob = Fm.scope.launch {
            try {
                val res = runCatching { NetDiscover.scan(ctx.applicationContext) { l -> lanFound.clear(); lanFound.addAll(lanFilter(l)) } }.getOrDefault(lanFound.toList())
                lanFound.clear(); lanFound.addAll(lanFilter(res))
            } finally { lanScanning = false; lanNone = lanFound.isEmpty() && lanJob?.isCancelled != true }
        }
    }
    fun lanCancel() { lanJob?.cancel(); lanScanning = false }
    /** Ручное «Обновить» на экране «Устройства»: сбрасывает загруженное содержимое раскрытых узлов и кэш корзин, всё читается заново. */
    fun refreshAll(ctx: Context) {
        Fm.scope.launch {
            // 9.4: раскрытые узлы перечитываются на месте (без «Загрузки…»): список не схлопывается, прокрутка и фокус остаются
            kids.keys.toList().forEach { stale.add(it) }; kidErr.clear(); autoRetry.clear()
            NetStore.list(ctx).forEach { c -> runCatching { CloudTrash.invalidate(NetTrash.rid(c)) } }
            CloudTrash.ver++
            runCatching { FmTrash.bump(); FmTrash.refreshSum() }
            runCatching { Fm.refreshVols() }
            ver++
            Fm.say(Fm.str(R.string.fm_refreshed))
        }
    }
    /** Подключение к найденному по LAN устройству: лучший протокол по открытым портам, гость; в избранное не сохраняется, пока владелица не сохранит сама. */
    private fun lanFilter(l: List<NetHost>): List<NetHost> { val k = lanKind; return if (k == null) l else l.filter { k in it.kinds } }
    /** Начальная папка подключения из поля «Общий ресурс или папка» (для SMB: smb://хост/ресурс/путь/), null: поле пусто или облако. Содержимое этой папки показывается сразу под устройством. */
    fun ownPath(c: NetConn?): String? {
        if (c == null || c.kind == "cloud") return null
        val saved = NetStore.list(Fm.sctx).firstOrNull { it.kind == c.kind && it.host == c.host && it.user == c.user } ?: c
        val q = saved.path.trim().trim('/').takeIf { it.isNotEmpty() } ?: return null
        return if (c.kind == "smb") "smb://" + c.host.trim().trim('/') + "/" + q + "/" else "/" + q
    }
    /** Запасной протокол найденного устройства: когда SMB не открылся, дерево пробует FTP, WebDAV, SFTP (запоминается на время работы). */
    val lanKindOver = HashMap<String, String>()
    val lanPref = listOf("smb", "ftp", "dav", "sftp")
    /** 14.11.2026 (владелица): нажатие на устройство в LAN раскрывает его дерево сразу; если в подключении указан общий ресурс или папка, под устройством сразу её содержимое (loadKids берёт ownPath), иначе корень (список ресурсов). Протокол: SMB, при неудаче FTP, WebDAV, SFTP, без вопросов. */
    suspend fun lanAutoOpen(p: FmPane, tid0: String, c0: NetConn, h: NetHost?) {
        suspend fun waitKids(id: String) { kotlinx.coroutines.withTimeoutOrNull(30000L) { while (kids[id] == null && kidErr[id] == null) kotlinx.coroutines.delay(200) } }
        var tid = tid0; var c = c0
        waitKids(tid)
        if (kidErr[tid] != null && h != null) {
            val order = (listOf(c.kind) + h.kinds.sortedBy { k -> lanPref.indexOf(k).let { if (it < 0) 9 else it } }).distinct()
            for (k in order.drop(1)) {
                lanKindOver[h.ip] = k
                val c2 = lanConn(h); val tid2 = "N|" + c2.kind + "|" + c2.host + "|" + c2.user + "|"
                p.rootOpen.remove(tid); kidErr.remove(tid)
                p.rootOpen.add(tid2)
                waitKids(tid2)
                tid = tid2; c = c2
                if (kids[tid2] != null) break
            }
        }
    }
    fun lanConn(h: NetHost, kind: String? = null): NetConn { val k = kind ?: lanKindOver[h.ip]?.takeIf { it in h.kinds } ?: lanKind?.takeIf { it in h.kinds } ?: h.kinds.firstOrNull() ?: "smb"; return NetConn(k, if (k == "dav") davUrlOf(h) else h.ip, 0, "", "", "", "", h.name) }
    /** 9.3: папка-приёмник для копирования и переноса, когда другая панель показывает дерево «Устройства»: берётся последняя РАСКРЫТАЯ папка этой панели (источник любой: память, SD/USB, ADB, сеть, облако),
     *  если ничего не раскрыто: папка строки с фокусом или последней нажатой (у файла его родитель). Иначе null (сообщение «раскрой папку»). */
    class RowRef(val key: String, val node: String?)
    private val lastRow = java.util.concurrent.ConcurrentHashMap<Int, RowRef>()
    /** T3: строка с фокусом (или последняя нажатая) каждой панели: помнится и после перезапуска (root_foc_p0/1). */
    val focusKey = java.util.concurrent.ConcurrentHashMap<Int, String>()
    /** 24.11.2026 (владелица: «вместо слова «Источники» сделай путь, как в X-plore»): путь строки под фокусом пульта в каждой панели (для заголовка корня). */
    val headPath = androidx.compose.runtime.mutableStateMapOf<Int, String>()
    /** Текст заголовка корня: путь отмеченного элемента (если отмечено несколько: первый и «+N»), иначе путь строки под фокусом, иначе пусто («Источники»). */
    /** Полный путь для шапки: у сетевых источников путь хранится внутри ресурса, спереди добавляется адрес («smb://хост/папка», как в X-plore). */
    fun shownPath(e: SelE): String {
        val c = e.net ?: return e.path
        if (e.path.contains("://")) return e.path      // путь уже с адресом («smb://хост/…»)
        val pt = if (e.path.startsWith("/") || e.path.isEmpty()) e.path else "/" + e.path
        return when (c.kind) {
            "dav" -> c.host.trimEnd('/') + pt
            "cloud" -> c.title + ":" + pt
            else -> c.kind + "://" + c.host + (if (c.port > 0) ":" + c.port else "") + pt
        }
    }
    fun headText(paneId: Int): String {
        val ps = sel.values.filter { it.ti == null && it.fi == null && it.path.isNotBlank() }.map { shownPath(it) }.sorted()
        if (ps.isNotEmpty()) return ps.first() + (if (ps.size > 1) "  (+" + (ps.size - 1) + ")" else "")
        return headPath[paneId].orEmpty()
    }
    fun noteRow(paneId: Int, key: String, node: String?, byFocus: Boolean = false) {
        lastRow[paneId] = RowRef(key, node)
        // фокус, пришедший сам при показе экрана (кнопок ещё не нажимали), запомненную строку не затирает
        if ((!byFocus || FocusRestore.keys != FocusRestore.keysAtShow) && focusKey[paneId] != key) { focusKey[paneId] = key; runCatching { Fm.sctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE).edit().putString("root_foc_p$paneId", key).apply() } }
    }
    class Dst(val src: String, val net: NetConn?, val path: String, val node: String) {
        fun pane(): FmPane = FmPane(9, path).also { ps ->
            if (net != null) { ps.src = "smb"; ps.smb = fsFor(net); ps.net = net } else ps.src = src
        }
        fun label(): String = Fm.pathLabel(pane(), if (net != null) "smb" else src, path)
    }
    /** Идентификатор узла дерева («L|путь», «D|путь», «N|вид|адрес|логин|путь») → место для записи. */
    fun nodeDst(id: String): Dst? = runCatching {
        when {
            id.startsWith("L|") -> Dst("local", null, id.removePrefix("L|").ifEmpty { "/" }, id)
            id.startsWith("D|") -> Dst("adb", null, id.removePrefix("D|").ifEmpty { "/" }, id)
            id.startsWith("N|") -> {
                val a = id.split('|', limit = 5)
                val c = NetStore.list(Fm.sctx).firstOrNull { it.kind == a[1] && it.host == a[2] && it.user == a[3] } ?: return@runCatching null
                val fs = fsFor(c)
                val rest = a.getOrNull(4).orEmpty()
                val path = if (rest == NetTrash.ROOT_MARK) NetTrash.rootPath(c, fs) else rest.ifEmpty { ownPath(c) ?: fs.root }
                Dst("smb", c, path, id)
            }
            else -> null
        }
    }.getOrNull()
    /**
     * 10.0 (fm1, владелица): правило «только явно раскрытая папка» и окно «Сначала выберите место» убраны. Как в опубликованной версии:
     * приёмник = текущая (последняя раскрытая) папка дерева другой панели, иначе папка строки с фокусом или последней нажатой. null: папки нет,
     * тогда копирование предлагает окно выбора папки (FmDlg.PickTarget), без тупика.
     */
    fun destFor(o: FmPane): Dst? {
        o.rootOpen.toList().asReversed().firstNotNullOfOrNull { nodeDst(it) }?.let { return it }
        val ref = lastRow[o.id]
        if (ref?.node != null && ref.key in rowsOf(o.id)) nodeDst(ref.node)?.let { return it }
        return null
    }

    // ---------- 10.0 (fm1): отметка в дереве «Источники»: папка = всё содержимое (логика в SelTree) ----------
    /** Видимые строки дерева панели с отметкой: ключ → элемент, содержимое раскрытых папок, элементы верхнего уровня (для «Все» и «Инверсии»). */
    class VisSel(compute: () -> Triple<Map<String, SelE>, Map<String, List<String>>, List<String>>) {
        private val t by lazy(LazyThreadSafetyMode.PUBLICATION, compute)
        val byKey: Map<String, SelE> get() = t.first
        val kids: Map<String, List<String>> get() = t.second
        val top: List<String> get() = t.third
    }
    private val visSel = java.util.concurrent.ConcurrentHashMap<Int, VisSel>()
    fun setVisSel(paneId: Int, v: VisSel) { visSel[paneId] = v }
    /** Файл «F|путь» и «f|…» лежит в папке «L|…» и «N|…»: для вложенности ключи приводятся к одному виду. */
    val canon: (String) -> String = { k -> if (k.startsWith("F|")) "L|" + k.substring(2) else if (k.startsWith("f|")) "N|" + k.substring(2) else k }
    private fun isTrash(e: SelE) = e.ti != null || e.fi != null
    /** 14.0: индекс отметки дерева (одна сборка на каждое изменение набора): covered/partial за O(глубина пути) вместо просмотра всего набора на каждую строку и каждую перекомпоновку. */
    val selIdx: androidx.compose.runtime.State<SelIndex> = androidx.compose.runtime.derivedStateOf { SelIndex(sel.keys.toList(), canon) }
    /** Отмечено само или через отмеченную папку выше. */
    fun covered(key: String): Boolean = selIdx.value.covered(key)
    /** Внутри папки отмечена только часть. */
    fun partial(key: String): Boolean = selIdx.value.partial(key)
    private fun apply(paneId: Int, keys: Set<String>) {
        val v = visSel[paneId]
        val m = LinkedHashMap<String, SelE>()
        for (k in keys) (sel[k] ?: v?.byKey?.get(k))?.let { m[k] = it }
        val gone = sel.keys.filter { it !in m }
        gone.forEach { sel.remove(it) }
        for ((k, e) in m) if (!sel.containsKey(k)) sel[k] = e
    }
    /** Нажатие на галочку строки дерева (элементы корзин отмечаются по одному, без вложенности). */
    fun toggleTree(paneId: Int, e: SelE) {
        if (isTrash(e)) { toggleSel(e); return }
        val v = visSel[paneId]
        // 26.11.2026 (как в X-plore): раскрытая папка (её содержимое видно в списке) по повторному нажатию: сначала всё, что лежит прямо в ней, затем ничего.
        // У свёрнутой папки отметка просто включается и выключается.
        val shownKids = v?.kids?.get(e.key)
        if (e.isDir && shownKids != null && (sel.containsKey(e.key) || SelTree.partial(sel.keys.toList(), e.key, canon))) {
            val ready = kids[e.key]
            if (ready != null) { cycleFiles(paneId, e, ready); return }
        }
        if (v == null || !v.byKey.containsKey(e.key)) { val keys = SelTree.toggle(sel.keys.toList(), e.key, { null }, canon); if (keys.contains(e.key)) sel[e.key] = e; apply(paneId, keys); return }
        apply(paneId, SelTree.toggle(sel.keys.toList(), e.key, { v.kids[it] }, canon))
    }
    /** Второе и третье нажатие на галочку папки: всё, что лежит прямо в ней (файлы и подпапки), затем ничего. */
    private fun cycleFiles(paneId: Int, e: SelE, tks: List<TK>) {
        val v = visSel[paneId]
        val files = tks.filter { Fm.showHidden || !it.name.startsWith(".") }
        val keys = SelTree.cycle(sel.keys.toList(), e.key, files.map { it.id }, { v?.kids?.get(it) }, canon)
        for (f in files) if (f.id in keys && !sel.containsKey(f.id) && v?.byKey?.containsKey(f.id) != true) sel[f.id] = SelE(f.id, e.net, e.key, e.path, f.name, f.path, false, null)
        apply(paneId, keys)
    }
    /** Галочка раскрытого устройства или тома без своей отметки: отметить или снять всё прямо внутри. */
    fun setAll(paneId: Int, list: List<SelE>, on: Boolean) {
        var keys: Set<String> = LinkedHashSet(sel.keys)
        for (e in list.filter { !isTrash(it) }) keys = if (on) SelTree.select(keys, e.key, canon) else keys.filter { it != e.key && !SelTree.under(it, e.key, canon) }.toSet()
        val tr = list.filter { isTrash(it) }
        apply(paneId, keys)
        tr.forEach { if (on) sel[it.key] = it else sel.remove(it.key) }
    }
    /** «Все» в дереве: каждый видимый отмечаемый элемент верхнего уровня (вложенное входит в отмеченные папки). */
    fun selectAllVisible(paneId: Int) { val v = visSel[paneId] ?: return; apply(paneId, SelTree.selectAll(sel.keys.toList(), v.top, canon)) }
    /** «Инверсия» по видимому (раскрытому) дереву. */
    fun invertVisible(paneId: Int) { val v = visSel[paneId] ?: return; val tr = sel.values.filter { isTrash(it) }; apply(paneId, SelTree.invert(sel.keys.filter { k -> sel[k]?.let { !isTrash(it) } ?: true }, v.top, { v.kids[it] }, canon)); tr.forEach { sel[it.key] = it } }
    /** Фокус пульта: первый значок нижней панели выбора и ключ последней видимой строки списка (стрелка «вниз» на ней уходит на панель). */
    val barFocus = androidx.compose.ui.focus.FocusRequester()
    @Volatile var lastVisKey: String? = null

    /** Дерево папок на «домике»: дочерние папки узла (по идентификатору узла), ошибки и кэш подключений. Загружаются лениво при раскрытии. */
    class TK(val name: String, val id: String, val path: String, val isDir: Boolean = true, val size: Long = 0L, val date: String = "", val count: Int = -1, val dirs: Int = -1, val files: Int = -1)

    /** Отмеченная строка дерева «домика»: ключ = id узла (путь целиком), источник (net = null: память телефона), папка-родитель, для «Корзины» ещё элемент корзины. */
    class SelE(val key: String, val net: NetConn?, val parentId: String, val parentPath: String, val name: String, val path: String, val isDir: Boolean, val ti: CloudTrash.TItem?, val fi: FmTrash.FItem? = null)
    val sel = androidx.compose.runtime.mutableStateMapOf<String, SelE>()
    fun toggleSel(e: SelE) { if (sel.containsKey(e.key)) sel.remove(e.key) else sel[e.key] = e }
    /** Отметки лежат только в раскрытых узлах: у отметки, чей родитель свёрнут (ни в одной панели), отметка снимается. */
    fun pruneSel() {
        val open = HashSet<String>(); Fm.panes.forEach { open.addAll(it.rootOpen) }
        val drop = sel.entries.filter { (_, e) -> e.ti == null && e.fi == null && e.parentId.isNotEmpty() && e.parentId !in open }.map { it.key }
        drop.forEach { sel.remove(it) }
    }
    /**
     * Порядок дочерних узлов «домика» по настройке «Сортировка» панели (раньше был зашит «папки, потом имя», и сортировка в этом дереве не действовала).
     * Корзина и служебные узлы остаются как есть. Дата есть только у локальных файлов.
     */
    /** 22.11.2026: результат сортировки раскрытой папки запоминается, пока тот же список [ks] и та же настройка (раньше сортировка 3000 записей шла при каждой перекомпоновке списка). */
    private class SortMemo(val src: List<TK>, val cfg: SortCfg, val res: List<TK>)
    private val sortMemo = HashMap<String, SortMemo>()
    fun sortKids(p: FmPane, id: String, ks: List<TK>): List<TK> {
        if (ks.size >= 200) {
            val dir0 = if (id.startsWith("L|")) id.removePrefix("L|") else id
            val cfg0 = runCatching { Fm.cfgFor(p, dir0) }.getOrNull() ?: p.sortCfg
            val m = synchronized(sortMemo) { sortMemo[id] }
            if (m != null && m.src === ks && m.cfg == cfg0) return m.res
            val res = sortKids0(p, id, ks)
            if (cfg0.key.values.none { it == SKey.COLOR || it == SKey.COUNT }) synchronized(sortMemo) { if (sortMemo.size > 24) sortMemo.clear(); sortMemo[id] = SortMemo(ks, cfg0, res) }
            return res
        }
        return sortKids0(p, id, ks)
    }
    private fun sortKids0(p: FmPane, id: String, ks: List<TK>): List<TK> {
        if (ks.size < 2 || ks.any { it.id.startsWith("Ti|") || it.id.startsWith("Ki|") || it.id.startsWith("Aitem|") || it.id.startsWith("T|") }) return ks
        val dir = if (id.startsWith("L|")) id.removePrefix("L|") else id
        val cfg = runCatching { Fm.cfgFor(p, dir) }.getOrNull() ?: p.sortCfg
        val map = LinkedHashMap<FmItem, TK>()
        for (k in ks) map[FmItem(k.name, k.isDir, k.size, k.date)] = k
        if (map.size != ks.size) return ks
        val needColor = cfg.keyOf(SGroup.DIR) == SKey.COLOR
        // 10.0 (chk): цвет сетевой папки хранится с приставкой «smb:» (так его задаёт панель сети)
        val ctx = SortCtx({ null }, { map[it]?.let { k -> if (k.count >= 0) k.count else appCount[k.id] ?: kids[k.id]?.size } ?: -1 }, { if (needColor) map[it]?.let { k -> UiPrefs.folderColors[if (k.id.startsWith("N|")) "smb:" + k.path else folderKey(p, k.path)] } ?: UiPrefs.folderDefault else 0 })
        return SortEngine.sort(map.keys.toList(), cfg, ctx).mapNotNull { map[it] }
    }
    /** Узлы, содержимое которых нужно перечитать «на месте» (без «Загрузки…» и без схлопывания списка): после удаления и копирования. */
    val stale: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())
    /** Ключи и уровни видимых строк дерева по панелям (обновляются при каждой отрисовке): нужны, чтобы после удаления передать фокус соседней строке. */
    private val rowsByPane = java.util.concurrent.ConcurrentHashMap<Int, Lazy<Pair<List<String>, List<Int>>>>()
    fun setRows(paneId: Int, keys: List<String>, levels: List<Int>) { rowsByPane[paneId] = lazyOf(keys to levels) }
    /** 22.11.2026: ключи и уровни считаются только когда понадобятся (раньше при каждой перекомпоновке списка, около 10 мс на 250 строк). */
    fun setRowsLazy(paneId: Int, f: () -> Pair<List<String>, List<Int>>) { rowsByPane[paneId] = lazy(LazyThreadSafetyMode.PUBLICATION, f) }
    /** 20.11.2026: строка [key] лежит внутри раскрытого узла [owner] (по видимому списку панели: следующие строки глубже владельца). */
    fun rowInside(paneId: Int, key: String, owner: String): Boolean {
        val (keys, lv) = rowsByPane[paneId]?.value ?: return false
        if (keys.size != lv.size) return false
        val oi = keys.indexOf(owner); val ki = keys.indexOf(key)
        if (oi < 0 || ki <= oi) return false
        for (j in oi + 1..ki) if (lv[j] <= lv[oi]) return false
        return true
    }
    /**
     * Ключ строки, которой отдать фокус после удаления строк [deleted]: следующий сосед того же уровня, иначе предыдущий сосед или родитель.
     * Считается ДО удаления, пока строки ещё в списке; ищется в той панели, где эти строки есть.
     */
    fun neighborOf(deleted: Set<String>): String? {
        val (keys, lv) = rowsByPane.values.map { it.value }.firstOrNull { (k, _) -> k.any { it in deleted } } ?: return null
        if (keys.isEmpty() || keys.size != lv.size) return null
        val idx = keys.indices.filter { keys[it] in deleted }
        if (idx.isEmpty()) return null
        val hi = idx.max(); val lo = idx.min(); val lvl = lv[lo]
        var i = hi + 1
        while (i < keys.size && keys[i] in deleted) i++
        if (i < keys.size && lv[i] == lvl) return keys[i]
        var j = lo - 1
        while (j >= 0 && keys[j] in deleted) j--
        if (j >= 0 && lv[j] >= lvl - 1) return keys[j]
        return if (i < keys.size && lv[i] >= lvl - 1) keys[i] else null
    }
    /**
     * Общее правило для ЛЮБОГО обновления дерева (удаление, переименование, перенос, копирование, создание, «Обновить», раскрытие и свёртывание):
     * строка, у которой был фокус, исчезла из списка → фокус получает соседняя строка на том же месте (следующая того же уровня, иначе предыдущая, иначе родитель);
     * при нескольких удалённых строках берётся строка после последней удалённой. Положение прокрутки держит сам список (строки с постоянными ключами).
     * Вызывается из строки, которая освобождается, пока на ней фокус.
     */
    fun rowsOf(paneId: Int): List<String> = rowsByPane[paneId]?.value?.first ?: emptyList()
    /** После переименования (исчезла одна строка, появилась одна): фокус новой строке, если пульт или клавиатура уже использовались. */
    fun focusRenamed(paneId: Int, before: List<String>) {
        Fm.scope.launch {
            kotlinx.coroutines.delay(900)
            val now = rowsOf(paneId)
            val gone = before.filter { it !in now }; val fresh = now.filter { it !in before }
            if (gone.size == 1 && fresh.size == 1 && FocusRestore.keyMode(Fm.sctx)) { FocusRestore.key = null; kotlinx.coroutines.delay(60); FocusRestore.key = fresh[0] }
        }
    }
    fun focusedRowGone(paneId: Int, key: String) {
        val old = rowsByPane[paneId]?.value ?: return
        Fm.scope.launch {
            kotlinx.coroutines.delay(350)
            val now = rowsByPane[paneId]?.value ?: return@launch
            if (key in now.first) return@launch     // строка только ушла за край экрана, она по-прежнему в списке
            val gone = old.first.filter { it !in now.first }.toSet()
            // переименование: одна строка исчезла, одна появилась на том же месте: фокус новой строке
            val fresh = now.first.filter { it !in old.first }
            if (gone.size == 1 && fresh.size == 1) { FocusRestore.key = null; kotlinx.coroutines.delay(60); FocusRestore.key = fresh[0]; return@launch }
            val keys = old.first; val lv = old.second
            if (keys.size != lv.size) return@launch
            val idx = keys.indices.filter { keys[it] in gone }
            if (idx.isEmpty()) return@launch
            val hi = idx.max(); val lo = idx.min(); val lvl = lv[lo]
            var nb: String? = null
            var i = hi + 1
            while (i < keys.size && keys[i] in gone) i++
            if (i < keys.size && lv[i] == lvl && keys[i] in now.first) nb = keys[i]
            if (nb == null) { var j = lo - 1; while (j >= 0 && keys[j] in gone) j--; if (j >= 0 && lv[j] >= lvl - 1 && keys[j] in now.first) nb = keys[j] }
            if (nb == null && i < keys.size && lv[i] >= lvl - 1 && keys[i] in now.first) nb = keys[i]
            if (nb != null) { FocusRestore.key = null; kotlinx.coroutines.delay(60); FocusRestore.key = nb }
        }
    }
    /** Отдать фокус строке [key] (через FocusRestore: строка сама просит фокус, как только появится в списке). */
    fun focusRowLater(key: String?) {
        if (key == null) return
        Fm.scope.launch { FocusRestore.key = null; kotlinx.coroutines.delay(60); FocusRestore.key = key }
    }
    val kids = PerKeyMap<String, List<TK>>()
    val kidErr = PerKeyMap<String, String>()
    /** 12.0 (владелица: «раскрытая папка должна подняться наверх»): узел дерева, который только что раскрыли в панели [p.id] —
     * FmRootList прокручивает к нему списком и сам стирает эту метку. */
    val scrollTarget = PerKeyMap<Int, String>()
    /** Тип облака у узла, ошибку которого лечит «свой ключ» (лимит общего ключа, API выключен, не тот scope). */
    val kidErrOwnKey = PerKeyMap<String, String>()
    /**
     * 12.0 («развёрнутая папка в облаке оставалась в памяти, даже когда разворачивала другую»): списки облачных узлов дерева, которые не раскрыты
     * ни в одной панели, выгружаются из памяти; раскрытые, ожидающие подключения и «Корзина» остаются. Работает при «Держать раскрытой только одну папку».
     */
    fun purgeClosedCloud() {
        val open = Fm.panes.flatMap { it.rootOpen.toList() + it.rootPending.toList() }.toSet()
        for (k in (kids.keys + kidErr.keys).toList()) if (k.startsWith("N|cloud|") && k !in open) { kids.remove(k); kidErr.remove(k); kidErrOwnKey.remove(k) }
    }
    private val autoRetry = HashMap<String, Int>()
    /** Сколько элементов узла показывать сразу (остальное счётчиком): быстрая прокрутка длинных папок. */
    const val LIMIT = 200
    /** 10.0 (chk): сколько элементов узла хранить до сортировки (показывается LIMIT уже отсортированных). */
    const val KEEP = 1000
    fun fsFor(c: NetConn): RemoteFs = fsCache.getOrPut(c.kind + "|" + c.host + "|" + c.user) { c.fs() }
    val appCount = PerKeyMap<String, Int>()
    /** 22.11.2026: число записей папки применяется вместе со списком (одним снимком), а не раньше: раньше это давало лишнюю перекомпоновку списка. */
    private val pendCnt = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val fsCache = java.util.concurrent.ConcurrentHashMap<String, RemoteFs>()
    /** Подключения раскрытых в дереве узлов (для постоянной связи и блокировок Wi-Fi). */
    fun openFs(): List<KeepFs> = runCatching {
        Fm.panes.flatMap { it.rootOpen.toList() }.filter { it.startsWith("N|") }.mapNotNull { id -> val a = id.split('|', limit = 5); if (a.size < 4) null else fsCache[a[1] + "|" + a[2] + "|" + a[3]] as? KeepFs }.distinct()
    }.getOrDefault(emptyList())
    val treeOn get() = "tree" in shown

    /** 14.0: узлы, у которых ПЕРВОЕ чтение уже идёт: повторный запуск (из эффекта списка после нажатия) не читает ту же папку второй раз. */
    private val loadingFirst: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Идентификаторы: «L|путь» (память телефона) и «N|вид|адрес|логин|путь» (сеть, облако). */
    suspend fun loadKids(id: String, favs: List<NetConn>) {
        val first = kids[id] == null
        if (first && !loadingFirst.add(id)) return
        try { loadKidsNow(id, favs) } finally { if (first) loadingFirst.remove(id) }
    }

    private suspend fun loadKidsNow(id: String, favs: List<NetConn>) {
        val perfK = System.nanoTime()
        try { loadKidsNow2(id, favs) } finally { PerfLog.mark("loadKids $id: ${(System.nanoTime() - perfK) / 1_000_000} мс") }
    }

    private suspend fun loadKidsNow2(id: String, favs: List<NetConn>) {
        kidErr.remove(id); kidErrOwnKey.remove(id)
        // облачные узлы без модуля «ADB-phon Облака»: сеть не трогаем, узел покажет «Нужен модуль облаков»
        if ((id.startsWith("N|cloud|") || (id.startsWith("T|") && !NetTrash.isRid(id.removePrefix("T|")))) && !CloudModule.ok(Fm.sctx)) { kidErr[id] = CloudModule.MARK; return }
        var cSel: NetConn? = null
        var pSel: String? = null
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
                CloudTrash.sums[host] = CloudTrash.sumOf(host, l.items, if (l.total >= 0) l.total else l.items.sumOf { maxOf(it.size, 0L) })
                if (l.items.isEmpty()) listOf(TK(Fm.str(R.string.tr_none), "Te|" + host, "", false, 0))
                else l.items.take(CloudTrash.TRASH_CAP).map { ti -> val kid = "Ti|" + host + "|" + ti.id; CloudTrash.tkItems[kid] = ti; TK(ti.name, kid, ti.origPath, ti.isDir, ti.size) }
            } else if (id.startsWith("A|")) {
                // «Приложения»: папка группы раскрывается в список приложений (первые 150, остальное счётчиком)
                val key = id.removePrefix("A|")
                val fs = AppsFs()
                val all = fs.list("apps://" + fs.dirName(key))
                pendCnt[id] = all.size
                all.take(150).map { TK(it.name, "Aitem|$key|" + it.path.substringAfterLast('/'), it.path) }
            } else if (id.startsWith("D|")) {
                // «ADB (оболочка)»: папки по ADB (нужна связь)
                FmIo.ensurePriv()?.let { if (it == "pair" || it == "wifi") Fm.autoPairOnce(); error("FIX:" + it) }
                val path = id.removePrefix("D|").ifEmpty { "/" }
                AdbFs.list(path).filter { it.isDir }.take(300).map { TK(it.name, "D|" + it.path, it.path, true, 0L, it.date) }
            } else if (id.startsWith("L|")) {
                val path = id.removePrefix("L|")
                val perfL = System.nanoTime()
                val plain = withContext(Dispatchers.IO) { File(path).listFiles() }
                PerfLog.mark("listFiles ${plain?.size} шт: ${(System.nanoTime() - perfL) / 1_000_000} мс")
                if ((plain == null || plain.isEmpty()) && FmIo.privEnabled && (plain == null || FmIo.restricted(path) || path.trimEnd('/').isEmpty())) {
                    // /data и другие закрытые места: список читает root (или ADB), как и в обычном списке «Файлов»
                    FmIo.ensurePriv()?.let { if (it == "pair" || it == "wifi") Fm.autoPairOnce(); error("FIX:" + it) }
                    val all = AdbFs.list(path).filter { Fm.showHidden || !it.name.startsWith(".") }.sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })
                    pendCnt[id] = all.size
                    all.take(KEEP).map { if (it.isDir) TK(it.name, "L|" + it.path, it.path, true, 0L, it.date) else TK(it.name, "F|" + it.path, it.path, false, it.size, it.date) }
                } else
                withContext(Dispatchers.IO) {
                    // 14.0: тот же список без второго чтения папки и без stat в каждом сравнении сортировки (раньше isDirectory считался на каждое сравнение, затем ещё lastModified/isDirectory/length):
                    // один stat на запись, порядок прежний (папки, затем имя без учёта регистра)
                    val fast = FmSub.mode != FmSub.CREATED
                    class Ent(val f: File, val dir: Boolean, val size: Long, val dt: String, val low: String)
                    val ents = ArrayList<Ent>(plain?.size ?: 0)
                    ents.addAll(parallelMapNotNull(plain.orEmpty().asList()) { f ->
                        val nm = f.name
                        if (!Fm.showHidden && nm.startsWith(".")) return@parallelMapNotNull null
                        val st = if (fast) runCatching { android.system.Os.stat(f.path) }.getOrNull() else null
                        if (st != null) { val d = android.system.OsConstants.S_ISDIR(st.st_mode); Ent(f, d, if (d) 0L else st.st_size, FmSub.stampMs(st.st_mtime * 1000L), nm.lowercase()) }
                        else { val d = f.isDirectory; Ent(f, d, if (d) 0L else f.length(), FmSub.stamp(f), nm.lowercase()) }
                    })
                    PerfLog.mark("stat всех: ${(System.nanoTime() - perfL) / 1_000_000} мс (накоплено)")
                    ents.sortWith(compareByDescending<Ent> { it.dir }.thenBy { it.low })
                    pendCnt[id] = ents.size
                    ents.take(KEEP).map { if (it.dir) TK(it.f.name, "L|" + it.f.absolutePath, it.f.absolutePath, true, 0L, it.dt) else TK(it.f.name, "F|" + it.f.absolutePath, it.f.absolutePath, false, it.size, it.dt) }
                }
            } else {
                val a = id.split('|', limit = 5)
                val c = favs.firstOrNull { it.kind == a[1] && it.host == a[2] && it.user == a[3] } ?: error("?")
                cSel = c
                val fs = fsCache.getOrPut(a[1] + "|" + a[2] + "|" + a[3]) { c.fs() }
                (fs as? KeepFs)?.connect(); (fs as? CloudFs)?.connect()
                val path = if (a.getOrNull(4) == NetTrash.ROOT_MARK) NetTrash.rootPath(c, fs) else a.getOrNull(4)?.takeIf { it.isNotEmpty() } ?: ownPath(c) ?: fs.root
                pSel = path
                withContext(Dispatchers.IO) {
                    val all = fs.list(path)
                    pendCnt[id] = all.size
                    all.take(KEEP).map { if (it.isDir) TK(it.name, "N|" + a[1] + "|" + a[2] + "|" + a[3] + "|" + it.path, it.path, true, 0L, it.date) else TK(it.name, "f|" + a[1] + "|" + a[2] + "|" + a[3] + "|" + it.path, it.path, false, it.size, it.date) }
                }
            }
            // 22.11.2026: сортировка большой папки считается здесь, в фоне, до показа (раньше 35+ мс на главном потоке в первом кадре раскрытия)
            if (list.size >= 200) withContext(Dispatchers.Default) { for (pn in Fm.panes) runCatching { sortKids(pn, id, list) } }
            // 22.11.2026: тот же список (повторное чтение «для свежести») не публикуется: раньше это давало ещё одну перекомпоновку обеих панелей
            val oldKids = kids[id]
            val same = oldKids != null && oldKids.size == list.size && oldKids.indices.all { i -> val x = oldKids[i]; val y = list[i]; x.name == y.name && x.id == y.id && x.size == y.size && x.date == y.date && x.isDir == y.isDir && x.count == y.count && x.dirs == y.dirs && x.files == y.files }
            if (same) { pendCnt.remove(id)?.let { if (appCount[id] != it) appCount[id] = it }; autoRetry.remove(id) }
            else androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { pendCnt.remove(id)?.let { appCount[id] = it }; kids[id] = list; autoRetry.remove(id) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { kidErr[id] = FmIo.nice(e); if (e is CloudApiError && CloudErr.retryable(e.kind) && (autoRetry[id] ?: 0) < 3) { autoRetry[id] = (autoRetry[id] ?: 0) + 1; Fm.scope.launch { delay(15_000); if (kidErr[id] != null) kidErr.remove(id) } } else if (e !is CloudApiError) autoRetry.remove(id); if (e is CloudApiError && e.kind in CloudErr.ownKeyKinds) kidErrOwnKey[id] = e.type ?: "drive" else if (e is CloudApiError && e.kind == "expired") kidErrOwnKey[id] = (e.type ?: "drive") + "@publish" }
    }

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE)

    /** Сколько раскрытых узлов «домика» помнить между запусками (на панель). */
    const val MAX_OPEN = 200

    /** Восстановление раскрытых узлов панели. Сетевые, облачные узлы и ADB только запоминаются (rootPending) и раскрываются лениво, когда есть связь. false: ничего не сохранено. */
    fun restoreOpen(ctx: Context, p: FmPane): Boolean {
        // T3 (19.11.2026): строка, на которой был фокус, помнится отдельно для каждой панели
        sp(ctx).getString("root_foc_p${p.id}", null)?.let { focusKey[p.id] = it }
        val raw = sp(ctx).getString("ro_p${p.id}", null) ?: return false
        // T3 (слова владелицы 19.11.2026: «в каждом окне запоминалось, и когда запускаю прогу, было развёрнуто в обоих окнах то, что разворачивал»): помнятся ВСЕ раскрытые узлы
        // обеих панелей. Группы и локальные папки раскрываются сразу (чтение с телефона быстрое); сетевые, облачные и ADB-узлы только запоминаются (rootPending) и раскрываются лениво,
        // когда строка видна и есть связь (без обхода сети на старте); исчезнувшая локальная папка или том пропускаются тихо
        TreeOpen.withAncestors(raw.split('\n').filter { it.isNotBlank() }.takeLast(MAX_OPEN)).forEach { id ->
            val net = id.startsWith("N|") || id.startsWith("D|") || id.startsWith("T|")
            if (net) p.rootPending.add(id)
            else if (id !in p.rootOpen && (!id.startsWith("L|") || localNodeAlive(id))) p.rootOpen.add(id)
        }
        return true
    }

    fun saveOpen(ctx: Context, p: FmPane) {
        val all = (p.rootOpen.toList() + p.rootPending.filter { it !in p.rootOpen }).distinct()
        sp(ctx).edit().putString("ro_p${p.id}", all.takeLast(MAX_OPEN).joinToString("\n")).apply()
    }

    /** T3: локальный узел «L|путь» ещё существует (том смонтирован, папка на месте); закрытые места (/data, Android/data) не проверяются: их читает root или ADB. */
    private fun localNodeAlive(id: String): Boolean = runCatching {
        val path = id.removePrefix("L|").ifEmpty { "/" }
        path == "/" || FmIo.restricted(path) || java.io.File(path).isDirectory
    }.getOrDefault(true)

    /** T3: полная запись состояния обеих панелей (раскрытое, строка с фокусом, выбранная строка, активная панель); вызывается из onPause/onStop, переживает убийство процесса. */
    fun flushState(ctx: Context, sync: Boolean) {
        if (!Fm.inited) return      // экран «Файлы» ещё не открывали: сохранённое не затираем
        val e = sp(ctx).edit()
        for (p in Fm.panes) {
            val all = (p.rootOpen.toList() + p.rootPending.filter { it !in p.rootOpen }).distinct()
            e.putString("ro_p${p.id}", all.takeLast(MAX_OPEN).joinToString("\n"))
            p.rootSel?.let { e.putString("root_sel_p${p.id}", it) }
            focusKey[p.id]?.let { e.putString("root_foc_p${p.id}", it) }
        }
        e.putInt("active_pane", Fm.active)
        if (sync) e.commit() else e.apply()
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
        // «Компьютеры» (Windows-ПК по Tailscale): включена и у тех, кто уже настраивал корень (один раз)
        if (saved != null && !sp(ctx).getBoolean("root_pc_added", false)) { shown = shown + "pc"; sp(ctx).edit().putStringSet("root_show", shown.toSet()).putBoolean("root_pc_added", true).apply() }
        else if (saved == null) sp(ctx).edit().putBoolean("root_pc_added", true).apply()
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

/** Кнопка «Дом» (9.3): корневой экран, все раскрытые узлы свёрнуты, список наверху, фокус на первой строке; повторное нажатие ничего не ломает. */
fun fmHome(p: FmPane) {
    fmShowRoot(p)
    p.rootOpen.clear(); p.rootPending.clear(); p.rootSel = null
    FmRootCfg.sel.clear()
    FmRootCfg.purgeClosedCloud()
    p.homeTick++
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

@androidx.compose.runtime.Immutable
internal class RR(
    val key: String, val title: String, val icon: ImageVector, val tint: Color, val path: String? = null,
    val total: Long = 0, val free: Long = 0, val ro: Boolean = false, val fs: String = "", val warn: Boolean = false,
    val vol: FmVol? = null, val net: NetConn? = null, val level: Int = 0, val expandKey: String? = null, val header: Int = 0, val act: () -> Unit = {},
    val treeId: String? = null, val busy: Boolean = false, val appName: String? = null, val err: Boolean = false, val file: FmItem? = null, val side: String? = null,
    val pc: PcHost? = null, val adev: AdbDev? = null, val tnet: NetConn? = null, val titem: CloudTrash.TItem? = null, val subText: String? = null, val drive: Boolean = false, val selE: FmRootCfg.SelE? = null,
    val fitem: FmTrash.FItem? = null, val ftrash: Boolean = false, val retry: (() -> Unit)? = null, val wifi: Boolean = false, val tog: Boolean? = null, val lpath: String? = null, val gname: String? = null,
    val dateRaw: String? = null, val cntKey: String? = null, val cntCls: FolderCounts.Cls? = null, val cntLoader: (suspend () -> Pair<Int, Int>?)? = null, val remoteApk: RemoteApk? = null,
) {
    /** Ветви дерева: для уровней 1..level true, если ниже есть ещё элемент того же уровня; lx: позиция вертикали уровня (dp). */
    var more: BooleanArray = BooleanArray(0)
    var lx: FloatArray = FloatArray(0)
    /** Своя ветвь (уровень level): засечка к строке и конец вертикали (до середины строки). Как в X-plore: линия одна, от родителя к раскрытому потомку цепочки. */
    var tick: Boolean = true
    var half: Boolean = true
    /** 22.11.2026: сколько раскрытых папок содержат эту строку (считая её саму, если она раскрыта): общий фон блока и цветные линии слева. */
    var grp: Int = 0
    /** Границы блоков раскрытых папок: бит k = строка первая (gTop) / последняя (gBot) у блока уровня k. */
    var gTop: Int = 0
    var gBot: Int = 0
    /** Раскрытое устройство или папка: все элементы внутри (папки и файлы). Галочка справа от такой строки отмечает или снимает отметку со всех сразу, как в X-plore. */
    var selAll: List<FmRootCfg.SelE> = emptyList()
    /** Устройство или подключение без своей отметки: показывается галочка «отметить всё внутри». */
    var selReq: Boolean = false
    /** Для устройства с «Корзиной» и «диском»: узел «диск» (корень источника), в котором отмечается всё. */
    var selTarget: String? = null

    /**
     * 14.0: сравнение строк по значению (без лямбд) для «обычных» строк: строка списка пропускается при перекомпоновке, если её описание не изменилось. Раньше список «Источники» при любом изменении
     * (раскрытие, пачка цифр, смена связи) строил новые RR, и ВСЕ видимые строки обеих панелей перерисовывались заново (на слабой приставке по несколько мс на строку, в первом кадре после нажатия около 100 мс и больше).
     * Лямбды таких строк (act, cntLoader) захватывают только то, что входит в подпись (ключ, путь, подключение с хэшем пароля, элемент). Строки с ошибками и служебные, корзины, устройства (ПК, Android),
     * Wi-Fi и поиск по сети сравниваются по ссылке, как раньше (всегда «новые»).
     */
    private val memoOk: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        if (pc != null || adev != null || titem != null || fitem != null || retry != null || ftrash || tnet != null || wifi) false
        else key == "dev" || key == "root" || key == "recent" || key.startsWith("vol:") || key.startsWith("h:") || key.startsWith("net:") || key.startsWith("lanf:") ||
            (expandKey != null) || key.startsWith("t:L|") || key.startsWith("t:F|") || key.startsWith("t:N|") || key.startsWith("t:f|") || key.startsWith("t:D|") || key.startsWith("t:Aitem|")
    }
    private fun netSig(c: NetConn?): String = if (c == null) "-" else c.kind + "|" + c.host + "|" + c.port + "|" + c.user + "|" + c.pass.hashCode() + "|" + c.path + "|" + c.smb + "|" + c.name
    private val sig: String by lazy(LazyThreadSafetyMode.NONE) {
        val sb = StringBuilder(200)
        val s = 1.toChar()
        sb.append(key).append(s).append(title).append(s).append(System.identityHashCode(icon)).append(s).append(tint.value).append(s).append(path).append(s)
            .append(total).append(s).append(free).append(s).append(ro).append(s).append(fs).append(s).append(warn).append(s).append(vol).append(s)
            .append(netSig(net)).append(s).append(level).append(s).append(expandKey).append(s).append(header).append(s).append(treeId).append(s)
            .append(busy).append(s).append(appName).append(s).append(err).append(s).append(file).append(s).append(side).append(s).append(subText).append(s)
            .append(drive).append(s).append(tog).append(s).append(lpath).append(s).append(gname).append(s).append(dateRaw).append(s)
            .append(cntKey).append(s).append(System.identityHashCode(cntCls)).append(s).append(remoteApk?.key).append(s)
        selE?.let { e -> sb.append(e.key).append('|').append(e.parentId).append('|').append(e.parentPath).append('|').append(e.name).append('|').append(e.path).append('|').append(e.isDir).append('|').append(netSig(e.net)) }
        sb.append(s).append(tick).append(half).append(grp).append(gTop).append(gBot).append(selReq).append(selTarget).append(s).append(more.contentToString()).append(lx.contentToString()).append(s)
        for (e in selAll) sb.append(e.key).append(',')
        sb.toString()
    }
    override fun equals(other: Any?): Boolean = this === other || (other is RR && memoOk && other.memoOk && sig == other.sig)
    override fun hashCode(): Int = if (memoOk) sig.hashCode() else System.identityHashCode(this)
}

private fun statOf(path: String): Pair<Long, Long> = runCatching {
    val st = android.os.StatFs(path); (st.blockCountLong * st.blockSizeLong) to (st.availableBlocksLong * st.blockSizeLong)
}.getOrDefault(0L to 0L)

/**
 * 22.11.2026: карта с состоянием на КАЖДЫЙ ключ (вместо mutableStateMapOf, у которого чтение любого ключа подписывает на всю карту). Раскрытие папки в одной панели
 * записывает kids/kidErr/scrollTarget своего узла и больше не перестраивает список соседней панели (две-три перекомпоновки по ~40-75 мс подряд).
 */
class PerKeyMap<K : Any, V : Any> : AbstractMutableMap<K, V>() {
    private val m = java.util.concurrent.ConcurrentHashMap<K, androidx.compose.runtime.MutableState<V?>>()
    private fun st(k: K) = m.getOrPut(k) { mutableStateOf<V?>(null) }
    override fun get(key: K): V? = st(key).value
    override fun containsKey(key: K): Boolean = st(key).value != null
    override fun put(key: K, value: V): V? { val s = st(key); val o = s.value; s.value = value; return o }
    override fun remove(key: K): V? { val s = m[key] ?: return null; val o = s.value; if (o != null) s.value = null; return o }
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = m.entries.mapNotNull { e -> e.value.value?.let { v -> java.util.AbstractMap.SimpleEntry(e.key, v) as MutableMap.MutableEntry<K, V> } }.toMutableSet()
    override fun clear() { for (s in m.values) if (s.value != null) s.value = null }
}

/** 22.11.2026: запомненные строки-файлы раскрытой папки (см. addKids). */
private class KidRows(val src: Any?, val level: Int, val c: NetConn?) { val map = HashMap<String, RR>() }

/** 23.11.2026: результат сборки строк «Источников» (считается в фоновом потоке, на главный поток попадает готовым). */
internal class RootBuilt(
    val rows: List<RR>, val keys: List<String>, val favs: List<NetConn>, val openIds: List<String>,
    val activate: List<String>, val sumTodo: List<String>, val applyNow: List<Pair<String, List<FmRootCfg.SelE>>>, val visSel: FmRootCfg.VisSel,
    val wifiLoad: Boolean, val probeKey: String?, val probePcs: List<PcHost>, val probeAnds: List<AdbDev>,
    val sortTick: Int, val trashVer: Int,
)

/**
 * 23.11.2026 (владелица: «делай фоновую сборку строк как у X-plore»): модель списка «Источников» вне Compose. Строки (RR), ветви дерева, блоки раскрытых папок, «выбрать всё внутри»
 * собираются в фоновом потоке по версии состояний (snapshotFlow читает те же состояния, что раньше читала перекомпоновка); на главный поток попадает только готовый результат,
 * который применяется к адаптеру RecyclerView (вставка и удаление диапазона строк) без перекомпоновки FmRootList.
 */
internal class RootModel(val p: FmPane, val ctx: Context, prim0: Color, sig0: String) {
    var prim by mutableStateOf(prim0)
    var cfgSig by mutableStateOf(sig0)
    private var retry by mutableStateOf(0)
    /** Последний применённый результат (главный поток). */
    var built: RootBuilt? = null
    var ver by androidx.compose.runtime.mutableIntStateOf(0)
    var rowsState by mutableStateOf<List<RR>>(emptyList())
    var sink: ((RootBuilt) -> Unit)? = null
    /** Вызывается сразу после того, как строки попали в адаптер (до раскладки): здесь список сразу ставится на раскрытую папку, чтобы раскладка была одна. */
    var afterApply: (() -> Unit)? = null
    private val kidRowCache = HashMap<String, KidRows>()
    private var favKey: List<Any?>? = null
    private var favsSavedC: List<NetConn> = emptyList()
    private var onlineFav: List<NetConn>? = null
    private var onlineV = false
    private var devStat: Pair<Boolean, Pair<Long, Long>>? = null
    private var pcsVer = -1; private var pcsC: List<PcHost> = emptyList(); private var andsC: List<AdbDev> = emptyList()
    private fun s(id: Int): String = ctx.getString(id)
    private fun s(id: Int, vararg a: Any): String = ctx.getString(id, *a)
    private fun onlineFor(f: List<NetConn>): Boolean { if (onlineFav !== f) { onlineFav = f; onlineV = FmRootCfg.hasNetwork(ctx) }; return onlineV }
    private fun pcsFor(v: Int): List<PcHost> { if (pcsVer != v) { pcsVer = v; pcsC = loadHosts(ctx); andsC = androidSync(ctx) }; return pcsC }
    private fun andsFor(v: Int): List<AdbDev> { pcsFor(v); return andsC }

    /** Один проход сборки. Вызывается в фоновом потоке внутри snapshotFlow (чтение состояний подписывает на перестройку). null: гонка с главным потоком, будет повтор. */
    fun buildSafe(): RootBuilt? {
        @Suppress("UNUSED_VARIABLE") val rt = retry
        return try { val b = build(); fails = 0; b } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            // гонка с главным потоком (обычные карты) или запись состояния в снимке только для чтения: повтор, а при повторном сбое сборка один раз идёт на главном потоке (см. loop)
            fails++; android.util.Log.w("RootModel", "сборка строк: " + e.javaClass.simpleName + " " + e.message)
            Fm.scope.launch { delay(120); retry++ }; null
        }
    }
    @Volatile private var fails = 0

    fun build(): RootBuilt {
        val prim = this.prim
        @Suppress("UNUSED_VARIABLE") val sigNow = cfgSig
        val shown = FmRootCfg.shown
        val fk = listOf(Fm.dlg, p.root, FmRootCfg.ver, WifiExplorer.ver)
        if (fk != favKey) { favKey = fk; favsSavedC = NetStore.list(ctx) + WifiExplorer.found.toList() }
        val favsSaved = favsSavedC
        // 10.11.2026 (владелица): найденные по LAN диски раскрываются как папки, даже если не добавлены в избранное: их подключения тоже в этом списке (для раскрытия), но не в списках групп
        val favs = favsSaved + FmRootCfg.lanFound.map { FmRootCfg.lanConn(it) }
        val tv = Marq.tv
        if (devStat?.first != p.root) devStat = p.root to statOf("/sdcard")
        val (devTotal, devFree) = devStat!!.second
        val sortTickRead = Fm.sortTick
        val trashVer = CloudTrash.ver + FmTrash.ver
        var wifiLoad = false; var probeKey: String? = null; var probePcs: List<PcHost> = emptyList(); var probeAnds: List<AdbDev> = emptyList()
    val perfT0 = System.nanoTime()
        val rows = ArrayList<RR>()
        val visOpen = ArrayList<String>()
        rows += RR("h:mem", s(R.string.rt_group_storage), Icons.Filled.PhoneAndroid, prim, header = 1)
        val treeOn = FmRootCfg.treeOn
        @Suppress("UNUSED_VARIABLE") val sortTickNow = Fm.sortTick   // перерисовка дерева при смене настройки «Сортировка»
        val openIds = p.rootOpen.toList()
        // 14.11.2026 (владелица: «зачем в LAN корзина? убери»): у сетевых подключений (LAN, WebDAV, Wi-Fi) строк «Корзина» и «диск» нет; корзина осталась у облаков (их своя) и у локальной памяти (наша); облака с корзиной показывают две виртуальные папки («Корзина» и «Облачный диск»), сам узел облака папок не грузит
        val virtIds = favs.filter { it.kind == "cloud" && CloudTrash.supported(CloudTrash.typeFor(ctx, it)) }.map { "N|" + it.kind + "|" + it.host + "|" + it.user + "|" }.toSet()
        val trRowTxt = s(R.string.tr_row); val trDriveTxt = s(R.string.tr_drive); val ntDriveTxt = s(R.string.nt_drive)
        // запомненные сетевые и ADB-узлы раскрываются лениво: только когда строка уже видна (родитель раскрыт) и есть связь
        val online = onlineFor(favsSaved)
        val activate = ArrayList<String>()
        fun pend(id: String?) { if (id != null && id in p.rootPending && (if (id.startsWith("D|")) Adb.isConnected else online)) activate += id }
        val loadingTxt = s(R.string.rt_loading)
        /** Квадратик выбора для файла или папки узла [parent]: путь родителя берётся из идентификатора узла. */
        fun selOf(k: FmRootCfg.TK, parent: String, c: NetConn?): FmRootCfg.SelE? {
            val pp = if (parent.startsWith("L|")) parent.removePrefix("L|") else if (parent.startsWith("N|")) parent.split('|', limit = 5).getOrNull(4).orEmpty().let { if (it == NetTrash.ROOT_MARK && c != null) NetTrash.rootPath(c, FmRootCfg.fsFor(c)) else it }.ifEmpty { FmRootCfg.ownPath(c) ?: "/" } else return null
            return FmRootCfg.SelE(k.id, if (parent.startsWith("N|")) c else null, parent, pp, k.name, k.path, k.isDir, null)
        }
        fun addKids(id: String, level: Int, c: NetConn?) {
            if (!treeOn || id !in p.rootOpen) return
            visOpen += id
            val ks0 = FmRootCfg.kids[id]
            // 10.0 (chk): сначала сортировка по настройке, потом обрезка до LIMIT строк (раньше обрезалось по имени, и при «по дате»/«по размеру» в больших папках нужное отсекалось)
            val ks = ks0?.let { PerfLog.t("sortKids(${it.size})", 1) { FmRootCfg.sortKids(p, id, it) } }?.let { if (id.startsWith("L|") || id.startsWith("N|")) it.take(FmRootCfg.LIMIT) else it }
            if (ks != null && AppLog.enabled) Thumbs.logList("дерево", id, ks.count { !it.isDir && it.name.substringAfterLast('.', "").lowercase().let { e -> e == "apk" || e == "apks" || e == "xapk" || e == "apkm" } })
            if (ks == null) {
                val er0 = FmRootCfg.kidErr[id]; val ok = FmRootCfg.kidErrOwnKey[id]
                // «отказано в доступе» (нет root): спокойная подсказка вместо красной ошибки
                if (er0 == CloudModule.MARK) {
                    // 12.0: метка MARK не хранит само состояние (OUTDATED/MISSING/WRONG_ABI) — раньше тут всегда писалось «Нужен модуль»,
                    // даже когда он стоит, но устарел под эту версию программы; теперь текст берётся по актуальному state() заново
                    val cmSt = CloudModule.state(ctx)
                    val cmTitle = CloudModule.missingText(ctx, cmSt)
                    // 12.1: модуль уже рабочий, а пометка осталась (поставили снаружи): строка «Перепроверить» вместо «нужен/обновить»; причина для устаревшего
                    val cmSub = when (cmSt) {
                        CloudModule.State.OK -> Fm.str(R.string.cm_tap_recheck)
                        CloudModule.State.OUTDATED -> Fm.str(R.string.cm_tap_update) + (CloudModule.reasonText(ctx)?.let { "\n$it" } ?: "")
                        else -> Fm.str(R.string.cm_tap_install)
                    }
                    rows += RR("t:" + id + ":cm", if (cmSt == CloudModule.State.OK) Fm.str(R.string.cm_recheck_title) else cmTitle, Icons.Filled.Cloud, Color(0xFF9E9E9E), level = level, subText = cmSub,
                        act = {
                            if (CloudModule.state(ctx) == CloudModule.State.OK) { FmRootCfg.kidErr.remove(id); FmRootCfg.kids.remove(id); CloudTrash.ver++ }
                            else Fm.dlg = FmDlg.CloudModule { FmRootCfg.kidErr.remove(id); FmRootCfg.kids.remove(id); CloudTrash.ver++ }
                        })
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
            val perfLoop = System.nanoTime()
            // 22.11.2026: строки-файлы раскрытой большой папки (RR) запоминаются, пока тот же список записей, уровень и подключение: раскрытие и любая перекомпоновка не строят 3000 объектов заново
            val kcOld = kidRowCache[id]?.takeIf { it.src === ks0 && it.level == level && it.c == c }
            val kcNew = if (ks.size >= 200 && kcOld == null) KidRows(ks0, level, c).also { if (kidRowCache.size > 16) kidRowCache.clear(); kidRowCache[id] = it } else kcOld
            ks.forEach { k ->
                if (k.id.startsWith("Ti|")) {
                    val ti = CloudTrash.tkItems[k.id]
                    val dirTxt = ti?.let { "/" + it.origPath.trim('/').substringBeforeLast('/', "") }
                    val whenTxt = ti?.takeIf { it.deletedAt > 0 }?.let { java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it.deletedAt)) }
                    rows += RR("t:" + k.id, k.name, if (k.isDir) Icons.Filled.Folder else iconFor(FmItem(k.name, false, 0, "")), if (k.isDir) Color(0xFFFFB74D) else Color(0xFF90A4AE), level = level, tnet = c, titem = ti, gname = if (k.isDir) null else k.name,
                        side = if (k.size >= 0) fmSize(k.size) else null, subText = listOfNotNull(dirTxt, whenTxt?.let { nbBlock(it) }).joinToString(SUB_SEP),
                        selE = ti?.let { FmRootCfg.SelE(k.id, c, id, "", k.name, k.path, k.isDir, it) })
                    return@forEach
                }
                if (k.id.startsWith("Ki|")) {
                    val fi = FmTrash.tk[k.id]
                    val whenTxt = fi?.takeIf { it.deletedAt > 0 }?.let { java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it.deletedAt)) }
                    rows += RR("t:" + k.id, k.name, if (k.isDir) Icons.Filled.Folder else iconFor(FmItem(k.name, false, 0, "")), if (k.isDir) Color(0xFFFFB74D) else Color(0xFF90A4AE), level = level, fitem = fi, gname = if (k.isDir) null else k.name,
                        side = if (k.size >= 0) fmSize(k.size) else null, subText = listOfNotNull(fi?.folder?.let { shortMiddle(it, 24) }, whenTxt?.let { nbBlock(it) }, fi?.volLabel).joinToString(SUB_SEP),
                        selE = fi?.let { FmRootCfg.SelE(k.id, null, id, "", k.name, k.path, k.isDir, null, it) })
                    return@forEach
                }
                if (k.id.startsWith("Te|")) { rows += RR("t:" + k.id, k.name, Icons.Filled.Folder, Color(0xFF9E9E9E), level = level); return@forEach }
                if (!k.isDir) {
                    kcNew?.map?.get(k.id)?.let { rows += it; return@forEach }
                    val item = FmItem(k.name, false, k.size, "")
                    val rrNew = RR("t:" + k.id, k.name, iconFor(item), Color(0xFF90A4AE), level = level, file = item, dateRaw = k.date.takeIf { it.isNotBlank() }, lpath = if (id.startsWith("L|") && item.ext in setOf("apk", "apks", "xapk", "apkm")) k.path else null,
                        // 11.0: значок APK и на телефоне по ADB, и в сети: временная копия (до 150 МБ), облако только из кэша значков
                        remoteApk = if (!id.startsWith("L|") && k.size > 0 && item.ext in setOf("apk", "apks", "xapk", "apkm") && c == null && id.startsWith("D|"))   // 22.11.2026: по сети (LAN, SMB, FTP, WebDAV, облака) значки APK не грузятся
                            RemoteApk((if (c != null) "N|" + c.kind + "|" + c.host + "|" + c.user else "D") + "|" + k.path + "|" + k.size + "|" + k.date, k.size, c == null || c.kind != "cloud", if (c == null) k.path else null) { dest ->
                                if (c != null) FmRootCfg.fsFor(c).copyTo(k.path, dest) else Adb.pull(k.path, dest)
                            } else null,
                        act = { Fm.openFromTree(c, k.path, item) }, selE = selOf(k, id, c))
                    rows += rrNew; kcNew?.map?.put(k.id, rrNew)
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
                        dateRaw = k.date.takeIf { it.isNotBlank() },
                        cntKey = if (c?.kind == "cloud") null else (if (c == null) "L" else c.kind + "|" + c.host + "|" + c.user) + "|" + k.path + "|" + k.date, cntCls = FolderCounts.clsOf(c),   // облака: у папок только дата, цифры не считаются
                        cntLoader = if (c == null) ({ FolderCounts.localCount(File(k.path)) }) else ({ FolderCounts.netCount(c, k.path) }),
                        act = { if (c == null) fmOpenLocal(p, k.path) else { fmLeaveRoot(p); Fm.connectNet(p, c, k.path) } })
                    pend(k.id)
                    addKids(k.id, level + 1, c)
                }
            }
            PerfLog.mark("addKids цикл ${ks.size} строк: ${(System.nanoTime() - perfLoop) / 1_000_000} мс")
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
        rows += RR("dev", s(R.string.rt_internal), Icons.Filled.PhoneAndroid, Color(0xFF26A69A), "/sdcard", devTotal, devFree, act = { fmOpenLocal(p, "/sdcard") }, treeId = "L|/sdcard")
        addKids("L|/sdcard", 1, null)
        Fm.vols.forEach { v ->
            rows += RR("vol:" + v.path, v.label, if (v.usb) Icons.Filled.Usb else Icons.Filled.SdCard, Color(0xFF5C6BC0), if (v.ok) v.path else null, v.total, v.free, v.ro, v.fs, !v.ok, v,
                act = { if (v.ok) fmOpenLocal(p, v.path) else Fm.say(Fm.str(R.string.fm_vol_unsupported_short)) }, treeId = if (v.ok) "L|" + v.path else null)
            if (v.ok) addKids("L|" + v.path, 1, null)
        }
        if ("root" in shown) { rows += RR("root", "Root", Icons.Filled.PhoneAndroid, Color(0xFF8D6E63), "/", devTotal, devFree, act = { fmOpenLocal(p, "/") }, treeId = "L|/"); addKids("L|/", 1, null) }
        if ("recent" in shown) { rows += RR("recent", s(R.string.rt_recent), Icons.Filled.History, Color(0xFFFFA726), act = { Fm.dlg = FmDlg.History() }, treeId = "H|"); addKids("H|", 1, null) }
        // «Корзина» файлового менеджера: рядом с «Недавними», число и размер по всем томам; раскрывается в список удалённого
        if (FmDel.trashOn || FmTrash.count > 0) {
            rows += RR("ftrash", s(R.string.tr_row), Icons.Filled.DeleteOutline, Color(0xFFEF5350), side = FmTrash.sumText(), treeId = "K|", ftrash = true, act = { Fm.active = p.id; Fm.dlg = FmDlg.FTrash() })
            addKids("K|", 1, null)
        }
        val lan = favsSaved.filter { it.kind != "dav" && it.kind != "cloud" }
        val dav = favsSaved.filter { (it.kind == "dav" && !WifiExplorer.isWifi(it)) || it.kind == "cloud" }
        val wifiSaved = favsSaved.filter { WifiExplorer.isWifi(it) && it !in WifiExplorer.found }
        if ("lan" in shown || "cloud" in shown || "wifi" in shown || "adb" in shown) rows += RR("h:net", s(R.string.rt_group_net), Icons.Filled.Lan, prim, header = 1)
        if ("lan" in shown) {
            rows += RR("lan", s(R.string.rt_lan), Icons.Filled.Lan, Color(0xFF42A5F5), expandKey = "lan")
            if ("lan" in p.rootOpen) {
                lan.forEach { c ->
                    val tid = "N|" + c.kind + "|" + c.host + "|" + c.user + "|"
                    rows += RR("net:" + c.kind + c.host + c.user, c.title, Icons.Filled.Dns, Color(0xFF42A5F5), net = c, level = 1, act = { fmLeaveRoot(p); Fm.connectNet(p, c, fresh = true) }, treeId = tid)
                    pend(tid)
                    if (tid in virtIds) addVirt(c, tid) else addKids(tid, 2, c)
                }
                val savedKeys = favsSaved.map { it.kind + "|" + it.host + "|" + it.user }.toSet()
                FmRootCfg.lanFound.toList().forEach { h ->
                    val c = FmRootCfg.lanConn(h)
                    if ((c.kind + "|" + c.host + "|" + c.user) in savedKeys) return@forEach
                    val tid = "N|" + c.kind + "|" + c.host + "|" + c.user + "|"
                    rows += RR("lanf:" + c.kind + c.host, h.name.ifBlank { h.ip }, Icons.Filled.Dns, Color(0xFFFFC107), net = c, level = 1,
                        subText = if (h.name.isNotBlank() && h.name != h.ip) h.ip else null, act = { fmLeaveRoot(p); Fm.openLanHost(p, h) }, treeId = tid)
                    addKids(tid, 2, c)
                }
                if (FmRootCfg.lanScanning) rows += RR("lan:scan", s(R.string.fm_net_scanning), Icons.Filled.Search, prim, level = 1, busy = true, subText = s(R.string.wf_cancel_hint), act = { FmRootCfg.lanCancel() })
                else {
                    if (FmRootCfg.lanNone && FmRootCfg.lanFound.isEmpty()) rows += RR("lan:none", s(R.string.wf_none), Icons.Filled.Search, Color(0xFF9E9E9E), level = 1)
                    rows += RR("lan:find", s(R.string.fm_net_scan_short), Icons.Filled.Search, prim, level = 1, act = { FmRootCfg.lanScan(ctx) })
                }
                rows += RR("lan+", s(R.string.rt_add_net), Icons.Filled.Add, prim, level = 1, act = { Fm.dlg = FmDlg.Source() })
            }
        }
        if ("cloud" in shown) {
            rows += RR("cloud", s(R.string.rt_cloud), Icons.Filled.Cloud, Color(0xFF4FC3F7), expandKey = "cloud")
            if ("cloud" in p.rootOpen) {
                dav.forEach { c ->
                    val tid = "N|" + c.kind + "|" + c.host + "|" + c.user + "|"
                    rows += RR("net:" + c.kind + c.host + c.user, c.title, Icons.Filled.Cloud, Color(0xFF4FC3F7), net = c, level = 1, act = { fmLeaveRoot(p); Fm.connectNet(p, c) }, treeId = tid)
                    pend(tid)
                    if (tid in virtIds) addVirt(c, tid) else addKids(tid, 2, c)
                }
                rows += RR("cloud+", s(R.string.rt_add_cloud), Icons.Filled.Add, prim, level = 1, act = { Fm.dlg = FmDlg.CloudAdd() })
            }
        }
        // «Wi-Fi проводник»: найденные поиском, свой сервер (галочка), сохранённые устройства, «Добавить устройство» (запускает поиск)
        if ("wifi" in shown) {
            val wfTint = if (WifiShare.running) Color(0xFF2E9E4F) else Color(0xFFF57C00)   // сервер включён: зелёный, выключен: оранжевый
            wifiLoad = true
            rows += RR("wifi", s(R.string.rt_wifi), Icons.Filled.Wifi, wfTint, expandKey = "wifi",
                subText = s(R.string.wf_mode_label, s(when (WifiExplorer.mode) { "lan" -> R.string.wf_mode_lan; "direct" -> R.string.wf_mode_direct; else -> R.string.wf_mode_auto })))
            if ("wifi" in p.rootOpen) {
                val needKeyTxt = s(R.string.wf_need_key)
                val srvOn = s(R.string.wf_on_sub, WifiShare.url); val srvOff = s(R.string.wf_off)
                if (WifiExplorer.scanning) rows += RR("wifi:scan", if (WifiExplorer.progress >= 100) s(R.string.wf_scanning_direct) else s(R.string.wf_scanning, WifiExplorer.progress), Icons.Filled.Search, prim, level = 1, busy = true, subText = s(R.string.wf_cancel_hint), act = { WifiExplorer.cancel() })
                fun devRow(c: NetConn, saved: Boolean) {
                    val tid = "N|" + c.kind + "|" + c.host + "|" + c.user + "|"
                    rows += RR("wifi:" + (if (saved) "s:" else "f:") + c.host, c.name.ifBlank { WifiExplorer.hostText(c) }, Icons.Filled.PhoneAndroid, if (saved) Color(0xFF26A69A) else Color(0xFFFFC107), net = c, level = 1, wifi = true,
                        subText = WifiExplorer.hostText(c) + (if (!saved && WifiExplorer.needKey[c.host] == true) "  ·  " + needKeyTxt else ""), treeId = tid, act = { fmLeaveRoot(p); Fm.connectNet(p, c) })
                    if (saved) pend(tid)
                    addKids(tid, 2, c)
                }
                WifiExplorer.found.toList().forEach { devRow(it, false) }
                if (WifiExplorer.noneFound && !WifiExplorer.scanning && WifiExplorer.found.isEmpty()) rows += RR("wifi:none", s(R.string.wf_none), Icons.Filled.Search, Color(0xFF9E9E9E), level = 1, act = { WifiExplorer.scan(ctx) })
                rows += RR("wifi:srv", s(R.string.wf_server_row), Icons.Filled.Wifi, Color(0xFF29B6F6), level = 1, tog = WifiShare.running, subText = if (WifiShare.running) srvOn else srvOff, act = { WifiExplorer.toggleServer(ctx) })
                wifiSaved.forEach { devRow(it, true) }
                rows += RR("wifi+", s(R.string.wf_add), Icons.Filled.Add, prim, level = 1, act = { WifiExplorer.scan(ctx) })
            }
        }
        // «Устройства» (10.11.2026, одна группа вместо «Компьютеров»; владелица: «одно устройство, а в нём компы и андроиды; у каждого папка для файлов и папка для видеоокна»).
        // В группе вперемешку Windows-ПК (по Tailscale, служба «Телефон в окне со звуком») и Android-устройства (ADB по сети). Строка раскрывается в ровно две папки:
        // «Файлы» (корень файлов устройства открывается источником в панели; две панели могут смотреть на разные устройства) и «Видеоокно» (экран открывается окном).
        // Остальное (Консоль, Отключить, Забыть, Изменить, Подключить по Tailscale) в меню долгого нажатия строки устройства. Ключ группы остался «pc» (выбор «Показать/Скрыть» сохраняется).
        if ("pc" in shown) {
            val pcs = pcsFor(FmRootCfg.ver)
            val ands = andsFor(FmRootCfg.ver)
            rows += RR("pc", s(R.string.rt_pc), Icons.Filled.Devices, Color(0xFF42A5F5), expandKey = "pc")
            if ("pc" in p.rootOpen) {
                val devKeys = pcs.joinToString(",") { it.ip } + "|" + ands.joinToString(",") { it.addr + it.alt }
                probeKey = devKeys + "#" + FmRootCfg.ver; probePcs = pcs; probeAnds = ands
                val filesTxt = s(R.string.dev_files); val videoTxt = s(R.string.dev_window)
                val callVTxt = s(R.string.dev_call_video); val callATxt = s(R.string.dev_call_audio)
                val onTxt = s(R.string.pc_online); val offTxt = s(R.string.pc_offline); val chkTxt = s(R.string.pc_checking)
                val all = pcs.map { it.name.ifBlank { it.ip } to (it as Any) } + ands.map { it.title to (it as Any) }
                all.sortedWith { x, y -> SortEngine.nameCmp(x.first, y.first, true, true) }.forEach { (_, dv) ->
                    if (dv is PcHost) {
                        val h = dv; val up = PcStatus.up[h.ip]; val ek = "dev:pc:" + h.ip
                        rows += RR("pc:" + h.ip, h.name.ifBlank { h.ip }, Icons.Filled.Computer, if (up == true) Color(0xFF26A69A) else Color(0xFF9E9E9E), level = 1, pc = h, expandKey = ek,
                            subText = h.ip + "  ·  " + (if (up == true) onTxt else if (up == false) offTxt else chkTxt))
                        if (ek in p.rootOpen) {
                            rows += RR("pcc:" + h.ip, callVTxt, Icons.Filled.Videocam, Color(0xFF26A69A), level = 2, act = { devCall(ctx, h.name.ifBlank { h.ip }, listOf(h.ip), h.code, false) })
                            rows += RR("pca:" + h.ip, callATxt, Icons.Filled.Call, Color(0xFF26A69A), level = 2, act = { devCall(ctx, h.name.ifBlank { h.ip }, listOf(h.ip), h.code, true) })
                            rows += RR("pcv:" + h.ip, videoTxt, Icons.Filled.DesktopWindows, Color(0xFF42A5F5), level = 2, act = { Fm.dlg = FmDlg.PcScreen(h) })
                            rows += RR("pcf:" + h.ip, filesTxt, Icons.Filled.Folder, Color(0xFFFFB74D), level = 2, act = { fmLeaveRoot(p); Fm.connectNet(p, h.conn(ctx)) })
                        }
                    } else if (dv is AdbDev) {
                        val d = dv; val up = AdbStatus.up[d.key]; val ek = "dev:" + d.key
                        rows += RR(d.key, d.title, Icons.Filled.PhoneAndroid, if (up == true) Color(0xFF9CCC65) else Color(0xFF9E9E9E), level = 1, adev = d, expandKey = ek,
                            subText = (AdbStatus.via[d.key] ?: d.addr) + "  ·  " + (if (up == true) onTxt else if (up == false) offTxt else chkTxt))
                        if (ek in p.rootOpen) {
                            rows += RR("andc:" + d.addr, callVTxt, Icons.Filled.Videocam, Color(0xFF26A69A), level = 2, act = { devCall(ctx, d.title, d.callIps(), null, false) })
                            rows += RR("anda:" + d.addr, callATxt, Icons.Filled.Call, Color(0xFF26A69A), level = 2, act = { devCall(ctx, d.title, d.callIps(), null, true) })
                            rows += RR("andf:" + d.addr, filesTxt, Icons.Filled.Folder, Color(0xFFFFB74D), level = 2, act = { DeviceWin.open(ctx, d, DeviceWin.FILES) })
                            rows += RR("andv:" + d.addr, videoTxt, Icons.Filled.DesktopWindows, Color(0xFF9CCC65), level = 2, act = { DeviceWin.open(ctx, d, DeviceWin.SCREEN) })
                        }
                    }
                }
                rows += RR("pc+", s(R.string.pc_add), Icons.Filled.Add, prim, level = 1, act = { Fm.dlg = FmDlg.DevAdd(null, null) })
            }
        }
        // число в строках «Корзина»: когда папки облака уже показаны, по одному облаку за раз
        val sumTodo = if (treeOn) ((if ("cloud" in shown && "cloud" in p.rootOpen) dav else emptyList()) + (if ("lan" in shown && "lan" in p.rootOpen) lan else emptyList())).filter { c ->
            val tid = "N|" + c.kind + "|" + c.host + "|" + c.user + "|"
            tid in p.rootOpen && CloudTrash.sums[NetTrash.rid(c)] == null && CloudTrash.supported(CloudTrash.typeFor(ctx, c))
        }.map { NetTrash.rid(it) } else emptyList()
        if ("apps" in shown) {
            rows += RR("apps", s(R.string.rt_apps), Icons.Filled.Android, Color(0xFF9CCC65), expandKey = "apps")
            if ("apps" in p.rootOpen) AppsFs.keys.forEach { (k, res) -> rows += RR("apps:" + k, s(res), Icons.Filled.Folder, Color(0xFF9CCC65), level = 1, act = { Fm.openApps(p, k) }, treeId = "A|" + k); addKids("A|" + k, 2, null) }
        }
        if ("adb" in shown) { rows += RR("adb", s(R.string.rt_adb), Icons.Filled.Android, Color(0xFF9CCC65), act = { fmLeaveRoot(p); Fm.setSource(p, "adb") }, treeId = "D|/"); pend("D|/"); addKids("D|/", 1, null) }
        PerfLog.mark("строки собраны (до пост-обработки): ${(System.nanoTime() - perfT0) / 1_000_000} мс, всего ${rows.size}")
        rows += RR("showhide", s(R.string.rt_showhide), Icons.Filled.Settings, Color(0xFF78909C), act = { Fm.dlg = FmDlg.RootShow() })
    
        // 10.11.2026 (владелица: «галочка у любого раскрытого устройства или папки, везде и у всех»): у каждой раскрытой строки дерева (устройство, том, облако, диск, папка, корзина) галочка отмечает всё, что отмечаемо в ней (прямые дети);
        // у строки без своей отметки и свёрнутой галочка тоже есть: нажатие раскрывает узел и отмечает всё внутри после загрузки
        val perfP = System.nanoTime()
        val applyNow = ArrayList<Pair<String, List<FmRootCfg.SelE>>>()
        val openSetNow = HashSet<String>(p.rootOpen)   // одно чтение состояния вместо чтения на каждую строку
        for (i in rows.indices) {
            val r = rows[i]; val tid = r.treeId ?: continue
            if (!treeOn || r.header == 1 || tid !in openSetNow) continue
            val acc = ArrayList<FmRootCfg.SelE>()
            var j = i + 1
            while (j < rows.size && rows[j].header != 1 && rows[j].level > r.level) { if (rows[j].level == r.level + 1) rows[j].selE?.let { acc += it }; j++ }
            if (acc.isNotEmpty()) { r.selAll = acc; if (tid in FmRootCfg.selAllPending) applyNow += tid to r.selAll }
        }
        PerfLog.mark("пост: selAll ${(System.nanoTime() - perfP) / 1_000_000} мс")
        // 10.11.2026 (владелица): групповых галочек (LAN, Облачные диски, Wi-Fi, Приложения) и галочек у аккаунтов облаков нет: галочка только у раскрытой строки, где ПРЯМО внутри есть папки или файлы
        // 10.11.2026 (владелица): галочка «всё внутри» только у групп, у строки «ADB» и у РАСКРЫТЫХ строк; у свёрнутых устройств и папок её нет
        for (r in rows) {
            val tid = r.treeId ?: continue
            if (true) continue   // 10.11.2026 (владелица): галочка только у РАСКРЫТЫХ строк, где есть что отмечать; «пустых» галочек (LAN без папок, свёрнутые устройства) нет
            if (!treeOn || r.header == 1 || r.selE != null || r.selAll.isNotEmpty() || r.file != null || r.appName != null) continue
            r.selReq = true
            if (tid in virtIds) r.selTarget = tid + (if (tid.startsWith("N|cloud|")) "/" else NetTrash.ROOT_MARK)
        }
        // 21.11.2026 (ускорение «тормозит раскрытие»): раньше для каждой строки и каждого уровня заново шёл поиск родителя назад и проход по всем детям родителя (O(n²): 244 строки
        // раскрытой папки занимали на Galaxy A7 около 2 секунд в главном потоке). Теперь родитель уровня берётся из «последней строки уровня», а итог по детям родителя считается один раз.
        run {
            val maxLv = 64
            val lastAt = IntArray(maxLv) { -1 }
            val endOf = IntArray(rows.size) { -2 }          // конец вертикали детей родителя: -1 нет детей, -2 ещё не считали
            val openOf = BooleanArray(rows.size)            // у родителя есть раскрытый ребёнок
            fun endFor(pj: Int): Int {
                var e = endOf[pj]
                if (e != -2) return e
                val k = rows[pj].level + 1
                var j = pj + 1; var lastChild = -1; var openChild = -1
                while (j < rows.size && rows[j].header != 1 && rows[j].level >= k) {
                    if (rows[j].level == k) { lastChild = j; if (j + 1 < rows.size && rows[j + 1].header != 1 && rows[j + 1].level > k) openChild = j }
                    j++
                }
                e = if (lastChild < 0) -1 else if (openChild >= 0) openChild else lastChild
                endOf[pj] = e; openOf[pj] = openChild >= 0
                return e
            }
            val padPx = TreeDim.pad; val indPx = TreeDim.ind; val half = TreeDim.arrow(false) / 2
            for (i in rows.indices) {
                val r = rows[i]
                val lv = r.level
                if (lv > 0 && r.header != 1) {
                    val moreArr = BooleanArray(lv)
                    var tickOwn = true; var halfOwn = true
                    val lxArr = FloatArray(lv)
                    for (k in 1..lv) {
                        val pj = if (k - 1 < maxLv) lastAt[k - 1] else -1
                        val groupParent = pj >= 0 && rows[pj].expandKey != null
                        lxArr[k - 1] = (padPx + indPx * (k - 1) + (if (groupParent) 9 else if (tv) 15 else half)).toFloat()
                        if (pj < 0) continue
                        val end = endFor(pj)
                        if (end < 0) continue
                        moreArr[k - 1] = i < end
                        if (k == lv) { halfOwn = i == end; tickOwn = if (openOf[pj]) i == end else true }
                    }
                    r.more = moreArr; r.tick = tickOwn; r.half = halfOwn
                    r.lx = lxArr
                }
                if (r.header == 0 && lv in 0 until maxLv) lastAt[lv] = i
            }
        }
        run {
            // 22.11.2026: блок (светлый фон, контур, тень) только у «листовых» раскрытых папок (без раскрытых вложенных) вместе с их строками; раскрытые родители выше остаются обычными, как в X-plore
            val n = rows.size
            val isOpen = BooleanArray(n) { i -> val r = rows[i]; treeOn && r.header == 0 && r.level >= 1 && r.treeId != null && r.expandKey == null && r.treeId in openSetNow }
            for (r in rows) { r.grp = 0; r.gTop = 0; r.gBot = 0 }
            for (i in 0 until n) {
                if (!isOpen[i]) continue
                val lv = rows[i].level
                var e = i + 1; var leaf = true
                while (e < n && rows[e].header == 0 && rows[e].level > lv) { if (isOpen[e]) { leaf = false; break }; e++ }
                if (!leaf) continue
                for (j in i until e) rows[j].grp = 1
                rows[i].gTop = 2; rows[e - 1].gBot = 2
            }
        }
        PerfLog.mark("пост: more/lx ${(System.nanoTime() - perfP) / 1_000_000} мс (накоплено)")
        // 10.0 (fm1): видимые отмечаемые строки: элемент → его содержимое (строки уровнем ниже под раскрытой папкой) и верхний уровень (родитель без своей отметки)
        val visSel = FmRootCfg.VisSel {   // 22.11.2026: считается по требованию (отметка, «Все», «Инверсия»), а не при каждой перекомпоновке
            val byKey = LinkedHashMap<String, FmRootCfg.SelE>(); val kidsOf = HashMap<String, MutableList<String>>(); val top = ArrayList<String>()
            val stack = ArrayList<RR>()
            for (r in rows) {
                if (r.header == 1) { stack.clear(); continue }
                while (stack.isNotEmpty() && stack.last().level >= r.level) stack.removeAt(stack.lastIndex)
                val parent = stack.lastOrNull()
                stack.add(r)
                val e = r.selE ?: continue
                byKey[e.key] = e
                val pe = parent?.selE
                if (pe != null && pe.ti == null && pe.fi == null) kidsOf.getOrPut(pe.key) { ArrayList() }.add(e.key)
                else if (e.ti == null && e.fi == null) top.add(e.key)
            }
            Triple<Map<String, FmRootCfg.SelE>, Map<String, List<String>>, List<String>>(byKey, kidsOf, top)
        }
        PerfLog.mark("пост: visSel ${(System.nanoTime() - perfP) / 1_000_000} мс (накоплено)")
        return RootBuilt(rows, rows.map { it.key }, favs, openIds, activate, sumTodo, applyNow, visSel, wifiLoad, probeKey, probePcs, probeAnds, sortTickRead, trashVer)
    }

    // ------------------------------------------------------------------ применение результата (главный поток)
    private var lastLoadKey: String? = null; private var loadJob: Job? = null
    private var lastActKey: String? = null; private var actJob: Job? = null
    private var lastSumKey: String? = null; private var sumJob: Job? = null
    private var lastProbeKey: String? = null; private var probeJob: Job? = null
    private var wifiLoaded = false

    fun apply(b: RootBuilt, scope: CoroutineScope) {
        val t0 = System.nanoTime()
        built = b
        if (!FmRv.ON) rowsState = b.rows
        for ((tid, list) in b.applyNow) if (FmRootCfg.selAllPending.remove(tid)) list.forEach { FmRootCfg.sel[it.key] = it }
        val rows = b.rows
        FmRootCfg.setRowsLazy(p.id) { val vis = rows.filter { it.header == 0 }; vis.map { it.key } to vis.map { it.level } }; FmRootCfg.setVisSel(p.id, b.visSel)
        sink?.invoke(b)
        afterApply?.invoke()
        ver++
        // эффекты, которые раньше жили в перекомпоновке списка
        val favs = b.favs
        val virtIds = favs.filter { it.kind == "cloud" && CloudTrash.supported(CloudTrash.typeFor(ctx, it)) }.map { "N|" + it.kind + "|" + it.host + "|" + it.user + "|" }.toSet()
        val lk = b.openIds.joinToString(",") + "#" + CloudTrash.ver + "#" + FmTrash.ver + "#" + favs.hashCode()
        if (lk != lastLoadKey) {
            lastLoadKey = lk; loadJob?.cancel()
            loadJob = scope.launch {
                for (id in b.openIds) if ((id.startsWith("L|") || id == "K|" || id.startsWith("N|") || id.startsWith("A|") || id.startsWith("D|") || id.startsWith("T|") || id == "H|") && id !in virtIds) {
                    if (FmRootCfg.kids[id] == null && FmRootCfg.kidErr[id] == null) launch { FmRootCfg.loadKids(id, favs) }
                    else if (FmRootCfg.stale.remove(id)) launch { FmRootCfg.loadKids(id, favs) }
                }
            }
        }
        val ak = b.activate.joinToString(",")
        if (ak != lastActKey) {
            lastActKey = ak; actJob?.cancel()
            val activate = b.activate
            actJob = scope.launch {
                for (id in activate.filter { it.startsWith("D|") }) { p.rootPending.remove(id); if (id !in p.rootOpen) p.rootOpen.add(id) }
                // облака и сеть, бывшие раскрытыми: перечитываются по одному (MEGA не любит параллельные запросы) и не сразу после запуска
                for (id in activate.filter { !it.startsWith("D|") }) {
                    delay((FmRootCfg.t0 + 1500L - System.currentTimeMillis()).coerceAtLeast(300L))
                    p.rootPending.remove(id); if (id !in p.rootOpen) p.rootOpen.add(id)
                    kotlinx.coroutines.withTimeoutOrNull(20000L) { while (id !in virtIds && FmRootCfg.kids[id] == null && FmRootCfg.kidErr[id] == null) delay(250) }
                }
            }
        }
        val sk = b.sumTodo.joinToString(",")
        if (sk != lastSumKey) {
            lastSumKey = sk; sumJob?.cancel()
            val sumTodo = b.sumTodo
            sumJob = scope.launch { if (sumTodo.any { NetTrash.isRid(it) }) NetTrash.ensureAll(ctx); for (h in sumTodo) { CloudTrash.refreshSum(ctx, h); delay(800) } }
        }
        if (b.wifiLoad && !wifiLoaded) { wifiLoaded = true; scope.launch { WifiExplorer.load(ctx) } }
        val pk = b.probeKey
        if (pk != lastProbeKey) {
            lastProbeKey = pk; probeJob?.cancel()
            if (pk != null) { val pcs = b.probePcs; val ands = b.probeAnds; probeJob = scope.launch { pcs.forEach { h -> launch { PcStatus.probe(h.ip) } }; ands.forEach { d -> launch { AdbStatus.probe(d) } } } }
        }
        PerfLog.mark("применение: ${rows.size} строк, ${(System.nanoTime() - t0) / 1_000_000} мс")
    }

    /** Цикл: сборка в фоновом потоке по версии состояний, применение на главном. */
    suspend fun loop() {
        val scope = kotlinx.coroutines.CoroutineScope(currentCoroutineContext())
        androidx.compose.runtime.snapshotFlow { buildSafe() }.flowOn(Dispatchers.Default).conflate().collect { b ->
            if (b != null) apply(b, scope)
            else if (fails >= 2) { val b2 = runCatching { androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { build() } }.getOrNull(); if (b2 != null) apply(b2, scope) }
        }
    }

    companion object {
        private class Cached(val ctx: java.lang.ref.WeakReference<Context>, val model: RootModel)
        private val cache = java.util.concurrent.ConcurrentHashMap<Int, Cached>()
        /** Модель панели; последняя собранная версия переживает пересоздание списка (возврат на экран «Файлы»): строки видны сразу, без пустого кадра. */
        fun create(p: FmPane, ctx: Context, prim: Color, sig: String): RootModel {
            val old = cache[p.id]
            val m = RootModel(p, ctx, prim, sig)
            val ob = old?.takeIf { it.ctx.get() === ctx }?.model?.built
            if (ob != null) { m.built = ob; if (!FmRv.ON) m.rowsState = ob.rows }
            else m.built = androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { m.build() }   // первый раз (или после смены окна): один синхронный проход, как раньше
            cache[p.id] = Cached(java.lang.ref.WeakReference(ctx), m)
            return m
        }
    }
}

/** Корневой экран панели: строки-«папки» памятей, сети, облаков и прочих источников, как в X-plore. */
@Composable
fun FmRootList(p: FmPane, modifier: Modifier) {
    val ctx = LocalContext.current
    val tv = Marq.tv
    val prim = MaterialTheme.colorScheme.primary
    val conf = androidx.compose.ui.platform.LocalConfiguration.current
    val cfgSig = conf.locales.toLanguageTags() + "|" + conf.fontScale + "|" + conf.layoutDirection
    val model = remember { RootModel.create(p, ctx, prim, cfgSig) }
    androidx.compose.runtime.SideEffect { model.prim = prim; model.cfgSig = cfgSig }
    LaunchedEffect(model) { model.loop() }
    LaunchedEffect(Unit) { androidx.compose.runtime.snapshotFlow { p.rootOpen.toList() }.collect { FmRootCfg.saveOpen(ctx, p) } }
    // 19.11.2026 (владелица): вышла из «Источников» (другая вкладка, папка) и вернулась: раскрытые диски, устройства и папки свёрнуты, остаются только верхние группы;
    // содержимое облака можно держать в кэше, но показывается свёрнутым. Поворот экрана (пересоздание окна) состояние не сбрасывает.
    androidx.compose.runtime.DisposableEffect(p) {
        onDispose {
            var cw: android.content.Context = ctx; while (cw is android.content.ContextWrapper && cw !is android.app.Activity) cw = cw.baseContext
            val chg = (cw as? android.app.Activity)?.isChangingConfigurations == true
            if (!chg) {
                // T3 (слова владелицы: «в каждом окне запоминалось ... развёрнуто в обоих окнах то, что разворачивал»): раскрытое, ожидающие узлы и выбранная строка при выходе с экрана НЕ сбрасываются
                FmRootCfg.sel.clear()
                FmRootCfg.purgeClosedCloud()
            }
        }
    }
    // число папок и файлов в корнях томов: в фоне
    val shown = FmRootCfg.shown
    LaunchedEffect(Fm.vols.size, p.root) {
        val paths = listOf("/sdcard") + Fm.vols.filter { it.ok }.map { it.path } + (if ("root" in shown) listOf("/") else emptyList())
        for (pa in paths) {
            if (FmRootCfg.counts.containsKey(pa)) continue
            val c = withContext(Dispatchers.IO) { runCatching { File(pa).listFiles()?.let { l -> l.count { it.isDirectory && it.name != FmTrash.DIR } to l.count { it.isFile } } }.getOrNull() }
            if (c != null) FmRootCfg.counts[pa] = c
        }
    }
    LaunchedEffect(FmTrash.ver, FmDel.trashOn, Fm.vols.size) { FmTrash.refreshSum() }
    LaunchedEffect(Unit) { androidx.compose.runtime.snapshotFlow { p.rootSel }.collect { s -> if (s != null) ctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE).edit().putString("root_sel_p${p.id}", s).apply() } }
    val rootCtx = LocalContext.current
    // 14.0: один раз на версию программа просит систему скомпилировать свой код (если уже есть root или подключённый ADB), см. SelfCompile
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(40_000); runCatching { SelfCompile.maybe(rootCtx) } }
    // 14.0: содержимое тома телефона читается заранее, пока список стоит свёрнутым: первое раскрытие после запуска показывает строки из памяти сразу (листинг на FUSE приставки занимает 150–600 мс), а свежий список приходит следом (refresh при раскрытии)
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1200)
        val vid = model.built?.rows?.firstOrNull { it.drive && it.treeId?.startsWith("L|") == true }?.treeId
        if (vid != null && FmRootCfg.kids[vid] == null && FmRootCfg.kidErr[vid] == null) runCatching { FmRootCfg.loadKids(vid, NetStore.list(rootCtx)) }
    }
    Column(modifier.then(Modifier.fillMaxWidth()).onPreviewKeyEvent { e ->
        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && FocusRestore.isDpad(e.nativeKeyEvent.keyCode)) FocusRestore.markKeys(rootCtx)
        // пульт: «Меню» или «Info» при отмеченном переводит фокус на нижнюю панель значков
        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && FmRootCfg.sel.isNotEmpty() && (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU || e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_INFO)) { runCatching { FmRootCfg.barFocus.requestFocus() }.isSuccess } else false
    }) {
        if (FmRv.ON) {
            // 22.11.2026: список на RecyclerView (FmRootRv.kt); прежний LazyColumn ниже оставлен как запасной (FmRv.ON = false)
            val st = remember { RvState() }
            rememberSavedTreeListRv("files.root." + p.id, { model.built?.keys ?: emptyList() }, { FmRootCfg.focusKey[p.id] ?: p.rootSel }, st)
            // 12.0 (владелица: «раскрытая папка должна подняться наверх»): сразу после раскрытия узла строка самой папки едет к верху списка (если она не рядом с верхом); метку ставит rootTreeToggle
            androidx.compose.runtime.DisposableEffect(model, st) {
                model.afterApply = {
                    val target = FmRootCfg.scrollTarget[p.id]
                    val rows = model.built?.rows
                    if (target != null && rows != null) {
                        val idx = rows.indexOfFirst { it.treeId == target }
                        if (idx >= 0 && (st.rv?.adapter?.itemCount ?: 0) > idx) {
                            val v = st.viewAt(idx)
                            val nearTop = v != null && v.top <= 40f * rootCtx.resources.displayMetrics.density
                            if (!nearTop) st.scrollToItem(idx, 0)
                            FmRootCfg.scrollTarget.remove(p.id)
                        }
                    }
                }
                onDispose { model.afterApply = null }
            }
            LaunchedEffect(st) {
                androidx.compose.runtime.snapshotFlow { FmRootCfg.scrollTarget[p.id] to model.ver }.collectLatest { (target, _) ->
                    target ?: return@collectLatest
                    val rows = model.built?.rows ?: return@collectLatest
                    val idx = rows.indexOfFirst { it.treeId == target }
                    if (idx < 0) return@collectLatest
                    var tries = 0
                    while (tries < 6 && (st.rv?.adapter?.itemCount ?: 0) <= idx) { androidx.compose.runtime.withFrameNanos { }; tries++ }
                    androidx.compose.runtime.withFrameNanos { }
                    val v = st.viewAt(idx)
                    val tolerancePx = 40f * rootCtx.resources.displayMetrics.density
                    val nearTop = v != null && v.top <= tolerancePx
                    if (!nearTop) st.scrollToItem(idx, 0)
                    FmRootCfg.scrollTarget.remove(p.id)
                }
            }
            val homeAtStartRv = remember { p.homeTick }
            LaunchedEffect(p.homeTick) {
                if (p.homeTick != homeAtStartRv) {
                    kotlinx.coroutines.delay(150)
                    st.scrollToItem(0)
                    val firstKeyRv = model.built?.rows?.firstOrNull { it.header == 0 }?.key
                    if (firstKeyRv != null && FocusRestore.keyMode(Fm.sctx)) { FocusRestore.key = null; kotlinx.coroutines.delay(60); FocusRestore.key = firstKeyRv }
                }
            }
            FmRootRvList(p, model, tv, st, Modifier.weight(1f).fillMaxWidth())
        } else {
        val rows = model.rowsState
        val perfT0 = System.nanoTime()
        val born = remember { booleanArrayOf(false) }
        val rootState = rememberSavedTreeList("files.root." + p.id, rows.map { it.key }, FmRootCfg.focusKey[p.id] ?: p.rootSel)   // T3: прокрутка и фокус возвращаются на строку, где был фокус
        // 12.0 (владелица: «раскрытая папка должна подняться наверх, а не прятаться внизу экрана, где анимацию не видно»):
        // сразу после раскрытия узла (treeToggle в FmRootRowView ставит метку) строка самой папки плавно едет к верху списка,
        // если она и так не рядом с верхом — иначе новые дочерние строки уезжают за экран и кажется, что ничего не произошло
        val scrollTargetId = FmRootCfg.scrollTarget[p.id]
        LaunchedEffect(scrollTargetId, rows) {
            val target = scrollTargetId ?: return@LaunchedEffect
            val idx = rows.indexOfFirst { it.treeId == target }
            if (idx < 0) return@LaunchedEffect
            val vis = rootState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx }
            // уже у верха (в пределах ~40dp) — лишний скачок не нужен; иначе едем так, чтобы строка легла к верхнему краю
            val tolerancePx = 40f * rootCtx.resources.displayMetrics.density
            val nearTop = vis != null && vis.offset <= tolerancePx
            if (!nearTop) rootState.scrollToItem(idx, 0)   // 14.0: без плавной прокрутки (каждый кадр прокрутки заново раскладывал строки)
            FmRootCfg.scrollTarget.remove(p.id)
        }
        // «Дом»: список наверх и фокус первой строке (если пульт или мышь/клавиатура уже использовались)
        PerfLog.mark("FmRootList: строк ${rows.size}, сборка строк ${(System.nanoTime() - perfT0) / 1_000_000} мс")
        val firstKey = rows.firstOrNull { it.header == 0 }?.key
        // T3 (19.11.2026): «Дом» срабатывает только на нажатие, а не при каждом новом создании списка: раньше после первого нажатия «Дом» список при каждом возврате на экран
        // прокручивался наверх (и забывал положение), а на пульте фокус уходил на первую строку
        val homeAtStart = remember { p.homeTick }
        LaunchedEffect(p.homeTick) {
            if (p.homeTick != homeAtStart) {
                kotlinx.coroutines.delay(150)
                rootState.scrollToItem(0)
                if (firstKey != null && FocusRestore.keyMode(Fm.sctx)) { FocusRestore.key = null; kotlinx.coroutines.delay(60); FocusRestore.key = firstKey }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight().verticalScrollbar(rootState)
            // пульт: «вниз» с последней строки списка уходит на нижнюю панель значков (если есть отмеченное), а не на нижнюю навигацию
            .focusProperties { exit = { d -> if (d == androidx.compose.ui.focus.FocusDirection.Down && FmRootCfg.sel.isNotEmpty()) FmRootCfg.barFocus else androidx.compose.ui.focus.FocusRequester.Default } }.focusGroup(), state = rootState, contentPadding = sbPadding(), verticalArrangement = Arrangement.spacedBy(if (tv) 2.dp else 0.dp)) {
            // 12.0 (владелица: «резко, надо как в X-plore — быстро но плавнее»): появление/исчезновение и сдвиг строк
            // при раскрытии папки в дереве анимируются (коротко, с плавной кривой), а не щёлкают мгновенной подменой списка
            items(rows, key = { it.key }, contentType = { it.header }) { r ->
                // 12.0 (владелица: «слипшийся текст при первом раскрытии»): временные строки-заглушки («Загрузка…», ошибка,
                // «нужен модуль» — ключ "t:...") на первом раскрытии появляются и тут же сменяются настоящим содержимым;
                // если заглушка ТОЖЕ анимированно входит и выходит, обе волны (её исчезновение + появление реальных строк)
                // накладываются и дают нахлёст текста. Заглушки показываются и убираются без анимации, растут только настоящие строки.
                // 18.11.2026 (владелица): выключатель «Анимация переходов» — выключен, весь список раскрывается/сворачивается мгновенно.
                // 14.0: строки списка «Файлов» без анимации появления и сдвига (animateItem и AnimatedVisibility стоили сотни мс на слабой приставке), независимо от выключателя
                FmRootRowView(p, r, tv, Modifier, false, born[0])
            }
        }
        ScrollbarHandle(rootState, Modifier.align(if (scrollbarLeft()) Alignment.CenterStart else Alignment.CenterEnd))
        }
        androidx.compose.runtime.SideEffect { FmRootCfg.lastVisKey = rootState.layoutInfo.visibleItemsInfo.lastOrNull()?.key as? String }
        }
        RootSelBarHost(p)
    }
}

/** 14.0: панель отмеченного показывается, пока есть отмеченное. Набор читается здесь, а не в самом списке «Источников»: список не перерисовывается целиком при каждой галочке. */
@Composable
private fun RootSelBarHost(p: FmPane) { if (FmRootCfg.sel.isNotEmpty()) RootSelBar(p) }

/** Групповые действия над отмеченным в дереве «домика»: общие для панели выбора и кнопок центральной колонки. */
internal object RootSelOps {
    private val files get() = FmRootCfg.sel.values.filter { it.ti == null && it.fi == null }
    fun pseudo(e: FmRootCfg.SelE, path: String): FmPane = FmPane(9, path).also { ps ->
        if (e.net != null) { ps.src = "smb"; ps.smb = FmRootCfg.fsFor(e.net); ps.net = e.net }
    }
    /** После заданий дерево перечитывается: подождать начала и конца задания и сбросить родительские узлы. */
    fun refreshLater(parents: List<String>, hosts: List<String>, focusKey: String? = null, isDelete: Boolean = false) {
        Fm.scope.launch {
            var started = false; var n = 0; var sawDlg = false
            while (n < 800) {
                kotlinx.coroutines.delay(150); n++
                val asking = Fm.dlg is FmDlg.Delete
                if (asking) sawDlg = true
                if (Fm.jobs.isNotEmpty()) started = true
                else if (started) break
                // 9.3: маленькое удаление заканчивается быстрее опроса (раньше ждали до двух минут): окно подтверждения закрылось и задания нет, значит всё сделано
                else if (isDelete && sawDlg && !asking) { kotlinx.coroutines.delay(500); break }
                else if (isDelete && !sawDlg && n >= 10) break
            }
            kotlinx.coroutines.delay(800)
            // 9.3: список перечитывается на месте (без «Загрузки…»): положение прокрутки не прыгает, а фокус отдаётся соседней строке
            parents.distinct().forEach { FmRootCfg.kidErr.remove(it); FmRootCfg.stale.add(it) }
            hosts.distinct().forEach { CloudTrash.invalidate(it) }
            CloudTrash.ver++
            FmRootCfg.focusRowLater(focusKey)
        }
    }
    /** После копирования или переноса: дождаться конца заданий и перечитать на месте источники и приёмник. */
    suspend fun afterJobs(parents: List<String>, seq0: Int = Fm.jobSeq) {
        kotlinx.coroutines.delay(300)
        // 10.0: перед копированием в сеть идёт сканирование приёмника (бывает долгим): сначала ждём появления задания (до ~60 с; очень быстрое задание
        // видно по счётчику jobSeq), потом его конца
        var n = 0; var started = false
        while (n < 4000) {
            kotlinx.coroutines.delay(300); n++
            if (Fm.jobs.isNotEmpty() || Fm.jobSeq != seq0) started = true
            if (Fm.jobs.isNotEmpty()) continue
            if (Fm.dlg is FmDlg.Conflict) continue
            if (started) break
            if (n >= 200) break
        }
        kotlinx.coroutines.delay(500)
        parents.distinct().forEach { FmRootCfg.kidErr.remove(it); FmRootCfg.stale.add(it) }
        CloudTrash.ver++
    }
    /** 20.11.2026: подпись источника для окна подтверждения: первая папка (и сколько ещё). */
    private fun groupsFrom(groups: Collection<List<FmRootCfg.SelE>>): String {
        val g0 = groups.firstOrNull()?.firstOrNull() ?: return ""
        val l = Fm.pathLabel(pseudo(g0, g0.parentPath), if (g0.net != null) "smb" else "local", g0.parentPath)
        return if (groups.size > 1) l + " (+" + (groups.size - 1) + ")" else l
    }
    fun transfer(p: FmPane, move0: Boolean) {
        val o = Fm.panes[1 - p.id]
        val fl = files
        val groups = fl.groupBy { (it.net?.let { n -> n.kind + "|" + n.host + "|" + n.user } ?: "L") + "#" + it.parentPath }
        val par = fl.map { it.parentId }
        // 9.3: папка-приёмник: последняя раскрытая папка другой панели (дерево) или её текущая папка (список); 10.0: папки нет, окно выбора папки
        val dst: FmRootCfg.Dst? = if (o.root) (FmRootCfg.destFor(o) ?: run {
            Fm.dlg = FmDlg.PickTarget(move0, groups.values.map { g -> pseudo(g[0], g[0].parentPath) to g.map { it.name } }) { afterJobs(par) }
            FmRootCfg.sel.clear(); return
        }) else null
        val toLabel = dst?.label() ?: Fm.pathLabel(o, o.src, o.path)
        Fm.askTransfer(move0, groupsFrom(groups.values), toLabel, fl.map { it.name }, 0L, dst?.pane() ?: o, null) { move, pol ->
            dst?.let { Fm.say(Fm.str(if (move) R.string.pk_moving_to else R.string.pk_copying_to, it.label())) }
            val seq0 = Fm.jobSeq
            Fm.scope.launch {
                for ((_, g) in groups) { Fm.copyMove(move, pseudo(g[0], g[0].parentPath), dst?.pane() ?: o, g.map { it.name }, policy = pol); kotlinx.coroutines.delay(700); while (Fm.dlg is FmDlg.Conflict) kotlinx.coroutines.delay(300) }
                afterJobs(par + listOfNotNull(dst?.node), seq0)
            }
            FmRootCfg.sel.clear()
        }
    }
    /** Копирование и перенос из обычного списка панели [p] (не дерево): в раскрытую папку дерева другой панели или в её текущую папку. */
    fun fromList(p: FmPane, move: Boolean) = toOther(p, Fm.targets(p), move)
    /** 10.0 (fm1): копирование и перенос [names] панели [p] в ТЕКУЩУЮ папку другой панели (как в опубликованной версии); другая панель на «Источниках»: её раскрытая папка, иначе окно выбора папки. */
    fun toOther(p: FmPane, names: List<String>, move: Boolean) {
        val o = Fm.panes[1 - p.id]
        if (names.isEmpty()) return
        // 20.11.2026: перед копированием и переносом окно подтверждения (настройка «Подтверждать копирование и перенос», по умолчанию включена)
        val bytes = names.sumOf { n -> p.items.firstOrNull { it.name == n }?.takeIf { !it.isDir }?.size ?: 0L }
        val from = Fm.pathLabel(p, p.src, p.path)
        if (!o.root) { Fm.askTransfer(move, from, Fm.pathLabel(o, o.src, o.path), names, bytes, o, p) { mv, pol -> Fm.copyMove(mv, p, o, names, policy = pol) }; return }
        val dst = FmRootCfg.destFor(o) ?: run { Fm.dlg = FmDlg.PickTarget(move, listOf(p to names)); return }
        Fm.askTransfer(move, from, dst.label(), names, bytes, dst.pane(), p) { mv, pol ->
            Fm.say(Fm.str(if (mv) R.string.pk_moving_to else R.string.pk_copying_to, dst.label()))
            val seq0 = Fm.jobSeq
            Fm.copyMove(mv, p, dst.pane(), names, policy = pol)
            Fm.scope.launch { afterJobs(listOf(dst.node), seq0) }
        }
    }
    fun deleteFiles() {
        val srcs = files.map { it.net?.let { n -> n.kind + "|" + n.host + "|" + n.user } ?: "L" }.distinct()
        if (srcs.size != 1) { Fm.say(Fm.str(R.string.sel_one_source)); return }
        val first = files[0]
        // одна «тень» панели на корне источника, имена от корня: так удаляется отмеченное из разных папок разом
        val par = files.map { it.parentId }
        val nb = FmRootCfg.neighborOf(files.map { "t:" + it.key }.toSet())
        Fm.askDelete(pseudo(first, "/"), files.map { it.path.trimStart('/') })
        FmRootCfg.sel.clear(); refreshLater(par, emptyList(), nb, true)
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
    fun transfer(move: Boolean) = RootSelOps.transfer(p, move)
    fun deleteFiles() = RootSelOps.deleteFiles()
    fun refreshLater(parents: List<String>, hosts: List<String>, focusKey: String? = null, isDelete: Boolean = false) = RootSelOps.refreshLater(parents, hosts, focusKey, isDelete)
    fun doForever() {
        val list = trash.toList(); val hosts = list.mapNotNull { it.net?.let { n -> NetTrash.rid(n) } }
        FmRootCfg.sel.clear()
        Fm.scope.launch {
            // 10.11.2026 (владелица: «в облачной корзине не могу удалить выбранные», «добавь полоску процесса»): удаление идёт заданием с полоской хода, потом список перечитывается и показывается итог
            var ok = 0; var firstErr: String? = null; var si = 0
            // файлы родной корзины Google Диска стираются пакетами по 25 за один запуск движка (по одному было около 5 с на файл), остальное по одному
            val batchable = list.filter { e -> e.fi == null && e.ti != null && !e.ti.isDir && e.net != null && CloudTrash.mode(CloudTrash.typeOfRid(ctx, NetTrash.rid(e.net))) == CloudTrash.NATIVE }
            val steps = ArrayList<Triple<String, Int, suspend () -> String?>>()
            batchable.groupBy { NetTrash.rid(it.net!!) }.forEach { (rid, es) ->
                es.chunked(25).forEach { ch -> steps += Triple(ch.first().name + " (" + ch.size + ")", ch.size, { CloudTrash.purgeNativeBatch(ctx, rid, ch.map { it.ti!! }) }) }
            }
            list.filter { it !in batchable }.forEach { e ->
                steps += Triple(e.name, 1, { if (e.fi != null) FmTrash.purge(e.fi) else e.net?.let { n -> CloudTrash.purgeAny(ctx, NetTrash.rid(n), e.ti!!) } })
            }
            // в окне хода: «Осталось: X из N» (N: все выбранные элементы), раньше висело «Подсчёт…» (общий объём не задавался)
            Fm.stepJob(Fm.str(R.string.sel_forever_title), steps.map { it.first }, list.size) { j, _ ->
                val st = steps[si++]
                val err = st.third()
                if (err == null) ok += st.second else if (firstErr == null) firstErr = err
                repeat(st.second) { j.item() }
                null
            }
            // проверка: пакет мог «пройти» и ничего не удалить; корзина перечитывается, оставшееся удаляется по одному
            if (batchable.isNotEmpty()) {
                val redo = ArrayList<FmRootCfg.SelE>()
                for ((rid, es) in batchable.groupBy { NetTrash.rid(it.net!!) }) {
                    val left = runCatching { CloudTrash.list(ctx, rid) }.getOrNull() ?: continue
                    redo += es.filter { e -> left.any { it.origPath == e.ti!!.origPath } }
                }
                if (redo.isNotEmpty()) {
                    ok = (ok - redo.size).coerceAtLeast(0)
                    Fm.say(Fm.str(R.string.sel_forever_redo, redo.size))
                    var ri = 0
                    Fm.stepJob(Fm.str(R.string.sel_forever_title), redo.map { it.name }, redo.size) { j, _ ->
                        val e = redo[ri++]
                        val err = e.net?.let { n -> CloudTrash.purgeAny(ctx, NetTrash.rid(n), e.ti!!) }
                        if (err == null) ok++ else if (firstErr == null) firstErr = err
                        j.item()
                        null
                    }
                }
            }
            hosts.distinct().forEach { CloudTrash.invalidate(it); FmRootCfg.kids.remove("T|$it"); FmRootCfg.kidErr.remove("T|$it") }
            CloudTrash.ver++
            if (list.any { it.fi != null }) { FmTrash.bump(); FmTrash.refreshSum() }
            Fm.say(if (firstErr == null) Fm.str(R.string.sel_forever_done, ok) else Fm.str(R.string.sel_forever_part, ok, list.size, firstErr.take(120)))
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
            // отмечены и файлы, и элементы корзины: действия не показываются, объясняем почему (10.11.2026)
            if (trash.isNotEmpty() && files.isNotEmpty()) Text(stringResource(R.string.sel_mixed), Modifier.padding(horizontal = 6.dp), fontSize = 11.sp, lineHeight = 13.sp, maxLines = 3, color = MaterialTheme.colorScheme.error)
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
    if (confirmForever) ConfirmDeleteAlert(
        what = stringResource(R.string.dd_t_perm, if (trash.size == 1) trash[0].name else stringResource(R.string.dd_items, trash.size)),
        onYes = { confirmForever = false; doForever() }, onNo = { confirmForever = false },
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
            LangScopePublic {
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.inverseSurface).padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(name, fontSize = 13.sp, color = MaterialTheme.colorScheme.inverseOnSurface, maxLines = 1)
            }
            }
        }
    }
}

/** Перетаскивание из дерева корневого экрана: отмеченные локальные строки (или только эта) берутся от общей папки, имена относительно неё. */
/** Раскрытой остаётся только цепочка к последней открытой папке; открыл другой источник (память, карта, сеть, облако и др.): прежний сворачивается. */
/** 9.4: свернули устройство, диск или папку: раскрытое внутри (все уровни) тоже сворачивается. */
private fun collapseInside(p: FmPane, id: String) {
    val own = listOf("/sdcard") + Fm.vols.filter { it.ok }.map { it.path.trimEnd('/') }
    val inside = TreeOpen.inside(p.rootOpen.toList(), id, own) { acc ->
        val a = acc.split('|', limit = 5)
        NetStore.list(Fm.sctx).firstOrNull { it.kind == a.getOrNull(1) && it.host == a.getOrNull(2) && it.user == a.getOrNull(3) }?.let { NetTrash.rid(it) }
    }
    if (inside.isNotEmpty()) { p.rootOpen.removeAll(inside.toSet()); p.rootPending.removeAll(inside.toSet()) }
}

/**
 * 20.11.2026 (слова владелицы: «свернул источник, нажал на него: раскрывается источник, а не устройства в нём, как в LAN»): при сворачивании группы или узла
 * всё раскрытое внутри забывается (в том числе запомненные «ожидающие» узлы); фокус и выбор, оставшиеся на скрытой строке, переходят на сам свёрнутый узел.
 */
internal fun collapseGroup(p: FmPane, key: String) {
    p.rootOpen.remove(key)
    p.rootPending.remove(key)
    val inside = (p.rootOpen + p.rootPending).filter { id ->
        when (key) {
            "lan", "cloud", "wifi", "apps" -> (id.startsWith("N|") || id.startsWith("T|") || id.startsWith("A|")) && netGroupOf(id) == key
            "pc" -> id.startsWith("dev:")
            else -> false
        }
    }
    p.rootOpen.removeAll(inside.toSet()); p.rootPending.removeAll(inside.toSet())
    refocusCollapsed(p, key, key)
}

/** После сворачивания узла [rowKey] (ключ строки) фокус и выбор, оставшиеся на скрытой строке внутри него ([hidden]: узнаётся по ключу), переходят на сам узел. */
internal fun refocusCollapsed(p: FmPane, rowKey: String, hiddenOwner: String) {
    val inside = { k: String? -> k != null && k != rowKey && FmRootCfg.rowInside(p.id, k, rowKey) }
    if (inside(p.rootSel)) p.rootSel = rowKey
    if (inside(FmRootCfg.focusKey[p.id])) FmRootCfg.focusKey[p.id] = rowKey
    FocusRestore.startKey = null
    FmRootCfg.focusRowLater(rowKey)
}

/** 9.4: верхние группы корня (LAN, Облака, Wi-Fi проводник, Устройства, Приложения): раскрыта только одна, остальные сворачиваются. */
private val ROOT_GROUPS = setOf("lan", "cloud", "wifi", "pc", "apps")
private fun openGroup(p: FmPane, key: String) {
    if (key in ROOT_GROUPS) p.rootOpen.removeAll { it in ROOT_GROUPS && it != key }
    if (key !in p.rootOpen) p.rootOpen.add(key)
}

/** 20.11.2026: группа корня (lan, cloud, wifi, apps), в которой лежит узел [id]; null: узел вне групп (память, том, ADB). */
private fun netGroupOf(id: String): String? = when {
        id.startsWith("A|") -> "apps"
        id.startsWith("T|") -> "cloud"
        id.startsWith("N|") -> {
            val a = id.split('|', limit = 5)
            when (a.getOrNull(1)) {
                "cloud" -> "cloud"
                "dav" -> {
                    val c = (NetStore.list(Fm.sctx) + WifiExplorer.found.toList()).firstOrNull { it.kind == "dav" && it.host == a.getOrNull(2) && it.user == a.getOrNull(3) }
                    // найденное по LAN устройство с WebDAV лежит в группе «LAN», а не «Облачные диски» (10.11.2026: из-за этого нажатие сворачивало всю группу LAN)
                    val inLan = c == null && FmRootCfg.lanFound.any { h -> FmRootCfg.lanConn(h).let { lc -> lc.kind == "dav" && lc.host == a.getOrNull(2) && lc.user == a.getOrNull(3) } }
                    if (inLan) "lan" else if (c != null && WifiExplorer.isWifi(c)) "wifi" else "cloud"
                }
                else -> "lan"
            }
        }
        else -> null
}

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
    // 10.11.2026 (владелица: на S9 при раскрытии диска всё дерево «Облачные диски» схлопывалось): группа, в которой лежит открываемый узел («lan», «cloud», «wifi», «apps»), остаётся раскрытой
    val keepGroup: String? = netGroupOf(id)
    // 10.11.2026 (владелица: «нажимаю на корзину, и диск сворачивается»; затем «везде одинаково: раскрыл одно, другое свернулось»): «Корзина» и «Облачный диск» — соседи и сворачивают друг друга, как всё остальное;
    // аккаунт облака (их родитель) при раскрытии корзины остаётся раскрытым
    fun acct(o: String): String? = when {
        o.startsWith("N|") -> o.split('|').take(4).joinToString("|")
        o.startsWith("T|") -> { val r = o.removePrefix("T|"); (NetStore.list(Fm.sctx) + WifiExplorer.found.toList() + FmRootCfg.lanFound.map { FmRootCfg.lanConn(it) }).firstOrNull { NetTrash.rid(it) == r }?.let { "N|" + it.kind + "|" + it.host + "|" + it.user } }
        else -> null
    }
    val myAcct = acct(id)
    fun sameAcctTrashPair(o: String): Boolean = id.startsWith("T|") && myAcct != null && o.startsWith("N|") && acct(o) == myAcct && o.split('|', limit = 5).getOrNull(4).isNullOrEmpty()
    val drop = p.rootOpen.filter { o -> o != id && !isAncestor(o) && o != keepGroup && !sameAcctTrashPair(o) }
    p.rootOpen.removeAll(drop.toSet())
    // 19.11.2026 (владелица: «развёрнутый диск облака не сворачивается, если разворачиваю другое»): запомненные «ожидающие» узлы (rootPending) раскрываются
    // заново, как только их строка видна (activate), поэтому свёрнутый диск тут же раскрывался снова. Теперь вместе со свёрнутыми узлами убираются и ожидающие.
    p.rootPending.removeAll { o -> o != id && !isAncestor(o) && o != keepGroup && !sameAcctTrashPair(o) }
}

/** Сдвиг одного уровня дерева (dp): вертикальные линии ветвей стоят максимально близко (было 22). Общая для дерева «домика» и дерева папок в панели. */
const val TREE_IND = 11

// 10.0 (fm1): перетаскивание строк дерева убрано (владелица: мешало прокрутке); квадратик отметки SmallCheck/SelBox/SelZone в FmSelUi.kt

/** 12.0: строка дерева растёт/гаснет при появлении ([animated]=true) или появляется сразу без анимации (временные заглушки «Загрузка…»/ошибка/«нужен модуль» — ключ "t:..."). */
@Composable
private fun FmRowEnter(animated: Boolean, visibleState: androidx.compose.animation.core.MutableTransitionState<Boolean>, modifier: Modifier, content: @Composable () -> Unit) {
    // 18.11.2026 (владелица): общий выключатель «Анимация переходов» — выключен, раскрытие папки мгновенное, без анимации.
    if (animated && UiAnim.on) {
        androidx.compose.animation.AnimatedVisibility(
            visibleState = visibleState,
            modifier = modifier,
            enter = androidx.compose.animation.fadeIn(tween(150)) + androidx.compose.animation.expandVertically(tween(180, easing = FastOutSlowInEasing)),
            exit = androidx.compose.animation.fadeOut(tween(120)) + androidx.compose.animation.shrinkVertically(tween(150, easing = FastOutSlowInEasing)),
        ) { content() }
    } else {
        androidx.compose.foundation.layout.Box(modifier, content = { content() })
    }
}

@Composable
private fun FmRootRowView(p: FmPane, r: RR, tv: Boolean, modifier: Modifier = Modifier, animated: Boolean = true, startShown: Boolean = false) {
    val prim = MaterialTheme.colorScheme.primary
    if (r.header == 1) {
        Text(r.title, modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 1.dp), fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold, color = prim)
        return
    }
    val ctx = LocalContext.current
    val src = remember { MutableInteractionSource() }
    // 9.3: строка, на которой фокус или которую нажали, запоминается как запасная папка-приёмник (файл: его родитель)
    val nodeForTarget = rootRowNode(r)
    // 14.0: фокус, наведение и нажатие строки собираются ОДНИМ сборщиком (раньше три collectIs*AsState = три корутины на каждую строку)
    val ibitsSt = rowInteractionBits(src) { FmRootCfg.noteRow(p.id, r.key, nodeForTarget, true) }
    val focused = (ibitsSt.value and 1) != 0
    val focusedNow = androidx.compose.runtime.rememberUpdatedState(focused)
    androidx.compose.runtime.DisposableEffect(r.key) { onDispose { if (focusedNow.value) FmRootCfg.focusedRowGone(p.id, r.key) } }
    val rowFr = remember { androidx.compose.ui.focus.FocusRequester() }
    // 14.0: эффект запускается только у той строки, которой возвращают фокус (раньше у каждой строки)
    if (FocusRestore.key == r.key) androidx.compose.runtime.LaunchedEffect(FocusRestore.key) { if (FocusRestore.key == r.key) { var tries = 0; while (FocusRestore.key == r.key && tries < 40) { kotlinx.coroutines.delay(250); runCatching { rowFr.requestFocus() }.onFailure { android.util.Log.i("FocusRestore", "request failed: " + it) }; tries++; if ((ibitsSt.value and 1) != 0) { android.util.Log.i("FocusRestore", "focused after " + tries); break } }; FocusRestore.key = null } }
    // T3: фокус после запуска получает строка активной панели, где он был; запрос снимается, если владелица уже нажала кнопку пульта (фокус не «перескакивает»)
    if (FocusRestore.startKey == r.key) androidx.compose.runtime.LaunchedEffect(FocusRestore.startKey) {   // 14.0: только у строки, которой возвращают фокус
        if (FocusRestore.startKey == r.key && FocusRestore.startPane == p.id) {
            var tries = 0; val m = FocusRestore.startMark
            while (FocusRestore.startKey == r.key && tries < 40 && m == FocusRestore.keys) { kotlinx.coroutines.delay(250); runCatching { rowFr.requestFocus() }; tries++; if ((ibitsSt.value and 1) != 0) break }
            android.util.Log.i("FocusRestore", "T3: запрос фокуса: панель " + p.id + " строка " + r.key + " попыток " + tries + " фокус " + ((ibitsSt.value and 1) != 0) + " нажатий пульта " + (FocusRestore.keys - m))
            if (FocusRestore.startKey == r.key) FocusRestore.startKey = null
        }
    }
    var menu by remember { mutableStateOf(false) }
    var iconPick by remember { mutableStateOf(false) }
    // 10.0 (fm1): отметка строки (папка = всё содержимое, вложенные строки отмечены через неё); галочка с крупной зоной нажатия справа
    val chkFr = remember { androidx.compose.ui.focus.FocusRequester() }
    val e0 = r.selE
    // 14.0: состояние галочки через derivedStateOf по индексу отметки: строка перерисовывается, только когда СВОЯ галочка (или «частично») поменялась. Раньше отметка читалась в композиции
    // прямо из набора, и любая галочка пересоздавала весь список «Источников» и все видимые строки, а на каждую строку просматривался весь набор отмеченного
    val chkBitsSt = remember(e0?.key, r.selAll) {
        androidx.compose.runtime.derivedStateOf {
            val ix = FmRootCfg.selIdx.value
            if (e0 != null) { if (ix.direct(e0.key)) 1 else 0 }   // 26.11.2026 (слово владелицы): значок «частично» (минус) убран, как в X-plore
            else if (r.selAll.isNotEmpty()) { 0 }   // как в X-plore: у устройства галка не горит, отмечено содержимое
            else 0
        }
    }
    val chkBits = chkBitsSt.value
    val chkOn = (chkBits and 1) != 0
    val chkPart = (chkBits and 2) != 0
    val chkClick: (() -> Unit)? =
        if (e0 != null) { { Fm.active = p.id; FmRootCfg.toggleTree(p.id, e0) } }
        // раскрытое устройство, том или «диск» без своей отметки: галочка отмечает или снимает всё прямо внутри
        else if (r.selAll.isNotEmpty()) { { Fm.active = p.id; FmRootCfg.setAll(p.id, r.selAll, !r.selAll.all { FmRootCfg.covered(it.key) }) } }
        else null
    val selected = p.rootSel == r.key
    val rowActive = selected || ibitsSt.value != 0
    val rowBgColor = if (selected) prim.copy(alpha = .22f) else if (chkOn && e0 != null) prim.copy(alpha = .12f) else if (r.level >= 1 && r.header == 0) Color(0xFF3F8FD2).copy(alpha = (0.08f + 0.05f * (r.level - 1)).coerceAtMost(0.28f)) else Color.Transparent
    val expanded = r.expandKey != null && r.expandKey in p.rootOpen
    val cnt = r.path?.let { FmRootCfg.counts[it] }
    // 10.2: «N папок, N файлов» у папки: дата сразу, цифры подгружаются фоном после показа списка (FolderCounts)
    val cKey = r.cntKey?.takeIf { FmSub.mode != FmSub.NONE }
    // 14.0: цифры папки через derivedStateOf (строка перерисовывается, только когда её цифры появились), а не чтением FolderCounts.ver каждой строкой без цифр
    val cntNow: Pair<Int, Int>? = if (cKey == null) null else remember(cKey) { androidx.compose.runtime.derivedStateOf { val t = FolderCounts.ver; if (t >= 0) FolderCounts.peek(cKey) else null } }.value
    var cVis by remember(cKey) { mutableStateOf(false) }
    val cView = androidx.compose.ui.platform.LocalView.current
    val cLoader = r.cntLoader
    if (cKey != null && cLoader != null && cntNow == null) LaunchedEffect(cKey, cVis) { if (!FolderCounts.has(cKey)) FolderCounts.load(ctx, cKey, r.cntCls ?: FolderCounts.NET, cVis, cLoader) }
    val bigFont = if (tv) 1.15f else 1f
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .22f)
    val treeToggle = { id: String -> rootTreeToggle(p, r.key, ctx, id) }
    // устройство LAN (найденное или сохранённое): раскрытие дерева с автоматическим раскрытием общего ресурса из подключения
    val rowToggle = { id: String -> rootRowToggle(p, r, ctx, id) }
    val rowClickR: () -> Unit = { rootRowClick(p, r, ctx) { menu = true } }
    val rowLongR: () -> Unit = { Fm.active = p.id; p.rootSel = r.key; menu = true }
    // 12.0 (владелица: «рывок, а надо вообще без рывка» — одного animateItem() на сдвиг соседних строк мало, новая строка выскакивала
    // сразу в полный рост, только прозрачность менялась): своя строка по-настоящему вырастает (fadeIn+expandVertically) при первом
    // появлении на экране — ключ строки меняется при каждом новом раскрытии/закрытии узла, поэтому анимация входа всегда свежая.
    // Временные строки-заглушки (animated=false, см. items() выше) растут без анимации — иначе их уход и приход настоящих строк
    // накладываются друг на друга и текст на миг «слипается».
    // 14.0: строки списка «Файлов» появляются мгновенно (раньше AnimatedVisibility на каждую строку: на слабой приставке раскладка и кадры анимации съедали сотни мс)
    androidx.compose.foundation.layout.Box(modifier) {
    Box2(
        Modifier.fillMaxWidth().onKeyEvent { e ->
            val id = r.treeId
            if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.DirectionDown && FmRootCfg.sel.isNotEmpty() && r.key == FmRootCfg.lastVisKey) { runCatching { FmRootCfg.barFocus.requestFocus() }.isSuccess }
            // 10.0 (fm1): пульт: «вправо» на строке с галочкой (файл или уже раскрытая папка) переводит фокус на галочку
            else if (chkClick != null && e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.DirectionRight && (id == null || !FmRootCfg.treeOn || id in p.rootOpen)) runCatching { chkFr.requestFocus() }.isSuccess
            else if (id == null || !FmRootCfg.treeOn || e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false
            else if (e.key == androidx.compose.ui.input.key.Key.DirectionRight && id !in p.rootOpen) { treeToggle(id); true }
            else if (e.key == androidx.compose.ui.input.key.Key.DirectionLeft && id in p.rootOpen) { treeToggle(id); true }
            else false
        }
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
                            if (r.more[k - 1]) drawLine(lineColor, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height + ext), w)
                            else if (r.half) drawLine(lineColor, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height / 2), w)
                            val hasChev = r.treeId != null && FmRootCfg.treeOn
                            if (r.tick) drawLine(lineColor, androidx.compose.ui.geometry.Offset(x, size.height / 2), androidx.compose.ui.geometry.Offset((TreeDim.pad + TreeDim.ind * lv + (if (hasChev) (if (tv) 4 else 1) else 0) - 1).dp.toPx(), size.height / 2), w)
                        }
                    }
                }
                // 14.0: плашка строки и полоска выбора рисуются здесь же (раньше отдельные модификаторы background и drawBehind)
                if (rowBgColor != Color.Transparent) drawRoundRect(rowBgColor, cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                if (selected) drawRect(prim, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height))
            }
            .then(if (focused) Modifier.border((if (tv) 3 else 2).dp, prim, RoundedCornerShape(8.dp)) else Modifier)
            .mouseSecondary { Fm.active = p.id; p.rootSel = r.key; menu = true }   // 10.0 (mouse): правая кнопка = меню строки, как долгое нажатие
            .focusRequester(rowFr).hoverable(src).combinedClickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current,
                onClick = rowClickR,
                onLongClick = rowLongR)
            .mouseLeftClicks(rowClickR, rowLongR)   // 10.0 (mouse): мышь: клик, «подержал и отпустил» = меню, «подержал и повёл» = прокрутка
            .then(if (cKey != null && cntNow == null) Modifier.onGloballyPositioned { c ->
                val b = c.boundsInWindow(); val v = c.isAttached && b.bottom > 0f && b.top < cView.rootView.height
                if (v != cVis) cVis = v
            } else Modifier)
            .heightIn(min = if (tv) 52.dp else if (r.level > 0) 34.dp else 38.dp).padding(start = (TreeDim.pad + r.level * TreeDim.ind).dp, end = 3.dp, top = if (tv) 3.dp else if (r.level > 0) 1.dp else 2.dp, bottom = if (tv) 3.dp else if (r.level > 0) 1.dp else 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (r.level > 0) 5.dp else 8.dp)) {
            if (r.expandKey != null) Icon(if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
            else if (r.treeId != null && FmRootCfg.treeOn) {
                // 10.2 (58, как в X-plore): синий треугольник только у папок, в которых что-то есть
                val open = r.treeId in p.rootOpen
                TreeLog.Note("Источники", r.level)
                val tlDm = androidx.compose.ui.platform.LocalDensity.current
                val empty = !open && cntNow != null && cntNow.first + cntNow.second == 0
                val arrowCol = treeArrowColor()   // 20.11.2026: цвет стрелок по настройке (по умолчанию в цвет текста)
                if (empty) Spacer(Modifier.padding(end = TreeDim.rootSp(r.level).dp).size(TreeDim.arrow(tv).dp))
                else Box(Modifier.padding(end = TreeDim.rootSp(r.level).dp).size(TreeDim.arrow(tv).dp).onGloballyPositioned { cc -> TreeLog.measured("Источники", r.level, tlDm.density, tlDm.fontScale, "поле стрелки", cc.positionInParent().x, cc.size.width.toFloat()) }.tapOnly { rowToggle(r.treeId) }, contentAlignment = Alignment.Center) {   // 14.0: стрелка без узла семантики, без фокуса и без ряби (tapOnly) (раскрывает и нажатие на строку), меньше узлов семантики
                    androidx.compose.foundation.Canvas(Modifier.size(if (tv) 16.dp else 12.dp)) {
                        val c = arrowCol
                        val path = androidx.compose.ui.graphics.Path().apply {
                            if (open) { moveTo(0f, size.height * .2f); lineTo(size.width, size.height * .2f); lineTo(size.width / 2, size.height * .9f) }
                            else { moveTo(size.width * .15f, 0f); lineTo(size.width * .15f, size.height); lineTo(size.width, size.height / 2) }
                            close()
                        }
                        drawPath(path, c)
                    }
                }
            }
            else if (FmRootCfg.treeOn && r.level > 0 && r.header == 0) Spacer(Modifier.padding(end = TreeDim.rootSp(r.level).dp).size(TreeDim.arrow(tv).dp))      // место под шеврон: значок и подпись встают правее значка родителя
            if (r.busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            FmRootRowIcon(p, r, tv)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (r.err) Text(r.title, Modifier.weight(1f), fontSize = (13f * bigFont).sp, fontWeight = FontWeight.Normal, maxLines = 9, softWrap = true, fontFamily = UiPrefs.listFont, color = MaterialTheme.colorScheme.error)
                    else MqText(r.title, active = rowActive, modifier = Modifier.weight(1f), fontSize = ((if (r.level > 0) 14f else 15f) * bigFont).sp, fontWeight = FontWeight.Bold, fontFamily = UiPrefs.listFontBold, names = true)
                    r.net?.let { nc -> Fm.panes.firstNotNullOfOrNull { pp -> (pp.smb as? KeepFs)?.takeIf { pp.src == "smb" && pp.net == nc } }?.let { NetDot(it) } }
                }
                // у томов первым свободное место (то, что нужно видеть), затем файловая система, счётчики папок и файлов в конце
                val svc = r.net?.takeIf { !r.drive && r.tnet == null }?.let { CloudIcons.serviceCaption(ctx, it) }
                val offlinePending = r.treeId != null && (r.net != null || r.tnet != null) && r.treeId in p.rootPending && r.treeId !in p.rootOpen && !remember { FmRootCfg.hasNetwork(ctx) }
                // 10.2: вторая строка как в X-plore: «дата и время · размер» у файла, «дата и время · N папок, N файлов» у папки
                val narrowRow = !tv && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 420
                val subDate = r.dateRaw?.takeIf { FmSub.mode != FmSub.NONE }?.let { nbBlock(FmSub.date(ctx, it, narrowRow)) }
                val subCounts = cntNow?.let { nbBlock(stringResource(if (narrowRow) R.string.rt_counts_s else R.string.rt_counts, it.first, it.second)) }
                val sub = listOfNotNull(
                    svc, if (offlinePending) stringResource(R.string.rt_parked) else null,
                    if (r.total > 0) stringResource(R.string.rt_free, fmGb(r.free), fmGb(r.total)) else null,
                    r.fs.takeIf { it.isNotBlank() }, if (r.ro) stringResource(R.string.rt_ro) else null, if (r.warn) stringResource(R.string.fm_vol_unsupported_short) else null,
                    subDate, subCounts,
                    r.file?.takeIf { FmSub.mode != FmSub.NONE }?.let { nbBlock(fmSize(it.size)) }, r.subText?.takeIf { it.isNotEmpty() }, r.path?.takeIf { r.net == null && r.file == null && r.treeId == null },
                    cnt?.let { nbBlock(stringResource(R.string.rt_counts, it.first, it.second)) },
                    if (r.level > 0) r.side?.takeIf { it.isNotBlank() } else null,   // 10.11.2026: число и размер корзины облака во вложенной строке идут второй строкой (справа они выдавливали название в столбик из букв)
                ).joinToString(SUB_SEP)
                if (sub.isNotEmpty()) MqText(sub, active = rowActive, modifier = Modifier.fillMaxWidth(), fontSize = ((if (narrowRow && r.dateRaw != null) 10 else 11) * bigFont).sp, lineHeight = (13 * bigFont).sp, fontFamily = UiPrefs.listFont, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                if (r.total > 0) FmSpaceBar(1f - r.free.toFloat() / r.total, Modifier.fillMaxWidth().padding(top = 1.dp))
            }
            if (r.ro) Icon(Icons.Filled.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
            r.tog?.let { on -> BigCheck(on, tv) { r.act() } }
            if (r.level == 0) r.side?.let { Text(it, fontSize = (13 * bigFont).sp, maxLines = 1, fontFamily = UiPrefs.listFont, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f)) }
            if (chkClick != null) Box(Modifier.width(if (tv) 40.dp else 32.dp), contentAlignment = Alignment.CenterEnd) { SelBox(chkOn, chkPart, tv) }
        }
        // 10.2 (58, как в X-plore): у папок и файлов телефона слева незаметная точка: нажатие открывает свойства, строка не выделяется
        if (e0 != null && e0.net == null && e0.path.startsWith("/") && r.header == 0) Box(
            Modifier.align(Alignment.CenterStart).offset(x = (-(TreeDim.pad + r.level * TreeDim.ind)).dp).size(TreeDim.dotW.dp, 32.dp)
                .tapOnly { Fm.active = p.id; Fm.propsPath(e0.path) },   // 14.0: служебная точка без узла семантики (tapOnly)
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .3f), androidx.compose.foundation.shape.CircleShape)) }
        if (chkClick != null) SelZone(tv, chkFr, rowFr, stringResource(if (chkOn) R.string.fm_unselect else R.string.fm_select), onLong = { Fm.active = p.id; p.rootSel = r.key; menu = true }) { chkClick() }
        if (iconPick) CustomIconPicker(r.title, "d:" + r.key) { iconPick = false }
        if (menu) FmRootRowMenu(p, r, { menu = false }, { iconPick = true })
    }
    }
}


internal fun rootRowNode(r: RR): String? = r.selE?.takeIf { it.ti == null && it.fi == null && r.file != null }?.parentId ?: r.treeId?.takeIf { it.startsWith("L|") || it.startsWith("N|") || it.startsWith("D|") }

internal fun rootTreeToggle(p: FmPane, rowKey: String, ctx: Context, id: String) {
    p.rootPending.remove(id); if (id in p.rootOpen) { p.rootOpen.remove(id); collapseInside(p, id); refocusCollapsed(p, rowKey, rowKey); if (Fm.treeOne) FmRootCfg.purgeClosedCloud() } else { FocusRestore.startKey = null; if (Fm.treeOne || !id.startsWith("N|cloud|")) rootAccordion(p, id); p.rootOpen.add(id); if (Fm.treeOne) FmRootCfg.purgeClosedCloud(); FmRootCfg.refresh(p, id, NetStore.list(ctx)); FmRootCfg.scrollTarget[p.id] = id }
}

internal fun rootRowToggle(p: FmPane, r: RR, ctx: Context, id: String) {
        val isLan = r.level == 1 && !r.drive && r.net != null && id == r.treeId && (r.key.startsWith("lanf:") || (r.key.startsWith("net:") && r.net.kind != "dav" && r.net.kind != "cloud"))
        val opening = id !in p.rootOpen
        rootTreeToggle(p, r.key, ctx, id)
        if (opening) FmRootCfg.pruneSel()   // 26.11.2026: раскрыли другую папку, прежняя свернулась: её отметки снимаются
        if (isLan && opening) {
            val nc = r.net!!
            val h = if (r.key.startsWith("lanf:")) FmRootCfg.lanFound.firstOrNull { FmRootCfg.lanConn(it).host == nc.host } else null
            Fm.scope.launch { FmRootCfg.lanAutoOpen(p, id, nc, h) }
        }
}

internal fun rootRowClick(p: FmPane, r: RR, ctx: Context, openMenu: () -> Unit) {
    val e0 = r.selE
    val nodeForTarget = rootRowNode(r)
    Fm.active = p.id; p.rootSel = r.key; FmRootCfg.noteRow(p.id, r.key, nodeForTarget); if (e0 != null && r.titem == null && r.fitem == null && FmRootCfg.sel.values.any { it.ti == null && it.fi == null }) FmRootCfg.toggleTree(p.id, e0) else if (r.titem != null || r.fitem != null) openMenu() else if (r.expandKey != null) { if (r.expandKey in p.rootOpen) collapseGroup(p, r.expandKey) else openGroup(p, r.expandKey) } else if (r.treeId != null && FmRootCfg.treeOn && r.file == null) rootRowToggle(p, r, ctx, r.treeId) else r.act()
}

/** 22.11.2026: значок строки (общий для строки Compose и для значка в строке RecyclerView). */
@Composable
internal fun FmRootRowIcon(p: FmPane, r: RR, tv: Boolean) {
    val rowCustom = if (r.header == 0) CustomIcons.spec("d:" + r.key) else null
    if (rowCustom != null) CustomIconView(rowCustom, if (tv) 32.dp else 28.dp) else if (r.appName != null) AppsRowIcon(r.appName, if (tv) 32.dp else 28.dp) else if (r.drive) FolderIcon(UiPrefs.folderDefault, r.treeId != null && r.treeId in p.rootOpen, if (tv) 32.dp else 28.dp) else if (r.icon == Icons.Filled.Folder && r.tint == Color(0xFFFFB74D) && r.treeId != null && r.treeId.startsWith("L|")) FolderOrAppIcon(r.treeId.removePrefix("L|"), UiPrefs.folderColors[folderKey(p, r.treeId.removePrefix("L|"))] ?: UiPrefs.folderDefault, r.treeId in p.rootOpen, if (tv) 32.dp else 28.dp, customKey = "f:" + folderKey(p, r.treeId.removePrefix("L|"))) else if (r.file != null) FileTypeIcon(r.file.name, r.file.ext, r.lpath, if (tv) 32.dp else 28.dp, remote = r.remoteApk) else if (r.gname != null) FileTypeIcon(r.gname, r.gname.substringAfterLast('.', "").lowercase(), null, if (tv) 32.dp else 28.dp) else if (r.net != null && !r.wifi) NetBrandIcon(r.net, if (CloudCatalog.bigRows && r.level == 0) (if (tv) 44.dp else 40.dp) else if (tv) 32.dp else 28.dp) else Icon(if (r.warn || r.err) Icons.Filled.Warning else r.icon, null, Modifier.size(if (tv) 32.dp else 28.dp), tint = if (r.warn || r.err) MaterialTheme.colorScheme.error else r.tint)
}

/** 22.11.2026: меню строки дерева «Источников» вынесено в отдельную функцию (общая для строки Compose и для списка на RecyclerView). */
@Composable
internal fun FmRootRowMenu(p: FmPane, r: RR, close: () -> Unit, pickIcon: () -> Unit) {
    val ctx = LocalContext.current
    LDropdownMenu(true, { close() }) {
            @Composable fun item(l: Int, run: () -> Unit) = DropdownMenuItem({ Text(stringResource(l), fontSize = 14.sp, color = if (l == R.string.fm_delete) deleteRed() else androidx.compose.ui.graphics.Color.Unspecified) }, { close(); run() })
            // 9.0: найденные и сохранённые устройства (сеть, LAN, Wi-Fi, ПК, Android): копирование названия и адреса
            if (r.pc != null || r.adev != null || (r.net != null && !r.drive)) {
                val devName = r.pc?.name?.ifBlank { r.pc.ip } ?: r.adev?.title ?: r.net?.title ?: r.title
                val devAddr = r.pc?.ip ?: r.adev?.addr ?: r.net?.host
                item(R.string.copy_name) { copyText(ctx, devName) }
                if (!devAddr.isNullOrBlank()) item(R.string.copy_addr) { copyText(ctx, devAddr) }
            }
            // 9.0: у остальных строк (папки и ресурсы сетевых источников и облаков, найденное по LAN, места) долгое нажатие даёт «Копировать название» и «Копировать путь/адрес»
            val localE = r.selE?.takeIf { it.net == null && it.ti == null && it.fi == null && r.appName == null }
            val isDev = r.pc != null || r.adev != null || (r.net != null && !r.drive)
            if (!isDev && localE == null && r.header == 0 && !r.err && !r.busy && r.title.isNotBlank() && r.key !in setOf("showhide", "wifi:scan", "lan:scan", "lan:find", "lan+", "cloud+", "lan:none", "wifi:none") &&
                !r.key.endsWith(":st") && !r.key.endsWith(":fix") && !r.key.endsWith(":empty") && !r.key.endsWith(":more")) {
                val nm = r.selE?.name ?: r.file?.name ?: r.gname ?: r.title
                val tid = r.treeId
                val pth: String? = r.selE?.path?.takeIf { it.isNotBlank() } ?: r.lpath ?: r.path ?: when {
                    tid != null && tid.startsWith("N|") -> tid.split('|').let { pp -> if (pp.size >= 5) pp[1] + "://" + pp[2] + pp[4] else null }
                    tid != null && tid.startsWith("L|") -> tid.removePrefix("L|")
                    else -> null
                }
                item(R.string.copy_name) { copyText(ctx, nm) }
                if (!pth.isNullOrBlank()) item(R.string.fm_copy_path) { copyText(ctx, pth) }
                // 11.0: «Свойства» есть у любой строки: имя, путь и сведения целиком (окно переносит текст)
                item(R.string.fm_props) {
                    val more = listOfNotNull(
                        r.subText?.takeIf { it.isNotEmpty() },
                        r.file?.let { fmSize(it.size) }?.takeIf { FmSub.mode != FmSub.NONE },
                        r.dateRaw?.takeIf { FmSub.mode != FmSub.NONE }?.let { FmSub.date(ctx, it, false) },
                    ).joinToString("  ·  ")
                    Fm.dlg = FmDlg.Props(Fm.str(R.string.fm_props_title), listOfNotNull(
                        Fm.str(R.string.fm_name) + ": " + nm,
                        pth?.takeIf { it.isNotBlank() }?.let { Fm.str(R.string.fm_path) + ": " + it },
                        more.takeIf { it.isNotEmpty() }?.let { Fm.str(R.string.fm_size) + ": " + it },
                    ).joinToString("\n"))
                }
            }
            // своя иконка для любой строки корневого экрана (диски, места, подключения, облака)
            if (r.header == 0 && !r.err && !r.busy && r.key != "showhide" && !r.key.endsWith(":st") && !r.key.endsWith(":fix") && !r.key.endsWith(":empty") && !r.key.endsWith(":more") && r.key != "wifi:scan" && r.key != "wifi:none") item(R.string.ic_row) { pickIcon() }
            r.retry?.let { rt -> item(R.string.rt_reconnect) { rt() } }
            if ((r.net != null || r.tnet != null) && r.treeId != null && r.treeId in p.rootPending && r.treeId !in p.rootOpen) item(R.string.rt_reconnect) { rootTreeToggle(p, r.key, ctx, r.treeId) }
            if (r.appName != null) AppMenuItems(p, FmItem(r.appName, false, 0, ""), Fm.panes[1 - p.id], copy = false) { close() }
            else if (r.expandKey == null && r.header == 0 && r.tnet == null && !r.ftrash && r.fitem == null) item(if (r.treeId != null && FmRootCfg.treeOn && r.file == null) R.string.fm_open_panel else R.string.fm_open) { r.act() }
            // полное меню файла и папки на корневом экране (как в списке панели): отметка, копирование, права доступа и так далее
            r.selE?.takeIf { it.net == null && it.ti == null && it.fi == null && r.appName == null }?.let { e ->
                val inSel = FmRootCfg.selIdx.value.direct(e.key)
                val multiSel = inSel && FmRootCfg.sel.size > 1
                val tg = if (multiSel) FmRootCfg.sel.values.filter { it.net == null && it.ti == null && it.fi == null } else listOf(e)
                fun pane(path: String): FmPane = FmPane(9, path).also { it.src = "local" }
                val rel = tg.map { it.path.trimStart('/') }
                val dir = if (e.isDir) e.path else e.parentPath
                if (e.isDir && !multiSel) item(R.string.fc_title) { Fm.dlg = FmDlg.FolderColor(e.path, e.path.trimEnd('/').substringAfterLast('/')) }
                fun refresh(focusKey: String? = null) { val before = FmRootCfg.rowsOf(p.id); val ids = tg.map { it.parentId } + (if (e.isDir) listOf(e.key) else emptyList()); Fm.scope.launch { kotlinx.coroutines.delay(600); var wn = 0; while (wn < 400 && (Fm.dlg is FmDlg.Rename || Fm.dlg is FmDlg.NewFolder || Fm.dlg is FmDlg.NewFile)) { kotlinx.coroutines.delay(300); wn++ }; kotlinx.coroutines.delay(if (wn > 0) 700L else 1900L); ids.distinct().forEach { FmRootCfg.kidErr.remove(it); FmRootCfg.stale.add(it) }; CloudTrash.ver++; FmRootCfg.focusRowLater(focusKey); FmRootCfg.focusRenamed(p.id, before) } }
                fun transfer(move: Boolean) {
                    val o = Fm.panes[1 - p.id]
                    val dst: FmRootCfg.Dst? = if (o.root) (FmRootCfg.destFor(o) ?: run { Fm.dlg = FmDlg.PickTarget(move, tg.groupBy { it.parentPath }.map { (pp, g) -> pane(pp) to g.map { it.name } }) { refresh() }; return }) else null
                    val toLbl = dst?.label() ?: Fm.pathLabel(o, o.src, o.path)
                    val fromLbl = Fm.pathLabel(pane(tg[0].parentPath), "local", tg[0].parentPath)
                    Fm.askTransfer(move, fromLbl, toLbl, tg.map { it.name }, 0L, dst?.pane() ?: o, null) { mv, pol ->
                        dst?.let { Fm.say(Fm.str(if (mv) R.string.pk_moving_to else R.string.pk_copying_to, it.label())) }
                        Fm.scope.launch {
                            for ((pp, g) in tg.groupBy { it.parentPath }) { Fm.copyMove(mv, pane(pp), dst?.pane() ?: o, g.map { it.name }, policy = pol); kotlinx.coroutines.delay(700); while (Fm.dlg is FmDlg.Conflict) kotlinx.coroutines.delay(300) }
                            RootSelOps.afterJobs(tg.map { it.parentId } + listOfNotNull(dst?.node))
                            refresh()
                        }
                    }
                }
                item(if (inSel) R.string.fm_unselect else R.string.fm_select) { FmRootCfg.toggleTree(p.id, e) }
                item(R.string.fm_copy) { transfer(false) }
                item(R.string.fm_move) { transfer(true) }
                item(R.string.fm_delete) { val nb = FmRootCfg.neighborOf(tg.map { "t:" + it.key }.toSet()); Fm.askDelete(pane("/"), rel); refresh(nb) }
                if (!multiSel && !e.isDir) item(R.string.fm_open_in_system) { ApkInstall.showPicker(pane(e.parentPath), FmItem(e.name, false, 0L, "")) }   // 24.11.2026: окно «Открыть через…» (и «Сбросить» запомненного) доступно и на корневом экране
                if (!multiSel) item(R.string.fm_rename) { Fm.dlg = FmDlg.Rename(pane(e.parentPath), e.name); refresh() }
                if (!multiSel) item(R.string.fm_duplicate) {
                    val dot = e.name.lastIndexOf('.')
                    val nn = if (dot > 0 && !e.isDir) e.name.substring(0, dot) + " (1)" + e.name.substring(dot) else e.name + " (1)"
                    Fm.copyMoveTo(false, pane(e.parentPath), pane(e.parentPath), "local", e.parentPath, listOf(e.name), nn); refresh()
                }
                if (!multiSel && !e.isDir && e.name.substringAfterLast('.').lowercase().let { x -> x == "apk" || ApkInstall.isBundle(x) } && ApkInstall.installedStores(Fm.sctx).isNotEmpty()) item(R.string.inst_market) { ApkInstall.openMarket(pane(e.parentPath), FmItem(e.name, false, 0, "")) }
                item(R.string.fm_props) { Fm.props(pane("/"), rel) }
                item(R.string.perm_title) { Fm.dlg = FmDlg.Perms(pane("/"), rel) }
                item(R.string.fm_make_archive) { Fm.dlg = FmDlg.MakeArchive(pane(e.parentPath), tg.filter { it.parentPath == e.parentPath }.map { it.name }) }
                if (!multiSel && !e.isDir && e.name.substringAfterLast('.').lowercase() in setOf("apks", "xapk", "apkm")) item(R.string.rp_menu) { Fm.repackFile(pane(e.parentPath), FmItem(e.name, false, 0, ""), Fm.panes[1 - p.id]) }
                item(R.string.copy_name) { copyText(ctx, tg.joinToString("\n") { it.name }) }
                item(R.string.fm_copy_path) { copyText(ctx, tg.joinToString("\n") { it.path }) }
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
            r.net?.takeIf { !r.drive && it.kind == "cloud" && CloudTrash.supported(CloudTrash.typeFor(ctx, it)) }?.let { nc -> item(R.string.tr_menu) { Fm.dlg = FmDlg.Trash(nc) } }
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
                    DropdownMenuItem({ Text((if (WifiExplorer.mode == m) "✓ " else "") + stringResource(res), fontSize = 14.sp) }, { close(); WifiExplorer.setMode(ctx, m) })
            }
            r.pc?.let { h ->
                item(R.string.dev_call_video) { devCall(ctx, h.name.ifBlank { h.ip }, listOf(h.ip), h.code, false) }
                item(R.string.dev_call_audio) { devCall(ctx, h.name.ifBlank { h.ip }, listOf(h.ip), h.code, true) }
                item(R.string.dev_window) { Fm.dlg = FmDlg.PcScreen(h) }
                item(R.string.dev_files) { fmLeaveRoot(p); Fm.connectNet(p, h.conn(ctx)) }
                item(R.string.rt_edit) { Fm.dlg = FmDlg.DevAdd(h, null) }
                item(R.string.rt_forget) { saveHosts(ctx, loadHosts(ctx).filter { it.ip != h.ip }); FmRootCfg.ver++ }
            }
            r.adev?.let { d ->
                item(R.string.dev_call_video) { devCall(ctx, d.title, d.callIps(), null, false) }
                item(R.string.dev_call_audio) { devCall(ctx, d.title, d.callIps(), null, true) }
                item(R.string.dev_window) { Fm.dlg = FmDlg.AndroidScreen(d) }
                item(R.string.dev_files) { fmLeaveRoot(p); Fm.connectNet(p, d.conn()) }
                item(R.string.dev_console) { Fm.dlg = FmDlg.AndroidConsole(d) }
                item(R.string.dev_ts) {
                    val ts = d.tsAddr
                    if (ts == null) { Fm.say(Fm.str(R.string.dev_ts_none)); Fm.dlg = FmDlg.DevAdd(null, d) }
                    else { fmLeaveRoot(p); Fm.connectNet(p, AdbDev(d.name, ts).conn()) }
                }
                item(R.string.rt_edit) { Fm.dlg = FmDlg.DevAdd(null, d) }
                item(R.string.dev_disconnect) { AdbLinks.drop(d.addrs); AdbStatus.up.remove(d.key); FmRootCfg.ver++ }
                item(R.string.rt_forget) { androidForget(ctx, d); FmRootCfg.ver++ }
            }
            if (r.key == "wifi:srv") item(R.string.wf_link) { Fm.dlg = FmDlg.WifiServer("/sdcard") }
            // найденное по LAN, ещё не сохранённое: сохранить в избранное как есть или сначала изменить (имя, пользователь, пароль, тип)
            if (r.key.startsWith("lanf:") && r.net != null) {
                val nc = r.net
                item(R.string.fm_net_save) { NetStore.save(ctx, nc); FmRootCfg.lanFound.removeAll { h -> FmRootCfg.lanConn(h).host == nc.host }; FmRootCfg.ver++ }
                item(R.string.fm_net_edit_title) { val h = FmRootCfg.lanFound.firstOrNull { FmRootCfg.lanConn(it).host == nc.host }; if (h != null) Fm.dlg = FmDlg.Source(h) }
            }
            if (r.key == "wifi+") item(R.string.wf_manual) { Fm.dlg = FmDlg.WifiDev(null, "", "") }
            if (r.key in setOf("root", "recent", "adb", "lan", "cloud", "wifi", "pc", "apps")) item(R.string.rt_hide) { FmRootCfg.set(ctx, r.key, false) }
    }
}

/** Окно «Показать/Скрыть»: какие необязательные строки видны на корневом экране. */
@Composable
fun RootShowDialog(close: () -> Unit) {
    val ctx = LocalContext.current
    val names = mapOf("root" to "Root", "recent" to stringResource(R.string.rt_recent), "lan" to stringResource(R.string.rt_lan), "cloud" to stringResource(R.string.rt_cloud), "wifi" to stringResource(R.string.rt_wifi), "pc" to stringResource(R.string.rt_pc), "apps" to stringResource(R.string.rt_apps), "tree" to stringResource(R.string.rt_tree), "adb" to stringResource(R.string.rt_adb))
    LAlertDialog(
        onDismissRequest = close, title = { Text(stringResource(R.string.rt_showhide)) },
        text = {
            Column {
                FmRootCfg.optional.forEach { k ->
                    Row(Modifier.fillMaxWidth().clickable { FmRootCfg.set(ctx, k, k !in FmRootCfg.shown) }, verticalAlignment = Alignment.CenterVertically) {
                        BigCheckbox(k in FmRootCfg.shown, { FmRootCfg.set(ctx, k, it) }); Text(names[k] ?: k, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(close) { Text(stringResource(R.string.btn_close)) } },
    )
}

/** 14.0: фокус (бит 1), наведение (2) и нажатие (4) строки одним сборщиком; [onFocus] вызывается при получении фокуса. */
@Composable
private fun rowInteractionBits(src: androidx.compose.foundation.interaction.MutableInteractionSource, onFocus: () -> Unit): androidx.compose.runtime.State<Int> {
    val st = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val onF = androidx.compose.runtime.rememberUpdatedState(onFocus)
    androidx.compose.runtime.LaunchedEffect(src) {
        var f = false; var h = false
        val pressed = ArrayList<androidx.compose.foundation.interaction.PressInteraction.Press>()
        src.interactions.collect { i ->
            when (i) {
                is androidx.compose.foundation.interaction.FocusInteraction.Focus -> { if (!f) onF.value(); f = true }
                is androidx.compose.foundation.interaction.FocusInteraction.Unfocus -> f = false
                is androidx.compose.foundation.interaction.HoverInteraction.Enter -> h = true
                is androidx.compose.foundation.interaction.HoverInteraction.Exit -> h = false
                is androidx.compose.foundation.interaction.PressInteraction.Press -> pressed.add(i)
                is androidx.compose.foundation.interaction.PressInteraction.Release -> pressed.remove(i.press)
                is androidx.compose.foundation.interaction.PressInteraction.Cancel -> pressed.remove(i.press)
            }
            st.intValue = (if (f) 1 else 0) or (if (h) 2 else 0) or (if (pressed.isNotEmpty()) 4 else 0)
        }
    }
    return st
}

/** Контейнер без лишних зависимостей: обычный Column с общим модификатором. */
@Composable
private fun Box2(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
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
    val usedColor = if (u > .9f) Color(0xFFDC2626) else BarStyle.used()
    Row(modifier.height(h).background(BarStyle.freeSpace(), RoundedCornerShape(1.dp)).barFrame(BarStyle.used(), RoundedCornerShape(1.dp), 0.6.dp)) {
        androidx.compose.foundation.layout.Box(Modifier.weight(u.coerceAtLeast(0.01f)).fillMaxHeight().background(usedColor, RoundedCornerShape(1.dp)))
        if (u < 1f) Spacer(Modifier.weight(1f - u))
    }
}
