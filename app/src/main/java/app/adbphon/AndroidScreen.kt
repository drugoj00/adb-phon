package app.adbphon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Экран Android-устройства (10.11.2026): просмотр и управление по ADB.
 * Основной путь: `screenrecord --output-format=h264 -` (поток H.264) и аппаратный декодер MediaCodec прямо на поверхность:
 * плавно (до 30–60 кадров/с, пока экран меняется), задержка ~0,2–0,5 с. Запасной путь, если screenrecord нет или он отказал
 * (часть приставок и новых версий Android): `screencap -p` по кругу, это 1–3 кадра/с, зато работает почти везде.
 * Ввод: касание, свайп, долгое нажатие, кнопки «Назад/Домой/Недавние», текст в любой раскладке (латиница — `input text`, остальное через вводный метод ADB-phon на устройстве).
 */
class AndroidScreenClient(private val devAddrs: List<String>) {
    var status by mutableStateOf("")
    /** 0 — выключено, 1 — подключение, 2 — идёт показ. */
    var state by mutableStateOf(0)
    var mode by mutableStateOf("")           // "h264" или "png"
    var frame by mutableStateOf<ImageBitmap?>(null)   // кадр запасного режима
    var vw by mutableStateOf(0); var vh by mutableStateOf(0)   // размер видео (после поворота)
    var quality by mutableStateOf("auto")
    @Volatile var surface: Surface? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var link: AdbLink? = null
    private var input: AdbLink.Session? = null
    /** Короткое сообщение под кнопками (ошибка набора текста и т. п.); пусто — нет. */
    var note by mutableStateOf("")
    @Volatile private var physW = 0
    @Volatile private var physH = 0
    /** Текущий поворот экрана устройства (0–3) по последнему опросу; -1 — неизвестен. Нужен для пересчёта касаний. */
    @Volatile private var curRot = -1
    /** Сколько раз экран перезапускался сам из-за поворота и сколько кадров показано (для проверки и журнала). */
    @Volatile var autoRestarts = 0
    @Volatile var framesOut = 0L
    /** Пока поток H.264 не дал первого кадра (screenrecord молчит на неподвижном экране, часть декодеров придерживает кадры), показывается снимок screencap, обновляемый раз в ~1,5 с. */
    var preview by mutableStateOf(false)
    /** Экран устройства выключен или устройство спит: вместо чёрного прямоугольника понятная подпись. */
    var off by mutableStateOf(false)

    private suspend fun checkOff(l: AdbLink) {
        val w = runCatching { l.shell("dumpsys power | grep -m1 mWakefulness=", 6_000) }.getOrDefault("")
        if (w.contains("mWakefulness")) off = w.contains("Asleep") || w.contains("Dozing")
    }
    private suspend fun grabPng(l: AdbLink): Bitmap? {
        val bytes = runCatching { l.execBytes("screencap -p", 10_000) }.getOrDefault(ByteArray(0))
        return if (bytes.size > 100) runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() else null
    }
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var gen = 0
    private fun app() = FmIo.app

    fun start() {
        stop(); state = 1; status = app().getString(R.string.spc_connecting); frame = null; mode = ""; curRot = -1; note = ""
        val my = ++gen
        job = scope.launch {
            try {
                val l = adbLinkFor(devAddrs); link = l
                val wm = l.shell("wm size 2>&1")
                val m = Regex("""(?:Override|Physical) size:\s*(\d+)x(\d+)""").findAll(wm).lastOrNull()
                physW = m?.groupValues?.get(1)?.toIntOrNull() ?: 0; physH = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
                                state = 2; status = ""
                while (isActive && gen == my) {
                    val t0 = System.currentTimeMillis()
                    val ok = runCatching { h264Round(l, my) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; false }
                    if (gen != my) break
                    if (!ok) { pngLoop(l, my); break }
                    if (System.currentTimeMillis() - t0 < 1500) delay(800)   // поток обрывается сразу: не крутить цикл вхолостую
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) { if (gen == my) { status = e.message ?: e.javaClass.simpleName; state = 0 } }
        }
    }

    fun stop() {
        gen++; job?.cancel(); job = null; preview = false; off = false
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        runCatching { input?.close() }; input = null
        state = 0
    }

    private fun capFor(): Triple<Int, Int, Int> = when (quality) {   // длинная сторона, битрейт, кадров в секунду (запасной режим)
        "fast" -> Triple(854, 1_500_000, 4)
        "sharp" -> Triple(1920, 12_000_000, 1)
        else -> Triple(1280, 4_000_000, 2)
    }

    /** Поворот экрана устройства (0–3); null — не удалось узнать (тогда считаем, что поворот не менялся). */
    private suspend fun rotation(l: AdbLink): Int? {
        // Android до 14: «SurfaceOrientation: N»; Android 15 этой строки не даёт — берём «orientation=N» первого окна просмотра (Viewport INTERNAL / Viewport: displayId=0)
        val o = runCatching { l.shell("dumpsys input 2>/dev/null | grep -m1 -E 'SurfaceOrientation|Viewport INTERNAL|Viewport: displayId=0'", 8_000) }.getOrDefault("")
        return (Regex("SurfaceOrientation:\\s*(\\d)").find(o) ?: Regex("orientation=(\\d)").find(o))?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun sizeArg(rot: Int, long: Int): String {
        if (physW <= 0 || physH <= 0) return ""
        var w = physW; var h = physH
        if (rot % 2 == 1) { val t = w; w = h; h = t }
        val sc = minOf(1.0, long.toDouble() / maxOf(w, h))
        val rw = ((w * sc).toInt() / 2) * 2; val rh = ((h * sc).toInt() / 2) * 2
        return "--size ${rw}x$rh"
    }

    /** Один сеанс screenrecord (до ~170 с). true — поток был (повторить), false — H.264 не получился (запасной режим). */
    private suspend fun h264Round(l: AdbLink, my: Int): Boolean = withContext(Dispatchers.IO) {
        val (long, br, _) = capFor()
        val rk = rotation(l)
        val rot = rk ?: (if (curRot >= 0) curRot else 0)
        if (rk != null) curRot = rk
        val s = l.open("exec:screenrecord --output-format=h264 ${sizeArg(rot, long)} --bit-rate $br --time-limit 170 -")
        val ins = s.openInputStream()
        // Поворот экрана устройства: раз в секунду смотрим ориентацию; сменилась — закрываем поток, цикл в start() сам запустит новый
        // сеанс с новым размером (пропорции видео и пересчёт касаний обновляются сразу, без кнопки «Перезапустить»)
        val rotating = java.util.concurrent.atomic.AtomicBoolean(false)
        val watcher = launch {
            while (isActive && gen == my) {
                delay(1000)
                val r = rotation(l) ?: continue
                if (r == rot) continue
                rotating.set(true); curRot = r; autoRestarts++
                if (physW > 0 && physH > 0) { if (r % 2 == 1) { vw = physH; vh = physW } else { vw = physW; vh = physH } }
                AppLog.i("ADBphon", "screen: rotation $rot -> $r, restarting the stream")
                runCatching { s.close() }
                break
            }
        }
        // часть декодеров (MTK на приставках) придерживает кадры, пока не наберётся «задержка вывода» (до 19 кадров): на статичном экране картинка не появляется; через 4 с без единого кадра закрываем поток и уходим в запасной режим (screencap)
        val f0 = framesOut
        val noFrames = java.util.concurrent.atomic.AtomicBoolean(false)
        val prev = launch {
            preview = true
            try {
                while (isActive && gen == my && framesOut == f0) {
                    checkOff(l)
                    val bmp = grabPng(l)
                    if (bmp != null && framesOut == f0) { if (vw == 0 || vh == 0) { vw = bmp.width; vh = bmp.height }; frame = bmp.asImageBitmap() }
                    delay(1500)
                }
            } finally { preview = false; if (mode != "png") frame = null }
        }
        val dog = launch { delay(6000); if (framesOut == f0 && gen == my && !rotating.get()) { noFrames.set(true); runCatching { s.close() } } }
        var b = ByteArray(1 shl 20); var n = 0
        var sps: ByteArray? = null; var pps: ByteArray? = null
        var frames = 0; var first = true; var cfgSps: ByteArray? = null
        val info = MediaCodec.BufferInfo()
        var pts = 0L
        fun drain(c: MediaCodec) {
            while (true) {
                val i = c.dequeueOutputBuffer(info, 0)
                when {
                    i >= 0 -> { c.releaseOutputBuffer(i, true); framesOut++ }
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { val f = c.outputFormat; vw = f.getInteger(MediaFormat.KEY_WIDTH); vh = f.getInteger(MediaFormat.KEY_HEIGHT) }
                    else -> return
                }
            }
        }
        fun feed(nal: ByteArray) {
            val c = codec ?: return
            var tries = 0
            while (tries++ < 50) {
                val ix = c.dequeueInputBuffer(10_000)
                if (ix >= 0) {
                    val bb = c.getInputBuffer(ix)!!; bb.clear(); bb.put(byteArrayOf(0, 0, 0, 1)); bb.put(nal)
                    c.queueInputBuffer(ix, 0, nal.size + 4, pts, 0); pts += 16_666
                    break
                }
                drain(c)
            }
            drain(c)
        }
        fun startCodec(sp: ByteArray, pp: ByteArray): Boolean {
            val sf = surface ?: return false
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            val (w0, h0) = if (vw > 0) vw to vh else if (physW > 0) (if (rot % 2 == 1) physH to physW else physW to physH) else (1280 to 720)
            val f = MediaFormat.createVideoFormat("video/avc", w0, h0)
            f.setInteger("low-latency", 1)
            f.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sp))
            f.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + pp))
            return runCatching {
                val c = MediaCodec.createDecoderByType("video/avc"); c.configure(f, sf, null, 0); c.start(); codec = c; cfgSps = sp; true
            }.getOrDefault(false)
        }
        try {
            val chunk = ByteArray(65536)
            var scanned = 0
            while (isActive && gen == my) {
                val r = try { ins.read(chunk) } catch (e: java.io.IOException) { if (rotating.get()) -1 else throw e }
                if (r < 0) break
                if (n + r > b.size) b = b.copyOf(maxOf(b.size * 2, n + r))
                System.arraycopy(chunk, 0, b, n, r); n += r
                if (first) {
                    if (n < 4) continue
                    val ok = (b[0].toInt() == 0 && b[1].toInt() == 0 && (b[2].toInt() == 1 || (b[2].toInt() == 0 && b[3].toInt() == 1)))
                    if (!ok) {   // это текст ошибки screenrecord: запасной режим
                        AppLog.i("ADBphon", "screenrecord: " + String(b, 0, minOf(n, 300), Charsets.UTF_8).replace("\n", " ⏎ "))
                        return@withContext false
                    }
                    first = false
                }
                // границы NAL по стартовым кодам 00 00 01
                var i = scanned
                var startPos = -1
                // найти все стартовые коды в b[0 until n)
                val starts = ArrayList<Int>()
                run {
                    var k = 0
                    while (k + 2 < n) { if (b[k].toInt() == 0 && b[k + 1].toInt() == 0 && b[k + 2].toInt() == 1) { starts.add(k); k += 3 } else k++ }
                }
                if (starts.size >= 2) {
                    for (si in 0 until starts.size - 1) {
                        val from = starts[si] + 3; var to = starts[si + 1]
                        if (to > from && b[to - 1].toInt() == 0) to--
                        if (to <= from) continue
                        val nal = b.copyOfRange(from, to)
                        when (nal[0].toInt() and 0x1f) {
                            7 -> sps = nal
                            8 -> {
                                pps = nal
                                val sp = sps
                                if (sp != null && (codec == null || !sp.contentEquals(cfgSps))) { if (!startCodec(sp, nal)) { delay(100) } }
                            }
                            1, 5 -> { if (codec != null) { feed(nal); frames++ } }
                        }
                    }
                    val keep = starts.last()
                    System.arraycopy(b, keep, b, 0, n - keep); n -= keep
                }
                if (codec == null && sps != null && pps != null) startCodec(sps!!, pps!!)
                scanned = 0; i = 0; startPos = 0
                if (frames == 1 && info.size == 0) { /* первый кадр отправлен */ }
            }
        } catch (e: java.io.IOException) { if (!noFrames.get()) throw e
        } finally { watcher.cancel(); dog.cancel(); prev.cancel(); runCatching { s.close() } }
        if (noFrames.get()) false else frames > 0 || !first
    }

    /** Запасной режим: снимок экрана (PNG) по кругу. */
    private suspend fun pngLoop(l: AdbLink, my: Int) {
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        vw = 0; vh = 0
        mode = "png"
        status = ""
        var fails = 0
        while (currentCoroutineContextActive() && gen == my) {
            val t0 = System.currentTimeMillis()
            val bmp = grabPng(l)
            if (fails % 2 == 0) checkOff(l)
            if (bmp != null) { fails = 0; vw = bmp.width; vh = bmp.height; frame = bmp.asImageBitmap() }
            else if (++fails >= 4) { status = app().getString(R.string.asc_no_frames); state = 0; return }
            val fps = capFor().third
            delay((1000L / fps - (System.currentTimeMillis() - t0)).coerceAtLeast(30L))
        }
    }

    private suspend fun currentCoroutineContextActive(): Boolean = kotlin.coroutines.coroutineContext[Job]?.isActive != false

    // ---------- ввод ----------
    private fun dispSize(): Pair<Int, Int> {
        if (physW <= 0 || physH <= 0) return 0 to 0
        if (mode != "png" && curRot >= 0) return if (curRot % 2 == 1) physH to physW else physW to physH
        val land = vw > vh
        return if (land == (physW > physH)) physW to physH else physH to physW
    }

    /** Сводка для стенда проверки (AndroidTestActivity): состояние, режим, размер видео, поворот, автоперезапуски, кадры. */
    fun info(): String { val (w, h) = dispSize(); return "state=$state mode=$mode video=${vw}x$vh rot=$curRot disp=${w}x$h autoRestarts=$autoRestarts frames=$framesOut status=$status note=$note" }

    // каждая команда ввода идёт отдельным коротким запросом (постоянная оболочка у части устройств проглатывала команды `input`)
    private fun send(cmd: String) { val l = link ?: return; scope.launch { runCatching { l.shell(cmd, 6_000) } } }

    fun tap(nx: Float, ny: Float) { val (w, h) = dispSize(); if (w > 0) send("input tap ${(nx * w).toInt()} ${(ny * h).toInt()}") }
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Int) {
        val (w, h) = dispSize(); if (w <= 0) return
        send("input swipe ${(x1 * w).toInt()} ${(y1 * h).toInt()} ${(x2 * w).toInt()} ${(y2 * h).toInt()} $ms")
    }
    fun key(code: Int) { send("input keyevent $code") }
    /**
     * Текст в поле на устройстве, в любой раскладке. Чистая латиница/цифры/знаки — быстрым `input text`. Всё остальное (кириллица, ї є ґ, эмодзи, перевод строки)
     * через вводный метод ADB-phon на самом устройстве ([TextIme]): на мгновение он становится текущей клавиатурой, получает строку широковещательным
     * сообщением, вставляет её в поле (commitText) и прежняя клавиатура возвращается. Буфер обмена не трогается.
     * Нужен ADB-phon (7.0 и новее) на управляемом устройстве.
     */
    fun text(t: String) {
        if (t.isEmpty()) return
        if (t.all { it.code in 32..126 }) { send("input text " + AdbLink.q(t.replace(" ", "%s"))); return }
        val l = link ?: return
        scope.launch {
            note = ""
            val ok = runCatching { imeType(l, t) }.getOrDefault(false)
            if (!ok) note = app().getString(R.string.vw_text_fail)
        }
    }

    private suspend fun imeType(l: AdbLink, t: String): Boolean {
        val b64 = android.util.Base64.encodeToString(t.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val ime = "app.adbphon/.TextIme"
        val sh = "P=\$(settings get secure default_input_method); W=\$(ime list -s 2>/dev/null | grep -c TextIme); " +
            "ime enable $ime >/dev/null 2>&1; ime set $ime >/dev/null 2>&1; R=0; i=0; " +
            "while [ \$i -lt 15 ] && [ \$R != 1 ]; do o=\$(am broadcast -a app.adbphon.IME_TEXT -p app.adbphon --es b64 '$b64' 2>&1); " +
            "case \"\$o\" in *result=1*) R=1;; *) sleep 0.3 2>/dev/null || sleep 1;; esac; i=\$((i+1)); done; " +
            "if [ -n \"\$P\" ] && [ \"\$P\" != null ]; then ime set \"\$P\" >/dev/null 2>&1; fi; " +
            "if [ \"\$W\" = 0 ]; then ime disable $ime >/dev/null 2>&1; fi; echo IMERES=\$R"
        val out = l.shell(sh, 30_000)
        AppLog.i("ADBphon", "screen text via IME: " + out.lines().lastOrNull { it.startsWith("IMERES") })
        return out.contains("IMERES=1")
    }
}

@Composable
fun AndroidScreenDialog(d: AdbDev, close: () -> Unit) {
    val c = remember { AndroidScreenClient(d.addrs) }
    DisposableEffect(Unit) { c.start(); onDispose { c.stop() } }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            AndroidScreenLive(c, d.title, close)
        }
    }
}

@Composable
private fun AndroidScreenLive(c: AndroidScreenClient, title: String, close: () -> Unit) {
    val vc = LocalViewConfiguration.current
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize(1, 1)) }
    var kbd by remember { mutableStateOf(false) }
    var qMenu by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val fr = c.frame
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ close() }) { Text(stringResource(R.string.btn_close), fontSize = 12.sp) }
            OutlinedButton({ c.key(4) }) { Text(stringResource(R.string.asc_back), fontSize = 12.sp) }
            OutlinedButton({ c.key(3) }) { Text(stringResource(R.string.asc_home), fontSize = 12.sp) }
            OutlinedButton({ c.key(187) }) { Text(stringResource(R.string.asc_recent), fontSize = 12.sp) }
            Box {
                OutlinedButton({ qMenu = true }) { Text(stringResource(R.string.spc_quality), fontSize = 12.sp) }
                DropdownMenu(qMenu, { qMenu = false }) {
                    DropdownMenuItem({ Text((if (c.quality == "auto") "✓ " else "") + stringResource(R.string.spc_q_auto)) }, { c.quality = "auto"; qMenu = false; c.start() })
                    DropdownMenuItem({ Text((if (c.quality == "fast") "✓ " else "") + stringResource(R.string.spc_q_fast)) }, { c.quality = "fast"; qMenu = false; c.start() })
                    DropdownMenuItem({ Text((if (c.quality == "sharp") "✓ " else "") + stringResource(R.string.spc_q_sharp)) }, { c.quality = "sharp"; qMenu = false; c.start() })
                }
            }
            OutlinedButton({ kbd = !kbd }) { Text(stringResource(R.string.spc_keys), fontSize = 12.sp) }
            OutlinedButton({ c.start() }) { Text(stringResource(R.string.asc_restart), fontSize = 12.sp) }
        }
        if (c.note.isNotEmpty()) Text(c.note, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
        if (kbd) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(typed, { typed = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.vw_type_hint), fontSize = 11.sp) })
                    Button({ c.text(typed); typed = "" }) { Text(stringResource(R.string.spc_send)) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Enter" to 66, "⌫" to 67, "Del" to 112, "Tab" to 61, "←" to 21, "↑" to 19, "↓" to 20, "→" to 22, "OK" to 23, "Vol+" to 24, "Vol-" to 25, "Power" to 26, "Menu" to 82).forEach { (l, k) ->
                        OutlinedButton({ c.key(k) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(l, fontSize = 12.sp) }
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { size = it }, contentAlignment = Alignment.Center) {
            val haveVideo = c.vw > 0 && c.vh > 0
            if (c.state != 2 || !haveVideo) {
                Text(if (c.status.isNotEmpty()) c.status else title + "\n" + stringResource(R.string.spc_connecting), color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            }
            // подгонка под экран: картинка целиком, пропорции сохраняются
            val vw = if (c.vw > 0) c.vw else 1; val vh = if (c.vh > 0) c.vh else 1
            val sc = minOf(size.width.toFloat() / vw, size.height.toFloat() / vh)
            val dw = (vw * sc).toInt().coerceAtLeast(1); val dh = (vh * sc).toInt().coerceAtLeast(1)
            val ox = (size.width - dw) / 2; val oy = (size.height - dh) / 2
            if (c.mode != "png") {
                AndroidView(factory = { cx ->
                    SurfaceView(cx).also { sv -> sv.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) { c.surface = h.surface }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { c.surface = h.surface }
                        override fun surfaceDestroyed(h: SurfaceHolder) { c.surface = null }
                    }) }
                }, modifier = Modifier.size(with(density) { dw.toDp() }, with(density) { dh.toDp() }))
            }
            // снимок поверх (запасной режим или ожидание первого кадра потока)
            if ((c.mode == "png" || c.preview) && fr != null) {
                Canvas(Modifier.fillMaxSize()) { drawImage(fr, dstOffset = IntOffset(ox, oy), dstSize = IntSize(dw, dh)) }
            }
            if (c.off) Text(stringResource(R.string.asc_off), color = Color.White, fontSize = 16.sp, modifier = Modifier.background(Color(0xCC000000)).padding(horizontal = 16.dp, vertical = 10.dp))
            if (c.state == 2 && haveVideo) {
                // слой жестов поверх картинки: касание, долгое нажатие, свайп
                Box(Modifier.fillMaxSize().pointerInput(dw, dh, ox, oy) {
                    fun nx(x: Float) = ((x - ox) / dw).coerceIn(0f, 1f)
                    fun ny(y: Float) = ((y - oy) / dh).coerceIn(0f, 1f)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position; var last = start
                        val t0 = down.uptimeMillis; var tEnd = t0
                        var moved = false
                        do {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull() ?: break
                            last = ch.position; tEnd = ch.uptimeMillis
                            if ((last - start).getDistance() > vc.touchSlop) moved = true
                            ch.consume()
                        } while (ev.changes.any { it.pressed })
                        val dur = (tEnd - t0).toInt().coerceIn(1, 3000)
                        if (moved) c.swipe(nx(start.x), ny(start.y), nx(last.x), ny(last.y), dur.coerceIn(80, 800))
                        else if (dur >= 500) c.swipe(nx(start.x), ny(start.y), nx(start.x), ny(start.y), 700)
                        else c.tap(nx(start.x), ny(start.y))
                    }
                })
            }
        }
    }
}
