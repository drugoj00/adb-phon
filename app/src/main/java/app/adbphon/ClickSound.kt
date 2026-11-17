package app.adbphon

import android.view.SoundEffectConstants
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.launch

/**
 * 10.2 (58): системный звук касания на нажатия по строкам, кнопкам и переключателям (как в X-plore). Звук играет само нажатие,
 * завершённое отпусканием (прокрутка и отмена звука не дают); системная настройка «Звук касания» учитывается самой системой.
 */
object ClickSound {
    @Volatile private var last = 0L
    fun play(v: View) {
        val t = android.os.SystemClock.uptimeMillis()
        if (t - last < 90) return      // одно нажатие слышно один раз
        last = t
        runCatching { v.playSoundEffect(SoundEffectConstants.CLICK) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private class ClickSoundIndication(private val base: IndicationNodeFactory, private val view: View) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode {
        val inner = base.create(interactionSource)
        return object : DelegatingNode() {
            init { delegate(inner) }
            override fun onAttach() {
                coroutineScope.launch {
                    interactionSource.interactions.collect { if (it is PressInteraction.Release) ClickSound.play(view) }
                }
            }
        }
    }
    override fun equals(other: Any?) = other is ClickSoundIndication && other.base == base && other.view === view
    override fun hashCode() = base.hashCode() * 31 + System.identityHashCode(view)
}

/** Включает звук касания для всех нажатий внутри [content] (обычные «clickable», кнопки, строки списков). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClickSoundHost(content: @Composable () -> Unit) {
    val base: Indication = LocalIndication.current
    val view = LocalView.current
    val ind = remember(base, view) { if (base is IndicationNodeFactory) ClickSoundIndication(base, view) else base }
    CompositionLocalProvider(LocalIndication provides ind, content = content)
}
