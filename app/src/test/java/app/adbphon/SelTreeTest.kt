package app.adbphon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** fm1: отметка в раскрытом дереве (папка = всё содержимое, снятие вложенного, «Все», «Инверсия», счётчик без двойного счёта). */
class SelTreeTest {
    // Download/ { a.txt, Pics/ { 1.jpg, 2.jpg }, b.txt }, Music/ (свёрнута), c.txt
    private val tree = mapOf(
        "Download" to listOf("Download/a.txt", "Download/Pics", "Download/b.txt"),
        "Download/Pics" to listOf("Download/Pics/1.jpg", "Download/Pics/2.jpg"),
    )
    private val kids: (String) -> List<String>? = { tree[it] }
    private val top = listOf("Download", "Music", "c.txt")

    @Test fun folder_selection_covers_nested_rows() {
        val s = SelTree.toggle(emptySet(), "Download", kids)
        assertEquals(setOf("Download"), s)
        assertTrue(SelTree.covered(s, "Download/a.txt"))
        assertTrue(SelTree.covered(s, "Download/Pics/2.jpg"))
        assertFalse(SelTree.covered(s, "Music"))
        assertFalse(SelTree.covered(s, "DownloadX"))       // похожее имя не считается вложенным
        assertEquals(1, SelTree.count(s))
    }

    @Test fun selecting_folder_absorbs_nested_keys() {
        var s: Set<String> = setOf("Download/a.txt", "Download/Pics/1.jpg", "c.txt")
        s = SelTree.toggle(s, "Download", kids)
        assertEquals(setOf("c.txt", "Download"), s)
        assertEquals(2, SelTree.count(s))
    }

    @Test fun unchecking_folder_clears_everything_inside() {
        var s = SelTree.toggle(emptySet(), "Download", kids)
        s = SelTree.toggle(s, "Download", kids)
        assertTrue(s.isEmpty())
        assertFalse(SelTree.covered(s, "Download/Pics/1.jpg"))
    }

    @Test fun nested_item_of_selected_folder_is_marked_on_its_own_and_not_counted_twice() {
        var s = SelTree.toggle(emptySet(), "Download", kids)
        s = SelTree.toggle(s, "Download/Pics/1.jpg", kids)
        assertEquals(setOf("Download", "Download/Pics/1.jpg"), s)
        assertEquals(1, SelTree.count(s))
        assertTrue(SelTree.covered(s, "Download/a.txt"))
        // повторное нажатие снимает только его, папка остаётся
        s = SelTree.toggle(s, "Download/Pics/1.jpg", kids)
        assertEquals(setOf("Download"), s)
    }

    @Test fun partial_folder_click_selects_it_whole() {
        var s: Set<String> = setOf("Download/Pics/1.jpg")
        assertTrue(SelTree.partial(s, "Download"))
        s = SelTree.toggle(s, "Download", kids)
        assertEquals(setOf("Download"), s)
    }

    @Test fun unknown_content_drops_folder_without_crash() {
        val s = SelTree.toggle(setOf("Music"), "Music/x.mp3", { null })
        assertEquals(setOf("Music", "Music/x.mp3"), s)
        assertEquals(1, SelTree.count(s))
    }

    @Test fun select_all_covers_all_visible_and_counts_once() {
        val s = SelTree.selectAll(setOf("Download/a.txt"), top)
        assertEquals(setOf("Download", "Music", "c.txt"), s)
        assertTrue(SelTree.covered(s, "Download/Pics/1.jpg"))
        assertEquals(3, SelTree.count(s))
    }

    @Test fun invert_works_on_expanded_tree() {
        // отмечены a.txt и вся Pics; инверсия: b.txt, Music, c.txt (Download только частично, сама не отмечается)
        val s = SelTree.invert(setOf("Download/a.txt", "Download/Pics"), top, kids)
        assertEquals(setOf("Download/b.txt", "Music", "c.txt"), s)
        // повторная инверсия возвращает исходное
        val back = SelTree.invert(s, top, kids)
        assertEquals(setOf("Download/a.txt", "Download/Pics"), back)
    }

    @Test fun invert_of_nothing_is_everything_and_folder_collapses() {
        val s = SelTree.invert(emptySet(), top, kids)
        assertEquals(setOf("Download", "Music", "c.txt"), s)
        assertTrue(SelTree.invert(s, top, kids).isEmpty())
    }

    @Test fun invert_keeps_selection_outside_visible_tree() {
        val s = SelTree.invert(setOf("/other/x"), listOf("c.txt"), kids)
        assertEquals(setOf("/other/x", "c.txt"), s)
    }

    @Test fun root_tree_keys_with_canon() {
        // корень «Источники»: папки «L|путь», файлы «F|путь»
        val canon: (String) -> String = { if (it.startsWith("F|")) "L|" + it.substring(2) else it }
        val rk = mapOf("L|/sdcard/Download" to listOf("F|/sdcard/Download/a.apk", "L|/sdcard/Download/Sub"))
        var s = SelTree.toggle(emptySet(), "L|/sdcard/Download", { rk[it] }, canon)
        assertTrue(SelTree.covered(s, "F|/sdcard/Download/a.apk", canon))
        assertTrue(SelTree.covered(s, "L|/sdcard/Download/Sub", canon))
        s = SelTree.toggle(s, "F|/sdcard/Download/a.apk", { rk[it] }, canon)
        assertEquals(setOf("L|/sdcard/Download", "F|/sdcard/Download/a.apk"), s)
        assertEquals(1, SelTree.count(s, canon))
        // корень «L|/» (Root): вложенность тоже распознаётся
        assertTrue(SelTree.under("F|/x.txt", "L|/", canon))
    }

    @Test fun normalize_removes_double_count() {
        assertEquals(setOf("Download", "c.txt"), SelTree.normalize(listOf("Download", "Download/a.txt", "Download/Pics/1.jpg", "c.txt")))
        assertEquals(2, SelTree.count(listOf("Download", "Download/Pics", "c.txt")))
    }

    // 26.11.2026 (как в X-plore): папка -> только файлы в ней -> ничего
    @Test fun folder_click_cycle_folder_then_files_then_none() {
        val files = listOf("Download/a.txt", "Download/Pics", "Download/b.txt")
        var s = SelTree.cycle(emptySet(), "Download", files, kids)
        assertEquals(setOf("Download"), s)
        s = SelTree.cycle(s, "Download", files, kids)
        assertEquals(setOf("Download/a.txt", "Download/Pics", "Download/b.txt"), s)
        assertFalse(SelTree.covered(s, "Download"))
        assertFalse(SelTree.covered(s, "Download/Pics/1.jpg") && !SelTree.covered(s, "Download/Pics"))
        assertTrue(SelTree.partial(s, "Download"))
        s = SelTree.cycle(s, "Download", files, kids)
        assertTrue(s.isEmpty())
    }

    @Test fun cycle_keeps_other_selection_and_partial_other_files_select_folder() {
        val files = listOf("Download/a.txt", "Download/b.txt")
        var s: Set<String> = setOf("c.txt", "Download")
        s = SelTree.cycle(s, "Download", files, kids)
        assertEquals(setOf("c.txt", "Download/a.txt", "Download/b.txt"), s)
        s = SelTree.cycle(s, "Download", files, kids)
        assertEquals(setOf("c.txt"), s)
        // отмечен один файл из двух: это не «только файлы», нажатие отмечает папку целиком
        assertEquals(setOf("Download"), SelTree.cycle(setOf("Download/a.txt"), "Download", files, kids))
    }
}
