package app.adbphon

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Тихая помощь окну игры, когда приложение подключено к ЭТОМУ телефону через ADB или root: на время окна системные настройки ориентации
 * выставляются так, чтобы экран лёг в альбом сам (автоповорот выключен, поворот пользователя 90° или 270°, система следует ему и не слушается
 * сторонних держателей ориентации), а при выходе, потере фокуса, сворачивании и закрытии приложения прежние значения возвращаются из сохранённого снимка.
 * Если процесс был убит посреди игры, снимок применяется при следующем запуске приложения ([restoreIfPending]).
 * Без ADB/root ничего не происходит: окно и так всегда выглядит альбомным (поворот слоя). Никаких сообщений, тостов и записей на экране.
 */
object OrientAssist {
    private const val KEY = "orient_snapshot"          // «автоповорот|поворот|игнор» до наших изменений; пусто — менять нечего
    private val lock = Mutex()
    /** Окно игры сейчас на экране и настройки изменены нами (снимок сохранён). */
    @Volatile private var active = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE)
    /** Только этот телефон: чужие устройства ADB не трогаем. */
    private fun selfAdb() = Adb.isConnected && Adb.currentHost.isEmpty()
    /** Разрешение «Изменение системных настроек» выдано (один раз владельцем): ориентация меняется без ADB. */
    fun canWrite(ctx: Context): Boolean = runCatching { android.provider.Settings.System.canWrite(ctx.applicationContext) }.getOrDefault(false)

    /**
     * При первом входе в окно игры, если разрешения нет, открывается его экран одной короткой строкой; отказ запоминается (больше не просим),
     * остаётся запасной вариант (слой окна повёрнут). Без ADB/root и без разрешения ничего не меняется.
     */
    fun askWrite(a: android.app.Activity) {
        if (canWrite(a) || sp(a).getBoolean("wr_asked", false)) return
        sp(a).edit().putBoolean("wr_asked", true).apply()
        runCatching {
            android.widget.Toast.makeText(a, a.getString(R.string.pad_write_ask), android.widget.Toast.LENGTH_LONG).show()
            a.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + a.packageName)))
        }
    }

    /** [portrait]: экран сейчас портретный (на приставке и в уже альбомном телефоне ничего менять не нужно). */
    fun begin(ctx: Context, flip: Boolean, portrait: Boolean) { scope.launch { runCatching { beginLocked(ctx, flip, portrait) } } }
    fun end(ctx: Context) { scope.launch { runCatching { lock.withLock { restoreLocked(ctx) } } } }
    fun restoreIfPending(ctx: Context) { if (sp(ctx).getString(KEY, "").isNullOrEmpty()) return; end(ctx) }

    private suspend fun get(cmd: String): String = runCatching { Adb.shell(cmd, 8_000L).trim() }.getOrDefault("")
    private suspend fun put(cmd: String) { runCatching { Adb.shell("$cmd 2>&1", 8_000L) } }

    private fun sysGet(ctx: Context, key: String): String = runCatching { android.provider.Settings.System.getInt(ctx.contentResolver, key).toString() }.getOrDefault("")
    /** Запись настройки: через разрешение WRITE_SETTINGS, иначе через ADB. */
    private suspend fun sysPut(ctx: Context, key: String, v: String) {
        if (canWrite(ctx)) { if (runCatching { android.provider.Settings.System.putInt(ctx.contentResolver, key, v.toInt()) }.getOrDefault(false)) return }
        if (selfAdb()) put("settings put system $key $v")
    }

    private suspend fun beginLocked(ctx: Context, flip: Boolean, portrait: Boolean) = lock.withLock {
        val adb = selfAdb()
        if (!adb && !canWrite(ctx)) return@withLock
        if (!active && !portrait) return@withLock
        val wantUr = if (flip) "3" else "1"
        if (active) {                                           // окно уже в игре (другая сторона альбома): снимок прежний, меняем только поворот
            if (sysGet(ctx, "user_rotation") != wantUr) sysPut(ctx, "user_rotation", wantUr)
            return@withLock
        }
        restoreLocked(ctx)                                     // остался снимок прошлого раза: сначала вернуть его, иначе записали бы уже изменённое
        val ar = sysGet(ctx, "accelerometer_rotation")
        val ur = sysGet(ctx, "user_rotation")
        val ig = if (!adb) "-" else if (get("cmd window get-ignore-orientation-request").contains("true")) "1" else "0"
        if (ar !in setOf("0", "1") || ur !in setOf("0", "1", "2", "3")) return@withLock
        // снимок сохраняется ДО изменений и только если что-то действительно будет меняться
        if (ar == "0" && ur == wantUr && (ig == "1" || ig == "-")) return@withLock
        sp(ctx).edit().putString(KEY, "$ar|$ur|$ig").commit()
        active = true
        if (ar != "0") sysPut(ctx, "accelerometer_rotation", "0")
        if (ur != wantUr) sysPut(ctx, "user_rotation", wantUr)
        if (adb && ig == "0") put("cmd window set-ignore-orientation-request true")
    }

    private suspend fun restoreLocked(ctx: Context) {
        val snap = sp(ctx).getString(KEY, "").orEmpty()
        if (snap.isEmpty()) { active = false; return }
        val p = snap.split('|')
        if (p.size != 3) { sp(ctx).edit().remove(KEY).commit(); return }
        if (!selfAdb() && !canWrite(ctx)) return                // нечем вернуть: снимок остаётся до следующего запуска
        if (p[2] != "-" && selfAdb()) put("cmd window set-ignore-orientation-request ${if (p[2] == "1") "true" else "false"}")
        sysPut(ctx, "user_rotation", p[1])
        sysPut(ctx, "accelerometer_rotation", p[0])
        sp(ctx).edit().remove(KEY).commit()
        active = false
    }

    // ---- держатель ориентации: крошечное окно-оверлей с запросом альбома. Побеждает чужие держатели (Rotation Control Pro) при ignoreOrientationRequest=false.
    // Нужно разрешение «Поверх других окон»; без него ничего не делается. Окно 1×1 пиксель, без касаний и фокуса, живёт только пока открыто окно игры.
    private var holder: android.view.View? = null

    fun holderOn(ctx: Context, flip: Boolean) {
        val app = ctx.applicationContext
        if (holder != null || !runCatching { android.provider.Settings.canDrawOverlays(app) }.getOrDefault(false)) return
        runCatching {
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val v = android.view.View(app)
            val lp = android.view.WindowManager.LayoutParams(1, 1, android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT)
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            lp.screenOrientation = if (flip) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            wm.addView(v, lp); holder = v
        }
    }

    fun holderOff(ctx: Context) {
        val v = holder ?: return
        holder = null
        runCatching { (ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).removeView(v) }
    }
}
