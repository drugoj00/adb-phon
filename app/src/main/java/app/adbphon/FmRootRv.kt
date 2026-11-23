@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package app.adbphon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.asAndroidBitmap
import android.widget.ImageView
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Folder
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * 22.11.2026 (владелица: «заменить сам список на лёгкий, как в X-plore, на простых элементах вместо Compose»): список «Источников» (дерево «Файлов») на RecyclerView.
 * Строка: простые View (текст, подпись, галочка, стрелка), переиспользуются (ViewHolder), стабильные идентификаторы, перестройка списка без diff по всему списку
 * (общее начало и конец, середина заменяется). Значок строки остаётся прежним (Compose-значок в маленьком ComposeView), чтобы вид не менялся.
 * Прежний список на LazyColumn оставлен в FmRoot.kt: [FmRv.ON] = false возвращает его (запасной вариант для отката).
 */
object FmRv {
    const val ON = true
}

/** Фасад над RecyclerView с теми же понятиями, что у LazyListState (первая видимая строка и смещение), для восстановления положения. */
/** 22.11.2026 (как в X-plore: вся раскрытая ветка на одном общем, заметно более ярком фоне; уровни различаются отступом и линиями ветвей): цвет фона раскрытого блока из цвета темы. */
internal fun blockBg(dark: Boolean, grp: Int, prim: Int): Int = if (dark) 0x1AFFFFFF else 0xFFF7FFFF.toInt()
/** Закрытые строки дерева: приглушённый серо-голубой тон из цвета темы (светлая) / чуть темнее поверхности (тёмная); блок раскрытой группы заметно светлее, как в X-plore. */
internal fun closedBg(dark: Boolean, prim: Int): Int {
    if (dark) return 0x38000000
    fun mix(w: Int, c: Int) = (255 * (1 - 0.16f) + c * 0.16f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (mix(255, prim shr 16 and 255) shl 16) or (mix(255, prim shr 8 and 255) shl 8) or mix(255, prim and 255)
}
/**
 * 22.11.2026 (как в X-plore, по снимкам: оранжевый контур (225,120,0) толщиной ~2dp по границе блока, вокруг блока мягкая чёрная тень ~14dp): контур внешнего блока оранжевый,
 * вложенных блоков чёрный (только верхняя и нижняя линии, чтобы не закрывать точки «свойства» слева), тень над верхней строкой каждого блока.
 */
internal fun drawBlockFrame(c: Canvas, e: RvEnv, p: Paint, grp: Int, gTop: Int, gBot: Int, w: Float, h: Float) {
    if (grp <= 0) return
    p.style = Paint.Style.FILL
    val bw = e.dp(2f)
    // тень над верхней строкой блока (за границей строки)
    if (gTop != 0) for (j in 0 until 7) { p.color = ((0.07f * (j + 1) * 255).toInt() shl 24); c.drawRect(0f, -(7 - j) * e.dp(2f), w, -(6 - j) * e.dp(2f), p) }
    p.color = 0xFFFFC800.toInt()
    c.drawRect(0f, 0f, bw, h, p); c.drawRect(w - bw, 0f, w, h, p)
    if ((gTop shr 1) and 1 != 0) c.drawRect(0f, 0f, w, bw, p)
    if ((gBot shr 1) and 1 != 0) c.drawRect(0f, h - bw, w, h, p)
    p.color = 0xFF000000.toInt()
    val nb = e.dp(1.5f)
    for (k in 2..minOf(grp, 6)) {
        if ((gTop shr k) and 1 != 0) c.drawRect(bw, 0f, w - bw, nb, p)
        if ((gBot shr k) and 1 != 0) c.drawRect(bw, h - nb, w - bw, h, p)
    }
}

internal fun isDarkTheme(onSurface: Int): Boolean = (0.2126f * ((onSurface shr 16 and 255) / 255f) + 0.7152f * ((onSurface shr 8 and 255) / 255f) + 0.0722f * ((onSurface and 255) / 255f)) > 0.5f

internal class RvState {
    var rv: RecyclerView? = null
    private val lm get() = rv?.layoutManager as? LinearLayoutManager
    val first: Int get() = lm?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
    val last: Int get() = lm?.findLastVisibleItemPosition() ?: -1
    val firstOffset: Int get() { val l = lm ?: return 0; val v = l.findViewByPosition(first) ?: return 0; return -(v.top - (rv?.paddingTop ?: 0)).coerceAtMost(0) }
    val scrolling: Boolean get() = (rv?.scrollState ?: RecyclerView.SCROLL_STATE_IDLE) != RecyclerView.SCROLL_STATE_IDLE
    fun scrollToItem(i: Int, off: Int = 0) { lm?.scrollToPositionWithOffset(i, -off) }
    fun isVisible(i: Int) = i in first..last && last >= 0
    fun viewAt(i: Int): View? = lm?.findViewByPosition(i)
}

private class Pal(val prim: Int, val onSurface: Int, val onPrimary: Int, val error: Int, val content: Int, val arrow: Int, val line: Int, val sub: Int, val side: Int)

/** Всё, что зависит от плотности/масштаба/темы: меняется редко, при смене список перерисовывается целиком. */
internal class RvEnv(
    val p: FmPane, val ctx: Context, val tv: Boolean, val dens: Float, val fs: Float, val narrowW: Boolean,
    val parent: CompositionContext, val density: androidx.compose.ui.unit.Density, val sig: String,
    val prim: Int, val onSurface: Int, val onPrimary: Int, val error: Int, val content: Int, val arrow: Int,
) {
    fun dp(v: Float) = v * dens
    fun dp(v: Int) = v * dens
    fun sp(v: Float) = v * dens * fs
    val lineCol: Int get() = (onSurface and 0x00FFFFFF) or (((0x22 / 0xFF.toFloat() * 255).toInt().coerceIn(0, 255)) shl 24)
    fun alpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a * 255).toInt().coerceIn(0, 255) shl 24)
}

internal data class RowModel(
    val selected: Boolean, val chkOn: Boolean, val chkPart: Boolean, val hasChk: Boolean, val expanded: Boolean, val open: Boolean,
    val cnt: Pair<Int, Int>?, val cntNow: Pair<Int, Int>?, val sub: String, val title: String, val offline: Boolean, val iconKey: String?,
) {
    /** Значок, уже вычисленный при сборке описания (не входит в сравнение): привязка строки не считает его второй раз. */
    var si: StaticIcon? = null
}

internal val condN: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
internal val condB: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

private val tfCache = HashMap<String, Typeface>()
/** Размер, межстрочный интервал, трекинг и начертание как у Compose Text с темой (bodyLarge: трекинг 0,5 sp). weight: 400, 600, 700. */
internal fun TextView.applyStyle(e: RvEnv, size: Float, lineH: Float, weight: Int, color: Int) {
    val px = e.sp(size)
    setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
    letterSpacing = e.sp(0.5f) / px
    if (android.os.Build.VERSION.SDK_INT >= 28) setLineHeight(e.sp(lineH).toInt()) else setLineSpacing(0f, e.sp(lineH) / (px * 1.17f))
    val narrow = UiPrefs.narrow
    val k = (if (narrow) "n" else "d") + weight
    typeface = tfCache.getOrPut(k) {
        val base = if (narrow) (if (weight >= 700) condB else condN) else Typeface.DEFAULT
        if (android.os.Build.VERSION.SDK_INT >= 28 && weight != 700) Typeface.create(base, weight, false) else if (weight >= 700 && !narrow) Typeface.DEFAULT_BOLD else base
    }
    setTextColor(color)
}

private fun rowModel(env: RvEnv, r: RR): RowModel {
    val p = env.p; val ctx = env.ctx; val tv = env.tv
    val e0 = r.selE
    val ix = FmRootCfg.selIdx.value
    var chkBits = 0
    if (e0 != null) { val on = ix.covered(e0.key); chkBits = (if (on) 1 else 0) or (if (e0.isDir && !on && ix.partial(e0.key)) 2 else 0) }
    else if (r.selAll.isNotEmpty()) { val on = r.selAll.all { ix.covered(it.key) }; chkBits = (if (on) 1 else 0) or (if (!on && r.selAll.any { ix.covered(it.key) || ix.partial(it.key) }) 2 else 0) }
    val cnt = r.path?.let { FmRootCfg.counts[it] }
    val cKey = r.cntKey?.takeIf { FmSub.mode != FmSub.NONE }
    val cntNow: Pair<Int, Int>? = if (cKey == null) null else { val t = FolderCounts.ver; if (t >= 0) FolderCounts.peek(cKey) else null }
    val bigFont = if (tv) 1.15f else 1f
    val narrowRow = !tv && env.narrowW
    val svc = r.net?.takeIf { !r.drive && r.tnet == null }?.let { CloudIcons.serviceCaption(ctx, it) }
    val offline = r.treeId != null && (r.net != null || r.tnet != null) && r.treeId in p.rootPending && r.treeId !in p.rootOpen && !FmRootCfg.hasNetwork(ctx)
    val subDate = r.dateRaw?.takeIf { FmSub.mode != FmSub.NONE }?.let { nbBlock(FmSub.date(ctx, it, narrowRow)) }
    val subCounts = cntNow?.let { nbBlock(ctx.getString(if (narrowRow) R.string.rt_counts_s else R.string.rt_counts, it.first, it.second)) }
    val sub = listOfNotNull(
        svc, if (offline) ctx.getString(R.string.rt_parked) else null,
        if (r.total > 0) ctx.getString(R.string.rt_free, fmGb(r.free), fmGb(r.total)) else null,
        r.fs.takeIf { it.isNotBlank() }, if (r.ro) ctx.getString(R.string.rt_ro) else null, if (r.warn) ctx.getString(R.string.fm_vol_unsupported_short) else null,
        subDate, subCounts,
        r.file?.takeIf { FmSub.mode != FmSub.NONE }?.let { nbBlock(fmSize(it.size)) }, r.subText?.takeIf { it.isNotEmpty() }, r.path?.takeIf { r.net == null && r.file == null && r.treeId == null },
        cnt?.let { nbBlock(ctx.getString(R.string.rt_counts, it.first, it.second)) },
        if (r.level > 0) r.side?.takeIf { it.isNotBlank() } else null,
    ).joinToString(SUB_SEP)
    @Suppress("UNUSED_VARIABLE") val unused = bigFont
    val si0 = staticIcon(env, r, r.treeId != null && r.treeId in p.rootOpen)
    return RowModel(
        selected = p.rootSel == r.key, chkOn = (chkBits and 1) != 0, chkPart = (chkBits and 2) != 0, hasChk = e0 != null || r.selAll.isNotEmpty(),
        expanded = r.expandKey != null && r.expandKey in p.rootOpen, open = r.treeId != null && r.treeId in p.rootOpen,
        cnt = cnt, cntNow = cntNow, sub = sub, title = if (r.header == 1) r.title else if (r.err) r.title else NameWrap.fmt(r.title), offline = offline,
        iconKey = si0?.key,
    ).also { it.si = si0 }
}

// ------------------------------------------------------------------ значки без Compose (кадры прокрутки стоили вдвое дороже из-за значков в ComposeView)

/** Готовые растровые значки: рисуются тем же кодом, что и значки Compose (папка, значки типов файлов), один раз на вид и размер. */
internal object IconBmp {
    private val cache = android.util.LruCache<String, android.graphics.Bitmap>(160)
    fun get(key: String, px: Int, draw: DrawScope.() -> Unit): android.graphics.Bitmap {
        cache.get(key)?.let { return it }
        val ib = androidx.compose.ui.graphics.ImageBitmap(px, px)
        val canvas = androidx.compose.ui.graphics.Canvas(ib)
        androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(androidx.compose.ui.unit.Density(1f), androidx.compose.ui.unit.LayoutDirection.Ltr, canvas, Size(px.toFloat(), px.toFloat()), draw)
        val b = ib.asAndroidBitmap(); cache.put(key, b); return b
    }
}

internal class StaticIcon(val key: String, val draw: DrawScope.() -> Unit, val bmp: android.graphics.Bitmap? = null, val loadPath: String? = null)

/** Значок строки, который не меняется со временем (обычная папка, значок типа файла без миниатюры): рисуется один раз в картинку. Остальные значки остаются в Compose. */
private fun staticIcon(e: RvEnv, r: RR, open: Boolean): StaticIcon? {
    if (r.header != 0 || r.appName != null) return null
    if (CustomIcons.specNow(e.ctx, "d:" + r.key) != null) return null
    val px = e.dp(if (e.tv) 32 else 28).toInt()
    fun folder(ci: Int): StaticIcon { val c = ci.coerceIn(0, FOLDER_COLORS.size - 1); return StaticIcon("D|$c|$open|$px", { val (top, bot) = FOLDER_COLORS[c]; drawFolderG(top, bot, open) }) }
    if (r.drive) return folder(UiPrefs.folderDefault)
    val tid = r.treeId
    if (r.icon == Icons.Filled.Folder && r.tint == Color(0xFFFFB74D) && tid != null && tid.startsWith("L|") && r.file == null) {
        val full = tid.removePrefix("L|")
        if (appFolderPkg(full.substringBeforeLast('/', ""), full.substringAfterLast('/')) != null) return null
        val fk = folderKey(e.p, full)
        if (CustomIcons.specNow(e.ctx, "f:$fk") != null) return null
        return folder(UiPrefs.folderColors[fk] ?: UiPrefs.folderDefault)
    }
    val f = r.file
    if (f != null) {
        val ext = f.ext; val cat = fileCat(ext)
        // в дереве у фото и видео миниатюр нет (нет локального пути), у APK по сети значок приложения не грузится: обычный значок типа
        if (cat == FCat.APK) {
            val lp = r.lpath
            if (CustomIcons.specForNow(e.ctx, ext, lp, cat, fileOnly = UiPrefs.apkIcons) != null) return null
            if (lp != null && UiPrefs.apkIcons) {
                val b = Thumbs.peek(Thumbs.apkKey(lp))
                return StaticIcon(key = "A|$lp|${b != null}|$px", draw = { drawGlyphG(cat) }, bmp = b, loadPath = if (b == null) lp else null)
            }
            return StaticIcon("G|$cat|$px", { drawGlyphG(cat) })
        }
        if (CustomIcons.specForNow(e.ctx, ext, r.lpath, cat, fileOnly = false) != null) return null
        if (ApkInstall.iconRef(e.ctx, ext) != null) return null
        return StaticIcon("G|$cat|$px", { drawGlyphG(cat) })
    }
    // значки групп и узлов (Память, Root, LAN, Облака...): векторный значок Material, тот же, что рисует Compose Icon, один раз в картинку
    if (r.gname == null && (r.net == null || r.wifi) && vectorSimple(r.icon)) {
        val col = if (r.warn || r.err) Color(e.error) else r.tint
        val iv = r.icon
        return StaticIcon("V|${System.identityHashCode(iv)}|${col.value}|$px", { drawVec(iv, col) })
    }
    return null
}

internal fun vectorSimple(iv: ImageVector): Boolean {
    fun ok(g: VectorGroup): Boolean {
        if (g.rotation != 0f || g.translationX != 0f || g.translationY != 0f || g.scaleX != 1f || g.scaleY != 1f) return false
        for (c in g) { if (c is VectorGroup) { if (!ok(c)) return false } else if (c is VectorPath) { if (c.fill == null) return false } }
        return true
    }
    return ok(iv.root)
}

internal fun DrawScope.drawVec(iv: ImageVector, tint: Color) {
    val sx = size.width / iv.viewportWidth; val sy = size.height / iv.viewportHeight
    scale(sx, sy, Offset.Zero) {
        fun walk(g: VectorGroup) {
            for (c in g) {
                if (c is VectorGroup) walk(c)
                else if (c is VectorPath) { val path = c.pathData.toPath(); path.fillType = c.pathFillType; drawPath(path, tint, alpha = c.fillAlpha) }
            }
        }
        walk(iv.root)
    }
}

internal fun DrawScope.drawFolderG(top: Color, bot: Color, open: Boolean) {
    val w = size.width; val h = size.height
    val r = CornerRadius(w * 0.09f)
    val shadow = Color.Black.copy(alpha = 0.18f)
    val back = androidx.compose.ui.graphics.Path().apply {
        moveTo(w * 0.04f, h * 0.20f); lineTo(w * 0.36f, h * 0.20f); lineTo(w * 0.45f, h * 0.31f); lineTo(w * 0.96f, h * 0.31f); lineTo(w * 0.96f, h * 0.86f); lineTo(w * 0.04f, h * 0.86f); close()
    }
    val backBrush = Brush.verticalGradient(listOf(bot, bot.copy(alpha = 0.9f)), 0f, h)
    val front = if (open) androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.14f, h * 0.42f); lineTo(w * 1.0f, h * 0.42f); lineTo(w * 0.86f, h * 0.88f); lineTo(w * 0.0f, h * 0.88f); close() } else null
    val frontBrush = if (open) Brush.verticalGradient(listOf(top, bot), h * 0.42f, h * 0.88f) else Brush.verticalGradient(listOf(top, bot), h * 0.38f, h * 0.88f)
    val shine = Color.White.copy(alpha = 0.28f)
    drawRoundRect(shadow, Offset(w * 0.05f, h * 0.30f), Size(w * 0.92f, h * 0.66f), r)
    drawPath(back, backBrush)
    if (front != null) drawPath(front, frontBrush)
    else {
        drawRoundRect(frontBrush, Offset(0f, h * 0.38f), Size(w, h * 0.50f), r)
        drawRoundRect(shine, Offset(w * 0.04f, h * 0.40f), Size(w * 0.92f, h * 0.05f), CornerRadius(w * 0.02f))
    }
}

internal fun DrawScope.drawGlyphG(cat: FCat) {
    val ink = Color.Black.copy(alpha = 0.22f)
    val w = this.size.width; val h = this.size.height
    fun docPage(c1: Color, c2: Color) {
        val p = androidx.compose.ui.graphics.Path().apply { moveTo(w * .18f, h * .04f); lineTo(w * .62f, h * .04f); lineTo(w * .86f, h * .28f); lineTo(w * .86f, h * .96f); lineTo(w * .18f, h * .96f); close() }
        drawPath(p, Brush.verticalGradient(listOf(c1, c2)))
        drawPath(p, ink, style = Stroke(w * .02f))
        val fold = androidx.compose.ui.graphics.Path().apply { moveTo(w * .62f, h * .04f); lineTo(w * .62f, h * .28f); lineTo(w * .86f, h * .28f); close() }
        drawPath(fold, Color.White.copy(alpha = .55f))
    }
    fun letter(t: String, col: Color = Color.White, k: Float = .42f) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = col.toArgb(); textSize = h * k; isFakeBoldText = true; textAlign = android.graphics.Paint.Align.CENTER }
        drawContext.canvas.nativeCanvas.drawText(t, w * .52f, h * .72f, paint)
    }
    when (cat) {
        FCat.AUDIO -> {
            val g = Brush.verticalGradient(listOf(Color(0xFF42A5F5), Color(0xFF1565C0)))
            drawOval(g, Offset(w * .14f, h * .60f), Size(w * .34f, h * .28f))
            drawRect(g, Offset(w * .42f, h * .14f), Size(w * .08f, h * .62f))
            val flag = androidx.compose.ui.graphics.Path().apply { moveTo(w * .50f, h * .14f); cubicTo(w * .70f, h * .20f, w * .84f, h * .32f, w * .80f, h * .56f); cubicTo(w * .74f, h * .42f, w * .62f, h * .38f, w * .50f, h * .36f); close() }
            drawPath(flag, g)
        }
        FCat.VIDEO -> {
            val g = Brush.linearGradient(listOf(Color(0xFF3F51B5), Color(0xFF7E57C2)), Offset(0f, 0f), Offset(w, h))
            drawRoundRect(g, Offset(w * .04f, h * .16f), Size(w * .92f, h * .68f), CornerRadius(w * .1f))
            for (i in 0 until 5) { drawRect(Color.White.copy(alpha = .8f), Offset(w * (.10f + i * .17f), h * .20f), Size(w * .09f, h * .07f)); drawRect(Color.White.copy(alpha = .8f), Offset(w * (.10f + i * .17f), h * .73f), Size(w * .09f, h * .07f)) }
            val tri = androidx.compose.ui.graphics.Path().apply { moveTo(w * .40f, h * .34f); lineTo(w * .40f, h * .66f); lineTo(w * .68f, h * .50f); close() }
            drawPath(tri, Color.White)
        }
        FCat.PHOTO -> {
            val cols = listOf(Color(0xFFEF5350), Color(0xFFFFB300), Color(0xFF66BB6A), Color(0xFF29B6F6))
            val cx = w * .5f; val cy = h * .5f; val r = w * .42f
            for (i in 0 until 4) {
                rotate(i * 90f, Offset(cx, cy)) {
                    val p = androidx.compose.ui.graphics.Path().apply { moveTo(cx, cy); cubicTo(cx - r * .55f, cy - r * .1f, cx - r * .6f, cy - r * .95f, cx, cy - r); cubicTo(cx + r * .2f, cy - r * .6f, cx + r * .1f, cy - r * .2f, cx, cy); close() }
                    drawPath(p, cols[i].copy(alpha = .92f))
                }
            }
        }
        FCat.APK -> { docPage(Color(0xFFA5D66B), Color(0xFF558B2F)); drawCircle(Color.White, w * .12f, Offset(w * .52f, h * .6f)) }
        FCat.TEXT, FCat.CODE -> {
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF81D4FA), Color(0xFF0288D1))), Offset(w * .16f, h * .12f), Size(w * .68f, h * .82f), CornerRadius(w * .06f))
            drawRoundRect(Color.White, Offset(w * .22f, h * .24f), Size(w * .56f, h * .64f), CornerRadius(w * .03f))
            for (i in 0 until 4) drawRect(Color(0xFF90A4AE), Offset(w * .28f, h * (.34f + i * .13f)), Size(w * (if (i == 3) .26f else .44f), h * .035f))
            for (i in 0 until 4) drawRoundRect(Color(0xFF37474F), Offset(w * (.24f + i * .15f), h * .06f), Size(w * .06f, h * .14f), CornerRadius(w * .03f))
            if (cat == FCat.CODE) { drawRect(Color(0xFF0288D1), Offset(w * .28f, h * .72f), Size(w * .2f, h * .035f)) }
        }
        FCat.PDF -> { docPage(Color(0xFFEF5350), Color(0xFFC62828)); drawRect(Color(0xFFB71C1C), Offset(w * .18f, h * .50f), Size(w * .68f, h * .26f)); letter("PDF", Color.White, .21f) }
        FCat.DOC -> { docPage(Color(0xFF4A90E2), Color(0xFF1B58B8)); letter("W") }
        FCat.XLS -> { docPage(Color(0xFF3FB36A), Color(0xFF1B7A43)); letter("X") }
        FCat.PPT -> { docPage(Color(0xFFFF8A50), Color(0xFFD83B1C)); letter("P") }
        FCat.ARCHIVE -> { docPage(Color(0xFFD7B27A), Color(0xFF8D6E63)); for (i in 0 until 5) drawRect(if (i % 2 == 0) Color(0xFF5D4037) else Color.White, Offset(w * .46f, h * (.30f + i * .1f)), Size(w * .12f, h * .06f)) }
        else -> docPage(Color(0xFFCFD8DC), Color(0xFF78909C))
    }
}

// ------------------------------------------------------------------ мелкие View

/** Шеврон группы (Material ExpandMore/ChevronRight) и закрашенная стрелка папки. */
internal class GlyphView(ctx: Context) : View(ctx) {
    var mode = 0   // 1 шеврон вправо, 2 шеврон вниз, 3 треугольник закрытый, 4 треугольник раскрытый, 5 нет
    var color = 0
    var sizePx = 0f   // сторона рисунка внутри поля
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun onDraw(c: Canvas) {
        if (mode == 0 || mode == 5) return
        paint.color = color; paint.style = Paint.Style.FILL
        val cx = width / 2f; val cy = height / 2f
        path.reset()
        when (mode) {
            1 -> { val s = sizePx / 24f; val ox = cx - sizePx / 2; val oy = cy - sizePx / 2
                path.moveTo(ox + 10 * s, oy + 6 * s); path.lineTo(ox + 8.59f * s, oy + 7.41f * s); path.lineTo(ox + 13.17f * s, oy + 12 * s); path.lineTo(ox + 8.59f * s, oy + 16.59f * s); path.lineTo(ox + 10 * s, oy + 18 * s); path.lineTo(ox + 16 * s, oy + 12 * s); path.close() }
            2 -> { val s = sizePx / 24f; val ox = cx - sizePx / 2; val oy = cy - sizePx / 2
                path.moveTo(ox + 16.59f * s, oy + 8.59f * s); path.lineTo(ox + 12 * s, oy + 13.17f * s); path.lineTo(ox + 7.41f * s, oy + 8.59f * s); path.lineTo(ox + 6 * s, oy + 10 * s); path.lineTo(ox + 12 * s, oy + 16 * s); path.lineTo(ox + 18 * s, oy + 10 * s); path.close() }
            3 -> { val w = sizePx; val ox = cx - w / 2; val oy = cy - w / 2
                path.moveTo(ox + w * .15f, oy); path.lineTo(ox + w * .15f, oy + w); path.lineTo(ox + w, oy + w / 2); path.close() }
            4 -> { val w = sizePx; val ox = cx - w / 2; val oy = cy - w / 2
                path.moveTo(ox, oy + w * .2f); path.lineTo(ox + w, oy + w * .2f); path.lineTo(ox + w / 2, oy + w * .9f); path.close() }
        }
        c.drawPath(path, paint)
    }
}

/** Квадратик отметки (как SelBox): без скругления, отмечено / частично / пусто. */
internal class CheckView(ctx: Context) : View(ctx) {
    var on = false; var part = false
    var boxPx = 0f; var strokePx = 0f; var prim = 0; var onSurface = 0; var onPrimary = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun onDraw(c: Canvas) {
        val sz = boxPx
        val l = width - sz; val t = (height - sz) / 2f
        val filled = on || part
        if (on) { paint.style = Paint.Style.FILL; paint.color = prim; c.drawRect(l, t, l + sz, t + sz, paint) }
        else if (part) { paint.style = Paint.Style.FILL; paint.color = (prim and 0x00FFFFFF) or (0x73 shl 24); c.drawRect(l, t, l + sz, t + sz, paint) }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = strokePx
        paint.color = if (filled) prim else (onSurface and 0x00FFFFFF) or (0xB3 shl 24)
        c.drawRect(l + strokePx / 2, t + strokePx / 2, l + sz - strokePx / 2, t + sz - strokePx / 2, paint)
        paint.style = Paint.Style.FILL; paint.color = onPrimary
        if (on) {
            val ic = sz - strokePx * 2; val s = ic / 24f; val ox = l + (sz - ic) / 2; val oy = t + (sz - ic) / 2
            path.reset(); path.moveTo(ox + 9 * s, oy + 16.17f * s); path.lineTo(ox + 4.83f * s, oy + 12 * s); path.lineTo(ox + 3.41f * s, oy + 13.41f * s); path.lineTo(ox + 9 * s, oy + 19 * s)
            path.lineTo(ox + 21 * s, oy + 7 * s); path.lineTo(ox + 19.59f * s, oy + 5.59f * s); path.close(); c.drawPath(path, paint)
        } else if (part) {
            val bw = sz * 0.55f; val bh = strokePx * 1.5f
            c.drawRoundRect(l + (sz - bw) / 2, t + (sz - bh) / 2, l + (sz + bw) / 2, t + (sz + bh) / 2, bh / 2, bh / 2, paint)
        }
    }
}

// ------------------------------------------------------------------ строка

internal class RvRow(val env0: () -> RvEnv, ctx: Context, val hostKeys: RvHost) : FrameLayout(ctx) {
    private val content = LinearLayout(ctx)
    private val chevron = GlyphView(ctx)
    private val tri = GlyphView(ctx)
    private var spinner: android.widget.ProgressBar? = null
    private var iconHost: ComposeView? = null
    private val iconImg = ImageView(ctx)
    private val iconState = mutableStateOf<RR?>(null)
    private val textCol = LinearLayout(ctx)
    private val title = TextView(ctx)
    private val sub = TextView(ctx)
    private var barHost: ComposeView? = null
    private val barState = mutableStateOf<RR?>(null)
    private var dotHost: ComposeView? = null
    private val dotState = mutableStateOf<RR?>(null)
    private var tailHost: ComposeView? = null
    private val tailState = mutableStateOf<RR?>(null)
    private val chk = CheckView(ctx)
    val zone = View(ctx)
    private val titleRow = LinearLayout(ctx)
    var r: RR? = null
    var m: RowModel? = null
    private var envSig = ""
    var countsJob: Job? = null
    private var iconJob: Job? = null
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint().apply { isAntiAlias = false }
    private val rect = RectF()
    private var dotDown = false
    private var zoneFocused = false
    private var hadFocus = false

    init {
        setWillNotDraw(false)
        isFocusable = true; isClickable = true; isLongClickable = true
        isContextClickable = true
        content.orientation = LinearLayout.HORIZONTAL; content.gravity = Gravity.CENTER_VERTICAL
        content.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        title.maxLines = 3; title.ellipsize = TextUtils.TruncateAt.END; title.includeFontPadding = false
        sub.maxLines = 3; sub.ellipsize = TextUtils.TruncateAt.END; sub.includeFontPadding = false
        titleRow.orientation = LinearLayout.HORIZONTAL; titleRow.gravity = Gravity.CENTER_VERTICAL
        titleRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        textCol.orientation = LinearLayout.VERTICAL
        textCol.addView(titleRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        textCol.addView(sub, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(chevron); content.addView(tri); content.addView(iconImg)
        content.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(chk)
        addView(content)
        zone.isFocusable = true; zone.isClickable = true; zone.isLongClickable = true; zone.isContextClickable = true
        addView(zone, LayoutParams(0, LayoutParams.MATCH_PARENT, Gravity.END))
        iconImg.scaleType = ImageView.ScaleType.FIT_XY; iconImg.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        chevron.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        tri.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setOnClickListener {
            val rr = r ?: return@setOnClickListener; val e = env0()
            if (flipArrow(rr)) postDelayed({ rootRowClick(e.p, rr, e.ctx) { hostKeys.openMenu(this, rr) } }, 20)   // стрелка меняется сразу, тяжёлая перестройка списка — следующим кадром
            else rootRowClick(e.p, rr, e.ctx) { hostKeys.openMenu(this, rr) }
        }
        setOnLongClickListener { val rr = r ?: return@setOnLongClickListener false; val e = env0(); Fm.active = e.p.id; e.p.rootSel = rr.key; hostKeys.openMenu(this, rr); true }
        setOnContextClickListener { val rr = r ?: return@setOnContextClickListener false; val e = env0(); Fm.active = e.p.id; e.p.rootSel = rr.key; hostKeys.openMenu(this, rr); true }
        setOnFocusChangeListener { _, f -> hadFocus = f; val rr = r; if (f && rr != null) FmRootCfg.noteRow(env0().p.id, rr.key, rootRowNode(rr), true); invalidate() }
        zone.setOnClickListener { chkClick() }
        zone.setOnLongClickListener { val rr = r ?: return@setOnLongClickListener false; val e = env0(); Fm.active = e.p.id; e.p.rootSel = rr.key; hostKeys.openMenu(this, rr); true }
        zone.setOnFocusChangeListener { _, f -> zoneFocused = f; invalidate() }
        tri.setOnClickListener { val rr = r ?: return@setOnClickListener; val id = rr.treeId ?: return@setOnClickListener; val e = env0(); flipArrow(rr); postDelayed({ rootRowToggle(e.p, rr, e.ctx, id) }, 20) }
        tri.isClickable = false
        val kl = View.OnKeyListener { v, code, ev -> onKey(v, code, ev) }
        setOnKeyListener(kl); zone.setOnKeyListener(kl)
    }

    /** Мгновенная реакция на нажатие: стрелка папки меняется до перестройки списка. Возвращает true, если нажатие раскроет или свернёт папку. */
    private fun flipArrow(rr: RR): Boolean {
        if (rr.expandKey != null || rr.treeId == null || !FmRootCfg.treeOn || rr.file != null || rr.titem != null || rr.fitem != null) return false
        if (FmRootCfg.sel.values.any { it.ti == null && it.fi == null } && rr.selE != null) return false
        if (tri.visibility != VISIBLE || tri.mode == 5) return false
        tri.mode = if (tri.mode == 3) 4 else 3; tri.invalidate(); return true
    }

    private fun chkClick() {
        val rr = r ?: return; val e = env0(); val e0 = rr.selE
        if (e0 != null) { Fm.active = e.p.id; FmRootCfg.toggleTree(e.p.id, e0) }
        else if (rr.selAll.isNotEmpty()) { Fm.active = e.p.id; FmRootCfg.setAll(e.p.id, rr.selAll, !(m?.chkOn ?: false)) }
    }

    private fun onKey(v: View, code: Int, ev: KeyEvent): Boolean {
        val rr = r ?: return false; val e = env0()
        if (ev.action != KeyEvent.ACTION_DOWN) return false
        if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP) {
            // стрелки вверх и вниз держат столбец: со строки на строку, с галочки на галочку (стандартный поиск фокуса путал их)
            val rv = parent as? RecyclerView
            val pos = rv?.getChildAdapterPosition(this) ?: -1
            if (rv != null && pos >= 0) {
                val step = if (code == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                var q = pos + step
                val ad = rv.adapter
                while (ad != null && q in 0 until ad.itemCount) {
                    val vh = rv.findViewHolderForAdapterPosition(q)
                    val row = vh?.itemView as? RvRow
                    if (row == null) { rv.scrollToPosition(q); rv.post { (rv.findViewHolderForAdapterPosition(q)?.itemView as? RvRow)?.let { if (v === zone && it.zone.visibility == VISIBLE) it.zone.requestFocus() else it.requestFocus() } }; return true }
                    if (row.isFocusable) { return if (v === zone && row.zone.visibility == VISIBLE) row.zone.requestFocus() else row.requestFocus() }
                    q += step
                }
            }
            if (!(code == KeyEvent.KEYCODE_DPAD_DOWN && FmRootCfg.sel.isNotEmpty() && rr.key == FmRootCfg.lastVisKey)) return false
        }
        if (v === zone) { return if (code == KeyEvent.KEYCODE_DPAD_LEFT) { requestFocus(); true } else false }
        if ((code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_INFO) && FmRootCfg.sel.isNotEmpty()) { return runCatching { FmRootCfg.barFocus.requestFocus() }.isSuccess }
        val id = rr.treeId
        if (code == KeyEvent.KEYCODE_DPAD_DOWN && FmRootCfg.sel.isNotEmpty() && rr.key == FmRootCfg.lastVisKey) return runCatching { FmRootCfg.barFocus.requestFocus() }.isSuccess
        val hasChk = m?.hasChk == true
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT && hasChk && (id == null || !FmRootCfg.treeOn || id in e.p.rootOpen)) return zone.requestFocus()
        if (id == null || !FmRootCfg.treeOn) return false
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT && id !in e.p.rootOpen) { rootTreeToggle(e.p, rr.key, e.ctx, id); return true }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && id in e.p.rootOpen) { rootTreeToggle(e.p, rr.key, e.ctx, id); return true }
        return false
    }

    /**
     * 23.11.2026: строка уходит в пул: Compose-вставки (значок, полоска места, точка связи, хвост) и кружок убираются из неё. Раньше они оставались внутри и при каждом повторном
     * показе строки (даже простой, файла) заново присоединялись к окну, а ComposeView при этом создаёт композицию (около 7 мс на строку на приставке).
     */
    fun releaseHosts() {
        iconHost?.let { (it.parent as? ViewGroup)?.removeView(it) }; iconHost = null; iconState.value = null
        barHost?.let { (it.parent as? ViewGroup)?.removeView(it) }; barHost = null; barState.value = null
        dotHost?.let { (it.parent as? ViewGroup)?.removeView(it) }; dotHost = null; dotState.value = null
        tailHost?.let { (it.parent as? ViewGroup)?.removeView(it) }; tailHost = null; tailState.value = null
        spinner?.let { (it.parent as? ViewGroup)?.removeView(it) }; spinner = null
    }

    private fun tail(rr: RR, e: RvEnv): ComposeView {
        var h = tailHost
        if (h == null) {
            h = ComposeView(context); h.setParentCompositionContext(env0().parent)
            h.setContent { val x = tailState.value; val en = env0(); if (x != null) androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides en.density) { RvTail(x, en.tv) } }
            h.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            tailHost = h
        }
        return h
    }

    fun bind(rr: RR, model: RowModel, e: RvEnv) {
        r = rr; m = model
        val tv = e.tv
        // сменилась плотность/масштаб/тема: пересоздавать нечего, всё применяется ниже заново
        envSig = e.sig
        countsJob?.cancel(); countsJob = null; iconJob?.cancel(); iconJob = null
        if (rr.header == 1) { bindHeader(rr, e); return }
        val lv = rr.level
        content.visibility = VISIBLE
        zone.visibility = if (model.hasChk) VISIBLE else GONE
        (zone.layoutParams as LayoutParams).width = e.dp(if (tv) 76 else 64).toInt()
        isFocusable = true
        val gap = e.dp(if (lv > 0) 5 else 8).toInt()
        content.setPadding(e.dp(TreeDim.pad + lv * TreeDim.ind).toInt(), e.dp(if (tv) 3 else if (lv > 0) 1 else 2).toInt(), e.dp(3).toInt(), e.dp(if (tv) 3 else if (lv > 0) 1 else 2).toInt())
        minimumHeight = e.dp(if (tv) 52 else if (lv > 0) 34 else 38).toInt(); content.minimumHeight = 0
        // стрелки
        chevron.visibility = GONE; tri.visibility = GONE
        val arrowPx = e.dp(TreeDim.arrow(tv)).toInt()
        if (rr.expandKey != null) {
            chevron.visibility = VISIBLE; chevron.mode = if (model.expanded) 2 else 1; chevron.color = e.alpha(e.onSurface, .7f); chevron.sizePx = e.dp(18f)
            chevron.layoutParams = LinearLayout.LayoutParams(e.dp(18).toInt(), e.dp(18).toInt()).also { it.marginEnd = gap }
        } else if (rr.treeId != null && FmRootCfg.treeOn) {
            tri.visibility = VISIBLE
            val emptyF = !model.open && model.cntNow != null && model.cntNow.first + model.cntNow.second == 0
            tri.mode = if (emptyF) 5 else if (model.open) 4 else 3; tri.color = e.arrow; tri.sizePx = e.dp(if (tv) 16 else 12)
            tri.layoutParams = LinearLayout.LayoutParams(arrowPx, arrowPx).also { it.marginEnd = gap + e.dp(TreeDim.rootSp(lv)).toInt() }
            tri.isClickable = !emptyF
        } else if (FmRootCfg.treeOn && lv > 0 && rr.header == 0) {
            tri.visibility = VISIBLE; tri.mode = 5
            tri.layoutParams = LinearLayout.LayoutParams(arrowPx, arrowPx).also { it.marginEnd = gap + e.dp(TreeDim.rootSp(lv)).toInt() }
            tri.isClickable = false
        }
        // кружок занятости
        if (rr.busy) {
            if (spinner == null) { spinner = android.widget.ProgressBar(context).also { content.addView(it, content.indexOfChild(iconImg)) } }
            spinner!!.visibility = VISIBLE; spinner!!.layoutParams = LinearLayout.LayoutParams(e.dp(14).toInt(), e.dp(14).toInt()).also { it.marginEnd = gap }
        } else spinner?.visibility = GONE
        // значок: обычная папка и значок типа файла — готовая картинка; остальное (миниатюры, значки приложений, свои значки, значки групп) — Compose
        val si = if (model.iconKey != null) (model.si ?: staticIcon(e, rr, model.open)) else null
        if (si != null) {
            val px = e.dp(if (tv) 32 else 28).toInt()
            iconImg.visibility = VISIBLE
            if (si.bmp != null) {
                iconImg.setImageBitmap(si.bmp)
                iconImg.outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, v.width * 0.2f) } }
                iconImg.clipToOutline = true
            } else { iconImg.clipToOutline = false; iconImg.setImageBitmap(IconBmp.get(si.key, px, si.draw)) }
            iconJob?.cancel(); iconJob = null
            val lp = si.loadPath
            if (lp != null) iconJob = Fm.scope.launch { delay(120); val bb = Thumbs.apkIcon(e.ctx, lp); if (bb != null && r === rr) hostKeys.rowChanged(this@RvRow) }
            iconImg.layoutParams = LinearLayout.LayoutParams(px, px).also { it.marginEnd = gap }
            iconHost?.visibility = GONE; iconState.value = null
        } else {
            iconImg.visibility = GONE
            val ih = iconHost ?: ComposeView(context).also { h ->
                h.setParentCompositionContext(env0().parent)
                h.setContent { val rr2 = iconState.value; val e2 = env0(); if (rr2 != null) androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides e2.density) { FmRootRowIcon(e2.p, rr2, e2.tv) } }
                h.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; h.descendantFocusability = FOCUS_BLOCK_DESCENDANTS; h.isFocusable = false
                iconHost = h; content.addView(h, content.indexOfChild(iconImg) + 1)
            }
            ih.visibility = VISIBLE
            iconState.value = rr
            ih.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginEnd = gap }
        }
        // текст
        val big = if (tv) 1.15f else 1f
        title.text = model.title
        if (rr.err) { title.maxLines = 9; title.applyStyle(e, 13f * big, 24f, 400, e.error) }
        else { title.maxLines = 3; title.applyStyle(e, (if (lv > 0) 14f else 15f) * big, 24f, 700, e.content) }
        val narrowRow = !tv && e.narrowW
        if (model.sub.isNotEmpty()) {
            sub.visibility = VISIBLE; sub.text = model.sub
            sub.applyStyle(e, (if (narrowRow && rr.dateRaw != null) 10f else 11f) * big, 13f * big, 600, e.alpha(e.content, .65f))
        } else sub.visibility = GONE
        // полоска занятого места
        if (rr.total > 0) {
            val b = barHost ?: ComposeView(context).also { h -> h.setParentCompositionContext(env0().parent); h.setContent { val x = barState.value; val en = env0(); if (x != null) androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides en.density) { FmSpaceBar(1f - x.free.toFloat() / x.total, Modifier.fillMaxWidth().padding(top = 1.dp)) } }; h.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; h.descendantFocusability = FOCUS_BLOCK_DESCENDANTS; h.isFocusable = false; barHost = h; textCol.addView(h, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
            b.visibility = VISIBLE; barState.value = rr
        } else barHost?.visibility = GONE
        // точка состояния подключения
        val kfs = rr.net?.let { nc -> Fm.panes.firstNotNullOfOrNull { pp -> (pp.smb as? KeepFs)?.takeIf { pp.src == "smb" && pp.net == nc } } }
        if (kfs != null) {
            val d = dotHost ?: ComposeView(context).also { h -> h.setParentCompositionContext(env0().parent); h.setContent { val x = dotState.value; val en = env0(); if (x != null) androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides en.density) { val k = x.net?.let { nc -> Fm.panes.firstNotNullOfOrNull { pp -> (pp.smb as? KeepFs)?.takeIf { pp.src == "smb" && pp.net == nc } } }; if (k != null) NetDot(k) } }; h.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; h.descendantFocusability = FOCUS_BLOCK_DESCENDANTS; h.isFocusable = false; dotHost = h; titleRow.addView(h) }
            d.visibility = VISIBLE; dotState.value = rr
        } else dotHost?.visibility = GONE
        // хвост: замок, переключатель, число справа у строк верхнего уровня
        val needTail = rr.ro || rr.tog != null || (lv == 0 && rr.side != null)
        if (needTail) {
            val t = tail(rr, e)
            if (t.parent == null) content.addView(t, content.indexOfChild(chk))
            t.visibility = VISIBLE; tailState.value = rr
            t.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginStart = gap }
        } else tailHost?.visibility = GONE
        // галочка
        if (model.hasChk) {
            chk.visibility = VISIBLE; chk.on = model.chkOn; chk.part = model.chkPart
            chk.boxPx = e.dp(if (tv) 29f else 23.5f); chk.strokePx = e.dp(2f); chk.prim = e.prim; chk.onSurface = e.onSurface; chk.onPrimary = e.onPrimary
            chk.layoutParams = LinearLayout.LayoutParams(e.dp(if (tv) 40 else 32).toInt(), e.dp(if (tv) 40 else 32).toInt()).also { it.marginStart = gap }
            chk.invalidate()
        } else chk.visibility = GONE
        // число папок и файлов: грузится, когда строка на экране
        val cKey = rr.cntKey?.takeIf { FmSub.mode != FmSub.NONE }
        val loader = rr.cntLoader
        if (cKey != null && loader != null && model.cntNow == null && !FolderCounts.has(cKey)) {
            countsJob = Fm.scope.launch { FolderCounts.load(e.ctx, cKey, rr.cntCls ?: FolderCounts.NET, true, loader); if (r === rr && FolderCounts.has(cKey)) hostKeys.rowChanged(this@RvRow) }
        }
        contentDescription = rr.title
        invalidate()
    }

    private fun bindHeader(rr: RR, e: RvEnv) {
        zone.visibility = GONE; isFocusable = false
        chevron.visibility = GONE; tri.visibility = GONE; spinner?.visibility = GONE; barHost?.visibility = GONE; dotHost?.visibility = GONE; tailHost?.visibility = GONE; chk.visibility = GONE
        iconState.value = null; iconHost?.visibility = GONE; iconImg.visibility = GONE; sub.visibility = GONE
        content.setPadding(e.dp(10).toInt(), e.dp(1).toInt(), e.dp(10).toInt(), e.dp(1).toInt()); content.minimumHeight = 0; minimumHeight = 0
        title.maxLines = 1; title.text = rr.title; title.applyStyle(e, 11f, 13f, 700, e.prim)
        invalidate()
    }


    override fun dispatchDraw(c: Canvas) { drawBack(c); super.dispatchDraw(c); drawFocus(c) }

    override fun setPressed(pressed: Boolean) { val ch = pressed != isPressed; super.setPressed(pressed); if (ch) invalidate() }

    private fun drawBack(c: Canvas) {
        val rr = r ?: return; val mm = m ?: return; val e = env0()
        if (rr.header == 1) return
        val h = height.toFloat(); val w = width.toFloat()
        val lv = rr.level
        // ветви дерева
        if (lv > 0 && rr.more.size == lv && rr.lx.size == lv) {
            linePaint.color = e.lineCol; linePaint.strokeWidth = e.dp(1f).coerceAtLeast(1f)
            val ext = if (e.tv) e.dp(2f) else 0f
            for (k in 1..lv) {
                val x = rr.lx[k - 1] * e.dens
                if (k < lv) { if (rr.more[k - 1]) c.drawLine(x, 0f, x, h + ext, linePaint) }
                else {
                    if (rr.more[k - 1]) c.drawLine(x, 0f, x, h + ext, linePaint)
                    else if (rr.half) c.drawLine(x, 0f, x, h / 2, linePaint)
                    val hasChev = rr.treeId != null && FmRootCfg.treeOn
                    if (rr.tick) c.drawLine(x, h / 2, (TreeDim.pad + TreeDim.ind * lv + (if (hasChev) (if (e.tv) 4 else 1) else 0) - 1) * e.dens, h / 2, linePaint)
                }
            }
        }
        // плашка и полоска выбора
        val blockHead = rr.grp > 0 && (rr.gTop and 2) != 0   // шапка блока (раскрытая папка) без отдельного оттенка: фон как у детей, выбор показывает только полоска слева
        val bgc = if (mm.selected && !blockHead) e.alpha(e.prim, .22f) else if (mm.chkOn && rr.selE != null) e.alpha(e.prim, .12f)
        else 0
        if (rr.grp == 0 && rr.header == 0) { bgPaint.style = Paint.Style.FILL; bgPaint.color = closedBg(isDarkTheme(e.onSurface), e.prim); c.drawRect(0f, 0f, w, h, bgPaint) }
        if (rr.grp > 0) {
            // фон раскрытого блока (папка и все её вложенные строки) СВЕТЛЕЕ обычных строк, как в X-plore: на светлой теме белый, на тёмной приподнятая поверхность
            bgPaint.style = Paint.Style.FILL; bgPaint.color = blockBg(isDarkTheme(e.onSurface), rr.grp, e.prim)
            rect.set(0f, 0f, w, h); c.drawRect(rect, bgPaint)
            drawBlockFrame(c, e, bgPaint, rr.grp, rr.gTop, rr.gBot, w, h)
        }
        if (bgc != 0) { bgPaint.style = Paint.Style.FILL; bgPaint.color = bgc; rect.set(0f, 0f, w, h); c.drawRoundRect(rect, e.dp(8f), e.dp(8f), bgPaint) }
        if (mm.selected) { bgPaint.style = Paint.Style.FILL; bgPaint.color = e.prim; c.drawRect(0f, 0f, e.dp(4f), h, bgPaint) }
        if (isPressed) { bgPaint.style = Paint.Style.FILL; bgPaint.color = e.alpha(e.onSurface, .12f); rect.set(0f, 0f, w, h); c.drawRoundRect(rect, e.dp(8f), e.dp(8f), bgPaint) }
        // точка «свойства» слева
        val e0 = rr.selE
        if (e0 != null) {
            bgPaint.style = Paint.Style.FILL; bgPaint.color = e.alpha(e.onSurface, .3f)
            val dw = e.dp(TreeDim.dotW)
            c.drawCircle(dw / 2, h / 2, e.dp(2.5f), bgPaint)
        }
    }

    private fun drawFocus(c: Canvas) {
        val rr = r ?: return; val e = env0()
        if (rr.header == 1) return
        val bw = e.dp(if (e.tv) 3 else 2)
        bgPaint.style = Paint.Style.STROKE; bgPaint.strokeWidth = bw; bgPaint.color = e.prim
        if (isFocused) { rect.set(bw / 2, bw / 2, width - bw / 2, height - bw / 2); c.drawRoundRect(rect, e.dp(8f), e.dp(8f), bgPaint) }
        if (zoneFocused) { val zw = zone.width.toFloat(); rect.set(width - zw + bw / 2, bw / 2, width - bw / 2, height - bw / 2); c.drawRoundRect(rect, e.dp(8f), e.dp(8f), bgPaint) }
    }

    /** Нажатие на точку «свойства» у левого края (отдельное от нажатия на строку). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val rr = r; val e0 = rr?.selE
        if (rr != null && e0 != null && rr.header == 0) {
            val e = env0(); val dw = e.dp(TreeDim.dotW); val dh = e.dp(32)
            val inDot = ev.x >= 0 && ev.x <= dw && ev.y >= (height - dh) / 2 && ev.y <= (height + dh) / 2
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && inDot) { dotDown = true; return true }
            if (dotDown) {
                if (ev.actionMasked == MotionEvent.ACTION_UP) { dotDown = false; if (inDot) { Fm.active = e.p.id; if (e0.net == null && e0.path.startsWith("/")) Fm.propsPath(e0.path) else Fm.dlg = FmDlg.Props(Fm.str(R.string.fm_props_title), Fm.str(R.string.fm_name) + ": " + e0.name + "\n" + Fm.str(R.string.fm_path) + ": " + e0.path + (if (e0.isDir) "\n" + Fm.str(R.string.fm_type) + ": " + Fm.str(R.string.fm_type_file).let { "—" } else "\n" + Fm.str(R.string.fm_type) + ": " + Fm.str(R.string.fm_type_file)) + "\n" + Fm.str(R.string.fm_size) + ": " + (rr.file?.takeIf { it.size >= 0 }?.let { fmSize(it.size) } ?: "—") + "\n" + Fm.str(R.string.fm_modified) + ": " + (rr.dateRaw?.takeIf { it.isNotBlank() }?.let { FmSub.date(e.ctx, it, false) } ?: "—")) } }
                else if (ev.actionMasked == MotionEvent.ACTION_CANCEL) dotDown = false
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow(); countsJob?.cancel(); countsJob = null
        val rr = r; if (hadFocus && rr != null) { hadFocus = false; FmRootCfg.focusedRowGone(env0().p.id, rr.key) }
    }
}

@Composable
private fun RvTail(r: RR, tv: Boolean) {
    val big = if (tv) 1.15f else 1f
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (r.ro) Icon(Icons.Filled.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
        r.tog?.let { on -> BigCheck(on, tv) { r.act() } }
        if (r.level == 0) r.side?.let { Text(it, fontSize = (13 * big).sp, maxLines = 1, fontFamily = UiPrefs.listFont, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f)) }
    }
}

// ------------------------------------------------------------------ адаптер

internal interface RvHost { fun openMenu(row: RvRow, r: RR); fun rowChanged(row: RvRow) }

internal class RvAdapter(var env: RvEnv, val host: RvHost) : RecyclerView.Adapter<RvAdapter.VH>() {
    class VH(val row: RvRow) : RecyclerView.ViewHolder(row)
    var rows: List<RR> = emptyList()
    private val ids = HashMap<String, Long>()
    private var nextId = 1L
    init { setHasStableIds(true) }
    override fun getItemCount() = rows.size
    override fun getItemId(position: Int): Long = ids.getOrPut(rows[position].key) { nextId++ }
    override fun getItemViewType(position: Int) = rows[position].header
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = RvRow({ env }, parent.context, host)
        v.layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
        return VH(v)
    }
    /** Что показано в строке сейчас (ключ строки -> описание): по нему видно, какие видимые строки действительно изменились. */
    private val bound = HashMap<String, RowModel>()
    // 23.11.2026: привязка строки идёт внутри раскладки Compose; без этого каждое чтение состояния (настройки, значки, подписи) записывалось в набор чтений раскладки (до 8 мс на кадр на приставке)
    override fun onBindViewHolder(h: VH, position: Int) { val r = rows.getOrNull(position) ?: return; androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { val m = rowModel(env, r); bound[r.key] = m; h.row.bind(r, m, env) } }
    override fun onViewRecycled(h: VH) {
        h.row.releaseHosts()
        h.row.countsJob?.cancel(); h.row.countsJob = null
    }
    /** Новый список строк: общее начало и конец сохраняются, середина заменяется (без полного сравнения тысяч строк). */
    fun submit(n: List<RR>, rv: RecyclerView) {
        val old = rows; rows = n
        var s = 0
        while (s < old.size && s < n.size && old[s].key == n[s].key) s++
        var eo = old.size; var en = n.size
        while (eo > s && en > s && old[eo - 1].key == n[en - 1].key) { eo--; en-- }
        val rem = eo - s; val ins = en - s
        val common = minOf(rem, ins)
        if (common > 0) notifyItemRangeChanged(s, common)
        if (rem > ins) notifyItemRangeRemoved(s + ins, rem - ins) else if (ins > rem) notifyItemRangeInserted(s + rem, ins - rem)
        // видимые строки вне изменённой середины: перерисовываются, только если описание строки изменилось (RR.equals сравнивает по значению)
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val a = lm.findFirstVisibleItemPosition(); val b = lm.findLastVisibleItemPosition()
        if (a < 0) return
        val shift = old.size - n.size
        for (i in a..minOf(b, n.size - 1)) {
            val oi = when { i < s -> i; i >= s + ins -> i + shift; else -> continue }
            val same = oi in old.indices && (old[oi] === n[i] || old[oi] == n[i])
            if (!same) notifyItemChanged(i) else lm.findViewByPosition(i)?.invalidate()   // ветви дерева читаются при отрисовке
        }
    }
    fun refreshVisible(rv: RecyclerView) {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val a = lm.findFirstVisibleItemPosition(); val b = lm.findLastVisibleItemPosition()
        if (a >= 0 && b >= a) notifyItemRangeChanged(a, b - a + 1)
    }
    /** Подпись состояний, от которых зависит вид видимых строк (читает те же состояния, что и строка): изменилась, значит строки перерисовываются. */
    fun probe(rv: RecyclerView): Int {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return 0
        val a = lm.findFirstVisibleItemPosition(); val b = lm.findLastVisibleItemPosition()
        var h = 17
        if (a < 0) return h
        for (i in a..minOf(b, rows.size - 1)) h = h * 31 + rowModel(env, rows[i]).hashCode()
        return h
    }
    /** 23.11.2026: позиции видимых строк, у которых описание отличается от показанного (читает те же состояния, что и строка). Выбор строки, отметка, цифры перерисовывают только изменившиеся строки, а не все видимые. */
    fun dirty(rv: RecyclerView): List<Int> {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return emptyList()
        val a = lm.findFirstVisibleItemPosition(); val b = lm.findLastVisibleItemPosition()
        if (a < 0) return emptyList()
        var out: ArrayList<Int>? = null
        for (i in a..minOf(b, rows.size - 1)) { val r = rows[i]; val old = bound[r.key]; if (old != null && old != rowModel(env, r)) (out ?: ArrayList<Int>().also { out = it }).add(i) }
        return out ?: emptyList()
    }
    fun clearBound() = bound.clear()
}

// ------------------------------------------------------------------ вид для Compose

/** Список «Источников» на RecyclerView. Возвращает фасад состояния прокрутки. */
@Composable
internal fun FmRootRvList(p: FmPane, model: RootModel, tv: Boolean, st: RvState, modifier: Modifier) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val conf = LocalConfiguration.current
    val parent = rememberCompositionContext()
    val cs = MaterialTheme.colorScheme
    val arrowCol = treeArrowColor().toArgb()
    val contentCol = androidx.compose.material3.LocalContentColor.current.toArgb()
    val sig = "${density.density}|${density.fontScale}|${cs.primary.toArgb()}|${cs.onSurface.toArgb()}|$arrowCol|$contentCol|$tv|${UiPrefs.narrow}|${UiPrefs.compactTree}"
    val env = RvEnv(p, ctx, tv, density.density, density.fontScale, conf.screenWidthDp < 420, parent, density, sig,
        cs.primary.toArgb(), cs.onSurface.toArgb(), cs.onPrimary.toArgb(), cs.error.toArgb(), contentCol, arrowCol)
    var menuRow by remember { mutableStateOf<RR?>(null) }
    var pickRow by remember { mutableStateOf<RR?>(null) }
    var menuX by remember { mutableStateOf(0) }; var menuY by remember { mutableStateOf(0) }; var menuW by remember { mutableStateOf(0) }; var menuH by remember { mutableStateOf(0) }
    val adRef = remember { arrayOfNulls<RvAdapter>(1) }
    val hostIf = remember { object : RvHost {
        override fun openMenu(row: RvRow, r: RR) { menuX = row.left; menuY = row.top; menuW = row.width; menuH = row.height; menuRow = r }
        override fun rowChanged(row: RvRow) { val rv = st.rv ?: return; val pos = rv.getChildAdapterPosition(row); if (pos >= 0) adRef[0]?.notifyItemChanged(pos) }
    } }
    val adapter = remember { RvAdapter(env, hostIf).also { adRef[0] = it } }
    val spacing = if (tv) (2 * density.density).toInt() else 0
    val padL = if (scrollbarLeft()) (SbMath.RESERVE_DP * density.density).toInt() else 0
    val padR = if (scrollbarLeft()) 0 else (SbMath.RESERVE_DP * density.density).toInt()
    val tick = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val sstate = androidx.compose.foundation.gestures.rememberScrollableState { d -> st.rv?.scrollBy(0, d.toInt()); d }
    val metrics: (Float) -> Triple<Float, Float, Float>? = { _ ->
        tick.intValue
        val rv = st.rv
        if (rv == null || adapter.itemCount == 0 || rv.childCount == 0) null
        else { var sum = 0f; for (k in 0 until rv.childCount) sum += rv.getChildAt(k).height; val avg = sum / rv.childCount
            Triple(st.first * avg + st.firstOffset, rv.height.toFloat(), avg * adapter.itemCount) }
    }
    androidx.compose.foundation.layout.Box(modifier.clipToBounds().verticalScrollbarFor(sstate, metrics)) {
        AndroidView(
            factory = { c ->
                val rv = RecyclerView(c)
                rv.layoutManager = LinearLayoutManager(c)
                rv.itemAnimator = null
                rv.setItemViewCacheSize(0)
                rv.clipToPadding = false; rv.clipChildren = false
                rv.isFocusable = false
                rv.overScrollMode = View.OVER_SCROLL_NEVER
                rv.adapter = adapter
                rv.recycledViewPool.setMaxRecycledViews(0, 40); rv.recycledViewPool.setMaxRecycledViews(1, 10)
                rv.setHasFixedSize(false)
                rv.addItemDecoration(object : RecyclerView.ItemDecoration() {
                    override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) { outRect.bottom = spacingNow }
                })
                rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { FmRootCfg.lastVisKey = adapter.rows.getOrNull(st.last)?.key; tick.intValue++ }
                })
                rv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> FmRootCfg.lastVisKey = adapter.rows.getOrNull(st.last)?.key; tick.intValue++ }
                st.rv = rv
                rv
            },
            modifier = Modifier.fillMaxSize(),
            update = { rv ->
                spacingNow = spacing
                rv.setPadding(padL, 0, padR, 0)
                val changed = adapter.env.sig != env.sig || adapter.env.p !== env.p
                adapter.env = env
                if (changed) { adapter.clearBound(); adapter.notifyDataSetChanged() }
                adapter.submit(model.built?.rows ?: emptyList(), rv)
                st.rv = rv
            },
        )
        // 23.11.2026: готовые строки приходят из фоновой сборки (RootModel) и применяются к адаптеру без перекомпоновки
        androidx.compose.runtime.DisposableEffect(model, adapter) {
            model.sink = { b -> st.rv?.let { rv -> adapter.submit(b.rows, rv) } }
            st.rv?.let { rv -> model.built?.let { b -> adapter.submit(b.rows, rv) } }
            onDispose { if (model.sink != null) model.sink = null }
        }
        // перерисовка видимых строк, когда меняются состояния, от которых они зависят (отметки, цифры папок, выбранная строка, язык дат)
        LaunchedEffect(adapter) {
            snapshotFlow { st.rv?.let { adapter.dirty(it) } ?: emptyList() }.collect { l -> for (i in l) adapter.notifyItemChanged(i) }
        }
        // фокус пульта: запрос на строку по ключу (после возврата, после запуска)
        LaunchedEffect(FocusRestore.key) { val k = FocusRestore.key ?: return@LaunchedEffect; rvFocusKey(st, adapter, k, { FocusRestore.key == k }) { FocusRestore.key = null } }
        LaunchedEffect(FocusRestore.startKey) {
            val k = FocusRestore.startKey ?: return@LaunchedEffect
            if (FocusRestore.startPane != p.id) return@LaunchedEffect
            val m = FocusRestore.startMark
            rvFocusKey(st, adapter, k, { FocusRestore.startKey == k && m == FocusRestore.keys }) { if (FocusRestore.startKey == k) FocusRestore.startKey = null }
        }
        androidx.compose.foundation.layout.Box(Modifier.offset { IntOffset(menuX, menuY) }.size(with(density) { menuW.toDp() }, with(density) { menuH.toDp() })) {
            val mr = menuRow
            if (mr != null) FmRootRowMenu(p, mr, { menuRow = null }, { pickRow = mr; menuRow = null })
        }
        pickRow?.let { r -> CustomIconPicker(r.title, "d:" + r.key) { pickRow = null } }
        ScrollbarHandle(sstate, Modifier.align(if (scrollbarLeft()) Alignment.CenterStart else Alignment.CenterEnd))
    }
}

private var spacingNow = 0


/** Просит фокус для строки с ключом [key] (до 40 попыток раз в 250 мс, как раньше), пока [still] истинно. */
private suspend fun rvFocusKey(st: RvState, ad: RvAdapter, key: String, still: () -> Boolean, done: () -> Unit) {
    var tries = 0
    while (still() && tries < 40) {
        delay(250)
        val idx = ad.rows.indexOfFirst { it.key == key }
        if (idx >= 0) {
            if (!st.isVisible(idx)) st.scrollToItem(idx)
            val v = st.viewAt(idx)
            if (v != null && (v.requestFocus() || (v.isInTouchMode && v.requestFocusFromTouch()))) break
        }
        tries++
    }
    done()
}

/** Положение прокрутки дерева: то же, что rememberSavedTreeList, но для RecyclerView. */
@Composable
internal fun rememberSavedTreeListRv(key: String, rowKeys: () -> List<String>, selKey: () -> String?, st: RvState) {
    val ctx = LocalContext.current
    val (i, o) = remember(key) { ScrollMem.getList(ctx, key) }
    val savedKey = remember(key) { ScrollMem.getKey(ctx, key) }
    val keys = rememberUpdatedState(rowKeys)
    val sel = rememberUpdatedState(selKey)
    var restored by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        val keys0 = FocusRestore.keys
        var waited = 0
        var found = false
        while (waited < 6000) {
            val ks = keys.value()
            val idx = savedKey?.let { ks.indexOf(it) } ?: -1
            if (idx >= 0) { st.scrollToItem(idx, o); found = true; break }
            if (i == 0 && o == 0 && ks.isNotEmpty()) break
            if (savedKey == null && ks.size > i + 3) { st.scrollToItem(i, o); break }
            delay(200); waited += 200
        }
        if (!found && savedKey != null && !(i == 0 && o == 0) && keys.value().size > i + 3) st.scrollToItem(i, o)
        var w2 = 0
        while (sel.value() == null && w2 < 2000) { delay(100); w2 += 100 }
        delay(150)
        val ks = keys.value(); val si = sel.value()?.let { ks.indexOf(it) } ?: -1
        if (si >= 0 && !st.isVisible(si)) st.scrollToItem((si - 2).coerceAtLeast(0))
        var lastN = ks.size; var stable = 0; var w3 = 0
        while (stable < 6 && w3 < 5000 && !st.scrolling) {
            delay(150); w3 += 150
            val n = keys.value().size
            if (n != lastN) { lastN = n; stable = 0 } else stable++
        }
        if (!st.scrolling) {
            val ks2 = keys.value(); val si2 = sel.value()?.let { ks2.indexOf(it) } ?: -1
            if (si2 >= 0 && !st.isVisible(si2)) st.scrollToItem((si2 - 2).coerceAtLeast(0))
        }
        restored = true
        if (FocusRestore.keyMode(ctx) && sel.value() != null) {
            val paneId = key.substringAfterLast('.').toIntOrNull() ?: -1
            if (paneId < 0) FocusRestore.key = sel.value()
            else if (Fm.active == paneId && FocusRestore.keys == keys0) {
                val tgt = sel.value()
                FocusRestore.startPane = paneId; FocusRestore.startMark = keys0; FocusRestore.startKey = tgt
                delay(8000)
                if (FocusRestore.startKey == tgt) FocusRestore.startKey = null
            }
        }
    }
    LaunchedEffect(st, restored) {
        if (!restored) return@LaunchedEffect
        flow { while (true) { emit(st.first to st.firstOffset); delay(250) } }.distinctUntilChanged().collectLatest { (a, b) ->
            delay(400); ScrollMem.putList(ctx, key, a, b); ScrollMem.putKey(ctx, key, keys.value().getOrNull(a))
        }
    }
    DisposableEffect(st, restored) { onDispose { if (restored) { ScrollMem.putList(ctx, key, st.first, st.firstOffset); ScrollMem.putKey(ctx, key, keys.value().getOrNull(st.first)) } } }
}
