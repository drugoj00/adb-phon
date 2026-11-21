package app.adbphon

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PerfLog.mark("App.onCreate начало")
        // TLS 1.3 с экспортом ключевого материала нужен для сопряжения и беспроводной отладки
        PerfLog.t("Conscrypt") { Security.insertProviderAt(Conscrypt.newProvider(), 1) }
        // корневые сертификаты для старых Android (7.1 и ниже): без них запросы к GitHub, ntfy и облакам заканчиваются «Trust anch
        PerfLog.t("TlsFix") { runCatching { TlsFix.install(this) } }
        PerfLog.t("AppLog.init") { AppLog.init(this) }
        PerfLog.t("Integrity.warm") { runCatching { Integrity.warm(this) } }
        PerfLog.t("Feedback.init") { Feedback.init(this) }
        PerfLog.t("Marq.init") { Marq.init(this) }
        PerfLog.t("UiPrefs.init") { UiPrefs.init(this) }
        AppLog.header(this)   // 16.2: шапка журнала при каждом запуске (если журнал включён)
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
        PerfLog.t("Adb.init") { Adb.init(this) }
        PerfLog.t("NetKeep.start") { NetKeep.start(this) }
        PerfLog.t("VersionKeeper.backupSelf") { VersionKeeper.backupSelf(this) }
        PerfLog.mark("App.onCreate конец")
    }
}
