package app.adbphon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Архив как «папка» в «Файлах»: панель работает с ним как с сетевым источником (список, вход, копирование наружу, просмотр),
 * для ZIP доступны запись: добавить, удалить, переименовать, создать папку (через временный файл и замену).
 */
class ArchiveFs(val file: File, val display: String, val prev: PrevState, private val tmpDir: File) : RemoteFs {
    class PrevState(val src: String, val smb: RemoteFs?, val net: NetConn?, val path: String, val pick: String)

    var password: CharArray? = null
    var charset: String? = null
    @Volatile private var rd: ArcReader? = null
    private var children = HashMap<String, LinkedHashMap<String, Node>>()
    class Node(val isDir: Boolean, val entry: ArcEntry?, var mtime: Long)

    val kind: Arc.Kind? get() = Arc.kindOf(file.name)
    val canWrite: Boolean get() = (kind ?: Arc.Kind.ZIP) == Arc.Kind.ZIP && file.canWrite()

    override val root = "/"
    override val label: String get() = (kind?.name ?: "ARC").lowercase().let { if (it == "sevenz") "7z" else it } + "://" + display

    @Synchronized fun reader(): ArcReader {
        rd?.let { return it }
        val r = Arc.open(file, charset, password)
        rd = r
        val m = HashMap<String, LinkedHashMap<String, Node>>()
        fun dir(k: String) = m.getOrPut(k) { LinkedHashMap() }
        dir("")
        for (e in r.entries) {
            val segs = e.path.split('/')
            var cur = ""
            for (i in 0 until segs.size - 1) {
                val nk = if (cur.isEmpty()) segs[i] else "$cur/${segs[i]}"
                val n = dir(cur).getOrPut(segs[i]) { Node(true, null, 0L) }
                if (!n.isDir) { cur = nk; continue }
                dir(nk); cur = nk
            }
            val last = segs.last()
            if (e.isDir) { val n = dir(cur).getOrPut(last) { Node(true, e, e.mtime) }; n.mtime = e.mtime; dir(if (cur.isEmpty()) last else "$cur/$last") }
            else dir(cur)[last] = Node(false, e, e.mtime)
        }
        children = m
        return r
    }

    @Synchronized fun invalidate() { runCatching { rd?.close() }; rd = null }

    private fun key(path: String) = Arc.clean(path)

    fun items(path: String): List<FmItem> {
        reader()
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return (children[key(path)] ?: throw IOException("no such folder")).map { (name, n) ->
            FmItem(name, n.isDir, if (n.isDir) 0L else maxOf(n.entry?.size ?: 0L, 0L), if (n.mtime > 0) df.format(Date(n.mtime)) else "")
        }
    }

    override suspend fun list(path: String): List<FsEntry> = withContext(Dispatchers.IO) {
        items(path).map { FsEntry(it.name, if (path.trimEnd('/').isEmpty()) "/" + it.name else path.trimEnd('/') + "/" + it.name, it.isDir, it.size) }
    }

    override fun parent(path: String): String? { val k = key(path); return if (k.isEmpty()) null else "/" + k.substringBeforeLast('/', "") }
    override suspend fun roots(): List<Pair<String, String>> = listOf(display to "/")

    fun exists(path: String): Boolean {
        reader(); val k = key(path); if (k.isEmpty()) return true
        return children[k.substringBeforeLast('/', "")]?.containsKey(k.substringAfterLast('/')) == true
    }

    fun nodeOf(path: String): Node? { reader(); val k = key(path); return children[k.substringBeforeLast('/', "")]?.get(k.substringAfterLast('/')) }

    /** Извлечь файл или папку [path] в [dest] (для папки это папка назначения). */
    fun extractTo(path: String, dest: File, p: OpProgress?) {
        val r = reader(); val k = key(path)
        val n = nodeOf(path) ?: throw IOException("not found")
        val pr = p ?: object : OpProgress { override val cancelled = false; override fun bytes(n: Long) {}; override fun file(name: String) {}; override fun item() {} }
        if (n.isDir) { Arc.extractAll(r, dest, pr, 0, k); return }
        val e = n.entry ?: throw IOException("not found")
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".fmpart")
        try {
            r.open(e).use { i -> part.outputStream().use { o -> val buf = ByteArray(128 * 1024); while (true) { if (pr.cancelled) throw LocalOps.Cancelled(); val c = i.read(buf); if (c < 0) break; o.write(buf, 0, c); pr.bytes(c.toLong()) } } }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw IOException("rename failed")
            if (e.mtime > 0) dest.setLastModified(e.mtime)
        } catch (t: Throwable) { part.delete(); throw t }
    }

    override suspend fun copyTo(path: String, dest: File) = withContext(Dispatchers.IO) { extractTo(path, dest, null) }

    override suspend fun head(path: String, n: Int): ByteArray = withContext(Dispatchers.IO) {
        val e = nodeOf(path)?.entry ?: throw IOException("not found")
        reader().open(e).use { i -> val b = ByteArray(n); var got = 0; while (got < n) { val c = i.read(b, got, n - got); if (c < 0) break; got += c }; b.copyOf(got) }
    }

    /** Размер содержимого [path] в распакованном виде (для полоски прогресса). */
    fun sizeOf(path: String): Long {
        val r = reader(); val k = key(path)
        return r.entries.filter { !it.isDir && (k.isEmpty() || it.path == k || it.path.startsWith("$k/")) }.sumOf { maxOf(it.size, 0L) }
    }

    fun info(path: String): String {
        val r = reader(); val k = key(path)
        val n = nodeOf(path)
        val sb = StringBuilder()
        sb.append("$label\n").append(if (k.isEmpty()) "/" else "/$k").append('\n')
        val e = n?.entry
        if (n == null && k.isNotEmpty()) return sb.append(Fm.str(R.string.fm_err_missing)).toString()
        if (n == null || n.isDir) {
            val sub = r.entries.filter { !it.isDir && (k.isEmpty() || it.path.startsWith("$k/")) }
            sb.append(Fm.str(R.string.arc_files_count, sub.size)).append('\n').append(Fm.str(R.string.arc_size_unpacked, fmSize(sub.sumOf { maxOf(it.size, 0L) }))).append('\n')
            val cs = sub.sumOf { maxOf(it.csize, 0L) }
            if (cs > 0) sb.append(Fm.str(R.string.arc_size_packed, fmSize(cs))).append('\n')
        } else if (e != null) {
            sb.append(Fm.str(R.string.arc_size_unpacked, fmSize(maxOf(e.size, 0L)))).append('\n')
            if (e.csize >= 0) { sb.append(Fm.str(R.string.arc_size_packed, fmSize(e.csize))).append('\n'); if (e.size > 0) sb.append(Fm.str(R.string.arc_ratio, 100 - (e.csize * 100 / e.size).toInt())).append('\n') }
            if (e.crc != 0L) sb.append("CRC32: %08X\n".format(e.crc))
            sb.append(Fm.str(R.string.arc_method, e.method)).append(if (e.encrypted) " · " + Fm.str(R.string.arc_encrypted) else "").append('\n')
            if (e.mtime > 0) sb.append(SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US).format(Date(e.mtime))).append('\n')
        }
        if (k.isEmpty()) sb.append(Fm.str(R.string.arc_names_charset, r.charset)).append('\n')
        return sb.toString().trim()
    }

    // ---------------- запись (только ZIP)

    private fun needWrite() { if (!canWrite) throw ArcUnsupported(Fm.str(R.string.arc_zip_only)) }

    private fun edit(delete: Set<String> = emptySet(), rename: Map<String, String> = emptyMap(), add: List<Pair<File, String>> = emptyList(), dirs: List<String> = emptyList(), p: OpProgress? = null) {
        needWrite()
        val cs = rd?.charset?.takeIf { it != "UTF-8" } ?: charset
        invalidate()
        ZipEdit.apply(file, tmpDir, cs, password, lastLevel, delete, rename, add, dirs, p)
        charset = null
    }
    var lastLevel = 6

    fun addFiles(list: List<Pair<File, String>>, p: OpProgress?) = edit(add = list.map { it.first to key(it.second) }, p = p)
    fun mkdir(path: String) = edit(dirs = listOf(key(path)))
    fun delete(paths: List<String>) = edit(delete = paths.map { key(it) }.toSet())
    fun rename(from: String, to: String) = edit(rename = mapOf(key(from) to key(to)))
    fun touch(path: String) { val t = File.createTempFile("new", ".tmp", tmpDir); try { addFiles(listOf(t to path), null) } finally { t.delete() } }
}
