package app.adbphon

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Чистая логика резервных копий (без Android): имена файлов, место хранения, расписание и ротация автокопий, метаданные «Копии с данными»,
 * проверка совместимости при восстановлении на другом устройстве, пользователи из `pm list users`, формат Android Backup (.ab) с шифрованием AES-256.
 * Всё здесь проверяется юнит-тестами на JVM (BkCoreTest).
 */

/** Имена файлов копий. Время в имени: ГГГГММДД-ЧЧММСС. */
object BkNames {
    const val SETTINGS_PREFIX = "ADB-phon-backup-"
    const val AUTO_PREFIX = "ADB-phon-auto-"
    const val SETTINGS_EXT = ".adbpb"
    const val DATA_EXT = ".ab"

    fun stamp(millis: Long, tz: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = tz }.format(Date(millis))

    fun settings(stamp: String) = SETTINGS_PREFIX + stamp + SETTINGS_EXT
    fun autoSettings(stamp: String) = AUTO_PREFIX + stamp + SETTINGS_EXT

    /** «Копия с данными»: имя приложения и дата, для другого пользователя пометка u<номер>. */
    fun data(label: String, user: Int, stamp: String) = safe(label) + (if (user != 0) "-u$user" else "") + "-" + stamp + DATA_EXT

    /** Автокопия данных одного приложения: префикс ротации свой у каждого пакета и пользователя. */
    fun autoDataPrefix(pkg: String, user: Int) = AUTO_PREFIX + safe(pkg) + "-u" + user + "-"
    fun autoData(pkg: String, user: Int, stamp: String) = autoDataPrefix(pkg, user) + stamp + DATA_EXT

    /** Имя без запрещённых в файловых системах знаков, без пробелов по краям, не длиннее 60 знаков. */
    fun safe(s: String): String {
        val t = s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.').take(60).trim()
        return t.ifEmpty { "app" }
    }

    /** Имя, введённое пользователем: чистится и получает нужное расширение. */
    fun withExt(name: String, ext: String): String {
        val n = safeFile(name)
        return if (n.lowercase().endsWith(ext)) n else n + ext
    }

    private fun safeFile(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifEmpty { "backup" }

    /** Свободное имя: если файл уже есть, добавляется -1, -2… перед расширением. */
    fun unique(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot); val ext = name.substring(dot)
        var i = 1
        while (taken("$base-$i$ext")) i++
        return "$base-$i$ext"
    }

    private val stampAnyRx = Regex("(\\d{8})-(\\d{6})")

    /** Время из имени файла копии (ГГГГММДД-ЧЧММСС, последнее вхождение) или null. */
    fun stampOf(name: String): String? = stampAnyRx.findAll(name).lastOrNull()?.value
}

/** Список копий в папке: только файлы нужного вида, без временных и пустых, новые сверху (время из имени, иначе дата файла). */
object BkList {
    fun of(entries: List<FsEntry>, ext: String): List<FsEntry> =
        entries.filter { !it.isDir && !it.name.startsWith(".") && it.size > 0 && it.name.lowercase().endsWith(ext) }
            .sortedWith(compareByDescending<FsEntry> { sortKey(it) }.thenBy { it.name.lowercase() })

    /** Ключ сортировки «ГГГГММДДЧЧММСС»: из имени, иначе из даты вида «ГГГГ-ММ-ДД ЧЧ:ММ»; неизвестная дата внизу. */
    fun sortKey(e: FsEntry): String {
        BkNames.stampOf(e.name)?.let { return it.replace("-", "") }
        val d = e.date.filter { it.isDigit() }
        return if (d.length >= 8 && e.date.trim().take(4).all { it.isDigit() }) d.take(14).padEnd(14, '0') else ""
    }

    /** Дата и время для показа: «15.11.2026 14:30» из имени, иначе дата файла как есть. */
    fun timeText(e: FsEntry): String {
        val s = BkNames.stampOf(e.name) ?: return e.date
        return s.substring(6, 8) + "." + s.substring(4, 6) + "." + s.substring(0, 4) + " " + s.substring(9, 11) + ":" + s.substring(11, 13)
    }
}

/**
 * «Что внутри» копии для строки списка: для копии настроек число настроек, подключений и облаков, для «Копии с данными» приложение и пользователь,
 * пометки «зашифрована» и «автокопия». То, что не удалось прочитать (зашифровано, сеть), берётся из имени файла.
 */
data class BkInside(
    val data: Boolean,
    val auto: Boolean = false,
    /** null: неизвестно. */
    val encrypted: Boolean? = null,
    val settings: Int = -1,
    val links: Int = -1,
    val clouds: Int = -1,
    val label: String = "",
    val user: Int = -1,
) {
    /** Шаблоны текста (строки ресурсов подставляет экран). */
    class Fmt(val counts: (Int, Int, Int) -> String, val app: (String, Int) -> String, val encrypted: String, val auto: String)

    fun text(f: Fmt): String {
        val l = ArrayList<String>()
        if (data) { if (label.isNotEmpty()) l += f.app(label, user.coerceAtLeast(0)) }
        else if (settings >= 0) l += f.counts(settings, links.coerceAtLeast(0), clouds.coerceAtLeast(0))
        if (encrypted == true) l += f.encrypted
        if (auto) l += f.auto
        return l.joinToString(", ")
    }

    companion object {
        private val tailRx = Regex("-(\\d{8}-\\d{6})(-\\d+)?$")
        private val userRx = Regex("-u(\\d+)$")

        /** Сведения по имени файла (как их даёт [BkNames]). */
        fun fromName(name: String): BkInside {
            val lower = name.lowercase()
            if (!lower.endsWith(BkNames.DATA_EXT)) return BkInside(false, auto = name.startsWith(BkNames.AUTO_PREFIX))
            var base = name.substring(0, name.length - BkNames.DATA_EXT.length)
            val auto = base.startsWith(BkNames.AUTO_PREFIX)
            if (auto) base = base.removePrefix(BkNames.AUTO_PREFIX)
            tailRx.find(base)?.let { base = base.substring(0, it.range.first) }
            var user = 0
            userRx.find(base)?.let { m -> user = m.groupValues[1].toIntOrNull() ?: 0; base = base.substring(0, m.range.first) }
            return BkInside(true, auto = auto, label = base, user = user)
        }
    }
}

/**
 * Место, где лежат копии: папка в памяти устройства, на SD-карте или USB («local»), либо папка на сетевом источнике или в облаке из сохранённых
 * подключений («net»: вид, адрес, пользователь из NetStore и путь). Пароль подключения в записи места не хранится.
 */
data class BkPlace(val local: Boolean, val path: String, val kind: String = "", val host: String = "", val user: String = "") {
    fun encode(): String = if (local) "local\t$path" else listOf("net", kind, host, user, path).joinToString("\t")
    fun child(name: String): String = if (path.endsWith("/")) path + name else "$path/$name"

    companion object {
        fun decode(s: String?): BkPlace? {
            if (s.isNullOrBlank()) return null
            val a = s.split('\t')
            return when {
                a[0] == "local" && a.size >= 2 && a[1].startsWith("/") -> BkPlace(true, a[1])
                a[0] == "net" && a.size >= 5 && a[1].isNotBlank() -> BkPlace(false, a[4], a[1], a[2], a[3])
                else -> null
            }
        }
    }
}

/** Автокопия по расписанию: режим, проверка срока, следующий запуск, ротация старых автокопий. */
object BkSchedule {
    const val OFF = 0
    const val DAY = 1
    const val WEEK = 2
    const val DAY_MS = 24L * 3600_000
    /** Запас: проверка идёт раз в несколько часов, копия «раз в сутки» не должна уезжать на сутки вперёд из-за пары минут. */
    const val SLACK_MS = 2L * 3600_000

    fun period(mode: Int): Long = when (mode) { DAY -> DAY_MS; WEEK -> 7 * DAY_MS; else -> 0L }

    /** Пора делать копию: режим включён и с прошлой копии прошло не меньше периода (с запасом). Часы ушли назад: тоже пора. */
    fun isDue(last: Long, mode: Int, now: Long): Boolean {
        val p = period(mode)
        if (p == 0L) return false
        if (last <= 0L || last > now) return true
        return now - last >= p - SLACK_MS
    }

    /** Когда будет следующая копия (null: выключено). */
    fun next(last: Long, mode: Int, now: Long): Long? {
        val p = period(mode)
        if (p == 0L) return null
        if (last <= 0L || last > now) return now
        return maxOf(now, last + p - SLACK_MS)
    }

    private val stampRx = Regex("\\d{8}-\\d{6}")

    /**
     * Какие автокопии удалить, чтобы осталось [keep] самых новых. Учитываются только СВОИ файлы: имя = [prefix] + время + [ext] ровно,
     * всё остальное (ручные копии, чужие файлы, автокопии других приложений) не трогается.
     */
    fun rotate(names: Collection<String>, prefix: String, ext: String, keep: Int): List<String> {
        val own = names.filter { n -> n.length > prefix.length + ext.length && n.startsWith(prefix) && n.endsWith(ext) && stampRx.matches(n.substring(prefix.length, n.length - ext.length)) }
        return own.sortedByDescending { it.substring(prefix.length, it.length - ext.length) }.drop(keep.coerceAtLeast(1))
    }
}

/** Пользователь устройства из `pm list users`. */
data class BkUser(val id: Int, val name: String, val running: Boolean)

object BkUsers {
    private val rx = Regex("UserInfo\\{(\\d+):([^:}]*):([0-9a-fA-F]+)\\}(.*)")
    fun parse(out: String): List<BkUser> = out.lines().mapNotNull { l ->
        rx.find(l)?.let { m -> BkUser(m.groupValues[1].toInt(), m.groupValues[2].trim(), m.groupValues[4].contains("running")) }
    }.distinctBy { it.id }.sortedBy { it.id }
}

/** Сведения о «Копии с данными» (первая запись внутри файла). */
data class BkMeta(
    val pkg: String,
    val label: String = "",
    val versionName: String = "",
    val versionCode: Long = 0,
    val targetSdk: Int = 0,
    val user: Int = 0,
    val sdk: Int = 0,
    val release: String = "",
    val model: String = "",
    val manufacturer: String = "",
    val device: String = "",
    val signer: String = "",
    val mode: String = "",
    val parts: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val time: Long = 0,
    val appVersion: String = "",
) {
    fun toBytes(): ByteArray {
        val p = Properties()
        p["format"] = FORMAT.toString(); p["app"] = "ADB-phon"; p["kind"] = "appdata"
        p["pkg"] = pkg; p["label"] = label; p["versionName"] = versionName; p["versionCode"] = versionCode.toString(); p["targetSdk"] = targetSdk.toString()
        p["user"] = user.toString(); p["sdk"] = sdk.toString(); p["release"] = release; p["model"] = model; p["manufacturer"] = manufacturer; p["device"] = device
        p["signer"] = signer; p["mode"] = mode; p["parts"] = parts.joinToString(","); p["notes"] = notes.joinToString(","); p["time"] = time.toString(); p["appVersion"] = appVersion
        val o = ByteArrayOutputStream()
        p.store(o, null)
        return o.toByteArray()
    }

    companion object {
        const val FORMAT = 1
        const val ENTRY = "adbphon-meta.properties"
        const val SUMS = "adbphon-sums.properties"

        fun parse(b: ByteArray): BkMeta {
            val p = Properties().apply { load(b.inputStream()) }
            if (p.getProperty("app") != "ADB-phon" || p.getProperty("kind") != "appdata") throw AbFormat.BadFormat("not an ADB-phon data copy")
            if ((p.getProperty("format")?.toIntOrNull() ?: 0) > FORMAT) throw AbFormat.BadFormat("newer format")
            fun s(k: String) = p.getProperty(k).orEmpty()
            fun l(k: String) = s(k).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val pkg = s("pkg").ifEmpty { throw AbFormat.BadFormat("no package") }
            // имя пакета потом идёт в команды оболочки (pm, rm, tar): только допустимые символы
            if (!Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*").matches(pkg)) throw AbFormat.BadFormat("bad package")
            return BkMeta(pkg, s("label"), s("versionName"), s("versionCode").toLongOrNull() ?: 0, s("targetSdk").toIntOrNull() ?: 0, s("user").toIntOrNull() ?: 0,
                s("sdk").toIntOrNull() ?: 0, s("release"), s("model"), s("manufacturer"), s("device"), s("signer"), s("mode"), l("parts"), l("notes"),
                s("time").toLongOrNull() ?: 0, s("appVersion"))
        }
    }
}

/** Устройство и пользователь, куда восстанавливается копия. null в полях: не удалось узнать. */
data class BkTarget(
    val installed: Boolean,
    val versionCode: Long?,
    val signer: String?,
    val sdk: Int,
    val model: String,
    val userExists: Boolean,
    val root: Boolean,
    val adb: Boolean,
)

/** Итог проверки совместимости: BLOCK запрещает восстановление данных, WARN и INFO показываются в окне. */
object BkCompat {
    enum class Level { BLOCK, WARN, INFO }
    enum class Code {
        NO_PRIV, USER_MISSING, NOT_INSTALLED, SIGNER_MISMATCH, SIGNER_UNKNOWN, VERSION_LOWER, VERSION_HIGHER,
        SDK_DIFF, OTHER_DEVICE, NO_ROOT_INTERNAL, AB_LIMITED, ONLY_APK,
    }
    data class Issue(val code: Code, val level: Level)

    fun check(m: BkMeta, t: BkTarget): List<Issue> {
        val out = ArrayList<Issue>()
        fun add(c: Code, l: Level) { out += Issue(c, l) }
        if (!t.root && !t.adb) add(Code.NO_PRIV, Level.BLOCK)
        if (!t.userExists) add(Code.USER_MISSING, Level.BLOCK)
        if (!t.installed) add(Code.NOT_INSTALLED, Level.BLOCK)
        else {
            val a = m.signer.lowercase().trim(); val b = t.signer?.lowercase()?.trim()
            if (a.isNotEmpty() && !b.isNullOrEmpty()) { if (a != b) add(Code.SIGNER_MISMATCH, Level.BLOCK) } else add(Code.SIGNER_UNKNOWN, Level.INFO)
            val v = t.versionCode
            if (v != null && m.versionCode > 0) {
                if (v < m.versionCode) add(Code.VERSION_LOWER, Level.WARN) else if (v > m.versionCode) add(Code.VERSION_HIGHER, Level.INFO)
            }
        }
        if (m.sdk > 0 && t.sdk > 0 && m.sdk != t.sdk) add(Code.SDK_DIFF, Level.WARN)
        if (m.model.isNotBlank() && t.model.isNotBlank() && !m.model.equals(t.model, true)) add(Code.OTHER_DEVICE, Level.INFO)
        val data = m.parts.filter { it != "apk" }
        if (("internal" in data || "internal_de" in data) && !t.root) add(Code.NO_ROOT_INTERNAL, Level.WARN)
        if ("ab" in data && !t.root && t.sdk >= 31 && m.targetSdk >= 31) add(Code.AB_LIMITED, Level.WARN)
        if (data.isEmpty()) add(Code.ONLY_APK, Level.INFO)
        return out
    }

    fun canRestore(issues: List<Issue>) = issues.none { it.level == Level.BLOCK }
}

/**
 * Формат Android Backup (.ab), как у `adb backup`: заголовок «ANDROID BACKUP», версия 5, сжатие deflate, шифрование «none» или «AES-256»
 * (PBKDF2-HMAC-SHA1 10000 раундов, соль 512 бит, мастер-ключ AES-256-CBC, контрольная сумма мастер-ключа). Такой файл открывает
 * android-backup-extractor на ПК (`java -jar abe.jar unpack копия.ab копия.tar пароль`); внутри обычный tar.
 * Формат не проверяет целостность сам: для этого внутри файла лежит список SHA-256 всех частей ([BkMeta.SUMS]).
 */
object AbFormat {
    const val MAGIC = "ANDROID BACKUP"
    const val VERSION = 5
    const val ROUNDS = 10_000
    private const val SALT = 64

    class BadFormat(msg: String) : Exception(msg)
    class NeedPass : Exception("need password")
    class WrongPass : Exception("wrong password")

    // ---------------------------------------------------------------- PBKDF2-HMAC-SHA1 по байтам пароля (как SecretKeyFactory PBKDF2WithHmacSHA1 с UTF-8)

    fun pbkdf2(pass: ByteArray, salt: ByteArray, rounds: Int, bytes: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(if (pass.isEmpty()) EmptyKey else SecretKeySpec(pass, "HmacSHA1"))
        val out = ByteArray(bytes)
        var block = 1; var off = 0
        while (off < bytes) {
            mac.update(salt); mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal(); val t = u.copyOf()
            for (i in 1 until rounds) { u = mac.doFinal(u); for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte() }
            val n = minOf(t.size, bytes - off); System.arraycopy(t, 0, out, off, n); off += n; block++
        }
        return out
    }

    /** Ключ HMAC нулевой длины (пустой пароль): SecretKeySpec его не принимает. */
    private object EmptyKey : javax.crypto.SecretKey {
        private fun readResolve(): Any = EmptyKey
        override fun getAlgorithm() = "HmacSHA1"
        override fun getFormat() = "RAW"
        override fun getEncoded() = ByteArray(0)
    }

    private fun utf8(pass: CharArray): ByteArray = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(pass)).let { b -> ByteArray(b.remaining()).also { b.get(it) } }

    /** Контрольная сумма мастер-ключа: байты ключа превращаются в символы со знаком (особенность Android), затем UTF-8, затем PBKDF2. */
    fun checksum(mk: ByteArray, salt: ByteArray, rounds: Int, version: Int): ByteArray {
        val chars = CharArray(mk.size) { i -> if (version >= 2) mk[i].toInt().toChar() else (mk[i].toInt() and 0xFF).toChar() }
        return pbkdf2(utf8(chars), salt, rounds, 32)
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    fun unhex(s: String): ByteArray {
        val t = s.trim()
        if (t.length % 2 != 0 || !t.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) throw BadFormat("bad hex")
        return ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /**
     * Начать файл .ab в [out]. Возвращает поток, в который пишется tar; его закрытие дописывает сжатие и шифрование и закрывает [out].
     * [pass] null или пустой: без шифрования.
     */
    fun writer(out: OutputStream, pass: CharArray?, rnd: SecureRandom = SecureRandom()): OutputStream {
        val head = StringBuilder().append(MAGIC).append('\n').append(VERSION).append('\n').append(1).append('\n')
        var body: OutputStream = out
        if (pass == null || pass.isEmpty()) {
            head.append("none\n")
            out.write(head.toString().toByteArray(Charsets.US_ASCII))
        } else {
            val userSalt = ByteArray(SALT).also { rnd.nextBytes(it) }
            val ckSalt = ByteArray(SALT).also { rnd.nextBytes(it) }
            val userKey = pbkdf2(utf8(pass), userSalt, ROUNDS, 32)
            val mk = ByteArray(32).also { rnd.nextBytes(it) }
            val mIv = ByteArray(16).also { rnd.nextBytes(it) }
            val ck = checksum(mk, ckSalt, ROUNDS, VERSION)
            val blob = ByteArrayOutputStream().apply { write(mIv.size); write(mIv); write(mk.size); write(mk); write(ck.size); write(ck) }.toByteArray()
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(userKey, "AES"), IvParameterSpec(ByteArray(16).also { rnd.nextBytes(it) })) }
            val encBlob = c.doFinal(blob)
            head.append("AES-256\n").append(hex(userSalt)).append('\n').append(hex(ckSalt)).append('\n').append(ROUNDS).append('\n')
                .append(hex(c.iv)).append('\n').append(hex(encBlob)).append('\n')
            out.write(head.toString().toByteArray(Charsets.US_ASCII))
            val mc = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(mk, "AES"), IvParameterSpec(mIv)) }
            body = CipherOutputStream(out, mc)
        }
        return DeflaterOutputStream(body, Deflater(Deflater.BEST_COMPRESSION), 64 * 1024)
    }

    class Header(val version: Int, val compressed: Boolean, val encrypted: Boolean)

    private fun line(i: InputStream): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c < 0) { if (b.size() == 0) throw BadFormat("truncated header") else break }
            if (c == '\n'.code) break
            b.write(c)
            if (b.size() > 4096) throw BadFormat("bad header")
        }
        return b.toString("US-ASCII")
    }

    /** Только заголовок: файл .ab ли это и зашифрован ли он (поток читается до конца заголовка). */
    fun header(i: InputStream): Header {
        if (line(i) != MAGIC) throw BadFormat("not an Android backup")
        val ver = line(i).trim().toIntOrNull() ?: throw BadFormat("bad version")
        if (ver < 1 || ver > VERSION) throw BadFormat("unsupported version $ver")
        val comp = line(i).trim() == "1"
        val enc = when (line(i).trim()) { "none" -> false; "AES-256" -> true; else -> throw BadFormat("unknown encryption") }
        return Header(ver, comp, enc)
    }

    fun isEncrypted(i: InputStream): Boolean = header(i).encrypted

    /**
     * Открыть .ab: возвращает поток tar. Ошибки: [BadFormat] (не .ab, повреждён), [NeedPass] (файл зашифрован, пароля нет), [WrongPass].
     * Неверный пароль определяется до чтения данных (по контрольной сумме мастер-ключа), файл при этом не меняется.
     */
    fun reader(i: InputStream, pass: CharArray?): InputStream {
        val h = header(i)
        var body: InputStream = i
        if (h.encrypted) {
            val userSalt = unhex(line(i)); val ckSalt = unhex(line(i))
            val rounds = line(i).trim().toIntOrNull()?.takeIf { it in 1..5_000_000 } ?: throw BadFormat("bad rounds")
            val userIv = unhex(line(i)); val blob = unhex(line(i))
            if (pass == null || pass.isEmpty()) throw NeedPass()
            val userKey = pbkdf2(utf8(pass), userSalt, rounds, 32)
            val plain = try {
                Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(userKey, "AES"), IvParameterSpec(userIv)) }.doFinal(blob)
            } catch (e: javax.crypto.BadPaddingException) { throw WrongPass() } catch (e: javax.crypto.IllegalBlockSizeException) { throw BadFormat("bad key blob") }
            var p = 0
            fun take(): ByteArray { if (p >= plain.size) throw WrongPass(); val n = plain[p].toInt() and 0xFF; p++; if (p + n > plain.size) throw WrongPass(); return plain.copyOfRange(p, p + n).also { p += n } }
            val mIv = take(); val mk = take(); val ck = take()
            if (mIv.size != 16 || mk.size != 32) throw WrongPass()
            val ok = checksum(mk, ckSalt, rounds, h.version).contentEquals(ck) || checksum(mk, ckSalt, rounds, if (h.version >= 2) 1 else 2).contentEquals(ck)
            if (!ok) throw WrongPass()
            body = CipherInputStream(i, Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(mk, "AES"), IvParameterSpec(mIv)) })
        }
        return if (h.compressed) InflaterInputStream(body, java.util.zip.Inflater(), 64 * 1024) else body
    }
}

/**
 * Упаковка «Копии с данными» в один файл .ab: tar с [BkMeta.ENTRY] первым, затем части копии (APK, tar данных, data.ab системы), в конце
 * [BkMeta.SUMS] с SHA-256 каждой записи. Самопроверка читает файл целиком и сверяет суммы.
 */
object BkPack {
    class Part(val name: String, val size: Long, val open: () -> InputStream)

    /** Записать копию; [progress] получает число записанных байтов частей. */
    fun write(out: OutputStream, pass: CharArray?, meta: BkMeta, parts: List<Part>, progress: (Long) -> Unit = {}) {
        val tar = org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(AbFormat.writer(out, pass), "UTF-8")
        tar.setLongFileMode(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.LONGFILE_POSIX)
        tar.setBigNumberMode(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.BIGNUMBER_POSIX)
        val sums = Properties()
        fun put(name: String, size: Long, src: InputStream) {
            val e = org.apache.commons.compress.archivers.tar.TarArchiveEntry(name)
            e.size = size; e.modTime = Date(meta.time.takeIf { it > 0 } ?: System.currentTimeMillis())
            tar.putArchiveEntry(e)
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024); var left = size; var done = 0L
            while (left > 0) {
                val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw java.io.IOException("$name: file shorter than expected")
                tar.write(buf, 0, n); md.update(buf, 0, n); left -= n; done += n
                progress(n.toLong())
            }
            tar.closeArchiveEntry()
            sums[name] = AbFormat.hex(md.digest()).lowercase()
        }
        val mb = meta.toBytes()
        put(BkMeta.ENTRY, mb.size.toLong(), mb.inputStream())
        for (p in parts) {
            require(p.name != BkMeta.ENTRY && p.name != BkMeta.SUMS && !p.name.contains("..")) { "bad part name" }
            p.open().use { put(p.name, p.size, it) }
        }
        val sb = ByteArrayOutputStream().also { sums.store(it, null) }.toByteArray()
        val e = org.apache.commons.compress.archivers.tar.TarArchiveEntry(BkMeta.SUMS); e.size = sb.size.toLong()
        tar.putArchiveEntry(e); tar.write(sb); tar.closeArchiveEntry()
        tar.finish(); tar.close()
    }

    /** Только сведения о копии (первая запись), без чтения частей. */
    fun readMeta(i: InputStream, pass: CharArray?): BkMeta {
        val tar = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(AbFormat.reader(i, pass), "UTF-8")
        val e = tar.nextEntry ?: throw AbFormat.BadFormat("empty")
        if (e.name != BkMeta.ENTRY) throw AbFormat.BadFormat("not an ADB-phon data copy")
        return BkMeta.parse(tar.readBytes())
    }

    class Result(val meta: BkMeta, val parts: List<String>, val bytes: Long)
    /** Ошибка получателя частей (запись на диск и т.п.), а не повреждение файла. */
    class SinkFailed(cause: Throwable) : RuntimeException(cause.message, cause)

    /**
     * Прочитать копию целиком со сверкой SHA-256. [sink] получает каждую часть (имя, размер, поток); null: только проверка.
     * Ошибка: [AbFormat.BadFormat] (повреждён, части не сходятся с суммами), [AbFormat.NeedPass], [AbFormat.WrongPass].
     */
    fun read(i: InputStream, pass: CharArray?, sink: ((String, Long, InputStream) -> Unit)? = null, progress: (Long) -> Unit = {}): Result {
        val tar = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(AbFormat.reader(i, pass), "UTF-8")
        var meta: BkMeta? = null
        val got = LinkedHashMap<String, String>()
        var sums: Properties? = null
        var total = 0L
        try {
            while (true) {
                val e = tar.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name
                if (name.contains("..") || name.startsWith("/") || !Regex("[A-Za-z0-9._@+-]+").matches(name)) throw AbFormat.BadFormat("bad entry name")
                if (name == BkMeta.SUMS) { sums = Properties().apply { load(tar.readBytes().inputStream()) }; continue }
                val md = MessageDigest.getInstance("SHA-256")
                val counting = object : FilterInputStream(tar) {
                    override fun read(): Int { val c = super.read(); if (c >= 0) { md.update(c.toByte()); progress(1) }; return c }
                    override fun read(b: ByteArray, off: Int, len: Int): Int { val n = super.read(b, off, len); if (n > 0) { md.update(b, off, n); progress(n.toLong()) }; return n }
                    override fun close() {}
                }
                if (name == BkMeta.ENTRY) meta = BkMeta.parse(counting.readBytes())
                else if (sink != null) { try { sink(name, e.size, counting) } catch (x: Throwable) { throw SinkFailed(x) } }
                val rest = ByteArray(64 * 1024)
                while (counting.read(rest, 0, rest.size) > 0) {}   // остаток записи (если получатель прочитал не всё) тоже идёт в сумму
                got[name] = AbFormat.hex(md.digest()).lowercase()
                total += e.size
            }
        } catch (e: java.io.EOFException) { throw AbFormat.BadFormat("truncated") } catch (e: java.util.zip.ZipException) { throw AbFormat.BadFormat("corrupt data") }
        catch (e: java.io.IOException) { throw AbFormat.BadFormat("corrupt: " + (e.message ?: e.javaClass.simpleName)) }
        val m = meta ?: throw AbFormat.BadFormat("no description inside")
        val s = sums ?: throw AbFormat.BadFormat("no checksums inside (truncated?)")
        if (s.stringPropertyNames() != got.keys) throw AbFormat.BadFormat("parts missing")
        for ((k, v) in got) if (s.getProperty(k) != v) throw AbFormat.BadFormat("checksum mismatch: $k")
        return Result(m, got.keys.filter { it != BkMeta.ENTRY }, total)
    }
}

/** SHA-256 сертификата подписи в виде строки (как `apksigner verify --print-certs`). */
fun bkCertSha256(cert: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
