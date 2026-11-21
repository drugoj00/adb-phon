package app.adbphon

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * 16.0 код 79: значок приложения прямо из APK, без PackageManager (Android 16 / realme UI 7: у части APK система возвращает значок «по умолчанию» или не разбирает файл).
 * Читаются двоичный AndroidManifest.xml (ссылка android:icon) и resources.arsc (таблица ресурсов: какие файлы лежат под этим номером при разной плотности экрана),
 * поэтому находятся и значки с обфусцированными именами файлов (res/aB.png, имена без расширения), и адаптивные значки со слоями (картинка, цвет, вектор),
 * и обёртки bitmap/inset/layer-list. Векторные ресурсы (VectorDrawable) разбираются в дерево [VNode], рисует их ApkIconDraw.
 * Чистый Kotlin без классов Android: проверяется модульным тестом на настоящих APK.
 */
object ApkIconRes {
    /**
     * 17.0 (код 81): откуда берутся части архива. Обычный файл (ZipFile) или оболочка устройства (ShellApk: `unzip -p`, без копирования всего файла).
     * [read] отдаёт распакованное содержимое записи; null: записи нет, она больше [max] байт или чтение не вышло (причина в [err]).
     */
    interface ApkSrc {
        fun read(name: String, max: Int): ByteArray?
        /** Причина последней неудачи чтения (для журнала). */
        val err: String
    }

    /** Источник на обычном ZipFile. */
    class ZipSrc(private val z: ZipFile) : ApkSrc {
        override var err: String = ""
            private set
        override fun read(name: String, max: Int): ByteArray? {
            val e = z.getEntry(name) ?: return null
            if (e.size > max) { err = "$name: ${e.size} Б больше лимита $max Б"; return null }
            return z.getInputStream(e).use { it.readBytes() }
        }
    }

    /** Что нарисовать: картинка из APK, цвет, адаптивный значок, набор слоёв, вектор или «не вышло» с причиной. */
    sealed class D {
        class Bmp(val path: String) : D()
        class Col(val argb: Int) : D()
        class Adaptive(val bg: D?, val fg: D?) : D()
        class Layers(val items: List<D>) : D()
        class Vec(val path: String, val root: VNode) : D()
        class Miss(val note: String) : D()
    }

    /** Элемент векторного ресурса: числа, цвета (уже разрешённые по таблице ресурсов), строки (pathData) и дети. */
    class VNode(val name: String, val f: Map<String, Float>, val c: Map<String, Int>, val s: Map<String, String>, val kids: List<VNode>)

    class Result(val root: D?, val trace: String)

    private class Attr(val name: String, val resId: Int, val type: Int, val data: Int, val raw: String?)
    private class XNode(val name: String, val attrs: List<Attr>, val kids: MutableList<XNode> = ArrayList())
    private class Entry(val density: Int, val plain: Boolean, val type: Int, val data: Int, val config: String)

    private const val TYPE_REF = 0x01
    private const val TYPE_STR = 0x03
    private const val TYPE_FLOAT = 0x04
    private const val TYPE_DIM = 0x05
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_BOOL = 0x12

    private fun u16(b: ByteBuffer, o: Int) = b.getShort(o).toInt() and 0xFFFF
    private fun u32(b: ByteBuffer, o: Int) = b.getInt(o)

    /** Лимиты чтения записей: манифест, таблица ресурсов (у больших приложений десятки МБ), остальные файлы значка. */
    private const val MAX_MANIFEST = 8 * 1024 * 1024
    private const val MAX_ARSC = 100 * 1024 * 1024
    private const val MAX_RES = 8 * 1024 * 1024

    private fun readAll(z: ApkSrc, name: String, max: Int = MAX_RES): ByteArray? = z.read(name, max)

    private fun buf(a: ByteArray) = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)

    private fun readPool(b: ByteBuffer, start: Int): List<String> {
        val count = u32(b, start + 8); val flags = u32(b, start + 16); val strStart = u32(b, start + 20)
        val utf8 = flags and 0x100 != 0
        val out = ArrayList<String>(count.coerceAtLeast(0))
        val base = start + strStart
        for (i in 0 until count) {
            var p = base + u32(b, start + 28 + i * 4)
            try {
                if (utf8) {
                    var l = b.get(p).toInt() and 0xFF; p++; if (l and 0x80 != 0) { l = ((l and 0x7F) shl 8) or (b.get(p).toInt() and 0xFF); p++ }
                    var n = b.get(p).toInt() and 0xFF; p++; if (n and 0x80 != 0) { n = ((n and 0x7F) shl 8) or (b.get(p).toInt() and 0xFF); p++ }
                    val bytes = ByteArray(n); for (k in 0 until n) bytes[k] = b.get(p + k)
                    out += String(bytes, Charsets.UTF_8)
                } else {
                    var n = u16(b, p); p += 2; if (n and 0x8000 != 0) { n = ((n and 0x7FFF) shl 16) or u16(b, p); p += 2 }
                    val sb = StringBuilder(n); for (k in 0 until n) sb.append(u16(b, p + k * 2).toChar())
                    out += sb.toString()
                }
            } catch (e: Throwable) { out += "" }
        }
        return out
    }

    /** Двоичный XML (манифест, ресурс-XML): дерево элементов. null: не двоичный XML. */
    private fun parseAxml(data: ByteArray): XNode? {
        val b = buf(data)
        if (data.size < 8 || u16(b, 0) != 0x0003) return null
        var pos = u16(b, 2)
        var pool: List<String> = emptyList()
        var resMap = IntArray(0)
        val stack = ArrayList<XNode>()
        var root: XNode? = null
        while (pos + 8 <= data.size) {
            val type = u16(b, pos); val hs = u16(b, pos + 2); val size = u32(b, pos + 4)
            if (size <= 0 || pos + size > data.size) break
            when (type) {
                0x0001 -> pool = readPool(b, pos)
                0x0180 -> { val n = (size - hs) / 4; resMap = IntArray(n) { u32(b, pos + hs + it * 4) } }
                0x0102 -> {
                    val ext = pos + 16
                    val nameIdx = u32(b, ext + 4)
                    val attrStart = u16(b, ext + 8); val attrSize = u16(b, ext + 10); val attrCount = u16(b, ext + 12)
                    val attrs = ArrayList<Attr>(attrCount)
                    for (i in 0 until attrCount) {
                        val a = ext + attrStart + i * attrSize
                        val n = u32(b, a + 4); val rawIdx = u32(b, a + 8)
                        attrs += Attr(pool.getOrNull(n).orEmpty(), resMap.getOrElse(n) { 0 }, b.get(a + 15).toInt() and 0xFF, u32(b, a + 16), pool.getOrNull(rawIdx))
                    }
                    val node = XNode(pool.getOrNull(nameIdx).orEmpty(), attrs)
                    if (stack.isNotEmpty()) stack.last().kids += node else if (root == null) root = node
                    stack += node
                }
                0x0103 -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            }
            pos += size
        }
        return root
    }

    private fun flat(n: XNode, out: MutableList<XNode> = ArrayList()): List<XNode> { out += n; n.kids.forEach { flat(it, out) }; return out }

    private class Table(val b: ByteBuffer, val pool: List<String>, val packages: List<Pair<Int, Int>>)   // packages: (id пакета, начало блока)

    private fun parseArsc(data: ByteArray): Table? {
        val b = buf(data)
        if (data.size < 12 || u16(b, 0) != 0x0002) return null
        var pos = u16(b, 2)
        var pool: List<String> = emptyList()
        val pk = ArrayList<Pair<Int, Int>>()
        while (pos + 8 <= data.size) {
            val type = u16(b, pos); val size = u32(b, pos + 4)
            if (size <= 0 || pos + size > data.size) break
            if (type == 0x0001 && pool.isEmpty()) pool = readPool(b, pos)
            if (type == 0x0200) pk += u32(b, pos + 8) to pos
            pos += size
        }
        return Table(b, pool, pk)
    }

    /** Все значения ресурса [id] по всем конфигурациям (плотность, обычная ли конфигурация без языка/ночи/направления). */
    private fun entries(t: Table, id: Int): List<Entry> {
        val b = t.b
        val pkgId = id ushr 24; val typeId = (id ushr 16) and 0xFF; val entryId = id and 0xFFFF
        val out = ArrayList<Entry>()
        for ((pid, pstart) in t.packages) {
            if (pid != pkgId) continue
            var pos = pstart + u16(b, pstart + 2)
            val end = pstart + u32(b, pstart + 4)
            while (pos + 8 <= end) {
                val type = u16(b, pos); val hs = u16(b, pos + 2); val size = u32(b, pos + 4)
                if (size <= 0 || pos + size > end) break
                if (type == 0x0201 && (b.get(pos + 8).toInt() and 0xFF) == typeId) {
                    val flags = b.get(pos + 9).toInt() and 0xFF
                    val count = u32(b, pos + 12); val entriesStart = u32(b, pos + 16)
                    val cfg = pos + 20
                    val cfgSize = u32(b, cfg)
                    val density = if (cfgSize >= 16) u16(b, cfg + 14) else 0
                    // «обычная» конфигурация: без mcc, mnc, языка, страны, ориентации, сенсора, клавиатуры, ночного режима и направления письма (плотность и версия Android допускаются)
                    var plain = true
                    for (k in 4 until minOf(cfgSize, 14)) if (b.get(cfg + k).toInt() != 0) plain = false
                    if (cfgSize >= 18) for (k in 16 until 18) if (b.get(cfg + k).toInt() != 0) plain = false
                    if (cfgSize >= 30 && (b.get(cfg + 29).toInt() and 0x30) != 0) plain = false
                    if (cfgSize >= 29 && (b.get(cfg + 28).toInt() and 0xC0) != 0) plain = false
                    val sparse = flags and 0x01 != 0; val off16 = flags and 0x02 != 0
                    var off = -1
                    val tbl = pos + hs
                    if (sparse) {
                        for (i in 0 until count) if (u16(b, tbl + i * 4) == entryId) { off = u16(b, tbl + i * 4 + 2) * 4 }
                    } else if (entryId < count) {
                        if (off16) { val v = u16(b, tbl + entryId * 2); if (v != 0xFFFF) off = v * 4 }
                        else { val v = u32(b, tbl + entryId * 4); if (v != -1) off = v }
                    }
                    if (off >= 0) {
                        val e = pos + entriesStart + off
                        val eFlags = u16(b, e + 2)
                        val cfgName = "d=$density" + (if (plain) "" else "*")
                        if (eFlags and 0x01 != 0) {
                            // составное значение (bag): значка в нём нет
                        } else if (eFlags and 0x08 != 0) {
                            // компактная запись: тип в старшей половине флагов, данные в поле ключа
                            out += Entry(density, plain, (eFlags ushr 8) and 0xFF, u32(b, e + 4), cfgName)
                        } else {
                            out += Entry(density, plain, b.get(e + 11).toInt() and 0xFF, u32(b, e + 12), cfgName)
                        }
                    }
                }
                pos += size
            }
        }
        return out
    }

    private fun isXml(p: String) = p.endsWith(".xml", true)

    private class Ctx(val t: Table, val z: ApkSrc, val tr: StringBuilder)

    /** Файлы-картинки ресурса (всё, что не .xml: имена бывают без расширения) и XML-файлы, ссылки раскрываются до 4 уровней. */
    private fun files(c: Ctx, id: Int, depth: Int = 0): List<Pair<Entry, String>> {
        val out = ArrayList<Pair<Entry, String>>()
        for (e in entries(c.t, id)) {
            if (e.type == TYPE_STR) c.t.pool.getOrNull(e.data)?.let { out += e to it }
            else if (e.type == TYPE_REF && depth < 4 && e.data != id) out += files(c, e.data, depth + 1)
        }
        return out
    }

    private fun bestBitmap(c: List<Pair<Entry, String>>): String? {
        val bm = c.filter { !isXml(it.second) }
        val pl = bm.filter { it.first.plain }.ifEmpty { bm }
        if (pl.isEmpty()) return null
        fun d(e: Entry) = when (e.density) { 0 -> 160; 0xFFFF -> 320; else -> e.density }
        val hi = pl.filter { d(it.first) >= 320 }
        return (if (hi.isNotEmpty()) hi.minByOrNull { d(it.first) } else pl.maxByOrNull { d(it.first) })?.second
    }

    /** Цвет ресурса: прямое значение, ссылка на цвет, градиент (берётся начальный цвет) или список состояний (первый цвет). null: не вышло. */
    private fun colorOfAttr(c: Ctx, a: Attr, depth: Int = 0): Int? {
        if (a.type in 0x1c..0x1f) return a.data
        if (a.type != TYPE_REF || depth > 4) return null
        // цвета самой системы (android:color/white, black, transparent): их в таблице APK нет
        when (a.data) { 0x0106000b -> return 0xFFFFFFFF.toInt(); 0x0106000c -> return 0xFF000000.toInt(); 0x0106000d -> return 0 }
        for (e in entries(c.t, a.data)) {
            if (e.type in 0x1c..0x1f) return e.data
            if (e.type == TYPE_REF && e.data != a.data) colorOfAttr(c, Attr("", 0, TYPE_REF, e.data, null), depth + 1)?.let { return it }
            if (e.type == TYPE_STR) {
                val p = c.t.pool.getOrNull(e.data) ?: continue
                if (!isXml(p)) continue
                val root = readAll(c.z, p)?.let { parseAxml(it) } ?: continue
                for (n in flat(root)) for (at in n.attrs) if ((at.name == "startColor" || at.name == "color" || at.resId == 0x0101019d || at.resId == 0x010101a5) && at.type in 0x1c..0x1f) return at.data
            }
        }
        return null
    }

    private fun toVNode(c: Ctx, n: XNode): VNode {
        val f = HashMap<String, Float>(); val col = HashMap<String, Int>(); val s = HashMap<String, String>()
        for (a in n.attrs) {
            val name = a.name.ifEmpty { when (a.resId) { 0x01010405 -> "pathData"; 0x01010404 -> "fillColor"; else -> "" } }
            if (name.isEmpty()) continue
            when {
                name.endsWith("Color") || name == "tint" -> colorOfAttr(c, a)?.let { col[name] = it }
                a.type == TYPE_STR && a.raw != null -> s[name] = a.raw
                a.type == TYPE_FLOAT -> f[name] = java.lang.Float.intBitsToFloat(a.data)
                a.type == TYPE_DIM -> f[name] = complexToFloat(a.data)
                a.type == TYPE_INT_DEC || a.type == TYPE_INT_HEX -> f[name] = a.data.toFloat()
                a.type == TYPE_BOOL -> f[name] = if (a.data != 0) 1f else 0f
            }
        }
        return VNode(n.name, f, col, s, n.kids.map { toVNode(c, it) })
    }

    private fun complexToFloat(v: Int): Float {
        val mant = (v shr 8).toFloat()   // знаковая мантисса в старших 24 битах
        val radix = (v shr 4) and 3
        val scale = when (radix) { 0 -> 1f / 1f; 1 -> 1f / (1 shl 7); 2 -> 1f / (1 shl 15); else -> 1f / (1 shl 23) }
        return mant * scale
    }

    private fun drawableOfAttr(c: Ctx, a: Attr?, what: String, depth: Int): D? {
        if (a == null) return null
        if (a.type in 0x1c..0x1f) return D.Col(a.data)
        if (a.type != TYPE_REF) return D.Miss("$what: тип значения ${a.type}")
        return drawableOfId(c, a.data, what, depth)
    }

    private fun drawableOfId(c: Ctx, id: Int, what: String, depth: Int): D {
        if (depth > 5) return D.Miss("$what: слишком глубокие ссылки")
        val ps = files(c, id)
        bestBitmap(ps)?.let { return D.Bmp(it) }
        entries(c.t, id).firstOrNull { it.type in 0x1c..0x1f }?.let { return D.Col(it.data) }
        val xmls = ps.map { it.second }.filter { isXml(it) }.distinct()
        if (xmls.isEmpty()) return D.Miss("$what: файла нет (id=0x${Integer.toHexString(id)})")
        // из нескольких XML (anydpi, версии системы) берётся первый, который удалось превратить в рисунок; адаптивный значок предпочтительнее остальных
        var best: D? = null
        for (x in xmls.sortedByDescending { it.contains("anydpi") }) {
            val r = drawableOfXml(c, x, what, depth + 1)
            if (r is D.Miss) { if (best == null) best = r; continue }
            if (r is D.Adaptive) return r
            if (best == null || best is D.Miss) best = r
        }
        return best ?: D.Miss("$what: XML не разобран")
    }

    private fun drawableOfXml(c: Ctx, path: String, what: String, depth: Int): D {
        val root = readAll(c.z, path)?.let { parseAxml(it) } ?: return D.Miss("$what: $path не двоичный XML")
        return drawableOfNode(c, root, path, what, depth)
    }

    private fun drawableOfNode(c: Ctx, root: XNode, path: String, what: String, depth: Int): D {
        if (depth > 6) return D.Miss("$what: слишком глубокая вложенность ($path)")
        fun attr(n: XNode, name: String, rid: Int) = n.attrs.firstOrNull { it.name == name || it.resId == rid }
        // слой или пункт: ссылка в атрибуте drawable или вложенный элемент-рисунок
        fun layerOf(n: XNode, w: String): D? = drawableOfAttr(c, attr(n, "drawable", 0x01010199), w, depth)
            ?: n.kids.firstOrNull()?.let { drawableOfNode(c, it, path, w, depth + 1) }
        return when (root.name) {
            "adaptive-icon" -> D.Adaptive(
                root.kids.firstOrNull { it.name == "background" }?.let { layerOf(it, "фон") },
                root.kids.firstOrNull { it.name == "foreground" }?.let { layerOf(it, "передний слой") })
            "bitmap" -> drawableOfAttr(c, attr(root, "src", 0x01010119), "bitmap", depth) ?: D.Miss("$what: bitmap без src ($path)")
            "inset" -> layerOf(root, "inset") ?: D.Miss("$what: inset пустой ($path)")
            "layer-list" -> {
                val items = root.kids.filter { it.name == "item" }.mapNotNull { layerOf(it, "слой") }
                if (items.isEmpty()) D.Miss("$what: layer-list без слоёв ($path)") else D.Layers(items)
            }
            "vector" -> D.Vec(path, toVNode(c, root))
            // простая фигура: сплошная заливка или начальный цвет градиента на весь слой
            "shape" -> (root.kids.firstOrNull { it.name == "solid" }?.attrs?.firstOrNull { it.name == "color" || it.resId == 0x010101a5 }
                ?: root.kids.firstOrNull { it.name == "gradient" }?.attrs?.firstOrNull { it.name == "startColor" || it.resId == 0x0101019d })
                ?.let { colorOfAttr(c, it) }?.let { D.Col(it) } ?: D.Miss("$what: <shape> без заливки ($path)")
            else -> D.Miss("$what: <${root.name}> не рисуется ($path)")
        }
    }

    /** Краткое описание рисунка для журнала. */
    fun describe(d: D?): String = when (d) {
        null -> "нет"
        is D.Bmp -> "картинка ${d.path}"
        is D.Col -> "цвет #%08x".format(d.argb)
        is D.Adaptive -> "адаптивный(фон: ${describe(d.bg)}; передний: ${describe(d.fg)})"
        is D.Layers -> "слои(" + d.items.joinToString("; ") { describe(it) } + ")"
        is D.Vec -> "вектор ${d.path}"
        is D.Miss -> "не вышло: ${d.note}"
    }

    /** Можно ли нарисовать: есть хоть один настоящий слой. */
    fun drawable(d: D?): Boolean = when (d) {
        null, is D.Miss -> false
        is D.Adaptive -> drawable(d.bg) || drawable(d.fg)
        is D.Layers -> d.items.any { drawable(it) }
        else -> true
    }

    /** 17.0 (код 83): корневой элемент манифеста: пакет и имя части ([split] null у основного APK, у части набора это её имя, например config.ru). */
    class ManifestHead(val pkg: String, val split: String?)

    /** Пакет и имя части из двоичного манифеста файла (без PackageManager: части набора система сама не разбирает). null: файл не APK или манифест не читается. */
    fun manifestHead(path: String): ManifestHead? = runCatching {
        ZipFile(path).use { z ->
            val man = ZipSrc(z).read("AndroidManifest.xml", MAX_MANIFEST) ?: return@use null
            val root = parseAxml(man) ?: return@use null
            if (root.name != "manifest") return@use null
            val pkg = root.attrs.firstOrNull { it.name == "package" }?.raw ?: return@use null
            ManifestHead(pkg, root.attrs.firstOrNull { it.name == "split" }?.raw?.takeIf { it.isNotBlank() })
        }
    }.getOrNull()

    /** Разбор значка APK. [Result.root] null: в архиве не нашлось, причина в [Result.trace]. */
    fun resolve(z: ZipFile): Result = resolve(ZipSrc(z))

    fun resolve(z: ApkSrc): Result {
        val man = readAll(z, "AndroidManifest.xml", MAX_MANIFEST) ?: return Result(null, "нет AndroidManifest.xml" + z.err.let { if (it.isEmpty()) "" else " ($it)" })
        val root = try { parseAxml(man) } catch (e: Throwable) { return Result(null, "манифест не читается: ${e.javaClass.simpleName}: ${e.message}") } ?: return Result(null, "манифест не двоичный XML")
        fun iconAttr(e: XNode, n: String, rid: Int) = e.attrs.firstOrNull { it.name == n || it.resId == rid }
        val els = flat(root)
        val ids = LinkedHashSet<Int>()
        els.firstOrNull { it.name == "application" }?.let { app ->
            iconAttr(app, "icon", 0x01010002)?.takeIf { it.type == TYPE_REF }?.let { ids += it.data }
            iconAttr(app, "roundIcon", 0x0101052c)?.takeIf { it.type == TYPE_REF }?.let { ids += it.data }
        }
        els.filter { it.name == "activity" || it.name == "activity-alias" }.forEach { e -> iconAttr(e, "icon", 0x01010002)?.takeIf { it.type == TYPE_REF }?.let { ids += it.data } }
        if (ids.isEmpty()) return Result(null, "в манифесте нет ссылки android:icon")
        val tr = StringBuilder("значок в манифесте: ").append(ids.joinToString { "0x" + Integer.toHexString(it) })
        val arsc = readAll(z, "resources.arsc", MAX_ARSC) ?: return Result(null, "$tr; нет resources.arsc" + z.err.let { if (it.isEmpty()) "" else " ($it)" })
        val t = try { parseArsc(arsc) } catch (e: Throwable) { null } ?: return Result(null, "$tr; resources.arsc не читается")
        val c = Ctx(t, z, tr)
        var first: D? = null
        for (id in ids) {
            val d = try { drawableOfId(c, id, "значок", 0) } catch (e: Throwable) { D.Miss("разбор 0x${Integer.toHexString(id)}: ${e.javaClass.simpleName}: ${e.message}") }
            tr.append("; 0x").append(Integer.toHexString(id)).append(" -> ").append(describe(d))
            if (drawable(d)) return Result(d, tr.toString())
            if (first == null) first = d
        }
        return Result(null, tr.toString())
    }
}
