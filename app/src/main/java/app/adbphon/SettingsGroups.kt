package app.adbphon

/**
 * 10.0 (set): группы экрана «Настройки». Чистая логика без Compose (проверяется юнит-тестами): порядок групп, пункты по группам, поиск.
 * Каждый пункт ровно в одной группе; ключ пункта связывает его с вёрсткой (SettingsItems.kt) и с названиями для поиска.
 */
object SetGroups {
    const val UPD = "upd"       // Обновления и копии (первая и заметная)
    const val UI = "ui"         // Интерфейс
    const val FILES = "files"   // Файлы
    const val PLAYER = "player" // Плеер (видео и музыка): панель, размер картинки, жесты
    const val ADB = "adb"       // Связь и ADB
    const val CLOUD = "cloud"   // Облака
    const val PANELS = "panels" // Панели
    const val SYS = "sys"       // Система

    val ORDER = listOf(UPD, UI, FILES, PLAYER, ADB, CLOUD, PANELS, SYS)

    /** set2: первый пункт первой группы: четыре большие кнопки (обновления, копия, восстановление, о программе). */
    const val HERO = "hero"

    /** Пункты в порядке показа: ключ пункта и его группа. */
    val ITEMS: List<Pair<String, String>> = listOf(
        HERO to UPD, "upd_auto" to UPD, "versions" to UPD, "ver_clear" to UPD, "backup" to UPD, "copies" to UPD,
        "theme" to UI, "lang" to UI, "font" to UI, "ui_size" to UI, "full" to UI, "colors" to UI, "bars" to UI, "tapguard" to UI, "anim" to UI,
        "dir_tap" to FILES, "narrow_font" to FILES, "folder_color" to FILES, "type_icons" to FILES, "thumbs" to FILES, "apk_icon" to FILES, "arrow_color" to FILES, "compact_tree" to FILES, "fm_sub" to FILES, "delete" to FILES, "open_files" to FILES,
        "installer" to FILES, "wifi_auto" to FILES, "saf" to FILES,
        "vp_hide" to PLAYER, "vp_keep" to PLAYER, "bg_music" to PLAYER, "vp_gest" to PLAYER, "vp_reset" to PLAYER,
        "helper" to ADB, "shizuku" to ADB, "perms" to ADB, "term" to ADB,
        "cmod" to CLOUD, "cmod_auto" to CLOUD, "cloud_bg" to CLOUD,
        "hand" to PANELS, "side_mode" to PANELS, "panel_colors" to PANELS,
        "startup" to SYS, "keep_on" to SYS, "exit_back" to SYS, "log" to SYS,
    )

    fun groupOf(key: String): String? = ITEMS.firstOrNull { it.first == key }?.second

    fun itemsOf(group: String): List<String> = ITEMS.filter { it.second == group }.map { it.first }

    /** Строка для сравнения: нижний регистр, ё как е, лишние пробелы убраны. */
    fun norm(s: String): String = s.lowercase().replace('ё', 'е').replace('…', ' ').replace("...", " ").trim().replace(Regex("\\s+"), " ")

    /** Минимальная длина запроса (одна буква находит слишком много). */
    const val MIN_QUERY = 2

    /**
     * set2: синонимы поиска (как в «Меню приложений»): слово запроса, похожее на любое слово группы, ищется всеми словами группы.
     * Начала слов на трёх языках, чтобы «бекап», «backup» и «копия» находили одно и то же.
     */
    val SYNONYMS: List<List<String>> = listOf(
        listOf("бекап", "бэкап", "backup", "копи", "копі", "резерв", "copy", "copies", "восстанов", "відновл", "restore"),
        listOf("обнов", "оновл", "update", "upgrade", "верси", "версі", "version"),
        listOf("язык", "мова", "мову", "language", "lang", "english", "русск", "україн", "англ"),
        listOf("тема", "тему", "темн", "светл", "світл", "dark", "light", "theme"),
        listOf("цвет", "колір", "кольор", "color", "colour", "акцент", "accent"),
        listOf("шрифт", "font", "размер", "розмір", "size", "масштаб", "scale", "крупн", "велик"),
        listOf("adb", "отладк", "налагодж", "debug", "shizuku", "root", "помощник", "помічник", "helper"),
        listOf("журнал", "лог", "log"),
        listOf("запуск", "старт", "startup", "start"),
        listOf("корзин", "кошик", "trash", "удален", "видален", "delete"),
        listOf("облак", "хмар", "cloud", "диск", "drive"),
        listOf("экран", "екран", "screen", "полноэкр", "повноекр", "fullscreen"),
        listOf("плеер", "плеєр", "player", "видео", "відео", "video"),
        listOf("жест", "gesture", "свайп", "swipe"),
        listOf("about", "github", "лиценз", "ліценз", "licens", "донат", "donat"),
    )

    /** Слово запроса и все синонимы его групп (уже в виде [norm]). */
    fun expand(word: String): Set<String> {
        val out = HashSet<String>().apply { add(word) }
        for (g in SYNONYMS) if (g.any { s -> word.contains(s) || (word.length >= 3 && s.startsWith(word)) }) out.addAll(g)
        return out
    }

    /**
     * Поиск по части названия: [titles] ключ пункта и все его названия (заголовок и подпункты) на текущем языке.
     * Каждое слово запроса (или его синоним) должно найтись в названиях пункта. Результат в порядке групп и пунктов, без повторов.
     */
    fun search(query: String, titles: Map<String, List<String>>): List<String> {
        val q = norm(query)
        if (q.length < MIN_QUERY) return emptyList()
        val words = q.split(' ').filter { it.isNotEmpty() }.map { expand(it) }
        val out = ArrayList<String>()
        for (g in ORDER) for (key in itemsOf(g)) {
            val text = titles[key]?.joinToString(" ") { norm(it) } ?: continue
            if (words.all { alts -> alts.any { text.contains(it) } }) out += key
        }
        return out
    }

    /** set2: название пункта для строки результата: то, где нашлись все слова запроса, иначе хоть одно, иначе первое. */
    fun bestTitle(query: String, list: List<String>): String? {
        val words = norm(query).split(' ').filter { it.isNotEmpty() }.map { expand(it) }
        fun hits(t: String): Int { val n = norm(t); return words.count { alts -> alts.any { n.contains(it) } } }
        return list.firstOrNull { words.isNotEmpty() && hits(it) == words.size } ?: list.firstOrNull { hits(it) > 0 } ?: list.firstOrNull()
    }
}
