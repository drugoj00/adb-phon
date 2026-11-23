package app.adbphon

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** 22.11.2026 (для 18.0): защита от повторного окна «Установить» (InstallGuard). */
class InstallGuardTest {
    @Before @After fun reset() = InstallGuard.resetForTest()

    private fun tmp(size: Int, name: String = "a.apk"): File = File.createTempFile("igt_", name).apply { writeBytes(ByteArray(size)); deleteOnExit() }

    @Test fun secondBeginIsRejectedWhileFirstRuns() {
        assertTrue(InstallGuard.tryBegin("p|1|10", 1000))
        assertFalse(InstallGuard.tryBegin("p|1|10", 2000))
        assertTrue(InstallGuard.tryBegin("q|1|10", 2000))   // другой пакет не мешает
    }

    @Test fun endAllowsNewBegin() {
        InstallGuard.tryBegin("k", 1000)
        InstallGuard.end("k")
        assertTrue(InstallGuard.tryBegin("k", 1500))
    }

    @Test fun lostEntryExpires() {
        InstallGuard.tryBegin("k", 1000)
        assertFalse(InstallGuard.isActive("k", 1000 + InstallGuard.STALE_MS))
        assertTrue(InstallGuard.tryBegin("k", 1000 + InstallGuard.STALE_MS))
    }

    @Test fun phaseMovesToSystemWindowAndKeepsStartTime() {
        InstallGuard.tryBegin("k", 1000)
        assertEquals(InstallGuard.Phase.WRITE, InstallGuard.phaseOf("k", 1100))
        InstallGuard.setPhase("k", InstallGuard.Phase.SYSTEM)
        assertEquals(InstallGuard.Phase.SYSTEM, InstallGuard.phaseOf("k", 1100))
        assertFalse(InstallGuard.tryBegin("k", 1200))
        InstallGuard.setPhase("none", InstallGuard.Phase.SYSTEM)   // чужого ключа нет, ничего не появляется
        assertNull(InstallGuard.phaseOf("none", 1200))
    }

    @Test fun keyDoesNotDependOnPathOfCopy() {
        val a = tmp(100, "x.apk"); val b = tmp(100, "y.apk")
        assertEquals(InstallGuard.key("pkg", listOf(a)), InstallGuard.key("pkg", listOf(b)))
        assertTrue(InstallGuard.key("pkg", listOf(a)) != InstallGuard.key("other", listOf(a)))
        assertTrue(InstallGuard.key("pkg", listOf(a)) != InstallGuard.key("pkg", listOf(tmp(101))))
        assertTrue(InstallGuard.key("pkg", listOf(a)) != InstallGuard.key("pkg", listOf(a, b)))
    }

    @Test fun recentSuccessCoversSixtySecondsOnly() {
        InstallGuard.tryBegin("k", 1000)
        InstallGuard.end("k", InstallGuard.Done(true, false, "", 5000))
        assertNotNull(InstallGuard.recentSuccess(listOf("k"), 5000 + InstallGuard.RECENT_MS))
        assertNull(InstallGuard.recentSuccess(listOf("k"), 5001 + InstallGuard.RECENT_MS))
        assertNull(InstallGuard.recentSuccess(listOf("nope"), 6000))
    }

    @Test fun failedOrCancelledIsNotTreatedAsInstalled() {
        InstallGuard.end("f", InstallGuard.Done(false, false, "err", 5000))
        InstallGuard.end("c", InstallGuard.Done(false, true, "", 5000))
        assertNull(InstallGuard.recentSuccess(listOf("f", "c"), 6000))
        // но итог доступен окну, открывшемуся во время установки
        assertNotNull(InstallGuard.finished(listOf("f"), 4000))
        assertNull(InstallGuard.finished(listOf("f"), 5001))
    }

    @Test fun anyActiveSeesAnyKey() {
        InstallGuard.tryBegin("adb:k", 1000)
        assertTrue(InstallGuard.anyActive(listOf("k", "adb:k"), 1100))
        assertFalse(InstallGuard.anyActive(listOf("k"), 1100))
    }
}
