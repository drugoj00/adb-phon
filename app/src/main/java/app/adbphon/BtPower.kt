package app.adbphon

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Питание Bluetooth для раздела «Мышь ПК»: включение и выключение без системного запроса.
 * Способы по порядку: Android 12 и старше напрямую через адаптер; затем root или ADB самого телефона
 * (`svc bluetooth enable|disable`, ADB подключается сам); системный запрос остаётся запасным путём.
 * Включает и выключает адаптер только по правилу: выключается на выходе лишь то, что включили мы.
 */
@SuppressLint("MissingPermission")
object BtPower {
    /** Адаптер включён (обновляется приёмником состояния). */
    var on by mutableStateOf(false)
    /** Идёт включение или выключение. */
    var busy by mutableStateOf(false)
    /** Включить без запроса не вышло: показать запасной путь (системный запрос). */
    var needPrompt by mutableStateOf(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var offJob: Job? = null
    @Volatile private var inside = false

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("bt_power", Context.MODE_PRIVATE)
    private fun adapter(ctx: Context) = (ctx.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isOn(ctx: Context) = adapter(ctx)?.isEnabled == true
    fun refresh(ctx: Context) { on = isOn(ctx) }

    fun autoOn(ctx: Context) = prefs(ctx).getBoolean("auto_on", true)
    fun autoOff(ctx: Context) = prefs(ctx).getBoolean("auto_off", true)
    fun setAutoOn(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("auto_on", v).apply() }
    fun setAutoOff(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("auto_off", v).apply() }

    /** Адаптер включили мы (запоминается и после перезапуска приложения). */
    private fun weEnabled(ctx: Context) = prefs(ctx).getBoolean("we", false)
    private fun setWe(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("we", v).apply() }

    private suspend fun waitFor(ctx: Context, want: Boolean): Boolean {
        repeat(30) { if (isOn(ctx) == want) return true; delay(200) }
        return isOn(ctx) == want
    }

    /** Переключить адаптер без запроса. true: состояние достигнуто. */
    private suspend fun switchTo(ctx: Context, want: Boolean): Boolean {
        val ad = adapter(ctx) ?: return false
        if (ad.isEnabled == want) return true
        // Android 12 и старше: напрямую
        if (Build.VERSION.SDK_INT <= 32) {
            val ok = runCatching { @Suppress("DEPRECATION") if (want) ad.enable() else ad.disable() }.getOrDefault(false)
            if (ok && waitFor(ctx, want)) return true
        }
        // root или ADB этого телефона (чужое устройство ADB сюда не подходит)
        if (!(Adb.isConnected && Adb.currentHost.isNotEmpty())) {
            FmIo.app = ctx.applicationContext
            if (withContext(Dispatchers.IO) { FmIo.ensurePriv() } == null) {
                runCatching { Adb.shell(if (want) "svc bluetooth enable" else "svc bluetooth disable") }
                if (waitFor(ctx, want)) return true
            }
        }
        return false
    }

    /** Счётчик обновлений: меняется, когда нужно перечитать список сопряжённых устройств. */
    var tick by mutableStateOf(0)
    private var switchJob: Job? = null
    fun switching(): Boolean = switchJob?.isActive == true
    @Volatile private var wanted: Boolean? = null

    /**
     * Переключение с таймаутом: «Переключаю…» не висит дольше 8 с (одна повторная попытка), после чего показывается реальное
     * состояние адаптера. Если адаптер уже в нужном состоянии (включили снаружи), ожидание сразу прекращается.
     */
    private fun run(ctx: Context, want: Boolean, mark: Boolean) {
        switchJob?.cancel()
        wanted = want
        switchJob = scope.launch {
            busy = true; needPrompt = false
            try {
                var ok = isOn(ctx) == want
                var attempt = 0
                while (!ok && attempt < 2) {
                    attempt++
                    ok = kotlinx.coroutines.withTimeoutOrNull(8_000) { switchTo(ctx, want) } == true || isOn(ctx) == want
                }
                if (ok) { if (mark) setWe(ctx, want) } else if (want) needPrompt = true
            } finally {
                busy = false; wanted = null; refresh(ctx); tick++
            }
        }
    }

    /** Кнопка-переключатель: одно нажатие включает или выключает. */
    fun toggle(ctx: Context) {
        val want = !isOn(ctx)
        offJob?.cancel()
        if (!want) {
            // выключение из приложения: сначала чисто отключить HID, потом адаптер
            scope.launch { BtHid.cleanDisconnect("выключатель Bluetooth", 1000); run(ctx, want, true) }
        } else run(ctx, want, true)
    }

    /** Любое изменение состояния адаптера (включили снаружи, шторка, ADB): состояние и список обновляются, ожидание снимается. */
    fun onSystemState(ctx: Context) {
        refresh(ctx); tick++
        val w = wanted
        if (busy && w != null && isOn(ctx) == w) switchJob?.cancel()
    }

    /** Вход в раздел «Мышь ПК»: отменяет отложенное выключение и при необходимости включает Bluetooth сам. */
    fun enter(ctx: Context) {
        inside = true
        offJob?.cancel(); offJob = null
        refresh(ctx)
        if (isOn(ctx)) { if (busy && wanted == true) switchJob?.cancel(); return }   // был включён до нас: не трогаем, флаг не ставим
        if (!autoOn(ctx) || busy) return
        run(ctx, true, true)
    }

    /** Выход из раздела, сворачивание или закрытие: через 2,5 с выключает Bluetooth, если его включили мы. */
    fun leave(ctx: Context, delayMs: Long = 2500) {
        inside = false
        offJob?.cancel()
        val app = ctx.applicationContext
        offJob = scope.launch {
            delay(delayMs)
            if (inside) return@launch
            offIfOurs(app)
        }
    }

    /** Закрытие приложения: без задержки. */
    fun leaveNow(ctx: Context) {
        inside = false
        offJob?.cancel()
        val app = ctx.applicationContext
        scope.launch { offIfOurs(app) }
    }

    private suspend fun offIfOurs(ctx: Context) {
        if (PadKeeper.enabled(ctx)) return    // фоновое подключение включено: Bluetooth и связь с хостом не трогаем
        if (!autoOff(ctx) || !weEnabled(ctx)) return
        if (!isOn(ctx)) { setWe(ctx, false); return }
        BtHid.cleanDisconnect("автовыключение", 1000)           // сначала отключить профиль HID и компьютер
        busy = true
        try { if (kotlinx.coroutines.withTimeoutOrNull(8_000) { switchTo(ctx, false) } == true || !isOn(ctx)) setWe(ctx, false) }
        finally { busy = false; refresh(ctx) }
    }
}
