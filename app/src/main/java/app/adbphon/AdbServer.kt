package app.adbphon

import android.content.Context
import android.util.Base64
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * «ADB-phon Server» (7.0, по просьбе пользователя 4PDA: «как в Shizuku»).
 * Небольшой процесс уровня shell (assets/server.jar, исходник helper/server/Server.java) запускается один раз через ADB и остаётся
 * работать после выключения отладки по Wi-Fi и отключения ADB, до перезагрузки телефона. Слушает только 127.0.0.1 и отвечает на
 * команды приложения (токен хранится в настройках приложения). Пока ADB подключён, всё идёт по ADB как раньше; без ADB команды
 * этого телефона идут через сервер.
 */
object AdbServer {
    const val PORT = 38247

    /** Сервер отвечает (проверено последним запросом). */
    @Volatile var up = false
    private lateinit var ctx: Context

    fun init(c: Context) { ctx = c.applicationContext }

    private fun sp() = ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE)

    /** Запускать сервер сам после подключения по ADB к этому телефону (по умолчанию да). */
    var auto: Boolean
        get() = ::ctx.isInitialized && sp().getBoolean("srv_auto", true)
        set(v) { if (::ctx.isInitialized) sp().edit().putBoolean("srv_auto", v).apply() }

    /** После запуска сервера выключать отладку по Wi-Fi (по умолчанию да: сервер продолжает работать без неё). */
    var offAfter: Boolean
        get() = ::ctx.isInitialized && sp().getBoolean("srv_off_after", true)
        set(v) { if (::ctx.isInitialized) sp().edit().putBoolean("srv_off_after", v).apply() }

    fun token(): String {
        var t = sp().getString("srv_token", null)
        if (t == null) {
            val b = ByteArray(16)
            java.security.SecureRandom().nextBytes(b)
            t = b.joinToString("") { "%02x".format(it) }
            sp().edit().putString("srv_token", t).apply()
        }
        return t
    }

    private fun open(timeoutMs: Int): Socket {
        val s = Socket()
        s.connect(InetSocketAddress("127.0.0.1", PORT), timeoutMs)
        return s
    }

    private fun header(mode: String, cmd: String): ByteArray {
        val b64 = Base64.encodeToString(cmd.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return (token() + "\n" + mode + "\n" + b64 + "\n").toByteArray(Charsets.UTF_8)
    }

    /** Проверка связи: сервер отвечает «pong». */
    fun ping(timeoutMs: Int = 800): Boolean {
        val ok = runCatching {
            open(timeoutMs).use { s ->
                s.soTimeout = timeoutMs
                s.getOutputStream().apply { write((token() + "\nping\n").toByteArray(Charsets.UTF_8)); flush() }
                BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).readLine()?.let { it.startsWith("pong") && it.contains("v=2") } == true
            }
        }.getOrDefault(false)
        up = ok
        return ok
    }

    /** Выполняет команду от имени shell и возвращает вывод. [input] подаётся на стандартный ввод команды (ровно [size] байт). */
    fun run(cmd: String, input: InputStream?, size: Long, timeoutMs: Long): String {
        val s = try { open(2000) } catch (e: Exception) { up = false; throw e }
        try {
            s.soTimeout = timeoutMs.coerceIn(1000L, Int.MAX_VALUE.toLong()).toInt()
            val o = s.getOutputStream()
            o.write(header(if (input != null) "exec" else "sh", cmd))
            if (input != null) {
                o.write((size.toString() + "\n").toByteArray(Charsets.UTF_8))
                var left = size
                val buf = ByteArray(65536)
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    o.write(buf, 0, n)
                    left -= n
                }
            }
            o.flush()
            return s.getInputStream().readBytes().toString(Charsets.UTF_8).trim()
        } catch (e: Exception) {
            up = false
            throw e
        } finally { runCatching { s.close() } }
    }

    /** Копирует вывод команды (бинарный, без stderr) в файл. */
    fun pullTo(cmd: String, dest: File) {
        val s = try { open(2000) } catch (e: Exception) { up = false; throw e }
        try {
            s.soTimeout = 0
            val o = s.getOutputStream()
            o.write(header("shb", cmd))
            o.flush()
            s.getInputStream().use { i -> dest.outputStream().use { f -> i.copyTo(f) } }
        } finally { runCatching { s.close() } }
    }

    /** Просит сервер завершиться. */
    fun stop() {
        runCatching {
            open(800).use { s ->
                s.soTimeout = 800
                s.getOutputStream().apply { write((token() + "\nstop\n").toByteArray(Charsets.UTF_8)); flush() }
                s.getInputStream().read()
            }
        }
        up = false
    }
}
