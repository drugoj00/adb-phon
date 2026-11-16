package app.adbphon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.runBlocking
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 10.1: потоковая отдача файла из сетевого источника или облака внешнему (и встроенному) плееру, как в X-plore.
 * Маленький HTTP-сервер на 127.0.0.1 (порт выбирает система). Файл читается из источника по запросу плеера (Range, HEAD, 200/206/416),
 * на диск ничего не пишется: кусками по 256 КБ из соединения источника прямо в сокет плеера. Старт сразу, перемотка работает.
 * Защита: слушает только 127.0.0.1; в адресе случайный токен (128 бит), по нему отдаётся только файл, который открыли из программы;
 * списков папок нет (любой другой путь: 404). Сервер закрывается, когда плеер отсоединился и прошло [idleMs] без запросов.
 * Пока сервер жив, работает служба переднего плана [StreamService] (иначе система замораживает процесс в фоне и звук/видео встают).
 */
object StreamServer {
    /** Источник одного открытого файла. [size] < 0: размер узнаётся при первом запросе ([RemoteFs.stat]). */
    class Src(val fs: RemoteFs, val path: String, @Volatile var size: Long, val mime: String)

    /** Простой без плеера до закрытия сервера. Менять можно только стендом проверки. */
    @Volatile var idleMs = 10 * 60_000L

    private const val CHUNK = 256 * 1024
    private val srcs = ConcurrentHashMap<String, Src>()
    private val conns: MutableSet<Socket> = Collections.synchronizedSet(HashSet())
    private val active = AtomicInteger()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "stream-conn").apply { isDaemon = true } }
    private val rnd = SecureRandom()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var lastAct = 0L
    @Volatile private var appCtx: Context? = null

    /** Для стенда проверки: сколько байт отдано и сколько запросов принято за всё время жизни процесса. */
    val sentBytes = AtomicLong()
    val requests = AtomicInteger()
    val starts = AtomicInteger()
    val peakConns = AtomicInteger()
    /** Последние запросы плеера (для стенда): время, Range, число байт, время до первого байта. */
    val recent: MutableList<String> = Collections.synchronizedList(ArrayList())

    val running: Boolean get() = server?.isClosed == false
    val port: Int get() = server?.localPort ?: 0
    /** Время открытия источника до первого байта последнего запроса, мс. */
    @Volatile var ttfb = -1L
    val activeConns: Int get() = active.get()
    val sources: Int get() = srcs.size

    /** MIME для заголовка и намерения: по расширению, с запасом для расширений, которых нет в системной таблице. Второй: что слать в HTTP. */
    fun mimeFor(ext: String, cat: String): Pair<String, String> {
        val sys = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (sys != null && (sys.startsWith("video/") || sys.startsWith("audio/"))) return sys to sys
        val t = when (ext) {
            "ts", "m2ts", "mts", "m2t" -> "video/mp2t"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"; "wmv" -> "video/x-ms-wmv"; "flv" -> "video/x-flv"; "mpg", "mpeg", "vob" -> "video/mpeg"; "3gp" -> "video/3gpp"
            "flac" -> "audio/flac"; "opus", "ogg", "oga" -> "audio/ogg"; "mka" -> "audio/x-matroska"; "m4a" -> "audio/mp4"; "ape" -> "audio/x-ape"; "wma" -> "audio/x-ms-wma"
            "aac" -> "audio/aac"; "wav" -> "audio/x-wav"; "mp3" -> "audio/mpeg"
            else -> null
        }
        return if (t != null) t to t else "$cat/*" to "application/octet-stream"
    }

    /** Регистрирует файл и возвращает адрес для плеера (сервер запускается при первом файле). */
    fun register(c: Context, fs: RemoteFs, path: String, size: Long, mime: String, name: String, httpMime: String = mime): String {
        val ctx = c.applicationContext; appCtx = ctx
        val s = ensure()
        val tok = ByteArray(16).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        srcs[tok] = Src(fs, path, size, httpMime)
        lastAct = System.currentTimeMillis()
        StreamService.start(ctx)
        return "http://127.0.0.1:${s.localPort}/$tok/" + Uri.encode(name)
    }

    @Synchronized private fun ensure(): ServerSocket {
        server?.takeIf { !it.isClosed }?.let { return it }
        val s = ServerSocket(0, 20, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        server = s; lastAct = System.currentTimeMillis(); starts.incrementAndGet()
        Thread {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(c) } }
            }
        }.apply { isDaemon = true; name = "stream-accept"; start() }
        Thread {
            // сторож простоя: нет ни одного соединения плеера дольше idleMs: сервер закрывается
            while (server === s && !s.isClosed) {
                runCatching { Thread.sleep(1000) }
                if (active.get() == 0 && System.currentTimeMillis() - lastAct > idleMs) { stop(s); break }
            }
        }.apply { isDaemon = true; name = "stream-idle"; start() }
        return s
    }

    /** Закрыть сервер, разорвать соединения, забыть все адреса и остановить службу. */
    fun stop(only: ServerSocket? = null) {
        var s: ServerSocket? = null
        synchronized(this) { s = server; if (only != null && s !== only) return; server = null }
        runCatching { s?.close() }
        synchronized(conns) { conns.toList() }.forEach { runCatching { it.close() } }
        srcs.clear()
        appCtx?.let { StreamService.stop(it) }
    }

    private fun readHead(i: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 16_384) {
            val b = i.read(); if (b < 0) return null
            sb.append(b.toChar())
            if (sb.length >= 4 && sb[sb.length - 1] == '\n' && sb[sb.length - 2] == '\r' && sb[sb.length - 3] == '\n' && sb[sb.length - 4] == '\r') return sb.toString()
        }
        return null
    }

    private fun reply(o: java.io.OutputStream, code: Int, text: String, extra: String = "") {
        val body = "$code $text\n".toByteArray()
        o.write(("HTTP/1.1 $code $text\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n$extra\r\n").toByteArray(Charsets.ISO_8859_1))
        o.write(body); o.flush()
    }

    /** Разбор заголовка Range для файла из [size] байт: null — Range нет или он непонятен (отдаём весь файл); пустой — недостижимый диапазон (416). */
    internal fun parseRange(h: String?, size: Long): LongArray? {
        val v = h?.substringAfter(':')?.trim()?.lowercase() ?: return null
        if (!v.startsWith("bytes=")) return null
        val first = v.removePrefix("bytes=").substringBefore(',').trim()
        val a = first.substringBefore('-').trim(); val b = first.substringAfter('-', "").trim()
        if (!first.contains('-')) return null
        if (a.isEmpty()) { val n = b.toLongOrNull() ?: return null; return if (n <= 0) LongArray(0) else longArrayOf((size - n).coerceAtLeast(0), size - 1) }
        val st = a.toLongOrNull() ?: return null
        val en = if (b.isEmpty()) size - 1 else (b.toLongOrNull() ?: return null).coerceAtMost(size - 1)
        if (st >= size || en < st) return LongArray(0)
        return longArrayOf(st, en)
    }

    private fun handle(sock: Socket) {
        val now = active.incrementAndGet()
        peakConns.updateAndGet { if (now > it) now else it }
        conns.add(sock)
        var ins: InputStream? = null
        try {
            sock.soTimeout = 30_000; sock.tcpNoDelay = true
            val head = readHead(sock.getInputStream()) ?: return
            val out = sock.getOutputStream()
            val lines = head.split("\r\n")
            val rl = lines[0].split(' ')
            if (rl.size < 2) { reply(out, 400, "Bad Request"); return }
            val method = rl[0].uppercase()
            if (method != "GET" && method != "HEAD") { reply(out, 405, "Method Not Allowed", "Allow: GET, HEAD\r\n"); return }
            requests.incrementAndGet(); lastAct = System.currentTimeMillis(); val tRecv = lastAct
            val seg = rl[1].substringBefore('?').split('/')
            // только /токен/имя: любой другой путь, корень и каталоги не отдаются
            val src = if (seg.size == 3 && seg[0].isEmpty()) srcs[seg[1]] else null
            if (src == null) { reply(out, 404, "Not Found"); return }
            if (src.size < 0) src.size = runCatching { runBlocking { src.fs.stat(src.path)?.size ?: 0L } }.getOrDefault(0L)
            val size = src.size
            if (size <= 0) { reply(out, 502, "Bad Gateway"); return }
            val rg = parseRange(lines.firstOrNull { it.startsWith("range:", true) }, size)
            if (rg != null && rg.isEmpty()) { reply(out, 416, "Range Not Satisfiable", "Content-Range: bytes */$size\r\n"); return }
            val start = rg?.get(0) ?: 0L; val end = rg?.get(1) ?: (size - 1)
            val total = end - start + 1
            // источник открывается лениво, при первом чтении: плееры часто присылают пробные запросы и сразу закрывают их (для FTP, SFTP и облака это целое соединение или процесс)
            val sb = StringBuilder(if (rg != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            sb.append("Content-Type: ").append(src.mime).append("\r\nContent-Length: ").append(total).append("\r\nAccept-Ranges: bytes\r\n")
            if (rg != null) sb.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(size).append("\r\n")
            sb.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1)); out.flush()
            if (method == "HEAD") return
            // плееры шлют пробные запросы и сразу закрывают соединение, дочитав заголовки: короткая пауза, чтобы не открывать из-за них соединение с источником
            sock.soTimeout = 120
            try { if (sock.getInputStream().read() < 0) return } catch (_: java.net.SocketTimeoutException) {}
            sock.soTimeout = 0   // дальше только запись: плеер на паузе не читает, это не ошибка
            var pos = start; var left = total; var retries = 0
            val buf = ByteArray(CHUNK)
            var tFirst = -1L
            val tag = "${System.currentTimeMillis() % 100000} $method ${if (rg != null) "$start-$end" else "full"}"
            recent.add(tag); if (recent.size > 40) recent.removeAt(0)
            while (left > 0) {
                if (ins == null) ins = try { runBlocking { src.fs.openAt(src.path, pos) } } catch (e: Throwable) { if (++retries > 3) { AppLog.i("ADBphon", "stream: открыть не удалось: ${e.javaClass.simpleName}: ${e.message}"); throw e }; Thread.sleep(400); null }
                val i = ins ?: continue
                // обрыв источника посреди передачи (простой соединения, смена сети): до трёх раз открываем заново с текущей позиции, плеер ничего не заметит
                val n = try { val r = i.read(buf, 0, minOf(CHUNK.toLong(), left).toInt()); if (r < 0) throw EOFException("источник кончился раньше времени"); r }
                catch (e: IOException) { if (++retries > 3) throw e; runCatching { i.close() }; ins = null; Thread.sleep(400); continue }
                out.write(buf, 0, n)
                pos += n; left -= n; sentBytes.addAndGet(n.toLong()); lastAct = System.currentTimeMillis()
                if (tFirst < 0) { tFirst = lastAct - tRecv; ttfb = tFirst }
            }
            out.flush()
        } catch (_: Throwable) {
            // плеер закрыл соединение (перемотка, выход) или источник недоступен: тихо закрываем
        } finally {
            runCatching { ins?.close() }
            runCatching { sock.close() }
            conns.remove(sock)
            lastAct = System.currentTimeMillis()
            active.decrementAndGet()
        }
    }

    fun status(): String = "running=$running port=$port active=${active.get()} sources=${srcs.size} requests=${requests.get()} sent=${sentBytes.get()} starts=${starts.get()} peak=${peakConns.get()} idleMs=$idleMs ttfb=$ttfb"
}

/** Служба переднего плана на время работы [StreamServer]: уведомление с кнопкой «Закрыть», блокировка сна (экран на время просмотра гасится по настройкам плеера). */
class StreamService : Service() {
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { StreamServer.stop(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, getString(R.string.stream_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val close = PendingIntent.getService(this, 3, Intent(this, StreamService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this).setSmallIcon(R.drawable.ic_stat_files).setOngoing(true).setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.stream_notif_title)).setContentText(getString(R.string.stream_notif_text)).setContentIntent(open)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), getString(R.string.btn_close), close).build())
            .also { if (android.os.Build.VERSION.SDK_INT >= 26) it.setChannelId(CH) }.build()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        if (wl == null) wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "adbphon:stream").apply { setReferenceCounted(false); acquire(6 * 3600_000L) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wl?.takeIf { it.isHeld }?.release() }; wl = null
        // службу убрала система или пользователь (кнопка «Закрыть»): сервер без неё не живёт
        StreamServer.stop()
        super.onDestroy()
    }

    companion object {
        const val CH = "stream"; const val ID = 4111
        fun start(ctx: Context) { runCatching { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, StreamService::class.java)) } }
        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, StreamService::class.java)) } }
    }
}
