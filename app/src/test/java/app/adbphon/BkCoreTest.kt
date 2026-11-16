package app.adbphon

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.TimeZone
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Резервные копии: формат .ab с AES-256, упаковка копии с данными, метаданные, совместимость, расписание и ротация, имена. */
class BkCoreTest {

    private fun jdkPbkdf2(pass: CharArray, salt: ByteArray, rounds: Int) =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(PBEKeySpec(pass, salt, rounds, 256)).encoded

    @Test fun pbkdf2MatchesJdk() {
        val salt = ByteArray(64) { (it * 3).toByte() }
        for (p in listOf("secret", "Пароль-фраза 123", "a")) {
            assertArrayEquals(p, jdkPbkdf2(p.toCharArray(), salt, 1000), AbFormat.pbkdf2(p.toByteArray(Charsets.UTF_8), salt, 1000, 32))
        }
    }

    /** Контрольная сумма мастер-ключа считается как в Android и abe.jar: байты со знаком как символы, PBKDF2WithHmacSHA1 Java. */
    @Test fun masterKeyChecksumMatchesJavaImplementation() {
        val mk = ByteArray(32) { (it * 37 - 100).toByte() }   // есть отрицательные байты
        val salt = ByteArray(64) { it.toByte() }
        val chars = CharArray(mk.size) { mk[it].toInt().toChar() }
        assertArrayEquals(jdkPbkdf2(chars, salt, 500), AbFormat.checksum(mk, salt, 500, 5))
    }

    private fun ab(payload: ByteArray, pass: String?): ByteArray {
        val o = ByteArrayOutputStream()
        AbFormat.writer(o, pass?.toCharArray()).use { it.write(payload) }
        return o.toByteArray()
    }

    private fun unab(b: ByteArray, pass: String?) = AbFormat.reader(ByteArrayInputStream(b), pass?.toCharArray()).readBytes()

    @Test fun abRoundTripEncrypted() {
        val data = ("tar body секрет " + "x".repeat(5000)).toByteArray()
        val enc = ab(data, "Пароль 1")
        val head = String(enc, 0, 40, Charsets.ISO_8859_1)
        assertTrue(head.startsWith("ANDROID BACKUP\n5\n1\nAES-256\n"))
        assertTrue(AbFormat.isEncrypted(ByteArrayInputStream(enc)))
        assertFalse(String(enc, Charsets.ISO_8859_1).contains("tar body"))
        assertArrayEquals(data, unab(enc, "Пароль 1"))
    }

    @Test fun abRoundTripPlain() {
        val data = "plain".toByteArray()
        val b = ab(data, null)
        assertTrue(String(b, Charsets.ISO_8859_1).startsWith("ANDROID BACKUP\n5\n1\nnone\n"))
        assertFalse(AbFormat.isEncrypted(ByteArrayInputStream(b)))
        assertArrayEquals(data, unab(b, null))
        assertArrayEquals(data, unab(b, "ignored"))
    }

    @Test fun abWrongAndMissingPassword() {
        val enc = ab("data".toByteArray(), "right")
        val copy = enc.copyOf()
        try { unab(enc, "wrong"); fail() } catch (e: AbFormat.WrongPass) {}
        try { unab(enc, null); fail() } catch (e: AbFormat.NeedPass) {}
        try { unab(enc, ""); fail() } catch (e: AbFormat.NeedPass) {}
        assertArrayEquals("file is not changed by a wrong password", copy, enc)
        assertArrayEquals("data".toByteArray(), unab(enc, "right"))
    }

    @Test fun abRejectsForeignFiles() {
        for (b in listOf("hello".toByteArray(), "ANDROID BACKUP\n9\n1\nnone\n".toByteArray(), "ANDROID BACKUP\n5\n1\nDES\n".toByteArray(), ByteArray(0))) {
            try { unab(b, null); fail() } catch (e: AbFormat.BadFormat) {}
        }
    }

    /** Файл без шифрования, сделанный как `adb backup` (заголовок + zlib), читается. */
    @Test fun readsAndroidStylePlainBackup() {
        val o = ByteArrayOutputStream()
        o.write("ANDROID BACKUP\n5\n1\nnone\n".toByteArray())
        DeflaterOutputStream(o, Deflater(Deflater.BEST_COMPRESSION)).use { it.write("apps/x/_manifest".toByteArray()) }
        assertEquals("apps/x/_manifest", String(unab(o.toByteArray(), null)))
    }

    private val meta = BkMeta(
        pkg = "com.example.app", label = "Пример: «приложение»", versionName = "1.2", versionCode = 12, targetSdk = 34, user = 95, sdk = 34,
        release = "14", model = "SM-A145F", manufacturer = "samsung", device = "a14", signer = "ab".repeat(32), mode = "root",
        parts = listOf("apk", "internal", "ext"), notes = listOf("sdk12"), time = 1_763_200_000_000L, appVersion = "10.0",
    )

    @Test fun metaRoundTrip() {
        assertEquals(meta, BkMeta.parse(meta.toBytes()))
        try { BkMeta.parse("app=Other\n".toByteArray()); fail() } catch (e: AbFormat.BadFormat) {}
    }

    private fun pack(pass: String?, parts: Map<String, ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        BkPack.write(o, pass?.toCharArray(), meta, parts.map { (n, b) -> BkPack.Part(n, b.size.toLong()) { ByteArrayInputStream(b) } })
        return o.toByteArray()
    }

    @Test fun packRoundTripAndSelfCheck() {
        val parts = linkedMapOf("base.apk" to ByteArray(70_000) { (it % 251).toByte() }, "internal.tar" to "данные".toByteArray(), "empty.tar" to ByteArray(0))
        for (pass in listOf(null, "п@ss")) {
            val f = pack(pass, parts)
            assertEquals(meta, BkPack.readMeta(ByteArrayInputStream(f), pass?.toCharArray()))
            val got = LinkedHashMap<String, ByteArray>()
            val r = BkPack.read(ByteArrayInputStream(f), pass?.toCharArray(), { n, _, s -> got[n] = s.readBytes() })
            assertEquals(meta, r.meta)
            assertEquals(parts.keys.toList(), r.parts)
            parts.forEach { (k, v) -> assertArrayEquals(k, v, got[k]) }
            // самопроверка без записи частей
            assertEquals(parts.keys.toList(), BkPack.read(ByteArrayInputStream(f), pass?.toCharArray()).parts)
        }
    }

    @Test fun packDetectsDamage() {
        val f = pack(null, mapOf("a.bin" to ByteArray(20_000) { (it * 7).toByte() }))
        // обрыв файла
        try { BkPack.read(ByteArrayInputStream(f.copyOf(f.size / 2)), null); fail() } catch (e: AbFormat.BadFormat) {}
        // испорченный байт в середине сжатых данных: ошибка распаковки или несовпадение сумм
        val bad = f.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x55).toByte() }
        try { BkPack.read(ByteArrayInputStream(bad), null); fail() } catch (e: AbFormat.BadFormat) {}
        // зашифрованный: неверный пароль до чтения данных
        val enc = pack("one", mapOf("a" to "x".toByteArray()))
        try { BkPack.read(ByteArrayInputStream(enc), "two".toCharArray()); fail() } catch (e: AbFormat.WrongPass) {}
    }

    @Test fun packRejectsPlainAndroidBackupWithoutMeta() {
        val o = ByteArrayOutputStream()
        val tar = org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(AbFormat.writer(o, null))
        val e = org.apache.commons.compress.archivers.tar.TarArchiveEntry("apps/x/_manifest"); e.size = 1
        tar.putArchiveEntry(e); tar.write(1); tar.closeArchiveEntry(); tar.close()
        try { BkPack.readMeta(ByteArrayInputStream(o.toByteArray()), null); fail() } catch (e: AbFormat.BadFormat) {}
    }

    private val good = BkTarget(installed = true, versionCode = 12, signer = "AB".repeat(32), sdk = 34, model = "SM-A145F", userExists = true, root = true, adb = true)
    private fun codes(t: BkTarget, m: BkMeta = meta) = BkCompat.check(m, t).map { it.code }.toSet()

    @Test fun compatSameDevice() {
        assertEquals(emptySet<BkCompat.Code>(), codes(good))
        assertTrue(BkCompat.canRestore(BkCompat.check(meta, good)))
    }

    @Test fun compatOtherDevice() {
        val t = good.copy(sdk = 33, model = "Pixel 7", versionCode = 10, root = false)
        val c = BkCompat.check(meta, t)
        val set = c.map { it.code }.toSet()
        assertTrue(BkCompat.Code.SDK_DIFF in set)
        assertTrue(BkCompat.Code.OTHER_DEVICE in set)
        assertTrue(BkCompat.Code.VERSION_LOWER in set)
        assertTrue(BkCompat.Code.NO_ROOT_INTERNAL in set)
        assertTrue("warnings only", BkCompat.canRestore(c))
    }

    @Test fun compatBlocks() {
        assertTrue(BkCompat.Code.SIGNER_MISMATCH in codes(good.copy(signer = "cd".repeat(32))))
        assertFalse(BkCompat.canRestore(BkCompat.check(meta, good.copy(signer = "cd".repeat(32)))))
        assertTrue(BkCompat.Code.NOT_INSTALLED in codes(good.copy(installed = false, versionCode = null, signer = null)))
        assertTrue(BkCompat.Code.USER_MISSING in codes(good.copy(userExists = false)))
        assertTrue(BkCompat.Code.NO_PRIV in codes(good.copy(root = false, adb = false)))
        assertTrue(BkCompat.Code.SIGNER_UNKNOWN in codes(good.copy(signer = null)))
        assertTrue(BkCompat.Code.VERSION_HIGHER in codes(good.copy(versionCode = 20)))
        val ab = meta.copy(parts = listOf("apk", "ab"))
        assertTrue(BkCompat.Code.AB_LIMITED in codes(good.copy(root = false), ab))
        assertFalse(BkCompat.Code.AB_LIMITED in codes(good.copy(root = false), ab.copy(targetSdk = 30)))
        assertTrue(BkCompat.Code.ONLY_APK in codes(good, meta.copy(parts = listOf("apk"))))
    }

    @Test fun scheduleDue() {
        val day = BkSchedule.DAY_MS
        val now = 100 * day
        assertFalse(BkSchedule.isDue(0, BkSchedule.OFF, now))
        assertTrue(BkSchedule.isDue(0, BkSchedule.DAY, now))
        assertTrue(BkSchedule.isDue(now - day, BkSchedule.DAY, now))
        assertTrue("slack: a few minutes early is fine", BkSchedule.isDue(now - day + 600_000, BkSchedule.DAY, now))
        assertFalse(BkSchedule.isDue(now - 3 * 3600_000, BkSchedule.DAY, now))
        assertFalse(BkSchedule.isDue(now - 3 * day, BkSchedule.WEEK, now))
        assertTrue(BkSchedule.isDue(now - 7 * day, BkSchedule.WEEK, now))
        assertTrue("clock went back", BkSchedule.isDue(now + day, BkSchedule.WEEK, now))
        assertNull(BkSchedule.next(now, BkSchedule.OFF, now))
        assertEquals(now, BkSchedule.next(0, BkSchedule.DAY, now))
        assertEquals(now - 3600_000 + day - BkSchedule.SLACK_MS, BkSchedule.next(now - 3600_000, BkSchedule.DAY, now))
        assertEquals(now, BkSchedule.next(now - 2 * day, BkSchedule.DAY, now))
    }

    @Test fun rotationDeletesOnlyOwnOldest() {
        val names = listOf(
            "ADB-phon-auto-20261101-010000.adbpb", "ADB-phon-auto-20261103-010000.adbpb", "ADB-phon-auto-20261102-010000.adbpb",
            "ADB-phon-auto-20261104-010000.adbpb", "ADB-phon-backup-20261001-010000.adbpb", "ADB-phon-auto-notes.adbpb",
            "ADB-phon-auto-com.x-u0-20261101-010000.ab", "other.txt",
        )
        assertEquals(listOf("ADB-phon-auto-20261102-010000.adbpb", "ADB-phon-auto-20261101-010000.adbpb"),
            BkSchedule.rotate(names, BkNames.AUTO_PREFIX, BkNames.SETTINGS_EXT, 2))
        assertEquals(emptyList<String>(), BkSchedule.rotate(names, BkNames.AUTO_PREFIX, BkNames.SETTINGS_EXT, 5))
        assertEquals(emptyList<String>(), BkSchedule.rotate(names, BkNames.autoDataPrefix("com.x", 0), BkNames.DATA_EXT, 1))
        assertEquals(listOf("ADB-phon-auto-com.x-u0-20261101-010000.ab"),
            BkSchedule.rotate(names + "ADB-phon-auto-com.x-u0-20261105-010000.ab", BkNames.autoDataPrefix("com.x", 0), BkNames.DATA_EXT, 1))
    }

    @Test fun names() {
        val st = BkNames.stamp(0, TimeZone.getTimeZone("UTC"))
        assertEquals("19700101-000000", st)
        assertEquals("ADB-phon-backup-19700101-000000.adbpb", BkNames.settings(st))
        assertEquals("ADB-phon-auto-19700101-000000.adbpb", BkNames.autoSettings(st))
        assertEquals("Мой_ банк-19700101-000000.ab", BkNames.data("Мой/ банк", 0, st))
        assertEquals("app-u95-19700101-000000.ab", BkNames.data("  ", 95, st))
        assertEquals("copy.ab", BkNames.withExt("copy", ".ab"))
        assertEquals("copy.AB", BkNames.withExt("copy.AB", ".ab"))
        assertEquals("a_b.adbpb", BkNames.withExt("a:b", ".adbpb"))
        val taken = setOf("x.ab", "x-1.ab")
        assertEquals("x-2.ab", BkNames.unique("x.ab") { it in taken })
        assertEquals("y.ab", BkNames.unique("y.ab") { it in taken })
    }

    @Test fun places() {
        val a = BkPlace(true, "/storage/107D-CBFB/Backups")
        assertEquals(a, BkPlace.decode(a.encode()))
        val b = BkPlace(false, "/Копии", "cloud", "gdrive1", "me@example.com (Google Drive)")
        assertEquals(b, BkPlace.decode(b.encode()))
        assertNull(BkPlace.decode(""))
        assertNull(BkPlace.decode("local\trelative"))
        assertEquals("/sdcard/x.ab", BkPlace(true, "/sdcard").child("x.ab"))
        assertEquals("smb://pc/share/x.ab", BkPlace(false, "smb://pc/share/", "smb", "pc").child("x.ab"))
    }

    @Test fun users() {
        val out = "Users:\n\tUserInfo{0:Владелец:c13} running\n\tUserInfo{95:DUAL_APP:20001010} running\n\tUserInfo{10:Guest:404}\n"
        val u = BkUsers.parse(out)
        assertEquals(listOf(0, 10, 95), u.map { it.id })
        assertEquals("DUAL_APP", u.last().name)
        assertTrue(u.first().running)
        assertFalse(u[1].running)
        assertNotNull(BkUsers.parse("garbage"))
    }

    // ---- bk2: простой путь копий (запомненная папка, список для восстановления, «что внутри»)

    @Test fun stampFromNames() {
        assertEquals("20261115-143005", BkNames.stampOf("ADB-phon-backup-20261115-143005.adbpb"))
        assertEquals("20261115-143005", BkNames.stampOf("ADB-phon-backup-20261115-143005-2.adbpb"))
        assertEquals("20261101-010000", BkNames.stampOf("ADB-phon-auto-com.x-u95-20261101-010000.ab"))
        assertNull(BkNames.stampOf("copy.adbpb"))
        // имя новой копии всегда с датой и проходит обратно через разбор
        val st = BkNames.stamp(1763216000000L, TimeZone.getTimeZone("UTC"))
        assertEquals(st, BkNames.stampOf(BkNames.settings(st)))
        assertEquals(st, BkNames.stampOf(BkNames.data("Telegram", 95, st)))
    }

    @Test fun listNewestFirstOnlyOwnKind() {
        fun e(n: String, size: Long = 10, date: String = "", dir: Boolean = false) = FsEntry(n, "/x/$n", dir, size, date)
        val all = listOf(
            e("ADB-phon-backup-20261110-090000.adbpb"),
            e("ADB-phon-auto-20261115-010000.adbpb"),
            e("ADB-phon-backup-20261112-120000-1.adbpb"),
            e("my.adbpb", date = "2026-11-14 08:00"),
            e("old.ADBPB", date = "2025-01-01 00:00"),
            e("nodate.adbpb", date = "14 Nov"),
            e(".ADB-phon-backup-20261116-000000.adbpb.part"),   // временный файл записи
            e("empty.adbpb", size = 0),
            e("Data", dir = true),
            e("Telegram-20261115-100000.ab"),
            e("notes.txt"),
        )
        assertEquals(listOf("ADB-phon-auto-20261115-010000.adbpb", "my.adbpb", "ADB-phon-backup-20261112-120000-1.adbpb", "ADB-phon-backup-20261110-090000.adbpb", "old.ADBPB", "nodate.adbpb"),
            BkList.of(all, BkNames.SETTINGS_EXT).map { it.name })
        assertEquals(listOf("Telegram-20261115-100000.ab"), BkList.of(all, BkNames.DATA_EXT).map { it.name })
        assertEquals(emptyList<String>(), BkList.of(emptyList(), BkNames.DATA_EXT).map { it.name })
        assertEquals("15.11.2026 14:30", BkList.timeText(e("ADB-phon-backup-20261115-143005.adbpb")))
        assertEquals("2026-11-14 08:00", BkList.timeText(e("my.adbpb", date = "2026-11-14 08:00")))
    }

    @Test fun insideFromNameAndText() {
        val f = BkInside.Fmt({ s, l, c -> "$s/$l/$c" }, { a, u -> "$a u$u" }, "enc", "auto")
        val d1 = BkInside.fromName("Telegram-u95-20261115-100000.ab")
        assertEquals(BkInside(true, label = "Telegram", user = 95), d1)
        assertEquals("Telegram u95", d1.text(f))
        assertEquals(BkInside(true, label = "Мой банк", user = 0), BkInside.fromName("Мой банк-20261115-100000-2.ab"))
        val auto = BkInside.fromName("ADB-phon-auto-com.x-u10-20261101-010000.ab")
        assertEquals(BkInside(true, auto = true, label = "com.x", user = 10), auto)
        assertEquals("com.x u10, auto", auto.text(f))
        assertEquals("plain", BkInside.fromName("plain.ab").label)
        val s = BkInside.fromName("ADB-phon-backup-20261115-143005.adbpb")
        assertFalse(s.data); assertFalse(s.auto)
        assertEquals("", s.text(f))   // до чтения файла «что внутри» неизвестно: строка показывает имя
        assertTrue(BkInside.fromName("ADB-phon-auto-20261115-010000.adbpb").auto)
        assertEquals("120/3/2", s.copy(encrypted = false, settings = 120, links = 3, clouds = 2).text(f))
        assertEquals("enc", s.copy(encrypted = true).text(f))
        assertEquals("Telegram u95, enc", d1.copy(encrypted = true).text(f))
    }
}
