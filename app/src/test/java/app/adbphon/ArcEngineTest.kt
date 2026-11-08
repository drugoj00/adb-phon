package app.adbphon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

class ArcEngineTest {
    private class P : OpProgress {
        var bytes = 0L; var items = 0
        override val cancelled = false
        override fun bytes(n: Long) { bytes += n }
        override fun file(name: String) {}
        override fun item() { items++ }
    }

    private fun tmp(): File = Files.createTempDirectory("arc").toFile()
    private fun src(r: File): File {
        val s = File(r, "src"); File(s, "папка/вложенная").mkdirs()
        File(s, "привет.txt").writeText("Привет, мир! ".repeat(100))
        File(s, "папка/data.bin").writeBytes(ByteArray(300_000) { (it * 31).toByte() })
        File(s, "папка/вложенная/три.txt").writeText("три")
        File(s, "empty.txt").writeBytes(ByteArray(0))
        return s
    }
    private fun same(a: File, b: File): Boolean {
        if (a.isDirectory != b.isDirectory) return false
        if (a.isFile) return a.readBytes().contentEquals(b.readBytes())
        val la = a.listFiles()!!.sortedBy { it.name }; val lb = b.listFiles()!!.sortedBy { it.name }
        return la.map { it.name } == lb.map { it.name } && la.indices.all { same(la[it], lb[it]) }
    }

    private fun roundTrip(format: String, ext: String, pass: CharArray? = null) {
        val r = tmp(); val s = src(r); val out = File(r, "a.$ext")
        ArcCreate.create(format, out, listOf(s), 6, pass, r, P())
        Arc.open(out, null, pass).use { rd ->
            assertTrue(rd.entries.any { it.path == "src/папка/data.bin" })
            assertEquals(Arc.test(rd, P()), 4)
            val d = File(r, "out"); Arc.extractAll(rd, d, P())
            assertTrue("содержимое отличается ($format)", same(s, File(d, "src")))
        }
        r.deleteRecursively()
    }

    @Test fun zip_roundtrip_with_russian_names() = roundTrip("zip", "zip")
    @Test fun zip_aes256_password() = roundTrip("zip", "zip", "пароль123".toCharArray())
    @Test fun sevenz_roundtrip() = roundTrip("7z", "7z")
    @Test fun tar_gz_roundtrip() = roundTrip("tar.gz", "tar.gz")
    @Test fun tar_bz2_roundtrip() = roundTrip("tar.bz2", "tar.bz2")
    @Test fun tar_xz_roundtrip() = roundTrip("tar.xz", "tar.xz")
    @Test fun tar_roundtrip() = roundTrip("tar", "tar")

    @Test fun zip_wrong_and_missing_password() {
        val r = tmp(); val s = src(r); val out = File(r, "a.zip")
        ArcCreate.create("zip", out, listOf(s), 6, "secret".toCharArray(), r, P())
        Arc.open(out, null, null).use { rd ->
            try { rd.open(rd.entries.first { !it.isDir }).read(); fail() } catch (e: ArcNeedPassword) { }
        }
        Arc.open(out, null, "wrong".toCharArray()).use { rd ->
            try { rd.open(rd.entries.first { !it.isDir }).read(); fail() } catch (e: ArcBadPassword) { }
        }
        r.deleteRecursively()
    }

    @Test fun zip_crc_matches_reference_values() {
        val r = tmp(); val s = src(r); val out = File(r, "a.zip")
        ArcCreate.create("zip", out, listOf(s), 6, null, r, P())
        val z = java.util.zip.ZipFile(out)               // эталон: стандартный java.util.zip читает наш архив
        for (e in z.entries()) if (!e.isDirectory) {
            val crc = CRC32(); z.getInputStream(e).use { i -> val b = ByteArray(8192); while (true) { val k = i.read(b); if (k < 0) break; crc.update(b, 0, k) } }
            assertEquals(e.crc, crc.value)
            val orig = File(s.parentFile, e.name)
            assertEquals(orig.length(), e.size)
        }
        assertTrue(z.getEntry("src/папка/data.bin") != null)
        z.close(); r.deleteRecursively()
    }

    @Test fun zip_slip_is_blocked() {
        val r = tmp(); val z = File(r, "evil.zip")
        ZipOutputStream(z.outputStream()).use { o ->
            for (n in listOf("../../evil1.txt", "/abs/evil2.txt", "ok/../../evil3.txt", "good.txt", "dir\\..\\evil4.txt")) { o.putNextEntry(ZipEntry(n)); o.write("x".toByteArray()); o.closeEntry() }
        }
        val dest = File(r, "dest")
        Arc.open(z).use { rd -> Arc.extractAll(rd, dest, P()) }
        assertTrue(File(dest, "good.txt").exists())
        assertFalse(File(r, "evil1.txt").exists()); assertFalse(File(r.parentFile, "evil1.txt").exists())
        assertFalse(File("/abs/evil2.txt").exists()); assertFalse(File(r, "evil3.txt").exists()); assertFalse(File(r, "evil4.txt").exists())
        // всё извлечённое лежит строго внутри dest
        dest.walkTopDown().forEach { assertTrue(it.canonicalPath.startsWith(dest.canonicalPath)) }
        r.deleteRecursively()
    }

    @Test fun zip_cp866_names_autodetected() {
        val r = tmp(); val z = File(r, "dos.zip")
        val cs = java.nio.charset.Charset.forName("IBM866")
        ZipOutputStream(z.outputStream(), cs).use { o -> o.putNextEntry(ZipEntry("Документы/Отчёт за год.txt")); o.write("1".toByteArray()); o.closeEntry() }
        // Java ставит флаг UTF-8 только для UTF-8; здесь имя в CP866 без флага
        Arc.open(z).use { rd -> assertTrue(rd.entries.map { it.path }.toString(), rd.entries.any { it.path == "Документы/Отчёт за год.txt" }) }
        r.deleteRecursively()
    }

    @Test fun zip_edit_add_delete_rename_keeps_other_entries_and_encryption() {
        val r = tmp(); val s = src(r); val out = File(r, "a.zip")
        ArcCreate.create("zip", out, listOf(s), 6, "pw".toCharArray(), r, P())
        val extra = File(r, "new.txt").also { it.writeText("новый файл") }
        ZipEdit.apply(out, r, null, "pw".toCharArray(), 6, delete = setOf("src/папка/вложенная"), rename = mapOf("src/привет.txt" to "src/hello.txt"), addFiles = listOf(extra to "src/new.txt"))
        Arc.open(out, null, "pw".toCharArray()).use { rd ->
            val names = rd.entries.map { it.path }
            assertTrue(names.toString(), "src/hello.txt" in names && "src/new.txt" in names)
            assertFalse("src/привет.txt" in names); assertFalse(names.any { it.startsWith("src/папка/вложенная") })
            assertTrue("src/папка/data.bin" in names)
            val d = File(r, "o"); Arc.extractAll(rd, d, P())
            assertEquals("новый файл", File(d, "src/new.txt").readText())
            assertTrue(File(d, "src/папка/data.bin").readBytes().contentEquals(File(s, "папка/data.bin").readBytes()))
            assertEquals("Привет, мир! ".repeat(100), File(d, "src/hello.txt").readText())
        }
        assertFalse(File(out.parentFile, out.name + ".fmnew").exists())
        r.deleteRecursively()
    }

    @Test fun extract_policy_skip_and_rename() {
        val r = tmp(); val s = src(r); val out = File(r, "a.zip")
        ArcCreate.create("zip", out, listOf(s), 6, null, r, P())
        val d = File(r, "o"); File(d, "src").mkdirs(); File(d, "src/привет.txt").writeText("старый")
        Arc.open(out).use { Arc.extractAll(it, d, P(), policy = 1) }
        assertEquals("старый", File(d, "src/привет.txt").readText())
        Arc.open(out).use { Arc.extractAll(it, d, P(), policy = 2) }
        assertTrue(File(d, "src/привет (1).txt").exists())
        Arc.open(out).use { Arc.extractAll(it, d, P(), policy = 0) }
        assertTrue(File(d, "src/привет.txt").readText().startsWith("Привет"))
        r.deleteRecursively()
    }

    @Test fun names_differing_only_by_case_do_not_overwrite_each_other() {
        val r = tmp(); val z = File(r, "case.zip")
        ZipOutputStream(z.outputStream()).use { o -> for ((n, c) in listOf("res/Ab.png" to "1", "res/aB.png" to "2")) { o.putNextEntry(ZipEntry(n)); o.write(c.toByteArray()); o.closeEntry() } }
        val d = File(r, "o"); Arc.open(z).use { Arc.extractAll(it, d, P()) }
        val contents = d.walkTopDown().filter { it.isFile }.map { it.readText() }.sorted().toList()
        assertEquals(listOf("1", "2"), contents)
        r.deleteRecursively()
    }

    @Test fun single_gz_and_kinds() {
        val r = tmp(); val f = File(r, "a.txt.gz")
        java.util.zip.GZIPOutputStream(f.outputStream()).use { it.write("hello".toByteArray()) }
        Arc.open(f).use { rd -> assertEquals("a.txt", rd.entries[0].path); assertEquals("hello", String(rd.open(rd.entries[0]).readBytes())) }
        assertEquals(Arc.Kind.TAR, Arc.kindOf("x.TAR.GZ")); assertEquals(Arc.Kind.ZIP, Arc.kindOf("a.apk")); assertEquals(Arc.Kind.RAR, Arc.kindOf("a.rar"))
        assertTrue(Arc.opensAsFolder("a.zip")); assertFalse(Arc.opensAsFolder("a.apk")); assertFalse(Arc.opensAsFolder("a.docx"))
        r.deleteRecursively()
    }

    @Test fun zip_add_replaces_existing_entry() {
        val r = tmp(); val s = src(r); val out = File(r, "a.zip")
        ArcCreate.create("zip", out, listOf(s), 6, null, r, P())
        val nf = File(r, "new.txt"); nf.writeText("NEW")
        ZipEdit.apply(out, r, null, null, 6, addFiles = listOf(nf to "src/привет.txt"))
        Arc.open(out, null, null).use { rd ->
        val hits = rd.entries.filter { it.path == "src/привет.txt" }
        assertEquals(1, hits.size)
        assertEquals("NEW", rd.open(hits[0]).use { it.readBytes().toString(Charsets.UTF_8) })
        }
    }
}
