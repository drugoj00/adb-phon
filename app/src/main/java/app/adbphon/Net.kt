package app.adbphon

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/** Сведения о локальной сети и поиск компьютеров с общими папками. */
object Lan {
    /** Адрес этого устройства в локальной сети и длина маски (обычно 24). */
    fun myAddress(): Pair<String, Int>? {
        val list = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback && !it.isVirtual }
        for (ni in list.sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }) {
            for (a in ni.interfaceAddresses) {
                val ip = a.address
                if (ip is Inet4Address && ip.isSiteLocalAddress) return ip.hostAddress!! to a.networkPrefixLength.toInt()
            }
        }
        return null
    }

    /** Ищет в сети устройства с открытым портом общих папок SMB (445). Возвращает их адреса. */
    suspend fun scanSmb(): List<String> = withContext(Dispatchers.IO) {
        val me = myAddress() ?: return@withContext emptyList()
        val parts = me.first.split(".")
        val base = parts.take(3).joinToString(".")
        coroutineScope {
            (1..254).map { n ->
                async {
                    val ip = "$base.$n"
                    if (ip == me.first) null
                    else runCatching { Socket().use { s -> s.connect(InetSocketAddress(ip, 445), 450); ip } }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
    }
}

/**
 * Приём файлов по Wi‑Fi: пока открыто окно, устройство показывает страницу по адресу вида http://192.168.1.50:8765.
 * Открой её в браузере телефона или компьютера в той же сети и выбери файлы: они появятся здесь.
 */
object UploadServer {
    const val PORT = 8765
    @Volatile private var server: ServerSocket? = null
    @Volatile var dir: File? = null

    private val page = """<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Все приложения: приём файлов</title>
<body style="font-family:sans-serif;max-width:640px;margin:24px auto;padding:0 16px">
<h2>Отправить файлы на устройство</h2>
<p>Выбери APK-файлы или архивы с приложениями (ZIP, 7z, RAR, TAR).</p>
<input id="f" type="file" multiple style="font-size:18px">
<button onclick="go()" style="font-size:18px;padding:8px 16px">Отправить</button>
<pre id="log" style="font-size:16px"></pre>
<script>
async function go(){
  const files=document.getElementById('f').files, log=document.getElementById('log');
  if(!files.length){log.textContent='Сначала выбери файлы';return}
  for(const f of files){
    log.textContent+='Отправляю '+f.name+'...\n';
    try{
      const r=await fetch('/up?name='+encodeURIComponent(f.name),{method:'POST',body:f});
      log.textContent+=(r.ok?'Готово: ':'Ошибка: ')+f.name+'\n';
    }catch(e){log.textContent+='Ошибка связи: '+f.name+'\n'}
  }
}
</script></body></html>"""

    fun start(ctx: Context) {
        if (server != null) return
        dir = File(ctx.cacheDir, "received").apply { mkdirs() }
        val ss = ServerSocket(PORT)
        server = ss
        Thread {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                Thread { runCatching { handle(c) } }.start()
            }
        }.start()
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    fun received(): List<File> = dir?.listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }.orEmpty()

    private fun handle(c: Socket) = c.use { s ->
        val input = s.getInputStream().buffered()
        fun readLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }
        val first = readLine() ?: return@use
        val parts = first.split(" ")
        var len = 0L
        while (true) {
            val h = readLine() ?: break
            if (h.isEmpty()) break
            if (h.lowercase().startsWith("content-length:")) len = h.substringAfter(':').trim().toLongOrNull() ?: 0L
        }
        val out = s.getOutputStream()
        fun reply(code: String, type: String, body: ByteArray) {
            out.write("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
            out.flush()
        }
        val path = parts.getOrNull(1).orEmpty()
        if (parts[0] == "POST" && path.startsWith("/up")) {
            val name = java.net.URLDecoder.decode(path.substringAfter("name=", "file.bin"), "UTF-8").substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^\\w.\\- ]"), "_").ifBlank { "file.bin" }
            val f = File(dir ?: return@use, name)
            f.outputStream().use { o ->
                var left = len
                val buf = ByteArray(64 * 1024)
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    o.write(buf, 0, n)
                    left -= n
                }
            }
            reply("200 OK", "text/plain; charset=utf-8", "ok".toByteArray())
        } else {
            reply("200 OK", "text/html; charset=utf-8", page.toByteArray())
        }
    }
}
