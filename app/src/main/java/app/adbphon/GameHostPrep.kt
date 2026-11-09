package app.adbphon

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Подготовка игрового устройства при подключении геймпада (слово владелицы 10.11.2026).
 * Если у хоста, к которому подключился геймпад, есть ADB-доступ (последний автосопряжённый хост, адрес в «last_auto»):
 *  1) освобождается память: `am kill-all` закрывает только фоновые кэшированные процессы, игру на экране не трогает;
 *  2) проверяется список контроллеров хоста: лишние виртуальные «Xbox 360 Controller» (остатки тестов и мостов) игра может
 *     принять за первый и не слушать этот телефон; показывается предупреждение, ничего не удаляется.
 * Выключатель: «Освобождать память игрового устройства». Любая ошибка (нет ADB, порт сменился) молча пропускается.
 */
object GameHostPrep {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastRun = 0L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("bt_auto", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("game_prep", true)
    fun setEnabled(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("game_prep", v).apply() }

    private fun freeMb(out: String): Long? = Regex("MemAvailable:\\s+(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull()?.div(1024)

    fun run(ctx: Context, hostAddr: String) {
        val app = ctx.applicationContext
        if (!enabled(app)) return
        val now = System.currentTimeMillis()
        if (now - lastRun < 60_000) return
        lastRun = now
        val last = prefs(app).getString("last_auto", null) ?: return
        val hp = last.substringBefore('|')
        val addr = last.substringAfter('|', "")
        if (!addr.equals(hostAddr, ignoreCase = true)) return    // ADB-доступ есть к другому устройству
        val host = hp.substringBeforeLast(':')
        val port = hp.substringAfterLast(':').toIntOrNull() ?: return
        scope.launch {
            val rs = RemoteShell(app, host, port)
            try {
                if (rs.open().isFailure) { AppLog.i("ADBphon", "game host: нет ADB к $host:$port, пропуск"); return@launch }
                val before = freeMb(rs.sh("grep MemAvailable /proc/meminfo", 8_000))
                rs.sh("am kill-all", 10_000)
                val after = freeMb(rs.sh("grep MemAvailable /proc/meminfo", 8_000))
                AppLog.i("ADBphon", "game host: свободно $before → $after МБ")
                val list = rs.sh("dumpsys input | grep -E '^ +[0-9]+: '", 10_000)
                val extra = list.lines().mapNotNull { Regex("^\\s*\\d+:\\s*(.+)$").find(it)?.groupValues?.get(1)?.trim() }
                    .filter { n -> n.contains("Xbox 360 Controller", true) || n.contains("vpad", true) || n.contains("uhid", true) }
                    .distinct()
                if (extra.isNotEmpty()) {
                    AppLog.i("ADBphon", "game host: лишние контроллеры $extra")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(app, app.getString(R.string.gph_extra, extra.joinToString(", ")), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Throwable) {
                AppLog.i("ADBphon", "game host: ${e.message}")
            } finally {
                runCatching { rs.close() }
            }
        }
    }
}
