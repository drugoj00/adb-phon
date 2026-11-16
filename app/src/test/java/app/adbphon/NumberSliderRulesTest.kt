package app.adbphon

import app.adbphon.NumberSliderRules.NumUnit
import app.adbphon.NumberSliderRules.Spec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberSliderRulesTest {
    @Test fun scaledRoundTrip() {
        val s = NumberSliderRules.AIR_SENS
        assertEquals(0.3f, s.sliderRange.start, 1e-6f)
        assertEquals(3f, s.sliderRange.endInclusive, 1e-6f)
        assertEquals(150, NumberSliderRules.toShown(1.5f, s))
        assertEquals(1.25f, NumberSliderRules.toStored(125, s), 1e-6f)
        for (n in s.min..s.max) assertEquals(n, NumberSliderRules.toShown(NumberSliderRules.toStored(n, s), s))
    }

    @Test fun clampAndRound() {
        val s = NumberSliderRules.PAD_DEAD
        assertEquals(12, NumberSliderRules.toShown(0.1249f, s))
        assertEquals(40, NumberSliderRules.toShown(0.9f, s))
        assertEquals(0, NumberSliderRules.toShown(-1f, s))
        assertEquals(0, NumberSliderRules.toShown(Float.NaN, s))
        assertEquals(0.4f, NumberSliderRules.toStored(99, s), 1e-6f)
        assertEquals(0f, NumberSliderRules.toStored(-5, s), 1e-6f)
    }

    @Test fun unscaled() {
        val s = NumberSliderRules.TERM_FONT
        assertEquals(9f..24f, s.sliderRange)
        assertEquals(13, NumberSliderRules.toShown(13.4f, s))
        assertEquals(24f, NumberSliderRules.toStored(30, s), 0f)
        assertEquals(360, NumberSliderRules.toShown(360f, NumberSliderRules.HUE))
    }

    @Test fun allSpecsMatchOldSliderRanges() {
        // прежние valueRange ползунков должны совпасть, иначе сдвинутся сохранённые значения
        fun r(s: Spec) = s.sliderRange.start to s.sliderRange.endInclusive
        assertEquals(0f to 100f, r(NumberSliderRules.RING_VOL))
        assertEquals(0f to 1f, r(NumberSliderRules.SCREEN_VOL))
        assertEquals(0f to 100f, r(NumberSliderRules.BAR_CONTRAST))
        assertEquals(0f to 0.4f, r(NumberSliderRules.PAD_DEAD))
        assertEquals(0.3f to 3f, r(NumberSliderRules.PAD_CSPEED))
        assertEquals(0.7f to 1.3f, r(NumberSliderRules.PAD_SIZE))
        assertEquals(0.3f to 1f, r(NumberSliderRules.PAD_ALPHA))
        assertEquals(10f to 60f, r(NumberSliderRules.TILT_ANGLE))
        assertEquals(0f to 0.3f, r(NumberSliderRules.TILT_DEAD))
        assertEquals(0f to 0.9f, r(NumberSliderRules.TILT_SMOOTH))
        assertEquals(0f to 2f, r(NumberSliderRules.RUMBLE_GAIN))
    }

    @Test fun customChoice() {
        assertFalse(NumberSliderRules.isCustom(30, NumberSliderRules.TRASH_DAYS_PRESETS))
        assertFalse(NumberSliderRules.isCustom(0, NumberSliderRules.TRASH_DAYS_PRESETS))
        assertTrue(NumberSliderRules.isCustom(14, NumberSliderRules.TRASH_DAYS_PRESETS))
        assertTrue(NumberSliderRules.isCustom(7, NumberSliderRules.BK_KEEP_PRESETS))
        assertEquals(1, NumberSliderRules.startValue(0, NumberSliderRules.TRASH_MB))
        assertEquals(700, NumberSliderRules.startValue(700, NumberSliderRules.TRASH_MB))
        assertEquals(1, NumberSliderRules.startValue(99, NumberSliderRules.BK_KEEP))
        // готовые варианты сами лежат в диапазоне своего значения (кроме 0 = «Без лимита»)
        for ((spec, presets) in listOf(
            NumberSliderRules.TRASH_DAYS to NumberSliderRules.TRASH_DAYS_PRESETS,
            NumberSliderRules.TRASH_MB to NumberSliderRules.TRASH_MB_PRESETS,
            NumberSliderRules.BK_KEEP to NumberSliderRules.BK_KEEP_PRESETS,
            NumberSliderRules.VPN_IDLE to NumberSliderRules.VPN_IDLE_PRESETS,
        )) for (p in presets) if (p != 0) assertTrue("$p", p in spec.range)
    }

    @Test fun seek() {
        val s = NumberSliderRules.seek(185_900)
        assertEquals(0..185, s.range)
        assertEquals(NumUnit.SEC, s.unit)
        assertEquals(65_000L, NumberSliderRules.seekMs(65))
        assertEquals(0L, NumberSliderRules.seekMs(-3))
        assertEquals(0..0, NumberSliderRules.seek(-1).range)
    }
}
