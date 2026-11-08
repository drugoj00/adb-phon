package app.adbphon

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Установка набора APK (пакет из нескольких частей) собственной сессией PackageInstaller: ADB и root не нужны.
 * Система один раз спрашивает подтверждение (нужно разрешение «Установка неизвестных приложений» для этой программы).
 */
object SessionInstall {
    /** Результат сессии: [code] 0 успех, 3 отменено пользователем, остальное ошибка (-1 время вышло, -2 сбой записи). */
    class Result(val code: Int, val message: String) { val ok get() = code == 0; val cancelled get() = code == PackageInstaller.STATUS_FAILURE_ABORTED }

    suspend fun install(ctx: Context, parts: List<File>): Result {
        if (parts.isEmpty()) return Result(-2, "no files")
        val app = ctx.applicationContext
        val pi = app.packageManager.packageInstaller
        val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        val action = "app.adbphon.INSTALL_RESULT_" + id
        val done = CompletableDeferred<Result>()
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { c.startActivity(confirm) }.onFailure { done.complete(Result(-2, it.message.orEmpty())) }
                    }
                    PackageInstaller.STATUS_SUCCESS -> done.complete(Result(0, ""))
                    else -> done.complete(Result(i.getIntExtra(PackageInstaller.EXTRA_STATUS, -2), i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()))
                }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(app, rx, IntentFilter(action), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            withContext(Dispatchers.IO) {
                pi.openSession(id).use { s ->
                    for (f in parts) f.inputStream().use { i -> s.openWrite(f.name, 0, f.length()).use { o -> i.copyTo(o); s.fsync(o) } }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    s.commit(PendingIntent.getBroadcast(app, id, Intent(action).setPackage(app.packageName), flags).intentSender)
                }
            }
            return withTimeoutOrNull(10 * 60_000L) { done.await() } ?: Result(-1, "timeout")
        } catch (e: Throwable) {
            runCatching { pi.abandonSession(id) }
            return Result(-2, e.message.orEmpty())
        } finally { runCatching { app.unregisterReceiver(rx) } }
    }
}
