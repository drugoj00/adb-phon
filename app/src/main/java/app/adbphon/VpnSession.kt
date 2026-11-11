package app.adbphon

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * «Сеанс связи по интернету» (7.0): время, пока Tailscale на этом телефоне включён программой. Чужой VPN на это время отключается
 * (Android допускает только один), прежнее состояние запоминается ПОЛНОСТЬЮ (always-on и lockdown, разрешение VPN ACTIVATE_VPN, был ли VPN
 * запущен) в настройках и возвращается автоматически: кнопкой «Вернуть сейчас» в уведомлении или в программе, когда Tailscale выключили или
 * закрыли, после простоя (по умолчанию 10 минут), при выходе из программы, после перезагрузки телефона и после принудительной остановки
 * программы (страхующее задание JobScheduler). Возврат: сначала останавливается Tailscale, потом возвращается прежний VPN и проверяется,
 * что VPN-сеть снова активна. Нужен ADB (или помощник) для appops/am force-stop.
 */
object VpnSession {
    private const val TS = "com.tailscale.ipn"
    private const val NID = 4432
    const val ACTION_END = "app.adbphon.VPN_SESSION_END"
    private const val JOB_CHECK = 4432
    private const val JOB_RECOVER = 4433

    class State(val pkg: String, val label: String, val alwaysOn: Boolean, val lockdown: String, val appops: String,
                val wasActive: Boolean, val wasRunning: Boolean, val startedAt: Long)

    var current by mutableStateOf<State?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watcher: Job? = null
    private val mutex = kotlinx.coroutines.sync.Mutex()

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("invites", Context.MODE_PRIVATE)
    private fun now() = System.currentTimeMillis() / 1000
    fun idleMinutes(c: Context): Int = sp(c).getInt("vpn_idle_min", 10)
    fun setIdleMinutes(c: Context, m: Int) { sp(c).edit().putInt("vpn_idle_min", m).apply() }

    // ---------------------------------------------------------------- хранение
    private fun save(c: Context, s: State?) {
        if (s == null) { sp(c).edit().remove("vsession").remove("vs_up").remove("vs_last_up").remove("vs_bytes").remove("vs_activity").apply(); current = null; return }
        val o = JSONObject().put("pkg", s.pkg).put("label", s.label).put("always", s.alwaysOn).put("lock", s.lockdown).put("ops", s.appops)
            .put("active", s.wasActive).put("running", s.wasRunning).put("at", s.startedAt)
        sp(c).edit().putString("vsession", o.toString()).apply()
        current = s
    }

    fun load(c: Context): State? = runCatching {
        val o = JSONObject(sp(c).getString("vsession", null) ?: return null)
        State(o.getString("pkg"), o.optString("label"), o.optBoolean("always"), o.optString("lock", "null"), o.optString("ops", "default"),
            o.optBoolean("active"), o.optBoolean("running"), o.optLong("at"))
    }.getOrNull()

    fun pending(c: Context) = load(c) != null

    // ---------------------------------------------------------------- команды
    private suspend fun sh(cmd: String): String = runCatching { Adb.shell(cmd, 15_000L) }.getOrDefault("")

    /** Есть ли поднятый туннель (tun/tap/wg/ppp): по ADB точно, без ADB — любая VPN-сеть (VpnGuard.vpnActive может ошибаться). */
    suspend fun upNow(c: Context): Boolean {
        if (!Adb.isConnected) return VpnGuard.vpnActive(c)
        val o = sh("ip -o link | grep -c -E ': (tun|tap|wg|ppp)[0-9]+: <[^>]*,UP'").trim()
        return (o.toIntOrNull() ?: 0) > 0
    }

    /** Включить службу спецвозможностей ADB-phon через оболочку ADB (список служб сохраняем). */
    private suspend fun enableAccessibility(c: Context) {
        val me = ComponentName(c, AutoPairService::class.java).flattenToString()
        val cur = sh("settings get secure enabled_accessibility_services").trim().let { if (it == "null") "" else it }
        if (cur.split(':').contains(me)) return
        val next = if (cur.isEmpty()) me else "$cur:$me"
        sh("settings put secure enabled_accessibility_services '$next'; settings put secure accessibility_enabled 1")
        for (i in 0 until 6) { if (AutoPairService.isEnabled(c)) break; delay(500) }
        delay(1500)
    }

    private suspend fun adbReady(c: Context): Boolean {
        if (Adb.isConnected) return true
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching { Adb.connectWireless(c, null) }
            delay(1500)
        }
        return Adb.isConnected
    }

    /** Снимок состояния чужого VPN. */
    private suspend fun snapshot(c: Context, info: VpnGuard.Info): State {
        val always = sh("settings get secure always_on_vpn_app").trim() == info.pkg
        val lock = sh("settings get secure always_on_vpn_lockdown").trim().ifEmpty { "null" }
        val opsLine = sh("appops get ${info.pkg} ACTIVATE_VPN")
        val mode = Regex("ACTIVATE_VPN:\\s*([a-z_]+)").find(opsLine)?.groupValues?.get(1) ?: "default"
        val running = sh("pidof ${info.pkg}").trim().isNotEmpty() || sh("ps -A | grep ${info.pkg} | head -1").contains(info.pkg)
        return State(info.pkg, info.label, always, lock, mode, VpnGuard.vpnActive(c), running, now())
    }

    /** Начать сеанс: снимок → сохранить → отключить чужой VPN. true, если после отключения VPN-сети нет. */
    suspend fun begin(c: Context, info: VpnGuard.Info): Boolean {
        val app = c.applicationContext
        if (info.pkg == "?" || !adbReady(app)) return false
        val st = load(app) ?: snapshot(app, info).also { save(app, it) }
        // `settings put secure always_on_vpn_app` НЕ меняет настоящее состояние системы (проверено на A14 10.11.2026), поэтому «Постоянный VPN»
        // выключается системным переключателем (Настройки → VPN → шестерёнка), его нажимает служба спецвозможностей; lockdown первым.
        var viaSystem = false
        if (st.alwaysOn) {
            // без службы спецвозможностей настоящий переключатель не нажать: включаем её по ADB (WRITE_SECURE_SETTINGS не нужен, есть оболочка)
            if (!AutoPairService.isEnabled(app)) enableAccessibility(app)
            if (AutoPairService.isEnabled(app)) viaSystem = SysVpn.apply(app, st.label, alwaysOn = false, lockdown = if (st.lockdown == "1") false else null, backToApp = true)
            if (!viaSystem) {
                // не вышло: ничего не ломаем (возможно, переключатель успели выключить: вернуть) и отказываемся от сеанса
                if (AutoPairService.isEnabled(app)) SysVpn.apply(app, st.label, alwaysOn = true, lockdown = if (st.lockdown == "1") true else null, backToApp = true)
                save(app, null)
                return false
            }
        }
        if (viaSystem) sh("am force-stop ${st.pkg}; echo OK")
        else sh("settings put secure always_on_vpn_lockdown 0; settings put secure always_on_vpn_app null; appops set ${st.pkg} ACTIVATE_VPN deny; am force-stop ${st.pkg}; echo OK")
        delay(1200)
        armWatchdog(st)
        val ok = !upNow(app)
        sp(app).edit().putLong("vs_activity", now()).putLong("vs_bytes", 0L).putBoolean("vs_up", false).apply()
        notifyShow(app, st)
        schedule(app)
        startWatcher(app)
        return ok
    }

    /** Завершить сеанс и вернуть как было. true, если возврат подтверждён проверкой. */
    suspend fun end(c: Context, reason: String): Boolean = mutex.withLock { endLocked(c, reason) }

    private suspend fun endLocked(c: Context, reason: String): Boolean {
        val app = c.applicationContext
        val st = load(app) ?: return true
        AppLog.i("ADBphon", "сеанс VPN: завершение ($reason)")
        if (!adbReady(app)) { notifyShow(app, st, failed = true); return false }
        // 1) Tailscale-VPN останавливаем (два VPN сразу нельзя)
        sh("am force-stop $TS")
        delay(800)
        // 2) прежний VPN: разрешение и «Постоянный VPN» (системным переключателем: только он настоящий и сам поднимает VPN), потом lockdown
        sh("appops set ${st.pkg} ACTIVATE_VPN ${st.appops}")
        var viaSystem = false
        if (st.alwaysOn) {
            if (!AutoPairService.isEnabled(app)) enableAccessibility(app)
            viaSystem = AutoPairService.isEnabled(app) && SysVpn.apply(app, st.label, alwaysOn = true, lockdown = if (st.lockdown == "1") true else null, backToApp = false)
        }
        if (!viaSystem) {
            if (st.alwaysOn) sh("settings put secure always_on_vpn_app ${st.pkg}")
            else if (st.wasRunning) sh("monkey -p ${st.pkg} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; echo started")
        }
        // 3) проверка: туннель прежнего VPN снова поднят (по интерфейсу tun, не по «любой VPN-сети»)
        var ok = !st.wasActive
        if (st.wasActive) for (i in 0 until 30) { if (upNow(app)) { ok = true; break }; delay(1000) }
        if (!ok && st.alwaysOn && !viaSystem) runCatching { app.startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        if (ok && !viaSystem && st.alwaysOn && st.lockdown == "1") sh("settings put secure always_on_vpn_lockdown 1")
        if (ok) {
            disarmWatchdog()
            save(app, null)
            watcher?.cancel(); watcher = null
            runCatching { (app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).apply { cancel(JOB_CHECK); cancel(JOB_RECOVER) } }
            notifyHide(app)
            AppLog.i("ADBphon", "сеанс VPN: прежний VPN возвращён (${st.pkg})")
        } else {
            notifyShow(app, st, failed = true)
            AppLog.w("ADBphon", "сеанс VPN: возврат не подтвердился (${st.pkg}), повторим")
        }
        return ok
    }

    // ---------------------------------------------------------------- страж в оболочке (принудительная остановка программы)
    private const val FLAG = "/data/local/tmp/adbphon-vpn-session.flag"
    private const val RESTORE = "/data/local/tmp/adbphon-vpn-restore.sh"

    /** Независимый от программы процесс оболочки: если ADB-phon не работает 3 минуты подряд, а сеанс записан, возвращает VPN по сохранённому сценарию. */
    private suspend fun armWatchdog(st: State) {
        val script = buildString {
            append("am force-stop $TS\n")
            append("appops set ${st.pkg} ACTIVATE_VPN ${st.appops}\n")
            // настоящий «Постоянный VPN» включается только системным переключателем: страж запускает ADB-phon, а он (VpnSession.onStart -> recover)
            // нажимает переключатель через службу спецвозможностей
            if (st.alwaysOn) append("am start -n app.adbphon/.MainActivity >/dev/null 2>&1" + "\n")
            else if (st.wasRunning) append("monkey -p ${st.pkg} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1" + "\n")
        }
        val b64 = android.util.Base64.encodeToString(script.toByteArray(), android.util.Base64.NO_WRAP)
        val loop = "n=0; while [ -f $FLAG ]; do if pidof app.adbphon >/dev/null 2>&1; then n=0; else n=\$((n+1)); fi; " +
            "if [ \$n -ge 9 ]; then sh $RESTORE; rm -f $FLAG; break; fi; sleep 20; done"
        sh("echo $b64 | base64 -d > $RESTORE; touch $FLAG; nohup setsid sh -c '$loop' >/dev/null 2>&1 </dev/null &")
    }

    private suspend fun disarmWatchdog() { sh("rm -f $FLAG $RESTORE") }

    // ---------------------------------------------------------------- наблюдение
    fun startWatcher(c: Context) {
        val app = c.applicationContext
        if (watcher?.isActive == true) return
        watcher = scope.launch { while (load(app) != null) { runCatching { check(app) }; delay(15_000) } }
    }

    /** Одна проверка условий завершения (вызывается наблюдателем и страхующим заданием). */
    suspend fun check(c: Context): Boolean {
        val app = c.applicationContext
        val st = load(app) ?: return true
        val t = now()
        val up = upNow(app)
        val p = sp(app)
        if (up) p.edit().putBoolean("vs_up", true).putLong("vs_last_up", t).apply()
        val upSeen = p.getBoolean("vs_up", false)
        val lastUp = p.getLong("vs_last_up", 0L)
        // активность Tailscale: растёт счётчик байтов приложения
        val uid = runCatching { app.packageManager.getApplicationInfo(TS, 0).uid }.getOrDefault(-1)
        if (uid >= 0 && up) {
            val b = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0) + TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
            val prev = p.getLong("vs_bytes", 0L)
            if (prev == 0L || b - prev > 20_000) p.edit().putLong("vs_activity", t).apply()
            p.edit().putLong("vs_bytes", b).apply()
        }
        val lastAct = p.getLong("vs_activity", t)
        return when {
            up && t - lastAct > idleMinutes(app) * 60L -> end(app, "простой")
            !up && upSeen && t - lastUp > 30 -> end(app, "Tailscale выключен")
            !up && !upSeen && t - st.startedAt > 240 -> end(app, "не соединились")
            else -> false
        }
    }

    /**
     * Восстановление после сбоя/перезагрузки: если сеанс остался записанным, а этот процесс его не ведёт (программу перезапустили или телефон
     * загрузился), берём наблюдение на себя; если Tailscale-сети нет больше минуты после начала сеанса, возвращаем чужой VPN.
     */
    suspend fun recover(c: Context): Boolean {
        val app = c.applicationContext
        val st = load(app) ?: return true
        if (watcher?.isActive == true) return false   // сеанс ведёт этот процесс: решает наблюдатель (check)
        startWatcher(app)
        val up = upNow(app)
        return if (!up && now() - st.startedAt > 60) end(app, "восстановление") else false
    }

    // ---------------------------------------------------------------- уведомление и задания
    private fun notifyShow(c: Context, st: State, failed: Boolean = false) {
        runCatching {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("vpn_session", c.getString(R.string.inv_sess_channel), NotificationManager.IMPORTANCE_LOW))
            val end = PendingIntent.getBroadcast(c, 4432, Intent(c, VpnSessionReceiver::class.java).setAction(ACTION_END), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(c, 4434, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(c, "vpn_session").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(c.getString(R.string.inv_sess_title))
                .setContentText(c.getString(if (failed) R.string.inv_sess_failed else R.string.inv_sess_text, st.label))
                .setOngoing(true).setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_revert, c.getString(R.string.inv_sess_restore_now), end).build()
            nm.notify(NID, n)
        }
    }

    private fun notifyHide(c: Context) { runCatching { (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NID) } }

    private fun schedule(c: Context) {
        runCatching {
            val js = c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val cn = ComponentName(c, VpnRecoverJobService::class.java)
            js.schedule(JobInfo.Builder(JOB_CHECK, cn).setPeriodic(15 * 60 * 1000L).setPersisted(true).build())
            js.schedule(JobInfo.Builder(JOB_RECOVER, cn).setMinimumLatency(25_000L).setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_LINEAR).setPersisted(true).build())
        }
    }

    /** Загрузка телефона: если сеанс остался записанным, вернуть VPN (задание само повторяется, пока не выйдет). */
    fun onBoot(c: Context) { if (pending(c)) schedule(c) }

    fun onExit(c: Context) {
        if (!pending(c)) return
        runCatching { runBlocking { withTimeoutOrNull(20_000L) { end(c, "выход из программы") } } }
    }

    fun onStart(c: Context) {
        val st = load(c)
        current = st
        if (st != null) { startWatcher(c); scope.launch { recover(c) } }
    }
}

class VpnSessionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != VpnSession.ACTION_END) return
        val pr = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { VpnSession.end(c.applicationContext, "кнопка") } finally { pr.finish() } }
    }
}

class VpnRecoverJobService : JobService() {
    override fun onStartJob(p: JobParameters?): Boolean {
        CoroutineScope(Dispatchers.IO).launch {
            val app = applicationContext
            val done = runCatching {
                if (p?.jobId == 4433) VpnSession.recover(app) else VpnSession.check(app)
            }.getOrDefault(false)
            // повторить, если сеанс всё ещё записан и это задание восстановления
            jobFinished(p, VpnSession.pending(app) && p?.jobId == 4433 && !done)
        }
        return true
    }
    override fun onStopJob(p: JobParameters?) = true
}

/**
 * Системный переключатель «Постоянный VPN» (Always-on VPN) у чужого VPN-приложения. Через `settings put` настоящее состояние не меняется
 * (проверено на Samsung A14, Android 14): VPN остаётся в системе always-on, Tailscale не получает разрешение VPN, а после «возврата» через
 * settings чужой VPN не подключается. Поэтому служба спецвозможностей открывает Настройки → VPN → шестерёнка приложения и нажимает
 * переключатели (Always-on, при необходимости «Блокировка подключений без VPN»). Узлы ищутся по тексту, а не по координатам.
 */
object SysVpn {
    @Volatile private var wanted = false
    @Volatile private var done = false
    @Volatile private var label = ""
    @Volatile private var alwaysTarget = false
    @Volatile private var lockTarget: Boolean? = null
    @Volatile private var goHome = false
    private var lastAct = 0L

    private val alwaysRx = Regex("always-on|постоянн|постійн", RegexOption.IGNORE_CASE)
    private val lockRx = Regex("без vpn|without vpn|lockdown|блокировк|блокуван", RegexOption.IGNORE_CASE)

    /** Выставить переключатели; true, если дошли до нужного состояния. */
    suspend fun apply(c: Context, appLabel: String, alwaysOn: Boolean, lockdown: Boolean?, backToApp: Boolean): Boolean {
        if (!AutoPairService.isEnabled(c)) return false
        label = appLabel; alwaysTarget = alwaysOn; lockTarget = lockdown; goHome = !backToApp; done = false; lastAct = 0L; wanted = true
        runCatching { Adb.shell("am start -a android.settings.VPN_SETTINGS", 10_000L) }
        var waited = 0
        while (!done && waited < 40) { delay(1000); waited++ }
        wanted = false
        val ok = done
        if (ok && backToApp) runCatching { Adb.shell("am start -n app.adbphon/.MainActivity", 10_000L) }
        return ok
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, d: Int = 0) {
        if (n == null || d > 40) return
        out.add(n); for (i in 0 until n.childCount) collect(n.getChild(i), out, d + 1)
    }
    private fun txt(n: AccessibilityNodeInfo) = (n.text?.toString().orEmpty() + " " + n.contentDescription?.toString().orEmpty()).trim()
    private fun clickUp(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n; var h = 0
        while (c != null && h < 6) { if (c.isClickable && c.isEnabled) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK); c = c.parent; h++ }
        return false
    }
    private fun switchOf(title: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var row: AccessibilityNodeInfo? = title; var h = 0
        while (row != null && h < 6) {
            val sub = ArrayList<AccessibilityNodeInfo>(); collect(row, sub)
            val sw = sub.firstOrNull { it.isCheckable && (it.className?.toString()?.contains("Switch") == true || it.className?.toString()?.contains("Check") == true) }
            if (sw != null) return sw
            row = row.parent; h++
        }
        return null
    }

    /** Вызывается службой спецвозможностей на каждое событие и раз в секунду. */
    fun step(svc: AccessibilityService, root: AccessibilityNodeInfo?) {
        if (!wanted || done || root == null) return
        if (root.packageName?.toString() != "com.android.settings") return
        val t = System.currentTimeMillis(); if (t - lastAct < 800) return
        val nodes = ArrayList<AccessibilityNodeInfo>(); collect(root, nodes)
        val vis = nodes.filter { it.isVisibleToUser }
        // страница приложения: переключатели
        val aTitle = vis.firstOrNull { it.text != null && alwaysRx.containsMatchIn(it.text.toString()) && it.className?.toString()?.contains("TextView") == true }
        if (aTitle != null) {
            val aSw = switchOf(aTitle)
            val lTitle = vis.firstOrNull { it.text != null && lockRx.containsMatchIn(it.text.toString()) && it.className?.toString()?.contains("TextView") == true }
            val lSw = lTitle?.let { switchOf(it) }
            val lt = lockTarget
            // порядок: выключая — сначала lockdown, потом always-on; включая — сначала always-on, потом lockdown
            fun fixLock(): Boolean { if (lt != null && lSw != null && lSw.isEnabled && lSw.isChecked != lt) { clickUp(lSw); lastAct = t; return true }; return false }
            if (!alwaysTarget && fixLock()) return
            if (aSw != null && aSw.isEnabled && aSw.isChecked != alwaysTarget) { clickUp(aSw); lastAct = t; return }
            if (alwaysTarget && fixLock()) return
            if (aSw != null && aSw.isChecked == alwaysTarget) {
                done = true
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                if (goHome) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }
            return
        }
        // подтверждающее окно (если система его покажет)
        vis.firstOrNull { it.className?.toString()?.contains("Button") == true && Regex("^(ok|ок|turn on|включить|увімкнути|подтвердить|так|да)$", RegexOption.IGNORE_CASE).matches(txt(it)) }
            ?.let { if (clickUp(it)) { lastAct = t; return } }
        // список VPN: шестерёнка в строке с названием приложения (правее 70% ширины, на одной высоте со строкой)
        val rowN = vis.firstOrNull { it.text?.toString() == label } ?: return
        val rb = android.graphics.Rect(); rowN.getBoundsInScreen(rb)
        val sw = android.graphics.Rect(); root.getBoundsInScreen(sw)
        val gear = vis.filter { it.isClickable && it.isEnabled }.firstOrNull {
            val b = android.graphics.Rect(); it.getBoundsInScreen(b)
            b.left > sw.width() * 0.7 && b.centerY() in (rb.top - 40)..(rb.bottom + 140) && b.width() < sw.width() * 0.3
        }
        if (gear != null && gear.performAction(AccessibilityNodeInfo.ACTION_CLICK)) lastAct = t
    }
}
