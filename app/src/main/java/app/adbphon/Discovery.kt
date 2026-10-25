package app.adbphon

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

/** Устройство, найденное в сети: tcp = открытый порт 5555, wifi = беспроводная отладка (mDNS). */
data class Found(val host: String, val port: Int, val kind: String, val self: Boolean)

object Discovery {
    /** Адрес этого телефона в активной сети (IPv4), например 192.168.1.138. */
    fun localIp(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }?.hostAddress
    }

    /** Перебор подсети /24: у кого открыт порт 5555 (режим «ADB по сети»). */
    /** Все частные IPv4-адреса этого телефона на всех интерфейсах (Wi-Fi, точка доступа, USB-модем). */
    fun myAddresses(ctx: Context): Set<String> {
        val set = linkedSetOf<String>()
        localIp(ctx)?.let { set.add(it) }
        runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }.forEach { ni ->
                ni.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }
                    .forEach { set.add(it.hostAddress ?: return@forEach) }
            }
        }
        return set
    }

    suspend fun scanTcp(ctx: Context, onFound: (Found) -> Unit) {
        val mine = myAddresses(ctx)
        if (mine.isEmpty()) return
        val prefixes = mine.map { it.substringBeforeLast('.') + "." }.distinct()
        val gate = Semaphore(64)
        coroutineScope {
            prefixes.flatMap { prefix ->
                (1..254).map { i ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val ip = prefix + i
                            runCatching {
                                Socket().use { it.connect(InetSocketAddress(ip, 5555), 350) }
                                onFound(Found(ip, 5555, "tcp", ip in mine))
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /** Беспроводная отладка Android 11+: устройства сами объявляют себя по mDNS. */
    suspend fun scanMdns(ctx: Context, ms: Long, onFound: (Found) -> Unit) = withContext(Dispatchers.Main) {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val mine = myAddresses(ctx)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val h = i.host?.hostAddress ?: return
                        onFound(Found(h, i.port, "wifi", h in mine))
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        runCatching { nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }
        delay(ms)
        runCatching { nsd.stopServiceDiscovery(listener) }
    }
}
