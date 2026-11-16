package app.adbphon

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Склейка таблиц ресурсов (resources.arsc) основной части и split-частей (язык, плотность экрана) в одну таблицу.
 * Строки значений и имена ресурсов частей добавляются к строкам основной таблицы, ссылки в добавленных записях пересчитываются;
 * блоки TypeSpec/Type основной таблицы остаются как есть, блоки Type частей (другие конфигурации) добавляются в конец пакета.
 * Чистый код без Android: проверяется юнит-тестом и aapt2 на ПК.
 */
object ArscMerge {
    private const val RES_TABLE = 0x0002
    private const val RES_STRING_POOL = 0x0001
    private const val RES_PACKAGE = 0x0200
    private const val RES_TYPE_SPEC = 0x0202
    private const val RES_TYPE = 0x0201
    private const val NO_ENTRY = -1
    private const val UTF8 = 0x100
    private const val SORTED = 0x1

    private fun bb(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    private fun u16(b: ByteBuffer, p: Int) = b.getShort(p).toInt() and 0xffff
    private fun u32(b: ByteBuffer, p: Int) = b.getInt(p)

    /** Строковый пул: строки как готовые байты (с длиной и завершающим нулём) и стили (списки промежутков). */
    private class Pool(val utf8: Boolean, val strings: MutableList<ByteArray>, val styles: MutableList<IntArray>, val flags: Int)

    private fun readPool(b: ByteBuffer, off: Int): Pool {
        val hdr = u16(b, off + 2); val size = u32(b, off + 4)
        val n = u32(b, off + 8); val sn = u32(b, off + 12); val flags = u32(b, off + 16)
        val sStart = u32(b, off + 20); val stStart = u32(b, off + 24)
        val utf8 = flags and UTF8 != 0
        val offs = IntArray(n) { u32(b, off + hdr + it * 4) }
        val soffs = IntArray(sn) { u32(b, off + hdr + n * 4 + it * 4) }
        val strings = ArrayList<ByteArray>(n)
        for (i in 0 until n) {
            val p = off + sStart + offs[i]
            val len: Int
            if (utf8) {
                var q = p
                var c = b.get(q).toInt() and 0xff; q++
                if (c and 0x80 != 0) q++        // длина в символах: 1 или 2 байта
                c = b.get(q).toInt() and 0xff; q++
                var bytes = c
                if (c and 0x80 != 0) { bytes = ((c and 0x7f) shl 8) or (b.get(q).toInt() and 0xff); q++ }
                len = (q - p) + bytes + 1
            } else {
                val c = u16(b, p)
                val chars = if (c and 0x8000 != 0) (((c and 0x7fff) shl 16) or u16(b, p + 2)) else c
                len = (if (c and 0x8000 != 0) 4 else 2) + chars * 2 + 2
            }
            val a = ByteArray(len); for (k in 0 until len) a[k] = b.get(p + k); strings.add(a)
        }
        val styles = ArrayList<IntArray>(sn)
        for (i in 0 until sn) {
            var p = off + stStart + soffs[i]
            val l = ArrayList<Int>()
            while (u32(b, p) != NO_ENTRY) { l.add(u32(b, p)); l.add(u32(b, p + 4)); l.add(u32(b, p + 8)); p += 12 }
            styles.add(l.toIntArray())
        }
        return Pool(utf8, strings, styles, flags)
    }

    private fun writePool(p: Pool): ByteArray {
        val n = p.strings.size; val sn = p.styles.size
        val hdr = 28
        val o = ByteArrayOutputStream()
        val strData = ByteArrayOutputStream(); val offs = IntArray(n)
        for (i in 0 until n) { offs[i] = strData.size(); strData.write(p.strings[i]) }
        while (strData.size() % 4 != 0) strData.write(0)
        val styData = ByteArrayOutputStream(); val soffs = IntArray(sn)
        for (i in 0 until sn) {
            soffs[i] = styData.size()
            for (v in p.styles[i]) styData.write(le32(v))
            styData.write(le32(NO_ENTRY))
        }
        if (sn > 0) { styData.write(le32(NO_ENTRY)); styData.write(le32(NO_ENTRY)) }
        val stringsStart = hdr + n * 4 + sn * 4
        val stylesStart = if (sn > 0) stringsStart + strData.size() else 0
        val total = stringsStart + strData.size() + styData.size()
        o.write(le16(RES_STRING_POOL)); o.write(le16(hdr)); o.write(le32(total))
        o.write(le32(n)); o.write(le32(sn)); o.write(le32((p.flags and SORTED.inv()) or (if (p.utf8) UTF8 else 0))); o.write(le32(stringsStart)); o.write(le32(stylesStart))
        for (v in offs) o.write(le32(v)); for (v in soffs) o.write(le32(v))
        o.write(strData.toByteArray()); o.write(styData.toByteArray())
        return o.toByteArray()
    }

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private class Pkg(val id: Int, val head: ByteArray, val typePool: ByteArray, val keyPool: Pool, val chunks: List<ByteArray>)

    private class Table(val global: Pool, val pkg: Pkg)

    private fun parse(data: ByteArray): Table? = runCatching {
        val b = bb(data)
        if (u16(b, 0) != RES_TABLE) return null
        val thdr = u16(b, 2)
        var p = thdr
        var global: Pool? = null
        var pkg: Pkg? = null
        while (p + 8 <= data.size) {
            val type = u16(b, p); val size = u32(b, p + 4)
            if (size <= 0) break
            when (type) {
                RES_STRING_POOL -> if (global == null) global = readPool(b, p)
                RES_PACKAGE -> if (pkg == null) {
                    val hdr = u16(b, p + 2)
                    val tsOff = u32(b, p + 268); val ksOff = u32(b, p + 276)
                    val typePool = data.copyOfRange(p + tsOff, p + ksOff)
                    val keyPool = readPool(b, p + ksOff)
                    val keySize = u32(b, p + ksOff + 4)
                    var q = p + ksOff + keySize
                    val chunks = ArrayList<ByteArray>()
                    while (q + 8 <= p + size) { val s = u32(b, q + 4); if (s <= 0) break; chunks.add(data.copyOfRange(q, q + s)); q += s }
                    pkg = Pkg(u32(b, p + 8), data.copyOfRange(p, p + hdr), typePool, keyPool, chunks)
                }
            }
            p += size
        }
        if (global == null || pkg == null) null else Table(global, pkg)
    }.getOrNull()

    /** Копия блока Type части с пересчитанными номерами строк: ключи (+[keyOff]) и строковые значения (+[strOff]); null, если формат записей не поддержан. */
    private fun shiftType(chunk: ByteArray, keyOff: Int, strOff: Int): ByteArray? {
        val c = chunk.copyOf(); val b = bb(c)
        val hdr = u16(b, 2); val flags = c[9].toInt() and 0xff
        val count = u32(b, 12); val entriesStart = u32(b, 16)
        val sparse = flags and 1 != 0; val off16 = flags and 2 != 0
        fun patch(entryPos: Int): Boolean {
            val esz = u16(b, entryPos); val ef = u16(b, entryPos + 2)
            if (ef and 8 != 0) return false      // компактные записи не поддержаны
            b.putInt(entryPos + 4, u32(b, entryPos + 4) + keyOff)
            fun value(vp: Int) { if ((c[vp + 3].toInt() and 0xff) == 0x03) b.putInt(vp + 4, u32(b, vp + 4) + strOff) }
            if (ef and 1 != 0) {
                val n = u32(b, entryPos + 12)
                for (i in 0 until n) value(entryPos + 16 + i * 12 + 4)
            } else value(entryPos + esz)
            return true
        }
        for (i in 0 until count) {
            val pos = hdr + i * (if (off16) 2 else 4)
            val rel: Long = when {
                sparse -> (u32(b, pos).toLong() ushr 16) * 4
                off16 -> { val v = u16(b, pos); if (v == 0xffff) -1 else v.toLong() * 4 }
                else -> { val v = u32(b, pos); if (v == NO_ENTRY) -1 else v.toLong() and 0xffffffffL }
            }
            if (rel < 0) continue
            if (!patch(entriesStart + rel.toInt())) return null
        }
        return c
    }

    /** Склеивает основную таблицу с таблицами частей. null: формат не поддержан (вызывающий код сообщает об этом). */
    fun merge(base: ByteArray, splits: List<ByteArray>): ByteArray? {
        val bt = parse(base) ?: return null
        val strings = ArrayList(bt.global.strings); val styles = ArrayList(bt.global.styles)
        val keys = ArrayList(bt.pkg.keyPool.strings)
        val chunks = ArrayList(bt.pkg.chunks)
        val spec = HashSet<Int>(); val typeCfg = HashSet<String>()
        fun cfgKey(ch: ByteArray): String { val id = ch[8].toInt() and 0xff; val hdr = u16(bb(ch), 2); return id.toString() + ":" + ch.copyOfRange(20, hdr).joinToString(",") }
        for (ch in chunks) {
            when (u16(bb(ch), 0)) { RES_TYPE_SPEC -> spec.add(ch[8].toInt() and 0xff); RES_TYPE -> typeCfg.add(cfgKey(ch)) }
        }
        for (s in splits) {
            val st = parse(s) ?: continue
            if (st.pkg.id != bt.pkg.id) continue
            if (st.global.utf8 != bt.global.utf8 || st.pkg.keyPool.utf8 != bt.pkg.keyPool.utf8) return null
            val strOff = strings.size; val keyOff = keys.size
            strings += st.global.strings
            // стиль номер i относится к строке номер i: до добавленных строк недостающие места заполняются пустыми стилями
            if (st.global.styles.isNotEmpty()) {
                while (styles.size < strOff) styles.add(IntArray(0))
                for (sp in st.global.styles) styles.add(IntArray(sp.size) { i -> if (i % 3 == 0) sp[i] + strOff else sp[i] })
            }
            keys += st.pkg.keyPool.strings
            for (ch in st.pkg.chunks) {
                when (u16(bb(ch), 0)) {
                    RES_TYPE_SPEC -> if (spec.add(ch[8].toInt() and 0xff)) chunks.add(ch)
                    RES_TYPE -> {
                        if (!typeCfg.add(cfgKey(ch))) continue
                        chunks.add(shiftType(ch, keyOff, strOff) ?: return null)
                    }
                    else -> {}
                }
            }
        }
        val outGlobal = writePool(Pool(bt.global.utf8, strings, styles, bt.global.flags))
        val outKeys = writePool(Pool(bt.pkg.keyPool.utf8, keys, ArrayList(), bt.pkg.keyPool.flags))
        val ph = bt.pkg.head.copyOf(); val pb = bb(ph)
        val typePoolStart = u32(pb, 268)
        pb.putInt(276, typePoolStart + bt.pkg.typePool.size)
        val body = ByteArrayOutputStream()
        body.write(bt.pkg.typePool); body.write(outKeys); for (ch in chunks) body.write(ch)
        val pkgSize = ph.size + body.size()
        pb.putInt(4, pkgSize)
        val th = base.copyOfRange(0, u16(bb(base), 2)); val tb = bb(th)
        tb.putInt(4, th.size + outGlobal.size + pkgSize)
        val out = ByteArrayOutputStream()
        out.write(th); out.write(outGlobal); out.write(ph); out.write(body.toByteArray())
        return out.toByteArray()
    }
}
