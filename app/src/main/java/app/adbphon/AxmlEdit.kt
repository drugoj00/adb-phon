package app.adbphon

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Правка бинарного AndroidManifest.xml при склейке split-частей в один APK: убирает признаки «это часть» (split, isFeatureSplit, configForSplit, isSplitRequired,
 * requiredSplitTypes, splitTypes) и включает распаковку библиотек при установке (extractNativeLibs=true), чтобы не требовалось выравнивание библиотек в архиве.
 * Остальные байты манифеста не меняются. Чистый код без Android.
 */
object AxmlEdit {
    private val drop = setOf("split", "isFeatureSplit", "configForSplit", "isSplitRequired", "requiredSplitTypes", "splitTypes")
    private fun u16(b: ByteBuffer, p: Int) = b.getShort(p).toInt() and 0xffff

    private fun poolString(b: ByteBuffer, pool: Int, idx: Int): String {
        val hdr = u16(b, pool + 2)
        val n = b.getInt(pool + 8); val flags = b.getInt(pool + 16); val stringsStart = b.getInt(pool + 20)
        if (idx < 0 || idx >= n) return ""
        val p = pool + stringsStart + b.getInt(pool + hdr + idx * 4)
        return if (flags and 0x100 != 0) {
            var q = p
            if (b.get(q).toInt() and 0x80 != 0) q += 2 else q += 1
            var len = b.get(q).toInt() and 0xff; q++
            if (len and 0x80 != 0) { len = ((len and 0x7f) shl 8) or (b.get(q).toInt() and 0xff); q++ }
            val a = ByteArray(len); for (i in 0 until len) a[i] = b.get(q + i); String(a, Charsets.UTF_8)
        } else {
            var c = u16(b, p); var q = p + 2
            if (c and 0x8000 != 0) { c = ((c and 0x7fff) shl 16) or u16(b, p + 2); q += 2 }
            val sb = StringBuilder(); for (i in 0 until c) sb.append(u16(b, q + i * 2).toChar()); sb.toString()
        }
    }

    /** Возвращает изменённый манифест; null, если разобрать не удалось. */
    fun stripSplit(src: ByteArray): ByteArray? = runCatching {
        val b = ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN)
        if (u16(b, 0) != 0x0003) return null
        var p = u16(b, 2)
        var pool = -1
        val out = ByteArrayOutputStream()
        out.write(src, 0, p)
        while (p + 8 <= src.size) {
            val type = u16(b, p); val size = b.getInt(p + 4)
            if (size <= 0) break
            if (type == 0x0001 && pool < 0) pool = p
            if (type == 0x0102 && pool >= 0) {
                val count = u16(b, p + 28)
                val aStart = p + 16 + u16(b, p + 24)
                val aSize = u16(b, p + 26)
                val keep = ArrayList<Int>()
                val newAttrs = ArrayList<ByteArray>()
                val oldToNew = IntArray(count + 1)
                for (i in 0 until count) {
                    val ap = aStart + i * aSize
                    val name = poolString(b, pool, b.getInt(ap + 4))
                    if (name in drop) continue
                    val a = src.copyOfRange(ap, ap + aSize)
                    if (name == "extractNativeLibs") {
                        val ab = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
                        ab.putInt(8, -1); a[15] = 0x12; ab.putInt(16, -1)
                    }
                    keep.add(i); newAttrs.add(a); oldToNew[i + 1] = newAttrs.size
                }
                val head = src.copyOfRange(p, p + 16 + u16(b, p + 24))
                val hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                val newSize = head.size + newAttrs.size * aSize
                hb.putInt(4, newSize)
                hb.putShort(28, newAttrs.size.toShort())
                for (off in intArrayOf(30, 32, 34)) { val v = u16(hb, off); hb.putShort(off, (if (v in 1..count) oldToNew[v] else 0).toShort()) }
                out.write(head); for (a in newAttrs) out.write(a)
            } else out.write(src, p, size)
            p += size
        }
        val res = out.toByteArray()
        ByteBuffer.wrap(res).order(ByteOrder.LITTLE_ENDIAN).putInt(4, res.size)
        res
    }.getOrNull()
}
