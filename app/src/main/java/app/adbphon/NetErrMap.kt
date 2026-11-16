package app.adbphon

/**
 * 10.0 (wad72, 4PDA): чистое сопоставление сырого текста ошибки облака или сетевого источника (движок облаков, okhttp, java.net, TLS)
 * с понятным видом ошибки. Без Android: проверяется юнит-тестами (NetErrMapTest) на образцах настоящих текстов.
 * Тексты для людей по виду ошибки собирает [NetErrText] (строки cld_*).
 */
object NetErrMap {
    enum class Kind {
        /** Яндекс Диск по WebDAV на бесплатном тарифе: «WebDAV is not available for the free tariff», 402. */
        YANDEX_WEBDAV_FREE,
        /** 402 Payment Required у другого сервиса. */
        PAYMENT,
        /** Место в облаке кончилось (507, storageQuotaExceeded, insufficient storage). */
        SPACE,
        /** 429 и «слишком много запросов». */
        RATE,
        /** 401: логин, пароль или вход не приняты. */
        AUTH,
        /** 403: доступ запрещён. */
        FORBIDDEN,
        /** 404: адрес или папка не найдены. */
        NOT_FOUND,
        /** Ошибка защищённого соединения: сертификат, TLS. */
        TLS,
        /** Сервер не ответил вовремя. */
        TIMEOUT,
        /** Нет сети, имя сервера не найдено, соединение отклонено. */
        NO_NET,
        /** 5xx: сбой на стороне сервиса. */
        SERVER,
    }

    /** Код HTTP из текста: «402 Payment Required», «Error 403», «HTTP 404», «status code 429», «: 507». */
    fun httpCodes(raw: String): Set<Int> {
        val out = LinkedHashSet<Int>()
        Regex("\\b([45]\\d\\d)\\s+(?:[A-Z][A-Za-z'-]+)").findAll(raw).forEach { out += it.groupValues[1].toInt() }
        Regex("(?i)(?:http(?:/\\d(?:\\.\\d)?)?|error|status(?:\\s*code)?|code|response)[\\s:=#]*([45]\\d\\d)\\b").findAll(raw).forEach { out += it.groupValues[1].toInt() }
        Regex("(?m)(?:^\\s*|:\\s)([45]\\d\\d)\\s*:").findAll(raw).forEach { out += it.groupValues[1].toInt() }
        return out
    }

    fun classify(raw: String): Kind? {
        if (raw.isBlank()) return null
        val t = raw.lowercase()
        val codes = httpCodes(raw)
        fun has(vararg s: String) = s.any { it in t }
        return when {
            has("free tariff", "not available for the free", "webdav is not available") || ("webdav" in t && (402 in codes || "payment required" in t) && "yandex" in t) -> Kind.YANDEX_WEBDAV_FREE
            402 in codes || has("payment required") -> Kind.PAYMENT
            507 in codes || has("insufficient storage", "storagequotaexceeded", "storage quota", "quotalimitreached", "insufficient_space", "insufficient space", "not enough space", "no space left", "disk quota exceeded", "over quota", "out of space", "quota_exceeded_storage", "entity too large") -> Kind.SPACE
            429 in codes || has("too many requests", "rate limit", "ratelimitexceeded", "rate_limit_exceeded", "userratelimitexceeded", "too_many_requests") -> Kind.RATE
            401 in codes || has("unauthorized", "invalid_grant", "authentication failed", "bad credentials", "invalid credentials", "expired or revoked", "login failed") -> Kind.AUTH
            403 in codes || has(" forbidden", "forbidden:", "access denied", "access_denied") -> Kind.FORBIDDEN
            404 in codes || has("directory not found", "object not found", "file not found", "itemnotfound", "path_not_found", "path/not_found") -> Kind.NOT_FOUND
            has("tls handshake timeout", "handshake timed out") -> Kind.TIMEOUT
            has("x509", "certificate", "sslhandshakeexception", "sslpeerunverified", "sslexception", "ssl handshake", "tls: ", "tls handshake", "certpathvalidator", "trust anchor", "handshake failure", "unknown authority", "ssl_error") -> Kind.TLS
            has("no such host", "unknownhostexception", "unable to resolve host", "network is unreachable", "no route to host", "connection refused", "connectexception", "failed to connect", "enetunreach", "ehostunreach", "econnrefused", "network unreachable", "server misbehaving", "temporary failure in name resolution", "connection reset", "broken pipe", "software caused connection abort", "no address associated") -> Kind.NO_NET
            has("timeout", "timed out", "deadline exceeded", "sockettimeoutexception", "context deadline") -> Kind.TIMEOUT
            has("dial tcp", "lookup ") -> Kind.NO_NET
            codes.any { it in 500..599 } || has("internal server error", "bad gateway", "service unavailable", "gateway timeout") -> Kind.SERVER
            else -> null
        }
    }

    /** Ошибка исключения: текст с именами классов всей цепочки причин (UnknownHostException и т. п. часто без сообщения). */
    fun throwableText(e: Throwable): String {
        val sb = StringBuilder()
        var c: Throwable? = e
        var n = 0
        while (c != null && n < 6) { sb.append(c.javaClass.simpleName).append(": ").append(c.message.orEmpty()).append('\n'); c = c.cause; n++ }
        return sb.toString()
    }

    /** Ошибка относится к сети (java.net, javax.net.ssl, okhttp) или содержит код HTTP: только такие подменяются понятным текстом. */
    fun isNetThrowable(e: Throwable): Boolean {
        var c: Throwable? = e
        var n = 0
        while (c != null && n < 6) {
            val cn = c.javaClass.name
            if (cn.startsWith("java.net.") || cn.startsWith("javax.net.ssl.") || cn.startsWith("okhttp3.") || cn.startsWith("java.security.cert.")) return true
            if (Regex("^(HTTP [45]\\d\\d|[45]\\d\\d: )").containsMatchIn(c.message.orEmpty())) return true
            c = c.cause; n++
        }
        return false
    }

    /**
     * Сырой текст движка без служебного: дата, «ERROR :», «Failed to lsd with 2 errors: last error was:», имя движка.
     * Нужен там, где вид ошибки не распознан: показывается суть, а не английский заголовок команды.
     */
    fun cleanRaw(raw: String): String {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("\"@type") && !it.startsWith("\"domain") && !it.startsWith("{") && !it.startsWith("}") && !it.startsWith("]") }
        var s = lines.lastOrNull { Regex("(?i)error|failed").containsMatchIn(it) } ?: lines.lastOrNull().orEmpty()
        s = s.replace(Regex("^\\d{4}/\\d\\d/\\d\\d \\d\\d:\\d\\d:\\d\\d\\s*"), "")
            .replace(Regex("^(ERROR|NOTICE|CRITICAL)\\s*:\\s*"), "")
            .replace(Regex("(?i)^.*?:\\s*Failed to [a-z]+(?: with \\d+ errors?)?:\\s*(?:last error was:\\s*)?"), "")
            .replace(Regex("(?i)^Failed to [a-z]+(?: with \\d+ errors?)?:\\s*(?:last error was:\\s*)?"), "")
            .replace(Regex("(?i)rclone"), "ADB-phon")
            .trim()
        return s.take(160)
    }
}

/** Образцы настоящих текстов ошибок (стенд NetTestActivity --es fakeerr yadav и юнит-тесты). */
object NetErrSamples {
    /** wad72, 4PDA, после 9.3: Яндекс Диск с паролем приложения на бесплатном тарифе. */
    const val YANDEX_402 = "2026/11/14 21:03:11 ERROR : : error listing: couldn't list files: WebDAV is not available for the free tariff.: 402 Payment Required\n" +
        "2026/11/14 21:03:11 Failed to lsd with 2 errors: last error was: couldn't list files: WebDAV is not available for the free tariff.: 402 Payment Required"
}
