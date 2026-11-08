package app.adbphon

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Флаг общего пробного ключа Google Диска. У общего ключа программы лимит Google на число пользователей, а число пользователей приложению не отдаётся,
 * поэтому владелица заранее закрывает «набор» маленьким файлом на сайте программы: `{"open":true}` / `{"open":false}`.
 * Читается только в момент, когда пользователь выбирает вход в Google Диск ОБЩИМ ключом (запрос GET без каких-либо идентификаторов; таймаут 4 с;
 * результат хранится 6 часов). Файл недоступен или не разобрался: считается «открыто», чтобы не ломать вход. «Закрыто» действует только на НОВЫЕ входы общим
 * ключом: уже вошедшие и их токены не затрагиваются. Переключателя в настройках нет.
 */
object SharedKey {
    const val URL = "https://drugoj00.github.io/adb-phon/shared-key.json"
    private const val TTL_MS = 6 * 3600_000L
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE)

    /** Отладка: другой адрес файла (локальный сервер); пусто: обычный. */
    fun setDebugUrl(ctx: Context, url: String?) { prefs(ctx).edit().apply { if (url.isNullOrBlank()) remove("sharedkey_url") else putString("sharedkey_url", url) }.apply() }
    private fun url(ctx: Context) = prefs(ctx).getString("sharedkey_url", null)?.takeIf { it.isNotBlank() } ?: URL

    fun resetCache(ctx: Context) { prefs(ctx).edit().remove("sharedkey_open").remove("sharedkey_ts").apply() }
    fun cachedState(ctx: Context): String { val p = prefs(ctx); return if (p.contains("sharedkey_ts")) "open=${p.getBoolean("sharedkey_open", true)} ageMin=${(System.currentTimeMillis() - p.getLong("sharedkey_ts", 0)) / 60000}" else "none" }

    /** Открыт ли набор на общий ключ. Блокирующее чтение выполняется в фоне; любой сбой даёт true. */
    suspend fun isOpen(ctx: Context): Boolean {
        val p = prefs(ctx)
        val ts = p.getLong("sharedkey_ts", 0L)
        if (ts > 0 && System.currentTimeMillis() - ts < TTL_MS && ts <= System.currentTimeMillis()) return p.getBoolean("sharedkey_open", true)
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(url(ctx)).openConnection() as HttpURLConnection
                c.connectTimeout = 4000; c.readTimeout = 4000; c.useCaches = false
                try {
                    if (c.responseCode != 200) null
                    else JSONObject(c.inputStream.bufferedReader().use { it.readText().take(2000) }).optBoolean("open", true)
                } finally { c.disconnect() }
            }.getOrNull()
        }
        // успешный ответ запоминается на 6 часов; сбой не запоминается (при следующем входе попробуем снова), а вход не блокируется
        if (r != null) p.edit().putBoolean("sharedkey_open", r).putLong("sharedkey_ts", System.currentTimeMillis()).apply()
        return r ?: true
    }
}
