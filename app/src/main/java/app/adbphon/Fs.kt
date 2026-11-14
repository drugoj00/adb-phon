package app.adbphon

import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties

/** Запись в списке файлов любого источника: память устройства, флешка через ADB, сетевая папка. */
data class FsEntry(val name: String, val path: String, val isDir: Boolean, val size: Long, val date: String = "")

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
                // файл самого устройства уже на месте: сведения о пакете (версии, подпись) читаются сразу; остальное подтягивается при установке
                if (src is LocalFs) out.add(ApkFile(e.name, Uri.fromFile(File(e.path)), e.size, File(e.path)))
                else out.add(ApkFile(e.name, Uri.EMPTY, e.size, null, fetch = {
                    cacheName(ctx, e.name).also { f -> src.copyTo(e.path, f) }
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
    private val line = Regex("""^(\S{10})\s+\d+\s+\S+\s+\S+\s+(\d+)\s+(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2})\s+(.+)$""")
    private fun q(p: String) = "'" + p.replace("'", "'\\''") + "'"

    override suspend fun list(path: String): List<FsEntry> {
        // завершающая «/» нужна, чтобы символические ссылки (/sdcard и т.п.) показывали содержимое, а не саму ссылку
        val out = Adb.shell("ls -lA ${q(path.trimEnd('/') + "/")} 2>&1")
        // «ls: //init: Permission denied» на отдельных файлах корня (Samsung, Android 10) не мешает показать остальное: ошибка только если записей нет совсем
        if ((out.contains("Permission denied") || out.contains("No such file")) && out.lines().none { line.find(it.trim()) != null }) error(out.lines().first())
        return out.lines().mapNotNull { l ->
            val m = line.find(l.trim()) ?: return@mapNotNull null
            val name = m.groupValues[4].substringBefore(" -> ")
            if (name.startsWith(".")) return@mapNotNull null
            val p = path.trimEnd('/') + "/" + name
            val t = m.groupValues[1][0]
            FsEntry(name, p, t == 'd' || (t == 'l' && !isApk(name)), m.groupValues[2].toLongOrNull() ?: 0L, m.groupValues[3].replace(Regex("\\s+"), " "))
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

/** Сервер не поддерживает выбранную версию SMB (понятная ошибка вместо «Access is denied» и сетевых исключений). */
class SmbVersionError(msg: String) : java.io.IOException(msg)

class SmbFs(private val host: String, private val user: String, private val pass: String, private val legacy: Boolean = false, private val maxVer: String? = null, private val version: String = "") : RemoteFs {
    /** Без логина: сначала анонимный вход, а если сервер его не принял («Access is denied»), гость с пустым паролем (так открываются папки Windows «для всех без пароля»). */
    @Volatile private var guestMode = false
    private var ctxCache: jcifs.CIFSContext? = null
    private val ctx: jcifs.CIFSContext get() = synchronized(this) { ctxCache ?: buildCtx().also { ctxCache = it } }
    private fun buildCtx(): jcifs.CIFSContext {
        val props = Properties().apply {
            if (legacy) {   // прежние настройки (только для сравнения в NetTestActivity)
                setProperty("jcifs.smb.client.connTimeout", "6000"); setProperty("jcifs.smb.client.responseTimeout", "20000"); setProperty("jcifs.smb.client.soTimeout", "20000")
            } else {
            setProperty("jcifs.smb.client.connTimeout", "4000")        // соединение с сервером не дольше 4 с
            setProperty("jcifs.smb.client.responseTimeout", "8000")    // ответ на запрос не дольше 8 с (раньше 20 с, а при полуоткрытом TCP до ~2 минут)
            setProperty("jcifs.smb.client.soTimeout", "8000")          // простой сокета не дольше 8 с
            setProperty("jcifs.smb.client.idleTimeout", "600000")      // транспорт не закрывается сам через 30 с простоя
            setProperty("jcifs.smb.client.sessionTimeout", "600000")
            }
        }
        // выбранная версия SMB: 1 = только SMB1, 2 = SMB 2.02–2.1, 3 = SMB 3.0–3.1.1; пусто («Авто») = как раньше, согласование выбирает библиотека
        when (version) {
            "1" -> { props.setProperty("jcifs.smb.client.minVersion", "SMB1"); props.setProperty("jcifs.smb.client.maxVersion", "SMB1") }
            "2" -> { props.setProperty("jcifs.smb.client.minVersion", "SMB202"); props.setProperty("jcifs.smb.client.maxVersion", "SMB210") }
            "3" -> { props.setProperty("jcifs.smb.client.minVersion", "SMB300"); props.setProperty("jcifs.smb.client.maxVersion", "SMB311") }
        }
        if (maxVer != null) { props.setProperty("jcifs.smb.client.maxVersion", maxVer); props.setProperty("jcifs.smb.client.signingEnforced", "false") }   // только для стенда NetTestActivity
        val base = jcifs.context.BaseContext(jcifs.config.PropertyConfiguration(props))
        return base.withCredentials(if (user.isBlank()) { if (guestMode) jcifs.smb.NtlmPasswordAuthenticator(null, "guest", "") else jcifs.smb.NtlmPasswordAuthenticator() } else jcifs.smb.NtlmPasswordAuthenticator(null, user, pass))
    }

    /** При выбранной версии: сбой согласования и обрыв соединения превращаются в понятное сообщение; ошибки входа и «нет такой папки» остаются как есть. */
    private fun mapErr(e: Throwable): Throwable {
        if (version.isEmpty() || e is kotlinx.coroutines.CancellationException) return e
        if (e is jcifs.smb.SmbAuthException) return e
        val st = (e as? jcifs.smb.SmbException)?.ntStatus ?: 0
        if (st != 0 && st != 0xC0000001.toInt() && st != 0xC00000BB.toInt() && st != 0xC000000D.toInt()) return e   // не «неуспех», «не поддерживается», «неверный параметр»: сервер ответил по существу
        AppLog.i("ADBphon", "SMB$version: ${e.javaClass.simpleName}: ${e.message}")
        return SmbVersionError(FmIo.app.getString(R.string.fm_smb_ver_unsupported, "SMB$version"))
    }
    private fun isDenied(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(6).any { it is jcifs.smb.SmbAuthException || (it is jcifs.smb.SmbException && it.ntStatus == 0xC0000022.toInt()) }
    private fun deniedErr(write: Boolean): Throwable = if (write) NoWriteRights() else java.io.IOException(FmIo.app.getString(R.string.fm_smb_guest_denied))
    private suspend fun <T> guard(write: Boolean = false, block: suspend () -> T): T = try { block() } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException || !isDenied(e)) throw mapErr(e)
        if (user.isNotBlank()) { if (write) throw NoWriteRights() else throw mapErr(e) }
        if (guestMode) throw mapErr(deniedErr(write))
        // анонимный вход отклонён: переключаемся на гостя и повторяем запрос один раз
        AppLog.i("ADBphon", "SMB $host: анонимный вход отклонён, пробуем гостя")
        guestMode = true
        synchronized(this) { runCatching { ctxCache?.close() }; ctxCache = null }
        try { block() } catch (e2: Throwable) { throw mapErr(if (isDenied(e2)) deniedErr(write) else e2) }
    }
    override fun close() { synchronized(this) { runCatching { ctxCache?.close() }; ctxCache = null } }
    /** Лёгкая проверка: есть ли папка (запрос атрибутов по уже открытой сессии, без чтения списка). */
    override suspend fun ping(path: String) { guard { withContext(Dispatchers.IO) { jcifs.smb.SmbFile(if (path.endsWith("/")) path else "$path/", ctx).exists() } } }

    override val root get() = "smb://$host/"
    override val label get() = "smb://$host"

    override suspend fun list(path: String): List<FsEntry> = guard { withContext(Dispatchers.IO) {
        val dir = jcifs.smb.SmbFile(if (path.endsWith("/")) path else "$path/", ctx)
        dir.listFiles().orEmpty().mapNotNull { f ->
            val name = f.name.trimEnd('/')
            if (name.endsWith("$") || name.startsWith(".")) return@mapNotNull null
            val isDir = runCatching { f.isDirectory }.getOrDefault(true)
            FsEntry(name, f.url.toString(), isDir, if (isDir) 0L else runCatching { f.length() }.getOrDefault(0L))
        }.sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })
    } }

    override suspend fun copyTo(path: String, dest: File) {
        guard { withContext(Dispatchers.IO) {
            jcifs.smb.SmbFile(path, ctx).inputStream.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        } }
    }

    /** Первые [n] байт файла (для просмотра текста). */
    override suspend fun head(path: String, n: Int): ByteArray = guard { withContext(Dispatchers.IO) {
        jcifs.smb.SmbFile(path, ctx).inputStream.use { i ->
            val buf = ByteArray(n); var got = 0
            while (got < n) { val r = i.read(buf, got, n - got); if (r < 0) break; got += r }
            buf.copyOf(got)
        }
    } }

    // ---- запись ----
    override suspend fun stat(path: String): FsEntry? = guard { withContext(Dispatchers.IO) {
        val f = jcifs.smb.SmbFile(path, ctx)
        if (!f.exists()) null else { val d = f.isDirectory; FsEntry(path.trimEnd('/').substringAfterLast('/'), path, d, if (d) 0L else f.length()) }
    } }
    override suspend fun mkdirRemote(path: String) { guard(true) { withContext(Dispatchers.IO) { jcifs.smb.SmbFile(path.trimEnd('/') + "/", ctx).mkdir() } } }
    override suspend fun renameRemote(from: String, to: String, overwrite: Boolean) { guard(true) { withContext(Dispatchers.IO) { jcifs.smb.SmbFile(from, ctx).renameTo(jcifs.smb.SmbFile(to, ctx), overwrite) } } }
    override suspend fun removeRemote(path: String, isDir: Boolean) { guard(true) { withContext(Dispatchers.IO) { jcifs.smb.SmbFile(if (isDir) path.trimEnd('/') + "/" else path, ctx).delete() } } }
    override suspend fun putFile(src: File, dest: String, j: OpProgress?) {
        guard(true) { withContext(Dispatchers.IO) {
            try { jcifs.smb.SmbFileOutputStream(jcifs.smb.SmbFile(dest, ctx)).use { o -> UpStream(src, j).use { i -> i.copyTo(o, 256 * 1024) } } }
            catch (e: java.io.IOException) { if (j?.cancelled == true) throw LocalOps.Cancelled(); throw e }
        } }
    }

    override fun parent(path: String): String? {
        val t = path.trimEnd('/')
        if (t == "smb://$host" || t.length <= "smb://".length) return null
        return t.substringBeforeLast('/') + "/"
    }

    override suspend fun roots(): List<Pair<String, String>> = listOf(host to root)
}
