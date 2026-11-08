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
import android.os.PowerManager
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * Служба на время входа в облако: пока владелица вводит пароль и 2FA в браузере (минуты), приложение свёрнуто, и система
 * могла остановить процесс программы вместе с движком входа (тогда браузер после выбора аккаунта показывал «сайт недоступен»).
 * Служба переднего плана с уведомлением «Вход в облако…» и блокировкой сна держит процесс живым; «Отмена» в уведомлении останавливает вход.
 */
class CloudLoginService : Service() {
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") {
            CloudAuth.reset()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, getString(R.string.cl_login_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 2, Intent(this, CloudLoginService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this).setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.cl_login_notif_title)).setContentText(getString(R.string.cl_login_notif_text)).setContentIntent(open)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), getString(R.string.btn_cancel), cancel).build())
            .also { if (android.os.Build.VERSION.SDK_INT >= 26) it.setChannelId(CH) }.build()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        if (wl == null) {
            wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "adbphon:cloudlogin").apply { setReferenceCounted(false); acquire(25 * 60_000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wl?.takeIf { it.isHeld }?.release() }; wl = null
        super.onDestroy()
    }

    companion object {
        const val CH = "cloudlogin"; const val ID = 4108
        fun start(ctx: Context) { runCatching { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, CloudLoginService::class.java)) } }
        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, CloudLoginService::class.java)) } }
    }
}

/**
 * Страница на 127.0.0.1:53682 после входа. Движок входа закрывает порт сразу после первого возврата Google; браузер, который
 * открыл адрес повторно (обновление, второй переход, предзагрузка), видел «Этот сайт недоступен». Пока идёт эта минута,
 * на порту отвечает программа: «Готово» после успеха или «Вход не завершён» после ошибки.
 */
object LoginEcho {
    const val PORT = 53682
    @Volatile private var srv: ServerSocket? = null

    private fun bind(): ServerSocket? = runCatching {
        ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 8) }
    }.getOrNull()

    /** Освобождает порт перед новым входом: наша страница закрывается, забытые процессы движка этого приложения завершаются. */
    fun freePort() {
        stop()
        repeat(2) { attempt ->
            val s = bind()
            if (s != null) { runCatching { s.close() }; return }
            if (attempt == 0) strayKill()
            Thread.sleep(700)
        }
    }

    private fun strayKill() {
        runCatching {
            File("/proc").listFiles()?.forEach { d ->
                val pid = d.name.toIntOrNull() ?: return@forEach
                if (pid == android.os.Process.myPid()) return@forEach
                val cmd = runCatching { File(d, "cmdline").readText() }.getOrDefault("")
                if (cmd.contains("libclouds.so") && cmd.contains("authorize")) runCatching { android.os.Process.killProcess(pid) }
            }
        }
    }

    @Synchronized fun stop() { runCatching { srv?.close() }; srv = null }

    @Synchronized fun start(ctx: Context, ok: Boolean, ttlMs: Long = 600_000) {
        stop()
        val s = bind() ?: return
        srv = s
        val lang = ctx.resources.configuration.locales[0].language.takeIf { it in setOf("ru", "uk", "en") } ?: "ru"
        val html = "<!DOCTYPE html><html lang=\"" + lang + "\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<body style=\"font:20px sans-serif;text-align:center;margin:18vh 16px 0\"><h1>" + ctx.getString(if (ok) R.string.cl_echo_ok_title else R.string.cl_echo_fail_title) + "</h1><p>" +
            ctx.getString(if (ok) R.string.cl_echo_ok_text else R.string.cl_echo_fail_text) + "</p><a href=\"intent://open#Intent;scheme=adbphon;package=app.adbphon;end\" style=\"color:#0f766e\">ADB-phon</a></body></html>"
        val body = html.toByteArray(Charsets.UTF_8)
        Thread {
            val end = System.currentTimeMillis() + ttlMs
            runCatching { s.soTimeout = 1000 }
            while (!s.isClosed && System.currentTimeMillis() < end) {
                try {
                    val c = s.accept()
                    Thread {
                        runCatching {
                            c.soTimeout = 2000
                            val first = c.getInputStream().bufferedReader().readLine().orEmpty()
                            val icon = first.contains("favicon")
                            val head = (if (icon) "HTTP/1.1 204 No Content\r\n" else "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n") + "Connection: close\r\n\r\n"
                            c.getOutputStream().apply { write(head.toByteArray()); if (!icon) write(body); flush() }
                        }
                        runCatching { c.close() }
                    }.start()
                } catch (_: SocketTimeoutException) { } catch (_: Exception) { break }
            }
            runCatching { s.close() }
            synchronized(this) { if (srv === s) srv = null }
        }.apply { isDaemon = true; start() }
    }
}

/** Возврат в ADB-phon после входа в облако: прямой запуск (если система разрешает из фона) и уведомление-запас. */
object CloudReturn {
    private const val ID = 4109

    fun canOverlay(ctx: Context) = runCatching { android.provider.Settings.canDrawOverlays(ctx) }.getOrDefault(false)

    @Volatile var resumedAt = 0L

    /**
     * Цепочка «пробуем по очереди»: 1) прямой запуск (работает, если выдано «Поверх других окон» или приложение на виду);
     * 2) команда am start через ADB-связь приложения («этот телефон») или root: оболочка может запускать из фона;
     * 3) уведомление «Облако добавлено» с полноэкранным намерением (если у программы разрешены уведомления);
     * 4) страница «Готово» в браузере со ссылкой. Какой способ сработал, пишется в журнал (тег CloudReturn).
     */
    fun toFront(ctx: Context, skipDirect: Boolean = false) {
        val t0 = System.currentTimeMillis()
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        fun front() = resumedAt >= t0
        Thread {
            val tag = "CloudReturn"
            if (!skipDirect) runCatching { android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { ctx.startActivity(i) } } }
            Thread.sleep(1500)
            if (front()) { android.util.Log.i(tag, "способ: прямой запуск (поверх окон: " + canOverlay(ctx) + ")"); return@Thread }
            android.util.Log.i(tag, "прямой запуск заблокирован системой (поверх окон: " + canOverlay(ctx) + ")")
            if (Adb.isConnected) {
                runCatching { kotlinx.coroutines.runBlocking { Adb.shell("am start -n app.adbphon/.MainActivity -f 0x30020000", 8_000) } }
                Thread.sleep(1200)
                if (front()) { android.util.Log.i(tag, "способ: am start через ADB-связь приложения"); return@Thread }
                android.util.Log.i(tag, "через ADB-связь не вышло")
            } else android.util.Log.i(tag, "ADB-связь приложения не подключена")
            runCatching {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("cloudlogin_done", ctx.getString(R.string.cl_login_channel), NotificationManager.IMPORTANCE_HIGH))
                val pi = PendingIntent.getActivity(ctx, 3, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val n = Notification.Builder(ctx).setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(ctx.getString(R.string.cl_done_notif_title)).setContentText(ctx.getString(R.string.cl_done_notif_text))
                    .setContentIntent(pi).setFullScreenIntent(pi, true).setAutoCancel(true).also { if (android.os.Build.VERSION.SDK_INT >= 26) it.setChannelId("cloudlogin_done") }.build()
                nm.notify(ID, n)
                val shown = nm.areNotificationsEnabled()
                android.util.Log.i(tag, "способ: уведомление «Облако добавлено» " + (if (shown) "показано" else "НЕ показано: уведомления ADB-phon отключены") + "; осталась ссылка на странице «Готово»")
            }
        }.start()
    }

    fun cancelNotice(ctx: Context) { runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID) } }
}
