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
    const val VERSION = "1.1"   // 6.0: 1.1 = движок упакован (UPX), установленный вес модуля 105 → 44 МБ; протокол прежний (ENGINE_API 1), модуль 1.0 остаётся рабочим
    const val VERSION_CODE = 2

    /** Вариант модуля: ключ, имя файла, ABI, e_machine движка в ELF, versionCode (база*10+номер), размер (МБ) и SHA-256 файла. */
    class Variant(val key: String, val abi: String, val machine: Int, val code: Int, val sizeMb: Int, val sha: String) {
        val file get() = "ADB-phon-Clouds-$VERSION-$key.apk"
    }
    val VARIANTS = listOf(
        Variant("arm64", "arm64-v8a", 183, 22, 22, "b92067e2f0ec41efb7a2c7040863044c183797deba00de3d8bb35bc0c80bf42e"),
        Variant("arm32", "armeabi-v7a", 40, 21, 24, "c8194b78f34fe2bcad5a857f992b8c9b628fe76e177f864edcbbd528fb57cac2"),
        Variant("x86_64", "x86_64", 62, 24, 25, "489935ab5c2da93b5aafad556152f1d33c8fd8a9f698860fe29fee2278c9430e"),
        Variant("x86", "x86", 3, 23, 24, "807c6a53026ef1bc27e7d54ae8229eb6c27da0ef273cb3cd549b09e89d467a28"),
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
            // 10.11.2026 (владелица: «пусть ставится как положено»): всегда обычной установкой с подтверждением системы, без тихой установки через ADB
            // (на S9 с Android 10 «pm install» по пути не открывал файл из кэша, и модуль не ставился)
            val r = SessionInstall.install(app, listOf(f))
            if (!r.ok) { error = if (r.cancelled) app.getString(R.string.fm_cancelled) else r.message.ifBlank { app.getString(R.string.cm_failed) }; phase = 4; return false }
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
