package app.adbphon

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Calendar
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Работа с архивами: чтение ZIP (в том числе с паролем AES-256 и ZipCrypto), 7z, TAR (gz, bz2, xz), одиночных gz/bz2/xz, RAR (v4);
 * создание ZIP (AES-256), 7z, TAR.GZ/BZ2/XZ; безопасное изменение ZIP (добавить, удалить, переименовать).
 * Без Android: проверяется обычными тестами на JVM.
 */

class ArcEntry(
    /** Путь внутри архива без ведущего и завершающего «/», без «.» и «..». */
    val path: String, val isDir: Boolean, val size: Long, val csize: Long, val mtime: Long, val crc: Long,
    val encrypted: Boolean, val method: String, val idx: Int,
)

class ArcNeedPassword : Exception("password required")
class ArcBadPassword : Exception("wrong password")
class ArcUnsupported(msg: String) : Exception(msg)

object Arc {
    enum class Kind { ZIP, SEVENZ, TAR, RAR, GZ, BZ2, XZ }

    fun kindOf(name: String): Kind? {
        val n = name.lowercase()
        return when {
            n.endsWith(".tar.gz") || n.endsWith(".tgz") || n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") || n.endsWith(".tar.xz") || n.endsWith(".txz") || n.endsWith(".tar") -> Kind.TAR
            n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".aar") || n.endsWith(".apk") || n.endsWith(".apks") || n.endsWith(".xapk") || n.endsWith(".epub") || n.endsWith(".docx") || n.endsWith(".xlsx") || n.endsWith(".pptx") || n.endsWith(".odt") || n.endsWith(".ods") || n.endsWith(".odp") -> Kind.ZIP
            n.endsWith(".7z") -> Kind.SEVENZ
            n.endsWith(".rar") -> Kind.RAR
            n.endsWith(".gz") -> Kind.GZ
            n.endsWith(".bz2") -> Kind.BZ2
            n.endsWith(".xz") -> Kind.XZ
            else -> null
        }
    }

    /** Расширения, которые по нажатию открываются как папка (документы и APK открываются своими способами). */
    fun opensAsFolder(name: String): Boolean {
        val n = name.lowercase()
        val k = kindOf(n) ?: return false
        return when (k) {
            Kind.ZIP -> n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".aar") || n.endsWith(".apks") || n.endsWith(".xapk")
            else -> true
        }
    }

    /** Безопасный путь: «\» как «/», без пустых, «.» и «..» сегментов (защита от выхода за папку назначения, zip-slip). */
    fun clean(name: String): String = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")

    fun isValidUtf8(b: ByteArray): Boolean = runCatching {
        Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b)); true
    }.getOrDefault(false)

    private fun cyrScore(s: String): Int = s.count { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' || it in 'і'..'ї' } * 2 - s.count { it == '�' || it in '\u0080'..'\u009F' || it in '─'..'╬' || it in 'Ђ'..'џ' && it !in 'А'..'я' }

    /** Кодировка имён без флага UTF-8: UTF-8, если все имена корректны, иначе CP866 или CP1251 по числу русских букв. */
    fun guessCharset(rawNames: List<ByteArray>): String {
        val non = rawNames.filter { n -> n.any { it < 0 } }
        if (non.isEmpty() || non.all { isValidUtf8(it) }) return "UTF-8"
        val c866 = Charset.forName("IBM866"); val c1251 = Charset.forName("windows-1251")
        val s866 = non.sumOf { cyrScore(String(it, c866)) }; val s1251 = non.sumOf { cyrScore(String(it, c1251)) }
        return if (s866 >= s1251) "IBM866" else "windows-1251"
    }

    fun open(file: File, charset: String? = null, password: CharArray? = null): ArcReader = when (kindOf(file.name) ?: sniff(file)) {
        Kind.ZIP -> ZipArc(file, charset, password)
        Kind.SEVENZ -> SevenZArc(file, password)
        Kind.TAR -> TarArc(file)
        Kind.RAR -> if (isRar5(file)) Rar5Arc(file, password) else RarArc(file, password)
        Kind.GZ, Kind.BZ2, Kind.XZ -> SingleArc(file)
        null -> throw ArcUnsupported("unknown archive format")
    }

    /** Сигнатура RAR5: «Rar!, 0x1A 0x07 0x01 0x00» (у RAR4 седьмой байт равен 0). */
    fun isRar5(f: File): Boolean = runCatching {
        val h = ByteArray(8); f.inputStream().use { it.read(h) }
        h[0] == 'R'.code.toByte() && h[1] == 'a'.code.toByte() && h[2] == 'r'.code.toByte() && h[3] == '!'.code.toByte() && h[6] == 1.toByte()
    }.getOrDefault(false)

    private fun sniff(f: File): Kind? = runCatching {
        val h = ByteArray(8); f.inputStream().use { it.read(h) }
        when {
            h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() -> Kind.ZIP
            h[0] == '7'.code.toByte() && h[1] == 'z'.code.toByte() -> Kind.SEVENZ
            h[0] == 'R'.code.toByte() && h[1] == 'a'.code.toByte() && h[2] == 'r'.code.toByte() -> Kind.RAR
            h[0] == 0x1f.toByte() && h[1] == 0x8b.toByte() -> Kind.GZ
            h[0] == 'B'.code.toByte() && h[1] == 'Z'.code.toByte() -> Kind.BZ2
            else -> null
        }
    }.getOrNull()

    fun dosTime(ms: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val y = c.get(Calendar.YEAR)
        if (y < 1980) return (1 shl 21) or (1 shl 16)
        return ((y - 1980) shl 25) or ((c.get(Calendar.MONTH) + 1) shl 21) or (c.get(Calendar.DAY_OF_MONTH) shl 16) or (c.get(Calendar.HOUR_OF_DAY) shl 11) or (c.get(Calendar.MINUTE) shl 5) or (c.get(Calendar.SECOND) / 2)
    }

    /**
     * Извлечь всё содержимое в [dest]. Пути проходят через [clean], выход за пределы [dest] исключён. [policy]: 0 заменять, 1 пропускать, 2 переименовывать.
     * Папки создаются, даты файлов сохраняются.
     */
    fun extractAll(r: ArcReader, dest: File, p: OpProgress, policy: Int = 0, prefix: String = "") {
        dest.mkdirs()
        val base = dest.canonicalFile
        val seenLower = HashSet<String>(); val sessionWritten = HashSet<String>()
        r.forEachData(prefix) { e, ins ->
            if (p.cancelled) throw LocalOps.Cancelled()
            val rel = if (prefix.isEmpty()) e.path else e.path.removePrefix(prefix).trimStart('/')
            if (rel.isEmpty()) return@forEachData
            var f = File(base, rel)
            if (!f.canonicalPath.startsWith(base.path + File.separator) && f.canonicalPath != base.path) throw IOException("blocked path: ${e.path}")
            if (e.isDir) { f.mkdirs(); return@forEachData }
            // имена, различающиеся только регистром, на устройствах с нечувствительной к регистру файловой системой перезаписали бы друг друга
            if (!seenLower.add(f.path.lowercase())) {
                var i = 1; val dot = rel.lastIndexOf('.').takeIf { it > rel.lastIndexOf('/') + 1 }
                do { f = File(base, if (dot != null) rel.substring(0, dot) + " ($i)" + rel.substring(dot) else "$rel ($i)"); i++ } while (!seenLower.add(f.path.lowercase()))
            }
            if (f.exists() && f.path.lowercase() !in sessionWritten) {
                when (policy) {
                    1 -> { ins?.close(); return@forEachData }
                    2 -> { var i = 1; val dot = rel.lastIndexOf('.').takeIf { it > rel.lastIndexOf('/') + 1 }; do { f = File(base, if (dot != null) rel.substring(0, dot) + " ($i)" + rel.substring(dot) else "$rel ($i)"); i++ } while (f.exists()) }
                }
            }
            f.parentFile?.mkdirs()
            val part = File(f.parentFile, f.name + ".fmpart")
            try {
                (ins ?: throw IOException("no data")).use { i ->
                    part.outputStream().use { o ->
                        val buf = ByteArray(128 * 1024)
                        while (true) { if (p.cancelled) throw LocalOps.Cancelled(); val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); p.bytes(n.toLong()) }
                    }
                }
                if (f.exists()) f.delete()
                if (!part.renameTo(f)) throw IOException("rename failed")
                if (e.mtime > 0) f.setLastModified(e.mtime)
            } catch (t: Throwable) { part.delete(); throw t }
            p.item()
        }
    }

    /** Проверка целостности: каждая запись читается целиком, CRC сверяется там, где формат это делает при чтении. Возвращает число проверенных файлов. */
    fun test(r: ArcReader, p: OpProgress): Int {
        var n = 0
        r.forEachData("") { e, ins ->
            if (p.cancelled) throw LocalOps.Cancelled()
            if (!e.isDir) {
                val crc = CRC32(); var got = 0L
                (ins ?: throw IOException("no data")).use { i -> val buf = ByteArray(128 * 1024); while (true) { val k = i.read(buf); if (k < 0) break; crc.update(buf, 0, k); got += k; p.bytes(k.toLong()) } }
                if (e.size >= 0 && got != e.size) throw IOException("size mismatch: ${e.path}")
                if (e.crc > 0 && e.method != "AES" && crc.value != e.crc && r !is TarArc) throw IOException("CRC mismatch: ${e.path}")
                n++
            }
        }
        return n
    }
}

/** Читатель архива: список записей и открытие содержимого. */
abstract class ArcReader : Closeable {
    abstract val entries: List<ArcEntry>
    abstract val kind: Arc.Kind
    var charset: String = "UTF-8"
    /** Поток данных записи (для архивов с прямым доступом). */
    abstract fun open(e: ArcEntry): InputStream
    /** Обход записей с данными (для tar в один проход). [ins] null у папок. */
    open fun forEachData(prefix: String, f: (ArcEntry, InputStream?) -> Unit) {
        for (e in entries) {
            if (prefix.isNotEmpty() && e.path != prefix && !e.path.startsWith("$prefix/")) continue
            if (e.isDir) f(e, null) else f(e, open(e))
        }
    }
    override fun close() {}
    val totalSize: Long get() = entries.filter { !it.isDir }.sumOf { maxOf(it.size, 0L) }
}

// ---------------------------------------------------------------- ZIP

private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

/** Параметры шифрования WinZip AES из дополнительного поля 0x9901. */
class AesInfo(val strength: Int, val method: Int, val version: Int) {
    val keyLen get() = when (strength) { 1 -> 16; 2 -> 24; else -> 32 }
    val saltLen get() = keyLen / 2
}

fun parseAesExtra(extra: ByteArray?): AesInfo? {
    if (extra == null) return null
    var o = 0
    while (o + 4 <= extra.size) {
        val id = le16(extra, o); val len = le16(extra, o + 2)
        if (id == 0x9901 && len >= 7 && o + 4 + 7 <= extra.size) return AesInfo(extra[o + 8].toInt() and 0xff, le16(extra, o + 9), le16(extra, o + 4))
        o += 4 + len
    }
    return null
}

private fun pbkdf2(pass: CharArray, salt: ByteArray, bits: Int): ByteArray =
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(PBEKeySpec(pass, salt, 1000, bits)).encoded

/** Поток расшифровки WinZip AES (CTR с little-endian счётчиком) и проверки HMAC-SHA1 в конце. */
private class AesDecrypt(raw: InputStream, private val aes: AesInfo, pass: CharArray, private val dataLen: Long) : InputStream() {
    private val src = raw
    private val cipher: Cipher
    private val mac = Mac.getInstance("HmacSHA1")
    private val counter = ByteArray(16)
    private var ks = ByteArray(16); private var ksPos = 16
    private var left = dataLen
    private var checked = false

    init {
        val salt = ByteArray(aes.saltLen); readFully(src, salt)
        val pv = ByteArray(2); readFully(src, pv)
        val dk = pbkdf2(pass, salt, (aes.keyLen * 2 + 2) * 8)
        if (dk[aes.keyLen * 2] != pv[0] || dk[aes.keyLen * 2 + 1] != pv[1]) throw ArcBadPassword()
        cipher = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(dk, 0, aes.keyLen, "AES")) }
        mac.init(SecretKeySpec(dk, aes.keyLen, aes.keyLen, "HmacSHA1"))
    }

    private fun nextKs() {
        var i = 0
        while (i < 8) { counter[i] = (counter[i] + 1).toByte(); if (counter[i] != 0.toByte()) break; i++ }
        ks = cipher.update(counter); ksPos = 0
    }

    override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) { verify(); return -1 }
        val n = src.read(b, off, minOf(len.toLong(), left).toInt())
        if (n < 0) throw IOException("truncated")
        mac.update(b, off, n)
        for (i in 0 until n) { if (ksPos == 16) nextKs(); b[off + i] = (b[off + i].toInt() xor ks[ksPos++].toInt()).toByte() }
        left -= n
        return n
    }

    private fun verify() {
        if (checked) return
        checked = true
        val m = ByteArray(10); readFully(src, m)
        val calc = mac.doFinal()
        if (!MessageDigest.isEqual(m, calc.copyOf(10))) throw IOException("AES authentication failed")
    }
}

private fun readFully(i: InputStream, b: ByteArray) {
    var got = 0
    while (got < b.size) { val r = i.read(b, got, b.size - got); if (r < 0) throw IOException("truncated"); got += r }
}

/** Классическое шифрование ZIP (ZipCrypto): только чтение. */
private class ZipCryptoDecrypt(raw: InputStream, pass: ByteArray, check: Int) : FilterInputStream(raw) {
    private val k = intArrayOf(305419896, 591751049, 878082192)
    init {
        for (c in pass) upd(c.toInt() and 0xff)
        val h = ByteArray(12); readFully(raw, h)
        var last = 0
        for (i in 0 until 12) { last = (h[i].toInt() and 0xff) xor dec(); upd(last) }
        if (last != check) throw ArcBadPassword()
    }
    private fun crc(c: Int, b: Int): Int { var x = (c xor b) and 0xff; var v = x; repeat(8) { v = if (v and 1 != 0) (v ushr 1) xor 0xEDB88320.toInt() else v ushr 1 }; return (c ushr 8) xor v }
    private fun upd(b: Int) { k[0] = crc(k[0], b); k[1] = (k[1] + (k[0] and 0xff)) * 134775813 + 1; k[2] = crc(k[2], k[1] ushr 24) }
    private fun dec(): Int { val t = (k[2] or 2) and 0xffff; return ((t * (t xor 1)) ushr 8) and 0xff }
    override fun read(): Int { val c = super.read(); if (c < 0) return -1; val p = c xor dec(); upd(p); return p }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len); if (n <= 0) return n
        for (i in 0 until n) { val p = (b[off + i].toInt() and 0xff) xor dec(); upd(p); b[off + i] = p.toByte() }
        return n
    }
}

class ZipArc(val file: File, forcedCharset: String? = null, private val password: CharArray? = null) : ArcReader() {
    override val kind = Arc.Kind.ZIP
    private val zf: ZipFile
    private val raw = ArrayList<ZipArchiveEntry>()
    override val entries: List<ArcEntry>

    init {
        var cs = forcedCharset ?: "UTF-8"
        var z = ZipFile.builder().setFile(file).setCharset(cs).setUseUnicodeExtraFields(true).get()
        if (forcedCharset == null) {
            val names = z.entries.asSequence().filter { !it.generalPurposeBit.usesUTF8ForNames() }.map { it.rawName }.toList()
            val g = Arc.guessCharset(names)
            if (g != "UTF-8") { z.close(); cs = g; z = ZipFile.builder().setFile(file).setCharset(cs).setUseUnicodeExtraFields(true).get() }
        }
        zf = z; charset = cs
        val list = ArrayList<ArcEntry>()
        for (e in z.entries) {
            val p = Arc.clean(e.name)
            if (p.isEmpty()) continue
            val aes = parseAesExtra(e.extra)
            raw.add(e)
            list.add(ArcEntry(p, e.isDirectory, e.size, e.compressedSize, e.time, e.crc, e.generalPurposeBit.usesEncryption(), if (aes != null) "AES" else methodName(e.method), raw.size - 1))
        }
        entries = list
    }

    private fun methodName(m: Int) = when (m) { 0 -> "STORE"; 8 -> "DEFLATE"; 9 -> "DEFLATE64"; 12 -> "BZIP2"; 14 -> "LZMA"; 99 -> "AES"; else -> "M$m" }

    fun rawEntry(e: ArcEntry): ZipArchiveEntry = raw[e.idx]
    fun rawStream(e: ArcEntry): InputStream = zf.getRawInputStream(raw[e.idx])

    override fun open(e: ArcEntry): InputStream {
        val ze = raw[e.idx]
        if (!ze.generalPurposeBit.usesEncryption()) return zf.getInputStream(ze)
        val pw = password ?: throw ArcNeedPassword()
        val aes = parseAesExtra(ze.extra)
        val rawIn = zf.getRawInputStream(ze)
        val dec: InputStream; val method: Int
        if (aes != null) {
            dec = AesDecrypt(rawIn, aes, pw, ze.compressedSize - aes.saltLen - 2 - 10); method = aes.method
        } else {
            val check = if (ze.generalPurposeBit.usesDataDescriptor()) (Arc.dosTime(ze.time) ushr 8) and 0xff else ((ze.crc ushr 24) and 0xff).toInt()
            dec = ZipCryptoDecrypt(rawIn, String(pw).toByteArray(Charsets.ISO_8859_1), check); method = ze.method
        }
        return when (method) {
            0 -> dec
            8 -> InflaterInputStream(dec, Inflater(true))
            else -> throw ArcUnsupported("compression method $method")
        }
    }

    override fun close() { zf.close() }
}

/** Данные одной записи ZIP для копирования без пересжатия. */
class RawZipEntry(val name: String, val isDir: Boolean, val method: Int, val crc: Long, val csize: Long, val size: Long, val mtime: Long, val encrypted: Boolean, val aesExtra: ByteArray?, val unixMode: Int)

/** Запись ZIP: UTF-8 имена, известные размеры (без дескрипторов), сжатие, AES-256. */
class ZipWriter(out: OutputStream, private val tmpDir: File) : Closeable {
    private class Counting(val o: OutputStream) : OutputStream() {
        var n = 0L
        override fun write(b: Int) { o.write(b); n++ }
        override fun write(b: ByteArray, off: Int, len: Int) { o.write(b, off, len); n += len }
        override fun flush() = o.flush()
        override fun close() = o.close()
    }
    private val out = Counting(out)
    private val cd = ByteArrayOutputStream()
    private var count = 0

    private fun w16(o: OutputStream, v: Int) { o.write(v and 0xff); o.write((v ushr 8) and 0xff) }
    private fun w32(o: OutputStream, v: Long) { w16(o, (v and 0xffff).toInt()); w16(o, ((v ushr 16) and 0xffff).toInt()) }

    private fun aesExtra(method: Int): ByteArray = byteArrayOf(0x01, 0x99.toByte(), 7, 0, 2, 0, 'A'.code.toByte(), 'E'.code.toByte(), 3, (method and 0xff).toByte(), ((method ushr 8) and 0xff).toByte())

    private fun writeEntry(name: String, flags: Int, method: Int, mtime: Long, crc: Long, csize: Long, size: Long, extra: ByteArray, mode: Int, data: InputStream?, p: OpProgress?) {
        if (count >= 65535) throw ArcUnsupported("ZIP64: too many entries")
        if (csize > 0xFFFFFFFEL || size > 0xFFFFFFFEL || out.n > 0xFFFFFFF0L) throw ArcUnsupported("ZIP64: archive too large")
        val nb = name.toByteArray(Charsets.UTF_8)
        val dt = Arc.dosTime(mtime)
        val need = if (method == 99) 51 else 20
        val fl = flags or 0x800
        val offset = out.n
        w32(out, 0x04034b50); w16(out, need); w16(out, fl); w16(out, method); w16(out, dt and 0xffff); w16(out, (dt ushr 16) and 0xffff)
        w32(out, crc); w32(out, csize); w32(out, size); w16(out, nb.size); w16(out, extra.size)
        out.write(nb); out.write(extra)
        if (data != null) {
            val buf = ByteArray(128 * 1024); var left = csize
            while (left > 0) { val n = data.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) throw IOException("truncated source"); out.write(buf, 0, n); left -= n }
        }
        // центральный каталог
        w32(cd, 0x02014b50); w16(cd, (3 shl 8) or 20); w16(cd, need); w16(cd, fl); w16(cd, method); w16(cd, dt and 0xffff); w16(cd, (dt ushr 16) and 0xffff)
        w32(cd, crc); w32(cd, csize); w32(cd, size); w16(cd, nb.size); w16(cd, extra.size); w16(cd, 0); w16(cd, 0); w16(cd, 0)
        w32(cd, (mode.toLong() and 0xffffffffL) shl 16); w32(cd, offset)
        cd.write(nb); cd.write(extra)
        count++
    }

    fun addDir(name: String, mtime: Long) {
        val n = Arc.clean(name); if (n.isEmpty()) return
        writeEntry("$n/", 0, 0, mtime, 0, 0, 0, ByteArray(0), 0x41ED, null, null)
    }

    /** Добавить файл: уровень 0 без сжатия, 1..9 со сжатием; [password] включает AES-256. */
    fun addFile(name: String, input: InputStream, mtime: Long, level: Int, password: CharArray?, p: OpProgress?, mode: Int = 0x81A4) {
        val n = Arc.clean(name); if (n.isEmpty()) return
        val plain = File.createTempFile("zpl", ".tmp", tmpDir)
        var enc: File? = null
        try {
            val crc = CRC32(); var size = 0L
            val method = if (level <= 0) 0 else 8
            val defl = if (method == 8) Deflater(level.coerceIn(1, 9), true) else null
            plain.outputStream().buffered(128 * 1024).use { fo ->
                val o: OutputStream = if (defl != null) DeflaterOutputStream(fo, defl, 128 * 1024) else fo
                val buf = ByteArray(128 * 1024)
                input.use { i ->
                    while (true) {
                        if (p?.cancelled == true) throw LocalOps.Cancelled()
                        val k = i.read(buf); if (k < 0) break
                        crc.update(buf, 0, k); size += k; o.write(buf, 0, k); p?.bytes(k.toLong())
                    }
                }
                if (o !== fo) o.close()
            }
            defl?.end()
            if (password == null) {
                plain.inputStream().buffered().use { writeEntry(n, 0, method, mtime, crc.value, plain.length(), size, ByteArray(0), mode, it, p) }
            } else {
                enc = File.createTempFile("zen", ".tmp", tmpDir)
                encryptTo(plain, enc, password)
                enc.inputStream().buffered().use { writeEntry(n, 1, 99, mtime, 0, enc.length(), size, aesExtra(method), mode, it, p) }
            }
            p?.item()
        } finally { plain.delete(); enc?.delete() }
    }

    private fun encryptTo(src: File, dst: File, pass: CharArray) {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val dk = pbkdf2(pass, salt, (32 * 2 + 2) * 8)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(dk, 0, 32, "AES")) }
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(dk, 32, 32, "HmacSHA1")) }
        val counter = ByteArray(16)
        dst.outputStream().buffered(128 * 1024).use { o ->
            o.write(salt); o.write(dk, 64, 2)
            src.inputStream().buffered(128 * 1024).use { i ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val k = i.read(buf); if (k < 0) break
                    var pos = 0
                    while (pos < k) {
                        var x = 0
                        while (x < 8) { counter[x] = (counter[x] + 1).toByte(); if (counter[x] != 0.toByte()) break; x++ }
                        val ks = cipher.update(counter)
                        val m = minOf(16, k - pos)
                        for (j in 0 until m) buf[pos + j] = (buf[pos + j].toInt() xor ks[j].toInt()).toByte()
                        pos += m
                    }
                    mac.update(buf, 0, k); o.write(buf, 0, k)
                }
            }
            o.write(mac.doFinal(), 0, 10)
        }
    }

    /** Скопировать запись из другого ZIP без распаковки и пересжатия (новое имя при переименовании). */
    fun addRaw(newName: String, e: RawZipEntry, raw: InputStream?) {
        val n = Arc.clean(newName); if (n.isEmpty()) return
        if (e.isDir) { writeEntry("$n/", 0, 0, e.mtime, 0, 0, 0, ByteArray(0), 0x41ED, null, null); return }
        val aes = e.aesExtra != null
        writeEntry(n, if (e.encrypted) 1 else 0, if (aes) 99 else e.method, e.mtime, if (aes) 0 else e.crc, e.csize, e.size, e.aesExtra ?: ByteArray(0), if (e.unixMode != 0) e.unixMode else 0x81A4, raw, null)
    }

    fun finish() {
        val start = out.n
        val b = cd.toByteArray(); out.write(b)
        w32(out, 0x06054b50); w16(out, 0); w16(out, 0); w16(out, count); w16(out, count); w32(out, b.size.toLong()); w32(out, start); w16(out, 0)
        out.flush()
    }

    override fun close() { out.close() }
}

/** Безопасное изменение ZIP: новая копия во временный файл, затем замена (при ошибке исходный архив цел). */
object ZipEdit {
    /** [delete]: пути (файл или папка со всем содержимым); [rename]: из → в (файл или папка); [addFiles]: локальный файл или папка → путь в архиве. */
    fun apply(file: File, tmpDir: File, charset: String?, password: CharArray?, level: Int, delete: Set<String> = emptySet(), rename: Map<String, String> = emptyMap(),
              addFiles: List<Pair<File, String>> = emptyList(), newDirs: List<String> = emptyList(), p: OpProgress? = null) {
        val out = File(file.parentFile, file.name + ".fmnew")
        try {
            ZipArc(file, charset, password).use { src ->
                ZipWriter(out.outputStream().buffered(256 * 1024), tmpDir).use { w ->
                    val seen = HashSet<String>()
                    // добавляемое заменяет одноимённое: старая запись не копируется, иначе новый файл молча терялся бы
                    val killAny = HashSet<String>(); val killFile = HashSet<String>()
                    fun collect(f: File, dst: String) { if (f.isDirectory) { killFile += dst; f.listFiles()?.forEach { collect(it, "$dst/${it.name}") } } else killAny += dst }
                    for ((f, dst) in addFiles) collect(f, Arc.clean(dst))
                    for (e in src.entries) {
                        if (p?.cancelled == true) throw LocalOps.Cancelled()
                        if (delete.any { e.path == it || e.path.startsWith("$it/") }) continue
                        if (killAny.isNotEmpty() || killFile.isNotEmpty()) {
                            if (!e.isDir && e.path in killFile) continue
                            if (e.path in killAny) continue
                            var up = e.path; var hit = false
                            while (up.contains('/')) { up = up.substringBeforeLast('/'); if (up in killAny) { hit = true; break } }
                            if (hit) continue
                        }
                        var name = e.path
                        for ((a, b) in rename) { if (name == a) { name = b; break } else if (name.startsWith("$a/")) { name = b + name.substring(a.length); break } }
                        if (!seen.add(name + if (e.isDir) "/" else "")) continue
                        val ze = src.rawEntry(e)
                        val meta = RawZipEntry(name, e.isDir, ze.method, ze.crc, ze.compressedSize, ze.size, ze.time, ze.generalPurposeBit.usesEncryption(), aesBytes(ze.extra), ze.unixMode)
                        w.addRaw(name, meta, if (e.isDir) null else src.rawStream(e))
                    }
                    for (d in newDirs) if (seen.add(Arc.clean(d) + "/")) w.addDir(d, System.currentTimeMillis())
                    for ((f, dst) in addFiles) addTree(w, f, Arc.clean(dst), level, password, p, seen)
                    w.finish()
                }
            }
            if (!out.renameTo(file)) { file.delete(); if (!out.renameTo(file)) throw IOException("cannot replace archive") }
        } finally { if (out.exists()) out.delete() }
    }

    private fun aesBytes(extra: ByteArray?): ByteArray? {
        if (extra == null) return null
        val a = parseAesExtra(extra) ?: return null
        return byteArrayOf(0x01, 0x99.toByte(), 7, 0, a.version.toByte(), 0, 'A'.code.toByte(), 'E'.code.toByte(), a.strength.toByte(), (a.method and 0xff).toByte(), ((a.method ushr 8) and 0xff).toByte())
    }

    fun addTree(w: ZipWriter, f: File, dst: String, level: Int, password: CharArray?, p: OpProgress?, seen: MutableSet<String>? = null) {
        if (p?.cancelled == true) throw LocalOps.Cancelled()
        if (f.isDirectory) {
            if (seen == null || seen.add("$dst/")) w.addDir(dst, f.lastModified())
            f.listFiles()?.sortedBy { it.name }?.forEach { addTree(w, it, "$dst/${it.name}", level, password, p, seen) }
        } else if (seen == null || seen.add(dst)) {
            w.addFile(dst, f.inputStream(), f.lastModified(), level, password, p)
        }
    }
}

// ---------------------------------------------------------------- 7z, TAR, RAR, одиночные

class SevenZArc(val file: File, password: CharArray?) : ArcReader() {
    override val kind = Arc.Kind.SEVENZ
    private val sz: SevenZFile
    private val raw = ArrayList<SevenZArchiveEntry>()
    override val entries: List<ArcEntry>
    init {
        sz = try {
            if (password != null) SevenZFile.builder().setFile(file).setPassword(password).get() else SevenZFile.builder().setFile(file).get()
        } catch (e: org.apache.commons.compress.PasswordRequiredException) { throw ArcNeedPassword() }
        val l = ArrayList<ArcEntry>()
        for (e in sz.entries) {
            val p = Arc.clean(e.name ?: continue); if (p.isEmpty()) continue
            raw.add(e)
            l.add(ArcEntry(p, e.isDirectory, e.size, -1, if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0L, if (e.hasCrc) e.crcValue else 0L, false, "7Z", raw.size - 1))
        }
        entries = l
    }
    override fun open(e: ArcEntry): InputStream = sz.getInputStream(raw[e.idx])
    override fun close() { sz.close() }
}

class TarArc(val file: File) : ArcReader() {
    override val kind = Arc.Kind.TAR
    override val entries: List<ArcEntry>
    init {
        val l = ArrayList<ArcEntry>()
        TarArchiveInputStream(wrap(file)).use { t ->
            var i = 0
            while (true) {
                val e = t.nextEntry ?: break
                val p = Arc.clean(e.name)
                if (p.isNotEmpty() && (e.isDirectory || e.isFile)) l.add(ArcEntry(p, e.isDirectory, e.size, -1, e.lastModifiedDate.time, 0, false, "TAR", i))
                i++
            }
        }
        entries = l
    }
    private fun wrap(f: File): InputStream {
        val n = f.name.lowercase(); val b = BufferedInputStream(f.inputStream(), 128 * 1024)
        return when { n.endsWith(".gz") || n.endsWith(".tgz") -> GzipCompressorInputStream(b); n.endsWith(".bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") -> BZip2CompressorInputStream(b); n.endsWith(".xz") || n.endsWith(".txz") -> XZCompressorInputStream(b); else -> b }
    }
    /** Для tar данные читаются обходом: один файл = один проход, поэтому [forEachData] обходит архив один раз. */
    override fun open(e: ArcEntry): InputStream {
        val t = TarArchiveInputStream(wrap(file))
        while (true) {
            val x = t.nextEntry ?: break
            if (Arc.clean(x.name) == e.path && !x.isDirectory) return object : FilterInputStream(t) { override fun close() { t.close() } }
        }
        t.close(); throw IOException("entry not found")
    }
    override fun forEachData(prefix: String, f: (ArcEntry, InputStream?) -> Unit) {
        val byPath = HashMap<String, ArcEntry>().also { m -> entries.forEach { m[it.path] = it } }
        TarArchiveInputStream(wrap(file)).use { t ->
            while (true) {
                val x = t.nextEntry ?: break
                val p = Arc.clean(x.name); val e = byPath[p] ?: continue
                if (prefix.isNotEmpty() && p != prefix && !p.startsWith("$prefix/")) continue
                if (e.isDir) f(e, null) else f(e, object : FilterInputStream(t) { override fun close() {} })
            }
        }
    }
}

class RarArc(val file: File, password: CharArray?) : ArcReader() {
    override val kind = Arc.Kind.RAR
    private val ar: com.github.junrar.Archive
    private val heads = ArrayList<com.github.junrar.rarfile.FileHeader>()
    override val entries: List<ArcEntry>
    init {
        ar = try {
            if (password != null) com.github.junrar.Archive(file, String(password)) else com.github.junrar.Archive(file)
        } catch (e: com.github.junrar.exception.UnsupportedRarV5Exception) { throw ArcUnsupported("RAR5 is not supported (only RAR 4)") }
          catch (e: com.github.junrar.exception.RarException) { throw ArcUnsupported("RAR: " + (e.message ?: "error")) }
        val l = ArrayList<ArcEntry>()
        for (h in ar.fileHeaders) {
            val p = Arc.clean(h.fileName ?: continue); if (p.isEmpty()) continue
            heads.add(h)
            l.add(ArcEntry(p, h.isDirectory, h.fullUnpackSize, h.fullPackSize, h.mTime?.time ?: 0L, h.fileCRC.toLong() and 0xffffffffL, h.isEncrypted, "RAR", heads.size - 1))
        }
        entries = l
    }
    override fun open(e: ArcEntry): InputStream {
        val h = heads[e.idx]
        if (h.isEncrypted && ar.isPasswordProtected && false) throw ArcNeedPassword()
        return ar.getInputStream(h)
    }
    override fun close() { ar.close() }
}

/**
 * RAR5: читает родная часть на основе UnRAR (junrar этот формат не умеет). Данные записи распаковываются во временный файл,
 * который удаляется при закрытии потока. Если родная библиотека не загрузилась, выдаётся ArcUnsupported.
 */
class Rar5Arc(val file: File, private val password: CharArray?) : ArcReader() {
    override val kind = Arc.Kind.RAR
    override val entries: List<ArcEntry>
    private val pw: String? = password?.let { String(it) }
    init {
        if (!RarNative.available) throw ArcUnsupported("RAR5 is not supported on this device")
        val raw = RarNative.list(file.absolutePath, pw)
        if (raw.isNotEmpty() && raw[0].startsWith("!")) {
            when (raw[0].substring(1).toIntOrNull()) {
                22 -> throw ArcNeedPassword()
                24 -> throw ArcBadPassword()
                else -> throw ArcUnsupported("RAR5: error " + raw[0].substring(1))
            }
        }
        val l = ArrayList<ArcEntry>()
        raw.forEachIndexed { i, line ->
            val f = line.split('\u0001'); if (f.size < 6) return@forEachIndexed
            val p = Arc.clean(f[0]); if (p.isEmpty()) return@forEachIndexed
            val flags = f[5].toLongOrNull() ?: 0L
            l.add(ArcEntry(p, flags and 0x20L != 0L, f[1].toLongOrNull() ?: -1L, f[2].toLongOrNull() ?: -1L, dosToMs(f[3].toLongOrNull() ?: 0L), f[4].toLongOrNull() ?: 0L, flags and 0x04L != 0L, "RAR5", i))
        }
        entries = l
    }
    private fun dosToMs(t: Long): Long {
        if (t == 0L) return 0L
        val c = Calendar.getInstance()
        c.set(((t shr 25) and 0x7f).toInt() + 1980, (((t shr 21) and 0xf).toInt() - 1).coerceIn(0, 11), ((t shr 16) and 0x1f).toInt().coerceAtLeast(1), ((t shr 11) and 0x1f).toInt(), ((t shr 5) and 0x3f).toInt(), ((t and 0x1f) * 2).toInt())
        return c.timeInMillis
    }
    override fun open(e: ArcEntry): InputStream {
        if (e.encrypted && pw == null) throw ArcNeedPassword()
        val tmp = File.createTempFile("rar5_", ".bin")
        val rc = RarNative.extract(file.absolutePath, e.idx, tmp.absolutePath, pw)
        if (rc != 0) { tmp.delete(); throw when (rc) { 22 -> ArcNeedPassword(); 24 -> ArcBadPassword(); else -> IOException("RAR5: $rc") } }
        return object : FilterInputStream(tmp.inputStream().buffered(256 * 1024)) { override fun close() { super.close(); tmp.delete() } }
    }
    override fun close() {}
}

/** Одиночный сжатый файл (gz, bz2, xz): одна запись с именем без расширения. */
class SingleArc(val file: File) : ArcReader() {
    override val kind: Arc.Kind = Arc.kindOf(file.name) ?: Arc.Kind.GZ
    override val entries: List<ArcEntry> = listOf(ArcEntry(file.name.substringBeforeLast('.'), false, -1, file.length(), file.lastModified(), 0, false, kind.name, 0))
    override fun open(e: ArcEntry): InputStream {
        val b = BufferedInputStream(file.inputStream(), 128 * 1024)
        return when (kind) { Arc.Kind.BZ2 -> BZip2CompressorInputStream(b); Arc.Kind.XZ -> XZCompressorInputStream(b); else -> GzipCompressorInputStream(b) }
    }
}

// ---------------------------------------------------------------- создание

object ArcCreate {
    /** Форматы создания: «zip», «7z», «tar.gz», «tar.bz2», «tar.xz», «tar». */
    val formats = listOf("zip", "7z", "tar.gz", "tar.bz2", "tar.xz", "tar")

    /** Создать архив [out] из файлов и папок [src]. [level]: 0..9 (0 без сжатия); [password] только для zip (AES-256). */
    fun create(format: String, out: File, src: List<File>, level: Int, password: CharArray?, tmpDir: File, p: OpProgress) {
        val part = File(out.parentFile, out.name + ".fmpart")
        try {
            when (format) {
                "zip" -> ZipWriter(part.outputStream().buffered(256 * 1024), tmpDir).use { w ->
                    val seen = HashSet<String>()
                    src.forEach { ZipEdit.addTree(w, it, it.name, level, password, p, seen) }
                    w.finish()
                }
                "7z" -> {
                    if (password != null) throw ArcUnsupported("7z with password: not supported when creating")
                    SevenZOutputFile(part).use { o ->
                        if (level == 0) o.setContentCompression(SevenZMethod.COPY) else o.setContentMethods(listOf(SevenZMethodConfiguration(SevenZMethod.LZMA2, org.tukaani.xz.LZMA2Options(level.coerceIn(1, 9)))))
                        fun add(f: File, name: String) {
                            if (p.cancelled) throw LocalOps.Cancelled()
                            val e = o.createArchiveEntry(f, name)
                            o.putArchiveEntry(e)
                            if (f.isFile) f.inputStream().use { i -> val buf = ByteArray(128 * 1024); while (true) { if (p.cancelled) throw LocalOps.Cancelled(); val k = i.read(buf); if (k < 0) break; o.write(buf, 0, k); p.bytes(k.toLong()) } }
                            o.closeArchiveEntry(); p.item()
                            if (f.isDirectory) f.listFiles()?.sortedBy { it.name }?.forEach { add(it, "$name/${it.name}") }
                        }
                        src.forEach { add(it, it.name) }
                        o.finish()
                    }
                }
                else -> {
                    if (password != null) throw ArcUnsupported("password: only for zip")
                    val raw = part.outputStream().buffered(256 * 1024)
                    val comp: OutputStream = when (format) {
                        "tar.gz" -> GzipCompressorOutputStream(raw, GzipParameters().apply { compressionLevel = level.coerceIn(1, 9) })
                        "tar.bz2" -> BZip2CompressorOutputStream(raw)
                        "tar.xz" -> XZCompressorOutputStream(raw, level.coerceIn(0, 9))
                        else -> raw
                    }
                    TarArchiveOutputStream(comp).use { t ->
                        t.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX); t.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                        fun add(f: File, name: String) {
                            if (p.cancelled) throw LocalOps.Cancelled()
                            t.putArchiveEntry(TarArchiveEntry(f, name))
                            if (f.isFile) f.inputStream().use { i -> val buf = ByteArray(128 * 1024); while (true) { if (p.cancelled) throw LocalOps.Cancelled(); val k = i.read(buf); if (k < 0) break; t.write(buf, 0, k); p.bytes(k.toLong()) } }
                            t.closeArchiveEntry(); p.item()
                            if (f.isDirectory) f.listFiles()?.sortedBy { it.name }?.forEach { add(it, "$name/${it.name}") }
                        }
                        src.forEach { add(it, it.name) }
                        t.finish()
                    }
                }
            }
            if (out.exists()) out.delete()
            if (!part.renameTo(out)) throw IOException("rename failed")
        } finally { if (part.exists()) part.delete() }
    }
}
