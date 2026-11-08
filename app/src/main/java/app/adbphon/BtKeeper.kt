package app.adbphon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Фоновое подключение геймпада к последнему хосту (ПК, приставка, магнитола, другой телефон) без участия человека.
 * Служба переднего плана (значок в шторке) держит регистрацию HID и каждые 5 с пробует подключиться к ПОСЛЕДНЕМУ хосту, к которому
 * HID уже подключался (чужие устройства не перебираются). Возвращает связь после перезапуска приложения, выключения и включения
 * Bluetooth на телефоне или на хосте, перезагрузки хоста и телефона. Останавливается при ручном «Отключить и забыть»,
 * пока человек сам не выберет устройство, и пока идёт ручное подключение или автосопряжение. Выключатель: «Держать подключение в фоне».
 */
object PadKeeper {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("bt_auto", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("keeper", true)
    /** Человек нажал «Отключить» в шторке: фоновая служба не стартует и сама не подключается, пока он не подключит что-нибудь вручную (запоминается и после перезапуска). */
    fun paused(ctx: Context) = prefs(ctx).getBoolean("paused", false)
    fun setPaused(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("paused", v).apply() }
    fun setEnabled(ctx: Context, v: Boolean) {
        if (v) setPaused(ctx, false)
        prefs(ctx).edit().putBoolean("keeper", v).apply()
        if (v) start(ctx) else ctx.applicationContext.stopService(Intent(ctx.applicationContext, PadKeeperService::class.java))
    }

    /** «Выход»: служба фонового подключения останавливается и убирает уведомление, чтобы система не перезапустила процесс (START_STICKY). Автоподключение вернётся при следующем открытии программы. */
    fun stopForExit(ctx: Context) {
        val app = ctx.applicationContext
        runCatching { app.stopService(Intent(app, PadKeeperService::class.java)) }
        runCatching { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(PadKeeperService.ID) }
    }

    /** Запуск службы (при открытии приложения, после загрузки и обновления). Нет права на Bluetooth или выключатель снят: ничего не делает. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!enabled(app) || paused(app)) return
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(app, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        runCatching { ContextCompat.startForegroundService(app, Intent(app, PadKeeperService::class.java)) }
            .onFailure { AppLog.e("ADBphon", "keeper start", it) }
    }
}

class PadKeeperService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var shown = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, getString(R.string.kpr_title), NotificationManager.IMPORTANCE_MIN))
        val n = notification(getString(R.string.kpr_wait_bt))
        if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(ID, n)
        if (intent?.action == ACTION_DISCONNECT) { disconnectAll(); return START_NOT_STICKY }    // сначала startForeground (иначе система считает запуск ошибкой), потом отключение
        Pad.load(applicationContext)    // настройки вибро и режима «Курсор» нужны и когда процесс жив только ради службы (окон нет)
        if (job == null) job = scope.launch { loop() }
        return START_STICKY
    }

    override fun onDestroy() { job?.cancel(); job = null; super.onDestroy() }

    /**
     * Кнопка «Отключить» в шторке: закрывает подключение геймпада к хосту (с отключением от компьютера), ADB-соединение приложения и root-сеанс,
     * останавливает помощника ввода, ставит автоподключения на паузу (BtAuto.userStopped, Adb.autoOff, запоминается) и убирает уведомление.
     * Автоподключение вернётся только после ручного подключения (раздел Bluetooth, выбор устройства, ADB) или включения «Держать подключение».
     */
    private fun disconnectAll() {
        val app = applicationContext
        AppLog.note("шторка: «Отключить» (хост=${BtHid.hostName}, ADB=${Adb.isConnected})")
        PadKeeper.setPaused(app, true)
        job?.cancel(); job = null
        BtAuto.userDisconnect()
        Adb.autoOff = true
        scope.launch {
            runCatching { BtHid.cleanDisconnect("шторка: отключить", 1000) }
            runCatching { Adb.helperStop() }
            runCatching { Adb.disconnect() }
            AppLog.note("шторка: подключения закрыты, уведомление убрано")
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID)
            stopSelf()
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val off = PendingIntent.getService(this, 3, Intent(this, PadKeeperService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle(getString(R.string.kpr_title))
            .setContentText(text).setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN).setSilent(true).setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.kpr_disconnect), off).build()
    }

    private fun show(text0: String) {
        val text = if (runCatching { Adb.isConnected }.getOrDefault(false)) text0 + " · " + getString(R.string.kpr_adb) else text0
        if (text == shown) return
        shown = text
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notification(text))
    }

    private suspend fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        var t = 0L
        while (t < ms) { if (cond()) return true; delay(250); t += 250 }
        return cond()
    }

    private suspend fun loop() {
        val app = applicationContext
        BtHid.ensureStateReceiver(app)
        while (true) {
            if (!PadKeeper.enabled(app)) { stopSelf(); return }
            runCatching {
                when {
                    !BtPower.isOn(app) -> show(getString(R.string.kpr_wait_bt))
                    !BtHid.registered -> {
                        show(getString(R.string.kpr_wait_bt))
                        BtHid.useMode(app, "pad"); BtHid.start(app)
                        waitFor(10_000) { BtHid.registered }
                    }
                    BtHid.hostName != null -> show(getString(R.string.kpr_connected, BtHid.hostName))
                    BtAuto.busy || BtAuto.manualActive || BtAuto.userStopped || BtHid.switching -> show(getString(R.string.kpr_paused))
                    else -> {
                        val ad = (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
                        // только то, к чему HID уже подключался, самое свежее первым
                        val row = BtHid.sortLastFirst(app, runCatching { ad?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
                            .filter { BtHid.hidOk(app, it.address) }.map { BtRow(it, it.address, runCatching { it.name }.getOrNull() ?: it.address) }).firstOrNull()
                        if (row?.dev != null) {
                            show(getString(R.string.kpr_connecting, row.name))
                            BtHid.connectTo(row.dev)
                            waitFor(8_000) { BtHid.hostName != null || BtAuto.manualActive || BtAuto.userStopped }
                        } else show(getString(R.string.kpr_no_host))
                    }
                }
            }.onFailure { AppLog.e("ADBphon", "keeper loop", it) }
            delay(if (BtHid.hostName != null) 3000 else 5000)
        }
    }

    companion object { private const val CH = "pad_keeper"; const val ID = 4417; const val ACTION_DISCONNECT = "app.adbphon.KEEPER_DISCONNECT" }
}
