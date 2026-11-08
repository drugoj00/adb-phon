package app.adbphon

import android.content.Context
import android.os.Environment
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * ПОЛНАЯ резервная копия состояния программы (формат .adbpb, версия 1).
 *
 * Состав: все настройки (все файлы shared_prefs, кроме технических), избранные подключения LAN и WebDAV с логинами, а также секреты: пароли подключений
 * (в программе они зашифрованы ключом Android Keystore, который на другое устройство не переносится), ключ ADB этого телефона, rclone.conf облаков
 * (когда он появится). Секреты кладутся в копию ТОЛЬКО если задана парольная фраза: весь файл тогда шифруется (PBKDF2WithHmacSHA256, 300000 итераций,
 * случайная соль, AES-256-GCM, заголовок входит в проверку целостности). Без фразы копия создаётся «без паролей и токенов».
 * Восстановление транзакционное: файл расшифровывается и проверяется целиком, перед заменой текущее состояние сохраняется для отката,
 * при любой ошибке в процессе всё возвращается назад.
 */
object AppBackup {
    const val EXT = "adbpb"
    const val FORMAT = 1
    private val MAGIC = "ADBPB".toByteArray()
    private const val ITER = 300_000

    /** Файлы настроек, которые не входят в копию: адрес папки копий (он свой на каждом устройстве) и служебные отметки. */
    val EXCLUDE_KEYS = setOf("adbphon:backup_tree", "adbphon:backup_auto_last", "adbphon:upd_last")
    /** net_fav хранится отдельно (пароли в нём шифрует Keystore этого устройства), служебные файлы библиотек пропускаются. */
    private fun skipPrefs(n: String) = n == "net_fav" || n.startsWith("androidx.") || n.startsWith("WebView") || n.startsWith("com.") || n.contains("_preferences_")
    /** Файлы filesDir, входящие в копию как секреты. */
    private val SECRET_FILES = listOf("adb_key.pk8", "adb_cert.der", "clouds.conf")

    class Summary(val settings: Int, val links: Int, val clouds: Int, val devices: Int, val secrets: Boolean)
    class BadFormat(msg: String) : Exception(msg)
    class NeedPass : Exception("need passphrase")
    class WrongPass : Exception("wrong passphrase")

    // ------------------------------------------------------------------ шифрование

    private fun kdf(pass: CharArray, salt: ByteArray, iter: Int): SecretKeySpec {
        val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pass, salt, iter, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    /** Упаковка: без фразы текст + SHA-256, с фразой шифрование AES-256-GCM. */
    fun seal(payload: ByteArray, pass: CharArray?): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        if (pass == null || pass.isEmpty()) {
            out.write(MAGIC); out.write(FORMAT); out.write(0)
            out.write(payload); out.write(MessageDigest.getInstance("SHA-256").digest(payload))
        } else {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val head = java.io.ByteArrayOutputStream().apply {
                write(MAGIC); write(FORMAT); write(1)
                write(java.nio.ByteBuffer.allocate(4).putInt(ITER).array()); write(salt); write(iv)
            }.toByteArray()
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, kdf(pass, salt, ITER), GCMParameterSpec(128, iv)); updateAAD(head) }
            out.write(head); out.write(c.doFinal(payload))
        }
        return out.toByteArray()
    }

    fun isEncrypted(data: ByteArray): Boolean = data.size > 7 && data.copyOfRange(0, 5).contentEquals(MAGIC) && data[6].toInt() == 1

    /** Распаковка и проверка. Исключения: [BadFormat] (не наш файл, повреждён, новая версия), [NeedPass], [WrongPass]. */
    fun unseal(data: ByteArray, pass: CharArray?): ByteArray {
        if (data.size < 8 || !data.copyOfRange(0, 5).contentEquals(MAGIC)) throw BadFormat("not an ADB-phon backup")
        val ver = data[5].toInt(); val flags = data[6].toInt()
        if (ver > FORMAT) throw BadFormat("backup made by a newer version (format $ver)")
        if (flags == 0) {
            if (data.size < 8 + 32) throw BadFormat("truncated")
            val body = data.copyOfRange(7, data.size - 32)
            if (!MessageDigest.getInstance("SHA-256").digest(body).contentEquals(data.copyOfRange(data.size - 32, data.size))) throw BadFormat("checksum mismatch")
            return body
        }
        if (pass == null || pass.isEmpty()) throw NeedPass()
        if (data.size < 7 + 4 + 16 + 12 + 16) throw BadFormat("truncated")
        val iter = java.nio.ByteBuffer.wrap(data, 7, 4).int
        if (iter < 10_000 || iter > 5_000_000) throw BadFormat("bad parameters")
        val salt = data.copyOfRange(11, 27); val iv = data.copyOfRange(27, 39)
        val head = data.copyOfRange(0, 39)
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, kdf(pass, salt, iter), GCMParameterSpec(128, iv)); updateAAD(head) }
            c.doFinal(data, 39, data.size - 39)
        } catch (e: javax.crypto.AEADBadTagException) { throw WrongPass() }
    }

    // ------------------------------------------------------------------ сбор состояния

    private fun prefsNames(ctx: Context): List<String> {
        val dir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        return (dir.list()?.filter { it.endsWith(".xml") }?.map { it.removeSuffix(".xml") } ?: emptyList()).filterNot { skipPrefs(it) }.sorted()
    }

    private fun prefToJson(ctx: Context, name: String): JSONObject {
        val o = JSONObject()
        ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
            if ("$name:$k" in EXCLUDE_KEYS) return@forEach
            val e = JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                is Set<*> -> e.put("t", "S").put("v", JSONArray(v.map { it.toString() }))
                else -> return@forEach
            }
            o.put(k, e)
        }
        return o
    }

    /** Ключи настроек, которые попадут в копию (для проверки полноты): все, кроме исключений. */
    fun includedKeys(ctx: Context): Set<String> = prefsNames(ctx).flatMap { n -> ctx.getSharedPreferences(n, Context.MODE_PRIVATE).all.keys.map { "$n:$it" } }.filter { it !in EXCLUDE_KEYS }.toSet()

    /** Содержимое копии в JSON. [secrets] — класть ли пароли, ключи и токены. */
    fun collect(ctx: Context, secrets: Boolean): JSONObject {
        val root = JSONObject().put("app", "ADB-phon").put("format", FORMAT).put("created", System.currentTimeMillis())
            .put("appVersion", runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrDefault("")).put("secrets", secrets)
        val prefs = JSONObject()
        prefsNames(ctx).forEach { prefs.put(it, prefToJson(ctx, it)) }
        root.put("prefs", prefs)
        val net = JSONArray()
        NetStore.list(ctx).forEach { c ->
            net.put(JSONObject().put("k", c.kind).put("h", c.host).put("p", c.port).put("u", c.user).put("d", c.path).put("v", c.smb).put("n", c.name).apply { if (secrets) put("w", c.pass) })
        }
        root.put("net", net)
        if (secrets) {
            val files = JSONObject()
            SECRET_FILES.forEach { n -> File(ctx.filesDir, n).takeIf { it.isFile }?.let { files.put(n, Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)) } }
            root.put("files", files)
        }
        return root
    }

    fun summarize(j: JSONObject): Summary {
        val prefs = j.getJSONObject("prefs")
        var settings = 0
        prefs.keys().forEach { settings += prefs.getJSONObject(it).length() }
        val net = j.optJSONArray("net") ?: JSONArray()
        var links = 0; var clouds = 0
        for (i in 0 until net.length()) { if (net.getJSONObject(i).optString("k") == "dav") clouds++ else links++ }
        val files = j.optJSONObject("files")
        if (files != null && files.has("clouds.conf")) clouds += Regex("(?m)^\\[").findAll(String(Base64.decode(files.getString("clouds.conf"), Base64.NO_WRAP))).count()
        // устройства: избранные и запомненные имена (Связь), известные HID-хосты (автоподключение)
        val ad = prefs.optJSONObject("adbphon")
        var devices = 0
        ad?.optJSONObject("favs")?.optString("v")?.let { devices += it.split("\n").count { l -> l.isNotBlank() } }
        ad?.optJSONObject("names")?.optString("v")?.let { devices += it.split("\n").count { l -> l.isNotBlank() } }
        prefs.optJSONObject("bt_last")?.keys()?.forEach { if (it.startsWith("last_")) devices++ }
        return Summary(settings, links, clouds, devices, j.optBoolean("secrets"))
    }

    // ------------------------------------------------------------------ создание

    fun defaultName(): String = "ADB-phon-backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "." + EXT

    /** Готовые байты файла копии и краткие сведения. */
    fun create(ctx: Context, pass: CharArray?): Pair<ByteArray, Summary> {
        val withSecrets = pass != null && pass.isNotEmpty()
        val j = collect(ctx, withSecrets)
        return seal(j.toString().toByteArray(Charsets.UTF_8), pass) to summarize(j)
    }

    /** Загрузки/ADB-phon-backup-<дата>.adbpb (по умолчанию) либо в app-папку, если доступа к Загрузкам нет. */
    fun saveDefault(ctx: Context, data: ByteArray): File {
        val dir = runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).also { it.mkdirs() } }.getOrNull()
        val f = File(dir ?: ctx.getExternalFilesDir(null) ?: ctx.filesDir, defaultName())
        return runCatching { f.writeBytes(data); f }.getOrElse {
            File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, defaultName()).also { it.writeBytes(data) }
        }
    }

    /** Сохранить в выбранную папку (SAF) или в Загрузки. Возвращает показываемое имя и файл (для «Поделиться»; для SAF файла нет). */
    fun saveAnywhere(ctx: Context, data: ByteArray, name: String = defaultName()): Pair<String, File?> {
        val tree = SettingsBackup.folder(ctx)
        if (tree != null) {
            val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
            val doc = android.provider.DocumentsContract.createDocument(ctx.contentResolver, parent, "application/octet-stream", name) ?: throw IllegalStateException("cannot create file")
            ctx.contentResolver.openOutputStream(doc)!!.use { it.write(data) }
            return (SettingsBackup.folderLabel(tree) + "/" + name) to null
        }
        val f = saveDefault(ctx, data)
        return f.absolutePath to f
    }

    /** Автокопия раз в сутки или неделю (настройка, по умолчанию выключена): всегда без паролей и токенов. */
    fun autoIfDue(ctx: Context) {
        val sp = ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE)
        val mode = sp.getInt("backup_auto", 0)
        if (mode == 0) return
        val period = if (mode == 1) 24L * 3600_000 else 7L * 24 * 3600_000
        if (System.currentTimeMillis() - sp.getLong("backup_auto_last", 0L) < period) return
        Thread {
            runCatching {
                val (data, _) = create(ctx, null)
                saveAnywhere(ctx, data, "ADB-phon-backup-auto-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "." + EXT)
                sp.edit().putLong("backup_auto_last", System.currentTimeMillis()).apply()
            }.onFailure { AppLog.e("ADBphon", "auto backup", it) }
        }.start()
    }

    // ------------------------------------------------------------------ восстановление

    /** Текущее состояние «как есть» (пароли подключений остаются зашифрованными ключом этого устройства): для отката. */
    private fun rollbackFile(ctx: Context) = File(ctx.filesDir, "rollback.json")

    fun hasRollback(ctx: Context) = rollbackFile(ctx).isFile

    private fun snapshotRaw(ctx: Context): JSONObject {
        val root = JSONObject().put("created", System.currentTimeMillis())
        val prefs = JSONObject()
        (prefsNames(ctx) + "net_fav").forEach { prefs.put(it, prefToJson(ctx, it)) }
        root.put("prefs", prefs)
        val files = JSONObject()
        SECRET_FILES.forEach { n -> File(ctx.filesDir, n).takeIf { it.isFile }?.let { files.put(n, Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)) } }
        return root.put("files", files)
    }

    private fun applyPrefs(ctx: Context, name: String, src: JSONObject, wipe: Boolean) {
        val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
        val ed = sp.edit()
        if (wipe) sp.all.keys.filter { "$name:$it" !in EXCLUDE_KEYS }.forEach { ed.remove(it) }
        src.keys().forEach { k ->
            if ("$name:$k" in EXCLUDE_KEYS) return@forEach
            val e = src.getJSONObject(k)
            when (e.getString("t")) {
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "s" -> ed.putString(k, e.getString("v"))
                "S" -> ed.putStringSet(k, e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
            }
        }
        if (!ed.commit()) throw IllegalStateException("cannot write $name")
    }

    private fun writeFileAtomic(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw IllegalStateException("cannot replace ${f.name}") }
    }

    /** Разбор и проверка файла без изменения состояния. */
    fun parse(data: ByteArray, pass: CharArray?): JSONObject {
        val j = try { JSONObject(String(unseal(data, pass), Charsets.UTF_8)) } catch (e: org.json.JSONException) { throw BadFormat("corrupt content") }
        if (j.optString("app") != "ADB-phon") throw BadFormat("not an ADB-phon backup")
        val f = migrate(j)
        // проверка структуры целиком до первой записи
        val prefs = f.getJSONObject("prefs")
        prefs.keys().forEach { n -> val o = prefs.getJSONObject(n); o.keys().forEach { k -> val e = o.getJSONObject(k); e.getString("t"); e.get("v") } }
        return f
    }

    /** Миграция форматов (пока только версия 1). */
    private fun migrate(j: JSONObject): JSONObject {
        val v = j.optInt("format", 1)
        if (v > FORMAT) throw BadFormat("backup made by a newer version (format $v)")
        return j
    }

    /** Транзакционное восстановление. Возвращает сводку. При ошибке состояние возвращается как было. */
    fun restore(ctx: Context, j: JSONObject): Summary {
        val summary = summarize(j)
        val snap = snapshotRaw(ctx)
        rollbackFile(ctx).writeText(snap.toString())          // откат доступен и после успешного восстановления
        try {
            val prefs = j.getJSONObject("prefs")
            prefs.keys().forEach { n -> applyPrefs(ctx, n, prefs.getJSONObject(n), wipe = true) }
            // подключения: пароли шифруются заново ключом Keystore ЭТОГО устройства
            val net = j.optJSONArray("net")
            if (net != null) {
                NetStore.replaceAll(ctx, (0 until net.length()).map { i -> net.getJSONObject(i).let { o -> NetConn(o.getString("k"), o.getString("h"), o.optInt("p", 0), o.optString("u"), o.optString("w"), o.optString("d"), o.optString("v"), o.optString("n")) } })
            }
            val files = j.optJSONObject("files")
            files?.keys()?.forEach { n -> if (n in SECRET_FILES) writeFileAtomic(File(ctx.filesDir, n), Base64.decode(files.getString(n), Base64.NO_WRAP)) }
        } catch (e: Throwable) {
            runCatching { applyRaw(ctx, snap) }
            throw e
        }
        return summary
    }

    private fun applyRaw(ctx: Context, snap: JSONObject) {
        val prefs = snap.getJSONObject("prefs")
        prefs.keys().forEach { n -> applyPrefs(ctx, n, prefs.getJSONObject(n), wipe = true) }
        val files = snap.optJSONObject("files")
        files?.keys()?.forEach { n -> writeFileAtomic(File(ctx.filesDir, n), Base64.decode(files.getString(n), Base64.NO_WRAP)) }
    }

    /** Откат последнего восстановления. */
    fun rollback(ctx: Context) {
        val f = rollbackFile(ctx)
        applyRaw(ctx, JSONObject(f.readText()))
    }

    // ------------------------------------------------------------------ самопроверка (на устройстве: am start ... BACKUP_SELFTEST)

    /** Проверки без записи в рабочее состояние: шифрование, неверная фраза, подмена, версия формата, полнота ключей. Возвращает строки «OK/FAIL: …». */
    fun selfTest(ctx: Context): List<String> {
        val out = ArrayList<String>()
        fun check(name: String, ok: Boolean) { out.add((if (ok) "OK: " else "FAIL: ") + name) }
        val data = "{\"app\":\"ADB-phon\",\"format\":1,\"prefs\":{},\"x\":\"секрет\"}".toByteArray(Charsets.UTF_8)
        val pass = "Пароль-фраза 123".toCharArray()
        val enc = seal(data, pass)
        check("шифрование/расшифровка", unseal(enc, pass).contentEquals(data))
        check("формат помечен как зашифрованный", isEncrypted(enc))
        check("шифртекст не содержит открытый текст", !String(enc, Charsets.ISO_8859_1).contains("секрет") && !String(enc, Charsets.ISO_8859_1).contains("ADB-phon"))
        check("неверная фраза: понятная ошибка", runCatching { unseal(enc, "другая".toCharArray()) }.exceptionOrNull() is WrongPass)
        check("пустая фраза для зашифрованной копии", runCatching { unseal(enc, null) }.exceptionOrNull() is NeedPass)
        val tampered = enc.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        check("подмена байта обнаружена", runCatching { unseal(tampered, pass) }.exceptionOrNull() is WrongPass)
        val tamperedHead = enc.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() }
        check("подмена заголовка обнаружена", runCatching { unseal(tamperedHead, pass) }.isFailure)
        val plain = seal(data, null)
        check("копия без фразы читается и проверяется", unseal(plain, null).contentEquals(data) && !isEncrypted(plain))
        check("подмена копии без фразы обнаружена", runCatching { unseal(plain.copyOf().also { it[10] = 0x20 }, null) }.exceptionOrNull() is BadFormat)
        check("чужой файл отклонён", runCatching { unseal("hello world".toByteArray(), null) }.exceptionOrNull() is BadFormat)
        val newer = enc.copyOf().also { it[5] = (FORMAT + 1).toByte() }
        check("копия новой версии формата отклонена", runCatching { unseal(newer, pass) }.exceptionOrNull() is BadFormat)
        // полнота: каждый ключ настроек, кроме исключений, попал в собранную копию
        val j = collect(ctx, true)
        val got = HashSet<String>()
        j.getJSONObject("prefs").let { p -> p.keys().forEach { n -> p.getJSONObject(n).keys().forEach { k -> got.add("$n:$k") } } }
        val want = includedKeys(ctx)
        check("полнота: все ключи настроек в копии (${want.size} из ${want.size})", got == want)
        val s = summarize(j)
        out.add("INFO: настроек ${s.settings}, подключений ${s.links}, облаков ${s.clouds}, устройств ${s.devices}")
        return out
    }
}
