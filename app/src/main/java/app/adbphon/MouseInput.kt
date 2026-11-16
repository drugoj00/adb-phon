package app.adbphon

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 10.0 (mouse): общие правила мыши и аэромыши в списках.
 * Аэромыши и виртуальные мыши (пульт в режиме мыши, ввод через ADB) иногда присылают нажатие без кнопки (buttonState = 0):
 * такое нажатие считается левой кнопкой, иначе список нельзя было «схватить» и протянуть.
 */
object MouseBtn {
    /** Левая кнопка мыши (или нажатие мыши без сведений о кнопке). Правая, средняя, «назад» и «вперёд» сюда не входят. */
    fun isLeft(type: PointerType, b: PointerButtons): Boolean =
        type == PointerType.Mouse && (b.isPrimaryPressed || !(b.isSecondaryPressed || b.isTertiaryPressed || b.isBackPressed || b.isForwardPressed))

    fun isRight(type: PointerType, b: PointerButtons): Boolean = type == PointerType.Mouse && b.isSecondaryPressed && !b.isPrimaryPressed
}

/**
 * 10.0 (mouse): скорость броска мышью, когда стандартный счётчик скорости даёт ноль. На слабых приставках кадр списка рисуется 50-200 мс,
 * события мыши приходят по одному на кадр, и стандартный счётчик считает, что указатель остановился (паузы больше 40 мс): инерции не было.
 * Здесь скорость берётся по точкам за последние 300 мс до отпускания (если таких нет, по предыдущей точке не старше 600 мс).
 * Отпустили через 100 мс и позже после последнего движения: указатель стоял, броска нет.
 */
object MouseFling {
    const val MAX = 8000f

    /** [ts] время точек (мс), [ys] положение (px), [upAt] время отпускания. Результат в px/с (знак как у движения указателя). */
    fun velocity(ts: List<Long>, ys: List<Float>, upAt: Long): Float {
        val n = minOf(ts.size, ys.size)
        if (n < 2) return 0f
        val tl = ts[n - 1]
        if (upAt - tl > 100) return 0f
        var r = n - 1
        while (r > 0 && tl - ts[r - 1] <= 300) r--
        if (r == n - 1) { if (tl - ts[n - 2] <= 600) r = n - 2 else return 0f }
        val dt = (tl - ts[r]).toFloat()
        if (dt < 8f) return 0f
        return ((ys[n - 1] - ys[r]) / dt * 1000f).coerceIn(-MAX, MAX)
    }
}

/**
 * 10.0 (mouse): список под курсором мыши. Клавиши PAGE_UP / PAGE_DOWN аэромыши и клавиатуры листают его, даже когда фокус пульта стоит
 * в другом месте (на кнопке, вкладке) и до списка клавиша не доходит. Запоминается самый внутренний список под курсором.
 */
object SbHover {
    private var owner: Any? = null
    private var pageFn: ((Int) -> Unit)? = null

    fun enter(owner: Any, page: (Int) -> Unit) { this.owner = owner; pageFn = page }
    fun leave(owner: Any) { if (this.owner === owner) { this.owner = null; pageFn = null } }

    /** Из Activity.dispatchKeyEvent, если клавишу никто не взял. true: список под курсором пролистан. */
    fun key(e: android.view.KeyEvent): Boolean {
        val dir = when (e.keyCode) {
            android.view.KeyEvent.KEYCODE_PAGE_UP -> -1
            android.view.KeyEvent.KEYCODE_PAGE_DOWN -> 1
            else -> return false
        }
        val f = pageFn ?: return false
        if (e.action == android.view.KeyEvent.ACTION_DOWN) runCatching { f(dir) }
        return true
    }
}

/**
 * 10.0 (mouse): меню файла мышью и аэромышью без правой кнопки. На приставках (проверено на X98plus, Android 11) система превращает
 * правую кнопку мыши в «Назад», до программы нажатие не доходит; у аэромышей правая кнопка обычно и есть «Назад». Поэтому меню строки:
 * нажать левую (ОК аэромыши), подержать неподвижно дольше долгого нажатия и отпустить. Сдвинул указатель: это прокрутка списка, меню нет
 * (так нажать-подержать-потянуть по-прежнему листает). Отпускание поглощается, чтобы обычный клик строки не сработал.
 * Вызывается из awaitEachGesture строки после первого нажатия мыши; true: открыть меню.
 */
suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.fmMouseHoldRelease(down: androidx.compose.ui.input.pointer.PointerInputChange): Boolean {
    if (!MouseBtn.isLeft(down.type, currentEvent.buttons)) return false
    val slop = viewConfiguration.touchSlop
    while (true) {
        val ev = awaitPointerEvent(PointerEventPass.Initial)
        val ch = ev.changes.firstOrNull { it.id == down.id } ?: return false
        if (ch.isConsumed || (ch.position - down.position).getDistance() > slop) return false
        if (!ch.pressed) {
            if (ch.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) return false
            ch.consume()
            return true
        }
    }
}

/**
 * 10.0 (mouse): левая кнопка мыши у строки с долгим нажатием (меню). Обычный combinedClickable открывал меню через 0,5 с удержания и
 * забирал жест: «нажал ОК аэромыши, подержал, повёл» вместо прокрутки открывало меню. Здесь для мыши: короткий клик = [onClick],
 * нажал, подержал неподвижно и отпустил = [onLong]; сдвинул указатель = прокрутка списка (mouseDragScroll), ни клика, ни меню.
 * Ставится ПОСЛЕ combinedClickable строки и работает в основном проходе: вложенные элементы (галочка) берут нажатие раньше, а если нет,
 * нажатие поглощается здесь, и собственный clickable строки его уже не видит. Касания и пульт идут как раньше.
 */
fun Modifier.mouseLeftClicks(onClick: () -> Unit, onLong: () -> Unit): Modifier = composed {
    val c by rememberUpdatedState(onClick)
    val l by rememberUpdatedState(onLong)
    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Main)
            if (!MouseBtn.isLeft(down.type, currentEvent.buttons)) return@awaitEachGesture
            down.consume()
            val slop = viewConfiguration.touchSlop
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Main)
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (ch.isConsumed || (ch.position - down.position).getDistance() > slop) return@awaitEachGesture
                if (!ch.pressed) {
                    ch.consume()
                    if (ch.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) l() else c()
                    return@awaitEachGesture
                }
            }
        }
    }
}

/**
 * Правая кнопка мыши: [onRight] (меню строки). Нажатие поглощается в первом проходе, поэтому обычный клик строки (открыть, отметить)
 * от правой кнопки не срабатывает. Левая кнопка и касания проходят как раньше.
 */
fun Modifier.mouseSecondary(onRight: () -> Unit): Modifier = composed {
    val cb by rememberUpdatedState(onRight)
    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!MouseBtn.isRight(down.type, currentEvent.buttons)) return@awaitEachGesture
            down.consume()
            cb()
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                ev.changes.forEach { it.consume() }
                if (ev.changes.none { it.pressed }) break
            }
        }
    }
}
