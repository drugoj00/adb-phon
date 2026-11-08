package app.adbphon

import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

/** Состояние проверки: последняя кнопка, подключённые геймпады. Рисунок берётся из общей раскладки окна игры (PadLayout, Geo, drawPad). */
object GpTest {
    var lastKey by mutableStateOf("")
    var devices by mutableStateOf<List<String>>(emptyList())
    var anyInput by mutableStateOf(false)
    var tick by mutableStateOf(0)          // перерисовка рисунка при каждом событии

    private val dpad = BooleanArray(4)      // вверх, вправо, вниз, влево (кнопки DPAD_*)
    private var hatX = 0f
    private var hatY = 0f

    fun refreshDevices() {
        devices = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
            .filter { d -> d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK }
            .map { d -> "${d.name}  (${"%04X".format(d.vendorId)}:${"%04X".format(d.productId)})" }
    }

    fun reset() {
        Pad.axes.fill(0f); Pad.buttons = 0; Pad.hat = 8; Pad.lt = false; Pad.rt = false
        dpad.fill(false); hatX = 0f; hatY = 0f; lastKey = ""; anyInput = false; tick++
    }

    private fun applyHat() {
        val up = hatY < -0.5f || dpad[0]; val right = hatX > 0.5f || dpad[1]; val down = hatY > 0.5f || dpad[2]; val left = hatX < -0.5f || dpad[3]
        Pad.hat = when {
            up && right -> 1; right && down -> 3; down && left -> 5; left && up -> 7
            up -> 0; right -> 2; down -> 4; left -> 6; else -> 8
        }
    }

    /** Кнопка геймпада или пульта: подсветка в общем рисунке. */
    fun key(code: Int, down: Boolean) {
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> dpad[0] = down
            KeyEvent.KEYCODE_DPAD_RIGHT -> dpad[1] = down
            KeyEvent.KEYCODE_DPAD_DOWN -> dpad[2] = down
            KeyEvent.KEYCODE_DPAD_LEFT -> dpad[3] = down
            KeyEvent.KEYCODE_BUTTON_A -> Pad.setBtn(Pad.A, down)
            KeyEvent.KEYCODE_BUTTON_B -> Pad.setBtn(Pad.B, down)
            KeyEvent.KEYCODE_BUTTON_X -> Pad.setBtn(Pad.X, down)
            KeyEvent.KEYCODE_BUTTON_Y -> Pad.setBtn(Pad.Y, down)
            KeyEvent.KEYCODE_BUTTON_L1 -> Pad.setBtn(Pad.L1, down)
            KeyEvent.KEYCODE_BUTTON_R1 -> Pad.setBtn(Pad.R1, down)
            KeyEvent.KEYCODE_BUTTON_L2 -> { Pad.lt = down; Pad.setBtn(Pad.L2, down) }
            KeyEvent.KEYCODE_BUTTON_R2 -> { Pad.rt = down; Pad.setBtn(Pad.R2, down) }
            KeyEvent.KEYCODE_BUTTON_SELECT -> Pad.setBtn(Pad.SELECT, down)
            KeyEvent.KEYCODE_BUTTON_START -> Pad.setBtn(Pad.START, down)
            KeyEvent.KEYCODE_BUTTON_MODE -> Pad.setBtn(Pad.HOME, down)
            KeyEvent.KEYCODE_BUTTON_THUMBL -> Pad.setBtn(Pad.L3, down)
            KeyEvent.KEYCODE_BUTTON_THUMBR -> Pad.setBtn(Pad.R3, down)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_1 -> Pad.setBtn(Pad.OK, down)
        }
        applyHat()
        tick++
    }

    /** Оси стиков, курков и хэт геймпада. */
    fun motion(e: MotionEvent) {
        fun a(vararg ax: Int): Float { var best = 0f; for (x in ax) { val v = e.getAxisValue(x); if (kotlin.math.abs(v) > kotlin.math.abs(best)) best = v }; return best }
        Pad.axes[0] = a(MotionEvent.AXIS_X); Pad.axes[1] = a(MotionEvent.AXIS_Y)
        Pad.axes[2] = a(MotionEvent.AXIS_Z, MotionEvent.AXIS_RX); Pad.axes[3] = a(MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY)
        hatX = a(MotionEvent.AXIS_HAT_X); hatY = a(MotionEvent.AXIS_HAT_Y)
        val l2 = a(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE) > 0.5f; val r2 = a(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS) > 0.5f
        if (l2 != Pad.lt) { Pad.lt = l2; Pad.setBtn(Pad.L2, l2) }
        if (r2 != Pad.rt) { Pad.rt = r2; Pad.setBtn(Pad.R2, r2) }
        applyHat()
        if (kotlin.math.hypot(Pad.axes[0], Pad.axes[1]) > 0.2f || kotlin.math.hypot(Pad.axes[2], Pad.axes[3]) > 0.2f) anyInput = true
        tick++
    }
}

/**
 * Экран «Проверка геймпада»: на весь экран показывает ввод геймпадов, подключённых к ЭТОМУ устройству, в той же раскладке и тем же
 * рисунком, что окно игры (PadLayout). Работает на телефоне и на ТВ-приставке с пультом (Back закрывает). Запуск: Ещё → Геймпад → «Проверка»
 * или `am start -n app.adbphon/.GamepadTestActivity` (действие app.adbphon.GAMEPAD_TEST).
 */
class GamepadTestActivity : ComponentActivity() {
    private val im by lazy { getSystemService(Context.INPUT_SERVICE) as InputManager }
    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = GpTest.refreshDevices()
        override fun onInputDeviceRemoved(id: Int) = GpTest.refreshDevices()
        override fun onInputDeviceChanged(id: Int) = GpTest.refreshDevices()
    }

    override fun attachBaseContext(newBase: Context) {
        val lang = newBase.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getString("lang", "auto") ?: "auto"
        super.attachBaseContext(Lang.wrap(newBase, lang))
    }

    private var ignored = false
    private fun orient() {
        val want = if (ignored) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (requestedOrientation != want) requestedOrientation = want
    }
    private fun onLetterboxed() {
        if (isInMultiWindowMode) return
        OrientGuard.markIgnored(this)
        if (!ignored) { ignored = true; orient() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        padImmersive()
        ignored = OrientGuard.recentlyIgnored(this)
        orient()
        OrientGuard.excludeGestures(this)
        OrientAssist.askWrite(this)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // `am start -n app.adbphon/.GamepadTestActivity --ez dryrun true`: окно игры без хоста, отчёты пишутся в logcat (тег ADBphonHID)
        if (intent?.getBooleanExtra("dryrun", false) == true && BtHid.hostName == null) {
            BtHid.dryRun = true
            startActivity(Intent(this, GamepadActivity::class.java))
            finish()
            return
        }
        // `am start -n app.adbphon/.GamepadTestActivity --ei rumble 200 [--ei rumblew 0]`: пробный выходной отчёт 8 [сильный, слабый] прямо в приёмник вибро
        // (проверка цепочки «отчёт -> вибромотор телефона» без компьютера; при нуле или без хоста ничего не делает)
        if (intent?.hasExtra("rumble") == true) {
            Pad.load(this)
            PadRumble.onReport(this, 8, byteArrayOf(intent.getIntExtra("rumble", 0).toByte(), intent.getIntExtra("rumblew", 0).toByte()))
            finish()
            return
        }
        Pad.load(this)
        GpTest.reset()
        GpTest.refreshDevices()
        runCatching { im.registerInputDeviceListener(listener, null) }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF5EEAD4), onPrimary = Color(0xFF00201C), surface = Color(0xFF14141B), background = Color(0xFF0B0B10))) {
                LandscapeInPortrait(onLetterboxed = { onLetterboxed() }) { GamepadTestScreen() }
            }
        }
    }

    override fun onStart() { super.onStart(); OrientAssist.begin(applicationContext, false, OrientGuard.displayPortrait(this)); OrientAssist.holderOn(this, false) }
    override fun onStop() { OrientAssist.holderOff(this); OrientAssist.end(applicationContext); super.onStop() }
    override fun onResume() { super.onResume(); padImmersive() }

    override fun onDestroy() {
        runCatching { im.unregisterInputDeviceListener(listener) }
        GpTest.reset()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        // Back с пульта закрывает; Back, пришедший от геймпада (кнопка B как Back), показывается как кнопка
        if (code == KeyEvent.KEYCODE_BACK && e.source and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD) {
            if (e.action == KeyEvent.ACTION_UP) finish()
            return true
        }
        if (e.action == KeyEvent.ACTION_DOWN || e.action == KeyEvent.ACTION_UP) {
            val down = e.action == KeyEvent.ACTION_DOWN
            GpTest.key(code, down)
            if (down) { GpTest.lastKey = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_"); GpTest.anyInput = true }
        }
        return true
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && e.action == MotionEvent.ACTION_MOVE) { GpTest.motion(e); return true }
        return super.dispatchGenericMotionEvent(e)
    }
}

@Composable
fun GamepadTestScreen() {
    val prim = MaterialTheme.colorScheme.primary
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Pad.load(ctx) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF14181C))) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val g = Geo(w, h, false)
        Canvas(Modifier.fillMaxSize()) {
            GpTest.tick           // подписка на перерисовку
            drawPad(g, prim)
        }
        // «Проверка геймпада» остаётся ОДНА над рядом L3 / Back / Guide / Start / R3 (его верхний край ≈ 0,25 высоты); остальное — в нижней середине окна, ниже ряда, в две строки
        val dens = LocalDensity.current
        val sp = { px: Float -> with(dens) { px.toSp() } }
        val lh = { px: Float -> with(dens) { (px * 1.2f).toSp() } }
        val minPx = with(dens) { 12f * density }
        val top = with(dens) { (h * 0.02f).toDp() }
        val boxH = with(dens) { (h * 0.20f).toDp() }
        Column(Modifier.align(Alignment.TopCenter).padding(top = top).height(boxH).fillMaxWidth(0.4f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
            val f1 = g.u * 0.050f
            Text(stringResource(R.string.gpt_title), fontSize = sp(f1), lineHeight = lh(f1), fontWeight = FontWeight.Bold, color = prim, textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
        }
        val f2 = maxOf(g.u * 0.030f, minPx)
        val devLine = if (GpTest.devices.isEmpty()) stringResource(R.string.gpt_none) else GpTest.devices.take(3).joinToString("  ·  ")
        val hintLine = if (GpTest.anyInput) stringResource(R.string.gpt_last, GpTest.lastKey.ifEmpty { "-" }) else stringResource(R.string.gpt_hint)
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = with(dens) { (h * 0.012f).toDp() }).fillMaxWidth(0.5f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(devLine, fontSize = sp(f2), lineHeight = lh(f2), color = Color.White.copy(alpha = .85f), textAlign = TextAlign.Center, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(hintLine, fontSize = sp(f2), lineHeight = lh(f2), color = prim.copy(alpha = .9f), textAlign = TextAlign.Center, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}
