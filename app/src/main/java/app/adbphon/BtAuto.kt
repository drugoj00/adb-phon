package app.adbphon

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Короткий ADB-канал к ДРУГОМУ устройству (приставка, ТВ-бокс, другой телефон) независимо от основного подключения приложения. */
class RemoteShell(private val ctx: Context, val host: String, val port: Int) {
    private val conn = AdbConn(ctx.applicationContext)

    suspend fun open(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val ok = withTimeout(30_000) { runInterruptible { conn.connect(host, port) } }
            if (!ok) error("connect failed")
        }
    }

    suspend fun sh(cmd: String, timeoutMs: Long = 20_000): String = withContext(Dispatchers.IO) {
        val s = conn.openStream("shell:$cmd")
        val out = java.io.ByteArrayOutputStream()
        val t = Thread { runCatching { val i = s.openInputStream(); val b = ByteArray(8192); while (true) { val n = i.read(b); if (n < 0) break; synchronized(out) { out.write(b, 0, n) } } } }
        t.start(); t.join(timeoutMs)
        runCatching { s.close() }
        synchronized(out) { out.toString("UTF-8") }.trim()
    }

    fun close() { runCatching { conn.disconnect() } }
}

/**
 * Автоматическое сопряжение с Android-устройством по ADB: включает на нём Bluetooth, сопрягается со стороны телефона,
 * сама подтверждает запросы на обоих устройствах и подключает профиль HID. Работает только с устройствами, куда пользователь
 * сам выдал ADB-доступ. Для Windows, macOS и Linux Bluetooth удалённо не включить: сопряжение идёт со стороны телефона вручную.
 */
@SuppressLint("MissingPermission")
object BtAuto {
    var status by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    private var manualJob: Job? = null

    /**
     * Ручной выбор устройства в списке: останавливает автоподключение (оно больше не переключается на другие устройства),
     * ждёт готовности HID, подключается и показывает настоящий статус. Если компьютер не принял подключение, телефон остаётся
     * видимым и ждёт входящего: пользователь нажимает «Подключить» на компьютере.
     */
    /** Из окна игры: чистое отключение от текущего хоста (release-all, disconnect), затем подключение к выбранному, окно остаётся открытым. */
    fun switchHost(ctx: Context, r: BtRow) { BtHid.switching = true; manualConnect(ctx, r, switching = true) }

    fun manualConnect(ctx: Context, r: BtRow, switching: Boolean = false) {
        val app = ctx.applicationContext
        pauseReconnect = true; userStopped = false
        PadKeeper.setPaused(app, false)    // ручное подключение: после «Отключить» в шторке фоновая служба снова может работать
        job?.cancel(); busy = false
        manualJob?.cancel()
        manualJob = scope.launch {
            val dev = r.dev ?: run { BtHid.switching = false; return@launch }
            try {
                if (switching && BtHid.hostName != null) BtHid.cleanDisconnect("смена устройства", 1000)
                status = s(app, R.string.bta_reconnect, r.name)
                if (!BtPower.isOn(app)) { status = s(app, R.string.bta_phone_bt); return@launch }
                if (!BtHid.registered) { BtHid.useMode(app, BtHid.mode); BtHid.start(app) }
                if (!waitFor(10_000, 300) { BtHid.registered }) { status = s(app, R.string.bta_not_ready); return@launch }
                if (dev.bondState != BluetoothDevice.BOND_BONDED) { status = s(app, R.string.bta_not_bonded, r.name); return@launch }
                var accepted = false
                repeat(2) { attempt ->
                    if (BtHid.hostName != null) return@launch
                    accepted = BtHid.connectTo(dev)
                    if (!accepted) { status = s(app, R.string.bta_refused); delay(1500); return@repeat }
                    status = s(app, R.string.bta_reconnect, r.name)
                    if (waitFor(10_000, 300) { BtHid.hostName != null }) { status = null; return@launch }
                    if (attempt == 0) { runCatching { BtHid.disconnect() }; delay(800) }
                }
                // компьютер не открыл HID-канал: телефон остаётся зарегистрированным и ждёт, пока подключение начнёт сам компьютер
                status = s(app, R.string.bta_wait_incoming, r.name)
                if (waitFor(120_000, 500) { BtHid.hostName != null }) status = null
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { status = s(app, R.string.bta_error, e.message.orEmpty()) }
            finally { if (switching) BtHid.switching = false }
        }
    }

    /** Пользователь сам отключился: автоподключение молчит, пока он не зайдёт на раздел заново. */
    @Volatile var pauseReconnect = false
    /** Человек сам отключил и забыл устройство: фоновое подключение молчит, пока он не выберет устройство или не зайдёт на раздел. */
    @Volatile var userStopped = false
    val manualActive get() = manualJob?.isActive == true
    fun userDisconnect() { pauseReconnect = true; userStopped = true; BtHid.disconnect() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("bt_auto", Context.MODE_PRIVATE)
    private fun s(ctx: Context, id: Int, vararg a: Any): String = runCatching { ctx.getString(id, *a) }.getOrDefault("")
    private fun adapter(ctx: Context) = (ctx.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun autoLast(ctx: Context) = prefs(ctx).getBoolean("auto_last", true)
    fun setAutoLast(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("auto_last", v).apply() }

    /** Адреса устройств, сопряжённых автоматически по ADB: это Android-приставки и телефоны, им предупреждение не нужно. */
    fun isAndroidHost(ctx: Context, addr: String): Boolean = prefs(ctx).getStringSet("android_hosts", emptySet()).orEmpty().contains(addr.uppercase())
    private fun markAndroid(ctx: Context, addr: String) {
        val s = prefs(ctx).getStringSet("android_hosts", emptySet()).orEmpty().toMutableSet(); s.add(addr.uppercase())
        prefs(ctx).edit().putStringSet("android_hosts", s).apply()
    }
    private fun unmarkAndroid(ctx: Context, addr: String) {
        val s = prefs(ctx).getStringSet("android_hosts", emptySet()).orEmpty().toMutableSet(); s.remove(addr.uppercase())
        prefs(ctx).edit().putStringSet("android_hosts", s).apply()
    }

    private val macRx = Regex("([0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5})")

    private suspend fun waitFor(timeoutMs: Long, step: Long = 300, cond: suspend () -> Boolean): Boolean {
        var t = 0L
        while (t < timeoutMs) { if (cond()) return true; delay(step); t += step }
        return cond()
    }

    /** Автоматическое подтверждение запроса сопряжения на одной из сторон: ищет окно запроса и нажимает кнопку согласия. */
    private val okRx = Regex("(?i)^(ok|ок|pair|pair & connect|connect|accept|allow|yes|да|так|принять|разрешить|подключить|сопрягать|сопряжение|устан\\. сопряж\\.?|установить связь|створити пару|підключити|прийняти|дозволити|добавить|додати|add)$")
    private val noRx = Regex("(?i)(cancel|отмена|скасув|deny|запрет|відхил|отклон|forget|нет|ні|no)")
    private val nodeRx = Regex("<node [^>]*?text=\"([^\"]*)\"[^>]*?bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"")

    private suspend fun confirmOnce(sh: suspend (String) -> String): Boolean {
        val xml = runCatching { sh("uiautomator dump /data/local/tmp/btui.xml >/dev/null 2>&1; cat /data/local/tmp/btui.xml 2>/dev/null") }.getOrDefault("")
        if (xml.length < 200) return false
        val nodes = nodeRx.findAll(xml).toList()
        val texts = nodes.map { it.groupValues[1] }
        // окно запроса сопряжения: есть слово Bluetooth и кнопка согласия
        if (texts.none { it.contains("bluetooth", true) || it.contains("блютуз", true) }) return false
        val btn = nodes.firstOrNull { it.groupValues[1].isNotBlank() && okRx.matches(it.groupValues[1].trim()) && !noRx.containsMatchIn(it.groupValues[1]) } ?: return false
        val g = btn.groupValues
        val l = g[2].toInt(); val t = g[3].toInt(); val r = g[4].toInt(); val b = g[5].toInt()
        sh("input tap ${(l + r) / 2} ${(t + b) / 2}")
        return true
    }

    /** Нажимает на хосте элемент, текст которого содержит одну из подстрок (экран настроек Bluetooth приставки). true, если нашёл. */
    private suspend fun tapNode(sh: suspend (String) -> String, vararg needles: String): Boolean {
        val xml = runCatching { sh("uiautomator dump /data/local/tmp/btui.xml >/dev/null 2>&1; cat /data/local/tmp/btui.xml 2>/dev/null") }.getOrDefault("")
        if (xml.length < 200) return false
        val n = nodeRx.findAll(xml).firstOrNull { m -> m.groupValues[1].isNotBlank() && needles.any { m.groupValues[1].contains(it, true) } } ?: return false
        val g = n.groupValues
        sh("input tap ${(g[2].toInt() + g[4].toInt()) / 2} ${(g[3].toInt() + g[5].toInt()) / 2}")
        return true
    }

    private fun mac(text: String): String? = macRx.find(text)?.groupValues?.get(1)?.uppercase()

    /** Подключиться к устройству и пройти все шаги. [mode] «mouse» или «pad» определяет, как телефон представится. */
    fun connect(ctx: Context, host: String, port: Int, mode: String) {
        if (busy) return
        val app = ctx.applicationContext
        job = scope.launch {
            busy = true
            val rs = RemoteShell(app, host, port)
            try {
                status = s(app, R.string.bta_connecting, host)
                val o = rs.open()
                if (o.isFailure) { status = s(app, R.string.bta_no_adb, host); return@launch }
                // 1) Bluetooth телефона и регистрация HID
                BtPower.enter(app)
                BtHid.useMode(app, mode); BtHid.start(app)
                if (!waitFor(15_000) { BtPower.isOn(app) }) { status = s(app, R.string.bta_phone_bt); return@launch }
                waitFor(8_000) { BtHid.registered }
                // 2) Bluetooth на целевом устройстве
                val on = rs.sh("settings get global bluetooth_on").trim() == "1"
                if (!on) {
                    status = s(app, R.string.bta_remote_on)
                    prefs(app).edit().putBoolean("was_off_$host", true).apply()
                    rs.sh("svc bluetooth enable")
                    if (!waitFor(20_000, 1000) { rs.sh("settings get global bluetooth_on").trim() == "1" }) { status = s(app, R.string.bta_remote_fail); return@launch }
                    delay(2500)
                }
                // устройство должно быть видимым для сопряжения: запрос видимости и согласие на нём самом
                run {
                    status = s(app, R.string.bta_visible_there)
                    val act = "android.bluetooth.adapter.action.REQUEST_DISCOVERABLE"
                    val comps = rs.sh("cmd package query-activities --brief -a $act 2>/dev/null").lines().map { it.trim() }.filter { it.contains("/.") && !it.startsWith("priority") }
                    val comp = comps.firstOrNull { it.startsWith("com.android.tv.settings") } ?: comps.firstOrNull()
                    rs.sh("am start -a $act --ei android.bluetooth.adapter.extra.DISCOVERABLE_DURATION 120 " + (comp?.let { "-n $it" } ?: ""))
                    waitFor(6_000, 700) { confirmOnce { c -> rs.sh(c) } }
                    // системное окно выбора приложения («Выполнить с помощью») не должно висеть на экране хоста: закрываем само
                    repeat(2) { if (rs.sh("dumpsys activity activities | grep -m1 mResumedActivity").contains("ResolverActivity")) { rs.sh("input keyevent 4"); delay(500) } }
                }
                // экран «Добавить устройство» делает приставку видимой и сам ищет телефоны (без него телефон часто не находит хост)
                run {
                    rs.sh("am start -a android.settings.BLUETOOTH_SETTINGS")
                    delay(1500)
                    runCatching { tapNode({ c -> rs.sh(c) }, "Добавить устройство", "Add device", "Pair new device", "Додати пристрій") }
                    delay(1500)
                }
                val addr = mac(rs.sh("dumpsys bluetooth_manager | grep -m1 -E 'address:'")) ?: mac(rs.sh("settings get secure bluetooth_address"))
                if (addr == null) { status = s(app, R.string.bta_no_addr); return@launch }
                val ad = adapter(app) ?: return@launch
                val dev = ad.getRemoteDevice(addr)
                markAndroid(app, addr)
                // 3) сопряжение со стороны телефона, подтверждение на обоих; 4) подключение профиля HID
                val selfSh: suspend (String) -> String = { c ->
                    if (Adb.isConnected && Adb.currentHost.isEmpty()) Adb.shell(c) else { FmIo.app = app; if (FmIo.ensurePriv() == null) Adb.shell(c) else "" }
                }
                var done = false
                for (attempt in 1..2) {
                    if (dev.bondState != BluetoothDevice.BOND_BONDED) {
                        status = s(app, R.string.bta_pairing)
                        runCatching { ad.cancelDiscovery() }
                        AppLog.i("ADBphon", "bta createBond=" + runCatching { dev.createBond() }.getOrElse { it.toString() } + " bond=" + dev.bondState + " addr=" + addr)
                        var tick = 0
                        val ok = waitFor(45_000, 1500) {
                            runCatching { confirmOnce(selfSh) }
                            runCatching { confirmOnce { c -> rs.sh(c) } }
                            // сопряжение не началось само (телефон не находит хост): приставка нажимает на телефон в своём списке «Доступные устройства»
                            if (++tick == 3 && dev.bondState == BluetoothDevice.BOND_NONE) {
                                val me = runCatching { ad.name }.getOrNull().orEmpty()
                                if (me.isNotBlank()) runCatching { tapNode({ c -> rs.sh(c) }, me) }
                            }
                            AppLog.i("ADBphon", "bta bond state=" + dev.bondState)
                            dev.bondState == BluetoothDevice.BOND_BONDED
                        }
                        if (!ok) { status = s(app, R.string.bta_pair_fail); return@launch }
                    }
                    status = s(app, R.string.bta_hid)
                    for (i in 1..2) {
                        BtHid.pick(BtRow(dev, addr, runCatching { dev.name }.getOrNull() ?: addr))
                        // запрос подтверждения может прийти и при подключении профиля
                        if (waitFor(9_000, 1000) { runCatching { confirmOnce(selfSh) }; runCatching { confirmOnce { c -> rs.sh(c) } }; BtHid.hostName != null }) { done = true; break }
                    }
                    if (done) break
                    // сопряжение осталось только на телефоне (устройство его забыло): удаляем и сопрягаемся заново
                    status = s(app, R.string.bta_repair)
                    runCatching { dev.javaClass.getMethod("removeBond").invoke(dev) }
                    waitFor(8_000) { dev.bondState == BluetoothDevice.BOND_NONE }
                    delay(1000)
                }
                runCatching { rs.sh("input keyevent 4; input keyevent 4; input keyevent 3") }    // экран настроек Bluetooth на хосте не оставляем
                prefs(app).edit().putString("last_auto", "$host:$port|$addr").apply()
                status = if (done) s(app, R.string.bta_done, BtHid.hostName ?: addr) else s(app, R.string.bta_hid_fail)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { status = s(app, R.string.bta_error, e.message.orEmpty()) }
            finally { rs.close(); busy = false }
        }
    }

    /** Включить или выключить Bluetooth на другом Android-устройстве, не сопрягаясь. */
    fun remotePower(ctx: Context, host: String, port: Int, on: Boolean) {
        if (busy) return
        val app = ctx.applicationContext
        scope.launch {
            busy = true
            val rs = RemoteShell(app, host, port)
            try {
                status = s(app, R.string.bta_connecting, host)
                if (rs.open().isFailure) { status = s(app, R.string.bta_no_adb, host); return@launch }
                rs.sh(if (on) "svc bluetooth enable" else "svc bluetooth disable")
                val ok = waitFor(15_000, 1000) { (rs.sh("settings get global bluetooth_on").trim() == "1") == on }
                status = s(app, if (ok) (if (on) R.string.bta_remote_is_on else R.string.bta_remote_is_off) else R.string.bta_remote_fail)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { status = s(app, R.string.bta_error, e.message.orEmpty()) }
            finally { rs.close(); busy = false }
        }
    }

    /** «Отключить и забыть»: разрывает HID, удаляет сопряжение на телефоне и возвращает Bluetooth цели, если включали его мы. */
    fun forget(ctx: Context) {
        userStopped = true
        val app = ctx.applicationContext
        pauseReconnect = true
        val last = prefs(app).getString("last_auto", null)
        val addr = BtHid.hostAddress() ?: last?.substringAfter('|')
        scope.launch {
            runCatching { BtHid.disconnect() }
            delay(400)
            val dev = addr?.let { runCatching { adapter(app)?.getRemoteDevice(it) }.getOrNull() }
            if (dev != null) { runCatching { dev.javaClass.getMethod("removeBond").invoke(dev) }; unmarkAndroid(app, dev.address) }
            val tgt = last?.substringBefore('|')
            if (tgt != null && prefs(app).getBoolean("was_off_${tgt.substringBefore(':')}", false)) {
                val rs = RemoteShell(app, tgt.substringBefore(':'), tgt.substringAfter(':').toIntOrNull() ?: 5555)
                if (rs.open().isSuccess) { runCatching { rs.sh("svc bluetooth disable") }; prefs(app).edit().putBoolean("was_off_${tgt.substringBefore(':')}", false).apply() }
                rs.close()
            }
            status = s(app, R.string.bta_forgotten)
        }
    }

    /**
     * Автоподключение при входе на раздел: пробует последние сопряжённые устройства по очереди (самое свежее первым),
     * повторяет попытку при потере. Работает, пока экран открыт и хост не подключён.
     */
    suspend fun reconnectLoop(ctx: Context) {
        val app = ctx.applicationContext
        if (!autoLast(app)) return
        var round = 0
        while (round < 3) {
            if (BtHid.hostName != null || pauseReconnect) { if (!busy && status != null && BtHid.hostName != null) status = null; if (pauseReconnect && !busy) status = null; return }
            if (busy) { delay(1000); continue }   // идёт автоматическое сопряжение: не мешаем
            if (!waitFor(12_000, 400) { BtHid.registered && BtPower.isOn(app) }) { delay(2000); round++; continue }
            val ad = adapter(app) ?: return
            val rows = BtHid.sortLastFirst(app, runCatching { ad.bondedDevices.toList() }.getOrDefault(emptyList()).filter { BtHid.hidOk(app, it.address) }.map { BtRow(it, it.address, runCatching { it.name }.getOrNull() ?: it.address) }).take(1)   // только то, к чему HID уже подключался
            for (r in rows) {
                if (BtHid.hostName != null || busy || pauseReconnect) break
                status = s(app, R.string.bta_reconnect, r.name)
                r.dev?.let { BtHid.connectTo(it) }
                if (waitFor(8_000) { BtHid.hostName != null || pauseReconnect }) { status = null; return }
            }
            round++
            delay(3000)
        }
        status = null
    }
}
