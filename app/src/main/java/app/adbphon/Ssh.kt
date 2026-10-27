package app.adbphon

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Запись списка файлов на удалённом компьютере. */
class SshEntry(val name: String, val isDir: Boolean, val size: Long, val mtime: Long)

/**
 * Подключение к компьютеру или серверу по SSH (Linux, macOS, Windows с SSH-сервером): команды и файлы (SFTP).
 * Ключ сервера запоминается при первом подключении; если потом он изменился — подключение отклоняется (защита от подмены).
 */
object Ssh {
    @Volatile private var session: Session? = null
    val isConnected: Boolean get() = session?.isConnected == true

    private const val PREFS = "adbphon_ssh"

    private fun hostId(host: String, port: Int) = if (port == 22) host else "[$host]:$port"

    /** Запоминает отпечаток ключа сервера при первом подключении, дальше сверяет. */
    private class Tofu(val ctx: Context) : HostKeyRepository {
        private fun prefs() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private fun fp(key: ByteArray): String =
            android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(key), android.util.Base64.NO_WRAP)

        override fun check(host: String, key: ByteArray): Int {
            val k = "hk_$host"
            val saved = prefs().getString(k, null)
            val now = fp(key)
            return when (saved) {
                null -> { prefs().edit().putString(k, now).apply(); HostKeyRepository.OK }
                now -> HostKeyRepository.OK
                else -> HostKeyRepository.CHANGED
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID(): String = "adbphon-tofu"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    /** Забыть запомненный ключ сервера (если он сменился законно, например после переустановки системы). */
    fun forgetHostKey(ctx: Context, host: String, port: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("hk_" + hostId(host, port)).remove("hk_$host").apply()
    }

    suspend fun connect(ctx: Context, host: String, port: Int, user: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            disconnectNow()
            val s = JSch().getSession(user, host, port)
            s.setPassword(password)
            s.setConfig("StrictHostKeyChecking", "yes")
            s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
            s.setHostKeyRepository(Tofu(ctx.applicationContext))
            s.setServerAliveInterval(20_000)
            s.connect(15_000)
            session = s
            AppLog.i("ADBphon", "ssh: подключено к ${hostId(host, port)}")
            Unit
        }.onFailure { AppLog.e("ADBphon", "ssh connect error", it) }
    }

    private fun disconnectNow() {
        runCatching { session?.disconnect() }
        session = null
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) { disconnectNow() }

    private fun requireSession(): Session = session?.takeIf { it.isConnected } ?: error("SSH не подключён")

    /** Выполнить одну команду, вернуть объединённый вывод (stdout + stderr), не более ~200 КБ. */
    suspend fun exec(cmd: String, timeoutMs: Long = 30_000): String = withContext(Dispatchers.IO) {
        val ch = requireSession().openChannel("exec") as ChannelExec
        val out = ByteArrayOutputStream()
        try {
            ch.setCommand(cmd)
            ch.setInputStream(null)
            ch.setErrStream(out)
            val ins = ch.inputStream
            ch.connect(10_000)
            val t0 = System.currentTimeMillis()
            val buf = ByteArray(8192)
            while (true) {
                while (ins.available() > 0) {
                    val n = ins.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (out.size() < 200_000) out.write(buf, 0, n)
                }
                if (ch.isClosed) {
                    if (ins.available() > 0) continue
                    break
                }
                if (System.currentTimeMillis() - t0 > timeoutMs) {
                    out.write("\n[время ожидания истекло]".toByteArray())
                    break
                }
                Thread.sleep(40)
            }
        } finally {
            runCatching { ch.disconnect() }
        }
        out.toString("UTF-8")
    }

    private fun <T> withSftp(block: (ChannelSftp) -> T): T {
        val ch = requireSession().openChannel("sftp") as ChannelSftp
        ch.connect(10_000)
        try {
            return block(ch)
        } finally {
            runCatching { ch.disconnect() }
        }
    }

    suspend fun home(): String = withContext(Dispatchers.IO) { withSftp { it.pwd() } }

    suspend fun list(path: String): Pair<String, List<SshEntry>> = withContext(Dispatchers.IO) {
        withSftp { ch ->
            val real = runCatching { ch.realpath(path) }.getOrDefault(path)
            val items = (ch.ls(real) as Iterable<*>).map { it as ChannelSftp.LsEntry }.filter { it.filename != "." && it.filename != ".." }
            val list = items.map { e ->
                var dir = e.attrs.isDir
                if (!dir && e.attrs.isLink) dir = runCatching { ch.stat(real.trimEnd('/') + "/" + e.filename).isDir }.getOrDefault(false)
                SshEntry(e.filename, dir, e.attrs.size, e.attrs.mTime.toLong() * 1000)
            }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
            real to list
        }
    }

    suspend fun download(remote: String, out: OutputStream) = withContext(Dispatchers.IO) { withSftp { it.get(remote, out) } }

    suspend fun upload(input: InputStream, remote: String) = withContext(Dispatchers.IO) { withSftp { it.put(input, remote) } }

    suspend fun delete(path: String, isDir: Boolean) = withContext(Dispatchers.IO) {
        withSftp { if (isDir) it.rmdir(path) else it.rm(path) }
    }
}
