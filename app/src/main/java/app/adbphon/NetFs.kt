package app.adbphon

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Сетевые источники «Файлов»: SMB, SFTP, FTP/FTPS, WebDAV(S). Все только читают и копируют в память (как SMB раньше).
 * Пароли хранятся в избранном ТОЛЬКО в зашифрованном виде (ключ в Android Keystore, AES-GCM) и никуда не отправляются.
 */
interface RemoteFs : Fs {
    /** Стартовая папка. */
    val root: String
    /** Подпись источника в списке («sftp://хост»). */
    val label: String
    suspend fun head(path: String, n: Int): ByteArray
    /** Закрыть соединения (при переподключении и закрытии источника). */
    fun close() {}
    /** Сам источник без обёртки постоянной связи ([KeepFs]); нужен, где важен конкретный тип (WebDAV-поток для плеера). */
    val raw: RemoteFs get() = this
    /** Лёгкая проверка, что связь жива (для keepalive и проверки после простоя). По умолчанию чтение папки. */
    suspend fun ping(path: String) { list(path) }

    // ---- запись (SFTP, FTP/FTPS, WebDAV, SMB); по умолчанию не поддерживается ----
    /** Запись по пути: размер и тип, null если нет. По умолчанию через список родительской папки. */
    suspend fun stat(path: String): FsEntry? { val par = parent(path.trimEnd('/')) ?: return null; val n = path.trimEnd('/').substringAfterLast('/'); return list(par).firstOrNull { it.name == n } }
    suspend fun mkdirRemote(path: String) { throw UnsupportedOperationException("mkdir") }
    /** Переименование; [overwrite]: заменить существующий файл (не все серверы умеют, тогда ошибка и запасной путь в [KeepFs.upload]). */
    suspend fun renameRemote(from: String, to: String, overwrite: Boolean) { throw UnsupportedOperationException("rename") }
    suspend fun removeRemote(path: String, isDir: Boolean) { throw UnsupportedOperationException("remove") }
    /** Записать локальный файл ровно по этому пути (поток, без загрузки в память); отмена: [OpProgress.cancelled]. Безопасную замену делает [KeepFs.upload]. */
    suspend fun putFile(src: File, dest: String, j: OpProgress?) { throw UnsupportedOperationException("put") }
}

/** Нет права записи в папку: понятное сообщение вместо молчаливой ошибки. */
class NoWriteRights : java.io.IOException(FmIo.app.getString(R.string.fm_no_write))

/** Файл для выгрузки как поток: считает байты и обрывается по отмене операции. */
class UpStream(f: File, private val j: OpProgress?) : InputStream() {
    private val ins = f.inputStream().buffered(256 * 1024)
    override fun read(): Int { if (j?.cancelled == true) throw LocalOps.Cancelled(); val b = ins.read(); if (b >= 0) j?.bytes(1); return b }
    override fun read(b: ByteArray, off: Int, len: Int): Int { if (j?.cancelled == true) throw LocalOps.Cancelled(); val n = ins.read(b, off, len); if (n > 0) j?.bytes(n.toLong()); return n }
    override fun close() { ins.close() }
}

/** Частные адреса локальной сети: самоподписанные сертификаты NAS и роутеров принимаются только для них. */
fun isPrivateHost(h: String): Boolean {
    val x = h.lowercase()
    if (x.endsWith(".local") || x == "localhost") return true
    val p = x.split('.').mapNotNull { it.toIntOrNull() }
    if (p.size != 4) return false
    return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 169 && p[1] == 254)
}

private val trustAll = object : X509TrustManager {
    override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
    override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun cut(n: Int, ins: InputStream): ByteArray {
    val buf = ByteArray(n); var got = 0
    while (got < n) { val r = ins.read(buf, got, n - got); if (r < 0) break; got += r }
    return buf.copyOf(got)
}

// ------------------------------------------------------------------ SFTP (JSch)

class SftpFs(private val host: String, private val port: Int, private val user: String, private val pass: String) : RemoteFs {
    private var session: Session? = null
    private var home = "/"
    override val root get() = home
    override val label get() = "sftp://$host" + if (port != 22) ":$port" else ""

    @Synchronized
    private fun ch(): ChannelSftp {
        var s = session
        if (s == null || !s.isConnected) {
            s = JSch().getSession(user.ifBlank { "anonymous" }, host, port).apply {
                setPassword(pass)
                setConfig("StrictHostKeyChecking", "no")   // LAN: ключ хоста не закрепляется
                setConfig("PreferredAuthentications", "password,keyboard-interactive")
                setServerAliveInterval(10_000)             // keepalive сервера каждые 10 с,
                setServerAliveCountMax(2)                  // два пропуска: сессия считается мёртвой
                timeout = 8000                             // простой сокета не дольше 8 с
                connect(5000)
            }
            session = s
        }
        val c = s.openChannel("sftp") as ChannelSftp
        c.connect(8000)
        if (home == "/") runCatching { home = c.pwd() }
        return c
    }

    private inline fun <T> use(block: (ChannelSftp) -> T): T { val c = ch(); try { return block(c) } finally { runCatching { c.disconnect() } } }

    suspend fun connect() = withContext(Dispatchers.IO) { use { } }
    override fun close() { synchronized(this) { runCatching { session?.disconnect() }; session = null } }
    override suspend fun ping(path: String) { withContext(Dispatchers.IO) { synchronized(this@SftpFs) { ch().disconnect(); session?.sendKeepAliveMsg() } } }

    override suspend fun list(path: String): List<FsEntry> = withContext(Dispatchers.IO) {
        use { c ->
            (c.ls(path) as Iterable<*>).map { it as ChannelSftp.LsEntry }.filter { it.filename != "." && it.filename != ".." }.map { e ->
                val dir = e.attrs.isDir || (e.attrs.isLink && runCatching { c.stat(join(path, e.filename)).isDir }.getOrDefault(false))
                FsEntry(e.filename, join(path, e.filename), dir, if (dir) 0L else e.attrs.size)
            }.sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })
        }
    }

    override suspend fun copyTo(path: String, dest: File) { withContext(Dispatchers.IO) { use { c -> dest.outputStream().use { c.get(path, it) } } } }
    override suspend fun head(path: String, n: Int): ByteArray = withContext(Dispatchers.IO) { use { c -> c.get(path).use { cut(n, it) } } }
    override fun parent(path: String): String? { val t = path.trimEnd('/'); return if (t.isEmpty() || t == "/") null else t.substringBeforeLast('/', "").ifEmpty { "/" } }
    override suspend fun roots(): List<Pair<String, String>> = listOf(host to root)
    private fun join(d: String, n: String) = if (d.endsWith("/")) d + n else "$d/$n"

    private fun w(e: com.jcraft.jsch.SftpException): Throwable = if (e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) NoWriteRights() else java.io.IOException("SFTP: " + (e.message ?: e.id.toString()), e)
    override suspend fun stat(path: String): FsEntry? = withContext(Dispatchers.IO) { use { c ->
        try { val a = c.stat(path); FsEntry(path.trimEnd('/').substringAfterLast('/'), path, a.isDir, if (a.isDir) 0L else a.size) } catch (e: com.jcraft.jsch.SftpException) { if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw w(e) }
    } }
    override suspend fun mkdirRemote(path: String) { withContext(Dispatchers.IO) { use { c -> try { c.mkdir(path) } catch (e: com.jcraft.jsch.SftpException) { throw w(e) } } } }
    override suspend fun renameRemote(from: String, to: String, overwrite: Boolean) { withContext(Dispatchers.IO) { use { c -> try { c.rename(from, to) } catch (e: com.jcraft.jsch.SftpException) { throw w(e) } } } }
    override suspend fun removeRemote(path: String, isDir: Boolean) { withContext(Dispatchers.IO) { use { c -> try { if (isDir) c.rmdir(path) else c.rm(path) } catch (e: com.jcraft.jsch.SftpException) { throw w(e) } } } }
    override suspend fun putFile(src: File, dest: String, j: OpProgress?) { withContext(Dispatchers.IO) { use { c ->
        try { UpStream(src, j).use { c.put(it, dest, ChannelSftp.OVERWRITE) } } catch (e: com.jcraft.jsch.SftpException) { if (j?.cancelled == true) throw LocalOps.Cancelled(); throw w(e) }
    } } }
}

// ------------------------------------------------------------------ WebDAV (OkHttp, PROPFIND)

class DavFs(private val url: String, private val user: String, private val pass: String) : RemoteFs {
    private val u = java.net.URI(if (url.contains("://")) url else "http://$url")
    private val origin = "${u.scheme}://${u.host}" + (if (u.port > 0) ":${u.port}" else "")
    private val basePath = (u.rawPath ?: "").ifEmpty { "/" }.let { if (it.endsWith("/")) it else "$it/" }
    override val root get() = basePath
    override val label get() = "dav://${u.host}"

    /** Полный адрес файла и заголовки для плеера (потоковое воспроизведение по HTTP). */
    fun urlFor(path: String): String = origin + path.replace(" ", "%20")
    fun authHeaders(): Map<String, String> = if (user.isNotBlank()) mapOf("Authorization" to Credentials.basic(user, pass)) else emptyMap()

    private val client: OkHttpClient by lazy {
        // короткий срок жизни соединений в пуле (5 с): после простоя запрос идёт по новому соединению, а не по мёртвому; таймаут чтения 10 с
        val b = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).followRedirects(true)
            .connectionPool(okhttp3.ConnectionPool(2, 5, TimeUnit.SECONDS))
        if (isPrivateHost(u.host ?: "")) {
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), null) }
            b.sslSocketFactory(ctx.socketFactory, trustAll).hostnameVerifier(HostnameVerifier { _, _ -> true })
        }
        b.build()
    }

    override fun close() { runCatching { client.connectionPool.evictAll() } }
    override suspend fun ping(path: String) { withContext(Dispatchers.IO) { client.newCall(req(path).head().build()).execute().use { } } }

    private fun req(path: String) = Request.Builder().url(origin + path.replace(" ", "%20")).apply { if (user.isNotBlank()) header("Authorization", Credentials.basic(user, pass)) }

    override suspend fun list(path: String): List<FsEntry> = withContext(Dispatchers.IO) {
        val dirPath = if (path.endsWith("/")) path else "$path/"
        val body = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:displayname/></d:prop></d:propfind>"
        val r = req(dirPath).header("Depth", "1").method("PROPFIND", body.toRequestBody("application/xml".toMediaType())).build()
        client.newCall(r).execute().use { resp ->
            if (resp.code == 401) error("401: нужен логин и пароль")
            if (resp.code !in 200..299) error("HTTP ${resp.code}")
            val xml = resp.body?.string().orEmpty()
            parse(xml, dirPath)
        }
    }

    private fun parse(xml: String, dir: String): List<FsEntry> {
        val p = android.util.Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true); setInput(xml.reader()) }
        val out = ArrayList<FsEntry>()
        var href = ""; var isDir = false; var size = 0L; var inResp = false; var tag = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "response") { inResp = true; href = ""; isDir = false; size = 0L }
                    if (inResp && tag == "collection") isDir = true
                }
                XmlPullParser.TEXT -> if (inResp) { val t = p.text.trim(); if (t.isNotEmpty()) { if (tag == "href") href += t; if (tag == "getcontentlength") size = t.toLongOrNull() ?: 0L } }
                XmlPullParser.END_TAG -> if (p.name == "response") {
                    inResp = false
                    val full = runCatching { java.net.URI(href).rawPath }.getOrDefault(href).let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
                    val norm = full.trimEnd('/')
                    if (norm != dir.trimEnd('/') && norm.isNotEmpty()) out.add(FsEntry(norm.substringAfterLast('/'), if (isDir) "$norm/" else norm, isDir, size))
                }
            }
            ev = p.next()
        }
        return out.sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override suspend fun copyTo(path: String, dest: File) {
        withContext(Dispatchers.IO) {
            client.newCall(req(path).get().build()).execute().use { resp ->
                if (resp.code !in 200..299) error("HTTP ${resp.code}")
                resp.body!!.byteStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            }
        }
    }

    override suspend fun head(path: String, n: Int): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(req(path).get().build()).execute().use { resp -> if (resp.code !in 200..299) error("HTTP ${resp.code}"); cut(n, resp.body!!.byteStream()) }
    }

    override fun parent(path: String): String? {
        val t = path.trimEnd('/')
        if (t.length < basePath.trimEnd('/').length || t == basePath.trimEnd('/') || t.isEmpty()) return null
        return t.substringBeforeLast('/', "") + "/"
    }

    override suspend fun roots(): List<Pair<String, String>> = listOf(u.host to root)

    private fun enc(path: String): String = Request.Builder().url(origin + path.replace(" ", "%20")).build().url.toString()
    private fun chk(code: Int, what: String, ok: Set<Int> = setOf(200, 201, 204)) { if (code in ok) return; if (code == 401 || code == 403 || code == 405 && what == "MKCOL") throw NoWriteRights(); error("HTTP $code ($what)") }
    override suspend fun mkdirRemote(path: String) { withContext(Dispatchers.IO) { client.newCall(req(path.trimEnd('/') + "/").method("MKCOL", null).build()).execute().use { chk(it.code, "MKCOL") } } }
    override suspend fun renameRemote(from: String, to: String, overwrite: Boolean) { withContext(Dispatchers.IO) {
        client.newCall(req(from).header("Destination", enc(to)).header("Overwrite", if (overwrite) "T" else "F").method("MOVE", null).build()).execute().use { chk(it.code, "MOVE") }
    } }
    override suspend fun removeRemote(path: String, isDir: Boolean) { withContext(Dispatchers.IO) { client.newCall(req(path).delete().build()).execute().use { chk(it.code, "DELETE", setOf(200, 202, 204, 404)) } } }
    override suspend fun putFile(src: File, dest: String, j: OpProgress?) { withContext(Dispatchers.IO) {
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = src.length()
            override fun writeTo(sink: okio.BufferedSink) { UpStream(src, j).use { i -> val buf = ByteArray(128 * 1024); while (true) { val n = i.read(buf); if (n < 0) break; sink.write(buf, 0, n) } } }
        }
        val c2 = client.newBuilder().writeTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        try { c2.newCall(req(dest).put(body).build()).execute().use { chk(it.code, "PUT") } } catch (e: java.io.IOException) { if (j?.cancelled == true) throw LocalOps.Cancelled(); throw e }
    } }
}

// ------------------------------------------------------------------ FTP / FTPS (явный TLS)

class FtpFs(private val host: String, private val port: Int, private val user: String, private val pass: String, private val tls: Boolean) : RemoteFs {
    override val root get() = "/"
    override val label get() = (if (tls) "ftps://" else "ftp://") + host + if (port != 21) ":$port" else ""

    private class Ctl(var sock: Socket, var rd: BufferedReader, var wr: OutputStream)

    private fun sslFactory() = SSLContext.getInstance("TLS").apply { init(null, if (isPrivateHost(host)) arrayOf<TrustManager>(trustAll) else null, null) }.socketFactory

    private fun reply(c: Ctl): Pair<Int, String> {
        var line = c.rd.readLine() ?: error("FTP: соединение закрыто")
        val code = line.take(3).toIntOrNull() ?: error("FTP: $line")
        val sb = StringBuilder(line)
        if (line.length > 3 && line[3] == '-') {
            while (true) { line = c.rd.readLine() ?: break; sb.append('\n').append(line); if (line.startsWith("$code ")) break }
        }
        return code to sb.toString()
    }

    private fun cmd(c: Ctl, s: String): Pair<Int, String> { c.wr.write((s + "\r\n").toByteArray()); c.wr.flush(); return reply(c) }

    private fun open(): Ctl {
        val s = Socket(); s.connect(InetSocketAddress(host, port), 5000); s.soTimeout = 8000
        val c = Ctl(s, BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)), s.getOutputStream())
        reply(c)
        if (tls) {
            val (a, _) = cmd(c, "AUTH TLS"); if (a !in 200..299) error("FTPS не поддерживается сервером")
            val ss = sslFactory().createSocket(s, host, port, true) as javax.net.ssl.SSLSocket
            ss.startHandshake()
            c.sock = ss; c.rd = BufferedReader(InputStreamReader(ss.inputStream, Charsets.UTF_8)); c.wr = ss.outputStream
            cmd(c, "PBSZ 0"); cmd(c, "PROT P")
        }
        var (code, text) = cmd(c, "USER " + user.ifBlank { "anonymous" })
        if (code == 331) { val r = cmd(c, "PASS " + if (user.isBlank()) "anonymous@" else pass); code = r.first; text = r.second }
        if (code !in 200..299) error("FTP: вход не удался ($text)".take(200))
        cmd(c, "TYPE I")
        cmd(c, "OPTS UTF8 ON")
        return c
    }

    private fun dataSocket(c: Ctl): Socket {
        val (code, text) = cmd(c, "EPSV")
        val d = Socket()
        if (code == 229) {
            val prt = Regex("\\(\\|\\|\\|(\\d+)\\|\\)").find(text)?.groupValues?.get(1)?.toInt() ?: error("EPSV: $text")
            d.connect(InetSocketAddress(host, prt), 5000)
        } else {
            val (c2, t2) = cmd(c, "PASV"); if (c2 != 227) error("FTP: пассивный режим не удался")
            val n = Regex("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").find(t2)?.groupValues?.drop(1)?.map { it.toInt() } ?: error("PASV: $t2")
            d.connect(InetSocketAddress(host, n[4] * 256 + n[5]), 5000)
        }
        d.soTimeout = 8000
        return if (tls) (sslFactory().createSocket(d, host, d.port, true) as javax.net.ssl.SSLSocket).also { it.startHandshake() } else d
    }

    private fun <T> session(block: (Ctl) -> T): T {
        val c = open()
        try { return block(c) } finally { runCatching { cmd(c, "QUIT") }; runCatching { c.sock.close() } }
    }

    suspend fun connect() = withContext(Dispatchers.IO) { session { } }
    /** FTP без постоянного соединения (каждая операция открывает своё): проверка = TCP-подключение к порту сервера. */
    override suspend fun ping(path: String) { withContext(Dispatchers.IO) { Socket().use { it.connect(InetSocketAddress(host, port), 2000) } } }

    override suspend fun list(path: String): List<FsEntry> = withContext(Dispatchers.IO) {
        session { c ->
            val d = dataSocket(c)
            var (code, _) = cmd(c, "MLSD " + quote(path))
            var lines: List<String>
            var mlsd = true
            if (code !in 100..199) {
                d.close()
                val d2 = dataSocket(c); mlsd = false
                val (c2, t2) = cmd(c, "LIST " + quote(path)); if (c2 !in 100..199) error("FTP: $t2".take(200))
                lines = BufferedReader(InputStreamReader(d2.getInputStream(), Charsets.UTF_8)).readLines(); d2.close(); reply(c)
            } else {
                lines = BufferedReader(InputStreamReader(d.getInputStream(), Charsets.UTF_8)).readLines(); d.close(); reply(c)
            }
            val out = if (mlsd) lines.mapNotNull { mlsdEntry(it, path) } else lines.mapNotNull { listEntry(it, path) }
            out.sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })
        }
    }

    private fun quote(p: String) = if (p.isEmpty()) "/" else p
    private fun join(d: String, n: String) = if (d.endsWith("/")) d + n else "$d/$n"

    private fun mlsdEntry(line: String, dir: String): FsEntry? {
        val i = line.indexOf(' '); if (i < 0) return null
        val facts = line.substring(0, i).split(';').filter { it.contains('=') }.associate { it.substringBefore('=').lowercase() to it.substringAfter('=') }
        val name = line.substring(i + 1)
        val type = facts["type"]?.lowercase() ?: return null
        if (type == "cdir" || type == "pdir" || name == "." || name == "..") return null
        val isDir = type == "dir"
        return FsEntry(name, join(dir, name), isDir, if (isDir) 0L else facts["size"]?.toLongOrNull() ?: 0L)
    }

    private fun listEntry(line: String, dir: String): FsEntry? {
        if (line.isBlank() || line.startsWith("total")) return null
        // unix: drwxr-xr-x 2 u g 4096 Jan 1 12:00 name
        val m = Regex("^([\\-dl])[rwxsStT\\-]{9}\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+\\w{3}\\s+\\d+\\s+[\\d:]+\\s+(.+)$").find(line)
        if (m != null) {
            var name = m.groupValues[3]; if (name == "." || name == "..") return null
            if (m.groupValues[1] == "l") name = name.substringBefore(" -> ")
            val isDir = m.groupValues[1] == "d" || m.groupValues[1] == "l"
            return FsEntry(name, join(dir, name), isDir, if (isDir) 0L else m.groupValues[2].toLongOrNull() ?: 0L)
        }
        // DOS: 01-02-26 03:04PM <DIR> name
        val w = Regex("^\\S+\\s+\\S+\\s+(<DIR>|\\d+)\\s+(.+)$").find(line) ?: return null
        val isDir = w.groupValues[1] == "<DIR>"
        return FsEntry(w.groupValues[2], join(dir, w.groupValues[2]), isDir, if (isDir) 0L else w.groupValues[1].toLongOrNull() ?: 0L)
    }

    override suspend fun copyTo(path: String, dest: File) {
        withContext(Dispatchers.IO) {
            session { c ->
                val d = dataSocket(c)
                val (code, t) = cmd(c, "RETR $path"); if (code !in 100..199) error("FTP: $t".take(200))
                d.getInputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o) } }; d.close(); reply(c)
            }
        }
    }

    override suspend fun head(path: String, n: Int): ByteArray = withContext(Dispatchers.IO) {
        session { c ->
            val d = dataSocket(c)
            val (code, t) = cmd(c, "RETR $path"); if (code !in 100..199) error("FTP: $t".take(200))
            val b = cut(n, d.getInputStream()); runCatching { d.close() }; b
        }
    }

    override fun parent(path: String): String? { val t = path.trimEnd('/'); return if (t.isEmpty()) null else t.substringBeforeLast('/', "").ifEmpty { "/" } }
    override suspend fun roots(): List<Pair<String, String>> = listOf(host to root)

    private fun wfail(code: Int, text: String): Throwable {
        val l = text.lowercase()
        return if (code == 553 || code == 532 || (code == 550 || code == 450) && (l.contains("perm") || l.contains("denied") || l.contains("access") || l.contains("not allowed") || l.contains("read-only") || l.contains("forbid"))) NoWriteRights()
        else java.io.IOException(("FTP: " + text).take(200))
    }
    override suspend fun mkdirRemote(path: String) { withContext(Dispatchers.IO) { session { c -> val (code, t) = cmd(c, "MKD " + quote(path.trimEnd('/'))); if (code !in 200..299) throw wfail(code, t) } } }
    override suspend fun renameRemote(from: String, to: String, overwrite: Boolean) { withContext(Dispatchers.IO) { session { c ->
        val (a, ta) = cmd(c, "RNFR " + quote(from)); if (a != 350) throw wfail(a, ta)
        val (b, tb) = cmd(c, "RNTO " + quote(to)); if (b !in 200..299) throw wfail(b, tb)
    } } }
    override suspend fun removeRemote(path: String, isDir: Boolean) { withContext(Dispatchers.IO) { session { c -> val (code, t) = cmd(c, (if (isDir) "RMD " else "DELE ") + quote(path)); if (code !in 200..299) throw wfail(code, t) } } }
    override suspend fun putFile(src: File, dest: String, j: OpProgress?) { withContext(Dispatchers.IO) { session { c ->
        val d = dataSocket(c)
        val (code, t) = cmd(c, "STOR " + quote(dest)); if (code !in 100..199) { runCatching { d.close() }; throw wfail(code, t) }
        try { d.getOutputStream().use { o -> UpStream(src, j).use { i -> i.copyTo(o, 128 * 1024) } } } catch (e: Throwable) { runCatching { d.close() }; throw e }
        runCatching { d.close() }
        val (fin, tf) = reply(c); if (fin !in 200..299) throw wfail(fin, tf)
    } } }
}

// ------------------------------------------------------------------ избранные подключения (пароль в Android Keystore)

data class NetConn(val kind: String, val host: String, val port: Int, val user: String, val pass: String, val path: String = "", val smb: String = "", val name: String = "") {
    /** Имя облака без названия сервиса в скобках («drugoj00@gmail.com (Google Диск)» показывается как «drugoj00@gmail.com»): сервис виден по значку. В записи имя хранится как было. */
    val title get() = if (kind == "cloud") user.replace(Regex("^(.+?)\\s*\\([^()]{2,32}\\)\\s*$"), "$1") else if (name.isNotBlank()) name else (if (kind == "dav") "WebDAV " else "${kind.uppercase()} ") + (if (kind == "dav") runCatching { java.net.URI(host).let { it.host + if (it.port > 0) ":${it.port}" else "" } }.getOrNull() ?: host else host) + (if (user.isNotBlank()) " ($user)" else "") + (if (kind == "smb" && smb.isNotEmpty()) " · SMB$smb" else "")
    /** Сетевой источник с постоянной связью (проверка после простоя, самовосстановление, keepalive): см. [KeepFs]. */
    fun fs(): RemoteFs = if (kind == "cloud") raw() else KeepFs { raw() }
    fun raw(): RemoteFs = when (kind) {
        "sftp" -> SftpFs(host, if (port > 0) port else 22, user, pass)
        "ftp" -> FtpFs(host, if (port > 0) port else 21, user, pass, false)
        "ftps" -> FtpFs(host, if (port > 0) port else 21, user, pass, true)
        "dav" -> DavFs(host, user, pass)
        "cloud" -> CloudFs(host, user)
        "andr" -> AndroidFs(host, if (port > 0) port else 5555, user)   // Android-устройство по ADB (10.11.2026); в user — запасной адрес
        "pc" -> PcFs(host, if (port > 0) port else 47322, user.ifBlank { "Android" }, pass)   // Windows-ПК по Tailscale (10.11.2026)
        else -> SmbFs(host, user, pass, version = smb)
    }
}

object NetStore {
    private const val ALIAS = "adbphon_net"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("net_fav", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(android.security.keystore.KeyGenParameterSpec.Builder(ALIAS, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    fun encrypt(s: String): String {
        if (s.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }

    fun decrypt(s: String): String {
        if (s.isEmpty()) return ""
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12))) }
        return String(c.doFinal(b.copyOfRange(12, b.size)))
    }

    /** Список избранного. Разбор по одной записи: сбой одной записи не должен обнулять весь список (иначе следующая запись «затёрла» бы остальные). */
    fun list(ctx: Context): List<NetConn> {
        val a = runCatching { JSONArray(prefs(ctx).getString("list", "[]")) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            runCatching {
                val o = a.getJSONObject(i)
                NetConn(o.getString("k"), o.getString("h"), o.optInt("p", 0), o.optString("u"), runCatching { decrypt(o.optString("w")) }.getOrDefault(""), o.optString("d"), o.optString("v"), o.optString("n"))
            }.getOrNull()
        }
    }

    /** Единая запись списка: предыдущее значение сохраняется в list_prev (на случай ошибки), запись синхронная, каждое удаление или замена пишется в журнал. */
    private fun writeList(ctx: Context, before: List<NetConn>, l: List<NetConn>, why: String) {
        val a = JSONArray()
        l.forEach { x -> a.put(JSONObject().put("k", x.kind).put("h", x.host).put("p", x.port).put("u", x.user).put("w", encrypt(x.pass)).put("d", x.path).put("v", x.smb).put("n", x.name)) }
        val lost = before.filter { b -> l.none { it.kind == b.kind && it.host == b.host && it.user == b.user } }
        if (lost.isNotEmpty()) AppLog.i("ADBphon", "NetStore $why: из списка ушло ${lost.size}: " + lost.joinToString { it.kind + "|" + it.host + "|" + it.user })
        val prev = prefs(ctx).getString("list", null)
        prefs(ctx).edit().apply { if (prev != null) putString("list_prev", prev) }.putString("list", a.toString()).commit()
    }

    fun save(ctx: Context, c: NetConn) {
        val cur = list(ctx)
        writeList(ctx, cur, cur.filterNot { it.kind == c.kind && it.host == c.host && it.user == c.user } + c, "save")
    }

    /** Заменить весь список подключений (восстановление копии): пароли шифруются ключом Keystore этого устройства. */
    fun replaceAll(ctx: Context, list: List<NetConn>) {
        val a = JSONArray()
        list.forEach { x -> a.put(JSONObject().put("k", x.kind).put("h", x.host).put("p", x.port).put("u", x.user).put("w", encrypt(x.pass)).put("d", x.path).put("v", x.smb).put("n", x.name)) }
        if (!prefs(ctx).edit().putString("list", a.toString()).commit()) throw IllegalStateException("cannot write net_fav")
    }

    /** Изменить существующую запись на месте (порядок в избранном сохраняется); другая запись с новым ключом заменяется. */
    fun update(ctx: Context, old: NetConn, new: NetConn) {
        fun same(a: NetConn, b: NetConn) = a.kind == b.kind && a.host == b.host && a.user == b.user
        val cur = list(ctx)
        val idx = cur.indexOfFirst { same(it, old) }
        val l = if (idx < 0) cur.filterNot { same(it, new) } + new
                else cur.filterIndexed { i, x -> i == idx || !same(x, new) }.map { if (same(it, old)) new else it }
        writeList(ctx, cur, l, "update")
    }

    fun remove(ctx: Context, c: NetConn) {
        if (c.kind == "cloud") kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { Rclone.deleteRemote(ctx.applicationContext, c.host) }
        else kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { val rid = NetTrash.rid(c); Rclone.deleteRemote(ctx.applicationContext, rid); Rclone.deleteRemote(ctx.applicationContext, rid + "_b"); NetTrash.forget(rid) }
        val cur = list(ctx)
        writeList(ctx, cur, cur.filterNot { it.kind == c.kind && it.host == c.host && it.user == c.user }, "remove")
    }
}

// ------------------------------------------------------------------ поиск устройств в сети (порты по подсети + mDNS)

data class NetHost(val ip: String, val name: String, val ports: Set<Int>) {
    /** Лучший протокол по открытым портам. */
    val kinds: List<String> get() = buildList { if (445 in ports) add("smb"); if (22 in ports) add("sftp"); if (21 in ports) add("ftp"); if (ports.any { it in setOf(80, 443, 5005, 5006, 8080, 8081) }) add("dav") }
}

object NetDiscover {
    private val probe = listOf(445, 22, 21, 80, 443, 5005, 8080)

    private suspend fun open(ip: String, port: Int, ms: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching { Socket().use { it.connect(InetSocketAddress(ip, port), ms); true } }.getOrDefault(false)
    }

    /** Перебор адресов своей подсети /24 по портам файловых служб и имена по mDNS. Ничего не отправляет за пределы локальной сети. */
    suspend fun scan(ctx: Context, onFound: (List<NetHost>) -> Unit): List<NetHost> = coroutineScope {
        // подсети физических интерфейсов (Wi-Fi, Ethernet, точка доступа); VPN-туннели (tun, ppp) и виртуальные адаптеры пропускаются
        val mine = HashSet<String>()
        val prefixes = LinkedHashSet<String>()
        runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !Regex("^(tun|ppp|dummy|lo|ip6|v4-)").containsMatchIn(it.name) }
                .forEach { ni -> ni.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().filter { it.isSiteLocalAddress }.forEach { a -> mine.add(a.hostAddress!!); prefixes.add(a.hostAddress!!.substringBeforeLast('.') + ".") } }
        }
        if (prefixes.isEmpty()) return@coroutineScope emptyList()
        val names = async { mdns(ctx) }
        val found = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Int>>()
        val sem = Semaphore(96)
        prefixes.take(2).flatMap { pre -> (1..254).map { i -> pre + i } }.map { ip ->
            async {
                if (ip in mine) return@async
                for (p in probe) sem.withPermit { if (open(ip, p, 350)) { found.getOrPut(ip) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(p); onFound(snapshot(found, emptyMap())) } }
            }
        }.awaitAll()
        val n = withTimeoutOrNull(3000) { names.await() } ?: emptyMap()
        snapshot(found, n)
    }

    private fun snapshot(found: Map<String, Set<Int>>, names: Map<String, String>) =
        found.map { (ip, ports) -> NetHost(ip, names[ip].orEmpty(), ports.toSet()) }.sortedBy { h -> h.ip.substringAfterLast('.').toIntOrNull() ?: 0 }

    /** Имена по mDNS (_smb, _sftp-ssh, _ftp, _webdav): адрес → имя службы. */
    private suspend fun mdns(ctx: Context): Map<String, String> {
        val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptyMap()
        val out = java.util.concurrent.ConcurrentHashMap<String, String>()
        val listeners = ArrayList<NsdManager.DiscoveryListener>()
        for (t in listOf("_smb._tcp", "_sftp-ssh._tcp", "_ftp._tcp", "_webdav._tcp")) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(s: String?, e: Int) {}
                override fun onStopDiscoveryFailed(s: String?, e: Int) {}
                override fun onDiscoveryStarted(s: String?) {}
                override fun onDiscoveryStopped(s: String?) {}
                override fun onServiceLost(i: NsdServiceInfo?) {}
                @Suppress("DEPRECATION")
                override fun onServiceFound(i: NsdServiceInfo) {
                    runCatching { nsd.resolveService(i, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(s: NsdServiceInfo?, e: Int) {}
                        override fun onServiceResolved(s: NsdServiceInfo) { s.host?.hostAddress?.let { out[it] = s.serviceName } }
                    }) }
                }
            }
            runCatching { nsd.discoverServices(t, NsdManager.PROTOCOL_DNS_SD, l); listeners.add(l) }
        }
        delay(4000)
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        return out
    }
}
