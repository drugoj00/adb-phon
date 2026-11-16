package app.adbphon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 10.0 (mouse): скорость броска мышью при медленной отрисовке (точки раз в 50-200 мс). */
class MouseFlingTest {
    @Test fun fastFramesGiveSpeed() {
        // точки каждые 10 мс, 20 px за точку: 2000 px/с вверх
        val ts = (0..10).map { 1000L + it * 10 }
        val ys = (0..10).map { 800f - it * 20f }
        assertEquals(-2000f, MouseFling.velocity(ts, ys, 1105), 1f)
    }

    @Test fun slowFramesStillGiveSpeed() {
        // как на приставке: события раз в 200 мс, стандартный счётчик тут даёт ноль
        val ts = listOf(14642063L, 14642256L, 14642488L)
        val ys = listOf(700f, 507.8f, 261f)
        val v = MouseFling.velocity(ts, ys, 14642496L)
        assertTrue("v=$v", v < -900f)
    }

    @Test fun stoppedBeforeReleaseNoFling() {
        val ts = listOf(0L, 10L, 20L)
        val ys = listOf(300f, 200f, 100f)
        assertEquals(0f, MouseFling.velocity(ts, ys, 400), 0f)
    }

    @Test fun singlePointNoFling() = assertEquals(0f, MouseFling.velocity(listOf(5L), listOf(1f), 6), 0f)

    @Test fun clampedToMax() {
        val v = MouseFling.velocity(listOf(0L, 10L), listOf(0f, 1000f), 12)
        assertEquals(MouseFling.MAX, v, 0f)
    }
}
