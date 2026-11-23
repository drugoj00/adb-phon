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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Установка набора APK (пакет из нескольких частей) собственной сессией PackageInstaller: ADB и root не нужны.
 * Система один раз спрашивает подтверждение (нужно разрешение «Установка неизвестных приложений» для этой программы).
 *
 * 22.11.2026 (для 18.0, жалоба «нажала „Установить“, процесса нет, вылезло опять „Установить“»): установка идёт под защитой [InstallGuard]
 * (тот же пакет и файл второй раз не запускается, второе системное окно не открывается), системное окно подтверждения открывается
 * один раз на сессию, ход (передача файла, ожидание ответа в системном окне) сообщается через [onStep].
 */
object SessionInstall {
    /** Результат сессии: [code] 0 успех, 3 отменено пользователем, остальное ошибка (-1 время вышло, -2 сбой записи, [BUSY] такая установка уже идёт). */
    class Result(val code: Int, val message: String) {
        val ok get() = code == 0
        val cancelled get() = code == PackageInstaller.STATUS_FAILURE_ABORTED
        val busy get() = code == BUSY
    }

    const val BUSY = -3

    /** Шаги хода для окна: 1 передача файла (done/all в байтах), 2 сессия закрыта, идёт установка, 4 открыто системное окно подтверждения. */
    fun interface Step { fun on(phase: Int, done: Long, all: Long) }

    /** Ключ защиты от повторного запуска: пакет файла + число частей + общий размер (не зависит от пути копии). Вызывать не в главном потоке. */
    fun guardKey(ctx: Context, parts: List<File>): String {
        val info = parts.firstOrNull()?.let { runCatching { ctx.packageManager.getPackageArchiveInfo(it.path, 0) }.getOrNull() }
        return InstallGuard.key(info?.packageName, parts)
    }

    /** 17.0 (код 83): итог пишется в журнал (код ошибки системы, пакет, версия), причина отказа переводится на язык программы. */
    suspend fun install(ctx: Context, parts: List<File>, notify: Boolean = true, onStep: Step? = null): Result {
        if (parts.isEmpty()) return Result(-2, "no files")
        val info = withContext(Dispatchers.IO) { runCatching { ctx.packageManager.getPackageArchiveInfo(parts.first().path, 0) }.getOrNull() }
        val key = InstallGuard.key(info?.packageName, parts)
        if (!InstallGuard.tryBegin(key)) return Result(BUSY, ctx.getString(R.string.ii_busy))
        var res: Result? = null
        // 22.11.2026 (18.0): уведомление Android с полосой хода; на ТВ и при выключенных уведомлениях не создаётся
        val label = withContext(Dispatchers.IO) {
            runCatching {
                val ai = info?.applicationInfo
                if (ai != null) { ai.sourceDir = parts.first().path; ai.publicSourceDir = parts.first().path }
                ai?.loadLabel(ctx.packageManager)?.toString()
            }.getOrNull()
        }?.takeIf { it.isNotBlank() } ?: parts.first().nameWithoutExtension
        val nt = if (notify) InstallNotify.begin(ctx, key, label, info?.versionName, info?.packageName) else null   // notify=false: у вызывающего своё уведомление (служба тихой установки)
        val step = Step { p, d, a -> nt?.step(p, d, a); onStep?.on(p, d, a) }
        try {
            val r = installRaw(ctx, parts, key, step, nt)
            @Suppress("DEPRECATION")
            InstLog.result("session", info?.packageName, info?.versionName, info?.let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }, parts.size, if (r.ok) "" else (r.code.toString() + " " + r.message), r.ok)
            res = if (r.ok || r.cancelled) r else Result(r.code, friendlyReason(ctx, r.message))
            return res
        } finally {
            val x = res
            nt?.finish(x)
            InstallGuard.end(key, InstallGuard.Done(x?.ok == true, x?.cancelled == true, x?.message.orEmpty(), System.currentTimeMillis()))
        }
    }

    private suspend fun installRaw(ctx: Context, parts: List<File>, key: String, onStep: Step?, nt: InstallNotify.Live? = null): Result {
        if (!Integrity.safe(ctx)) return Result(-2, "integrity")
        val app = ctx.applicationContext
        val pi = app.packageManager.packageInstaller
        val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        nt?.watch(pi, id)
        val action = "app.adbphon.INSTALL_RESULT_" + id
        val done = CompletableDeferred<Result>()
        val total = parts.sumOf { it.length() }.coerceAtLeast(1L)
        // системное окно подтверждения открывается один раз на сессию, даже если система пришлёт STATUS_PENDING_USER_ACTION повторно
        val confirmShown = AtomicBoolean(false)
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        if (!confirmShown.compareAndSet(false, true)) return
                        @Suppress("DEPRECATION")
                        val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirm == null) { done.complete(Result(-2, "no confirm intent")); return }
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { c.startActivity(confirm) }
                            .onSuccess { InstallGuard.setPhase(key, InstallGuard.Phase.SYSTEM); onStep?.on(4, total, total) }
                            .onFailure { done.complete(Result(-2, it.message.orEmpty())) }
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
                    var sent = 0L
                    var lastReport = 0L
                    onStep?.on(1, 0L, total)
                    val buf = ByteArray(256 * 1024)
                    for (f in parts) f.inputStream().use { i ->
                        s.openWrite(f.name, 0, f.length()).use { o ->
                            while (true) {
                                val n = i.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                sent += n
                                if (sent - lastReport >= 512 * 1024) { lastReport = sent; onStep?.on(1, sent, total) }
                            }
                            s.fsync(o)
                        }
                    }
                    onStep?.on(2, total, total)
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
