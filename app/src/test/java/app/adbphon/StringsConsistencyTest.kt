package app.adbphon

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Согласованность строк ресурсов в трёх языках: values (en), values-ru, values-uk.
 * Ключи совпадают, плейсхолдеры одинаковые, нет длинного тире и запрещённых слов.
 * Та же проверка для ручного запуска: C:\adbphon_w14\проверка_строк.py
 */
class StringsConsistencyTest {
    private val res: File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val langs = listOf("values", "values-ru", "values-uk")

    /** Ключи, которые сознательно есть только в values (имя программы). */
    private val onlyDefault = setOf("app_name")

    /** Старые строки из общего strings.xml с длинным тире (до 10.0); новые сюда не добавлять. */
    private val legacyDash = setOf("ap_batch_q", "ap_warn_system", "fm_same_folder", "pc_add_hint")

    /** Файлы новых задач 10.0: для них правила строже (первое лицо). */
    private val newFiles = listOf("kbtn", "bkp", "bk2", "misc", "cld", "fm1", "inst", "set", "cnt", "bk3").map { "strings_$it.xml" }

    private val ph = Regex("%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([sdfxXcboeEgG])")

    /** имя ресурса -> (файл, тексты) */
    private fun load(dir: String, only: Collection<String>? = null): Map<String, Pair<String, List<String>>> {
        val out = LinkedHashMap<String, Pair<String, List<String>>>()
        val files = File(res, dir).listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty()
            .filter { only == null || it.name in only }.sortedBy { it.name }
        val dbf = DocumentBuilderFactory.newInstance()
        for (f in files) {
            val root = dbf.newDocumentBuilder().parse(f).documentElement
            val kids = root.childNodes
            for (i in 0 until kids.length) {
                val el = kids.item(i) as? Element ?: continue
                val name = el.getAttribute("name")
                if (name.isEmpty() || el.getAttribute("translatable") == "false") continue
                val items = { tag: String -> el.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it).textContent } } }
                val key = when (el.tagName) {
                    "string" -> name
                    "plurals" -> "plurals:$name"
                    "string-array", "array" -> "array:$name"
                    else -> continue
                }
                val texts = if (el.tagName == "string") listOf(el.textContent) else items("item")
                out[key] = f.name to texts
            }
        }
        return out
    }

    private fun placeholders(texts: List<String>): Set<String> =
        texts.flatMap { t -> ph.findAll(t.replace("%%", "")).map { m -> m.groupValues[1] + m.groupValues[2].lowercase().replace('x', 'd') }.toList() }.toSet()

    private fun report(errs: List<String>) =
        assertTrue("Нарушений: ${errs.size}\n" + errs.take(60).joinToString("\n"), errs.isEmpty())

    @Test fun sameKeysInAllThreeLanguages() {
        val maps = langs.associateWith { load(it) }
        val all = maps.values.flatMap { it.keys }.toSortedSet()
        val errs = all.filter { it !in onlyDefault }.mapNotNull { k ->
            val miss = langs.filter { k !in maps.getValue(it) }
            if (miss.isEmpty()) null else "$k (${maps.values.firstNotNullOf { it[k] }.first}): нет в ${miss.joinToString()}"
        }
        report(errs)
    }

    @Test fun samePlaceholdersAndArraySizes() {
        val maps = langs.associateWith { load(it) }
        val errs = mutableListOf<String>()
        for ((k, en) in maps.getValue("values")) {
            for (l in langs.drop(1)) {
                val other = maps.getValue(l)[k] ?: continue
                if (placeholders(en.second) != placeholders(other.second))
                    errs += "$k [$l]: ${placeholders(en.second)} != ${placeholders(other.second)}"
                if (k.startsWith("array:") && en.second.size != other.second.size)
                    errs += "$k [$l]: пунктов ${en.second.size} != ${other.second.size}"
            }
        }
        report(errs)
    }

    @Test fun noLongDashNoBannedWords() {
        val errs = mutableListOf<String>()
        val banned = Regex("rclone|magisk", RegexOption.IGNORE_CASE)
        for (l in langs) for ((k, v) in load(l)) for (t in v.second) {
            if ('\u2014' in t && k !in legacyDash) errs += "${v.first} [$l] $k: длинное тире"
            if (banned.containsMatchIn(t)) errs += "${v.first} [$l] $k: запрещённое слово"
        }
        report(errs)
    }

    @Test fun newFilesHaveNoFirstPerson() {
        val first = mapOf(
            "values" to Regex("\\b(I|I'm|I'll|I've|me|my|mine|we|we're|we'll|us|our|ours)\\b"),
            "values-ru" to Regex("(?<![\\p{L}-])(я|мне|меня|мной|мой|моя|моё|мои|мы|нам|нас|наш|наша|наше|наши)(?![\\p{L}-])", RegexOption.IGNORE_CASE),
            "values-uk" to Regex("(?<![\\p{L}'\u2019\u02bc-])(я|мені|мене|мною|мій|моя|моє|мої|ми|нам|нас|наш|наша|наше|наші)(?![\\p{L}'\u2019\u02bc-])", RegexOption.IGNORE_CASE),
        )
        val errs = mutableListOf<String>()
        for (l in langs) for ((k, v) in load(l, newFiles)) for (t in v.second)
            first.getValue(l).find(t)?.let { errs += "${v.first} [$l] $k: «${it.value}»" }
        report(errs)
    }

    @Test fun newFilesExistInAllLanguages() {
        val errs = newFiles.flatMap { f -> langs.filter { !File(File(res, it), f).isFile }.map { "$f нет в $it" } }
        report(errs)
    }
}
