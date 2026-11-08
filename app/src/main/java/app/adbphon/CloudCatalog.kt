package app.adbphon

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.security.MessageDigest

/** Параметр провайдера из описания встроенного движка (config providers). */
class COpt(
    val name: String, val help: String, val def: String, val required: Boolean, val isPassword: Boolean, val advanced: Boolean,
    val hide: Int, val type: String, val exclusive: Boolean, val examples: List<Pair<String, String>>, val provider: String,
)

class CProv(val name: String, val desc: String, val opts: List<COpt>)

/** Строка каталога: [type] — тип в движке, [preset] — готовые значения (например, provider=Cloudflare у S3), [title] — название для человека. */
class CEntry(val type: String, val title: String, val iconKey: String?, val preset: Map<String, String> = emptyMap(), val note: String = "", val needLogin: Boolean = false)

/**
 * Каталог облаков. Список типов и описание их параметров берутся из встроенного движка (config providers), поэтому форма любого типа
 * строится автоматически: поля, подсказки, обязательные, секретные (скрыты и обфусцируются), выпадающие списки.
 * Входит ли тип в движок, проверяется по этому же списку.
 */
object CloudCatalog {
    @Volatile private var cache: List<CProv>? = null
    var loadError by mutableStateOf("")
    /** Сравнение оформления сетки: подписи у всех плиток (true) или только у заглушек (false, по умолчанию). Переключается долгим нажатием на заголовок окна «Добавить облако». */
    var labelAll by mutableStateOf(false)
    /** Крупные значки в строках «домика» и избранном (36–40 dp вместо 28–32); временный параметр для сравнения, включается из стенда NetTestActivity (extra cloudbig=1). */
    var bigRows by mutableStateOf(false)

    /** Типы, которые входят в движок, но не являются облаками для обычного пользователя (составные, служебные, локальные). */
    val virtual = setOf("alias", "local", "crypt", "cache", "chunker", "combine", "hasher", "memory", "union", "compress", "archive", "tardigrade", "hdfs", "doi", "netstorage")

    /** Типы с входом через браузер (общий мастер: authorize движка, затем запись token в настройку). */
    val oauth = setOf("drive", "onedrive", "dropbox", "yandex", "box", "google photos", "pcloud", "hidrive", "putio", "premiumizeme", "zoho", "google cloud storage", "sharefile", "huaweidrive")

    suspend fun load(ctx: Context): List<CProv> {
        cache?.let { return it }
        return withContext(Dispatchers.IO) {
            val r = Rclone.run(ctx, listOf("config", "providers"), 60_000)
            if (!r.ok) { loadError = Rclone.reason(r); return@withContext emptyList() }
            val a = JSONArray(r.out.trim())
            val list = (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val oa = o.optJSONArray("Options") ?: JSONArray()
                CProv(o.getString("Name"), o.optString("Description"), (0 until oa.length()).map { j ->
                    val p = oa.getJSONObject(j)
                    val ex = p.optJSONArray("Examples")
                    COpt(
                        p.getString("Name"), p.optString("Help"), p.optString("DefaultStr"), p.optBoolean("Required"), p.optBoolean("IsPassword"), p.optBoolean("Advanced"),
                        p.optInt("Hide"), p.optString("Type", "string"), p.optBoolean("Exclusive"),
                        if (ex == null) emptyList() else (0 until ex.length()).map { k -> ex.getJSONObject(k).let { e -> e.optString("Value") to e.optString("Help") } },
                        p.optString("Provider"),
                    )
                })
            }
            cache = list
            list
        }
    }

    fun find(list: List<CProv>, type: String): CProv? = list.firstOrNull { it.name == type }

    /** Короткое название сервиса по типу (и провайдеру у S3): запись каталога, иначе описание движка, иначе имя типа. */
    fun serviceName(type: String, provider: String?, url: String? = null): String {
        val u = url.orEmpty().lowercase()
        if (type == "webdav") when { "yandex" in u -> return "Яндекс"; "mail.ru" in u -> return "Mail.ru" }
        return shortBrand(rawServiceName(type, provider))
    }

    /** Короткий бренд для подписи: без уточнений в скобках и без родовых слов «диск», «disk», «drive» («Яндекс Диск (пароль приложения)» → «Яндекс»). */
    fun shortBrand(s: String): String {
        var r = s.replace(Regex("\\s*\\([^()]*\\)"), "").trim()
        val words = r.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val kept = words.filterNot { it.lowercase().trim(',', '.') in setOf("диск", "диска", "disk", "drive") }
        if (kept.isNotEmpty()) r = kept.joinToString(" ")
        return r.trim().ifEmpty { s }
    }

    private fun rawServiceName(type: String, provider: String?): String {
        (popular + s3).firstOrNull { it.type == type && (provider == null || it.preset["provider"] == null || it.preset["provider"] == provider) }?.let { return it.title }
        val d = cache?.firstOrNull { it.name == type }?.desc?.takeIf { it.isNotBlank() && it.length <= 42 }
        return d ?: type.replaceFirstChar { it.uppercase() }
    }

    /** Фильтр «Provider» параметра: список через запятую, «!» перед списком означает «кроме». Пусто: для всех. */
    fun appliesTo(filter: String, provider: String?): Boolean {
        if (filter.isBlank()) return true
        val neg = filter.startsWith("!")
        val set = filter.removePrefix("!").split(',').map { it.trim() }.toSet()
        val hit = provider != null && provider in set
        return if (neg) !hit else hit
    }

    /** Поля формы: видимые, не служебные, подходящие под выбранного провайдера (для S3). */
    fun formOpts(p: CProv, preset: Map<String, String>, provValue: String?): List<COpt> {
        return p.opts.filter { o ->
            !o.advanced && (o.hide and 1) == 0 && o.name !in setOf("token", "auth_url", "token_url", "client_id", "client_secret", "description", "env_auth", "bearer_token", "bearer_token_command") &&
                o.name !in preset.keys && appliesTo(o.provider, provValue)
        }
    }

    /** Название значка по типу и настройке (для S3 по провайдеру). Значка нет: null (рисуется облако с буквой). */
    fun iconKey(type: String, provider: String? = null, endpoint: String? = null): String? = when (type) {
        "drive" -> "drive"; "onedrive" -> "onedrive"; "dropbox" -> "dropbox"; "yandex" -> "yandex"; "box" -> "box"; "mega" -> "mega"
        "google photos" -> "gphotos"; "pcloud" -> "pcloud"; "mailru" -> "mailru"; "protondrive" -> "protondrive"; "zoho" -> "zoho"
        "google cloud storage" -> "gcs"; "azureblob", "azurefiles" -> "azure"; "b2" -> "backblaze"; "swift" -> "swift"; "sharefile" -> "sharefile"
        "huaweidrive" -> "huawei"; "oracleobjectstorage" -> "oci"; "internetarchive" -> "iarchive"; "cloudinary" -> "cloudinary"; "iclouddrive" -> "icloud"
        "s3" -> when {
            endpoint?.contains("yandexcloud") == true -> "yandexcloud"
            else -> when (provider) {
                "AWS" -> "s3aws"; "Cloudflare" -> "cloudflare"; "Wasabi" -> "wasabi"; "DigitalOcean" -> "digitalocean"; "Minio" -> "minio"; "Ceph" -> "ceph"
                "Alibaba" -> "alibaba"; "HuaweiOBS" -> "huawei"; "IBMCOS" -> "ibm"; "IONOS" -> "ionos"; "Scaleway" -> "scaleway"; "OVHcloud" -> "ovh"; "Hetzner" -> "hetzner"
                else -> "s3aws"
            }
        }
        "webdav" -> when (provider) { "nextcloud" -> "nextcloud"; "owncloud" -> "owncloud"; else -> null }
        else -> null
    }

    /** Есть ли у записи каталога фирменный значок (иначе рисуется облако с буквой). */
    fun hasIcon(e: CEntry): Boolean = CloudIcons.resByKey(e.iconKey ?: iconKey(e.type, e.preset["provider"], e.preset["endpoint"])) != null

    /** Порядок каталога: сначала по важности те, у кого есть фирменный значок, затем остальные (внутри групп порядок сохраняется). */
    fun ordered(list: List<CEntry>): List<CEntry> = list.sortedBy { if (hasIcon(it)) 0 else 1 }

    /** Популярные (после Google Диск, OneDrive, Dropbox, Яндекс Диск, Box и MEGA, у которых свои кнопки). */
    val popular: List<CEntry> = listOf(
        CEntry("google photos", "Google Фото", "gphotos"),
        CEntry("pcloud", "pCloud", "pcloud"),
        CEntry("mailru", "Mail.ru Облако", "mailru"),
        CEntry("protondrive", "Proton Drive", "protondrive"),
        CEntry("koofr", "Koofr", null),
        CEntry("jottacloud", "Jottacloud", null),
        CEntry("zoho", "Zoho WorkDrive", "zoho"),
        CEntry("filen", "Filen", null),
        CEntry("internxt", "Internxt", null),
        CEntry("hidrive", "HiDrive", null),
        CEntry("icedrive", "Icedrive", null),
        CEntry("seafile", "Seafile", null),
        CEntry("putio", "put.io", null),
        CEntry("premiumizeme", "Premiumize", null),
        CEntry("gofile", "Gofile", null),
        CEntry("sugarsync", "SugarSync", null),
        CEntry("opendrive", "OpenDrive", null),
    )

    val s3: List<CEntry> = listOf(
        CEntry("s3", "Amazon S3", "s3aws", mapOf("provider" to "AWS")),
        CEntry("s3", "Cloudflare R2", "cloudflare", mapOf("provider" to "Cloudflare")),
        CEntry("s3", "Wasabi", "wasabi", mapOf("provider" to "Wasabi")),
        CEntry("b2", "Backblaze B2", "backblaze"),
        CEntry("s3", "DigitalOcean Spaces", "digitalocean", mapOf("provider" to "DigitalOcean")),
        CEntry("s3", "MinIO", "minio", mapOf("provider" to "Minio")),
        CEntry("storj", "Storj", null),
        CEntry("s3", "Яндекс Object Storage", "yandexcloud", mapOf("provider" to "Other", "endpoint" to "storage.yandexcloud.net", "region" to "ru-central1")),
        CEntry("azureblob", "Azure Blob", "azure"),
        CEntry("google cloud storage", "Google Cloud Storage", "gcs"),
    )

    /** Имя настройки для записи: одно и то же для того же аккаунта (повторное добавление заменяет запись). */
    fun nameFor(type: String, ident: String): String =
        "acc_" + MessageDigest.getInstance("SHA-1").digest((type + ":" + ident.trim().lowercase()).toByteArray()).joinToString("") { "%02x".format(it) }.take(10)

    /** Короткая человекочитаемая причина по ответу движка. */
    fun explain(ctx: Context, text: String): String {
        val t = text.lowercase()
        return when {
            "2fa" in t || "two-factor" in t || "multi-factor" in t || "otp" in t || "totp" in t || "mfa" in t -> ctx.getString(R.string.cl_err_2fa)
            "401" in t || "403" in t || "denied" in t || "forbidden" in t || "unauthor" in t || "invalid" in t || "incorrect" in t || "wrong" in t || "credential" in t ||
                "login" in t || "password" in t || "authenticat" in t || "signature" in t || "not found" in t && "user" in t || "bad auth" in t -> ctx.getString(R.string.cl_err_bad)
            "429" in t || "rate" in t || "limit" in t || "quota" in t || "too many" in t || "try again" in t || "blocked" in t -> ctx.getString(R.string.cl_err_limit)
            "lookup" in t || "dial" in t || "timeout" in t || "timed out" in t || "no such host" in t || "connection" in t || "network" in t -> ctx.getString(R.string.cl_err_net)
            else -> ctx.getString(R.string.cl_err_other, text.lines().lastOrNull { it.isNotBlank() }?.take(160).orEmpty())
        }
    }
}

/** Добавление облака без входа через браузер: значения формы, обфускация паролей, проверка входа и списка корня. */
object CloudSetup {
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")

    fun reset() { busy = false; error = "" }

    suspend fun add(ctx0: Context, entry: CEntry, prov: CProv, opts: List<COpt>, values: Map<String, String>): NetConn? {
        val ctx = ctx0.applicationContext
        CloudTestHook.fakeAdd?.let { busy = true; kotlinx.coroutines.delay(700); busy = false; return it }
        val missing = opts.firstOrNull { (it.required || (entry.needLogin && (it.name == "user" || it.name == "pass"))) && values[it.name].isNullOrBlank() && it.def.isBlank() }
        if (missing != null) { error = ctx.getString(R.string.cl_err_missing, optLabel(ctx, missing.name)); return null }
        busy = true; error = ""
        try {
            val pairs = ArrayList<Pair<String, String>>()
            entry.preset.forEach { (k, v) -> pairs += k to v }
            for (o in opts) {
                val v = values[o.name]?.trim().orEmpty()
                if (v.isEmpty() || v == o.def) continue
                if (o.isPassword) {
                    val ob = Rclone.run(ctx, listOf("obscure", "-"), 30_000, stdin = v)
                    val s = ob.out.trim().lines().lastOrNull().orEmpty()
                    if (!ob.ok || s.isEmpty()) { error = ctx.getString(R.string.cl_err_other, "obscure"); return null }
                    pairs += o.name to s
                } else pairs += o.name to v
            }
            val ident = listOf("user", "username", "email", "account", "access_key_id", "url", "host", "endpoint", "bucket", "vault").firstNotNullOfOrNull { values[it]?.takeIf { s -> s.isNotBlank() } } ?: ""
            val name = CloudCatalog.nameFor(entry.type + entry.preset.values.joinToString(""), ident)
            val tmp = "tmp_" + System.nanoTime().toString(36)
            RcloneConf.put(ctx, tmp, entry.type, null, pairs)
            val r = try { Rclone.run(ctx, listOf("lsd", "$tmp:") + Rclone.common, 90_000) } finally { RcloneConf.remove(ctx, tmp) }
            if (!r.ok) { error = CloudCatalog.explain(ctx, Rclone.reason(r)); return null }
            RcloneConf.put(ctx, name, entry.type, null, pairs)
            val conn = NetConn("cloud", name, 0, (if (ident.isNotBlank()) "$ident (" else "(") + entry.title + ")", "")
            NetStore.save(ctx, conn)
            return conn
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = CloudCatalog.explain(ctx, e.message ?: e.toString()); return null }
        finally { busy = false }
    }

    /** Подпись поля: перевод для частых параметров (строки copt_*), иначе имя параметра. */
    fun optLabel(ctx: Context, name: String): String {
        val id = ctx.resources.getIdentifier("copt_" + name.replace(Regex("[^a-z0-9_]"), "_"), "string", ctx.packageName)
        return if (id != 0) ctx.getString(id) else name.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
