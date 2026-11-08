package app.adbphon

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Автоуправление Wi-Fi для сетевых источников «Файлов»: если Wi-Fi был выключен, при открытии сетевого источника он включается (через ADB этого устройства
 * или root), а при уходе из сетевых источников выключается обратно; если Wi-Fi уже был включён, ничего не выключается. Android 10+ не даёт приложению
 * переключать Wi-Fi самому, поэтому без ADB/root показывается подсказка с кнопкой системной панели. Настройка «Автоуправление Wi-Fi» (по умолчанию включено).
 */
object WifiAuto {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var releaseJob: Job? = null
    /** Wi-Fi включили мы (и значит должны вернуть выключенным). */
    @Volatile var weEnabled = false

    fun enabled(ctx: Context) = ctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE).getBoolean("wifi_auto", true)
    fun setEnabled(ctx: Context, v: Boolean) { ctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE).edit().putBoolean("wifi_auto", v).apply(); if (!v) weEnabled = false }

    private fun wm(ctx: Context) = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    fun isOn(ctx: Context) = runCatching { wm(ctx).isWifiEnabled }.getOrDefault(true)

    /** Какой канал доступен: оболочка ADB этого же устройства (в том числе root). null: нет. */
    private suspend fun shell(cmd: String): String? {
        if (Adb.isConnected && Adb.currentHost.isEmpty()) return runCatching { Adb.shell(cmd) }.getOrNull()
        return null
    }

    /** Вызывается при открытии сетевого источника. Возвращает false, если Wi-Fi выключен и включить его не удалось (показана подсказка). */
    suspend fun onNetOpen(ctx: Context): Boolean {
        releaseJob?.cancel(); releaseJob = null
        if (!enabled(ctx) || isOn(ctx)) return true
        Fm.say(Fm.str(R.string.wifi_turning_on))
        val out = shell("svc wifi enable")
        if (out == null) { Fm.say(Fm.str(R.string.wifi_need_manual)); runCatching { ctx.startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; return false }
        weEnabled = true
        var t = 0
        while (t < 15) { if (connected(ctx)) return true; delay(1000); t++ }
        return connected(ctx)
    }

    private fun connected(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    /** Источник покинут (другая папка не по сети, другой источник, уход с вкладки, сворачивание): через несколько секунд вернуть Wi-Fi выключенным, если включали мы. */
    fun maybeRelease(ctx: Context, delayMs: Long = 8000) {
        if (!weEnabled) return
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(delayMs)
            if (!weEnabled || !enabled(ctx)) return@launch
            if (NetKeep.anyOpen()) return@launch   // сетевой источник открыт (в том числе при свёрнутом приложении): Wi-Fi не отпускается
            if (Fm.jobs.isNotEmpty()) { maybeRelease(ctx, 5000); return@launch }               // идёт копирование: ждём конца
            if (!isOn(ctx)) { weEnabled = false; return@launch }                           // пользователь сам выключил
            if (Adb.isConnected && Adb.currentHost.isNotEmpty()) return@launch              // управляем другим устройством: его Wi-Fi не трогаем
            if (shell("svc wifi disable") != null) weEnabled = false
        }
    }
}
