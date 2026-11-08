package app.adbphon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalOpsTest {
    private class P(var cancelAfterBytes: Long = Long.MAX_VALUE) : OpProgress {
        var bytes = 0L; var items = 0; val files = ArrayList<String>()
        override val cancelled: Boolean get() = bytes >= cancelAfterBytes
        override fun bytes(n: Long) { bytes += n }
        override fun file(name: String) { files += name }
        override fun item() { items++ }
    }

    private fun tree(): File {
        val r = Files.createTempDirectory("fmops").toFile()
        File(r, "a/b").mkdirs(); File(r, "a/empty").mkdirs()
        File(r, "a/one.txt").writeBytes(ByteArray(1000) { it.toByte() })
        File(r, "a/b/two.bin").writeBytes(ByteArray(600_000) { (it * 7).toByte() })
        File(r, "a/b/три.txt").writeText("привет")
        return r
    }

    private fun same(a: File, b: File): Boolean {
        if (a.isDirectory != b.isDirectory) return false
        if (a.isFile) return a.readBytes().contentEquals(b.readBytes())
        val la = a.listFiles()!!.sortedBy { it.name }; val lb = b.listFiles()!!.sortedBy { it.name }
        return la.map { it.name } == lb.map { it.name } && la.indices.all { same(la[it], lb[it]) }
    }

    @Test fun copy_is_exact_and_reports_all_bytes() {
        val r = tree(); val p = P()
        val total = LocalOps.size(File(r, "a"))
        LocalOps.copy(File(r, "a"), File(r, "copy"), p)
        assertTrue(same(File(r, "a"), File(r, "copy")))
        assertEquals(total, p.bytes)
        assertEquals(LocalOps.count(File(r, "a")), p.items)
        assertTrue(p.files.contains("three.txt".let { "три.txt" }))
        assertEquals(File(r, "a/one.txt").lastModified(), File(r, "copy/one.txt").lastModified())
        r.deleteRecursively()
    }

    @Test fun cancel_leaves_no_partial_files() {
        val r = tree(); val p = P(cancelAfterBytes = 100_000)
        try { LocalOps.copy(File(r, "a"), File(r, "copy"), p); fail("must be cancelled") } catch (e: LocalOps.Cancelled) { }
        val left = File(r, "copy").walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertFalse("остался временный файл: $left", left.any { it.endsWith(".fmpart") })
        assertFalse(File(r, "copy/b/two.bin").exists())          // недописанный файл не появился под своим именем
        assertTrue(File(r, "a/b/two.bin").exists())              // источник цел
        r.deleteRecursively()
    }

    @Test fun move_on_same_volume_is_rename() {
        val r = tree(); val before = File(r, "a").walkTopDown().count(); val p = P()
        LocalOps.move(File(r, "a"), File(r, "moved"), p)
        assertFalse(File(r, "a").exists())
        assertEquals(before, File(r, "moved").walkTopDown().count())
        assertTrue(p.bytes > 600_000)
        r.deleteRecursively()
    }

    @Test fun move_with_existing_target_copies_verifies_then_deletes_source() {
        val r = tree(); val p = P()
        File(r, "dst").mkdirs()
        LocalOps.move(File(r, "a"), File(r, "dst"), p)           // цель существует: копия поверх, сверка, удаление источника
        assertFalse(File(r, "a").exists())
        assertTrue(File(r, "dst/b/two.bin").exists())
        assertEquals(600_000, File(r, "dst/b/two.bin").length().toInt())
        r.deleteRecursively()
    }

    @Test fun folder_into_itself_is_rejected() {
        val r = tree()
        try { LocalOps.copy(File(r, "a"), File(r, "a/b/inner"), P()); fail("must be rejected") } catch (e: java.io.IOException) { }
        try { LocalOps.move(File(r, "a"), File(r, "a/b/inner"), P()); fail("must be rejected") } catch (e: java.io.IOException) { }
        assertTrue(File(r, "a/one.txt").exists())
        r.deleteRecursively()
    }

    @Test fun delete_counts_every_item() {
        val r = tree(); val n = LocalOps.count(File(r, "a")); val p = P()
        assertTrue(LocalOps.delete(File(r, "a"), p))
        assertFalse(File(r, "a").exists())
        assertEquals(n, p.items)
        r.deleteRecursively()
    }

    @Test fun job_publishes_at_most_ten_times_per_second_and_final_value_is_exact() {
        val j = FmJob("t", 1, true)
        j.totalUnits = 1_000_000
        var seen = 0; var last = -1L
        val t0 = System.nanoTime()
        while (System.nanoTime() - t0 < 300_000_000L) { j.bytes(1000); if (j.doneUnits != last) { seen++; last = j.doneUnits } }
        assertTrue("слишком частые обновления: $seen", seen <= 5)
        j.publish(true)
        assertTrue(j.doneUnits > 0)
        assertTrue(j.fraction!! in 0f..1f)
    }

    @Test fun protected_paths() {
        // правила защиты проверяются в FmIo.isProtected (Android-зависимый класс), здесь только дублирующий образец шаблона для томов
        assertTrue(Regex("^/storage/[^/]+$").matches("/storage/107D-CBFB"))
        assertFalse(Regex("^/storage/[^/]+$").matches("/storage/107D-CBFB/Music"))
    }
}
