@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.adbphon

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Цель сброса при перетаскивании. [kind]: 0 свободное место панели, 1 папка в списке или дереве, 2 фрагмент пути, 3 значок источника, 4 «вверх», 5 вкладка панели.
 * [src] — источник целевой папки (у значка «Телефон» всегда local, даже если панель сейчас на сети).
 */
class DndTarget(val id: Any) {
    var pane: FmPane = Fm.panes[0]
    var path: String = ""
    var kind: Int = 0
    var src: String = "local"
    var label: String = ""
    var rect: Rect = Rect.Zero
    val key: String get() = "${pane.id}|$kind|$path"
}

class DndList(val state: LazyListState, val rect: Rect)

/**
 * Перетаскивание файлов и папок: зажать и тянуть (мышь, аэромышь) или долгое касание и тянуть (сенсор).
 * При отпускании над целью всегда показывается окно «Копировать / Переместить / Отмена»: никаких автоматических решений.
 */
object FmDnd {
    var on by mutableStateOf(true)
    var active by mutableStateOf(false)
    var pos by mutableStateOf(Offset.Zero)
    var hover by mutableStateOf<DndTarget?>(null)
    var src: FmPane? = null
    var names: List<String> = emptyList()
    var first: FmItem? = null
    val targets = HashMap<Any, DndTarget>()
    val lists = HashMap<Int, DndList>()

    /** Указатель, который ведёт перетаскивание, начало координат корневого слоя в окне и время последнего события (для сброса зависшего состояния). */
    var pointerId = -1L
    var rootOrigin = Offset.Zero
    @Volatile var lastEvent = 0L

    /** Пересчитать цель под неподвижным пальцем (после смены панели или раскрытия папки список сдвигается). */
    fun rehit() { if (!active) return; val h = hit(pos); if (h?.key != hover?.key) hover = h }

    fun begin(p: FmPane, f: FmItem, at: Offset, pid: Long = -1L, key: String = f.name) {
        pointerId = pid; lastEvent = System.nanoTime() / 1_000_000
        val n = if (key in p.selected) Fm.targets(p) else listOf(key)
        if (n.isEmpty()) return
        src = p; names = n; first = f; pos = at; hover = null; active = true
        hover = hit(at)
    }

    /** Начало перетаскивания строки корневого экрана: источник задан «виртуальной» панелью (общая папка), имена относительно неё. */
    fun beginNames(p: FmPane, f: FmItem, at: Offset, pid: Long, n: List<String>) {
        pointerId = pid; lastEvent = System.nanoTime() / 1_000_000
        if (n.isEmpty()) return
        src = p; names = n; first = f; pos = at; hover = null; active = true
        hover = hit(at)
    }

    fun move(at: Offset) { if (!active) return; pos = at; val h = hit(at); if (h?.key != hover?.key) hover = h }

    fun cancel() { active = false; hover = null; pointerId = -1L }

    fun setOn(ctx: android.content.Context, v: Boolean) { on = v; ctx.applicationContext.getSharedPreferences("fm", android.content.Context.MODE_PRIVATE).edit().putBoolean("dnd", v).apply() }

    fun end() {
        val t = hover; val s = src; val n = names
        val was = active
        active = false; hover = null
        if (!was || t == null || s == null) return
        Fm.dlg = FmDlg.Drop(s, n, t.pane, t.src, t.path, t.label)
    }

    /** Подходит ли цель: не та же самая папка и не папка внутри себя. */
    private fun valid(t: DndTarget): Boolean {
        val s = src ?: return false
        val same = t.src == s.src && (t.src != "smb" || t.pane.net?.host == s.net?.host)
        if (same && t.path.trimEnd('/') == s.path.trimEnd('/')) return false
        if (same) for (n in names) { val full = FmIo.join(s.path, n); if (t.path == full || t.path.startsWith("$full/")) return false }
        return true
    }

    /** Цель под указателем: самая маленькая из подходящих, свободное место панели только если ничего более точного нет. */
    private fun hit(at: Offset): DndTarget? {
        var best: DndTarget? = null; var bestArea = Float.MAX_VALUE
        var pane: DndTarget? = null
        for (t in targets.values) {
            if (!t.rect.contains(at) || !valid(t)) continue
            if (t.kind == 0) { pane = t; continue }
            val a = t.rect.width * t.rect.height
            if (a < bestArea) { best = t; bestArea = a }
        }
        return best ?: pane
    }
}

/** Делает элемент целью сброса. Путь и подпись обновляются при каждой перерисовке, размер и место при каждом изменении расположения. */
fun Modifier.dndTarget(p: FmPane, path: String, kind: Int, label: String = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }, src: String = p.src): Modifier = composed {
    val t = remember { DndTarget(Any()) }
    t.pane = p; t.path = path; t.kind = kind; t.label = label; t.src = src
    DisposableEffect(t) { onDispose { FmDnd.targets.remove(t.id) } }
    this.onGloballyPositioned { c -> t.rect = c.boundsInRoot(); FmDnd.targets[t.id] = t }
}

/** Подсвечена ли эта цель (для подсветки строки, пути, значка). */
fun dndHot(p: FmPane, path: String, kind: Int): Boolean { val h = FmDnd.hover; return FmDnd.active && h != null && h.kind == kind && h.pane === p && h.path == path }

/** «Призрак» под указателем, подсказка и автопрокрутка списков у краёв; всё в одном слое поверх экрана «Файлы». */
@Composable
fun FmDndOverlay() {
    if (!FmDnd.active) return
    BackHandler { FmDnd.cancel() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val f = FmDnd.first
        val n = FmDnd.names.size
        Surface(
            // «призрак» едет прямо под пальцем, как значок файла в проводнике Windows
            Modifier.offset { IntOffset((FmDnd.pos.x - origin.x).roundToInt() - 36, (FmDnd.pos.y - origin.y).roundToInt() - 36) },
            shape = RoundedCornerShape(12.dp), tonalElevation = 8.dp, shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = .8f),
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (f != null) { val sp0 = FmDnd.src; val full0 = if (sp0 != null) FmIo.join(sp0.path, f.name) else ""; if (f.isDir) FolderIcon(UiPrefs.folderColors[if (sp0 != null) folderKey(sp0, full0) else ""] ?: UiPrefs.folderDefault, false, 40.dp, customKey = if (sp0 != null) "f:" + folderKey(sp0, full0) else null) else FileTypeIcon(f.name, f.ext, if (sp0 != null && sp0.src == "local") full0 else null, 40.dp) }
                Text(f?.name.orEmpty(), maxLines = 1, fontSize = 14.sp, modifier = Modifier.widthIn(max = 200.dp))
                if (n > 1) Text("×$n", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            FmDnd.hover?.let { stringResource(R.string.fm_drop_to_hint, it.label) } ?: stringResource(R.string.fm_drop_hint),
            Modifier.align(Alignment.TopCenter).padding(top = 6.dp).background(Color.Black.copy(alpha = .7f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 12.sp, color = Color.White, maxLines = 1,
        )
    }
    // автопрокрутка у краёв списка, раскрытие папки и переключение панели при удержании; зависшее состояние сбрасывается
    LaunchedEffect(Unit) {
        val timer = HoverTimer()
        while (FmDnd.active) {
            val now = System.nanoTime() / 1_000_000
            if (dndStale(FmDnd.lastEvent, now)) { FmDnd.cancel(); break }
            FmDnd.rehit()
            val pos = FmDnd.pos
            for ((_, l) in FmDnd.lists) {
                if (pos.x < l.rect.left || pos.x > l.rect.right || pos.y < l.rect.top - 40f || pos.y > l.rect.bottom + 40f) continue
                val edge = 96f
                val up = pos.y - l.rect.top; val down = l.rect.bottom - pos.y
                if (up < edge) l.state.dispatchRawDelta(-(edge - up).coerceIn(4f, edge) / 4f)
                else if (down < edge) l.state.dispatchRawDelta((edge - down).coerceIn(4f, edge) / 4f)
            }
            val h = FmDnd.hover
            val delayMs = if (h?.kind == 5) 600L else 800L
            if (timer.tick(h?.key.orEmpty(), now, delayMs) && h != null) {
                when (h.kind) {
                    1 -> if (Fm.treeMode) Fm.expand(h.pane, h.path) else Fm.load(h.pane, h.path)      // папка раскрывается, а в списке открывается
                    5 -> if (Fm.active != h.pane.id) Fm.active = h.pane.id                             // вкладка другой панели (портрет)
                    4 -> Fm.load(h.pane, h.path)                                                        // «вверх»
                    3 -> if (h.pane.root) fmOpenLocal(h.pane, h.path)                                   // строка источника на корневом экране
                }
            }
            delay(16)
        }
    }
}

private fun dropIcon(p: FmPane, src: String): androidx.compose.ui.graphics.vector.ImageVector = when {
    src == "smb" && p.smb is CloudFs -> Icons.Filled.Cloud
    src == "smb" && p.smb is ArchiveFs -> Icons.Filled.FolderZip
    src == "smb" -> Icons.Filled.Dns
    src == "adb" -> Icons.Filled.Android
    p.path.startsWith("/storage/") && !p.path.startsWith("/storage/emulated") -> Icons.Filled.SdCard
    else -> Icons.Filled.PhoneAndroid
}

/**
 * Окно после сброса (08.11.2026, образец владелицы: окно «Копировать» в X-plore): заголовок «Копировать» (или «Переместить»), имя и размер, карандашик для нового имени,
 * галочка «Режим перемещения (удалить файлы из источника)», «Копировать в» с значком устройства или папки и путём, кнопки «Отмена» и «ОК».
 * Компактно: высокий узкий шрифт, как у названий файлов. Ничего не делается, пока не нажато «ОК».
 */
@Composable
fun DropDialog(d: FmDlg.Drop, close: () -> Unit) {
    val n = d.names.size
    var all by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(if (n == 1) d.names[0] else "") }
    var size by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(d) {
        size = withContext(Dispatchers.IO) {
            runCatching {
                if (d.src.src == "local") d.names.sumOf { LocalOps.size(File(FmIo.join(d.src.path, it))) }
                else if (d.names.all { nm -> d.src.items.firstOrNull { it.name == nm }?.isDir == false }) d.names.sumOf { nm -> d.src.items.first { it.name == nm }.size } else null
            }.getOrNull()
        }
    }
    val cancelFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val teal = Color(0xFF0F766E)
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f)
    val tv = Marq.tv
    fun ok() {
        val nn = newName.trim()
        val valid = n == 1 && nn.isNotEmpty() && nn != d.names[0] && '/' !in nn && '\\' !in nn
        close(); Fm.copyMoveTo(move, d.src, d.dst, d.dstSrc, d.toPath, d.names, if (valid) nn else null)
    }
    LDialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val land = maxWidth > maxHeight
            if (land) {
                // в альбоме окно прижато к низу экрана, как карточка копирования
                val view = androidx.compose.ui.platform.LocalView.current
                androidx.compose.runtime.SideEffect { runCatching { (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setGravity(android.view.Gravity.BOTTOM) } }
            }
            val w = if (land) maxWidth * 0.96f else if (tv) minOf(maxWidth * 0.8f, 560.dp) else maxWidth - 24.dp
            Surface(Modifier.width(w).heightIn(max = maxHeight * 0.94f), shape = RoundedCornerShape(18.dp), tonalElevation = 6.dp) {
                Column(Modifier.fillMaxWidth()) {
                if (land) DropLandRow(d, n, move, { move = it }, editing, { editing = !editing }, newName, { newName = it }, size, { ok() }, close, cancelFocus, teal, dim) else {
                Column(Modifier.weight(1f, fill = false).barScroll().padding(start = 16.dp, end = 12.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (move) Icons.Filled.DriveFileMove else Icons.Filled.ContentCopy, null, tint = if (move) Color(0xFF6A5ACD) else teal, modifier = Modifier.size(24.dp))
                        Text(stringResource(if (move) R.string.drop_title_move else R.string.drop_title_copy), Modifier.padding(start = 10.dp), fontSize = 19.sp, fontFamily = CondFont)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (editing) androidx.compose.foundation.text.BasicTextField(newName, { newName = it }, singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, fontFamily = CondFont, color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(teal),
                            modifier = Modifier.weight(1f).border(1.dp, teal, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp))
                        else Text(if (n == 1) newName else fmItemsText(n), Modifier.weight(1f), fontSize = 16.sp, fontFamily = CondFont, fontWeight = FontWeight.Medium, maxLines = 3)
                        if (n == 1) Icon(Icons.Filled.Edit, stringResource(R.string.fm_rename), tint = if (editing) teal else dim, modifier = Modifier.padding(start = 8.dp).size(28.dp).clickable { editing = !editing }.padding(3.dp))
                    }
                    if (n > 1) {
                        val shown = if (all) d.names else d.names.take(3)
                        Column(Modifier.clickable { all = !all }.padding(start = 4.dp)) {
                            shown.forEach { Text(it, fontSize = 12.sp, maxLines = 1, fontFamily = CondFont, color = dim) }
                            if (!all && n > 3) Text(stringResource(R.string.fm_drop_and_more, n - 3), fontSize = 12.sp, fontFamily = CondFont, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(size?.let { fmSize(it) } ?: " ", fontSize = 13.sp, fontFamily = CondFont, color = dim)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(dropIcon(d.src, d.src.src), null, tint = dim, modifier = Modifier.size(18.dp))
                        Text(Fm.pathLabel(d.src, d.src.src, d.src.path), Modifier.padding(start = 6.dp), fontSize = 12.sp, fontFamily = CondFont, color = dim, maxLines = 2)
                    }
                    Row(Modifier.fillMaxWidth().clickable { move = !move }, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(move, { move = it }, Modifier.size(36.dp))
                        Text(stringResource(R.string.drop_move_mode), Modifier.padding(start = 4.dp), fontSize = 13.sp, lineHeight = 15.sp, fontFamily = CondFont)
                    }
                    Text(stringResource(if (move) R.string.drop_move_to else R.string.drop_copy_to), fontSize = 14.sp, fontFamily = CondFont, fontWeight = FontWeight.Medium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(dropIcon(d.dst, d.dstSrc), null, tint = teal, modifier = Modifier.size(22.dp))
                        Text(Fm.pathLabel(d.dst, d.dstSrc, d.toPath), Modifier.padding(start = 8.dp), fontSize = 16.sp, fontFamily = CondFont, color = teal, maxLines = 3)
                    }
                }
                // кнопки закреплены внизу и видны всегда, даже в альбоме на низком экране (содержимое выше прокручивается)
                Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 2.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(close, Modifier.focusRequester(cancelFocus)) { Text(stringResource(R.string.btn_cancel), fontSize = 15.sp) }
                    TextButton({ ok() }) { Text(stringResource(R.string.drop_ok), fontSize = 15.sp) }
                }
                }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
}

/** Окно подтверждения в альбоме: всё в одну строку (что, размер, откуда, куда, перенос, кнопки). */
@Composable
private fun DropLandRow(
    d: FmDlg.Drop, n: Int, move: Boolean, onMove: (Boolean) -> Unit, editing: Boolean, onEdit: () -> Unit, newName: String, onName: (String) -> Unit,
    size: Long?, onOk: () -> Unit, close: () -> Unit, cancelFocus: androidx.compose.ui.focus.FocusRequester, teal: Color, dim: Color,
) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        // строка 1: что копируем, размер, откуда → куда
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (move) Icons.Filled.DriveFileMove else Icons.Filled.ContentCopy, null, tint = if (move) Color(0xFF6A5ACD) else teal, modifier = Modifier.size(22.dp))
            if (editing) androidx.compose.foundation.text.BasicTextField(newName, onName, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontFamily = CondFont, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(teal),
                modifier = Modifier.weight(1f).border(1.dp, teal, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
            else Text(if (n == 1) newName else fmItemsText(n), Modifier.weight(1f, fill = true), fontSize = 15.sp, fontFamily = CondFont, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (n == 1) Icon(Icons.Filled.Edit, stringResource(R.string.fm_rename), tint = if (editing) teal else dim, modifier = Modifier.size(28.dp).clickable { onEdit() }.padding(3.dp))
            Text(size?.let { fmSize(it) } ?: "", fontSize = 13.sp, fontFamily = CondFont, color = dim, maxLines = 1)
            Icon(dropIcon(d.src, d.src.src), null, tint = dim, modifier = Modifier.size(18.dp))
            Text(d.src.path.trimEnd('/').substringAfterLast('/').ifEmpty { Fm.pathLabel(d.src, d.src.src, d.src.path) }, Modifier.widthIn(max = 140.dp), fontSize = 13.sp, fontFamily = CondFont, color = dim, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text("→", fontSize = 16.sp, color = dim)
            Icon(dropIcon(d.dst, d.dstSrc), null, tint = teal, modifier = Modifier.size(20.dp))
            Text(d.label.ifEmpty { Fm.pathLabel(d.dst, d.dstSrc, d.toPath) }, Modifier.widthIn(max = 140.dp), fontSize = 14.sp, fontFamily = CondFont, color = teal, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        // строка 2: перенос вместо копирования и кнопки
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clickable { onMove(!move) }, verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(move, { onMove(it) }, Modifier.size(36.dp))
                Text(stringResource(R.string.drop_title_move), Modifier.padding(start = 2.dp), fontSize = 13.sp, fontFamily = CondFont, maxLines = 1)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            TextButton(close, Modifier.focusRequester(cancelFocus)) { Text(stringResource(R.string.btn_cancel), fontSize = 14.sp, maxLines = 1) }
            TextButton(onOk) { Text(stringResource(R.string.drop_ok), fontSize = 14.sp, maxLines = 1) }
        }
    }
}

/** Крупная кнопка-значок без слов; название читается вслух и показывается по долгому нажатию, у фокуса пульта рамка. */
@Composable
internal fun DropIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, color: Color, h: androidx.compose.ui.unit.Dp, modifier: Modifier, shortLabel: String? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val sh = RoundedCornerShape(16.dp)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.foundation.layout.Box(
        modifier.height(h).clip(sh).background(color.copy(alpha = .16f))
            .then(if (focused) Modifier.border(3.dp, color, sh) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(onClick = onClick, onLongClick = { android.widget.Toast.makeText(ctx, name, android.widget.Toast.LENGTH_SHORT).show() })
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Icon(icon, null, Modifier.size(if (Marq.tv) 40.dp else 30.dp), tint = color)
            if (shortLabel != null) Text(shortLabel, fontSize = if (Marq.tv) 13.sp else 11.sp, lineHeight = if (Marq.tv) 16.sp else 13.sp, maxLines = 1, softWrap = false, fontFamily = UiPrefs.listFont, fontWeight = FontWeight.Medium, color = color)
            else if (Marq.tv && focused) Text(name, fontSize = 13.sp, color = color)
        }
    }
}

@Composable
private fun PathBlock(label: String, path: String) {
    Column {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(shortMiddle(path, 110), fontSize = 13.sp, lineHeight = 15.sp, fontFamily = UiPrefs.listFont, maxLines = 3)
    }
}

/** Длинный путь сокращается серединой: начало и конец видны. */
fun shortMiddle(s: String, max: Int): String = if (s.length <= max) s else s.take(max / 3) + "…" + s.takeLast(max - max / 3 - 1)

/** «N элементов» с правильным окончанием на языке приложения. */
fun fmItemsText(n: Int): String {
    val lang = runCatching { Fm.sctx.resources.configuration.locales[0].language }.getOrDefault("ru")
    val word = when (lang) {
        "ru" -> if (n % 10 == 1 && n % 100 != 11) "элемент" else if (n % 10 in 2..4 && n % 100 !in 12..14) "элемента" else "элементов"
        "uk" -> if (n % 10 == 1 && n % 100 != 11) "елемент" else if (n % 10 in 2..4 && n % 100 !in 12..14) "елементи" else "елементів"
        else -> if (n == 1) "item" else "items"
    }
    return "$n $word"
}

