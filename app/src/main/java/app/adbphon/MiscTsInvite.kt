package app.adbphon

/**
 * Отправка приглашения в сеть Tailscale (10.0): чистая логика без Android, проверяется юнит-тестами (MiscTsInviteTest).
 * API: POST https://api.tailscale.com/api/v2/tailnet/-/user-invites, тело [{"email":"…","role":"member"}],
 * авторизация «Authorization: Bearer <токен API>». Токен должен принадлежать пользователю (OAuth-клиент приглашать не может:
 * приглашению нужен приглашающий). Ответ 200: массив UserInvite (id, role, tailnetId, inviterId, email, lastEmailSentAt, inviteUrl).
 */
object TsInviteApi {
    const val URL = "https://api.tailscale.com/api/v2/tailnet/-/user-invites"
    /** Страница, где создаётся токен API (в справке окна «Нужен токен API Tailscale»). */
    const val KEYS_PAGE = "https://login.tailscale.com/admin/settings/keys"

    /** Транспорт: отправить POST и вернуть (код ответа, тело). Исключение = сеть недоступна. В тестах подменяется. */
    fun interface Http { fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String?> }

    sealed class Result {
        /** Приглашение создано; [emailSent]: Tailscale сообщил, что письмо ушло (есть lastEmailSentAt). */
        data class Sent(val email: String, val inviteUrl: String?, val emailSent: Boolean) : Result()
        /** [kind]: email, token, auth, forbidden, notfound, bad, exists, rate, server, net, other; [http]: код ответа (0 без ответа). */
        data class Error(val kind: String, val http: Int, val message: String?) : Result()
    }

    private val EMAIL = Regex("^[A-Za-z0-9._%+'\\-]+@[A-Za-z0-9](?:[A-Za-z0-9\\-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9\\-]*[A-Za-z0-9])?)+$")

    fun normEmail(s: String) = s.trim()
    fun validEmail(s: String) = normEmail(s).let { it.length in 6..254 && EMAIL.matches(it) }

    /** Токен: без пробелов и непустой (формат tskey-api-… не требуем строго, чтобы не отвергать будущие виды ключей). */
    fun normToken(s: String) = s.trim()
    fun validToken(s: String) = normToken(s).let { it.length >= 10 && it.none { c -> c.isWhitespace() || c.code < 32 } }

    fun jsonEscape(s: String): String = buildString {
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    fun body(email: String, role: String = "member"): String = "[{\"email\":\"" + jsonEscape(normEmail(email)) + "\",\"role\":\"" + jsonEscape(role) + "\"}]"

    fun headers(token: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer " + normToken(token),
        "Content-Type" to "application/json",
        "Accept" to "application/json",
    )

    /** Значение строкового поля JSON (первое вхождение), с разбором экранирования; null, если поля нет. */
    fun jsonField(json: String?, name: String): String? {
        if (json == null) return null
        val m = Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json) ?: return null
        val raw = m.groupValues[1]
        val sb = StringBuilder(); var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val n = raw[i + 1]) {
                    'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                    'u' -> { if (i + 5 < raw.length) { raw.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()) }; i += 4 } }
                    else -> sb.append(n)
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    /** Разбор ответа API. Текст ошибки берётся из поля message (обрезается до 200 знаков), токен в него не попадает. */
    fun parse(http: Int, body: String?, email: String, token: String? = null): Result {
        val msg = jsonField(body, "message")?.let { m -> if (token != null && token.isNotBlank()) m.replace(normToken(token), "***") else m }?.take(200)
        return when {
            http in 200..299 -> {
                val t = body?.trim().orEmpty()
                if (t.startsWith("[") || t.startsWith("{") || t.isEmpty()) {
                    val sentAt = jsonField(body, "lastEmailSentAt")
                    Result.Sent(jsonField(body, "email") ?: normEmail(email), jsonField(body, "inviteUrl"), !sentAt.isNullOrBlank() && !sentAt.startsWith("0001-"))
                } else Result.Error("other", http, t.take(200))
            }
            http == 401 -> Result.Error("auth", http, msg)
            http == 403 -> Result.Error("forbidden", http, msg)
            http == 404 -> Result.Error("notfound", http, msg)
            http == 409 -> Result.Error("exists", http, msg)
            http == 400 || http == 422 -> Result.Error(if (msg?.contains("already", true) == true) "exists" else "bad", http, msg)
            http == 429 -> Result.Error("rate", http, msg)
            http >= 500 -> Result.Error("server", http, msg)
            else -> Result.Error("other", http, msg)
        }
    }

    /** Проверка ввода и отправка. Сеть только через [http]. */
    fun send(http: Http, token: String?, email: String): Result {
        if (!validEmail(email)) return Result.Error("email", 0, null)
        if (token == null || !validToken(token)) return Result.Error("token", 0, null)
        val (code, resp) = try { http.post(URL, headers(token), body(email)) } catch (e: Exception) { return Result.Error("net", 0, e.javaClass.simpleName) }
        return parse(code, resp, email, token)
    }
}
