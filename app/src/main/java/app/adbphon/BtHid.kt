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
/** Строка списка сопряжённых компьютеров. [dev] = null только у тестовых строк. */
data class BtRow(val dev: BluetoothDevice?, val address: String, val name: String)

@SuppressLint("MissingPermission")
object BtHid {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 28
    var registered by mutableStateOf(false)
    var hostName by mutableStateOf<String?>(null)     // имя подключённого компьютера (null — не подключено)
    var hostAddr by mutableStateOf<String?>(null)
    private var sectionJob: kotlinx.coroutines.Job? = null
    var error by mutableStateOf<String?>(null)
    private var appCtx: Context? = null
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Мышь (отчёт 1): кнопки, dx, dy, колесо. Клавиатура (отчёт 2): модификаторы, 0, 6 кодов клавиш.
    private val MOUSE_KB: IntArray = intArrayOf(
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x01, 0x09, 0x01, 0xA1, 0x00, 0x05, 0x09, 0x19, 0x01, 0x29, 0x03,
        0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02, 0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06, 0xC0, 0xC0,
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x02, 0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95, 0x08, 0x81, 0x02, 0x95, 0x01, 0x75, 0x08, 0x81, 0x03,
        0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02, 0x95, 0x01, 0x75, 0x03, 0x91, 0x03,
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00, 0xC0,
    )

    /**
     * Геймпад (отчёт 1, 9 байт): X, Y (левый стик), Z, Rz (правый стик), тормоз и газ (курки L2 и R2, 0..255), хэт-переключатель
     * (крестовина, 0..7 по часовой с «вверх» = 0, 8 = не нажата) и 16 кнопок. Порядок кнопок как в Linux/Android для игровых
     * контроллеров: 1 A, 2 B, 3 -, 4 X, 5 Y, 6 -, 7 L1, 8 R1, 9 L2, 10 R2, 11 Select, 12 Start, 13 Home, 14 L3, 15 R3.
     */
    private val PAD_INPUT: List<Int> = listOf(
        0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x85, 0x04,
        0x09, 0x01, 0xA1, 0x00, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02, 0xC0,
        0x05, 0x02, 0x09, 0xC5, 0x09, 0xC4, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02,
        0x05, 0x01, 0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42, 0x65, 0x00,
        0x75, 0x04, 0x95, 0x01, 0x81, 0x03,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x10, 0x81, 0x02,
    )

    /** Выходные отчёты для обратной вибрации: три варианта «совместимости». */
    private val OUT_XBOX = listOf(      // отчёт 6: включение моторов, четыре силы (левый курок, правый курок, сильный, слабый), длительность, задержка, повтор
        0x05, 0x0F, 0x09, 0x21, 0xA1, 0x02, 0x85, 0x06, 0x09, 0x97, 0x15, 0x00, 0x25, 0x01, 0x75, 0x04, 0x95, 0x01, 0x91, 0x02,
        0x15, 0x00, 0x25, 0x00, 0x75, 0x04, 0x95, 0x01, 0x91, 0x03,
        0x09, 0x70, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x04, 0x91, 0x02,
        0x09, 0x50, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x01, 0x91, 0x02,
        0x09, 0xA7, 0x91, 0x02, 0x09, 0x7C, 0x91, 0x02, 0xC0,
    )
    private val OUT_DS = listOf(        // отчёт 7: флаги, 0, 0, слабый, сильный
        0x06, 0x00, 0xFF, 0x09, 0x20, 0xA1, 0x02, 0x85, 0x07, 0x09, 0x21, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x05, 0x91, 0x02, 0xC0,
    )
    private val OUT_PID = listOf(       // отчёт 8: сильный, слабый
        0x05, 0x0F, 0x09, 0x21, 0xA1, 0x02, 0x85, 0x08, 0x09, 0x70, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x02, 0x91, 0x02, 0xC0,
    )

    /**
     * ЗАМОРОЖЕННЫЙ СОСТАВНОЙ ДЕСКРИПТОР (04.11.2026, версия 3.1, вариант 1: 214 байт): одна регистрация на оба раздела.
     * Мышь: отчёт 1. Клавиатура: отчёт 2. Геймпад: входной отчёт 4 (9 байт). Проверено на Windows: после одного сопряжения создаются
     * «HID-compliant mouse», «HID Keyboard Device» и «HID-compliant game controller». SDP: «ADB-phon» / «Phone as mouse and keyboard» /
     * «ADB-phon», подкласс SUBCLASS1_COMBO. Первые 119 байт (мышь и клавиатура) совпадают с опубликованными версиями.
     * ПРАВИЛО: НЕ МЕНЯТЬ порядок, номера отчётов и состав. Новое можно только ДОБАВЛЯТЬ В ХВОСТ новыми отчётами (номер 5 и выше,
     * кроме 6, 7, 8 под вибро); Windows кэширует дескриптор при сопряжении, любая правка заставляет пересопрягать ПК.
     * Составной вариант с вибро Output-отчётами (324 байта, Usage Page 0x0F в геймпаде) Windows НЕ принимал.
     */
    private val DESCRIPTOR: ByteArray = MOUSE_KB.map { it.toByte() }.toByteArray()
    private val PAD_DESCRIPTOR: ByteArray = (PAD_INPUT + OUT_XBOX + OUT_DS + OUT_PID + listOf(0xC0)).map { it.toByte() }.toByteArray()

    /** Выходной отчёт 8 как вибро в «своей» (vendor) коллекции: некоторые хосты не принимают вибро Usage Page 0x0F внутри геймпада. */
    private val OUT_VENDOR = listOf(0x06, 0x00, 0xFF, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x08, 0x09, 0x02, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x02, 0x91, 0x02, 0xC0)

    /**
     * ТЕСТОВЫЕ ВАРИАНТЫ ОДНОГО (составного) дескриптора для поиска того, что принимает Windows. Включаются только файлом
     * Android/data/app.adbphon/files/desc_variant.txt (число); без файла работает вариант 1 (принят Windows, по умолчанию).
     * Вариант 0: старая схема (мышь 119 байт, геймпад отдельной регистрацией).
     * 1: мышь+клавиатура+геймпад без вибро; 2: то же + вибро PID; 3: то же + все три вибро (324 байта, Windows не принимал);
     * 4: то же, что 1, но коротко (12 кнопок, без курков); 5: вариант 1 + вибро отдельной vendor-коллекцией.
     */
    /**
     * ИТОГОВЫЙ ДЕСКРИПТОР (05.11.2026, версия 3.1): вариант 2, 236 байт = мышь (отчёт 1) + клавиатура (2) + геймпад (4) + вибро PID внутри геймпада
     * (Output, отчёт 8: [сильный, слабый]). Windows ПРИНЯЛ после пересопряжения: «Bluetooth HID Device», HID-compliant mouse, HID Keyboard Device,
     * HID-compliant game controller; Output-отчёт 8 доходит с ПК (Вибро_с_ПК.py), телефон вибрирует. Файла-переключателя больше нет.
     * ЗАМОРОЖЕН: порядок, номера отчётов и состав не менять; любое изменение = пересопряжение на всех хостах. Новое только в хвост (номера 3, 5, 9 и выше).
     */
    @Volatile var variant = 2
    @Suppress("UNUSED_PARAMETER")
    fun loadVariant(ctx: Context) {}
    private val PAD_SHORT: List<Int> = listOf(
        0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x85, 0x04,
        0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02,
        0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x0C, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x0C, 0x81, 0x02,
        0x75, 0x04, 0x95, 0x01, 0x81, 0x03,
        0xC0,
    )
    private fun descriptorFor(pad: Boolean): ByteArray = when (variant) {
        1 -> MOUSE_KB + PAD_INPUT + listOf(0xC0)
        2 -> MOUSE_KB + PAD_INPUT + OUT_PID + listOf(0xC0)
        3 -> MOUSE_KB + PAD_INPUT + OUT_XBOX + OUT_DS + OUT_PID + listOf(0xC0)
        4 -> MOUSE_KB + PAD_SHORT
        5 -> MOUSE_KB + PAD_INPUT + listOf(0xC0) + OUT_VENDOR
        else -> null
    }?.map { it.toByte() }?.toByteArray() ?: if (pad) PAD_DESCRIPTOR else DESCRIPTOR

    /** Вариант вибрации не меняет дескриптор: выходные отчёты принимаются все три. */
    @Suppress("UNUSED_PARAMETER")
    fun restartPad(ctx: Context) {}

    /** Что сейчас зарегистрировано: «mouse» (мышь и клавиатура) или «pad» (геймпад). Одновременно возможно одно устройство HID. */
    var mode by mutableStateOf("mouse")
    private var reconnectTo: BluetoothDevice? = null
    private var lastHost: BluetoothDevice? = null

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registered = isRegistered
            // после смены режима снова подключаемся к тому же хосту
            val d = reconnectTo
            if (isRegistered && d != null) {
                reconnectTo = null
                scope.launch { delay(300); runCatching { hid?.connect(d) } }
            }
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            if (data != null) appCtx?.let { PadRumble.onReport(it, id.toInt() and 0xFF, data) }
            runCatching { if (device != null) hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS) }
        }

        override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) {
            if (data != null) appCtx?.let { PadRumble.onReport(it, reportId.toInt() and 0xFF, data) }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            lastState = state
            AppLog.i("ADBphon", "hid state ${device?.address} -> $state")
            // 09.11.2026 (слово владелицы): если человек выбрал устройство, другие устройства (например, компьютер) не подключаются сами
            val wanted = wantedAddr
            if (state == BluetoothProfile.STATE_CONNECTED && device != null && wanted != null && !device.address.equals(wanted, ignoreCase = true)) {
                AppLog.i("ADBphon", "hid: подключение от ${device.address} отклонено, выбрано $wanted")
                runCatching { hid?.disconnect(device) }
                appCtx?.let { PadKeeper.askConnect(it, device.address, runCatching { device.name }.getOrNull() ?: device.address) }   // запрос: «хочет подключиться»
                return
            }
            if (state == BluetoothProfile.STATE_CONNECTED && device != null) {
                touchLast(device.address); markHidOk(device.address)
                host = device
                lastHost = device
                hostAddr = device.address
                hostName = runCatching { device.name }.getOrNull() ?: device.address
                appCtx?.let { GameHostPrep.run(it, device.address) }    // память и лишние контроллеры игрового устройства (если есть ADB)
            } else if (state == BluetoothProfile.STATE_DISCONNECTED && device != null && device == host) {
                host = null
                hostName = null
                hostAddr = null
                PadRumble.stop(appCtx)
            }
        }
    }

    /**
     * Раздел «Мышь ПК» или «Геймпад»: регистрирует соответствующий дескриптор. При смене раздела отменяет регистрацию, регистрирует
     * другой дескриптор и снова подключается к тому же хосту (мышь всегда регистрируется прежним проверенным дескриптором).
     */
    fun useMode(ctx: Context, m: String) {
        if (mode == m) return
        releaseAll()           // отпустить всё, что было нажато в прежнем разделе
        mode = m
        if (variant != 0) return   // составной тестовый вариант: одна регистрация, перерегистрации нет
        val h = hid ?: return
        reconnectTo = host ?: lastHost
        runCatching { h.unregisterApp() }
        registered = false; host = null; hostName = null; hostAddr = null
        scope.launch { delay(500); if (hid != null && mode == m) register(ctx) }
    }

    /** Bluetooth выключили (кнопкой, шторкой, ADB): служба HID пропала, старый прокси недействителен. При включении он берётся заново. */
    fun onAdapterOff(ctx: Context) {
        val h = hid
        runCatching { if (h != null) (ctx.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h) }
        hid = null; registered = false; host = null; hostName = null; hostAddr = null
        PadRumble.stop(appCtx)
    }

    fun hostAddress(): String? = (host ?: lastHost)?.address

    /** Отчёт геймпада (9 байт, формат в [PAD_DESCRIPTOR]). */
    fun sendPad(data: ByteArray) {
        if (mode != "pad") return
        // номер отчёта геймпада 4 не совпадает с мышью (1) и клавиатурой (2): компьютер со старым описанием устройства не примет его за мышь
        send(4, data)
    }

    /** Подключается к службе Bluetooth HID и регистрирует телефон как мышь/клавиатуру. Нужно разрешение «Устройства поблизости» (Android 12+). */
    fun start(ctx: Context) {
        if (!supported) return
        error = null
        appCtx = ctx.applicationContext
        ensureStateReceiver(ctx)
        if (hid != null) { if (!registered) register(ctx); return }
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
            // мышь и клавиатура: ЗАМОРОЖЕНО, как в опубликованных версиях; геймпад: отдельная регистрация
            loadVariant(c)
            val pad = mode == "pad" && variant == 0
            val sdp = if (pad) BluetoothHidDeviceAppSdpSettings("ADB-phon Gamepad", "Phone as gamepad", "ADB-phon", BluetoothHidDevice.SUBCLASS2_GAMEPAD, PAD_DESCRIPTOR)
                      else BluetoothHidDeviceAppSdpSettings("ADB-phon", "Phone as mouse and keyboard", "ADB-phon", BluetoothHidDevice.SUBCLASS1_COMBO, descriptorFor(false))
            AppLog.i("ADBphon", "hid registerApp variant=$variant pad=$pad len=${sdp.descriptors.size}")
            h.registerApp(sdp, null, null, c.applicationContext.mainExecutor, callback)
        }.onFailure { AppLog.e("ADBphon", "bt hid register", it); error = "bt_unsupported" }
    }

    fun stop() {
        releaseAll()
        runCatching { host?.let { hid?.disconnect(it) } }
        runCatching { hid?.unregisterApp() }
        registered = false
        host = null
        hostName = null
        hostAddr = null
    }

    /** Все кнопки мыши и клавиатуры отпущены, оси геймпада в нуле: отправляется ВСЕГДА перед отключением, потерей фокуса, выходом из раздела. */
    /** Что реально отправлялось ненулевым: «все отпущено» шлётся только для этого (без лишних отчётов на хост). */
    private val dirty = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    fun releaseAll() {
        if (dryRun) {
            for (id in listOf(1, 2, 4)) if (dirty.remove(id) != null) android.util.Log.i("ADBphonHID", "release-all id=$id")
            return
        }
        val h = hid ?: return
        val d = host ?: return
        for (id in listOf(1, 2, 4)) {
            if (dirty.remove(id) == null) continue
            val z = when (id) { 4 -> Pad.neutral(); 1 -> byteArrayOf(0, 0, 0, 0); else -> ByteArray(8) }
            runCatching { h.sendReport(d, id, z) }
        }
    }

    /** Вход в раздел «Мышь ПК» или «Геймпад»: отменяет отложенное отключение. */
    fun sectionEnter() { sectionJob?.cancel(); sectionJob = null }

    /**
     * Выход из раздела или сворачивание: сразу «всё отпущено», через 2,5 с телефон перестаёт быть устройством ввода хоста
     * (HID отключается и регистрация снимается). Возврат в раздел в течение 2,5 с ничего не отключает.
     */
    fun sectionLeave(delayMs: Long = 2500) {
        releaseAll()
        // включено фоновое подключение: связь с хостом остаётся при выходе из раздела
        if (appCtx?.let { PadKeeper.enabled(it) } == true) return
        sectionJob?.cancel()
        sectionJob = scope.launch { delay(delayMs); cleanDisconnect("выход из раздела") }
    }

    /** Состояние HID-подключения к устройству по данным системы: 0 нет, 1 идёт подключение, 2 подключено, 3 отключение. */
    fun connState(dev: BluetoothDevice): Int = runCatching { hid?.getConnectionState(dev) ?: 0 }.getOrDefault(0)

    /** Последнее состояние из уведомления системы и причина, чтобы показывать пользователю настоящий статус. */
    var lastState by mutableStateOf(0)

    /**
     * Подключиться к сопряжённому компьютеру: HID-приложение уже зарегистрировано, сопряжение завершено; чужие попытки подключения
     * сначала отменяются (иначе система отвечает «состояние не изменилось» и игнорирует запрос). true: система приняла запрос.
     */
    fun connectTo(dev: BluetoothDevice): Boolean {
        val h = hid ?: return false
        touchLast(dev.address)
        runCatching {
            h.getDevicesMatchingConnectionStates(intArrayOf(BluetoothProfile.STATE_CONNECTING, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_DISCONNECTING))
                .filter { it != dev }.forEach { h.disconnect(it) }
        }
        val st = connState(dev)
        if (st == BluetoothProfile.STATE_CONNECTED) return true
        if (st == BluetoothProfile.STATE_CONNECTING || st == BluetoothProfile.STATE_DISCONNECTING) runCatching { h.disconnect(dev) }
        val ok = runCatching { h.connect(dev) }.getOrDefault(false)
        AppLog.i("ADBphon", "hid connect ${dev.address} bond=${dev.bondState} state=$st -> $ok")
        return ok
    }

    /** Подключиться к уже сопряжённому компьютеру (чаще подключение начинает сам компьютер). */
    fun connect(dev: BluetoothDevice) { connectTo(dev) }

    /** Устройства, к которым HID действительно подключался: только их автоподключение пробует снова. */
    fun hidOk(ctx: Context, address: String) = prefs(ctx).getLong("hid_ok_$address", 0L) > 0L
    fun markHidOk(address: String) { appCtx?.let { prefs(it).edit().putLong("hid_ok_$address", System.currentTimeMillis()).apply() } }

    // --- порядок списка подключений: последнее использованное (подключённое или выбранное) первым ---
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("bt_last", Context.MODE_PRIVATE)

    /** Запоминает время последнего подключения/выбора компьютера. */
    private fun touchLast(address: String) {
        val c = appCtx ?: return
        prefs(c).edit().putLong("last_$address", System.currentTimeMillis()).apply()
    }

    /**
     * Список сопряжённых компьютеров: сначала самый свежий. Для устройства без записи о подключении берётся время, когда оно впервые
     * попало в список (так новое сопряжённое сразу вверху); при равном времени по имени.
     */
    fun sortLastFirst(ctx: Context, rows: List<BtRow>): List<BtRow> {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val ed = p.edit()
        val keyed = rows.map { r ->
            val last = p.getLong("last_${r.address}", 0L)
            var seen = p.getLong("seen_${r.address}", 0L)
            if (seen == 0L) { seen = now; ed.putLong("seen_${r.address}", seen) }
            r to maxOf(last, seen)
        }
        ed.apply()
        return keyed.sortedWith(compareByDescending<Pair<BtRow, Long>> { it.second }.thenBy { it.first.name.lowercase() }).map { it.first }
    }

    /** Выбор строки списка: запоминает время и подключается (для настоящего устройства). */
    fun pick(row: BtRow) {
        touchLast(row.address)
        row.dev?.let { connect(it) }
    }

    fun disconnect() {
        val d = host ?: return
        releaseAll()
        runCatching { hid?.disconnect(d) }
    }

    /**
     * Чистое отключение без участия пользователя: «все отпущено» (только для реально отправленных ненулевых отчётов), затем
     * disconnect хоста; ждёт отключения до [ms] мс (чтобы на компьютере не оставалось «зависшего» подключения), затем снимает регистрацию.
     */
    suspend fun cleanDisconnect(reason: String, ms: Long = 1000) {
        val d = host
        AppLog.i("ADBphon", "hid clean: release-all + disconnect, причина=$reason, хост=${d?.address}")
        releaseAll()
        if (d != null) {
            runCatching { hid?.disconnect(d) }
            kotlinx.coroutines.withTimeoutOrNull(ms) { while (hostName != null) delay(40) }
        }
        runCatching { hid?.unregisterApp() }
        registered = false; host = null; hostName = null; hostAddr = null
        PadRumble.stop(appCtx)
    }

    /** Мгновенный вариант для моментов, когда процесс или адаптер вот-вот исчезнут (выключение Bluetooth, закрытие приложения): без ожидания. */
    fun disconnectNow(reason: String) {
        val d = host
        if (d == null) { AppLog.i("ADBphon", "hid clean now: хоста нет, отключать нечего, причина=$reason"); return }
        AppLog.i("ADBphon", "hid clean now: release-all + disconnect, причина=$reason, хост=${d.address}")
        releaseAll()
        runCatching { hid?.disconnect(d) }
    }

    private var stateRx: android.content.BroadcastReceiver? = null

    /** Приёмник состояния адаптера на весь срок жизни процесса: при выключении Bluetooth (шторка, приложение, adb) телефон сначала отключается от хоста. */
    fun ensureStateReceiver(ctx: Context) {
        if (stateRx != null) return
        val app = ctx.applicationContext
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: android.content.Intent?) {
                val st = i?.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1) ?: return
                if (st == android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF) disconnectNow("TURNING_OFF")
                if (st == android.bluetooth.BluetoothAdapter.STATE_OFF) { AppLog.i("ADBphon", "hid: адаптер выключен, регистрация сброшена"); onAdapterOff(app) }
            }
        }
        runCatching { app.registerReceiver(rx, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)); stateRx = rx }
    }

    /** Идёт переключение на другой хост из окна игры: окно не закрывается, пока хост на время отсутствует. */
    /** Адрес устройства, которое выбрал человек: подключения от других отклоняются (null: принимать любое). */
    @Volatile var wantedAddr: String? = null
    var switching by mutableStateOf(false)

    /** Пробный прогон без хоста (только для проверки по журналу): отчёты не уходят, а пишутся в logcat с тегом ADBphonHID. */
    @Volatile var dryRun = false

    private fun send(id: Int, data: ByteArray) {
        if (dryRun) {
            val zero0 = if (id == 4) data.contentEquals(Pad.neutral()) else data.all { it.toInt() == 0 }
            if (zero0) dirty.remove(id) else dirty[id] = true
            android.util.Log.i("ADBphonHID", "out id=$id " + data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
            return
        }
        val h = hid ?: return
        val d = host ?: return
        // запоминаем, что отправили ненулевое состояние; нулевое снимает пометку
        val zero = if (id == 4) data.contentEquals(Pad.neutral()) else data.all { it.toInt() == 0 }
        if (zero) dirty.remove(id) else dirty[id] = true
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

    /** Отчёт мыши для режима «Курсор»: кнопки (1 левая, 2 правая, 4 средняя), смещение, колесо. Большое смещение режется на отчёты по 127. */
    fun sendMouse(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        var x = dx; var y = dy; var first = true
        do {
            val sx = x.coerceIn(-127, 127); val sy = y.coerceIn(-127, 127)
            send(1, byteArrayOf(buttons.toByte(), b(sx), b(sy), b(if (first) wheel else 0)))
            x -= sx; y -= sy; first = false
        } while (x != 0 || y != 0)
    }

    /** Отчёт клавиатуры для режима «Курсор»: модификаторы и до шести удерживаемых кодов клавиш. */
    fun sendKeys(report: ByteArray) { send(2, report) }

    fun wheel(n: Int) {
        if (n != 0) send(1, byteArrayOf(0, 0, 0, b(n)))
    }

    /** buttons: 1 левая, 2 правая, 4 средняя. */
    fun click(buttons: Int, times: Int = 1) {
        scope.launch {
            try {
                repeat(times) {
                    send(1, byteArrayOf(buttons.toByte(), 0, 0, 0))
                    delay(25)
                    send(1, byteArrayOf(0, 0, 0, 0))
                    delay(40)
                }
            } finally {
                // отмена на полпути: кнопка не должна остаться нажатой на хосте
                runCatching { hid?.let { h -> host?.let { d -> h.sendReport(d, 1, byteArrayOf(0, 0, 0, 0)) } } }
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
