package app.adbphon

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 10.2: вторая строка файла и папки в «Файлах» как в X-plore: «дата и время · размер» (файл) и «дата и время · N папок, N файлов» (папка).
 * Что брать за дату: изменено (по умолчанию), создано (если файловая система отдаёт, иначе изменено) или ничего (вторая строка скрыта).
 * Дата по языку и локали; на узком экране телефона и при текущем годе без года, чтобы строка помещалась целиком.
 */
object FmSub {
    const val MODIFIED = 0; const val CREATED = 1; const val NONE = 2

    var mode by mutableIntStateOf(MODIFIED)
        private set

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE)
    fun init(ctx: Context) { mode = sp(ctx).getInt("sub_mode", MODIFIED).coerceIn(0, 2) }

    fun set(ctx: Context, v: Int) {
        mode = v.coerceIn(0, 2)
        sp(ctx).edit().putInt("sub_mode", mode).apply()
        // списки перечитываются, чтобы даты в строках соответствовали выбору
        runCatching { FmRootCfg.refreshAll(ctx) }
    }

    private val fmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }

    /** Время для списков (строка «yyyy-MM-dd HH:mm»): изменения или создания файла по настройке. */
    fun stamp(f: File): String {
        var ms = f.lastModified()
        if (mode == CREATED) runCatching {
            val t = java.nio.file.Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).creationTime().toMillis()
            if (t > 0) ms = t
        }
        return fmt.get()!!.format(Date(ms))
    }

    /** Строка даты из «yyyy-MM-dd HH:mm» по локали; [narrow]: без года, если год текущий. Не разобралась: как есть (дата сети бывает другого вида). */
    fun date(ctx: Context, raw: String, narrow: Boolean): String {
        val d = runCatching { fmt.get()!!.parse(raw) }.getOrNull() ?: return fmDate(raw)
        val loc = Locale.getDefault()
        val cal = java.util.Calendar.getInstance().apply { time = d }
        val sameYear = cal.get(java.util.Calendar.YEAR) == java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val skeleton = (if (narrow && sameYear) "ddMM" else "ddMMyyyy") + (if (DateFormat.is24HourFormat(ctx)) "HHmm" else "hmma")
        val pattern = DateFormat.getBestDateTimePattern(loc, skeleton).replace(", ", " ")
        return SimpleDateFormat(pattern, loc).format(d)
    }

    /** Вторая строка файла: «дата · размер»; пусто, если выбрано «ничего». */
    fun fileLine(ctx: Context, date: String, size: String?, narrow: Boolean): String? {
        if (mode == NONE) return null
        return listOfNotNull(date.takeIf { it.isNotBlank() }?.let { date(ctx, it, narrow) }, size).joinToString(if (narrow) " · " else "  ·  ").ifEmpty { null }
    }
}

/** Пункт настроек «Вторая строка в списке» (группа «Файлы»): изменено, создано или ничего; пояснение свёрнуто в название. */
@Composable
fun FilesSubLineSetting() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    HintTitle(stringResource(R.string.set_fm_sub), stringResource(R.string.set_fm_sub_hint))
    EqChoices(listOf(
        EqChoice(stringResource(R.string.fm_sub_mod), FmSub.mode == FmSub.MODIFIED) { FmSub.set(ctx, FmSub.MODIFIED) },
        EqChoice(stringResource(R.string.fm_sub_cre), FmSub.mode == FmSub.CREATED) { FmSub.set(ctx, FmSub.CREATED) },
        EqChoice(stringResource(R.string.fm_sub_none), FmSub.mode == FmSub.NONE) { FmSub.set(ctx, FmSub.NONE) },
    ), perRow = 3)
}
