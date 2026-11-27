@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package app.adbphon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 22.11.2026: список ВНУТРИ папки («Файлы», режим списка и дерева) на RecyclerView, как «Источники» (FmRootRv.kt). Прежний LazyColumn остаётся в FileManager.kt:
 * [FmPaneRv.ON] = false возвращает его. Строки не-файлов (заголовки групп, корзина, «диск», сообщения) показываются прежними Compose-функциями в ComposeView.
 */
object FmPaneRv { const val ON = true }

/** Строка списка панели: файл или папка (item != null) либо любая другая Compose-строка ([other]). */
internal class PaneRvItem(val key: String, val item: FmItem?, val node: Any?, val depth: Int, val nodePath: String, val last: List<Boolean>, val other: (@Composable () -> Unit)?) {
    var grp: Int = 0
    var gTop: Int = 0
    var gBot: Int = 0
    val menuKey: String get() = if (depth == 0) (item?.name ?: key) else nodePath
}

internal class PaneModel(val sel: Boolean, val part: Boolean, val open: Boolean, val cnt: Pair<Int, Int>?, val iconKey: String?, val sub: String, val chkColor: Int = 0) {
    /** Значок, уже вычисленный при сборке описания (не входит в сравнение). */
    var si: StaticIcon? = null
    override fun equals(other: Any?) = other is PaneModel && sel == other.sel && part == other.part && open == other.open && cnt == other.cnt && iconKey == other.iconKey && sub == other.sub && chkColor == other.chkColor
    override fun hashCode() = ((((sel.hashCode() * 31 + part.hashCode()) * 31 + open.hashCode()) * 31 + (cnt?.hashCode() ?: 0)) * 31 + (iconKey?.hashCode() ?: 0)) * 31 + sub.hashCode()
}

private fun fullOf(p: FmPane, it: PaneRvItem): String = if (it.depth > 0) it.nodePath else FmIo.join(p.path, it.item!!.name)
private fun selKeyOf(p: FmPane, it: PaneRvItem): String = if (it.depth > 0) it.nodePath.removePrefix(p.path.trimEnd('/') + "/") else it.item!!.name

private fun countKey(p: FmPane, f: FmItem, full: String): String? =
    if (f.isDir && FmSub.mode != FmSub.NONE && p.net?.kind != "cloud" && !(p.src == "smb" && p.smb is ArchiveFs)) "P|" + p.src + "|" + (p.net?.let { it.kind + "|" + it.host + "|" + it.user } ?: "") + "|" + full + "|" + f.date else null

/** Значок без Compose: папка, значок типа, локальный APK (из кеша, с фоновой загрузкой), векторный значок приложений. null: оставить Compose (миниатюры фото и видео, значки приложений, свои значки). */
private fun paneStaticIcon(e: RvEnv, p: FmPane, it: PaneRvItem, open: Boolean): StaticIcon? {
    val f = it.item ?: return null
    val full = fullOf(p, it)
    val px = e.dp(32).toInt()
    if (f.isDir) {
        val key = folderKey(p, full)
        if (appFolderPkg(full.substringBeforeLast('/', ""), full.substringAfterLast('/')) != null) return null
        if (CustomIcons.specNow(e.ctx, "f:$key") != null) return null
        val c = (UiPrefs.folderColors[key] ?: UiPrefs.folderDefault).coerceIn(0, FOLDER_COLORS.size - 1)
        return StaticIcon("D|$c|$open|$px", { val (top, bot) = FOLDER_COLORS[c]; drawFolderG(top, bot, open) })
    }
    if (p.src == "smb" && p.smb is AppsFs) return null
    val ext = f.ext; val cat = fileCat(ext)
    val local = p.src == "local"
    if (cat == FCat.APK) {
        if (CustomIcons.specForNow(e.ctx, ext, if (local) full else null, cat, fileOnly = UiPrefs.apkIcons) != null) return null
        if (local && UiPrefs.apkIcons) {
            val b = Thumbs.peek(Thumbs.apkKey(full))
            return StaticIcon(key = "A|$full|${b != null}|$px", draw = { drawGlyphG(cat) }, bmp = b, loadPath = if (b == null) full else null)
        }
        if (p.src == "adb") return null   // значок APK с устройства по ADB: прежний путь
        return StaticIcon("G|$cat|$px", { drawGlyphG(cat) })
    }
    if ((cat == FCat.PHOTO || cat == FCat.VIDEO) && local && UiPrefs.thumbs) return null   // локальные миниатюры: прежний Compose-значок (в фоне, с заглушкой)
    if (CustomIcons.specForNow(e.ctx, ext, if (local) full else null, cat, fileOnly = false) != null) return null
    if (ApkInstall.iconRef(e.ctx, ext) != null) return null
    return StaticIcon("G|$cat|$px", { drawGlyphG(cat) })
}

private fun paneModel(e: RvEnv, it: PaneRvItem): PaneModel {
    val p = e.p; val f = it.item!!
    val full = fullOf(p, it); val sk = selKeyOf(p, it)
    val ix = p.selIdx.value
    val sel = ix.direct(sk)
    val part = f.isDir && !sel && ix.partial(sk)
    val open = e.p.expanded.let { ex -> it.node != null && f.isDir && it.nodePath in ex }
    val ck = countKey(p, f, full)
    val cnt = if (ck == null) null else { val t = FolderCounts.ver; if (t >= 0) FolderCounts.peek(ck) else null }
    val narrow = !e.tv && e.narrowW
    val cntTxt = cnt?.let { nbBlock(e.ctx.getString(if (narrow) R.string.rt_counts_s else R.string.rt_counts, it.first, it.second)) }
    val sub = (if (f.isDir) FmSub.fileLine(e.ctx, f.date, cntTxt, narrow) else FmSub.fileLine(e.ctx, f.date, if (Fm.showSize) fmSize(f.size) else null, narrow)) ?: ""
    val si0 = paneStaticIcon(e, p, it, open)
    return PaneModel(sel, part, open, cnt, si0?.key, sub, checkFill(e.prim)).also { it.si = si0 }
}

internal interface PaneHost { fun openMenu(row: PaneRow, it: PaneRvItem); fun rowChanged(row: PaneRow) }

internal class PaneRow(val env0: () -> RvEnv, ctx: Context, val host: PaneHost) : FrameLayout(ctx) {
    private val content = LinearLayout(ctx)
    private val dot = View(ctx)
    private val indent = View(ctx)
    private val tri = GlyphView(ctx)
    private val iconImg = ImageView(ctx)
    private var iconHost: ComposeView? = null
    private val iconState = mutableStateOf<PaneRvItem?>(null)
    private val textCol = LinearLayout(ctx)
    private val title = TextView(ctx)
    private val sub = TextView(ctx)
    private val chk = CheckView(ctx)
    val zone = View(ctx)
    var item: PaneRvItem? = null
    var m: PaneModel? = null
    private var job: Job? = null
    private var iconJob: Job? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var zoneFocused = false
    private var dotFocus = false      // 24.11.2026: точка «свойства» слева берёт фокус пульта (стрелка влево), ОК открывает свойства
    private var ptrDownAt = 0L
    private var prevTapAt = 0L
    private var longKey = false
    private var hadFocus = false

    init {
        setWillNotDraw(false)
        isFocusable = true; isClickable = true; isLongClickable = true; isContextClickable = true
        content.orientation = LinearLayout.HORIZONTAL; content.gravity = Gravity.CENTER_VERTICAL
        title.maxLines = 3; title.ellipsize = TextUtils.TruncateAt.END; title.includeFontPadding = false
        sub.maxLines = 3; sub.ellipsize = TextUtils.TruncateAt.END; sub.includeFontPadding = false
        textCol.orientation = LinearLayout.VERTICAL
        textCol.addView(title, LinearLayout.LayoutParams(-1, -2)); textCol.addView(sub, LinearLayout.LayoutParams(-1, -2))
        content.addView(dot); content.addView(indent); content.addView(tri); content.addView(iconImg)
        content.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f)); content.addView(chk)
        addView(content, LayoutParams(-1, -2))
        zone.isFocusable = true; zone.isClickable = true; zone.isLongClickable = true; zone.isContextClickable = true
        addView(zone, LayoutParams(0, -1, Gravity.END))
        iconImg.scaleType = ImageView.ScaleType.FIT_XY
        for (v in listOf<View>(dot, indent, tri, iconImg, chk)) v.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        dot.isClickable = true
        dot.setOnClickListener { val i = item ?: return@setOnClickListener; val e = env0(); Fm.active = e.p.id; Fm.props(e.p, listOf(selKeyOf(e.p, i))) }
        tri.setOnClickListener { val i = item ?: return@setOnClickListener; val e = env0(); Fm.active = e.p.id; postDelayed({ Fm.toggleExpand(e.p, i.nodePath) }, 20); tri.mode = if (tri.mode == 3) 4 else 3; tri.invalidate() }
        setOnClickListener { clickOrEnter() }
        setOnLongClickListener { openMenu(); true }
        setOnContextClickListener { openMenu(); true }
        zone.setOnClickListener { val i = item ?: return@setOnClickListener; val e = env0(); Fm.active = e.p.id; Fm.toggleSel(e.p, selKeyOf(e.p, i)) }
        zone.setOnLongClickListener { openMenu(); true }
        zone.setOnFocusChangeListener { _, f -> zoneFocused = f; invalidate() }
        setOnFocusChangeListener { _, f -> hadFocus = f; if (!f) dotFocus = false; val i = item; if (f && i != null && i.depth == 0) env0().p.lastName = i.item?.name; invalidate() }
        val kl = OnKeyListener { v, code, ev -> onKey(v, code, ev) }
        setOnKeyListener(kl); zone.setOnKeyListener(kl)
    }

    private fun openMenu() { val i = item ?: return; val e = env0(); Fm.active = e.p.id; e.p.menuFor = i.menuKey; host.openMenu(this, i) }

    private fun rowClick() {
        val i = item ?: return; val e = env0(); val p = e.p; val f = i.item ?: return; val depth = i.depth; val nodePath = i.nodePath
        Fm.active = p.id
        if (depth == 0) p.lastName = f.name
        if (longKey) longKey = false
        else if (p.selected.isNotEmpty() && depth > 0) Fm.toggleSel(p, nodePath.removePrefix(p.path.trimEnd('/') + "/"))
        else if (depth > 0) {
            if (f.isDir) { if (Fm.treeMode && Fm.dirTapExpand) Fm.toggleExpand(p, nodePath) else Fm.load(p, nodePath) } else Fm.load(p, nodePath.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }, pick = f.name)
        } else if (p.selected.isNotEmpty()) Fm.toggleSel(p, f.name) else if (f.isDir) { if (Fm.treeMode && Fm.dirTapExpand) Fm.toggleExpand(p, FmIo.join(p.path, f.name)) else Fm.open(p, f.name) } else Fm.openFile(p, f)
    }

    private fun clickOrEnter() {
        val i = item ?: return; val e = env0(); val p = e.p; val f = i.item ?: return
        val t = android.os.SystemClock.uptimeMillis()
        val viaPointer = t - ptrDownAt < 800L
        val canEnter = f.isDir && Fm.treeMode && Fm.dirTapExpand
        if (viaPointer && canEnter && prevTapAt != 0L && t - prevTapAt <= android.view.ViewConfiguration.getDoubleTapTimeout()) {
            prevTapAt = 0L; Fm.active = p.id; Fm.load(p, if (i.depth == 0) FmIo.join(p.path, f.name) else i.nodePath)
        } else { prevTapAt = if (viaPointer) t else 0L; rowClick() }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean { if (ev.actionMasked == MotionEvent.ACTION_DOWN) ptrDownAt = android.os.SystemClock.uptimeMillis(); return super.dispatchTouchEvent(ev) }

    private fun onKey(v: View, code: Int, ev: KeyEvent): Boolean {
        val i = item ?: return false; val e = env0(); val p = e.p; val f = i.item ?: return false
        if (ev.action != KeyEvent.ACTION_DOWN) return false
        if (dotFocus && v !== zone) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) { dotFocus = false; invalidate(); Fm.active = p.id; Fm.props(p, listOf(selKeyOf(p, i))); return true }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_LEFT) { dotFocus = false; invalidate(); return true }
            dotFocus = false; invalidate()
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP) {
            val rv = parent as? RecyclerView; val pos = rv?.getChildAdapterPosition(this) ?: -1
            if (rv != null && pos >= 0) {
                val step = if (code == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                var q = pos + step; val ad = rv.adapter
                while (ad != null && q in 0 until ad.itemCount) {
                    val row = rv.findViewHolderForAdapterPosition(q)?.itemView as? PaneRow
                    if (row == null) { rv.scrollToPosition(q); val qq = q; rv.post { (rv.findViewHolderForAdapterPosition(qq)?.itemView as? PaneRow)?.let { r -> if (v === zone) r.zone.requestFocus() else r.requestFocus() } }; return true }
                    if (row.item != null) return if (v === zone) row.zone.requestFocus() else row.requestFocus()
                    q += step
                }
            }
            return false
        }
        if (v === zone) return if (code == KeyEvent.KEYCODE_DPAD_LEFT) { requestFocus(); true } else false
        if (code == KeyEvent.KEYCODE_MENU) { openMenu(); return true }
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) && ev.repeatCount == 1) { longKey = true; openMenu(); return true }
        val treeDir = i.node != null && f.isDir
        val open = treeDir && i.nodePath in p.expanded
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT && !(treeDir && !open)) return zone.requestFocus()
        if (treeDir) {
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { Fm.expand(p, i.nodePath); return true }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && open) { Fm.collapse(p, i.nodePath); return true }
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) { dotFocus = true; invalidate(); return true }
        return false
    }

    /** 23.11.2026: строка уходит в пул: Compose-значок убирается (иначе повторный показ простой строки заново создавал композицию, около 7 мс на приставке). */
    fun releaseHosts() { iconHost?.let { (it.parent as? ViewGroup)?.removeView(it) }; iconHost = null; iconState.value = null }

    fun bind(it: PaneRvItem, mm: PaneModel, e: RvEnv) {
        item = it; m = mm
        job?.cancel(); job = null; iconJob?.cancel(); iconJob = null
        val f = it.item ?: return
        val p = e.p; val tv = e.tv; val dense = Fm.dense; val depth = it.depth
        val treeOn = it.node != null
        alpha = if (f.hidden) 0.6f else 1f
        minimumHeight = e.dp(if (tv) 54 else if (dense) 0 else 46).toInt()
        content.setPadding(e.dp(TreeDim.pad).toInt(), e.dp(if (tv) 4 else if (dense) 0 else 4).toInt(), e.dp(if (dense) 0 else 2).toInt(), e.dp(if (tv) 4 else if (dense) 0 else 4).toInt())
        dot.layoutParams = LinearLayout.LayoutParams(e.dp(TreeDim.dotW).toInt(), e.dp(32).toInt())
        indent.layoutParams = LinearLayout.LayoutParams(if (treeOn) e.dp(TreeDim.ind * depth).toInt() else 0, 1)
        val arrowPx = e.dp(TreeDim.fmArrow).toInt()
        if (treeOn) {
            tri.visibility = VISIBLE; tri.layoutParams = LinearLayout.LayoutParams(arrowPx, arrowPx)
            val nonEmpty = mm.cnt == null || mm.cnt.first + mm.cnt.second > 0
            if (f.isDir && nonEmpty) { tri.mode = if (mm.open) 4 else 3; tri.color = e.arrow; tri.sizePx = e.dp(12); tri.isClickable = true; tri.setPadding(0, 0, 0, 0) }
            else { tri.mode = 5; tri.isClickable = false }
        } else tri.visibility = GONE
        val px = e.dp(32).toInt()
        val si = if (mm.iconKey != null) (mm.si ?: paneStaticIcon(e, p, it, mm.open)) else null
        if (si != null) {
            iconImg.visibility = VISIBLE
            if (si.bmp != null) {
                iconImg.setImageBitmap(si.bmp)
                iconImg.outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, v.width * 0.2f) } }
                iconImg.clipToOutline = true
            } else { iconImg.clipToOutline = false; iconImg.setImageBitmap(IconBmp.get(si.key, px, si.draw)) }
            iconImg.layoutParams = LinearLayout.LayoutParams(px, px)
            iconHost?.visibility = GONE; iconState.value = null
            val lp = si.loadPath
            if (lp != null) iconJob = Fm.scope.launch { delay(120); val bb = Thumbs.apkIcon(e.ctx, lp); if (bb != null && item === it) host.rowChanged(this@PaneRow) }
        } else {
            iconImg.visibility = GONE
            val ih = iconHost ?: ComposeView(context).also { h ->
                h.hostInit(e.parent)
                h.setContent { val x = iconState.value; val e2 = env0(); if (x != null) androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides e2.density) { PaneIconCompose(e2.p, x) } }
                h.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; h.descendantFocusability = FOCUS_BLOCK_DESCENDANTS; h.isFocusable = false
                iconHost = h; content.addView(h, content.indexOfChild(iconImg) + 1)
            }
            ih.visibility = VISIBLE; iconState.value = it
            ih.layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
        (textCol.layoutParams as LinearLayout.LayoutParams).marginStart = e.dp(if (dense) 8 else 10).toInt()
        title.text = NameWrap.fmt(f.name)
        title.applyStyle(e, if (dense) 15f else 14f, if (dense) 17f else 18f, 700, e.content)
        if (mm.sub.isNotEmpty()) { sub.visibility = VISIBLE; sub.text = mm.sub; sub.applyStyle(e, if (dense) 11f else 10f, if (dense) 13f else 12f, 600, e.alpha(e.content, .6f)) } else sub.visibility = GONE
        chk.layoutParams = LinearLayout.LayoutParams(e.dp(if (tv) 40 else 34).toInt(), e.dp(if (tv) 40 else 34).toInt()).also { it2 -> it2.marginStart = e.dp(4).toInt(); it2.marginEnd = e.dp(4).toInt() }
        chk.on = mm.sel; chk.part = mm.part; chk.parent = mm.sel && mm.open; chk.boxPx = e.dp(if (tv) 29f else 23.5f); chk.strokePx = e.dp(2f); chk.fill = mm.chkColor; chk.onSurface = e.onSurface; chk.invalidate()      // 24.11.2026: без этого галочка не перерисовывалась при смене отметки (жалоба wad72)
        (zone.layoutParams as LayoutParams).width = e.dp(if (tv) 76 else 64).toInt()
        val ck = countKey(p, f, fullOf(p, it))
        if (ck != null && mm.cnt == null && !FolderCounts.has(ck)) {
            val full = fullOf(p, it)
            job = Fm.scope.launch {
                FolderCounts.load(e.ctx, ck, if (p.src == "local") FolderCounts.LOCAL else FolderCounts.clsOf(p.net ?: NetConn("smb", "", 0, "", "")), true) {
                    (if (p.src == "local") FolderCounts.localCount(java.io.File(full)) else null) ?: run { val l = FmIo.list(p, full); l.count { x -> x.isDir && x.name != FmTrash.DIR } to l.count { x -> !x.isDir } }
                }
                if (item === it && FolderCounts.has(ck)) host.rowChanged(this@PaneRow)
            }
        }
        contentDescription = f.name
        invalidate()
    }

    override fun dispatchDraw(c: Canvas) { drawBack(c); super.dispatchDraw(c); drawFocus(c) }

    private fun drawBack(c: Canvas) {
        val i = item ?: return; val mm = m ?: return; val e = env0(); val h = height.toFloat(); val w = width.toFloat()
        val depth = i.depth
        if (i.grp == 0 && i.node != null) { paint.style = Paint.Style.FILL; paint.color = closedBg(isDarkTheme(e.onSurface), e.prim); c.drawRect(0f, 0f, w, h, paint) }
        if (i.grp > 0) {
            paint.style = Paint.Style.FILL; paint.color = blockBg(isDarkTheme(e.onSurface), i.grp, e.prim)
            c.drawRect(0f, 0f, w, h, paint)
            drawBlockFrame(c, e, paint, i.grp, i.gTop, i.gBot, w, h)
        }
        val bg = if (mm.sel) e.alpha(e.prim, .20f) else 0
        if (bg != 0) { paint.style = Paint.Style.FILL; paint.color = bg; c.drawRect(0f, 0f, w, h, paint) }
        paint.style = Paint.Style.STROKE
        if (i.node != null && depth > 0) {
            paint.color = e.alpha(e.onSurface, .28f); paint.strokeWidth = e.dp(1.2f)
            val ind = e.dp(22f); val x0 = e.dp(24f); val mid = h / 2
            for (k in 0 until depth) {
                val cx = x0 + k * ind + ind / 2
                if (k < depth - 1) { if (!i.last.getOrElse(k + 2) { true }) c.drawLine(cx, 0f, cx, h, paint) }
                else {
                    val lastK = i.last.getOrElse(k + 2) { true }
                    c.drawLine(cx, 0f, cx, if (lastK) mid else h, paint)
                    c.drawLine(cx, mid, x0 + depth * ind + e.dp(4f), mid, paint)
                }
            }
        }
        if (e.tv) { paint.color = e.alpha(e.onSurface, .14f); paint.strokeWidth = e.dp(1f); c.drawLine(e.dp(12f), h - 0.5f, w - e.dp(12f), h - 0.5f, paint) }
        // точка «свойства»
        paint.style = Paint.Style.FILL; paint.color = e.alpha(e.onSurface, .3f)
        c.drawCircle(e.dp(TreeDim.pad) + e.dp(TreeDim.dotW) / 2, h / 2, e.dp(2.5f), paint)
        if (isPressed) { paint.color = e.alpha(e.onSurface, .12f); c.drawRect(0f, 0f, w, h, paint) }
    }

    private fun drawFocus(c: Canvas) {
        val e = env0()
        if (isFocused) { paint.style = Paint.Style.FILL; paint.color = e.alpha(e.prim, .16f); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint) }
        if (dotFocus && isFocused) { val bw0 = e.dp(if (e.tv) 3 else 2); val dh = e.dp(32); paint.style = Paint.Style.STROKE; paint.strokeWidth = bw0; paint.color = e.prim; rect.set(bw0 / 2, (height - dh) / 2, e.dp(TreeDim.dotW) + e.dp(TreeDim.pad), (height + dh) / 2); c.drawRoundRect(rect, e.dp(8f), e.dp(8f), paint) }
        if (zoneFocused) { val bw = e.dp(if (e.tv) 3 else 2); paint.style = Paint.Style.STROKE; paint.strokeWidth = bw; paint.color = e.prim; rect.set(width - zone.width + bw / 2, bw / 2, width - bw / 2, height - bw / 2); c.drawRoundRect(rect, e.dp(8f), e.dp(8f), paint) }
    }

    override fun setPressed(pressed: Boolean) { val ch = pressed != isPressed; super.setPressed(pressed); if (ch) invalidate() }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow(); job?.cancel(); iconJob?.cancel()
        hadFocus = false
    }
}

/**
 * 24.11.2026: владельцы дерева видов (жизненный цикл, хранилище состояния, ViewModel) ставятся на ComposeView-хост строки явно, из Activity панели.
 * Без этого значок запомненного приложения (и другие Compose-значки строк), созданный в момент ухода программы под другое окно, падал:
 * IllegalStateException "Composed into the View which doesn't propagate ViewTreeLifecycleOwner!" в RecyclerView.onLayout.
 */
internal fun androidx.compose.ui.platform.ComposeView.hostInit(p: CompositionContext) {
    setParentCompositionContext(p)
    bindTreeOwners(this)
}

internal fun bindTreeOwners(v: View) {
    var c: Context? = v.context
    while (c is android.content.ContextWrapper && c !is androidx.activity.ComponentActivity) c = c.baseContext
    val a = c as? androidx.activity.ComponentActivity ?: return
    v.setViewTreeLifecycleOwner(a)
    v.setViewTreeSavedStateRegistryOwner(a)
    v.setViewTreeViewModelStoreOwner(a)
}

@Composable
private fun PaneIconCompose(p: FmPane, it: PaneRvItem) {
    val f = it.item ?: return
    val full = fullOf(p, it)
    if (f.isDir) {
        val key = folderKey(p, full)
        FolderOrAppIcon(full, UiPrefs.folderColors[key] ?: UiPrefs.folderDefault, it.node != null && it.nodePath in p.expanded, 32.dp, Modifier, customKey = "f:" + key)
    } else if (p.src == "smb" && p.smb is AppsFs) AppsRowIcon(f.name, 32.dp)
    else FileTypeIcon(f.name, f.ext, if (p.src == "local") full else null, 32.dp,
        remote = if (p.src == "adb" && f.size > 0 && (f.ext == "apk" || f.ext == "apks" || f.ext == "xapk" || f.ext == "apkm"))
            RemoteApk(p.src + "||" + full + "|" + f.size + "|" + f.date, f.size, true, full) { dest -> Fm.fetchTo(p, full, dest) } else null)
}

internal class PaneAdapter(var env: RvEnv, val host: PaneHost) : RecyclerView.Adapter<PaneAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v)
    var items: List<PaneRvItem> = emptyList()
    private val ids = HashMap<String, Long>(); private var nextId = 1L
    init { setHasStableIds(true) }
    override fun getItemCount() = items.size
    override fun getItemId(position: Int): Long = ids.getOrPut(items[position].key) { nextId++ }
    override fun getItemViewType(position: Int) = if (items[position].item != null) 0 else 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v: View = if (viewType == 0) PaneRow({ env }, parent.context, host) else OtherHolder({ env }, parent.context)
        v.layoutParams = RecyclerView.LayoutParams(-1, -2)
        return VH(v)
    }
    /** Что показано в строке сейчас (ключ строки -> описание): по нему видно, какие видимые строки действительно изменились. */
    private val bound = HashMap<String, PaneModel>()
    // 23.11.2026: привязка идёт внутри раскладки Compose; без withoutReadObservation каждое чтение состояния записывалось в набор чтений раскладки
    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items.getOrNull(position) ?: return
        val v = h.itemView
        androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
            if (v is PaneRow && it.item != null) { val m = paneModel(env, it); bound[it.key] = m; v.bind(it, m, env) } else if (v is OtherHolder) v.bind(it)
        }
    }
    override fun onViewRecycled(h: VH) { (h.itemView as? PaneRow)?.releaseHosts() }
    /** 23.11.2026: позиции видимых строк, у которых описание отличается от показанного: отметка, выбор, цифры перерисовывают только изменившиеся строки. */
    fun dirty(rv: RecyclerView): List<Int> {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return emptyList()
        val a = lm.findFirstVisibleItemPosition(); val b = lm.findLastVisibleItemPosition()
        if (a < 0) return emptyList()
        var out: ArrayList<Int>? = null
        for (i in a..minOf(b, items.size - 1)) { val it = items[i]; if (it.item == null) continue; val old = bound[it.key]; val cur = paneModel(env, it); if (old != null && old != cur) (out ?: ArrayList<Int>().also { out = it }).add(i) }
        return out ?: emptyList()
    }
    fun clearBound() = bound.clear()
    fun submit(n: List<PaneRvItem>, rv: RecyclerView) {
        val old = items; items = n
        var s = 0
        while (s < old.size && s < n.size && old[s].key == n[s].key) s++
        var eo = old.size; var en = n.size
        while (eo > s && en > s && old[eo - 1].key == n[en - 1].key) { eo--; en-- }
        val rem = eo - s; val ins = en - s; val common = minOf(rem, ins)
        if (common > 0) notifyItemRangeChanged(s, common)
        if (rem > ins) notifyItemRangeRemoved(s + ins, rem - ins) else if (ins > rem) notifyItemRangeInserted(s + rem, ins - rem)
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val a = lm.findFirstVisibleItemPosition(); val b = lm.findLastVisibleItemPosition()
        if (a < 0) return
        val shift = old.size - n.size
        for (i in a..minOf(b, n.size - 1)) {
            val oi = when { i < s -> i; i >= s + ins -> i + shift; else -> continue }
            val same = oi in old.indices && (old[oi] === n[i] || (old[oi].key == n[i].key && old[oi].item == n[i].item && old[oi].depth == n[i].depth && old[oi].grp == n[i].grp && old[oi].gTop == n[i].gTop && old[oi].gBot == n[i].gBot && old[oi].last == n[i].last && old[oi].other == null && n[i].other == null))
            if (!same) notifyItemChanged(i) else lm.findViewByPosition(i)?.invalidate()
        }
    }
    fun refreshVisible(rv: RecyclerView) {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val a = lm.findFirstVisibleItemPosition(); val b = lm.findLastVisibleItemPosition()
        if (a >= 0 && b >= a) notifyItemRangeChanged(a, b - a + 1)
    }
    fun probe(rv: RecyclerView): Int {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return 0
        val a = lm.findFirstVisibleItemPosition(); val b = lm.findLastVisibleItemPosition()
        var h = 17
        if (a < 0) return h
        for (i in a..minOf(b, items.size - 1)) { val it = items[i]; if (it.item != null) h = h * 31 + paneModel(env, it).hashCode() }
        return h
    }
}

/** Строка не-файла (заголовок группы, корзина, «диск», сообщение): прежняя Compose-функция в ComposeView. */
internal class OtherHolder(val env0: () -> RvEnv, ctx: Context) : FrameLayout(ctx) {
    private val state = mutableStateOf<PaneRvItem?>(null)
    private val host = ComposeView(ctx)
    init {
        host.hostInit(env0().parent)
        host.setContent { val x = state.value; val e = env0(); if (x?.other != null) androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides e.density) { x.other?.invoke() } }
        addView(host, LayoutParams(-1, -2))
    }
    fun bind(it: PaneRvItem) { state.value = it }
}

@Composable
internal fun FmPaneRvList(p: FmPane, items: List<PaneRvItem>, tv: Boolean, st: RvState, menuContent: @Composable (PaneRvItem) -> Unit, onEmptyLong: () -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val conf = LocalConfiguration.current
    val parent = rememberCompositionContext()
    val cs = MaterialTheme.colorScheme
    val arrowCol = treeArrowColor().toArgb()
    val contentCol = androidx.compose.material3.LocalContentColor.current.toArgb()
    val sig = "${density.density}|${density.fontScale}|${cs.primary.toArgb()}|${cs.onSurface.toArgb()}|$arrowCol|$contentCol|$tv|${UiPrefs.narrow}|${UiPrefs.compactTree}|${Fm.dense}"
    val env = RvEnv(p, ctx, tv, density.density, density.fontScale, conf.screenWidthDp < 420, parent, density, sig, cs.primary.toArgb(), cs.onSurface.toArgb(), cs.onPrimary.toArgb(), cs.error.toArgb(), contentCol, arrowCol)
    var menuX by remember { mutableStateOf(0) }; var menuY by remember { mutableStateOf(0) }; var menuW by remember { mutableStateOf(0) }; var menuH by remember { mutableStateOf(0) }
    val adRef = remember { arrayOfNulls<PaneAdapter>(1) }
    val hostIf = remember { object : PaneHost {
        override fun openMenu(row: PaneRow, it: PaneRvItem) { menuX = row.left; menuY = row.top; menuW = row.width; menuH = row.height }
        override fun rowChanged(row: PaneRow) { val rv = st.rv ?: return; val pos = rv.getChildAdapterPosition(row); if (pos >= 0) adRef[0]?.notifyItemChanged(pos) }
    } }
    val adapter = remember { PaneAdapter(env, hostIf).also { adRef[0] = it } }
    val spacing = if (tv) (3 * density.density).toInt() else 0
    val padL = if (scrollbarLeft()) (SbMath.RESERVE_DP * density.density).toInt() else 0
    val padR = if (scrollbarLeft()) 0 else (SbMath.RESERVE_DP * density.density).toInt()
    val itemsNow = rememberUpdatedState(items)
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
                bindTreeOwners(rv)
                rv.layoutManager = LinearLayoutManager(c); rv.itemAnimator = null; rv.setItemViewCacheSize(0); rv.clipToPadding = false; rv.clipChildren = false; rv.isFocusable = false
                rv.overScrollMode = View.OVER_SCROLL_NEVER; rv.adapter = adapter
                rv.recycledViewPool.setMaxRecycledViews(0, 40); rv.recycledViewPool.setMaxRecycledViews(1, 10)
                rv.addItemDecoration(object : RecyclerView.ItemDecoration() { override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) { outRect.bottom = paneSpacing } })
                rv.addOnScrollListener(object : RecyclerView.OnScrollListener() { override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { tick.intValue++ } })
                rv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> tick.intValue++ }
                rv.setOnLongClickListener { onEmptyLong(); true }
                st.rv = rv
                rv
            },
            modifier = Modifier.fillMaxSize(),
            update = { rv ->
                paneSpacing = spacing
                rv.setPadding(padL, 0, padR, 0)
                val changed = adapter.env.sig != env.sig || adapter.env.p !== env.p
                adapter.env = env
                if (changed) { adapter.clearBound(); adapter.notifyDataSetChanged() }
                adapter.submit(itemsNow.value, rv)
                st.rv = rv
            },
        )
        // 24.11.2026: tick (раскладка и прокрутка) будит подписку: при первом запуске строк ещё нет, ни одно состояние не читалось, и поток больше не просыпался (галочки не рисовались)
        LaunchedEffect(adapter) { snapshotFlow { tick.intValue; DirtyRows(st.rv?.let { adapter.dirty(it) } ?: emptyList()) }.collect { d -> for (i in d.list) adapter.notifyItemChanged(i) } }
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.offset { IntOffset(menuX, menuY) }.size(with(density) { menuW.toDp() }, with(density) { menuH.toDp() })) {
            val mk = p.menuFor
            if (mk != null) { val it = items.firstOrNull { x -> x.item != null && x.menuKey == mk }; if (it != null) menuContent(it) }
        }
        ScrollbarHandle(sstate, Modifier.align(if (scrollbarLeft()) Alignment.CenterStart else Alignment.CenterEnd))
    }
}

private var paneSpacing = 0
