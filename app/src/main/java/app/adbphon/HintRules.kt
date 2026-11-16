package app.adbphon

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 10.0: правила подсказок со стрелкой «Жми сюда» поверх браузера. Какие фразы искать на экране (для каждого сервиса и шага), какие подписи показывать
 * на ru/uk/en: всё в данных, а не в коде. Если Google или Яндекс поменяют страницу, правится маленький JSON на сайте программы, а не выпускается новая версия.
 *
 * Порядок источников: 1) встроенный набор (res/raw/hint_rules.json); 2) более новый набор с сайта, сохранённый в файле (обновляется не чаще раза в сутки
 * при входе в облако или открытии мастера ключа; нет сети или файл не разобрался: остаётся прежний набор); 3) файл hint_rules_test.json в папке files/ программы
 * (только для проверок, заменяет всё). В наборе нет ключей и паролей, только строки для поиска и подписи; размеры ограничены.
 */
object HintRules {
    const val URL_RULES = "https://drugoj00.github.io/adb-phon/hint-rules.json"
    private const val TTL_MS = 24 * 3600_000L

    class Step(val find: List<String>, val exact: Boolean, val wizard: Int?, val label: Map<String, String>, val text: Map<String, String>) {
        fun labelFor(lang: String) = label[lang] ?: label["ru"] ?: label["en"] ?: ""
        fun textFor(lang: String) = text[lang] ?: text["ru"] ?: text["en"] ?: ""
    }

    @Volatile private var rules: Map<String, List<Step>> = emptyMap()
    @Volatile private var version = -1
    @Volatile private var loaded = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun cacheFile(ctx: Context) = File(ctx.applicationContext.filesDir, "hint_rules.json")
    private fun testFile(ctx: Context) = ctx.applicationContext.getExternalFilesDir(null)?.let { File(it, "hint_rules_test.json") }

    private fun strMap(o: JSONObject?): Map<String, String> {
        val m = HashMap<String, String>()
        if (o != null) for (k in listOf("ru", "uk", "en")) o.optString(k, "").trim().take(160).takeIf { it.isNotEmpty() }?.let { m[k] = it }
        return m
    }

    /** Разбор набора; любая ошибка или несоответствие формату: null (набор не применяется). */
    fun parse(json: String): Pair<Int, Map<String, List<Step>>>? = runCatching {
        val root = JSONObject(json)
        val v = root.getInt("version")
        val sv = root.getJSONObject("services")
        val out = LinkedHashMap<String, List<Step>>()
        for (id in sv.keys().asSequence().take(20)) {
            val arr = sv.getJSONObject(id).getJSONArray("steps")
            val steps = ArrayList<Step>()
            for (i in 0 until minOf(arr.length(), 12)) {
                val s = arr.getJSONObject(i)
                val fa = s.getJSONArray("find")
                val find = (0 until minOf(fa.length(), 12)).map { fa.getString(it).trim().take(80) }.filter { it.isNotEmpty() }
                if (find.isEmpty()) continue
                steps += Step(find, s.optBoolean("exact", false), if (s.has("wizard")) s.optInt("wizard") else null, strMap(s.optJSONObject("label")), strMap(s.optJSONObject("text")))
            }
            out[id] = steps
        }
        v to out
    }.getOrNull()

    private fun apply(p: Pair<Int, Map<String, List<Step>>>) { version = p.first; rules = p.second }

    @Synchronized private fun ensure(ctx: Context) {
        if (loaded) return
        loaded = true
        val bt = runCatching { ctx.resources.openRawResource(R.raw.hint_rules).bufferedReader().use { it.readText() } }
        bt.getOrNull()?.let { parse(it) }?.let { apply(it) }
        val c = cacheFile(ctx)
        if (c.exists()) runCatching { c.readText() }.getOrNull()?.let { parse(it) }?.takeIf { it.first >= version }?.let { apply(it) }
        val t = testFile(ctx)
        if (t != null && t.exists()) runCatching { t.readText() }.getOrNull()?.let { parse(it) }?.let { apply(it); version = Int.MAX_VALUE }
    }

    /** Шаги сервиса [id]; для мастера можно оставить только шаги нужного номера. */
    fun steps(ctx: Context, id: String, wizard: Int? = null): List<Step> {
        ensure(ctx)
        return rules[id].orEmpty().filter { it.wizard == null || wizard == null || it.wizard == wizard }
    }

    /** Раз в сутки (при входе в облако или открытии мастера) пробует скачать более новый набор; без сети или с ошибкой всё остаётся как было. */
    fun refreshAsync(ctx: Context) {
        val app = ctx.applicationContext
        ensure(app)
        val p = app.getSharedPreferences("adbphon", Context.MODE_PRIVATE)
        val ts = p.getLong("hint_rules_ts", 0L)
        val now = System.currentTimeMillis()
        if (ts in 1..now && now - ts < TTL_MS) return
        scope.launch {
            runCatching {
                val c = URL(URL_RULES).openConnection() as HttpURLConnection
                c.connectTimeout = 4000; c.readTimeout = 4000; c.useCaches = false
                try {
                    if (c.responseCode == 200) {
                        val txt = c.inputStream.bufferedReader().use { it.readText().take(64_000) }
                        val parsed = parse(txt)
                        if (parsed != null && parsed.first >= version && version != Int.MAX_VALUE) {
                            cacheFile(app).writeText(txt); apply(parsed)
                        }
                        p.edit().putLong("hint_rules_ts", System.currentTimeMillis()).apply()
                    }
                } finally { c.disconnect() }
            }
        }
    }

    fun lang(ctx: Context): String = ctx.resources.configuration.locales[0].language.takeIf { it in setOf("ru", "uk", "en") } ?: "ru"
}
