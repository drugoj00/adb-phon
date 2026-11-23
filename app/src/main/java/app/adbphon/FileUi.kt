package app.adbphon

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.drawWithCache
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
import kotlinx.coroutines.async
import java.io.File

/** Настройки внешнего вида списков «Файлов»: узкий шрифт, цвета папок, «Открывать как». Хранятся в prefs «fm» и входят в резервную копию. */
object UiPrefs {
    var narrow by mutableStateOf(true)
    /** Миниатюры фото и видео вместо значков (по умолчанию включены). */
    var thumbs by mutableStateOf(true)
    /** 20.11.2026 (4PDA, wad72): значок самого приложения у .apk (из файла), как в Total Commander; по умолчанию включён. */
    var apkIcons by mutableStateOf(true)
    /** 20.11.2026: цвет стрелок раскрытия у папок: 0 авто (в цвет текста), 1 акцент темы, 2 синий (как раньше), 3 свой цвет [arrowCustom]. */
    var arrowMode by mutableStateOf(0)
    var arrowCustom by mutableStateOf(0xFF1E88E5.toInt())
    /** 20.11.2026: компактные отступы дерева (точка слева, шаг уровня, стрелка): строки шире, место освобождается; по умолчанию включено. */
    var compactTree by mutableStateOf(true)
    /** 20.11.2026: боковая панель кнопок «Файлов»: false = первые кнопки и «Ещё» (как раньше), true = все кнопки в прокручиваемой колонке без «Ещё». */
    var sideScroll by mutableStateOf(false)
    /** 20.11.2026: масштаб кнопок боковой панели: 0 авто, 1 крупнее (+15%), 2 ещё крупнее (+30%). */
    var sideScale by mutableStateOf(0)
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
        apkIcons = p.getBoolean("apk_icons", true)
        arrowMode = p.getInt("arrow_mode", 0).coerceIn(0, 3)
        arrowCustom = p.getInt("arrow_custom", 0xFF1E88E5.toInt())
        compactTree = p.getBoolean("compact_tree", true)
        sideScroll = p.getBoolean("side_scroll", false)
        sideScale = p.getInt("side_scale", 0).coerceIn(0, 2)
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
        InstallerStore.get(ctx)   // 10.0: единый выбор установщика читается при запуске (перенос старых настроек)
        if (Thumbs.TEST_HOOK) AppLog.enabled = true   // тестовая сборка: журнал включён без настройки
    }

    fun setAskInstaller(ctx: Context, v: Boolean) { askInstaller = v; sp(ctx).edit().putBoolean("ask_installer", v).apply() }
    fun setThumbs(ctx: Context, v: Boolean) { thumbs = v; sp(ctx).edit().putBoolean("thumbs", v).apply() }
    fun setApkIcons(ctx: Context, v: Boolean) { apkIcons = v; sp(ctx).edit().putBoolean("apk_icons", v).apply() }
    fun setArrowMode(ctx: Context, m: Int) { arrowMode = m; sp(ctx).edit().putInt("arrow_mode", m).apply() }
    fun setArrowCustom(ctx: Context, c: Int) { arrowCustom = c; arrowMode = 3; sp(ctx).edit().putInt("arrow_custom", c).putInt("arrow_mode", 3).apply() }
    fun setCompactTree(ctx: Context, v: Boolean) { compactTree = v; sp(ctx).edit().putBoolean("compact_tree", v).apply() }
    fun setSideScroll(ctx: Context, v: Boolean) { sideScroll = v; sp(ctx).edit().putBoolean("side_scroll", v).apply() }
    fun setSideScale(ctx: Context, v: Int) { sideScale = v; sp(ctx).edit().putInt("side_scale", v).apply() }
    fun setNarrow(ctx: Context, v: Boolean) { narrow = v; sp(ctx).edit().putBoolean("narrow_font", v).apply() }
    fun setFolderDefault(ctx: Context, i: Int) { folderDefault = i; sp(ctx).edit().putInt("folder_default", i).apply() }
    fun setFolderColor(ctx: Context, key: String, i: Int?) {
        if (i == null) folderColors.remove(key) else folderColors[key] = i
        sp(ctx).edit().putString("folder_colors", folderColors.entries.joinToString("\n") { it.key + "=" + it.value }).apply()
    }
    fun setOpenAs(ctx: Context, cat: String, v: String) { openAs[cat] = v; sp(ctx).edit().putString("open_as_$cat", v).apply() }

    /** Узкий системный шрифт того же размера: в строку помещается больше текста. null: обычный. */
    val listFont: FontFamily? get() = if (narrow) CondFont else null
    /** 10.5: жирное начертание узкого шрифта (семейство из готового шрифта не меняет толщину само). */
    val listFontBold: FontFamily? get() = if (narrow) CondFontBold else null
}

/** Цвет стрелок раскрытия папок по настройке «Цвет стрелок у папок». */
@Composable
fun treeArrowColor(): Color = when (UiPrefs.arrowMode) {
    1 -> MaterialTheme.colorScheme.primary
    2 -> Color(0xFF1E88E5)
    3 -> Color(UiPrefs.arrowCustom)
    else -> MaterialTheme.colorScheme.onSurface
}

/** Размеры дерева (dp): при «Компактных отступах» точка слева, шаг уровня и стрелка уже. */
object TreeDim {
    val ind: Int get() = if (UiPrefs.compactTree) 8 else 11
    val pad: Int get() = if (UiPrefs.compactTree) 4 else 8
    val dotW: Int get() = if (UiPrefs.compactTree) 11 else 16
    fun arrow(tv: Boolean): Int = if (tv) 30 else if (UiPrefs.compactTree) 16 else 20
    val fmArrow: Int get() = if (UiPrefs.compactTree) 18 else 22
    /**
     * 15.0 код 75 (просьба wad72, 4PDA): дерево «Источников», обычный (не компактный) режим, строки вложенного уровня: промежуток точка–стрелка был 13,9 dp, стрелка–значок 9,2 dp (измерено на A14);
     * добавка после поля стрелки уравнивает их. В компактном режиме промежутки уже равны (7,8 и 7,5 dp), добавка 0.
     */
    fun rootSp(level: Int): Int = if (!UiPrefs.compactTree && level > 0) 5 else 0
    /**
     * 16.0 код 79 (4PDA, wad72: «промежутки в дереве у меня не меняются»): код 75 выровнял только дерево «Источников», а список «Файлов» в режиме дерева (FileManager, строки папки с треугольником)
     * остался прежним (на A14 видимые промежутки точка–стрелка и стрелка–значок различались, цифры в журнале отчёта). Поле стрелки теперь 18 dp (компактно) или 22 dp, видимый треугольник 12 dp стоит в нём не по центру, а с отступом слева:
     * промежутки между краем точки, видимой частью треугольника и значком равны (закрытый и раскрытый треугольник считаются отдельно), общая ширина строки не меняется.
     */
    fun paneArrowInk(open: Boolean): Float = (fmArrow - 12 - (dotW - 5) / 2f - (if (open) -1.2f else 1.8f)) / 2f   // закрытый треугольник пустой слева на 1,8 dp (0,15 от 12); раскрытый занимает все 12 dp, а значок открытой папки сам уже на 1,2 dp
}

/** 16.0 код 79: строки журнала про дерево папок: плотность экрана, масштаб шрифта и применённые отступы (раз для каждого сочетания), чтобы по журналу было видно, что реально нарисовано. */
object TreeLog {
    private val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Composable
    fun Note(kind: String, level: Int) {
        if (!AppLog.enabled) return
        val dm = androidx.compose.ui.platform.LocalDensity.current
        val cfg = androidx.compose.ui.platform.LocalConfiguration.current
        androidx.compose.runtime.LaunchedEffect(kind, level, dm.density, dm.fontScale, UiPrefs.compactTree) {
            val key = "$kind|$level|${dm.density}|${dm.fontScale}|${UiPrefs.compactTree}"
            if (seen.add(key)) AppLog.i("TreeLog", "TreeLog: $kind, уровень $level: плотность ${dm.density} (${cfg.densityDpi} dpi), масштаб шрифта ${dm.fontScale}, экран ${cfg.screenWidthDp}x${cfg.screenHeightDp} dp, " +
                "Android ${Build.VERSION.SDK_INT} ${Build.MANUFACTURER}, компактные отступы=${UiPrefs.compactTree}, точка ${TreeDim.dotW} dp, шаг уровня ${TreeDim.ind} dp, левый отступ ${TreeDim.pad} dp, " +
                "поле стрелки ${TreeDim.arrow(false)} dp (список «Файлов» ${TreeDim.fmArrow} dp, отступ треугольника ${"%.2f".format(TreeDim.paneArrowInk(false))}/${"%.2f".format(TreeDim.paneArrowInk(true))} dp (закрыт/раскрыт)), добавка после стрелки ${TreeDim.rootSp(level)} dp, " +
                "значок 28/32 dp (ТВ), ключ=$key")
        }
    }

    /** Измеренное положение: [what] = стрелка (левый край, ширина) и текст (левый край) в dp относительно строки; пишется раз для каждого уровня и сочетания. */
    fun measured(kind: String, level: Int, density: Float, fontScale: Float, what: String, xPx: Float, wPx: Float) {
        if (!AppLog.enabled) return
        val key = "m|$kind|$level|$density|$fontScale|${UiPrefs.compactTree}|$what"
        if (seen.add(key)) AppLog.i("TreeLog", "TreeLog: измерено $kind, уровень $level: $what ширина ${"%.1f".format(wPx / density)} dp (${"%.0f".format(wPx)} px при плотности $density)")
    }
}

val CondFont: FontFamily = FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
val CondFontBold: FontFamily = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD))

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
    // 14.0: контуры и градиенты строятся один раз на размер (drawWithCache), а не на каждый кадр и каждую строку списка (на слабой приставке значки папок съедали заметную часть отрисовки)
    androidx.compose.foundation.layout.Spacer(modifier.size(size).drawWithCache {
        val w = this.size.width; val h = this.size.height
        val r = CornerRadius(w * 0.09f)
        val shadow = Color.Black.copy(alpha = 0.18f)
        val back = Path().apply {
            moveTo(w * 0.04f, h * 0.20f); lineTo(w * 0.36f, h * 0.20f); lineTo(w * 0.45f, h * 0.31f); lineTo(w * 0.96f, h * 0.31f); lineTo(w * 0.96f, h * 0.86f); lineTo(w * 0.04f, h * 0.86f); close()
        }
        val backBrush = Brush.verticalGradient(listOf(bot, bot.copy(alpha = 0.9f)), 0f, h)
        val front = if (open) Path().apply { moveTo(w * 0.14f, h * 0.42f); lineTo(w * 1.0f, h * 0.42f); lineTo(w * 0.86f, h * 0.88f); lineTo(w * 0.0f, h * 0.88f); close() } else null
        val frontBrush = if (open) Brush.verticalGradient(listOf(top, bot), h * 0.42f, h * 0.88f) else Brush.verticalGradient(listOf(top, bot), h * 0.38f, h * 0.88f)
        val shine = Color.White.copy(alpha = 0.28f)
        onDrawBehind {
            drawRoundRect(shadow, Offset(w * 0.05f, h * 0.30f), Size(w * 0.92f, h * 0.66f), r)
            drawPath(back, backBrush)
            if (front != null) drawPath(front, frontBrush)
            else {
                drawRoundRect(frontBrush, Offset(0f, h * 0.38f), Size(w, h * 0.50f), r)
                drawRoundRect(shine, Offset(w * 0.04f, h * 0.40f), Size(w * 0.92f, h * 0.05f), CornerRadius(w * 0.02f))
            }
        }
    })
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
    "apk", "apks", "xapk", "apkm" -> FCat.APK
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
    private val none = HashMap<String, Long>()   // ключ -> когда не вышло; значки APK пробуются снова через 20 с (11.0), остальное один раз
    private val gate = Semaphore(2)
    private val apkGate = Semaphore(2)       // 11.0: значки APK не ждут миниатюры фото и видео (зависшее чтение видео держало оба места)
    private val remoteGate = Semaphore(1)    // скачивание APK с телефона по ADB и из сети ради значка: по одному
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

    /** Ключ значка APK: путь, размер и время файла (заменённый файл с тем же именем получает новый значок). */
    fun apkKey(path: String): String {
        val f = java.io.File(path)
        return "a:$path:${f.length()}:${f.lastModified()}"
    }

    private fun drawBmp(d: android.graphics.drawable.Drawable): Bitmap =
        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { b -> val c = android.graphics.Canvas(b); d.setBounds(0, 0, 96, 96); d.draw(c) }

    /** Строка журнала про значки APK. В файле журнала метки (тега) нет, поэтому «ApkIcon» стоит в самом тексте: по нему строки ищут. */
    private fun lg(msg: String) { AppLog.w("ApkIcon", "ApkIcon: $msg") }

    /** 16.2: какая ветка показа значка выбрана для .apk и почему (одна строка на файл и ветку раз в 10 минут). Нужна, чтобы по журналу видеть, доходит ли дело до чтения значка. */
    fun apkBranch(name: String, path: String?, branch: String) {
        if (!AppLog.enabled) return
        val key = "br:$name|$branch"
        val now = System.currentTimeMillis()
        val skip = synchronized(logTimes) { val t = logTimes[key]; if (t != null && now - t < 600_000L) true else { logTimes[key] = now; false } }
        if (!skip) lg("ветка=$branch файл=$name путь=${path ?: "нет"} настройка=${if (UiPrefs.apkIcons) "вкл" else "выкл"}")
    }

    /** 16.2: в начале показа папки: сколько в ней .apk и при каких условиях (раз в 30 с на папку). */
    fun logList(mode: String, path: String, apkCount: Int) {
        if (!AppLog.enabled) return
        val key = "ls:$mode|$path"
        val now = System.currentTimeMillis()
        val skip = synchronized(logTimes) { val t = logTimes[key]; if (t != null && now - t < 30_000L) true else { logTimes[key] = now; false } }
        if (skip) return
        lg("список режим=$mode path=$path apk=$apkCount настройка=${if (UiPrefs.apkIcons) "вкл" else "выкл"} Android=${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT}) версия=${appVersionText()} все_файлы=${allFilesText()} канал_оболочки=${Adb.bytesChannel() ?: "нет"}")
    }

    fun appVersionText(): String = runCatching { val pi = FmIo.app.packageManager.getPackageInfo(FmIo.app.packageName, 0); "${pi.versionName}(код ${pi.longVersionCode})" }.getOrDefault("?")
    fun allFilesText(): String = if (Build.VERSION.SDK_INT >= 30) (if (runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)) "да" else "нет") else "н/д"

    /** Тестовая проверка (TEST_HOOK): файл apkicon_force.txt в папке файлов приложения (Android/data/app.adbphon/files): 1 = как будто система значок не отдала, 2 = и разбор архива пропускается. В выпуске false. */
    const val TEST_HOOK = false
    /** Тест (TEST_HOOK): файл apkicon_shell.txt: 1 = значок через оболочку сразу, даже если файл читается; 2 = то же, но без unzip (свой разбор zip); 3 = диагностика канала; 4 = считать файл закрытым от приложения (canRead=false). */
    private fun shellForce(): Int = if (!TEST_HOOK) 0 else runCatching { java.io.File(FmIo.app.getExternalFilesDir(null), "apkicon_shell.txt").readText().trim().toInt() }.getOrDefault(0)
    private fun forceMode(): Int = if (!TEST_HOOK) 0 else runCatching { java.io.File(FmIo.app.getExternalFilesDir(null), "apkicon_force.txt").readText().trim().toInt() }.getOrDefault(0)

    // ---- 17.0 (код 81): значок APK через оболочку устройства (без копирования файла) и кэш на диске ----

    private val shellGate = java.util.concurrent.Semaphore(2)     // чтение через оболочку: не больше двух файлов сразу (как значки APK)
    private val shellFail = HashMap<String, Long>()               // ключ -> когда оболочка значка не дала: повтор не раньше чем через 5 минут
    private val logTimes = HashMap<String, Long>()                // ключ -> когда писали в журнал: одна строка на файл раз в 10 минут (список прокручивается и перерисовывается)

    private fun lgOnce(key: String, msg: String) {
        val now = System.currentTimeMillis()
        val skip = synchronized(logTimes) { val t = logTimes[key]; if (t != null && now - t < 600_000L) true else { logTimes[key] = now; false } }
        if (!skip) lg(msg)
    }

    private fun shellFailedRecently(key: String): Boolean = synchronized(shellFail) { shellFail[key]?.let { System.currentTimeMillis() - it < 300_000L } == true }

    private fun diskIcons(ctx: Context) = java.io.File(ctx.cacheDir, "apkicons").apply { mkdirs() }
    /** Тест (TEST_HOOK): файл apkicon_salt.txt добавляется к ключу кэша значков на диске (новое значение = кэш как пустой). */
    private fun cacheSalt(): String = if (!TEST_HOOK) "" else runCatching { java.io.File(FmIo.app.getExternalFilesDir(null), "apkicon_salt.txt").readText().trim() }.getOrDefault("")
    private val appVer: Long by lazy { runCatching { FmIo.app.packageManager.getPackageInfo(FmIo.app.packageName, 0).longVersionCode }.getOrDefault(0L) }
    /** Кэш значков на диске: ключ = путь + размер + время файла + версия программы (после обновления разбор мог улучшиться). */
    private fun diskGet(ctx: Context, key: String): Bitmap? {
        val png = java.io.File(diskIcons(ctx), sha1("$key|v$appVer${cacheSalt()}") + ".png")
        return if (png.isFile) runCatching { BitmapFactory.decodeFile(png.path) }.onFailure { lgOnce("dg:$key", "кэш значка на диске не читается: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull() else null
    }
    private fun diskPut(ctx: Context, key: String, b: Bitmap) {
        val dir = diskIcons(ctx)
        runCatching { java.io.File(dir, sha1("$key|v$appVer${cacheSalt()}") + ".png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }; prune(dir) }
            .onFailure { lgOnce("dp:$key", "значок не записан в кэш на диске: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** Размер и время файла по оболочке («размер:секунды»): у файла, закрытого от приложения, File.length() и lastModified() дают 0. null: не вышло. */
    private fun shellStat(path: String, anyHost: Boolean): String? {
        val q = "'" + path.replace("'", "'\\''") + "'"
        // ровно 2 раза по 20 цифр: ответ известной длины (связь ADB не закрывает поток вовремя)
        val r = Adb.execBytes("printf '%020d%020d' \$(stat -c %s $q 2>/dev/null) \$(stat -c %Y $q 2>/dev/null)", 100, 2500L, anyHost, 40)
        val t = r.data?.toString(Charsets.UTF_8)?.trim() ?: return null
        if (t.length != 40) return null
        val sz = t.substring(0, 20).toLongOrNull() ?: return null
        val mt = t.substring(20).toLongOrNull() ?: return null
        return if (sz > 0) "$sz:$mt" else null
    }

    /**
     * Значок APK из частей архива, прочитанных оболочкой устройства (`unzip -p`, при его отсутствии свой разбор zip по смещениям): без копирования файла и без
     * ограничения размера. Причины неудач дописываются в [why] (в журнал). null: не вышло.
     */
    private fun iconViaShell(path: String, anyHost: Boolean, why: StringBuilder): Bitmap? {
        val ch = Adb.bytesChannel(anyHost)
        if (ch == null) { why.append("через оболочку: нет канала (ни root, ни ADB, ни помощника), файл приложению не открывается: нет прав; "); return null }
        val sh = object : ShellApk.Sh {
            override val channel: String = ch
            override val reliable: Boolean = ch != "ADB"
            override fun run(cmd: String, max: Int, timeoutMs: Long, expect: Int) = Adb.execBytes(cmd, max, timeoutMs, anyHost, expect)
        }
        shellGate.acquire()
        try {
            val src = ShellApk(path, sh, noUnzip = shellForce() == 2)   // время считается с момента, когда место в очереди получено
            val r = ApkIconRes.resolve(src)
            val d = r.root
            val bmp = if (d != null) ApkIconDraw.render(src, d) else null
            why.append("через оболочку (").append(src.stats()).append("): ").append(r.trace)
            if (d != null && bmp == null) why.append("; рисунок не нарисован (").append(ApkIconRes.describe(d)).append(")")
            if (bmp == null && src.err.isNotEmpty()) why.append("; причина: ").append(src.err)
            why.append("; ")
            return bmp
        } catch (e: Throwable) {
            why.append("через оболочку: ${e.javaClass.simpleName}: ${e.message}; ")
            return null
        } finally { shellGate.release() }
    }

    /** Тест (TEST_HOOK, apkicon_shell.txt = 3): проверка, как связь ADB отдаёт большие ответы `exec:`; итог строкой в журнал. */
    private fun shellDiag(path: String) {
        val sb = StringBuilder("DIAG канал=${Adb.bytesChannel() ?: "нет"}: ")
        for (n in listOf(1_000, 70_000, 300_000, 1_000_000, 3_000_000, 10_000_000, 30_000_000, 70_000_000)) {
            val t = System.currentTimeMillis()
            val r = Adb.execBytes("head -c $n " + "'" + path.replace("'", "'\\''") + "'", 200_000_000, 20_000L, true)
            sb.append("$n->${r.data?.size ?: -1}${if (r.err.isNotEmpty()) "[" + r.err + "]" else ""} ${System.currentTimeMillis() - t}мс; ")
        }
        for (n in listOf(70_000, 1_000_000, 10_000_000)) {   // то же через dd блоками
            val t = System.currentTimeMillis()
            val r = Adb.execBytes("dd if='" + path + "' bs=4096 count=${n / 4096} 2>/dev/null", 200_000_000, 20_000L, true)
            sb.append("dd$n->${r.data?.size ?: -1}${if (r.err.isNotEmpty()) "[" + r.err + "]" else ""} ${System.currentTimeMillis() - t}мс; ")
        }
        lg(sb.toString())
    }

    private val defaultIcon: Bitmap? by lazy { runCatching { drawBmp(FmIo.app.packageManager.defaultActivityIcon) }.getOrNull() }

    /** Значок «по умолчанию» (робот системы): Android отдаёт его вместо ошибки, когда не смог прочитать ресурсы APK. */
    private fun isDefaultIcon(pm: android.content.pm.PackageManager, d: android.graphics.drawable.Drawable, bmp: Bitmap): Boolean {
        val cs = d.constantState
        if (cs != null && cs === runCatching { pm.defaultActivityIcon.constantState }.getOrNull()) return true
        return defaultIcon?.let { runCatching { it.sameAs(bmp) }.getOrDefault(false) } == true
    }

    /**
     * Значок самого приложения из APK. null: файл не читается или значка нет.
     * 16.0 код 79 (4PDA, wad72: Android 16, realme UI 7: «у обоих просто значки APK»): порядок способов и подробная причина в журнале по каждому:
     * 1) система (getPackageArchiveInfo + loadIcon; значок «по умолчанию» и исключения не принимаются за успех, вместо loadIcon пробуется прямой getDrawableForDensity),
     * 2) свой разбор архива: манифест + таблица ресурсов resources.arsc (ApkIconRes и ApkIconDraw: картинки с любыми именами, адаптивные, векторные значки),
     * 3) прежний запасной способ: картинка с именем ic_launcher/icon из zip.
     */
    private fun iconOfApk(ctx: Context, path: String, why: StringBuilder): Bitmap? {
        val pm = ctx.packageManager
        val force = forceMode()
        fun fin(b: Bitmap?, how: String): Bitmap? { if (b != null) why.append("получен способом «$how»; "); return b }
        // 1. система
        if (force < 1) {
            val info = try { pm.getPackageArchiveInfo(path, 0)?.applicationInfo } catch (e: Throwable) { why.append("getPackageArchiveInfo: ${e.javaClass.simpleName}: ${e.message}; "); null }
            if (info == null) { if (why.isEmpty()) why.append("getPackageArchiveInfo вернул null (манифест не разобран); ") }
            else {
                info.sourceDir = path; info.publicSourceDir = path
                val viaLoad = try {
                    val d = info.loadIcon(pm); val bmp = drawBmp(d)
                    if (isDefaultIcon(pm, d, bmp)) { why.append("loadIcon вернул значок по умолчанию (info.icon=0x${Integer.toHexString(info.icon)}); "); null } else bmp
                } catch (e: Throwable) { why.append("loadIcon: ${e.javaClass.simpleName}: ${e.message}; "); null }
                if (viaLoad != null) return fin(viaLoad, "система")
                if (info.icon != 0) {
                    // loadIcon молча подменяет ошибку значком по умолчанию: ресурс читается напрямую, чтобы увидеть настоящую причину
                    try {
                        val d = pm.getResourcesForApplication(info).getDrawableForDensity(info.icon, android.util.DisplayMetrics.DENSITY_XXHIGH, null)
                        if (d != null) { val bmp = drawBmp(d); if (!isDefaultIcon(pm, d, bmp)) return fin(bmp, "getDrawableForDensity") }
                        why.append("getDrawableForDensity: пусто; ")
                    } catch (e: Throwable) { why.append("getDrawableForDensity: ${e.javaClass.simpleName}: ${e.message}; ") }
                } else why.append("у приложения нет значка в манифесте (для системы); ")
            }
        } else why.append("проверка: система пропущена; ")
        // 2. свой разбор манифеста и таблицы ресурсов
        if (force < 2) {
            try {
                java.util.zip.ZipFile(path).use { z ->
                    val r = ApkIconRes.resolve(z)
                    why.append("разбор архива: ").append(r.trace).append("; ")
                    val d = r.root
                    if (d != null) {
                        val bmp = ApkIconDraw.render(z, d)
                        if (bmp != null) return fin(bmp, "разбор архива")
                        why.append("рисунок не нарисован (${ApkIconRes.describe(d)}); ")
                    }
                }
            } catch (e: Throwable) { why.append("разбор архива: ${e.javaClass.simpleName}: ${e.message}; ") }
        } else why.append("проверка: разбор архива пропущен; ")
        // 3. картинка по имени
        return fin(iconFromZip(path, why), "картинка по имени")
    }

    /** Запасной значок: файл-картинка значка внутри APK по имени (ic_launcher, icon, app_icon). Адаптивный значок без готовой картинки не поддерживается. */
    private fun iconFromZip(path: String, why: StringBuilder): Bitmap? = try {
        java.util.zip.ZipFile(path).use { z ->
            val cand = z.entries().asSequence().filter { !it.isDirectory }.filter { e ->
                val n = e.name.lowercase()
                n.startsWith("res/") && (n.endsWith(".png") || n.endsWith(".webp")) &&
                    (n.contains("/ic_launcher") || n.contains("/icon") || n.contains("/app_icon")) &&
                    !n.contains("foreground") && !n.contains("background") && !n.contains("monochrome")
            }.toList()
            fun dens(n: String) = when { "xxxhdpi" in n -> 5; "xxhdpi" in n -> 4; "xhdpi" in n -> 3; "hdpi" in n -> 2; "mdpi" in n -> 1; else -> 0 }
            val best = cand.maxWithOrNull(compareBy({ dens(it.name.lowercase()) }, { it.size }))
            if (best == null) { why.append("в zip нет картинки значка по имени; "); null }
            else z.getInputStream(best).use { BitmapFactory.decodeStream(it) }?.let { Bitmap.createScaledBitmap(it, 96, 96, true) }
        }
    } catch (e: Throwable) { why.append("значок из zip: ${e.javaClass.simpleName}: ${e.message}; "); null }

    /** APKS, XAPK, APKM: значок из «icon.png» набора или из основного APK внутри (его приходится вынуть во временный файл). */
    private fun iconOfBundle(ctx: Context, path: String, tmpDir: java.io.File, why: StringBuilder): Bitmap? {
        java.util.zip.ZipFile(path).use { z ->
            val all = z.entries().asSequence().filter { !it.isDirectory }.toList()
            all.firstOrNull { it.name.equals("icon.png", true) }?.let { e ->
                z.getInputStream(e).use { BitmapFactory.decodeStream(it) }?.let { return Bitmap.createScaledBitmap(it, 96, 96, true) }
            }
            val apks = all.filter { it.name.endsWith(".apk", true) }
            val base = apks.firstOrNull { it.name.substringAfterLast('/').equals("base.apk", true) }
                ?: apks.filter { n -> n.name.substringAfterLast('/').let { !it.startsWith("config.", true) && !it.startsWith("split_", true) } }.maxByOrNull { it.size }
                ?: apks.maxByOrNull { it.size } ?: return null
            val out = java.io.File(tmpDir, "b" + System.nanoTime().toString(36) + ".apk")
            try {
                z.getInputStream(base).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                return iconOfApk(ctx, out.path, why)
            } finally { out.delete() }
        }
    }

    /**
     * Значок приложения из APK. Порядок (17.0 код 81):
     * 1) файл приложению открывается (canRead): система + свой разбор, как раньше; не вышло, а есть оболочка: части архива через оболочку;
     * 2) файл закрыт (Android 11+, realme UI, карта памяти): СРАЗУ части архива через оболочку (`unzip -p` / разбор zip по смещениям, ADB, помощник или root),
     *    без копирования и для файла любого размера; затем прежние запасные пути (SAF до 400 МБ, копия через ADB до 150 МБ).
     * Результат кэшируется на диске (путь + размер + время файла + версия программы). Набор APKS/XAPK/APKM: значок из него или из основного APK внутри.
     * Не вышло: null (список покажет нейтральный значок), в журнал одна строка «ApkIcon:» с путём, размером, canRead, способом и причиной.
     */
    suspend fun apkIcon(ctx: Context, path: String): Bitmap? = load(apkKey(path), apkGate) {
        val tmpDir = java.io.File(ctx.cacheDir, "apkicon").apply { mkdirs() }
        val copy = java.io.File(tmpDir, "c" + System.nanoTime().toString(36))
        val why = StringBuilder()
        val f = java.io.File(path)
        val can = f.canRead() && shellForce() != 4   // тест: 4 = как будто файл приложению закрыт
        val ext = path.substringAfterLast('.', "").lowercase()
        val saf = SafAccess.needs(path)
        val safOk = saf && SafAccess.covered(path)
        var size = f.length()
        var dkey = apkKey(path)
        val t0 = System.currentTimeMillis()
        lgOnce("st:$path", "старт чтения значка: $path (читается=$can, размер=$size, SAF нужен=$saf, расширение=$ext, канал оболочки=${Adb.bytesChannel() ?: "нет"})")
        fun head() = "$path ($size Б, читается=$can, SAF нужен=$saf, SAF выдан=$safOk, Android ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}, канал оболочки=${Adb.bytesChannel() ?: "нет"}, ${System.currentTimeMillis() - t0} мс)"
        try {
            if (!can && Adb.bytesChannel() != null) {
                // у файла, закрытого от приложения, размер и время для ключа кэша берутся у оболочки
                shellStat(path, false)?.let { st -> dkey = "a:$path:$st"; size = st.substringBefore(':').toLongOrNull() ?: size }
            }
            if (shellForce() == 3) { shellDiag(path); return@load null }
            if (shellForce() !in 1..3) diskGet(ctx, dkey)?.let { return@load it }
            fun done(b: Bitmap?): Bitmap? {
                if (b != null) { diskPut(ctx, dkey, b); if (why.isNotEmpty()) lgOnce("ok:$dkey", "значок получен: ${head()}; $why") }
                return b
            }
            var shellTried = false
            fun viaShell(): Bitmap? {
                shellTried = true
                if (shellFailedRecently(dkey)) { why.append("через оболочку: недавно не вышло, повтор позже; "); return null }
                val b = iconViaShell(path, false, why)
                if (b == null) synchronized(shellFail) { shellFail[dkey] = System.currentTimeMillis() }
                return b
            }
            if (ext == "apk" && (!can || shellForce() in 1..2) && Adb.bytesChannel() != null) viaShell()?.let { return@load done(it) }
            var src = path
            if (!can && saf && safOk) {
                if ((SafAccess.stat(path)?.size ?: Long.MAX_VALUE) > 400L * 1024 * 1024) why.append("SAF: файл больше 400 МБ, копия не делается; ")
                else {
                    SafAccess.openIn(path).use { i -> copy.outputStream().use { o -> i.copyTo(o) } }
                    src = copy.path
                    why.append("значок читается из SAF-копии (${copy.length()} Б); ")
                }
            } else if (!can && !saf) {
                // файл приложению не открывается, а список показан: копия через ADB или root (до 150 МБ) и значок из неё
                if (size > 150L * 1024 * 1024) why.append("файл недоступен приложению и больше 150 МБ ($size Б), копия через ADB не делается; ")
                else try {
                    kotlinx.coroutines.runBlocking { Adb.pull(path, copy) }
                    if (copy.length() > 0) { src = copy.path; why.append("значок читается из копии через ADB/root (${copy.length()} Б); ") }
                    else why.append("копия через ADB пустая (ADB не подключён?); ")
                } catch (e: Throwable) { why.append("копия через ADB не вышла: ${e.javaClass.simpleName}: ${e.message}; ") }
            }
            if (!can && src == path && ext == "apk" && !shellTried) why.append("файл недоступен приложению (нет прав), оболочки нет; ")
            val r = if (!can && src == path) null else iconOfFile(ctx, src, ext, tmpDir, why)
            if (r != null) return@load done(r)
            // прямое чтение не дало значка, а оболочка ещё не пробовалась: читаем куски архива оболочкой
            if (ext == "apk" && can && !shellTried && Adb.bytesChannel() != null) viaShell()?.let { return@load done(it) }
            lgOnce("no:$dkey", "значка нет: ${head()}; $why")
            null
        } catch (e: Throwable) {
            lg("сбой чтения значка: ${head()}: ${e.javaClass.simpleName}: ${e.message}; $why")
            throw e
        } finally { copy.delete() }
    }

    private fun iconOfFile(ctx: Context, src: String, ext: String, tmpDir: java.io.File, why: StringBuilder): Bitmap? =
        if (ext == "apk") iconOfApk(ctx, src, why) else iconOfBundle(ctx, src, tmpDir, why)

    /**
     * 11.0: значок APK для источников без обычного пути файла (телефон по ADB, сеть, облако). Сначала дисковый кэш значков; если его нет и [allowFetch],
     * файл (до 150 МБ) копируется во временный файл, значок вынимается и сохраняется в кэш (PNG), копия удаляется. Облако без кэша не качается
     * (трафик и квота): [allowFetch] = false, значок остаётся обычным. Не вышло: null.
     */
    suspend fun apkIconRemote(ctx: Context, key: String, size: Long, ext: String, allowFetch: Boolean, devPath: String? = null, fetch: suspend (java.io.File) -> Unit): Bitmap? {
        val mk = "ar:$key"
        cache.get(mk)?.let { return it }
        lgOnce("st:$mk", "старт чтения значка (удалённый источник): ${devPath ?: key} (размер=$size, загрузка=${if (allowFetch) "да" else "нет"}, канал оболочки=${Adb.bytesChannel(true) ?: "нет"})")
        val dir = java.io.File(ctx.cacheDir, "apkicons").apply { mkdirs() }
        val png = java.io.File(dir, sha1(key + cacheSalt()) + ".png")
        if (png.isFile) runCatching { BitmapFactory.decodeFile(png.path) }.getOrNull()?.let { cache.put(mk, it); return it }
        // 17.0 (код 81): файл лежит на подключённом по ADB устройстве: части архива читаются оболочкой (`unzip -p`), копия не нужна, размер не ограничен
        val shellOk = devPath != null && ext == "apk" && allowFetch && Adb.bytesChannel(true) != null
        if (!shellOk && (!allowFetch || size <= 0L || size > 150L * 1024 * 1024)) {
            if (allowFetch && size > 150L * 1024 * 1024) lgOnce("big:$key", "значка нет: $key ($size Б): файл больше 150 МБ, а оболочки для чтения кусков нет (ADB не подключён?)")
            return null
        }
        if (synchronized(none) { none[mk]?.let { t -> System.currentTimeMillis() - t <= 20_000L } == true }) return null
        val res = apkGate.withPermit {
            remoteGate.withPermit {
                cache.get(mk) ?: withContext(Dispatchers.IO) {
                    val tmpDir = java.io.File(ctx.cacheDir, "apkicon").apply { mkdirs() }
                    val copy = java.io.File(tmpDir, "r" + System.nanoTime().toString(36) + "." + ext)
                    val why = StringBuilder()
                    try {
                        if (shellOk && !shellFailedRecently(mk)) {
                            val b = iconViaShell(devPath!!, true, why)
                            if (b != null) { runCatching { png.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }; prune(dir) }; lgOnce("ok:$mk", "значок получен: $devPath ($size Б, устройство по ADB, Android ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}); $why"); return@withContext b }
                            synchronized(shellFail) { shellFail[mk] = System.currentTimeMillis() }
                        }
                        if (size <= 0L || size > 150L * 1024 * 1024) { lgOnce("no:$mk", "значка нет: $devPath ($size Б, устройство по ADB, Android ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}); $why"); return@withContext null }
                        fetch(copy)
                        val r = iconOfFile(ctx, copy.path, ext, tmpDir, why)
                        if (r == null) lgOnce("no:$mk", "значок не получен из копии: ${devPath ?: key} (размер копии ${copy.length()}, Android ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}); $why")
                        else runCatching { png.outputStream().use { r.compress(Bitmap.CompressFormat.PNG, 90, it) }; prune(dir) }
                        r
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: Throwable) {
                        lg("сбой значка из удалённого файла: $key: ${e.javaClass.simpleName}: ${e.message}")
                        null
                    } finally { copy.delete() }
                }
            }
        }
        if (res != null) { cache.put(mk, res); synchronized(none) { none.remove(mk) } } else synchronized(none) { none[mk] = System.currentTimeMillis() }
        return res
    }

    private fun sha1(s: String): String = java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Дисковый кэш значков не больше 400 файлов: лишние старые удаляются. */
    private fun prune(dir: java.io.File) {
        val all = dir.listFiles() ?: return
        if (all.size > 400) all.sortedBy { it.lastModified() }.take(all.size - 400).forEach { it.delete() }
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

    private suspend fun load(key: String, g: Semaphore = gate, block: () -> Bitmap?): Bitmap? {
        cache.get(key)?.let { return it }
        if (synchronized(none) { none[key]?.let { t -> !((key.startsWith("a:") || key.startsWith("ar:")) && System.currentTimeMillis() - t > 20_000L) } == true }) return null
        val isApk = key.startsWith("a:") || key.startsWith("ar:")
        if (isApk && synchronized(inflight) { key in inflight }) return null   // это чтение ещё идёт (прошлая попытка не закончилась): вторую не запускаем
        return g.withPermit {
            cache.get(key) ?: run {
                val b = if (!isApk) withContext(Dispatchers.IO) { runCatching(block).getOrNull() } else {
                    // 16.2: чтение значка APK идёт в отдельной задаче и ждётся не дольше 25 с: зависшее чтение (медленный файл, система не отвечает) раньше занимало место в очереди (2 места)
                    // навсегда, и у остальных APK значки не появлялись, а в журнале ничего не писалось
                    synchronized(inflight) { inflight += key }
                    val t0 = System.currentTimeMillis()
                    val d = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).let { sc -> sc.async { runCatching(block).getOrNull() } }
                    d.invokeOnCompletion { synchronized(inflight) { inflight -= key }; runCatching { d.getCompleted() }.getOrNull()?.let { cache.put(key, it) } }
                    kotlinx.coroutines.withTimeoutOrNull(25_000L) { d.await() }.also { if (it == null && d.isActive) lgOnce("to:$key", "значок не успел за 25 с, чтение продолжается в фоне: $key (прошло ${System.currentTimeMillis() - t0} мс)") }
                }
                if (b != null) { cache.put(key, b); synchronized(none) { none.remove(key) } } else synchronized(none) { none[key] = System.currentTimeMillis() }
                b
            }
        }
    }
    private val inflight = HashSet<String>()
    private val bgScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
}

/** Дата «2026-11-08 03:32» на экране как «08.11.2026 03:32» (день.месяц.год). */
fun fmDate(s: String): String = Regex("""^(\d{4})-(\d{2})-(\d{2})(.*)$""").replace(s) { m -> "${m.groupValues[3]}.${m.groupValues[2]}.${m.groupValues[1]}${m.groupValues[4]}" }

/**
 * Единый значок файла для списка, дерева и диалогов: цветной значок по типу; для фото и видео (локальные файлы) миниатюра; для APK значок самого
 * приложения; если для типа выбрано «открывать в другом приложении», значок этого приложения.
 */
/** 11.0: APK на источнике без обычного пути (ADB, сеть, облако): ключ, размер и способ получить файл во временную копию. */
class RemoteApk(val key: String, val size: Long, val allowFetch: Boolean, val devPath: String? = null, val fetch: suspend (java.io.File) -> Unit)

@Composable
fun FileTypeIcon(name: String, ext: String, localPath: String?, size: Dp, modifier: Modifier = Modifier, remote: RemoteApk? = null) {
    val customSpec = CustomIcons.specFor(ext, localPath, fileCat(ext), fileOnly = fileCat(ext) == FCat.APK && UiPrefs.apkIcons)
    if (customSpec != null) {
        if (fileCat(ext) == FCat.APK) LaunchedEffect(name) { Thumbs.apkBranch(name, localPath, "свой значок для типа/расширения (CustomIcons), значок из APK не запрашивается") }
        CustomIconView(customSpec, size, modifier); return
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cat = fileCat(ext)
    // запомненный выбор программы для расширения: значок файла = значок этой программы (у фото и видео бейджем в углу поверх миниатюры)
    val ref = ApkInstall.iconRef(ctx, ext)
    val isMedia = cat == FCat.PHOTO || cat == FCat.VIDEO
    // APK и наборы (APKS, XAPK, APKM): значок самого приложения из файла, а не значок установщика («через ADB» и т.п.)
    val other = if (isMedia || cat == FCat.APK) null else ref
    var bmp by remember(localPath, other) { mutableStateOf<Bitmap?>(null) }
    var badge by remember(ref, isMedia) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(ref, isMedia) {
        if (ref == null || !isMedia) { badge = null; return@LaunchedEffect }
        badge = Thumbs.peek(ref) ?: Thumbs.refIcon(ctx, ref)
        if (badge == null) ApkInstall.forget(ctx, ext)       // программу удалили: запоминание снимается, значок возвращается
    }
    val apkOn = UiPrefs.apkIcons
    LaunchedEffect(localPath, other, remote?.key, apkOn) {
        // 20.11.2026 (wad72): настройка «Значок приложения у .apk» выключена: у APK обычный значок типа
        if (cat == FCat.APK && !apkOn) { bmp = null; Thumbs.apkBranch(name, localPath, "настройка «Значок приложения у .apk» выключена"); return@LaunchedEffect }
        if (cat == FCat.APK && localPath == null && remote == null) Thumbs.apkBranch(name, null, "нет пути и нет удалённого источника (localPath=null, remote=null): значок не запрашивается")
        if (localPath == null && remote != null && other == null && cat == FCat.APK) {
            Thumbs.apkBranch(name, remote.devPath, "удалённый источник (устройство по ADB/сеть/облако) размер=${remote.size} загрузка=${if (remote.allowFetch) "да" else "нет"}")
            bmp = Thumbs.peek("ar:" + remote.key) ?: run { delay(150); Thumbs.apkIconRemote(ctx, remote.key, remote.size, ext, remote.allowFetch, remote.devPath, remote.fetch) }
            return@LaunchedEffect
        }
        if (other != null) {
            bmp = Thumbs.peek(other) ?: Thumbs.refIcon(ctx, other)
            if (bmp == null) ApkInstall.forget(ctx, ext)
            return@LaunchedEffect
        }
        if (localPath == null || (!UiPrefs.thumbs && cat != FCat.APK)) return@LaunchedEffect   // 11.0: значок приложения у APK не зависит от «Миниатюр» (они для фото и видео)
        val key = when (cat) { FCat.PHOTO -> "p:$localPath"; FCat.VIDEO -> "v:$localPath"; FCat.APK -> Thumbs.apkKey(localPath); else -> null } ?: return@LaunchedEffect
        if (cat == FCat.APK) Thumbs.apkBranch(name, localPath, "локальный путь: значок читается из файла")
        bmp = Thumbs.peek(key) ?: run {
            delay(120)       // при быстрой прокрутке строка успевает уйти с экрана до загрузки
            when (cat) { FCat.PHOTO -> Thumbs.photo(localPath); FCat.VIDEO -> Thumbs.video(localPath); else -> Thumbs.apkIcon(ctx, localPath) }
        }
        // 11.0: значок APK с первого раза не вышел (файл ещё пишется, доступ только что выдан): одна повторная попытка
        if (bmp == null && cat == FCat.APK) { delay(21_000); bmp = Thumbs.apkIcon(ctx, localPath) }
    }
    val shape = RoundedCornerShape(size * 0.2f)
    // 12.0 (владелица, замечено на слабой приставке x96-2): миниатюра грузится в фоне и раньше подменяла обычный значок мгновенно,
    // уже после того как сама строка доиграла свою анимацию появления — получался отдельный «скачок» одной иконки. Теперь переход
    // от обычного значка к миниатюре (и наоборот, пока грузится следующая папка) идёт через Crossfade, а не мгновенную подмену.
    androidx.compose.animation.Crossfade(bmp, label = "file_icon", animationSpec = androidx.compose.animation.core.tween(150)) { b ->
        if (b != null) {
            Box(modifier.size(size).clip(shape)) {
                Image(b.asImageBitmap(), null, Modifier.size(size), contentScale = if (cat == FCat.PHOTO || cat == FCat.VIDEO) ContentScale.Crop else ContentScale.Fit)
                badge?.let { bd -> Image(bd.asImageBitmap(), null, Modifier.size(size * 0.46f).align(Alignment.BottomEnd).clip(RoundedCornerShape(size * 0.1f))) }
                if (cat == FCat.VIDEO && other == null && badge == null) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(size * 0.28f).align(Alignment.TopStart).padding(2.dp).background(Color.Black.copy(alpha = .5f), CircleShape), tint = Color.White)
                    Thumbs.durations[localPath ?: ""]?.let { ms ->
                        val sec = ms / 1000
                        FloorText(if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60), Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = .6f), RoundedCornerShape(3.dp)).padding(horizontal = 2.dp), fontSize = 8.sp, color = Color.White, maxLines = 1)
                    }
                }
            }
        } else {
            FileGlyph(cat, ext, size, modifier)
        }
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
        confirmButton = { if (showReset) TextButton({ if (iconKey != null) CustomIcons.set(ctxFc, iconKey, null); onPick(null) }) { Text(androidx.compose.ui.res.stringResource(R.string.fc_reset)) } else TextButton(onClose) { Text(androidx.compose.ui.res.stringResource(R.string.btn_close)) } },
        dismissButton = { if (showReset) TextButton(onClose) { Text(androidx.compose.ui.res.stringResource(R.string.btn_close)) } },
    )
}

/** Блок настроек внешнего вида «Файлов»: узкий шрифт, цвет папок по умолчанию, «Открывать как» для фото, аудио и видео. */
@Composable
fun FilesLookSection() {
    FilesBgMusicSetting(); FilesWifiAutoSetting(); FilesThumbsSetting()
    InstallerDefaultSetting()   // 10.0: «Установщик по умолчанию» вместо «Спрашивать, чем устанавливать»
    CloudModuleSetting(); CloudModuleAutoSetting()
    FilesOpenAsSetting(); FilesDirTapSetting(); FilesFolderColorSetting(); FilesTypeIconsSetting()
}

// 10.0 (set): пункты блока по отдельности, чтобы экран «Настройки» раскладывал их по группам («Файлы», «Облака»)
@Composable
fun FilesBgMusicSetting() {
    androidx.compose.runtime.key(PlayerPrefs.rev) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var bg by remember { mutableStateOf(ctx.getSharedPreferences("fm", Context.MODE_PRIVATE).getBoolean("bg_music", false)) }
    SettingSwitch(bg, { bg = it; ctx.getSharedPreferences("fm", Context.MODE_PRIVATE).edit().putBoolean("bg_music", it).apply() }, androidx.compose.ui.res.stringResource(R.string.set_bg_music), androidx.compose.ui.res.stringResource(R.string.set_bg_music_hint))
    }
}

@Composable
fun FilesWifiAutoSetting() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var wa by remember { mutableStateOf(WifiAuto.enabled(ctx)) }
    SettingSwitch(wa, { wa = it; WifiAuto.setEnabled(ctx, it) }, androidx.compose.ui.res.stringResource(R.string.set_wifi_auto), androidx.compose.ui.res.stringResource(R.string.set_wifi_auto_hint))
}

@Composable
fun FilesThumbsSetting() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    SettingSwitch(UiPrefs.thumbs, { UiPrefs.setThumbs(ctx, it) }, androidx.compose.ui.res.stringResource(R.string.set_thumbs), androidx.compose.ui.res.stringResource(R.string.set_thumbs_hint))
}

/** Модуль «ADB-phon Облака»: версия, «Установить»/«Обновить» и «Удалить» (строки-действия, 10.0 set). */
@Composable
fun CloudModuleSetting() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 7.6: модуль «ADB-phon Облака»: версия, автообновление и удаление
    @Suppress("UNUSED_VARIABLE") val cmTick = CloudModule.ver
    val cmV = CloudModule.installedVersion(ctx)
    var cmDel by remember { mutableStateOf(false) }
    // 9.0: обе кнопки всегда на месте: «Установить» (или «Обновить», если есть новая версия) и «Удалить»; неподходящая в этом состоянии серая
    val cmState = CloudModule.state(ctx)
    val cmUp = cmV != null && CloudModule.upgradeAvailable(ctx)
    // 12.0: кнопка живая не только при мягком обновлении (OK -> новее), но и когда стоящая версия вообще не подходит (OUTDATED/WRONG_ABI и т.п.,
    // например старый модуль 1.2 под программу, которой нужен 1.3) — иначе тупик: поставить новую версию можно было только через «Удалить» и заново
    val cmCanInstall = CloudModule.supportedDevice() && (cmV == null || cmUp || cmState != CloudModule.State.OK)
    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        FloorText(androidx.compose.ui.res.stringResource(R.string.set_cmod_title), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        FloorText(if (cmV != null) androidx.compose.ui.res.stringResource(R.string.set_cmod_ver, cmV, CloudModule.variant()?.key ?: "-") else androidx.compose.ui.res.stringResource(R.string.set_cmod_none), fontSize = 13.sp)
        SetAction(SetIcons.download, androidx.compose.ui.res.stringResource(if (cmV != null) R.string.cmod_btn_update else R.string.cmod_btn_install), enabled = cmCanInstall) { CloudModule.reset(); CloudModule.start(ctx, null, upgrade = cmUp) }
        SetAction(SetIcons.delete, androidx.compose.ui.res.stringResource(R.string.set_cmod_remove), enabled = cmV != null, danger = true) { cmDel = true }
    }
    if (cmDel) LAlertDialog(
        onDismissRequest = { cmDel = false },
        title = { FloorText(androidx.compose.ui.res.stringResource(R.string.cmu_remove_title)) },
        text = { FloorText(androidx.compose.ui.res.stringResource(R.string.cmu_remove_text), fontSize = 14.sp) },
        confirmButton = { TextButton({ cmDel = false; Fm.scope.launch { Fm.say(CloudModule.uninstall(ctx)) } }) { FloorText(androidx.compose.ui.res.stringResource(R.string.cmu_remove)) } },
        dismissButton = { TextButton({ cmDel = false }) { FloorText(androidx.compose.ui.res.stringResource(R.string.btn_cancel)) } },
    )
}

@Composable
fun CloudModuleAutoSetting() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var cmAuto by remember(CloudModule.autoTick) { mutableStateOf(CloudModule.auto(ctx)) }
    SettingSwitch(cmAuto, { cmAuto = it; CloudModule.setAuto(ctx, it) }, androidx.compose.ui.res.stringResource(R.string.set_cmod_auto), androidx.compose.ui.res.stringResource(R.string.set_cmod_auto_hint))
}

/** Запомненные программы для типов файлов (пункт виден, только когда есть запомненный выбор). */
@Composable
fun FilesOpenAsSetting() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var remTick by remember { mutableStateOf(0) }
    val remembered = remember(remTick, UiPrefs.openAs.size) {
        val l = ArrayList<Pair<String, String>>()
        UiPrefs.openAs.forEach { (k, v) -> if (k.startsWith("ext:")) l += k.removePrefix("ext:") to (if (v == "builtin") "builtin" else "cmp:$v") }
        l.sortedBy { it.first }
    }
    // 9.0: пункт показывается только когда есть запомненный выбор программы для типа файла; пояснение свёрнуто в названии, одинокого слова «Спрашивать» больше нет
    if (remembered.isNotEmpty()) HintTitle(androidx.compose.ui.res.stringResource(R.string.set_open_files), androidx.compose.ui.res.stringResource(R.string.set_open_files_hint))
    remembered.forEach { (e, id) ->
        val what = when {
            id == "builtin" -> androidx.compose.ui.res.stringResource(R.string.oa_builtin)
            id == "system" -> androidx.compose.ui.res.stringResource(R.string.inst_system)
            id == "archive" -> androidx.compose.ui.res.stringResource(R.string.arc_open_as_archive)
            id == "adb" -> androidx.compose.ui.res.stringResource(R.string.inst_adb_phon)
            else -> runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(id.removePrefix("cmp:").substringBefore(Char(47)), 0)).toString() }.getOrDefault(id)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FloorText("." + e + "  →  " + what, Modifier.weight(1f), fontSize = 13.sp)
            TextButton({ ApkInstall.forget(ctx, e); remTick++ }) { FloorText(androidx.compose.ui.res.stringResource(R.string.rt_forget)) }
        }
    }
    if (remembered.isNotEmpty()) TextButton({ remembered.forEach { (e, _) -> ApkInstall.forget(ctx, e) }; remTick++ }) { FloorText(androidx.compose.ui.res.stringResource(R.string.reset_all)) }
}

@Composable
fun FilesDirTapSetting() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    FloorText(androidx.compose.ui.res.stringResource(R.string.set_dir_tap), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    EqChoices(listOf(
        EqChoice(androidx.compose.ui.res.stringResource(R.string.dt_expand), Fm.dirTapExpand) { Fm.dirTapExpand = true; ctx.getSharedPreferences("fm", Context.MODE_PRIVATE).edit().putString("dir_tap", "expand").apply() },
        EqChoice(androidx.compose.ui.res.stringResource(R.string.dt_open), !Fm.dirTapExpand) { Fm.dirTapExpand = false; ctx.getSharedPreferences("fm", Context.MODE_PRIVATE).edit().putString("dir_tap", "open").apply() },
    ))
}

@Composable
fun FilesFolderColorSetting() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var colorDlg by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { colorDlg = true }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FolderIcon(UiPrefs.folderDefault, false, 32.dp)
        Text(androidx.compose.ui.res.stringResource(R.string.set_folder_color), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    }
    if (colorDlg) FolderColorDialog(androidx.compose.ui.res.stringResource(R.string.set_folder_color), UiPrefs.folderDefault, false, { i -> if (i != null) UiPrefs.setFolderDefault(ctx, i); colorDlg = false }, { colorDlg = false })
}

@Composable
fun FilesTypeIconsSetting() {
    var typeIcons by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { typeIcons = true }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FileGlyph(FCat.PHOTO, "", 32.dp)
        Text(androidx.compose.ui.res.stringResource(R.string.ic_types), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    }
    if (typeIcons) FileTypeIconsDialog { typeIcons = false }
}

@Composable
private fun Modifier.androidx_scroll(): Modifier = this.then(Modifier.heightIn(max = 360.dp)).then(Modifier.barScroll())


// ------------------------------------------------------------------ значки папок приложений (9.0)

private val appFolderParent = Regex("^(/storage/emulated/\\d+|/sdcard|/storage/self/primary|/data/media/\\d+)/Android/(data|obb|media)$|^/storage/[^/]+/Android/(data|obb|media)$|^/data/(data|user/\\d+|user_de/\\d+)$")

/** Имя пакета, если папка лежит в Android/data, obb, media или /data/data (имя папки совпадает с пакетом приложения), иначе null. */
fun appFolderPkg(parentPath: String, name: String): String? =
    if (name.contains('.') && !name.startsWith(".") && name.all { it.isLetterOrDigit() || it == '.' || it == '_' } && appFolderParent.matches(parentPath.trimEnd('/'))) name else null

/** Папка приложения: значок самого приложения (если оно установлено на этом устройстве) с маленькой папкой в углу, иначе обычная папка. */
@Composable
fun AppFolderIcon(pkg: String, colorIdx: Int, open: Boolean, size: Dp, customKey: String?, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var bmp by remember(pkg) { mutableStateOf(Thumbs.peek("i:$pkg")) }
    LaunchedEffect(pkg) { if (bmp == null) bmp = Thumbs.appIcon(ctx, pkg) }
    val b = bmp
    if (b == null || (customKey != null && CustomIcons.spec(customKey) != null)) FolderIcon(colorIdx, open, size, modifier, customKey)
    else Image(b.asImageBitmap(), null, modifier.size(size).clip(RoundedCornerShape(size * 0.2f)))   // 10.2 (58): значок самого приложения, без значка папки в углу
}

@Composable
fun FolderOrAppIcon(fullPath: String, colorIdx: Int, open: Boolean, size: Dp, modifier: Modifier = Modifier, customKey: String? = null) {
    val pkg = appFolderPkg(fullPath.substringBeforeLast('/', ""), fullPath.substringAfterLast('/'))
    if (pkg != null) AppFolderIcon(pkg, colorIdx, open, size, customKey, modifier) else FolderIcon(colorIdx, open, size, modifier, customKey)
}


/** 9.0: окно «Свойства»: названия пунктов цветом интерфейса (акцент), между строками тонкие разделители, пустая строка отделяет несколько файлов. */
@Composable
fun PropsBody(body: String) {
    val keyColor = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth()) {
        val rows = body.split('\n')
        rows.forEachIndexed { i, r ->
            if (r.isBlank()) { Spacer(Modifier.height(10.dp)); return@forEachIndexed }
            val at = r.indexOf(": ")
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                if (at in 1..24) {
                    FloorText(r.substring(0, at), Modifier.weight(0.44f).padding(end = 8.dp), color = keyColor, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp)
                    FloorText(r.substring(at + 2), Modifier.weight(0.56f), fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                } else FloorText(r, Modifier.fillMaxWidth(), fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            }
            val nextBlank = i + 1 >= rows.size || rows[i + 1].isBlank()
            if (!nextBlank) androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = lineColor)
        }
    }
}


/**
 * 10.0: окно «Свойства» (папка, файл, несколько файлов, диск, подключение) без прокрутки: строки делятся на страницы (портрет 6 строк; ландшафт две колонки по 4),
 * переключатель «Ещё ›» / «‹ Назад»; BACK возвращает на предыдущую страницу, на первой закрывает.
 */
@Composable
fun PropsDialog(title: String, body: String, close: () -> Unit) {
    val land = isLandscapeUi()
    val perCol = if (land) 3 else 4
    val perPage = if (land) perCol * 2 else perCol
    // каждый файл (блок до пустой строки) на своей странице; длинный блок делится по perPage строк
    val pages = remember(body, land) {
        val out = ArrayList<List<String>>()
        body.split("\n\n").map { blk -> blk.split('\n').filter { it.isNotBlank() } }.filter { it.isNotEmpty() }.forEach { rows -> rows.chunked(perPage).forEach { out.add(it) } }
        if (out.isEmpty()) out.add(listOf(""))
        out
    }
    var page by remember(pages) { androidx.compose.runtime.mutableIntStateOf(0) }
    LAlertDialog(
        wide = land,
        onDismissRequest = { if (page > 0) page-- else close() },
        title = { Text(title + if (pages.size > 1) "  (" + (page + 1) + "/" + pages.size + ")" else "", maxLines = 2, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
        text = {
            val rows = pages[page.coerceIn(0, pages.lastIndex)]
            if (land) {
                // две колонки: в левую первые perCol непустых строк, остальное вправо
                var seen = 0; var cut = rows.size
                for ((i, r) in rows.withIndex()) { if (r.isNotBlank()) { if (seen == perCol) { cut = i; break }; seen++ } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) { PropsBody(rows.subList(0, cut).dropLastWhile { it.isBlank() }.joinToString("\n")) }
                    Column(Modifier.weight(1f)) { PropsBody(rows.subList(cut, rows.size).dropWhile { it.isBlank() }.joinToString("\n")) }
                }
            } else PropsBody(rows.joinToString("\n"))
        },
        confirmButton = { TextButton(close) { Text(androidx.compose.ui.res.stringResource(R.string.fm_close)) } },
        dismissButton = { if (pages.size > 1) TextButton({ page = if (page < pages.size - 1) page + 1 else 0 }) { Text(if (page < pages.size - 1) androidx.compose.ui.res.stringResource(R.string.cl_more) + " \u203a" else "\u2039 " + androidx.compose.ui.res.stringResource(R.string.okw_back), maxLines = 1, softWrap = false) } },
    )
}
