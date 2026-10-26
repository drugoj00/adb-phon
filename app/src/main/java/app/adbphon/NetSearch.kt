package app.adbphon

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Результат поиска приложения в сети. */
data class NetApp(val name: String, val summary: String, val source: String, val id: String)

/**
 * Поиск и скачивание APK из открытых каталогов: F-Droid (свободные приложения) и релизы на GitHub.
 * Плюс скачивание по прямой ссылке. Неофициальные зеркала платных программ не используются.
 */
object NetSearch {
    private const val UA = "TVApps/1.0 (Android)"

    private fun get(url: String, accept: String = "application/json"): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 25_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", accept)
        return try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    suspend fun search(q: String, source: String): List<NetApp> = withContext(Dispatchers.IO) {
        val enc = URLEncoder.encode(q.trim(), "UTF-8")
        when (source) {
            "github" -> {
                val items = JSONObject(get("https://api.github.com/search/repositories?q=$enc+topic:android&per_page=15")).optJSONArray("items") ?: JSONArray()
                (0 until items.length()).map { items.getJSONObject(it) }.map {
                    NetApp(it.optString("name"), it.optString("description", "").takeIf { d -> d != "null" }.orEmpty(), "github", it.getString("full_name"))
                }
            }
            else -> {
                val apps = JSONObject(get("https://search.f-droid.org/api/search_apps?q=$enc&lang=ru")).optJSONArray("apps") ?: JSONArray()
                (0 until apps.length()).map { apps.getJSONObject(it) }.mapNotNull {
                    val pkg = it.optString("url").trimEnd('/').substringAfterLast('/')
                    if (pkg.isBlank()) null else NetApp(it.optString("name"), it.optString("summary"), "fdroid", pkg)
                }
            }
        }
    }

    /** Адреса APK для выбранного приложения: сначала самый подходящий, дальше запасные (для других процессоров). */
    suspend fun apkUrls(app: NetApp): List<String> = withContext(Dispatchers.IO) {
        if (app.source == "github") {
            val assets = JSONObject(get("https://api.github.com/repos/${app.id}/releases/latest")).optJSONArray("assets") ?: JSONArray()
            val urls = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.optString("name").lowercase().endsWith(".apk") }
                .map { it.getString("browser_download_url") }
            val abis = Build.SUPPORTED_ABIS.map { it.lowercase() }
            // сначала файлы под процессор устройства или универсальные, потом остальные
            urls.sortedBy { u ->
                val n = u.lowercase()
                when {
                    abis.any { n.contains(it) } -> 0
                    n.contains("universal") -> 1
                    listOf("arm64", "armeabi", "x86").none { n.contains(it) } -> 2
                    else -> 3
                }
            }
        } else {
            val j = JSONObject(get("https://f-droid.org/api/v1/packages/${app.id}"))
            val suggested = j.optInt("suggestedVersionCode")
            val pk = j.optJSONArray("packages") ?: JSONArray()
            val all = (0 until pk.length()).map { pk.getJSONObject(it) }
            val name = all.firstOrNull { it.optInt("versionCode") == suggested }?.optString("versionName")
            // одна версия может выпускаться несколькими файлами под разные процессоры: пробуем по очереди
            val codes = all.filter { it.optString("versionName") == name }.map { it.optInt("versionCode") }.sortedByDescending { it == suggested }.take(5)
            codes.map { "https://f-droid.org/repo/${app.id}_$it.apk" }
        }
    }

    /** Все версии приложения в каталоге F-Droid (новые первыми). */
    suspend fun fdroidVersions(pkg: String): List<FdVersion> = withContext(Dispatchers.IO) {
        val text = try { get("https://f-droid.org/api/v1/packages/$pkg") } catch (e: Exception) {
            if (e.message?.contains("404") == true) return@withContext emptyList() else throw e
        }
        val pk = JSONObject(text).optJSONArray("packages") ?: JSONArray()
        (0 until pk.length()).map { pk.getJSONObject(it) }
            .map { FdVersion(it.optString("versionName"), it.optLong("versionCode"), "https://f-droid.org/repo/${pkg}_${it.optLong("versionCode")}.apk") }
            .distinctBy { it.code }.sortedByDescending { it.code }.take(40)
    }

    /** Скачивает файл в папку временных файлов; onProgress получает мегабайты. */
    suspend fun download(ctx: Context, url: String, onProgress: (Long) -> Unit): ApkFile? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(ctx.cacheDir, "batch").apply { mkdirs() }
            val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "app.apk" }.let { if (it.lowercase().endsWith(".apk")) it else "$it.apk" }
            val out = File(dir, "net_" + name.replace(Regex("[^\\w.\\-]"), "_"))
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 40_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            c.inputStream.use { i -> out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    total += n
                    onProgress(total / 1024 / 1024)
                }
            } }
            c.disconnect()
            ApkFile(name, Uri.fromFile(out), out.length(), out)
        }.getOrNull()
    }
}
