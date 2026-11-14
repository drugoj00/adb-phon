package app.adbphon

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipFile

/**
 * Работа с файлами для панелей менеджера. Источники панели:
 * «local» (прямой доступ к файлам, связь с ADB не нужна), «adb» (оболочка ADB: Android/data и системные папки), «smb» (сетевая папка).
 * Все функции возвращают текст ошибки или null, если всё прошло.
 */
object FmIo {
    lateinit var app: Context
    /** Использовать root или ADB для ограниченных папок (Android/data и т.п.). Настройка вкладки «Файлы», по умолчанию включено. */
    @Volatile var privEnabled = true
    /** Папка недоступна обычному приложению: нужны root или ADB. */
    class NeedPriv : Exception()

    private val restrictedRx = Regex("^(/storage/emulated/\\d+|/sdcard|/storage/self/primary|/data/media/\\d+)/Android/(data|obb)(/.*)?$")
    private val systemRx = Regex("^/(data|system|vendor|proc|sys|cache|apex|product|odm|mnt/runtime|mnt/pass_through)(/.*)?$")

    /** Места, где без root/ADB доступа нет: Android/data и Android/obb (Android 11+), системные разделы и /data. */
    fun restricted(path: String): Boolean =
        (android.os.Build.VERSION.SDK_INT >= 30 && restrictedRx.matches(path.trimEnd('/'))) || systemRx.matches(path.trimEnd('/'))

    private fun hasSu(): Boolean =
        (System.getenv("PATH") ?: "/system/bin:/system/xbin").split(':').any { File(it, "su").exists() } ||
            listOf("/sbin/su", "/su/bin/su", "/system/xbin/su", "/system/bin/su", "/product/bin/su", "/debug_ramdisk/su").any { File(it).exists() }

    /**
     * Ограниченной папке нужны root или ADB. Сначала root (если на устройстве есть su; запрос один раз), иначе ADB по беспроводной отладке:
     * подключение выполняется само, без перехода во вкладку «Связь». Возвращает null, если связь есть, иначе код единственного шага:
     * «wifi» (включить отладку по Wi-Fi) или «pair» (ввести код сопряжения).
     */
    @Volatile private var rootFailAt = 0L

    suspend fun ensurePriv(): String? {
        if (Adb.isRoot) return null
        // 9.0: root первый (на магнитолах он есть), ADB только запасной путь; другое устройство по ADB не трогаем; отказ su не повторяем чаще раза в 5 минут
        if (!remote && Adb.currentHost.isEmpty() && hasSu() && !Adb.rootSuppressed && System.currentTimeMillis() - rootFailAt > 300_000L) {
            if (Adb.tryRoot()) { Hooks.refresh(); return null }
            rootFailAt = System.currentTimeMillis()
        }
        if (Adb.isConnected) return null
        val cr = app.contentResolver
        fun wifiOn() = runCatching { android.provider.Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1 }.getOrDefault(false)
        if (android.os.Build.VERSION.SDK_INT >= 30 && !wifiOn()) {
            if (WifiDebug.userOff(app)) return "wifi"   // пользователь выключил переключателем: сами не включаем
            // права WRITE_SECURE_SETTINGS у приложения уже есть (выдаются по ADB): включаем отладку по Wi-Fi сами
            runCatching { android.provider.Settings.Global.putInt(cr, "adb_wifi_enabled", 1) }
            kotlinx.coroutines.delay(1500)
            if (!wifiOn()) return "wifi"
        }
        if (Adb.connectWireless(app, null).isSuccess) { Hooks.refresh(); return null }
        if (Adb.connectLocal(5555).isSuccess) { Hooks.refresh(); return null }
        return "pair"
    }
    /** ADB смотрит на другое устройство: пути «local» и «adb» тогда относятся к разным устройствам. */
    var remote = false

    private fun s(id: Int, vararg a: Any): String = runCatching { app.getString(id, *a) }.getOrDefault("")
    private fun q(x: String) = "'" + x.replace("'", "'\\''") + "'"
    fun join(dir: String, name: String) = dir.trimEnd('/') + "/" + name
    private val lsLine = Regex("^([dl-])\\S+\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+(\\d{4}-\\d\\d-\\d\\d)\\s+(\\d\\d:\\d\\d)\\s+(.+)$")

    /** Понятный текст ошибки без упоминания связи для обычного режима. */
    fun nice(e: Throwable): String {
        val m = e.message.orEmpty()
        return if (m.contains("connect()") || m.contains("not connected", true)) s(R.string.fm_err_adb_need) else m.ifBlank { e.javaClass.simpleName }
    }

    private suspend fun sh(cmd: String, timeout: Long = 25_000): String = Adb.shell(cmd, timeout)

    private fun failed(out: String): String? =
        out.lines().firstOrNull { it.contains("denied", true) || it.contains("No such", true) || it.contains("cannot", true) || it.contains("Read-only", true) || it.contains("not permitted", true) || it.contains("error", true) }

    private fun unsupported() = s(R.string.fm_err_unsupported)

    // ---------- список ----------
    suspend fun list(p: FmPane, path: String): List<FmItem> = when (p.src) {
        "adb" -> {
            val out = sh("ls -laL ${q(path.trimEnd('/') + "/")} 2>&1")
            val l = out.lines().mapNotNull { lsLine.find(it.trim()) }.map {
                val (t, size, d, tm, name) = it.destructured
                FmItem(name.substringBefore(" -> "), t != "-", size.toLongOrNull() ?: 0, "$d $tm")
            }.filter { it.name != "." && it.name != ".." }
            if (l.isEmpty() && (out.contains("Permission denied") || out.contains("No such file") || out.contains("Not a directory"))) error(out.lines().first { it.isNotBlank() })
            l
        }
        "smb" -> (p.smb as? ArchiveFs)?.let { a -> withContext(Dispatchers.IO) { a.items(path) } } ?: (p.smb ?: error("smb")).list(path).map { FmItem(it.name, it.isDir, it.size, "") }
        else -> withContext(Dispatchers.IO) {
            if (restricted(path)) throw NeedPriv()
            val d = File(path)
            if (!d.exists()) error(s(R.string.fm_err_missing))
            val arr = d.listFiles() ?: throw NeedPriv()
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            arr.filter { it.name != FmTrash.DIR }.map { FmItem(it.name, it.isDirectory, if (it.isDirectory) 0L else it.length(), df.format(Date(it.lastModified()))) }
        }
    }

    suspend fun exists(p: FmPane, path: String): Boolean = when (p.src) {
        "adb" -> sh("[ -e ${q(path)} ] && echo 1").contains("1")
        "smb" -> (p.smb as? ArchiveFs)?.let { a -> withContext(Dispatchers.IO) { a.exists(path) } } ?: (p.smb as? KeepFs)?.let { runCatching { it.stat(path) }.getOrNull() != null } ?: false
        else -> withContext(Dispatchers.IO) { statLocal(path) != null }
    }

    /** Запись на сетевой источник (SFTP, FTP, WebDAV, SMB): текст ошибки или null. */
    private suspend fun netOp(f: suspend () -> Unit): String? = try { f(); null } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { nice(e) }
    fun netFs(p: FmPane): KeepFs? = (p.smb as? KeepFs)?.takeIf { p.src == "smb" }

    /** Запись на телефоне: null если нет. Битая символическая ссылка тоже считается существующей, регистр имён решает файловая система тома. */
    fun statLocal(path: String): CNode? = try {
        if (!java.nio.file.Files.exists(java.nio.file.Paths.get(path), java.nio.file.LinkOption.NOFOLLOW_LINKS)) null
        else { val f = File(path); val d = f.isDirectory; CNode(d, if (d) 0L else f.length(), f.lastModified()) }
    } catch (e: Throwable) {
        val f = File(path); if (f.exists()) CNode(f.isDirectory, if (f.isDirectory) 0L else f.length(), f.lastModified()) else null
    }

    /** После переноса слитых папок убрать пустые каталоги в источнике (непустые, где остались пропущенные файлы, не трогаются). */
    suspend fun pruneEmpty(p: FmPane, path: String) {
        when (p.src) {
            "adb" -> sh("find ${q(path)} -depth -type d -empty -delete 2>&1", 60_000L)
            "local" -> withContext(Dispatchers.IO) { fun rec(f: File) { if (f.isDirectory) { f.listFiles()?.forEach { rec(it) }; f.delete() } }; rec(File(path)) }
            else -> {}
        }
    }

    suspend fun subdirs(p: FmPane, path: String): List<String> = list(p, path).filter { it.isDir }.map { join(path, it.name) }.sorted()

    // ---------- создание, переименование, удаление ----------
    /** Операция над архивом, открытым в панели (запись поддерживает только ZIP). */
    private suspend fun arcOp(p: FmPane, f: (ArchiveFs) -> Unit): String? {
        val a = p.smb as? ArchiveFs ?: return unsupported()
        return try { withContext(Dispatchers.IO) { f(a) }; null } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { nice(e) }
    }

    suspend fun mkdir(p: FmPane, path: String): String? = when (p.src) {
        "adb" -> failed(sh("mkdir -p ${q(path)} 2>&1"))
        "smb" -> { val n = netFs(p); if (n != null) netOp { n.mkdirRemote(path) } else arcOp(p) { a -> a.mkdir(path) } }
        else -> withContext(Dispatchers.IO) { if (File(path).mkdirs() || File(path).isDirectory) null else s(R.string.fm_err_denied) }
    }

    suspend fun touch(p: FmPane, path: String): String? = when (p.src) {
        "adb" -> failed(sh("touch ${q(path)} 2>&1"))
        "smb" -> { val n = netFs(p); if (n != null) netOp { withContext(Dispatchers.IO) { val t = File.createTempFile("touch", ".tmp", app.cacheDir); try { n.upload(t, path, false) } finally { t.delete() } } } else arcOp(p) { a -> a.touch(path) } }
        else -> withContext(Dispatchers.IO) { runCatching { File(path).createNewFile(); null }.getOrElse { s(R.string.fm_err_denied) } }
    }

    suspend fun rename(p: FmPane, from: String, to: String): String? = when (p.src) {
        "adb" -> failed(sh("mv ${q(from)} ${q(to)} 2>&1"))
        "smb" -> { val n = netFs(p); if (n != null) netOp { n.renameRemote(from, to, false) } else arcOp(p) { a -> a.rename(from, to) } }
        else -> withContext(Dispatchers.IO) { if (File(to).exists()) s(R.string.fm_err_exists) else if (File(from).renameTo(File(to))) null else s(R.string.fm_err_denied) }
    }

    /** Системные и корневые каталоги, которые нельзя удалять и переносить. */
    fun isProtected(path: String): Boolean {
        val t = path.trimEnd('/').ifEmpty { "/" }
        return t in setOf("/", "/sdcard", "/storage", "/storage/emulated", "/storage/emulated/0", "/system", "/data", "/vendor", "/proc", "/dev", "/mnt", "/etc", "/sdcard/Android", "/storage/emulated/0/Android") ||
            Regex("^/storage/[^/]+$").matches(t)
    }

    suspend fun delete(p: FmPane, path: String, j: OpProgress? = null): String? {
        if (isProtected(path)) return s(R.string.fm_protected)
        return when (p.src) {
            "adb" -> failed(sh("rm -rf ${q(path)} 2>&1", 10 * 60_000L))
            "smb" -> { val cf = p.smb as? CloudFs; val nc = p.net; val nfs = p.smb
                if (cf != null) { val fi = p.items.firstOrNull { it.name == path.substringAfterLast('/') }; cf.deletePath(path, fi?.isDir ?: false, Fm.deletePermanent, fi?.size ?: -1L) }
                else if (nc != null && nfs != null && nfs !is ArchiveFs) { val fi = p.items.firstOrNull { it.name == path.substringAfterLast('/') }; NetTrash.delete(app, nc, nfs, path, fi?.isDir ?: false, Fm.deletePermanent, fi?.size ?: -1L) }
                else arcOp(p) { it.delete(listOf(path)) } }
            else -> withContext(Dispatchers.IO) {
                val f = File(path)
                // телефон и карта памяти: сначала корзина (если включена и подходит), иначе удаление навсегда
                if (!Fm.deletePermanent && FmTrash.why(f) == FmTrash.Why.OK) {
                    val err = FmTrash.moveIn(f, j)
                    if (err != null) FmTrash.failed.add(path to err)
                    null
                } else if (LocalOps.delete(f, j)) null else s(R.string.fm_err_denied)
            }
        }
    }

    // ---------- копирование и перенос ----------
    suspend fun copyMove(src: FmPane, dst: FmPane, name: String, isDir: Boolean, move: Boolean, j: OpProgress? = null, toName: String? = null, deleteFirst: Boolean = false): String? {
        val from = join(src.path, name); val to = join(dst.path, toName ?: name)
        if (move && isProtected(from)) return s(R.string.fm_protected)
        // в облаке файл перезаписывается самим rclone, удалять заранее не нужно (и нельзя: удаление в облаке не поддержано)
        if (deleteFirst && !(dst.src == "smb" && dst.smb is CloudFs)) delete(dst, to)?.let { return it }
        return try {
            when {
                dst.src == "smb" && dst.smb is KeepFs -> if (src.src == "adb") unsupported() else toNet(src, dst, from, to, isDir, move, j)
                src.src == "smb" -> if (dst.src != "local") unsupported() else withContext(Dispatchers.IO) {
                    smbToLocal(src.smb!!, from, isDir, File(to), j)
                    if (move) { if (src.smb is ArchiveFs) (src.smb as ArchiveFs).delete(listOf(from)) else delete(src, from)?.let { throw java.io.IOException(it) } }
                    null
                }
                dst.src == "smb" && dst.smb is CloudFs -> if (src.src != "local") unsupported() else withContext(Dispatchers.IO) {
                    val f = File(from); j?.file(f.name)
                    (dst.smb as CloudFs).upload(f, to, isDir)
                    j?.bytes(if (isDir) 0L else f.length()); j?.item()
                    if (move) File(from).deleteRecursively()
                    null
                }
                dst.src == "smb" -> (dst.smb as? ArchiveFs)?.let { a ->
                    if (src.src != "local") unsupported() else withContext(Dispatchers.IO) { a.addFiles(listOf(File(from) to to), j); if (move) File(from).deleteRecursively(); null }
                } ?: unsupported()
                src.src == "local" && dst.src == "local" -> withContext(Dispatchers.IO) { localCopyMove(File(from), File(to), move, j) }
                remote && src.src != dst.src -> crossRemote(src, dst, from, to, isDir, move)
                else -> {
                    // на ADB: точный путь назначения (в том числе «имя (1)»), файл пишется во временный .fmpart и подменяется, чтобы не остаться с половиной
                    val parent = to.substringBeforeLast('/', "").ifEmpty { "/" }
                    val body = when {
                        move -> "mv -f ${q(from)} ${q(to)}"
                        isDir -> "cp -r ${q(from)} ${q(to)}"
                        else -> "cp -f ${q(from)} ${q(to)}.fmpart && mv -f ${q(to)}.fmpart ${q(to)}"
                    }
                    failed(sh("mkdir -p ${q(parent)} && $body 2>&1", 20 * 60_000L))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: LocalOps.Cancelled) { null } catch (e: Throwable) { nice(e) }
    }

    /**
     * Копирование и перенос в SFTP, FTP/FTPS, WebDAV, SMB: из памяти телефона, из архива, из облака и из другого сетевого источника (через временный файл в кэше,
     * потоком). Каждый файл пишется безопасно ([KeepFs.upload]: временное имя, проверка размера, замена), отмена убирает временные файлы.
     */
    private suspend fun toNet(src: FmPane, dst: FmPane, from: String, to: String, isDir: Boolean, move: Boolean, j: OpProgress?): String? = withContext(Dispatchers.IO) {
        val fs = dst.smb as KeepFs
        val tmpDir = File(app.cacheDir, "fmx").apply { mkdirs() }
        class K(val name: String, val path: String, val dir: Boolean)
        suspend fun kids(sp: String): List<K> = when {
            src.src == "local" -> File(sp).listFiles().orEmpty().filter { it.name != FmTrash.DIR }.map { K(it.name, it.absolutePath, it.isDirectory) }
            src.smb is ArchiveFs -> list(src, sp).map { K(it.name, join(sp, it.name), it.isDir) }
            else -> src.smb!!.list(sp).map { K(it.name, it.path, it.isDir) }
        }
        suspend fun put(sp: String, dp: String, dir: Boolean) {
            if (j?.cancelled == true) throw LocalOps.Cancelled()
            val name = sp.trimEnd('/').substringAfterLast('/')
            if (dir) {
                fs.ensureDir(dp)
                for (k in kids(sp)) put(k.path, join(dp, k.name), k.dir)
                j?.item(); return
            }
            j?.file(name)
            var tmp: File? = null
            val local: File = if (src.src == "local") File(sp) else File(tmpDir, System.nanoTime().toString(36) + "_" + name.replace(Regex("[^\\w.\\- ]"), "_")).also { tmp = it }
            try {
                if (tmp != null) { if (src.smb is ArchiveFs) (src.smb as ArchiveFs).extractTo(sp, tmp!!, null) else src.smb!!.copyTo(sp, tmp!!) }
                if (j?.cancelled == true) throw LocalOps.Cancelled()
                fs.upload(local, dp, true, j)
            } finally { tmp?.delete() }
            j?.item()
        }
        put(from, to, isDir)
        if (move) {
            if (src.src == "local") File(from).deleteRecursively()
            else if (src.smb is ArchiveFs) (src.smb as ArchiveFs).delete(listOf(from))
            else delete(src, from)?.let { throw java.io.IOException(it) }
        }
        null
    }

    private fun localCopyMove(from: File, to: File, move: Boolean, j: OpProgress?): String? {
        val pr = j ?: object : OpProgress { override val cancelled = false; override fun bytes(n: Long) {}; override fun file(name: String) {}; override fun item() {} }
        if (move) LocalOps.move(from, to, pr) else LocalOps.copy(from, to, pr)
        return null
    }

    private fun smbToLocal(smb: RemoteFs, url: String, isDir: Boolean, dest: File, j: OpProgress? = null) {
        if (smb is ArchiveFs) { smb.extractTo(url, dest, j); return }
        if (!isDir) { dest.parentFile?.mkdirs(); kotlinx.coroutines.runBlocking { smb.copyTo(url, dest) }; return }
        dest.mkdirs()
        for (e in kotlinx.coroutines.runBlocking { smb.list(url) }) smbToLocal(smb, e.path, e.isDir, File(dest, e.name), j)
    }

    /** Копирование между этим телефоном и удалённым устройством ADB (пути разных устройств). */
    private suspend fun crossRemote(src: FmPane, dst: FmPane, from: String, to: String, isDir: Boolean, move: Boolean): String? {
        if (src.src == "local") {
            suspend fun push(f: File, dest: String) {
                if (f.isDirectory) { sh("mkdir -p ${q(dest)}"); f.listFiles().orEmpty().forEach { push(it, join(dest, it.name)) } }
                else {
                    sh("mkdir -p ${q(dest.substringBeforeLast('/', "").ifEmpty { "/" })}")
                    val tmp = "$dest.fmpart"
                    f.inputStream().use { Adb.pushStream(it, f.length(), tmp) }
                    failed(sh("mv -f ${q(tmp)} ${q(dest)} 2>&1"))?.let { throw java.io.IOException(it) }
                }
            }
            withContext(Dispatchers.IO) { push(File(from), to) }
            if (move) File(from).deleteRecursively()
        } else {
            suspend fun pull(path: String, dir: Boolean, dest: File) {
                if (dir) {
                    dest.mkdirs()
                    val l = list(src, path)
                    l.forEach { pull(join(path, it.name), it.isDir, File(dest, it.name)) }
                } else {
                    dest.parentFile?.mkdirs()
                    val tmp = File(dest.parentFile, dest.name + ".fmpart")
                    try { Adb.pull(path, tmp); if (dest.exists()) dest.delete(); if (!tmp.renameTo(dest)) throw java.io.IOException("rename failed") } catch (e: Throwable) { tmp.delete(); throw e }
                }
            }
            pull(from, isDir, File(to))
            if (move) sh("rm -rf ${q(from)} 2>&1")
        }
        return null
    }

    // ---------- сведения ----------
    /** Права файла или папки: восьмеричный режим, владелец и группа. */
    class PermInfo(val mode: Int, val owner: String, val group: String, val isDir: Boolean)

    /** Итог чтения прав: [info], либо [fix] («wifi» или «pair»: программа сама не смогла подключить ADB), либо [err] с текстом. */
    class PermRes(val info: PermInfo? = null, val fix: String? = null, val err: String? = null)

    /** Читает права. Программа подключает свой ADB сама (root, затем беспроводная отладка), как для закрытых папок. */
    suspend fun permStat(p: FmPane, path: String): PermRes {
        if (p.src != "local" && p.src != "adb") return PermRes(err = s(R.string.perm_err_kind))
        if (p.src == "local" && remote) return PermRes(err = s(R.string.perm_err_remote))
        if (!Adb.isRoot) { val fix = ensurePriv(); if (fix != null) return PermRes(fix = fix) }
        val o = runCatching { sh("stat -c '%a|%U|%G|%F' ${q(path)} 2>&1").trim() }.getOrDefault("")
        val ps = o.split('|')
        if (ps.size < 4) return PermRes(err = o.ifEmpty { s(R.string.perm_err_read) })
        return PermRes(info = PermInfo(ps[0].toIntOrNull(8) ?: return PermRes(err = o), ps[1], ps[2], ps[3].contains("directory")))
    }

    private val permName = Regex("^[A-Za-z0-9_.-]{0,40}$")

    /** chmod и chown для списка путей; recursive действует на содержимое папок. Возвращает текст ошибки или null. */
    suspend fun applyPerms(p: FmPane, paths: List<String>, mode: String, owner: String, group: String, recursive: Boolean): String? {
        if (!Regex("^[0-7]{3,4}$").matches(mode)) return "mode?"
        if (!permName.matches(owner) || !permName.matches(group)) return "name?"
        if (!Adb.isRoot) { val fix = ensurePriv(); if (fix != null) return s(if (fix == "wifi") R.string.fm_fix_wifi_text else R.string.fm_fix_pair_text) }
        val r = if (recursive) "-R " else ""
        val own = if (owner.isNotEmpty() || group.isNotEmpty()) (owner + (if (group.isNotEmpty()) ":$group" else "")) else ""
        val cmd = StringBuilder()
        for (path in paths) {
            cmd.append("chmod ").append(r).append(mode).append(' ').append(q(path)).append(" 2>&1; ")
            if (own.isNotEmpty()) cmd.append("chown ").append(r).append(own).append(' ').append(q(path)).append(" 2>&1; ")
        }
        val out = runCatching { sh(cmd.toString(), 180_000L).trim() }.getOrElse { return nice(it) }
        return out.ifEmpty { null }
    }

    private val pkgInPath = Regex("^(?:/storage/emulated/\\d+|/sdcard|/storage/self/primary|/data/media/\\d+)/Android/(?:data|obb|media)/([A-Za-z0-9_.]+\\.[A-Za-z0-9_.]+)(?:/.*)?$|^/data/(?:data|user/\\d+|user_de/\\d+)/([A-Za-z0-9_.]+\\.[A-Za-z0-9_.]+)(?:/.*)?$")

    /** 9.0: имя пакета для окна «Свойства»: у APK из самого файла, у папки (и всего внутри) в Android/data, obb, media и /data/data по имени папки. */
    private suspend fun pkgOf(p: FmPane, path: String): String? {
        pkgInPath.matchEntire(path.trimEnd('/'))?.let { m -> return m.groupValues[1].ifEmpty { m.groupValues[2] } }
        if (!path.lowercase().endsWith(".apk")) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                when (p.src) {
                    "local" -> app.packageManager.getPackageArchiveInfo(path, 0)?.packageName
                    "adb" -> {
                        val size = sh("stat -c %s ${q(path)} 2>/dev/null").trim().toLongOrNull() ?: Long.MAX_VALUE
                        if (size > 120L * 1024 * 1024) null else {
                            val tmp = File(app.cacheDir, "pkgprobe.apk"); tmp.delete()
                            Adb.pull(path, tmp)
                            app.packageManager.getPackageArchiveInfo(tmp.path, 0)?.packageName.also { tmp.delete() }
                        }
                    }
                    else -> null
                }
            }.getOrNull()
        }
    }

    suspend fun props(p: FmPane, path: String): String {
        val sb = StringBuilder()
        sb.append(s(R.string.fm_path)).append(": ").append(path).append('\n')
        pkgOf(p, path)?.let { sb.append(s(R.string.fm_package)).append(": ").append(it).append('\n') }
        when (p.src) {
            "adb" -> {
                val st = runCatching { sh("stat -c '%F|%s|%A|%U:%G|%y' ${q(path)} 2>&1").trim() }.getOrDefault("")
                val du = runCatching { sh("du -sk ${q(path)} 2>/dev/null").trim().substringBefore('\t').substringBefore(' ') }.getOrDefault("")
                val parts = st.split('|')
                if (parts.size >= 5) {
                    sb.append(s(R.string.fm_type)).append(": ").append(parts[0]).append('\n')
                    sb.append(s(R.string.fm_size)).append(": ").append(fmSize(du.toLongOrNull()?.times(1024) ?: parts[1].toLongOrNull() ?: 0L)).append('\n')
                    sb.append(s(R.string.fm_rights)).append(": ").append(parts[2]).append('\n')
                    sb.append(s(R.string.fm_owner)).append(": ").append(parts[3]).append('\n')
                    sb.append(s(R.string.fm_modified)).append(": ").append(parts[4].substringBefore('.')).append('\n')
                } else sb.append(st).append('\n')
            }
            "smb" -> (p.smb as? ArchiveFs)?.let { a -> sb.append(withContext(Dispatchers.IO) { runCatching { a.info(path) }.getOrElse { nice(it) } }).append('\n') }
            else -> withContext(Dispatchers.IO) {
                val f = File(path)
                val size = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()
                val cnt = if (f.isDirectory) f.walkTopDown().count() - 1 else 0
                sb.append(s(R.string.fm_type)).append(": ").append(if (f.isDirectory) "dir ($cnt)" else "file").append('\n')
                sb.append(s(R.string.fm_size)).append(": ").append(fmSize(size)).append('\n')
                sb.append(s(R.string.fm_rights)).append(": ").append(if (f.canRead()) "r" else "-").append(if (f.canWrite()) "w" else "-").append(if (f.canExecute()) "x" else "-").append('\n')
                sb.append(s(R.string.fm_modified)).append(": ").append(SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US).format(Date(f.lastModified()))).append('\n')
                if (f.isFile && Arc.kindOf(f.name) != null) sb.append('\n').append(runCatching { archiveSummary(f) }.getOrDefault(""))
            }
        }
        return sb.toString().trim()
    }

    suspend fun search(p: FmPane, root: String, text: String): List<String> = when (p.src) {
        "adb" -> sh("find ${q(root)} -maxdepth 8 -iname ${q("*$text*")} 2>/dev/null | head -300", 60_000L).lines().filter { it.isNotBlank() }
        "smb" -> emptyList()
        else -> withContext(Dispatchers.IO) { File(root).walkTopDown().onEnter { it.name != FmTrash.DIR }.maxDepth(8).filter { it.name.contains(text, true) }.take(300).map { it.absolutePath }.toList() }
    }

    suspend fun readText(p: FmPane, path: String): String = when (p.src) {
        "adb" -> sh("head -c 30000 ${q(path)} 2>&1")
        "smb" -> String(p.smb!!.head(path, 30000), Charsets.UTF_8)
        else -> withContext(Dispatchers.IO) { File(path).inputStream().use { String(it.readNBytesCompat(30000), Charsets.UTF_8) } }
    }

    private fun InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = ByteArray(n); var got = 0
        while (got < n) { val r = read(buf, got, n - got); if (r < 0) break; got += r }
        return buf.copyOf(got)
    }

    // ---------- архивы ----------
    fun isArchive(n: String) = Arc.kindOf(n).let { it != null && n.lowercase().let { l -> !(l.endsWith(".docx") || l.endsWith(".xlsx") || l.endsWith(".pptx") || l.endsWith(".odt") || l.endsWith(".ods") || l.endsWith(".odp") || l.endsWith(".epub")) } }

    /** Сведения об архиве: формат, число файлов, размеры; для APK ещё пакет, версия, разрешения, подпись. */
    fun archiveSummary(f: File): String {
        val sb = StringBuilder()
        runCatching {
            Arc.open(f).use { r ->
                val files = r.entries.count { !it.isDir }
                val un = r.totalSize; val cs = r.entries.sumOf { maxOf(it.csize, 0L) }
                sb.append(s(R.string.arc_format)).append(": ").append(r.kind.name).append('\n').append(s(R.string.arc_files_count, files)).append('\n').append(s(R.string.arc_size_unpacked, fmSize(un))).append('\n')
                if (cs > 0 && un > 0) sb.append(s(R.string.arc_size_packed, fmSize(cs))).append("  ").append(s(R.string.arc_ratio, 100 - (cs * 100 / un).toInt())).append('\n')
                if (r.entries.any { it.encrypted }) sb.append(s(R.string.arc_encrypted)).append('\n')
            }
        }.onFailure { sb.append(nice(it)).append('\n') }
        if (f.name.lowercase().endsWith(".apk")) sb.append(apkSummary(f))
        return sb.toString().trim()
    }

    @Suppress("DEPRECATION")
    private fun apkSummary(f: File): String {
        val pm = app.packageManager
        val sb = StringBuilder()
        runCatching {
            val flags = android.content.pm.PackageManager.GET_PERMISSIONS or android.content.pm.PackageManager.GET_ACTIVITIES or (if (android.os.Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES)
            val pi = pm.getPackageArchiveInfo(f.absolutePath, flags) ?: return@runCatching
            pi.applicationInfo?.let { it.sourceDir = f.absolutePath; it.publicSourceDir = f.absolutePath; sb.append("\n").append(pm.getApplicationLabel(it)).append('\n') }
            sb.append(pi.packageName).append("  ").append(pi.versionName ?: "").append(" (").append(if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()).append(")\n")
            pi.applicationInfo?.let { sb.append("minSdk ").append(it.minSdkVersion).append(", targetSdk ").append(it.targetSdkVersion).append('\n') }
            val perms = pi.requestedPermissions?.toList().orEmpty()
            if (perms.isNotEmpty()) sb.append(s(R.string.arc_permissions, perms.size)).append(":\n").append(perms.take(25).joinToString("\n") { "  " + it.substringAfterLast('.') }).append(if (perms.size > 25) "\n  …" else "").append('\n')
            val acts = pi.activities?.map { it.name.substringAfterLast('.') }.orEmpty()
            if (acts.isNotEmpty()) sb.append("Activity (").append(acts.size).append("): ").append(acts.take(8).joinToString(", ")).append(if (acts.size > 8) ", …" else "").append('\n')
            val sig = if (android.os.Build.VERSION.SDK_INT >= 28) pi.signingInfo?.apkContentsSigners else pi.signatures
            sig?.firstOrNull()?.let { sg ->
                val d = java.security.MessageDigest.getInstance("SHA-256").digest(sg.toByteArray()).joinToString("") { "%02X".format(it) }
                sb.append(s(R.string.arc_cert_sha)).append(": ").append(d.chunked(2).joinToString(":")).append('\n')
            }
            val abis = runCatching { Arc.open(f).use { r -> r.entries.filter { it.path.startsWith("lib/") }.mapNotNull { it.path.split('/').getOrNull(1) }.distinct() } }.getOrDefault(emptyList())
            if (abis.isNotEmpty()) sb.append("ABI: ").append(abis.joinToString(", ")).append('\n')
        }
        return sb.toString()
    }
    private fun isTar(n: String) = n.lowercase().let { it.endsWith(".tar") || it.endsWith(".tar.gz") || it.endsWith(".tgz") }

    suspend fun archiveList(p: FmPane, path: String): List<String> {
        val l = path.lowercase()
        return when (p.src) {
            "adb" -> sh(if (isTar(l)) "tar tf ${q(path)} 2>&1 | head -500" else "unzip -l ${q(path)} 2>&1 | head -500", 40_000L).lines().filter { it.isNotBlank() }
            "smb" -> listOf(unsupported())
            else -> withContext(Dispatchers.IO) {
                val out = ArrayList<String>()
                if (isTar(l)) tarWalk(tarIn(File(path))) { n, dir, size, _ -> if (out.size < 500) out.add((if (dir) "[dir]  " else fmSize(size).padEnd(9)) + n) }
                else if (l.endsWith(".gz")) out.add(File(path).name.removeSuffix(".gz"))
                else ZipFile(File(path)).use { z -> z.entries().asSequence().take(500).forEach { out.add((if (it.isDirectory) "[dir]  " else fmSize(it.size).padEnd(9)) + it.name) } }
                out
            }
        }
    }

    suspend fun extract(src: FmPane, name: String, to: FmPane): String? {
        val path = join(src.path, name); val l = name.lowercase()
        return try {
            when {
                src.src == "smb" || to.src == "smb" -> unsupported()
                src.src == "local" && to.src == "local" -> withContext(Dispatchers.IO) {
                    val dest = File(to.path)
                    when {
                        isTar(l) -> tarWalk(tarIn(File(path))) { n, dir, _, ins -> val f = safe(dest, n); if (dir) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { o -> ins.copyTo(o) } } }
                        l.endsWith(".gz") -> GZIPInputStream(File(path).inputStream()).use { i -> File(dest, name.removeSuffix(".gz")).outputStream().use { o -> i.copyTo(o) } }
                        else -> ZipFile(File(path)).use { z -> z.entries().asSequence().forEach { e ->
                            val f = safe(dest, e.name)
                            if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); z.getInputStream(e).use { i -> f.outputStream().use { o -> i.copyTo(o) } } }
                        } }
                    }
                    null
                }
                remote && src.src != to.src -> s(R.string.fm_err_unsupported)
                else -> {
                    val d = q(to.path)
                    failed(sh(if (isTar(l)) "tar xf ${q(path)} -C $d 2>&1" else "unzip -o ${q(path)} -d $d 2>&1", 20 * 60_000L))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { nice(e) }
    }

    /** Создание архива tar.gz (программы zip на телефоне нет). */
    suspend fun makeTar(p: FmPane, names: List<String>, outName: String): String? = try {
        when (p.src) {
            "adb" -> failed(sh("tar czf ${q(join(p.path, outName))} -C ${q(p.path)} ${names.joinToString(" ") { q(it) }} 2>&1", 20 * 60_000L))
            "smb" -> unsupported()
            else -> withContext(Dispatchers.IO) {
                GZIPOutputStream(File(join(p.path, outName)).outputStream().buffered()).use { o ->
                    names.forEach { tarAdd(o, File(join(p.path, it)), it) }
                    o.write(ByteArray(1024))
                }
                null
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { nice(e) }

    private fun safe(dest: File, name: String): File {
        val f = File(dest, name)
        if (!f.canonicalPath.startsWith(dest.canonicalPath)) error("bad path: $name")
        return f
    }

    private fun tarIn(f: File): InputStream = f.inputStream().buffered().let { if (f.name.lowercase().let { n -> n.endsWith(".gz") || n.endsWith(".tgz") }) GZIPInputStream(it) else it }

    private fun tarAdd(out: OutputStream, f: File, name: String) {
        val dir = f.isDirectory
        val full = if (dir) "$name/" else name
        val hdr = ByteArray(512)
        fun put(off: Int, text: String, len: Int) { val b = text.toByteArray(Charsets.UTF_8); System.arraycopy(b, 0, hdr, off, minOf(b.size, len)) }
        var prefix = ""; var nm = full
        if (full.toByteArray(Charsets.UTF_8).size > 100) {
            var cut = -1
            var i = full.indexOf('/')
            while (i >= 0) {
                val rest = full.substring(i + 1)
                if (full.substring(0, i).toByteArray().size <= 155 && rest.toByteArray().size <= 100) { cut = i; break }
                i = full.indexOf('/', i + 1)
            }
            if (cut < 0) error("name too long: $full")
            prefix = full.substring(0, cut); nm = full.substring(cut + 1)
        }
        put(0, nm, 100)
        put(100, if (dir) "0000755" else "0000644", 8)
        put(108, "0000000", 8); put(116, "0000000", 8)
        put(124, "%011o".format(if (dir) 0L else f.length()), 12)
        put(136, "%011o".format(f.lastModified() / 1000), 12)
        for (k in 148 until 156) hdr[k] = ' '.code.toByte()
        hdr[156] = (if (dir) '5' else '0').code.toByte()
        put(257, "ustar", 6); put(263, "00", 2)
        put(345, prefix, 155)
        val sum = hdr.sumOf { it.toInt() and 0xff }
        put(148, "%06o".format(sum), 6); hdr[154] = 0; hdr[155] = ' '.code.toByte()
        out.write(hdr)
        if (dir) f.listFiles().orEmpty().sortedBy { it.name }.forEach { tarAdd(out, it, "$name/${it.name}") }
        else {
            f.inputStream().use { it.copyTo(out) }
            val pad = (512 - (f.length() % 512)).toInt() % 512
            if (pad > 0) out.write(ByteArray(pad))
        }
    }

    private class Limited(val ins: InputStream, var left: Long) : InputStream() {
        override fun read(): Int { if (left <= 0) return -1; val r = ins.read(); if (r >= 0) left--; return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = ins.read(b, off, minOf(len.toLong(), left).toInt()); if (r > 0) left -= r; return r
        }
    }

    private fun readFully(i: InputStream, b: ByteArray): Boolean {
        var got = 0
        while (got < b.size) { val r = i.read(b, got, b.size - got); if (r < 0) return false; got += r }
        return true
    }

    private fun skipFully(i: InputStream, n: Long) { var left = n; val buf = ByteArray(8192); while (left > 0) { val r = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) return; left -= r } }

    private fun cstr(h: ByteArray, off: Int, len: Int): String { var e = off; while (e < off + len && h[e].toInt() != 0) e++; return String(h, off, e - off, Charsets.UTF_8) }

    /** Обход tar: для каждой записи вызывается обработчик с именем, признаком папки, размером и потоком содержимого. */
    private fun tarWalk(inp: InputStream, handler: (String, Boolean, Long, InputStream) -> Unit) {
        inp.use {
            val h = ByteArray(512)
            var longName: String? = null
            while (readFully(it, h)) {
                if (h.all { b -> b.toInt() == 0 }) break
                var name = cstr(h, 0, 100)
                val pre = cstr(h, 345, 155)
                if (pre.isNotEmpty()) name = "$pre/$name"
                val size = cstr(h, 124, 12).trim().ifEmpty { "0" }.toLong(8)
                val type = h[156].toInt().toChar()
                val pad = (512 - (size % 512)) % 512
                if (type == 'L') { longName = String(Limited(it, size).readNBytesCompat(size.toInt()), Charsets.UTF_8).trimEnd('\u0000'); skipFully(it, pad); continue }
                if (type == 'x' || type == 'g') { skipFully(it, size + pad); continue }
                if (longName != null) { name = longName; longName = null }
                val lim = Limited(it, size)
                handler(name.trimEnd('/'), type == '5' || name.endsWith("/"), size, lim)
                skipFully(it, lim.left + pad)
            }
        }
    }
}

