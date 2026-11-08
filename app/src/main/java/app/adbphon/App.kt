package app.adbphon

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // TLS 1.3 с экспортом ключевого материала нужен для сопряжения и беспроводной отладки
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
        AppLog.init(this)
        Feedback.init(this)
        Marq.init(this)
        UiPrefs.init(this)
        // выход из приложения останавливает музыку (если не включено «Играть музыку в фоне»)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: android.app.Activity) { started++; if (Fm.backgrounded) NetKeep.onForeground(); Fm.backgrounded = false }
            override fun onActivityStopped(activity: android.app.Activity) {
                started--
                if (started <= 0) { Fm.backgrounded = true; NetKeep.onBackground(); WifiAuto.maybeRelease(this@App) }
                if (started <= 0 && AudioState.active && !getSharedPreferences("fm", MODE_PRIVATE).getBoolean("bg_music", false)) AudioService.cmd(this@App, "stop")
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        Adb.init(this)
        NetKeep.start(this)
        VersionKeeper.backupSelf(this)
    }
}
