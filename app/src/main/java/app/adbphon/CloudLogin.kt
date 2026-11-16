package app.adbphon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.view.WindowManager.LayoutParams as LP
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * Служба на время входа в облако: пока владелица вводит пароль и 2FA в браузере (минуты), приложение свёрнуто, и система
 * могла остановить процесс программы вместе с движком входа (тогда браузер после выбора аккаунта показывал «сайт недоступен»).
 * Служба переднего плана с уведомлением «Вход в облако…» и блокировкой сна держит процесс живым; «Отмена» в уведомлении останавливает вход.
 */
class CloudLoginService : Service() {
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") {
            CloudAuth.reset()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, getString(R.string.cl_login_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 2, Intent(this, CloudLoginService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this).setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.cl_login_notif_title)).setContentText(getString(R.string.cl_login_notif_text)).setContentIntent(open)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), getString(R.string.btn_cancel), cancel).build())
            .also { if (android.os.Build.VERSION.SDK_INT >= 26) it.setChannelId(CH) }.build()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        if (wl == null) {
            wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "adbphon:cloudlogin").apply { setReferenceCounted(false); acquire(25 * 60_000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wl?.takeIf { it.isHeld }?.release() }; wl = null
        CloudOverlay.hide()      // вход закончен или отменён: плавающая подсказка исчезает
        super.onDestroy()
    }

    companion object {
        const val CH = "cloudlogin"; const val ID = 4108
        fun start(ctx: Context) { runCatching { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, CloudLoginService::class.java)) } }
        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, CloudLoginService::class.java)) } }
    }
}

/**
 * 10.0: плавающая подсказка поверх браузера (вход в Google Диск, мастер своего ключа Яндекса). Окно ПРОЗРАЧНОЕ: без фона и рамки, только крупная яркая пульсирующая стрелка
 * и короткая подпись; нажатия проходят сквозь него (FLAG_NOT_TOUCHABLE), отдельная маленькая кнопка «✕» закрывает подсказку.
 * Стрелка ставится НЕ по координатам, а по найденному на экране элементу: служба спецвозможностей программы (AutoPairService) раз в секунду читает дерево элементов
 * браузера, ищет по тексту фразы из правил (HintRules: ru/uk/en, обновляются с сайта) и стрелку рисует у границ найденного элемента. Элемента на экране нет
 * (страница другая, текст другой, служба выключена): стрелки нет, внизу только текст шага, указателя «в пустоту» не бывает.
 * Нужно разрешение «Поверх других окон». Исчезает при завершении входа, при возврате в программу, по «✕» и через 3 минуты.
 */
object CloudOverlay {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val views = ArrayList<android.view.View>()
    private var hint: HintView? = null
    private var appCtx: Context? = null
    @Volatile var front = true
    @Volatile var dismissed = false
    @Volatile var shown = false
    /** Браузер, в котором идёт вход, не отдаёт текст страницы (по нескольку проверок подряд ни одного шага и почти нет текста): предлагаем Chrome. */
    val blindState = androidx.compose.runtime.mutableStateOf(false)
    @Volatile private var noMatch = 0

    // мастер ключа (не вход): подсказки включены, пока открыт мастер
    @Volatile private var armedId: String? = null
    @Volatile private var armedStep: Int? = null
    @Volatile private var armedAt = 0L

    private fun serviceId(): String? {
        val a = armedId
        if (a != null && System.currentTimeMillis() - armedAt < 30 * 60_000L) return a
        return if (CloudAuth.phase == CloudAuth.WAIT && CloudAuth.curType == "drive") "drive" else null
    }

    /** Мастер ключа открыл страницу сервиса в браузере: пока мастер открыт, поверх браузера показывается подсказка этого шага. */
    fun arm(ctx: Context, id: String, wizardStep: Int?) {
        armedId = id; armedStep = wizardStep; armedAt = System.currentTimeMillis(); dismissed = false
        HintRules.refreshAsync(ctx)
    }
    fun disarm() { armedId = null; armedStep = null; hide() }

    /** MainActivity.onPause: программа ушла в фон (открылся браузер). */
    fun onAppPause(ctx: Context) {
        front = false
        if (serviceId() == null || dismissed || !CloudReturn.canOverlay(ctx)) return
        val app = ctx.applicationContext
        main.postDelayed({ if (!front && serviceId() != null && !dismissed) show(app) }, 700)
    }

    /** MainActivity.onResume: вернулись в программу, подсказка в окне, плавающая не нужна. */
    fun onAppResume() { front = true; hide() }

    /** Новый вход: закрытая подсказка снова может показаться. */
    fun reset() { dismissed = false; noMatch = 0; blindState.value = false }

    fun hide() {
        main.post {
            for (v in views) runCatching { (v.context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).removeView(v) }
            views.clear(); hint = null; shown = false
        }
    }

    // ---- поиск элемента на экране (вызывается из AutoPairService раз в секунду, пока подсказка показана) ----

    private fun collect(n: android.view.accessibility.AccessibilityNodeInfo?, out: MutableList<android.view.accessibility.AccessibilityNodeInfo>, depth: Int = 0) {
        if (n == null || depth > 60 || out.size > 4000) return
        out.add(n)
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1)
    }

    /** Подходящий элемент для шага: самый маленький видимый, чей текст или описание содержит фразу (или равен ей при exact). */
    private fun findFor(step: HintRules.Step, nodes: List<android.view.accessibility.AccessibilityNodeInfo>): android.graphics.Rect? {
        var best: android.graphics.Rect? = null; var bestArea = Long.MAX_VALUE
        val r = android.graphics.Rect()
        for (n in nodes) {
            val t = n.text?.toString().orEmpty().trim().lowercase(); val d = n.contentDescription?.toString().orEmpty().trim().lowercase()
            if (t.isEmpty() && d.isEmpty()) continue
            if (!n.isVisibleToUser) continue
            val ok = step.find.any { f -> val p = f.lowercase(); if (step.exact) (t == p || d == p) else (t.contains(p) || d.contains(p)) }
            if (!ok) continue
            n.getBoundsInScreen(r)
            if (r.isEmpty || r.width() < 4 || r.height() < 4) continue
            val area = r.width().toLong() * r.height()
            if (area < bestArea) { bestArea = area; best = android.graphics.Rect(r) }
        }
        return best
    }

    /** AutoPairService.tick: найти на экране элементы шагов и передать подсказке. */
    fun scan(root: android.view.accessibility.AccessibilityNodeInfo?) {
        val h = hint ?: return
        val ctx = appCtx ?: return
        val id = serviceId() ?: return
        if (root == null || root.packageName?.toString() == ctx.packageName) { main.post { h.setFound(emptyMap()) }; return }
        val steps = HintRules.steps(ctx, id, armedStep)
        val nodes = ArrayList<android.view.accessibility.AccessibilityNodeInfo>()
        collect(root, nodes)
        val found = HashMap<Int, android.graphics.Rect>()
        steps.forEachIndexed { i, s -> findFor(s, nodes)?.let { found[i] = it } }
        if (found.isNotEmpty()) { noMatch = 0; main.post { blindState.value = false } }
        else {
            noMatch++
            val textNodes = nodes.count { !it.text.isNullOrBlank() }
            val pkg = root.packageName?.toString().orEmpty()
            if (noMatch >= 4 && pkg != "com.android.chrome") { val blind = textNodes < 25; main.post { blindState.value = blind } }
        }
        main.post { h.setFound(found) }
    }

    /** Прозрачный рисунок: стрелки у найденных элементов и строки шагов, которых на экране нет; всё с чёрной обводкой, без фона. */
    internal class HintView(ctx: Context, private val tv: Boolean, private val steps: List<Pair<String, String>>) : android.view.View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFEA00.toInt(); style = android.graphics.Paint.Style.FILL }
        private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = 7 * d; strokeJoin = android.graphics.Paint.Join.ROUND }
        private val txt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = android.graphics.Typeface.DEFAULT_BOLD }
        private val txtEdge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = android.graphics.Paint.Style.STROKE; strokeJoin = android.graphics.Paint.Join.ROUND; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        private var pulse = 1f
        private var found: Map<Int, android.graphics.Rect> = emptyMap()
        private val anim = android.animation.ValueAnimator.ofFloat(0.88f, 1.14f).apply {
            duration = 550; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
        }
        fun setFound(f: Map<Int, android.graphics.Rect>) { found = f; invalidate() }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

        private fun line(c: android.graphics.Canvas, s: String, x: Float, y: Float, sz: Float, right: Boolean = false) {
            // длинная строка уменьшается, чтобы помещаться в ширину экрана (портрет, крупный шрифт)
            var z = sz; txt.textSize = z
            while (txt.measureText(s) > width - 24 * d && z > 10 * d) { z *= 0.94f; txt.textSize = z }
            txtEdge.textSize = z; txtEdge.strokeWidth = z * 0.17f
            val w = txt.measureText(s)
            val xx = if (right) x - w else x
            c.drawText(s, xx, y, txtEdge); c.drawText(s, xx, y, txt)
        }

        override fun onDraw(c: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val loc = IntArray(2); getLocationOnScreen(loc)
            val len = (if (tv) 150 else 100) * d
            val big = (if (tv) 36f else 24f) * d
            val small = big * 0.66f
            // 1) стрелки у найденных элементов
            // одна стрелка за раз: первый по порядку шагов найденный элемент; следующий шаг появится, когда этот исчезнет
            val cur = found.keys.minOrNull()
            for ((i, r0) in found) {
                if (i != cur) continue
                val (label, _) = steps.getOrNull(i) ?: continue
                val r = android.graphics.RectF(r0.left - loc[0].toFloat(), r0.top - loc[1].toFloat(), r0.right - loc[0].toFloat(), r0.bottom - loc[1].toFloat())
                txt.textSize = big
                val gap = 8 * d
                val roomR = w - r.right - gap; val roomL = r.left - gap
                val vertical = maxOf(roomR, roomL) < len * 0.9f          // сбоку места нет (узкий экран, элемент во всю ширину): стрелка сверху или снизу
                val up = r.top > len + big * 1.5f
                val pointLeft = roomR >= roomL                           // стрелка справа от элемента и смотрит на него влево, иначе слева и смотрит вправо
                val tipX = if (vertical) r.centerX().coerceIn(len * 0.35f, w - len * 0.35f) else if (pointLeft) r.right + gap else r.left - gap
                val tipY = if (vertical) (if (up) r.top - gap else r.bottom + gap) else r.centerY()
                c.save(); c.translate(tipX, tipY)
                if (vertical) c.rotate(if (up) -90f else 90f) else if (!pointLeft) c.rotate(180f)
                c.scale(pulse, pulse)
                val hd = len * 0.42f; val hw = len * 0.28f; val sw = len * 0.11f
                val p = android.graphics.Path().apply { moveTo(0f, 0f); lineTo(hd, -hw); lineTo(hd, -sw); lineTo(len, -sw); lineTo(len, sw); lineTo(hd, sw); lineTo(hd, hw); close() }
                c.drawPath(p, edge); c.drawPath(p, fill)
                c.restore()
                val lw = txt.measureText(label)
                if (vertical) {
                    val lx = (tipX - lw / 2).coerceIn(8 * d, w - lw - 8 * d)
                    val ly = if (up) tipY - len * pulse - 10 * d else tipY + len * pulse + big
                    line(c, label, lx, ly.coerceIn(big, h - 8 * d), big)
                } else {
                    val ly = (tipY - hw * 1.15f - 6 * d).coerceAtLeast(big)
                    if (pointLeft) line(c, label, (tipX + 8 * d).coerceAtMost(w - lw - 8 * d), ly, big)
                    else line(c, label, (tipX - 8 * d).coerceAtLeast(lw + 8 * d), ly, big, true)
                }
            }
            // 2) нет ни одного найденного элемента: одна короткая строка текущего шага, без указателя
            if (found.isEmpty()) steps.firstOrNull { it.second.isNotEmpty() }?.let { line(c, it.second, w - 16 * d, h * 0.80f, small, true) }
        }
    }

    /** Кнопка «✕» без фона: только крест с чёрной обводкой и жёлтой серединой, читается на любом фоне. */
    private class CloseMark(ctx: Context) : android.view.View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = 11 * d; strokeCap = android.graphics.Paint.Cap.ROUND }
        private val mid = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFEA00.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = 5 * d; strokeCap = android.graphics.Paint.Cap.ROUND }
        init { isClickable = true }
        override fun onDraw(c: android.graphics.Canvas) {
            val m = width * 0.22f; val w = width.toFloat(); val h = height.toFloat()
            for (p in arrayOf(edge, mid)) { c.drawLine(m, m, w - m, h - m, p); c.drawLine(w - m, m, m, h - m, p) }
        }
    }

    fun show(app: Context) {
        main.post {
            if (views.isNotEmpty() || !CloudReturn.canOverlay(app)) return@post
            val id = serviceId() ?: return@post
            val lang = HintRules.lang(app)
            val steps = HintRules.steps(app, id, armedStep).map { it.labelFor(lang) to it.textFor(lang) }
            if (steps.isEmpty()) return@post
            appCtx = app
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val d = app.resources.displayMetrics.density
            val tv = Marq.tv
            // 1) прозрачный слой: ни фокуса, ни касаний, нажатия идут на страницу под ним
            val hv = HintView(app, tv, steps)
            val lp = LP(LP.MATCH_PARENT, LP.MATCH_PARENT, LP.TYPE_APPLICATION_OVERLAY,
                LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCHABLE or LP.FLAG_LAYOUT_IN_SCREEN, android.graphics.PixelFormat.TRANSLUCENT)
            if (runCatching { wm.addView(hv, lp) }.isFailure) return@post
            views += hv; hint = hv; shown = true
            // 2) маленькая кнопка «✕» у правого края: единственное, что принимает нажатие
            val x = CloseMark(app).apply { setOnClickListener { dismissed = true; hide() } }
            val s = ((if (tv) 56 else 40) * d).toInt()
            val lp2 = LP(s, s, LP.TYPE_APPLICATION_OVERLAY, LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCH_MODAL, android.graphics.PixelFormat.TRANSLUCENT).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END; this.x = (6 * d).toInt(); y = 0 }
            if (runCatching { wm.addView(x, lp2) }.isSuccess) views += x
            main.postDelayed({ if (hint === hv) hide() }, 180_000L)
        }
    }
}

/**
 * Страница на 127.0.0.1:53682 после входа. Движок входа закрывает порт сразу после первого возврата Google; браузер, который
 * открыл адрес повторно (обновление, второй переход, предзагрузка), видел «Этот сайт недоступен». Пока идёт эта минута,
 * на порту отвечает программа: «Готово» после успеха или «Вход не завершён» после ошибки.
 */
object LoginEcho {
    const val PORT = 53682
    @Volatile private var srv: ServerSocket? = null

    private fun bind(): ServerSocket? = runCatching {
        ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 8) }
    }.getOrNull()

    /** Освобождает порт перед новым входом: наша страница закрывается, забытые процессы движка этого приложения завершаются. */
    fun freePort() {
        stop()
        repeat(2) { attempt ->
            val s = bind()
            if (s != null) { runCatching { s.close() }; return }
            if (attempt == 0) strayKill()
            Thread.sleep(700)
        }
    }

    private fun strayKill() {
        runCatching {
            File("/proc").listFiles()?.forEach { d ->
                val pid = d.name.toIntOrNull() ?: return@forEach
                if (pid == android.os.Process.myPid()) return@forEach
                val cmd = runCatching { File(d, "cmdline").readText() }.getOrDefault("")
                if (cmd.contains("libclouds.so") && cmd.contains("authorize")) runCatching { android.os.Process.killProcess(pid) }
            }
        }
    }

    @Synchronized fun stop() { runCatching { srv?.close() }; srv = null }

    @Synchronized fun start(ctx: Context, ok: Boolean, ttlMs: Long = 600_000) {
        stop()
        val s = bind() ?: return
        srv = s
        val lang = ctx.resources.configuration.locales[0].language.takeIf { it in setOf("ru", "uk", "en") } ?: "ru"
        val html = "<!DOCTYPE html><html lang=\"" + lang + "\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<body style=\"font:20px sans-serif;text-align:center;margin:18vh 16px 0\"><h1>" + ctx.getString(if (ok) R.string.cl_echo_ok_title else R.string.cl_echo_fail_title) + "</h1><p>" +
            ctx.getString(if (ok) R.string.cl_echo_ok_text else R.string.cl_echo_fail_text) + "</p><p><a href=\"intent://open#Intent;scheme=adbphon;package=app.adbphon;end\" style=\"display:inline-block;margin-top:14px;padding:16px 40px;border-radius:14px;background:#0f766e;color:#fff;font-weight:bold;font-size:24px;text-decoration:none\">ADB-phon \u203a</a></p>" +
            "<script>setTimeout(function(){location.href='intent://open#Intent;scheme=adbphon;package=app.adbphon;end'},600)</script></body></html>"
        val body = html.toByteArray(Charsets.UTF_8)
        Thread {
            val end = System.currentTimeMillis() + ttlMs
            runCatching { s.soTimeout = 1000 }
            while (!s.isClosed && System.currentTimeMillis() < end) {
                try {
                    val c = s.accept()
                    Thread {
                        runCatching {
                            c.soTimeout = 2000
                            val first = c.getInputStream().bufferedReader().readLine().orEmpty()
                            val icon = first.contains("favicon")
                            val head = (if (icon) "HTTP/1.1 204 No Content\r\n" else "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n") + "Connection: close\r\n\r\n"
                            c.getOutputStream().apply { write(head.toByteArray()); if (!icon) write(body); flush() }
                        }
                        runCatching { c.close() }
                    }.start()
                } catch (_: SocketTimeoutException) { } catch (_: Exception) { break }
            }
            runCatching { s.close() }
            synchronized(this) { if (srv === s) srv = null }
        }.apply { isDaemon = true; start() }
    }
}

/** Возврат в ADB-phon после входа в облако: прямой запуск (если система разрешает из фона) и уведомление-запас. */
object CloudReturn {
    private const val ID = 4109

    fun canOverlay(ctx: Context) = runCatching { android.provider.Settings.canDrawOverlays(ctx) }.getOrDefault(false)

    @Volatile var resumedAt = 0L

    /**
     * Цепочка «пробуем по очереди»: 1) прямой запуск (работает, если выдано «Поверх других окон» или приложение на виду);
     * 2) команда am start через ADB-связь приложения («этот телефон») или root: оболочка может запускать из фона;
     * 3) уведомление «Облако добавлено» с полноэкранным намерением (если у программы разрешены уведомления);
     * 4) страница «Готово» в браузере со ссылкой. Какой способ сработал, пишется в журнал (тег CloudReturn).
     */
    fun toFront(ctx: Context, skipDirect: Boolean = false) {
        val t0 = System.currentTimeMillis()
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        fun front() = resumedAt >= t0
        Thread {
            val tag = "CloudReturn"
            if (!skipDirect) runCatching { android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { ctx.startActivity(i) } } }
            Thread.sleep(1500)
            if (front()) { android.util.Log.i(tag, "способ: прямой запуск (поверх окон: " + canOverlay(ctx) + ")"); return@Thread }
            android.util.Log.i(tag, "прямой запуск заблокирован системой (поверх окон: " + canOverlay(ctx) + ")")
            if (Adb.isConnected) {
                runCatching { kotlinx.coroutines.runBlocking { Adb.shell("am start -n app.adbphon/.MainActivity -f 0x30020000", 8_000) } }
                Thread.sleep(1200)
                if (front()) { android.util.Log.i(tag, "способ: am start через ADB-связь приложения"); return@Thread }
                android.util.Log.i(tag, "через ADB-связь не вышло")
            } else android.util.Log.i(tag, "ADB-связь приложения не подключена")
            runCatching {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("cloudlogin_done", ctx.getString(R.string.cl_login_channel), NotificationManager.IMPORTANCE_HIGH))
                val pi = PendingIntent.getActivity(ctx, 3, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val n = Notification.Builder(ctx).setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(ctx.getString(R.string.cl_done_notif_title)).setContentText(ctx.getString(R.string.cl_done_notif_text))
                    .setContentIntent(pi).setFullScreenIntent(pi, true).setAutoCancel(true).also { if (android.os.Build.VERSION.SDK_INT >= 26) it.setChannelId("cloudlogin_done") }.build()
                nm.notify(ID, n)
                val shown = nm.areNotificationsEnabled()
                android.util.Log.i(tag, "способ: уведомление «Облако добавлено» " + (if (shown) "показано" else "НЕ показано: уведомления ADB-phon отключены") + "; осталась ссылка на странице «Готово»")
            }
        }.start()
    }

    fun cancelNotice(ctx: Context) { runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID) } }
}
