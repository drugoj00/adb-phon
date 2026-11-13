package app.adbphon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Аватарки и картинка вместо видео в окне звонка (7.6).
 * «Моя аватарка»: выбранная картинка обрезается по центру в квадрат, сжимается до 128×128 JPEG (до ~15 КБ) и хранится локально; при начале звонка уходит собеседнику
 * по каналу данных звонка (один раз). «Картинка вместо видео»: что показывать в окне, пока у собеседника нет видео: его аватарку (по умолчанию), свою картинку или только букву.
 */
object CallPics {
    private const val AV_MAX = 15_000
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("call_ui", Context.MODE_PRIVATE)
    private fun dir(c: Context) = File(c.applicationContext.filesDir, "callpics").apply { mkdirs() }

    fun mode(c: Context): String = sp(c).getString("pic_mode", "peer").let { if (it == "own" || it == "letter") it else "peer" }
    fun setMode(c: Context, m: String) { sp(c).edit().putString("pic_mode", m).apply() }

    private fun decode(open: () -> InputStream?, max: Int): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()!!.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1; while (o.outWidth / (s * 2) >= max && o.outHeight / (s * 2) >= max) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        open()!!.use { BitmapFactory.decodeStream(it, null, o2) }
    }.getOrNull()

    private fun jpeg(b: Bitmap, q: Int): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

    private fun squareAvatar(src: Bitmap): ByteArray {
        val side = minOf(src.width, src.height)
        val crop = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        var size = 128
        while (true) {
            val sc = Bitmap.createScaledBitmap(crop, size, size, true)
            var q = 85
            var bytes = jpeg(sc, q)
            while (bytes.size > AV_MAX && q > 30) { q -= 10; bytes = jpeg(sc, q) }
            if (bytes.size <= AV_MAX || size <= 80) return bytes
            size -= 16
        }
    }

    private fun saveAvatar(ctx: Context, bmp: Bitmap?): Boolean {
        if (bmp == null) return false
        val bytes = squareAvatar(bmp)
        File(dir(ctx), "avatar.jpg").writeBytes(bytes)
        sp(ctx).edit().putString("avatar_b64", Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
        return true
    }

    fun setAvatar(ctx: Context, uri: Uri): Boolean = saveAvatar(ctx, decode({ ctx.contentResolver.openInputStream(uri) }, 512))
    fun setAvatarFile(ctx: Context, f: File): Boolean = saveAvatar(ctx, decode({ f.inputStream() }, 512))
    fun avatarB64(c: Context): String = sp(c).getString("avatar_b64", "") ?: ""
    fun clearAvatar(c: Context) { sp(c).edit().remove("avatar_b64").apply(); File(dir(c), "avatar.jpg").delete() }
    fun avatarBitmap(c: Context): Bitmap? = avatarB64(c).takeIf { it.isNotEmpty() }?.let { runCatching { val b = Base64.decode(it, Base64.DEFAULT); BitmapFactory.decodeByteArray(b, 0, b.size) }.getOrNull() }

    /** Своя картинка вместо видео: до 960 пикселей по большей стороне, JPEG, хранится в памяти программы. */
    fun setPic(ctx: Context, uri: Uri): Boolean {
        val b = decode({ ctx.contentResolver.openInputStream(uri) }, 960) ?: return false
        val k = 960f / maxOf(b.width, b.height)
        val sc = if (k < 1f) Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true) else b
        File(dir(ctx), "pic.jpg").writeBytes(jpeg(sc, 82))
        sp(ctx).edit().putBoolean("pic_has", true).apply()
        return true
    }
    fun hasPic(c: Context) = sp(c).getBoolean("pic_has", false) && File(dir(c), "pic.jpg").isFile
    fun clearPic(c: Context) { sp(c).edit().remove("pic_has").apply(); File(dir(c), "pic.jpg").delete() }
    fun picDataUri(c: Context): String = if (hasPic(c)) "data:image/jpeg;base64," + Base64.encodeToString(File(dir(c), "pic.jpg").readBytes(), Base64.NO_WRAP) else ""
}
