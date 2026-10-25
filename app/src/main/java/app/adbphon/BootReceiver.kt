package app.adbphon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/** После загрузки заново включает закреплённые службы спецвозможностей (Android 14 часто выключает их при перезапуске). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        apply(context)
    }

    companion object {
        fun pinned(ctx: Context): List<String> =
            ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getString("pinned_access", "").orEmpty()
                .split(",").filter { it.isNotBlank() }

        fun apply(ctx: Context) {
            val pins = pinned(ctx)
            if (pins.isEmpty()) return
            if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) {
                Log.w("ADBphon", "boot: нет права WRITE_SECURE_SETTINGS")
                return
            }
            val cr = ctx.contentResolver
            val cur = Settings.Secure.getString(cr, "enabled_accessibility_services").orEmpty().split(":").filter { it.isNotBlank() }
            val missing = pins.filter { it !in cur }
            if (missing.isEmpty()) return
            Settings.Secure.putString(cr, "enabled_accessibility_services", (cur + missing).joinToString(":"))
            Settings.Secure.putInt(cr, "accessibility_enabled", 1)
            Log.i("ADBphon", "boot: включены службы $missing")
        }
    }
}
