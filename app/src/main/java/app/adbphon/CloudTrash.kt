package app.adbphon

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Корзина облака. Три вида:
 *  1) NATIVE: родная корзина Google Диска (список «только удалённые», восстановление и очистка самим облаком);
 *  2) OWN: корзина программы у остальных облаков. Удаление = серверный перенос (moveto) в скрытую папку [DIR] в корне облака;
 *     для каждого удалённого элемента своя подпапка «ГГГГММДД-ЧЧММСС-n» с самим элементом под прежним именем и файлом meta.json
 *     {origPath, name, isDir, size, deletedAt, stored}. Показ, восстановление и удаление навсегда делает программа;
 *  3) NONE: облака без корня для папок (хранилища бакетов, Google Фото и т. п.): корзины нет, удаление сразу и навсегда.
 */
object CloudTrash {
    const val DIR = ".adbphon-trash"
    const val NATIVE = 0; const val OWN = 1; const val NONE = 2
    private val noTrash = setOf("s3", "b2", "azureblob", "google cloud storage", "storj", "google photos", "seafile", "http")

    fun mode(type: String?): Int = when {
        type == null -> NONE
        type == "drive" -> NATIVE
        type in noTrash -> NONE
        else -> OWN
    }

    fun supported(type: String?) = mode(type) != NONE

    /** Ключ «удалить навсегда» для типа облака; null: такого ключа у облака нет. */
    fun hardFlag(type: String?): String? = when (type) {
        "drive" -> "--drive-use-trash=false"
        "mega" -> "--mega-hard-delete"
        "onedrive" -> "--onedrive-hard-delete"
        "yandex" -> "--yandex-hard-delete"
        else -> null
    }

    /** Элемент корзины. NATIVE: id и origPath = путь файла; OWN: id = подпапка, origPath = прежний путь, stored = путь элемента внутри подпапки (пусто: у подпапки нет meta.json). */
    class TItem(val id: String, val name: String, val origPath: String, val isDir: Boolean, val size: Long, val deletedAt: Long, val stored: String)
    class Listing(val items: List<TItem>, val total: Long)

    /** Итог корзины для строки «Корзина» в дереве и панели: count < 0: узнать не удалось. */
    class Sum(val count: Int, val total: Long)
    /** Сколько ВСЕГО элементов в родной корзине, если их больше показываемых 300 (10.11.2026: счётчик вечно показывал «300», а удаление шло, но видно этого не было). */
    val fullCount = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Сколько элементов родной корзины загружается в списки (10.11.2026: было 300, а «Отметить всё» брало ещё меньше, 200 строк дерева: теперь все до 3000). */
    const val TRASH_CAP = 3000
    /** Итог корзины: если элементов больше показываемых, число полное, а размер неизвестен (-1). */
    fun sumOf(remote: String, items: List<TItem>, total: Long): Sum { val full = fullCount[remote]; return if (full != null && full > items.size) Sum(full, -1) else Sum(items.size, total) }
    val sums = androidx.compose.runtime.mutableStateMapOf<String, Sum>()
    private val sumBusy = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Элементы корзины в дереве «домика» по id узла «Ti|облако|подпапка». */
    val tkItems = HashMap<String, TItem>()
    /** Счётчик: любое изменение корзины или содержимого облака; дерево перечитывает сброшенные узлы. */
    var ver by androidx.compose.runtime.mutableStateOf(0)

    /** Корзина или облако изменились: сбросить итог, список корзины и открытые папки облака в дереве. */
    fun invalidate(host: String, dropSum: Boolean = true) {
        if (dropSum) sums.remove(host)
        FmRootCfg.kids.remove("T|" + host); FmRootCfg.kidErr.remove("T|" + host)
        FmRootCfg.kids.keys.filter { it.startsWith("N|") && it.split('|', limit = 5).let { a -> a.size >= 4 && NetTrash.rid(NetConn(a[1], a[2], 0, a[3], "")) == host } }.forEach { FmRootCfg.kids.remove(it) }
        ver++
    }

    /** «Восстановить» из дерева (без окна). */
    fun restoreFromTree(ctx: Context, c: NetConn, ti: TItem) {
        Fm.scope.launch {
            val rid = NetTrash.rid(c)
            val e = try { NetTrash.ensure(ctx, c); if (mode(typeOf(ctx, rid)) == OWN) restoreOwn(ctx, rid, ti) else restore(ctx, rid, ti.origPath) } catch (x: Throwable) { x.message ?: x.javaClass.simpleName }
            if (e != null) Fm.say(e) else { Fm.say(ctx.getString(R.string.tr_restored)); invalidate(rid); Fm.reloadCloud(rid) }
        }
    }

    fun sumText(s: Sum?): String = when {
        s == null -> "…"
        s.count < 0 -> ""
        s.count == 0 -> "0"
        s.total >= 0 -> s.count.toString() + "  ·  " + fmSize(s.total)
        else -> s.count.toString()
    }

    /** Ленивый подсчёт «N · размер»: один запрос под общим замком облака, ошибка = «неизвестно» (без повторов). */
    suspend fun refreshSum(ctx: Context, remote: String) {
        if (!NetTrash.isRid(remote) && !CloudModule.ok(ctx)) return
        if (!sumBusy.add(remote)) return
        try {
            val s = try {
                when (mode(typeOf(ctx, remote))) {
                    OWN -> { val sc = scanOwn(ctx, remote); Sum(sc.ids.size, sc.total) }
                    NATIVE -> { val l = list(ctx, remote); sumOf(remote, l, l.sumOf { it.size }) }
                    else -> Sum(-1, 0)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { Sum(-1, 0) }
            sums[remote] = s
        } finally { sumBusy.remove(remote) }
    }

    private val seq = java.util.concurrent.atomic.AtomicInteger(0)
    private fun spec(remote: String, p: String) = "$remote:" + p.trimStart('/')
    private fun typeOf(ctx: Context, remote: String): String? = if (NetTrash.isRid(remote)) "net" else CloudIcons.typeOfRemote(ctx, remote)
    /** Тип для корзины по имени источника (облако: тип облака; сеть: «net»). */
    fun typeOfRid(ctx: Context, remote: String): String? = typeOf(ctx, remote)
    /** Тип для корзины у подключения: облако или сетевой источник с корзиной; null: корзины нет. */
    fun typeFor(ctx: Context, c: NetConn): String? = if (c.kind == "cloud") CloudIcons.typeOf(ctx, c) else if (NetTrash.supported(c)) "net" else null
    /** Удалить навсегда файл или папку по пути внутри источника. */
    suspend fun purgeRel(ctx: Context, remote: String, rel: String, isDir: Boolean): String? {
        val r = lrun(ctx, remote, listOf(if (isDir) "purge" else "deletefile", spec(remote, rel)) + Rclone.common, 60 * 60_000L)
        return if (r.ok || notFound(r)) null else errText(ctx, remote, r)
    }
    private fun errText(ctx: Context, remote: String, r: Rclone.Result): String = CloudErr.fromText(ctx, r.err + "\n" + r.out, typeOf(ctx, remote)).message ?: Rclone.reason(r)
    private fun notFound(r: Rclone.Result) = r.code == 3 || r.code == 4 || (r.err + r.out).contains("not found", true)

    /** Любая команда к облаку идёт под общим замком облака: MEGA при параллельных входах отвечает ошибками, а перенос и список одновременно рвут друг друга. */
    private suspend fun lrun(ctx: Context, remote: String, args: List<String>, timeoutMs: Long = 120_000): Rclone.Result =
        CloudFs.lockOf(remote).withLock { Rclone.run(ctx, args, timeoutMs) }

    // ---------- родная корзина (Google Диск) ----------

    suspend fun list(ctx: Context, remote: String): List<TItem> {
        val r = CloudFs.lockOf(remote).withLock { Rclone.run(ctx, listOf("lsjson", "$remote:", "-R", "--files-only", "--drive-trashed-only", "--no-mimetype", "--no-modtime") + Rclone.common, 150_000) }
        if (!r.ok) throw CloudErr.fromText(ctx, r.err + "\n" + r.out, typeOf(ctx, remote))
        val a = JSONArray(r.out.trim().ifEmpty { "[]" })
        if (a.length() > TRASH_CAP) fullCount[remote] = a.length() else fullCount.remove(remote)
        return (0 until minOf(a.length(), TRASH_CAP)).map { a.getJSONObject(it) }.map { o -> TItem(o.getString("Path"), o.getString("Name"), o.getString("Path"), false, o.optLong("Size", 0).coerceAtLeast(0), 0, "") }
    }

    /**
     * Восстановить ОДИН файл из корзины Google Диска. Родная команда «вернуть из корзины» работает на всю папку сразу (вернула бы и чужие
     * удалённые файлы той же папки), поэтому файл копируется на самом сервере по его номеру в прежний путь (при занятом имени с «(восстановлено)»),
     * копия проверяется, и только потом исходный файл убирается из корзины. Любая неясность: файл остаётся в корзине.
     */
    suspend fun restore(ctx: Context, remote: String, path: String): String? {
        val fail = ctx.getString(R.string.tr_restore_fail)
        return try {
            val orig = path.trim('/')
            val r = lrun(ctx, remote, listOf("lsjson", "$remote:", "-R", "--files-only", "--drive-trashed-only", "--no-mimetype", "--no-modtime") + Rclone.common, 150_000)
            if (!r.ok) return errText(ctx, remote, r)
            val a = JSONArray(r.out.trim().ifEmpty { "[]" })
            var id: String? = null; var size = 0L
            for (k in 0 until a.length()) { val o = a.getJSONObject(k); if (o.getString("Path") == orig) { id = o.optString("ID").ifEmpty { null }; size = o.optLong("Size", 0); break } }
            if (id == null) { AppLog.note("корзина: восстановление $orig: файла в корзине нет"); return fail }
            val parent = orig.substringBeforeLast('/', "")
            val tag = ctx.getString(R.string.tr_restored_tag)
            var target = orig; var n = 0
            while (exists(ctx, remote, target)) {
                n++
                if (n > 50) return fail
                target = (if (parent.isEmpty()) "" else "$parent/") + renamed(orig.substringAfterLast('/'), tag, n, false)
            }
            val c = lrun(ctx, remote, listOf("backend", "copyid", "$remote:", id, spec(remote, target)) + Rclone.common, 60 * 60_000L)
            AppLog.note("корзина: восстановление $orig в $target (копия по номеру) код ${c.code}")
            if (!c.ok) return errText(ctx, remote, c)
            if (!visible(ctx, remote, target)) { AppLog.note("корзина: копия $target не видна, исходный файл оставлен в корзине"); return fail }
            purgeNative(ctx, remote, TItem(orig, orig.substringAfterLast('/'), orig, false, size, 0, ""))?.let { AppLog.note("корзина: копия есть, убрать исходный из корзины не удалось: $it") }
            null
        } catch (e: Throwable) { AppLog.note("корзина: восстановление не удалось: ${e.javaClass.simpleName}: ${e.message}"); fail }
    }

    suspend fun clean(ctx: Context, remote: String): String? {
        val r = lrun(ctx, remote, listOf("cleanup", "$remote:") + Rclone.common, 30 * 60_000L)
        return if (r.ok) null else errText(ctx, remote, r)
    }

    // ---------- корзина программы (остальные облака) ----------

    /** Удаление «в корзину программы»: сначала meta.json, затем одна команда moveto на элемент (на папку целиком). Не удалось: ничего не удалено, возвращается текст ошибки. */
    suspend fun moveIn(ctx: Context, remote: String, path: String, isDir: Boolean, knownSize: Long): String? {
        val clean = path.trim('/')
        if (clean.isEmpty()) return ctx.getString(R.string.tr_move_fail, "/")
        val name = clean.substringAfterLast('/')
        val id = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + "-" + seq.incrementAndGet()
        val size = if (isDir) -1L else knownSize // размер папки считается при показе корзины (лишний процесс при удалении = лишний вход в облако)
        val stored = if (name == "meta.json") "_/meta.json" else name
        val meta = JSONObject().put("origPath", clean).put("name", name).put("isDir", isDir).put("size", size).put("deletedAt", System.currentTimeMillis()).put("stored", stored)
        val tmp = File(ctx.cacheDir, "trashmeta_${System.nanoTime()}.json")
        try {
            tmp.writeText(meta.toString())
            val r1 = lrun(ctx, remote, listOf("copyto", tmp.absolutePath, spec(remote, "$DIR/$id/meta.json")) + Rclone.common, 120_000)
            if (!r1.ok) return ctx.getString(R.string.tr_move_fail, errText(ctx, remote, r1))
        } catch (e: Throwable) {
            return ctx.getString(R.string.tr_move_fail, e.message ?: e.javaClass.simpleName)
        } finally { tmp.delete() }
        val r2 = try {
            lrun(ctx, remote, listOf("moveto", spec(remote, clean), spec(remote, "$DIR/$id/$stored")) + Rclone.common, 60 * 60_000L)
        } catch (e: Throwable) {
            AppLog.note("корзина: перенос $clean в $DIR/$id не удался: ${e.javaClass.simpleName}")
            runCatching { dropMeta(ctx, remote, id) }
            return ctx.getString(R.string.tr_move_fail, e.message ?: e.javaClass.simpleName)
        }
        if (!r2.ok) {
            AppLog.note("корзина: moveto $clean в $DIR/$id код ${r2.code}: ${Rclone.reason(r2)}")
            runCatching { dropMeta(ctx, remote, id) }
            return ctx.getString(R.string.tr_move_fail, errText(ctx, remote, r2))
        }
        AppLog.note("корзина: $clean перенесён в $DIR/$id (код 0)")
        return null
    }

    class Scan(val ids: LinkedHashSet<String>, val sizes: HashMap<String, Long>, val total: Long)

    /** Один список всего содержимого корзины: подпапки (по одной на элемент), размеры по ним и общий итог. */
    suspend fun scanOwn(ctx: Context, remote: String): Scan {
        val r = CloudFs.lockOf(remote).withLock { Rclone.run(ctx, listOf("lsjson", spec(remote, DIR), "-R", "--no-mimetype", "--no-modtime") + Rclone.common, 300_000) }
        if (!r.ok) { if (notFound(r)) return Scan(LinkedHashSet(), HashMap(), 0); throw CloudErr.fromText(ctx, r.err + "\n" + r.out, typeOf(ctx, remote)) }
        val a = JSONArray(r.out.trim().ifEmpty { "[]" })
        val ids = LinkedHashSet<String>(); val sizes = HashMap<String, Long>(); var total = 0L
        for (k in 0 until a.length()) {
            val o = a.getJSONObject(k); val pth = o.getString("Path"); val id = pth.substringBefore('/')
            if (o.optBoolean("IsDir")) { if ('/' !in pth) ids.add(id); continue }
            ids.add(id)
            val sz = o.optLong("Size", 0).coerceAtLeast(0)
            total += sz
            if (pth != "$id/meta.json") sizes[id] = (sizes[id] ?: 0L) + sz
        }
        return Scan(ids, sizes, total)
    }

    suspend fun listOwn(ctx: Context, remote: String): Listing {
        val sc = scanOwn(ctx, remote)
        val ids = sc.ids; val sizes = sc.sizes; val total = sc.total
        if (ids.isEmpty()) return Listing(emptyList(), 0)
        // все meta.json одной командой, а не по процессу на элемент
        val tmp = File(ctx.cacheDir, "trashmetas_${System.nanoTime()}")
        val items = try {
            val c = CloudFs.lockOf(remote).withLock { Rclone.run(ctx, listOf("copy", spec(remote, DIR), tmp.absolutePath, "--include", "/*/meta.json") + Rclone.common, 300_000) }
            if (!c.ok) throw CloudErr.fromText(ctx, c.err + "\n" + c.out, typeOf(ctx, remote))
            ids.map { id ->
                val o = runCatching { JSONObject(File(tmp, "$id/meta.json").readText()) }.getOrNull()
                if (o == null) TItem(id, id, "", true, sizes[id] ?: -1L, 0, "")
                else {
                    val isDir = o.optBoolean("isDir")
                    TItem(id, o.optString("name", id), o.optString("origPath"), isDir, if (isDir) (sizes[id] ?: 0L) else o.optLong("size", sizes[id] ?: -1L), o.optLong("deletedAt", 0), o.optString("stored", o.optString("name", id)))
                }
            }
        } finally { tmp.deleteRecursively() }
        return Listing(items.sortedByDescending { it.deletedAt }, total)
    }

    private suspend fun exists(ctx: Context, remote: String, path: String): Boolean {
        val r = lrun(ctx, remote, listOf("lsjson", spec(remote, path), "--stat", "--no-mimetype", "--no-modtime") + Rclone.common, 120_000)
        if (r.ok) return true
        if (notFound(r)) return false
        throw CloudErr.fromText(ctx, r.err + "\n" + r.out, typeOf(ctx, remote))
    }

    private fun renamed(name: String, tag: String, n: Int, isDir: Boolean): String {
        val t = if (n <= 1) tag else tag.dropLast(1) + " $n" + tag.takeLast(1)
        val dot = name.lastIndexOf('.')
        return if (isDir || dot <= 0) "$name $t" else name.substring(0, dot) + " $t" + name.substring(dot)
    }

    /** Убрать только описание элемента и пустую подпапку (содержимое подпапки не трогается: не пустая подпапка остаётся). */
    private suspend fun dropMeta(ctx: Context, remote: String, id: String) {
        val flag = listOfNotNull(hardFlag(typeOf(ctx, remote)))
        lrun(ctx, remote, listOf("deletefile", spec(remote, "$DIR/$id/meta.json")) + flag + Rclone.common, 120_000)
        lrun(ctx, remote, listOf("rmdir", spec(remote, "$DIR/$id")) + Rclone.common, 120_000)
    }

    private suspend fun visible(ctx: Context, remote: String, path: String, tries: Int = 3): Boolean {
        for (k in 1..tries) {
            val r = lrun(ctx, remote, listOf("lsjson", spec(remote, path), "--stat", "--no-mimetype", "--no-modtime") + Rclone.common, 120_000)
            if (r.ok) return true
            if (k < tries) kotlinx.coroutines.delay(2_000)
        }
        return false
    }

    /**
     * Безопасное возвращение в прежний путь: 1) убедиться, что элемент ещё лежит в корзине; 2) найти свободное имя (занято: «(восстановлено)»);
     * 3) перенести; 4) убедиться, что элемент виден по новому пути; 5) только потом убрать описание (meta.json) и пустую подпапку.
     * Подпапка корзины целиком не удаляется никогда: при любой неясности остаётся всё как есть и возвращается ошибка.
     */
    suspend fun restoreOwn(ctx: Context, remote: String, it: TItem): String? {
        val fail = ctx.getString(R.string.tr_restore_fail)
        return try {
            val src = if (it.stored.isEmpty()) "$DIR/${it.id}" else "$DIR/${it.id}/${it.stored}"
            val orig = it.origPath.trim('/').ifEmpty { it.name }
            val parent = orig.substringBeforeLast('/', "")
            val tag = ctx.getString(R.string.tr_restored_tag)
            if (!exists(ctx, remote, src)) { AppLog.note("корзина: восстановление $orig: в корзине $src не найден"); return fail }
            var target = orig; var n = 0
            while (exists(ctx, remote, target)) {
                n++
                if (n > 50) return fail
                target = (if (parent.isEmpty()) "" else "$parent/") + renamed(orig.substringAfterLast('/'), tag, n, it.isDir)
            }
            val r = lrun(ctx, remote, listOf("moveto", spec(remote, src), spec(remote, target)) + Rclone.common, 60 * 60_000L)
            AppLog.note("корзина: восстановление $src в $target код ${r.code}")
            if (!r.ok) return errText(ctx, remote, r)
            if (!visible(ctx, remote, target)) {
                AppLog.note("корзина: после переноса $target не виден, ничего не удалено (подпапка ${it.id} оставлена)")
                return fail
            }
            if (it.stored.isNotEmpty()) runCatching { dropMeta(ctx, remote, it.id) }
            null
        } catch (e: Throwable) { AppLog.note("корзина: восстановление не удалось: ${e.javaClass.simpleName}: ${e.message}"); fail }
    }

    private suspend fun purgePath(ctx: Context, remote: String, path: String): String? {
        val r = lrun(ctx, remote, listOf("purge", spec(remote, path)) + listOfNotNull(hardFlag(typeOf(ctx, remote))) + Rclone.common, 60 * 60_000L)
        return if (r.ok || notFound(r)) null else errText(ctx, remote, r)
    }

    /** Один элемент корзины: навсегда. */
    suspend fun purgeItem(ctx: Context, remote: String, it: TItem): String? = try { AppLog.note("корзина: удаление навсегда $DIR/${it.id} (${it.origPath})"); purgePath(ctx, remote, "$DIR/${it.id}") } catch (e: Throwable) { e.message ?: e.javaClass.simpleName }

    /**
     * Удалить навсегда один элемент родной корзины Google Диска: команда к уже удалённому файлу или папке (флаг «только удалённые»
     * находит его в корзине, а «не в корзину» стирает окончательно).
     */
    suspend fun purgeNative(ctx: Context, remote: String, it: TItem): String? = try {
        AppLog.note("корзина: удаление навсегда из родной корзины ${it.origPath}")
        val r = lrun(ctx, remote, listOf(if (it.isDir) "purge" else "deletefile", spec(remote, it.origPath), "--drive-trashed-only", "--drive-use-trash=false") + Rclone.common, 60 * 60_000L)
        if (r.ok || notFound(r)) null else errText(ctx, remote, r)
    } catch (e: Throwable) { e.message ?: e.javaClass.simpleName }

    /**
     * Несколько ФАЙЛОВ родной корзины Google Диска за один запуск движка (10.11.2026: по одному элементу выходило около 5 с на файл, 200 штук около 17 минут):
     * список путей кладётся во временный файл, команда «delete --files-from» стирает именно их навсегда (флаги те же, что у одиночного удаления).
     */
    suspend fun purgeNativeBatch(ctx: Context, remote: String, items: List<TItem>): String? = try {
        AppLog.note("корзина: пакетное удаление навсегда из родной корзины: ${items.size} шт.")
        val f = java.io.File(ctx.cacheDir, "trash_batch_" + System.nanoTime() + ".txt")
        f.writeText(items.joinToString("\n") { it.origPath.trimStart('/') })
        try {
            val r = lrun(ctx, remote, listOf("delete", "$remote:", "--files-from", f.absolutePath, "--drive-trashed-only", "--drive-use-trash=false") + Rclone.common, 60 * 60_000L)
            AppLog.note("корзина: пакет код ${r.code}: " + (r.err + " " + r.out).replace('\n', ' ').take(500))
            // «не найдено» здесь НЕ считается успехом (10.11.2026: так пакет «удалял» молча, ничего не стерев); итог проверяется перечитыванием корзины
            if (r.ok) null else errText(ctx, remote, r)
        } finally { f.delete() }
    } catch (e: Throwable) { e.message ?: e.javaClass.simpleName }

    /** Один элемент корзины: навсегда, для родной корзины облака и для корзины программы. */
    suspend fun purgeAny(ctx: Context, remote: String, it: TItem): String? = if (mode(typeOf(ctx, remote)) == NATIVE) purgeNative(ctx, remote, it) else purgeItem(ctx, remote, it)

    /** Вся корзина программы: навсегда. */
    suspend fun cleanOwn(ctx: Context, remote: String): String? = try { purgePath(ctx, remote, DIR) } catch (e: Throwable) { e.message ?: e.javaClass.simpleName }
}

/**
 * Окно «Корзина»: у Google Диска родная корзина облака (список, «Восстановить», очистка), у остальных облаков корзина программы
 * (список с прежней папкой, размером и датой; на каждом элементе «Восстановить» и «Удалить навсегда»). Очистка и удаление навсегда всегда с подтверждением.
 */
@Composable
fun CloudTrashDlg(c: NetConn, close: () -> Unit, initConfirm: String? = null) {
    val ctx = LocalContext.current
    val rid = NetTrash.rid(c)
    val type = remember(c) { CloudTrash.typeFor(ctx, c) }
    val own = CloudTrash.mode(type) == CloudTrash.OWN
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(if (initConfirm != null && FmDel.needAsk(true)) initConfirm else null) } // "": вся корзина; иначе id элемента
    var busy by remember { mutableStateOf(false) }
    var total by remember { mutableStateOf(-1L) }
    val items = remember { mutableStateListOf<CloudTrash.TItem>() }
    fun reload() {
        loading = true
        scope.launch {
            if (c.kind != "cloud") runCatching { NetTrash.ensure(ctx, c) }
            val r = runCatching { if (own) CloudTrash.listOwn(ctx, rid) else CloudTrash.Listing(CloudTrash.list(ctx, rid), -1L) }
            items.clear(); r.getOrNull()?.let { items.addAll(it.items); total = it.total; CloudTrash.sums[rid] = CloudTrash.sumOf(rid, it.items, if (it.total >= 0) it.total else it.items.sumOf { x -> maxOf(x.size, 0L) }) }
            msg = r.exceptionOrNull()?.message.orEmpty()
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }
    var autoDone by remember { mutableStateOf(false) }
    val dateFmt = remember { java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()) }
    fun runConfirm(cf: String) {
        if (busy) return
        busy = true
        scope.launch {
            val e = when {
                cf.isEmpty() -> if (own) CloudTrash.cleanOwn(ctx, rid) else CloudTrash.clean(ctx, rid)
                else -> items.firstOrNull { x -> x.id == cf }?.let { x -> CloudTrash.purgeAny(ctx, rid, x) }
            }
            busy = false; confirm = null
            if (e != null) msg = e else { msg = ""; Fm.say(ctx.getString(if (cf.isEmpty()) R.string.tr_cleaned else R.string.tr_deleted_forever)); CloudTrash.invalidate(rid, false); reload() }
        }
    }
    /** Спрашивать ли про удаление навсегда и очистку, решает настройка «Подтверждение удаления». */
    fun ask(cf: String) { if (FmDel.needAsk(true)) confirm = cf else runConfirm(cf) }
    // окно открыто сразу с «очистить» или «удалить навсегда», а подтверждение выключено: выполнить, как только список загружен
    LaunchedEffect(loading) { if (!loading && initConfirm != null && !FmDel.needAsk(true) && !autoDone) { autoDone = true; runConfirm(initConfirm) } }
    LAlertDialog(
        wide = true, onDismissRequest = close,
        title = { Text(stringResource(R.string.tr_title, if (c.kind == "cloud") CloudErr.providerName(ctx, type) else c.title)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // шапка не прокручивается: итог и «Очистить корзину» всегда на виду, даже в альбомной ориентации на низком экране
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (loading) stringResource(R.string.tr_counting) else if (msg.isNotEmpty()) "" else if (items.isEmpty()) stringResource(R.string.tr_empty) else if (total >= 0) stringResource(R.string.tr_total, items.size, fmSize(total)) else stringResource(R.string.tr_count, items.size),
                        Modifier.weight(1f), fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                    TextButton({ ask("") }, enabled = !busy && !loading && items.isNotEmpty() && confirm == null) { Text(stringResource(R.string.tr_clean), fontSize = 13.sp) }
                }
                if (loading || busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text(stringResource(R.string.rt_loading), Modifier.padding(start = 8.dp), fontSize = 13.sp) }
                if (msg.isNotEmpty()) Text(msg, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                confirm?.let { cf ->
                    Text(if (cf.isEmpty()) stringResource(R.string.tr_confirm) else stringResource(R.string.tr_confirm_one, items.firstOrNull { x -> x.id == cf }?.name.orEmpty()), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
                Column(Modifier.weight(1f, fill = false).barScroll(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items.forEach { it ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(it.name, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                val dir = "/" + it.origPath.trim('/').substringBeforeLast('/', "")
                                val sz = if (it.size >= 0) fmSize(it.size) else if (it.isDir) stringResource(R.string.cf_folder) else ""
                                val whenTxt = if (it.deletedAt > 0) dateFmt.format(java.util.Date(it.deletedAt)) else ""
                                Text(listOf(dir, sz, whenTxt).filter { s -> s.isNotEmpty() }.joinToString("  ·  "), fontSize = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                            }
                            IconButton({
                                if (busy) return@IconButton
                                busy = true
                                scope.launch {
                                    val e = if (own) CloudTrash.restoreOwn(ctx, rid, it) else CloudTrash.restore(ctx, rid, it.origPath)
                                    busy = false
                                    if (e != null) msg = e else { msg = ""; Fm.say(ctx.getString(R.string.tr_restored)); Fm.reloadCloud(rid); CloudTrash.invalidate(rid, false); reload() }
                                }
                            }, enabled = !busy) { Icon(Icons.Filled.Restore, stringResource(R.string.tr_restore), tint = MaterialTheme.colorScheme.primary) }
                            IconButton({ if (!busy) ask(it.id) }, enabled = !busy) { Icon(Icons.Filled.DeleteForever, stringResource(R.string.tr_perm), tint = Color(0xFFC62828)) }
                        }
                    }
                    Text(stringResource(if (own) R.string.tr_note_own else R.string.tr_note_show), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
                }
            }
        },
        confirmButton = {
            if (confirm != null) TextButton({ runConfirm(confirm ?: return@TextButton) }, enabled = !busy && !loading) { Text(stringResource(if (confirm == "") R.string.tr_clean_yes else R.string.tr_perm_yes), color = Color(0xFFC62828)) }
        },
        dismissButton = { TextButton(if (confirm != null) ({ confirm = null }) else close) { Text(stringResource(if (confirm != null) R.string.btn_cancel else R.string.btn_close)) } },
    )
}
