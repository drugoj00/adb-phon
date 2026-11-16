package app.adbphon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Сбой поиска ADB по mDNS во втором пространстве (user 95): «Ssaid settings not accessible» не должен ронять приложение. */
class AppLogCrashTest {
    private fun ex(msg: String, cls: String): Throwable = IllegalStateException(msg).also {
        it.stackTrace = arrayOf(StackTraceElement("android.provider.Settings\$Secure", "getString", "Settings.java", 1), StackTraceElement(cls, "isEmulator", "SourceFile", 37))
    }

    @Test fun ssaidInMdnsIsKnown() = assertTrue(AppLog.isKnownMdnsCrash(ex("Ssaid settings not accessible", "io.github.muntashirakon.adb.android.AdbMdns")))
    @Test fun ssaidInAndroidUtilsIsKnown() = assertTrue(AppLog.isKnownMdnsCrash(ex("Ssaid settings not accessible", "io.github.muntashirakon.adb.android.AndroidUtils")))
    @Test fun wrappedCauseIsKnown() = assertTrue(AppLog.isKnownMdnsCrash(RuntimeException("x", ex("Ssaid settings not accessible", "io.github.muntashirakon.adb.android.AdbMdns")).also { it.stackTrace = (it.cause as Throwable).stackTrace }))
    @Test fun otherErrorsStayFatal() {
        assertFalse(AppLog.isKnownMdnsCrash(ex("something else", "io.github.muntashirakon.adb.android.AdbMdns")))
        assertFalse(AppLog.isKnownMdnsCrash(ex("Ssaid settings not accessible", "app.adbphon.Fm")))
        assertFalse(AppLog.isKnownMdnsCrash(ArrayIndexOutOfBoundsException("length=2; index=-8")))
    }
}
