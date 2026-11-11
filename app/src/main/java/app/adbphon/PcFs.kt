package app.adbphon

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * «Компьютеры» (10.11.2026): файлы Windows-ПК как источник файлового менеджера и список ПК на корневом экране.
 * Протокол: ТОДО программы «Телефон в окне со звуком» → экран_пк_протокол.md (раздел «Файловая служба»): порт 47322, рукопожатие HMAC по
 * «Коду программы» ПК, шестое слово AUTH «files», подтверждение владельцем ПК при первом подключении, кадры R/r.
 * Только по Tailscale (адрес 100.x); пароля нет, вместо него код программы ПК (в хранилище Keystore).
 */
data class PcHost(val name: String, val ip: String, val code: String)

/** Сохранённые ПК: «имя|адрес|код», код хранится зашифрованным (Android Keystore, как пароли сетевых источников); старые открытые записи читаются. */
fun loadHosts(ctx: Context): List<PcHost> =
    (ctx.getSharedPreferences("screen_pc", Context.MODE_PRIVATE).getString("hosts", "") ?: "").lines().mapNotNull { l ->
        val p = l.split('|'); if (p.size < 3) null
        else PcHost(p[0], p[1], if (p[2].startsWith("e:")) runCatching { NetStore.decrypt(p[2].substring(2)) }.getOrDefault("") else p[2])
    }

fun saveHosts(ctx: Context, l: List<PcHost>) {
    ctx.getSharedPreferences("screen_pc", Context.MODE_PRIVATE).edit()
        .putString("hosts", l.joinToString("\n") { it.name.replace("|", " ").replace("\n", " ") + "|" + it.ip + "|" + (if (it.code.isEmpty()) "" else "e:" + runCatching { NetStore.encrypt(it.code) }.getOrDefault("")) }).apply()
}

fun pcMyName(ctx: Context): String = ctx.getSharedPreferences("screen_pc", Context.MODE_PRIVATE).getString("my_name", null) ?: (Build.MODEL ?: "Android")

/** Подключение для файлового менеджера: user = имя телефона (его видит владелец ПК в окне разрешения), pass = код программы ПК, name = подпись. */
fun PcHost.conn(ctx: Context): NetConn = NetConn("pc", ip.substringBefore(':'), ip.substringAfter(':', "47322").toIntOrNull() ?: 47322, pcMyName(ctx), code, "", "", name.ifBlank { ip })

/** Доступность ПК (на корневом экране): служба слушает порт 47322 и отвечает приветствием. */
object PcStatus {
    val up = mutableStateMapOf<String, Boolean>()
    suspend fun probe(ip: String) {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip.substringBefore(':'), ip.substringAfter(':', "47322").toIntOrNull() ?: 47322), 1800); s.soTimeout = 1800
                    val b = ByteArray(9); var n = 0
                    while (n < 9) { val r = s.getInputStream().read(b, n, 9 - n); if (r <= 0) break; n += r }
                    n == 9 && String(b, 0, 9, Charsets.UTF_8) == "TVO-SCR1 "
                }
            }.getOrDefault(false)
        }
        up[ip] = ok
    }
}

class PcRemoteError(msg: String) : IOException(msg)

/** Состояние соединения с одним ПК (общее для обёрток [PcFs] с теми же адресом, именем и кодом). */
class PcState {
    val lock = Any()
    var sock: Socket? = null
    var inp: InputStream? = null
    var outp: OutputStream? = null
    var seq = 0
    @Volatile var owner: Any? = null
    companion object {
        private val all = HashMap<String, PcState>()
        fun of(key: String): PcState = synchronized(all) { all.getOrPut(key) { PcState() } }
    }
}

class PcFs(private val host: String, private val port: Int, private val name: String, private val code: String) : RemoteFs {
    /** Соединение общее для всех обёрток к одному ПК: переподключение [KeepFs] создаёт новый [PcFs], но не новый сеанс и не новый запрос разрешения на ПК. */
    private val st = PcState.of("$host:$port|$name|" + code.filter { it.isLetterOrDigit() }.uppercase()).also { it.owner = this }

    override val root get() = "/"
    override val label get() = "pc://$host"

    // ---------- рукопожатие и кадры ----------
    private fun ctx(): Context = FmIo.app
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun rnd(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
    private fun secret() = MessageDigest.getInstance("SHA-256").digest(("tvo-scr-v1:" + code.filter { it.isLetterOrDigit() }.uppercase()).toByteArray(Charsets.UTF_8))
    private fun mac(sec: ByteArray, msg: String): String { val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(sec, "HmacSHA256")); return hex(m.doFinal(msg.toByteArray(Charsets.UTF_8))) }
    private fun same(a: String, b: String): Boolean { if (a.length != b.length) return false; var d = 0; for (i in a.indices) d = d or (a[i].code xor b[i].code); return d == 0 }
    private fun clientId(): String {
        val p = ctx().getSharedPreferences("screen_pc", Context.MODE_PRIVATE)
        var id = p.getString("cid", null)
        if (id == null || id.length != 32) { id = hex(rnd(16)); p.edit().putString("cid", id).apply() }
        return id
    }
    private fun readLine(s: InputStream, max: Int): String? {
        val sb = StringBuilder()
        while (sb.length < max) { val c = s.read(); if (c < 0) return null; if (c == '\n'.code) return sb.toString().trimEnd('\r'); sb.append(c.toChar()) }
        return null
    }
    private fun readFull(s: InputStream, b: ByteArray, n: Int) {
        var got = 0
        while (got < n) { val r = s.read(b, got, n - got); if (r <= 0) throw EOFException("closed"); got += r }
    }

    /** Открыть соединение, если ещё не открыто. Первое подключение ждёт ответа владельца ПК (до ~60 с). */
    fun connect() { synchronized(st.lock) { if (st.sock == null) open() } }

    private fun open() {
        val c = ctx()
        if (host.trim().startsWith("100.") && ScreenPcClient(c).vpnState() == 2) throw IOException(c.getString(R.string.spc_vpn_hint))
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host.trim(), port), 6000); s.tcpNoDelay = true; s.soTimeout = 75000
            val i = s.getInputStream(); val o = s.getOutputStream()
            val hello = readLine(i, 100)
            if (hello == null || !hello.startsWith("TVO-SCR1 ")) throw IOException(c.getString(R.string.spc_not_service))
            val ns = hello.substring(9).trim(); val nc = hex(rnd(16)); val cid = clientId(); val sec = secret()
            val nm = Base64.encodeToString(name.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            o.write(("AUTH $cid $nm $nc " + mac(sec, "c|$ns|$nc|$cid") + " files\n").toByteArray(Charsets.UTF_8)); o.flush()
            var reply = readLine(i, 200)
            while (reply == "WAIT") reply = readLine(i, 200)
            when {
                reply == null -> throw IOException(c.getString(R.string.spc_dropped))
                reply == "DENIED" -> throw IOException(c.getString(R.string.spc_denied))
                reply == "BUSY" -> throw IOException(c.getString(R.string.pc_busy))
                !reply.startsWith("OK ") || !same(mac(sec, "s|$nc|$ns"), reply.substring(3).trim()) -> throw IOException(c.getString(R.string.spc_host_check))
            }
            s.soTimeout = 40000
            st.sock = s; st.inp = i; st.outp = o
        } catch (e: Throwable) { runCatching { s.close() }; throw e }
    }

    /** Закрывает общее соединение, только если эта обёртка последняя (новая обёртка после переподключения его забрала). В фоне: запрос может держать блокировку. */
    override fun close() {
        if (st.owner !== this) return
        Thread { synchronized(st.lock) { if (st.owner === this) { runCatching { st.outp?.write(byteArrayOf('b'.code.toByte(), 0, 0, 0, 0)); st.outp?.flush() }; runCatching { st.sock?.close() }; st.sock = null; st.inp = null; st.outp = null } } }.apply { isDaemon = true }.start()
    }

    /** Один запрос и ответ. Ошибка связи закрывает сокет (поднимет заново обёртка KeepFs); отказ ПК приходит как [PcRemoteError] без разрыва. */
    private fun req(op: String, args: List<String>, data: ByteArray = ByteArray(0)): ByteArray = synchronized(st.lock) { reqLocked(op, args, data) }

    private fun reqLocked(op: String, args: List<String>, data: ByteArray): ByteArray {
        if (st.sock == null) open()
        val i = st.inp!!; val o = st.outp!!
        try {
            val id = (++st.seq).toString()
            val head = (listOf(id, op) + args).joinToString("\n").toByteArray(Charsets.UTF_8)
            val pay = ByteBuffer.allocate(4 + head.size + data.size).order(ByteOrder.LITTLE_ENDIAN).putInt(head.size).put(head).put(data).array()
            val fr = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN); fr.put('R'.code.toByte()); fr.putInt(pay.size)
            o.write(fr.array()); o.write(pay); o.flush()
            val hd = ByteArray(5)
            while (true) {
                readFull(i, hd, 5)
                val bb = ByteBuffer.wrap(hd).order(ByteOrder.LITTLE_ENDIAN); val t = bb.get().toInt().toChar(); val n = bb.int
                if (n < 0 || n > 64 * 1024 * 1024) throw IOException("bad frame")
                val d = ByteArray(n); if (n > 0) readFull(i, d, n)
                if (t != 'r' || n < 4) continue
                val hl = ByteBuffer.wrap(d, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (hl < 0 || hl > n - 4) throw IOException("bad reply")
                val h = String(d, 4, hl, Charsets.UTF_8).split('\n')
                if (h[0] != id) continue
                if (h.getOrNull(1) != "OK") throw PcRemoteError(h.drop(2).joinToString("\n").ifBlank { "ERR" })
                return d.copyOfRange(4 + hl, n)
            }
        } catch (e: PcRemoteError) { throw e }
        catch (e: Throwable) {
            runCatching { st.sock?.close() }; st.sock = null; st.inp = null; st.outp = null
            throw if (e is IOException) e else SocketException(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun call(op: String, vararg a: String, data: ByteArray = ByteArray(0)): ByteArray = withContext(Dispatchers.IO) { req(op, a.toList(), data) }

    // ---------- Fs / RemoteFs ----------
    private fun join(d: String, n: String) = if (d.endsWith("/")) d + n else "$d/$n"
    private fun isRoot(p: String) = p.isEmpty() || p == "/"

    private fun parseLine(dir: String, l: String): FsEntry? {
        val x = l.split('|'); if (x.size < 3) return null
        val d = x[1] == "d"
        return FsEntry(x[0], join(dir, x[0]), d, if (d) 0L else (x[2].toLongOrNull() ?: 0L))
    }

    private suspend fun rootsRaw(): List<Triple<String, String, Long>> =
        String(call("roots"), Charsets.UTF_8).lines().mapNotNull { l -> val x = l.split('|'); if (x.size >= 2) Triple(x[0], x[1], x.getOrNull(3)?.toLongOrNull() ?: 0L) else null }

    override suspend fun list(path: String): List<FsEntry> {
        if (isRoot(path)) return rootsRaw().map { FsEntry(it.first, it.second, true, 0L) }
        val r = String(call("list", path), Charsets.UTF_8).lines().filter { it.isNotBlank() }.mapNotNull { parseLine(path, it) }
        return r.sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override suspend fun roots(): List<Pair<String, String>> = rootsRaw().map { it.first to it.second }

    override fun parent(path: String): String? {
        val t = path.trimEnd('/')
        if (isRoot(path)) return null
        val p = t.substringBeforeLast('/', "")
        return when { p.isEmpty() -> "/"; p.length == 2 && p[1] == ':' -> "$p/"; else -> p }
    }

    override suspend fun head(path: String, n: Int): ByteArray = call("read", path, "0", n.coerceIn(1, 1 shl 20).toString())

    override suspend fun copyTo(path: String, dest: File) {
        withContext(Dispatchers.IO) {
            dest.outputStream().use { out ->
                var off = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val b = req("read", listOf(path, off.toString(), (1 shl 20).toString()))
                    if (b.isEmpty()) break
                    out.write(b); off += b.size
                }
            }
        }
    }

    override suspend fun ping(path: String) { call("ping") }

    override suspend fun stat(path: String): FsEntry? {
        if (isRoot(path)) return FsEntry("", "/", true, 0L)
        return try {
            val l = String(call("stat", path), Charsets.UTF_8).lines().firstOrNull { it.isNotBlank() } ?: return null
            val x = l.split('|'); val d = x.getOrNull(1) == "d"
            FsEntry(path.trimEnd('/').substringAfterLast('/'), path, d, if (d) 0L else (x.getOrNull(2)?.toLongOrNull() ?: 0L))
        } catch (e: PcRemoteError) { if (e.message?.contains("найден") == true) null else throw e }
    }

    override suspend fun mkdirRemote(path: String) { call("mkdir", path) }
    override suspend fun renameRemote(from: String, to: String, overwrite: Boolean) { call("rename", from, to, if (overwrite) "1" else "0") }
    /** Удаление идёт в корзину Windows (не навсегда). */
    override suspend fun removeRemote(path: String, isDir: Boolean) { call("delete", path) }

    override suspend fun putFile(src: File, dest: String, j: OpProgress?) {
        withContext(Dispatchers.IO) {
            src.inputStream().use { ins ->
                val buf = ByteArray(512 * 1024); var off = 0L; var first = true
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (j?.cancelled == true) throw LocalOps.Cancelled()
                    var n = 0
                    while (n < buf.size) { val r = ins.read(buf, n, buf.size - n); if (r <= 0) break; n += r }
                    if (n == 0 && !first) break
                    req("write", listOf(dest, off.toString(), if (first) "t" else "a"), if (n == buf.size) buf else buf.copyOf(n))
                    j?.bytes(n.toLong()); off += n; first = false
                    if (n < buf.size) break
                }
            }
        }
    }
}

// ---------- окна: добавить компьютер, экран ----------

/** «Добавить компьютер»: название, адрес в Tailscale, код программы ПК. Для уже сохранённого — правка. */
@Composable
fun PcAddDialog(edit: PcHost?, close: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(edit?.name ?: "") }
    var ip by remember { mutableStateOf(edit?.ip ?: ctx.getSharedPreferences("screen_pc", Context.MODE_PRIVATE).getString("last_ip", "") ?: "") }
    var code by remember { mutableStateOf(edit?.code ?: "") }
    var err by remember { mutableStateOf("") }
    LAlertDialog(
        onDismissRequest = close, ime = true,
        title = { HintTitle(stringResource(R.string.pc_add_title), stringResource(R.string.pc_add_hint), titleSize = 18.sp) },
        text = {
            Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CField(stringResource(R.string.pc_name), name, { name = it })
                CField(stringResource(R.string.spc_ip), ip, { ip = it.trim() }, type = KeyboardType.Uri)
                CField(stringResource(R.string.spc_code), code, { code = it.uppercase() })
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton({
                if (ip.isBlank() || code.filter { it.isLetterOrDigit() }.length != 12) { err = ctx.getString(R.string.spc_need_fields); return@TextButton }
                val l = loadHosts(ctx).filter { it.ip != ip && it.ip != edit?.ip }.toMutableList()
                l.add(PcHost(name.trim().ifBlank { ip }, ip, code))
                saveHosts(ctx, l); FmRootCfg.ver++
                close()
            }) { Text(stringResource(R.string.fm_apply)) }
        },
        dismissButton = { TextButton(close) { Text(stringResource(R.string.btn_cancel)) } },
    )
}

/** Экран ПК во весь экран поверх файлового менеджера: то же подключение и те же жесты, что на вкладке «Экран ПК». */
@Composable
fun PcScreenDialog(h: PcHost, close: () -> Unit) {
    val ctx = LocalContext.current
    val c = remember { ScreenPcClient(ctx.applicationContext) }
    var started by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { c.connect(h.ip, h.code, pcMyName(ctx)); started = true; onDispose { c.disconnect() } }
    LaunchedEffect(c.state, started) { if (started && c.state == 0 && c.status.isBlank()) close() }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (c.state == 2) ScreenPcLive(c)
            else Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(h.name.ifBlank { h.ip }, fontSize = 18.sp)
                Text(c.status.ifBlank { stringResource(R.string.spc_connecting) }, fontSize = 14.sp, color = if (c.state == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground)
                Button(close, Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.btn_close)) }
            }
        }
    }
}
