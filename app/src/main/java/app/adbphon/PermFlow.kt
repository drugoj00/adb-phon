package app.adbphon

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * Единый порядок для разрешений: пояснение одной фразой → сразу открыть системную страницу нужного разрешения → пока она открыта, следим
 * за состоянием (опрос каждые 400 мс до 2 минут), и как только разрешение выдано, сами выводим ADB-phon вперёд и продолжаем прерванное действие
 * без повторных нажатий. Ушла назад без разрешения: ничего насильно не открываем, действие продолжается без него, отказ запоминается
 * и повторно не предлагается 24 часа.
 * Используется: вход в облако («Поверх других окон» для автовозврата, затем уведомления), оверлей геймпада.
 */
object PermFlow {
    private const val DAY = 24 * 3600 * 1000L
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pending: (() -> Unit)? = null
    @Volatile private var step = 0            // 0 нет; 1 ждём «Поверх других окон»; 2 ждём ответ на запрос уведомлений
    private var askNotif = false
    private var activity: WeakReference<Activity>? = null
    private var gen = 0
    @Volatile private var deadline = 0L        // до этого момента ждём возврата со страницы разрешения; позже ожидание сбрасывается
    private fun busy() = step != 0 && System.currentTimeMillis() < deadline

    fun findActivity(ctx: Context): Activity? { var c: Context? = ctx; while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }; return null }

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE)
    private fun refused(ctx: Context, key: String) = System.currentTimeMillis() - sp(ctx).getLong(key, 0L) < DAY
    private fun markRefused(ctx: Context, key: String) { sp(ctx).edit().putLong(key, System.currentTimeMillis()).apply() }

    fun overlayOk(ctx: Context) = runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)
    fun notifOk(ctx: Context) = Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    /** Нужно ли сейчас предлагать разрешение (не выдано и не отказано за последние сутки). */
    fun needOverlay(ctx: Context) = !overlayOk(ctx) && !refused(ctx, "perm_overlay_refused")
    fun needNotif(ctx: Context) = !notifOk(ctx) && !refused(ctx, "perm_notif_refused")

    /** Вход в облако через браузер: сначала «Поверх других окон» (автовозврат), затем уведомления, затем [go]. */
    fun forCloudLogin(ctx: Context, go: () -> Unit) {
        val act = findActivity(ctx)
        if (act == null || (!needOverlay(ctx) && !needNotif(ctx))) { go(); return }
        if (busy()) return        // страница разрешения уже открыта этой попыткой: второй раз не открываем
        activity = WeakReference(act); pending = go; askNotif = true; gen++
        next(act)
    }

    /** Оверлей геймпада: нужно только «Поверх других окон»; после выдачи оверлей запускается сам. */
    fun forOverlayOnly(ctx: Context, go: () -> Unit): Boolean {
        val act = findActivity(ctx)
        if (overlayOk(ctx)) { go(); return true }
        if (act == null) return false
        if (busy()) return false
        activity = WeakReference(act); pending = go; askNotif = false; gen++
        // для оверлея отказ не запоминаем: владелица нажала кнопку сама
        sp(ctx).edit().remove("perm_overlay_refused").apply()
        next(act)
        return false
    }

    private fun finish() { val g = pending; pending = null; step = 0; g?.invoke() }

    private fun next(act: Activity) {
        if (!overlayOk(act) && !refused(act, "perm_overlay_refused") && step != 3) {
            step = 1; deadline = System.currentTimeMillis() + 125_000L
            runCatching { Toast.makeText(act, act.getString(R.string.perm_overlay_why), Toast.LENGTH_LONG).show() }
            runCatching { act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + act.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            watchOverlay(act.applicationContext, gen)
            return
        }
        if (askNotif && needNotif(act) && Build.VERSION.SDK_INT >= 33) {
            askNotif = false; step = 2
            runCatching { Toast.makeText(act, act.getString(R.string.perm_notif_why), Toast.LENGTH_LONG).show() }
            runCatching { act.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7701) }.onFailure { step = 0; finish() }
            return
        }
        finish()
    }

    /** Пока открыта страница разрешения: опрос; выдано, сразу вернуть приложение вперёд (запуск из фона после выдачи «Поверх» разрешён). */
    private fun watchOverlay(app: Context, my: Int) {
        val end = System.currentTimeMillis() + 120_000L
        val tick = object : Runnable {
            override fun run() {
                if (my != gen || step != 1) return
                if (overlayOk(app)) {
                    runCatching { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
                    android.util.Log.i("PermFlow", "«Поверх других окон» выдано: возвращаем приложение вперёд")
                    return        // продолжение сделает onResume
                }
                if (System.currentTimeMillis() < end) main.postDelayed(this, 400)
                else { android.util.Log.i("PermFlow", "ожидание разрешения закончилось: слежка остановлена, продолжение отменено"); pending = null; step = 0 }
            }
        }
        main.postDelayed(tick, 400)
    }

    /** MainActivity.onResume: вернулись со страницы разрешения (сами или нас вернул опрос). */
    fun onResume(act: Activity) {
        activity = WeakReference(act)
        if (step == 1 && System.currentTimeMillis() > deadline) { pending = null; step = 0; return }   // старое ожидание: само ничего не запускаем
        if (step == 1) {
            if (!overlayOk(act)) markRefused(act, "perm_overlay_refused")
            step = 0
            next(act)
        }
    }

    fun onPermissionResult(act: Activity, requestCode: Int, granted: Boolean) {
        if (requestCode != 7701 || step != 2) return
        if (!granted) markRefused(act, "perm_notif_refused")
        step = 0
        finish()
    }
}
