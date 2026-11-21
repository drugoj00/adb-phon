package app.adbphon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 17.0 (код 83): язык устройства среди частей набора и разбор манифеста частей (на настоящем наборе JK BMS, если папка есть; иначе пропуск). */
class ApkSetsTest {
    @Test fun missingLanguageRules() {
        val xapk = listOf("com.jktech.bms.apk", "config.arm64_v8a.apk", "config.en.apk", "config.mdpi.apk")
        assertEquals("ru", Bundles.missingLanguage(xapk, "ru"))
        assertNull(Bundles.missingLanguage(xapk, "en"))
        assertNull(Bundles.missingLanguage(listOf("base.apk", "split_config.ru.apk", "split_config.en.apk"), "ru"))
        assertNull(Bundles.missingLanguage(listOf("base.apk", "split_config.arm64_v8a.apk"), "ru"))
        assertNull(Bundles.missingLanguage(listOf("base.apk", "split_config.in.apk"), "id"))
    }

    @Test fun manifestHeadOfRealSet() {
        val dir = File(System.getenv("APK_SET_DIR") ?: "C:/Загрузки/JK_BMS_6.4.05_APK")
        assumeTrue(dir.isDirectory)
        val base = ApkIconRes.manifestHead(File(dir, "base.apk").path)
        assertNotNull(base)
        assertNull(base!!.split)
        val en = ApkIconRes.manifestHead(File(dir, "split_config.en.apk").path)
        assertNotNull(en)
        assertEquals(base.pkg, en!!.pkg)
        assertEquals("config.en", en.split)
    }
}
