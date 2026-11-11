package app.adbphon

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Защита от двойного нажатия (7.5). Мыши и пульты на ТВ-приставках иногда шлют на одно нажатие два события: два касания в одну точку подряд
 * или две клавиши «ОК» (DPAD_CENTER и ENTER). Второе нажатие попадало уже на другой элемент (после первого окно или раздел раскрылся и сдвинул
 * строки), поэтому на «Корзину» открывались ещё консоль и резервная копия. Теперь второе касание в ту же точку в течение 350 мс и вторая клавиша «ОК»
 * в течение 200 мс игнорируются вместе со всем жестом. Выключается в «Настройках»; на площадках-«тачпадах» (Мышь ПК, пульт) не действует.
 */
object TapGuard {
    var on by mutableStateOf(true)
        private set
    /** Больше нуля, пока на экране площадка, где быстрые повторные касания нужны (двойной клик, повторные тапы). */
    @Volatile var suspended = 0

    private const val TAP_MS = 350L
    private const val KEY_MS = 200L
    private var tapSlopPx = 90f

    private var lastDownTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var blocking = false

    private var lastOkTime = 0L
    private val blockedKeys = HashSet<Int>()

    @Volatile var dropped = 0
        private set

    fun init(ctx: Context) {
        on = ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getBoolean("tap_guard", true)
        tapSlopPx = 40f * ctx.resources.displayMetrics.density
    }

    fun set(ctx: Context, v: Boolean) {
        on = v
        ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE).edit().putBoolean("tap_guard", v).apply()
        reset()
    }

    private fun reset() { blocking = false; lastDownTime = 0L; lastOkTime = 0L; blockedKeys.clear() }

    /** true: событие проглотить (дубль). */
    fun touch(ev: MotionEvent): Boolean {
        if (!on || suspended > 0) { if (blocking) blocking = false; return false }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val t = ev.eventTime
                val dt = t - lastDownTime
                val dx = ev.x - lastX
                val dy = ev.y - lastY
                if (lastDownTime != 0L && dt in 0..TAP_MS && dx * dx + dy * dy <= tapSlopPx * tapSlopPx) {
                    blocking = true; dropped++
                    AppLog.i("ADBphon", "защита от двойного нажатия: касание в ту же точку через ${dt} мс пропущено (${ev.source.toString(16)})")
                    return true
                }
                blocking = false; lastDownTime = t; lastX = ev.x; lastY = ev.y
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { val b = blocking; blocking = false; return b }
            else -> return blocking
        }
    }

    private fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER ||
        code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_BUTTON_SELECT

    /** true: событие проглотить (вторая клавиша «ОК» на то же нажатие). */
    fun key(ev: KeyEvent): Boolean {
        if (!on || suspended > 0 || !isOk(ev.keyCode)) return false
        val code = ev.keyCode
        if (ev.action == KeyEvent.ACTION_DOWN) {
            if (ev.repeatCount > 0) return code in blockedKeys
            val t = ev.eventTime
            if (lastOkTime != 0L && t - lastOkTime in 0..KEY_MS) {
                blockedKeys.add(code); dropped++
                AppLog.i("ADBphon", "защита от двойного нажатия: вторая клавиша ОК (код $code) через ${t - lastOkTime} мс пропущена")
                return true
            }
            lastOkTime = t
            return false
        }
        if (ev.action == KeyEvent.ACTION_UP) return blockedKeys.remove(code)
        return false
    }
}

/** Ставится внутрь площадок, где быстрые повторные касания нужны (двойной клик тачпада, повторные тапы пульта): на время показа защита не действует. */
@Composable
fun TapGuardOff() {
    DisposableEffect(Unit) { TapGuard.suspended++; onDispose { TapGuard.suspended-- } }
}
