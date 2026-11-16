package app.adbphon

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

/** Канал данных FTP: обычный сокет или TLS (PROT P). [finish] завершает передачу так, чтобы сервер её принял (для TLS: close_notify). */
interface FtpData : Closeable {
    fun getInputStream(): InputStream
    fun getOutputStream(): OutputStream
    /** Конец загрузки (STOR): сообщить серверу о завершении и дождаться его ответа (не дольше [waitMs]). */
    fun finish(waitMs: Int = 3000) {}
}

class FtpPlainData(private val s: Socket) : FtpData {
    override fun getInputStream(): InputStream = s.getInputStream()
    override fun getOutputStream(): OutputStream = s.getOutputStream()
    override fun close() { runCatching { s.close() } }
}

/**
 * TLS канала данных через [SSLEngine] вручную (16.11.2026). Платформенный SSLSocket (Conscrypt) поверх уже подключённого сокета
 * при close()/shutdownOutput() не доставляет серверу close_notify, и строгие серверы (pyftpdlib и др.) не подтверждают загрузку (нет 226).
 * Здесь close_notify формируется и отправляется явно. Движок создаётся из того же [SSLContext] с узлом и портом управляющего канала:
 * так продолжается его TLS-сессия (серверы с require_ssl_reuse).
 */
class FtpTlsData(private val raw: Socket, ctx: SSLContext, host: String, port: Int) : FtpData {
    private val eng: SSLEngine = ctx.createSSLEngine(host, port).apply { useClientMode = true }
    private val rin = raw.getInputStream()
    private val rout = raw.getOutputStream()
    private var netIn: ByteBuffer = ByteBuffer.allocate(eng.session.packetBufferSize).apply { flip() }   // режим чтения
    private var appIn: ByteBuffer = ByteBuffer.allocate(eng.session.applicationBufferSize).apply { flip() }
    private var netOut: ByteBuffer = ByteBuffer.allocate(eng.session.packetBufferSize)
    private var eof = false          // сервер закрыл TLS (close_notify) или TCP
    private var closed = false
    private val empty = ByteBuffer.allocate(0)

    fun handshake(): FtpTlsData {
        eng.beginHandshake()
        drive()
        return this
    }

    /** Доводит рукопожатие (и служебный обмен) до конца. */
    private fun drive() {
        while (true) {
            when (eng.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> runTasks()
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrapSend(empty)
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, SSLEngineResult.HandshakeStatus.FINISHED, null -> return
                else -> { // NEED_UNWRAP (и NEED_UNWRAP_AGAIN новых платформ)
                    val r = unwrapOnce()
                    if (r == null) throw SSLException("TLS данных: соединение закрыто во время рукопожатия")
                    if (r.status == SSLEngineResult.Status.CLOSED) throw SSLException("TLS данных: сервер закрыл соединение")
                }
            }
        }
    }

    private fun runTasks() { while (true) { (eng.delegatedTask ?: break).run() } }

    /** Шифрует src (или служебные записи) и отправляет всё в сокет. */
    private fun wrapSend(src: ByteBuffer): SSLEngineResult {
        while (true) {
            netOut.clear()
            val r = eng.wrap(src, netOut)
            when (r.status) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> { netOut = ByteBuffer.allocate(maxOf(netOut.capacity() * 2, eng.session.packetBufferSize)); continue }
                else -> {}
            }
            netOut.flip()
            if (netOut.hasRemaining()) { rout.write(netOut.array(), netOut.arrayOffset() + netOut.position(), netOut.remaining()); rout.flush() }
            if (r.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) runTasks()
            return r
        }
    }

    /** Одна попытка расшифровать запись (дочитывая из сокета при нехватке). null = TCP закрыт без данных. */
    private fun unwrapOnce(): SSLEngineResult? {
        while (true) {
            appIn.compact()
            val r = try { eng.unwrap(netIn, appIn) } finally { appIn.flip() }
            when (r.status) {
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    if (netIn.capacity() < eng.session.packetBufferSize) { val n = ByteBuffer.allocate(eng.session.packetBufferSize); n.put(netIn); n.flip(); netIn = n }
                    if (!fill()) return null
                }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    val n = ByteBuffer.allocate(appIn.remaining() + eng.session.applicationBufferSize); n.put(appIn); n.flip(); appIn = n
                }
                else -> { if (r.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) runTasks(); return r }
            }
        }
    }

    /** Дочитывает зашифрованные байты из сокета в netIn. false = конец потока. */
    private fun fill(): Boolean {
        netIn.compact()
        try {
            if (!netIn.hasRemaining()) { val n = ByteBuffer.allocate(netIn.capacity() * 2); netIn.flip(); n.put(netIn); netIn = n }
            val k = rin.read(netIn.array(), netIn.arrayOffset() + netIn.position(), netIn.remaining())
            if (k < 0) return false
            netIn.position(netIn.position() + k)
            return true
        } finally { netIn.flip() }
    }

    private fun readApp(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (!appIn.hasRemaining()) {
            if (eof) return -1
            val r = unwrapOnce()
            if (r == null) { eof = true; return -1 }
            if (r.status == SSLEngineResult.Status.CLOSED) { eof = true; if (eng.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) runCatching { wrapSend(empty) }; if (!appIn.hasRemaining()) return -1 }
            // служебные записи (билеты сессии TLS 1.3, обновление ключей): ответить, если движок просит
            if (!eof && eng.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) wrapSend(empty)
        }
        val n = minOf(len, appIn.remaining()); appIn.get(b, off, n); return n
    }

    private val ins = object : InputStream() {
        override fun read(): Int { val b = ByteArray(1); while (true) { val n = readApp(b, 0, 1); if (n < 0) return -1; if (n == 1) return b[0].toInt() and 0xFF } }
        override fun read(b: ByteArray, off: Int, len: Int): Int = readApp(b, off, len)
        override fun available(): Int = appIn.remaining()
        override fun close() { this@FtpTlsData.close() }
    }

    private val outs = object : OutputStream() {
        override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("TLS данных: канал закрыт")
            val src = ByteBuffer.wrap(b, off, len)
            while (src.hasRemaining()) {
                val r = wrapSend(src)
                if (r.status == SSLEngineResult.Status.CLOSED) throw IOException("TLS данных: сервер закрыл соединение")
            }
        }
        override fun flush() { rout.flush() }
        override fun close() { this@FtpTlsData.close() }
    }

    override fun getInputStream(): InputStream = ins
    override fun getOutputStream(): OutputStream = outs

    /** Отправить close_notify (один раз). */
    private fun sendCloseNotify() {
        if (eng.isOutboundDone) return
        eng.closeOutbound()
        var guard = 0
        while (!eng.isOutboundDone && guard++ < 8) wrapSend(empty)
    }

    override fun finish(waitMs: Int) {
        if (closed) return
        sendCloseNotify()
        // ждём close_notify сервера или закрытия TCP: сервер к этому моменту дописал файл
        runCatching {
            raw.soTimeout = waitMs
            val end = System.currentTimeMillis() + waitMs
            val b = ByteArray(8192)
            while (System.currentTimeMillis() < end) { if (readApp(b, 0, b.size) < 0) break }
        }   // тайм-аут или обрыв здесь не ошибка: итог загрузки скажет ответ сервера на управляющем канале
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { sendCloseNotify() }
        runCatching { raw.close() }
    }
}
