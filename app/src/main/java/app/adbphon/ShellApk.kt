package app.adbphon

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * 17.0 (код 81): части APK читаются через оболочку устройства (ADB, помощник, root), файл целиком НЕ копируется и размера у него нет ограничения.
 * Причина: на Android 16 / realme UI 7 приложению может быть закрыт прямой доступ к файлу на карте памяти (canRead=false), а прежняя копия
 * через ADB работала только до 150 МБ (OsmAnd 243 МБ значка не получал).
 *
 * Способ 1: `unzip -p файл запись` (toybox, есть на Android 15; читает только нужную запись через центральный каталог zip).
 * Способ 2 (если unzip нет или не работает): свой разбор zip по смещениям: хвост файла (`tail -c`) -> конец центрального каталога ->
 * центральный каталог (`dd`) -> нужная запись (`dd`) -> распаковка Inflater. Читается столько байт, сколько занимает запись.
 *
 * Время: на манифест и мелкие файлы значка [budgetMs] (4 с), таблица resources.arsc (у больших приложений десятки МБ) читается отдельно, до 12 с,
 * и в этот бюджет не входит. Чистый Kotlin без Android: проверяется модульным тестом.
 */
class ShellApk(private val path: String, private val sh: Sh, private val budgetMs: Long = 4000L, noUnzip: Boolean = false) : ApkIconRes.ApkSrc {
    class Out(val data: ByteArray?, val err: String)

    interface Sh {
        /** Название канала для журнала: root, помощник, ADB. */
        val channel: String
        /**
         * Канал отдаёт большие ответы целиком и закрывает поток вовремя (помощник, root). У связи ADB внутри приложения это не так: ответ бывает обрезан или поток не закрывается
         * (на A14: из 30 МБ приходило 18 МБ, из 300 КБ 139 КБ), поэтому там читается свой разбор zip: точные длины, дочитывание, проверка CRC.
         */
        val reliable: Boolean
        /**
         * Выполняет команду оболочки, возвращает её стандартный вывод как есть (байты). Больше [max] байт или сбой канала: data == null, причина в err.
         * [expect] >= 0: вывод ровно столько байт, ответ считается полученным, как только они пришли (не ждать закрытия потока); пришло меньше: возвращается то, что пришло.
         */
        fun run(cmd: String, max: Int, timeoutMs: Long, expect: Int = -1): Out
    }

    override var err: String = ""
        private set

    /** Каким способом читается: «unzip -p» или «dd+zip». */
    var mode = if (noUnzip || !sh.reliable) "dd+zip" else "unzip -p"
        private set
    /** Почему вместо unzip -p пошли своим разбором (пусто, если unzip сработал). */
    var fallbackWhy = ""
        private set
    var calls = 0
        private set
    var bytes = 0L
        private set
    /** Замечания канала к принятым ответам (например, поток оборван досрочно). */
    private var chanNote = ""
    private var unzipConfirmed = false
    private var useRange = noUnzip || !sh.reliable
    private var t0 = System.currentTimeMillis()
    private var deadline = t0 + budgetMs
    private val memo = HashMap<String, ByteArray>()

    fun stats(): String = "$mode через ${sh.channel}, вызовов $calls, прочитано $bytes Б, ${System.currentTimeMillis() - t0} мс" + (if (fallbackWhy.isEmpty()) "" else "; $fallbackWhy") + (if (chanNote.isEmpty()) "" else "; канал: $chanNote")

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private fun glob(n: String) = n.replace(Regex("""([\[\]*?\\])""")) { "\\" + it.value }

    private fun timeoutFor(name: String): Long {
        val rem = deadline - System.currentTimeMillis()
        return if (name == "resources.arsc") maxOf(rem, 12000L) else rem
    }

    private fun run(cmd: String, max: Int, tmo: Long, expect: Int = -1): Out {
        calls++
        val r = sh.run(cmd, max, tmo, expect)
        bytes += r.data?.size ?: 0
        if (r.data != null && r.err.isNotEmpty() && chanNote.length < 300) chanNote += (if (chanNote.isEmpty()) "" else " | ") + r.err
        return r
    }

    override fun read(name: String, max: Int): ByteArray? {
        memo[name]?.let { if (it.size <= max) return it }
        err = ""
        val tmo = timeoutFor(name)
        if (tmo < 300L) { err = "$name: бюджет времени ${budgetMs} мс исчерпан"; return null }
        val start = System.currentTimeMillis()
        val r = try { readInner(name, max, tmo) } finally {
            // время чтения таблицы ресурсов в общий бюджет не входит
            if (name == "resources.arsc") deadline += System.currentTimeMillis() - start
        }
        if (r != null && r.size <= 1_000_000) memo[name] = r
        return r
    }

    private fun readInner(name: String, max: Int, tmo: Long): ByteArray? {
        if (!useRange) {
            val r = run("unzip -p ${q(path)} ${q(glob(name))} 2>/dev/null | head -c ${max + 1}", max + 1, tmo)
            val d = r.data
            if (d == null) { err = "$name: ${r.err}"; return null }   // сбой канала или тайм-аут: другой способ тут не поможет
            if (d.size > max) { err = "$name: больше лимита $max Б"; return null }
            if (d.isNotEmpty()) {
                val bad = incomplete(name, d)
                if (bad == null) { unzipConfirmed = true; return d }
                // канал отдал начало записи и закрылся (у связи ADB бывает на больших ответах): неполные данные не годятся, дальше свой разбор zip: он знает точный размер
                fallbackWhy = "unzip -p вернул неполные данные ($bad)"
                useRange = true; mode = "dd+zip"
            } else if (unzipConfirmed) return null   // unzip работает, записи в архиве просто нет (или она пустая)
            else {
                // первое чтение (манифест) пусто: unzip нет или не умеет -p; узнаём причину и переходим на свой разбор
                val diag = run("(ls -l ${q(path)}; unzip -p ${q(path)} ${q("AndroidManifest.xml")} 2>&1 >/dev/null) 2>&1 | head -c 300", 400, 1500L.coerceAtMost(maxOf(tmo, 600L)))
                val why = diag.data?.toString(Charsets.UTF_8)?.trim()?.replace('\n', '|').orEmpty().ifEmpty { diag.err }
                fallbackWhy = "unzip -p не вернул данных ($why)"
                useRange = true; mode = "dd+zip"
            }
        }
        return readRange(name, max, tmo)
    }

    /**
     * Проверка полноты записи, пришедшей по каналу: у двоичного XML и таблицы ресурсов размер записан в заголовке, у PNG в конце блок IEND, у WEBP размер в RIFF.
     * null: полная (или признаков нет); иначе описание, чего не хватает.
     */
    private fun incomplete(name: String, d: ByteArray): String? {
        if (d.size >= 8 && ((name.endsWith(".xml") && d[0].toInt() == 0x03 && d[1].toInt() == 0) || (name.endsWith(".arsc") && d[0].toInt() == 0x02 && d[1].toInt() == 0))) {
            val want = u32(d, 4)
            if (want in 8..Int.MAX_VALUE.toLong() && d.size < want) return "$name: получено ${d.size} из $want Б"
        }
        if (name.endsWith(".png", true) && d.size >= 12 && d[0] == 0x89.toByte() && d[1] == 0x50.toByte()) {
            val t = d.size - 12
            if (!(d[t + 4] == 'I'.code.toByte() && d[t + 5] == 'E'.code.toByte() && d[t + 6] == 'N'.code.toByte() && d[t + 7] == 'D'.code.toByte())) return "$name: PNG без конца"
        }
        if (d.size >= 12 && d[0] == 'R'.code.toByte() && d[1] == 'I'.code.toByte() && d[2] == 'F'.code.toByte() && d[3] == 'F'.code.toByte()) {
            val want = u32(d, 4) + 8
            if (d.size < want) return "$name: WEBP получено ${d.size} из $want Б"
        }
        return null
    }

    // ---------- свой разбор zip по смещениям ----------

    private class ZE(val method: Int, val csize: Long, val usize: Long, val off: Long, val crc: Long)
    private var ents: HashMap<String, ZE>? = null
    private var entsErr = ""

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
    private fun u64(b: ByteArray, o: Int) = u32(b, o) or (u32(b, o + 4) shl 32)

    private var fileSize = -1L

    /** Размер файла по оболочке (`stat -c %s`); нужен, чтобы знать точную длину каждого чтения. */
    private fun size(tmo: Long): Long {
        if (fileSize >= 0) return fileSize
        for (i in 1..3) {
            // ровно 20 цифр: ответ известной длины, связь ADB не нужно ждать до закрытия потока
            val r = run("printf %020d \$(stat -c %s ${q(path)} 2>/dev/null)", 64, tmo.coerceAtMost(2500L), 20)
            val t = r.data?.toString(Charsets.UTF_8)?.trim().orEmpty()
            val v = t.toLongOrNull()
            if (v != null && v > 0) { fileSize = v; return v }
            if (v == 0L) { err = "файл не найден или недоступен оболочке (stat пуст)"; return -1 }
        }
        if (err.isEmpty()) err = "размер файла не получен"
        return -1
    }

    /** Одно чтение блоками: [want] байт с позиции [off]; меньше, если файл кончился или связь оборвала ответ (вызывающий дочитывает). */
    private fun ddOnce(off: Long, want: Int, tmo: Long): Out {
        val bs = 4096L
        val lead = (off % bs).toInt()
        val blocks = (lead + want + bs - 1) / bs
        val expect = (if (fileSize >= 0) minOf(blocks * bs, fileSize - (off - lead)) else blocks * bs).toInt()
        if (expect <= lead) return Out(ByteArray(0), "")
        val r = run("dd if=${q(path)} bs=4096 skip=${off / bs} count=$blocks 2>/dev/null", expect + 16, tmo, expect)
        val d = r.data ?: return r
        if (d.size <= lead) return Out(ByteArray(0), r.err)
        return Out(d.copyOfRange(lead, minOf(d.size, lead + want)), r.err)
    }

    /**
     * [len] байт с позиции [off] файла: `dd` кусками до 4 МБ; оборванный ответ дочитывается с места обрыва (до 6 неудачных попыток подряд).
     * Меньше [len] возвращается только у конца файла. null: канал не отвечает (причина в err).
     */
    private fun rng(off: Long, len0: Int, tmo: Long): ByteArray? {
        val total = size(tmo)
        val len = if (total >= 0) minOf(len0.toLong(), maxOf(0L, total - off)).toInt() else len0
        if (len <= 0) return ByteArray(0)
        val out = ByteArray(len)
        var n = 0
        var bad = 0
        while (n < len) {
            val left = deadline - System.currentTimeMillis()
            val rem = if (left < 1500L && tmo > left) tmo else left
            val r = ddOnce(off + n, minOf(len - n, 4 * 1024 * 1024), maxOf(rem, 1500L))
            val d = r.data
            if (d == null) { err = r.err; return null }
            if (d.isEmpty()) { if (++bad >= 6) { err = "чтение остановилось на ${off + n} из ${off + len} (${r.err})"; return null }; continue }
            System.arraycopy(d, 0, out, n, d.size)
            n += d.size
            if (d.size < minOf(len - (n - d.size), 4 * 1024 * 1024)) bad++ else bad = 0   // обрыв: дочитываем
            if (bad >= 6) { err = "ответ обрывается: прочитано $n из $len"; return null }
            if (total >= 0 && off + n >= total) break
        }
        return if (n == len) out else out.copyOf(n)
    }

    private fun loadDir(tmo: Long): Boolean {
        if (ents != null) return true
        if (entsErr.isNotEmpty()) { err = entsErr; return false }
        fun fail(m: String): Boolean { entsErr = "zip-разбор: $m"; err = entsErr; return false }
        val total = size(tmo)
        if (total < 0) return fail("размер файла не получен ($err)")
        val tailLen = minOf(total, 70000L).toInt()
        val tail = rng(total - tailLen, tailLen, tmo) ?: return fail("хвост файла не прочитан ($err)")
        if (tail.size < 22) return fail("файл пуст или недоступен оболочке (прочитано ${tail.size} Б)")
        var e = -1
        for (i in tail.size - 22 downTo 0) if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() && tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()) { e = i; break }
        if (e < 0) return fail("нет конца центрального каталога (это не zip или файл обрезан)")
        var count = u16(tail, e + 10).toLong(); var cdSize = u32(tail, e + 12); var cdOff = u32(tail, e + 16)
        if ((cdOff == 0xFFFFFFFFL || count == 0xFFFFL || cdSize == 0xFFFFFFFFL) && e >= 20 &&
            tail[e - 20] == 0x50.toByte() && tail[e - 19] == 0x4b.toByte() && tail[e - 18] == 0x06.toByte() && tail[e - 17] == 0x07.toByte()) {
            val off64 = u64(tail, e - 20 + 8)
            val z = rng(off64, 56, tmo) ?: return fail("zip64: запись не прочитана ($err)")
            if (z.size < 56 || u32(z, 0) != 0x06064b50L) return fail("zip64: неверная запись")
            count = u64(z, 32); cdSize = u64(z, 40); cdOff = u64(z, 48)
        }
        if (cdSize <= 0 || cdSize > 32L * 1024 * 1024) return fail("центральный каталог $cdSize Б (слишком большой)")
        val cd = rng(cdOff, cdSize.toInt(), tmo) ?: return fail("центральный каталог не прочитан ($err)")
        if (cd.size < cdSize) return fail("центральный каталог прочитан не весь (${cd.size} из $cdSize Б)")
        val m = HashMap<String, ZE>()
        var p = 0
        while (p + 46 <= cd.size && u32(cd, p) == 0x02014b50L) {
            val method = u16(cd, p + 10)
            var cs = u32(cd, p + 20); var us = u32(cd, p + 24)
            val nl = u16(cd, p + 28); val el = u16(cd, p + 30); val cl = u16(cd, p + 32)
            var lo = u32(cd, p + 42)
            if (p + 46 + nl + el > cd.size) break
            val name = String(cd, p + 46, nl, Charsets.UTF_8)
            if (cs == 0xFFFFFFFFL || us == 0xFFFFFFFFL || lo == 0xFFFFFFFFL) {
                var x = p + 46 + nl; val xe = x + el
                while (x + 4 <= xe) {
                    val id = u16(cd, x); val sz = u16(cd, x + 2)
                    if (id == 1) {
                        var q = x + 4
                        if (us == 0xFFFFFFFFL && q + 8 <= xe) { us = u64(cd, q); q += 8 }
                        if (cs == 0xFFFFFFFFL && q + 8 <= xe) { cs = u64(cd, q); q += 8 }
                        if (lo == 0xFFFFFFFFL && q + 8 <= xe) { lo = u64(cd, q) }
                        break
                    }
                    x += 4 + sz
                }
            }
            m[name] = ZE(method, cs, us, lo, u32(cd, p + 16))
            p += 46 + nl + el + cl
        }
        if (m.isEmpty()) return fail("центральный каталог пуст (ожидалось $count записей)")
        ents = m
        return true
    }

    private val crcSeen = HashMap<String, Long>()

    private fun readRange(name: String, max: Int, tmo: Long): ByteArray? {
        // сумма не сошлась: данные могли исказиться при передаче (читаем ещё раз); если второй раз получилась та же сумма, в самом архиве неверная запись (моды), данные принимаются
        val r = readRange1(name, max, tmo)
        if (r == null && err.startsWith("$name: контрольная сумма")) {
            val r2 = readRange1(name, max, tmo)
            if (r2 != null) return r2
            if (crcSeen[name] != null && crcSeen[name] == crcSeen[name + "#2"]) { err = ""; return forced[name] }
        }
        return r
    }

    private val forced = HashMap<String, ByteArray>()

    private fun readRange1(name: String, max: Int, tmo: Long): ByteArray? {
        if (!loadDir(tmo)) return null
        val z = ents!![name] ?: run { err = ""; return null }
        if (z.usize > max || z.csize > max.toLong() + max / 4) { err = "$name: ${z.usize} Б больше лимита $max Б"; return null }
        val head = rng(z.off, 4096, tmo) ?: return null
        if (head.size < 30 || u32(head, 0) != 0x04034b50L) { err = "$name: нет заголовка записи по смещению ${z.off}"; return null }
        val ds = 30 + u16(head, 26) + u16(head, 28)
        val comp: ByteArray = if (ds + z.csize <= head.size) head.copyOfRange(ds, (ds + z.csize).toInt())
            else (rng(z.off + ds, z.csize.toInt(), tmo) ?: return null)
        if (comp.size < z.csize) { err = "$name: прочитано ${comp.size} из ${z.csize} Б"; return null }
        val res: ByteArray? = when (z.method) {
            0 -> comp
            8 -> {
                val inf = Inflater(true)
                try {
                    inf.setInput(comp)
                    val out = ByteArrayOutputStream(z.usize.toInt().coerceIn(32, max))
                    val buf = ByteArray(65536)
                    while (!inf.finished()) {
                        val n = inf.inflate(buf)
                        if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                        out.write(buf, 0, n)
                        if (out.size() > max) { err = "$name: больше лимита $max Б"; return null }
                    }
                    if (out.size().toLong() != z.usize) { err = "$name: распаковано ${out.size()} из ${z.usize} Б"; null } else out.toByteArray()
                } catch (e: Exception) { err = "$name: распаковка: ${e.javaClass.simpleName}: ${e.message}"; null } finally { inf.end() }
            }
            else -> { err = "$name: способ сжатия ${z.method} не поддерживается"; null }
        }
        if (res != null) {
            val c = java.util.zip.CRC32(); c.update(res)
            if (c.value != z.crc) {
                if (crcSeen.containsKey(name)) crcSeen[name + "#2"] = c.value else crcSeen[name] = c.value
                forced[name] = res
                err = "$name: контрольная сумма записи не сошлась (данные искажены при передаче)"; return null
            }
        }
        return res
    }
}
