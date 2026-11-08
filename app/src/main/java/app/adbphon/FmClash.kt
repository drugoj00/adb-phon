package app.adbphon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Описание записи в папке для проверки совпадений: папка ли, размер и время изменения (мс, 0 если неизвестно). */
data class CNode(val isDir: Boolean, val size: Long, val mtime: Long)

/** Доступ к дереву одной стороны (источник или приёмник). Совпадение имён решает файловая система: на FAT/exFAT регистр не важен, на ext4 важен. */
interface CTree {
    suspend fun stat(path: String): CNode?
    suspend fun list(path: String): List<String>
}

/** Решение по совпавшему имени. */
enum class CAct { REPLACE, SKIP, RENAME }

/** Совпадение верхнего уровня: имя [rel], что копируем ([src]) и что уже лежит ([dst]). */
class Clash(val rel: String, val src: CNode, val dst: CNode) {
    val merge: Boolean get() = src.isDir && dst.isDir
    val typeDiffers: Boolean get() = src.isDir != dst.isDir
}

/** Итог проверки: совпадения верхнего уровня и число совпавших файлов внутри сливаемых папок. */
class ClashReport(val top: List<Clash>, val inner: Int)

/** Одна операция плана: что взять в источнике ([from]) и куда положить в приёмнике ([to]); [deleteFirst]: сначала убрать существующее другого типа. */
class CopyOp(val from: String, val to: String, val isDir: Boolean, val deleteFirst: Boolean = false)

/** План: операции и верхние папки, которые сливались (после переноса пустые из них убираются). */
class CopyPlan(val ops: List<CopyOp>, val merged: List<String>)

object ClashLogic {
    const val LIMIT = 20000
    fun join(d: String, n: String) = d.trimEnd('/') + "/" + n

    /** Что уже есть в приёмнике: верхний уровень и, для папок с одним именем (слияние), совпавшие файлы внутри. */
    suspend fun scan(src: CTree, srcDir: String, dst: CTree, dstDir: String, names: List<String>): ClashReport {
        val top = ArrayList<Clash>(); var inner = 0; var seen = 0
        suspend fun walk(sp: String, dp: String): Unit {
            for (c in src.list(sp)) {
                if (++seen > LIMIT) return
                val sc = src.stat(join(sp, c)) ?: continue
                val dc = dst.stat(join(dp, c)) ?: continue
                if (sc.isDir && dc.isDir) walk(join(sp, c), join(dp, c)) else inner++
            }
        }
        for (n in names) {
            val s = src.stat(join(srcDir, n)) ?: CNode(false, -1, 0)
            val d = dst.stat(join(dstDir, n)) ?: continue
            top += Clash(n, s, d)
            if (s.isDir && d.isDir) walk(join(srcDir, n), join(dstDir, n))
        }
        return ClashReport(top, inner)
    }

    /**
     * План операций по решениям [acts] (нет решения у совпавшего имени: пропустить, это безопасно).
     * Папки с одним именем сливаются: «заменить» перезаписывает совпавшие файлы, «пропустить» оставляет существующие и добавляет недостающие.
     */
    suspend fun plan(src: CTree, srcDir: String, dst: CTree, dstDir: String, names: List<String>, acts: Map<String, CAct>, uniq: suspend (String, Boolean) -> String): CopyPlan {
        val ops = ArrayList<CopyOp>(); val merged = ArrayList<String>()
        suspend fun expand(rel: String, replace: Boolean): Unit {
            for (c in src.list(join(srcDir, rel))) {
                val cr = join(rel, c)
                val sc = src.stat(join(srcDir, cr)) ?: continue
                val dc = dst.stat(join(dstDir, cr))
                when {
                    dc == null -> ops += CopyOp(cr, cr, sc.isDir)
                    sc.isDir && dc.isDir -> expand(cr, replace)
                    replace -> ops += CopyOp(cr, cr, sc.isDir, deleteFirst = sc.isDir != dc.isDir)
                }
            }
        }
        for (n in names) {
            val s = src.stat(join(srcDir, n)) ?: CNode(false, -1, 0)
            val d = dst.stat(join(dstDir, n))
            if (d == null) { ops += CopyOp(n, n, s.isDir); continue }
            when (acts[n] ?: CAct.SKIP) {
                CAct.RENAME -> ops += CopyOp(n, uniq(n, s.isDir), s.isDir)
                CAct.SKIP -> if (s.isDir && d.isDir) { merged += n; expand(n, false) }
                CAct.REPLACE -> if (s.isDir && d.isDir) { merged += n; expand(n, true) } else ops += CopyOp(n, n, s.isDir, deleteFirst = s.isDir != d.isDir)
            }
        }
        return CopyPlan(ops, merged)
    }

    /** 1 копируемое новее лежащего, -1 старее, 0 то же время (FAT хранит время с точностью до 2 с) или неизвестно. */
    fun newer(s: CNode, d: CNode): Int = when {
        s.mtime <= 0 || d.mtime <= 0 -> 0
        s.mtime - d.mtime > 2000 -> 1
        d.mtime - s.mtime > 2000 -> -1
        else -> 0
    }

    /** «имя (1).расширение», «имя (2).расширение»…: первое свободное по проверке [taken]. */
    suspend fun freeName(name: String, isDir: Boolean = false, taken: suspend (String) -> Boolean): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 && !isDir }
        val base = if (dot != null) name.substring(0, dot) else name; val ext = if (dot != null) name.substring(dot) else ""
        var i = 1
        while (taken("$base ($i)$ext")) i++
        return "$base ($i)$ext"
    }
}

/** Сторона копирования как дерево: телефон, ADB, открытый архив. На ADB метаданные берутся из списка папки (одна команда на папку). */
class PaneTree(private val p: FmPane) : CTree {
    private val dirs = HashMap<String, Map<String, CNode>>()

    override suspend fun stat(path: String): CNode? = when {
        p.src == "local" -> withContext(Dispatchers.IO) { FmIo.statLocal(path) }
        p.src == "smb" && p.smb is ArchiveFs -> (p.smb as ArchiveFs).let { a ->
            withContext(Dispatchers.IO) { a.nodeOf(path)?.let { n -> CNode(n.isDir, if (n.isDir) 0L else maxOf(n.entry?.size ?: 0L, 0L), n.mtime) } }
        }
        else -> {
            val t = path.trimEnd('/')
            val parent = t.substringBeforeLast('/', "").ifEmpty { "/" }
            val m = dirs.getOrPut(parent) {
                try { FmIo.list(p, parent).associate { it.name.lowercase() to CNode(it.isDir, it.size, parseDate(it.date)) } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { emptyMap() }
            }
            m[t.substringAfterLast('/').lowercase()]
        }
    }

    override suspend fun list(path: String): List<String> = FmIo.list(p, path).map { it.name }

    private fun parseDate(s: String): Long = if (s.isBlank()) 0L else runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(s)?.time ?: 0L }.getOrDefault(0L)
}

// ---------------------------------------------------------------- окна

/** Текст окон копирования и удаления: строка с явной высотой (тема даёт слишком большой межстрочный интервал), узкий шрифт списков по желанию. */
@Composable
private fun T(text: String, size: Float, modifier: Modifier = Modifier, weight: FontWeight? = null, color: Color = Color.Unspecified, align: TextAlign? = null, maxLines: Int = 1, list: Boolean = false) {
    Text(
        text, modifier, fontSize = size.sp, lineHeight = (size * 1.25f).sp, fontWeight = weight, color = color, textAlign = align,
        maxLines = maxLines, softWrap = maxLines > 1, overflow = TextOverflow.Ellipsis, fontFamily = if (list) UiPrefs.listFont else null,
    )
}

/** Общий каркас окон копирования, переноса и удаления: одна ширина, скругление 24, отступы 16, шаг 8/12/16. Сверху ряд кнопок-значков. */
@Composable
fun FmDialogFrame(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val tv = Marq.tv
    LDialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val land = maxWidth > maxHeight
            val w = if (land || tv) minOf(maxWidth * 0.88f, if (tv) 720.dp else 760.dp) else maxWidth - 20.dp
            androidx.compose.material3.Surface(Modifier.width(w).heightIn(max = maxHeight * 0.92f), shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            }
        }
    }
}

/** Фокус на безопасную кнопку: окно появляется не сразу, поэтому несколько попыток (иначе фокус остаётся на первой кнопке, «Заменить» или «Удалить»). */
private suspend fun focusSafely(r: FocusRequester) {
    repeat(12) { kotlinx.coroutines.delay(60); if (runCatching { r.requestFocus() }.isSuccess) return }
}

private val cfGreen = Color(0xFF2E7D32)
private val cfOrange = Color(0xFFE65100)
private val cfGrey = Color(0xFF78909C)

/** Дата без года, если она в этом году (узкая колонка). */
private fun fmtWhen(ms: Long): String {
    if (ms <= 0) return ""
    val cal = java.util.Calendar.getInstance(); val y = cal.get(java.util.Calendar.YEAR); cal.timeInMillis = ms
    return SimpleDateFormat(if (cal.get(java.util.Calendar.YEAR) == y) "dd.MM HH:mm" else "dd.MM.yy", Locale.getDefault()).format(Date(ms))
}

/** Окно «Файл уже существует»: ряд кнопок-значков, заголовок, список совпадений в колонках «Было» и «Стало», «Для всех». */
@Composable
fun ConflictDialog(d: FmDlg.Conflict, close: () -> Unit) {
    val tv = Marq.tv
    val all = d.report.top
    var idx by remember { mutableStateOf(0) }
    var forAll by remember { mutableStateOf(false) }
    val acts = remember { HashMap<String, CAct>() }
    val skipFocus = remember { FocusRequester() }
    val cur = all[idx]
    val newName by produceState(cur.rel, cur.rel) { value = runCatching { Fm.uniqueNameFor(d.dst, cur.rel, cur.src.isDir) }.getOrDefault(cur.rel) }
    fun decide(a: CAct) {
        acts[cur.rel] = a
        if (forAll || idx >= all.lastIndex) {
            for (c in all.drop(idx + 1)) acts[c.rel] = a
            close(); Fm.copyMove(d.move, d.src, d.dst, d.names, acts.toMap())
        } else idx++
    }
    val k = if (tv) 1.2f else 1f
    FmDialogFrame(close) {
        val bs = if (tv) 76.dp else 64.dp
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // ровно три действия: «Переименовать» (безопасное, в фокусе), «Заменить», «Отменить» (всю операцию; ничего ещё не скопировано)
            DropIconButton(Icons.Filled.DriveFileRenameOutline, stringResource(R.string.fm_rename), Color(0xFF0F766E), bs, Modifier.weight(1f).focusRequester(skipFocus), shortLabel = stringResource(R.string.cf_b_rename)) { decide(CAct.RENAME) }
            DropIconButton(Icons.Filled.SwapHoriz, stringResource(R.string.fm_replace), cfOrange, bs, Modifier.weight(1f), shortLabel = stringResource(R.string.cf_b_replace)) { decide(CAct.REPLACE) }
            DropIconButton(Icons.Filled.Close, stringResource(R.string.cf_b_cancel), cfGrey, bs, Modifier.weight(1f), shortLabel = stringResource(R.string.cf_b_cancel), onClick = close)
        }
        LaunchedEffect(idx) { focusSafely(skipFocus) }
        val left = all.size - idx
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            T(
                if (all.size == 1) stringResource(if (cur.dst.isDir) R.string.cf_title_dir else R.string.cf_title_file) else stringResource(R.string.cf_title_many, left),
                19f * k, weight = FontWeight.SemiBold,
            )
            T(stringResource(if (d.move) R.string.cf_sub_move else R.string.cf_sub_copy), 14f * k, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
        }
        val numW = if (tv) 100.dp else 78.dp
        val rows = all.drop(idx).take(5)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                T(stringResource(R.string.cf_was), 11f * k, Modifier.width(numW), FontWeight.Bold, MaterialTheme.colorScheme.primary, TextAlign.End)
                T(stringResource(R.string.cf_now), 11f * k, Modifier.width(numW), FontWeight.Bold, MaterialTheme.colorScheme.primary, TextAlign.End)
                Spacer(Modifier.width(28.dp))
            }
            for ((i, c) in rows.withIndex()) ClashRow(c, i == 0 && all.size > 1, numW, k)
            if (all.size - idx > rows.size) T(stringResource(R.string.fm_drop_and_more, all.size - idx - rows.size), 13f * k, color = MaterialTheme.colorScheme.primary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (d.report.inner > 0) T(stringResource(R.string.cf_inner, d.report.inner), 13f * k, maxLines = 2, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f))
            T(stringResource(R.string.cf_rename_hint, shortMiddle(newName, 40)), 13f * k, list = true, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (rows.any { !it.merge && !it.typeDiffers && ClashLogic.newer(it.src, it.dst) > 0 }) LegendItem(Icons.Filled.ArrowUpward, cfGreen, stringResource(R.string.cf_legend_newer), k)
                if (rows.any { !it.merge && !it.typeDiffers && ClashLogic.newer(it.src, it.dst) < 0 }) LegendItem(Icons.Filled.ArrowDownward, cfOrange, stringResource(R.string.cf_legend_older), k)
                if (rows.any { !it.merge && !it.typeDiffers && it.src.mtime > 0 && it.dst.mtime > 0 && ClashLogic.newer(it.src, it.dst) == 0 }) LegendItem(Icons.Filled.DragHandle, cfGrey, stringResource(R.string.cf_legend_same), k)
                if (rows.any { it.merge }) LegendItem(Icons.Filled.CallMerge, MaterialTheme.colorScheme.primary, stringResource(R.string.cf_legend_merge), k)
                if (rows.any { it.typeDiffers }) LegendItem(Icons.Filled.Warning, cfOrange, stringResource(R.string.cf_legend_type), k)
            }
        }
        if (all.size > 1) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { forAll = !forAll }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(forAll, { forAll = it })
            T(stringResource(R.string.cf_all), 15f * k)
        }
    }
}

@Composable
private fun LegendItem(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, text: String, k: Float) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, null, Modifier.size(14.dp), tint = color)
        T(text, 11f * k, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
    }
}

/** Строка: значок типа, имя одной строкой (сокращение в середине), «Было» и «Стало» (размер и дата, числа по правому краю), пометка новее/старее/слияние. */
@Composable
private fun ClashRow(c: Clash, current: Boolean, numW: androidx.compose.ui.unit.Dp, k: Float) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (current) MaterialTheme.colorScheme.primary.copy(alpha = .10f) else Color.Transparent).padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            if (c.src.isDir) FolderIcon(UiPrefs.folderDefault, false, 24.dp) else FileTypeIcon(c.rel, c.rel.substringAfterLast('.', "").lowercase(), null, 24.dp)
        }
        T(shortMiddle(c.rel, 30), 15f * k, Modifier.weight(1f).padding(start = 6.dp, end = 4.dp), list = true)
        NumCell(c.dst, numW, k)
        NumCell(c.src, numW, k)
        val nw = ClashLogic.newer(c.src, c.dst)
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            when {
                c.merge -> Icon(Icons.Filled.CallMerge, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                c.typeDiffers -> Icon(Icons.Filled.Warning, null, Modifier.size(20.dp), tint = cfOrange)
                c.src.mtime > 0 && c.dst.mtime > 0 && nw > 0 -> Icon(Icons.Filled.ArrowUpward, null, Modifier.size(20.dp), tint = cfGreen)
                c.src.mtime > 0 && c.dst.mtime > 0 && nw < 0 -> Icon(Icons.Filled.ArrowDownward, null, Modifier.size(20.dp), tint = cfOrange)
                c.src.mtime <= 0 || c.dst.mtime <= 0 -> {}   // дата одной из сторон неизвестна (например, на сетевом сервере): значок не показываем
                else -> Icon(Icons.Filled.DragHandle, null, Modifier.size(20.dp), tint = cfGrey)
            }
        }
    }
}

@Composable
private fun NumCell(n: CNode, w: androidx.compose.ui.unit.Dp, k: Float) {
    Column(Modifier.width(w), horizontalAlignment = Alignment.End) {
        T(if (n.isDir) stringResource(R.string.cf_folder) else if (n.size >= 0) fmSize(n.size) else "", 13f * k, align = TextAlign.End, list = true, modifier = Modifier.fillMaxWidth())
        T(fmtWhen(n.mtime), 10f * k, align = TextAlign.End, list = true, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Единое подтверждение удаления: сверху «Удалить» и «Отмена», затем что именно и куда («в корзину» или «навсегда»).
 * Навсегда: красная кнопка и «Отменить нельзя». Для удаления в корзину есть галочка «Больше не спрашивать» (ставит «Только безвозвратное»).
 * Спрашивать ли вообще, решает настройка «Подтверждение удаления» (см. Fm.askDelete).
 */
@Composable
fun DeleteDialog(d: FmDlg.Delete, close: () -> Unit) {
    val tv = Marq.tv
    val k = if (tv) 1.2f else 1f
    val cancelFocus = remember { FocusRequester() }
    val cctx = androidx.compose.ui.platform.LocalContext.current
    val tconn = d.pane.net?.takeIf { d.pane.src == "smb" && d.pane.smb !is ArchiveFs }
    val cloud = tconn != null
    val ctype = if (tconn != null) CloudTrash.typeFor(cctx, tconn) else null
    var perm by remember { androidx.compose.runtime.mutableStateOf(d.force) }
    var never by remember { androidx.compose.runtime.mutableStateOf(false) }
    val red = Color(0xFFC62828)
    val permanent = perm || (d.plan?.perm ?: true)
    FmDialogFrame(close) {
        val bs = if (tv) 76.dp else 64.dp
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DropIconButton(Icons.Filled.DeleteOutline, stringResource(R.string.fm_delete), if (permanent) red else MaterialTheme.colorScheme.primary, bs, Modifier.weight(1f), shortLabel = stringResource(R.string.fm_delete)) {
                close()
                if (never && !permanent) FmDel.setConfirm(cctx, FmDel.PERM)
                Fm.deletePermanent = perm
                Fm.delete(d.pane, d.names).invokeOnCompletion { Fm.deletePermanent = false }
            }
            DropIconButton(Icons.Filled.Close, stringResource(R.string.btn_cancel), cfGrey, bs, Modifier.weight(1f).focusRequester(cancelFocus), shortLabel = stringResource(R.string.btn_cancel), onClick = close)
        }
        LaunchedEffect(Unit) { focusSafely(cancelFocus) }
        val what = if (d.names.size == 1) shortMiddle(d.names[0].substringAfterLast('/'), 26) else stringResource(R.string.dd_items, d.names.size)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            T(stringResource(if (permanent) R.string.dd_t_perm else R.string.dd_t_trash, what), 19f * k, weight = FontWeight.SemiBold, maxLines = 3)
            T(stringResource(if (permanent) R.string.dd_s_perm else R.string.dd_s_trash), 14f * k, color = if (permanent) red else MaterialTheme.colorScheme.onSurface.copy(alpha = .65f), weight = if (permanent) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in d.names.take(5)) {
                val it = d.pane.items.firstOrNull { x -> x.name == n.substringAfterLast('/') }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                        if (it?.isDir == true) FolderIcon(UiPrefs.folderDefault, false, 24.dp) else FileTypeIcon(n, n.substringAfterLast('.', "").lowercase(), null, 24.dp)
                    }
                    T(shortMiddle(n, 34), 15f * k, Modifier.weight(1f).padding(start = 8.dp), list = true)
                    if (it != null && !it.isDir && it.size > 0) T(fmSize(it.size), 13f * k, align = TextAlign.End, list = true, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                }
            }
            if (d.names.size > 5) T(stringResource(R.string.fm_drop_and_more, d.names.size - 5), 13f * k, color = MaterialTheme.colorScheme.primary)
        }
        d.note?.let { T(it, 13f * k, color = red, maxLines = 4) }
        d.plan?.let { pl ->
            if (pl.big > 0) T(stringResource(R.string.dd_big, pl.big), 13f * k, color = cfOrange, maxLines = 3)
            if (pl.none > 0) T(stringResource(R.string.dd_none, pl.none), 13f * k, color = cfOrange, maxLines = 3)
        }
        if (d.names.any { it.startsWith(".") }) T(stringResource(R.string.fm_delete_hidden_warn), 13f * k, color = cfOrange, maxLines = 2)
        if (cloud) {
            val isNet = tconn?.kind != "cloud"
            T(stringResource(when (CloudTrash.mode(ctype)) { CloudTrash.NATIVE -> R.string.tr_del_trash; CloudTrash.OWN -> if (isNet) R.string.nt_del_own else R.string.tr_del_own; else -> if (isNet) R.string.nt_del_none else R.string.tr_del_nonreturn }), 13f * k, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f), maxLines = 5)
            if (CloudTrash.supported(ctype) && !d.force) Row(Modifier.fillMaxWidth().clickable { perm = !perm }, verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(perm, { perm = it })
                T(stringResource(R.string.tr_del_perm), 14f * k, color = if (perm) red else MaterialTheme.colorScheme.onSurface, maxLines = 2)
            }
        }
        if (!permanent) Row(Modifier.fillMaxWidth().clickable { never = !never }, verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(never, { never = it })
            T(stringResource(R.string.dd_never), 14f * k, maxLines = 2)
        }
    }
}
