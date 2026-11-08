package app.adbphon

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Окно игры (стики и кнопки) в отдельной Activity: только альбом при любых настройках поворота
 * (в манифесте landscape, а в коде ориентация подтверждается при создании, возврате и каждой смене конфигурации).
 * Остальное приложение вращается свободно; при выходе из окна возвращается обычная вкладка.
 */
class GamepadActivity : ComponentActivity() {
    /** Запомнено, что система игнорирует запрос ориентации: альбом не запрашивается, окно сразу рисуется повёрнутым слоем (см. [OrientGuard]). */
    private var ignored = false

    /** Ориентация зафиксирована в одной стороне альбома и не переворачивается сама от наклона телефона; её меняет только кнопка «Перевернуть». */
    private fun lockLandscape() {
        val want = if (ignored) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else if (Pad.flip) ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        if (requestedOrientation != want) requestedOrientation = want
    }

    /** Окно оказалось узкой альбомной полосой на портретном экране: система игнорирует запрос. Запрос снимается, окно займёт весь экран, слой повернётся. */
    private fun onLetterboxed() {
        if (isInMultiWindowMode) return
        OrientGuard.markIgnored(this)
        if (!ignored) { ignored = true; lockLandscape() }
    }

    override fun attachBaseContext(newBase: Context) {
        val lang = newBase.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getString("lang", "auto") ?: "auto"
        super.attachBaseContext(Lang.wrap(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // начальная сторона: сохранённая, иначе та, в которой телефон был при входе (обратный альбом 270° или обычный)
        val rot = runCatching { @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation }.getOrDefault(android.view.Surface.ROTATION_90)
        Pad.loadFlip(this, rot == android.view.Surface.ROTATION_270)
        ignored = OrientGuard.recentlyIgnored(this)
        lockLandscape()
        padImmersive()
        OrientGuard.excludeGestures(this)
        OrientAssist.askWrite(this)
        Pad.playing = true
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF5EEAD4), onPrimary = androidx.compose.ui.graphics.Color(0xFF00201C), secondaryContainer = androidx.compose.ui.graphics.Color(0xFF0F766E), surface = androidx.compose.ui.graphics.Color(0xFF14141B), background = androidx.compose.ui.graphics.Color(0xFF0B0B10))) {
                LaunchedEffect(BtHid.hostName) { if (BtHid.hostName == null && !BtHid.dryRun && !BtHid.switching) finish() }
                LaunchedEffect(Pad.playing) { if (!Pad.playing) finish() }
                LaunchedEffect(Pad.flip) { lockLandscape(); OrientAssist.begin(applicationContext, Pad.flip, OrientGuard.displayPortrait(this@GamepadActivity)) }
                PadOverlay(onExit = { finish() }, onLetterboxed = { onLetterboxed() })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lockLandscape()
        OrientAssist.begin(applicationContext, Pad.flip, OrientGuard.displayPortrait(this))
        OrientAssist.holderOn(this, Pad.flip)
        BtHid.sectionEnter(); Pad.fg = true
    }

    override fun onResume() {
        super.onResume()
        padImmersive()
        lockLandscape()
        Pad.fg = true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // что-то (системный поворот, сторонняя программа) потянуло в портрет: возвращаем альбом (если система его слушается); слой пересчитывается сам по размеру окна
        lockLandscape()
    }

    override fun onPause() {
        Pad.fg = false
        Pad.releaseAll()
        super.onPause()
    }

    override fun onStop() {
        // ушли из окна (HOME, другое приложение, экран погас): «всё отпущено», позже HID отключается
        BtHid.dryRun = false       // пробный режим без хоста живёт только пока окно на экране
        Pad.releaseAll()
        BtHid.sectionLeave()
        BtPower.leave(applicationContext)
        OrientAssist.holderOff(this)
        OrientAssist.end(applicationContext)
        super.onStop()
    }

    override fun onDestroy() {
        OrientAssist.end(applicationContext)
        Pad.releaseAll()
        Pad.playing = false
        BtHid.dryRun = false
        super.onDestroy()
    }
}

/**
 * Окна игры всегда выглядят альбомными, что бы ни делали чужие программы. Без таймеров: геометрия окна определяется по реальным размерам окна и экрана.
 * Окно портретное: сразу рисуется слой, повёрнутый на 90° (вторая сторона по Pad.flip), на весь экран. Окно альбомное: рисуется как есть.
 * Запрос альбома (`requestedOrientation`) остаётся лишь бонусом: если система его слушается, экран ложится в альбом сам. Если она его игнорирует
 * (ignoreOrientationRequest, Rotation Control Pro), окно получается узкой альбомной полосой на портретном экране: это распознаётся по первому кадру,
 * запрос снимается и запоминается на неделю (следующие запуски без запроса и без полосы).
 */
object OrientGuard {
    private const val KEY = "pad_orient_ignored_at"
    private const val WEEK = 7L * 24 * 3600 * 1000

    fun displayPortrait(a: android.app.Activity): Boolean {
        val p = android.graphics.Point()
        @Suppress("DEPRECATION") a.windowManager.defaultDisplay.getRealSize(p)
        return p.y > p.x
    }

    fun recentlyIgnored(a: android.app.Activity): Boolean =
        System.currentTimeMillis() - a.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getLong(KEY, 0L) < WEEK

    fun markIgnored(a: android.app.Activity) { a.getSharedPreferences("adbphon", Context.MODE_PRIVATE).edit().putLong(KEY, System.currentTimeMillis()).apply() }

    /** Окно альбомное по форме, а экран портретный: окно зажато системой в полосу. */
    fun letterboxed(a: android.app.Activity, w: Float, h: Float): Boolean = w > h && displayPortrait(a) && !a.isInMultiWindowMode

    /**
     * Системные жесты (назад, домой) не должны выкидывать из окна игры: по краям окна исключаются полосы; система позволяет до 200 dp на край,
     * берётся середина каждого края.
     */
    fun excludeGestures(a: android.app.Activity) {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val v = a.window.decorView
        v.addOnLayoutChangeListener { view, l, t, r, b, _, _, _, _ ->
            val d = view.resources.displayMetrics.density
            val w = r - l; val h = b - t
            val e = (28 * d).toInt(); val len = (190 * d).toInt()
            view.systemGestureExclusionRects = listOf(
                android.graphics.Rect(0, h / 2 - len / 2, e, h / 2 + len / 2),
                android.graphics.Rect(w - e, h / 2 - len / 2, w, h / 2 + len / 2),
                android.graphics.Rect(w / 2 - len / 2, h - e, w / 2 + len / 2, h),
                android.graphics.Rect(w / 2 - len / 2, 0, w / 2 + len / 2, e),
            )
        }
    }
}

/** Содержимое, нарисованное для альбома, на портретном экране поворачивается на 90° и занимает весь экран; в альбоме без изменений. */
@androidx.compose.runtime.Composable
fun LandscapeInPortrait(onLetterboxed: () -> Unit = {}, content: @androidx.compose.runtime.Composable () -> Unit) {
    val act = androidx.compose.ui.platform.LocalContext.current.findActivity()
    androidx.compose.foundation.layout.BoxWithConstraints(androidx.compose.ui.Modifier.fillMaxSize()) {
        val dens = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.SideEffect { if (act != null && OrientGuard.letterboxed(act, with(dens) { maxWidth.toPx() }, with(dens) { maxHeight.toPx() })) onLetterboxed() }
        if (maxHeight <= maxWidth) content()
        else {
            val sw = maxWidth; val sh = maxHeight
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.wrapContentSize(androidx.compose.ui.Alignment.TopStart, unbounded = true)
                    .requiredSize(sh, sw)
                    .graphicsLayer {
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        rotationZ = 90f
                        translationX = sw.toPx()
                    },
            ) { content() }
        }
    }
}
