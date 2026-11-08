package app.adbphon

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Запуск оверлея в одно нажатие: общий вход для плитки быстрых настроек, ярлыка значка и кнопки во вкладке. */
object OverlayLaunch {
    /** Нет разрешения «Поверх других окон»: открыть его экран одной короткой подсказкой; иначе включить или выключить оверлей. */
    fun toggle(ctx: Context): Boolean {
        if (OverlayPadService.running || OverlayPadService.starting) { OverlayPadService.stop(ctx); return false }
        return start(ctx)
    }

    /** true — оверлей запущен; false — открыт экран разрешения. */
    fun start(ctx: Context): Boolean {
        if (!Settings.canDrawOverlays(ctx) && PermFlow.findActivity(ctx) != null) {
            // из самого приложения: пояснение → страница разрешения → после выдачи вернуться сами и запустить оверлей
            PermFlow.forOverlayOnly(ctx) { OverlayPadService.start(ctx.applicationContext); offerTile(ctx.applicationContext); PermFlow.findActivity(ctx)?.moveTaskToBack(true) }
            return false
        }
        if (!Settings.canDrawOverlays(ctx)) {
            runCatching {
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.pad_overlay_perm), android.widget.Toast.LENGTH_LONG).show()
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return false
        }
        OverlayPadService.start(ctx)
        return true
    }

    /** Один раз предложить системой добавить плитку «Геймпад поверх игры» в быстрые настройки (Android 13+, одно нажатие «Добавить»). */
    fun offerTile(ctx: Context) {
        val sp = ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE)
        if (sp.getBoolean("tile_asked", false) || Build.VERSION.SDK_INT < 33) return
        sp.edit().putBoolean("tile_asked", true).apply()
        runCatching {
            val sb = ctx.getSystemService(android.app.StatusBarManager::class.java)
            sb.requestAddTileService(ComponentName(ctx, PadOverlayTile::class.java), ctx.getString(R.string.pad_overlay_label), Icon.createWithResource(ctx, R.drawable.ic_pad_tile), ctx.mainExecutor) { }
        }
    }
}

/** Плитка быстрых настроек «Геймпад поверх игры»: одно нажатие включает или выключает оверлей. */
class PadOverlayTile : TileService() {
    override fun onStartListening() { update() }
    override fun onClick() {
        val on = OverlayLaunch.toggle(applicationContext)
        qsTile?.let { it.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; it.updateTile() }
    }
    private fun update() {
        val t = qsTile ?: return
        t.state = if (OverlayPadService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = getString(R.string.pad_overlay_label)
        t.updateTile()
    }
}

/** Ярлык значка («Геймпад поверх игры»): без окна запускает оверлей и сразу закрывается. */
class PadOverlayShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OverlayLaunch.toggle(applicationContext)
        finish()
    }
}
