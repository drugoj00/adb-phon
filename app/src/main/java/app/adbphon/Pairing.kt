package app.adbphon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * Простое сопряжение с беспроводной отладкой (Android 11+): порт сопряжения программа находит сама (mDNS),
 * а 6-значный код вводится в уведомлении, не уходя с экрана настроек.
 */
object Pairing {
    /** Порт сопряжения: больше нуля, пока в настройках открыто окно «Подключение с кодом сопряжения». */
    var port by mutableIntStateOf(0)
        private set
    @Volatile var host: InetAddress? = null
    private var mdns: AdbMdns? = null

    /** Пользователь нажал «Подключить автоматически»: служба спецвозможностей сама ведёт по настройкам и читает код. */
    @Volatile var autoWanted = false

    fun autoStart() {
        autoWanted = true
        CoroutineScope(Dispatchers.Default).launch { kotlinx.coroutines.delay(180_000); autoWanted = false }
    }

    /** Сопряжение с кодом, прочитанным из окна настроек. */
    fun autoPair(ctx: Context, host: String, port: Int, code: String) {
        val app = ctx.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            Adb.awaitReady()
            val r = Adb.pair(port, code, host)
            if (r.isSuccess) {
                var ok = false
                repeat(4) { if (!ok) { Adb.connectWireless(app, null); ok = Adb.isConnected; if (!ok) kotlinx.coroutines.delay(1500) } }
                Conn.refresh()
                autoWanted = false
                showResult(app, app.getString(if (ok) R.string.notif_ok else R.string.notif_paired_only))
                // вернуться в программу
                runCatching { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            } else {
                showResult(app, app.getString(R.string.notif_fail))
            }
        }
    }

    private const val CHANNEL = "adb_pair"
    const val NOTIF_ID = 4021
    const val KEY_CODE = "code"
    const val ACTION = "app.adbphon.PAIR_CODE"

    fun start(ctx: Context) {
        if (mdns?.isRunning == true) return
        val app = ctx.applicationContext
        mdns = AdbMdns(app, AdbMdns.SERVICE_TYPE_TLS_PAIRING, AdbMdns.OnAdbDaemonDiscoveredListener { h, p ->
            host = h
            port = if (p > 0) p else 0
        }).also { runCatching { it.start() } }
    }

    fun stop() {
        runCatching { mdns?.stop() }
        mdns = null
        port = 0
    }

    private fun builder(ctx: Context): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(ctx)

    private fun manager(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_HIGH))
        }
        return nm
    }

    /** Уведомление с полем для кода сопряжения. */
    fun showInput(ctx: Context, text: String = ctx.getString(R.string.notif_enter_code)) {
        val nm = manager(ctx)
        val ri = RemoteInput.Builder(KEY_CODE).setLabel(ctx.getString(R.string.pair_code)).build()
        val pi = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, PairReceiver::class.java).setAction(ACTION).setPackage(ctx.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
        )
        val action = Notification.Action.Builder(android.R.drawable.ic_menu_send, ctx.getString(R.string.notif_action), pi).addRemoteInput(ri).build()
        val n = builder(ctx)
        n.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(ctx.getString(R.string.notif_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .addAction(action)
        nm.notify(NOTIF_ID, n.build())
    }

    fun showResult(ctx: Context, text: String) {
        val nm = manager(ctx)
        val n = builder(ctx)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(ctx.getString(R.string.notif_title))
            .setContentText(text)
            .setAutoCancel(true)
            .apply { if (Build.VERSION.SDK_INT >= 26) setTimeoutAfter(15000) }
            .build()
        nm.notify(NOTIF_ID, n)
    }

    fun cancel(ctx: Context) { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) }
}

/** Принимает код из уведомления, сопрягается и подключается. */
class PairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Pairing.KEY_CODE)?.toString()?.filter(Char::isDigit).orEmpty()
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Adb.awaitReady()
                val port = Pairing.port
                val host = Pairing.host?.hostAddress
                if (code.length < 6) {
                    Pairing.showInput(app, app.getString(R.string.notif_code_short))
                } else if (port <= 0 || host == null) {
                    Pairing.showInput(app, app.getString(R.string.notif_no_port))
                } else {
                    val r = Adb.pair(port, code, host)
                    if (r.isSuccess) {
                        // после сопряжения порт подключения тоже находится сам
                        var ok = false
                        repeat(3) { if (!ok) { Adb.connectWireless(app, null); ok = Adb.isConnected; if (!ok) kotlinx.coroutines.delay(1500) } }
                        Conn.refresh()
                        Pairing.showResult(app, app.getString(if (ok) R.string.notif_ok else R.string.notif_paired_only))
                        if (ok) Pairing.stop()
                    } else {
                        Pairing.showInput(app, app.getString(R.string.notif_fail))
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
