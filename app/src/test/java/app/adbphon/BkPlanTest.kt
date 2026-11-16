package app.adbphon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** 10.0 (bk3): единое окно копий: выбор содержимого, способ снятия данных, определение типа копии, что разрешено восстановить. */
class BkPlanTest {

    private val f = BkAppFacts("com.x", targetSdk = 34, allowBackup = true, debuggable = false)

    @Test fun initialFromSoftAndFromSettings() {
        val soft = BkPlan.initial(setOf("a.b", "c.d"))
        assertFalse(soft.settings); assertTrue(soft.apk); assertTrue(soft.data); assertEquals(setOf("a.b", "c.d"), soft.apps)
        val set = BkPlan.initial(emptySet())
        assertTrue(set.settings); assertFalse(set.apk); assertFalse(set.data); assertTrue(set.apps.isEmpty())
    }

    @Test fun dataWayRules() {
        assertEquals(BkDataWay.ROOT, BkPlan.dataWay(root = true, adb = true, deviceSdk = 34, f = f.copy(allowBackup = false)))
        assertEquals(BkDataWay.NO_PRIV, BkPlan.dataWay(false, false, 34, f))
        assertEquals(BkDataWay.NOT_ALLOWED, BkPlan.dataWay(false, true, 34, f.copy(allowBackup = false)))
        assertEquals(BkDataWay.SDK12, BkPlan.dataWay(false, true, 31, f.copy(targetSdk = 31)))
        assertEquals(BkDataWay.SYSTEM, BkPlan.dataWay(false, true, 31, f.copy(targetSdk = 30)))
        assertEquals(BkDataWay.SYSTEM, BkPlan.dataWay(false, true, 30, f))
        assertEquals(BkDataWay.SYSTEM, BkPlan.dataWay(false, true, 34, f.copy(debuggable = true)))
        // другое устройство по ADB: сведений нет, решает сама системная копия
        assertEquals(BkDataWay.SYSTEM, BkPlan.dataWay(false, true, 34, null))
        assertTrue(BkPlan.internalPossible(BkDataWay.ROOT)); assertTrue(BkPlan.internalPossible(BkDataWay.SYSTEM))
        assertFalse(BkPlan.internalPossible(BkDataWay.SDK12)); assertTrue(BkPlan.dataPossible(BkDataWay.SDK12)); assertFalse(BkPlan.dataPossible(BkDataWay.NO_PRIV))
    }

    @Test fun problems() {
        assertEquals(BkPlan.Problem.NOTHING, BkPlan.problem(BkWhat(false, false, false, emptySet()), false, ""))
        assertEquals(BkPlan.Problem.NO_APPS, BkPlan.problem(BkWhat(false, true, false, emptySet()), false, ""))
        // настройки отмечены, приложения нет: копия настроек всё равно делается
        assertNull(BkPlan.problem(BkWhat(true, true, false, emptySet()), false, ""))
        assertEquals(BkPlan.Problem.NEED_PASS, BkPlan.problem(BkWhat(true, false, false, emptySet()), true, ""))
        assertNull(BkPlan.problem(BkWhat(true, false, false, emptySet()), true, "x"))
    }

    @Test fun jobsSkipAppsWithoutAnything() {
        val w = BkWhat(false, apk = false, data = true, apps = setOf("b.root", "a.none"))
        val skipped = ArrayList<String>()
        val j = BkPlan.jobs(w, mapOf("b.root" to BkDataWay.ROOT, "a.none" to BkDataWay.NO_PRIV), skipped)
        assertEquals(listOf(BkJob("b.root", false, true, BkDataWay.ROOT)), j)
        assertEquals(listOf("a.none"), skipped)
        // с APK приложение остаётся, данные без доступа не снимаются
        val j2 = BkPlan.jobs(w.copy(apk = true), mapOf("b.root" to BkDataWay.ROOT, "a.none" to BkDataWay.NO_PRIV))
        assertEquals(listOf(BkJob("a.none", true, false, BkDataWay.NO_PRIV), BkJob("b.root", true, true, BkDataWay.ROOT)), j2)
        assertTrue(BkPlan.jobs(BkWhat(true, false, false, setOf("x")), emptyMap()).isEmpty())
        // только APK: способ данных не нужен
        assertEquals(listOf(BkJob("x", true, false, null)), BkPlan.jobs(BkWhat(false, true, false, setOf("x")), emptyMap()))
    }

    @Test fun restoreDefaultsAndBlocks() {
        assertEquals(true to true, BkPlan.restoreDefaults(hasApk = true, hasData = true, installed = false, installedOlder = false))
        assertEquals(false to true, BkPlan.restoreDefaults(true, true, installed = true, installedOlder = false))
        assertEquals(true to true, BkPlan.restoreDefaults(true, true, installed = true, installedOlder = true))
        assertEquals(true to false, BkPlan.restoreDefaults(true, false, installed = true, installedOlder = false))
        assertEquals(false to true, BkPlan.restoreDefaults(false, true, installed = false, installedOlder = false))

        val C = BkCompat.Code.NOT_INSTALLED
        assertEquals(BkPlan.Block.NOTHING, BkPlan.restoreBlock(emptySet(), false, false, true, true))
        assertEquals(BkPlan.Block.NO_PRIV, BkPlan.restoreBlock(emptySet(), false, true, priv = false, user0 = true))
        assertNull(BkPlan.restoreBlock(setOf(C), wantApk = true, wantData = false, priv = false, user0 = true))   // APK системным установщиком
        assertEquals(BkPlan.Block.OTHER_USER_NEEDS_PRIV, BkPlan.restoreBlock(setOf(C), true, false, priv = false, user0 = false))
        assertEquals(BkPlan.Block.NOT_INSTALLED, BkPlan.restoreBlock(setOf(C), false, true, priv = true, user0 = true))
        assertNull(BkPlan.restoreBlock(setOf(C), true, true, priv = true, user0 = true))
        assertEquals(BkPlan.Block.SIGNER_MISMATCH, BkPlan.restoreBlock(setOf(BkCompat.Code.SIGNER_MISMATCH), true, true, true, true))
        assertEquals(BkPlan.Block.USER_MISSING, BkPlan.restoreBlock(setOf(BkCompat.Code.USER_MISSING), true, false, true, false))
    }

    // ---------------------------------------------------------------- определение типа копии

    private fun abCopy(parts: List<String>, pass: CharArray? = null): ByteArray {
        val meta = BkMeta(pkg = "org.example.app", label = "Пример", versionCode = 5, user = 10, parts = parts, time = 1L)
        val body = "apk-bytes".toByteArray()
        val o = ByteArrayOutputStream()
        BkPack.write(o, pass, meta, listOf(BkPack.Part("base.apk", body.size.toLong()) { body.inputStream() }))
        return o.toByteArray()
    }

    @Test fun detectSettingsCopy() {
        val plain = "ADBPB".toByteArray() + byteArrayOf(1, 0, 0x7b)
        val enc = "ADBPB".toByteArray() + byteArrayOf(1, 1, 0, 0)
        assertEquals(setOf(BkPart.SETTINGS), BkDetect.detect("x.adbpb", plain).parts)
        assertEquals(false, BkDetect.detect("x.adbpb", plain).encrypted)
        assertEquals(true, BkDetect.detect("любое имя", enc).encrypted)
        assertTrue(BkDetect.detect("x.adbpb", enc).settings)
    }

    @Test fun detectAppCopies() {
        val both = BkDetect.detect("a.ab", abCopy(listOf("apk", "internal", "ext")))
        assertEquals(setOf(BkPart.APK, BkPart.DATA), both.parts)
        assertEquals("org.example.app", both.meta?.pkg); assertEquals(10, both.meta?.user); assertEquals(false, both.encrypted)
        assertTrue(both.app); assertFalse(both.settings)
        assertEquals(setOf(BkPart.APK), BkDetect.detect("a.ab", abCopy(listOf("apk"))).parts)
        assertEquals(setOf(BkPart.DATA), BkDetect.detect("a.ab", abCopy(listOf("ab"))).parts)
    }

    @Test fun detectEncryptedAppCopy() {
        val b = abCopy(listOf("apk", "internal"), "секрет".toCharArray())
        val noPass = BkDetect.detect("a.ab", b)
        assertEquals(true, noPass.encrypted); assertTrue(noPass.parts.isEmpty()); assertFalse(noPass.unknown); assertTrue(noPass.app)
        val wrong = BkDetect.detect("a.ab", b, "другой".toCharArray())
        assertEquals(true, wrong.encrypted); assertTrue(wrong.parts.isEmpty())
        val ok = BkDetect.detect("a.ab", b, "секрет".toCharArray())
        assertEquals(setOf(BkPart.APK, BkPart.DATA), ok.parts)
    }

    @Test fun detectForeignAndUnknown() {
        // системная копия adb backup: .ab без сведений ADB-phon внутри
        val o = ByteArrayOutputStream()
        val tar = org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(AbFormat.writer(o, null))
        val e = org.apache.commons.compress.archivers.tar.TarArchiveEntry("apps/com.x/_manifest"); val d = "m".toByteArray(); e.size = d.size.toLong()
        tar.putArchiveEntry(e); tar.write(d); tar.closeArchiveEntry(); tar.finish(); tar.close()
        val foreign = BkDetect.detect("backup.ab", o.toByteArray())
        assertTrue(foreign.foreign); assertFalse(foreign.app)
        val unk = BkDetect.detect("photo.jpg", byteArrayOf(-1, -40, -1, -32))
        assertTrue(unk.unknown); assertFalse(unk.app); assertFalse(unk.settings)
        assertTrue(BkDetect.isCopyName("ADB-phon-backup-20261116-101010.adbpb")); assertTrue(BkDetect.isCopyName("App-20261116-101010.AB"))
        assertFalse(BkDetect.isCopyName(".x.ab.part")); assertFalse(BkDetect.isCopyName("notes.txt"))
    }

    @Test fun errKinds() {
        assertEquals(BkDetect.Err.NOT_OURS, BkDetect.errKind(AbFormat.BadFormat("not an ADB-phon data copy")))
        assertEquals(BkDetect.Err.NEWER, BkDetect.errKind(AbFormat.BadFormat("newer format")))
        assertEquals(BkDetect.Err.DAMAGED, BkDetect.errKind(AbFormat.BadFormat("checksum mismatch: base.apk")))
        assertEquals(BkDetect.Err.DAMAGED, BkDetect.errKind(AbFormat.BadFormat("bad package")))
        assertEquals(BkDetect.Err.NEED_PASS, BkDetect.errKind(AbFormat.NeedPass()))
        assertEquals(BkDetect.Err.WRONG_PASS, BkDetect.errKind(AbFormat.WrongPass()))
        assertEquals(BkDetect.Err.DAMAGED, BkDetect.errKind(java.io.IOException("base.apk: file shorter than expected")))
        assertEquals(BkDetect.Err.OTHER, BkDetect.errKind(java.io.IOException("Нет места")))
    }
}
