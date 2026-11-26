package app.adbphon

/**
 * 10.0 (fm1): общая логика отметки в дереве «Файлов» (панель и корень «Источники»), без Compose и Android: покрыта юнит-тестами.
 *
 * Ключ элемента: путь через «/» (в панели: имя относительно текущей папки, «Download/фото.jpg»; в корне: идентификатор узла «L|/sdcard/Download»).
 * Правило: отмеченная папка = отмечено ВСЁ её содержимое (вложенное тоже). В множестве хранится только сама папка, её содержимое
 * отдельными ключами не дублируется (нет двойного счёта, операции получают папку целиком).
 * Снятие отметки с вложенного элемента отмеченной папки: папка снимается, а всё остальное её содержимое остаётся отмеченным
 * (на каждом уровне от папки до элемента отмечаются соседи). Папка, внутри которой отмечена только часть, показывается «частично отмеченной».
 * [canon] приводит разные виды ключей одного дерева к общему виду (в корне файлы «F|путь», папки «L|путь»).
 */
object SelTree {
    private val ID: (String) -> String = { it }

    /** [k] лежит внутри папки [a] (на любой глубине). */
    fun under(k: String, a: String, canon: (String) -> String = ID): Boolean {
        val ck = canon(k); val ca = canon(a)
        if (ck == ca || ca.isEmpty()) return false
        val base = if (ca.endsWith("/")) ca else "$ca/"
        return ck.startsWith(base)
    }

    private fun same(a: String, b: String, canon: (String) -> String) = a == b || canon(a) == canon(b)

    /** Элемент отмечен сам или через отмеченную папку выше. */
    fun covered(sel: Collection<String>, k: String, canon: (String) -> String = ID): Boolean = k in sel || sel.any { same(it, k, canon) || under(k, it, canon) }

    /** Папка не отмечена целиком, но внутри неё что-то отмечено. */
    fun partial(sel: Collection<String>, k: String, canon: (String) -> String = ID): Boolean = !covered(sel, k, canon) && sel.any { under(it, k, canon) }

    /** Убрать ключи, которые уже входят в отмеченную папку выше (порядок сохраняется). */
    fun normalize(sel: Collection<String>, canon: (String) -> String = ID): Set<String> {
        val out = LinkedHashSet<String>()
        for (k in sel) if (sel.none { a -> under(k, a, canon) } && out.none { same(it, k, canon) }) out.add(k)
        return out
    }

    /** Сколько отмечено на самом деле (папка с содержимым считается один раз). */
    fun count(sel: Collection<String>, canon: (String) -> String = ID): Int = normalize(sel, canon).size

    /** Отметить: вложенные ключи внутри [k] поглощаются им. */
    fun select(sel: Collection<String>, k: String, canon: (String) -> String = ID): Set<String> {
        if (covered(sel, k, canon)) return LinkedHashSet(sel)
        val out = LinkedHashSet<String>()
        for (x in sel) if (!under(x, k, canon)) out.add(x)
        out.add(k)
        return out
    }

    /**
     * Снять отметку с [k]. Если [k] входит в отмеченную папку выше, папка снимается, а её остальное содержимое остаётся отмеченным:
     * [kids] даёт ключи содержимого раскрытой папки (null, если папка не раскрыта и содержимое неизвестно).
     */
    fun unselect(sel: Collection<String>, k: String, kids: (String) -> List<String>?, canon: (String) -> String = ID): Set<String> {
        val out = LinkedHashSet<String>()
        for (x in sel) if (!same(x, k, canon) && !under(x, k, canon)) out.add(x)
        val anc = out.firstOrNull { under(k, it, canon) } ?: return out
        out.remove(anc)
        var cur = anc
        var guard = 0
        while (guard++ < 64) {
            val ks = kids(cur) ?: return out
            val next = ks.firstOrNull { same(it, k, canon) || under(k, it, canon) } ?: return out
            for (s in ks) if (s != next) out.add(s)
            if (same(next, k, canon)) break
            cur = next
        }
        return out
    }

    /**
     * Нажатие на галочку (26.11.2026, как в X-plore): отмеченная сама строка снимается (вместе с тем, что отмечено отдельно внутри неё);
     * строка внутри отмеченной папки отмечается и сама (папка остаётся отмеченной, счёт и операции не двоятся: [normalize]); остальное отмечается целиком.
     * [kids] осталось для совместимости.
     */
    fun toggle(sel: Collection<String>, k: String, kids: (String) -> List<String>?, canon: (String) -> String = ID): Set<String> {
        if (sel.any { same(it, k, canon) }) { val out = LinkedHashSet<String>(); for (x in sel) if (!same(x, k, canon) && !under(x, k, canon)) out.add(x); return out }
        if (covered(sel, k, canon)) return LinkedHashSet(sel).also { it.add(k) }
        return select(sel, k, canon)
    }

    /**
     * 26.11.2026 (слово владелицы, как в X-plore): нажатия на галочку папки идут по кругу: 1) папка целиком; 2) папка снимается, отмечается всё, что лежит прямо в ней
     * (файлы и подпапки, но не глубже; [files]: ключи элементов прямо в папке); 3) всё внутри снимается. Для вложенной в отмеченную выше папку и для прочих случаев работает [toggle].
     */
    fun cycle(sel: Collection<String>, k: String, files: List<String>, kids: (String) -> List<String>?, canon: (String) -> String = ID): Set<String> {
        if (sel.any { same(it, k, canon) }) {
            val out = LinkedHashSet<String>()
            for (x in sel) if (!same(x, k, canon) && !under(x, k, canon)) out.add(x)
            out.addAll(files)
            return out
        }
        if (partial(sel, k, canon)) {
            val inside = sel.filter { under(it, k, canon) }
            if (files.isNotEmpty() && inside.size == files.size && inside.all { a -> files.any { same(it, a, canon) } }) {
                val out = LinkedHashSet<String>()
                for (x in sel) if (!under(x, k, canon)) out.add(x)
                return out
            }
        }
        return toggle(sel, k, kids, canon)
    }

    /** «Все»: отмечается каждый видимый элемент верхнего уровня (вложенное входит в них). Отмеченное вне [top] сохраняется. */
    fun selectAll(sel: Collection<String>, top: List<String>, canon: (String) -> String = ID): Set<String> = normalize(sel.toList() + top, canon)

    /**
     * «Инверсия» по видимому дереву: у каждого видимого элемента состояние меняется на обратное; свёрнутая папка меняется целиком,
     * раскрытая определяется своим содержимым. Если в раскрытой папке после инверсии отмечено всё, отмечается сама папка.
     */
    fun invert(sel: Collection<String>, top: List<String>, kids: (String) -> List<String>?, canon: (String) -> String = ID): Set<String> {
        fun walk(k: String, inherited: Boolean): Pair<Set<String>, Boolean> {
            val on = inherited || sel.any { same(it, k, canon) }
            val ks = kids(k)
            if (ks.isNullOrEmpty()) return if (on) emptySet<String>() to false else setOf(k) to true
            val acc = LinkedHashSet<String>()
            var all = true
            for (c in ks) { val (s, full) = walk(c, on); acc.addAll(s); if (!full) all = false }
            return if (all) setOf(k) to true else acc to false
        }
        val out = LinkedHashSet<String>()
        // отмеченное вне видимого дерева (другая папка, другая панель) не трогается
        for (x in sel) if (top.none { t -> same(t, x, canon) || under(x, t, canon) }) out.add(x)
        for (t in top) out.addAll(walk(t, false).first)
        return normalize(out, canon)
    }
}

/**
 * 14.0: быстрый индекс отметки для МНОГИХ запросов к одному набору (по запросу на каждую видимую строку). SelTree.covered/partial просматривают весь набор
 * (на каждый запрос O(число отмеченных), с конкатенацией строк на каждый элемент): при сотнях отмеченных и десятках строк каждая отметка пересчитывалась десятки миллисекунд
 * на каждой перекомпоновке. Здесь индекс строится один раз на набор, запрос стоит O(глубина пути). Результаты те же, что у SelTree.covered/partial (в том же порядке проверок).
 */
class SelIndex(sel: Collection<String>, private val canon: (String) -> String = { it }) {
    private val raw = HashSet<String>(sel)
    private val cset = HashSet<String>()
    private val anc = HashSet<String>()

    init {
        for (s in sel) {
            val c = canon(s)
            cset.add(c)
            // предки отмеченного: каждый префикс до «/» (без слэша) и вместе со слэшем (кроме самого ключа)
            var i = c.indexOf('/')
            while (i >= 0) {
                if (i > 0) anc.add(c.substring(0, i))
                if (i + 1 < c.length) anc.add(c.substring(0, i + 1))
                i = c.indexOf('/', i + 1)
            }
        }
    }

    val isEmpty get() = raw.isEmpty()

    /** 26.11.2026 (как в X-plore): отмечена САМА эта строка (не через папку выше): галочка рисуется только у неё. */
    fun direct(k: String): Boolean = raw.isNotEmpty() && (k in raw || canon(k) in cset)

    /** То же, что SelTree.covered(sel, k, canon): отмечен сам или через отмеченную папку выше. */
    fun covered(k: String): Boolean {
        if (raw.isEmpty()) return false
        if (k in raw) return true
        val ck = canon(k)
        if (ck in cset) return true
        var i = ck.indexOf('/')
        while (i >= 0) {
            if (i > 0 && ck.substring(0, i) in cset) return true
            if (i + 1 < ck.length && ck.substring(0, i + 1) in cset) return true
            i = ck.indexOf('/', i + 1)
        }
        return false
    }

    /** То же, что SelTree.partial(sel, k, canon): папка не отмечена целиком, но внутри неё что-то отмечено. */
    fun partial(k: String): Boolean {
        if (raw.isEmpty()) return false
        return !covered(k) && canon(k) in anc
    }
}
