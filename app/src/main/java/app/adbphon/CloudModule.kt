package app.adbphon

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Команда установки APK из файла: содержимое подаётся потоком (`cat | pm install -S размер`). Установка по пути (`pm install -r путь`) на части прошивок
 * (S9, Android 10) не работает: системная служба не может читать файл в каталоге приложения на карте («System server has no access to read file»).
 */
fun pmInstallCmd(path: String): String {
    val q = "'" + path.replace("'", "'\\''") + "'"
    return "n=\$(wc -c < $q); cat $q | pm install -r -S \$n 2>&1"
}

/** Облако без модуля: понятное сообщение вместо падения (движок живёт в отдельном пакете «ADB-phon Облака»). */
class CloudModuleMissing(msg: String) : IllegalStateException(msg)

/**
 * Модуль «ADB-phon Облака» (пакет [PKG]): отдельное приложение с движком облачных дисков. Основная программа запускает его из каталога библиотек
 * пакета модуля (в манифесте модуля extractNativeLibs="true"), а настройки и данные хранит у себя. Модуль принимается только с той же подписью,
 * что у основной программы, и с достаточной версией протокола [API_NEEDED]. Скачивается и ставится только по просьбе пользователя, когда нужны облака;
 * скачанный файл лежит в кэше основной программы, проверяется по SHA-256 и удаляется сразу после установки (и при ошибке, и при следующем запуске).
 */
object CloudModule {
    const val PKG = "app.adbphon.clouds"
    /** Нужная версия протокола между основной программой и модулем (meta-data ENGINE_API у модуля). */
    const val API_NEEDED = 1
    /** Известная версия модуля, которую предлагаем скачать. Файлов четыре, по одному на архитектуру (один пакет, одна подпись). */
    // ЧЕРНОВИК (12.11.2026): 1.2 = модуль без значка в списке приложений; релиз clouds-1.2 на GitHub нужно выложить ДО выпуска основной программы с этой таблицей
    const val VERSION = "1.2"   // 6.0: 1.1 = движок упакован (UPX), установленный вес модуля 105 → 44 МБ; протокол прежний (ENGINE_API 1), модуль 1.0 остаётся рабочим
    const val VERSION_CODE = 3

    /** Вариант модуля: ключ, имя файла, ABI, e_machine движка в ELF, versionCode (база*10+номер), размер (МБ) и SHA-256 файла. */
    class Variant(val key: String, val abi: String, val machine: Int, val code: Int, val sizeMb: Int, val sha: String) {
        val file get() = "ADB-phon-Clouds-$VERSION-$key.apk"
    }
    val VARIANTS = listOf(
        Variant("arm64", "arm64-v8a", 183, 32, 22, "f6953d40e569905bb6e010c8c1caff895e6a0a158b5c608fa3ed4aa1c1386435"),
        Variant("arm32", "armeabi-v7a", 40, 31, 24, "ebc6ed6715383d13d92bdba647be0432c15256fa6a788928338ca336a36ca358"),
        Variant("x86_64", "x86_64", 62, 34, 26, "39fe0df21096cf1ac11f2dbaad405dc978e677216d9b95233310bed2534ea09b"),
        Variant("x86", "x86", 3, 33, 24, "07bc3efaa10d695c861532a842081af591a300d5d2d9d889646e5ab5c7ea520c"),
    )

    /** Вариант для этого устройства: первый подходящий из SUPPORTED_ABIS (порядок задаёт система: на 64-разрядном ядре с 32-разрядным пространством пользователя в списке только 32-разрядные). null: архитектура не поддерживается. */
    fun variant(): Variant? = android.os.Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
        when (abi) { "arm64-v8a" -> "arm64"; "armeabi-v7a", "armeabi" -> "arm32"; "x86_64" -> "x86_64"; "x86" -> "x86"; else -> null }
    }?.let { k -> VARIANTS.first { it.key == k } }

    fun sizeMb(): Int = variant()?.sizeMb ?: 0
    private const val RELEASE_BASE = "https://github.com/drugoj00/adb-phon/releases/download/clouds-$VERSION/"

    enum class State { MISSING, OK, OUTDATED, FOREIGN, WRONG_ABI }

    /** Метка ошибки узла дерева «нужен модуль» (не показывается пользователю). */
    const val MARK = "\u0001cloudmod"

    /** Движок есть под arm64, arm32 (ARMv7), x86_64 и x86; на других архитектурах модуль не предлагается (остаются WebDAV и другие источники). */
    fun supportedDevice(): Boolean = variant() != null

    // ---- состояние диалога ----
    var phase by mutableIntStateOf(0)          // 0 вопрос, 1 загрузка, 2 проверка, 3 установка, 4 ошибка
    var progress by mutableIntStateOf(0)
    var error by mutableStateOf("")
    @Volatile private var cancelled = false
    /** Растёт после установки модуля: окна, зависящие от его наличия, перерисовываются. */
    var ver by mutableIntStateOf(0)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE)

    /** Отладка: другой адрес и хэш файла модуля (локальный сервер, пока нет выпуска). Пусто: обычный адрес выпуска. */
    fun setDebugSource(ctx: Context, url: String?, sha: String?) {
        prefs(ctx).edit().apply { if (url.isNullOrBlank()) { remove("cloudmod_url"); remove("cloudmod_sha") } else { putString("cloudmod_url", url); putString("cloudmod_sha", sha.orEmpty().lowercase()) } }.apply()
    }
    private fun url(ctx: Context) = prefs(ctx).getString("cloudmod_url", null)?.takeIf { it.isNotBlank() } ?: (RELEASE_BASE + (variant()?.file ?: "unsupported"))
    private fun sha(ctx: Context) = prefs(ctx).getString("cloudmod_sha", null)?.takeIf { it.isNotBlank() } ?: (variant()?.sha ?: "")

    private fun info(ctx: Context): android.content.pm.PackageInfo? = runCatching {
        ctx.applicationContext.packageManager.getPackageInfo(PKG, PackageManager.GET_META_DATA)
    }.getOrNull()

    /** Версия протокола установленного модуля: из meta-data; не прочиталась (пакет только что установлен) читаем иначе; совсем нет: 0. */
    private fun apiOf(ctx: Context, pi: android.content.pm.PackageInfo): Int {
        val pm = ctx.applicationContext.packageManager
        val key = "app.adbphon.clouds.ENGINE_API"
        pi.applicationInfo?.metaData?.takeIf { it.containsKey(key) }?.let { return it.getInt(key, 0) }
        runCatching { pm.getApplicationInfo(PKG, PackageManager.GET_META_DATA).metaData?.takeIf { it.containsKey(key) }?.getInt(key, 0) }.getOrNull()?.let { return it }
        runCatching { pm.getPackageArchiveInfo(pi.applicationInfo?.sourceDir.orEmpty(), PackageManager.GET_META_DATA)?.applicationInfo?.metaData?.takeIf { it.containsKey(key) }?.getInt(key, 0) }.getOrNull()?.let { return it }
        return -1   // сведений нет
    }

    @Volatile private var lastLogged = ""

    /** e_machine из заголовка ELF (little-endian): 183 AArch64, 40 ARM, 62 x86-64, 3 x86; -1: не прочитался. */
    private fun elfMachine(f: File): Int = runCatching {
        f.inputStream().use { i -> val h = ByteArray(20); if (i.read(h) < 20 || h[0] != 0x7F.toByte() || h[1] != 'E'.code.toByte()) -1 else (h[18].toInt() and 0xFF) or ((h[19].toInt() and 0xFF) shl 8) }
    }.getOrDefault(-1)

    fun installedCode(ctx: Context): Long = info(ctx)?.let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() } ?: 0L

    /**
     * OUTDATED только когда у модуля явно указана версия протокола ниже нужной. Если сведений о ней нет (пакет только что установлен и система ещё не
     * отдала данные), а версия пакета не ниже известной, модуль считается подходящим: слово «обновить» без реальной причины не показывается.
     */
    fun state(ctx: Context): State {
        val pi = info(ctx) ?: return State.MISSING
        val pm = ctx.applicationContext.packageManager
        if (pm.checkSignatures(ctx.packageName, PKG) != PackageManager.SIGNATURE_MATCH) return State.FOREIGN
        val api = apiOf(ctx, pi)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        var st = if (api >= API_NEEDED || (api < 0 && code >= VERSION_CODE)) State.OK else State.OUTDATED
        // установлен вариант не для этой архитектуры (ручная установка, перенос): по ELF движка; предлагается поставить нужный
        if (st == State.OK) {
            val want = variant()
            val dir = pi.applicationInfo?.nativeLibraryDir
            val m = if (dir != null) elfMachine(File(dir, "libclouds.so")) else -1
            if (want != null && m > 0 && m != want.machine) st = State.WRONG_ABI
        }
        val line = "модуль облаков: $st (протокол $api, нужен $API_NEEDED, версия ${pi.versionName}/$code)"
        if (line != lastLogged) { lastLogged = line; AppLog.note(line) }
        return st
    }

    fun ok(ctx: Context) = state(ctx) == State.OK

    /** Процесс уже спрашивал про обновление модуля (один раз за запуск программы). */
    @Volatile var upgradeAsked = false
    /** Модуль установлен, рабочий, но старее известного (6.0: стоит 1.0, а есть 1.1 с меньшим весом): программа сама предлагает обновить обычной установкой, не втихую. */
    fun upgradeAvailable(ctx: Context): Boolean {
        val v = variant() ?: return false
        if (state(ctx) != State.OK) return false
        return installedCode(ctx) in 1 until v.code.toLong()
    }

    /** Исполняемый файл движка из каталога библиотек модуля; null, если модуля нет, он чужой или устарел. */
    fun engine(ctx: Context): File? {
        if (!ok(ctx)) return null
        val dir = info(ctx)?.applicationInfo?.nativeLibraryDir ?: return null
        return File(dir, "libclouds.so").takeIf { it.isFile }
    }

    fun installedVersion(ctx: Context): String? = info(ctx)?.versionName

    /** Модуль поставили или убрали не из нашего окна (файловым менеджером, из настроек): окна перерисовываются. */
    fun watch(ctx: Context) {
        val app = ctx.applicationContext
        val f = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_ADDED); addAction(android.content.Intent.ACTION_PACKAGE_REMOVED); addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        androidx.core.content.ContextCompat.registerReceiver(app, object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) { if (i.data?.schemeSpecificPart == PKG) { ver++; if (state(c) == State.OK) cleanup(c) } }
        }, f, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Сообщение для облачных узлов и ошибок. */
    fun missingText(ctx: Context, st: State = state(ctx)): String =
        ctx.getString(when (st) { State.OUTDATED -> R.string.cm_need_update; State.WRONG_ABI -> R.string.cm_wrong_abi; else -> R.string.cm_need })

    fun dir(ctx: Context): File = (ctx.applicationContext.externalCacheDir ?: ctx.applicationContext.cacheDir).let { File(it, "cloudmod") }

    /** Убрать скачанные и недокачанные файлы модуля (после установки, при ошибке, при запуске). */
    fun cleanup(ctx: Context) { runCatching { dir(ctx).deleteRecursively() } }

    fun reset() { phase = 0; progress = 0; error = ""; cancelled = false }
    fun cancel(ctx: Context) { cancelled = true; if (phase !in 1..3) { reset() } }

    private fun download(ctx: Context, to: File) {
        val c = URL(url(ctx)).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "ADB-phon")
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            to.parentFile?.mkdirs()
            c.inputStream.use { i -> to.outputStream().use { o ->
                val buf = ByteArray(256 * 1024); var got = 0L
                while (true) {
                    if (cancelled) throw LocalOps.Cancelled()
                    val n = i.read(buf); if (n < 0) break
                    o.write(buf, 0, n); got += n
                    if (total > 0) progress = (got * 100 / total).toInt().coerceIn(0, 100)
                }
            } }
        } finally { c.disconnect() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- 7.6: окно установки модуля, вопрос об обновлении и автообновление (рисуются в корне экрана: [ModuleHost]) ----
    var confirm by mutableStateOf<FmDlg.InstallConfirm?>(null)
    var runDlg by mutableStateOf<FmDlg.InstallRunDlg?>(null)
    /** Корневой вопрос «Доступно обновление модуля 1.1 → 1.2. Обновить сейчас?». */
    var askUpdate by mutableStateOf(false)
    fun auto(ctx: Context) = prefs(ctx).getBoolean("cloudmod_auto", false)
    fun setAuto(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("cloudmod_auto", v).apply(); autoTick++ }
    var autoTick by mutableIntStateOf(0)

    /**
     * Установить скачанный файл модуля через окно «Установка» (ADB-phon) или выбранным установщиком.
     * true: поставлено, false: ошибка (текст в [error]), null: отмена.
     */
    private suspend fun installViaWindow(app: Context, f: File): Boolean? {
        val o = ViaAsk.ask(app, 1) ?: return null
        if (o.id != "adb") {
            if (!ViaAsk.launchExternal(app, f, o)) { error = app.getString(R.string.fm_no_app); return false }
            val need = (variant()?.code ?: 0).toLong()
            repeat(720) { if (installedCode(app) >= need && ok(app)) return true; delay(250) }
            return null
        }
        val af = ApkFile(f.name, android.net.Uri.fromFile(f), f.length(), f)
        val meta = withContext(Dispatchers.IO) { runCatching { ApkInfo.readFor(app, af) }.getOrNull() }
        val result = kotlinx.coroutines.CompletableDeferred<Boolean?>()
        var started = false
        confirm = FmDlg.InstallConfirm(listOf(af), mapOf(af.name to meta)) { wipe ->
            started = true; confirm = null
            Fm.scope.launch {
                try {
                    if (Adb.isConnected && !FmIo.remote) {
                        val run = InstRun(); InstBg.launch(app, run) { runDlg = FmDlg.InstallRunDlg(it) }
                        val res = installWithProgress(app, listOf(af), mapOf(af.name to meta), run, wipe)
                        if (res.second > 0) error = run.fails.firstOrNull().orEmpty()
                        result.complete(res.second == 0 && res.first > 0)
                    } else {
                        val r = SessionInstall.install(app, listOf(f))
                        if (!r.ok) error = if (r.cancelled) app.getString(R.string.fm_cancelled) else r.message
                        result.complete(if (r.cancelled) null else r.ok)
                    }
                } catch (e: Throwable) { error = e.message ?: e.javaClass.simpleName; result.complete(false) }
            }
        }
        // окно закрыли без «Установить»: отмена
        while (!result.isCompleted) { delay(250); if (!started && confirm == null) result.complete(null) }
        return result.await()
    }

    /** Состояние окон корня закрыть (после установки/отмены). */
    fun closeInstallWindows() { confirm = null; runDlg = null }

    /** Удалить модуль: pm uninstall при ADB/root, иначе системное окно удаления. Возвращает текст итога. */
    suspend fun uninstall(ctx: Context): String {
        val app = ctx.applicationContext
        if (info(app) == null) return app.getString(R.string.cmu_removed)
        if (Adb.isConnected) {
            val out = withContext(Dispatchers.IO) { runCatching { Adb.shell("pm uninstall --user 0 $PKG 2>&1", 60_000, fast = true) }.getOrDefault("") }
            ver++
            return if (out.contains("Success")) app.getString(R.string.cmu_removed) else app.getString(R.string.cmu_remove_fail, out.lines().firstOrNull { it.isNotBlank() }.orEmpty())
        }
        runCatching { app.startActivity(android.content.Intent(android.content.Intent.ACTION_DELETE, android.net.Uri.parse("package:$PKG")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return app.getString(R.string.cmu_removed)
    }

    /** Скачать, проверить, установить, убрать файл. true: модуль готов. Ошибка и причина остаются в [error]. */
    suspend fun fetchAndInstall(ctx: Context): Boolean {
        val app = ctx.applicationContext
        cancelled = false; error = ""; progress = 0
        val f = File(dir(app), variant()?.file ?: "module.apk")
        try {
            val v = variant()
            if (v == null) { error = app.getString(R.string.cm_unsupported, android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()); phase = 4; return false }
            // вариант с большим versionCode уже стоит (другая архитектура): система не даёт поставить меньший поверх, нужно сначала удалить установленный
            if (installedCode(app) > v.code) { error = app.getString(R.string.cm_remove_first); phase = 4; return false }
            phase = 1
            withContext(Dispatchers.IO) { cleanup(app); download(app, f) }
            phase = 2
            val got = withContext(Dispatchers.IO) { sha256(f) }
            if (got != sha(app)) { error = app.getString(R.string.cm_sha_bad); phase = 4; return false }
            phase = 3
            // 7.6 (владелица: «я же просил, чтобы окно установки было всегда»): установка и обновление модуля всегда идут через наше окно «Установка» (выбор установщика,
            // если включено «Спрашивать», затем «Сейчас / Новое», метка «Обновление», подпись и ход установки), а не молча и не одним системным окном
            val res = installViaWindow(app, f)
            if (res == null) { reset(); return false }
            if (!res) { phase = 4; if (error.isBlank()) error = app.getString(R.string.cm_failed); return false }
            // пакет модуля появляется не мгновенно
            repeat(20) { if (ok(app)) return@repeat; delay(250) }
            if (!ok(app)) { error = app.getString(R.string.cm_failed); phase = 4; return false }
            reset(); ver++; return true
        } catch (e: LocalOps.Cancelled) { reset(); return false
        } catch (e: kotlinx.coroutines.CancellationException) { reset(); throw e
        } catch (e: Throwable) { error = e.message ?: e.javaClass.simpleName; phase = 4; return false
        } finally { cleanup(app) }
    }

    /** Нажатие «Скачать и установить»: при необходимости сначала разрешение на установку, затем загрузка; потом [then]. */
    fun start(ctx: Context, then: (() -> Unit)?, upgrade: Boolean = false) {
        if (phase in 1..3) return
        // модуль уже стоит (поставили снаружи): ничего не скачиваем, система не должна предлагать «обновление» (кроме явного обновления старого модуля)
        if (ok(ctx) && !(upgrade && upgradeAvailable(ctx))) { reset(); if (Fm.dlg is FmDlg.CloudModule) Fm.dlg = null; then?.invoke(); return }
        if (!Fm.ensureInstallPermission { start(ctx, then) }) return
        Fm.scope.launch {
            val done = fetchAndInstall(ctx)
            if (done) { Fm.say(ctx.getString(R.string.cm_done)); if (Fm.dlg is FmDlg.CloudModule) Fm.dlg = null; then?.invoke() }
        }
    }
}

/** Окно «Нужен модуль облаков»: вопрос, ход загрузки, ошибка. */
@Composable
fun CloudModuleDialog(d: FmDlg.CloudModule, close: () -> Unit) {
    val ctx = LocalContext.current
    val st = CloudModule.state(ctx)
    val phase = CloudModule.phase
    // модуль появился не из этого окна (поставлен файловым менеджером или из настроек): окно закрывается само и продолжается прерванное действие
    val modVer = CloudModule.ver
    androidx.compose.runtime.LaunchedEffect(modVer, st) {
        if (st == CloudModule.State.OK && CloudModule.phase !in 1..3 && !(d.upgrade && CloudModule.upgradeAvailable(ctx))) { CloudModule.reset(); close(); d.then?.invoke() }
    }
    LAlertDialog(
        onDismissRequest = { if (phase !in 1..3) { CloudModule.reset(); close() } else { CloudModule.cancel(ctx); close() } },
        title = { Text(stringResource(R.string.cm_title), maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (phase) {
                    1, 2, 3 -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp).padding(end = 0.dp), strokeWidth = 2.dp)
                            Text(stringResource(when (phase) { 1 -> R.string.cm_downloading; 2 -> R.string.cm_verifying; else -> R.string.cm_installing }) + if (phase == 1 && CloudModule.progress > 0) " ${CloudModule.progress} %" else "", Modifier.padding(start = 12.dp), fontSize = 14.sp)
                        }
                        if (phase == 1) ThemedProgress({ CloudModule.progress / 100f }, Modifier.padding(top = 4.dp))
                    }
                    4 -> {
                        Text(stringResource(R.string.cm_failed), fontSize = 14.sp)
                        if (CloudModule.error.isNotBlank()) Text(CloudModule.error.take(200), fontSize = 12.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                    }
                    else -> Text(stringResource(if (d.upgrade && st == CloudModule.State.OK) R.string.cm_ask_upgrade else when (st) { CloudModule.State.OUTDATED -> R.string.cm_ask_update; CloudModule.State.WRONG_ABI -> R.string.cm_ask_abi; else -> R.string.cm_ask }, CloudModule.sizeMb()), fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            if (phase == 0 || phase == 4) TextButton({ CloudModule.reset(); CloudModule.start(ctx, d.then, d.upgrade); }) { Text(stringResource(R.string.cm_install)) }
        },
        dismissButton = { TextButton({ CloudModule.cancel(ctx); CloudModule.reset(); close() }) { Text(stringResource(R.string.btn_cancel)) } },
    )
}

/**
 * 7.6: корневые окна модуля облаков (видны на любой вкладке): вопрос об обновлении при запуске, ход скачивания, окно «Установка» с версиями и ход установки, ошибка.
 * «Обновлять автоматически» включается кнопкой в вопросе или в Настройках; тогда при запуске обновление начинается само, а окно «Установка» всё равно показывается.
 */
@Composable
fun ModuleHost() {
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(CloudModule.ver, CloudModule.autoTick) {
        kotlinx.coroutines.delay(2000)
        if (!CloudModule.upgradeAsked && CloudModule.phase !in 1..3 && CloudModule.upgradeAvailable(ctx)) {
            CloudModule.upgradeAsked = true
            if (CloudModule.auto(ctx)) CloudModule.start(ctx, null, upgrade = true) else CloudModule.askUpdate = true
        }
    }
    val v = CloudModule.variant()
    if (CloudModule.askUpdate) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { CloudModule.askUpdate = false },
            title = { Text(stringResource(R.string.cmu_title), maxLines = 2) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.cmu_ask, CloudModule.installedVersion(ctx).orEmpty(), CloudModule.VERSION), fontSize = 14.sp)
                    TextButton({ CloudModule.askUpdate = false; CloudModule.setAuto(ctx, true); CloudModule.start(ctx, null, upgrade = true) }) { Text(stringResource(R.string.cmu_auto)) }
                }
            },
            confirmButton = { TextButton({ CloudModule.askUpdate = false; CloudModule.start(ctx, null, upgrade = true) }) { Text(stringResource(R.string.cmu_update)) } },
            dismissButton = { TextButton({ CloudModule.askUpdate = false }) { Text(stringResource(R.string.cmu_later)) } },
        )
    }
    // ход скачивания/проверки, если обновление запущено не из окна «Файлов»
    if (Fm.dlg !is FmDlg.CloudModule && CloudModule.confirm == null && CloudModule.runDlg == null) {
        if (CloudModule.phase in 1..2) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { },
                title = { Text(stringResource(R.string.cmu_title), maxLines = 2) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(stringResource(if (CloudModule.phase == 1) R.string.cm_downloading else R.string.cm_verifying) + if (CloudModule.phase == 1 && CloudModule.progress > 0) " ${CloudModule.progress} %" else "", Modifier.padding(start = 12.dp), fontSize = 14.sp)
                        }
                        if (CloudModule.phase == 1) ThemedProgress({ CloudModule.progress / 100f }, Modifier.padding(top = 4.dp))
                    }
                },
                confirmButton = { },
                dismissButton = { TextButton({ CloudModule.cancel(ctx) }) { Text(stringResource(R.string.btn_cancel)) } },
            )
        } else if (CloudModule.phase == 4) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { CloudModule.reset() },
                title = { Text(stringResource(R.string.cmu_title), maxLines = 2) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.cm_failed), fontSize = 14.sp)
                        if (CloudModule.error.isNotBlank()) Text(CloudModule.error.take(200), fontSize = 12.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = { TextButton({ CloudModule.reset(); CloudModule.start(ctx, null, upgrade = true) }) { Text(stringResource(R.string.cm_install)) } },
                dismissButton = { TextButton({ CloudModule.reset() }) { Text(stringResource(R.string.btn_close)) } },
            )
        }
    }
    CloudModule.confirm?.let { d -> InstallConfirmDialog(d) { CloudModule.confirm = null } }
    CloudModule.runDlg?.let { d -> InstallRunDialog(d) { CloudModule.runDlg = null } }
    @Suppress("UNUSED_VARIABLE") val unused = v
}
