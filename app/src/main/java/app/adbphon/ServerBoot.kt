package app.adbphon

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Запуск «ADB-phon Server» после перезагрузки (как у Shizuku): сам включает отладку по Wi-Fi, подключается, запускает сервер и
 * (по настройке) снова выключает отладку. Нужны: Android 11+, право WRITE_SECURE_SETTINGS (выдаётся по ADB один раз, программа
 * делает это сама), сопряжение, сделанное раньше, и включённый Wi-Fi. Если Wi-Fi к этому времени ещё не поднялся, попытка
 * повторяется через 20 секунд (до 8 раз).
 */
object ServerBoot {
    const val ACTION_RETRY = "app.adbphon.SERVER_RETRY"

    fun go(ctx: Context, attempt: Int, pr: BroadcastReceiver.PendingResult?) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ok = runCatching { run(ctx.applicationContext) }.getOrDefault(false)
                val shz = runCatching { ShizukuHost.boot(ctx.applicationContext) }.getOrDefault(false)   // сервер Shizuku (если запускали раньше)
                if (!ok || !shz) { retry(ctx.applicationContext, attempt); return@launch }
                if (!ok) retry(ctx.applicationContext, attempt)
            } finally { runCatching { pr?.finish() } }
        }
    }

    private fun retry(ctx: Context, attempt: Int) {
        if (attempt >= 8) return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(ctx, 77, Intent(ctx, BootReceiver::class.java).setAction(ACTION_RETRY).putExtra("n", attempt + 1),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 20_000, pi)
    }

    /** true: сервер работает (или ничего делать не нужно). false: не вышло, нужна повторная попытка. */
    private suspend fun run(ctx: Context): Boolean {
        AdbServer.init(ctx)
        AppLog.i("ADBphon", "автозапуск сервера: auto=${AdbServer.auto} sdk=${Build.VERSION.SDK_INT} root=${Adb.isRoot} право=${WifiDebug.canWrite(ctx)} userOff=${WifiDebug.userOff(ctx)}")
        if (!AdbServer.auto || Build.VERSION.SDK_INT < 30 || Adb.isRoot) return true
        if (AdbServer.ping()) return true
        if (!WifiDebug.canWrite(ctx)) return true   // нет права WRITE_SECURE_SETTINGS. Флаг «пользователь сам выключил отладку» здесь не учитывается: режим сервера как раз включает её на несколько секунд и снова выключает; отключается настройкой AdbServer.auto
        runCatching { Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 1) }
        delay(1500)
        if (!Adb.isConnected) Adb.connectWireless(ctx, null)
        AppLog.i("ADBphon", "автозапуск сервера: связь ADB=${Adb.isConnected}")
        if (!Adb.isConnected) return false
        val r = Adb.serverStart(ctx)
        if (r.isFailure) return false
        if (AdbServer.offAfter) runCatching { Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 0) }
        return true
    }
}
