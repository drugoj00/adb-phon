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
        reply(R_STARTED, "started")
        // 2. файл во временную папку (разрешение на чтение выдал клиент)
        val dir = File(cacheDir, "silent").apply { mkdirs() }
        val f = File(dir, "s" + System.nanoTime() + ".apk")
        try {
            withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { ins -> f.outputStream().use { o -> ins.copyTo(o) } } ?: error("no stream") }
        } catch (e: Throwable) { reply(R_ERROR, "error", msg = "read: " + e.message); f.delete(); return }
        try {
            // 3. подпись самого APK тоже наша (даже при подмене имени вызывающего чужой файл не ставится)
            val info = withContext(Dispatchers.IO) { archiveInfo(f) }
            if (info == null) { reply(R_ERROR, "error", msg = "not an apk"); return }
            if (!sameSignerApk(f, caller)) { reply(R_BADSIG, "badsig", info.first, info.second, "apk signature differs from the caller"); return }
            val label = info.third.ifBlank { info.first }
            startFg(getString(R.string.silent_notif_title), label, 0, 100)
            // 4. установка
            val ok: Boolean; var msg = ""
            if (Adb.isConnected || Adb.isRoot) {
                val af = ApkFile(f.name, Uri.fromFile(f), f.length(), f)
                val meta = withContext(Dispatchers.IO) { runCatching { ApkInfo.readFor(app, af) }.getOrNull() }
                val run = InstRun()
                val poll = scope.launch {
                    while (true) {
                        val p = if (run.all > 0) (run.done * 100 / run.all).toInt().coerceIn(0, 100) else 0
                        startFg(getString(R.string.silent_notif_title), label, p, 100)
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
                if (r.cancelled) { reply(R_CANCELLED, "cancelled", info.first, info.second, msg); return }
            }
            if (ok) reply(R_OK, "ok", info.first, info.second) else reply(R_ERROR, "error", info.first, info.second, msg)
        } finally { f.delete() }
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

    @Suppress("DEPRECATION")
    private fun certs(info: android.content.pm.PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return sigs.orEmpty().map { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun sameSignerApk(f: File, caller: String): Boolean {
        val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val mine = certs(runCatching { packageManager.getPackageInfo(caller, flag) }.getOrNull())
        val theirs = certs(runCatching { packageManager.getPackageArchiveInfo(f.path, flag) }.getOrNull())
        return mine.isNotEmpty() && theirs.isNotEmpty() && mine == theirs
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
        if (max > 0) b.setProgress(max, progress, false) else b.setProgress(0, 0, true)
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
