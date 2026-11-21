package app.adbphon

/**
 * 17.0 (код 83): итог каждой установки одной строкой в журнале: способ, пакет, версия, число частей и для неудачи код ошибки системы
 * (INSTALL_FAILED_...) с первой строкой ответа. Поиск по слову «Install:».
 */
object InstLog {
    fun result(method: String, pkg: String?, verName: String?, verCode: Long?, parts: Int, raw: String, ok: Boolean) {
        val code = Regex("INSTALL_[A-Z_]+").find(raw)?.value
        val first = raw.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(200).orEmpty()
        AppLog.i(
            "ADBphon",
            "Install: итог=" + (if (ok) "успех" else "ошибка") + " способ=$method пакет=${pkg ?: "?"} версия=${verName ?: "?"}(${verCode ?: "?"}) частей=$parts" +
                (if (!ok) " код=${code ?: "-"} ответ=\"$first\"" else ""),
        )
    }
}
