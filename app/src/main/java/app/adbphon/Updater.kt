package app.adbphon

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Версии самой программы: список выпусков на GitHub и загрузка выбранной версии. */
object Updater {
    private const val RELEASES = "https://api.github.com/repos/drugoj00/adb-phon/releases?per_page=30"

    data class Release(val tag: String, val title: String, val date: String, val url: String, val size: Long)

    /** Список выпусков с APK. Блокирующий вызов — запускать не в главном потоке. */
    fun fetch(): List<Release> {
        val c = URL(RELEASES).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "ADB-phon")
        try {
            if (c.responseCode == 404) return emptyList() // репозиторий или выпусков ещё нет
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            val out = ArrayList<Release>()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val assets = r.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        out += Release(
                            tag = r.optString("tag_name"),
                            title = r.optString("name").ifBlank { r.optString("tag_name") },
                            date = r.optString("published_at").take(10),
                            url = a.optString("browser_download_url"),
                            size = a.optLong("size"),
                        )
                        break
                    }
                }
            }
            return out
        } finally {
            c.disconnect()
        }
    }

    /** Скачивает APK выпуска в папку версий приложения и возвращает файл. */
    fun download(ctx: Context, r: Release): File {
        val dest = File(VersionKeeper.dir(ctx), "ADB-phon-github-${r.tag.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk")
        val part = File(dest.path + ".part")
        val c = URL(r.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "ADB-phon")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            c.inputStream.use { i -> part.outputStream().use { o -> i.copyTo(o) } }
        } finally {
            c.disconnect()
        }
        if (dest.exists()) dest.delete()
        part.renameTo(dest)
        return dest
    }
}
