package app.adbphon

import android.webkit.MimeTypeMap

/**
 * Типы файлов для «Открыть в системе» и «Открыть через…».
 * Системная таблица Android ([MimeTypeMap]) не знает многих книг и форматов (fb2, mobi, azw3, djvu, cbz...), и запрос уходил с типом любого файла,
 * из-за чего читалки (FBReader и др.) не предлагались. Своя таблица дополняет системную: берётся там, где системной записи нет.
 */
object FileMime {
    private val OWN: Map<String, String> = mapOf(
        // книги и комиксы
        "fb2" to "application/x-fictionbook+xml",
        "fbz" to "application/x-zip-compressed-fb2",
        "epub" to "application/epub+zip",
        "mobi" to "application/x-mobipocket-ebook",
        "prc" to "application/x-mobipocket-ebook",
        "azw" to "application/vnd.amazon.ebook",
        "azw3" to "application/vnd.amazon.mobi8-ebook",
        "kfx" to "application/vnd.amazon.ebook",
        "djvu" to "image/vnd.djvu",
        "djv" to "image/vnd.djvu",
        "cbz" to "application/vnd.comicbook+zip",
        "cbr" to "application/vnd.comicbook-rar",
        "cb7" to "application/x-cb7",
        "cbt" to "application/x-cbt",
        "lit" to "application/x-ms-reader",
        "chm" to "application/vnd.ms-htmlhelp",
        "rtf" to "application/rtf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "pdf" to "application/pdf",
        "md" to "text/markdown",
        // архивы и прочее, чего часто нет в системной таблице
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "zip" to "application/zip",
        "gz" to "application/gzip",
        "tar" to "application/x-tar",
        "xz" to "application/x-xz",
        "bz2" to "application/x-bzip2",
        "apks" to "application/x-apks",
        "xapk" to "application/xapk-package-archive",
        "apkm" to "application/vnd.apkm",
        "json" to "application/json",
        "xml" to "text/xml",
        "csv" to "text/csv",
        "srt" to "application/x-subrip",
        "vtt" to "text/vtt",
        "ass" to "text/x-ssa",
        "ssa" to "text/x-ssa",
        "flac" to "audio/flac",
        "opus" to "audio/ogg",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "avif" to "image/avif",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "torrent" to "application/x-bittorrent",
        "iso" to "application/x-iso9660-image",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
    )

    /** Тип по расширению: сначала системная таблица (чтобы не менять уже работающие типы), затем своя; null, если нигде нет (тогда решает вызывающий). */
    fun known(ext: String): String? {
        val e = ext.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(e) ?: OWN[e]
    }

    /** Тип по расширению, а для неизвестных запасной тип [fallback] (для запроса приложений обычно любой тип). */
    fun of(ext: String, fallback: String = "*/*"): String = known(ext) ?: fallback

    /** Тип по имени файла: двойное расширение .fb2.zip (книга в архиве) отдельно от обычного .zip. */
    fun ofName(name: String, fallback: String = "*/*"): String {
        val n = name.lowercase()
        if (n.endsWith(".fb2.zip")) return "application/x-zip-compressed-fb2"
        return of(n.substringAfterLast('.', ""), fallback)
    }
}
