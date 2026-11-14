package app.adbphon

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Поля ввода и пульт. Пока управление идёт стрелками пульта, поле, на которое перешёл фокус, клавиатуру не открывает: стрелки уводят фокус дальше,
 * а «ОК» включает ввод и открывает клавиатуру. «Назад» при открытой клавиатуре закрывает её и возвращает стрелки. Касание и мышь ведут себя как обычно.
 */
object ImeGate {
    /** Последним действием были стрелки/ОК пульта (касание сбрасывает). */
    var dpadMode by mutableStateOf(false)
    /** Клавиатуре разрешено открыться для текущего поля. */
    var allowed by mutableStateOf(false)
    /** Сейчас в фокусе поле ввода (идёт сеанс ввода). */
    var fieldFocused by mutableStateOf(false)
    /** Когда клавиатуре разрешили открыться (мс): сразу после этого она ещё выезжает, стрелки не должны считать её закрытой. */
    @Volatile var allowedAt = 0L

    /** Границы элемента, который сейчас в фокусе (сообщает Compose; null, если фокуса нет). */
    @Volatile var focusedCoords: androidx.compose.ui.layout.LayoutCoordinates? = null

    /** Касание или клик в точке окна (x, y): если там элемент в фокусе и это поле ввода, ввод включается и клавиатура открывается. */
    fun tapOnField(x: Float, y: Float) {
        val c = focusedCoords
        val ok = fieldFocused && !allowed && c != null && c.isAttached && c.boundsInWindow().contains(androidx.compose.ui.geometry.Offset(x, y))
        if (ok) allowed = true
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ImeGateHost(content: @Composable () -> Unit) {
    val view = LocalView.current
    // 9.2: на приставке до первого касания управляет пульт: поле, на которое фокус пришёл без касания (запуск, смена вкладки), клавиатуру не открывает
    androidx.compose.runtime.remember { if (Marq.tv) ImeGate.dpadMode = true; 0 }
    InterceptPlatformTextInput(
        interceptor = PlatformTextInputInterceptor { request, next ->
            ImeGate.fieldFocused = true
            // 9.2: на приставке фокус на поле (в том числе возвращённый при смене вкладки касанием или мышью) клавиатуру не открывает; открывают ОК пульта
            // и касание/клик именно по полю (см. tapOnField); на телефоне прежнее правило: без пульта клавиатура открывается сама
            ImeGate.allowed = if (Marq.tv) false else !ImeGate.dpadMode
            try {
                coroutineScope {
                    var job: Job? = null
                    var watch: Job? = null
                    snapshotFlow { ImeGate.allowed }.collect { ok ->
                        if (ok) {
                            ImeGate.allowedAt = System.currentTimeMillis()
                            if (job?.isActive != true) job = launch { next.startInputMethod(request) }
                            // клавиатуру закрыли кнопкой «Назад» (её забирает сама клавиатура): ввод выключается, стрелки снова ходят по экрану
                            if (ImeGate.dpadMode && watch?.isActive != true) watch = launch {
                                delay(1200)
                                var hidden = 0
                                while (true) {
                                    delay(300)
                                    val vis = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                                    hidden = if (vis) 0 else hidden + 1
                                    if (hidden >= 2) { ImeGate.allowed = false; break }
                                }
                            }
                        } else { job?.cancel(); job = null; watch?.cancel(); watch = null }
                    }
                }
                awaitCancellation()
            } finally {
                ImeGate.fieldFocused = false; ImeGate.allowed = false
            }
        },
        content = content,
    )
}

/** Ставится на корень экрана и на окна-диалоги (у каждого окна свой разбор клавиш). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.imeGateKeys(): Modifier = composed {
    val fm = LocalFocusManager.current
    val view = LocalView.current
    val tapScope = androidx.compose.runtime.rememberCoroutineScope()
    var origin by androidx.compose.runtime.remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    this
        .onGloballyPositioned { origin = it.positionInWindow() }
        .onFocusedBoundsChanged { ImeGate.focusedCoords = it }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                ImeGate.dpadMode = false
                if (Marq.tv) {
                    // 9.2: касание или клик по самому полю открывает клавиатуру (в точке касания должно стоять поле, которое получило фокус)
                    val up = waitForUpOrCancellation(PointerEventPass.Initial)
                    if (up != null) {
                        val sx = origin.x + down.position.x; val sy = origin.y + down.position.y
                        tapScope.launch { delay(150); ImeGate.tapOnField(sx, sy) }
                    }
                }
            }
        }
        .onPreviewKeyEvent { e ->
            val k = e.key
            val dir = when (k) {
                Key.DirectionUp -> FocusDirection.Up
                Key.DirectionDown -> FocusDirection.Down
                Key.DirectionLeft -> FocusDirection.Left
                Key.DirectionRight -> FocusDirection.Right
                else -> null
            }
            val ok = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter
            val down = e.type == KeyEventType.KeyDown
            if (down && (dir != null || ok)) ImeGate.dpadMode = true
            if (!ImeGate.fieldFocused || !ImeGate.dpadMode) return@onPreviewKeyEvent false
            if (!ImeGate.allowed) {
                when {
                    dir != null -> { if (down) fm.moveFocus(dir); true }
                    ok -> { if (down) ImeGate.allowed = true; true }
                    else -> false
                }
            } else if (k == Key.Back) { if (down) ImeGate.allowed = false; true }
            else if (dir != null) {
                // 9.2: режим ввода включён (касанием, мышью или после ОК), но клавиатура закрыта или не выехала (её закрыли кнопкой «Назад» у самой клавиатуры,
                // поле получило фокус при касании): поле не должно «залипать». Стрелка выводит из режима ввода и двигает фокус дальше.
                // Пока клавиатура открыта (или выезжает первые 1,5 с), стрелки остаются клавиатуре и курсору в тексте.
                val imeVisible = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                if (!imeVisible && System.currentTimeMillis() - ImeGate.allowedAt > 1500) { ImeGate.allowed = false; if (down) fm.moveFocus(dir); true } else false
            } else false
        }
}
