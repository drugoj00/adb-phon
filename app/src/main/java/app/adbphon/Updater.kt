package app.adbphon

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Версии самой программы: список выпусков на GitHub и загрузка выбранной версии. */
object Updater {
    private val RELEASES: String get() = Sx.RELEASES

    data class Release(val tag: String, val title: String, val date: String, val url: String, val size: Long, val body: String = "") {
        /** versionCode из строки «versionCode: N» в описании выпуска (0: строки нет). Нужен, когда имя версии то же, а сборка новее (замена файла выпуска). */
        val code: Long get() = Regex("""(?im)^\W*versionCode\s*[:=]\s*(\d+)""").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }

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
                // самообновление смотрит только выпуски самой программы (тег вида «v5.0» или «5.0»); выпуск модуля облаков («clouds-1.0») и любые другие теги пропускаются
                if (!Regex("^v?\\d+(\\.\\d+)*\$").matches(r.optString("tag_name"))) continue
                if (r.optBoolean("prerelease") || r.optBoolean("draft")) continue
                val assets = r.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    // файл программы, а не модуля облаков
                    if (a.optString("name").endsWith(".apk", ignoreCase = true) && !a.optString("name").contains("clouds", ignoreCase = true)) {
                        out += Release(
                            tag = r.optString("tag_name"),
                            title = r.optString("name").ifBlank { r.optString("tag_name") },
                            date = r.optString("published_at").take(10),
                            url = a.optString("browser_download_url"),
                            size = a.optLong("size"),
                            body = r.optString("body"),
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

    /** Язык описания по коду интерфейса: ru, uk или en (авто: по языку системы; прочие языки получают английский текст). */
    fun langOf(code: String): String {
        val c = if (code == "auto" || code.isBlank()) java.util.Locale.getDefault().language else code
        return when (c.lowercase().take(2)) { "ru", "be", "kk" -> "ru"; "uk" -> "uk"; else -> "en" }
    }

    private val H_EN = Regex("^\\W*(English|Англійська|Английский)\\W*$", RegexOption.IGNORE_CASE)
    private val H_RU = Regex("^\\W*(Русский|Russian|Російська)\\W*$", RegexOption.IGNORE_CASE)
    private val H_UK = Regex("^\\W*(Українська|Ukrainian|Украинский)\\W*$", RegexOption.IGNORE_CASE)
    private val STOP = Regex("^\\W*(Requirements|Требования|Вимоги)", RegexOption.IGNORE_CASE)
    private val FIXED = Regex("^\\W*(Fixed|Исправлено|Виправлено)\\W*:\\W*(.*)$", RegexOption.IGNORE_CASE)
    private val SHA = Regex("(?i)\\bsha-?(1|256)\\b|\\b[0-9a-f]{40,64}\\b")

    private fun clean(s: String): String = s.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1").replace("**", "").replace("__", "").replace("`", "").trim()

    /**
     * 10.2 (правило 23): пункты «Что нового» из описания выпуска GitHub для языка [lang] (ru, uk, en). Блоки языков в описании: «**English**», «**Русский**»,
     * «**Українська**»; нет блока языка: английский, затем всё описание. В список идут маркированные строки и строка «Исправлено: …»; заголовки «#»,
     * ссылки на руководство, строки с SHA и всё, что после «Требования и ограничения», отбрасываются. Пусто: описания нет.
     */
    fun notes(body: String, lang: String): List<String> {
        if (body.isBlank()) return emptyList()
        val blocks = LinkedHashMap<String, MutableList<String>>()
        var cur = "all"
        for (raw in body.replace("\r", "").lines()) {
            val t = raw.trim()
            val hd = when { H_EN.matches(t.replace("*", "")) -> "en"; H_RU.matches(t.replace("*", "")) -> "ru"; H_UK.matches(t.replace("*", "")) -> "uk"; else -> null }
            if (hd != null) { cur = hd; blocks.getOrPut(cur) { ArrayList() }; continue }
            blocks.getOrPut(cur) { ArrayList() }.add(raw)
        }
        val lines = blocks[lang] ?: blocks["en"] ?: blocks["all"] ?: blocks.values.firstOrNull() ?: return emptyList()
        val out = ArrayList<String>()
        for (raw in lines) {
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            if (STOP.containsMatchIn(t.replace("*", "").trim())) break
            if (SHA.containsMatchIn(t)) continue
            val f = FIXED.find(t.replace("**", ""))
            if (f != null) { val x = clean(f.groupValues[2]); if (x.isNotEmpty()) out += clean(t.substringBefore(':').replace("*", "")).trimStart { !it.isLetter() } + ": " + x; continue }
            if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ")) { val x = clean(t.drop(2)); if (x.isNotEmpty()) out += x }
        }
        return out
    }

    /** Скачивает APK выпуска в папку версий приложения и возвращает файл. */
    fun download(ctx: Context, r: Release): File {
        if (!Integrity.safe(ctx)) throw IllegalStateException("integrity")
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
