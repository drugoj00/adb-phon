package app.adbphon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Автокопия по расписанию (раз в сутки или раз в неделю): копия настроек в запомненную папку копий, с паролем, если в разделе включено
 * «Шифровать паролем» (пароль хранится зашифрованным ключом Android Keystore, см. [BkStore.passForCopy]). По желанию также «Копии с данными»
 * приложений последней ручной копии, но только если root или ADB уже доступны (иначе пропуск с записью в журнал). Хранятся последние N
 * автокопий (удаляются только свои файлы с префиксом ADB-phon-auto-). Запуск: JobScheduler раз в несколько часов (переживает перезагрузку),
 * проверка срока внутри; итог в уведомлении и в разделе настроек.
 */
object BkAuto {
    const val JOB = 4471
    private const val CH = "backup_auto"
    private const val NID = 4472
    private val lock = Mutex()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE)
    fun mode(ctx: Context) = prefs(ctx).getInt("backup_auto", 0)
    fun setMode(ctx: Context, m: Int) { prefs(ctx).edit().putInt("backup_auto", m).apply(); schedule(ctx) }
    fun keep(ctx: Context) = prefs(ctx).getInt("bkp_auto_keep", 5).coerceIn(1, 50)
    fun setKeep(ctx: Context, n: Int) { prefs(ctx).edit().putInt("bkp_auto_keep", n).apply() }
    fun withData(ctx: Context) = prefs(ctx).getBoolean("bkp_auto_data", false)
    fun setWithData(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("bkp_auto_data", on).apply() }
    fun last(ctx: Context) = prefs(ctx).getLong("backup_auto_last", 0L)
    fun lastResult(ctx: Context): String? = prefs(ctx).getString("bkp_auto_result", null)

    /** Включить или снять задание планировщика по текущему режиму. Сеть нужна, если место копий сетевое. */
    fun schedule(ctx: Context) {
        runCatching {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (mode(ctx) == BkSchedule.OFF) { js.cancel(JOB); return }
            val net = !BkStore.last(ctx, BkStore.KEY_SETTINGS).local
            val b = JobInfo.Builder(JOB, ComponentName(ctx, BkJobService::class.java)).setPersisted(true).setPeriodic(6 * 3600_000L, 2 * 3600_000L)
            if (net) b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            js.schedule(b.build())
        }.onFailure { AppLog.e("ADBphon", "bkp schedule", it) }
    }

    /** При запуске программы: задание на месте, просроченная копия делается сразу в фоне. */
    fun onAppStart(ctx: Context) {
        val app = ctx.applicationContext
        schedule(app)
        if (BkSchedule.isDue(last(app), mode(app), System.currentTimeMillis())) bg.launch { run(app, false) }
    }

    private fun langCtx(ctx: Context): Context = Lang.wrap(ctx.applicationContext, prefs(ctx).getString("lang", "auto") ?: "auto")

    /** Сделать автокопию. [force]: не смотреть на срок («Выполнить сейчас»). Возвращает текст итога. */
    suspend fun run(ctx: Context, force: Boolean, step: (String) -> Unit = {}): String = lock.withLock {
        val app = ctx.applicationContext
        if (!force && !BkSchedule.isDue(last(app), mode(app), System.currentTimeMillis())) return@withLock lastResult(app).orEmpty()
        runCatching { FmIo.app }.onFailure { FmIo.app = app }
        val lc = langCtx(app)
        runCatching { Fm.sctx }.onFailure { Fm.sctx = lc }
        val lines = ArrayList<String>()
        var ok = true
        var settingsOk = false
        // шифрование включено, а пароль не задан: копия настроек пишется без паролей (секреты кладутся только под паролем), с пометкой
        var noSecrets = false
        val pw = try { BkStore.passForCopy(app) } catch (e: BkStore.NeedPass) { noSecrets = true; null }
        if (noSecrets) lines += lc.getString(R.string.bk4_auto_nosecrets)
        val stamp = BkNames.stamp(System.currentTimeMillis())
        // 1) настройки
        val place = BkStore.last(app, BkStore.KEY_SETTINGS)
        val tmp = File(BkStore.tmpDir(app), BkNames.autoSettings(stamp))
        try {
            step(lc.getString(R.string.bkp_step_settings))
            val (data, _) = AppBackup.create(app, pw)
            AppBackup.unseal(data, pw)   // самопроверка до записи
            tmp.writeBytes(data)
            val o = BkStore.open(app, place)
            try {
                val size = BkStore.write(o, tmp, tmp.name)
                BkStore.localFile(o, tmp.name)?.let { AppBackup.unseal(it.readBytes(), pw) }
                lines += lc.getString(R.string.bkp_saved, BkStore.display(app, place, tmp.name), BkStore.size(lc, size))
                val old = BkSchedule.rotate(BkStore.list(o).map { it.name }, BkNames.AUTO_PREFIX, BkNames.SETTINGS_EXT, keep(app))
                old.forEach { n -> runCatching { BkStore.delete(o, n) }.onFailure { AppLog.e("ADBphon", "bkp rotate $n", it) } }
                if (old.isNotEmpty()) lines += lc.getString(R.string.bkp_rotated, old.size)
                settingsOk = true
            } finally { o.close() }
        } catch (e: Throwable) {
            ok = false
            AppLog.e("ADBphon", "bkp auto settings", e)
            lines += lc.getString(R.string.bkp_auto_failed, e.message ?: e.javaClass.simpleName)
        } finally { tmp.delete() }
        // 2) копии с данными: только если root или ADB уже есть
        if (withData(app)) {
            val apps = BkData.autoApps(app)
            if (apps.isEmpty()) lines += lc.getString(R.string.bkp_auto_no_apps)
            else if (!(Adb.isRoot || Adb.isConnected)) {
                AppLog.i("ADBphon", "bkp auto: копии с данными пропущены, нет root и ADB")
                lines += lc.getString(R.string.bkp_auto_data_skipped)
            } else {
                val user = BkData.autoUser(app)
                val dplace = BkStore.last(app, BkStore.KEY_DATA)
                for (pkg in apps) {
                    val label = runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    try {
                        step(lc.getString(R.string.bkp_step_pack, label))
                        val s = BkData.createOne(lc, AppEntry(pkg, label, false, true, true, "", emptyList()), user, dplace, BkNames.autoData(pkg, user, BkNames.stamp(System.currentTimeMillis())), pw) {}
                        lines += s.text.lines().first()
                        val o = BkStore.open(app, dplace)
                        try {
                            BkSchedule.rotate(BkStore.list(o).map { it.name }, BkNames.autoDataPrefix(pkg, user), BkNames.DATA_EXT, keep(app)).forEach { n -> runCatching { BkStore.delete(o, n) } }
                        } finally { o.close() }
                    } catch (e: Throwable) {
                        ok = false
                        AppLog.e("ADBphon", "bkp auto data $pkg", e)
                        lines += lc.getString(R.string.bkp_failed, label, e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        }
        val text = lines.joinToString("\n")
        val time = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val ed = prefs(app).edit().putString("bkp_auto_result", "$time: $text")
        // срок отсчитывается от удачной копии настроек; при ошибке следующая попытка будет на следующей проверке планировщика
        if (settingsOk) ed.putLong("backup_auto_last", System.currentTimeMillis())
        ed.apply()
        notify(app, lc, ok, text)
        text
    }

    private fun notify(app: Context, lc: Context, ok: Boolean, text: String) {
        runCatching {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.areNotificationsEnabled()) { AppLog.i("ADBphon", "bkp auto: уведомления выключены, итог только в настройках"); return }
            if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, lc.getString(R.string.bkp_channel), NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(app, NID, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            @Suppress("DEPRECATION")
            val b = if (android.os.Build.VERSION.SDK_INT >= 26) Notification.Builder(app, CH) else Notification.Builder(app)
            b.setSmallIcon(R.drawable.ic_stat_files).setAutoCancel(true).setContentIntent(open)
                .setContentTitle(lc.getString(if (ok) R.string.bkp_auto_done else R.string.bkp_auto_error))
                .setContentText(text.lines().firstOrNull().orEmpty()).setStyle(Notification.BigTextStyle().bigText(text))
            nm.notify(NID, b.build())
        }.onFailure { AppLog.e("ADBphon", "bkp notify", it) }
    }
}

/** Задание планировщика автокопии. Проверка: `adb shell cmd jobscheduler run -f app.adbphon 4471` (копия делается, если срок подошёл или есть метка bk_force). */
class BkJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onStartJob(p: JobParameters?): Boolean {
        scope.launch {
            val flag = File(getExternalFilesDir(null), "bk_force")
            val force = runCatching { flag.exists() && flag.delete() }.getOrDefault(false)
            runCatching { BkAuto.run(applicationContext, force) }.onFailure { AppLog.e("ADBphon", "bkp job", it) }
            jobFinished(p, false)
        }
        return true
    }
    override fun onStopJob(p: JobParameters?): Boolean = true
}
