package app.adbphon

import app.adbphon.TermLogic.End
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Консоль: причина конца сеанса, решение о переподключении, текущая папка по приглашению (10.0, wad72). */
class TermLogicTest {
    @Test fun exitCommands() {
        assertTrue(TermLogic.isExitCmd("exit"))
        assertTrue(TermLogic.isExitCmd("  exit 0 "))
        assertTrue(TermLogic.isExitCmd("logout"))
        assertTrue(TermLogic.isExitCmd("ls\nexit"))
        assertFalse(TermLogic.isExitCmd("cd /"))
        assertFalse(TermLogic.isExitCmd("echo exit"))
        assertFalse(TermLogic.isExitCmd("exitcode"))
    }

    @Test fun endReasons() {
        val now = 100_000L
        assertEquals(End.USER_EXIT, TermLogic.endReason(now - 500, 0, now, true, "adb", true))
        assertEquals(End.USER_EXIT, TermLogic.endReason(0, now - 1000, now, true, "root", true))
        // exit давно, потом обрыв: не ожидаемо
        assertEquals(End.LINK_LOST, TermLogic.endReason(now - 60_000, 0, now, true, "adb", false))
        assertEquals(End.SHELL_DIED, TermLogic.endReason(0, 0, now, true, "adb", true))
        assertEquals(End.SHELL_DIED, TermLogic.endReason(0, 0, now, true, "root", false))
        assertEquals(End.OPEN_FAILED, TermLogic.endReason(0, 0, now, false, "adb", false))
    }

    @Test fun decisions() {
        val exit = TermLogic.decide(End.USER_EXIT, 0, true)
        assertTrue(exit.showEnded); assertFalse(exit.reconnect)
        val lost = TermLogic.decide(End.LINK_LOST, 0, true)
        assertFalse(lost.showEnded); assertTrue(lost.reconnect); assertEquals(1500L, lost.delayMs)
        assertEquals(3000L, TermLogic.decide(End.LINK_LOST, 1, true).delayMs)
        val tooMany = TermLogic.decide(End.SHELL_DIED, TermLogic.MAX_AUTO, true)
        assertFalse(tooMany.reconnect); assertTrue(tooMany.button)
        val nothing = TermLogic.decide(End.LINK_LOST, 0, false)
        assertFalse(nothing.reconnect); assertTrue(nothing.button); assertFalse(nothing.showEnded)
    }

    @Test fun recentAttempts() {
        assertEquals(2, TermLogic.recent(listOf(10_000L, 50_000L, 69_000L), 80_000L))
    }

    @Test fun cwdFromPrompt() {
        // строки из сообщения wad72
        assertEquals("/", TermLogic.cwdFromText("RE6079L1:/sdcard \$ \nRE6079L1:/ \$ "))
        assertEquals("/sdcard/Download", TermLogic.cwdFromText("ls\nfile.txt\nRE6079L1:/sdcard/Download \$ "))
        assertEquals("/data/local/tmp", TermLogic.cwdFromText("a14xm:/data/local/tmp # "))
        assertNull(TermLogic.cwdFromText("total 0\ndrwx 2 root"))
    }

    @Test fun cdCommandQuotes() {
        assertEquals("cd '/sdcard/My '\\''Files'", TermLogic.cdCmd("/sdcard/My 'Files"))
    }
}
