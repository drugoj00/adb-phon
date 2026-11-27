package app.adbphon

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.delay

/**
 * 26.11.2026 (4PDA wad72, пост 146220268): в режиме «Во весь экран» при выключенном автоповороте система не показывает свою кнопку поворота
 * (кнопки навигации спрятаны). Программа показывает свою: телефон повернули, в углу появляется кнопка; нажатие поворачивает экран.
 * Режим выключен или автоповорот включён: поворот снова как в системе.
 */
@Composable
fun FullRotateButton(full: Boolean) {
    val ctx = LocalContext.current
    val act = remember(ctx) { generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull() } ?: return
    var offer by remember { mutableStateOf<Int?>(null) }   // предлагаемый поворот (Surface.ROTATION_*)
    DisposableEffect(full) {
        if (!full) {
            release(act)
            offer = null
            onDispose { }
        } else {
            var candidate = -1
            var since = 0L
            val listener = object : OrientationEventListener(act) {
                override fun onOrientationChanged(angle: Int) {
                    if (angle == ORIENTATION_UNKNOWN) return
                    // 0: естественное положение; датчик считает по часовой стрелке, а поворот экрана против
                    val rot = when (angle) { in 335..359, in 0..25 -> Surface.ROTATION_0; in 65..115 -> Surface.ROTATION_270; in 245..295 -> Surface.ROTATION_90; else -> -1 }
                    if (rot < 0) return
                    val now = android.os.SystemClock.uptimeMillis()
                    if (rot != candidate) { candidate = rot; since = now; return }
                    if (now - since < 400) return
                    if (autoRotate(act)) { release(act); offer = null; return }
                    @Suppress("DEPRECATION")
                    val shown = act.windowManager.defaultDisplay.rotation
                    offer = if (rot != shown) rot else null
                }
            }
            if (listener.canDetectOrientation()) listener.enable()
            onDispose { listener.disable() }
        }
    }
    val rot = offer ?: return
    LaunchedEffect(rot) { delay(7000); offer = null }
    // 27.11.2026 (4PDA wad72, пост 146236023): вид как у кнопки смены панелей: серая с тонкой окантовкой, значок цвета её стрелки
    val cs = MaterialTheme.colorScheme
    val bg = cs.background
    val dark = (0.2126f * bg.red + 0.7152f * bg.green + 0.0722f * bg.blue) < 0.5f
    val purple = if (dark) Color(0xFFC4B5FD) else Color(0xFF6D28D9)
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val desc = stringResource(R.string.fs_rotate)
    Popup(alignment = Alignment.BottomStart, offset = IntOffset(0, 0)) {
        Box(
            Modifier.padding(start = 16.dp, bottom = 80.dp).size(56.dp).clip(shape)
                // кнопка лежит поверх списка, поэтому серый цвет смешан с фоном и не просвечивает
                .background(cs.onSurface.copy(alpha = .14f).compositeOver(bg))
                .border(if (focused) 3.dp else 1.dp, if (focused) purple else cs.outline.copy(alpha = .5f), shape)
                .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, onClickLabel = desc) {
                    act.requestedOrientation = orientationFor(act, rot); offer = null
                }
                .semantics { contentDescription = desc },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.ScreenRotation, null, Modifier.size(30.dp), tint = purple) }
    }
}

private fun autoRotate(ctx: Context) = Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1

/** Вернуть поворот системе (как объявлено у окна: fullUser). */
private fun release(act: Activity) {
    if (act.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_FULL_USER && act.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    }
}

private fun orientationFor(act: Activity, rot: Int): Int {
    @Suppress("DEPRECATION")
    val shown = act.windowManager.defaultDisplay.rotation
    val land = act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val flat = shown == Surface.ROTATION_0 || shown == Surface.ROTATION_180
    val naturalLandscape = flat == land   // у части планшетов естественное положение альбомное
    return if (!naturalLandscape) when (rot) {
        Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    } else when (rot) {
        Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
}
