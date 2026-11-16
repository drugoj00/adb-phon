package app.adbphon

import app.adbphon.NetErrMap.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Сопоставление сырых текстов ошибок облаков (движок, okhttp, java.net) с понятным видом ошибки (10.0, wad72). */
class NetErrMapTest {
    private fun k(s: String) = NetErrMap.classify(s)

    @Test fun yandexWebdavFreeTariff() {
        assertEquals(Kind.YANDEX_WEBDAV_FREE, k(NetErrSamples.YANDEX_402))
        assertEquals(Kind.YANDEX_WEBDAV_FREE, k("Failed to lsd with 2 errors: last error was: WebDAV is not available for the free tariff.: 402 Payment Required"))
        assertEquals("couldn't list files: WebDAV is not available for the free tariff.: 402 Payment Required", NetErrMap.cleanRaw(NetErrSamples.YANDEX_402))
    }

    @Test fun payment() {
        assertEquals(Kind.PAYMENT, k("couldn't list files: 402 Payment Required"))
        assertEquals(Kind.PAYMENT, k("IllegalStateException: HTTP 402"))
    }

    @Test fun space() {
        assertEquals(Kind.SPACE, k("googleapi: Error 403: The user's Drive storage quota has been exceeded., storageQuotaExceeded"))
        assertEquals(Kind.SPACE, k("Failed to copy: 507 Insufficient Storage"))
        assertEquals(Kind.SPACE, k("write /sdcard/x: no space left on device"))
    }

    @Test fun rate() {
        assertEquals(Kind.RATE, k("too_many_requests/..."))
        assertEquals(Kind.RATE, k("couldn't list: 429 Too Many Requests"))
        assertEquals(Kind.RATE, k(CloudErr.FAKE_BODY))
    }

    @Test fun authAndForbidden() {
        assertEquals(Kind.AUTH, k("couldn't list files: 401 Unauthorized"))
        assertEquals(Kind.AUTH, k("IllegalStateException: 401: нужен логин и пароль"))
        assertEquals(Kind.FORBIDDEN, k("couldn't list files: 403 Forbidden"))
    }

    @Test fun notFound() {
        assertEquals(Kind.NOT_FOUND, k("2026/11/14 21:03:11 Failed to lsd: directory not found"))
        assertEquals(Kind.NOT_FOUND, k("PROPFIND: 404 Not Found"))
        assertEquals("directory not found", NetErrMap.cleanRaw("2026/11/14 21:03:11 Failed to lsd: directory not found"))
    }

    @Test fun tls() {
        assertEquals(Kind.TLS, k("Get \"https://dav.example.com/\": x509: certificate signed by unknown authority"))
        assertEquals(Kind.TLS, k("SSLHandshakeException: java.security.cert.CertPathValidatorException: Trust anchor for certification path not found."))
        assertEquals(Kind.TIMEOUT, k("Get \"https://x/\": net/http: TLS handshake timeout"))
    }

    @Test fun network() {
        assertEquals(Kind.NO_NET, k("dial tcp: lookup webdav.example.com: no such host"))
        assertEquals(Kind.NO_NET, k("UnknownHostException: Unable to resolve host \"webdav.yandex.ru\": No address associated with hostname"))
        assertEquals(Kind.NO_NET, k("dial tcp 192.168.1.5:8080: connect: connection refused"))
        assertEquals(Kind.NO_NET, k("dial tcp: lookup x on 8.8.8.8:53: server misbehaving"))
        assertEquals(Kind.TIMEOUT, k("dial tcp 1.2.3.4:443: i/o timeout"))
        assertEquals(Kind.TIMEOUT, k("SocketTimeoutException: timeout"))
    }

    @Test fun server() {
        assertEquals(Kind.SERVER, k("couldn't list: 502 Bad Gateway"))
        assertEquals(Kind.SERVER, k("IllegalStateException: HTTP 503"))
    }

    @Test fun unknownStaysNull() {
        assertNull(k(""))
        assertNull(k("object is not a directory"))
        assertNull(k("copied 404 files"))
    }

    @Test fun httpCodes() {
        assertEquals(setOf(403), NetErrMap.httpCodes("Error 403: something"))
        assertEquals(setOf(402), NetErrMap.httpCodes("tariff.: 402 Payment Required"))
        assertEquals(setOf(404), NetErrMap.httpCodes("HTTP/1.1 404"))
        assertTrue(NetErrMap.httpCodes("copied 404 files").isEmpty())
    }

    @Test fun netThrowable() {
        assertTrue(NetErrMap.isNetThrowable(java.net.UnknownHostException("x")))
        assertTrue(NetErrMap.isNetThrowable(IllegalStateException("HTTP 404")))
        assertTrue(NetErrMap.isNetThrowable(RuntimeException("wrap", javax.net.ssl.SSLHandshakeException("bad cert"))))
        assertFalse(NetErrMap.isNetThrowable(IllegalStateException("Permission denied")))
        assertEquals(Kind.NO_NET, k(NetErrMap.throwableText(java.net.UnknownHostException())))
    }
}
