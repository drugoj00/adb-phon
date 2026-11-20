package app.adbphon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 11.0: фоновая тихая установка для приложений с той же подписью (Меню приложений, Pulsar, Vision и др.), без окон ADB-phon.
 *
 * Запуск: startForegroundService с действием [ACTION], ссылкой на APK в `data` (content-URI, клиент выдаёт чтение через FLAG_GRANT_READ_URI_PERMISSION),
 * строкой `caller` (пакет вызывающего) и необязательным `receiver` (ResultReceiver). Принимаются только наши программы ([TRUSTED] или с подписью ADB-phon),
 * и только APK с той же подписью, что у вызывающего (чужой файл под именем нашей программы не ставится).
 * Установка: ADB или root, если есть; иначе сессия PackageInstaller (окно показывает только система). Интерфейс ADB-phon не открывается.
 * Ход и итог уходят в ResultReceiver и в явный broadcast [RESULT_ACTION] пакету вызывающего.
 */
class InstallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var active = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg(getString(R.string.silent_notif_title), getString(R.string.silent_notif_prepare), -1, -1)
        if (intent == null || intent.action != ACTION) { finish(startId); return START_NOT_STICKY }
        active++
        scope.launch { try { handle(intent) } finally { withContext(Dispatchers.Main) { active--; if (active <= 0) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } } } }
        return START_NOT_STICKY
    }

    private fun finish(startId: Int) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }

    override fun onDestroy() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); super.onDestroy() }

    // ---------- обработка одной заявки ----------
    private suspend fun handle(i: Intent) {
        val app = applicationContext
        runCatching { FmIo.app }.onFailure { FmIo.app = app }
        val caller = i.getStringExtra("caller").orEmpty()
        @Suppress("DEPRECATION")
        val rx: ResultReceiver? = i.getParcelableExtra("receiver")
        @Suppress("DEPRECATION")
        val uri: Uri? = i.data ?: i.getParcelableExtra("uri")
        fun reply(code: Int, status: String, pkg: String = "", ver: String = "", msg: String = "") {
            val b = Bundle().apply { putString("package", pkg); putString("version", ver); putString("status", status); putString("message", msg); putString("caller", caller) }
            runCatching { rx?.send(code, b) }
            if (caller.isNotEmpty() && code != R_PROGRESS) runCatching {
                app.sendBroadcast(Intent(RESULT_ACTION).setPackage(caller).putExtras(b).putExtra("code", code))
            }
        }
        // 1. кто просит: тот же издатель (подпись пакета-вызывающего совпадает с подписью ADB-phon)
        if (uri == null || caller.isEmpty() || !trusted(caller)) { reply(R_DENIED, "denied", msg = "caller not allowed"); return }
        // 15.0 код 75 (правило «никаких тихих установок»): с этого места ADB-phon показывает СВОЁ окно: что ставится, способ, ход, итог или причина
        val callerLabel = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(caller, 0)).toString() }.getOrDefault(caller)
        val method = getString(if (Adb.isRoot) R.string.silent_m_root else if (Adb.isConnected) R.string.silent_m_adb else R.string.silent_m_system)
        var shown = false
        suspend fun win(stage: Int, label: String = "", pkg: String = "", info: String = "", icon: androidx.compose.ui.graphics.ImageBitmap? = null, pct: Int = -1, text: String = "", newName: String = "", newCode: Long = 0L) {
            SilentInstallState.cur = SilentInstallState.Data(stage, callerLabel, label, pkg, info, method, pct, text, icon, newName, newCode)
            if (!shown) { shown = true; showWindow() }
        }
        win(0)
        if (!withContext(Dispatchers.IO) { Integrity.safe(app) }) { win(3, text = getString(R.string.silent_win_denied, "integrity")); reply(R_DENIED, "denied", msg = "integrity"); return }
        reply(R_STARTED, "started")
        // 2. файл во временную папку (разрешение на чтение выдал клиент)
        val dir = File(cacheDir, "silent").apply { mkdirs() }
        val f = File(dir, "s" + System.nanoTime() + ".apk")
        try {
            withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { ins -> f.outputStream().use { o -> ins.copyTo(o) } } ?: error("no stream") }
        } catch (e: Throwable) { win(3, text = "read: " + e.message); reply(R_ERROR, "error", msg = "read: " + e.message); f.delete(); return }
        try {
            // 3. подпись самого APK тоже наша (даже при подмене имени вызывающего чужой файл не ставится)
            val info = withContext(Dispatchers.IO) { archiveInfo(f) }
            if (info == null) { win(3, text = "not an apk"); reply(R_ERROR, "error", msg = "not an apk"); return }
            val label = info.third.ifBlank { info.first }
            val af = ApkFile(f.name, Uri.fromFile(f), f.length(), f)
            val meta = withContext(Dispatchers.IO) { runCatching { ApkInfo.readFor(app, af) }.getOrNull() }
            // что ставится: название, установлено -> новая (с кодами), размер
            val mb = "%.1f".format(java.util.Locale.US, f.length() / 1048576.0)
            val cur = meta?.curName
            val infoText = if (cur != null) getString(R.string.silent_win_info_upd, label, cur, meta.curCode.toInt(), info.second, meta.verCode.toInt(), mb)
                else getString(R.string.silent_win_info_new, label, info.second, (meta?.verCode ?: 0L).toInt(), mb)
            val newCode = meta?.verCode ?: 0L
            if (!sameSignerApk(f, caller)) { win(3, label, info.first, infoText, meta?.icon, text = getString(R.string.silent_win_badsig)); reply(R_BADSIG, "badsig", info.first, info.second, "apk signature differs from the caller"); return }
            startFg(getString(R.string.silent_notif_title), label, 0, 100)
            win(1, label, info.first, infoText, meta?.icon, pct = if (Adb.isConnected || Adb.isRoot) 0 else -1, newName = info.second, newCode = newCode)
            // 4. установка
            val ok: Boolean; var msg = ""
            if (Adb.isConnected || Adb.isRoot) {
                val run = InstRun()
                val poll = scope.launch {
                    while (true) {
                        val p = (run.overall * 100).toInt().coerceIn(0, 100)
                        startFg(getString(R.string.silent_notif_title), label, p, 100)
                        win(1, label, info.first, infoText, meta?.icon, pct = p, newName = info.second, newCode = newCode)
                        reply(R_PROGRESS, "progress", info.first, info.second, p.toString())
                        delay(700)
                    }
                }
                val r = installWithProgress(app, listOf(af), mapOf(af.name to meta), run)
                poll.cancel()
                ok = r.second == 0 && r.first > 0
                if (!ok) msg = run.fails.joinToString("; ")
            } else {
                val r = SessionInstall.install(app, listOf(f))
                ok = r.ok; msg = r.message
                if (r.cancelled) { win(4, label, info.first, infoText, meta?.icon); reply(R_CANCELLED, "cancelled", info.first, info.second, msg); return }
            }
            if (ok) {
                win(2, label, info.first, infoText, meta?.icon, pct = 100, newName = info.second, newCode = newCode)
                reply(R_OK, "ok", info.first, info.second)
            } else {
                win(3, label, info.first, infoText, meta?.icon, text = msg)
                reply(R_ERROR, "error", info.first, info.second, msg)
            }
            // окно не на экране (запуск из фона заблокирован системой): итог остаётся обычным уведомлением, по нажатию откроет окно
            if (!SilentInstallActivity.visible) postResult(if (ok) getString(R.string.silent_win_done, label, info.second, newCode.toInt()) else getString(R.string.ii_fail_generic) + (if (msg.isNotBlank()) ": $msg" else ""))
        } finally { f.delete() }
    }

    private fun windowIntent() = Intent(applicationContext, SilentInstallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    private suspend fun showWindow() { withContext(Dispatchers.Main) { runCatching { startActivity(windowIntent()) } } }

    private fun postResult(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = android.app.PendingIntent.getActivity(this, 0, windowIntent(), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val b = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(getString(R.string.silent_notif_title)).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi).setAutoCancel(true)
        runCatching { nm.notify(NOTIF_ID + 1, b.build()) }
    }

    // ---------- проверки подписи ----------
    /**
     * Вызывающий: либо подпись как у ADB-phon, либо программа из списка наших ([TRUSTED]; у них свои ключи подписи). Ставится только APK,
     * подписанный так же, как вызывающий: программа обновляет и ставит лишь настоящие выпуски собственного ключа.
     */
    private fun trusted(pkg: String): Boolean {
        if (runCatching { packageManager.checkSignatures(pkg, packageName) == PackageManager.SIGNATURE_MATCH }.getOrDefault(false)) return true
        return pkg in TRUSTED && runCatching { packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }

    /**
     * Сертификаты подписантов (SHA-256). 15.0 код 75: у ФАЙЛА вне системы (getPackageArchiveInfo) Android 9 и 10 заполняет только устаревшее поле `signatures`
     * (флаг GET_SIGNATURES), а `signingInfo` оставляет пустым; у установленного пакета заполнено `signingInfo`. Раньше читалось одно `signingInfo` на Android 9+, поэтому
     * подпись настоящего выпуска «не находилась» и проверка отвечала «apk signature differs from the caller» (найдено на Galaxy A7 и S9, Android 10).
     * Теперь читаются оба поля; источник возвращается в [src].
     */
    @Suppress("DEPRECATION")
    private fun certs(info: android.content.pm.PackageInfo?, src: StringBuilder? = null): Set<String> {
        if (info == null) return emptySet()
        val fromNew = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else null
        val sigs = fromNew ?: info.signatures
        src?.append(if (fromNew != null) "signingInfo" else if (info.signatures != null) "signatures" else "нет")
        return sigs.orEmpty().map { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    /**
     * Ставится только APK с тем же набором сертификатов подписи, что у установленного вызывающего (строгое равенство множеств, как и раньше; защита не ослаблена).
     * Для файла запрашиваются оба флага (GET_SIGNING_CERTIFICATES и GET_SIGNATURES), см. [certs].
     */
    @Suppress("DEPRECATION")
    private fun sameSignerApk(f: File, caller: String): Boolean {
        val pm = packageManager
        val flags = (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0) or PackageManager.GET_SIGNATURES
        val s1 = StringBuilder(); val s2 = StringBuilder()
        val mine = certs(runCatching { pm.getPackageInfo(caller, flags) }.getOrNull(), s1)
        val theirs = certs(runCatching { pm.getPackageArchiveInfo(f.path, flags) }.getOrNull(), s2)
        val ok = mine.isNotEmpty() && theirs.isNotEmpty() && mine == theirs
        android.util.Log.i("AdbPhonSilent", "подпись: caller=$caller SDK=${Build.VERSION.SDK_INT} mine=${mine.map { it.take(8) }}(${s1}) apk=${theirs.map { it.take(8) }}(${s2}) -> $ok")
        return ok
    }

    private fun archiveInfo(f: File): Triple<String, String, String>? = runCatching {
        val pi = packageManager.getPackageArchiveInfo(f.path, 0) ?: return null
        pi.applicationInfo?.let { it.sourceDir = f.path; it.publicSourceDir = f.path }
        Triple(pi.packageName, pi.versionName.orEmpty(), pi.applicationInfo?.let { packageManager.getApplicationLabel(it).toString() }.orEmpty())
    }.getOrNull()

    // ---------- уведомление службы ----------
    private fun startFg(title: String, text: String, progress: Int, max: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.silent_notif_channel), NotificationManager.IMPORTANCE_LOW))
        val b = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText(text).setOngoing(true)
            .setContentIntent(android.app.PendingIntent.getActivity(this, 0, windowIntent(), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
        if (max > 0) b.setProgress(max, progress, false) else b.setProgress(100, 0, false)   // 20.11.2026: без бегущей полоски
        val n = b.build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTIF_ID, n)
    }

    companion object {
        const val ACTION = "app.adbphon.action.INSTALL_SILENT"
        const val RESULT_ACTION = "app.adbphon.action.INSTALL_RESULT"
        /** Наши программы с собственными ключами подписи (Меню приложений, Pulsar, Vision). */
        val TRUSTED = setOf("app.adbphon", "app.tvapps", "app.click", "app.torplayer")
        const val R_OK = 0
        const val R_PROGRESS = 1
        const val R_STARTED = 2
        const val R_CANCELLED = 3
        const val R_DENIED = 10
        const val R_BADSIG = 11
        const val R_ERROR = 12
        private const val CHANNEL = "silent_install"
        private const val NOTIF_ID = 7711
    }
}
