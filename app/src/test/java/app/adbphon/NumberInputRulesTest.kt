package app.adbphon

import app.adbphon.NumberInputRules.Check
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberInputRulesTest {
    @Test fun parse() {
        assertEquals(7, NumberInputRules.parse("007"))
        assertEquals(42, NumberInputRules.parse(" 42 "))
        assertEquals(-5, NumberInputRules.parse("-5"))
        assertNull(NumberInputRules.parse(""))
        assertNull(NumberInputRules.parse("-"))
        assertNull(NumberInputRules.parse("1a"))
        assertNull(NumberInputRules.parse("99999999999"))
    }

    @Test fun checkRange() {
        assertEquals(Check.Ok(1), NumberInputRules.check("1", 1, 300))
        assertEquals(Check.Ok(300), NumberInputRules.check("300", 1, 300))
        assertEquals(Check.OutOfRange(0), NumberInputRules.check("0", 1, 300))
        assertEquals(Check.OutOfRange(301), NumberInputRules.check("301", 1, 300))
        assertEquals(Check.Empty, NumberInputRules.check("  ", 1, 300))
        assertEquals(Check.NotNumber, NumberInputRules.check("x", 1, 300))
        assertTrue(NumberInputRules.canApply("150", 50, 300))
        assertFalse(NumberInputRules.canApply("49", 50, 300))
        assertFalse(NumberInputRules.canApply("", 50, 300))
    }

    @Test fun errorOnlyForTypedBadText() {
        assertFalse(NumberInputRules.isError("", 1, 10))
        assertFalse(NumberInputRules.isError("5", 1, 10))
        assertTrue(NumberInputRules.isError("11", 1, 10))
        assertTrue(NumberInputRules.isError("0", 1, 10))
    }

    @Test fun clean() {
        assertEquals("123", NumberInputRules.clean("1 2,3", 1, 300))
        assertEquals("300", NumberInputRules.clean("3000", 1, 300))   // не длиннее границы
        assertEquals("5", NumberInputRules.clean("-5", 1, 300))      // минус не нужен
        assertEquals("-5", NumberInputRules.clean("-5", -10, 10))
        assertEquals("-5", NumberInputRules.clean("-5-", -10, 10))
        assertEquals("", NumberInputRules.clean("abc", 0, 9))
    }

    @Test fun stepIsConstantAndClamped() {
        assertEquals(125, NumberInputRules.step(100, 1, 25, 50, 300))
        assertEquals(75, NumberInputRules.step(100, -1, 25, 50, 300))
        assertEquals(300, NumberInputRules.step(290, 1, 25, 50, 300))
        assertEquals(50, NumberInputRules.step(60, -1, 25, 50, 300))
        assertEquals(Int.MAX_VALUE, NumberInputRules.step(Int.MAX_VALUE, 1, 1, 0, Int.MAX_VALUE))
        assertEquals(2, NumberInputRules.step(1, 1, 0, 0, 5))   // шаг не меньше 1
        assertTrue(NumberInputRules.canDec(2, 1)); assertFalse(NumberInputRules.canDec(1, 1))
        assertTrue(NumberInputRules.canInc(9, 10)); assertFalse(NumberInputRules.canInc(10, 10))
    }

    @Test fun units() {
        assertEquals("300 с", NumberInputRules.withUnit(300, "с"))
        assertEquals("150%", NumberInputRules.withUnit(150, "%"))
        assertEquals("7", NumberInputRules.withUnit(7, ""))
        assertEquals("1" to "300 с", NumberInputRules.rangeArgs(1, 300, "с"))
        assertEquals(3, NumberInputRules.maxLen(1, 300))
        assertEquals(3, NumberInputRules.maxLen(-10, 5))
    }
}
