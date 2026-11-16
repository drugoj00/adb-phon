package app.adbphon

import java.net.Socket

/** Стенд проверки потоковой отдачи ([StreamServer]) из [NetTestActivity]: сырой HTTP-клиент по адресу, который выдал сервер. Только для проверки. */
object StreamTest {
    /** Один запрос: статус, заголовки, число байт тела, SHA-1 тела, время до первого байта и всего. [limit]: читать не больше стольких байт тела, затем закрыть (как плеер при перемотке). */
    fun probe(url: String, method: String, range: String?, limit: Long = Long.MAX_VALUE): String {
        val u = java.net.URI(url)
        val t0 = System.currentTimeMillis()
        Socket("127.0.0.1", u.port).use { s ->
            s.soTimeout = 60_000
            val req = "$method ${u.rawPath} HTTP/1.1\r\nHost: 127.0.0.1\r\n" + (if (range != null) "Range: $range\r\n" else "") + "Connection: close\r\n\r\n"
            s.getOutputStream().write(req.toByteArray()); s.getOutputStream().flush()
            val i = s.getInputStream()
            val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) { val b = i.read(); if (b < 0) break; sb.append(b.toChar()) }
            val head = sb.toString().split("\r\n")
            fun h(n: String) = head.firstOrNull { it.startsWith("$n:", true) }?.substringAfter(':')?.trim() ?: "-"
            val md = java.security.MessageDigest.getInstance("SHA-1")
            var n = 0L; var first = -1L
            val buf = ByteArray(65536)
            if (method != "HEAD") while (n < limit) {
                val r = i.read(buf, 0, minOf(buf.size.toLong(), limit - n).toInt()); if (r < 0) break
                if (first < 0) first = System.currentTimeMillis() - t0
                md.update(buf, 0, r); n += r
            }
            return "status=${head[0]} cl=${h("Content-Length")} cr=${h("Content-Range")} ct=${h("Content-Type")} ar=${h("Accept-Ranges")} bytes=$n sha1=" +
                md.digest().joinToString("") { "%02x".format(it) } + " ttfbMs=$first totalMs=${System.currentTimeMillis() - t0}"
        }
    }
}
