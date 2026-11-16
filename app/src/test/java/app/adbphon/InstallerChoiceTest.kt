package app.adbphon

import app.adbphon.InstallerChoice.Decision
import app.adbphon.InstallerChoice.Opt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Окно «Установить через…»: когда ставить сразу запомненным, когда показывать окно и куда ставить фокус. */
class InstallerChoiceTest {
    private val withAdb = listOf(Opt("adb", true), Opt("session", true), Opt("system", true), Opt("cmp:io.github.muntashirakon.AppManager/x.Installer", true), Opt("archive", true))
    private val noAdb = listOf(Opt("adb", false), Opt("session", true), Opt("system", true), Opt("archive", true))
    private val bundleNoAdb = listOf(Opt("adb", false), Opt("session", true), Opt("system", false), Opt("archive", true))

    @Test fun askEveryTimeShowsWindowFocusOnFirstAvailable() {
        assertEquals(Decision.Show(0, false), InstallerChoice.decide(withAdb, null))
        assertEquals(Decision.Show(1, false), InstallerChoice.decide(noAdb, null))
        assertEquals(Decision.Show(1, false), InstallerChoice.decide(noAdb, "ask"))
    }

    @Test fun savedAndAvailableInstallsAtOnce() {
        assertEquals(Decision.Auto("adb"), InstallerChoice.decide(withAdb, "adb"))
        assertEquals(Decision.Auto("system"), InstallerChoice.decide(noAdb, "system"))
    }

    @Test fun savedButUnavailableShowsWindowAgain() {
        // запомнен ADB-phon тихо, а связи нет: окно с пометкой, фокус на первом доступном
        assertEquals(Decision.Show(1, true), InstallerChoice.decide(noAdb, "adb"))
        // системный не ставит пакеты из частей
        assertEquals(Decision.Show(1, true), InstallerChoice.decide(bundleNoAdb, "system"))
        // запомненную программу удалили: её нет в списке
        assertEquals(Decision.Show(1, true), InstallerChoice.decide(noAdb, "cmp:com.gone/x.A"))
    }

    @Test fun forceAlwaysShowsWindowWithFocusOnSaved() {
        assertEquals(Decision.Show(2, false), InstallerChoice.decide(withAdb, "system", force = true))
        assertEquals(Decision.Show(0, false), InstallerChoice.decide(withAdb, null, force = true))
    }

    @Test fun sameExternalInstallerMatchesByPackage() {
        assertTrue(InstallerChoice.matches("cmp:io.github.muntashirakon.AppManager/old.Activity", "cmp:io.github.muntashirakon.AppManager/x.Installer"))
        assertFalse(InstallerChoice.matches("cmp:a.b/c", "cmp:a.bc/c"))
        assertEquals(Decision.Auto("cmp:io.github.muntashirakon.AppManager/x.Installer"), InstallerChoice.decide(withAdb, "cmp:io.github.muntashirakon.AppManager/old.Activity"))
    }

    @Test fun archiveIsNeverRememberedNorFocusedFirst() {
        assertFalse(InstallerChoice.rememberable("archive"))
        assertNull(InstallerChoice.afterPick(null, "archive", true))
        assertEquals("adb", InstallerChoice.afterPick("adb", "archive", true))
        // всё недоступно, кроме архива: фокус на архиве
        assertEquals(3, InstallerChoice.focusIndex(listOf(Opt("adb", false), Opt("session", false), Opt("system", false), Opt("archive", true)), null))
        assertEquals(-1, InstallerChoice.focusIndex(listOf(Opt("adb", false)), null))
    }

    @Test fun rememberCheckbox() {
        assertEquals("system", InstallerChoice.afterPick(null, "system", true))
        assertNull(InstallerChoice.afterPick(null, "system", false))
        // галочка снята: прежний выбор остаётся (вернётся, когда снова станет доступен)
        assertEquals("adb", InstallerChoice.afterPick("adb", "session", false))
        assertEquals("session", InstallerChoice.afterPick("adb", "session", true))
    }

    @Test fun migrationFromOldSettings() {
        // «Спрашивать» было включено (по умолчанию): окно, как и раньше
        assertNull(InstallerChoice.migrate(true, listOf("system", null, null, null)))
        // выключено: запомненное для .apk, затем для пакетов; «Открыть как архив» не переносится
        assertEquals("system", InstallerChoice.migrate(false, listOf("system", "adb", null, null)))
        assertEquals("cmp:a.b/c", InstallerChoice.migrate(false, listOf("archive", "ask", "cmp:a.b/c", null)))
        // выключено и ничего не запомнено: прежний способ без окна, ADB-phon
        assertEquals("adb", InstallerChoice.migrate(false, listOf(null, "ask", null, null)))
    }

    @Test fun normalize() {
        assertNull(InstallerChoice.normalize(""))
        assertNull(InstallerChoice.normalize("ask"))
        assertNull(InstallerChoice.normalize("market"))
        assertEquals("adb", InstallerChoice.normalize(" adb "))
        assertEquals("a.b", InstallerChoice.pkgOf("cmp:a.b/c.D"))
        assertNull(InstallerChoice.pkgOf("system"))
    }
}
