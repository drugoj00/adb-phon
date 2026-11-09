package app.adbphon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.min

/**
 * ПРОТОТИП: полупрозрачные кнопки поверх игры на ЭТОМ телефоне. Каждый элемент — отдельное маленькое окно-оверлей (TYPE_APPLICATION_OVERLAY, разрешение
 * «Поверх других окон»): касания мимо элементов проходят сквозь в игру, элементы держат свои касания одновременно (мультитач). Ввод идёт в Pad, а цикл Pad.loop
 * отправляет его в виртуальный контроллер ([VirtualPad]). Только прототип, в релиз не входит.
 */
class OverlayPadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private var watch: Job? = null
    private var builtPortrait = false
    private var builtW = 0
    private var builtH = 0
    private val views = ArrayList<View>()
    private lateinit var wm: WindowManager

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // тихий канал: минимальная важность, без звука и вибрации
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Gamepad overlay", NotificationManager.IMPORTANCE_MIN).apply { setSound(null, null); enableVibration(false); setShowBadge(false) })
        val stop = PendingIntent.getService(this, 2, Intent(this, OverlayPadService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this).setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel).setContentTitle("ADB-phon").setOngoing(true)
            .apply { if (Build.VERSION.SDK_INT >= 26) setChannelId(CH) }
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), getString(R.string.btn_close), stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(ID, n)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (intent?.action == ACTION_STOP) { shutdown(true); return START_NOT_STICKY }     // сначала startForeground (иначе система считает запуск ошибкой), потом остановка
        Pad.load(applicationContext)
        getSharedPreferences("adbphon", MODE_PRIVATE).edit().putBoolean("overlay_on", true).apply()
        if (starting || running) return START_STICKY
        starting = true
        scope.launch {
            // подключение к помощнику (запуск через ADB только если он не жив) — вне главного потока
            val ok = Settings.canDrawOverlays(this@OverlayPadService) && kotlinx.coroutines.withContext(Dispatchers.IO) { VirtualPad.start(applicationContext) }
            starting = false
            if (!ok) { android.widget.Toast.makeText(applicationContext, getString(R.string.pad_local_need_adb), android.widget.Toast.LENGTH_LONG).show(); shutdown(false); return@launch }
            VirtualPad.on = true
            Pad.fg = true
            running = true
            // живучесть и батарея (тихо, только при ADB): исключение из Doze и «активная» корзина на время игры
            kotlinx.coroutines.withContext(Dispatchers.IO) { keepAlive(true) }
            // ориентацию экрана задаёт сама игра: оверлей её не меняет, а подстраивает раскладку под реальный экран
            build()
            if (loop == null) loop = scope.launch { Pad.loop() }
            // автопереключение портрет/альбом без перезапуска службы: следим за реальными размерами дисплея
            if (watch == null) watch = scope.launch { while (true) { kotlinx.coroutines.delay(300); if (running) { val q = realSize(); val port = q.y > q.x; if (port != builtPortrait || q.x != builtW || q.y != builtH) { clear(); build() } } } }
        }
        return START_STICKY      // после убийства процесса система перезапускает службу: оверлей поднимается заново и подключается к живому помощнику
    }

    private suspend fun keepAlive(on: Boolean) {
        if (!(Adb.isConnected && Adb.currentHost.isEmpty() && !Adb.isRoot)) return
        val sp = getSharedPreferences("adbphon", MODE_PRIVATE)
        if (on) {
            val had = Adb.shell("cmd deviceidle whitelist", 8_000L).contains(packageName)
            if (!had) { Adb.shell("cmd deviceidle whitelist +$packageName 2>&1", 8_000L); sp.edit().putBoolean("overlay_wl_added", true).apply() }
            Adb.shell("am set-standby-bucket $packageName active 2>&1", 8_000L)
        } else if (sp.getBoolean("overlay_wl_added", false)) {
            Adb.shell("cmd deviceidle whitelist -$packageName 2>&1", 8_000L); sp.edit().putBoolean("overlay_wl_added", false).apply()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); if (running) { clear(); build() } }

    private fun realSize(): android.graphics.Point {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        // ширина без боковой панели навигации (телефон в ландшафте, панель слева или справа): окна оверлея отсчитываются от её края, иначе правые кнопки уходят за экран
        val u = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(u)
        // панель навигации может стоять снизу (портрет), слева или справа (ландшафт) и быть видна в игре: берём область без неё по обеим осям
        val w = if (u.widthPixels in (m.widthPixels / 2)..m.widthPixels) u.widthPixels else m.widthPixels
        val h = if (u.heightPixels in (m.heightPixels / 2)..m.heightPixels) u.heightPixels else m.heightPixels
        return android.graphics.Point(w, h)
    }

    private fun add(v: View, x: Int, y: Int, w: Int, h: Int) {
        val lp = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START; lp.x = x; lp.y = y
        runCatching { wm.addView(v, lp); views.add(v) }
    }

    private fun build() {
        val p = realSize(); val W = p.x; val H = p.y
        val a = Pad.alpha.coerceIn(0.15f, 1f)
        val sz = Pad.scale.coerceIn(0.7f, 1.3f)
        builtPortrait = H > W; builtW = W; builtH = H
        if (H > W) buildPortrait(W, H, a, sz) else buildLandscape(W, H, a, sz)
    }

    /** Портрет: единица u = ширина. Внизу слева левый стик и крестовина, справа ABXY и правый стик, ряд LT/LB и RT/RB над ними, Back/Guide/Start по центру над системной навигацией. Отступы у краёв ≥ 0,07u (жесты). */
    private fun buildPortrait(W: Int, H: Int, a: Float, sz: Float) {
        val u = W.toFloat()
        fun px(f: Float) = (f * u).toInt()
        val navBottom = px(0.05f)
        val rs = px(0.17f * sz)
        val smallR = px(0.06f); val yS = H - navBottom - px(0.10f)
        add(ButtonView(this, "BK", Pad.SELECT, a, round = true), W / 2 - px(0.19f) - smallR, yS - smallR, 2 * smallR, 2 * smallR)
        add(ButtonView(this, "H", Pad.HOME, a, round = true), W / 2 - smallR, yS - smallR, 2 * smallR, 2 * smallR)
        add(ButtonView(this, "ST", Pad.START, a, round = true), W / 2 + px(0.19f) - smallR, yS - smallR, 2 * smallR, 2 * smallR)
        val lcx = px(0.07f) + rs; val lcy = yS - smallR - px(0.04f) - rs
        add(StickView(this, 0, 1, a), lcx - rs, lcy - rs, 2 * rs, 2 * rs)
        val dsz = px(0.36f * sz); val dcy = lcy - rs - px(0.03f) - dsz / 2
        add(DpadView(this, a), lcx - dsz / 2, dcy - dsz / 2, dsz, dsz)
        val br = px(0.065f * sz); val d = px(0.125f * sz)
        val rcx = W - px(0.07f) - d - br; val rcy = yS - smallR - px(0.04f) - d - br
        fun btn(label: String, bit: Int, bx: Int, by: Int) = add(ButtonView(this, label, bit, a, round = true), bx - br, by - br, 2 * br, 2 * br)
        btn("A", Pad.A, rcx, rcy + d); btn("B", Pad.B, rcx + d, rcy); btn("X", Pad.X, rcx - d, rcy); btn("Y", Pad.Y, rcx, rcy - d)
        val rs2 = px(0.15f * sz); val r2cy = rcy - d - br - px(0.03f) - rs2
        add(StickView(this, 2, 3, a), rcx - rs2, r2cy - rs2, 2 * rs2, 2 * rs2)
        val sw = px(0.27f); val sh = px(0.12f)
        val yTop = minOf(dcy - dsz / 2, r2cy - rs2) - px(0.03f) - 2 * sh - px(0.02f)
        add(ButtonView(this, "LT", -2, a), px(0.07f), yTop, sw, sh)
        add(ButtonView(this, "LB", Pad.L1, a), px(0.07f), yTop + sh + px(0.02f), sw, sh)
        add(ButtonView(this, "RT", -3, a), W - px(0.07f) - sw, yTop, sw, sh)
        add(ButtonView(this, "RB", Pad.R1, a), W - px(0.07f) - sw, yTop + sh + px(0.02f), sw, sh)
        val cs = px(0.09f)
        add(CloseView(this) { shutdown(true) }, (W - cs) / 2, px(0.10f), cs, cs)
    }

    private fun buildLandscape(W: Int, H: Int, a: Float, sz: Float) {
        val u = min(W, H).toFloat()
        val r = (0.19f * u).toInt()
        add(StickView(this, 0, 1, a), (0.05f * W).toInt(), H - 2 * r - (0.08f * u).toInt(), 2 * r, 2 * r)
        val br = (0.075f * u).toInt(); val d = (0.135f * u).toInt()
        val cx = W - (0.26f * u).toInt(); val cy = H - (0.26f * u).toInt()
        fun btn(label: String, bit: Int, bx: Int, by: Int) = add(ButtonView(this, label, bit, a, round = true), bx - br, by - br, 2 * br, 2 * br)
        btn("A", Pad.A, cx, cy + d); btn("B", Pad.B, cx + d, cy); btn("X", Pad.X, cx - d, cy); btn("Y", Pad.Y, cx, cy - d)
        val sw = (0.22f * u).toInt(); val sh = (0.12f * u).toInt()
        add(ButtonView(this, "LT", -2, a), (0.03f * W).toInt(), (0.04f * u).toInt(), sw, sh)
        add(ButtonView(this, "LB", Pad.L1, a), (0.03f * W).toInt(), (0.04f * u).toInt() + sh + (0.02f * u).toInt(), sw, sh)
        add(ButtonView(this, "RT", -3, a), W - (0.03f * W).toInt() - sw, (0.04f * u).toInt(), sw, sh)
        add(ButtonView(this, "RB", Pad.R1, a), W - (0.03f * W).toInt() - sw, (0.04f * u).toInt() + sh + (0.02f * u).toInt(), sw, sh)
        val cs = (0.09f * u).toInt()
        add(CloseView(this) { shutdown(true) }, (W - cs) / 2, (0.02f * u).toInt(), cs, cs)
        // Back, Guide и Start компактным рядом по центру сверху, под кнопкой закрытия (как в портрете, те же кнопки Pad.SELECT/HOME/START);
        // зона касания не меньше 44 dp, ряд не заходит на курки и бамперы по бокам
        val dpPx = resources.displayMetrics.density
        val bd = maxOf((0.10f * u).toInt(), (44f * dpPx).toInt()); val gap = (0.015f * u).toInt()
        val ry = (0.02f * u).toInt() + cs + (0.02f * u).toInt()
        add(ButtonView(this, "BK", Pad.SELECT, a, round = true), W / 2 - bd / 2 - bd - gap, ry, bd, bd)
        add(ButtonView(this, "H", Pad.HOME, a, round = true), W / 2 - bd / 2, ry, bd, bd)
        add(ButtonView(this, "ST", Pad.START, a, round = true), W / 2 + bd / 2 + gap, ry, bd, bd)
    }

    private fun clear() { views.forEach { runCatching { wm.removeView(it) } }; views.clear() }

    /** [kill]: пользователь закрыл оверлей: убрать и помощника с виртуальным контроллером. При смерти процесса помощник остаётся жить. */
    private fun shutdown(kill: Boolean) {
        running = false; starting = false
        getSharedPreferences("adbphon", MODE_PRIVATE).edit().putBoolean("overlay_on", false).apply()
        loop?.cancel(); loop = null; watch?.cancel(); watch = null
        runCatching { clear() }
        VirtualPad.stop(kill)
        Pad.fg = false
        scope.launch { kotlinx.coroutines.withContext(Dispatchers.IO) { keepAlive(false) } }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() { super.onDestroy() }

    companion object {
        const val CH = "padoverlay"; const val ID = 4119; const val ACTION_STOP = "stop"
        @Volatile var running = false
        @Volatile var starting = false
        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, OverlayPadService::class.java)) }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, OverlayPadService::class.java).setAction(ACTION_STOP)) }
    }
}

private fun basePaint(a: Float, fill: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = if (fill) Paint.Style.FILL else Paint.Style.STROKE; strokeWidth = 3f
    // серая заливка и тёмная обводка: видно и на светлом, и на тёмном фоне игры
    color = if (fill) android.graphics.Color.argb((a * 110).toInt(), 128, 128, 128) else android.graphics.Color.argb((a * 230).toInt(), 20, 20, 20)
}

/** Стик: касание в окне задаёт отклонение от его центра; отпускание возвращает в центр. */
private class StickView(ctx: Context, val ix: Int, val iy: Int, val a: Float) : View(ctx) {
    private var kx = 0f; private var ky = 0f
    private var pid = -1
    private val fill = basePaint(a, true); private val line = basePaint(a, false)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb((a * 160).toInt(), 255, 255, 255) }
    override fun onDraw(c: Canvas) {
        val r = width / 2f
        c.drawCircle(r, r, r - 4f, fill); c.drawCircle(r, r, r - 4f, line)
        c.drawCircle(r + kx * r * 0.7f, r + ky * r * 0.7f, r * 0.34f, knob)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> if (pid == -1) { pid = e.getPointerId(e.actionIndex); upd(e, e.actionIndex) }
            MotionEvent.ACTION_MOVE -> { val i = e.findPointerIndex(pid); if (i >= 0) upd(e, i) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL ->
                if (e.actionMasked == MotionEvent.ACTION_CANCEL || e.getPointerId(e.actionIndex) == pid) { pid = -1; kx = 0f; ky = 0f; Pad.axes[ix] = 0f; Pad.axes[iy] = 0f; Pad.dirty = true; invalidate() }
        }
        return true
    }
    private fun upd(e: MotionEvent, i: Int) {
        val r = width / 2f
        var dx = (e.getX(i) - r) / r; var dy = (e.getY(i) - r) / r
        val m = hypot(dx, dy); if (m > 1f) { dx /= m; dy /= m }
        kx = dx; ky = dy; Pad.axes[ix] = dx; Pad.axes[iy] = dy; Pad.dirty = true; invalidate()
    }
}

/** Кнопка: [bit] ≥ 0 — кнопка Pad; -2 — левый курок (тормоз), -3 — правый (газ). */
private class ButtonView(ctx: Context, val label: String, val bit: Int, val a: Float, val round: Boolean = false) : View(ctx) {
    private var down = false
    private val fill = basePaint(a, true); private val line = basePaint(a, false)
    private val hot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb((a * 170).toInt(), 94, 234, 212) }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb((a * 255).toInt(), 255, 255, 255); textAlign = Paint.Align.CENTER; isFakeBoldText = true; setShadowLayer(5f, 0f, 0f, android.graphics.Color.BLACK) }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        txt.textSize = h * 0.38f
        if (round) { val r = min(w, h) / 2f - 3f; c.drawCircle(w / 2, h / 2, r, if (down) hot else fill); c.drawCircle(w / 2, h / 2, r, line) }
        else { val rr = RectF(3f, 3f, w - 3f, h - 3f); c.drawRoundRect(rr, h / 3, h / 3, if (down) hot else fill); c.drawRoundRect(rr, h / 3, h / 3, line) }
        c.drawText(label, w / 2, h / 2 + txt.textSize * 0.35f, txt)
    }
    private fun set(on: Boolean) {
        if (down == on) return
        down = on
        when (bit) { -2 -> Pad.lt = on; -3 -> Pad.rt = on; else -> Pad.buttons = if (on) Pad.buttons or (1 shl bit) else Pad.buttons and (1 shl bit).inv() }
        Pad.dirty = true; invalidate()
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> set(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> set(false)
        }
        return true
    }
}

/** Маленький крестик сверху по центру: закрыть оверлей. */
private class CloseView(ctx: Context, val onClose: () -> Unit) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(150, 255, 255, 255); strokeWidth = 5f; style = Paint.Style.STROKE }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(70, 0, 0, 0) }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); c.drawCircle(w / 2, w / 2, w / 2 - 2f, bg)
        val m = w * 0.3f; c.drawLine(m, m, w - m, w - m, p); c.drawLine(w - m, m, m, w - m, p)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean { if (e.actionMasked == MotionEvent.ACTION_UP) onClose(); return true }
}

/** Крестовина: касание задаёт одно из 8 направлений (0 вверх, по часовой), в центре и при отпускании нейтраль (8). */
private class DpadView(ctx: Context, val a: Float) : View(ctx) {
    private var hat = 8
    private var pid = -1
    private val fill = basePaint(a, true); private val line = basePaint(a, false)
    private val hot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb((a * 170).toInt(), 94, 234, 212) }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val t = w / 3f
        fun arm(l: Float, tp: Float, on: Boolean) { val r = RectF(l + 7f, tp + 7f, l + t - 7f, tp + t - 7f); c.drawRoundRect(r, t / 5, t / 5, if (on) hot else fill); c.drawRoundRect(r, t / 5, t / 5, line) }
        val up = hat == 7 || hat == 0 || hat == 1; val right = hat in 1..3; val down = hat in 3..5; val left = hat in 5..7
        arm(t, 0f, up); arm(t, 2 * t, down); arm(0f, t, left); arm(2 * t, t, right)
        val r = RectF(t + 7f, t + 7f, 2 * t - 7f, 2 * t - 7f); c.drawRoundRect(r, t / 5, t / 5, fill); c.drawRoundRect(r, t / 5, t / 5, line)
    }
    private fun set(h: Int) { if (h != hat) { hat = h; Pad.hat = h; Pad.dirty = true; invalidate() } }
    private fun dir(e: MotionEvent, i: Int): Int {
        val c = width / 2f; val dx = e.getX(i) - c; val dy = e.getY(i) - c
        if (hypot(dx, dy) < width * 0.12f) return 8
        var ang = Math.toDegrees(Math.atan2(dx.toDouble(), -dy.toDouble())); if (ang < 0) ang += 360.0
        return (((ang + 22.5) / 45.0).toInt()) % 8
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> if (pid == -1) { pid = e.getPointerId(e.actionIndex); set(dir(e, e.actionIndex)) }
            MotionEvent.ACTION_MOVE -> { val i = e.findPointerIndex(pid); if (i >= 0) set(dir(e, i)) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL ->
                if (e.actionMasked == MotionEvent.ACTION_CANCEL || e.getPointerId(e.actionIndex) == pid) { pid = -1; set(8) }
        }
        return true
    }
}
