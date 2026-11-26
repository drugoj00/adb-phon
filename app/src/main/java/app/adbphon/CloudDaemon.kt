package app.adbphon

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom

/**
 * 26.11.2026 (владелица: «в настройки облака добавь вкл в фоне»): движок облаков постоянной службой (rcd). Запускается один раз и помнит вход в облака,
 * поэтому папки читаются без нового запуска и нового входа. Слушает только 127.0.0.1, вход по случайному паролю (передаётся через окружение, не в командной
 * строке). Любой сбой службы не мешает работе: чтение идёт прежним способом (отдельный запуск движка на запрос).
 */
object CloudDaemon {
    private const val PREF = "cloud_bg"
    private val lock = Mutex()
    @Volatile private var proc: Process? = null
    private var port = 0
    private var auth = ""
    private var key = ""
    @Volatile private var failedAt = 0L

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = sp(ctx).getBoolean(PREF, false)
    fun setEnabled(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(PREF, v).apply()
        if (v) { failedAt = 0L; prewarm(ctx) } else stop()
    }

    /** Заранее запустить службу, чтобы первое открытие облака не ждало запуска движка. */
    fun prewarm(ctx: Context) {
        val app = ctx.applicationContext
        if (!enabled(app)) return
        Thread { runCatching { kotlinx.coroutines.runBlocking { lock.withLock { ensure(app) } } } }.start()
    }

    fun stop() {
        proc?.let { runCatching { it.destroyForcibly() } }
        proc = null
    }

    /** Службы, оставшиеся от прошлого запуска программы (процесс программы убили, а движок остался). */
    private fun killStale() {
        runCatching {
            File("/proc").listFiles()?.forEach { d ->
                val pid = d.name.toIntOrNull() ?: return@forEach
                val cmd = runCatching { File(d, "cmdline").readText().replace('\u0000', ' ') }.getOrNull() ?: return@forEach
                if (cmd.contains("libclouds.so") && cmd.contains(" rcd ")) runCatching { android.os.Process.killProcess(pid) }
            }
        }
    }

    /** Ключ настроек без токенов и сеансов: движок сам переписывает их в файле, из-за этого службу перезапускать не нужно. */
    private fun confKey(ctx: Context): String = runCatching {
        Rclone.conf(ctx).readLines().filterNot { l -> val t = l.trimStart(); t.startsWith("token") || t.startsWith("session_id") || t.startsWith("master_key") }.joinToString("\n").hashCode().toString()
    }.getOrDefault("")

    private suspend fun ensure(ctx: Context): Boolean {
        val k = confKey(ctx)
        if (proc?.isAlive == true && k == key) return true
        stop()
        if (System.currentTimeMillis() - failedAt < 60_000L) return false
        killStale()
        val p0 = runCatching { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort } }.getOrNull() ?: return false
        val rnd = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val pass = android.util.Base64.encodeToString(rnd, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
        val started = runCatching {
            Rclone.spawn(ctx, listOf("rcd", "--rc-addr", "127.0.0.1:$p0") + Rclone.common, mapOf("RCLONE_RC_USER" to "adbphon", "RCLONE_RC_PASS" to pass))
        }.getOrNull() ?: run { failedAt = System.currentTimeMillis(); return false }
        Thread { runCatching { started.inputStream.use { s -> val b = ByteArray(4096); while (s.read(b) >= 0) { } } } }.apply { isDaemon = true }.start()
        port = p0
        auth = "Basic " + android.util.Base64.encodeToString("adbphon:$pass".toByteArray(), android.util.Base64.NO_WRAP)
        for (i in 0 until 100) {
            if (!started.isAlive) break
            if (runCatching { post("rc/noop", JSONObject(), 2000).first == 200 }.getOrDefault(false)) {
                proc = started; key = k
                AppLog.i("ADBphon", "движок облаков запущен в фоне")
                return true
            }
            delay(100)
        }
        runCatching { started.destroyForcibly() }
        failedAt = System.currentTimeMillis()
        AppLog.i("ADBphon", "движок облаков в фоне не запустился, чтение обычным способом")
        return false
    }

    /** Забыть запомненные службой подключения и каталоги (после записи, удаления, переименования отдельным запуском движка). */
    fun invalidate() {
        if (proc?.isAlive != true) return
        runCatching { post("fscache/clear", JSONObject(), 4000) }
    }

    private fun post(method: String, body: JSONObject, timeoutMs: Int): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port/$method").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 3000; c.readTimeout = timeoutMs
            c.setRequestProperty("Authorization", auth); c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val s = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to s
        } finally { c.disconnect() }
    }

    /**
     * Список папки через службу. Возвращает результат в том же виде, что и `lsjson` (массив JSON в out, текст ошибки в err), или null:
     * служба выключена или не работает, читать обычным способом.
     */
    suspend fun list(ctx: Context, remote: String, path: String): Rclone.Result? = withContext(Dispatchers.IO) {
        if (!enabled(ctx)) return@withContext null
        val app = ctx.applicationContext
        if (!lock.withLock { ensure(app) }) return@withContext null
        val body = JSONObject().put("fs", "$remote:").put("remote", path.trim('/')).put("opt", JSONObject().put("noMimeType", true))
        val r = try { post("operations/list", body, 150_000) } catch (e: java.net.SocketTimeoutException) { throw NetTimeout("облако > 150 с") } catch (e: java.io.IOException) { return@withContext null }
        if (r.first == 200) Rclone.Result(0, runCatching { JSONObject(r.second).getJSONArray("list").toString() }.getOrDefault("[]"), "")
        else Rclone.Result(1, "", runCatching { JSONObject(r.second).getString("error") }.getOrDefault(r.second.take(500)))
    }
}
