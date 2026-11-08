package app.adbphon

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * ПРОТОТИП «играть на этом телефоне»: ввод окна геймпада идёт в ВИРТУАЛЬНЫЙ КОНТРОЛЛЕР этого же телефона (Xbox 360, vid 045e pid 028e).
 * Виртуальное устройство создаёт `uinput -` в ОТДЕЛЬНОМ процессе оболочки (HELPER, запускается через ADB один раз и не зависит от процесса приложения):
 * он слушает 127.0.0.1:PORT и отдаёт принятые строки JSON в uinput. Приложение подключается к нему обычным сокетом; убийство процесса приложения контроллер
 * не убирает, после перезапуска оверлей переподключается к живому помощнику без ADB. ADB нужен только чтобы запустить помощника.
 * Системный виртуальный контроллер создаёт лишь привилегированный код (оболочка ADB через uinput или root); без этого остаётся только эмуляция касаний (Accessibility).
 */
object VirtualPad {
    const val PORT = 38555
    /** Режим включён: отчёты Pad.loop идут сюда, а не в BtHid. */
    var on by mutableStateOf(false)
    @Volatile private var sock: Socket? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "vpad-io").apply { isDaemon = true } }
    private var last = IntArray(8) { Int.MIN_VALUE }
    private var lastBtn = 0

    private val BTN: Array<Pair<Int, Int>> = arrayOf(Pad.A to 304, Pad.B to 305, Pad.X to 307, Pad.Y to 308, Pad.L1 to 310, Pad.R1 to 311, Pad.SELECT to 314, Pad.START to 315, Pad.HOME to 316, Pad.L3 to 317, Pad.R3 to 318, Pad.OK to 304)
    private const val REG = """{"id":1,"command":"register","name":"Xbox 360 Controller","vid":1118,"pid":654,"bus":"usb","configuration":[{"type":100,"data":[1,3]},{"type":101,"data":[304,305,307,308,310,311,314,315,316,317,318]},{"type":103,"data":[0,1,2,3,4,5,16,17]}],"abs_info":[{"code":0,"info":{"value":0,"minimum":-32768,"maximum":32767,"fuzz":16,"flat":128}},{"code":1,"info":{"value":0,"minimum":-32768,"maximum":32767,"fuzz":16,"flat":128}},{"code":2,"info":{"value":0,"minimum":0,"maximum":255,"fuzz":0,"flat":0}},{"code":3,"info":{"value":0,"minimum":-32768,"maximum":32767,"fuzz":16,"flat":128}},{"code":4,"info":{"value":0,"minimum":-32768,"maximum":32767,"fuzz":16,"flat":128}},{"code":5,"info":{"value":0,"minimum":0,"maximum":255,"fuzz":0,"flat":0}},{"code":16,"info":{"value":0,"minimum":-1,"maximum":1,"fuzz":0,"flat":0}},{"code":17,"info":{"value":0,"minimum":-1,"maximum":1,"fuzz":0,"flat":0}}]}"""
    /** Скрипт помощника: uinput как сопроцесс, регистрация контроллера, слушатель сокета (каждое подключение дописывает строки в uinput). */
    private val HELPER = "#!/system/bin/sh\nPORT=\${1:-$PORT}\necho \$\$ > /data/local/tmp/vpad.pid\nuinput - |&\nexec 4>&p\ncat /data/local/tmp/vpad_reg.json >&4\nnc -L -p \$PORT -s 127.0.0.1 sh -c 'cat >&4' 4>&4\n"

    private fun selfAdb() = Adb.isConnected && Adb.currentHost.isEmpty() && !Adb.isRoot
    /** Команды оболочки к ЭТОМУ телефону можно выполнить: ADB к самому себе или root. */
    private fun canShell() = Adb.isConnected && Adb.currentHost.isEmpty()

    /** Версия помощника на /dev/uhid (Android 8-11, где нет утилиты uinput): сверяется с /data/local/tmp/vpad.ver при каждом запуске. */
    private const val UHID_VER = 2
    private const val UHID_ASSET = "vpad_uhid.jar"
    private const val UHID_JAR = "/data/local/tmp/vpad_uhid.jar"
    @Volatile var mode = ""        // "uinput" или "uhid": чем поднят помощник (у uhid-помощника узнаётся по приветствию, даже без ADB)
    @Volatile private var helperVer = 0

    /** Помощник уже слушает порт (жив после смерти приложения). */
    fun helperAlive(): Boolean = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 300); true } }.getOrDefault(false)

    /** Можно ли начать: помощник жив или есть ADB к этому телефону (или root). */
    fun available(): Boolean = helperAlive() || canShell()

    private fun sha256(b: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Есть ли на телефоне утилита uinput (Android 12 и новее); иначе виртуальный контроллер делает помощник на /dev/uhid. */
    private suspend fun hasUinput(): Boolean = Adb.shell("[ -x /system/bin/uinput ] && echo YES || echo NO", 8_000).contains("YES")

    /** Версия запущенного помощника (файл рядом с pid), "" если файла нет. */
    private suspend fun runningVer(): String = Adb.shell("cat /data/local/tmp/vpad.ver 2>/dev/null", 6_000).trim()

    private suspend fun killHelper() {
        Adb.shell("p=\$(cat /data/local/tmp/vpad.pid 2>/dev/null); [ -n \"\$p\" ] && { pkill -P \$p; kill \$p; }; rm -f /data/local/tmp/vpad.pid /data/local/tmp/vpad.ver", 8_000)
        var t = 0
        while (t < 30 && helperAlive()) { kotlinx.coroutines.delay(100); t++ }
    }

    /**
     * Помощник на /dev/uhid: dex-пакет из assets кладётся в /data/local/tmp, проверяется контрольная сумма (SHA-256), запускается app_process
     * в отдельном процессе (переживает приложение). Версия пишется в vpad.ver; помощник старой версии перезапускается.
     */
    private suspend fun startUhid(ctx: Context): Boolean {
        val bytes = ctx.assets.open(UHID_ASSET).use { it.readBytes() }
        val want = sha256(bytes)
        val have = Adb.shell("sha256sum $UHID_JAR 2>/dev/null", 8_000).trim().substringBefore(' ')
        if (have != want) {
            Adb.shell("rm -f $UHID_JAR; echo ${android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)} | base64 -d > $UHID_JAR && chmod 444 $UHID_JAR", 15_000)
            val after = Adb.shell("sha256sum $UHID_JAR 2>/dev/null", 8_000).trim().substringBefore(' ')
            if (after != want) { AppLog.e("ADBphon", "vpad uhid: контрольная сумма помощника не совпала ($after)"); return false }
        }
        Adb.shell("(setsid nohup sh -c 'echo \$\$ > /data/local/tmp/vpad.pid; echo uhid$UHID_VER > /data/local/tmp/vpad.ver; exec app_process -Djava.class.path=$UHID_JAR /system/bin VpadUhid' >/data/local/tmp/vpad_helper.log 2>&1 &); sleep 2", 15_000)
        var t = 0
        while (t < 40 && !helperAlive()) { kotlinx.coroutines.delay(100); t++ }
        return helperAlive()
    }

    private fun b64(s: String) = android.util.Base64.encodeToString(s.toByteArray(), android.util.Base64.NO_WRAP)

    /** Подключиться к помощнику; если его нет, запустить через ADB (при необходимости подключив ADB к себе сам по беспроводной отладке). */
    suspend fun start(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        if (sock?.isConnected == true && sock?.isClosed == false) return@withContext true
        if (helperAlive() && canShell()) {
            // помощник на uhid прежней версии заменяется (проверка версии при каждом запуске: по приветствию помощника и по файлу vpad.ver)
            val v = runCatching { runningVer() }.getOrDefault("")
            if (v.startsWith("uhid") && v != "uhid$UHID_VER") { AppLog.note("vpad: версия помощника $v, нужна uhid$UHID_VER, перезапуск"); runCatching { killHelper() } }
        }
        if (!helperAlive()) {
            if (!canShell()) runCatching { Adb.connectWireless(ctx.applicationContext, null) }        // автоподключение к самому телефону (беспроводная отладка, Android 11+)
            if (!canShell()) runCatching { Adb.connectWireless(ctx.applicationContext, 5555) }        // Android 8-10: `adb tcpip 5555` с ПК один раз
            if (!canShell()) return@withContext false
            if (!hasUinput()) {
                mode = "uhid"
                AppLog.note("vpad: утилиты uinput нет, помощник на /dev/uhid")
                if (!startUhid(ctx)) return@withContext false
                kotlinx.coroutines.delay(500)                       // регистрация контроллера
                return@withContext connect()
            }
            mode = "uinput"
            Adb.shell("echo ${b64(HELPER)} | base64 -d > /data/local/tmp/vpad_helper.sh && echo ${b64(REG)} | base64 -d > /data/local/tmp/vpad_reg.json && chmod 755 /data/local/tmp/vpad_helper.sh && (setsid nohup sh /data/local/tmp/vpad_helper.sh $PORT >/data/local/tmp/vpad_helper.log 2>&1 &); sleep 2", 15_000)
            var t = 0
            while (t < 40 && !helperAlive()) { kotlinx.coroutines.delay(100); t++ }
            if (!helperAlive()) return@withContext false
            kotlinx.coroutines.delay(500)                       // регистрация контроллера
        }
        val okc = connect()
        if (okc && mode == "uhid" && helperVer != UHID_VER && canShell()) {
            // старый помощник на uhid без файла версии: заменить на текущий
            AppLog.note("vpad: помощник uhid v$helperVer, нужен v$UHID_VER, замена")
            stop(false); runCatching { killHelper() }
            if (!startUhid(ctx)) return@withContext false
            kotlinx.coroutines.delay(500)
            return@withContext connect()
        }
        okc
    }

    private fun connect(): Boolean = runCatching {
        val s = Socket(); s.tcpNoDelay = true; s.connect(InetSocketAddress("127.0.0.1", PORT), 1000)
        // у помощника на uhid первая строка «uhid ВЕРСИЯ»; у помощника на uinput приветствия нет
        s.soTimeout = 250
        val hello = runCatching { s.getInputStream().bufferedReader().readLine() }.getOrNull()
        s.soTimeout = 0
        if (hello != null && hello.startsWith("uhid")) { mode = "uhid"; helperVer = hello.substringAfter(' ').trim().toIntOrNull() ?: 0 }
        sock = s; last.fill(Int.MIN_VALUE); lastBtn = 0; true
    }.getOrDefault(false)

    /** Закрыть связь; [kill] — ещё и убрать помощника вместе с виртуальным контроллером (пользователь закрыл оверлей). */
    fun stop(kill: Boolean) {
        val s = sock; sock = null
        val uhid = mode == "uhid"
        // помощник на uhid умеет сам убрать контроллер и свои файлы по команде quit (работает и без ADB)
        io.execute { if (kill && uhid) runCatching { s?.getOutputStream()?.apply { write("{\"command\":\"quit\"}\n".toByteArray()); flush() } }; runCatching { s?.close() } }
        on = false
        if (kill && canShell()) io.execute { runCatching { kotlinx.coroutines.runBlocking { Adb.shell("p=\$(cat /data/local/tmp/vpad.pid); pkill -P \$p; kill \$p; rm -f /data/local/tmp/vpad.pid /data/local/tmp/vpad.ver /data/local/tmp/vpad_uhid.jar /data/local/tmp/vpad_helper.log", 8_000) } } }
    }

    private fun write(line: String) {
        io.execute {
            val data = (line + "\n").toByteArray()
            var ok = runCatching { sock?.getOutputStream()?.apply { write(data); flush() } != null }.getOrDefault(false)
            if (!ok && helperAlive() && connect()) ok = runCatching { sock?.getOutputStream()?.apply { write(data); flush() } != null }.getOrDefault(false)
        }
    }

    private fun ev(vararg e: Int) { write("{\"id\":1,\"command\":\"inject\",\"events\":[" + e.joinToString(",") + "]}") }

    /** Отчёт Pad (9 байт: 0..3 стики 0..255, 4 LT, 5 RT, 6 крестовина 0..7 или 8, кнопки берутся из Pad.buttons): отправляются только изменения, одной пачкой. */
    fun send(r: ByteArray, buttons: Int) {
        if (sock == null) return
        val cur = IntArray(8)
        cur[0] = ((r[0].toInt() and 0xFF) - 128) * 257
        cur[1] = ((r[1].toInt() and 0xFF) - 128) * 257
        cur[2] = ((r[2].toInt() and 0xFF) - 128) * 257
        cur[3] = ((r[3].toInt() and 0xFF) - 128) * 257
        cur[4] = r[4].toInt() and 0xFF
        cur[5] = r[5].toInt() and 0xFF
        val hat = r[6].toInt() and 0x0F
        cur[6] = when (hat) { 1, 2, 3 -> 1; 5, 6, 7 -> -1; else -> 0 }      // ось 16: вправо +1, влево -1
        cur[7] = when (hat) { 7, 0, 1 -> -1; 3, 4, 5 -> 1; else -> 0 }      // ось 17: вверх -1, вниз +1
        val codes = intArrayOf(0, 1, 3, 4, 2, 5, 16, 17)
        val e = ArrayList<Int>()
        for (i in 0 until 8) if (cur[i] != last[i]) { e += 3; e += codes[i]; e += cur[i]; e += 0; e += 0; e += 0; last[i] = cur[i] }
        var mask = 0
        for ((bit, code) in BTN) if (buttons and (1 shl bit) != 0) mask = mask or (1 shl (code - 304))
        if (mask != lastBtn) {
            for ((_, code) in BTN.distinctBy { it.second }) {
                val b = 1 shl (code - 304); val was = lastBtn and b != 0; val now = mask and b != 0
                if (was != now) { e += 1; e += code; e += if (now) 1 else 0; e += 0; e += 0; e += 0 }
            }
            lastBtn = mask
        }
        if (e.isNotEmpty()) ev(*e.toIntArray())
    }
}
