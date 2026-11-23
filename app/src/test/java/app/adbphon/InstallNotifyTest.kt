package app.adbphon

import app.adbphon.InstallNotify.St
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 22.11.2026 (для 18.0): переходы состояний и частота обновлений уведомления об установке (без Android). */
class InstallNotifyTest {
    @Test fun percentByBytes() {
        assertEquals(0, InstallNotify.percent(0, 100))
        assertEquals(50, InstallNotify.percent(512, 1024))
        assertEquals(100, InstallNotify.percent(2000, 1000))   // больше целого не даёт больше 100
        assertEquals(0, InstallNotify.percent(5, 0))           // пустой размер без деления на ноль
        assertEquals(0, InstallNotify.percent(-5, 100))
    }

    @Test fun fullHappyPath() {
        var s = St.TRANSFER
        s = InstallNotify.next(s, 1); assertEquals(St.TRANSFER, s)
        s = InstallNotify.next(s, 2); assertEquals(St.INSTALLING, s)    // сессия закрыта
        s = InstallNotify.next(s, 4); assertEquals(St.WAIT, s)           // открыто системное окно
        s = InstallNotify.onSystemProgress(s); assertEquals(St.INSTALLING, s)   // нажато «Установить»
        assertEquals(St.OK, InstallNotify.finalState(0))
    }

    @Test fun waitIsNotRolledBackByPhase2() {
        assertEquals(St.WAIT, InstallNotify.next(St.WAIT, 2))
        assertEquals(St.WAIT, InstallNotify.next(St.WAIT, 1))
    }

    @Test fun systemProgressOnlyMovesWait() {
        assertEquals(St.TRANSFER, InstallNotify.onSystemProgress(St.TRANSFER))   // ход записи не считается ответом в окне
        assertEquals(St.INSTALLING, InstallNotify.onSystemProgress(St.INSTALLING))
        assertEquals(St.OK, InstallNotify.onSystemProgress(St.OK))
    }

    @Test fun finalStatesAreFinal() {
        for (f in listOf(St.OK, St.FAIL, St.CANCEL)) {
            assertTrue(InstallNotify.isFinal(f))
            for (ph in 1..4) assertEquals(f, InstallNotify.next(f, ph))
            assertEquals(f, InstallNotify.onSystemProgress(f))
        }
        assertFalse(InstallNotify.isFinal(St.WAIT))
    }

    @Test fun resultMapping() {
        assertEquals(St.OK, InstallNotify.finalState(0))
        assertEquals(St.CANCEL, InstallNotify.finalState(3))
        assertEquals(St.CANCEL, InstallNotify.finalState(null))
        assertEquals(St.FAIL, InstallNotify.finalState(1))
        assertEquals(St.FAIL, InstallNotify.finalState(-1))
        assertEquals(St.FAIL, InstallNotify.finalState(-2))
    }

    @Test fun throttleInsideTransfer() {
        assertTrue(InstallNotify.shouldPost(null, -1, 0, St.TRANSFER, 0, 1000))                 // первое — сразу
        assertFalse(InstallNotify.shouldPost(St.TRANSFER, 10, 1000, St.TRANSFER, 11, 1200))     // рано (200 мс)
        assertTrue(InstallNotify.shouldPost(St.TRANSFER, 10, 1000, St.TRANSFER, 11, 1400))      // можно (400 мс и +1 %)
        assertFalse(InstallNotify.shouldPost(St.TRANSFER, 10, 1000, St.TRANSFER, 10, 5000))     // процент тот же
    }

    @Test fun stateChangeAlwaysPostsAndStaticStatesDoNot() {
        assertTrue(InstallNotify.shouldPost(St.TRANSFER, 99, 1000, St.WAIT, 100, 1001))
        assertFalse(InstallNotify.shouldPost(St.WAIT, 100, 1000, St.WAIT, 100, 9000))
        assertFalse(InstallNotify.shouldPost(St.INSTALLING, 100, 1000, St.INSTALLING, 100, 9000))
        assertTrue(InstallNotify.shouldPost(St.INSTALLING, 100, 1000, St.FAIL, 100, 1001))
    }

    @Test fun timeoutsAndIds() {
        assertEquals(10_000L, InstallNotify.timeoutMs(St.OK))
        assertTrue(InstallNotify.timeoutMs(St.FAIL) <= 60_000L)        // после ошибки уведомление не залипает
        assertTrue(InstallNotify.timeoutMs(St.WAIT) >= 60_000L)
        val a = InstallNotify.notifId("pkg|1|10")
        assertEquals(a, InstallNotify.notifId("pkg|1|10"))              // тот же ключ — то же уведомление
        assertTrue(a in 7000..7999)
        assertTrue(InstallNotify.notifId(Int.MIN_VALUE.toString()) in 7000..7999)
    }
}
