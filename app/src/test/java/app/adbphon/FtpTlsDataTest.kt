package app.adbphon

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Канал данных FTPS через SSLEngine: сервер должен получить close_notify (чтение заканчивается -1, а не обрывом). */
class FtpTlsDataTest {
    private val pass = "testtest".toCharArray()
    private val serverCtx: SSLContext by lazy {
        val ks = KeyStore.getInstance("PKCS12"); javaClass.classLoader!!.getResourceAsStream("ftps_test.p12").use { ks.load(it, pass) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass) }
        SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    private fun clientCtx() = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), null) }

    private fun data(n: Int) = ByteArray(n) { (it * 31 + 7).toByte() }

    private fun upload(proto: String, n: Int) {
        val pool = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { ss ->
            val got = pool.submit<ByteArray> {
                val raw = ss.accept()
                val s = serverCtx.socketFactory.createSocket(raw, null, raw.port, true) as SSLSocket
                s.useClientMode = false; s.enabledProtocols = arrayOf(proto); s.soTimeout = 5000
                val b = s.inputStream.readBytes()    // строгий конец: -1 только после close_notify
                s.close(); b
            }
            val raw = Socket(); raw.connect(InetSocketAddress("127.0.0.1", ss.localPort), 3000); raw.soTimeout = 5000
            val d = FtpTlsData(raw, clientCtx(), "127.0.0.1", ss.localPort).handshake()
            val src = data(n)
            d.getOutputStream().write(src); d.getOutputStream().flush()
            d.finish(); d.close()
            assertArrayEquals(src, got.get(10, TimeUnit.SECONDS))
        }
        pool.shutdownNow()
    }

    private fun download(proto: String, n: Int) {
        val pool = Executors.newSingleThreadExecutor()
        val src = data(n)
        ServerSocket(0).use { ss ->
            val done = pool.submit<Int> {
                val raw = ss.accept()
                val s = serverCtx.socketFactory.createSocket(raw, null, raw.port, true) as SSLSocket
                s.useClientMode = false; s.enabledProtocols = arrayOf(proto); s.soTimeout = 5000
                s.outputStream.write(src); s.outputStream.flush()
                s.shutdownOutput()                      // close_notify сервера
                val r = s.inputStream.read()            // close_notify клиента: -1
                s.close(); r
            }
            val raw = Socket(); raw.connect(InetSocketAddress("127.0.0.1", ss.localPort), 3000); raw.soTimeout = 5000
            val d = FtpTlsData(raw, clientCtx(), "127.0.0.1", ss.localPort).handshake()
            val b = d.getInputStream().readBytes(); d.close()
            assertArrayEquals(src, b)
            assertEquals(-1, done.get(10, TimeUnit.SECONDS))
        }
        pool.shutdownNow()
    }

    @Test fun uploadTls12() { upload("TLSv1.2", 100); upload("TLSv1.2", 3_000_000) }
    @Test fun uploadTls13() { upload("TLSv1.3", 100); upload("TLSv1.3", 3_000_000) }
    @Test fun uploadEmpty() { upload("TLSv1.3", 0) }
    @Test fun downloadTls12() { download("TLSv1.2", 3_000_000) }
    @Test fun downloadTls13() { download("TLSv1.3", 70_000) }
    /** Проверка самого стенда: обрыв TCP без close_notify сервер видит как ошибку, а не как конец данных. */
    @Test fun abruptCloseIsDetected() {
        val pool = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { ss ->
            val got = pool.submit<String> {
                val raw = ss.accept()
                val s = serverCtx.socketFactory.createSocket(raw, null, raw.port, true) as SSLSocket
                s.useClientMode = false; s.soTimeout = 5000
                try { s.inputStream.readBytes(); "eof" } catch (e: java.io.IOException) { "error" } finally { s.close() }
            }
            val raw = Socket(); raw.connect(InetSocketAddress("127.0.0.1", ss.localPort), 3000); raw.soTimeout = 5000
            val d = FtpTlsData(raw, clientCtx(), "127.0.0.1", ss.localPort).handshake()
            d.getOutputStream().write(data(1000)); raw.close()
            assertEquals("error", got.get(10, TimeUnit.SECONDS))
        }
        pool.shutdownNow()
    }

    @Test fun finishIsQuickWhenServerAnswers() {
        val t = System.currentTimeMillis(); upload("TLSv1.3", 1000)
        assertTrue("finish ждал тайм-аут", System.currentTimeMillis() - t < 2500)
    }
}
