package app.adbphon

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Обратная вибрация геймпада: хост (игра) шлёт выходной отчёт с силой двух моторов, телефон вибрирует.
 * Сильный мотор: длинные низкие «удары, ямы»; слабый мотор: быстрая лёгкая «дрожь». Амплитуда берётся из отчёта
 * (если мотор телефона её поддерживает), иначе обычные импульсы. Остановка: нулевой отчёт, окончание длительности,
 * молчание хоста более 2,5 с, отключение хоста.
 */
object PadRumble {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var strong = 0f
    @Volatile private var weak = 0f
    @Volatile private var until = 0L
    @Volatile private var lastReport = 0L
    @Volatile private var releasing = false
    /** Последний принятый выходной отчёт (для проверки): «id: байты». */
    var last by mutableStateOf("")
    var count by mutableStateOf(0)
    /** Время последнего принятого ненулевого отчёта (для индикатора в окне игры). */
    @Volatile var lastAt = 0L

    private fun vib(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    /** Вибро от игры сейчас идёт: отклик нажатий не должен его обрывать. */
    fun active(): Boolean = job?.isActive == true && System.currentTimeMillis() - lastReport < 700

    fun amplitudeControl(ctx: Context): Boolean = vib(ctx)?.let { it.hasVibrator() && Build.VERSION.SDK_INT >= 26 && it.hasAmplitudeControl() } == true

    /** Выходной отчёт от хоста. [id] номер отчёта, [data] без номера. */
    fun onReport(ctx: Context, id: Int, data: ByteArray) {
        // в режиме «Курсор» геймпад не геймпад: вибрация от ПК не нужна
        if (Pad.cursor) { if (job != null) stop(ctx); return }
        count++
        last = "$id: " + data.joinToString(" ") { "%02X".format(it) }
        AppLog.i("ADBphon", "pad output report $last")
        var s = 0; var w = 0; var dur = 0L
        when {
            id == 6 && data.size >= 5 -> {            // Xbox-подобный: [включение, левый курок, правый курок, сильный, слабый, длительность, задержка, повтор]
                val en = data[0].toInt() and 0x0F
                s = if (en and 0x04 != 0) data[3].toInt() and 0xFF else 0
                w = if (en and 0x08 != 0) data[4].toInt() and 0xFF else 0
                val d = if (data.size > 5) data[5].toInt() and 0xFF else 0
                dur = if (d == 0 || d == 0xFF) 0L else d * 10L
            }
            id == 7 && data.size >= 5 -> {            // PlayStation-подобный: [флаги, 0, 0, слабый, сильный]
                w = data[3].toInt() and 0xFF; s = data[4].toInt() and 0xFF
            }
            id == 8 && data.size >= 2 -> {            // универсальный PID: [сильный, слабый]
                s = data[0].toInt() and 0xFF; w = data[1].toInt() and 0xFF
            }
            else -> return
        }
        lastReport = System.currentTimeMillis()
        if (s != 0 || w != 0) lastAt = lastReport
        until = if (dur > 0) lastReport + dur else 0L
        if (s == 0 && w == 0) { releasing = true } else { releasing = false; strong = s.toFloat(); weak = w.toFloat() }
        if (Pad.rumble) ensureLoop(ctx.applicationContext)
    }

    fun stop(ctx: Context? = null) {
        strong = 0f; weak = 0f; releasing = false; until = 0L
        job?.cancel(); job = null
        ctx?.let { runCatching { vib(it)?.cancel() } }
    }

    private fun ensureLoop(ctx: Context) {
        if (job?.isActive == true) return
        job = scope.launch {
            val v = vib(ctx)
            if (v == null || !v.hasVibrator()) return@launch
            val amp = Build.VERSION.SDK_INT >= 26 && v.hasAmplitudeControl()
            val attr = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
            while (isActive) {
                val now = System.currentTimeMillis()
                if (!Pad.rumble || BtHid.hostName == null || now - lastReport > 2500 || (until > 0 && now > until)) break
                if (releasing) { strong *= 0.55f; weak *= 0.55f }           // плавное затухание после нулевого отчёта
                val g = Pad.rumbleGain
                val s = (strong * g).coerceIn(0f, 255f)
                val w = (weak * g).coerceIn(0f, 255f)
                if (s < 12f && w < 12f) break
                runCatching {
                    if (amp) {
                        val e = if (s >= w * 0.8f) VibrationEffect.createOneShot(70, s.toInt().coerceIn(1, 255))
                        else VibrationEffect.createWaveform(longArrayOf(0, 18, 14, 18), intArrayOf(0, w.toInt().coerceIn(1, 255), 0, w.toInt().coerceIn(1, 255)), -1)
                        @Suppress("DEPRECATION") v.vibrate(e, attr)
                    } else {
                        val big = s >= w * 0.8f
                        val level = if (big) s else w
                        if (level >= 40f) {
                            @Suppress("DEPRECATION") v.vibrate(VibrationEffect.createOneShot(if (big) 70 else 20, VibrationEffect.DEFAULT_AMPLITUDE), attr)
                        }
                    }
                }
                delay(if (s >= w * 0.8f) 75 else 55)
            }
            runCatching { v.cancel() }
            strong = 0f; weak = 0f; releasing = false
        }
    }
}
