package app.adbphon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.PathParser
import java.util.zip.ZipFile

/**
 * 16.0 код 79: рисует значок, найденный ApkIconRes (картинка, цвет, слои, вектор, адаптивный значок), в квадратный Bitmap.
 * Нужен, когда Android сам значок из APK не отдал. Адаптивный значок рисуется как у системы: слои 108x108, видна середина 72x72, углы скруглены.
 */
object ApkIconDraw {
    private class Cnt { var painted = 0 }

    /** null: ничего нарисовать не удалось (причина в журнал пишет вызывающий). */
    fun render(z: ZipFile, d: ApkIconRes.D, size: Int = 96): Bitmap? = render(ApkIconRes.ZipSrc(z), d, size)

    fun render(z: ApkIconRes.ApkSrc, d: ApkIconRes.D, size: Int = 96): Bitmap? {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val n = Cnt()
        if (d is ApkIconRes.D.Adaptive) {
            val big = size * 2
            val layer = Bitmap.createBitmap(big, big, Bitmap.Config.ARGB_8888)
            val lc = Canvas(layer)
            val r = RectF(0f, 0f, big.toFloat(), big.toFloat())
            d.bg?.let { draw(z, it, lc, r, n) }
            d.fg?.let { draw(z, it, lc, r, n) }
            if (n.painted == 0) return null
            val mask = Path().apply { addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.26f, size * 0.26f, Path.Direction.CW) }
            c.save(); c.clipPath(mask)
            val a = (big / 6)          // видна середина: 72 из 108 единиц
            c.drawBitmap(layer, Rect(a, a, big - a, big - a), RectF(0f, 0f, size.toFloat(), size.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            c.restore()
        } else draw(z, d, c, RectF(0f, 0f, size.toFloat(), size.toFloat()), n)
        return if (n.painted > 0) out else null
    }

    private fun draw(z: ApkIconRes.ApkSrc, d: ApkIconRes.D, c: Canvas, r: RectF, n: Cnt) {
        when (d) {
            is ApkIconRes.D.Bmp -> decode(z, d.path, r.width().toInt())?.let { b ->
                c.drawBitmap(b, null, r, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)); n.painted++
            }
            is ApkIconRes.D.Col -> { c.drawRect(r, Paint().apply { color = d.argb }); n.painted++ }
            is ApkIconRes.D.Layers -> d.items.forEach { draw(z, it, c, r, n) }
            is ApkIconRes.D.Adaptive -> { d.bg?.let { draw(z, it, c, r, n) }; d.fg?.let { draw(z, it, c, r, n) } }
            is ApkIconRes.D.Vec -> drawVector(d.root, c, r, n)
            is ApkIconRes.D.Miss -> {}
        }
    }

    /** Картинка из архива; крупная читается уменьшенной (до 2x нужного размера). Не картинка или повреждена: null. */
    private fun decode(z: ApkIconRes.ApkSrc, path: String, want: Int): Bitmap? {
        val bytes = z.read(path, 12 * 1024 * 1024) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= want * 2 && o.outHeight / (s * 2) >= want * 2) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun withAlpha(color: Int, k: Float): Int {
        val a = ((color ushr 24) * k.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0xFFFFFF)
    }

    private fun drawVector(v: ApkIconRes.VNode, c: Canvas, r: RectF, n: Cnt) {
        val vw = (v.f["viewportWidth"] ?: v.f["width"] ?: 24f).takeIf { it > 0f } ?: 24f
        val vh = (v.f["viewportHeight"] ?: v.f["height"] ?: 24f).takeIf { it > 0f } ?: 24f
        c.save()
        c.translate(r.left, r.top)
        c.scale(r.width() / vw, r.height() / vh)
        c.clipRect(0f, 0f, vw, vh)
        val alpha = v.f["alpha"] ?: 1f
        // tint вектора: всё нарисованное перекрашивается в этот цвет (форма остаётся)
        val tint = v.c["tint"]?.takeIf { (it ushr 24) != 0 }
        val saved = if (tint != null) c.saveLayer(null, Paint().apply { colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN) }) else -1
        v.kids.forEach { node(it, c, alpha, n) }
        if (saved >= 0) c.restoreToCount(saved)
        c.restore()
    }

    private fun node(k: ApkIconRes.VNode, c: Canvas, alpha: Float, n: Cnt) {
        when (k.name) {
            "group" -> {
                c.save()
                val px = k.f["pivotX"] ?: 0f; val py = k.f["pivotY"] ?: 0f
                c.translate(k.f["translateX"] ?: 0f, k.f["translateY"] ?: 0f)
                c.translate(px, py)
                c.rotate(k.f["rotation"] ?: 0f)
                c.scale(k.f["scaleX"] ?: 1f, k.f["scaleY"] ?: 1f)
                c.translate(-px, -py)
                k.kids.forEach { node(it, c, alpha, n) }
                c.restore()
            }
            "clip-path" -> k.s["pathData"]?.let { s -> runCatching { c.clipPath(PathParser.createPathFromPathData(s)) } }
            "path" -> {
                val s = k.s["pathData"] ?: return
                val p = runCatching { PathParser.createPathFromPathData(s) }.getOrNull() ?: return
                if ((k.f["fillType"] ?: 0f) == 1f) p.fillType = Path.FillType.EVEN_ODD
                k.c["fillColor"]?.let { col ->
                    c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = withAlpha(col, (k.f["fillAlpha"] ?: 1f) * alpha) })
                    n.painted++
                }
                val sw = k.f["strokeWidth"] ?: 0f
                k.c["strokeColor"]?.takeIf { sw > 0f }?.let { col ->
                    c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; strokeWidth = sw; color = withAlpha(col, (k.f["strokeAlpha"] ?: 1f) * alpha)
                        strokeCap = when ((k.f["strokeLineCap"] ?: 0f).toInt()) { 1 -> Paint.Cap.ROUND; 2 -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT }
                        strokeJoin = when ((k.f["strokeLineJoin"] ?: 0f).toInt()) { 1 -> Paint.Join.ROUND; 2 -> Paint.Join.BEVEL; else -> Paint.Join.MITER }
                        strokeMiter = k.f["strokeMiterLimit"] ?: 4f
                    })
                    n.painted++
                }
            }
        }
    }
}
