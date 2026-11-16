package app.adbphon

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * «Копия с данными» одним файлом в выбранном месте. Сначала механизм [FullBackup] собирает части в папке на устройстве (APK, tar данных с root,
 * data.ab системы через ADB), затем они упаковываются в один файл формата Android Backup (.ab, по желанию с паролем AES-256) со сведениями о
 * приложении, устройстве, пользователе и подписи ([BkMeta]) и суммами SHA-256. Восстановление: файл распаковывается во временную папку,
 * проверяется совместимость ([BkCompat]), данные возвращаются через [FullBackup.restore] для выбранного пользователя.
 */
object BkData {
    private const val STAGE = FullBackup.BASE + "/.restore"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE)
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Приложения и пользователь последней ручной копии с данными: их берёт автокопия. */
    fun rememberForAuto(ctx: Context, pkgs: List<String>, user: Int) { prefs(ctx).edit().putString("bkp_auto_apps", pkgs.joinToString("\n")).putInt("bkp_auto_user", user).apply() }
    fun autoApps(ctx: Context): List<String> = prefs(ctx).getString("bkp_auto_apps", "").orEmpty().lines().filter { it.isNotBlank() }
    fun autoUser(ctx: Context): Int = prefs(ctx).getInt("bkp_auto_user", 0)

    suspend fun users(): List<BkUser> = runCatching { BkUsers.parse(Adb.shell("pm list users")) }.getOrDefault(emptyList()).ifEmpty { listOf(BkUser(0, "", true)) }
    suspend fun currentUser(): Int = runCatching { Adb.shell("am get-current-user").trim().toIntOrNull() }.getOrNull() ?: 0

    private class Dev(val sdk: Int, val release: String, val model: String, val manufacturer: String, val device: String)
    private suspend fun dev(): Dev {
        // 10.0 (bk3): без root и ADB (копия только APK) сведения берутся у своего устройства, без обращения к оболочке
        val l = if (!(Adb.isRoot || Adb.isConnected)) emptyList() else runCatching { Adb.shell("getprop ro.build.version.sdk; getprop ro.build.version.release; getprop ro.product.model; getprop ro.product.manufacturer; getprop ro.product.device").lines().map { it.trim() } }.getOrDefault(emptyList())
        fun g(i: Int) = l.getOrNull(i).orEmpty()
        return Dev(g(0).toIntOrNull() ?: android.os.Build.VERSION.SDK_INT, g(1).ifEmpty { android.os.Build.VERSION.RELEASE }, g(2).ifEmpty { android.os.Build.MODEL }, g(3).ifEmpty { android.os.Build.MANUFACTURER }, g(4).ifEmpty { android.os.Build.DEVICE })
    }

    @Suppress("DEPRECATION")
    private fun signerOf(pi: PackageInfo?): String? {
        if (pi == null) return null
        val s = if (android.os.Build.VERSION.SDK_INT >= 28) pi.signingInfo?.apkContentsSigners else pi.signatures
        return s?.firstOrNull()?.toByteArray()?.let { bkCertSha256(it) }
    }

    @Suppress("DEPRECATION")
    private val sigFlags get() = if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    /** SHA-256 сертификата подписи файла APK. */
    fun signerOfApk(ctx: Context, f: File): String? = runCatching { signerOf(ctx.packageManager.getPackageArchiveInfo(f.absolutePath, sigFlags)) }.getOrNull()

    /** SHA-256 подписи установленного приложения (на этом устройстве; для другого устройства по ADB неизвестно). */
    @Suppress("DEPRECATION")
    fun signerInstalled(ctx: Context, pkg: String): String? = if (FmIo.remote) null else runCatching { signerOf(ctx.packageManager.getPackageInfo(pkg, sigFlags or PackageManager.MATCH_UNINSTALLED_PACKAGES)) }.getOrNull()

    /** Записанная копия: строка итога, имя файла в месте и проверенный размер. */
    class Saved(val text: String, val name: String, val size: Long)

    /** Части копии из папки на устройстве: напрямую (своё устройство, есть доступ ко всем файлам) или через ADB во временную папку. */
    private suspend fun partsOf(ctx: Context, dir: String, tmp: File): List<File> = withContext(Dispatchers.IO) {
        val skip = setOf(".list", ".done")
        val direct = if (!FmIo.remote) File(dir).listFiles()?.filter { it.isFile && it.name !in skip && it.canRead() } else null
        if (direct != null && direct.isNotEmpty()) return@withContext direct
        val names = Adb.shell("ls -1 ${q(dir)} 2>/dev/null").lines().map { it.trim() }.filter { it.isNotEmpty() && it !in skip && !it.contains('/') }
        tmp.mkdirs()
        names.map { n -> File(tmp, n).also { Adb.pull("$dir/$n", it) } }
    }

    /**
     * 10.0 (bk3): только APK своего устройства: файлы APK читаются напрямую (sourceDir и части), root и ADB не нужны.
     * Возвращает папку с копиями файлов и meta.json, как у [FullBackup.backup], или null, если прямой путь невозможен.
     */
    @Suppress("DEPRECATION")
    private fun apkDirect(ctx: Context, a: AppEntry, tmp: File): File? {
        if (FmIo.remote) return null
        val pi = runCatching { ctx.packageManager.getPackageInfo(a.pkg, 0) }.getOrNull() ?: return null
        val ai = pi.applicationInfo ?: return null
        val src = (listOfNotNull(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())).map { File(it) }
        if (src.isEmpty() || src.any { !it.canRead() }) return null
        tmp.mkdirs()
        // имена частей как у pm path: base.apk, split_*.apk (одинаковые имена в разных папках не встречаются)
        for (f in src) f.copyTo(File(tmp, f.name), true)
        val vc = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        val j = JSONObject().put("pkg", a.pkg).put("label", a.label).put("versionName", pi.versionName.orEmpty()).put("versionCode", vc)
            .put("mode", "apk").put("user", 0).put("targetSdk", ai.targetSdkVersion).put("parts", org.json.JSONArray(listOf("apk"))).put("notes", org.json.JSONArray())
        File(tmp, "meta.json").writeText(j.toString())
        return tmp
    }

    /**
     * Одна копия: сбор частей, упаковка, запись в место, самопроверка, уборка временной папки.
     * [withApk], [withData] (bk3): что снимать; только APK на своём устройстве идёт без root и ADB.
     */
    suspend fun createOne(ctx: Context, a: AppEntry, user: Int, place: BkPlace, wantName: String, pass: CharArray?, withApk: Boolean = true, withData: Boolean = true, step: (String) -> Unit): Saved {
        var dir: String? = null
        val tmp = File(BkStore.tmpDir(ctx), "stage_" + System.nanoTime())
        val direct = if (withApk && !withData) withContext(Dispatchers.IO) { runCatching { apkDirect(ctx, a, File(tmp, "apk")) }.getOrNull() } else null
        val summary = if (direct != null) ctx.getString(R.string.fbk_done_one, a.label, ctx.getString(R.string.fbk_p_apk), "")
            else FullBackup.backup(ctx, a, user, { dir = it }, withApk, withData, step)
        val d = dir
        if (direct == null && d == null) throw IOException(ctx.getString(R.string.bk3_err_no_stage))
        try {
            step(ctx.getString(R.string.bkp_step_pack, a.label))
            val files = if (direct != null) direct.listFiles()?.filter { it.isFile }.orEmpty() else partsOf(ctx, d!!, tmp)
            if (files.isEmpty()) throw IOException(ctx.getString(R.string.bkp_err_empty))
            val mj = files.firstOrNull { it.name == "meta.json" }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() } ?: JSONObject()
            fun arr(k: String) = mj.optJSONArray(k)?.let { x -> (0 until x.length()).map { x.getString(it) } } ?: emptyList()
            val apk = files.firstOrNull { it.name == "base.apk" } ?: files.firstOrNull { it.name.endsWith(".apk") }
            val dv = dev()
            val meta = BkMeta(
                pkg = a.pkg, label = a.label, versionName = mj.optString("versionName"), versionCode = mj.optLong("versionCode"), targetSdk = mj.optInt("targetSdk"),
                user = user, sdk = dv.sdk, release = dv.release, model = dv.model, manufacturer = dv.manufacturer, device = dv.device,
                signer = apk?.let { signerOfApk(ctx, it) } ?: signerInstalled(ctx, a.pkg).orEmpty(), mode = mj.optString("mode"),
                parts = arr("parts"), notes = arr("notes"), time = System.currentTimeMillis(),
                appVersion = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty(),
            )
            val parts = files.map { f -> BkPack.Part(f.name, f.length()) { f.inputStream() } }
            val total = files.sumOf { it.length() }.coerceAtLeast(1L)
            var done = 0L; var lastShown = 0L
            val prog: (Long) -> Unit = { n -> done += n; if (done - lastShown > 4L * 1024 * 1024 || done == total) { lastShown = done; step(ctx.getString(R.string.bkp_step_pack_pct, a.label, (done * 100 / total).toInt())) } }
            val o = BkStore.open(ctx, place)
            try {
                val taken = runCatching { BkStore.list(o).map { it.name }.toSet() }.getOrDefault(emptySet())
                val name = BkNames.unique(BkNames.withExt(wantName, BkNames.DATA_EXT)) { it in taken }
                val size: Long
                val out = BkStore.localFile(o, name)
                if (out != null) {
                    // своё место в памяти: файл пишется сразу туда (без лишней копии), под временным именем
                    out.parentFile?.mkdirs()
                    val part = File(out.parentFile, ".$name.part")
                    try { part.outputStream().use { BkPack.write(it, pass, meta, parts, prog) } } catch (e: Throwable) { part.delete(); if (BkStore.needAllFiles()) throw IOException(ctx.getString(R.string.bkp_err_all_files, place.path), e); throw e }
                    step(ctx.getString(R.string.bkp_step_check, a.label))
                    // самопроверка до переименования: битый файл не попадает в список копий под настоящим именем
                    try { part.inputStream().buffered().use { BkPack.read(it, pass) } } catch (e: Throwable) { part.delete(); throw e }
                    if (!part.renameTo(out)) { part.delete(); throw IOException(ctx.getString(R.string.fm_err_denied)) }
                    size = out.length()
                } else {
                    val f = File(BkStore.tmpDir(ctx), name)
                    try {
                        f.outputStream().use { BkPack.write(it, pass, meta, parts, prog) }
                        step(ctx.getString(R.string.bkp_step_check, a.label))
                        f.inputStream().buffered().use { BkPack.read(it, pass) }
                        step(ctx.getString(R.string.bkp_step_upload, BkStore.display(ctx, place)))
                        var up = 0L; val all = f.length().coerceAtLeast(1L); var shown = 0L
                        size = BkStore.write(o, f, name) { n -> up += n; if (up - shown > 2L * 1024 * 1024 || up == all) { shown = up; step(ctx.getString(R.string.bkp_step_upload_pct, BkStore.display(ctx, place), (up * 100 / all).toInt())) } }
                    } finally { f.delete() }
                }
                if (size <= 0L) throw IOException(ctx.getString(R.string.bkp_err_empty))
                if (d != null) runCatching { Adb.shell("rm -rf ${q(d)}", 120_000) }
                val enc = if (pass != null && pass.isNotEmpty()) "\n" + ctx.getString(R.string.bkp_enc_yes) else ""
                return Saved(ctx.getString(R.string.bkp_saved, BkStore.display(ctx, place, name), BkStore.size(ctx, size)) + "\n" + summary + enc, name, size)
            } finally { o.close() }
        } finally { tmp.deleteRecursively() }
    }

    // ---------------------------------------------------------------- восстановление

    /** Открытый файл копии: локальный файл (скачанный из сети во временный), сведения, зашифрован ли. */
    class Loaded(val place: BkPlace, val name: String, val file: File, val temp: Boolean, val meta: BkMeta, val encrypted: Boolean, val pass: CharArray?) {
        fun dispose() { if (temp) file.delete() }
    }

    /** Скачать (если нужно) и прочитать сведения. Ошибки: AbFormat.NeedPass, AbFormat.WrongPass, AbFormat.BadFormat, IOException. */
    suspend fun fetch(ctx: Context, place: BkPlace, name: String, step: (String) -> Unit): Pair<File, Boolean> = withContext(Dispatchers.IO) {
        val o = BkStore.open(ctx, place)
        try {
            BkStore.localFile(o, name)?.let { return@withContext it to false }
            step(ctx.getString(R.string.bkp_step_download, BkStore.display(ctx, place, name)))
            val f = File(BkStore.tmpDir(ctx), "in_" + System.nanoTime() + ".ab")
            BkStore.fetch(o, name, f)
            f to true
        } finally { o.close() }
    }

    suspend fun load(file: File, temp: Boolean, place: BkPlace, name: String, pass: CharArray?): Loaded = withContext(Dispatchers.IO) {
        val enc = file.inputStream().buffered().use { AbFormat.isEncrypted(it) }
        val meta = file.inputStream().buffered().use { BkPack.readMeta(it, pass) }
        Loaded(place, name, file, temp, meta, enc, pass)
    }

    /** Состояние устройства для проверки совместимости. Подключает root или ADB, если их ещё нет. */
    suspend fun target(ctx: Context, m: BkMeta, user: Int): BkTarget {
        runCatching { FmIo.app }.onFailure { FmIo.app = ctx.applicationContext }
        val fix = runCatching { FmIo.ensurePriv() }.getOrDefault("pair")
        val priv = fix == null
        if (!priv) {
            val pi = runCatching { ctx.packageManager.getPackageInfo(m.pkg, 0) }.getOrNull()
            @Suppress("DEPRECATION") val vc = pi?.let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
            return BkTarget(pi != null, vc, signerInstalled(ctx, m.pkg), android.os.Build.VERSION.SDK_INT, android.os.Build.MODEL, user == 0, false, false)
        }
        val installed = runCatching { Adb.shell("pm list packages --user $user ${m.pkg}").lines().any { it.trim() == "package:" + m.pkg } }.getOrDefault(false)
        val vc = if (installed) runCatching { Regex("versionCode=(\\d+)").find(Adb.shell("dumpsys package ${m.pkg} | grep -m1 versionCode=", 30_000))?.groupValues?.get(1)?.toLongOrNull() }.getOrNull() else null
        val d = dev()
        val users = users()
        return BkTarget(installed, vc, if (installed) signerInstalled(ctx, m.pkg) else null, d.sdk, d.model, users.any { it.id == user }, Adb.isRoot, Adb.isConnected)
    }

    /** Распаковать части во временную папку на устройстве (напрямую или через ADB). [only]: только эти части (по именам). */
    private suspend fun stage(ctx: Context, l: Loaded, only: (String) -> Boolean, step: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val dir = STAGE + "/" + BkNames.stamp(System.currentTimeMillis())
        val direct = !FmIo.remote && !BkStore.needAllFiles() && (File(dir).mkdirs() || File(dir).isDirectory)
        if (!direct) Adb.shell("mkdir -p ${q(dir)}")
        val cache = File(BkStore.tmpDir(ctx), "unpack_" + System.nanoTime())
        val total = l.file.length().coerceAtLeast(1L)
        var done = 0L; var shown = 0L
        try {
            l.file.inputStream().buffered().use { input ->
                BkPack.read(input, l.pass, { name, size, s ->
                    if (!only(name)) return@read
                    if (direct) File(dir, name).outputStream().use { s.copyTo(it, 256 * 1024) }
                    else {
                        cache.mkdirs(); val f = File(cache, name)
                        f.outputStream().use { s.copyTo(it, 256 * 1024) }
                        f.inputStream().use { kotlinx.coroutines.runBlocking { Adb.pushStream(it, size, "$dir/$name") } }   // получатель частей не suspend: поток IO и так занят распаковкой
                        f.delete()
                    }
                }, { n -> done += n; if (done - shown > 4L * 1024 * 1024) { shown = done; step(ctx.getString(R.string.bkp_step_unpack_pct, (done * 100 / total).toInt().coerceAtMost(99))) } })
            }
        } catch (e: Throwable) { runCatching { Adb.shell("rm -rf ${q(dir)}") }; if (direct) File(dir).deleteRecursively(); throw e } finally { cache.deleteRecursively() }
        dir
    }

    private fun copyOf(l: Loaded, dir: String, parts: List<String>) = FullCopy(dir, l.meta.pkg, l.meta.label, l.meta.versionName, l.meta.versionCode, "", l.meta.mode, parts, l.meta.notes, 0)

    /** Поставить приложение из копии (только по кнопке; данные не трогаются). Для второго пользователя сначала пробуется уже установленное у основного. */
    suspend fun installFromCopy(ctx: Context, l: Loaded, user: Int, step: (String) -> Unit): String {
        if (user != 0) {
            val r = runCatching { Adb.shell("pm install-existing --user $user ${l.meta.pkg}") }.getOrDefault("")
            if (r.contains("installed for user", true) || r.contains("Success", true)) return ctx.getString(R.string.bkp_installed, l.meta.label.ifEmpty { l.meta.pkg })
        }
        if ("apk" !in l.meta.parts) return ctx.getString(R.string.bkp_no_apk)
        val dir = stage(ctx, l, { it.endsWith(".apk") }, step)
        try {
            return FullBackup.restore(ctx, copyOf(l, dir, listOf("apk")), user, FullBackup.ApkMode.FORCE, step)
        } finally { runCatching { Adb.shell("rm -rf ${q(dir)}") } }
    }

    /**
     * 10.0 (bk3): APK из копии без root и ADB: части распаковываются в кэш программы и ставятся системным установщиком
     * (один запрос подтверждения от Android, нужно разрешение «Установка неизвестных приложений»). Только своё устройство, основной пользователь.
     */
    suspend fun installNoPriv(ctx: Context, l: Loaded, step: (String) -> Unit): String {
        if ("apk" !in l.meta.parts) return ctx.getString(R.string.bkp_no_apk)
        val label = l.meta.label.ifEmpty { l.meta.pkg }
        val dir = File(BkStore.tmpDir(ctx), "apk_" + System.nanoTime())
        try {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                val total = l.file.length().coerceAtLeast(1L); var done = 0L; var shown = 0L
                l.file.inputStream().buffered().use { input ->
                    BkPack.read(input, l.pass, { name, _, s -> if (name.endsWith(".apk")) File(dir, name).outputStream().use { s.copyTo(it, 256 * 1024) } },
                        { n -> done += n; if (done - shown > 4L * 1024 * 1024) { shown = done; step(ctx.getString(R.string.bkp_step_unpack_pct, (done * 100 / total).toInt().coerceAtMost(99))) } })
                }
            }
            val apks = dir.listFiles()?.filter { it.name.endsWith(".apk") }.orEmpty().sortedBy { if (it.name == "base.apk") 0 else 1 }
            if (apks.isEmpty()) return ctx.getString(R.string.bkp_no_apk)
            step(ctx.getString(R.string.fbk_rs_apk, label))
            val r = SessionInstall.install(ctx, apks)
            return when {
                r.ok -> ctx.getString(R.string.bk3_apk_installed, label)
                r.cancelled -> ctx.getString(R.string.bk3_apk_cancelled, label)
                r.code == -1 -> ctx.getString(R.string.fbk_rs_fail, label, ctx.getString(R.string.bk3_err_timeout))
                else -> ctx.getString(R.string.fbk_rs_fail, label, ctx.getString(R.string.bk3_err_install) + if (r.message.isNotBlank()) " (" + r.message.take(150) + ")" else "")
            }
        } finally { dir.deleteRecursively() }
    }

    /** Вернуть данные (без установки APK: приложение уже должно стоять). */
    suspend fun restore(ctx: Context, l: Loaded, user: Int, step: (String) -> Unit): String {
        val dir = stage(ctx, l, { !it.endsWith(".apk") }, step)
        try {
            return FullBackup.restore(ctx, copyOf(l, dir, l.meta.parts - "apk"), user, FullBackup.ApkMode.NONE, step)
        } finally { runCatching { Adb.shell("rm -rf ${q(dir)}", 120_000) } }
    }

    fun issueText(ctx: Context, i: BkCompat.Issue, m: BkMeta, t: BkTarget?): String = when (i.code) {
        BkCompat.Code.NO_PRIV -> ctx.getString(R.string.bkp_c_no_priv)
        BkCompat.Code.USER_MISSING -> ctx.getString(R.string.bkp_c_user_missing)
        BkCompat.Code.NOT_INSTALLED -> ctx.getString(R.string.bkp_c_not_installed)
        BkCompat.Code.SIGNER_MISMATCH -> ctx.getString(R.string.bkp_c_signer_mismatch)
        BkCompat.Code.SIGNER_UNKNOWN -> ctx.getString(R.string.bkp_c_signer_unknown)
        BkCompat.Code.VERSION_LOWER -> ctx.getString(R.string.bkp_c_version_lower, m.versionName, t?.versionCode?.toString() ?: "?")
        BkCompat.Code.VERSION_HIGHER -> ctx.getString(R.string.bkp_c_version_higher)
        BkCompat.Code.SDK_DIFF -> ctx.getString(R.string.bkp_c_sdk_diff, m.release.ifEmpty { m.sdk.toString() }, t?.sdk?.toString() ?: "?")
        BkCompat.Code.OTHER_DEVICE -> ctx.getString(R.string.bkp_c_other_device, m.model)
        BkCompat.Code.NO_ROOT_INTERNAL -> ctx.getString(R.string.bkp_c_no_root_internal)
        BkCompat.Code.AB_LIMITED -> ctx.getString(R.string.bkp_c_ab_limited)
        BkCompat.Code.ONLY_APK -> ctx.getString(R.string.bkp_c_only_apk)
    }
}
