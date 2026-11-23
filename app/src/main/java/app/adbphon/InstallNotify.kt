package app.adbphon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 22.11.2026 (для 18.0): уведомление Android с полосой хода установки.
 * Жалоба: после нажатия «Установить» в системном окне хода не видно, и непонятно, идёт ли установка.
 * Одно уведомление на одну установку (ключ [InstallGuard]): «Передача файла» (полоса по байтам) → «Ожидается ответ в системном окне»
 * → «Идёт установка» → итог («Установлено: имя версия» / «Не установлено: причина» / «Отмена»), итог убирается сам.
 * Разрешение на уведомления не запрашивается: если оно выключено или отказано, уведомление просто не показывается. На ТВ уведомлений нет.
 * Логика переходов и частоты обновлений вынесена в чистые функции ([next], [percent], [shouldPost]) и проверяется тестом.
 */
object InstallNotify {
    enum class St { TRANSFER, WAIT, INSTALLING, OK, FAIL, CANCEL }

    fun isFinal(s: St) = s == St.OK || s == St.FAIL || s == St.CANCEL

    /** Процент передачи 0..100 по байтам. */
    fun percent(done: Long, all: Long): Int = if (all <= 0L) 0 else (done.coerceIn(0L, all) * 100L / all).toInt()

    /**
     * Следующее состояние по шагу [SessionInstall.Step]: 1 передача файла, 2 сессия закрыта (идёт установка), 4 открыто системное окно.
     * Итоговые состояния не меняются; из «ожидается ответ» шаг 2 не откатывает назад.
     */
    fun next(cur: St, phase: Int): St {
        if (isFinal(cur)) return cur
        return when (phase) {
            2 -> if (cur == St.WAIT) cur else St.INSTALLING
            4 -> St.WAIT
            else -> cur
        }
    }

    /** В системном окне нажато «Установить» (система начала ставить): ожидание → установка. */
    fun onSystemProgress(cur: St): St = if (cur == St.WAIT) St.INSTALLING else cur

    /** Итог по коду результата сессии: 0 успех, 3 отмена, иначе ошибка; null (прервано без ответа) — отмена. */
    fun finalState(code: Int?): St = when (code) { null -> St.CANCEL; 0 -> St.OK; 3 -> St.CANCEL; else -> St.FAIL }

    /** Обновлять уведомление при смене состояния, а внутри передачи — не чаще 400 мс и при сдвиге хотя бы на 1 %. */
    fun shouldPost(lastState: St?, lastPct: Int, lastAt: Long, state: St, pct: Int, now: Long): Boolean {
        if (lastState == null || lastState != state) return true
        if (state != St.TRANSFER) return false
        return pct != lastPct && now - lastAt >= 400L
    }

    /** Через сколько миллисекунд уведомление убирается само (страховка, если процесс остановлен посреди установки). */
    fun timeoutMs(s: St): Long = when (s) {
        St.OK -> 10_000L
        St.CANCEL -> 5_000L
        St.FAIL -> 30_000L
        St.TRANSFER, St.INSTALLING -> 3 * 60_000L
        St.WAIT -> 10 * 60_000L
    }

    /** Стабильный номер уведомления из ключа установки. */
    fun notifId(key: String): Int = 7000 + ((key.hashCode() and 0x7fffffff) % 1000)

    private const val CH = "install_progress"

    /** Одна установка. Создаётся через [begin]; null на ТВ и при выключенных уведомлениях. */
    class Live internal constructor(private val ctx: Context, key: String, private val label: String, private val version: String, private val pkg: String?) {
        private val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        private val id = notifId(key)
        private var st: St = St.TRANSFER
        private var pct = 0
        private var lastState: St? = null
        private var lastPct = -1
        private var lastAt = 0L
        private var cb: PackageInstaller.SessionCallback? = null
        private var pi: PackageInstaller? = null

        @Synchronized fun step(phase: Int, done: Long, all: Long) {
            if (isFinal(st)) return
            if (phase == 1) pct = percent(done, all)
            st = next(st, phase)
            post()
        }

        /** Следит за сессией системы: когда в окне нажато «Установить», сессия становится активной или идёт ход, и «ожидается ответ» меняется на «идёт установка». */
        fun watch(installer: PackageInstaller, sessionId: Int) {
            val c = object : PackageInstaller.SessionCallback() {
                override fun onCreated(i: Int) {}
                override fun onBadgingChanged(i: Int) {}
                override fun onFinished(i: Int, success: Boolean) {}
                override fun onActiveChanged(i: Int, active: Boolean) { if (i == sessionId && active) sys() }
                override fun onProgressChanged(i: Int, progress: Float) { if (i == sessionId && progress > 0f) sys() }
            }
            cb = c; pi = installer
            runCatching { installer.registerSessionCallback(c, Handler(Looper.getMainLooper())) }
        }

        @Synchronized private fun sys() {
            val n = onSystemProgress(st)
            if (n != st) { st = n; post() }
        }

        /** Итог: [r] null — прервано без ответа. */
        @Synchronized fun finish(r: SessionInstall.Result?) {
            runCatching { cb?.let { pi?.unregisterSessionCallback(it) } }
            if (isFinal(st)) return
            st = finalState(r?.code)
            post(r?.message.orEmpty())
            val gone = timeoutMs(st)
            InstallGuard.scope.launch { delay(gone); runCatching { nm.cancel(id) } }
        }

        private fun post(reason: String = "") {
            val now = System.currentTimeMillis()
            if (!shouldPost(lastState, lastPct, lastAt, st, pct, now)) return
            lastState = st; lastPct = pct; lastAt = now
            runCatching {
                if (!nm.areNotificationsEnabled()) return
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    val ch = NotificationChannel(CH, ctx.getString(R.string.inn_channel), NotificationManager.IMPORTANCE_LOW)
                    ch.setSound(null, null); ch.enableVibration(false)
                    nm.createNotificationChannel(ch)
                }
                nm.notify(id, build(reason))
            }
        }

        private fun build(reason: String): Notification {
            val b = Notification.Builder(ctx).setSmallIcon(R.drawable.ic_stat_files).setOnlyAlertOnce(true).setShowWhen(false)
            if (android.os.Build.VERSION.SDK_INT >= 26) { b.setChannelId(CH); b.setTimeoutAfter(timeoutMs(st)) }
            val fin = isFinal(st)
            b.setOngoing(!fin).setAutoCancel(true)
            b.setContentTitle(ctx.getString(R.string.inn_title, label))
            when (st) {
                // на Samsung в свёрнутой строке текст под полосой не виден, поэтому процент и «ждёт ответа» дублируются короткой подписью рядом с названием
                St.TRANSFER -> { b.setContentText(ctx.getString(R.string.inn_transfer, pct)).setSubText("$pct %"); b.setProgress(100, pct, false) }
                St.WAIT -> { b.setContentText(ctx.getString(R.string.inn_wait)).setSubText(ctx.getString(R.string.inn_sub_wait)); b.setProgress(0, 0, true) }
                St.INSTALLING -> { b.setContentText(ctx.getString(R.string.inn_installing)); b.setProgress(0, 0, true) }
                St.OK -> {
                    b.setContentTitle(ctx.getString(R.string.inn_ok, label, version).trim())
                    val open = pkg?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
                    if (open != null) b.setContentIntent(PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
                St.FAIL -> b.setContentTitle(ctx.getString(R.string.inn_fail, label)).setContentText(reason.ifBlank { ctx.getString(R.string.inn_fail_unknown) })
                St.CANCEL -> b.setContentText(ctx.getString(R.string.inn_cancel))
            }
            return b.build()
        }
    }

    /** Начало установки: null на ТВ (там своё окно хода) и если уведомления выключены. Первое уведомление показывается сразу. */
    fun begin(ctx: Context, key: String, label: String, version: String?, pkg: String?): Live? {
        if (Marq.tv) return null
        val app = ctx.applicationContext
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!runCatching { nm.areNotificationsEnabled() }.getOrDefault(false)) return null
        return Live(app, key, label, version.orEmpty(), pkg).also { it.step(1, 0L, 1L) }
    }
}
