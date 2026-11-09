package app.adbphon

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * «Wi-Fi проводник» на корневом экране «Файлов»: свой сервер (включается галочкой), сохранённые устройства и поиск новых в этой сети.
 * Устройство — это WebDAV-подключение (Wi-Fi сервер другого ADB-phon), помеченное в поле smb значением «wifi»; дальше оно раскрывается в дереве как папка LAN.
 */
object WifiExplorer {
    const val TAG = "wifi"
    const val PORT = 8080

    /** Найденные поиском, ещё не сохранённые устройства. */
    val found = mutableStateListOf<NetConn>()
    /** Адрес найденного устройства (http://ip:порт/) → для входа нужен пароль-ключ. */
    val needKey = mutableStateMapOf<String, Boolean>()
    /** Чем соединяться: auto (общая сеть и Wi-Fi Direct), lan (только общая сеть), direct (только Wi-Fi Direct). Выбор: долгий тап по «Wi-Fi проводник». */
    var mode by mutableStateOf("auto")
    private var modeLoaded = false
    fun load(ctx: Context) { if (!modeLoaded) { modeLoaded = true; mode = ctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE).getString("wifi_mode", "auto") ?: "auto" } }
    fun setMode(ctx: Context, m: String) { modeLoaded = true; mode = m; ctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE).edit().putString("wifi_mode", m).apply(); ver++ }
    var scanning by mutableStateOf(false)
    var progress by mutableStateOf(0)
    var ver by mutableStateOf(0)
    private var job: Job? = null

    fun isWifi(c: NetConn) = c.kind == "dav" && c.smb == TAG
    /** «ip:порт» для подписи. */
    fun hostText(c: NetConn) = c.host.substringAfter("://").trimEnd('/')

    /** Включить или выключить свой сервер (папка «/sdcard»). Ссылка и ключ показываются в строке «Сервер». */
    fun toggleServer(ctx: Context) {
        if (WifiShare.running) { WifiShare.stop(); WifiHotspot.stop(); WifiDirect.stop(); Fm.say(ctx.getString(R.string.wf_srv_off)) }
        else {
            val noPerm = Build.VERSION.SDK_INT >= 29 && !WifiHotspot.hasPerm(ctx)
            if (noPerm) PermFlow.findActivity(ctx)?.let { WifiHotspot.askPerm(it) }     // разрешение для Wi-Fi Direct: окно системы
            WifiShare.start(ctx, "/sdcard")
            if (WifiShare.running) {
                // группа Wi-Fi Direct поднимается сама: устройства рядом найдут сервер и без общего роутера
                if (!noPerm && mode != "lan") WifiDirect.startHost(ctx, WifiShare.port, WifiShare.key, (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL).trim())
                Fm.say(ctx.getString(R.string.wf_srv_on, WifiShare.url))
            } else if (!noPerm && Build.VERSION.SDK_INT >= 29) {
                // нет общей сети: сервер всё равно включаем, адрес даст группа Wi-Fi Direct
                Fm.say(WifiShare.error.ifBlank { ctx.getString(R.string.ws_no_net) })
            } else Fm.say(WifiShare.error.ifBlank { ctx.getString(R.string.ws_no_net) })
        }
        ver++
    }

    private fun myAddresses(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.name.startsWith("ppp") && !it.name.startsWith("rmnet") }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().filter { it.isSiteLocalAddress }.mapNotNull { it.hostAddress } }
    }.getOrDefault(emptyList())

    /** Проверка одного адреса: отвечает ли там Wi-Fi сервер ADB-phon (заголовок X-ADBphon). Возвращает (имя, нужен ли пароль). */
    private fun probe(ip: String, port: Int, connectMs: Int): Pair<String, Boolean>? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), connectMs)
            s.soTimeout = 1500
            s.getOutputStream().apply { write("OPTIONS / HTTP/1.0\r\nHost: $ip\r\n\r\n".toByteArray()); flush() }
            val head = ByteArray(4096)
            var n = 0
            while (n < head.size) { val r = s.getInputStream().read(head, n, head.size - n); if (r <= 0) break; n += r; if (String(head, 0, n).contains("\r\n\r\n")) break }
            val line = String(head, 0, n).lines().firstOrNull { it.startsWith("X-ADBphon:", true) } ?: return@runCatching null
            val v = line.substringAfter(':').trim()
            URLDecoder.decode(v.substringBeforeLast('|'), "UTF-8") to (v.substringAfterLast('|') != "1")
        }
    }.getOrNull()

    fun scan(ctx: Context) {
        if (scanning) return
        found.clear(); needKey.clear(); scanning = true; progress = 0; ver++
        val app = ctx.applicationContext
        job = Fm.scope.launch {
            try {
                val mine = myAddresses()
                val saved = NetStore.list(app).filter { isWifi(it) }.map { hostText(it) }.toSet()
                val targets = mine.map { it.substringBeforeLast('.') }.distinct().flatMap { p -> (1..254).map { "$p.$it" } }.filter { it !in mine }
                    .flatMap { ip -> WifiShare.PORTS.map { ip to it } }.let { if (mode == "direct") emptyList() else it }
                val total = maxOf(1, targets.size)
                val done = AtomicInteger(0)
                val sem = Semaphore(48)
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        // Wi-Fi Direct: ищем группу соседнего устройства с ADB-phon и входим в неё сами
                        if (Build.VERSION.SDK_INT >= 29 && mode != "lan") {
                            if (!WifiHotspot.hasPerm(app)) PermFlow.findActivity(ctx)?.let { act -> withContext(Dispatchers.Main) { WifiHotspot.askPerm(act) } }
                            else launch {
                                WifiDirect.error = ""
                                val j = runCatching { WifiDirect.join(app) }.getOrNull()
                                if (j != null) {
                                    val url = "http://${j.ip}:${j.port}/"
                                    withContext(Dispatchers.Main) {
                                        if (found.none { it.host == url } && "${j.ip}:${j.port}" !in saved) {
                                            found.add(NetConn("dav", url, 0, "adb", j.key, "", TAG, j.name)); needKey[url] = false; ver++
                                        }
                                    }
                                } else if (WifiDirect.error.isNotBlank()) withContext(Dispatchers.Main) { Fm.say(WifiDirect.error) }
                            }
                        }
                        targets.map { (ip, port) ->
                            async {
                                sem.withPermit {
                                    val r = probe(ip, port, 350)
                                    if (r != null && "$ip:$port" !in saved) {
                                        val url = "http://$ip:$port/"
                                        withContext(Dispatchers.Main) {
                                            if (found.none { it.host == url }) { found.add(NetConn("dav", url, 0, "", "", "", TAG, r.first)); needKey[url] = r.second; ver++ }
                                        }
                                    }
                                    progress = done.incrementAndGet() * 100 / total
                                }
                            }
                        }.awaitAll()
                    }
                }
            } finally { scanning = false; progress = 100; ver++ }
        }
    }

    fun cancel() { job?.cancel(); scanning = false; ver++ }

    /** Сохранить найденное или введённое устройство постоянно. */
    fun save(ctx: Context, old: NetConn?, c: NetConn) {
        if (old != null && NetStore.list(ctx).any { it.kind == old.kind && it.host == old.host && it.user == old.user }) NetStore.update(ctx, old, c) else NetStore.save(ctx, c)
        found.removeAll { it.host == c.host }
        FmRootCfg.kids.remove("N|dav|" + c.host + "|" + c.user + "|")
        FmRootCfg.ver++; ver++
    }

    /** «192.168.1.5» → http://192.168.1.5:8080/; «ip:порт» и полные ссылки оставляются. */
    fun normalize(input: String): String {
        var s = input.trim()
        if (s.isEmpty()) return s
        if (!s.contains("://")) s = "http://$s"
        val u = runCatching { java.net.URI(s) }.getOrNull() ?: return s
        val hostPart = u.host ?: return s
        val port = if (u.port > 0) u.port else PORT
        val path = (u.rawPath ?: "").ifEmpty { "/" }.let { if (it.endsWith("/")) it else "$it/" }
        return "${u.scheme}://$hostPart:$port$path"
    }
}

/** «Добавить устройство»: метка, адрес и пароль; «Тест» проверяет связь, «Сохранить» запоминает устройство постоянно. */
@Composable
fun WifiDeviceDialog(edit: NetConn?, preHost: String, preName: String, close: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(edit?.name ?: preName) }
    var host by remember { mutableStateOf(edit?.let { WifiExplorer.hostText(it) } ?: preHost) }
    var pass by remember { mutableStateOf(edit?.pass.orEmpty()) }
    var test by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    fun build(): NetConn { val url = WifiExplorer.normalize(host); return NetConn("dav", url, 0, if (pass.isBlank()) "" else "adb", pass, "", WifiExplorer.TAG, name.trim()) }
    LAlertDialog(
        onDismissRequest = close, ime = true,
        title = { Text(stringResource(R.string.wf_add), fontSize = 18.sp) },
        text = {
            Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CField(stringResource(R.string.wf_label), name, { name = it })
                CField(stringResource(R.string.wf_server), host, { host = it; test = null }, type = KeyboardType.Uri)
                CField(stringResource(R.string.wf_pass), pass, { pass = it; test = null }, password = true)
                if (busy) Text(stringResource(R.string.wf_testing), fontSize = 13.sp)
                test?.let { Text(it, fontSize = 13.sp, color = if (testOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton({
                        if (host.isBlank()) return@TextButton
                        busy = true; test = null
                        val c = build()
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { DavFs(c.host, c.user, c.pass).list("/").size }.map { Result.success(it) }.getOrElse { Result.failure(it) } }
                            busy = false
                            testOk = r.isSuccess
                            test = r.fold({ ctx.getString(R.string.wf_test_ok) }, { FmIo.nice(it) })
                        }
                    }, enabled = !busy, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.wf_test), maxLines = 1, fontSize = 14.sp) }
                    TextButton(close, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.btn_cancel), maxLines = 1, fontSize = 14.sp) }
                    TextButton({
                        if (host.isBlank()) return@TextButton
                        val c = build().let { if (it.name.isBlank()) it.copy(name = WifiExplorer.hostText(it)) else it }
                        WifiExplorer.save(ctx, edit, c); close()
                    }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.wf_save), maxLines = 1, fontSize = 14.sp) }
                }
            }
        },
    )
}
