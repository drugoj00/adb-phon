package app.adbphon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** Настройки внешнего вида списков «Файлов»: узкий шрифт, цвета папок, «Открывать как». Хранятся в prefs «fm» и входят в резервную копию. */
object UiPrefs {
    var narrow by mutableStateOf(true)
    /** Миниатюры фото и видео вместо значков (по умолчанию включены). */
    var thumbs by mutableStateOf(true)
    /** 7.6: спрашивать, чем устанавливать (окно «Установить через…» во всех точках установки); по умолчанию включено. */
    var askInstaller by mutableStateOf(true)
    var folderDefault by mutableStateOf(0)
    /** Цвет конкретной папки: ключ «источник|путь» → номер цвета палитры. */
    val folderColors = mutableStateMapOf<String, Int>()
    /** Как открывать фото, аудио и видео: «builtin» (встроенный просмотрщик, по умолчанию) или пакет другого приложения. */
    val openAs = mutableStateMapOf<String, String>()
    /** Запомненный способ установки по расширению (apk, apks, apkm, xapk): нужен значкам файлов. */
    val instMode = mutableStateMapOf<String, String>()
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        val p = sp(ctx)
        narrow = true   // узкий шрифт в списках всегда (настройка убрана по слову владелицы 08.11.2026)
        thumbs = p.getBoolean("thumbs", true)
        askInstaller = p.getBoolean("ask_installer", true)
        folderDefault = p.getInt("folder_default", 0).coerceIn(0, FOLDER_COLORS.size - 1)
        folderColors.clear()
        p.getString("folder_colors", "").orEmpty().split('\n').forEach { l -> val i = l.lastIndexOf('='); if (i > 0) l.substring(i + 1).toIntOrNull()?.let { folderColors[l.substring(0, i)] = it } }
        openAs.clear()
        p.all.forEach { (k, v) -> if (k.startsWith("open_as_") && v is String) openAs[k.removePrefix("open_as_")] = v }
        // старая настройка «Открывать APK как архив» стала запомненным выбором для .apk («Открыть как архив»), ключ удаляется
        if (p.contains("apk_as_archive")) {
            val e = p.edit()
            if (p.getBoolean("apk_as_archive", false) && p.getString("inst_apk", "ask") == "ask") e.putString("inst_apk", "archive")
            e.remove("apk_as_archive").apply()
        }
        instMode.clear()
        listOf("apk", "apks", "apkm", "xapk").forEach { e -> p.getString("inst_$e", "ask")?.takeIf { it != "ask" }?.let { instMode[e] = it } }
    }

    fun setAskInstaller(ctx: Context, v: Boolean) { askInstaller = v; sp(ctx).edit().putBoolean("ask_installer", v).apply() }
    fun setThumbs(ctx: Context, v: Boolean) { thumbs = v; sp(ctx).edit().putBoolean("thumbs", v).apply() }
    fun setNarrow(ctx: Context, v: Boolean) { narrow = v; sp(ctx).edit().putBoolean("narrow_font", v).apply() }
    fun setFolderDefault(ctx: Context, i: Int) { folderDefault = i; sp(ctx).edit().putInt("folder_default", i).apply() }
    fun setFolderColor(ctx: Context, key: String, i: Int?) {
        if (i == null) folderColors.remove(key) else folderColors[key] = i
        sp(ctx).edit().putString("folder_colors", folderColors.entries.joinToString("\n") { it.key + "=" + it.value }).apply()
    }
    fun setOpenAs(ctx: Context, cat: String, v: String) { openAs[cat] = v; sp(ctx).edit().putString("open_as_$cat", v).apply() }

    /** Узкий системный шрифт того же размера: в строку помещается больше текста. null: обычный. */
    val listFont: FontFamily? get() = if (narrow) CondFont else null
}

val CondFont: FontFamily = FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))

/** Палитра папок: верх и низ градиента. Первая жёлтая (по умолчанию). */
val FOLDER_COLORS = listOf(
    Color(0xFFFFD54F) to Color(0xFFF9A825), Color(0xFFFFB74D) to Color(0xFFEF6C00), Color(0xFFEF5350) to Color(0xFFC62828), Color(0xFFF48FB1) to Color(0xFFD81B60),
    Color(0xFFBA68C8) to Color(0xFF7B1FA2), Color(0xFF5C8DF0) to Color(0xFF1E4FC2), Color(0xFF4FC3F7) to Color(0xFF0288D1), Color(0xFF4DD0C8) to Color(0xFF00897B),
    Color(0xFF81C784) to Color(0xFF2E7D32), Color(0xFFB0BEC5) to Color(0xFF607D8B), Color(0xFFA1887F) to Color(0xFF5D4037), Color(0xFF616161) to Color(0xFF212121),
)
val FOLDER_COLOR_NAMES = listOf(R.string.fc_yellow, R.string.fc_orange, R.string.fc_red, R.string.fc_pink, R.string.fc_purple, R.string.fc_blue, R.string.fc_cyan, R.string.fc_teal, R.string.fc_green, R.string.fc_gray, R.string.fc_brown, R.string.fc_black)

/** Нарисованная папка: градиент, лёгкая тень, закладка; открытая показывает отогнутую переднюю стенку. */
@Composable
fun FolderIcon(colorIdx: Int, open: Boolean, size: Dp, modifier: Modifier = Modifier, customKey: String? = null) {
    if (customKey != null) { val cs = CustomIcons.spec(customKey); if (cs != null) { CustomIconView(cs, size, modifier); return } }
    val (top, bot) = FOLDER_COLORS[colorIdx.coerceIn(0, FOLDER_COLORS.size - 1)]
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val r = CornerRadius(w * 0.09f)
        // тень
        drawRoundRect(Color.Black.copy(alpha = 0.18f), Offset(w * 0.05f, h * 0.30f), Size(w * 0.92f, h * 0.66f), r)
        // задняя стенка с закладкой
        val back = Path().apply {
            moveTo(w * 0.04f, h * 0.20f); lineTo(w * 0.36f, h * 0.20f); lineTo(w * 0.45f, h * 0.31f); lineTo(w * 0.96f, h * 0.31f); lineTo(w * 0.96f, h * 0.86f); lineTo(w * 0.04f, h * 0.86f); close()
        }
        drawPath(back, Brush.verticalGradient(listOf(bot, bot.copy(alpha = 0.9f)), 0f, h))
        // передняя стенка
        if (open) {
            val front = Path().apply { moveTo(w * 0.14f, h * 0.42f); lineTo(w * 1.0f, h * 0.42f); lineTo(w * 0.86f, h * 0.88f); lineTo(w * 0.0f, h * 0.88f); close() }
            drawPath(front, Brush.verticalGradient(listOf(top, bot), h * 0.42f, h * 0.88f))
        } else {
            drawRoundRect(Brush.verticalGradient(listOf(top, bot), h * 0.38f, h * 0.88f), Offset(0f, h * 0.38f), Size(w, h * 0.50f), r)
            drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(w * 0.04f, h * 0.40f), Size(w * 0.92f, h * 0.05f), CornerRadius(w * 0.02f))
        }
    }
}

// ------------------------------------------------------------------ значки файлов

enum class FCat { PHOTO, VIDEO, AUDIO, PDF, DOC, XLS, PPT, TEXT, ARCHIVE, APK, CODE, OTHER }

fun fileCat(ext: String): FCat = when (ext) {
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> FCat.PHOTO
    "mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts", "flv", "wmv", "mpg", "mpeg" -> FCat.VIDEO
    "mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma", "amr", "mid" -> FCat.AUDIO
    "pdf" -> FCat.PDF
    "doc", "docx", "odt", "rtf" -> FCat.DOC
    "xls", "xlsx", "ods", "csv" -> FCat.XLS
    "ppt", "pptx", "odp" -> FCat.PPT
    "txt", "log", "md" -> FCat.TEXT
    "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "jar" -> FCat.ARCHIVE
    "apk", "apks", "xapk" -> FCat.APK
    "json", "xml", "html", "js", "css", "py", "sh", "java", "kt", "c", "cpp", "h", "conf", "ini", "yaml", "yml", "properties" -> FCat.CODE
    else -> FCat.OTHER
}

/** Категория для «Открывать как»: photo, audio, video или null. */
fun openCat(ext: String): String? = when (fileCat(ext)) { FCat.PHOTO -> "photo"; FCat.AUDIO -> "audio"; FCat.VIDEO -> "video"; else -> null }

private fun catStyle(c: FCat): Triple<ImageVector, Color, Color> = when (c) {
    FCat.PHOTO -> Triple(Icons.Filled.Image, Color(0xFF26C6A6), Color(0xFF0288D1))
    FCat.VIDEO -> Triple(Icons.Filled.PlayArrow, Color(0xFFFF7043), Color(0xFFD32F2F))
    FCat.AUDIO -> Triple(Icons.Filled.MusicNote, Color(0xFFBA68C8), Color(0xFFE91E63))
    FCat.PDF -> Triple(Icons.Filled.PictureAsPdf, Color(0xFFEF5350), Color(0xFFB71C1C))
    FCat.DOC -> Triple(Icons.Filled.Description, Color(0xFF42A5F5), Color(0xFF1565C0))
    FCat.XLS -> Triple(Icons.Filled.TableChart, Color(0xFF66BB6A), Color(0xFF2E7D32))
    FCat.PPT -> Triple(Icons.Filled.Slideshow, Color(0xFFFFA726), Color(0xFFE65100))
    FCat.TEXT -> Triple(Icons.Filled.Description, Color(0xFF90A4AE), Color(0xFF546E7A))
    FCat.ARCHIVE -> Triple(Icons.Filled.FolderZip, Color(0xFFD7A86E), Color(0xFF8D6E63))
    FCat.APK -> Triple(Icons.Filled.Android, Color(0xFF9CCC65), Color(0xFF558B2F))
    FCat.CODE -> Triple(Icons.Filled.Code, Color(0xFF4DB6AC), Color(0xFF00695C))
    FCat.OTHER -> Triple(Icons.Filled.InsertDriveFile, Color(0xFFB0BEC5), Color(0xFF78909C))
}

/** Лёгкий кэш миниатюр и значков: загрузка в фоне, не больше двух одновременно, 90 записей. */
object Thumbs {
    /** Оперативный кэш по размеру памяти (1/16 кучи), миниатюры 128 px (на ТВ и слабом железе 96 px). */
    val durations = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val cache = object : android.util.LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt().coerceIn(2_000_000, 40_000_000)) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }
    private val none = HashSet<String>()
    private val gate = Semaphore(2)
    fun peek(key: String): Bitmap? = cache.get(key)

    suspend fun photo(path: String): Bitmap? = load("p:$path") {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1; while (o.outWidth / (s * 2) >= 128 && o.outHeight / (s * 2) >= 128) s *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    suspend fun video(path: String): Bitmap? = load("v:$path") {
        val r = MediaMetadataRetriever()
        try { r.setDataSource(path); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { durations[path] = it }; r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { Bitmap.createScaledBitmap(it, 128, (128f * it.height / it.width).toInt().coerceAtLeast(1), true) } }
        finally { runCatching { r.release() } }
    }

    suspend fun apkIcon(ctx: Context, path: String): Bitmap? = load("a:$path") {
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(path, 0)?.applicationInfo
        android.util.Log.i("ApkIcon", "path=$path exists=${java.io.File(path).canRead()} info=${info != null}")
        if (info == null) return@load null
        info.sourceDir = path; info.publicSourceDir = path
        val d = info.loadIcon(pm)
        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { b -> val c = android.graphics.Canvas(b); d.setBounds(0, 0, 96, 96); d.draw(c) }
    }

    /** Значок действия приложения («пакет/класс»): как его показывает система в окне «Открыть с помощью». */
    suspend fun componentIcon(ctx: Context, cmp: String): Bitmap? = load("c:$cmp") {
        val pm = ctx.packageManager
        val d = runCatching { pm.getActivityIcon(android.content.ComponentName(cmp.substringBefore(Char(47)), cmp.substringAfter(Char(47)))) }.getOrNull() ?: runCatching { pm.getApplicationIcon(cmp.substringBefore(Char(47))) }.getOrNull() ?: return@load null
        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { b -> val c = android.graphics.Canvas(b); d.setBounds(0, 0, 96, 96); d.draw(c) }
    }

    suspend fun refIcon(ctx: Context, ref: String): Bitmap? = if (ref.startsWith("c:")) componentIcon(ctx, ref.removePrefix("c:")) else appIcon(ctx, ref.removePrefix("i:"))

    suspend fun appIcon(ctx: Context, pkg: String): Bitmap? = load("i:$pkg") {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { b -> val c = android.graphics.Canvas(b); d.setBounds(0, 0, 96, 96); d.draw(c) }
    }

    private suspend fun load(key: String, block: () -> Bitmap?): Bitmap? {
        cache.get(key)?.let { return it }
        if (synchronized(none) { key in none }) return null
        return gate.withPermit {
            cache.get(key) ?: withContext(Dispatchers.IO) { runCatching(block).getOrNull() }.also { b -> if (b != null) cache.put(key, b) else synchronized(none) { none.add(key) } }
        }
    }
}

/** Дата «2026-11-08 03:32» на экране как «08.11.2026 03:32» (день.месяц.год). */
fun fmDate(s: String): String = Regex("""^(\d{4})-(\d{2})-(\d{2})(.*)$""").replace(s) { m -> "${m.groupValues[3]}.${m.groupValues[2]}.${m.groupValues[1]}${m.groupValues[4]}" }

/**
 * Единый значок файла для списка, дерева и диалогов: цветной значок по типу; для фото и видео (локальные файлы) миниатюра; для APK значок самого
 * приложения; если для типа выбрано «открывать в другом приложении», значок этого приложения.
 */
@Composable
fun FileTypeIcon(name: String, ext: String, localPath: String?, size: Dp, modifier: Modifier = Modifier) {
    val customSpec = CustomIcons.specFor(ext, localPath, fileCat(ext))
    if (customSpec != null) { CustomIconView(customSpec, size, modifier); return }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cat = fileCat(ext)
    // запомненный выбор программы для расширения: значок файла = значок этой программы (у фото и видео бейджем в углу поверх миниатюры)
    val ref = ApkInstall.iconRef(ctx, ext)
    val isMedia = cat == FCat.PHOTO || cat == FCat.VIDEO
    val other = if (isMedia) null else ref
    var bmp by remember(localPath, other) { mutableStateOf<Bitmap?>(null) }
    var badge by remember(ref, isMedia) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(ref, isMedia) {
        if (ref == null || !isMedia) { badge = null; return@LaunchedEffect }
        badge = Thumbs.peek(ref) ?: Thumbs.refIcon(ctx, ref)
        if (badge == null) ApkInstall.forget(ctx, ext)       // программу удалили: запоминание снимается, значок возвращается
    }
    LaunchedEffect(localPath, other) {
        if (other != null) {
            bmp = Thumbs.peek(other) ?: Thumbs.refIcon(ctx, other)
            if (bmp == null) ApkInstall.forget(ctx, ext)
            return@LaunchedEffect
        }
        if (localPath == null || !UiPrefs.thumbs) return@LaunchedEffect
        val key = when (cat) { FCat.PHOTO -> "p:$localPath"; FCat.VIDEO -> "v:$localPath"; FCat.APK -> "a:$localPath"; else -> null } ?: return@LaunchedEffect
        bmp = Thumbs.peek(key) ?: run {
            delay(120)       // при быстрой прокрутке строка успевает уйти с экрана до загрузки
            when (cat) { FCat.PHOTO -> Thumbs.photo(localPath); FCat.VIDEO -> Thumbs.video(localPath); else -> Thumbs.apkIcon(ctx, localPath) }
        }
    }
    val shape = RoundedCornerShape(size * 0.2f)
    val b = bmp
    if (b != null) {
        Box(modifier.size(size).clip(shape)) {
            Image(b.asImageBitmap(), null, Modifier.size(size), contentScale = if (cat == FCat.PHOTO || cat == FCat.VIDEO) ContentScale.Crop else ContentScale.Fit)
            badge?.let { bd -> Image(bd.asImageBitmap(), null, Modifier.size(size * 0.46f).align(Alignment.BottomEnd).clip(RoundedCornerShape(size * 0.1f))) }
            if (cat == FCat.VIDEO && other == null && badge == null) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(size * 0.28f).align(Alignment.TopStart).padding(2.dp).background(Color.Black.copy(alpha = .5f), CircleShape), tint = Color.White)
                Thumbs.durations[localPath ?: ""]?.let { ms ->
                    val sec = ms / 1000
                    androidx.compose.material3.Text(if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60), Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = .6f), RoundedCornerShape(3.dp)).padding(horizontal = 2.dp), fontSize = 8.sp, color = Color.White, maxLines = 1)
                }
            }
        }
    } else {
        FileGlyph(cat, ext, size, modifier)
    }
}

/** Свои векторные значки типов без подложки (прозрачный фон), в узнаваемом стиле Windows/Office/Acrobat, но не копии логотипов. */
@Composable
fun FileGlyph(cat: FCat, ext: String, size: Dp, modifier: Modifier = Modifier) {
    val ink = Color.Black.copy(alpha = 0.22f)
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        fun docPage(c1: Color, c2: Color) {
            val p = Path().apply { moveTo(w * .18f, h * .04f); lineTo(w * .62f, h * .04f); lineTo(w * .86f, h * .28f); lineTo(w * .86f, h * .96f); lineTo(w * .18f, h * .96f); close() }
            drawPath(p, Brush.verticalGradient(listOf(c1, c2)))
            drawPath(p, ink, style = androidx.compose.ui.graphics.drawscope.Stroke(w * .02f))
            val fold = Path().apply { moveTo(w * .62f, h * .04f); lineTo(w * .62f, h * .28f); lineTo(w * .86f, h * .28f); close() }
            drawPath(fold, Color.White.copy(alpha = .55f))
        }
        fun letter(t: String, col: Color = Color.White, k: Float = .42f) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = col.toArgb(); textSize = h * k; isFakeBoldText = true; textAlign = android.graphics.Paint.Align.CENTER }
            drawContext.canvas.nativeCanvas.drawText(t, w * .52f, h * .72f, paint)
        }
        when (cat) {
            FCat.AUDIO -> {   // синяя нота без подложки
                val g = Brush.verticalGradient(listOf(Color(0xFF42A5F5), Color(0xFF1565C0)))
                drawOval(g, Offset(w * .14f, h * .60f), Size(w * .34f, h * .28f))
                drawRect(g, Offset(w * .42f, h * .14f), Size(w * .08f, h * .62f))
                val flag = Path().apply { moveTo(w * .50f, h * .14f); cubicTo(w * .70f, h * .20f, w * .84f, h * .32f, w * .80f, h * .56f); cubicTo(w * .74f, h * .42f, w * .62f, h * .38f, w * .50f, h * .36f); close() }
                drawPath(flag, g)
            }
            FCat.VIDEO -> {   // кинолента сине-фиолетовая, перфорация сверху и снизу, белый «играть», без подложки
                val g = Brush.linearGradient(listOf(Color(0xFF3F51B5), Color(0xFF7E57C2)), Offset(0f, 0f), Offset(w, h))
                drawRoundRect(g, Offset(w * .04f, h * .16f), Size(w * .92f, h * .68f), CornerRadius(w * .1f))
                for (i in 0 until 5) { drawRect(Color.White.copy(alpha = .8f), Offset(w * (.10f + i * .17f), h * .20f), Size(w * .09f, h * .07f)); drawRect(Color.White.copy(alpha = .8f), Offset(w * (.10f + i * .17f), h * .73f), Size(w * .09f, h * .07f)) }
                val tri = Path().apply { moveTo(w * .40f, h * .34f); lineTo(w * .40f, h * .66f); lineTo(w * .68f, h * .50f); close() }
                drawPath(tri, Color.White)
            }
            FCat.PHOTO -> {   // вертушка из цветных лепестков
                val cols = listOf(Color(0xFFEF5350), Color(0xFFFFB300), Color(0xFF66BB6A), Color(0xFF29B6F6))
                val cx = w * .5f; val cy = h * .5f; val r = w * .42f
                for (i in 0 until 4) {
                    withTransformRot(i * 90f, cx, cy) {
                        val p = Path().apply { moveTo(cx, cy); cubicTo(cx - r * .55f, cy - r * .1f, cx - r * .6f, cy - r * .95f, cx, cy - r); cubicTo(cx + r * .2f, cy - r * .6f, cx + r * .1f, cy - r * .2f, cx, cy); close() }
                        drawPath(p, cols[i].copy(alpha = .92f))
                    }
                }
            }
            FCat.TEXT, FCat.CODE -> {   // блокнот со спиралью
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
            FCat.APK -> { docPage(Color(0xFFA5D66B), Color(0xFF558B2F)); drawCircle(Color.White, w * .12f, Offset(w * .52f, h * .6f)) }
            else -> docPage(Color(0xFFCFD8DC), Color(0xFF78909C))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.withTransformRot(deg: Float, cx: Float, cy: Float, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    rotate(deg, Offset(cx, cy), block)
}

/** Диалог выбора цвета папки: 12 цветов; «по умолчанию» возвращает цвет, выбранный в настройках. */
@Composable
fun FolderColorDialog(title: String, current: Int?, showReset: Boolean, onPick: (Int?) -> Unit, onClose: () -> Unit, iconKey: String? = null) {
    val ctxFc = androidx.compose.ui.platform.LocalContext.current
    var iconPick by remember { mutableStateOf(false) }
    if (iconPick && iconKey != null) CustomIconPicker(title, iconKey) { iconPick = false }
    LAlertDialog(
        onDismissRequest = onClose, title = { Text(androidx.compose.ui.res.stringResource(R.string.fc_title) + ": " + title, fontSize = 16.sp, maxLines = 1) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (iconKey != null) {
                    Row(Modifier.fillMaxWidth().clickable { iconPick = true }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val cs = CustomIcons.spec(iconKey)
                        if (cs != null) CustomIconView(cs, 36.dp) else FolderIcon(current ?: UiPrefs.folderDefault, false, 36.dp)
                        Text(androidx.compose.ui.res.stringResource(R.string.ic_choose), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    }
                }
                FOLDER_COLORS.chunked(4).forEachIndexed { r, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEachIndexed { c, _ ->
                            val i = r * 4 + c
                            Column(Modifier.clickable { onPick(i) }.padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box { FolderIcon(i, false, 44.dp); if (current == i) Icon(Icons.Filled.Check, null, Modifier.size(20.dp).align(Alignment.Center), tint = Color.White) }
                                Text(androidx.compose.ui.res.stringResource(FOLDER_COLOR_NAMES[i]), fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { if (showReset) androidx.compose.material3.TextButton({ if (iconKey != null) CustomIcons.set(ctxFc, iconKey, null); onPick(null) }) { Text(androidx.compose.ui.res.stringResource(R.string.fc_reset)) } else androidx.compose.material3.TextButton(onClose) { Text(androidx.compose.ui.res.stringResource(R.string.btn_close)) } },
        dismissButton = { if (showReset) androidx.compose.material3.TextButton(onClose) { Text(androidx.compose.ui.res.stringResource(R.string.btn_close)) } },
    )
}

/** Блок настроек внешнего вида «Файлов»: узкий шрифт, цвет папок по умолчанию, «Открывать как» для фото, аудио и видео. */
@Composable
fun FilesLookSection() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var colorDlg by remember { mutableStateOf(false) }
    var bg by remember { mutableStateOf(ctx.getSharedPreferences("fm", Context.MODE_PRIVATE).getBoolean("bg_music", false)) }
    SettingSwitch(bg, { bg = it; ctx.getSharedPreferences("fm", Context.MODE_PRIVATE).edit().putBoolean("bg_music", it).apply() }, androidx.compose.ui.res.stringResource(R.string.set_bg_music), androidx.compose.ui.res.stringResource(R.string.set_bg_music_hint))
    var wa by remember { mutableStateOf(WifiAuto.enabled(ctx)) }
    SettingSwitch(wa, { wa = it; WifiAuto.setEnabled(ctx, it) }, androidx.compose.ui.res.stringResource(R.string.set_wifi_auto), androidx.compose.ui.res.stringResource(R.string.set_wifi_auto_hint))
    SettingSwitch(UiPrefs.thumbs, { UiPrefs.setThumbs(ctx, it) }, androidx.compose.ui.res.stringResource(R.string.set_thumbs), androidx.compose.ui.res.stringResource(R.string.set_thumbs_hint))
    SettingSwitch(UiPrefs.askInstaller, { UiPrefs.setAskInstaller(ctx, it) }, androidx.compose.ui.res.stringResource(R.string.set_ask_installer), androidx.compose.ui.res.stringResource(R.string.set_ask_installer_hint))
    // 7.6: модуль «ADB-phon Облака»: версия, автообновление и удаление
    @Suppress("UNUSED_VARIABLE") val cmTick = CloudModule.ver
    val cmV = CloudModule.installedVersion(ctx)
    var cmAuto by remember(CloudModule.autoTick) { mutableStateOf(CloudModule.auto(ctx)) }
    var cmDel by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.Text(if (cmV != null) androidx.compose.ui.res.stringResource(R.string.set_cmod_ver, cmV, CloudModule.variant()?.key ?: "-") else androidx.compose.ui.res.stringResource(R.string.set_cmod_none), fontSize = 13.sp, modifier = androidx.compose.ui.Modifier.weight(1f))
        if (cmV != null) androidx.compose.material3.TextButton({ cmDel = true }) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.set_cmod_remove)) }
    }
    SettingSwitch(cmAuto, { cmAuto = it; CloudModule.setAuto(ctx, it) }, androidx.compose.ui.res.stringResource(R.string.set_cmod_auto), androidx.compose.ui.res.stringResource(R.string.set_cmod_auto_hint))
    if (cmDel) LAlertDialog(
        onDismissRequest = { cmDel = false },
        title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.cmu_remove_title)) },
        text = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.cmu_remove_text), fontSize = 14.sp) },
        confirmButton = { androidx.compose.material3.TextButton({ cmDel = false; Fm.scope.launch { Fm.say(CloudModule.uninstall(ctx)) } }) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.cmu_remove)) } },
        dismissButton = { androidx.compose.material3.TextButton({ cmDel = false }) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.btn_cancel)) } },
    )
    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.set_open_files), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    var remTick by remember { mutableStateOf(0) }
    val remembered = remember(remTick, UiPrefs.openAs.size) {
        val l = ArrayList<Pair<String, String>>()
        UiPrefs.openAs.forEach { (k, v) -> if (k.startsWith("ext:")) l += k.removePrefix("ext:") to (if (v == "builtin") "builtin" else "cmp:$v") }
        listOf("apk", "apks", "apkm", "xapk").forEach { e -> ApkInstall.saved(ctx, e)?.let { l += e to it } }
        l.sortedBy { it.first }
    }
    if (remembered.isEmpty()) androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.inst_ask), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
    remembered.forEach { (e, id) ->
        val what = when {
            id == "builtin" -> androidx.compose.ui.res.stringResource(R.string.oa_builtin)
            id == "system" -> androidx.compose.ui.res.stringResource(R.string.inst_system)
            id == "archive" -> androidx.compose.ui.res.stringResource(R.string.arc_open_as_archive)
            id == "adb" -> androidx.compose.ui.res.stringResource(R.string.inst_adb_phon)
            else -> runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(id.removePrefix("cmp:").substringBefore(Char(47)), 0)).toString() }.getOrDefault(id)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Text("." + e + "  →  " + what, Modifier.weight(1f), fontSize = 13.sp)
            androidx.compose.material3.TextButton({ ApkInstall.forget(ctx, e); remTick++ }) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.rt_forget)) }
        }
    }
    if (remembered.isNotEmpty()) androidx.compose.material3.TextButton({ remembered.forEach { (e, _) -> ApkInstall.forget(ctx, e) }; remTick++ }) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.reset_all)) }
    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.set_dir_tap), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    EqChoices(listOf(
        EqChoice(androidx.compose.ui.res.stringResource(R.string.dt_expand), Fm.dirTapExpand) { Fm.dirTapExpand = true; ctx.getSharedPreferences("fm", Context.MODE_PRIVATE).edit().putString("dir_tap", "expand").apply() },
        EqChoice(androidx.compose.ui.res.stringResource(R.string.dt_open), !Fm.dirTapExpand) { Fm.dirTapExpand = false; ctx.getSharedPreferences("fm", Context.MODE_PRIVATE).edit().putString("dir_tap", "open").apply() },
    ))
    Row(Modifier.fillMaxWidth().clickable { colorDlg = true }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FolderIcon(UiPrefs.folderDefault, false, 32.dp)
        Text(androidx.compose.ui.res.stringResource(R.string.set_folder_color), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    }
    var typeIcons by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { typeIcons = true }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FileGlyph(FCat.PHOTO, "", 32.dp)
        Text(androidx.compose.ui.res.stringResource(R.string.ic_types), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    }
    if (typeIcons) FileTypeIconsDialog { typeIcons = false }
    if (colorDlg) FolderColorDialog(androidx.compose.ui.res.stringResource(R.string.set_folder_color), UiPrefs.folderDefault, false, { i -> if (i != null) UiPrefs.setFolderDefault(ctx, i); colorDlg = false }, { colorDlg = false })
}

@Composable
private fun Modifier.androidx_scroll(): Modifier = this.then(Modifier.heightIn(max = 360.dp)).then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))
