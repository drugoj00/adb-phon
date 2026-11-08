package app.adbphon

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Проверка совпадений при копировании: верхний уровень, вложенные файлы, регистр (FAT и ext4), слияние папок, планы по решениям. */
class ClashLogicTest {
    /** Фальшивое дерево: ключи — полные пути; [ci]: имена без учёта регистра (FAT/exFAT), иначе с учётом (ext4). */
    class Fake(files: Map<String, CNode>, val ci: Boolean = false) : CTree {
        private val m = files.mapKeys { if (ci) it.key.lowercase() else it.key }
        private fun k(p: String) = (if (ci) p.lowercase() else p).trimEnd('/')
        override suspend fun stat(path: String): CNode? = m[k(path)]
        override suspend fun list(path: String): List<String> {
            val base = path.trimEnd('/') + "/"
            return m.keys.filter { it.startsWith(base.let { b -> if (ci) b.lowercase() else b }) && !it.substring(base.length).contains('/') }
                .map { it.substring(base.length) }
        }
    }
    private fun f(size: Long = 1, t: Long = 1000) = CNode(false, size, t)
    private fun d() = CNode(true, 0, 1000)
    private fun <T> run(b: suspend () -> T): T = runBlocking { b() }

    @Test fun noClash() {
        val src = Fake(mapOf("/s/a.txt" to f()))
        val dst = Fake(mapOf("/d/b.txt" to f()))
        val r = run { ClashLogic.scan(src, "/s", dst, "/d", listOf("a.txt")) }
        assertTrue(r.top.isEmpty()); assertEquals(0, r.inner)
    }

    @Test fun sameNameFile() {
        val src = Fake(mapOf("/s/a.txt" to f(14, 5000)))
        val dst = Fake(mapOf("/d/a.txt" to f(7, 1000)))
        val r = run { ClashLogic.scan(src, "/s", dst, "/d", listOf("a.txt", "b.txt")) }
        assertEquals(listOf("a.txt"), r.top.map { it.rel })
        assertEquals(1, ClashLogic.newer(r.top[0].src, r.top[0].dst))
    }

    @Test fun caseSensitiveAndInsensitive() {
        val src = Fake(mapOf("/s/Photo.JPG" to f()))
        // ext4: Photo.JPG и photo.jpg — разные файлы
        assertTrue(run { ClashLogic.scan(src, "/s", Fake(mapOf("/d/photo.jpg" to f()), ci = false), "/d", listOf("Photo.JPG")) }.top.isEmpty())
        // FAT/exFAT: это один и тот же файл
        assertEquals(1, run { ClashLogic.scan(src, "/s", Fake(mapOf("/d/photo.jpg" to f()), ci = true), "/d", listOf("Photo.JPG")) }.top.size)
    }

    @Test fun unicodeAndSpaces() {
        val n = "Фото отпуск (1) №2.jpg"
        val r = run { ClashLogic.scan(Fake(mapOf("/s/$n" to f())), "/s", Fake(mapOf("/d/$n" to f())), "/d", listOf(n)) }
        assertEquals(1, r.top.size)
    }

    @Test fun folderMergeCountsInnerFiles() {
        val src = Fake(mapOf("/s/p" to d(), "/s/p/a.txt" to f(), "/s/p/new.txt" to f(), "/s/p/sub" to d(), "/s/p/sub/x" to f(), "/s/p/sub/y" to f()))
        val dst = Fake(mapOf("/d/p" to d(), "/d/p/a.txt" to f(), "/d/p/sub" to d(), "/d/p/sub/x" to f()))
        val r = run { ClashLogic.scan(src, "/s", dst, "/d", listOf("p")) }
        assertEquals(1, r.top.size); assertTrue(r.top[0].merge)
        assertEquals(2, r.inner) // a.txt и sub/x
    }

    @Test fun typeDiffers() {
        val r = run { ClashLogic.scan(Fake(mapOf("/s/p" to d())), "/s", Fake(mapOf("/d/p" to f())), "/d", listOf("p")) }
        assertTrue(r.top[0].typeDiffers)
    }

    @Test fun planSkipMergeAddsOnlyNew() {
        val src = Fake(mapOf("/s/p" to d(), "/s/p/a.txt" to f(), "/s/p/new.txt" to f(), "/s/p/sub" to d(), "/s/p/sub/x" to f(), "/s/p/sub/y" to f()))
        val dst = Fake(mapOf("/d/p" to d(), "/d/p/a.txt" to f(), "/d/p/sub" to d(), "/d/p/sub/x" to f()))
        val p = run { ClashLogic.plan(src, "/s", dst, "/d", listOf("p"), mapOf("p" to CAct.SKIP)) { it } }
        assertEquals(setOf("p/new.txt", "p/sub/y"), p.ops.map { it.from }.toSet())
        assertEquals(listOf("p"), p.merged)
    }

    @Test fun planReplaceMergeOverwritesBoth() {
        val src = Fake(mapOf("/s/p" to d(), "/s/p/a.txt" to f(), "/s/p/new.txt" to f()))
        val dst = Fake(mapOf("/d/p" to d(), "/d/p/a.txt" to f()))
        val p = run { ClashLogic.plan(src, "/s", dst, "/d", listOf("p"), mapOf("p" to CAct.REPLACE)) { it } }
        assertEquals(setOf("p/a.txt", "p/new.txt"), p.ops.map { it.from }.toSet())
    }

    @Test fun planRenameUsesFreeName() {
        val src = Fake(mapOf("/s/a.txt" to f()))
        val dst = Fake(mapOf("/d/a.txt" to f(), "/d/a (1).txt" to f()))
        val p = run { ClashLogic.plan(src, "/s", dst, "/d", listOf("a.txt"), mapOf("a.txt" to CAct.RENAME)) { n -> ClashLogic.freeName(n) { dst.stat("/d/$it") != null } } }
        assertEquals("a (2).txt", p.ops.single().to)
    }

    @Test fun planDefaultIsSkipAndNewIsCopied() {
        val src = Fake(mapOf("/s/a.txt" to f(), "/s/b.txt" to f()))
        val dst = Fake(mapOf("/d/a.txt" to f()))
        val p = run { ClashLogic.plan(src, "/s", dst, "/d", listOf("a.txt", "b.txt"), emptyMap()) { it } }
        assertEquals(listOf("b.txt"), p.ops.map { it.from })
    }

    @Test fun replaceDifferentTypeDeletesFirst() {
        val src = Fake(mapOf("/s/p" to d()))
        val dst = Fake(mapOf("/d/p" to f()))
        val p = run { ClashLogic.plan(src, "/s", dst, "/d", listOf("p"), mapOf("p" to CAct.REPLACE)) { it } }
        assertTrue(p.ops.single().deleteFirst)
    }

    @Test fun newerWithFatTolerance() {
        assertEquals(0, ClashLogic.newer(f(t = 10_000), f(t = 11_500)))
        assertEquals(1, ClashLogic.newer(f(t = 20_000), f(t = 10_000)))
        assertEquals(-1, ClashLogic.newer(f(t = 10_000), f(t = 20_000)))
        assertEquals(0, ClashLogic.newer(f(t = 0), f(t = 20_000)))
    }
}
