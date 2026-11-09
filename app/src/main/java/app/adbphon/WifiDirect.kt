package app.adbphon

import android.annotation.SuppressLint
import android.content.Context
import android.net.MacAddress
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.security.SecureRandom

/**
 * Wi-Fi Direct: связь устройств напрямую, без роутера. Телефон или приставка с включённым Wi-Fi сервером создаёт группу Wi-Fi Direct
 * (адрес 192.168.49.1, адреса клиентам раздаёт сама система) и объявляет в ней службу с именем сети, паролем, адресом, портом и ключом сервера.
 * Другое устройство с ADB-phon при поиске («Wi-Fi проводник → Добавить устройство») само находит эту службу, входит в группу и добавляет устройство в список.
 * Нужен Android 10 и новее, разрешение «Устройства Wi-Fi рядом» (до Android 13 геолокация).
 */
@SuppressLint("MissingPermission")
object WifiDirect {
    var active by mutableStateOf(false)
    var ssid by mutableStateOf("")
    var pass by mutableStateOf("")
    var ip by mutableStateOf("")
    var error by mutableStateOf("")

    private var mgr: WifiP2pManager? = null
    private var ch: WifiP2pManager.Channel? = null
    private val rnd = SecureRandom()

    class Joined(val name: String, val ip: String, val port: Int, val key: String)

    private fun supported() = Build.VERSION.SDK_INT >= 29

    private fun ensure(ctx: Context): Boolean {
        if (ch != null && mgr != null) return true
        val m = ctx.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: return false
        mgr = m
        ch = m.initialize(ctx.applicationContext, Looper.getMainLooper()) { ch = null }
        return ch != null
    }

    private fun rndStr(n: Int, abc: String) = (1..n).map { abc[rnd.nextInt(abc.length)] }.joinToString("")

    private fun listener(onOk: () -> Unit = {}, onFail: (Int) -> Unit = {}) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = onOk()
        override fun onFailure(reason: Int) = onFail(reason)
    }

    /** Создать группу и объявить в ней службу. [onDone]: true, если группа создана. */
    fun startHost(ctx: Context, port: Int, key: String, deviceName: String, onDone: (Boolean) -> Unit = {}) {
        error = ""
        if (!supported()) { onDone(false); return }
        if (!WifiHotspot.hasPerm(ctx)) { error = ctx.getString(R.string.ws_hot_perm); onDone(false); return }
        if (!ensure(ctx)) { error = ctx.getString(R.string.wd_none); onDone(false); return }
        val m = mgr!!; val c = ch!!
        m.removeGroup(c, null)
        // имя группы постоянное для устройства: Android спрашивает «разрешить отладку по Wi-Fi в этой сети» один раз (с галочкой «всегда»), а не при каждом включении
        val name = "DIRECT-ab-ADBphon-" + (android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "0000").takeLast(4).lowercase()
        val pw = rndStr(12, "abcdefghjkmnpqrstuvwxyz23456789")
        val cfg = WifiP2pConfig.Builder().setNetworkName(name).setPassphrase(pw).enablePersistentMode(false)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ).build()
        // после removeGroup система освобождается не сразу: пауза, затем создание
        Thread {
            Thread.sleep(700)
            android.os.Handler(Looper.getMainLooper()).post {
                m.createGroup(c, cfg, listener({
                    m.requestGroupInfo(c) { g ->
                        ssid = g?.networkName ?: name
                        pass = g?.passphrase ?: pw
                        ip = "192.168.49.1"
                        active = true
                        publish(m, c, deviceName, port, key)
                        onDone(true)
                    }
                }, { r -> error = ctx.getString(R.string.wd_fail, r); active = false; onDone(false) }))
            }
        }.start()
    }

    private fun publish(m: WifiP2pManager, c: WifiP2pManager.Channel, deviceName: String, port: Int, key: String) {
        val txt = mapOf("n" to deviceName, "ssid" to ssid, "p" to pass, "ip" to ip, "port" to port.toString(), "k" to key)
        val info = WifiP2pDnsSdServiceInfo.newInstance("ADBphon" + rndStr(3, "0123456789"), "_adbphon._tcp", txt)
        m.clearLocalServices(c, listener({ m.addLocalService(c, info, null) }, { m.addLocalService(c, info, null) }))
    }

    fun stop() {
        val m = mgr; val c = ch
        if (m != null && c != null) { runCatching { m.clearLocalServices(c, null) }; runCatching { m.removeGroup(c, null) } }
        active = false; ssid = ""; pass = ""; ip = ""
    }

    /**
     * Найти службу ADB-phon в соседней группе Wi-Fi Direct, войти в группу и вернуть данные сервера. null: не нашли за [ms] мс.
     * Служба объявляет имя группы и пароль. Группа для остальных устройств обычная сеть Wi-Fi («DIRECT-…» с паролем), адреса раздаёт сама группа:
     * вход идёт командой `cmd wifi connect-network` через связь ADB этого устройства (или root), подтверждений на экране нет.
     * Если связи ADB нет, остаётся подсказка с именем сети и паролем.
     */
    suspend fun join(ctx: Context, ms: Long = 60_000): Joined? {
        if (!supported() || active || !WifiHotspot.hasPerm(ctx) || !ensure(ctx)) return null
        val m = mgr!!; val c = ch!!
        var found: Joined? = null
        var ssidFound = ""; var passFound = ""
        m.setDnsSdResponseListeners(c, { _, _, _ -> }, { _, rec, dev ->
            val s = rec["ssid"]; val p = rec["p"]
            if (s != null && p != null && found == null) {
                found = Joined(rec["n"].orEmpty().ifBlank { dev.deviceName ?: "Wi-Fi Direct" }, rec["ip"].orEmpty().ifBlank { "192.168.49.1" }, rec["port"]?.toIntOrNull() ?: 8080, rec["k"].orEmpty())
                ssidFound = s; passFound = p
            }
        })
        m.clearServiceRequests(c, null)
        m.addServiceRequest(c, WifiP2pDnsSdServiceRequest.newInstance(), null)
        var t = 0L
        var asked = 0L
        var connecting = false
        try {
            while (t < ms) {
                if (!connecting && asked <= 0L) { m.discoverPeers(c, null); m.discoverServices(c, null); asked = 6000L }
                delay(500); t += 500; asked -= 500
                val f = found ?: continue
                if (!connecting) {
                    connecting = true
                    runCatching { m.stopPeerDiscovery(c, null) }
                    val cmd = "cmd wifi connect-network '" + ssidFound.replace("'", "") + "' wpa2 '" + passFound.replace("'", "") + "'"
                    if (Adb.isConnected) runCatching { Adb.shell(cmd) } else error = ctx.getString(R.string.wd_manual, ssidFound, passFound)
                }
                if (reachable(f.ip, f.port)) return f.let { Joined(it.name, it.ip, it.port, it.key) }
            }
        } finally {
            runCatching { m.stopPeerDiscovery(c, null) }
            runCatching { m.clearServiceRequests(c, null) }
        }
        return null
    }

    private fun reachable(ip: String, port: Int): Boolean = runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(ip, port), 600); true } }.getOrDefault(false)

    /** Выйти из группы Wi-Fi Direct (забыть сеть «DIRECT-ab-ADBphon-…»): телефон сам вернётся в домашнюю сеть. */
    suspend fun leave(): Boolean {
        if (!Adb.isConnected) return false
        val out = runCatching { Adb.shell("cmd wifi list-networks") }.getOrDefault("")
        val ids = out.lines().filter { it.contains("DIRECT-ab-ADBphon") }.mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull() }.distinct()
        ids.forEach { runCatching { Adb.shell("cmd wifi forget-network $it") } }
        return ids.isNotEmpty()
    }
}
