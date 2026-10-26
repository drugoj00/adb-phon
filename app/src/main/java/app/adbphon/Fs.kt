package app.adbphon

import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties

/** Запись в списке файлов любого источника: память устройства, флешка через ADB, сетевая папка. */
data class FsEntry(val name: String, val path: String, val isDir: Boolean, val size: Long)

/** Источник файлов. Копирование нужно, чтобы поставить файл или распаковать архив. */
interface Fs {
    suspend fun list(path: String): List<FsEntry>
    suspend fun copyTo(path: String, dest: File)
    fun parent(path: String): String?
    /** Быстрые места: подпись и путь. */
    suspend fun roots(): List<Pair<String, String>>
}

private fun cacheName(ctx: Context, name: String): File {
    val dir = File(ctx.cacheDir, "batch").apply { mkdirs() }
    return File(dir, System.nanoTime().toString(36) + "_" + name.replace(Regex("[^\\w.\\- ]"), "_"))
}

private fun isApk(n: String) = n.lowercase().endsWith(".apk")

/** Все APK папки и вложенных папок (до двух уровней); сами файлы подтягиваются, только когда их ставят. */
suspend fun Fs.apksIn(ctx: Context, path: String): List<ApkFile> {
    val out = ArrayList<ApkFile>()
    suspend fun walk(p: String, depth: Int) {
        for (e in runCatching { list(p) }.getOrDefault(emptyList())) {
            if (out.size >= 300) return
            if (!e.isDir && isApk(e.name)) {
                val src = this
                out.add(ApkFile(e.name, Uri.EMPTY, e.size, null, fetch = {
                    if (src is LocalFs) File(e.path) else cacheName(ctx, e.name).also { f -> src.copyTo(e.path, f) }
                }))
            } else if (e.isDir && depth < 2) walk(e.path, depth + 1)
        }
    }
    walk(path, 0)
    return out.sortedBy { it.name.lowercase() }
}

/** Перекладывает выбранный файл во временную папку и, если это архив, достаёт из него APK. */
suspend fun Fs.apksFromFile(ctx: Context, e: FsEntry): List<ApkFile> {
    val f = if (this is LocalFs) File(e.path) else cacheName(ctx, e.name).also { copyTo(e.path, it) }
    return if (isApk(e.name)) listOf(ApkFile(e.name, Uri.fromFile(f), f.length(), f)) else withContext(Dispatchers.IO) { Archives.extractApks(ctx, Uri.fromFile(f)) }
}

// ---------- память самого устройства ----------

object LocalFs : Fs {
    override suspend fun list(path: String): List<FsEntry> = withContext(Dispatchers.IO) {
        File(path).listFiles().orEmpty().filter { !it.name.startsWith(".") }
            .map { FsEntry(it.name, it.absolutePath, it.isDirectory, it.length()) }
            .sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override suspend fun copyTo(path: String, dest: File) { withContext(Dispatchers.IO) { File(path).copyTo(dest, true) } }
    override fun parent(path: String): String? = File(path).parentFile?.takeIf { it.canRead() }?.absolutePath
    override suspend fun roots(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out.add("Память" to Environment.getExternalStorageDirectory().absolutePath)
        Storage.roots().drop(1).forEach { out.add(it.name to it.absolutePath) }
        return out
    }
}

// ---------- флешки и папки через ADB (то, что система приложению не показывает) ----------

object AdbFs : Fs {
    private val line = Regex("""^(\S{10})\s+\d+\s+\S+\s+\S+\s+(\d+)\s+\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}\s+(.+)$""")
    private fun q(p: String) = "'" + p.replace("'", "'\\''") + "'"

    override suspend fun list(path: String): List<FsEntry> {
        // завершающая «/» нужна, чтобы символические ссылки (/sdcard и т.п.) показывали содержимое, а не саму ссылку
        val out = Adb.shell("ls -lA ${q(path.trimEnd('/') + "/")} 2>&1")
        if (out.contains("Permission denied") || out.contains("No such file")) error(out.lines().first())
        return out.lines().mapNotNull { l ->
            val m = line.find(l.trim()) ?: return@mapNotNull null
            val name = m.groupValues[3].substringBefore(" -> ")
            if (name.startsWith(".")) return@mapNotNull null
            val p = path.trimEnd('/') + "/" + name
            val t = m.groupValues[1][0]
            FsEntry(name, p, t == 'd' || (t == 'l' && !isApk(name)), m.groupValues[2].toLongOrNull() ?: 0L)
        }.sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override suspend fun copyTo(path: String, dest: File) { Adb.pull(path, dest) }

    override fun parent(path: String): String? {
        val t = path.trimEnd('/')
        if (t.isEmpty()) return null
        return t.substringBeforeLast('/', "").ifEmpty { "/" }.takeIf { t != "/" }
    }

    override suspend fun roots(): List<Pair<String, String>> {
        val cand = listOf("/sdcard", "/storage", "/mnt/media_rw", "/mnt/usb", "/mnt/usb_storage", "/mnt/sdcard", "/mnt/extsd", "/data/media/0", "/mnt")
        val found = ArrayList<Pair<String, String>>()
        for (c in cand) {
            val r = runCatching { Adb.shell("ls -d ${q(c)} 2>&1") }.getOrDefault("")
            if (r.trim() == c) found.add(c to c)
        }
        return found
    }
}

// ---------- сетевые папки (SMB / Windows-общий доступ) ----------

class SmbFs(private val host: String, private val user: String, private val pass: String) : Fs {
    private val ctx by lazy {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.connTimeout", "6000")
            setProperty("jcifs.smb.client.responseTimeout", "20000")
            setProperty("jcifs.smb.client.soTimeout", "20000")
        }
        val base = jcifs.context.BaseContext(jcifs.config.PropertyConfiguration(props))
        base.withCredentials(if (user.isBlank()) jcifs.smb.NtlmPasswordAuthenticator() else jcifs.smb.NtlmPasswordAuthenticator(null, user, pass))
    }

    val root get() = "smb://$host/"

    override suspend fun list(path: String): List<FsEntry> = withContext(Dispatchers.IO) {
        val dir = jcifs.smb.SmbFile(if (path.endsWith("/")) path else "$path/", ctx)
        dir.listFiles().orEmpty().mapNotNull { f ->
            val name = f.name.trimEnd('/')
            if (name.endsWith("$") || name.startsWith(".")) return@mapNotNull null
            val isDir = runCatching { f.isDirectory }.getOrDefault(true)
            FsEntry(name, f.url.toString(), isDir, if (isDir) 0L else runCatching { f.length() }.getOrDefault(0L))
        }.sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override suspend fun copyTo(path: String, dest: File) {
        withContext(Dispatchers.IO) {
            jcifs.smb.SmbFile(path, ctx).inputStream.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        }
    }

    override fun parent(path: String): String? {
        val t = path.trimEnd('/')
        if (t == "smb://$host" || t.length <= "smb://".length) return null
        return t.substringBeforeLast('/') + "/"
    }

    override suspend fun roots(): List<Pair<String, String>> = listOf(host to root)
}
