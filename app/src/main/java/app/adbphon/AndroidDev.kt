package app.adbphon

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import io.github.muntashirakon.adb.AdbStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * «Устройства» → Android (10.11.2026): другие телефоны, планшеты и приставки как источник файлового менеджера, экран и консоль.
 * У каждого устройства СВОЁ ADB-соединение ([AdbLink], свой [AdbConn] с общим ключом программы), поэтому две панели «Файлов»
 * смотрят на разные устройства одновременно, а глобальное подключение «Связи» ([Adb]) не трогается.
 * Адрес: «хост[:порт]», порт по умолчанию 5555 (adbd по tcpip). Второй адрес (alt) — запасной путь (например, 100.x и LAN).
 * Адрес Tailscale (100.x) 11.11.2026: проверено на приставке x96-2 (Android 9, root, Tailscale 1.104.5): ВХОДЯЩИЙ TCP на 100.x в adbd (порт 5555)
 * Android-клиент Tailscale пропускает (adb connect/shell с ПК по 100.90.224.17 работают; слушающий на всех адресах сокет принимает). Прежнее
 * утверждение «не пропускает» было неверным. Реальные причины неудач: (1) первое подключение к спящему/«простаивающему» узлу Tailscale
 * занимает несколько секунд (пока строится путь), а проба ждала 1,5–2,5 с — теперь для 100.x ждём дольше и повторяем; (2) ЭТОТ телефон
 * сам не в сети Tailscale: Android держит один VPN за раз, и при постоянном OpenVPN (A7, A14) Tailscale запустить нельзя, 100.x недоступен
 * (понятное сообщение вместо «Tailscale не пропускает»); (3) устройство-цель без Tailscale или с выключенной отладкой по сети.
 */
data class AdbDev(val name: String, val addr: String, val alt: String = "") {
    val host: String get() = addr.substringBefore(':')
    val port: Int get() = addr.substringAfter(':', "5555").toIntOrNull() ?: 5555
    val title: String get() = name.ifBlank { addr }
    /** Адрес Tailscale (100.x), если задан, и адрес обычной сети. */
    val tsAddr: String? get() = listOf(addr, alt).firstOrNull { it.startsWith("100.") }
    val lanAddr: String? get() = listOf(addr, alt).firstOrNull { it.isNotBlank() && !it.startsWith("100.") }
    val key: String get() = "andr:" + norm(addr)
    /** Адреса (без порта), по которым устройство могли записать в «Люди» (адрес Tailscale и адрес сети). */
    fun callIps(): List<String> = listOfNotNull(tsAddr, lanAddr).map { it.substringBefore(':') }.distinct()
    val addrs: List<String> get() = listOf(addr, alt).filter { it.isNotBlank() }.map { norm(it) }.distinct()
    /** Подключение для файлового менеджера: в «user» ездит запасной адрес. */
    fun conn(): NetConn = NetConn("andr", host, port, if (alt.isBlank()) "" else norm(alt), "", "", "", title)
    companion object {
        fun norm(a: String): String { val t = a.trim(); return if (t.contains(':')) t else "$t:5555" }
    }
}

private fun devPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("android_dev", Context.MODE_PRIVATE)

fun loadAndroid(ctx: Context): List<AdbDev> =
    (devPrefs(ctx).getString("list", "") ?: "").lines().mapNotNull { l ->
        val p = l.split('|'); if (p.size < 2 || p[1].isBlank()) null else AdbDev(p[0], p[1], p.getOrElse(2) { "" })
    }

fun saveAndroid(ctx: Context, l: List<AdbDev>) {
    devPrefs(ctx).edit().putString("list", l.joinToString("\n") { it.name.replace("|", " ").replace("\n", " ") + "|" + AdbDev.norm(it.addr) + "|" + (if (it.alt.isBlank()) "" else AdbDev.norm(it.alt)) }).apply()
}

/** Избранные ADB-подключения из «Связи» один раз переносятся в список устройств (новые добавляются сами; забытые не возвращаются). */
fun androidSync(ctx: Context): List<AdbDev> {
    val sp = devPrefs(ctx)
    val imported = (sp.getString("imported", "") ?: "").split('\n').filter { it.isNotBlank() }.toMutableSet()
    val cur = loadAndroid(ctx).toMutableList()
    var changed = false
    (ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getString("favs", "") ?: "").split("\n").filter { it.isNotBlank() }.forEach { line ->
        val p = line.split("\t"); if (p.size < 3) return@forEach
        val host = p[1].trim(); val port = p[2].toIntOrNull() ?: 5555
        if (host.isBlank() || host.startsWith("127.") || host == "localhost") return@forEach
        val a = "$host:$port"
        if (a in imported) return@forEach
        imported += a; changed = true
        if (cur.none { it.addrs.contains(a) }) cur += AdbDev(p[0].ifBlank { host }, a)
    }
    if (changed) { saveAndroid(ctx, cur); sp.edit().putString("imported", imported.joinToString("\n")).apply() }
    return cur
}

/** Забыть устройство: убрать из списка (повторного переноса из «Связи» не будет). */
fun androidForget(ctx: Context, d: AdbDev) {
    AdbLinks.drop(d.addrs)
    saveAndroid(ctx, loadAndroid(ctx).filter { it.key != d.key })
    val sp = devPrefs(ctx)
    val imp = (sp.getString("imported", "") ?: "").split('\n').filter { it.isNotBlank() }.toMutableSet().apply { addAll(d.addrs) }
    sp.edit().putString("imported", imp.joinToString("\n")).apply()
}

/** Доступность Android-устройства (порт ADB отвечает) и какой из адресов сработал. */
object AdbStatus {
    val up = mutableStateMapOf<String, Boolean>()
    val via = mutableStateMapOf<String, String>()
    fun reach(addr: String, ms: Int = 1500): Boolean = runCatching {
        Socket().use { s -> s.connect(InetSocketAddress(addr.substringBefore(':'), addr.substringAfter(':', "5555").toIntOrNull() ?: 5555), ms); true }
    }.getOrDefault(false)
    /** Терпеливая проба для подключения: у адреса Tailscale (100.x) первые пакеты уходят, пока строится путь до узла, поэтому ждём 3 с и пробуем второй раз (всего ≤ 6 с: операции списка в «Файлах» обрываются через 8 с и сообщение об ошибке должно успеть дойти до человека). */
    fun reachPatient(addr: String): Boolean = if (!addr.startsWith("100.")) reach(addr, 2500) else (reach(addr, 3000) || reach(addr, 3000))
    /** У этого телефона есть адрес сети Tailscale (100.64.0.0/10), то есть он сам сейчас в tailnet. */
    fun selfInTailnet(): Boolean = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence().flatMap { it.inetAddresses.asSequence() }
            .any { a -> a is java.net.Inet4Address && (a.address[0].toInt() and 0xff) == 100 && (a.address[1].toInt() and 0xff) in 64..127 }
    }.getOrDefault(false)
    suspend fun probe(d: AdbDev) {
        val ok = withContext(Dispatchers.IO) { d.addrs.firstOrNull { reach(it) } }
        up[d.key] = ok != null
        if (ok != null) via[d.key] = ok else via.remove(d.key)
    }
}

class AdbLinkError(msg: String) : IOException(msg)

/** Одно независимое ADB-соединение с устройством по сети. */
class AdbLink(val host: String, val port: Int) {
    private val mgr = AdbConn(FmIo.app)
    private val mutex = Mutex()
    val addr: String get() = "$host:$port"
    val connected: Boolean get() = runCatching { mgr.isConnected }.getOrDefault(false)

    private fun msgUnreach(): String = FmIo.app.getString(
        if (!host.startsWith("100.")) R.string.adbd_err_unreach else if (AdbStatus.selfInTailnet()) R.string.vw_err_ts_peer else R.string.vw_err_ts_self, addr)

    suspend fun ensure() {
        if (connected) return
        mutex.withLock {
            if (connected) return
            if (!withContext(Dispatchers.IO) { AdbStatus.reachPatient(addr) }) throw AdbLinkError(msgUnreach())
            var last: Throwable? = null
            for (attempt in 1..2) {
                // первое подключение с этого телефона: пока владелец устройства нажимает «Разрешить», попытка обрывается; вторая проходит
                runCatching { mgr.disconnect() }
                val r = runCatching { withTimeout(25_000) { runInterruptible(Dispatchers.IO) { mgr.connect(host, port) } } }
                if (r.getOrNull() == true) return
                last = r.exceptionOrNull()
                if (last is CancellationException && last !is kotlinx.coroutines.TimeoutCancellationException) throw last
            }
            throw AdbLinkError(FmIo.app.getString(R.string.adbd_err_auth, addr) + (last?.message?.let { " ($it)" } ?: ""))
        }
    }

    fun disconnect() { runCatching { mgr.disconnect() } }

    fun open(service: String): AdbStream = mgr.openStream(AdbText.safe(service))

    /** Читает поток до конца (или метки) с ограничением по времени: прочитанное считается ответом, зависший поток закрывается. */
    private fun readAll(s: AdbStream, timeoutMs: Long, mark: String?): String {
        val out = ByteArrayOutputStream()
        var error: Throwable? = null
        val reader = Thread {
            try {
                val input = s.openInputStream(); val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    synchronized(out) { out.write(buf, 0, n) }
                    if (mark != null && synchronized(out) { out.toString("UTF-8") }.trimEnd().endsWith(mark)) break
                }
            } catch (e: Throwable) { error = e }
        }
        reader.isDaemon = true; reader.start(); reader.join(timeoutMs)
        if (reader.isAlive) { runCatching { s.close() }; reader.join(2000) } else runCatching { s.close() }
        val text = synchronized(out) { out.toString("UTF-8") }.let { if (mark != null) it.trimEnd().removeSuffix(mark) else it }.trim()
        val e = error
        if (e != null && text.isEmpty() && e.message?.contains("closed", ignoreCase = true) != true) throw e
        return text
    }

    /** Команда оболочки (без терминала: вывод чистый, без «\r»). */
    suspend fun shell(cmd: String, timeoutMs: Long = 25_000): String {
        ensure()
        // службой shell: (с терминалом) toybox ls не экранирует пробелы в именах, как это делает при выводе в канал (exec:)
        return withContext(Dispatchers.IO) { readAll(open("shell:$cmd; echo $MARK"), timeoutMs, MARK).replace(13.toChar().toString(), "") }
    }

    /** Вывод команды как байты (скриншот PNG и т.п.). */
    suspend fun execBytes(cmd: String, timeoutMs: Long = 20_000, max: Int = 40_000_000): ByteArray {
        ensure()
        return withContext(Dispatchers.IO) {
            val s = open("exec:$cmd"); val out = ByteArrayOutputStream()
            val t = Thread { runCatching { val i = s.openInputStream(); val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; synchronized(out) { if (out.size() < max) out.write(b, 0, n) } } } }
            t.isDaemon = true; t.start(); t.join(timeoutMs)
            if (t.isAlive) { runCatching { s.close() }; t.join(1500) } else runCatching { s.close() }
            synchronized(out) { out.toByteArray() }
        }
    }

    /** Читает поток до конца: библиотека ADB для коротких файлов вместо конца потока бросает «Stream closed», это считается концом. */
    private fun pump(i: InputStream, o: java.io.OutputStream): Long {
        val b = ByteArray(128 * 1024); var tot = 0L
        while (true) {
            val n = try { i.read(b) } catch (e: IOException) { if (e.message?.contains("closed", ignoreCase = true) == true) -1 else throw e }
            if (n < 0) break
            o.write(b, 0, n); tot += n
        }
        return tot
    }

    suspend fun pull(path: String, dest: File) {
        ensure()
        val got = withContext(Dispatchers.IO) {
            val s = open("exec:cat " + q(path))
            try { s.openInputStream().use { i -> dest.outputStream().use { o -> pump(i, o) } } } finally { runCatching { s.close() } }
        }
        // размер на устройстве сверяется с полученным: обрыв связи не должен выдаваться за целый файл
        val want = shell("wc -c < " + q(path) + " 2>&1", 15_000).trim().toLongOrNull()
        if (want == null) throw IOException(FmIo.app.getString(R.string.adbd_err_read, path.substringAfterLast('/')))
        if (want != got) throw IOException(FmIo.app.getString(R.string.adbd_err_short, got.toString(), want.toString()))
    }

    suspend fun head(path: String, n: Int): ByteArray {
        ensure()
        return withContext(Dispatchers.IO) {
            val s = open("exec:head -c $n " + q(path))
            try {
                val bo = ByteArrayOutputStream()
                s.openInputStream().use { i -> pump(i, bo) }
                bo.toByteArray().let { if (it.size > n) it.copyOf(n) else it }
            } finally { runCatching { s.close() } }
        }
    }

    /** Записать локальный файл ровно по пути на устройстве (поток; отмена через [OpProgress]); размер проверяется после записи. */
    suspend fun push(src: File, dest: String, j: OpProgress?) {
        ensure()
        withContext(Dispatchers.IO) {
            val size = src.length()
            val par = dest.substringBeforeLast('/', "")
            if (par.isNotEmpty()) readAll(open("exec:mkdir -p " + q(par) + "; echo $MARK"), 15_000, MARK)
            val s = open("exec:head -c $size > " + q(dest))
            try {
                val out = s.openOutputStream()
                try { UpStream(src, j).use { it.copyTo(out, 128 * 1024) } } catch (e: Throwable) { runCatching { s.close() }; runCatching { readAll(open("exec:rm -f " + q(dest) + "; echo $MARK"), 10_000, MARK) }; throw e }
                out.flush()
                readAll(s, 600_000, null)
            } finally { runCatching { s.close() } }
            val have = readAll(open("exec:wc -c < " + q(dest) + " 2>&1; echo $MARK"), 15_000, MARK).trim().toLongOrNull()
            if (have != size) throw IOException(FmIo.app.getString(R.string.adbd_err_short, (have ?: -1L).toString(), size.toString()))
        }
    }

    /** Постоянная оболочка (консоль, ввод касаний). */
    class Session(private val s: AdbStream) {
        private val out = s.openOutputStream()
        @Synchronized fun write(b: ByteArray) { out.write(b); out.flush() }
        fun line(t: String) = write((t + "\n").toByteArray(Charsets.UTF_8))
        fun close() { runCatching { s.close() } }
        val input: InputStream get() = s.openInputStream()
    }
    suspend fun session(): Session { ensure(); return withContext(Dispatchers.IO) { Session(open("shell:")) } }

    companion object {
        const val MARK = "@@ADBPHON_END@@"
        fun q(p: String) = "'" + p.replace("'", "'\\''") + "'"
    }
}

object AdbLinks {
    private val m = HashMap<String, AdbLink>()
    @Synchronized fun of(addr: String): AdbLink {
        val a = AdbDev.norm(addr)
        return m.getOrPut(a) { AdbLink(a.substringBefore(':'), a.substringAfter(':').toIntOrNull() ?: 5555) }
    }
    @Synchronized fun drop(addrs: List<String>) { addrs.forEach { m.remove(AdbDev.norm(it))?.disconnect() } }
}

/** Рабочее соединение с устройством: сначала адреса, на которых порт отвечает; если ни один не подключился, последняя ошибка. */
suspend fun adbLinkFor(addrs: List<String>): AdbLink {
    val order = withContext(Dispatchers.IO) { addrs.filter { it.isNotBlank() }.map { AdbDev.norm(it) }.distinct().sortedBy { if (AdbStatus.reach(it, 1500)) 0 else 1 } }
    var last: Throwable? = null
    for (a in order) {
        val l = AdbLinks.of(a)
        try { l.ensure(); return l } catch (e: CancellationException) { throw e } catch (e: Throwable) { last = e }
    }
    throw last ?: AdbLinkError("no address")
}

/** Файлы Android-устройства по ADB как источник файлового менеджера: чтение, копирование туда и обратно, перенос, удаление, переименование, новая папка. */
class AndroidFs(private val host: String, private val port: Int, private val alt: String) : RemoteFs {
    @Volatile private var cur: AdbLink? = null
    private suspend fun link(): AdbLink {
        cur?.let { if (it.connected) return it }
        return adbLinkFor(listOf("$host:$port", alt)).also { cur = it }
    }

    override val root get() = "/sdcard"
    override val label get() = "adb://$host"
    override fun close() { /* соединение общее для панелей и экрана: закрывается командой «Отключить» */ }
    override suspend fun ping(path: String) { link().shell("echo ok", 8_000) }

    private val line = Regex("""^(\S{10})\s+\d+\s+\S+\s+\S+\s+(\d+)\s+\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}\s+(.+)$""")
    private fun q(p: String) = AdbLink.q(p)
    private fun isApk(n: String) = n.lowercase().endsWith(".apk")

    private fun check(out: String, write: Boolean) {
        val o = out.lowercase()
        if (o.contains("permission denied") || o.contains("read-only file system") || o.contains("operation not permitted")) throw if (write) NoWriteRights() else IOException(out.lines().first())
        if (o.contains("no such file")) throw IOException(out.lines().first())
    }

    override suspend fun list(path: String): List<FsEntry> {
        // «| cat»: toybox ls при выводе прямо в сокет экранирует пробелы в именах («a\ b»), в канал выводит как есть
        val out = link().shell("ls -lA ${q(path.trimEnd('/') + "/")} 2>&1 | cat")
        if ((out.contains("Permission denied") || out.contains("No such file")) && out.lines().none { line.find(it.trim()) != null }) throw IOException(out.lines().first())
        return out.lines().mapNotNull { l ->
            val m = line.find(l.trim()) ?: return@mapNotNull null
            val name = m.groupValues[3].substringBefore(" -> ")
            if (name.startsWith(".")) return@mapNotNull null
            val t = m.groupValues[1][0]
            FsEntry(name, path.trimEnd('/') + "/" + name, t == 'd' || (t == 'l' && !isApk(name)), m.groupValues[2].toLongOrNull() ?: 0L)
        }.sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override suspend fun copyTo(path: String, dest: File) { link().pull(path, dest) }
    override suspend fun head(path: String, n: Int): ByteArray = link().head(path, n)

    override fun parent(path: String): String? {
        val t = path.trimEnd('/')
        if (t.isEmpty()) return null
        return t.substringBeforeLast('/', "").ifEmpty { "/" }.takeIf { t != "/" }
    }

    override suspend fun roots(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val l = link()
        for (c in listOf("/sdcard", "/storage", "/mnt/media_rw", "/mnt/usb", "/mnt/usb_storage", "/mnt/sdcard", "/mnt/extsd", "/data/media/0", "/mnt")) {
            val r = runCatching { l.shell("ls -d ${q(c)} 2>&1") }.getOrDefault("")
            if (r.trim() == c) out.add(c to c)
        }
        return out
    }

    override suspend fun stat(path: String): FsEntry? {
        val p = path.trimEnd('/')
        val o = link().shell("ls -ldL ${q(p)} 2>&1")
        val m = Regex("""^(\S{10})\s+\d+\s+\S+\s+\S+\s+(\d+)\s""").find(o.trim()) ?: return null
        return FsEntry(p.substringAfterLast('/'), p, m.groupValues[1][0] == 'd', m.groupValues[2].toLongOrNull() ?: 0L)
    }

    override suspend fun mkdirRemote(path: String) { val o = link().shell("mkdir -p ${q(path.trimEnd('/'))} 2>&1"); check(o, true) }
    override suspend fun renameRemote(from: String, to: String, overwrite: Boolean) {
        val o = link().shell(if (overwrite) "mv -f ${q(from)} ${q(to)} 2>&1" else "mv -n ${q(from)} ${q(to)} 2>&1"); check(o, true)
        if (o.isNotBlank() && !o.contains("rename", true) && o.lowercase().let { it.contains("cannot") || it.contains("failed") || it.contains("error") }) throw IOException(o.lines().first())
    }
    override suspend fun removeRemote(path: String, isDir: Boolean) { val o = link().shell("rm -rf ${q(path.trimEnd('/'))} 2>&1", 120_000); check(o, true) }
    override suspend fun putFile(src: File, dest: String, j: OpProgress?) {
        try { link().push(src, dest, j) }
        catch (e: IOException) { if (j?.cancelled == true) throw LocalOps.Cancelled(); throw e }
    }
}
