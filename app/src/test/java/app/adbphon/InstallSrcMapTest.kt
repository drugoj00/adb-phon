package app.adbphon

import app.adbphon.InstallSrcMap.Src
import org.junit.Assert.assertEquals
import org.junit.Test

/** Источник установки по пакету установщика (installingPackageName) и инициатору (initiatingPackageName, Android 11+). */
class InstallSrcMapTest {
    private fun d(inst: String?, init: String? = null, sys: Boolean = false) = InstallSrcMap.decide(inst, init, sys)

    @Test fun stores() {
        assertEquals(Src.PLAY, d("com.android.vending"))
        assertEquals(Src.RUSTORE, d("ru.vk.store"))
        assertEquals(Src.GALAXY, d("com.sec.android.app.samsungapps"))
        assertEquals(Src.HUAWEI, d("com.huawei.appmarket"))
        assertEquals(Src.XIAOMI, d("com.xiaomi.mipicks"))
        assertEquals(Src.XIAOMI, d("com.xiaomi.market"))
        assertEquals(Src.AMAZON, d("com.amazon.venezia"))
        assertEquals(Src.FDROID, d("org.fdroid.fdroid"))
        assertEquals(Src.FDROID, d("com.aurora.store"))
    }

    @Test fun shellAndFile() {
        assertEquals(Src.SHELL, d("com.android.shell"))
        assertEquals(Src.SHELL, d("app.adbphon"))
        assertEquals(Src.SHELL, d(null, "com.android.shell"))
        assertEquals(Src.FILE, d("com.google.android.packageinstaller"))
        assertEquals(Src.FILE, d("com.android.packageinstaller", "com.mi.android.globalFileexplorer"))
    }

    @Test fun storeBehindPackageInstaller() {
        // APK скачан магазином, а поставлен через системный установщик: источник магазин
        assertEquals(Src.FDROID, d("com.google.android.packageinstaller", "org.fdroid.fdroid"))
    }

    @Test fun otherAndUnknown() {
        assertEquals(Src.OTHER, d("io.github.muntashirakon.AppManager"))
        assertEquals(Src.OTHER, d(null, "com.example.installer"))
        assertEquals(Src.PREINSTALLED, d(null, null, sys = true))
        assertEquals(Src.UNKNOWN, d(null))
        assertEquals(Src.UNKNOWN, d(""))
    }
}
