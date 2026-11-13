package app.adbphon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Установка «В фоне» (7.6): окно «Установка» или окно хода закрывается, установка идёт дальше (её ведёт процесс программы, а служба переднего плана
 * не даёт системе его остановить даже при закрытом окне), в шторке уведомление с ходом (n из N, байты) и итогом; по нажатию открывается окно хода или журнал.
 * Ход ведёт тот же InstRun, что и в окне «Установка», поэтому окно можно открыть снова.
 */
object InstBg {
    /** «В фоне» нажато в окне «Установка»: следующий запуск установки не показывает окно хода, а сразу уходит в фон. */
    @Volatile var nextBackground = false
    /** Последний запущенный в фоне ход: по нажатию на уведомление его окно открывается снова. */
    @Volatile var last: InstRun? = null
    @Volatile var active: InstRun? = null
    /** Окно хода, открытое из уведомления (корень MainActivity). */
    var show by mutableStateOf<InstRun?>(null)

    /** Окно хода, если запуск не фоновый; иначе сразу фоновая служба. */
    fun launch(ctx: Context, run: InstRun, showDialog: (InstRun) -> Unit) {
        if (nextBackground) { nextBackground = false; begin(ctx, run) } else showDialog(run)
    }

    fun begin(ctx: Context, run: InstRun) {
        run.background = true
        last = run; active = run
        runCatching { androidx.core.content.ContextCompat.startForegroundService(ctx.applicationContext, Intent(ctx.applicationContext, InstBgService::class.java)) }
            .onFailure { AppLog.e("ADBphon", "install bg start", it) }
    }

    fun openFromNotification() { last?.let { show = it } }
}

/** Окно хода, открытое из уведомления (подключается в корне приложения). */
@Composable
fun InstBgHost() {
    val r = InstBg.show ?: return
    InstallRunDialog(FmDlg.InstallRunDlg(r)) { InstBg.show = null }
}

class InstBgService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: kotlinx.coroutines.Job? = null

    override fun onBind(i: Intent?): IBinder? = null

    private fun open(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("inst_bg", true)
        return PendingIntent.getActivity(this, 4108, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun progress(run: InstRun?): Notification {
        val b = Notification.Builder(this).setSmallIcon(R.drawable.ic_stat_files).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open())
        if (android.os.Build.VERSION.SDK_INT >= 26) b.setChannelId(CH)
        if (run == null) { b.setContentTitle(getString(R.string.ii_prog_title)).setProgress(0, 0, true); return b.build() }
        b.setContentTitle(getString(R.string.ii_prog_title) + if (run.total > 1) "  ·  " + getString(R.string.ii_prog_count, run.index.coerceAtLeast(1), run.total) else "")
        val mb = { v: Long -> String.format(java.util.Locale.US, "%.1f", v / 1048576.0) }
        b.setContentText(listOfNotNull(run.name.ifEmpty { null }, if (run.all > 1) getString(R.string.ii_mb, mb(run.done) + " " + getString(R.string.unit_mb), mb(run.all) + " " + getString(R.string.unit_mb)) else null).joinToString("  ·  "))
        if (run.phase == 2 || run.all <= 1) b.setProgress(0, 0, true) else b.setProgress(1000, ((run.done.toDouble() / run.all.coerceAtLeast(1L)) * 1000).toInt().coerceIn(0, 1000), false)
        val stop = PendingIntent.getService(this, 4109, Intent(this, InstBgService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        if (run.total > 1) b.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), getString(R.string.ii_stop), stop).build())
        return b.build()
    }

    private fun done(run: InstRun): Notification {
        val b = Notification.Builder(this).setSmallIcon(R.drawable.ic_stat_files).setAutoCancel(true).setContentIntent(open())
        if (android.os.Build.VERSION.SDK_INT >= 26) b.setChannelId(CH)
        val title = if (run.bad == 0) getString(R.string.ii_done_all) else getString(R.string.install_result_n, run.ok, run.bad)
        b.setContentTitle(title)
        if (run.fails.isNotEmpty()) {
            val t = run.fails.take(8).joinToString("\n") { "− $it" }
            b.setContentText(run.fails.first()).setStyle(Notification.BigTextStyle().bigText(t))
        } else if (run.name.isNotEmpty()) b.setContentText(run.name)
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") InstBg.active?.stop = true
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, getString(R.string.ib_channel), NotificationManager.IMPORTANCE_LOW))
        val first = progress(InstBg.active)
        runCatching { if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, first) }
        if (loop == null) loop = scope.launch {
            while (true) {
                val run = InstBg.active
                if (run == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); break }
                if (run.finished) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    nm.notify(ID_DONE, done(run))
                    InstBg.active = null
                    runCatching { Hooks.reloadApps() }
                    stopSelf(); break
                }
                nm.notify(ID, progress(run))
                delay(500)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { loop?.cancel(); super.onDestroy() }

    companion object { const val CH = "install_bg"; const val ID = 4108; const val ID_DONE = 4112 }
}
