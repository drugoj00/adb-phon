package app.adbphon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Откат входа в облако без системного WebView: окно программы только для проверенных облаков, по https и при наличии WebView. */
class CloudWebTest {
    @Test fun yandexWithWebViewUsesOwnWindow() = assertTrue(CloudWeb.shouldUse("yandex", "https://oauth.yandex.ru/x", true))
    @Test fun noWebViewFallsBackToExternalBrowser() {
        assertFalse(CloudWeb.shouldUse("yandex", "https://oauth.yandex.ru/x", false))
        assertFalse(CloudWeb.shouldUse(null, "https://oauth.yandex.ru/x", false))
    }
    @Test fun googleNeverUsesWebView() {
        assertFalse(CloudWeb.shouldUse("drive", "https://accounts.google.com/o/oauth2/auth", true))
        assertFalse(CloudWeb.shouldUse("google photos", "https://accounts.google.com/o/oauth2/auth", true))
    }
    @Test fun plainHttpNeverUsesWebView() = assertFalse(CloudWeb.shouldUse("yandex", "http://example.com", true))
    @Test fun wizardPagesWithoutTypeFollowWebViewPresence() {
        assertTrue(CloudWeb.shouldUse(null, "https://oauth.yandex.ru/client/new", true))
        assertFalse(CloudWeb.shouldUse(null, "https://oauth.yandex.ru/client/new", false))
    }
}
