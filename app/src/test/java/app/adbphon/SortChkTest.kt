package app.adbphon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** 10.0 (chk): проверка исправлений сортировки: кириллица по алфавиту, даты разных источников, дерево, стабильность, все ключи в обе стороны. */
class SortChkTest {
    private fun f(name: String, size: Long = 0, date: String = "2026-01-01 10:00") = FmItem(name, false, size, date)
    private fun d(name: String, date: String = "2026-01-01 10:00") = FmItem(name, true, 0, date)
    private fun names(l: List<FmItem>) = l.map { it.name }
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    @Test fun ukrainian_letters_follow_the_alphabet_not_after_ya() {
        val l = listOf(f("яма"), f("їжак"), f("ірис"), f("ґанок"), f("гора"), f("єнот"), f("ера"), f("дім"), f("зима"), f("ворота"))
        assertEquals(listOf("ворота", "гора", "ґанок", "дім", "ера", "єнот", "зима", "ірис", "їжак", "яма"), names(SortEngine.sort(l, SortCfg())))
    }

    @Test fun russian_yo_goes_right_after_ye() {
        val l = listOf(f("жук"), f("ёж"), f("ель"), f("яр"))
        assertEquals(listOf("ель", "ёж", "жук", "яр"), names(SortEngine.sort(l, SortCfg())))
    }

    @Test fun cyrillic_case_sensitive_puts_capitals_block_first_like_latin() {
        val l = listOf(f("б"), f("А"), f("а"), f("Б"))
        assertEquals(listOf("А", "Б", "а", "б"), names(SortEngine.sort(l, SortCfg(ignoreCase = false, natural = false))))
        assertEquals(listOf("А", "а", "Б", "б"), names(SortEngine.sort(l, SortCfg(ignoreCase = true))))
    }

    @Test fun natural_numbers_with_cyrillic_and_case() {
        val l = listOf(f("Фото 10.jpg"), f("фото 2.jpg"), f("Фото 1.jpg"), f("фото 02.jpg"))
        val cfg = SortCfg(sep = emptySet())
        assertEquals(listOf("Фото 1.jpg", "фото 02.jpg", "фото 2.jpg", "Фото 10.jpg"), names(SortEngine.sort(l, cfg)))
        // без «чисел по значению» цифры сравниваются по одной: 02 < 1 < 10 < 2
        assertEquals(listOf("фото 02.jpg", "Фото 1.jpg", "Фото 10.jpg", "фото 2.jpg"), names(SortEngine.sort(l, cfg.copy(natural = false))))
    }

    @Test fun natural_off_ignore_case_matches_lowercase_compare_for_latin() {
        // одинаково с «естественным» режимом: без учёта регистра сравниваются строчные буквы («_» перед буквами)
        val words = listOf("b10", "B2", "a", "A1", "a01", "z", "Z0", "_x", "x_")
        for (a in words) for (b in words) {
            val exp = Integer.signum(a.lowercase().compareTo(b.lowercase())); val got = Integer.signum(SortEngine.nameCmp(a, b, false, true))
            assertEquals("$a <> $b", exp, got)
        }
    }

    @Test fun date_key_understands_both_formats_and_rejects_garbage() {
        assertEquals("20261116103000", SortEngine.dateKey("2026-11-16 10:30"))
        assertEquals("20261116103045", SortEngine.dateKey("2026-11-16 10:30:45"))
        assertEquals("20261116103000", SortEngine.dateKey("16.11.2026 10:30"))
        assertEquals("20260105000000", SortEngine.dateKey("5.1.2026"))
        assertNull(SortEngine.dateKey(""))
        assertNull(SortEngine.dateKey("   "))
        assertNull(SortEngine.dateKey("вчера"))
    }

    @Test fun date_sort_mixed_formats_and_unknown_last_both_directions() {
        val l = listOf(f("a", date = "16.11.2026 10:30"), f("b", date = "2025-01-01 00:00"), f("c", date = ""), f("d", date = "2026-11-16 09:00"))
        assertEquals(listOf("b", "d", "a", "c"), names(SortEngine.sort(l, SortCfg().withKey(SGroup.FILE, SKey.DATE, true))))
        assertEquals(listOf("a", "d", "b", "c"), names(SortEngine.sort(l, SortCfg().withKey(SGroup.FILE, SKey.DATE, false))))
    }

    @Test fun folders_on_top_or_bottom_with_both_directions() {
        val l = listOf(f("b.bin", 2), d("y"), f("a.bin", 1), d("x"))
        assertEquals(listOf("x", "y", "a.bin", "b.bin"), names(SortEngine.sort(l, SortCfg())))
        val bottom = SortCfg(order = listOf(SGroup.FILE, SGroup.PHOTO, SGroup.VIDEO, SGroup.MUSIC, SGroup.DOC, SGroup.DIR))
        assertEquals(listOf("a.bin", "b.bin", "x", "y"), names(SortEngine.sort(l, bottom)))
        val desc = bottom.withKey(SGroup.DIR, SKey.NAME, false).withKey(SGroup.FILE, SKey.SIZE, false)
        assertEquals(listOf("b.bin", "a.bin", "y", "x"), names(SortEngine.sort(l, desc)))
    }

    @Test fun every_key_of_every_group_is_deterministic_in_both_directions() {
        val items = listOf(
            d("Папка 2", "2026-02-01 00:00"), d("папка 10", "2026-01-01 00:00"), d("Archive", "2026-03-01 00:00"),
            f("b.txt", 5, "2026-01-02 00:00"), f("A.TXT", 5, "2026-01-02 00:00"), f("c.bin", 9, ""),
            f("x.jpg", 3), f("y.mp4", 4), f("z.mp3", 1), f("w.pdf", 2),
        )
        val ctx = SortCtx(meta = { SMeta(title = it.name, track = 1, durMs = 5) }, count = { if (it.isDir) it.name.length else -1 }, color = { 1 })
        val rnd = java.util.Random(11)
        for (g in SGroup.values()) for (k in SortGroups.keys.getValue(g)) for (asc in listOf(true, false)) {
            val cfg = SortCfg(grouped = true).withKey(g, k, asc)
            val a = names(SortEngine.sort(items, cfg, ctx))
            repeat(5) { assertEquals("$g $k $asc", a, names(SortEngine.sort(items.shuffled(rnd), cfg, ctx))) }
            assertEquals(items.size, a.size)
        }
    }

    @Test fun equal_keys_fall_back_to_name_ascending_even_when_descending() {
        val l = listOf(f("c", 5), f("a", 5), f("b", 5), f("z", 1))
        assertEquals(listOf("a", "b", "c", "z"), names(SortEngine.sort(l, SortCfg().withKey(SGroup.FILE, SKey.SIZE, false))))
        assertEquals(listOf("z", "a", "b", "c"), names(SortEngine.sort(l, SortCfg().withKey(SGroup.FILE, SKey.SIZE, true))))
    }

    @Test fun inside_check_for_tree_levels() {
        assertTrue(SortEngine.isInside("/sdcard", "/sdcard/Music"))
        assertTrue(SortEngine.isInside("/sdcard/", "/sdcard/Music/Album/"))
        assertTrue(!SortEngine.isInside("/sdcard", "/sdcard"))
        assertTrue(!SortEngine.isInside("/sdcard", "/sdcard2/x"))
        assertTrue(SortEngine.isInside("smb://pc/share/", "smb://pc/share/a"))
    }

    @Test fun dates_from_network_sources() {
        assertEquals("2026-11-16 10:30", FmDates.ofMillis(FmDates.mlsdMillis("20261116103000"), utc))
        assertEquals("2026-11-16 10:30", FmDates.ofMillis(FmDates.mlsdMillis("20261116103000.123"), utc))
        assertEquals(-1L, FmDates.mlsdMillis("abc"))
        assertEquals("2026-11-16 10:30", FmDates.ofMillis(FmDates.httpMillis("Mon, 16 Nov 2026 10:30:00 GMT"), utc))
        assertEquals(-1L, FmDates.httpMillis(""))
        assertEquals("", FmDates.ofMillis(0))
        assertEquals("", FmDates.ofMillis(-5))
        // DOS-список IIS: 12-часовое время
        assertEquals("2026-11-16 22:30", FmDates.ofMillis(FmDates.dosMillis("11-16-26", "10:30PM", utc), utc))
        assertEquals("2026-11-16 00:05", FmDates.ofMillis(FmDates.dosMillis("11-16-2026", "12:05AM", utc), utc))
        assertEquals(-1L, FmDates.dosMillis("x", "10:30PM", utc))
    }

    @Test fun unix_ls_dates_guess_the_year() {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).apply { timeZone = utc }.parse("2026-11-16 12:00")!!.time
        assertEquals("2026-11-16 10:30", FmDates.ofMillis(FmDates.unixLsMillis("Nov", "16", "10:30", now, utc), utc))
        // декабрь «в будущем» = прошлый год
        assertEquals("2025-12-20 08:00", FmDates.ofMillis(FmDates.unixLsMillis("Dec", "20", "08:00", now, utc), utc))
        assertEquals("2024-03-05 00:00", FmDates.ofMillis(FmDates.unixLsMillis("Mar", " 5", "2024", now, utc), utc))
        assertEquals(-1L, FmDates.unixLsMillis("Foo", "5", "2024", now, utc))
    }
}
