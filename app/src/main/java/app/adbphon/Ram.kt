package app.adbphon

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Очистка ОЗУ: завершает фоновые процессы других приложений (то же, что система делает при нехватке памяти). */
object Ram {
    private fun freeMb(ctx: Context): Long {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.availMem / (1024 * 1024)
    }

    /** Возвращает строку для сообщения: сколько освободилось и сколько свободно. */
    suspend fun clean(ctx: Context): String {
        val before = freeMb(ctx)
        withContext(Dispatchers.IO) {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            runCatching {
                ctx.packageManager.getInstalledApplications(0).forEach { ai ->
                    if (ai.packageName != ctx.packageName) runCatching { am.killBackgroundProcesses(ai.packageName) }
                }
            }
        }
        delay(900) // ядру нужно мгновение, чтобы вернуть память
        val after = freeMb(ctx)
        val freed = (after - before).coerceAtLeast(0)
        return if (freed > 0) ctx.getString(R.string.ram_done, freed, after) else ctx.getString(R.string.ram_done_zero, after)
    }
}
