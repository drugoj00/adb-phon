package app.adbphon

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Своя сеть Wi-Fi этого устройства («локальная точка доступа» Android): другое устройство подключается к ней напрямую, роутер не нужен.
 * Интернета в этой сети нет, она нужна только для передачи файлов. Имя и пароль сети выдаёт система.
 */
object WifiHotspot {
    var active by mutableStateOf(false)
    var ssid by mutableStateOf("")
    var pass by mutableStateOf("")
    var error by mutableStateOf("")
    private var res: WifiManager.LocalOnlyHotspotReservation? = null

    fun perms(): Array<String> = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasPerm(ctx: Context): Boolean = perms().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Запрос разрешения у пользователя; после ответа он снова нажимает кнопку. */
    fun askPerm(act: Activity) { act.requestPermissions(perms(), 7710) }

    fun start(ctx: Context, onReady: () -> Unit) {
        error = ""
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    res = reservation
                    if (Build.VERSION.SDK_INT >= 30) {
                        val c = reservation.softApConfiguration
                        ssid = c.ssid.orEmpty(); pass = c.passphrase.orEmpty()
                    } else {
                        @Suppress("DEPRECATION") val c = reservation.wifiConfiguration
                        ssid = c?.SSID.orEmpty(); pass = c?.preSharedKey.orEmpty()
                    }
                    active = true
                    onReady()
                }
                override fun onStopped() { active = false; res = null }
                override fun onFailed(reason: Int) {
                    active = false
                    error = ctx.getString(when (reason) { ERROR_NO_CHANNEL -> R.string.ws_hot_nochan; ERROR_TETHERING_DISALLOWED -> R.string.ws_hot_denied; ERROR_INCOMPATIBLE_MODE -> R.string.ws_hot_mode; else -> R.string.ws_hot_fail })
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            error = ctx.getString(R.string.ws_hot_perm)
        } catch (e: Exception) {
            error = ctx.getString(R.string.ws_hot_fail) + " " + (e.message ?: "")
        }
    }

    fun stop() { runCatching { res?.close() }; res = null; active = false }
}
