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
        Adb.init(this)
        VersionKeeper.backupSelf(this)
    }
}
