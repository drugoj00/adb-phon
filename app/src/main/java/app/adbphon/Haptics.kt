package app.adbphon

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.ChipColors
import androidx.compose.material3.ChipElevation
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SelectableChipElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext

/**
 * Лёгкий отклик на нажатие кнопок: еле заметная вибрация и тихий короткий звук. Оба вида включены по умолчанию
 * и выключаются в настройках. Все стандартные кнопки программы (ниже) вызывают [tick] сами.
 */
/** Включается вокруг экранов пульта и мыши: только их кнопки с командами дают отклик. */
val LocalFeedbackOn = compositionLocalOf { false }

object Feedback {
    var vibro by mutableStateOf(true)
    var beep by mutableStateOf(false)
    /** Окно приложения: через него система сама выдаёт штатный отклик касания (как в клавиатуре Samsung). */
    @Volatile var host: android.view.View? = null
    private var last = 0L
    private var click: AudioTrack? = null
    private var vib: Vibrator? = null
    private var ready = false

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        val p = sp(ctx)
        vibro = p.getBoolean("fb_vibro", true)
        beep = p.getBoolean("fb_beep", false)
        Thread { runCatching { prepare(ctx) } }.start()
    }

    fun setVibro(ctx: Context, on: Boolean) { vibro = on; sp(ctx).edit().putBoolean("fb_vibro", on).apply() }
    fun setBeep(ctx: Context, on: Boolean) { beep = on; sp(ctx).edit().putBoolean("fb_beep", on).apply() }

    @Synchronized
    private fun prepare(ctx: Context) {
        if (ready) return
        ready = true
        val app = ctx.applicationContext
        vib = runCatching {
            if (Build.VERSION.SDK_INT >= 31) (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        }.getOrNull()
        // тап Samsung лежит в программе (res/raw/tap_sound.ogg); не раскодировался: свой глухой клик
        val tap = runCatching { tapSound(app) }
        click = tap.getOrNull() ?: runCatching { makeClick() }.getOrNull()
        AppLog.i("ADBphon", "звук отклика: " + (if (tap.getOrNull() != null) "тап S9 из программы" else "запасной глухой клик (" + tap.exceptionOrNull() + ")"))
    }

    /** Звук касания Samsung (res/raw/tap_sound.ogg): декодируем в память и поднимаем до 80% громкости, как в сравнении на компьютере. */
    private fun tapSound(ctx: Context): AudioTrack? {
        val ex = MediaExtractor()
        ctx.resources.openRawResourceFd(R.raw.tap_sound).use { fd -> ex.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
        ex.selectTrack(0)
        val fmt = ex.getTrackFormat(0)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val bytes = java.io.ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var guard = 0
        while (!outDone && guard++ < 2000) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(5_000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 5_000)
            if (o >= 0) {
                val out = codec.getOutputBuffer(o)!!
                val chunk = ByteArray(info.size)
                out.position(info.offset); out.get(chunk)
                bytes.write(chunk)
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
        }
        codec.stop(); codec.release(); ex.release()
        val raw = bytes.toByteArray()
        val total = raw.size / 2
        if (total == 0) return null
        val sb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = total / ch
        val mono = ShortArray(frames)
        var peak = 1
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until ch) sum += sb.get(f * ch + c).toInt()
            val v = sum / ch
            mono[f] = v.toShort()
            if (Math.abs(v) > peak) peak = Math.abs(v)
        }
        val gain = 0.95 * Short.MAX_VALUE / peak
        for (i in mono.indices) mono[i] = (mono[i] * gain).toInt().coerceIn(-32767, 32767).toShort()
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(mono.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        t.write(mono, 0, mono.size)
        return t
    }

    /**
     * Глухой клик, слышный и на маленьком динамике телефона: затухающий тон 450 Гц плюс приглушённый шумовой удар, 35 мс.
     * (Тон ниже 250 Гц динамик телефона почти не воспроизводит: выходил дребезг.) Играет по каналу будильника: громкость не зависит от ползунка мультимедиа.
     */
    private fun makeClick(): AudioTrack {
        val rate = 44100
        val n = rate * 35 / 1000
        val rnd = java.util.Random(11)
        var lp = 0.0
        val pcm = ShortArray(n) { i ->
            val t = i.toDouble() / rate
            lp += 0.2 * ((rnd.nextDouble() * 2 - 1) - lp)      // фильтр нижних частот: шум становится «закрытым»
            val body = Math.sin(2.0 * Math.PI * 450.0 * t) * Math.exp(-t / 0.007)
            val knock = lp * 4.0 * Math.exp(-t / 0.003)
            val attack = Math.min(1.0, t / 0.0008)
            ((body * 0.8 + knock * 0.5) * attack * 0.85 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(n * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        t.write(pcm, 0, n)
        return t
    }

    fun tick(ctx: Context) {
        if (!vibro && !beep) return
        val now = System.currentTimeMillis()
        if (now - last < 35) return
        last = now
        prepare(ctx)
        // вибро от игры имеет приоритет: короткий тик не обрывает его (звук щелчка остаётся)
        if (vibro && !PadRumble.active()) runCatching {
            val v = vib
            if (v != null && v.hasVibrator()) {
                // моторам телефона нужно 30-40 мс, чтобы раскачаться: короче толчок не чувствуется; 45 мс на полной силе мотора ощущаются как короткий упругий толчок
                val e = VibrationEffect.createOneShot(60, 200)
                try {
                    @Suppress("DEPRECATION") v.vibrate(e, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
                } catch (t: Throwable) {
                    AppLog.i("ADBphon", "вибро с атрибутами не вышло: " + t)
                    v.vibrate(e)
                }
            }
        }
        if (beep) runCatching {
            click?.let { it.stop(); it.setVolume(0.1f); it.reloadStaticData(); it.play() }
        }
    }
}

@Composable
fun Button(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(), elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null, contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) {
    val ctx = LocalContext.current
    val fb = LocalFeedbackOn.current
    androidx.compose.material3.Button({ if (fb) Feedback.tick(ctx); onClick() }, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(), elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled), contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) {
    val ctx = LocalContext.current
    val fb = LocalFeedbackOn.current
    androidx.compose.material3.OutlinedButton({ if (fb) Feedback.tick(ctx); onClick() }, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
}

@Composable
fun TextButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(), elevation: ButtonElevation? = null, border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) {
    val ctx = LocalContext.current
    val fb = LocalFeedbackOn.current
    androidx.compose.material3.TextButton({ if (fb) Feedback.tick(ctx); onClick() }, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
}

@Composable
fun FilledTonalButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = ButtonDefaults.filledTonalShape,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(), elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null, contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) {
    val ctx = LocalContext.current
    val fb = LocalFeedbackOn.current
    androidx.compose.material3.FilledTonalButton({ if (fb) Feedback.tick(ctx); onClick() }, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
}

@Composable
fun IconButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(), interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val fb = LocalFeedbackOn.current
    androidx.compose.material3.IconButton({ if (fb) Feedback.tick(ctx); onClick() }, modifier, enabled, colors, interactionSource, content)
}

@Composable
fun AssistChip(
    onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null, trailingIcon: @Composable (() -> Unit)? = null, shape: Shape = AssistChipDefaults.shape,
    colors: ChipColors = AssistChipDefaults.assistChipColors(), elevation: ChipElevation? = AssistChipDefaults.assistChipElevation(),
    border: BorderStroke? = AssistChipDefaults.assistChipBorder(enabled), interactionSource: MutableInteractionSource? = null,
) {
    val ctx = LocalContext.current
    val fb = LocalFeedbackOn.current
    androidx.compose.material3.AssistChip({ if (fb) Feedback.tick(ctx); onClick() }, label, modifier, enabled, leadingIcon, trailingIcon, shape, colors, elevation, border, interactionSource)
}

@Composable
fun FilterChip(
    selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null, trailingIcon: @Composable (() -> Unit)? = null, shape: Shape = FilterChipDefaults.shape,
    colors: SelectableChipColors = FilterChipDefaults.filterChipColors(), elevation: SelectableChipElevation? = FilterChipDefaults.filterChipElevation(),
    border: BorderStroke? = FilterChipDefaults.filterChipBorder(enabled, selected), interactionSource: MutableInteractionSource? = null,
) {
    val ctx = LocalContext.current
    val fb = LocalFeedbackOn.current
    androidx.compose.material3.FilterChip(selected, { if (fb) Feedback.tick(ctx); onClick() }, label, modifier, enabled, leadingIcon, trailingIcon, shape, colors, elevation, border, interactionSource)
}
