package app.adbphon

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import app.adbphon.FloorText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.math.hypot

/**
 * Режим «Кнопки на экран» (10.0, kbtn): оверлей-геймпад вместо виртуального контроллера нажимает в точки экрана игры службой спецвозможностей
 * программы (AutoPairService, dispatchGesture). Логика жестов в [ScreenTouchPlan], здесь отправка жестов, профили и окно расстановки.
 */
object ScreenTouch {
    private const val KEY_ON = "screen_touch_on"
    private const val SP = "screen_touch"

    @Volatile var svc: WeakReference<AccessibilityService>? = null
    /** Пакет окна на переднем плане (по событиям службы), без самой программы и системной шторки. */
    @Volatile var fgPkg = ""
    @Volatile var active = false
    var editing = false

    private val plan = ScreenTouchPlan()
    private val taps = HashSet<String>()
    private var strokes = HashMap<Int, GestureDescription.StrokeDescription>()
    private var busy = false
    private var backoffUntil = 0L
    private var targets: Map<String, StTarget> = emptyMap()
    private var tKey = ""
    private var warnedKey = ""

    fun enabled(ctx: Context) = ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getBoolean(KEY_ON, false)
    fun setEnabled(ctx: Context, on: Boolean) { ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply() }
    fun ready(ctx: Context) = AutoPairService.isEnabled(ctx) && svc?.get() != null

    /** Контекст службы оверлея на языке программы. */
    fun lctx(ctx: Context): Context = Lang.wrap(ctx, ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getString("lang", "auto") ?: "auto")

    /** Из AutoPairService.onAccessibilityEvent: запомнить игру на переднем плане. */
    fun onEvent(s: AccessibilityService, e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = e.packageName?.toString() ?: return
        if (p == s.packageName || p == "com.android.systemui" || p == "android") return
        fgPkg = p
    }

    /** Из кнопок оверлея: нажатие запоминается, чтобы быстрое касание (нажал и отпустил между шагами) не потерялось. */
    fun edge(bit: Int, on: Boolean) { if (active && on) StIds.ofBit(bit)?.let { taps.add(it) } }
    fun edgeHat(h: Int) { if (active) taps.addAll(StIds.ofHat(h)) }

    fun realSize(ctx: Context): android.graphics.Point {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
        return android.graphics.Point(m.widthPixels, m.heightPixels)
    }

    fun load(ctx: Context, pkg: String, w: Int, h: Int): Map<String, StTarget> =
        StProfile.decode(ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(StProfile.key(pkg, w, h), null), w, h)

    fun save(ctx: Context, pkg: String, w: Int, h: Int, t: Map<String, StTarget>) {
        val e = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
        if (t.isEmpty()) e.remove(StProfile.key(pkg, w, h)) else e.putString(StProfile.key(pkg, w, h), StProfile.encode(t, w, h))
        e.apply(); tKey = ""
    }

    /** Цикл оверлея в режиме «Кнопки на экран» (главный поток): состояние Pad → жесты. */
    suspend fun loop(ctx: Context) {
        active = true; plan.reset(); strokes.clear(); busy = false; tKey = ""; warnedKey = ""
        try { while (true) { delay(16); pump(ctx) } } finally { active = false; liftAll() }
    }

    private fun pump(ctx: Context) {
        val now = SystemClock.uptimeMillis()
        if (editing || busy || now < backoffUntil) return
        val s = svc?.get() ?: return
        val sz = realSize(ctx)
        val key = StProfile.key(fgPkg, sz.x, sz.y)
        if (key != tKey) { liftAll(); tKey = key; targets = if (fgPkg.isEmpty()) emptyMap() else load(ctx, fgPkg, sz.x, sz.y); return }
        val inp = StInput.fromPad(Pad.buttons, Pad.lt, Pad.rt, Pad.hat, Pad.axes, HashSet(taps)); taps.clear()
        if (targets.isEmpty()) {
            val touched = inp.held.isNotEmpty() || inp.taps.isNotEmpty() || inp.sticks.values.any { hypot(it.first, it.second) > 0.3f }
            if (touched && warnedKey != key) {
                warnedKey = key
                android.widget.Toast.makeText(ctx, lctx(ctx).getString(R.string.kbtn_toast_no_profile), android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        val st = plan.step(inp, targets)
        if (st.isNotEmpty()) dispatch(s, st)
    }

    private val cb = object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(g: GestureDescription?) { busy = false }
        // жест отменён (чаще всего настоящим касанием экрана): система уже подняла все пальцы, следующий шаг прижмёт зажатое заново
        override fun onCancelled(g: GestureDescription?) { busy = false; plan.reset(); strokes.clear(); backoffUntil = SystemClock.uptimeMillis() + 30 }
    }

    private fun dispatch(s: AccessibilityService, st: List<StStroke>) {
        val b = GestureDescription.Builder()
        val made = HashMap<Int, GestureDescription.StrokeDescription>()
        for (k in st) {
            val path = Path().apply { moveTo(k.x0.coerceAtLeast(0f), k.y0.coerceAtLeast(0f)); lineTo(k.x1.coerceAtLeast(0f), k.y1.coerceAtLeast(0f)) }
            val prev = if (k.cont >= 0) strokes[k.cont] else null
            if (k.cont >= 0 && prev == null) { plan.reset(); strokes.clear(); return }
            val d = runCatching { prev?.continueStroke(path, 0, k.durMs, k.willContinue) ?: GestureDescription.StrokeDescription(path, 0, k.durMs, k.willContinue) }.getOrNull()
                ?: run { plan.reset(); strokes.clear(); return }
            b.addStroke(d); made[k.id] = d
        }
        busy = true
        val ok = runCatching { s.dispatchGesture(b.build(), cb, null) }.getOrDefault(false)
        if (ok) strokes = made else { busy = false; plan.reset(); strokes.clear(); backoffUntil = SystemClock.uptimeMillis() + 100 }
    }

    /** Поднять все прижатые пальцы (выход из оверлея, смена игры, окно расстановки). */
    fun liftAll() {
        val s = svc?.get()
        if (s != null && plan.down.isNotEmpty()) {
            val st = plan.step(StInput(emptySet()), targets)
            if (st.isNotEmpty()) dispatch(s, st)
        }
        plan.reset(); strokes.clear(); busy = false; taps.clear()
    }

    /** Включить службу спецвозможностей программы без экрана настроек: право WRITE_SECURE_SETTINGS (выдаётся по ADB к этому телефону). */
    suspend fun autoEnable(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        if (AutoPairService.isEnabled(app)) return@withContext true
        app.getSharedPreferences("adbphon", Context.MODE_PRIVATE).edit().putBoolean("acc_wanted", true).apply()
        if (app.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != android.content.pm.PackageManager.PERMISSION_GRANTED && Adb.isConnected && Adb.currentHost.isEmpty())
            runCatching { Adb.shell("pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS", 10_000L) }
        AutoPairService.ensureEnabled(app)
        repeat(8) { if (AutoPairService.isEnabled(app)) return@withContext true; delay(500) }
        AutoPairService.isEnabled(app)
    }

    /** Перезапустить оверлей, если он работает (смена режима). */
    fun restartOverlay(ctx: Context) {
        if (!(OverlayPadService.running || OverlayPadService.starting)) return
        val app = ctx.applicationContext
        OverlayPadService.stop(app)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (!OverlayPadService.running) OverlayPadService.start(app) }, 900)
    }
}

// ------------------------------------------------------------------ раздел «Геймпад»: переключатель режима

@Composable
fun ScreenTouchSwitch(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(ScreenTouch.enabled(ctx)) }
    var dlg by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp).toggleable(on, role = Role.Switch) { want ->
            if (want) dlg = true else { on = false; ScreenTouch.setEnabled(ctx, false); ScreenTouch.restartOverlay(ctx) }
        }.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.kbtn_switch), fontSize = 14.sp)
            Text(stringResource(R.string.kbtn_switch_sub), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
        }
        Switch(on, null)
    }
    if (dlg) {
        val need = !AutoPairService.isEnabled(ctx)
        fun turnOn() { on = true; ScreenTouch.setEnabled(ctx, true); ScreenTouch.restartOverlay(ctx) }
        LAlertDialog(
            onDismissRequest = { if (!busy) dlg = false },
            title = { Text(stringResource(R.string.kbtn_switch)) },
            text = {
                val st = rememberScrollState()
                Column(Modifier.heightIn(max = 420.dp).verticalScrollbar(st).verticalScroll(st), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.kbtn_dlg_text), fontSize = 14.sp)
                    Text(stringResource(R.string.kbtn_limits), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f))
                    if (need) {
                        Text(stringResource(R.string.kbtn_need_service), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        if (Build.VERSION.SDK_INT >= 33) OutlinedButton({
                            runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text(stringResource(R.string.kbtn_app_info)) }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    if (!need) { turnOn(); dlg = false; return@TextButton }
                    busy = true
                    scope.launch {
                        val ok = ScreenTouch.autoEnable(ctx)
                        busy = false; dlg = false; turnOn()
                        if (ok) android.widget.Toast.makeText(ctx, ctx.getString(R.string.kbtn_enabled_toast), android.widget.Toast.LENGTH_SHORT).show()
                        else {
                            android.widget.Toast.makeText(ctx, ctx.getString(R.string.kbtn_open_settings_toast), android.widget.Toast.LENGTH_LONG).show()
                            runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                }, enabled = !busy) { Text(stringResource(if (need) R.string.kbtn_enable else R.string.kbtn_ok)) }
            },
            dismissButton = { TextButton({ dlg = false }, enabled = !busy) { Text(stringResource(R.string.kbtn_cancel)) } },
        )
    }
}

// ------------------------------------------------------------------ оверлей: значок прицела и окно расстановки

/** Значок прицела рядом с крестиком оверлея: открыть расстановку точек для игры на переднем плане. */
internal class StSetupButton(ctx: Context, val onClick: () -> Unit) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(170, 255, 255, 255); strokeWidth = 4f; style = Paint.Style.STROKE }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(70, 0, 0, 0) }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val r = w / 2
        c.drawCircle(r, r, r - 2f, bg); c.drawCircle(r, r, w * 0.22f, p)
        c.drawLine(r, w * 0.15f, r, w * 0.85f, p); c.drawLine(w * 0.15f, r, w * 0.85f, r, p)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean { if (e.actionMasked == MotionEvent.ACTION_UP) onClick(); return true }
}

/**
 * Окно расстановки: полупрозрачный слой поверх игры, метки кнопок и стиков перетаскиваются на кнопки игры. Метка без места лежит в «лотке» по центру
 * (полая), долгое нажатие возвращает её туда. Красная метка лежит под кнопкой оверлея: касание попадёт в оверлей, а не в игру.
 */
@SuppressLint("ViewConstructor")
internal class StEditor(private val base: Context, private val wm: WindowManager, private val pkg: String, private val cover: () -> List<FloatArray>, private val onClose: () -> Unit) {
    private val ctx = android.view.ContextThemeWrapper(ScreenTouch.lctx(base), android.R.style.Theme_DeviceDefault)
    private val size = ScreenTouch.realSize(base)
    private val set = LinkedHashMap<String, StTarget>(ScreenTouch.load(base, pkg, size.x, size.y))
    private var root: FrameLayout? = null

    fun show() {
        ScreenTouch.editing = true; ScreenTouch.liftAll()
        val dp = ctx.resources.displayMetrics.density
        val marks = MarkView()
        val fr = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.keyCode == KeyEvent.KEYCODE_BACK) { if (e.action == KeyEvent.ACTION_UP) close(false); return true }
                return super.dispatchKeyEvent(e)
            }
        }
        fr.addView(marks, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { setColor(android.graphics.Color.argb(215, 20, 24, 28)); cornerRadius = 12 * dp }
        }
        val label = runCatching { base.packageManager.getApplicationLabel(base.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        fun tv(t: String, sz: Float) = TextView(ctx).apply { text = t; setTextColor(android.graphics.Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, sz); gravity = Gravity.CENTER_HORIZONTAL }
        bar.addView(tv(ctx.getString(R.string.kbtn_ed_title, label), 14f))
        bar.addView(tv(ctx.getString(R.string.kbtn_ed_hint), 12f).apply { alpha = 0.85f })
        fun btn(t: String, act: () -> Unit) = Button(ctx).apply {
            text = t; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); minHeight = (40 * dp).toInt(); minimumHeight = (40 * dp).toInt()
            minWidth = 0; minimumWidth = 0; setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0); setOnClickListener { act() }
        }
        val row1 = LinearLayout(ctx).apply { gravity = Gravity.CENTER_HORIZONTAL }
        row1.addView(btn(ctx.getString(R.string.kbtn_ed_minus)) { marks.radius(-1) })
        row1.addView(btn(ctx.getString(R.string.kbtn_ed_plus)) { marks.radius(1) })
        row1.addView(btn(ctx.getString(R.string.kbtn_ed_clear)) { set.clear(); marks.invalidate() })
        row1.addView(btn("⇅") {
            val lp = bar.layoutParams as FrameLayout.LayoutParams
            lp.gravity = if (lp.gravity and Gravity.BOTTOM == Gravity.BOTTOM) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bar.layoutParams = lp
        })
        val row2 = LinearLayout(ctx).apply { gravity = Gravity.CENTER_HORIZONTAL }
        row2.addView(btn(ctx.getString(R.string.kbtn_cancel)) { close(false) })
        row2.addView(btn(ctx.getString(R.string.kbtn_ed_save)) { close(true) })
        bar.addView(row1); bar.addView(row2)
        fr.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (28 * dp).toInt() })
        val lp = WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        if (runCatching { wm.addView(fr, lp) }.isSuccess) { root = fr; row2.getChildAt(1).requestFocus() } else { ScreenTouch.editing = false; onClose() }
    }

    fun dismiss() { if (root != null) close(false) }

    private fun close(save: Boolean) {
        if (save) {
            ScreenTouch.save(base, pkg, size.x, size.y, set)
            android.widget.Toast.makeText(base, ctx.getString(R.string.kbtn_ed_saved), android.widget.Toast.LENGTH_SHORT).show()
        }
        root?.let { runCatching { wm.removeView(it) } }; root = null
        ScreenTouch.editing = false
        onClose()
    }

    private inner class MarkView : View(ctx) {
        private val dp = resources.displayMetrics.density
        private val mr = 18 * dp
        private val dim = Paint().apply { color = android.graphics.Color.argb(80, 0, 0, 0) }
        private val fillOk = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(220, 15, 118, 110) }
        private val fillBad = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(230, 200, 40, 40) }
        private val fillTray = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(150, 90, 90, 90) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * dp; color = android.graphics.Color.WHITE }
        private val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * dp; color = android.graphics.Color.rgb(250, 204, 21) }
        private val area = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = android.graphics.Color.argb(200, 94, 234, 212); pathEffect = android.graphics.DashPathEffect(floatArrayOf(10 * dp, 6 * dp), 0f) }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true; textSize = 13 * dp }
        private val loc = IntArray(2)
        private var drag: String? = null
        private var selected: String? = null
        private var downX = 0f; private var downY = 0f; private var moved = false
        private var bad: Set<String> = emptySet()
        private val longPress = Runnable { val d = drag; if (d != null && !moved) { set.remove(d); drag = null; invalidate() } }

        private fun caption(id: String) = when (id) { StIds.START -> "II"; StIds.DU -> "↑"; StIds.DR -> "→"; StIds.DD -> "↓"; StIds.DL -> "←"; else -> id }

        /** Точка метки в координатах окна: поставленная или место в лотке. */
        private fun pos(id: String): Pair<Float, Float> {
            getLocationOnScreen(loc)
            set[id]?.let { return (it.x - loc[0]) to (it.y - loc[1]) }
            val tray = StIds.all.filter { it !in set }
            val i = tray.indexOf(id).coerceAtLeast(0)
            val cols = ((width * 0.8f) / (mr * 2.6f)).toInt().coerceIn(3, 8)
            val step = mr * 2.6f
            val x0 = width / 2f - (minOf(cols, tray.size) - 1) * step / 2
            return (x0 + (i % cols) * step) to (height * 0.55f + (i / cols) * step)
        }

        fun radius(dir: Int) {
            val id = selected?.takeIf { it in StIds.sticks } ?: set.keys.firstOrNull { it in StIds.sticks } ?: return
            val t = set[id] ?: return
            val m = minOf(size.x, size.y).toFloat()
            set[id] = t.copy(r = (t.r + dir * m * 0.01f).coerceIn(m * 0.03f, m * 0.3f)); selected = id; invalidate()
        }

        override fun onDraw(c: Canvas) {
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            bad = StProfile.covered(set, cover())
            for (id in StIds.all) {
                val (x, y) = pos(id); val placed = id in set
                if (placed && id in StIds.sticks) c.drawCircle(x, y, set.getValue(id).r, area)
                c.drawCircle(x, y, mr, when { !placed -> fillTray; id in bad -> fillBad; else -> fillOk })
                c.drawCircle(x, y, mr, if (id == selected) sel else ring)
                c.drawText(caption(id), x, y + txt.textSize * 0.35f, txt)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; moved = false
                    drag = StIds.all.map { it to pos(it) }.filter { (_, p) -> hypot(p.first - e.x, p.second - e.y) <= mr * 1.6f }
                        .minByOrNull { (_, p) -> hypot(p.first - e.x, p.second - e.y) }?.first
                    if (drag != null) { selected = drag; postDelayed(longPress, 600) }
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    val d = drag ?: return true
                    if (!moved && hypot(e.x - downX, e.y - downY) < 8 * dp) return true
                    moved = true; removeCallbacks(longPress)
                    getLocationOnScreen(loc)
                    val m = minOf(size.x, size.y).toFloat()
                    val sx = (e.rawX).coerceIn(0f, size.x - 1f); val sy = (e.rawY).coerceIn(0f, size.y - 1f)
                    set[d] = StTarget(sx, sy, set[d]?.r ?: if (d in StIds.sticks) m * 0.12f else 0f)
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); drag = null; invalidate() }
            }
            return true
        }
    }
}
