package app.adbphon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/**
 * ShellApk (значок APK по частям через оболочку) на настоящих файлах: оболочка устройства имитируется (unzip -p, tail -c, dd).
 * Проверяются оба способа: `unzip -p` и свой разбор zip по смещениям (когда unzip нет). Результат должен совпасть с чтением через ZipFile.
 * Папка с APK: переменная APK_CORPUS (по умолчанию C:\Программы\АНДРОИД); нет папки: тест пропускается.
 */
class ShellApkTest {
    /** Имитация оболочки: понимает только команды, которые строит ShellApk. */
    private class FakeSh(val f: File, val unzipWorks: Boolean, val cut: Boolean = false, override val reliable: Boolean = true, val flaky: Boolean = false) : ShellApk.Sh {
        override val channel = "тест"
        private val rnd = java.util.Random(7)
        var cmds = 0
        private fun args(cmd: String): List<String> = Regex("'((?:[^']|'\\\\'')*)'").findAll(cmd).map { it.groupValues[1].replace("'\\''", "'") }.toList()
        override fun run(cmd: String, max: Int, timeoutMs: Long, expect: Int): ShellApk.Out {
            cmds++
            when {
                cmd.startsWith("printf %020d") -> return ShellApk.Out(String.format("%020d", f.length()).toByteArray(), "")
                cmd.startsWith("unzip -p") -> {
                    if (!unzipWorks) return ShellApk.Out(ByteArray(0), "")
                    val a = args(cmd)
                    val name = a[1].replace(Regex("""\\([\[\]*?\\])""")) { it.groupValues[1] }
                    ZipFile(f).use { z ->
                        val e = z.getEntry(name) ?: return ShellApk.Out(ByteArray(0), "")
                        val b = z.getInputStream(e).use { it.readBytes() }
                        return ShellApk.Out(b.copyOf(minOf(if (cut && b.size > 4096) b.size * 95 / 100 else b.size, max)), "")
                    }
                }
                cmd.startsWith("tail -c") -> {
                    val n = cmd.removePrefix("tail -c ").substringBefore(' ').toLong()
                    RandomAccessFile(f, "r").use { r ->
                        val start = maxOf(0L, r.length() - n)
                        val b = ByteArray((r.length() - start).toInt()); r.seek(start); r.readFully(b)
                        return ShellApk.Out(b, "")
                    }
                }
                cmd.startsWith("dd if=") -> {
                    val skip = Regex("skip=(\\d+)").find(cmd)!!.groupValues[1].toLong()
                    val count = Regex("count=(\\d+)").find(cmd)!!.groupValues[1].toLong()
                    RandomAccessFile(f, "r").use { r ->
                        val start = skip * 4096
                        if (start >= r.length()) return ShellApk.Out(ByteArray(0), "")
                        val b = ByteArray(minOf(count * 4096, r.length() - start).toInt()); r.seek(start); r.readFully(b)
                        if (b.size > max) return ShellApk.Out(null, "больше max")
                        // связь ADB иногда обрывает ответ: отдаётся только начало
                        if (flaky && b.size > 8192 && rnd.nextInt(2) == 0) return ShellApk.Out(b.copyOf(4096 * (1 + rnd.nextInt(b.size / 4096 - 1))), "поток оборван")
                        return ShellApk.Out(b, "")
                    }
                }
                cmd.startsWith("(ls") -> return ShellApk.Out("unzip: not found".toByteArray(), "")
            }
            return ShellApk.Out(null, "неизвестная команда: $cmd")
        }
    }

    private fun corpus(): List<File> {
        val root = File(System.getenv("APK_CORPUS") ?: "C:\\Программы\\АНДРОИД")
        assumeTrue(root.isDirectory)
        // разные размеры: берём каждый 12-й файл из списка, до 40 штук, плюс самый большой до 300 МБ
        val all = root.walkTopDown().filter { it.isFile && it.extension.equals("apk", true) && it.length() < 300L * 1024 * 1024 }.take(600).toList()
        assumeTrue(all.isNotEmpty())
        val pick = all.filterIndexed { i, _ -> i % 12 == 0 }.take(40).toMutableList()
        all.maxByOrNull { it.length() }?.let { if (it !in pick) pick += it }
        return pick
    }

    private fun check(unzipWorks: Boolean, reliable: Boolean = true, flaky: Boolean = false) {
        var same = 0; var diff = 0
        val report = StringBuilder()
        for (f in corpus()) {
            val zr = try { ZipFile(f).use { ApkIconRes.resolve(it) } } catch (e: Throwable) { continue }
            val sh = FakeSh(f, unzipWorks, reliable = reliable, flaky = flaky)
            val sa = ShellApk(f.path, sh, budgetMs = 120_000L)
            val r = try { ApkIconRes.resolve(sa) } catch (e: Throwable) { report.append("ER ${f.name}: ${e}\n"); diff++; continue }
            val ok = ApkIconRes.describe(r.root) == ApkIconRes.describe(zr.root)
            if (ok) same++ else { diff++; report.append("РАЗНО ${f.name}: zip=[${ApkIconRes.describe(zr.root)}] shell=[${ApkIconRes.describe(r.root)}] ${sa.stats()} err=${sa.err} trace=${r.trace}\n") }
            if (unzipWorks && reliable) assertEquals("способ", "unzip -p", sa.mode) else assertTrue("ожидался разбор zip: ${sa.stats()}", sa.mode == "dd+zip" || zr.root == null)
        }
        println("unzip=$unzipWorks: совпало $same, различий $diff\n$report")
        assertEquals(report.toString(), 0, diff)
    }

    /** Разбор одного файла из переменной APK_ONE (список через ;): печатает трассу через ZipFile и через оболочку. Без переменной пропускается. */
    @Test fun oneFile() {
        val list = System.getenv("APK_ONE") ?: return
        for (n in list.split(';').filter { it.isNotBlank() }) {
            val f = File(n)
            val z = ZipFile(f).use { ApkIconRes.resolve(it) }
            val sa = ShellApk(f.path, FakeSh(f, true), 120_000L)
            val r = ApkIconRes.resolve(sa)
            println("ONE ${f.name} | zip: ${z.trace} | shell: ${r.trace} | ${sa.stats()} err=${sa.err}")
        }
    }

    @Test fun unzipMode() = check(true)

    @Test fun rangeModeWithoutUnzip() = check(false)

    /** Связь ADB: ненадёжный канал (ответы обрываются) читается только по смещениям с дочитыванием и проверкой CRC. */
    @Test fun unreliableChannelFlaky() = check(true, reliable = false, flaky = true)

    /** Канал отдал неполный ответ (как связь ADB на больших записях): ShellApk должен заметить и дочитать своим разбором zip. */
    @Test fun truncatedUnzipFallsBack() {
        var checked = 0
        for (f in corpus().take(15)) {
            val zr = try { ZipFile(f).use { ApkIconRes.resolve(it) } } catch (e: Throwable) { continue }
            val sa = ShellApk(f.path, FakeSh(f, true, cut = true), 60_000L)
            val r = ApkIconRes.resolve(sa)
            assertEquals(f.name + " " + sa.stats(), ApkIconRes.describe(zr.root), ApkIconRes.describe(r.root))
            checked++
        }
        assertTrue(checked > 0)
    }

    @Test fun missingEntryIsNullNotError() {
        val f = corpus().first()
        for (w in listOf(true, false)) {
            val sa = ShellApk(f.path, FakeSh(f, w), 60_000L)
            assertEquals(null, sa.read("no/such/file.png", 1000))
        }
    }
}
