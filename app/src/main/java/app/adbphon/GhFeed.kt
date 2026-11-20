package app.adbphon

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 16.0 код 77: запасной путь проверки выпусков GitHub без API (api.github.com даёт 60 запросов в час на адрес; при исчерпании лимита, 403/429, сбое сети или
 * непонятном ответе проверка обновлений раньше просто не удавалась). Читаются обычные страницы сайта, лимит API они не тратят: лента
 * github.com/<репозиторий>/releases.atom (тег, название, дата, описание) и github.com/<репозиторий>/releases/expanded_assets/<тег> (имена и ссылки файлов).
 * Ответ приводится к виду ответа API (tag_name, name, published_at, body, assets[name, size, browser_download_url]); размер файла в ленте неизвестен (0).
 * Сумма SHA-256 и versionCode берутся, как и раньше, из описания выпуска; подпись файла и целостность проверяются прежними проверками при установке.
 */
object GhFeed {
    /**
     * Проверка без GitHub (только тестовая сборка, TEST_HOOK = true): файл gh_test_base.txt в папке файлов приложения (Android/data/app.adbphon/files) с адресом-заменой
     * для api.github.com и github.com (локальный сервер). В выпускной сборке TEST_HOOK = false: адреса не подменяются никогда.
     */
    private const val TEST_HOOK = false
    private fun testBase(): String = if (!TEST_HOOK) "" else runCatching { java.io.File(FmIo.app.getExternalFilesDir(null), "gh_test_base.txt").readText() }.getOrNull().orEmpty().trim().trimEnd('/')

    fun map(url: String): String {
        val b = testBase()
        return if (b.isEmpty()) url else url.replace("https://api.github.com", b).replace("https://github.com", b)
    }

    fun text(url: String, accept: String): String {
        val c = URL(map(url)).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "ADB-phon")
        c.setRequestProperty("Accept", accept)
        try {
            val code = c.responseCode
            if (code !in 200..299) error("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun unesc(t: String) = t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    /** Описание выпуска из html ленты в вид Markdown, который понимают разборщики «Что нового», суммы и versionCode. */
    private fun htmlToMd(contentRaw: String): String {
        val html = unesc(contentRaw)
        return html.replace(Regex("(?i)<strong>(.*?)</strong>"), "**$1**").replace(Regex("(?i)<h[1-6][^>]*>"), "### ").replace(Regex("(?i)</h[1-6]>"), "\n")
            .replace(Regex("(?i)<li[^>]*>"), "- ").replace(Regex("(?i)</li>|</p>|<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "").let(::unesc)
            .replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** Выпуски из ленты (новые первыми) в виде объектов API без списка файлов. */
    fun feed(repo: String, max: Int = 8): List<JSONObject> {
        val feed = text("https://github.com/$repo/releases.atom", "application/atom+xml")
        val out = ArrayList<JSONObject>()
        for (m in Regex("(?s)<entry>(.*?)</entry>").findAll(feed)) {
            if (out.size >= max) break
            val e = m.groupValues[1]
            val link = Regex("""<link[^>]*href="[^"]*/releases/tag/([^"]+)"""").find(e)?.groupValues?.get(1) ?: continue
            val tag = URLDecoder.decode(link, "UTF-8")
            val title = unesc(Regex("(?s)<title>(.*?)</title>").find(e)?.groupValues?.get(1).orEmpty()).trim()
            val updated = Regex("<updated>(.*?)</updated>").find(e)?.groupValues?.get(1).orEmpty()
            val content = Regex("(?s)<content[^>]*>(.*?)</content>").find(e)?.groupValues?.get(1).orEmpty()
            out += JSONObject().put("tag_name", tag).put("name", title).put("published_at", updated).put("body", htmlToMd(content)).put("prerelease", false).put("draft", false)
        }
        if (out.isEmpty()) error("лента выпусков пуста или не распознана")
        return out
    }

    /** Файлы выпуска [tag] со страницы expanded_assets: имя и прямая ссылка. */
    fun assets(repo: String, tag: String): JSONArray {
        val page = text("https://github.com/$repo/releases/expanded_assets/${URLEncoder.encode(tag, "UTF-8").replace("+", "%20")}", "text/html")
        val out = JSONArray()
        for (path in Regex("""href="(/${Regex.escape(repo)}/releases/download/[^"]+)"""").findAll(page).map { it.groupValues[1] }.distinct()) {
            out.put(JSONObject().put("name", URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")).put("size", 0).put("browser_download_url", "https://github.com$path"))
        }
        return out
    }

    /** Выпуски с файлами (как ответ API releases): у первых [withAssets] выпусков подгружаются файлы. */
    fun releases(repo: String, max: Int = 8, withAssets: Int = 5): List<JSONObject> {
        val list = feed(repo, max)
        list.forEachIndexed { i, r -> if (i < withAssets) r.put("assets", runCatching { assets(repo, r.getString("tag_name")) }.getOrElse { JSONArray() }) else r.put("assets", JSONArray()) }
        return list
    }

    /** Ссылки на APK последнего выпуска (как releases/latest): null, если выпусков или файлов нет. */
    fun latestApkUrls(repo: String): List<String> {
        val r = feed(repo, 1).first()
        val a = assets(repo, r.getString("tag_name"))
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("name").lowercase().endsWith(".apk") }.map { it.getString("browser_download_url") }
    }
}
