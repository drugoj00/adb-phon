package app.adbphon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvFontRulesTest {
    private val eps = 0.001f
    private val steps = listOf(0.85f, 1.0f, 1.15f, 1.3f)   // AppScale.steps

    @Test fun roles() {
        assertEquals(TvFontRules.Role.MAIN, TvFontRules.roleOf(Float.NaN))
        assertEquals(TvFontRules.Role.MAIN, TvFontRules.roleOf(14f))
        assertEquals(TvFontRules.Role.MAIN, TvFontRules.roleOf(24f))
        assertEquals(TvFontRules.Role.CAPTION, TvFontRules.roleOf(13f))
        assertEquals(TvFontRules.Role.CAPTION, TvFontRules.roleOf(11f))
        assertEquals(TvFontRules.Role.TECH, TvFontRules.roleOf(10f))
        assertEquals(TvFontRules.Role.TECH, TvFontRules.roleOf(9f))
    }

    @Test fun minimumsHoldForEveryFontSetting() {
        for (font in steps) {
            val m = TvFontRules.tvMult(font)
            for (req in listOf(Float.NaN, 9f, 10f, 11f, 12f, 13f, 14f, 15f, 16f)) {
                val eff = TvFontRules.effectiveSp(req, m)
                val min = TvFontRules.roleOf(req).minSp
                assertTrue("font=$font req=$req eff=$eff", eff + eps >= min)
            }
        }
    }

    @Test fun exactMinimumsAtNormal() {
        assertEquals(18f, TvFontRules.effectiveSp(14f, 1f), eps)
        assertEquals(18f, TvFontRules.effectiveSp(Float.NaN, 1f), eps)
        assertEquals(16f, TvFontRules.effectiveSp(12f, 1f), eps)
        assertEquals(14f, TvFontRules.effectiveSp(9f, 1f), eps)
        assertEquals(24f, TvFontRules.effectiveSp(24f, 1f), eps)   // крупное не трогаем
        assertEquals(20f, TvFontRules.sizeSp(20f, 1f), eps)
    }

    @Test fun veryLargeIsBigger() {
        val n = TvFontRules.effectiveSp(12f, TvFontRules.tvMult(1.0f))
        val xl = TvFontRules.effectiveSp(12f, TvFontRules.tvMult(1.3f))
        assertTrue(xl > n)
        assertEquals(16f * 1.3f, xl, eps)
        assertEquals(18f * 1.3f, TvFontRules.effectiveSp(14f, TvFontRules.tvMult(1.3f)), eps)
    }

    @Test fun smallSettingDoesNotShrinkBelowMinimum() {
        assertEquals(1f, TvFontRules.tvMult(0.85f), eps)
        // множитель меньше 1 (например, на телефоне с включённым PHONES_DIALOG_FONT): минимум в sp окна растёт, на экране не меньше минимума
        assertEquals(18f, TvFontRules.effectiveSp(14f, 0.85f), eps)
        assertEquals(TvFontRules.TV_FONT_MAX, TvFontRules.tvMult(5f), eps)
    }

    @Test fun lineHeight() {
        assertTrue(TvFontRules.lineSp(18f, Float.NaN).isNaN())
        assertEquals(24f, TvFontRules.lineSp(18f, 24f), eps)            // хватает: без изменений
        assertEquals(18f * 1.2f, TvFontRules.lineSp(18f, 12f), eps)     // тесно: поднимаем
    }

    @Test fun fontScale() {
        // ТВ: всегда системный * множитель, повторно то же самое (окно и основной экран)
        assertEquals(1.3f, TvFontRules.fontScale(1f, 1f, 1.3f, tv = true, phones = false), eps)
        assertEquals(1.3f, TvFontRules.fontScale(1f, 1.3f, 1.3f, tv = true, phones = false), eps)
        assertEquals(1.0f, TvFontRules.fontScale(1f, 0.85f, 0.85f, tv = true, phones = false), eps)
        // телефон: без изменений
        assertEquals(1f, TvFontRules.fontScale(1f, 1f, 1.3f, tv = false, phones = false), eps)
        // телефон с включённым PHONES_DIALOG_FONT: окну (системный масштаб) добавляется множитель, основному экрану второй раз не добавляется
        assertEquals(1.3f, TvFontRules.fontScale(1f, 1f, 1.3f, tv = false, phones = true), eps)
        assertEquals(1.3f, TvFontRules.fontScale(1f, 1.3f, 1.3f, tv = false, phones = true), eps)
    }
}
