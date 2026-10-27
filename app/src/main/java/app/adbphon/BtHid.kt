package app.adbphon

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Телефон как Bluetooth-мышь и клавиатура (профиль HID Device, Android 9+): компьютер, ноутбук, планшет, iPhone или телевизор
 * видят обычную мышь/клавиатуру, ничего ставить на них не нужно. Один раз сопрягаются, дальше подключаются сами.
 */
@SuppressLint("MissingPermission")
object BtHid {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 28
    var registered by mutableStateOf(false)
    var hostName by mutableStateOf<String?>(null)     // имя подключённого компьютера (null — не подключено)
    var error by mutableStateOf<String?>(null)
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Мышь (отчёт 1): кнопки, dx, dy, колесо. Клавиатура (отчёт 2): модификаторы, 0, 6 кодов клавиш.
    private val DESCRIPTOR: ByteArray = intArrayOf(
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x01, 0x09, 0x01, 0xA1, 0x00, 0x05, 0x09, 0x19, 0x01, 0x29, 0x03,
        0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02, 0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06, 0xC0, 0xC0,
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x02, 0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95, 0x08, 0x81, 0x02, 0x95, 0x01, 0x75, 0x08, 0x81, 0x03,
        0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02, 0x95, 0x01, 0x75, 0x03, 0x91, 0x03,
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00, 0xC0,
    ).map { it.toByte() }.toByteArray()

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registered = isRegistered
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED && device != null) {
                host = device
                hostName = runCatching { device.name }.getOrNull() ?: device.address
            } else if (state == BluetoothProfile.STATE_DISCONNECTED && device != null && device == host) {
                host = null
                hostName = null
            }
        }
    }

    /** Подключается к службе Bluetooth HID и регистрирует телефон как мышь/клавиатуру. Нужно разрешение «Устройства поблизости» (Android 12+). */
    fun start(ctx: Context) {
        if (!supported) return
        error = null
        if (hid != null) { register(ctx); return }
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) { error = "bt_off"; return }
        val ok = runCatching {
            adapter.getProfileProxy(ctx.applicationContext, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HID_DEVICE) {
                        hid = proxy as BluetoothHidDevice
                        register(ctx)
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    hid = null
                    registered = false
                    host = null
                    hostName = null
                }
            }, BluetoothProfile.HID_DEVICE)
        }.getOrDefault(false)
        if (!ok) error = "bt_unsupported"
    }

    private fun register(ctx: Context? = null) {
        val h = hid ?: return
        val c = ctx ?: return
        runCatching {
            val sdp = BluetoothHidDeviceAppSdpSettings("ADB-phon", "Phone as mouse and keyboard", "ADB-phon",
                BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR)
            h.registerApp(sdp, null, null, c.applicationContext.mainExecutor, callback)
        }.onFailure { AppLog.e("ADBphon", "bt hid register", it); error = "bt_unsupported" }
    }

    fun stop() {
        runCatching { hid?.unregisterApp() }
        registered = false
        host = null
        hostName = null
    }

    /** Подключиться к уже сопряжённому компьютеру (чаще подключение начинает сам компьютер). */
    fun connect(dev: BluetoothDevice) {
        runCatching { hid?.connect(dev) }
    }

    fun disconnect() {
        val d = host ?: return
        runCatching { hid?.disconnect(d) }
    }

    private fun send(id: Int, data: ByteArray) {
        val h = hid ?: return
        val d = host ?: return
        runCatching { h.sendReport(d, id, data) }
    }

    private fun b(v: Int) = v.coerceIn(-127, 127).toByte()

    fun move(dx: Float, dy: Float) {
        var x = dx.toInt()
        var y = dy.toInt()
        // большие смещения режем на отчёты по 127
        while (x != 0 || y != 0) {
            val sx = x.coerceIn(-127, 127)
            val sy = y.coerceIn(-127, 127)
            send(1, byteArrayOf(0, b(sx), b(sy), 0))
            x -= sx
            y -= sy
        }
    }

    fun wheel(n: Int) {
        if (n != 0) send(1, byteArrayOf(0, 0, 0, b(n)))
    }

    /** buttons: 1 левая, 2 правая, 4 средняя. */
    fun click(buttons: Int, times: Int = 1) {
        scope.launch {
            repeat(times) {
                send(1, byteArrayOf(buttons.toByte(), 0, 0, 0))
                delay(25)
                send(1, byteArrayOf(0, 0, 0, 0))
                delay(40)
            }
        }
    }

    /** Нажать и отпустить клавишу: модификаторы (1 Ctrl, 2 Shift, 4 Alt, 8 Win) и код HID (0 — только модификатор). */
    fun key(mod: Int, code: Int) {
        scope.launch {
            send(2, byteArrayOf(mod.toByte(), 0, code.toByte(), 0, 0, 0, 0, 0))
            delay(25)
            send(2, ByteArray(8))
            delay(20)
        }
    }

    /** Ввод текста латиницей и знаками (раскладка компьютера должна быть английской). Возвращает число символов, которых нет в таблице. */
    fun typeText(text: String): Int {
        var skipped = 0
        scope.launch {
            for (ch in text) {
                val kc = charToKey(ch)
                if (kc == null) { skipped++; continue }
                send(2, byteArrayOf(kc.first.toByte(), 0, kc.second.toByte(), 0, 0, 0, 0, 0))
                delay(18)
                send(2, ByteArray(8))
                delay(18)
            }
        }
        return skipped
    }

    private const val SHIFT = 2

    private fun charToKey(c: Char): Pair<Int, Int>? {
        return when (c) {
            in 'a'..'z' -> 0 to (0x04 + (c - 'a'))
            in 'A'..'Z' -> SHIFT to (0x04 + (c - 'A'))
            in '1'..'9' -> 0 to (0x1E + (c - '1'))
            '0' -> 0 to 0x27
            '\n' -> 0 to 0x28
            '\t' -> 0 to 0x2B
            ' ' -> 0 to 0x2C
            '-' -> 0 to 0x2D; '_' -> SHIFT to 0x2D
            '=' -> 0 to 0x2E; '+' -> SHIFT to 0x2E
            '[' -> 0 to 0x2F; '{' -> SHIFT to 0x2F
            ']' -> 0 to 0x30; '}' -> SHIFT to 0x30
            '\\' -> 0 to 0x31; '|' -> SHIFT to 0x31
            ';' -> 0 to 0x33; ':' -> SHIFT to 0x33
            '\'' -> 0 to 0x34; '"' -> SHIFT to 0x34
            '`' -> 0 to 0x35; '~' -> SHIFT to 0x35
            ',' -> 0 to 0x36; '<' -> SHIFT to 0x36
            '.' -> 0 to 0x37; '>' -> SHIFT to 0x37
            '/' -> 0 to 0x38; '?' -> SHIFT to 0x38
            '!' -> SHIFT to 0x1E; '@' -> SHIFT to 0x1F; '#' -> SHIFT to 0x20; '$' -> SHIFT to 0x21; '%' -> SHIFT to 0x22
            '^' -> SHIFT to 0x23; '&' -> SHIFT to 0x24; '*' -> SHIFT to 0x25; '(' -> SHIFT to 0x26; ')' -> SHIFT to 0x27
            else -> null
        }
    }
}
