package app.adbphon

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 10.0 (chk): дата изменения файла в одном виде «гггг-ММ-дд ЧЧ:мм» (местное время) из ответов разных источников.
 * Раньше сетевые источники (SMB, SFTP, FTP, WebDAV, «Файлы устройства» другого Android) отдавали в «Файлы» пустую дату,
 * и сортировка «по дате» там молча работала как «по имени». Без Android: проверяется тестами на JVM.
 * Все функции *Millis возвращают -1, если дату понять нельзя.
 */
object FmDates {
    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** Миллисекунды в строку списка; 0 и меньше: пустая строка (дата неизвестна). */
    fun ofMillis(ms: Long, tz: TimeZone = TimeZone.getDefault()): String =
        if (ms <= 0) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = tz }.format(Date(ms))

    /** FTP MLSD «modify=20261116103000» (бывает с долями секунды «.123»), время UTC. */
    fun mlsdMillis(s: String?): Long {
        val d = s?.trim()?.substringBefore('.') ?: return -1
        if (d.length !in 12..14 || !d.all { it in '0'..'9' }) return -1
        val f = if (d.length == 14) "yyyyMMddHHmmss" else "yyyyMMddHHmm"
        return runCatching { SimpleDateFormat(f, Locale.US).apply { timeZone = UTC; isLenient = false }.parse(d.take(f.length))!!.time }.getOrDefault(-1L)
    }

    /** WebDAV getlastmodified: «Mon, 16 Nov 2026 10:30:00 GMT» (RFC 1123). */
    fun httpMillis(s: String?): Long {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return -1
        return runCatching { SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(t)!!.time }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrDefault(-1L)
    }

    /**
     * Строка unix-списка FTP: месяц «Nov», день «16», время «10:30» (год текущий; если дата получилась в будущем больше чем на сутки, прошлый)
     * или год «2025» (время 00:00). Время сервера считается местным.
     */
    fun unixLsMillis(mon: String, day: String, timeOrYear: String, now: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): Long {
        val m = MONTHS.indexOf(mon.trim().lowercase().take(3)); val d = day.trim().toIntOrNull() ?: return -1
        if (m < 0 || d !in 1..31) return -1
        val cal = Calendar.getInstance(tz).apply { timeInMillis = now; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val nowYear = cal.get(Calendar.YEAR)
        if (timeOrYear.contains(':')) {
            val hh = timeOrYear.substringBefore(':').toIntOrNull() ?: return -1; val mm = timeOrYear.substringAfter(':').toIntOrNull() ?: return -1
            cal.set(nowYear, m, d, hh, mm)
            if (cal.timeInMillis > now + 86_400_000L) cal.set(Calendar.YEAR, nowYear - 1)
        } else {
            val y = timeOrYear.trim().toIntOrNull() ?: return -1
            cal.set(y, m, d, 0, 0)
        }
        return cal.timeInMillis
    }

    /** Строка DOS-списка FTP (IIS): «11-16-26» и «10:30AM» (бывает и 24-часовое «22:30», и год из четырёх цифр). */
    fun dosMillis(date: String, time: String, tz: TimeZone = TimeZone.getDefault()): Long {
        val p = date.trim().split('-', '/'); if (p.size != 3) return -1
        val mo = p[0].toIntOrNull() ?: return -1; val dd = p[1].toIntOrNull() ?: return -1; var yy = p[2].toIntOrNull() ?: return -1
        if (p[2].length <= 2) yy += if (yy < 70) 2000 else 1900
        val t = time.trim().uppercase(); val pm = t.endsWith("PM"); val am = t.endsWith("AM")
        val hm = t.removeSuffix("PM").removeSuffix("AM")
        var hh = hm.substringBefore(':').toIntOrNull() ?: return -1; val mi = hm.substringAfter(':').toIntOrNull() ?: return -1
        if (pm && hh < 12) hh += 12
        if (am && hh == 12) hh = 0
        if (mo !in 1..12 || dd !in 1..31 || hh !in 0..23 || mi !in 0..59) return -1
        return Calendar.getInstance(tz).apply { clear(); set(yy, mo - 1, dd, hh, mi) }.timeInMillis
    }
}
