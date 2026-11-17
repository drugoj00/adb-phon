package app.adbphon

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.withLock

/** Разбор путей съёмных накопителей без Android (проверяется обычными тестами на JVM). */
object SafPaths {
    class Loc(val uuid: String, val rel: String)

    private val rx = Regex("^/storage/([^/]+)(?:/(.*))?$")

    /** «/storage/56A1-D255/DCIM/a.jpg» → том «56A1-D255», путь «DCIM/a.jpg». Внутренняя память и «self» не съёмные: null. */
    fun parse(path: String): Loc? {
        val m = rx.matchEntire(path.trimEnd('/')) ?: return null
        val u = m.groupValues[1]
        if (u == "emulated" || u == "self" || u == "primary") return null
        return Loc(u, m.groupValues[2].trim('/'))
    }

    /** Идентификатор документа внешнего хранилища: «ТОМ:путь внутри тома». */
    fun docId(uuid: String, rel: String) = "$uuid:$rel"

    /** Выбранное дерево — именно корень тома [uuid] («ТОМ:» без пути). */
    fun isVolumeRoot(treeDocId: String?, uuid: String) = treeDocId != null && treeDocId.equals("$uuid:", ignoreCase = true)

    fun parentRel(rel: String) = rel.trim('/').substringBeforeLast('/', "")
    fun nameOf(rel: String) = rel.trim('/').substringAfterLast('/')
    fun pathOf(uuid: String, rel: String) = "/storage/$uuid" + if (rel.isEmpty()) "" else "/" + rel.trim('/')
}

/**
 * 10.2 (код 56): полный доступ к съёмным накопителям (карта памяти, USB) без ADB и root через системную выдачу доступа к папке
 * (Storage Access Framework). Доступ выдаётся один раз на том и запоминается по его UUID. Если обычные файловые операции на томе
 * не проходят (Android 10 и новее без полного доступа к файлам), записи, переименование, удаление, копирование идут через выданное дерево.
 */
object SafAccess {
    private const val AUTH = "com.android.externalstorage.documents"
    private const val DIR_MIME = DocumentsContract.Document.MIME_TYPE_DIR
    private val app: Context get() = FmIo.app
    private val cr get() = app.contentResolver
    private fun prefs() = app.getSharedPreferences("fm", Context.MODE_PRIVATE)

    class Node(val name: String, val dir: Boolean, val size: Long, val mtime: Long)

    /** Список съёмного тома просит выдать доступ к нему (окно «Нужен доступ к карте памяти»). */
    class NeedGrant : Exception()

    // ---------- выданный доступ ----------
    /** Выданное дерево тома [uuid] (с живым сохранённым разрешением) или null. */
    fun tree(uuid: String): Uri? {
        val s = prefs().getString("saf_$uuid", null) ?: return null
        val u = runCatching { Uri.parse(s) }.getOrNull() ?: return null
        val ok = runCatching { cr.persistedUriPermissions.any { it.uri == u && it.isReadPermission && it.isWritePermission } }.getOrDefault(false)
        return if (ok) u else null
    }

    /** Тома, которым выдан доступ. */
    fun granted(): List<String> = prefs().all.keys.filter { it.startsWith("saf_") }.map { it.removePrefix("saf_") }.filter { tree(it) != null }.sorted()

    fun forget(uuid: String) {
        val s = prefs().getString("saf_$uuid", null)
        if (s != null) runCatching { cr.releasePersistableUriPermission(Uri.parse(s), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        prefs().edit().remove("saf_$uuid").apply()
        probe.remove(uuid)
    }

    /** Окно выбора папки системы, открытое на корне тома. */
    fun pickIntent(uuid: String): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(AUTH, SafPaths.docId(uuid, "")))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    }

    const val OK = 0
    const val ERR_NOT_TREE = 1
    const val ERR_NOT_ROOT = 2
    const val ERR_FAIL = 3

    /** Результат выбора: проверяется, что выбран корень именно этого тома, затем разрешение запоминается. Возвращает [OK] или код ошибки. */
    fun accept(uuid: String, uri: Uri): Int {
        if (!runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)) return ERR_NOT_TREE
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        if (!SafPaths.isVolumeRoot(id, uuid)) return ERR_NOT_ROOT
        return try {
            cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs().edit().putString("saf_$uuid", uri.toString()).apply()
            probe.remove(uuid)
            OK
        } catch (e: Throwable) { ERR_FAIL }
    }

    /** Подключённые съёмные тома: UUID и название. */
    fun volumes(ctx: Context): List<Pair<String, String>> = runCatching {
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
        sm.storageVolumes.filter { !it.isPrimary && it.uuid != null && (it.state == android.os.Environment.MEDIA_MOUNTED || it.state == android.os.Environment.MEDIA_MOUNTED_READ_ONLY) }
            .map { it.uuid!! to (it.getDescription(ctx)?.takeIf { d -> d.isNotBlank() } ?: it.uuid!!) }
    }.getOrDefault(emptyList())

    /** Название тома для окон: из списка томов менеджера, иначе его UUID. */
    fun label(uuid: String): String =
        runCatching { Fm.vols.firstOrNull { it.path == "/storage/$uuid" }?.label }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching {
                val sm = app.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
                sm.storageVolumes.firstOrNull { it.uuid.equals(uuid, true) }?.getDescription(app)
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: uuid

    // ---------- когда нужен SAF ----------
    private val probe = ConcurrentHashMap<String, Pair<Long, Boolean>>()

    /** Только для проверки на устройстве: метка `saf_force` рядом с `stands_on` заставляет считать запись файлами запрещённой. */
    fun isForced(): Boolean = runCatching { StandGuard.allowed(app) && File(app.getExternalFilesDir(null), "saf_force").exists() }.getOrDefault(false)

    private fun canWriteFile(uuid: String): Boolean = try {
        val f = File("/storage/$uuid/.adbphon_probe")
        if (f.exists()) f.delete()
        val ok = f.createNewFile(); f.delete(); ok
    } catch (e: Throwable) { false }

    /** Путь на съёмном томе, куда обычные файловые операции не проходят: работа идёт через выданное дерево. */
    fun needs(path: String): Boolean {
        val loc = SafPaths.parse(path) ?: return false
        if (isForced()) return true
        val now = android.os.SystemClock.elapsedRealtime()
        probe[loc.uuid]?.let { if (now - it.first < 30_000L) return it.second }
        val r = !canWriteFile(loc.uuid)
        probe[loc.uuid] = now to r
        return r
    }

    /** Корень SAF-тома можно показать как папку, даже если файловое чтение закрыто. */
    fun covered(path: String): Boolean = SafPaths.parse(path)?.let { tree(it.uuid) != null } == true

    /**
     * Убедиться, что доступ к тому пути [path] выдан; если нет, показать окно «Нужен доступ к карте памяти» и дождаться выбора.
     * true: доступ есть.
     */
    suspend fun ensure(path: String): Boolean {
        val loc = SafPaths.parse(path) ?: return false
        if (tree(loc.uuid) != null) return true
        return asker.ask(loc.uuid)
    }

    private val asker = object {
        private val lock = kotlinx.coroutines.sync.Mutex()
        suspend fun ask(uuid: String): Boolean = lock.withLock {
            if (tree(uuid) != null) return@withLock true
            val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val dlg = FmDlg.SafNeed(uuid, label(uuid)) { ok -> d.complete(ok) }
            Fm.dlg = dlg
            while (!d.isCompleted) {
                kotlinx.coroutines.delay(300)
                if (Fm.dlg !== dlg) d.complete(false)   // окно закрыто другим окном
            }
            if (Fm.dlg === dlg) Fm.dlg = null
            tree(uuid) != null
        }
    }

    // ---------- операции над деревом ----------
    private fun denied() = IOException(app.getString(R.string.fm_err_denied))

    private fun uriOf(path: String): Uri {
        val loc = SafPaths.parse(path) ?: throw denied()
        val t = tree(loc.uuid) ?: throw denied()
        return DocumentsContract.buildDocumentUriUsingTree(t, SafPaths.docId(loc.uuid, loc.rel))
    }

    private val cols = arrayOf(
        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    fun stat(path: String): Node? = try {
        cr.query(uriOf(path), cols, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null
            else Node(c.getString(0).orEmpty(), c.getString(1) == DIR_MIME, if (c.isNull(2)) 0L else c.getLong(2), if (c.isNull(3)) 0L else c.getLong(3))
        }
    } catch (e: Throwable) { null }

    fun children(path: String): List<Node> {
        val loc = SafPaths.parse(path) ?: throw denied()
        val t = tree(loc.uuid) ?: throw denied()
        if (stat(path) == null) throw IOException(app.getString(R.string.fm_err_missing))
        val q = DocumentsContract.buildChildDocumentsUriUsingTree(t, SafPaths.docId(loc.uuid, loc.rel))
        val out = ArrayList<Node>()
        cr.query(q, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) out.add(Node(c.getString(0).orEmpty(), c.getString(1) == DIR_MIME, if (c.isNull(2)) 0L else c.getLong(2), if (c.isNull(3)) 0L else c.getLong(3)))
        } ?: throw denied()
        return out
    }

    /** Список папки для панели (имя, папка ли, размер, время «yyyy-MM-dd HH:mm»). */
    fun list(path: String): List<FmItem> {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        return children(path).filter { it.name != FmTrash.DIR }.map { FmItem(it.name, it.dir, if (it.dir) 0L else it.size, fmt.format(java.util.Date(it.mtime))) }
    }

    private fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    /** Создаёт папку или файл [name] внутри [parentPath]; если система дала другое имя, возвращает его переименованным. */
    private fun create(parentPath: String, name: String, dir: Boolean): Boolean {
        val mime = if (dir) DIR_MIME else mimeOf(name)
        val made = DocumentsContract.createDocument(cr, uriOf(parentPath), mime, name) ?: return false
        val got = runCatching { DocumentsContract.getDocumentId(made).substringAfterLast('/').substringAfter(':') }.getOrDefault(name)
        if (got != name) {
            // система изменила имя (недопустимые знаки, добавила расширение): вернуть как просили, иначе убрать лишнее
            val renamed = runCatching { DocumentsContract.renameDocument(cr, made, name) }.getOrNull()
            if (renamed == null) { runCatching { DocumentsContract.deleteDocument(cr, made) }; return false }
        }
        return true
    }

    fun mkdirs(path: String) {
        val loc = SafPaths.parse(path) ?: throw denied()
        if (tree(loc.uuid) == null) throw denied()
        var cur = SafPaths.pathOf(loc.uuid, "")
        for (seg in loc.rel.split('/').filter { it.isNotEmpty() }) {
            val next = cur.trimEnd('/') + "/" + seg
            val st = stat(next)
            if (st == null) { if (!create(cur, seg, true)) throw denied() } else if (!st.dir) throw IOException(app.getString(R.string.fm_err_exists))
            cur = next
        }
    }

    fun touch(path: String) {
        val loc = SafPaths.parse(path) ?: throw denied()
        if (stat(path) != null) return
        mkdirs(SafPaths.pathOf(loc.uuid, SafPaths.parentRel(loc.rel)))
        if (!create(SafPaths.pathOf(loc.uuid, SafPaths.parentRel(loc.rel)), SafPaths.nameOf(loc.rel), false)) throw denied()
    }

    fun delete(path: String): Boolean = try { DocumentsContract.deleteDocument(cr, uriOf(path)) } catch (e: Throwable) { stat(path) == null }

    /** Переименование в той же папке тома; false: другая папка или том (тогда перенос копией через [copyTree]) или сбой. */
    fun rename(from: String, to: String): Boolean {
        val a = SafPaths.parse(from) ?: return false; val b = SafPaths.parse(to) ?: return false
        if (!a.uuid.equals(b.uuid, true) || SafPaths.parentRel(a.rel) != SafPaths.parentRel(b.rel)) return false
        return try { DocumentsContract.renameDocument(cr, uriOf(from), SafPaths.nameOf(b.rel)) != null } catch (e: Throwable) { false }
    }

    fun openIn(path: String): InputStream = cr.openInputStream(uriOf(path)) ?: throw denied()
    fun openOut(path: String): OutputStream = cr.openOutputStream(uriOf(path), "wt") ?: throw denied()

    /** Документ-ссылка для внешних программ («Открыть в…»): выдаётся им как чтение. */
    fun documentUri(path: String): Uri = uriOf(path)

    // ---------- копирование и перенос с участием SAF ----------
    private fun nodeOf(path: String): Node? =
        if (needs(path)) stat(path) else File(path).takeIf { it.exists() }?.let { Node(it.name, it.isDirectory, if (it.isDirectory) 0L else it.length(), it.lastModified()) }

    private fun kidsOf(path: String): List<Node> =
        if (needs(path)) children(path) else File(path).listFiles().orEmpty().filter { it.name != FmTrash.DIR }.map { Node(it.name, it.isDirectory, if (it.isDirectory) 0L else it.length(), it.lastModified()) }

    private fun openInAny(path: String): InputStream = if (needs(path)) openIn(path) else File(path).inputStream()

    private fun deleteAny(path: String): Boolean = if (needs(path)) delete(path) else File(path).deleteRecursively()

    private fun mkdirsAny(path: String) { if (needs(path)) mkdirs(path) else if (!File(path).mkdirs() && !File(path).isDirectory) throw denied() }

    private fun join(a: String, b: String) = a.trimEnd('/') + "/" + b

    /** Пишет поток в [to] безопасно: временный «.fmpart», проверка размера, замена старого файла, переименование. */
    private fun writeAny(to: String, ins: InputStream, size: Long, pr: OpProgress) {
        val parent = to.trimEnd('/').substringBeforeLast('/')
        val name = to.trimEnd('/').substringAfterLast('/')
        mkdirsAny(parent)
        val part = join(parent, "$name.fmpart")
        val viaSaf = needs(to)
        if (viaSaf) { if (stat(part) != null) delete(part) } else File(part).delete()
        try {
            if (viaSaf) touch(part)
            val out = if (viaSaf) openOut(part) else File(part).outputStream()
            var written = 0L
            out.use { o ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    if (pr.cancelled) throw LocalOps.Cancelled()
                    val n = ins.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n); written += n
                    pr.bytes(n.toLong())
                }
            }
            val got = if (viaSaf) stat(part)?.size ?: -1L else File(part).length()
            if (size >= 0 && got != size && !(viaSaf && got <= 0L && written == size)) throw IOException("size mismatch")
            if (viaSaf) {
                if (stat(to) != null) delete(to)
                if (!rename(part, to)) throw IOException("rename failed")
            } else {
                val t = File(to); if (t.exists()) t.delete()
                if (!File(part).renameTo(t)) throw IOException("rename failed")
            }
        } catch (e: Throwable) {
            if (viaSaf) runCatching { delete(part) } else File(part).delete()
            throw e
        }
    }

    private fun copyNode(from: String, to: String, n: Node, pr: OpProgress) {
        if (pr.cancelled) throw LocalOps.Cancelled()
        if (n.dir) {
            mkdirsAny(to)
            for (k in kidsOf(from)) copyNode(join(from, k.name), join(to, k.name), k, pr)
            pr.item()
        } else {
            pr.file(n.name)
            openInAny(from).use { writeAny(to, it, n.size, pr) }
            pr.item()
        }
    }

    /**
     * Копирование и перенос дерева, когда хотя бы одна сторона на съёмном томе под SAF. Перенос: копия, затем удаление источника
     * (в пределах одного тома SAF сначала пробуется мгновенное перемещение). Возвращает null или ошибку бросает исключением.
     */
    fun copyTree(from: String, to: String, move: Boolean, pr: OpProgress) {
        val n = nodeOf(from) ?: throw IOException(app.getString(R.string.fm_err_missing))
        if (n.dir && (to.trimEnd('/') + "/").startsWith(from.trimEnd('/') + "/")) throw IOException("into itself")
        if (move && needs(from) && needs(to)) {
            val a = SafPaths.parse(from); val b = SafPaths.parse(to)
            if (a != null && b != null && a.uuid.equals(b.uuid, true) && SafPaths.nameOf(a.rel) == SafPaths.nameOf(b.rel) && stat(to) == null) {
                val ok = runCatching {
                    mkdirs(SafPaths.pathOf(b.uuid, SafPaths.parentRel(b.rel)))
                    DocumentsContract.moveDocument(cr, uriOf(from), uriOf(SafPaths.pathOf(a.uuid, SafPaths.parentRel(a.rel))), uriOf(SafPaths.pathOf(b.uuid, SafPaths.parentRel(b.rel)))) != null
                }.getOrDefault(false)
                if (ok) { pr.bytes(if (n.dir) 0L else n.size); pr.item(); return }
            }
        }
        copyNode(from, to, n, pr)
        if (move) { if (!deleteAny(from)) throw denied() }
    }
}
