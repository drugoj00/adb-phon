package app.adbphon

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** 22.11.2026: копирование потока с ходом работы и отменой ([Pump]): данные не искажаются, байты считаются верно, отмена и ошибки не вешают поток. */
class PumpTest {
    private class Prog(val cancelAfter: Long = Long.MAX_VALUE) : OpProgress {
        @Volatile var total = 0L
        override val cancelled get() = total >= cancelAfter
        override fun bytes(n: Long) { total += n }
        override fun file(name: String) {}
        override fun item() {}
    }

    @Test fun dataIntactAndCounted() {
        for (size in listOf(0, 1, 511, 512 * 1024, 512 * 1024 + 7, 5_000_003)) {
            val src = ByteArray(size) { (it * 31 + 7).toByte() }
            val out = ByteArrayOutputStream(); val j = Prog()
            val n = Pump.stream(ByteArrayInputStream(src), out, j)
            assertEquals(size.toLong(), n); assertEquals(size.toLong(), j.total); assertArrayEquals(src, out.toByteArray())
        }
    }

    @Test fun cancelStopsEarly() {
        val src = object : InputStream() { override fun read() = 1; override fun read(b: ByteArray, o: Int, l: Int): Int { java.util.Arrays.fill(b, o, o + l, 1); return l } }
        val j = Prog(cancelAfter = 3L * 512 * 1024)
        try { Pump.stream(src, ByteArrayOutputStream(), j); fail("нет отмены") } catch (_: LocalOps.Cancelled) {}
        assertTrue(j.total < 20L * 512 * 1024)
    }

    @Test fun writerErrorPropagates() {
        val bad = object : OutputStream() { override fun write(b: Int) { throw java.io.IOException("диск полон") }; override fun write(b: ByteArray, o: Int, l: Int) { throw java.io.IOException("диск полон") } }
        try { Pump.stream(ByteArrayInputStream(ByteArray(3_000_000)), bad, null); fail("ошибка потеряна") } catch (e: java.io.IOException) { assertEquals("диск полон", e.message) }
    }

    @Test fun readerErrorPropagatesAndFileRemoved() {
        val f = java.io.File.createTempFile("pump", ".bin")
        val bad = object : InputStream() { var k = 0; override fun read() = 0; override fun read(b: ByteArray, o: Int, l: Int): Int { if (++k > 2) throw java.io.IOException("сеть"); return l } }
        try { Pump.toFile(bad, f, null); fail("ошибка потеряна") } catch (e: java.io.IOException) { assertEquals("сеть", e.message) }
        assertFalse(f.exists())
    }

    @Test fun halfProgressNeverExceedsHalf() {
        val j = Prog(); val h = Pump.Half(j); h.bytes(1001); h.bytes(1001)
        assertEquals(1000L, j.total)
    }
}
