package app.adbphon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Проверка порядка по каждому критерию каждой группы (обычный тест на JVM, без Android). */
class SortEngineTest {
    private fun f(name: String, size: Long = 0, date: String = "2026-01-01 10:00") = FmItem(name, false, size, date)
    private fun d(name: String, date: String = "2026-01-01 10:00") = FmItem(name, true, 0, date)
    private fun names(l: List<FmItem>) = l.map { it.name }

    @Test fun natural_order_puts_file2_before_file10() {
        val l = listOf(f("file10.txt"), f("file2.txt"), f("file1.txt"), f("file02.txt"), f("File3.txt"))
        assertEquals(listOf("file1.txt", "file02.txt", "file2.txt", "File3.txt", "file10.txt"), names(SortEngine.sort(l, SortCfg())))
    }

    @Test fun plain_order_when_natural_off() {
        val l = listOf(f("file10.txt"), f("file2.txt"), f("file1.txt"))
        assertEquals(listOf("file1.txt", "file10.txt", "file2.txt"), names(SortEngine.sort(l, SortCfg(natural = false))))
    }

    @Test fun case_sensitivity_is_configurable() {
        val l = listOf(f("b.txt"), f("A.txt"), f("a.txt"), f("B.txt"))
        assertEquals(listOf("A.txt", "a.txt", "B.txt", "b.txt"), names(SortEngine.sort(l, SortCfg(ignoreCase = true))))
        assertEquals(listOf("A.txt", "B.txt", "a.txt", "b.txt"), names(SortEngine.sort(l, SortCfg(ignoreCase = false, natural = false))))
    }

    @Test fun cyrillic_ignores_case() {
        val l = listOf(f("яблоко"), f("Арбуз"), f("банан"), f("Вишня"))
        assertEquals(listOf("Арбуз", "банан", "Вишня", "яблоко"), names(SortEngine.sort(l, SortCfg())))
    }

    @Test fun folders_first_then_files_then_media_blocks_in_default_order() {
        val l = listOf(f("z.mp3"), f("a.txt"), d("dir"), f("y.mp4"), f("x.jpg"), f("b.bin"), f("c.pdf"))
        val g = SortEngine.sortGroups(l, SortCfg())
        assertEquals(listOf(SGroup.DIR, SGroup.FILE, SGroup.PHOTO, SGroup.VIDEO, SGroup.MUSIC), g.map { it.first })
        // документы по умолчанию вливаются в «Файлы»
        assertEquals(listOf("a.txt", "b.bin", "c.pdf"), names(g[1].second))
    }

    @Test fun groups_order_is_configurable() {
        val l = listOf(f("z.mp3"), d("dir"), f("y.mp4"), f("x.jpg"), f("a.bin"))
        val cfg = SortCfg(order = listOf(SGroup.MUSIC, SGroup.VIDEO, SGroup.PHOTO, SGroup.FILE, SGroup.DIR, SGroup.DOC))
        assertEquals(listOf("z.mp3", "y.mp4", "x.jpg", "a.bin", "dir"), names(SortEngine.sort(l, cfg)))
    }

    @Test fun media_not_separate_merges_into_files() {
        val l = listOf(f("b.mp3", 5), f("a.bin", 9), f("c.jpg", 1))
        val cfg = SortCfg(sep = emptySet()).withKey(SGroup.FILE, SKey.SIZE, true)
        assertEquals(listOf("c.jpg", "b.mp3", "a.bin"), names(SortEngine.sort(l, cfg)))
    }

    @Test fun grouped_mode_separates_every_group_even_documents() {
        val l = listOf(f("a.pdf"), f("b.mp3"), d("x"), f("c.bin"))
        val g = SortEngine.sortGroups(l, SortCfg(grouped = true))
        assertEquals(listOf(SGroup.DIR, SGroup.FILE, SGroup.MUSIC, SGroup.DOC), g.map { it.first })
    }

    @Test fun files_by_size_ext_date_both_directions() {
        val l = listOf(f("a.zip", 300, "2026-03-01 00:00"), f("b.apk", 100, "2026-01-01 00:00"), f("c.bin", 200, "2026-02-01 00:00"))
        assertEquals(listOf("b.apk", "c.bin", "a.zip"), names(SortEngine.sort(l, SortCfg().withKey(SGroup.FILE, SKey.SIZE, true))))
        assertEquals(listOf("a.zip", "c.bin", "b.apk"), names(SortEngine.sort(l, SortCfg().withKey(SGroup.FILE, SKey.SIZE, false))))
        assertEquals(listOf("b.apk", "c.bin", "a.zip"), names(SortEngine.sort(l, SortCfg().withKey(SGroup.FILE, SKey.DATE, true))))
        assertEquals(listOf("a.zip", "c.bin", "b.apk"), names(SortEngine.sort(l, SortCfg().withKey(SGroup.FILE, SKey.DATE, false))))
        assertEquals(listOf("b.apk", "c.bin", "a.zip"), names(SortEngine.sort(l, SortCfg().withKey(SGroup.FILE, SKey.EXT, true))))
        assertEquals(listOf("a.zip", "c.bin", "b.apk"), names(SortEngine.sort(l, SortCfg().withKey(SGroup.FILE, SKey.EXT, false))))
    }

    @Test fun folders_by_name_date_count_color() {
        val l = listOf(d("b", "2026-02-01 00:00"), d("a", "2026-03-01 00:00"), d("c", "2026-01-01 00:00"))
        assertEquals(listOf("a", "b", "c"), names(SortEngine.sort(l, SortCfg())))
        assertEquals(listOf("c", "b", "a"), names(SortEngine.sort(l, SortCfg().withKey(SGroup.DIR, SKey.DATE, true))))
        assertEquals(listOf("a", "b", "c"), names(SortEngine.sort(l, SortCfg().withKey(SGroup.DIR, SKey.DATE, false))))
        val cnt = mapOf("a" to 5, "b" to 50, "c" to 1)
        val col = mapOf("a" to 3, "b" to 0, "c" to 7)
        val ctx = SortCtx(count = { cnt[it.name] ?: -1 }, color = { col[it.name] ?: 0 })
        assertEquals(listOf("c", "a", "b"), names(SortEngine.sort(l, SortCfg().withKey(SGroup.DIR, SKey.COUNT, true), ctx)))
        assertEquals(listOf("b", "a", "c"), names(SortEngine.sort(l, SortCfg().withKey(SGroup.DIR, SKey.COUNT, false), ctx)))
        assertEquals(listOf("b", "a", "c"), names(SortEngine.sort(l, SortCfg().withKey(SGroup.DIR, SKey.COLOR, true), ctx)))
        assertEquals(listOf("c", "a", "b"), names(SortEngine.sort(l, SortCfg().withKey(SGroup.DIR, SKey.COLOR, false), ctx)))
    }

    private val music = listOf(f("01.mp3", 30), f("02.mp3", 10), f("03.mp3", 20), f("04.mp3", 40))
    private val tags = mapOf(
        "01.mp3" to SMeta(title = "Zebra", artist = "Beta", album = "Y", track = 3, durMs = 200_000),
        "02.mp3" to SMeta(title = "apple", artist = "alpha", album = "X", track = 1, durMs = 100_000),
        "03.mp3" to SMeta(title = "Mango", artist = "Beta", album = "X", track = 2, durMs = 300_000),
        "04.mp3" to SMeta(title = "unknown", artist = null, album = null, track = -1, durMs = -1),
    )
    private fun m(k: SKey, asc: Boolean = true) =
        names(SortEngine.sort(music, SortCfg().withKey(SGroup.MUSIC, k, asc), SortCtx(meta = { tags[it.name] })))

    @Test fun music_by_every_criterion() {
        assertEquals(listOf("01.mp3", "02.mp3", "03.mp3", "04.mp3"), m(SKey.NAME))
        assertEquals(listOf("04.mp3", "03.mp3", "02.mp3", "01.mp3"), m(SKey.NAME, false))
        assertEquals(listOf("02.mp3", "03.mp3", "04.mp3", "01.mp3"), m(SKey.TITLE))
        assertEquals(listOf("01.mp3", "04.mp3", "03.mp3", "02.mp3"), m(SKey.TITLE, false))
        // исполнитель: alpha, Beta, Beta (по имени файла 01, 03), без исполнителя в конце
        assertEquals(listOf("02.mp3", "01.mp3", "03.mp3", "04.mp3"), m(SKey.ARTIST))
        assertEquals(listOf("01.mp3", "03.mp3", "02.mp3", "04.mp3"), m(SKey.ARTIST, false))
        assertEquals(listOf("02.mp3", "03.mp3", "01.mp3", "04.mp3"), m(SKey.ALBUM))
        assertEquals(listOf("01.mp3", "02.mp3", "03.mp3", "04.mp3"), m(SKey.ALBUM, false))
        assertEquals(listOf("02.mp3", "03.mp3", "01.mp3", "04.mp3"), m(SKey.TRACK))
        assertEquals(listOf("01.mp3", "03.mp3", "02.mp3", "04.mp3"), m(SKey.TRACK, false))
        assertEquals(listOf("02.mp3", "01.mp3", "03.mp3", "04.mp3"), m(SKey.DURATION))
        assertEquals(listOf("03.mp3", "01.mp3", "02.mp3", "04.mp3"), m(SKey.DURATION, false))
        assertEquals(listOf("02.mp3", "03.mp3", "01.mp3", "04.mp3"), m(SKey.SIZE))
        assertEquals(listOf("04.mp3", "01.mp3", "03.mp3", "02.mp3"), m(SKey.SIZE, false))
    }

    @Test fun music_without_tags_yet_falls_back_to_name() {
        val cfg = SortCfg().withKey(SGroup.MUSIC, SKey.TITLE, true)
        assertEquals(listOf("01.mp3", "02.mp3", "03.mp3", "04.mp3"), names(SortEngine.sort(music, cfg)))
    }

    @Test fun video_by_duration_resolution_size_date() {
        val v = listOf(f("a.mp4", 300, "2026-01-03 00:00"), f("b.mkv", 100, "2026-01-01 00:00"), f("c.avi", 200, "2026-01-02 00:00"))
        val meta = mapOf("a.mp4" to SMeta(durMs = 5000, w = 1920, h = 1080), "b.mkv" to SMeta(durMs = 1000, w = 3840, h = 2160), "c.avi" to SMeta(durMs = 3000, w = 640, h = 480))
        val ctx = SortCtx(meta = { meta[it.name] })
        fun s(k: SKey, asc: Boolean = true) = names(SortEngine.sort(v, SortCfg().withKey(SGroup.VIDEO, k, asc), ctx))
        assertEquals(listOf("b.mkv", "c.avi", "a.mp4"), s(SKey.DURATION))
        assertEquals(listOf("a.mp4", "c.avi", "b.mkv"), s(SKey.DURATION, false))
        assertEquals(listOf("c.avi", "a.mp4", "b.mkv"), s(SKey.RES))
        assertEquals(listOf("b.mkv", "a.mp4", "c.avi"), s(SKey.RES, false))
        assertEquals(listOf("b.mkv", "c.avi", "a.mp4"), s(SKey.SIZE))
        assertEquals(listOf("b.mkv", "c.avi", "a.mp4"), s(SKey.DATE))
        assertEquals(listOf("a.mp4", "c.avi", "b.mkv"), s(SKey.DATE, false))
    }

    @Test fun photos_by_date_taken_size() {
        val p = listOf(f("a.jpg", 30), f("b.png", 10), f("c.jpg", 20))
        val meta = mapOf("a.jpg" to SMeta(taken = 3000), "b.png" to SMeta(taken = 1000), "c.jpg" to SMeta(taken = 2000))
        val ctx = SortCtx(meta = { meta[it.name] })
        assertEquals(listOf("b.png", "c.jpg", "a.jpg"), names(SortEngine.sort(p, SortCfg().withKey(SGroup.PHOTO, SKey.TAKEN, true), ctx)))
        assertEquals(listOf("a.jpg", "c.jpg", "b.png"), names(SortEngine.sort(p, SortCfg().withKey(SGroup.PHOTO, SKey.TAKEN, false), ctx)))
        assertEquals(listOf("b.png", "c.jpg", "a.jpg"), names(SortEngine.sort(p, SortCfg().withKey(SGroup.PHOTO, SKey.SIZE, true), ctx)))
    }

    @Test fun missing_values_always_go_last_in_both_directions() {
        val l = listOf(f("x.mp3"), f("y.mp3"), f("z.mp3"))
        val meta = mapOf("x.mp3" to SMeta(durMs = 10), "z.mp3" to SMeta(durMs = 20))
        val ctx = SortCtx(meta = { meta[it.name] })
        assertEquals(listOf("x.mp3", "z.mp3", "y.mp3"), names(SortEngine.sort(l, SortCfg().withKey(SGroup.MUSIC, SKey.DURATION, true), ctx)))
        assertEquals(listOf("z.mp3", "x.mp3", "y.mp3"), names(SortEngine.sort(l, SortCfg().withKey(SGroup.MUSIC, SKey.DURATION, false), ctx)))
    }

    @Test fun groups_sort_independently() {
        // музыка по длительности убыванию, файлы по размеру возрастанию, папки по имени
        val l = listOf(f("b.bin", 50), f("a.bin", 90), f("m1.mp3"), f("m2.mp3"), d("zz"), d("aa"))
        val meta = mapOf("m1.mp3" to SMeta(durMs = 10), "m2.mp3" to SMeta(durMs = 99))
        val cfg = SortCfg().withKey(SGroup.FILE, SKey.SIZE, true).withKey(SGroup.MUSIC, SKey.DURATION, false)
        assertEquals(listOf("aa", "zz", "b.bin", "a.bin", "m2.mp3", "m1.mp3"), names(SortEngine.sort(l, cfg, SortCtx(meta = { meta[it.name] }))))
    }

    @Test fun needs_meta_only_for_tag_criteria() {
        assertTrue(!SortCfg().needsMeta(SGroup.MUSIC))
        assertTrue(SortCfg().withKey(SGroup.MUSIC, SKey.ARTIST, true).needsMeta(SGroup.MUSIC))
        assertTrue(SortCfg().withKey(SGroup.VIDEO, SKey.RES, true).needsMeta(SGroup.VIDEO))
        assertTrue(SortCfg().withKey(SGroup.PHOTO, SKey.TAKEN, true).needsMeta(SGroup.PHOTO))
        assertTrue(SortCfg().withKey(SGroup.DIR, SKey.COUNT, true).needsMeta(SGroup.DIR))
        assertTrue(!SortCfg().withKey(SGroup.DIR, SKey.COLOR, true).needsMeta(SGroup.DIR))
    }

    @Test fun settings_roundtrip_and_corruption() {
        val c = SortCfg(grouped = true, natural = false, ignoreCase = false, order = listOf(SGroup.MUSIC, SGroup.DIR, SGroup.FILE, SGroup.PHOTO, SGroup.VIDEO, SGroup.DOC), sep = setOf(SGroup.DOC, SGroup.MUSIC))
            .withKey(SGroup.MUSIC, SKey.ARTIST, false).withKey(SGroup.VIDEO, SKey.RES, true).withKey(SGroup.DIR, SKey.COLOR, false)
        assertEquals(c, SortCfg.decode(c.encode()))
        assertEquals(SortCfg(), SortCfg.decode(SortCfg().encode()))
        assertNull(SortCfg.decode(null)); assertNull(SortCfg.decode(""))
        // неизвестный критерий отбрасывается, остальное сохраняется
        val bad = SortCfg.decode("g0n1i1|o=DF|M=SIZEX:1|F=SIZE:0")
        assertNotNull(bad)
        assertEquals(SKey.NAME, bad!!.keyOf(SGroup.MUSIC))
        assertEquals(SKey.SIZE, bad.keyOf(SGroup.FILE)); assertTrue(!bad.ascOf(SGroup.FILE))
        assertEquals(6, bad.order.size)
        // критерий чужой группы (число элементов у файлов) не принимается
        assertEquals(SKey.NAME, SortCfg.decode("g0n1i1|F=COUNT:1")!!.keyOf(SGroup.FILE))
    }

    @Test fun natural_compare_edge_cases() {
        assertTrue(SortEngine.nameCmp("a9", "a10", true, true) < 0)
        assertTrue(SortEngine.nameCmp("a010", "a9", true, true) > 0)
        assertEquals(0, SortEngine.nameCmp("abc", "ABC", true, true))
        assertTrue(SortEngine.nameCmp("abc", "ABC", true, false) > 0)
        assertTrue(SortEngine.nameCmp("a", "a1", true, true) < 0)
        assertTrue(SortEngine.nameCmp("99999999999999999999", "100000000000000000000", true, true) < 0)
    }

    @Test fun big_folder_sorts_fast_and_stably() {
        val n = 60_000
        val rnd = java.util.Random(7)
        val l = List(n) { i ->
            when (rnd.nextInt(5)) {
                0 -> d("dir$i"); 1 -> f("track$i.mp3", rnd.nextInt(1_000_000).toLong()); 2 -> f("clip$i.mp4")
                3 -> f("pic$i.jpg"); else -> f("file$i.dat", rnd.nextInt(1_000_000).toLong())
            }
        }
        val t0 = System.nanoTime()
        val a = SortEngine.sort(l, SortCfg().withKey(SGroup.FILE, SKey.SIZE, false))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(n, a.size)
        val b = SortEngine.sort(l.shuffled(java.util.Random(3)), SortCfg().withKey(SGroup.FILE, SKey.SIZE, false))
        assertEquals(names(a), names(b))                  // результат не зависит от исходного порядка
        println("sort of $n items: $ms ms")
        assertTrue("слишком медленно: $ms мс", ms < 4000)
    }
}
