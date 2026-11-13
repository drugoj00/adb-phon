package app.adbphon

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Мелодии звонков (7.6): отдельно для входящего видеозвонка и входящего голосового, гудок ожидания у звонящего, громкость.
 * 8 встроенных мелодий синтезируются кодом (чужих записей нет; те же, что в «Телефон в окне со звуком» 4.1), «Свой файл» копируется в память программы, «Без звука».
 * Настройки: prefs «ringtone». Мелодия играет через MediaPlayer по кругу, пока звонок не принят, не отклонён и не отменён (Calls.cancelRing → Ringtones.stop).
 */
object RingSynth {
    private const val SR = 44100

    private fun note(b: FloatArray, t0: Double, dur: Double, f: Double, amp: Double, kind: Int) {
        val s0 = (t0 * SR).toInt(); val n = (dur * SR).toInt()
        for (i in 0 until n) {
            val k = s0 + i; if (k < 0 || k >= b.size) break
            val t = i.toDouble() / SR; val w = 2 * Math.PI * f * t
            var env = 1.0
            var att = minOf(1.0, t / 0.004); var rel = minOf(1.0, (dur - t) / 0.03); if (rel < 0) rel = 0.0
            val v: Double = when (kind) {
                0 -> Math.sin(w) * Math.exp(-t * 2.8) + 0.4 * Math.sin(w * 2.76) * Math.exp(-t * 6.0) + 0.18 * Math.sin(w * 5.4) * Math.exp(-t * 9.0)
                1 -> { env = Math.pow(Math.sin(Math.PI * t / dur), 2.0); att = 1.0; rel = 1.0; Math.sin(w) + 0.25 * Math.sin(2 * w) }
                2 -> Math.tanh(2.5 * Math.sin(w)) * 0.8
                3 -> { env = Math.exp(-t * 8); Math.sin(w) + 0.5 * Math.sin(3 * w) * Math.exp(-t * 18) }
                4 -> Math.sin(w) * Math.exp(-t * 4.5) + 0.3 * Math.sin(4 * w) * Math.exp(-t * 12)
                5 -> Math.sin(w) * Math.exp(-t * 3.0) + 0.3 * Math.sin(2 * w) * Math.exp(-t * 5.0)
                6 -> { val g = 0.5 + 0.5 * Math.tanh(4 * Math.sin(2 * Math.PI * 20 * t)); 0.5 * (Math.sin(2 * Math.PI * 1050 * t) + Math.sin(2 * Math.PI * 1330 * t)) * g }
                else -> Math.sin(w)
            }
            b[k] += (v * env * att * rel * amp).toFloat()
        }
    }

    private fun glide(b: FloatArray, t0: Double, dur: Double, f0: Double, f1: Double, amp: Double, vib: Double) {
        val s0 = (t0 * SR).toInt(); val n = (dur * SR).toInt(); var ph = 0.0
        for (i in 0 until n) {
            val k = s0 + i; if (k < 0 || k >= b.size) break
            val t = i.toDouble() / SR; val u = t / dur
            val f = (f0 + (f1 - f0) * u) * (1.0 + vib * Math.sin(2 * Math.PI * 5.0 * t))
            ph += 2 * Math.PI * f / SR
            val env = Math.pow(Math.sin(Math.PI * u), 2.0)
            b[k] += ((Math.sin(ph) + 0.2 * Math.sin(2 * ph)) * env * amp).toFloat()
        }
    }

    private fun seq(b: FloatArray, start: Double, step: Double, dur: Double, amp: Double, kind: Int, fr: DoubleArray) {
        for (i in fr.indices) note(b, start + i * step, dur, fr[i], amp, kind)
    }

    private fun period(id: String) = when (id) {
        "m1" -> 4.5; "m2" -> 3.2; "m3" -> 3.0; "m4" -> 2.8; "m5" -> 3.4; "m6" -> 5.0; "m7" -> 4.2; "m8" -> 3.6
        "w1" -> 5.0; "w2" -> 3.5; "w3" -> 4.0; "w4" -> 4.0
        else -> 3.0
    }

    /** WAV (моно, 16 бит, 44,1 кГц) одного периода мелодии с паузой для зацикливания. */
    fun wav(id: String): ByteArray {
        val b = FloatArray((period(id) * SR).toInt())
        when (id) {
            "m1" -> { seq(b, 0.0, 0.32, 1.4, 0.5, 0, doubleArrayOf(659.25, 783.99, 1046.5, 783.99)); seq(b, 1.9, 0.32, 1.5, 0.5, 0, doubleArrayOf(659.25, 783.99, 1046.5)) }
            "m2" -> { seq(b, 0.0, 0.13, 0.9, 0.5, 5, doubleArrayOf(523.25, 659.25, 783.99, 1046.5, 1318.5, 1046.5, 783.99, 659.25)); seq(b, 1.5, 0.13, 0.9, 0.5, 5, doubleArrayOf(587.33, 698.46, 880.0, 1174.7, 880.0)) }
            "m3" -> { note(b, 0.0, 0.4, 1.0, 0.5, 6); note(b, 0.55, 0.4, 1.0, 0.5, 6) }
            "m4" -> { seq(b, 0.0, 0.14, 0.11, 0.5, 2, doubleArrayOf(880.0, 1046.5, 1318.5, 1046.5, 880.0, 1318.5)); seq(b, 1.3, 0.14, 0.11, 0.5, 2, doubleArrayOf(880.0, 1046.5, 1318.5)) }
            "m5" -> { seq(b, 0.0, 0.18, 0.5, 0.5, 3, doubleArrayOf(392.0, 523.25, 659.25, 783.99, 659.25, 523.25)); seq(b, 1.6, 0.18, 0.5, 0.5, 3, doubleArrayOf(783.99, 659.25, 523.25, 392.0)) }
            "m6" -> { seq(b, 0.0, 0.5, 1.4, 0.5, 1, doubleArrayOf(523.25, 587.33, 659.25, 783.99, 880.0)); note(b, 2.6, 2.0, 1046.5, 0.5, 1) }
            "m7" -> seq(b, 0.0, 0.28, 1.0, 0.5, 4, doubleArrayOf(1318.5, 987.77, 1046.5, 1174.7, 1046.5, 987.77, 880.0, 987.77))
            "m8" -> { glide(b, 0.0, 1.2, 392.0, 587.33, 0.5, 0.02); glide(b, 1.2, 1.2, 587.33, 392.0, 0.5, 0.02) }
            "w1" -> note(b, 0.0, 1.0, 425.0, 0.5, 9)
            "w2" -> { note(b, 0.0, 0.4, 425.0, 0.5, 9); note(b, 0.6, 0.4, 425.0, 0.5, 9) }
            "w3" -> { note(b, 0.0, 1.5, 783.99, 0.5, 0); note(b, 0.35, 1.5, 987.77, 0.5, 0) }
            "w4" -> glide(b, 0.0, 1.4, 350.0, 470.0, 0.5, 0.0)
            else -> {}
        }
        var pk = 0.0; for (v in b) pk = maxOf(pk, Math.abs(v.toDouble()))
        val sc = if (pk > 0) 0.85 / pk else 1.0
        val n = b.size * 2
        val o = ByteArrayOutputStream(44 + n)
        fun i32(v: Int) { o.write(v and 255); o.write((v shr 8) and 255); o.write((v shr 16) and 255); o.write((v shr 24) and 255) }
        fun i16(v: Int) { o.write(v and 255); o.write((v shr 8) and 255) }
        o.write("RIFF".toByteArray()); i32(36 + n); o.write("WAVE".toByteArray())
        o.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(SR); i32(SR * 2); i16(2); i16(16)
        o.write("data".toByteArray()); i32(n)
        for (v in b) i16((Math.max(-1.0, Math.min(1.0, v * sc)) * 32767).toInt())
        return o.toByteArray()
    }
}

object Ringtones {
    val RING_IDS = listOf("m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "file", "off")
    val WAIT_IDS = listOf("w1", "w2", "w3", "w4", "off")

    class Cfg(
        var video: String = "m2", var audio: String = "m1", var wait: String = "w1", var vol: Int = 70,
        var videoFile: String = "", var videoFileName: String = "", var audioFile: String = "", var audioFileName: String = "",
    )

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("ringtone", Context.MODE_PRIVATE)

    fun cfg(ctx: Context): Cfg {
        val s = sp(ctx)
        val c = Cfg(s.getString("video", "m2") ?: "m2", s.getString("audio", "m1") ?: "m1", s.getString("wait", "w1") ?: "w1", s.getInt("vol", 70),
            s.getString("videoFile", "") ?: "", s.getString("videoFileName", "") ?: "", s.getString("audioFile", "") ?: "", s.getString("audioFileName", "") ?: "")
        c.vol = c.vol.coerceIn(0, 100)
        if (c.video !in RING_IDS) c.video = "m2"
        if (c.audio !in RING_IDS) c.audio = "m1"
        if (c.wait !in WAIT_IDS) c.wait = "w1"
        return c
    }

    fun save(ctx: Context, c: Cfg) {
        sp(ctx).edit().putString("video", c.video).putString("audio", c.audio).putString("wait", c.wait).putInt("vol", c.vol.coerceIn(0, 100))
            .putString("videoFile", c.videoFile).putString("videoFileName", c.videoFileName).putString("audioFile", c.audioFile).putString("audioFileName", c.audioFileName).apply()
    }

    fun label(ctx: Context, id: String, fileName: String = ""): String = when (id) {
        "m1" -> ctx.getString(R.string.ring_m1); "m2" -> ctx.getString(R.string.ring_m2); "m3" -> ctx.getString(R.string.ring_m3); "m4" -> ctx.getString(R.string.ring_m4)
        "m5" -> ctx.getString(R.string.ring_m5); "m6" -> ctx.getString(R.string.ring_m6); "m7" -> ctx.getString(R.string.ring_m7); "m8" -> ctx.getString(R.string.ring_m8)
        "w1" -> ctx.getString(R.string.ring_w1); "w2" -> ctx.getString(R.string.ring_w2); "w3" -> ctx.getString(R.string.ring_w3); "w4" -> ctx.getString(R.string.ring_w4)
        "file" -> if (fileName.isNotBlank()) ctx.getString(R.string.ring_file_named, fileName) else ctx.getString(R.string.ring_file)
        else -> ctx.getString(R.string.ring_off)
    }

    /** Файл для проигрывания; null = «Без звука». Встроенные мелодии создаются при первом обращении в кэше программы. */
    fun path(ctx: Context, id: String, kind: String, c: Cfg): File? {
        if (id == "off" || id.isBlank()) return null
        var use = id
        if (use == "file") {
            val f = File(if (kind == "audio") c.audioFile else c.videoFile)
            if (f.isFile && f.length() > 0) return f
            use = if (kind == "audio") "m1" else "m2"   // файл пропал: мелодия по умолчанию, а не тишина
        }
        val dir = File(ctx.applicationContext.cacheDir, "ringtones").apply { mkdirs() }
        val f = File(dir, use + "_v1.wav")
        if (!f.isFile || f.length() < 1000) f.writeBytes(RingSynth.wav(use))
        return f
    }

    // ---------------------------------------------------------------- проигрывание
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var player: MediaPlayer? = null
    @Volatile var playing: Boolean = false
        private set

    fun stop() {
        main.post {
            runCatching { player?.stop() }; runCatching { player?.release() }
            player = null; playing = false
        }
    }

    /** По кругу, пока не вызван stop(); onFail — файл не открылся (играет встроенная мелодия вместо него). */
    fun play(ctx: Context, file: File, vol: Int, fallback: File? = null, onFail: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        main.post {
            runCatching { player?.stop() }; runCatching { player?.release() }
            player = null
            val mp = MediaPlayer()
            player = mp
            val v = vol.coerceIn(0, 100) / 100f
            runCatching {
                mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                mp.setDataSource(file.absolutePath)
                mp.isLooping = true
                mp.setVolume(v, v)
                mp.setOnPreparedListener { if (player === it) { it.start(); playing = true } }
                mp.setOnErrorListener { m, _, _ ->
                    runCatching { m.release() }
                    if (player === m) {
                        player = null; playing = false
                        onFail?.invoke()
                        if (fallback != null && fallback != file) play(app, fallback, vol)
                    }
                    true
                }
                mp.prepareAsync()
            }.onFailure {
                android.util.Log.w("TvoCall", "ring play: $it")
                runCatching { mp.release() }; player = null; playing = false
                onFail?.invoke()
                if (fallback != null && fallback != file) play(app, fallback, vol)
            }
        }
    }

    /** Входящий звонок: мелодия по типу звонка; при беззвучном режиме и «Без звука» не играет. */
    fun playRing(ctx: Context, voice: Boolean) {
        val app = ctx.applicationContext
        runCatching {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            val c = cfg(app); val kind = if (voice) "audio" else "video"
            val id = if (voice) c.audio else c.video
            val f = path(app, id, kind, c) ?: return
            val builtin = path(app, if (voice) "m1" else "m2", kind, Cfg())
            play(app, f, c.vol, builtin) { android.util.Log.i("TvoCall", "ring file failed, built-in tune") }
            android.util.Log.i("TvoCall", "ring: $kind=$id vol=${c.vol}")
        }.onFailure { android.util.Log.w("TvoCall", "playRing: $it") }
    }

    /** Свой файл: копия в память программы (расширение сохраняется). Возвращает путь или null. */
    fun importFile(ctx: Context, uri: android.net.Uri, kind: String): Pair<String, String>? = runCatching {
        val app = ctx.applicationContext
        var name = "ringtone"
        app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
        val ext = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "bin" }
        val dir = File(app.filesDir, "ringtones").apply { mkdirs() }
        val dst = File(dir, "${kind}_own.$ext")
        dir.listFiles()?.filter { it.name.startsWith("${kind}_own.") && it != dst }?.forEach { it.delete() }
        app.contentResolver.openInputStream(uri)!!.use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        if (dst.length() <= 0) { dst.delete(); null } else dst.absolutePath to name
    }.getOrNull()
}
