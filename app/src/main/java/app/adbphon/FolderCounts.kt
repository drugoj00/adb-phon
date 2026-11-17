package app.adbphon

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/**
 * 10.2: число папок и файлов у папки («N папок, N файлов», вторая строка как в X-plore) считается ФОНОМ уже после показа списка:
 * сначала мгновенно дата, цифры подставляются по мере готовности.
 *  — только непосредственные дети (один уровень): список папки берётся через уже открытое соединение источника ([FmRootCfg.fsFor]);
 *  — видимые строки идут первыми (своя очередь), остальные потом; строка ушла с экрана или список закрыт: задача отменяется (Compose отменяет корутину);
 *  — своё ограничение параллельности на вид источника ([Cls]): локально много потоков, по сети мало, облака осторожно;
 *  — кэш в памяти и на диске по ключу «источник+путь+дата изменения»: повторный вход мгновенный, при смене даты папки пересчёт;
 *  — пока программа не на экране ([active] = false) задачи ждут: батарея не тратится.
 */
object FolderCounts {
    class Cls(vis: Int, bg: Int) { val v = Semaphore(vis); val b = Semaphore(bg) }
    val LOCAL = Cls(8, 4)
    val NET = Cls(3, 2)
    val FTP = Cls(2, 1)

    /** Растёт, когда появились новые цифры (пачками, не чаще раза в ~150 мс): строки перечитывают кэш. */
    var ver by mutableIntStateOf(0)
        private set
    @Volatile var active = true

    private val cache: MutableMap<String, Long> = Collections.synchronizedMap(object : LinkedHashMap<String, Long>(512, .75f, false) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Long>?) = size > 4000
    })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var loaded = false
    @Volatile private var dirty = false
    @Volatile private var bumpPending = false
    private var burstStart = 0L
    private var burstN = 0
    private var lastAt = 0L

    private fun pack(d: Int, f: Int) = (d.toLong() shl 32) or (f.toLong() and 0xFFFFFFFFL)
    fun peek(key: String): Pair<Int, Int>? = cache[key]?.let { (it shr 32).toInt() to it.toInt() }
    fun has(key: String) = cache.containsKey(key)

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "fmcounts.tsv")

    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                file(ctx).takeIf { it.exists() }?.forEachLine { l ->
                    val a = l.split('\t'); if (a.size == 3) cache[a[0]] = pack(a[1].toInt(), a[2].toInt())
                }
            }
            loaded = true
        }
    }

    private fun put(ctx: Context, key: String, v: Pair<Int, Int>) {
        cache[key] = pack(v.first, v.second); dirty = true
        val now = System.currentTimeMillis()
        if (now - lastAt > 2000) { burstStart = now; burstN = 0 }
        burstN++; lastAt = now
        if (!bumpPending) {
            bumpPending = true
            scope.launch {
                delay(150)
                bumpPending = false
                ver++
                // запись на диск не чаще раза в несколько секунд
                delay(3000)
                if (dirty) { dirty = false; save(ctx) }
                if (burstN > 0 && System.currentTimeMillis() - lastAt > 2000) { AppLog.i("ADBphon", "FolderCounts: посчитано $burstN за ${lastAt - burstStart} мс"); burstN = 0 }
            }
        }
    }

    private fun save(ctx: Context) = runCatching {
        val snap = synchronized(cache) { cache.entries.map { it.key to it.value } }
        file(ctx).bufferedWriter().use { w -> snap.forEach { (k, v) -> w.write(k); w.write("\t"); w.write(((v shr 32).toInt()).toString()); w.write("\t"); w.write((v.toInt()).toString()); w.newLine() } }
    }

    /**
     * Посчитать и положить в кэш. [visible]: строка на экране (очередь видимых). Приостанавливается, пока нет разрешения очереди;
     * отмена корутины (строка исчезла, список закрыт) снимает ожидание. Источник не ответил: цифр нет, остаётся дата.
     */
    suspend fun load(ctx: Context, key: String, cls: Cls, visible: Boolean, loader: suspend () -> Pair<Int, Int>?) {
        ensureLoaded(ctx)
        if (has(key)) return
        (if (visible) cls.v else cls.b).withPermit {
            while (!active) delay(400)
            if (has(key)) return
            val r = withContext(Dispatchers.IO) { runCatching { loader() }.getOrNull() }
            if (r != null) put(ctx, key, r)
        }
    }

    /** Локальная папка: число папок и файлов (один уровень); null: не читается. */
    fun localCount(d: File): Pair<Int, Int>? {
        val ls = d.listFiles() ?: return null
        var a = 0; var b = 0
        for (x in ls) { if (x.name == FmTrash.DIR) continue; if (x.isDirectory) a++ else b++ }
        return a to b
    }

    /** Папка сетевого источника через открытое соединение (общий список [FmRootCfg.fsFor]). */
    suspend fun netCount(c: NetConn, path: String): Pair<Int, Int>? {
        val l = FmRootCfg.fsFor(c).list(path)
        return l.count { it.isDir && it.name != CloudTrash.DIR } to l.count { !it.isDir }
    }

    fun clsOf(c: NetConn?): Cls = if (c == null) LOCAL else when (c.kind) { "ftp", "ftps" -> FTP; else -> NET }
}
