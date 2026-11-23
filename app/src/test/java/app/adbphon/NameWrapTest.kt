package app.adbphon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 20.11.2026 (обновлено 23.11.2026 под rv40): разрыв разрешён (U+200B) после _ - ( ) [ ] + , / . (кроме точки между цифрами), между строчной и заглавной буквой и перед «.расширением»;
 * внутри расширения и сразу после его точки стоит склеиватель U+2060, поэтому .apk/.zip/.sha256 не рвутся. Только отображение: имя, буфер, переименование не меняются.
 */
class NameWrapTest {
    private val z = "​"   // можно перенести
    private val w = "⁠"   // нельзя переносить
    private fun ext(e: String) = "." + w + e.dropLast(1).map { "$it$w" }.joinToString("") + e.last()   // .⁠a⁠p⁠k
    private fun plain(s: String) = s.replace(z, "").replace(w, "")

    @Test fun breaksAfterSeparatorsAndBeforeExtension() {
        assertEquals("Smart${z}Tube_${z}stable_${z}32.71_${z}universal$z${ext("apk")}", NameWrap.fmt("SmartTube_stable_32.71_universal.apk"))
        assertEquals("ADB-${z}phon-${z}13.0$z${ext("apk")}", NameWrap.fmt("ADB-phon-13.0.apk"))   // цифры вокруг точки не рвутся, после дефиса разрыв есть
    }

    @Test fun camelCaseAndBrackets() {
        assertEquals("My${z}Holiday${z}Photo", NameWrap.fmt("MyHolidayPhoto"))
        assertEquals("a(${z}1)$z${ext("txt")}", NameWrap.fmt("a(1).txt"))   // перед расширением один разрыв, внутри расширения ни одного
    }

    @Test fun extensionNeverBreaksAndTextIsUnchanged() {
        for (n in listOf("app-release_v1.2.apk", "backup.2026.zip", "ADB-phon-18.0.apk.sha256", "Some_File(1).zip")) {
            val r = NameWrap.fmt(n)
            assertEquals(n, plain(r))   // видимые символы те же: ничего не потеряно и не добавлено
            val e = n.substringAfterLast('.')
            val tail = r.substring(r.lastIndexOf("."))
            assertFalse("разрыв внутри расширения: $n", tail.contains(z))
            assertTrue(tail.contains(w))
            assertEquals(".$e", plain(tail))
        }
    }

    @Test fun veryLongNameIsShortenedInMiddleKeepingExtension() {
        val n = "A".repeat(60) + "_middle_part_" + "B".repeat(40) + "universal.apk"
        val r = plain(NameWrap.fmt(n))
        assertTrue(r.length <= NameWrap.MAX)
        assertTrue(r.contains("…"))
        assertTrue(r.endsWith("universal.apk"))
        assertTrue(r.startsWith("AAAA"))
    }

    @Test fun shortNameUnchangedWithoutSeparators() {
        assertEquals("notes", NameWrap.fmt("notes"))
        assertEquals("plain$z${ext("txt")}", NameWrap.fmt("plain.txt"))   // разрыв только перед расширением
        assertFalse(NameWrap.fmt("plain.txt").substringBefore(".").contains(w))
    }
}
