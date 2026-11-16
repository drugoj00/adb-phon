package app.adbphon

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 10.1: слой жестов над встроенным видеоплеером (палец и мышь дают одни и те же события указателя) и кнопка «Под экран».
 * Жесты: по вертикали в правой половине экрана громкость, в левой яркость; по горизонтали перемотка (чем длиннее свайп, тем дальше;
 * применяется при отпускании). Ось выбирается после порога и держится до отпускания. Короткое касание проходит к плееру и показывает/прячет панель.
 * Свайпы по нижней полосе с панелью управления не перехватываются: там шкала времени плеера.
 */
class VideoGestureUi(private val act: Activity, val pv: PlayerView, private val player: () -> ExoPlayer?) {
    private val dp = act.resources.displayMetrics.density
    private val prefs = act.getSharedPreferences("adbphon", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val hint = TextView(act).apply {
        setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER; visibility = View.GONE
        setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        background = GradientDrawable().apply { setColor(0xAA000000.toInt()); cornerRadius = 12 * dp }
    }
    private val hideHint = Runnable { hint.visibility = View.GONE }
    private var volAcc = 0f
    private fun on(key: String) = prefs.getBoolean(key, true)
    private var seekBase = -1L
    private var seekTarget = 0L

    /** Режимы кнопки «Под экран»: по размеру (вписать), заполнить (с обрезкой), растянуть. */
    private val modes = intArrayOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL)
    private val modeNames = intArrayOf(R.string.vp_fit_fit, R.string.vp_fit_fill, R.string.vp_fit_stretch)
    private val keep = prefs.getBoolean(PlayerPrefs.KEEP, true)   // «Запоминать размер кадра»
    private var mode = if (keep) prefs.getInt(PlayerPrefs.SIZE, 0).coerceIn(0, 2) else 0   // первый запуск и выключенное запоминание: по размеру

    val root = object : FrameLayout(act) {
        private var sx = 0f; private var sy = 0f; private var lastY = 0f
        private var axis = 0          // 0 не выбрана, 1 горизонталь (перемотка), 2 вертикаль (громкость/яркость), -1 жест не наш
        private var leftSide = false
        private var brightnessSide = false   // этот жест по вертикали меняет яркость (иначе громкость)
        private var vt: VelocityTracker? = null
        private var downT = 0L
        private val slop = ViewConfiguration.get(act).scaledTouchSlop * 2

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { vt?.recycle(); vt = VelocityTracker.obtain(); downT = e.eventTime }
            vt?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.x; sy = e.y; lastY = e.y; axis = 0; leftSide = e.x < width / 2f
                    if (pv.isControllerFullyVisible && e.y > height - 130 * dp) axis = -1
                }
                MotionEvent.ACTION_MOVE -> if (axis == 0 && e.pointerCount == 1) {
                    val dx = e.x - sx; val dy = e.y - sy
                    if (max(abs(dx), abs(dy)) > slop) {
                        // настройки читаются на лету; выключенный жест гасится (3): плеер не получает конец жеста как касание
                        if (abs(dx) > abs(dy)) axis = if (on(PlayerPrefs.SEEK) || on(PlayerPrefs.FLING)) 1 else 3
                        else {
                            brightnessSide = leftSide != prefs.getBoolean(PlayerPrefs.SWAP, false)
                            axis = if (on(if (brightnessSide) PlayerPrefs.BRI else PlayerPrefs.VOL)) 2 else 3
                        }
                        lastY = e.y; return true
                    }
                }
            }
            return axis > 0 || axis == 3
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (axis <= 0 && axis != 3) return false
            if (axis == 3) { if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) axis = 0; return true }
            vt?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (axis == 1) { if (on(PlayerPrefs.SEEK)) seekMove(e.x - sx, width) } else { adjust(brightnessSide, -(e.y - lastY) / height); lastY = e.y }
                MotionEvent.ACTION_UP -> {
                    if (axis == 1) {
                        // быстрый бросок (высокая скорость, короткий жест): следующий/предыдущий файл; медленное ведение: перемотка
                        vt?.computeCurrentVelocity(1000)
                        val vx = vt?.xVelocity ?: 0f
                        android.util.Log.i("ADBphonMedia", "жест: горизонталь vx=" + vx.roundToInt() + " px/с, dp=" + dp + ", длительность " + (e.eventTime - downT) + " мс")
                        if (on(PlayerPrefs.FLING) && abs(vx) > 1200 * dp && e.eventTime - downT < 350) { seekEnd(false); fling(vx < 0) } else seekEnd(on(PlayerPrefs.SEEK))
                    }
                    axis = 0; vt?.recycle(); vt = null
                }
                MotionEvent.ACTION_CANCEL -> { if (axis == 1) seekEnd(false); axis = 0; vt?.recycle(); vt = null }
            }
            return true
        }

        // колесо мыши: громкость
        override fun onGenericMotionEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_SCROLL && on(PlayerPrefs.VOL)) { adjust(false, e.getAxisValue(MotionEvent.AXIS_VSCROLL) * 0.06f); return true }
            return super.onGenericMotionEvent(e)
        }
    }.apply {
        addView(pv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (48 * dp).toInt() })
    }

    init {
        pv.resizeMode = modes[mode]
        // «Скрывать панель через»: секунды из настроек (по умолчанию 5), 0 = не скрывать; повторное касание скрывает панель
        val hideSec = prefs.getInt(PlayerPrefs.HIDE, 5)
        pv.controllerShowTimeoutMs = if (hideSec <= 0) 0 else hideSec * 1000
        pv.controllerHideOnTouch = true
        addFitButton()
    }

    fun showHint(text: String, ms: Long = 1000) {
        hint.text = text; hint.visibility = View.VISIBLE
        handler.removeCallbacks(hideHint); handler.postDelayed(hideHint, ms)
    }

    private fun addFitButton() {
        val bar = pv.findViewById<ViewGroup?>(androidx.media3.ui.R.id.exo_basic_controls) ?: return
        val bg = StateListDrawable().apply {
            val on = GradientDrawable().apply { setColor(0x66FFFFFF); cornerRadius = 8 * dp }
            addState(intArrayOf(android.R.attr.state_focused), on); addState(intArrayOf(android.R.attr.state_pressed), on)
            addState(intArrayOf(), GradientDrawable().apply { setColor(0); cornerRadius = 8 * dp })
        }
        val b = TextView(act).apply {
            text = act.getString(R.string.vp_fit); setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            background = bg; isFocusable = true; isClickable = true
            contentDescription = act.getString(R.string.vp_fit) + ": " + act.getString(modeNames[mode])
            setOnClickListener { cycle(this) }
        }
        bar.addView(b, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun cycle(b: View) {
        mode = (mode + 1) % modes.size
        pv.resizeMode = modes[mode]
        if (keep) prefs.edit().putInt(PlayerPrefs.SIZE, mode).apply()
        val label = act.getString(R.string.vp_fit_hint, act.getString(modeNames[mode]))
        android.util.Log.i("ADBphonMedia", "Под экран: $label")
        b.contentDescription = label
        showHint(label)
    }

    private fun fmt(ms: Long): String {
        val s = (abs(ms) / 1000).toInt(); val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun seekMove(dx: Float, w: Int) {
        val p = player() ?: return
        if (seekBase < 0) seekBase = p.currentPosition
        val dur = p.duration
        // чувствительность из настроек: секунд видео на всю ширину экрана (по умолчанию 180)
        val delta = (dx / w * prefs.getInt(PlayerPrefs.SENS, 180)).roundToInt() * 1000L
        seekTarget = (seekBase + delta).coerceAtLeast(0L).let { if (dur != C.TIME_UNSET && dur > 0) it.coerceAtMost(dur) else it }
        val d = seekTarget - seekBase
        showHint((if (d >= 0) "+" else "−") + fmt(d) + " → " + fmt(seekTarget), 4000)
    }

    private fun seekEnd(apply: Boolean) {
        if (apply && seekBase >= 0) { android.util.Log.i("ADBphonMedia", "жест: перемотка $seekBase -> $seekTarget"); player()?.seekTo(seekTarget) }
        seekBase = -1L
        if (apply) showHint(hint.text.toString(), 700) else { handler.removeCallbacks(hideHint); hint.visibility = View.GONE }
    }

    /** Бросок влево: следующий файл списка, вправо: предыдущий. Файл один (или края списка): короткая подсказка. */
    private fun fling(next: Boolean) {
        val p = player() ?: return
        if (next && p.hasNextMediaItem()) p.seekToNextMediaItem()
        else if (!next && p.hasPreviousMediaItem()) { p.seekToPreviousMediaItem(); if (p.currentPosition > 3000) p.seekTo(0) }
        else { showHint(act.getString(if (next) R.string.vp_no_next else R.string.vp_no_prev)); return }
        android.util.Log.i("ADBphonMedia", "жест: бросок " + (if (next) "вперёд" else "назад") + " -> " + p.currentMediaItem?.mediaMetadata?.title)
        showHint((if (next) "» " else "« ") + (p.currentMediaItem?.mediaMetadata?.title ?: ""), 1200)
    }

    private fun adjust(brightness: Boolean, frac: Float) {
        if (brightness) {
            val lp = act.window.attributes
            val cur = if (lp.screenBrightness < 0) runCatching { android.provider.Settings.System.getInt(act.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f) else lp.screenBrightness
            lp.screenBrightness = (cur + frac).coerceIn(0.02f, 1f); act.window.attributes = lp
            android.util.Log.i("ADBphonMedia", "жест: яркость " + (lp.screenBrightness * 100).roundToInt())
            showHint(act.getString(R.string.vp_bright, (lp.screenBrightness * 100).roundToInt()))
        } else {
            val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            volAcc += frac * max * 1.5f
            if (abs(volAcc) >= 1f) { am.setStreamVolume(AudioManager.STREAM_MUSIC, (am.getStreamVolume(AudioManager.STREAM_MUSIC) + volAcc.toInt()).coerceIn(0, max), 0); volAcc = 0f }
            android.util.Log.i("ADBphonMedia", "жест: громкость " + am.getStreamVolume(AudioManager.STREAM_MUSIC) + "/" + max)
            showHint(act.getString(R.string.vp_volume, am.getStreamVolume(AudioManager.STREAM_MUSIC), max))
        }
    }
}
