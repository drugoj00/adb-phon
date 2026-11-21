package app.adbphon

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Временные замеры (17.11 ускорение): журнал «PERF» с длительностью шагов старта и раскрытия папок; трассы системы (Trace) для Perfetto. */
/** 21.11.2026 (ускорение «тормозит раскрытие»): stat по каждому файлу большой папки читается в 4 потока (на FUSE один вызов стоит заметно; 600 файлов на Galaxy A7 занимали ~200 мс подряд). Порядок результатов прежний. */
suspend fun <A, B : Any> parallelMapNotNull(src: List<A>, threads: Int = 4, minParallel: Int = 64, f: (A) -> B?): List<B> {
    if (src.size < minParallel) return src.mapNotNull(f)
    val out = arrayOfNulls<Any>(src.size)
    val chunk = (src.size + threads - 1) / threads
    coroutineScope {
        (0 until threads).map { k ->
            async(Dispatchers.IO) { for (i in k * chunk until minOf(src.size, (k + 1) * chunk)) out[i] = f(src[i]) }
        }.forEach { it.await() }
    }
    @Suppress("UNCHECKED_CAST")
    return out.filterNotNull() as List<B>
}

object PerfLog {
    @JvmField var on = false   // замеры выключены: включать только при разборе скорости (журнал PERF)
    val t0 = SystemClock.elapsedRealtime()
    inline fun <T> t(name: String, minMs: Long = 2, block: () -> T): T {
        if (!on) return block()
        android.os.Trace.beginSection(name)
        val s = SystemClock.elapsedRealtimeNanos()
        try { return block() } finally {
            android.os.Trace.endSection()
            val ms = (SystemClock.elapsedRealtimeNanos() - s) / 1_000_000
            if (ms >= minMs) android.util.Log.i("PERF", "$name ${ms} мс (с начала процесса ${SystemClock.elapsedRealtime() - t0} мс)")
        }
    }
    fun mark(name: String) { if (on) android.util.Log.i("PERF", "$name (с начала процесса ${SystemClock.elapsedRealtime() - t0} мс)") }
}
