package app.adbphon

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Имя пакета из файла копии идёт в команды оболочки: недопустимые символы отклоняются при разборе. */
class BkSafeNamesTest {
    private fun meta(pkg: String) = "app=ADB-phon\nkind=appdata\nformat=1\npkg=$pkg\n".toByteArray()

    @Test fun normalPackagesAccepted() {
        for (p in listOf("com.example.app", "org.telegram.messenger", "android", "com.app_1.x2"))
            assertEquals(p, BkMeta.parse(meta(p)).pkg)
    }

    @Test fun shellCharsRejected() {
        for (p in listOf("x;reboot", "com.a b", "com.a\$(id)", "com.a`id`", "com.a'b", "../etc", ".com.a", "com.a|sh"))
            try { BkMeta.parse(meta(p)); fail("принят: $p") } catch (_: AbFormat.BadFormat) {}
    }
}
