package app.adbphon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAccountsTest {
    private val tok = "{\"access_token\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}"
    private fun conn(name: String, title: String) = NetConn("cloud", name, 0, title, "")

    @Test fun sameEmailSameNameWhateverTheToken() {
        val a = CloudAccounts.name("drive", "me@gmail.com", tok)
        val b = CloudAccounts.name("drive", "me@gmail.com", tok.replace("A", "B"))
        assertEquals(a, b)
        assertTrue(a.startsWith("acc_") && a.length == 14)
    }
    @Test fun differentEmailOrTypeGivesDifferentName() {
        val a = CloudAccounts.name("drive", "me@gmail.com", tok)
        assertNotEquals(a, CloudAccounts.name("drive", "other@gmail.com", tok))
        assertNotEquals(a, CloudAccounts.name("dropbox", "me@gmail.com", tok))
    }
    @Test fun legacyNameStaysTheSame() {
        // имя старых записей: sha1("drive:почта").take(10): повторный вход не должен менять имя
        assertEquals("acc_" + CloudAccounts.sha1("drive:me@gmail.com").take(10), CloudAccounts.name("drive", "me@gmail.com", tok))
    }
    @Test fun blankEmailFallsBackToToken() {
        assertEquals(CloudAccounts.name("pcloud", null, tok), CloudAccounts.name("pcloud", "  ", tok))
        assertNotEquals(CloudAccounts.name("pcloud", null, tok), CloudAccounts.name("pcloud", null, tok.replace("A", "B")))
    }
    @Test fun loginRequiredForMainClouds() {
        for (t in listOf("drive", "onedrive", "dropbox", "yandex", "box")) {
            assertTrue(CloudAccounts.needLogin(t, null)); assertTrue(CloudAccounts.needLogin(t, "")); assertFalse(CloudAccounts.needLogin(t, "a@b.c"))
        }
        assertFalse(CloudAccounts.needLogin("pcloud", null))
    }
    @Test fun reAddReplacesInsteadOfDuplicating() {
        val n = CloudAccounts.name("drive", "me@gmail.com", tok)
        val other = conn(CloudAccounts.name("drive", "x@gmail.com", tok), "x@gmail.com (Google Диск)")
        val first = conn(n, "Аккаунт (Google Диск)")      // старая запись без логина
        val list = listOf(other, first)
        val again = conn(n, "me@gmail.com (Google Диск)")
        assertTrue(CloudAccounts.exists(list, again))
        val res = CloudAccounts.upsert(list, again)
        assertEquals(2, res.size)
        assertEquals(1, res.count { it.host == n })
        assertEquals("me@gmail.com (Google Диск)", res.first { it.host == n }.user)
        assertTrue(res.any { it.host == other.host })
    }
    @Test fun newAccountIsAdded() {
        val list = listOf(conn("acc_1111111111", "a@b.c (Google Диск)"))
        val c = conn("acc_2222222222", "d@e.f (Google Диск)")
        assertFalse(CloudAccounts.exists(list, c))
        assertEquals(2, CloudAccounts.upsert(list, c).size)
    }
    @Test fun nonCloudKeyedByKindHostUser() {
        val a = NetConn("smb", "pc", 0, "u", "p"); val b = NetConn("smb", "pc", 0, "other", "p")
        assertEquals(2, CloudAccounts.upsert(listOf(a), b).size)
        assertEquals(1, CloudAccounts.upsert(listOf(a), a.copy(pass = "new")).size)
    }
    @Test fun confSectionReplacedNotAppended() {
        var cfg = ""
        cfg = CloudAccounts.confReplace(cfg, "acc_1", "drive", "{t1}", listOf("scope" to "drive"))
        cfg = CloudAccounts.confReplace(cfg, "acc_2", "dropbox", "{t2}", emptyList())
        cfg = CloudAccounts.confReplace(cfg, "acc_1", "drive", "{t1new}", listOf("scope" to "drive"))
        assertEquals(1, Regex("(?m)^\\[acc_1\\]").findAll(cfg).count())
        assertEquals(1, Regex("(?m)^\\[acc_2\\]").findAll(cfg).count())
        assertTrue(cfg.contains("token = {t1new}")); assertFalse(cfg.contains("token = {t1}\n"))
        assertTrue(cfg.contains("token = {t2}"))
    }
    @Test fun revokedAccessRelogin() {
        // (г) доступ отозвали, вход тем же аккаунтом: то же имя, раздел заменён новым токеном, запись одна
        val n = CloudAccounts.name("drive", "me@gmail.com", "{old}")
        var cfg = CloudAccounts.confReplace("", n, "drive", "{old}", emptyList())
        cfg = CloudAccounts.confReplace(cfg, CloudAccounts.name("drive", "me@gmail.com", "{new}"), "drive", "{new}", emptyList())
        assertEquals(1, Regex("(?m)^\\[acc_").findAll(cfg).count()); assertTrue(cfg.contains("token = {new}"))
    }
}

class TreeOpenTest {
    private val own = listOf("/sdcard", "/storage/ABCD-1234")
    @Test fun folderCollapsesItsSubfolders() {
        val open = listOf("L|/sdcard", "L|/sdcard/A", "L|/sdcard/A/B", "L|/sdcard/AB", "L|/sdcard/C")
        assertEquals(listOf("L|/sdcard/A/B"), TreeOpen.inside(open, "L|/sdcard/A", own))     // «AB» и «C» не вложены
    }
    @Test fun deviceCollapsesAllLevels() {
        val open = listOf("L|/sdcard", "L|/sdcard/A", "L|/sdcard/A/B", "L|/storage/ABCD-1234/X")
        assertEquals(listOf("L|/sdcard/A", "L|/sdcard/A/B"), TreeOpen.inside(open, "L|/sdcard", own))
    }
    @Test fun rootRowDoesNotTouchOwnStorage() {
        val open = listOf("L|/", "L|/etc", "L|/sdcard", "L|/sdcard/A", "L|/storage/ABCD-1234/X")
        assertEquals(listOf("L|/etc"), TreeOpen.inside(open, "L|/", own))
    }
    @Test fun networkNodeCollapsesItsFoldersAndTrash() {
        val acc = "N|cloud|acc_1|me@gmail.com (Google Диск)|"
        val open = listOf(acc, acc + "\u0001root", "N|cloud|acc_1|me@gmail.com (Google Диск)|/Docs", "N|cloud|acc_2|x|/Docs", "T|rid1", "T|rid2", "L|/sdcard/Docs")
        val r = TreeOpen.inside(open, acc, own) { if (it == acc) "rid1" else null }
        assertEquals(listOf(acc + "\u0001root", "N|cloud|acc_1|me@gmail.com (Google Диск)|/Docs", "T|rid1"), r)
    }
    @Test fun smbFolderInsideShare() {
        val s = "N|smb|pc|u|smb://pc/share/"
        val open = listOf(s, "N|smb|pc|u|smb://pc/share/a/", "N|smb|pc|u|smb://pc/share/a/b/", "N|smb|pc|u|smb://pc/share/c/")
        assertEquals(listOf("N|smb|pc|u|smb://pc/share/a/b/"), TreeOpen.inside(open, "N|smb|pc|u|smb://pc/share/a/", own))
    }
    @Test fun notFolderGivesNothing() { assertTrue(TreeOpen.inside(listOf("L|/a"), "cloud", own).isEmpty()) }
}
