package app.adbphon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/** Только для debug-сборки: проверка подключения и сопряжения командой am broadcast (без нажатий по экрану). */
@OptIn(DelicateCoroutinesApi::class)
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val op = intent.getStringExtra("op") ?: return
        GlobalScope.launch {
            val r: Any = runCatching {
                when (op) {
                    "pair" -> Adb.pair(intent.getIntExtra("port", 0), intent.getStringExtra("code") ?: "", intent.getStringExtra("host") ?: "127.0.0.1")
                    "connect" -> Adb.connectWireless(app, intent.getIntExtra("port", 0).takeIf { it > 0 })
                    "tcp" -> Adb.connectLocal(intent.getIntExtra("port", 5555))
                    "boot" -> { BootReceiver.apply(app); "boot ok" }
                    "shell" -> Adb.shell(intent.getStringExtra("cmd") ?: "id")
                    else -> "unknown op"
                }
            }.getOrElse { it }
            Log.i("ADBphonDebug", "$op -> $r")
        }
    }
}
