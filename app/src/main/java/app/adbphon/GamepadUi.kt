@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package app.adbphon

import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.foundation.layout.heightIn
import android.os.Build
import kotlin.math.asin
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Телефон как Bluetooth-геймпад: состояние кнопок и осей, настройки и отправка отчётов HID.
 * Отчёт уходит каждые 8 мс, только если что-то изменилось (очередь не накапливается: отправляется всегда последнее состояние).
 */
object Pad {
    var dead by mutableStateOf(0.12f)          // мёртвая зона стика 0..0.4
    var sony by mutableStateOf(false)          // раскладка: false Xbox (A,B,X,Y), true PlayStation
    var scale by mutableStateOf(1f)            // размер элементов
    var alpha by mutableStateOf(0.75f)         // прозрачность
    var haptic by mutableStateOf(true)         // локальный отклик нажатий (независимо от обратной вибрации)
    var tilt by mutableStateOf(false)          // наклон телефона заменяет выбранный стик
    var tiltTarget by mutableStateOf(1)        // 0 левый стик, 1 правый стик, 2 крестовина, 3 руль (ось X левого стика)
    var tiltAxes by mutableStateOf(0)          // 0 обе оси, 1 только X, 2 только Y
    var tiltAngle by mutableStateOf(30f)       // угол полного отклонения, градусы 10..60
    var tiltDead by mutableStateOf(0.12f)      // мёртвая зона наклона 0..0.3
    var tiltSmooth by mutableStateOf(0.4f)     // сглаживание 0..0.9
    var tiltInvX by mutableStateOf(false)
    var tiltInvY by mutableStateOf(false)
    var tiltHide by mutableStateOf(false)      // скрыть экранный стик, который заменён наклоном
    var rumble by mutableStateOf(true)         // обратная вибрация от игры
    var rumbleGain by mutableStateOf(1f)       // сила 0..2
    var rumbleMode by mutableStateOf(0)        // 0 Xbox-подобный, 1 PlayStation-подобный, 2 универсальный PID
    var floating by mutableStateOf(false)      // стик с плавающим центром (центр там, где коснулись)
    var cursor by mutableStateOf(false)        // режим «Курсор»: на ПК уходят отчёты мыши и клавиатуры, отчёт геймпада не шлётся
    var cspeed by mutableStateOf(1f)           // скорость курсора 0.3..3
    /** Раздел «Геймпад» открыт на весь экран. */
    var playing by mutableStateOf(false)
    /** Выход из раздела (ставит экран «Ещё»). */
    var exit: () -> Unit = {}

    // 0 лев X, 1 лев Y, 2 прав X, 3 прав Y; значения -1..1
    val axes = FloatArray(4)
    // наклон: сглаженные углы телефона (градусы) и запомненное нулевое положение
    @Volatile var angX = 0f
    @Volatile var angY = 0f
    @Volatile var calX = 0f
    @Volatile var calY = 0f
    var lt = false
    var rt = false
    var hat = 8
    var buttons = 0
    @Volatile var dirty = true

    const val A = 0; const val B = 1; const val X = 3; const val Y = 4
    const val L1 = 6; const val R1 = 7; const val L2 = 8; const val R2 = 9
    const val SELECT = 10; const val START = 11; const val HOME = 12; const val L3 = 13; const val R3 = 14; const val OK = 15

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("pad", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = sp(ctx)
        dead = p.getFloat("dead", 0.12f); sony = p.getBoolean("sony", false); scale = p.getFloat("scale", 1f); alpha = p.getFloat("alpha", 0.75f)
        haptic = p.getBoolean("haptic", true); tilt = p.getBoolean("tilt2", false); floating = p.getBoolean("floating", false)
        tiltTarget = p.getInt("t_target", 1); tiltAxes = p.getInt("t_axes", 0); tiltAngle = p.getFloat("t_angle", 30f); tiltDead = p.getFloat("t_dead2", 0.12f)
        tiltSmooth = p.getFloat("t_smooth", 0.4f); tiltInvX = p.getBoolean("t_invx", false); tiltInvY = p.getBoolean("t_invy", false); tiltHide = p.getBoolean("t_hide", false)
        rumble = p.getBoolean("rumble", true); rumbleGain = p.getFloat("r_gain", 1f); rumbleMode = p.getInt("r_mode", 0)
        cursor = p.getBoolean("cursor", false); cspeed = p.getFloat("cspeed", 1f)
    }

    fun save(ctx: Context) {
        sp(ctx).edit().putFloat("dead", dead).putBoolean("sony", sony).putFloat("scale", scale).putFloat("alpha", alpha)
            .putBoolean("haptic", haptic).putBoolean("tilt2", tilt).putBoolean("floating", floating)
            .putInt("t_target", tiltTarget).putInt("t_axes", tiltAxes).putFloat("t_angle", tiltAngle).putFloat("t_dead2", tiltDead).putFloat("t_smooth", tiltSmooth)
            .putBoolean("t_invx", tiltInvX).putBoolean("t_invy", tiltInvY).putBoolean("t_hide", tiltHide)
            .putBoolean("rumble", rumble).putFloat("r_gain", rumbleGain).putInt("r_mode", rumbleMode)
            .putBoolean("cursor", cursor).putFloat("cspeed", cspeed).apply()
    }

    /** Нулевое положение: текущий наклон телефона становится центром. */
    fun calibrate(ctx: Context) { calX = angX; calY = angY; calSet = true }
    /** Нуль уже определён (в течение первых 0,3 с после включения наклона наклон ничего не шлёт). */
    @Volatile var calSet = false
    /** Окно игры показывается в повёрнутом виде: система держит портрет (сторонняя программа), содержимое поворачивается само. */
    var forced by mutableStateOf(false)
    /** Сторона альбома: false = обычный (LANDSCAPE), true = перевёрнутый на 180° (REVERSE_LANDSCAPE). Запоминается между запусками. */
    var flip by mutableStateOf(false)
    val forcedSign: Int get() = if (flip) -1 else 1
    fun loadFlip(ctx: Context, entryReverse: Boolean) {
        val v = sp(ctx).getInt("flip", -1)
        flip = if (v >= 0) v == 1 else entryReverse
    }
    fun toggleFlip(ctx: Context) { flip = !flip; sp(ctx).edit().putInt("flip", if (flip) 1 else 0).apply() }

    /** Профили раскладки: три ячейки с сохранёнными настройками. */
    fun saveProfile(ctx: Context, n: Int) {
        fun b(v: Boolean) = if (v) 1f else 0f
        sp(ctx).edit().putString("prof$n", listOf(dead, b(sony), scale, alpha, b(haptic), b(tilt), b(floating), tiltTarget.toFloat(), tiltAxes.toFloat(), tiltAngle, tiltDead,
            tiltSmooth, b(tiltInvX), b(tiltInvY), b(tiltHide), b(rumble), rumbleGain, rumbleMode.toFloat()).joinToString(",")).apply()
    }

    fun hasProfile(ctx: Context, n: Int) = sp(ctx).getString("prof$n", null) != null

    fun loadProfile(ctx: Context, n: Int) {
        val v = sp(ctx).getString("prof$n", null)?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: return
        if (v.size < 7) return
        dead = v[0]; sony = v[1] > 0.5f; scale = v[2]; alpha = v[3]; haptic = v[4] > 0.5f; tilt = v[5] > 0.5f; floating = v[6] > 0.5f
        if (v.size >= 18) {
            tiltTarget = v[7].toInt(); tiltAxes = v[8].toInt(); tiltAngle = v[9]; tiltDead = v[10]; tiltSmooth = v[11]
            tiltInvX = v[12] > 0.5f; tiltInvY = v[13] > 0.5f; tiltHide = v[14] > 0.5f; rumble = v[15] > 0.5f; rumbleGain = v[16]; rumbleMode = v[17].toInt()
        }
        save(ctx)
        BtHid.restartPad(ctx)
    }

    fun setBtn(bit: Int, on: Boolean) {
        val m = 1 shl bit
        val n = if (on) buttons or m else buttons and m.inv()
        if (n != buttons) { buttons = n; dirty = true }
    }

    fun setAxis(i: Int, v: Float) { if (axes[i] != v) { axes[i] = v; dirty = true } }

    private fun stickByte(v: Float): Int = (v * 127f + 128f).toInt().coerceIn(0, 255)

    /** Мёртвая зона: внутри нуль, дальше растёт от нуля до единицы. */
    private fun dz(x: Float, y: Float, d: Float = dead): Pair<Float, Float> {
        val m = hypot(x, y)
        if (m <= d || m == 0f) return 0f to 0f
        val k = ((m - d) / (1f - d)).coerceIn(0f, 1f) / m
        return x * k to y * k
    }

    /** Наклон как вектор -1..1 с учётом нуля, угла, осей, инверсии и мёртвой зоны. */
    fun tiltVec(): Pair<Float, Float> {
        if (!tilt || !fg || !calSet) return 0f to 0f
        var x = (angX - calX) / tiltAngle
        var y = (angY - calY) / tiltAngle
        if (tiltInvX) x = -x
        if (tiltInvY) y = -y
        if (tiltAxes == 1 || tiltTarget == 3) y = 0f
        if (tiltAxes == 2) x = 0f
        x = x.coerceIn(-1f, 1f); y = y.coerceIn(-1f, 1f)
        return dz(x, y, tiltDead)
    }

    /** Какой экранный стик (0 левый, 1 правый) сейчас заменён наклоном; -1 если никакой. */
    fun tiltStick(): Int = if (!tilt) -1 else when (tiltTarget) { 0, 3 -> 0; 1 -> 1; else -> -1 }

    /**
     * Номера кнопок HID для Windows (кнопка 1 = бит 0). Xbox: 1 A, 2 B, 3 X, 4 Y, 5 LB, 6 RB, 7 Back, 8 Start, 9 L3, 10 R3, 11 Guide,
     * 12 LT, 13 RT (цифровые копии курков). PlayStation: 1 крест, 2 круг, 3 квадрат, 4 треугольник, 5 L1, 6 R1, 7 L2, 8 R2,
     * 9 Share, 10 Options, 11 L3, 12 R3, 13 PS.
     */
    private fun hidButtons(): Int {
        var out = 0
        val m = if (sony) intArrayOf(A, 0, B, 1, X, 2, Y, 3, L1, 4, R1, 5, L2, 6, R2, 7, SELECT, 8, START, 9, L3, 10, R3, 11, HOME, 12, OK, 13)
                else intArrayOf(A, 0, B, 1, X, 2, Y, 3, L1, 4, R1, 5, SELECT, 6, START, 7, L3, 8, R3, 9, HOME, 10, L2, 11, R2, 12, OK, 13)
        var i = 0
        while (i < m.size) { if (buttons and (1 shl m[i]) != 0) out = out or (1 shl m[i + 1]); i += 2 }
        return out
    }

    fun report(): ByteArray {
        var lx = axes[0]; var ly = axes[1]; var rx = axes[2]; var ry = axes[3]
        var hatV = hat
        if (tilt) {
            val (tx, ty) = tiltVec()
            when (tiltTarget) {
                0, 3 -> if (hypot(lx, ly) < 0.02f) { lx = tx; ly = ty }
                1 -> if (hypot(rx, ry) < 0.02f) { rx = tx; ry = ty }
                2 -> if (hat == 8 && hypot(tx, ty) > 0.5f) {
                    val ang = Math.toDegrees(atan2(tx.toDouble(), -ty.toDouble())).let { if (it < 0) it + 360.0 else it }
                    hatV = (((ang + 22.5) / 45.0).toInt()) % 8
                }
            }
        }
        // значения после наклона уже с мёртвой зоной наклона; ручные стики проходят свою мёртвую зону
        val l = if (tilt && (tiltTarget == 0 || tiltTarget == 3) && hypot(axes[0], axes[1]) < 0.02f) (lx to ly) else dz(lx, ly)
        val r = if (tilt && tiltTarget == 1 && hypot(axes[2], axes[3]) < 0.02f) (rx to ry) else dz(rx, ry)
        val b = hidButtons()
        val out = ByteArray(9)
        out[0] = stickByte(l.first).toByte(); out[1] = stickByte(l.second).toByte()
        out[2] = stickByte(r.first).toByte(); out[3] = stickByte(r.second).toByte()
        out[4] = (if (lt) 255 else 0).toByte()     // тормоз (L2)
        out[5] = (if (rt) 255 else 0).toByte()     // газ (R2)
        out[6] = (hatV and 0x0F).toByte()
        out[7] = (b and 0xFF).toByte(); out[8] = ((b shr 8) and 0xFF).toByte()
        return out
    }

    /** Нейтральный отчёт: оси по центру, курки и кнопки отпущены, крестовина свободна. */
    fun neutral(): ByteArray = byteArrayOf(128.toByte(), 128.toByte(), 128.toByte(), 128.toByte(), 0, 0, 8, 0, 0)

    /** Раздел на переднем плане: наклон и отчёты только при этом. */
    @Volatile var fg = true

    /** Куда уходит отчёт: виртуальный контроллер этого телефона (прототип) или ПК по Bluetooth. */
    fun emit(r: ByteArray) { if (VirtualPad.on) VirtualPad.send(r, buttons) else BtHid.sendPad(r) }

    fun releaseAll() {
        axes.fill(0f); lt = false; rt = false; hat = 8; buttons = 0; dirty = false
        if (VirtualPad.on) VirtualPad.send(neutral(), 0)
        BtHid.releaseAll()
    }

    /**
     * Режим «Курсор» (125 Гц): левый стик двигает курсор с ускорением (малый наклон медленно, большой быстро), правый стик крутит колесо
     * (вертикаль) или прокручивает по горизонтали (Shift+колесо), A/B/X = левая/правая/средняя кнопка (удержание = перетаскивание),
     * крестовина = стрелки (повтор делает Windows при удержании), OK = Enter, Y = Esc, Start = Win, Back = Alt+Tab,
     * L1/R1 = Alt+Влево/Alt+Вправо, L2 удерживать = медленнее, R2 удерживать = быстрее. Отчёт геймпада в этом режиме не шлётся.
     */
    private class CursorEngine {
        var ax = 0f; var ay = 0f; var wy = 0f; var wx = 0f
        var lastBtn = 0
        var lastKb = ByteArray(8)
        var prevBtns = 0
        var pulseMod = 0; var pulseKey = 0; var pulseTicks = 0
        var shiftTicks = 0

        fun reset() { ax = 0f; ay = 0f; wy = 0f; wx = 0f; lastBtn = 0; lastKb = ByteArray(8); prevBtns = 0; pulseTicks = 0; shiftTicks = 0 }

        fun tick() {
            val l = dz(axes[0], axes[1]); val r = dz(axes[2], axes[3])
            val m = hypot(l.first, l.second)
            val mul = cspeed * (if (lt) 0.3f else 1f) * (if (rt) 2.5f else 1f)
            var ix = 0; var iy = 0
            if (m > 0f) {
                val f = (0.15f * m + 0.85f * m * m) * 20f * mul       // пикселей за тик 8 мс: плавный разгон
                ax += l.first / m * f; ay += l.second / m * f
                ix = ax.toInt(); iy = ay.toInt(); ax -= ix; ay -= iy
            } else { ax = 0f; ay = 0f }
            // прокрутка: побеждает ось с большим отклонением
            var iw = 0; var horizontal = false
            if (abs(r.first) > abs(r.second)) { wx += r.first * 0.10f; wy = 0f; val n = wx.toInt(); wx -= n; if (n != 0) { iw = -n; horizontal = true } }
            else { wy += -r.second * 0.10f; wx = 0f; val n = wy.toInt(); wy -= n; iw = n }
            if (horizontal) shiftTicks = 24
            // клавиатура
            val b = buttons
            val rising = b and prevBtns.inv()
            prevBtns = b
            if (rising and (1 shl SELECT) != 0) { pulseMod = 4; pulseKey = 0x2B; pulseTicks = 6 }       // Alt+Tab
            if (rising and (1 shl L1) != 0) { pulseMod = 4; pulseKey = 0x50; pulseTicks = 6 }          // Alt+Влево
            if (rising and (1 shl R1) != 0) { pulseMod = 4; pulseKey = 0x4F; pulseTicks = 6 }          // Alt+Вправо
            var mod = 0
            val keys = ArrayList<Int>(6)
            val h = hat
            if (h == 7 || h == 0 || h == 1) keys.add(0x52)
            if (h in 3..5) keys.add(0x51)
            if (h in 5..7) keys.add(0x50)
            if (h in 1..3) keys.add(0x4F)
            if (b and (1 shl OK) != 0) keys.add(0x28)
            if (b and (1 shl Y) != 0) keys.add(0x29)
            if (b and (1 shl START) != 0) mod = mod or 8
            if (pulseTicks > 0) { mod = mod or pulseMod; keys.add(pulseKey); pulseTicks-- }
            if (shiftTicks > 0) { mod = mod or 2; shiftTicks-- }
            val kb = ByteArray(8); kb[0] = mod.toByte()
            for (i in 0 until minOf(6, keys.size)) kb[2 + i] = keys[i].toByte()
            if (!kb.contentEquals(lastKb)) { BtHid.sendKeys(kb); lastKb = kb }
            // мышь (после клавиатуры: Shift уже нажат, когда идёт горизонтальная прокрутка)
            val btn = (if (b and (1 shl A) != 0) 1 else 0) or (if (b and (1 shl B) != 0) 2 else 0) or (if (b and (1 shl X) != 0) 4 else 0)
            if (btn != lastBtn || ix != 0 || iy != 0 || iw != 0) { BtHid.sendMouse(btn, ix, iy, iw); lastBtn = btn }
        }
    }

    /** Цикл отправки: каждые 8 мс, только при изменении; остановка снимает все нажатия. */
    suspend fun loop() {
        var last: ByteArray? = null
        val cur = CursorEngine()
        var wasCursor = cursor
        try {
            while (true) {
                if (cursor != wasCursor) {
                    // переключение режима: «всё отпущено» для старого режима, чтобы ничего не залипло
                    if (BtHid.hostName != null || BtHid.dryRun || VirtualPad.on) { if (wasCursor) BtHid.releaseAll() else emit(neutral()) }
                    cur.reset(); last = null; wasCursor = cursor
                }
                if (fg && (BtHid.hostName != null || BtHid.dryRun || VirtualPad.on)) {
                    if (cursor) cur.tick()
                    else {
                        // отчёт уходит только при реальном изменении ввода; в покое ничего не отправляется
                        val r = report()
                        if (last == null && r.contentEquals(neutral())) last = r
                        if (last == null || !r.contentEquals(last)) { emit(r); last = r }
                    }
                } else { last = null; cur.reset() }
                dirty = false
                delay(8)
            }
        } finally { releaseAll() }
    }
}

// ------------------------------------------------------------------ геометрия

internal class Geo(val w: Float, val h: Float, val mirror: Boolean, val il: Float = 0f, val ir: Float = 0f) {
    val span = (w - il - ir).coerceAtLeast(1f)
    private fun x(v: Float) = if (mirror) il + span * (1f - v) else il + span * v
    private fun xn(v: Float) = il + span * v          // без зеркала (кнопки окна стоят всегда на своих местах)
    /** Базовая единица размеров (см. PadLayout): окно уже 2,05:1 уменьшает элементы. Плотность экрана и масштаб шрифта не влияют. */
    internal val S = PadLayout.LAND
    val u = minOf(h, w / PadLayout.ASPECT)
    private fun pt(p: PadLayout.Pt) = Offset(x(p.x), h * p.y)
    val sr = u * S.stickR * Pad.scale        // радиус основания стика
    val br = u * S.abxyR * Pad.scale         // радиус кнопки ABXY
    val lStick = pt(S.lStick)
    val rStick = pt(S.rStick)
    val dpad = pt(S.dpad)
    val abxy = pt(S.abxy)
    val dr = u * S.dpadR * Pad.scale
    val okR get() = dr * 0.30f           // радиус центральной кнопки OK
    val dGap get() = dr * 0.14f          // зазор между OK и плечами
    val abxyD = u * S.abxyDist * Pad.scale
    val bA get() = Offset(abxy.x, abxy.y + abxyD)
    val bB get() = Offset(abxy.x + abxyD, abxy.y)
    val bX get() = Offset(abxy.x - abxyD, abxy.y)
    val bY get() = Offset(abxy.x, abxy.y - abxyD)
    val small = u * S.smallR * Pad.scale
    val select = pt(S.select)
    val home = pt(S.home)
    val start = pt(S.start)
    val l3 = pt(S.l3)
    val r3 = pt(S.r3)
    val shW = minOf(w * S.shCapFrac, u * S.shoulderW)
    val shH = u * S.shoulderH * Pad.scale.coerceAtLeast(0.8f)
    val l2r get() = rectAt(S.l2Left, h * S.shTop2)
    val l1r get() = rectAt(S.l1Left, h * S.shTop1)
    val r1r get() = rectAt(S.r1Left, h * S.shTop1)
    val r2r get() = rectAt(S.r2Left, h * S.shTop2)
    private fun rectAt(left: Float, top: Float): androidx.compose.ui.geometry.Rect {
        val xl = if (mirror) il + span - left * span - shW else il + left * span
        return androidx.compose.ui.geometry.Rect(xl, top, xl + shW, top + shH)
    }
    // кнопки окна: положение и размер из таблицы
    private fun box(b: PadLayout.Box) = androidx.compose.ui.geometry.Rect(xn(b.cx) - b.w * u / 2, h * b.cy - b.h * u / 2, xn(b.cx) + b.w * u / 2, h * b.cy + b.h * u / 2)
    val zClose get() = box(S.close)
    val zSett get() = box(S.settings)
    val zFlip get() = box(S.flip)
    val zMode get() = box(S.mode)
    val zLay get() = box(S.layout)
    val zDev get() = box(S.device)
    /** «dp» окна: единица размеров значков и подписей (на A14 это ровно плотность экрана). */
    val unitDp get() = u / 411f
}

private sealed class Ctl {
    class Stick(val right: Boolean, var center: Offset) : Ctl() { var t0 = 0L; var maxd = 0f }
    object Dpad : Ctl()
    class Btn(val bit: Int) : Ctl()
    class Trig(val left: Boolean) : Ctl()
}

private fun near(p: Offset, c: Offset, r: Float) = hypot(p.x - c.x, p.y - c.y) <= r

/** Какой элемент под пальцем в точке [p]. */
private fun hit(g: Geo, p: Offset): Ctl? {
    val sz = g.small * 1.5f
    return when {
        g.l2r.contains(p) -> Ctl.Trig(true)
        g.r2r.contains(p) -> Ctl.Trig(false)
        g.l1r.contains(p) -> Ctl.Btn(Pad.L1)
        g.r1r.contains(p) -> Ctl.Btn(Pad.R1)
        near(p, g.bA, g.br * 1.25f) -> Ctl.Btn(Pad.A)
        near(p, g.bB, g.br * 1.25f) -> Ctl.Btn(Pad.B)
        near(p, g.bX, g.br * 1.25f) -> Ctl.Btn(Pad.X)
        near(p, g.bY, g.br * 1.25f) -> Ctl.Btn(Pad.Y)
        near(p, g.select, sz) -> Ctl.Btn(Pad.SELECT)
        near(p, g.start, sz) -> Ctl.Btn(Pad.START)
        near(p, g.home, sz) -> Ctl.Btn(Pad.HOME)
        near(p, g.l3, sz) -> Ctl.Btn(Pad.L3)
        near(p, g.r3, sz) -> Ctl.Btn(Pad.R3)
        near(p, g.dpad, g.okR * 1.08f) -> Ctl.Btn(Pad.OK)
        near(p, g.dpad, g.okR + g.dGap * 0.6f) -> null   // зазор между OK и плечами: ничего
        near(p, g.dpad, g.dr * 1.25f) -> Ctl.Dpad
        near(p, g.lStick, g.sr * 1.3f) -> if (Pad.tiltHide && Pad.tiltStick() == 0) null else Ctl.Stick(false, g.lStick)
        near(p, g.rStick, g.sr * 1.3f) -> if (Pad.tiltHide && Pad.tiltStick() == 1) null else Ctl.Stick(true, g.rStick)
        Pad.floating && p.x < g.w * (if (g.mirror) 0.5f else 0.5f) -> if (p.y > g.h * 0.3f) Ctl.Stick(g.mirror, p) else null
        Pad.floating && p.y > g.h * 0.3f -> Ctl.Stick(!g.mirror, p)
        else -> null
    }
}

private fun hatFrom(g: Geo, p: Offset): Int {
    val dx = p.x - g.dpad.x; val dy = p.y - g.dpad.y
    if (hypot(dx, dy) < g.okR + g.dGap * 0.6f) return 8
    // 0 вверх, дальше по часовой стрелке через 45°
    val ang = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).let { if (it < 0) it + 360.0 else it }
    return (((ang + 22.5) / 45.0).toInt()) % 8
}

private fun Ctl.apply(ctx: Context, g: Geo, p: Offset) {
    when (this) {
        is Ctl.Stick -> {
            val dx = (p.x - center.x) / g.sr; val dy = (p.y - center.y) / g.sr
            val m = hypot(dx, dy)
            if (m > maxd) maxd = m
            val k = if (m > 1f) 1f / m else 1f
            if (right) { Pad.setAxis(2, dx * k); Pad.setAxis(3, dy * k) } else { Pad.setAxis(0, dx * k); Pad.setAxis(1, dy * k) }
        }
        // отклик при каждой смене направления (в том числе диагонали), не при отпускании
        Ctl.Dpad -> { val h = hatFrom(g, p); if (h != Pad.hat) { if (h != 8) tick(ctx); Pad.hat = h; Pad.dirty = true } }
        else -> {}
    }
}

private fun Ctl.press(ctx: Context, g: Geo, p: Offset) {
    when (this) {
        is Ctl.Btn -> { Pad.setBtn(bit, true); tick(ctx) }
        is Ctl.Trig -> { if (left) { Pad.lt = true; Pad.setBtn(Pad.L2, true) } else { Pad.rt = true; Pad.setBtn(Pad.R2, true) }; Pad.dirty = true; tick(ctx) }
        Ctl.Dpad -> apply(ctx, g, p)
        is Ctl.Stick -> { t0 = System.currentTimeMillis(); maxd = 0f; apply(ctx, g, p) }
    }
}

private fun Ctl.release(ctx: Context) {
    when (this) {
        is Ctl.Btn -> Pad.setBtn(bit, false)
        is Ctl.Trig -> { if (left) { Pad.lt = false; Pad.setBtn(Pad.L2, false) } else { Pad.rt = false; Pad.setBtn(Pad.R2, false) }; Pad.dirty = true }
        Ctl.Dpad -> { Pad.hat = 8; Pad.dirty = true }
        is Ctl.Stick -> {
            if (right) { Pad.setAxis(2, 0f); Pad.setAxis(3, 0f) } else { Pad.setAxis(0, 0f); Pad.setAxis(1, 0f) }
            // короткое нажатие по стику без движения = нажатие стика (L3 слева, R3 справа)
            if (maxd < 0.30f && System.currentTimeMillis() - t0 < 400) {
                val bit = if (right) Pad.R3 else Pad.L3
                Pad.setBtn(bit, true); tick(ctx)      // тап по центру стика = L3/R3: отклик
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ Pad.setBtn(bit, false) }, 110)
            }
        }
    }
}

private fun tick(ctx: Context) { if (Pad.haptic) Feedback.tick(ctx) }

// ------------------------------------------------------------------ экран геймпада на весь экран

/** Активность, даже если контекст обёрнут (язык приложения). */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * Раздел «Геймпад» на весь экран: без шапки и нижней панели приложения, без системных панелей (режим «погружение» со свайпом),
 * поверх выреза экрана. Малые прозрачные кнопки сверху: выход и настройки.
 */
@Composable
fun PadOverlay(onExit: () -> Unit, onLetterboxed: () -> Unit = {}) {
    val ctx = LocalContext.current
    var settings by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Pad.load(ctx) }
    LaunchedEffect(Unit) { Pad.loop() }
    androidx.activity.compose.BackHandler { if (settings) settings = false else onExit() }
    // системные панели скрыты, вырез занят, экран не гаснет
    DisposableEffect(Unit) {
        val act = ctx.findActivity()
        val w = act?.window
        val ctrl = w?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        val oldCut = if (w != null && Build.VERSION.SDK_INT >= 28) w.attributes.layoutInDisplayCutoutMode else 0
        if (w != null) {
            if (Build.VERSION.SDK_INT >= 30) w.attributes = w.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
            else if (Build.VERSION.SDK_INT >= 28) w.attributes = w.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        ctrl?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose {
            ctrl?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            if (w != null) {
                if (Build.VERSION.SDK_INT >= 28) w.attributes = w.attributes.apply { layoutInDisplayCutoutMode = oldCut }
                val keep = ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getBoolean("keep_on", false)
                if (!keep) w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
    // наклон телефона: пока выключен, датчик не слушается; при каждом включении нулём становится среднее положение за первые 0,3 с
    DisposableEffect(Pad.tilt) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val rot = {
            if (Pad.forced) (if (Pad.forcedSign > 0) Surface.ROTATION_90 else Surface.ROTATION_270)
            else runCatching { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation }.getOrDefault(Surface.ROTATION_90)
        }
        var t0 = 0L; var n = 0; var sumX = 0f; var sumY = 0f
        Pad.calSet = false
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val ax = e.values[0]; val ay = e.values[1]
                val (sx, sy) = when (rot()) { Surface.ROTATION_90 -> ay to -ax; Surface.ROTATION_270 -> -ay to ax; Surface.ROTATION_180 -> -ax to -ay; else -> ax to ay }
                val g = 9.81f
                val rx = Math.toDegrees(asin((-sx / g).coerceIn(-1f, 1f)).toDouble()).toFloat()
                val ry = Math.toDegrees(asin((sy / g).coerceIn(-1f, 1f)).toDouble()).toFloat()
                val now = System.currentTimeMillis()
                if (!Pad.calSet) {
                    if (t0 == 0L) t0 = now
                    sumX += rx; sumY += ry; n++
                    Pad.angX = rx; Pad.angY = ry
                    if (now - t0 >= 300 && n > 0) { Pad.calX = sumX / n; Pad.calY = sumY / n; Pad.calSet = true }
                    return
                }
                val k = 1f - Pad.tiltSmooth.coerceIn(0f, 0.9f)
                Pad.angX += (rx - Pad.angX) * k
                Pad.angY += (ry - Pad.angY) * k
                Pad.dirty = true
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (Pad.tilt && acc != null) sm.registerListener(l, acc, SensorManager.SENSOR_DELAY_GAME)
        onDispose { runCatching { sm.unregisterListener(l) }; Pad.calSet = false }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF14181C))) {
      val portrait = maxHeight > maxWidth
      SideEffect { Pad.forced = portrait }
      // окно оказалось узкой альбомной полосой на портретном экране (система игнорирует запрос ориентации): сообщить окну, чтобы оно сняло запрос
      val actForGuard = ctx.findActivity()
      val dnsGuard = androidx.compose.ui.platform.LocalDensity.current
      SideEffect { if (actForGuard != null && OrientGuard.letterboxed(actForGuard, with(dnsGuard) { maxWidth.toPx() }, with(dnsGuard) { maxHeight.toPx() })) onLetterboxed() }
      // при принудительном портрете (сторонняя программа поворота) содержимое поворачивается вручную: рисование и касания считаются одной формулой
      val rot = if (!portrait) 0 else Pad.forcedSign
      // геймпад не зависит от настройки «Панели управления» (Слева/Справа): всегда стандартный вид как у настоящего контроллера (левый стик и крестовина слева, правый стик и A/B/X/Y справа)
      PadSurface(false, rot, onExit = onExit, onSettings = { settings = true }, onDevices = { devices = true })
      if (devices) PadDevices { devices = false }
      if (settings) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)).clickable { settings = false }, contentAlignment = Alignment.Center) {
          androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
              modifier = Modifier.fillMaxWidth(0.88f).fillMaxHeight(0.94f)) {
              Column(Modifier.padding(16.dp)) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                      Text(stringResource(R.string.pad_settings), Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                      TextButton({ settings = false }) { Text(stringResource(R.string.fm_close)) }
                  }
                  if (portrait) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) { PadSettings(); PadProfilesAndHost(true) { settings = false } }
                  else Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                      Column(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) { PadSettings() }
                      Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) { PadProfilesAndHost(true) { settings = false } }
                  }
              }
          }
      }
    }
}

/**
 * Поверхность игры: стики, кнопки и три малые кнопки окна (выход, настройки, «Перевернуть» в нижнем левом углу).
 * [rot]: 0 обычный экран, 1 или -1 экран портретный, а содержимое повёрнуто на 90° по или против часовой стрелки.
 */
@Composable
private fun PadSurface(mirror: Boolean, rot: Int, onExit: () -> Unit, onSettings: () -> Unit, onDevices: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val sysDensity = androidx.compose.ui.platform.LocalDensity.current.density
    val pressed = remember { mutableStateOf(0L) }          // для перерисовки
    val prim = MaterialTheme.colorScheme.primary
    val icClose = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Filled.Close)
    val icSet = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Filled.Settings)
    val icRot = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Filled.ScreenRotation)
    val icMouse = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Filled.Mouse)
    val icVib = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Filled.Vibration)
    val icGame = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Filled.SportsEsports)
    val modeGame = stringResource(R.string.pad_mode_game); val modeCursor = stringResource(R.string.pad_mode_cursor)
    val devLabel = stringResource(R.string.pad_device)
    val hostNow = BtHid.hostName
    val caption = if (BtHid.switching) (BtAuto.status ?: stringResource(R.string.pad_switching)) else if (hostNow != null) stringResource(R.string.bt_connected, hostNow) else stringResource(R.string.pad_no_host)
    val flipText = stringResource(R.string.pad_flip)
    val dryNote = stringResource(R.string.pad_dry_note)
    // стик показывает наклон: перерисовка по таймеру
    LaunchedEffect(Pad.tilt) { while (Pad.tilt) { pressed.value = System.nanoTime(); delay(33) } }
    // индикатор вибрации от игры: вспыхивает на ~0,6 с после каждого ненулевого отчёта
    LaunchedEffect(PadRumble.count) { pressed.value = System.nanoTime(); delay(650); pressed.value = System.nanoTime() }
    // безопасные поля по бокам: вырез экрана (только в настоящем альбоме)
    var il = 0f; var ir = 0f
    // вырез экрана не учитывается: раскладка в долях ВСЕГО окна одинакова на любом устройстве, а по вертикальной середине боковых краёв элементов нет
    // зоны малых кнопок в координатах содержимого (альбом): из таблицы PadLayout
    fun zones(cw: Float, ch: Float): List<androidx.compose.ui.geometry.Rect> {
        val g = Geo(cw, ch, false, il, ir)
        return listOf(g.zClose, g.zSett, g.zFlip, g.zLay, g.zMode, g.zDev)
    }
    Canvas(
        Modifier.fillMaxSize()
            .pointerInput(mirror, rot, il, ir, sysDensity) {
                val owners = HashMap<PointerId, Ctl>()
                // кнопки окна срабатывают по тапу без движения
                val taps = HashMap<PointerId, Triple<Int, Offset, Boolean>>()
                val W = size.width.toFloat(); val H = size.height.toFloat()
                val cw = if (rot == 0) W else H; val chh = if (rot == 0) H else W
                val density = Geo(cw, chh, mirror, il, ir).unitDp
                fun map(p: Offset): Offset = when (rot) { 1 -> Offset(p.y, W - p.x); -1 -> Offset(H - p.y, p.x); else -> p }
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val g = Geo(cw, chh, mirror, il, ir)
                        val zn = zones(cw, chh)
                        val zc = zn[0]; val zs = zn[1]; val zf = zn[2]; val zl = zn[3]; val zm = zn[4]; val zd = zn[5]
                        for (ch in ev.changes) {
                            val id = ch.id
                            val p = map(ch.position)
                            if (ch.pressed && !ch.previousPressed) {
                                val z = when { zc.contains(p) -> 1; zs.contains(p) -> 2; zf.contains(p) -> 3; zl.contains(p) -> 4; zm.contains(p) -> 5; zd.contains(p) -> 6; else -> 0 }
                                if (z != 0) { taps[id] = Triple(z, p, true); tick(ctx) }      // отклик при касании кнопки окна
                                else hit(g, p)?.let { c -> owners[id] = c; c.press(ctx, g, p) }
                            } else if (ch.pressed) {
                                val t = taps[id]
                                if (t != null) { if (hypot(p.x - t.second.x, p.y - t.second.y) > 18f * density) taps[id] = t.copy(third = false) }
                                else owners[id]?.apply(ctx, g, p)
                            } else {
                                val t = taps.remove(id)
                                if (t != null) {
                                    val still = t.third && when (t.first) { 1 -> zc; 2 -> zs; 3 -> zf; 5 -> zm; 6 -> zd; else -> zl }.contains(p)
                                    if (still) when (t.first) { 1 -> onExit(); 2 -> onSettings(); 3 -> Pad.toggleFlip(ctx); 5 -> { Pad.cursor = !Pad.cursor; Pad.save(ctx) }; 6 -> onDevices(); else -> { Pad.sony = !Pad.sony; Pad.save(ctx) } }
                                } else if (owners.containsKey(id)) owners.remove(id)?.release(ctx)
                            }
                            ch.consume()
                        }
                        pressed.value = System.nanoTime()
                    }
                }
            },
    ) {
        pressed.value   // подписка на перерисовку
        val W = size.width; val H = size.height
        val cw = if (rot == 0) W else H; val chh = if (rot == 0) H else W
        val g = Geo(cw, chh, mirror, il, ir)
        val density = g.unitDp
        val zn = zones(cw, chh)
        val zc = zn[0]; val zs = zn[1]; val zf = zn[2]; val zl = zn[3]; val zm = zn[4]; val zd = zn[5]
        val body: DrawScope.() -> Unit = {
            drawPad(g, prim)
            val bg = Color.Black.copy(alpha = .45f); val fg = Color.White.copy(alpha = .9f)
            drawCircle(bg, zc.width / 2, zc.center); drawCircle(bg, zs.width / 2, zs.center)
            val ic = 22f * density
            translate(zc.center.x - ic / 2, zc.center.y - ic / 2) { with(icClose) { draw(Size(ic, ic), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(fg)) } }
            val icg = ic * 1.23f   // шестерёнка чуть крупнее остальных значков, строго по центру кнопки
            translate(zs.center.x - icg / 2, zs.center.y - icg / 2) { with(icSet) { draw(Size(icg, icg), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(fg)) } }
            drawRoundRect(bg, zf.topLeft, zf.size, androidx.compose.ui.geometry.CornerRadius(zf.height / 2))
            translate(zf.left + 10f * density, zf.center.y - ic / 2) { with(icRot) { draw(Size(ic, ic), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(fg)) } }
            paint.color = fg.toArgb(); paint.textSize = 12f * density; paint.textAlign = android.graphics.Paint.Align.LEFT
            drawContext.canvas.nativeCanvas.drawText(flipText, zf.left + 10f * density + ic + 6f * density, zf.center.y + 4.2f * density, paint)
            // переключатель режима «Игра» / «Курсор» (над «Перевернуть»)
            drawRoundRect(if (Pad.cursor) TEAL_D.copy(alpha = .85f) else bg, zm.topLeft, zm.size, androidx.compose.ui.geometry.CornerRadius(zm.height / 2))
            translate(zm.left + 10f * density, zm.center.y - ic / 2) { with(if (Pad.cursor) icMouse else icGame) { draw(Size(ic, ic), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(fg)) } }
            paint.color = fg.toArgb(); paint.textSize = 12f * density; paint.textAlign = android.graphics.Paint.Align.LEFT
            drawContext.canvas.nativeCanvas.drawText(if (Pad.cursor) modeCursor else modeGame, zm.left + 10f * density + ic + 6f * density, zm.center.y + 4.2f * density, paint)
            // переключатель раскладки Xbox / PlayStation (справа внизу)
            drawRoundRect(bg, zl.topLeft, zl.size, androidx.compose.ui.geometry.CornerRadius(zl.height / 2))
            paint.color = fg.toArgb(); paint.textSize = 13f * density; paint.textAlign = android.graphics.Paint.Align.CENTER
            drawContext.canvas.nativeCanvas.drawText(if (Pad.sony) "PS" else "Xbox", zl.center.x, zl.center.y + 4.6f * density, paint)
            // пробный режим без компьютера: строка в нижней середине окна, над подписью хоста; не ближе к ряду Back/Guide/Start, чем нижняя середина (между крестовиной/стиками и нижними кнопками)
            if (BtHid.dryRun) {
                paint.color = TEAL.copy(alpha = .9f).toArgb(); paint.textAlign = android.graphics.Paint.Align.CENTER
                paint.textSize = 11f * density
                val maxW = cw * 0.30f
                val note = android.text.TextUtils.ellipsize(dryNote, android.text.TextPaint(paint).also { if (it.measureText(dryNote) > maxW) it.textSize = it.textSize * 0.9f }, maxW, android.text.TextUtils.TruncateAt.END).toString()
                val wNote = paint.measureText(note)
                if (wNote > maxW) paint.textSize = paint.textSize * 0.9f
                drawContext.canvas.nativeCanvas.drawText(note, cw / 2, chh * g.S.dryY, paint)
            }
            // индикатор вибрации от игры (скрыт при выключенной вибрации и в режиме «Курсор»)
            if (Pad.rumble && !Pad.cursor) {
                val on = System.currentTimeMillis() - PadRumble.lastAt < 600
                val isz = g.u * PadLayout.RUMBLE_ICON
                translate(cw * g.S.rumbleIcon.x - isz / 2, chh * g.S.rumbleIcon.y - isz / 2) {
                    with(icVib) { draw(Size(isz, isz), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(if (on) TEAL else Color.White.copy(alpha = 0.28f))) }
                }
            }
            // кнопка «Устройство» и подпись с именем текущего хоста
            drawRoundRect(bg, zd.topLeft, zd.size, androidx.compose.ui.geometry.CornerRadius(zd.height / 2))
            paint.color = fg.toArgb(); paint.textSize = 12f * density; paint.textAlign = android.graphics.Paint.Align.CENTER
            drawContext.canvas.nativeCanvas.drawText(devLabel, zd.center.x, zd.center.y + 4.2f * density, paint)
            paint.color = Color.White.copy(alpha = 0.6f).toArgb(); paint.textSize = 11f * density
            drawContext.canvas.nativeCanvas.drawText(caption.take(30), cw / 2, chh * g.S.caption.y - 2f * density, paint)
            paint.textAlign = android.graphics.Paint.Align.CENTER
        }
        if (rot == 0) body() else withTransform({
            translate(W / 2, H / 2); rotate(rot * 90f, Offset.Zero); translate(-cw / 2, -chh / 2)
        }) { body() }
    }
}

// ------------------------------------------------------------------ рисование

private val cA = Color(0xFF22C55E); private val cB = Color(0xFFEF4444); private val cX = Color(0xFF3B82F6); private val cY = Color(0xFFFACC15)
private val psX = Color(0xFF60A5FA); private val psO = Color(0xFFF87171); private val psS = Color(0xFFF472B6); private val psT = Color(0xFF34D399)

private val TEAL = Color(0xFF5EEAD4)
private val TEAL_D = Color(0xFF0F766E)

internal fun DrawScope.drawPad(g: Geo, @Suppress("UNUSED_PARAMETER") prim: Color) {
    val a = Pad.alpha
    val acc = TEAL
    val ring = Color.White.copy(alpha = 0.30f * a + 0.10f)
    val rim = acc.copy(alpha = 0.55f * a + 0.15f)
    val bodyTop = Color(0xFF3A444C).copy(alpha = 0.55f * a + 0.35f)
    val bodyBot = Color(0xFF1F272D).copy(alpha = 0.55f * a + 0.35f)
    fun isOn(bit: Int) = Pad.buttons and (1 shl bit) != 0

    // ---------------- стики ----------------
    fun stick(c: Offset, right: Boolean) {
        val tiltHere = Pad.tilt && Pad.tiltStick() == (if (right) 1 else 0)
        if (tiltHere && Pad.tiltHide) return
        val vx = if (right) Pad.axes[2] else Pad.axes[0]; val vy = if (right) Pad.axes[3] else Pad.axes[1]
        val manual = hypot(vx, vy) > 0.02f
        val (tx, ty) = if (tiltHere && !manual) Pad.tiltVec() else (vx to vy)
        val on = hypot(tx, ty) > 0.001f
        val click = isOn(if (right) Pad.R3 else Pad.L3)
        // основание: кольцо с мягким градиентом и тенью
        drawCircle(Color.Black.copy(alpha = 0.35f), g.sr * 1.04f, Offset(c.x, c.y + g.sr * 0.05f))
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(bodyBot, bodyTop), center = c, radius = g.sr), g.sr, c)
        drawCircle(if (on) acc.copy(alpha = 0.85f) else ring, g.sr, c, style = Stroke(if (on) 4f else 2.5f))
        drawCircle(ring.copy(alpha = ring.alpha * 0.5f), g.sr * 0.62f, c, style = Stroke(1.5f))
        // шайба
        val k = Offset(c.x + tx * g.sr * 0.55f, c.y + ty * g.sr * 0.55f)
        val capR = g.sr * 0.46f
        drawCircle(Color.Black.copy(alpha = 0.4f), capR * 1.05f, Offset(k.x, k.y + capR * 0.1f))
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(
            if (on || click) listOf(acc, TEAL_D) else listOf(Color(0xFFBFC8CF), Color(0xFF6B7780)),
            center = Offset(k.x - capR * 0.3f, k.y - capR * 0.3f), radius = capR * 1.4f), capR, k)
        drawCircle(Color.White.copy(alpha = 0.25f), capR * 0.62f, k, style = Stroke(1.5f))
    }
    stick(g.lStick, false); stick(g.rStick, true)

    // ---------------- крестовина: четыре отдельных плеча вокруг круглой кнопки OK ----------------
    val d = g.dr
    val side = d * 0.54f                // сторона каждого из четырёх одинаковых квадратов-стрелок
    val inner = g.okR + g.dGap          // зазор от круга OK до внутреннего края квадрата одинаков во все стороны
    val c0 = g.dpad
    val h = Pad.hat
    val up = h == 7 || h == 0 || h == 1; val right = h in 1..3; val down = h in 3..5; val left = h in 5..7
    val corner = androidx.compose.ui.geometry.CornerRadius(side * 0.26f)
    fun plate(x: Float, y: Float, on: Boolean) {
        drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset(x, y + 4f), Size(side, side), corner)
        drawRoundRect(androidx.compose.ui.graphics.Brush.verticalGradient(if (on) listOf(acc.copy(alpha = 0.95f), TEAL_D) else listOf(bodyTop, bodyBot), startY = y, endY = y + side), Offset(x, y), Size(side, side), corner)
        drawRoundRect(if (on) acc else ring, Offset(x, y), Size(side, side), corner, style = Stroke(if (on) 3f else 2f))
    }
    val tp = inner + side / 2           // расстояние центров квадратов от центра по своей оси
    plate(c0.x - side / 2, c0.y - tp - side / 2, up)
    plate(c0.x - side / 2, c0.y + tp - side / 2, down)
    plate(c0.x - tp - side / 2, c0.y - side / 2, left)
    plate(c0.x + tp - side / 2, c0.y - side / 2, right)
    fun tri(cx: Float, cy: Float, dx: Float, dy: Float, on: Boolean) {
        val sz = side * 0.24f
        val p = Path().apply {
            if (dy != 0f) { moveTo(cx, cy + dy * sz); lineTo(cx - sz, cy - dy * sz * 0.6f); lineTo(cx + sz, cy - dy * sz * 0.6f) }
            else { moveTo(cx + dx * sz, cy); lineTo(cx - dx * sz * 0.6f, cy - sz); lineTo(cx - dx * sz * 0.6f, cy + sz) }
            close()
        }
        drawPath(p, if (on) Color.Black.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.55f))
    }
    tri(c0.x, c0.y - tp, 0f, -1f, up); tri(c0.x, c0.y + tp, 0f, 1f, down); tri(c0.x - tp, c0.y, -1f, 0f, left); tri(c0.x + tp, c0.y, 1f, 0f, right)
    // центральная кнопка OK
    run {
        val on = isOn(Pad.OK)
        drawCircle(Color.Black.copy(alpha = 0.4f), g.okR * 1.05f, Offset(c0.x, c0.y + 4f))
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(if (on) listOf(acc, TEAL_D) else listOf(Color(0xFF46535B), Color(0xFF1F272D)),
            center = Offset(c0.x - g.okR * 0.3f, c0.y - g.okR * 0.3f), radius = g.okR * 1.5f), g.okR, c0)
        drawCircle(if (on) acc else acc.copy(alpha = 0.7f), g.okR, c0, style = Stroke(if (on) 4f else 2.5f))
        label("OK", c0, g.okR * 1.1f, if (on) Color.Black.copy(alpha = 0.85f) else Color.White, 1f, false)
    }

    // ---------------- ABXY: круглые кнопки с градиентом и цветными знаками ----------------
    fun face(c: Offset, bit: Int, col: Color, shape: Int, letter: String) {
        val on = isOn(bit)
        drawCircle(Color.Black.copy(alpha = 0.35f), g.br * 1.03f, Offset(c.x, c.y + g.br * 0.06f))
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(
            if (on) listOf(col.copy(alpha = 1f), col.copy(alpha = 0.65f)) else listOf(bodyTop, bodyBot),
            center = Offset(c.x - g.br * 0.3f, c.y - g.br * 0.3f), radius = g.br * 1.5f), g.br, c)
        drawCircle(col.copy(alpha = if (on) 1f else 0.75f), g.br, c, style = Stroke(if (on) 4.5f else 2.5f))
        if (on) drawCircle(col.copy(alpha = 0.25f), g.br * 1.25f, c, style = Stroke(5f))
        val sc = if (on) Color.Black.copy(alpha = 0.8f) else col
        val k = g.br * 0.40f
        when (shape) {
            1 -> { drawLine(sc, Offset(c.x - k, c.y - k), Offset(c.x + k, c.y + k), 6f); drawLine(sc, Offset(c.x + k, c.y - k), Offset(c.x - k, c.y + k), 6f) }
            2 -> drawCircle(sc, k, c, style = Stroke(6f))
            3 -> drawRoundRect(sc, Offset(c.x - k, c.y - k), Size(k * 2, k * 2), androidx.compose.ui.geometry.CornerRadius(3f), style = Stroke(6f))
            4 -> { val p = Path().apply { moveTo(c.x, c.y - k * 1.15f); lineTo(c.x + k * 1.15f, c.y + k * 0.85f); lineTo(c.x - k * 1.15f, c.y + k * 0.85f); close() }; drawPath(p, sc, style = Stroke(6f)) }
            else -> label(letter, c, g.br, sc, 1f, false)
        }
    }
    if (Pad.sony) { face(g.bA, Pad.A, psX, 1, ""); face(g.bB, Pad.B, psO, 2, ""); face(g.bX, Pad.X, psS, 3, ""); face(g.bY, Pad.Y, psT, 4, "") }
    else { face(g.bA, Pad.A, cA, 0, "A"); face(g.bB, Pad.B, cB, 0, "B"); face(g.bX, Pad.X, cX, 0, "X"); face(g.bY, Pad.Y, cY, 0, "Y") }

    // ---------------- плечевые и курки ----------------
    fun shoulder(r: androidx.compose.ui.geometry.Rect, text: String, bit: Int) {
        val on = isOn(bit)
        val cr = androidx.compose.ui.geometry.CornerRadius(22f)
        drawRoundRect(Color.Black.copy(alpha = 0.3f), Offset(r.left, r.top + 4f), r.size, cr)
        drawRoundRect(androidx.compose.ui.graphics.Brush.verticalGradient(if (on) listOf(acc, TEAL_D) else listOf(bodyTop, bodyBot), startY = r.top, endY = r.bottom), r.topLeft, r.size, cr)
        drawRoundRect(if (on) acc else ring, r.topLeft, r.size, cr, style = Stroke(if (on) 3.5f else 2f))
        label(text, r.center, g.shH * 0.55f, if (on) Color.Black.copy(alpha = 0.8f) else Color.White, 1f, false)
    }
    shoulder(g.l2r, "L2", Pad.L2); shoulder(g.l1r, "L1", Pad.L1); shoulder(g.r1r, "R1", Pad.R1); shoulder(g.r2r, "R2", Pad.R2)

    // ---------------- центральные кнопки ----------------
    fun small(c: Offset, text: String, bit: Int) {
        val on = isOn(bit)
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(if (on) listOf(acc, TEAL_D) else listOf(bodyTop, bodyBot), center = c, radius = g.small * 1.4f), g.small, c)
        drawCircle(if (on) acc else ring, g.small, c, style = Stroke(2f))
        label(text, c, g.small * 0.8f, if (on) Color.Black.copy(alpha = 0.8f) else Color.White, 1f, false)
    }
    small(g.select, if (Pad.sony) "SHR" else "BAK", Pad.SELECT); small(g.start, if (Pad.sony) "OPT" else "STA", Pad.START); small(g.home, if (Pad.sony) "PS" else "H", Pad.HOME)
    small(g.l3, "L3", Pad.L3); small(g.r3, "R3", Pad.R3)
}

private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true }

private fun DrawScope.label(text: String, c: Offset, size: Float, col: Color, a: Float, on: Boolean) {
    paint.color = (if (on) Color.Black else col).copy(alpha = (0.95f * a + 0.05f).coerceIn(0f, 1f)).toArgb()
    paint.textSize = size * 0.9f
    drawContext.canvas.nativeCanvas.drawText(text, c.x, c.y + size * 0.32f, paint)
}


// ------------------------------------------------------------------ настройки геймпада (вкладка и окно в игре)

@Composable
private fun PadRadios(labels: List<Int>, selected: Int, pick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, l ->
            Row(Modifier.fillMaxWidth().clickable { pick(i) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected == i, { pick(i) }); Text(stringResource(l), fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun PadSettings() {
    val ctx = LocalContext.current
    @Composable fun slider(label: Int, v: Float, range: ClosedFloatingPointRange<Float>, text: String, set: (Float) -> Unit) {
        Text(stringResource(label) + ": " + text, fontSize = 13.sp)
        Slider(v, { set(it); Pad.save(ctx) }, valueRange = range)
    }
    @Composable fun check(label: Int, on: Boolean, set: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().clickable { set(!on); Pad.save(ctx) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(on, { set(it); Pad.save(ctx) }); Text(stringResource(label), fontSize = 13.sp)
        }
    }
    Text(stringResource(R.string.pad_layout), fontWeight = FontWeight.Medium, fontSize = 13.sp)
    PadRadios(listOf(R.string.pad_xbox, R.string.pad_ps), if (Pad.sony) 1 else 0) { Pad.sony = it == 1; Pad.save(ctx) }
    slider(R.string.pad_dead, Pad.dead, 0f..0.4f, "${(Pad.dead * 100).toInt()}%") { Pad.dead = it }
    slider(R.string.pad_cspeed, Pad.cspeed, 0.3f..3f, "${(Pad.cspeed * 100).toInt()}%") { Pad.cspeed = it }
    slider(R.string.pad_size, Pad.scale, 0.7f..1.3f, "${(Pad.scale * 100).toInt()}%") { Pad.scale = it }
    slider(R.string.pad_alpha, Pad.alpha, 0.3f..1f, "${(Pad.alpha * 100).toInt()}%") { Pad.alpha = it }
    // отклик нажатий: общий для программы (вибро и звук щелчка), здесь выключатель окна игры и те же переключатели
    check(R.string.pad_haptic, Pad.haptic) { Pad.haptic = it }
    if (Pad.haptic) {
        check(R.string.fb_vibro, Feedback.vibro) { Feedback.setVibro(ctx, it); if (it) Feedback.tick(ctx) }
        check(R.string.fb_beep, Feedback.beep) { Feedback.setBeep(ctx, it); if (it) Feedback.tick(ctx) }
        if (!Feedback.vibro && !Feedback.beep) Text(stringResource(R.string.pad_fb_off), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
    }
    check(R.string.pad_float, Pad.floating) { Pad.floating = it }

    // наклон
    check(R.string.pad_tilt, Pad.tilt) { Pad.tilt = it }
    if (Pad.tilt) {
        Text(stringResource(R.string.pad_t_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
        Text(stringResource(R.string.pad_t_target), fontWeight = FontWeight.Medium, fontSize = 13.sp)
        PadRadios(listOf(R.string.pad_t_left, R.string.pad_t_right, R.string.pad_t_dpad, R.string.pad_t_wheel), Pad.tiltTarget) { Pad.tiltTarget = it; Pad.save(ctx) }
        Text(stringResource(R.string.pad_t_axes), fontWeight = FontWeight.Medium, fontSize = 13.sp)
        PadRadios(listOf(R.string.pad_ax_both, R.string.pad_ax_x, R.string.pad_ax_y), Pad.tiltAxes) { Pad.tiltAxes = it; Pad.save(ctx) }
        slider(R.string.pad_t_angle, Pad.tiltAngle, 10f..60f, "${Pad.tiltAngle.toInt()}°") { Pad.tiltAngle = it }
        slider(R.string.pad_t_dead, Pad.tiltDead, 0f..0.3f, "${(Pad.tiltDead * 100).toInt()}%") { Pad.tiltDead = it }
        slider(R.string.pad_t_smooth, Pad.tiltSmooth, 0f..0.9f, "${(Pad.tiltSmooth * 100).toInt()}%") { Pad.tiltSmooth = it }
        check(R.string.pad_t_invx, Pad.tiltInvX) { Pad.tiltInvX = it }
        check(R.string.pad_t_invy, Pad.tiltInvY) { Pad.tiltInvY = it }
        check(R.string.pad_t_hide, Pad.tiltHide) { Pad.tiltHide = it }
        OutlinedButton({ Pad.calibrate(ctx); Feedback.tick(ctx) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.pad_t_cal), fontSize = 13.sp) }
    }

    // обратная вибрация
    check(R.string.pad_rumble, Pad.rumble) { Pad.rumble = it; if (!it) PadRumble.stop(ctx) }
    if (Pad.rumble) {
        slider(R.string.pad_rumble_gain, Pad.rumbleGain, 0f..2f, "${(Pad.rumbleGain * 100).toInt()}%") { Pad.rumbleGain = it }
        Text(stringResource(R.string.pad_rumble_mode), fontWeight = FontWeight.Medium, fontSize = 13.sp)
        PadRadios(listOf(R.string.pad_rm_xbox, R.string.pad_rm_ds, R.string.pad_rm_pid), Pad.rumbleMode) { Pad.rumbleMode = it; Pad.save(ctx) }
        Text(stringResource(R.string.pad_rumble_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
        Text(stringResource(if (PadRumble.amplitudeControl(ctx)) R.string.pad_rumble_amp else R.string.pad_rumble_noamp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
    }
}

/** Профили раскладки и отключение: на вкладке и в окне настроек поверх игры. */
@Composable
fun PadProfilesAndHost(inGame: Boolean, close: () -> Unit = {}) {
    val ctx = LocalContext.current
    Text(stringResource(R.string.pad_profiles), fontWeight = FontWeight.Medium, fontSize = 13.sp)
    for (n in 1..3) {
        var has by remember(n) { mutableStateOf(Pad.hasProfile(ctx, n)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.pad_slot, n), Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, softWrap = false)
            TextButton({ Pad.saveProfile(ctx, n); has = true }) { Text(stringResource(R.string.pad_save), fontSize = 12.sp, maxLines = 1) }
            TextButton({ Pad.loadProfile(ctx, n) }, enabled = has) { Text(stringResource(R.string.pad_load), fontSize = 12.sp, maxLines = 1) }
        }
    }
    if (inGame) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton({ close(); BtAuto.forget(ctx) }) { Text(stringResource(R.string.bta_forget), fontSize = 12.sp) }
    }
}

// ------------------------------------------------------------------ вкладка «Ещё → Геймпад»: подключённый хост (запасной вид под игрой)

@Composable
fun PadConnected(host: String, land: Boolean) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Pad.load(ctx) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberSavedScroll("tab.pad")), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BridgeTip()
        Text(stringResource(R.string.bt_connected, host), Modifier.fillMaxWidth(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, maxLines = 2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button({ Pad.playing = true }, Modifier.weight(1f)) { Text(stringResource(R.string.pad_play)) }
            PadTrialButton(compact = true)
        }
        PadLocalButton(Modifier.fillMaxWidth())
        OutlinedButton({ runCatching { ctx.startActivity(android.content.Intent(ctx, GamepadTestActivity::class.java)) } }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.gpt_open)) }
        PadSettings()
        PadProfilesAndHost(true)
    }
}


/** Компактный список сопряжённых хостов в окне игры: тап подключает выбранный (чистое отключение от текущего, затем подключение), окно не закрывается. */
@Composable
private fun PadDevices(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
    val rows = remember {
        BtHid.sortLastFirst(ctx, runCatching { adapter?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
            .map { d -> BtRow(d, d.address, runCatching { d.name }.getOrNull() ?: d.address) })
    }
    val cur = BtHid.hostAddress()?.uppercase()
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)).clickable { onClose() }, contentAlignment = Alignment.Center) {
        androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.7f).heightIn(max = 320.dp).clickable(enabled = false) {}) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.pad_devices_title), Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    androidx.compose.material3.TextButton(onClose) { Text(stringResource(R.string.fm_close)) }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    rows.forEach { r ->
                        val isCur = BtHid.hostName != null && cur != null && r.address.uppercase() == cur
                        Row(Modifier.fillMaxWidth().clickable { if (!isCur) BtAuto.switchHost(ctx, r); onClose() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(r.address, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                            }
                            if (isCur) Text(stringResource(R.string.pad_dev_current), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}


/** Окно на ВЕСЬ экран: системные панели скрыты (появляются по свайпу), содержимое заходит под вырез, безопасные поля считает раскладка. */
fun Activity.padImmersive() {
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
    if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
    val c = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
    c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}


/** Пробный режим окна игры без компьютера: стики, кнопки и наклон работают на экране, ничего не уходит на ПК. Значок игры; подпись — по долгому нажатию. */
fun startPadTrial() { BtHid.dryRun = true; Pad.playing = true }

@Composable
fun PadTrialButton(compact: Boolean = false, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val label = stringResource(R.string.pad_try_nopc)
    val sh = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.heightIn(min = 40.dp).clip(sh)
            .border(if (focused) 3.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, sh)
            .onFocusChanged { st -> focused = st.isFocused }
            .combinedClickable(onClick = { startPadTrial() }, onLongClick = { android.widget.Toast.makeText(ctx, label, android.widget.Toast.LENGTH_SHORT).show() })
            .semantics { this.contentDescription = label }
            .padding(horizontal = if (compact) 10.dp else 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.SportsEsports, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        if (!compact) Text(label, fontSize = 14.sp, maxLines = 1)
    }
}

/** ПРОТОТИП «Играть на этом телефоне»: полупрозрачные кнопки поверх игры и виртуальный контроллер этого телефона (нужны «Поверх других окон» и ADB к самому телефону). */
@Composable
fun PadLocalButton(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val sh = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    val label = stringResource(R.string.pad_local)
    Row(
        modifier.heightIn(min = 40.dp).clip(sh).border(1.dp, MaterialTheme.colorScheme.outline, sh)
            .combinedClickable(onClick = {
                if (OverlayLaunch.start(ctx)) { OverlayLaunch.offerTile(ctx); ctx.findActivity()?.moveTaskToBack(true) }
            }, onLongClick = { android.widget.Toast.makeText(ctx, label, android.widget.Toast.LENGTH_SHORT).show() })
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.SportsEsports, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, fontSize = 14.sp, maxLines = 1)
    }
}
