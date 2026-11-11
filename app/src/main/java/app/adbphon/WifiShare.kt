package app.adbphon

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Wi-Fi сервер для прямого подключения: папка этого устройства открывается по ссылке в браузере любого телефона, компьютера или телевизора в той же сети.
 * Из браузера можно скачивать файлы и загружать свои (форма внизу страницы). Ссылка содержит случайный ключ; без ключа сервер ничего не отдаёт.
 */
object WifiShare {
    var running by mutableStateOf(false)
    var url by mutableStateOf("")
    var root by mutableStateOf("")
    var error by mutableStateOf("")
    /** Ключ показан отдельно (для WebDAV-пароля); пуст в режиме своей сети. */
    var key by mutableStateOf("")
    @Volatile private var open = false
    val PORTS = listOf(8080, 8081, 8082, 8090, 8888, 1111)
    /** Порт, на котором сервер слушает сейчас. */
    @Volatile var port = 0
    /** Заголовок X-ADBphon для поиска: имя устройства и нужен ли пароль. */
    @Volatile private var tag = ""
    private var server: ServerSocket? = null
    private var token = ""
    private var pool = Executors.newCachedThreadPool()

    /** [openNet]: своя точка доступа этого устройства: подключиться могут только знающие пароль сети, ключ в ссылке не нужен. */
    fun start(ctx: Context, dir: String, openNet: Boolean = false) {
        stop()
        error = ""
        open = openNet
        tag = URLEncoder.encode((android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + android.os.Build.MODEL).trim(), "UTF-8").replace("+", "%20") + "|" + (if (openNet) "1" else "0")
        val base = File(dir).takeIf { it.isDirectory } ?: File("/sdcard")
        // адрес локальной сети: в своей сети интерфейс точки доступа, иначе eth0/wlan, а не VPN (tun/ppp) и не мобильный
        val lan = runCatching {
            val ifs = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
            fun addrs(f: (java.net.NetworkInterface) -> Boolean) = ifs.filter(f).flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().filter { it.isSiteLocalAddress }.mapNotNull { it.hostAddress } }
            val ap = addrs { it.name.startsWith("ap") || it.name.startsWith("swlan") || it.name.startsWith("wlan1") || it.name.startsWith("wigig") || it.name.startsWith("p2p") }
            val gen = addrs { it.name.startsWith("wlan") || it.name.startsWith("eth") }.let { l -> if (openNet) l.sortedBy { 0 } else l }
            val wl = addrs { it.name.startsWith("wlan") }
            (if (openNet) ap.firstOrNull() ?: wl.firstOrNull() ?: gen.firstOrNull() else gen.firstOrNull() ?: ap.firstOrNull())
        }.getOrNull()
        val ip = lan ?: Discovery.myAddresses(ctx).firstOrNull() ?: run { error = ctx.getString(R.string.ws_no_net); return }
        // порт 8080 нужен для поиска устройств; reuseAddress: сразу после выключения порт не «залипает» и сервер не уходит на случайный порт
        // порт из короткого списка (его же перебирает поиск устройств): если 8080 занят другой программой, берётся следующий
        val s = PORTS.firstNotNullOfOrNull { pt -> runCatching { ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress(pt), 20) } }.getOrNull() }
            ?: runCatching { ServerSocket(0, 20) }.getOrElse { error = it.message.orEmpty(); return }
        server = s
        port = s.localPort
        token = ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "abcdefghjkmnpqrstuvwxyz23456789"[(it.toInt() and 0xFF) % 31].toString() }
        root = base.absolutePath
        key = if (openNet) "" else token
        url = if (openNet) "http://$ip:${s.localPort}/" else "http://$ip:${s.localPort}/$token/"
        running = true
        pool = Executors.newCachedThreadPool()
        Thread {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(c, base) }; runCatching { c.close() } }
            }
        }.also { it.isDaemon = true }.start()
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        runCatching { pool.shutdownNow() }
        running = false; url = ""; key = ""
    }

    // ------------------------------------------------------------ HTTP

    private fun readLine(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
            if (b.size() > 16384) break
        }
        return b.toString("UTF-8")
    }

    private fun handle(sock: Socket, base: File) {
        sock.soTimeout = 60_000
        val ins = BufferedInputStream(sock.getInputStream())
        val out = sock.getOutputStream()
        val reqLine = readLine(ins) ?: return
        val parts = reqLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val headers = HashMap<String, String>()
        while (true) { val l = readLine(ins) ?: break; if (l.isEmpty()) break; val k = l.indexOf(':'); if (k > 0) headers[l.substring(0, k).trim().lowercase()] = l.substring(k + 1).trim() }
        val full = parts[1]
        val path = decode(full.substringBefore('?'))
        val seg = path.trim('/').split('/').filter { it.isNotEmpty() }
        val basicOk = headers["authorization"]?.let { h -> runCatching { String(android.util.Base64.decode(h.substringAfter(' ').trim(), android.util.Base64.DEFAULT), Charsets.UTF_8).substringAfter(':') == token }.getOrDefault(false) } == true
        val pathMode = !open && seg.firstOrNull() == token
        if (!(open || pathMode || basicOk)) {
            discard(ins, headers)
            send(out, 401, listOf("WWW-Authenticate: Basic realm=\"ADB-phon\""), "text/plain; charset=utf-8", "Нужен ключ".toByteArray()); return
        }
        val pre = if (pathMode) "/$token/" else "/"
        val rel = (if (pathMode) seg.drop(1) else seg).joinToString("/")
        val target = File(base, rel).canonicalFile
        if (!inside(base, target)) { discard(ins, headers); send(out, 403, emptyList(), "text/plain; charset=utf-8", "Нет доступа".toByteArray()); return }
        when {
            method == "OPTIONS" -> { discard(ins, headers); send(out, 200, listOf("DAV: 1, 2", "Allow: OPTIONS, GET, HEAD, POST, PUT, DELETE, MKCOL, MOVE, COPY, PROPFIND, PROPPATCH, LOCK, UNLOCK", "MS-Author-Via: DAV"), "text/plain", ByteArray(0)) }
            method == "PROPFIND" -> { discard(ins, headers); propfind(out, base, target, pre, headers["depth"] ?: "1") }
            method == "PROPPATCH" -> { discard(ins, headers); send(out, 207, emptyList(), "application/xml; charset=utf-8", "<?xml version=\"1.0\"?><D:multistatus xmlns:D=\"DAV:\"/>".toByteArray()) }
            method == "PUT" -> put(ins, out, headers, target)
            method == "MKCOL" -> { discard(ins, headers); when { target.exists() -> send(out, 405, emptyList(), "text/plain", ByteArray(0)); target.parentFile?.isDirectory != true -> send(out, 409, emptyList(), "text/plain", ByteArray(0)); target.mkdir() -> send(out, 201, emptyList(), "text/plain", ByteArray(0)); else -> send(out, 403, emptyList(), "text/plain", ByteArray(0)) } }
            method == "DELETE" -> { discard(ins, headers); if (!target.exists()) send(out, 404, emptyList(), "text/plain", ByteArray(0)) else if (target.canonicalPath == base.canonicalPath) send(out, 403, emptyList(), "text/plain", ByteArray(0)) else { target.deleteRecursively(); send(out, 204, emptyList(), "text/plain", ByteArray(0)) } }
            method == "MOVE" -> { discard(ins, headers); move(out, base, target, pre, headers, copy = false) }
            method == "COPY" -> { discard(ins, headers); move(out, base, target, pre, headers, copy = true) }
            // блокировки не настоящие (один владелец), но Windows, macOS и многие менеджеры файлов без них пишут только «для чтения»
            method == "LOCK" -> { discard(ins, headers); lock(out, target, pre, base) }
            method == "UNLOCK" -> { discard(ins, headers); send(out, 204, emptyList(), "text/plain", ByteArray(0)) }
            method == "POST" && target.isDirectory -> { upload(ins, headers, target); redirect(out, path) }
            method == "GET" && target.isDirectory -> send(out, 200, emptyList(), "text/html; charset=utf-8", page(base, target, pre).toByteArray())
            (method == "GET" || method == "HEAD") && target.isFile -> sendFile(out, target, headers["range"], method == "HEAD")
            else -> { discard(ins, headers); send(out, 404, emptyList(), "text/plain; charset=utf-8", "Не найдено".toByteArray()) }
        }
    }

    private fun decode(raw: String) = URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
    private fun inside(base: File, f: File) = f.path == base.canonicalPath || f.path.startsWith(base.canonicalPath + "/")
    private fun encSeg(n: String) = URLEncoder.encode(n, "UTF-8").replace("+", "%20")

    /** Прочитать и отбросить тело запроса (по Content-Length), чтобы клиент получил ответ без обрыва связи. */
    private fun discard(ins: InputStream, headers: Map<String, String>) {
        var left = headers["content-length"]?.toLongOrNull() ?: return
        val buf = ByteArray(8192)
        while (left > 0) { val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; left -= n }
    }

    private fun send(out: OutputStream, code: Int, extra: List<String>, type: String, body: ByteArray) {
        val st = when (code) { 200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 207 -> "Multi-Status"; 303 -> "See Other"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"; 412 -> "Precondition Failed"; else -> "Error" }
        val sb = StringBuilder("HTTP/1.1 $code $st\r\n")
        if (code != 204) sb.append("Content-Type: $type\r\n")
        extra.forEach { sb.append(it).append("\r\n") }
        sb.append("X-ADBphon: ").append(tag).append("\r\n")
        sb.append("Content-Length: ${if (code == 204) 0 else body.size}\r\nConnection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        if (code != 204) out.write(body)
        out.flush()
    }

    private fun xmlEsc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun propfind(out: OutputStream, base: File, target: File, pre: String, depth: String) {
        if (!target.exists()) { send(out, 404, emptyList(), "text/plain; charset=utf-8", "Не найдено".toByteArray()); return }
        val rfc = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        fun href(f: File): String {
            val rel = f.canonicalPath.removePrefix(base.canonicalPath).trim('/')
            return pre + (if (rel.isEmpty()) "" else rel.split('/').joinToString("/") { encSeg(it) } + (if (f.isDirectory) "/" else ""))
        }
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">")
        fun one(f: File) {
            sb.append("<D:response><D:href>").append(xmlEsc(href(f))).append("</D:href><D:propstat><D:prop><D:displayname>").append(xmlEsc(f.name)).append("</D:displayname>")
            if (f.isDirectory) sb.append("<D:resourcetype><D:collection/></D:resourcetype>")
            else sb.append("<D:resourcetype/><D:getcontentlength>").append(f.length()).append("</D:getcontentlength><D:getcontenttype>").append(mime(f).substringBefore(';')).append("</D:getcontenttype>")
            sb.append("<D:getlastmodified>").append(rfc.format(Date(f.lastModified()))).append("</D:getlastmodified>")
            sb.append("<D:creationdate>").append(iso.format(Date(f.lastModified()))).append("</D:creationdate>")
            sb.append("<D:getetag>\"").append(java.lang.Long.toHexString(f.lastModified())).append('-').append(java.lang.Long.toHexString(f.length())).append("\"</D:getetag>")
            sb.append("<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock>")
            if (f.canonicalPath == base.canonicalPath) runCatching { val st = android.os.StatFs(f.path); sb.append("<D:quota-available-bytes>").append(st.availableBytes).append("</D:quota-available-bytes><D:quota-used-bytes>").append(st.totalBytes - st.availableBytes).append("</D:quota-used-bytes>") }
            sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
        }
        one(target)
        if (target.isDirectory && depth != "0") (target.listFiles() ?: emptyArray()).sortedBy { it.name.lowercase() }.forEach { one(it) }
        sb.append("</D:multistatus>")
        send(out, 207, emptyList(), "application/xml; charset=utf-8", sb.toString().toByteArray())
    }

    /** Приём файла целиком (Content-Length или chunked) во временный файл, затем замена. */
    private fun put(ins: InputStream, out: OutputStream, headers: Map<String, String>, target: File) {
        val par = target.parentFile
        if (par == null || !par.isDirectory) { discard(ins, headers); send(out, 409, emptyList(), "text/plain", ByteArray(0)); return }
        if (target.isDirectory) { discard(ins, headers); send(out, 405, emptyList(), "text/plain", ByteArray(0)); return }
        val existed = target.exists()
        if (headers["expect"]?.contains("100", true) == true) { out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); out.flush() }
        val tmp = File(par, "." + target.name + ".adbphon-part")
        try {
            tmp.outputStream().buffered(64 * 1024).use { os ->
                if (headers["transfer-encoding"]?.contains("chunked", true) == true) {
                    while (true) {
                        val ln = readLine(ins)?.substringBefore(';')?.trim() ?: break
                        val n = ln.toIntOrNull(16) ?: break
                        if (n == 0) { readLine(ins); break }
                        var left = n; val buf = ByteArray(16384)
                        while (left > 0) { val r = ins.read(buf, 0, minOf(buf.size, left)); if (r < 0) throw java.io.IOException("eof"); os.write(buf, 0, r); left -= r }
                        readLine(ins)
                    }
                } else {
                    var left = headers["content-length"]?.toLongOrNull() ?: 0L
                    val buf = ByteArray(64 * 1024)
                    while (left > 0) { val r = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) throw java.io.IOException("eof"); os.write(buf, 0, r); left -= r }
                }
            }
            if (existed) target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("rename")
            send(out, if (existed) 204 else 201, emptyList(), "text/plain", ByteArray(0))
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            runCatching { send(out, 403, emptyList(), "text/plain", ByteArray(0)) }
        }
    }

    private fun move(out: OutputStream, base: File, from: File, pre: String, headers: Map<String, String>, copy: Boolean) {
        val dst = headers["destination"]
        if (dst == null || !from.exists()) { send(out, 404, emptyList(), "text/plain", ByteArray(0)); return }
        val dpath = decode(runCatching { java.net.URI(dst).rawPath }.getOrDefault(dst))
        val rest = if (dpath.startsWith(pre)) dpath.removePrefix(pre) else dpath.trimStart('/')
        val to = File(base, rest.trim('/')).canonicalFile
        if (!inside(base, to) || to.path == base.canonicalPath) { send(out, 403, emptyList(), "text/plain", ByteArray(0)); return }
        val over = !headers["overwrite"].equals("F", true)
        val existed = to.exists()
        if (existed && !over) { send(out, 412, emptyList(), "text/plain", ByteArray(0)); return }
        if (to.parentFile?.isDirectory != true) { send(out, 409, emptyList(), "text/plain", ByteArray(0)); return }
        if (existed) to.deleteRecursively()
        val ok = if (copy) runCatching { if (from.isDirectory) from.copyRecursively(to, true) else { from.copyTo(to, true); true } }.getOrDefault(false)
                 else (from.renameTo(to) || runCatching { if (from.isDirectory) from.copyRecursively(to, true) && from.deleteRecursively() else { from.copyTo(to, true); from.delete() } }.getOrDefault(false))
        if (ok) send(out, if (existed) 204 else 201, emptyList(), "text/plain", ByteArray(0)) else send(out, 403, emptyList(), "text/plain", ByteArray(0))
    }

    private fun lock(out: OutputStream, target: File, pre: String, base: File) {
        val token = "opaquelocktoken:" + java.util.UUID.randomUUID()
        val rel = target.canonicalPath.removePrefix(base.canonicalPath).trim('/')
        val href = pre + (if (rel.isEmpty()) "" else rel.split('/').joinToString("/") { encSeg(it) })
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock><D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope><D:depth>infinity</D:depth><D:timeout>Second-3600</D:timeout><D:locktoken><D:href>$token</D:href></D:locktoken><D:lockroot><D:href>" + xmlEsc(href) + "</D:href></D:lockroot></D:activelock></D:lockdiscovery></D:prop>"
        send(out, 200, listOf("Lock-Token: <$token>"), "application/xml; charset=utf-8", body.toByteArray())
    }

    private fun redirect(out: OutputStream, to: String) {
        out.write("HTTP/1.1 303 See Other\r\nLocation: $to\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
    }

    private fun mime(f: File): String = when (f.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
        "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/aac"; "flac" -> "audio/flac"; "ogg" -> "audio/ogg"; "wav" -> "audio/wav"
        "txt", "log", "md" -> "text/plain; charset=utf-8"; "html", "htm" -> "text/html; charset=utf-8"; "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    private fun sendFile(out: OutputStream, f: File, range: String?, head: Boolean) {
        val len = f.length()
        var from = 0L; var to = len - 1
        var partial = false
        val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
        if (m != null) {
            val a = m.groupValues[1]; val b = m.groupValues[2]
            if (a.isNotEmpty()) { from = a.toLong(); if (b.isNotEmpty()) to = minOf(b.toLong(), len - 1) }
            else if (b.isNotEmpty()) from = maxOf(0L, len - b.toLong())
            partial = true
        }
        if (from > to) { from = 0; to = len - 1; partial = false }
        val size = to - from + 1
        val sb = StringBuilder()
        sb.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: ${mime(f)}\r\nAccept-Ranges: bytes\r\nContent-Length: $size\r\nConnection: close\r\n")
        if (partial) sb.append("Content-Range: bytes $from-$to/$len\r\n")
        sb.append("Content-Disposition: inline; filename*=UTF-8''${URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")}\r\n\r\n")
        out.write(sb.toString().toByteArray())
        if (head) { out.flush(); return }
        f.inputStream().use { ins ->
            ins.skip(from)
            val buf = ByteArray(64 * 1024)
            var left = size
            while (left > 0) { val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; out.write(buf, 0, n); left -= n }
        }
        out.flush()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(base: File, dir: File, pre: String): String {
        val rel = dir.canonicalPath.removePrefix(base.canonicalPath).trim('/')
        val prefix = pre + (if (rel.isEmpty()) "" else rel.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") } + "/")
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>ADB-phon</title>")
        sb.append("<style>body{font-family:sans-serif;margin:16px;max-width:900px}a{text-decoration:none;color:#0F766E}li{margin:6px 0}small{color:#667}</style></head><body>")
        sb.append("<h2>/").append(esc(rel)).append("</h2><ul>")
        if (rel.isNotEmpty()) sb.append("<li><a href=\"").append(prefix.substringBeforeLast('/').substringBeforeLast('/')).append("/\">⬆ ..</a></li>")
        val items = (dir.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") }.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        for (f in items) {
            val enc = URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
            if (f.isDirectory) sb.append("<li>📁 <a href=\"").append(prefix).append(enc).append("/\">").append(esc(f.name)).append("</a></li>")
            else sb.append("<li>📄 <a href=\"").append(prefix).append(enc).append("\">").append(esc(f.name)).append("</a> <small>")
                .append(sizeText(f.length())).append(" · ").append(df.format(Date(f.lastModified()))).append("</small></li>")
        }
        sb.append("</ul><hr><form method=\"post\" enctype=\"multipart/form-data\" action=\"").append(prefix).append("\"><input type=\"file\" name=\"f\" multiple> <button>Загрузить сюда</button></form></body></html>")
        return sb.toString()
    }

    private fun sizeText(n: Long): String = when {
        n >= 1L shl 30 -> String.format(Locale.US, "%.1f ГБ", n / 1073741824.0)
        n >= 1L shl 20 -> String.format(Locale.US, "%.1f МБ", n / 1048576.0)
        n >= 1L shl 10 -> String.format(Locale.US, "%.0f КБ", n / 1024.0)
        else -> "$n Б"
    }

    /** Разбор multipart/form-data потоком: файлы сохраняются в [dir] под безопасным именем (без пути, без перезаписи чужих). */
    private fun upload(ins: InputStream, headers: Map<String, String>, dir: File) {
        val ct = headers["content-type"] ?: return
        val boundary = Regex("boundary=\"?([^\";]+)\"?").find(ct)?.groupValues?.get(1) ?: return
        val delim = ("\r\n--$boundary").toByteArray()
        var left = headers["content-length"]?.toLongOrNull() ?: return
        fun rd(): Int { if (left <= 0) return -1; val c = ins.read(); if (c >= 0) left--; return c }
        // первая граница: «--boundary\r\n»
        var skip = ByteArrayOutputStream()
        while (true) { val c = rd(); if (c < 0) return; skip.write(c); if (skip.size() >= 2 && skip.toString("ISO-8859-1").contains("--$boundary\r\n")) break; if (skip.size() > 4096) return }
        while (left > 0) {
            // заголовки части
            val hb = ByteArrayOutputStream()
            while (true) { val c = rd(); if (c < 0) return; hb.write(c); val t = hb.toByteArray(); if (t.size >= 4 && t[t.size - 4] == '\r'.code.toByte() && t[t.size - 3] == '\n'.code.toByte() && t[t.size - 2] == '\r'.code.toByte() && t[t.size - 1] == '\n'.code.toByte()) break; if (hb.size() > 16384) return }
            val head = hb.toString("UTF-8")
            val raw = Regex("filename=\"([^\"]*)\"").find(head)?.groupValues?.get(1)
            val name = raw?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
            var os: OutputStream? = null
            if (name != null) {
                var f = File(dir, name)
                var n = 1
                while (f.exists()) { f = File(dir, name.substringBeforeLast('.', name) + " ($n)" + (if ('.' in name) "." + name.substringAfterLast('.') else "")); n++ }
                os = f.outputStream().buffered(64 * 1024)
            }
            // тело до границы: скользящее окно
            val win = ByteArray(delim.size); var wl = 0
            var end = false
            while (!end) {
                val c = rd(); if (c < 0) { os?.close(); return }
                if (wl < win.size) win[wl++] = c.toByte()
                else {
                    os?.write(win[0].toInt())
                    System.arraycopy(win, 1, win, 0, win.size - 1); win[win.size - 1] = c.toByte()
                }
                if (wl == win.size && win.contentEquals(delim)) end = true
            }
            os?.close()
            // после границы: «\r\n» (следующая часть) или «--» (конец)
            val a = rd(); val b = rd()
            if (a == '-'.code && b == '-'.code) return
        }
    }
}

/** Окно «Wi-Fi сервер»: запуск и остановка, ссылка для браузера. */
@Composable
fun WifiServerDialog(startDir: String, close: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    @Suppress("UNUSED_EXPRESSION") tick
    LAlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.ws_title), fontSize = 18.sp) },
        text = {
            Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (WifiShare.running) {
                    if (WifiDirect.active) Text(stringResource(R.string.ws_p2p_info, WifiDirect.ssid, WifiDirect.pass, WifiDirect.ip), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    else if (WifiDirect.error.isNotEmpty()) Text(WifiDirect.error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    if (WifiHotspot.active) {
                        Text(stringResource(R.string.ws_hot_net), fontSize = 14.sp)
                        SelectionContainer { Text(WifiHotspot.ssid, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                        Text(stringResource(R.string.ws_hot_pass), fontSize = 12.sp)
                        SelectionContainer { Text(WifiHotspot.pass, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                        Text(stringResource(R.string.ws_hot_then), fontSize = 13.sp)
                    } else Text(stringResource(R.string.ws_on), fontSize = 14.sp)
                    SelectionContainer { Text(WifiShare.url, fontSize = 17.sp, color = MaterialTheme.colorScheme.primary) }
                    if (WifiShare.key.isNotEmpty()) Text(stringResource(R.string.ws_dav_key, WifiShare.key), fontSize = 12.sp)
                    Text(stringResource(R.string.ws_folder, WifiShare.root), fontSize = 12.sp)
                    Text(stringResource(R.string.ws_dav_how), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f))
                } else {
                    HintTitle(stringResource(R.string.ws_off, startDir), stringResource(R.string.ws_p2p_hint), titleSize = 14.sp, titleWeight = null, hintSize = 13.sp)
                    if (WifiShare.error.isNotEmpty()) Text(WifiShare.error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    if (WifiHotspot.error.isNotEmpty()) Text(WifiHotspot.error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                Text(stringResource(if (WifiHotspot.active) R.string.ws_warn_net else R.string.ws_warn), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
            }
        },
        confirmButton = {
            if (WifiShare.running) TextButton({ WifiShare.stop(); WifiHotspot.stop(); WifiDirect.stop() }) { Text(stringResource(R.string.ws_stop)) }
            else TextButton({ WifiHotspot.error = ""; WifiDirect.error = ""; WifiExplorer.toggleServer(ctx) }) { Text(stringResource(R.string.ws_start)) }
        },
        dismissButton = { TextButton(close) { Text(stringResource(R.string.btn_close)) } },
    )
}
