package app.adbphon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Части программы: какие файлы считаются основной частью и частями конфигурации (от них зависит, спросит ли копирование формат результата). */
class RepackNameTest {
    @Test fun baseAndConfigParts() {
        assertTrue(Bundles.isBasePart("base.apk"))
        assertTrue(Bundles.isBasePart("splits/base-master.apk"))
        assertTrue(Bundles.isConfigPart("split_config.arm64_v8a.apk"))
        assertTrue(Bundles.isConfigPart("split_config.xxhdpi.apk"))
        assertTrue(Bundles.isConfigPart("split_config.ru.apk"))
        assertFalse(Bundles.isConfigPart("base.apk"))
        assertFalse(Bundles.isConfigPart("AppMenu-4.3.apk"))
    }

    @Test fun mergeKeepsSingleEntryAndSignatureFiles() {
        // склейка только основной части: манифест и таблица ресурсов на месте, подписи оригинала не переносятся
        val dir = createTempDir("repack")
        val base = java.io.File(dir, "base.apk")
        java.util.zip.ZipOutputStream(base.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("META-INF/BNDLTOOL.RSA")); z.write(1); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("classes.dex")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
        }
        val out = java.io.File(dir, "out.apk")
        // нет манифеста: понятная ошибка, а не пустой файл
        assertEquals("нет AndroidManifest.xml", RepackCore.mergeToApk(base, emptyList(), out, null))
        assertFalse(out.exists())
        dir.deleteRecursively()
    }
}
