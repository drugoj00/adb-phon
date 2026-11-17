package app.adbphon

/**
 * 10.0: чистая логика окна «Установить через…» (без Android, проверяется юнит-тестами).
 * Один общий запомненный установщик для .apk, .apks, .xapk и .apkm; null значит «Спрашивать каждый раз».
 * Запомненный вариант применяется без окна, только если он сейчас доступен; иначе окно показывается снова.
 */
object InstallerChoice {
    /** Тихая установка ADB-phon (ADB или root). */
    const val QUIET = "adb"
    /** Установка ADB-phon своей сессией с подтверждением Android. */
    const val SESSION = "session"
    /** Системный установщик Android. */
    const val SYSTEM = "system"
    /** Не установщик, а действие в том же окне (открыть как архив): не запоминается. */
    const val ARCHIVE = "archive"
    const val ASK = "ask"

    /** Вариант в окне: id и доступен ли он сейчас. */
    data class Opt(val id: String, val available: Boolean)

    sealed class Decision {
        /** Поставить сразу этим вариантом, без окна. */
        data class Auto(val id: String) : Decision()
        /** Показать окно; [focus] номер строки для фокуса (-1: доступных нет), [savedOff] запомненный вариант сейчас недоступен. */
        data class Show(val focus: Int, val savedOff: Boolean) : Decision()
    }

    /** Можно ли запомнить вариант (действия вроде «Открыть как архив» не запоминаются). */
    fun rememberable(id: String): Boolean = id != ARCHIVE && id != "market" && id != "builtin" && id != ASK && id.isNotBlank()

    /** Приводит сохранённое значение к виду хранилища: пустое, «ask» и не-установщики дают null. */
    fun normalize(v: String?): String? = v?.trim()?.takeIf { it.isNotEmpty() && rememberable(it) }

    /** Пакет стороннего установщика из id вида «cmp:пакет/класс». */
    fun pkgOf(id: String): String? = if (id.startsWith("cmp:")) id.removePrefix("cmp:").substringBefore('/').takeIf { it.isNotEmpty() } else null

    /** Совпадает ли запомненное значение с вариантом: точно или тот же сторонний установщик (класс окна в новой версии мог смениться). */
    fun matches(saved: String, id: String): Boolean {
        if (saved == id) return true
        val a = pkgOf(saved) ?: return false
        return a == pkgOf(id)
    }

    /** Строка запомненного варианта в списке или -1. */
    fun indexOfSaved(opts: List<Opt>, saved: String?): Int {
        val s = normalize(saved) ?: return -1
        val exact = opts.indexOfFirst { it.id == s }
        return if (exact >= 0) exact else opts.indexOfFirst { matches(s, it.id) }
    }

    /** Строка для фокуса: запомненная, если доступна, иначе первая доступная, иначе -1. */
    fun focusIndex(opts: List<Opt>, saved: String?): Int {
        val i = indexOfSaved(opts, saved)
        if (i >= 0 && opts[i].available) return i
        return opts.indexOfFirst { it.available && it.id != ARCHIVE }.takeIf { it >= 0 } ?: opts.indexOfFirst { it.available }
    }

    /**
     * Что делать при установке. [force]: окно нужно всегда (пункт меню «Установить через…»).
     * Запомненный и доступный вариант: сразу; запомненный, но недоступный или отсутствующий: окно с пометкой.
     */
    fun decide(opts: List<Opt>, saved: String?, force: Boolean = false): Decision {
        val s = normalize(saved)
        val i = indexOfSaved(opts, s)
        if (!force && s != null && i >= 0 && opts[i].available) return Decision.Auto(opts[i].id)
        // 10.2: запомнено тихое «ADB-phon», а ADB и root сейчас нет: ставим тем же ADB-phon с подтверждением системы (окно с версиями, затем системный установщик)
        if (!force && s == QUIET && i >= 0 && !opts[i].available && opts.any { it.id == SESSION && it.available }) return Decision.Auto(SESSION)
        return Decision.Show(focusIndex(opts, s), s != null && (i < 0 || !opts[i].available))
    }

    /** Новое значение хранилища после выбора в окне. Галочка снята: запомненное не меняется. */
    fun afterPick(saved: String?, picked: String, rememberChecked: Boolean): String? =
        if (rememberChecked && rememberable(picked)) picked else normalize(saved)

    /**
     * Перенос старых настроек (до 10.0): «Спрашивать, чем устанавливать» [askLegacy] и запомненное по расширениям
     * (inst_apk, inst_apks, inst_xapk, inst_apkm). Включённое «Спрашивать» показывало окно всегда: выбор не переносится.
     * Выключенное: берётся запомненное для .apk, затем для пакетов; если ничего нет, ADB-phon (прежний способ без окна).
     */
    fun migrate(askLegacy: Boolean, legacy: List<String?>): String? {
        if (askLegacy) return null
        return legacy.firstNotNullOfOrNull { normalize(it) } ?: QUIET
    }
}
