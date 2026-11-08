@file:OptIn(ExperimentalFoundationApi::class)
package app.adbphon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Операция с сетевым источником не ответила за отведённое время: соединение считается мёртвым (полуоткрытый TCP). */
class NetTimeout(what: String) : IOException("timed out: $what")

/**
 * Постоянное соединение для сетевых источников (SMB, SFTP, FTP/FTPS, WebDAV): обёртка над [RemoteFs].
 *  — перед операцией после простоя (> 8 с) делается быстрая проверка связи (до 2,5 с): мёртвое соединение закрывается и открывается новое;
 *  — операция с ошибкой связи (таймаут, сброс, обрыв, «сессия закрыта сервером») повторяется один раз на НОВОМ соединении, без окон ошибок;
 *  — ни одна операция чтения списка/проверки не ждёт дольше 8 с (раньше ждали TCP-повтор ~2 минуты);
 *  — пока источник открыт, [NetKeep] каждые ~12 с делает лёгкий запрос, держит Wi-Fi и сообщает о смене сети.
 * Кэш каталогов панели, раскрытые папки и учётные данные (замыкание [make]) при переподключении сохраняются.
 */
class KeepFs(private val make: () -> RemoteFs) : RemoteFs {
    companion object {
        const val OFFLINE = 0; const val OK = 1; const val RECONNECT = 2
        const val OP_MS = 8000L; const val PING_MS = 2500L; const val IDLE_PING_MS = 8000L
        val all: MutableSet<KeepFs> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<KeepFs, Boolean>()))

        /** Ошибка связи (стоит переподключиться), а не отказ сервера (неверный пароль, нет файла, нет прав). */
        fun isNetDead(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(8).any { t ->
            val m = (t.message ?: "").lowercase()
            when {
                t is NetTimeout || t is SocketException || t is SocketTimeoutException || t is EOFException || t is javax.net.ssl.SSLException -> true
                t.javaClass.name.startsWith("jcifs.util.transport.") -> true
                t is java.net.UnknownHostException -> false
                t.javaClass.name.startsWith("jcifs.") -> Regex("transport|connection|timed out|timeout|closed|disconnect|reset|io_timeout|network_name_deleted|session_deleted|invalid_handle|broken").containsMatchIn(m) && !m.contains("logon") && !m.contains("access_denied")
                t is com.jcraft.jsch.JSchException -> Regex("timeout|session is down|connection is closed|broken pipe|reset|socket|eof|packet corrupt|channel is not opened|connect").containsMatchIn(m) && !m.contains("auth")
                t is IOException -> Regex("closed|reset|broken pipe|timed out|timeout|eof|unexpected end|connection|stream").containsMatchIn(m) && !m.contains("401") && !m.contains("not found")
                else -> false
            }
        }
    }

    @Volatile private var inner: RemoteFs = make()
    private val gen = AtomicInteger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Состояние для значка: нет связи / подключено / переподключение. */
    var state by mutableIntStateOf(OK)
    @Volatile var lastOk = System.currentTimeMillis()
    @Volatile var lastPath = ""
    @Volatile var stale = false
    @Volatile private var checking = false
    /** Сколько операций сейчас идёт на этом подключении (копирование, чтение списка, загрузка): пока больше нуля, периодические проверки связи не выполняются, чтобы не рвать передачу (FTP, SFTP, SMB, WebDAV). */
    private val busy = AtomicInteger()
    val isBusy: Boolean get() = busy.get() > 0
    @Volatile private var lastReset = 0L

    init { all.add(this) }

    override val raw: RemoteFs get() = inner
    override val root get() = inner.root
    override val label get() = inner.label
    override fun parent(path: String) = inner.parent(path)
    override suspend fun roots() = inner.roots()
    override fun close() { runCatching { inner.close() } }

    private fun log(s: String) = AppLog.i("ADBphon", "LAN ${inner.label}: $s")

    /** Закрыть мёртвый транспорт и открыть новый (только если никто другой уже не сделал этого после ошибки). */
    private fun reset(seen: Int, why: String) {
        synchronized(this) {
            if (gen.get() != seen) return
            gen.incrementAndGet()
            lastReset = System.currentTimeMillis()
            log("переподключение ($why)")
            state = RECONNECT
            val old = inner
            inner = make()
            Thread { runCatching { old.close() } }.apply { isDaemon = true }.start()
        }
    }

    private suspend fun <T> timed(ms: Long?, what: String, block: suspend () -> T): T {
        val d = scope.async { block() }
        if (ms == null) return d.await()
        // результат оборачивается: null (например, «записи нет») не должен считаться таймаутом
        val r = withTimeoutOrNull(ms) { arrayOf<Any?>(d.await()) }
        if (r == null) { d.cancel(); throw NetTimeout("$what > ${ms / 1000} с") }
        @Suppress("UNCHECKED_CAST")
        return r[0] as T
    }

    /** Быстрая проверка связи после простоя. true — жива (или проверка неприменима). */
    private suspend fun preflight() {
        if (checking || isBusy) return
        checking = true
        try {
            val g = gen.get()
            val t0 = System.currentTimeMillis()
            val r = runCatching { timed(PING_MS, "проверка") { inner.ping(lastPath.ifEmpty { inner.root }) } }
            val e = r.exceptionOrNull()
            if (e == null) { lastOk = System.currentTimeMillis(); if (state != OK) state = OK }
            else if (isNetDead(e)) { log("проверка связи не прошла за ${System.currentTimeMillis() - t0} мс: ${e.message}"); reset(g, "проверка"); lastOk = 0 }
            else lastOk = System.currentTimeMillis()         // отказ сервера на сам запрос проверки (нет папки и т.п.): связь есть
        } finally { checking = false }
    }

    /**
     * Выполнить операцию. [big]: передача данных без общего ограничения по времени (контроль по простою сокета), иначе не дольше 8 с на попытку.
     * Ошибка связи: одна повторная попытка на новом соединении; затем понятная ошибка (никогда не ждёт минуты).
     */
    suspend fun <T> call(path: String?, big: Boolean = false, what: String = "операция", op: suspend (RemoteFs) -> T): T {
        if (path != null) lastPath = path
        if (stale) { stale = false; reset(gen.get(), "сеть изменилась") }
        else if (System.currentTimeMillis() - lastOk > IDLE_PING_MS) preflight()
        busy.incrementAndGet()
        try {
        for (attempt in 1..2) {
            val g = gen.get(); val fs = inner
            try {
                val r = timed(if (big) null else OP_MS, what) { op(fs) }
                lastOk = System.currentTimeMillis(); if (state != OK) state = OK
                return r
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (!isNetDead(e)) { lastOk = System.currentTimeMillis(); throw e }       // отказ сервера: переподключение не поможет
                log("$what: ${e.javaClass.simpleName}: ${e.message} (попытка $attempt)")
                if (attempt == 2) { state = OFFLINE; throw e }
                reset(g, e.javaClass.simpleName)
            }
        }
        error("unreachable")
        } finally { busy.decrementAndGet() }
    }

    override suspend fun list(path: String): List<FsEntry> = call(path, what = "список папки") { it.list(path).filter { e -> e.name != CloudTrash.DIR && !isTmpName(e.name) } }
    private fun isTmpName(n: String) = n.startsWith(".adbphon-") && (n.endsWith(".part") || n.endsWith(".old"))

    // ---- запись: каждая операция идёт через call (счётчик активных операций, повтор на новом соединении при обрыве) ----
    override suspend fun stat(path: String): FsEntry? = call(path.trimEnd('/').substringBeforeLast('/', ""), what = "проверка") { it.stat(path) }
    override suspend fun mkdirRemote(path: String) = call(path.trimEnd('/').substringBeforeLast('/', ""), what = "создание папки") { it.mkdirRemote(path) }
    override suspend fun renameRemote(from: String, to: String, overwrite: Boolean) = call(from.trimEnd('/').substringBeforeLast('/', ""), what = "переименование") { it.renameRemote(from, to, overwrite) }
    override suspend fun removeRemote(path: String, isDir: Boolean) = call(path.trimEnd('/').substringBeforeLast('/', ""), what = "удаление") { it.removeRemote(path, isDir) }
    override suspend fun putFile(src: File, dest: String, j: OpProgress?) = call(dest.trimEnd('/').substringBeforeLast('/', ""), big = true, what = "запись") { it.putFile(src, dest, j) }

    private fun child(dir: String, name: String) = dir.trimEnd('/') + "/" + name

    /** Папка есть или будет создана (для копирования папок целиком). */
    suspend fun ensureDir(path: String) { if (stat(path)?.isDir == true) return; mkdirRemote(path) }

    /**
     * Безопасная запись файла [src] в [dest] ([replace]: заменить существующий): сначала во временное имя в той же папке (.adbphon-….part),
     * проверка размера, затем переименование поверх оригинала; если сервер не умеет заменять при переименовании, оригинал уходит во временное
     * имя (.old), новый встаёт на место и только потом старый удаляется. При любом сбое или отмене оригинал цел, временные файлы убираются.
     */
    suspend fun upload(src: File, dest: String, replace: Boolean, j: OpProgress? = null) {
        val dir = dest.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
        val tag = java.lang.Long.toString(System.nanoTime() and 0xFFFFFFFFFL, 36)
        val tmp = child(dir, ".adbphon-$tag.part"); val old = child(dir, ".adbphon-$tag.old")
        var tmpLeft = false
        try {
            val ex = stat(dest)
            if (ex != null && (!replace || ex.isDir)) throw java.io.IOException(FmIo.app.getString(R.string.fm_err_exists))
            tmpLeft = true
            putFile(src, tmp, j)
            val got = stat(tmp)?.size ?: -1L
            if (got != src.length()) throw java.io.IOException(FmIo.app.getString(R.string.fm_up_size_mismatch, got, src.length()))
            if (ex == null) { renameRemote(tmp, dest, false); tmpLeft = false }
            else {
                val direct = runCatching { renameRemote(tmp, dest, true) }
                if (direct.isSuccess) tmpLeft = false
                else {
                    (direct.exceptionOrNull() as? CancellationException)?.let { throw it }
                    // сервер не заменяет при переименовании: оригинал во временное имя, новый на место, старый долой; сбой возвращает оригинал
                    renameRemote(dest, old, false)
                    try { renameRemote(tmp, dest, false); tmpLeft = false } catch (e: Throwable) { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { renameRemote(old, dest, false) } }; throw e }
                    runCatching { removeRemote(old, false) }
                }
            }
        } finally {
            if (tmpLeft) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                // после обрыва или отмены сервер ещё может дописывать принятые пакеты: убираем временный файл с проверкой и повтором
                for (k in 1..4) {
                    kotlinx.coroutines.delay(if (k == 1) 250L else 800L)
                    runCatching { removeRemote(tmp, false) }.onFailure { log("временный файл $tmp: ${it.javaClass.simpleName}: ${it.message}") }
                    if (runCatching { stat(tmp) }.getOrNull() == null) break
                }
            }
        }
    }
    override suspend fun copyTo(path: String, dest: File) = call(path.substringBeforeLast('/', ""), big = true, what = "копирование") { it.copyTo(path, dest) }
    override suspend fun head(path: String, n: Int): ByteArray = call(path.substringBeforeLast('/', ""), what = "чтение файла") { it.head(path, n) }
    override suspend fun ping(path: String) = call(path, what = "проверка") { it.ping(path) }

    /** Подключение при открытии источника (SFTP и FTP проверяют вход сразу). */
    suspend fun connect() = call(null, what = "подключение") { f -> when (f) { is SftpFs -> f.connect(); is FtpFs -> f.connect(); else -> {} } }

    /** Лёгкий запрос по расписанию [NetKeep]: держит сессию живой и обновляет значок. */
    suspend fun keepalive() {
        if (checking || isBusy) return
        val g = gen.get()
        val r = runCatching { timed(PING_MS * 2, "keepalive") { inner.ping(lastPath.ifEmpty { inner.root }) } }
        val e = r.exceptionOrNull()
        when {
            e == null -> { lastOk = System.currentTimeMillis(); if (state != OK) { log("связь восстановлена"); state = OK } }
            isNetDead(e) -> { reset(g, "keepalive: ${e.message}"); runCatching { timed(PING_MS * 2, "keepalive") { inner.ping(lastPath.ifEmpty { inner.root }) } }.onSuccess { lastOk = System.currentTimeMillis(); state = OK }.onFailure { state = OFFLINE } }
            else -> lastOk = System.currentTimeMillis()
        }
    }

    /**
     * Сеть телефона сменилась ([force]) или экран включили / приложение вернулось ([force]=false): проверить связь сразу.
     * При смене сети соединение пересоздаётся (но не чаще раза в 1,5 с: события приходят пачкой), при включении экрана — только если проверка не прошла.
     */
    suspend fun invalidateNow(why: String, force: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastOk < 3000 || isBusy)) return
        stale = false
        if (force) { if (now - lastReset >= 1500) reset(gen.get(), why) }
        else {
            val g = gen.get()
            val e = runCatching { timed(PING_MS, "проверка") { inner.ping(lastPath.ifEmpty { inner.root }) } }.exceptionOrNull()
            if (e == null || !isNetDead(e)) { lastOk = System.currentTimeMillis(); if (state != OK) state = OK; return }
            reset(g, why)
        }
        runCatching { timed(PING_MS * 2, "проверка") { inner.ping(lastPath.ifEmpty { inner.root }) } }.onSuccess { lastOk = System.currentTimeMillis(); state = OK }.onFailure { state = if (isNetDead(it)) OFFLINE else OK }
    }
}

/**
 * Фон для постоянной связи: keepalive каждые ~12 с, Wi-Fi и wake lock пока открыт сетевой источник, реакция на смену сети и включение экрана.
 * Блокировки держатся, пока открыт хотя бы один сетевой источник (в панели или раскрытым в дереве), без временного лимита и вне зависимости от фона;
 * отпускаются, когда все сетевые источники закрыты или свёрнуты, и при выходе из программы. Никаких настроек: всегда включено.
 */
object NetKeep {
    private const val PERIOD_MS = 12_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var bgSince = 0L
    private var lastNet: Network? = null
    private var lastAddrs = ""

    fun active(): List<KeepFs> = (Fm.panes.filter { it.src == "smb" }.mapNotNull { it.smb as? KeepFs } + FmRootCfg.openFs()).distinct()

    /** Есть ли открытый сетевой источник (панель или раскрытый в дереве узел, в том числе облако): тогда Wi-Fi и процессор не отпускаются. */
    fun anyOpen(): Boolean = Fm.panes.any { it.src == "smb" && it.net != null } || Fm.panes.any { p -> p.rootOpen.any { it.startsWith("N|") } }

    fun start(app: Context) {
        if (started) return
        started = true
        val ctx = app.applicationContext
        scope.launch {
            while (true) {
                delay(PERIOD_MS)
                runCatching { tick(ctx) }
            }
        }
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { if (lastNet != null && lastNet != network) changed("сеть сменилась") ; lastNet = network }
                override fun onLost(network: Network) { if (lastNet == network) { lastNet = null; markStale("Wi-Fi потеряна") } }
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                    val a = lp.linkAddresses.joinToString(",") { it.address.hostAddress ?: "" }
                    if (lastAddrs.isNotEmpty() && a != lastAddrs) changed("изменился IP")
                    lastAddrs = a
                }
            })
        }
        runCatching {
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { if (i.action == Intent.ACTION_SCREEN_ON || i.action == Intent.ACTION_USER_PRESENT) probeAll("экран включён", false) }
            }, IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_USER_PRESENT) })
        }
    }

    private fun markStale(why: String) { active().forEach { it.stale = true; it.state = KeepFs.RECONNECT }; AppLog.i("ADBphon", "LAN: $why, соединения помечены устаревшими") }
    private fun changed(why: String) { markStale(why); probeAll(why) }

    /** Немедленная проверка и переподключение всех открытых источников. */
    fun probeAll(why: String, force: Boolean = true) { active().forEach { f -> scope.launch { runCatching { f.invalidateNow(why, force) } } } }

    fun onForeground() { bgSince = 0L; if (active().isNotEmpty()) probeAll("приложение снова на экране", false) }
    fun onBackground() { bgSince = System.currentTimeMillis() }

    private suspend fun tick(ctx: Context) {
        val list = active()
        if (list.isEmpty() && !anyOpen()) { release(); return }
        hold(ctx)
        list.forEach { f -> scope.launch { runCatching { f.keepalive() } } }
    }

    private fun hold(ctx: Context) {
        runCatching {
            if (wifiLock == null) {
                val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF, "adbphon:lan").apply { setReferenceCounted(false) }
            }
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
            if (wakeLock == null) {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "adbphon:lan").apply { setReferenceCounted(false) }
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire()       // без таймера: отпускается только когда сетевых источников не осталось
        }
    }

    /** Выход из программы: блокировки отпускаются сразу. */
    fun releaseAll() = release()

    private fun release() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
    }
}


/** Значок состояния сетевого источника без текста: зелёный — подключено, жёлтый — переподключение, красный — нет связи. Подсказка по долгому нажатию. */
@androidx.compose.runtime.Composable
fun NetDot(fs: KeepFs, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st = fs.state
    val color = when (st) { KeepFs.OK -> androidx.compose.ui.graphics.Color(0xFF2E9E5B); KeepFs.RECONNECT -> androidx.compose.ui.graphics.Color(0xFFE0A100); else -> androidx.compose.ui.graphics.Color(0xFFD32F2F) }
    val text = androidx.compose.ui.res.stringResource(when (st) { KeepFs.OK -> R.string.net_state_ok; KeepFs.RECONNECT -> R.string.net_state_reconnect; else -> R.string.net_state_off })
    androidx.compose.foundation.layout.Box(
        modifier.padding(horizontal = 4.dp).size(10.dp).background(color, androidx.compose.foundation.shape.CircleShape)
            .combinedClickable(onClick = {}, onLongClick = { android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show() })
            .semantics { contentDescription = text },
    )
}
