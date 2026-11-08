package app.adbphon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoverTimerTest {
    @Test fun fires_once_per_hold_and_resets_on_target_change() {
        val t = HoverTimer()
        assertFalse(t.tick("a", 0, 800))
        assertFalse(t.tick("a", 799, 800))
        assertTrue(t.tick("a", 800, 800))
        assertFalse(t.tick("a", 900, 800))          // после срабатывания отсчёт заново
        assertTrue(t.tick("a", 1600, 800))          // держим дальше: срабатывает снова (раскрытие вглубь)
        assertFalse(t.tick("b", 1700, 800))         // другая цель: сброс
        assertFalse(t.tick("b", 2400, 800))
        assertTrue(t.tick("b", 2500, 800))
    }

    @Test fun tab_switch_uses_shorter_delay_and_empty_key_never_fires() {
        val t = HoverTimer()
        assertFalse(t.tick("tab", 0, 600)); assertTrue(t.tick("tab", 600, 600))
        assertFalse(t.tick("", 700, 600)); assertFalse(t.tick("", 5000, 600))
    }

    @Test fun stale_drag_is_detected() {
        assertFalse(dndStale(1000, 15_000)); assertTrue(dndStale(1000, 21_001))
    }
}
