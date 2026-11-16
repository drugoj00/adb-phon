package app.adbphon

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Запись и чтение файлов копий в выбранном месте: память устройства, SD-карта, USB (прямой доступ к файлам), сетевые источники SMB, SFTP, FTP/FTPS,
 * WebDAV и ПК (через [KeepFs]: запись во временное имя, проверка размера, замена) и облака ([CloudFs]). Последнее место запоминается отдельно
 * для копии настроек и для «Копии с данными».
 */
object BkStore {
    const val KEY_SETTINGS = "bkp_place_settings"
    const val KEY_DATA = "bkp_place_data"
    /** Виды сохранённых подключений, куда можно писать копии. */
    val NET_KINDS = setOf("smb", "sftp", "ftp", "ftps", "dav", "pc", "cloud")

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE)

    /** Папки по умолчанию: Download/ADB-phon во внутренней памяти (создаётся при первой записи), для копий с данными подпапка Data. */
    const val DEFAULT_DIR = "/sdcard/Download/ADB-phon"
    fun defaultPlace(key: String): BkPlace = BkPlace(true, if (key == KEY_DATA) "$DEFAULT_DIR/Data" else DEFAULT_DIR)
    fun last(ctx: Context, key: String): BkPlace = BkPlace.decode(prefs(ctx).getString(key, null)) ?: defaultPlace(key)
    fun setLast(ctx: Context, key: String, p: BkPlace) { prefs(ctx).edit().putString(key, p.encode()).apply() }

    // ---- шифрование паролем (одно на копии настроек, копии с данными и автокопию)
    private const val ENC = "bk2_enc"
    /** Пароль хранится только зашифрованным ключом Android Keystore этого устройства; в копию настроек не входит (AppBackup.EXCLUDE_KEYS). */
    private const val PASS = "bkp_auto_pass"
    fun encOn(ctx: Context) = prefs(ctx).getBoolean(ENC, false)
    fun setEncOn(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean(ENC, on).apply() }
    fun pass(ctx: Context): String = prefs(ctx).getString(PASS, null)?.let { runCatching { NetStore.decrypt(it) }.getOrNull() }.orEmpty()
    fun setPass(ctx: Context, p: String) {
        val e = prefs(ctx).edit()
        if (p.isEmpty()) e.remove(PASS) else runCatching { e.putString(PASS, NetStore.encrypt(p)) }
        e.apply()
    }
    /** Пароль для новой копии: null без шифрования. Шифрование включено, а пароля нет: [NeedPass]. */
    fun passForCopy(ctx: Context): CharArray? {
        if (!encOn(ctx)) return null
        val p = pass(ctx)
        if (p.isEmpty()) throw NeedPass()
        return p.toCharArray()
    }
    class NeedPass : Exception("no password")

    /** Свободно на томе локальной папки (ближайшая существующая родительская), -1: неизвестно. */
    fun freeSpace(p: BkPlace): Long {
        if (!p.local) return -1L
        var f: File? = File(p.path)
        while (f != null && !f.exists()) f = f.parentFile
        return f?.usableSpace?.takeIf { it > 0 } ?: -1L
    }

    /** Первые [n] байт файла копии (для «что внутри» в списке). */
    suspend fun head(o: Open, name: String, n: Int): ByteArray = withContext(Dispatchers.IO) {
        val fs = o.fs
        if (fs == null) File(o.place.path, name).inputStream().use { i -> val b = ByteArray(n); var off = 0; while (off < n) { val r = i.read(b, off, n - off); if (r < 0) break; off += r }; b.copyOf(off) }
        else fs.head(o.place.child(name), n)
    }

    fun conn(ctx: Context, p: BkPlace): NetConn? = if (p.local) null else NetStore.list(ctx).firstOrNull { it.kind == p.kind && it.host == p.host && it.user == p.user }

    /** Полный путь для показа: «/storage/107D-CBFB/Backups/имя» или «Название подключения: /папка/имя». */
    fun display(ctx: Context, p: BkPlace, name: String? = null): String {
        val path = if (name == null) p.path else p.child(name)
        if (p.local) return path
        val t = conn(ctx, p)?.title ?: p.host
        return "$t: $path"
    }

    fun size(ctx: Context, b: Long): String {
        fun f(v: Double) = String.format(Locale.getDefault(), if (v < 10) "%.1f" else "%.0f", v)
        return when {
            b < 1024 -> "$b " + ctx.getString(R.string.unit_b)
            b < 1024L * 1024 -> f(b / 1024.0) + " " + ctx.getString(R.string.unit_kb)
            b < 1024L * 1024 * 1024 -> f(b / 1048576.0) + " " + ctx.getString(R.string.unit_mb)
            else -> f(b / 1073741824.0) + " " + ctx.getString(R.string.unit_gb)
        }
    }

    /** Нет доступа ко всем файлам (Android 11+): запись в память и на SD-карту напрямую невозможна. */
    fun needAllFiles(): Boolean = android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()

    /** Открытое место: для сети одно подключение на всю операцию. Закрывать [close]. */
    class Open(val ctx: Context, val place: BkPlace, val fs: RemoteFs?) : java.io.Closeable {
        override fun close() { runCatching { fs?.close() } }
        val isCloud get() = fs is CloudFs
    }

    /** Подключение к месту. Ошибка: понятный текст в IOException. */
    suspend fun open(ctx: Context, p: BkPlace): Open = withContext(Dispatchers.IO) {
        if (p.local) return@withContext Open(ctx, p, null)
        runCatching { FmIo.app }.onFailure { FmIo.app = ctx.applicationContext }
        val c = conn(ctx, p) ?: throw IOException(ctx.getString(R.string.bkp_err_no_conn, p.host))
        if (c.kind == "cloud" && !CloudModule.ok(ctx)) throw IOException(ctx.getString(R.string.bkp_err_cloud_module))
        val fs = c.fs()
        try { (fs as? KeepFs)?.connect() } catch (e: Throwable) { runCatching { fs.close() }; throw IOException(FmIo.nice(e), e) }
        Open(ctx, p, fs)
    }

    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    suspend fun list(o: Open): List<FsEntry> = withContext(Dispatchers.IO) {
        if (o.fs == null) {
            val d = File(o.place.path)
            val arr = d.listFiles() ?: throw IOException(o.ctx.getString(if (needAllFiles()) R.string.bkp_err_all_files else R.string.bkp_err_no_folder, o.place.path))
            arr.map { FsEntry(it.name, it.absolutePath, it.isDirectory, if (it.isDirectory) 0L else it.length(), df.format(Date(it.lastModified()))) }
        } else o.fs.list(o.place.path)
    }

    suspend fun exists(o: Open, name: String): Boolean = withContext(Dispatchers.IO) {
        if (o.fs == null) File(o.place.path, name).exists() else list(o).any { it.name == name }
    }

    private fun prog(onBytes: (Long) -> Unit) = object : OpProgress {
        override val cancelled = false
        override fun bytes(n: Long) = onBytes(n)
        override fun file(name: String) {}
        override fun item() {}
    }

    /**
     * Записать файл [src] под именем [name]. Возвращает размер записанного файла (проверенный: файл есть и размер совпадает).
     * Существующий файл не заменяется (IOException).
     */
    suspend fun write(o: Open, src: File, name: String, onBytes: (Long) -> Unit = {}): Long = withContext(Dispatchers.IO) {
        val want = src.length()
        if (want <= 0L) throw IOException(o.ctx.getString(R.string.bkp_err_empty))
        val fs = o.fs
        val got: Long = when {
            fs == null -> {
                val dir = File(o.place.path)
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException(o.ctx.getString(if (needAllFiles()) R.string.bkp_err_all_files else R.string.bkp_err_no_folder, o.place.path))
                val dst = File(dir, name)
                if (dst.exists()) throw IOException(o.ctx.getString(R.string.fm_err_exists))
                val tmp = File(dir, ".$name.part")
                try {
                    src.inputStream().use { i -> tmp.outputStream().use { out -> val b = ByteArray(256 * 1024); while (true) { val n = i.read(b); if (n < 0) break; out.write(b, 0, n); onBytes(n.toLong()) }; out.fd.sync() } }
                    if (!tmp.renameTo(dst)) throw IOException(o.ctx.getString(R.string.fm_err_denied))
                } catch (e: Throwable) { tmp.delete(); if (e is IOException && needAllFiles()) throw IOException(o.ctx.getString(R.string.bkp_err_all_files, o.place.path), e); throw e }
                dst.length()
            }
            fs is KeepFs -> {
                val to = o.place.child(name)
                fs.upload(src, to, false, prog(onBytes))
                fs.stat(to)?.size ?: -1L
            }
            fs is CloudFs -> {
                if (list(o).any { it.name == name }) throw IOException(o.ctx.getString(R.string.fm_err_exists))
                fs.upload(src, o.place.child(name), false)
                onBytes(want)
                list(o).firstOrNull { it.name == name }?.size ?: -1L
            }
            else -> throw IOException(o.ctx.getString(R.string.fm_err_unsupported))
        }
        if (got != want) throw IOException(o.ctx.getString(R.string.bkp_err_size, got, want))
        got
    }

    /** Локальный файл копии (место в памяти) или null для сети. */
    fun localFile(o: Open, name: String): File? = if (o.fs == null) File(o.place.path, name) else null

    /** Скачать файл копии в [dest] (для сети и облака). */
    suspend fun fetch(o: Open, name: String, dest: File) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val fs = o.fs
        if (fs == null) File(o.place.path, name).copyTo(dest, true) else fs.copyTo(o.place.child(name), dest)
        if (!dest.isFile || dest.length() == 0L) throw IOException(o.ctx.getString(R.string.bkp_err_empty))
    }

    /** Удалить свой файл (ротация автокопий). В облаке удаление окончательное, без корзины. */
    suspend fun delete(o: Open, name: String) = withContext(Dispatchers.IO) {
        val fs = o.fs
        when {
            fs == null -> { val f = File(o.place.path, name); if (f.exists() && !f.delete()) throw IOException(o.ctx.getString(R.string.fm_err_denied)) }
            fs is CloudFs -> fs.deletePath(o.place.child(name), false, true)?.let { throw IOException(it) }
            else -> fs.removeRemote(o.place.child(name), false)
        }
    }

    /** Временная папка в кэше программы для упаковки и скачивания. */
    fun tmpDir(ctx: Context): File = File(ctx.cacheDir, "bkp").apply { mkdirs() }
}
