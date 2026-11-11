package app.adbphon

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.withTimeoutOrNull

/**
 * «Экран ПК» (10.11.2026): просмотр и управление экраном Windows-ПК с Android по сети Tailscale.
 * На ПК работает ScreenShare.exe host («Телефон в окне со звуком», пункт трея «Доступ к этому экрану»); протокол TVO-SCR1 (HMAC-SHA256 по «Коду программы» ПК,
 * подтверждение владельца ПК при первом подключении), кадры JPEG, мышь и клавиши в ответ. Описание: Программы\PhoneInWindowWithSound\ТОДО\экран_пк_протокол.md.
 */
class ScreenPcClient(private val ctx: Context) {
    /** 0 — не подключено, 1 — соединение или ожидание разрешения на ПК, 2 — идёт показ. */
    var state by mutableStateOf(0)
    var status by mutableStateOf("")
    var frame by mutableStateOf<ImageBitmap?>(null)
    var hostW by mutableStateOf(1920)
    var hostH by mutableStateOf(1080)
    val monitors = mutableStateListOf<String>()
    var clipFromPc by mutableStateOf<String?>(null)
    var mon = 0
    var qAuto = true; var qW = 1280; var qQ = 70; var qFps = 10

    @Volatile private var sock: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var closing = false
    private val lock = Any()
    private var buttons = 0

    private fun prefs() = ctx.getSharedPreferences("screen_pc", Context.MODE_PRIVATE)

    private fun clientId(): String {
        val p = prefs()
        var id = p.getString("cid", null)
        if (id == null || id.length != 32) { id = hex(rand(16)); p.edit().putString("cid", id).apply() }
        return id
    }

    private fun rand(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }
    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    private fun norm(c: String): String = c.filter { it.isLetterOrDigit() }.uppercase()
    private fun secret(code: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(("tvo-scr-v1:" + norm(code)).toByteArray(Charsets.UTF_8))
    private fun mac(secret: ByteArray, msg: String): String {
        val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(secret, "HmacSHA256"))
        return hex(m.doFinal(msg.toByteArray(Charsets.UTF_8)))
    }
    private fun same(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var d = 0; for (i in a.indices) d = d or (a[i].code xor b[i].code); return d == 0
    }

    private fun readLine(s: InputStream, max: Int = 400): String? {
        val sb = StringBuilder()
        while (sb.length < max) {
            val c = s.read(); if (c < 0) return null
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        return null
    }

    private fun writeLine(o: OutputStream, line: String) { synchronized(lock) { o.write((line + "\n").toByteArray(Charsets.UTF_8)); o.flush() } }

    fun send(type: Char, data: ByteArray = ByteArray(0)) {
        val o = out ?: return
        try {
            synchronized(lock) {
                val h = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN); h.put(type.code.toByte()); h.putInt(data.size)
                o.write(h.array()); if (data.isNotEmpty()) o.write(data); o.flush()
            }
        } catch (_: Exception) { }
    }

    fun sendQ() {
        send('Q', ("maxw=$qW;q=$qQ;fps=$qFps;mon=$mon;auto=" + (if (qAuto) 1 else 0)).toByteArray(Charsets.UTF_8))
    }

    /** Мышь: доли экрана 0..65535, кнопки (1 левая, 2 правая, 4 средняя), колесо. */
    fun mouse(nx: Float, ny: Float, btn: Int, wheel: Int = 0) {
        val b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt((nx.coerceIn(0f, 1f) * 65535).toInt()); b.putInt((ny.coerceIn(0f, 1f) * 65535).toInt()); b.putInt(btn); b.putInt(wheel)
        buttons = btn; send('m', b.array())
    }

    fun key(vk: Int, up: Boolean, extended: Boolean = false) {
        val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(vk); b.putInt((if (up) 1 else 0) or (if (extended) 2 else 0)); send('k', b.array())
    }

    fun tap(vk: Int, extended: Boolean = false, vararg mods: Int) {
        for (m in mods) key(m, false)
        key(vk, false, extended); key(vk, true, extended)
        for (m in mods.reversed()) key(m, true)
    }

    fun text(t: String) { if (t.isNotEmpty()) send('t', t.toByteArray(Charsets.UTF_8)) }

    /** 0 — VPN нет, 1 — работает Tailscale (адрес 100.x), 2 — работает ТОЛЬКО другой VPN (Android допускает один VPN: Tailscale с ним не заработает). */
    fun vpnState(): Int = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val vpns = cm.allNetworks.filter { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        if (vpns.isEmpty()) 0
        else if (vpns.any { n -> cm.getLinkProperties(n)?.linkAddresses?.any { a -> a.address.hostAddress?.startsWith("100.") == true } == true }) 1
        else 2
    } catch (_: Exception) { 0 }

    fun connect(host: String, code: String, name: String) {
        if (state != 0) return
        // конфликт VPN: к адресу Tailscale (100.x) при другом VPN не соединяемся, а просим выключить его
        if (host.trim().startsWith("100.") && vpnState() == 2) { status = ctx.getString(R.string.spc_vpn_hint); return }
        state = 1; closing = false; frame = null; monitors.clear()
        status = ctx.getString(R.string.spc_connecting)
        Thread {
            try {
                val s = Socket(); s.connect(InetSocketAddress(host.trim(), 47322), 6000); s.tcpNoDelay = true; s.soTimeout = 90000
                sock = s; val i = s.getInputStream(); val o = s.getOutputStream(); out = o
                val hello = readLine(i, 100)
                if (hello == null || !hello.startsWith("TVO-SCR1 ")) { fail(ctx.getString(R.string.spc_not_service)); return@Thread }
                val ns = hello.substring(9).trim(); val nc = hex(rand(16)); val cid = clientId(); val sec = secret(code)
                val nm = Base64.encodeToString(name.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                writeLine(o, "AUTH $cid $nm $nc " + mac(sec, "c|$ns|$nc|$cid"))
                var reply = readLine(i, 200)
                while (reply == "WAIT") { status = ctx.getString(R.string.spc_wait_allow); reply = readLine(i, 200) }
                when {
                    reply == null -> { fail(ctx.getString(R.string.spc_dropped)); return@Thread }
                    reply == "DENIED" -> { fail(ctx.getString(R.string.spc_denied)); return@Thread }
                    reply == "BUSY" -> { fail(ctx.getString(R.string.spc_busy)); return@Thread }
                    !reply.startsWith("OK ") || !same(mac(sec, "s|$nc|$ns"), reply.substring(3).trim()) -> { fail(ctx.getString(R.string.spc_host_check)); return@Thread }
                }
                s.soTimeout = 0
                state = 2; status = ""
                sendQ()
                val head = ByteArray(5)
                while (!closing) {
                    if (!readFull(i, head, 5)) break
                    val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN); val t = bb.get().toInt().toChar(); val n = bb.int
                    if (n < 0 || n > 32 * 1024 * 1024) break
                    val d = ByteArray(n); if (n > 0 && !readFull(i, d, n)) break
                    when (t) {
                        'F' -> if (n > 12) {
                            val b2 = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN); hostW = b2.int; hostH = b2.int
                            val bmp = BitmapFactory.decodeByteArray(d, 12, n - 12)
                            if (bmp != null) frame = bmp.asImageBitmap()
                        }
                        'M' -> { val l = String(d, Charsets.UTF_8).split('\n').filter { it.isNotBlank() }; monitors.clear(); monitors.addAll(l) }
                        'C' -> clipFromPc = String(d, Charsets.UTF_8)
                    }
                }
                if (!closing) fail(ctx.getString(R.string.spc_lost)) else cleanup()
            } catch (e: Exception) {
                if (!closing) {
                    val vpn = if (host.trim().startsWith("100.") && vpnState() == 0) "\n" + ctx.getString(R.string.spc_no_ts) else ""
                    fail(ctx.getString(R.string.spc_error, e.message ?: "") + vpn)
                } else cleanup()
            }
        }.start()
    }

    private fun readFull(s: InputStream, b: ByteArray, n: Int): Boolean {
        var got = 0
        while (got < n) { val r = try { s.read(b, got, n - got) } catch (_: Exception) { -1 }; if (r <= 0) return false; got += r }
        return true
    }

    private fun fail(msg: String) { status = msg; cleanup() }
    private fun cleanup() { try { sock?.close() } catch (_: Exception) { }; sock = null; out = null; state = 0 }

    fun disconnect() { closing = true; send('b'); cleanup(); frame = null }
}

@Composable
fun ScreenPcScreen() {
    val ctx = LocalContext.current
    val c = remember { ScreenPcClient(ctx.applicationContext) }
    DisposableEffect(Unit) { onDispose { c.disconnect() } }
    var ip by remember { mutableStateOf(ctx.getSharedPreferences("screen_pc", Context.MODE_PRIVATE).getString("last_ip", "") ?: "") }
    var code by remember { mutableStateOf(ctx.getSharedPreferences("screen_pc", Context.MODE_PRIVATE).getString("last_code", "") ?: "") }
    var name by remember { mutableStateOf(ctx.getSharedPreferences("screen_pc", Context.MODE_PRIVATE).getString("my_name", null) ?: (Build.MODEL ?: "Android")) }
    val hosts = remember { mutableStateListOf<PcHost>().also { it.addAll(loadHosts(ctx)) } }

    if (c.state == 0) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.spc_title), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp)
            Text(stringResource(R.string.spc_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .7f))
            if (hosts.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    hosts.forEach { h -> AssistChip({ ip = h.ip; code = h.code }, { Text(h.name + " · " + h.ip, fontSize = 12.sp) }) }
                }
            }
            OutlinedTextField(ip, { ip = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.spc_ip)) })
            OutlinedTextField(code, { code = it.uppercase() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.spc_code)) })
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.spc_myname)) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    if (ip.isBlank() || code.filter { it.isLetterOrDigit() }.length != 12) { c.status = ctx.getString(R.string.spc_need_fields) }
                    else {
                        ctx.getSharedPreferences("screen_pc", Context.MODE_PRIVATE).edit().putString("last_ip", ip).putString("last_code", code).putString("my_name", name).apply()
                        hosts.removeAll { it.ip == ip }; hosts.add(PcHost(ip, ip, code)); saveHosts(ctx, hosts)
                        c.connect(ip, code, name.ifBlank { "Android" })
                    }
                }) { Text(stringResource(R.string.spc_connect)) }
                if (hosts.isNotEmpty()) OutlinedButton({ hosts.removeAll { it.ip == ip }; saveHosts(ctx, hosts) }) { Text(stringResource(R.string.spc_forget)) }
            }
            if (c.status.isNotEmpty()) Text(c.status, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        return
    }
    ScreenPcLive(c)
}

@Composable
private fun KeyChip(c: ScreenPcClient, label: String, vk: Int, ext: Boolean = false, mods: IntArray = IntArray(0)) {
    AssistChip({ c.tap(vk, ext, *mods) }, { Text(label, fontSize = 12.sp) })
}

@Composable
fun ScreenPcLive(c: ScreenPcClient) {
    val vc = LocalViewConfiguration.current
    var size by remember { mutableStateOf(IntSize(1, 1)) }
    var kbd by remember { mutableStateOf(false) }
    var qMenu by remember { mutableStateOf(false) }
    var mMenu by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val fr = c.frame
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ c.disconnect() }) { Text(stringResource(R.string.spc_disconnect), fontSize = 12.sp) }
            Box {
                OutlinedButton({ qMenu = true }) { Text(stringResource(R.string.spc_quality), fontSize = 12.sp) }
                DropdownMenu(qMenu, { qMenu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.spc_q_auto)) }, { c.qAuto = true; c.qW = 1600; c.qQ = 75; c.qFps = 12; c.sendQ(); qMenu = false })
                    DropdownMenuItem({ Text(stringResource(R.string.spc_q_fast)) }, { c.qAuto = false; c.qW = 960; c.qQ = 50; c.qFps = 15; c.sendQ(); qMenu = false })
                    DropdownMenuItem({ Text(stringResource(R.string.spc_q_sharp)) }, { c.qAuto = false; c.qW = 1920; c.qQ = 85; c.qFps = 8; c.sendQ(); qMenu = false })
                }
            }
            if (c.monitors.size > 1) Box {
                OutlinedButton({ mMenu = true }) { Text(stringResource(R.string.spc_monitor), fontSize = 12.sp) }
                DropdownMenu(mMenu, { mMenu = false }) {
                    c.monitors.forEach { l ->
                        val p = l.split('|')
                        DropdownMenuItem({ Text((p[0].toIntOrNull() ?: 0).plus(1).toString() + ": " + p.getOrElse(1) { "" } + "x" + p.getOrElse(2) { "" }) }, { c.mon = p[0].toIntOrNull() ?: 0; c.sendQ(); mMenu = false })
                    }
                }
            }
            OutlinedButton({ kbd = !kbd }) { Text(stringResource(R.string.spc_keys), fontSize = 12.sp) }
            OutlinedButton({ c.send('g') }) { Text(stringResource(R.string.spc_clip_get), fontSize = 12.sp) }
        }
        if (kbd) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(typed, { typed = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.spc_type_hint), fontSize = 11.sp) })
                    Button({ c.text(typed); typed = "" }) { Text(stringResource(R.string.spc_send)) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    KeyChip(c, "Enter", 0x0D); KeyChip(c, "⌫", 0x08); KeyChip(c, "Esc", 0x1B); KeyChip(c, "Tab", 0x09); KeyChip(c, "Del", 0x2E, true)
                    KeyChip(c, "←", 0x25, true); KeyChip(c, "↑", 0x26, true); KeyChip(c, "↓", 0x28, true); KeyChip(c, "→", 0x27, true)
                    KeyChip(c, "Home", 0x24, true); KeyChip(c, "End", 0x23, true); KeyChip(c, "PgUp", 0x21, true); KeyChip(c, "PgDn", 0x22, true)
                    KeyChip(c, "Win", 0x5B, true); KeyChip(c, "Alt+Tab", 0x09, false, intArrayOf(0x12)); KeyChip(c, "Ctrl+C", 0x43, false, intArrayOf(0x11)); KeyChip(c, "Ctrl+V", 0x56, false, intArrayOf(0x11))
                    KeyChip(c, "Ctrl+A", 0x41, false, intArrayOf(0x11)); KeyChip(c, "Ctrl+Z", 0x5A, false, intArrayOf(0x11))
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { size = it }, contentAlignment = Alignment.Center) {
            if (fr == null) {
                Text(if (c.status.isNotEmpty()) c.status else stringResource(R.string.spc_connecting), color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            } else {
                // подгонка под экран: картинка целиком, пропорции сохраняются
                val sc = minOf(size.width.toFloat() / fr.width, size.height.toFloat() / fr.height)
                val dw = (fr.width * sc).toInt().coerceAtLeast(1); val dh = (fr.height * sc).toInt().coerceAtLeast(1)
                val ox = (size.width - dw) / 2; val oy = (size.height - dh) / 2
                Canvas(Modifier.fillMaxSize().pointerInput(dw, dh, ox, oy) {
                    fun nx(x: Float) = ((x - ox) / dw).coerceIn(0f, 1f)
                    fun ny(y: Float) = ((y - oy) / dh).coerceIn(0f, 1f)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        var moved = false; var long = false; var scroll = false; var dragging = false
                        c.mouse(nx(start.x), ny(start.y), 0)
                        val t0 = down.uptimeMillis
                        var last = start
                        do {
                            val ev = if (!long && !moved && !scroll) withTimeoutOrNull(500) { awaitPointerEvent() } else awaitPointerEvent()
                            if (ev == null) {
                                // палец не двигается 0,5 с: длинное нажатие = правая кнопка
                                long = true
                                c.mouse(nx(start.x), ny(start.y), 2); c.mouse(nx(start.x), ny(start.y), 0)
                                continue
                            }
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                scroll = true
                                val dy = ev.changes.map { it.position.y - it.previousPosition.y }.average().toFloat()
                                if (dy != 0f) c.mouse(nx(start.x), ny(start.y), 0, (dy * 2).toInt())
                                ev.changes.forEach { it.consume() }
                            } else if (pressed.size == 1 && !scroll && !long) {
                                val p = pressed[0].position
                                if (!moved && (p - start).getDistance() > vc.touchSlop) { moved = true; dragging = true; c.mouse(nx(start.x), ny(start.y), 1) }
                                if (dragging) { c.mouse(nx(p.x), ny(p.y), 1); last = p }
                                ev.changes.forEach { it.consume() }
                            }
                        } while (ev == null || ev.changes.any { it.pressed })
                        if (dragging) c.mouse(nx(last.x), ny(last.y), 0)
                        else if (!moved && !long && !scroll) {
                            c.mouse(nx(start.x), ny(start.y), 1); c.mouse(nx(start.x), ny(start.y), 0)
                        }
                    }
                }) {
                    drawImage(fr, dstOffset = IntOffset(ox, oy), dstSize = IntSize(dw, dh))
                }
            }
        }
    }
    val clip = c.clipFromPc
    if (clip != null) {
        val ctx = LocalContext.current
        AlertDialog({ c.clipFromPc = null }, confirmButton = {
            TextButton({
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("PC", clip)); c.clipFromPc = null
            }) { Text(stringResource(R.string.spc_copy)) }
        }, dismissButton = { TextButton({ c.clipFromPc = null }) { Text(stringResource(R.string.btn_close)) } },
            title = { Text(stringResource(R.string.spc_clip_title)) }, text = { Text(if (clip.length > 2000) clip.take(2000) + "…" else clip, fontSize = 13.sp) })
    }
}
