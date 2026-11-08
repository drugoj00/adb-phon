package app.adbphon

/**
 * Сортировка списка «Файлы» по группам: папки, обычные файлы, фото, видео, музыка, документы.
 * Здесь нет ничего из Android: файл проверяется обычными тестами на JVM (app/src/test).
 */

/** Группы элементов. [ch] — однобуквенный код для хранения в настройках. */
enum class SGroup(val ch: Char) { DIR('D'), FILE('F'), PHOTO('P'), VIDEO('V'), MUSIC('M'), DOC('C') }

/** Критерии сортировки. Какие доступны в группе — см. [SortGroups.keys]. */
enum class SKey { NAME, EXT, SIZE, DATE, COLOR, COUNT, TITLE, ARTIST, ALBUM, TRACK, DURATION, RES, TAKEN }

/** Теги, прочитанные из файла в фоне. Неизвестное значение: null или -1 (такие элементы идут в конец списка в любом направлении). */
data class SMeta(
    val title: String? = null, val artist: String? = null, val album: String? = null,
    val track: Int = -1, val durMs: Long = -1, val w: Int = 0, val h: Int = 0, val taken: Long = -1,
)

object SortGroups {
    val keys: Map<SGroup, List<SKey>> = mapOf(
        SGroup.DIR to listOf(SKey.NAME, SKey.DATE, SKey.COUNT, SKey.COLOR),
        SGroup.FILE to listOf(SKey.NAME, SKey.EXT, SKey.SIZE, SKey.DATE),
        SGroup.PHOTO to listOf(SKey.NAME, SKey.TAKEN, SKey.DATE, SKey.SIZE),
        SGroup.VIDEO to listOf(SKey.NAME, SKey.DATE, SKey.SIZE, SKey.DURATION, SKey.RES),
        SGroup.MUSIC to listOf(SKey.NAME, SKey.TITLE, SKey.ARTIST, SKey.ALBUM, SKey.TRACK, SKey.DURATION, SKey.DATE, SKey.SIZE),
        SGroup.DOC to listOf(SKey.NAME, SKey.EXT, SKey.SIZE, SKey.DATE),
    )

    // те же расширения, что в fileCat (FileUi.kt); здесь продублированы, чтобы движок не зависел от Android
    private val photo = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    private val video = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts", "flv", "wmv", "mpg", "mpeg")
    private val music = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma", "amr", "mid")
    private val doc = setOf("pdf", "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp", "txt", "log", "md")

    /** Группа по типу файла, без учёта настроек. */
    fun base(isDir: Boolean, ext: String): SGroup = when {
        isDir -> SGroup.DIR
        ext in photo -> SGroup.PHOTO
        ext in video -> SGroup.VIDEO
        ext in music -> SGroup.MUSIC
        ext in doc -> SGroup.DOC
        else -> SGroup.FILE
    }
}

/**
 * Настройка сортировки.
 * [grouped]: режим «Группировать по типу» (заголовки; каждая группа отдельно).
 * [sep]: какие из групп «Фото / Видео / Музыка / Документы» идут отдельным блоком со своей сортировкой; остальные вливаются в «Файлы».
 * Папки всегда отдельным блоком, «Файлы» — общий блок.
 */
data class SortCfg(
    val grouped: Boolean = false,
    val natural: Boolean = true,
    val ignoreCase: Boolean = true,
    val order: List<SGroup> = listOf(SGroup.DIR, SGroup.FILE, SGroup.PHOTO, SGroup.VIDEO, SGroup.MUSIC, SGroup.DOC),
    val sep: Set<SGroup> = setOf(SGroup.PHOTO, SGroup.VIDEO, SGroup.MUSIC),
    val key: Map<SGroup, SKey> = SGroup.values().associateWith { SKey.NAME },
    val asc: Map<SGroup, Boolean> = SGroup.values().associateWith { true },
) {
    fun keyOf(g: SGroup): SKey = key[g]?.takeIf { it in SortGroups.keys.getValue(g) } ?: SKey.NAME
    fun ascOf(g: SGroup): Boolean = asc[g] ?: true

    /** Блок, в который попадает элемент с учётом настроек. */
    fun groupOf(isDir: Boolean, ext: String): SGroup {
        val b = SortGroups.base(isDir, ext)
        return if (b == SGroup.DIR || b == SGroup.FILE || grouped || b in sep) b else SGroup.FILE
    }

    fun withKey(g: SGroup, k: SKey, a: Boolean) = copy(key = key + (g to k), asc = asc + (g to a))

    /** Нужны ли для сортировки этой группы теги из файла (читаются лениво в фоне). */
    fun needsMeta(g: SGroup): Boolean = when (g) {
        SGroup.MUSIC -> keyOf(g) in setOf(SKey.TITLE, SKey.ARTIST, SKey.ALBUM, SKey.TRACK, SKey.DURATION)
        SGroup.VIDEO -> keyOf(g) in setOf(SKey.DURATION, SKey.RES)
        SGroup.PHOTO -> keyOf(g) == SKey.TAKEN
        SGroup.DIR -> keyOf(g) == SKey.COUNT
        else -> false
    }

    fun encode(): String = buildString {
        append("g").append(if (grouped) 1 else 0).append("n").append(if (natural) 1 else 0).append("i").append(if (ignoreCase) 1 else 0)
        append("|o=").append(order.joinToString("") { it.ch.toString() })
        append("|s=").append(sep.joinToString("") { it.ch.toString() })
        SGroup.values().forEach { g -> append('|').append(g.ch).append('=').append(keyOf(g).name).append(':').append(if (ascOf(g)) 1 else 0) }
    }

    companion object {
        private fun grp(c: Char) = SGroup.values().firstOrNull { it.ch == c }

        /** Разбор строки из настроек. Любая порча даёт null (тогда берутся значения по умолчанию). */
        fun decode(s: String?): SortCfg? {
            if (s.isNullOrBlank()) return null
            return runCatching {
                val parts = s.split('|')
                val head = parts[0]
                var cfg = SortCfg(
                    grouped = head.substringAfter('g').take(1) == "1",
                    natural = head.substringAfter('n').take(1) == "1",
                    ignoreCase = head.substringAfter('i').take(1) == "1",
                )
                for (p in parts.drop(1)) {
                    val k = p.substringBefore('='); val v = p.substringAfter('=')
                    when {
                        k == "o" -> {
                            val o = v.mapNotNull { grp(it) }.distinct()
                            cfg = cfg.copy(order = o + SGroup.values().filter { it !in o })
                        }
                        k == "s" -> cfg = cfg.copy(sep = v.mapNotNull { grp(it) }.toSet())
                        k.length == 1 -> grp(k[0])?.let { g ->
                            val key = runCatching { SKey.valueOf(v.substringBefore(':')) }.getOrNull()
                            if (key != null && key in SortGroups.keys.getValue(g)) cfg = cfg.withKey(g, key, v.substringAfter(':') != "0")
                        }
                    }
                }
                cfg
            }.getOrNull()
        }
    }
}

/** Что движок спрашивает у приложения: теги, число элементов папки, номер цвета папки. */
class SortCtx(
    val meta: (FmItem) -> SMeta? = { null },
    val count: (FmItem) -> Int = { -1 },
    val color: (FmItem) -> Int = { 0 },
)

object SortEngine {
    /** Сравнение имён: «естественное» (file2 перед file10) и/или без учёта регистра. */
    fun nameCmp(a: String, b: String, natural: Boolean, ignoreCase: Boolean): Int {
        if (!natural) return if (ignoreCase) a.compareTo(b, ignoreCase = true) else a.compareTo(b)
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca in '0'..'9' && cb in '0'..'9') {
                var si = i; while (si < a.length && a[si] == '0') si++
                var sj = j; while (sj < b.length && b[sj] == '0') sj++
                var ei = si; while (ei < a.length && a[ei] in '0'..'9') ei++
                var ej = sj; while (ej < b.length && b[ej] in '0'..'9') ej++
                val la = ei - si; val lb = ej - sj
                if (la != lb) return la.compareTo(lb)
                for (k in 0 until la) { val d = a[si + k].compareTo(b[sj + k]); if (d != 0) return d }
                i = ei; j = ej
            } else {
                val x = if (ignoreCase) ca.lowercaseChar() else ca
                val y = if (ignoreCase) cb.lowercaseChar() else cb
                if (x != y) return x.compareTo(y)
                i++; j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun comparator(cfg: SortCfg, g: SGroup, ctx: SortCtx): Comparator<FmItem> {
        val key = cfg.keyOf(g); val asc = cfg.ascOf(g)
        val byName = Comparator<FmItem> { a, b -> nameCmp(a.name, b.name, cfg.natural, cfg.ignoreCase) }
        fun <T : Comparable<T>> num(sel: (FmItem) -> T?) = Comparator<FmItem> { a, b ->
            val x = sel(a); val y = sel(b)
            when { x == null && y == null -> 0; x == null -> 1; y == null -> -1; asc -> x.compareTo(y); else -> y.compareTo(x) }
        }
        fun str(sel: (FmItem) -> String?) = Comparator<FmItem> { a, b ->
            val x = sel(a); val y = sel(b)
            when {
                x == null && y == null -> 0; x == null -> 1; y == null -> -1
                else -> { val c = nameCmp(x, y, cfg.natural, cfg.ignoreCase); if (asc) c else -c }
            }
        }
        val first: Comparator<FmItem> = when (key) {
            SKey.NAME -> Comparator { a, b -> val c = byName.compare(a, b); if (asc) c else -c }
            SKey.EXT -> str { it.ext }
            SKey.SIZE -> num { it.size }
            SKey.DATE -> num { it.date.takeIf { d -> d.isNotBlank() } }
            SKey.COLOR -> num { ctx.color(it) }
            SKey.COUNT -> num { ctx.count(it).takeIf { n -> n >= 0 } }
            SKey.TITLE -> str { ctx.meta(it)?.title }
            SKey.ARTIST -> str { ctx.meta(it)?.artist }
            SKey.ALBUM -> str { ctx.meta(it)?.album }
            SKey.TRACK -> num { ctx.meta(it)?.track?.takeIf { n -> n > 0 } }
            SKey.DURATION -> num { ctx.meta(it)?.durMs?.takeIf { n -> n >= 0 } }
            SKey.RES -> num { ctx.meta(it)?.let { m -> m.w.toLong() * m.h }?.takeIf { n -> n > 0 } }
            SKey.TAKEN -> num { ctx.meta(it)?.taken?.takeIf { n -> n >= 0 } }
        }
        // при равенстве: имя по возрастанию, затем точное имя (порядок не «дрожит»)
        return if (key == SKey.NAME) Comparator { a, b -> val c = first.compare(a, b); if (c != 0) c else a.name.compareTo(b.name) }
        else Comparator { a, b ->
            var c = first.compare(a, b)
            if (c == 0) c = byName.compare(a, b)
            if (c == 0) c = a.name.compareTo(b.name)
            c
        }
    }

    /** Блоки в порядке показа: пустые отброшены, внутри блока своя сортировка. */
    fun sortGroups(items: List<FmItem>, cfg: SortCfg, ctx: SortCtx = SortCtx()): List<Pair<SGroup, List<FmItem>>> {
        val buckets = HashMap<SGroup, ArrayList<FmItem>>()
        for (it in items) buckets.getOrPut(cfg.groupOf(it.isDir, it.ext)) { ArrayList() }.add(it)
        return cfg.order.mapNotNull { g -> buckets[g]?.let { l -> g to l.sortedWith(comparator(cfg, g, ctx)) } }
    }

    fun sort(items: List<FmItem>, cfg: SortCfg, ctx: SortCtx = SortCtx()): List<FmItem> =
        sortGroups(items, cfg, ctx).flatMap { it.second }
}
