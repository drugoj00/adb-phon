package app.adbphon

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 22.11.2026 (18.0, тормоза LAN): копирование потока из сети в файл.
 * Раньше везде был `InputStream.copyTo(OutputStream)` с буфером 8 КБ: на SMB это отдельный запрос на каждые 8 КБ, ход работы не показывался.
 * Теперь: буфер 512 КБ (SMB читает по размеру, согласованному с сервером, но уже без лишних запросов на каждые 8 КБ), чтение и запись
 * идут в двух потоках (пока пишется кусок на медленный диск приставки, читается следующий), байты считаются по факту записи ([OpProgress.bytes]),
 * отмена проверяется на каждом куске ([LocalOps.Cancelled]). Без Android: проверяется обычным тестом на JVM.
 */
object Pump {
    private const val CHUNK = 512 * 1024
    private const val SLOTS = 4
    /** Скачивание + загрузка (сеть в сеть) идут двумя проходами: каждый даёт половину байт, чтобы полоса не уходила за 100%. */
    class Half(private val j: OpProgress) : OpProgress {
        override val cancelled get() = j.cancelled
        override fun bytes(n: Long) = j.bytes(n / 2)
        override fun file(name: String) {}
        override fun item() {}
    }
    private class Piece(val b: ByteArray, val n: Int)

    /** Перекачать [i] в [o]; закрывать потоки должен вызывающий. Возвращает число записанных байт. */
    fun stream(i: InputStream, o: OutputStream, j: OpProgress?): Long {
        val free = ArrayBlockingQueue<ByteArray>(SLOTS)
        repeat(SLOTS) { free.add(ByteArray(CHUNK)) }
        val full = ArrayBlockingQueue<Piece>(SLOTS + 1)
        val err = AtomicReference<Throwable?>(null)
        val written = java.util.concurrent.atomic.AtomicLong()
        val end = Piece(ByteArray(0), -1)
        val w = Thread({
            try {
                while (true) {
                    val p = full.take()
                    if (p.n < 0) break
                    o.write(p.b, 0, p.n)
                    written.addAndGet(p.n.toLong())
                    j?.bytes(p.n.toLong())
                    free.put(p.b)
                }
            } catch (t: Throwable) { err.set(t) }
        }, "fm-pump").also { it.isDaemon = true; it.start() }
        try {
            while (true) {
                if (j?.cancelled == true) throw LocalOps.Cancelled()
                var b: ByteArray? = null
                while (b == null) { err.get()?.let { throw it }; if (j?.cancelled == true) throw LocalOps.Cancelled(); b = free.poll(200, TimeUnit.MILLISECONDS) }
                val n = i.read(b)
                if (n < 0) break
                if (n == 0) { free.put(b); continue }
                val piece = Piece(b, n)
                while (!full.offer(piece, 200, TimeUnit.MILLISECONDS)) { err.get()?.let { throw it }; if (j?.cancelled == true) throw LocalOps.Cancelled() }
            }
            while (!full.offer(end, 200, TimeUnit.MILLISECONDS)) { err.get()?.let { throw it } }
            w.join()
            err.get()?.let { throw it }
            return written.get()
        } catch (t: Throwable) {
            w.interrupt()
            throw t
        }
    }

    /** Скачать поток в файл [dest] через [stream]; при ошибке или отмене недокачанный файл удаляется. */
    fun toFile(i: InputStream, dest: File, j: OpProgress?): Long {
        dest.parentFile?.mkdirs()
        var ok = false
        try {
            val n = dest.outputStream().use { o -> stream(i, o, j) }
            ok = true
            return n
        } finally { if (!ok) runCatching { dest.delete() } }
    }
}
