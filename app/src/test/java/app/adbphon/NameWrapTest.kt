package app.adbphon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 20.11.2026: перенос длинных имён после разделителей, расширение остаётся с последним фрагментом, длинное имя сокращается в середине. */
class NameWrapTest {
    private val z = "​"

    @Test fun breaksAfterSeparatorsNotBeforeExtension() {
        assertEquals("Smart${z}Tube_${z}stable_${z}32.71_${z}universal.apk", NameWrap.fmt("SmartTube_stable_32.71_universal.apk"))
        assertEquals("ADB-phon-13.0.apk", NameWrap.fmt("ADB-phon-13.0.apk"))   // дефис Compose переносит сам, цифры вокруг точки не рвутся
    }

    @Test fun camelCaseAndBrackets() {
        assertEquals("My${z}Holiday${z}Photo", NameWrap.fmt("MyHolidayPhoto"))
        assertEquals("a(${z}1).txt", NameWrap.fmt("a(1).txt"))   // перед расширением разрыва нет
    }

    @Test fun veryLongNameIsShortenedInMiddleKeepingExtension() {
        val n = "A".repeat(60) + "_middle_part_" + "B".repeat(40) + "universal.apk"
        val r = NameWrap.fmt(n).replace(z, "")
        assertTrue(r.length <= NameWrap.MAX)
        assertTrue(r.contains("…"))
        assertTrue(r.endsWith("universal.apk"))
        assertTrue(r.startsWith("AAAA"))
    }

    @Test fun shortNameUnchangedWithoutSeparators() {
        assertEquals("notes", NameWrap.fmt("notes"))
        assertFalse(NameWrap.fmt("plain.txt").contains(z))
    }
}
