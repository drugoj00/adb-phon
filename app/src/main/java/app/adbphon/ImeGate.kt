package app.adbphon

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ImeGateHost(content: @Composable () -> Unit) {
    val view = LocalView.current
    InterceptPlatformTextInput(
        interceptor = PlatformTextInputInterceptor { request, next ->
            ImeGate.fieldFocused = true
            ImeGate.allowed = !ImeGate.dpadMode
            try {
                coroutineScope {
                    var job: Job? = null
                    var watch: Job? = null
                    snapshotFlow { ImeGate.allowed }.collect { ok ->
                        if (ok) {
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
fun Modifier.imeGateKeys(): Modifier = composed {
    val fm = LocalFocusManager.current
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                ImeGate.dpadMode = false
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
            } else if (k == Key.Back) { if (down) ImeGate.allowed = false; true } else false
        }
}
