package app.adbphon

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Главные экраны (лончеры) устройства и «Меню приложений» как запасной: перед отключением или удалением единственного
 * главного экрана программа предлагает поставить «Меню приложений»: файл скачивается с GitHub (drugoj00/app-menu).
 */
object Launchers {
    const val MENU_PKG = "app.tvapps"

    /** Пакеты, которые сейчас могут быть главным экраном (включённые компоненты HOME, без служебной заставки системы). */
    suspend fun homePackages(ctx: Context, remote: Boolean): List<String> {
        if (!remote) {
            val pm = ctx.packageManager
            return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .map { it.activityInfo }
                .filter { it.packageName != "android" && !it.name.endsWith("FallbackHome") }
                .map { it.packageName }.distinct()
        }
        val out = Adb.shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME", 30_000)
        return Regex("""([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)""").findAll(out)
            .filter { !it.groupValues[2].endsWith("FallbackHome") && it.groupValues[1] != "android" }
            .map { it.groupValues[1] }.distinct().toList()
    }

    /** Меню приложений установлено на этом телефоне (для чужого устройства проверяется через оболочку). */
    suspend fun menuInstalled(ctx: Context, remote: Boolean): Boolean =
        if (!remote) runCatching { ctx.packageManager.getPackageInfo(MENU_PKG, 0); true }.getOrDefault(false)
        else Adb.shell("pm path $MENU_PKG", 20_000).contains("package:")

    fun openMenu(ctx: Context): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(MENU_PKG) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private const val LATEST = "https://api.github.com/repos/drugoj00/app-menu/releases/latest"
    /** Прямая ссылка на выпуск 2.1: запасная, если список выпусков GitHub не ответил. */
    private const val FALLBACK = "https://github.com/drugoj00/app-menu/releases/download/v2.1/AppMenu-2.1.apk"

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "ADB-phon")
        return c
    }

    /** Ссылка на APK последнего выпуска Меню приложений. Блокирующий вызов. */
    private fun latestUrl(): String = runCatching {
        val c = open(LATEST)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) return@runCatching FALLBACK
            val assets = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONArray("assets") ?: return@runCatching FALLBACK
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", true)) return@runCatching a.optString("browser_download_url").ifBlank { FALLBACK }
            }
            FALLBACK
        } finally { c.disconnect() }
    }.getOrDefault(FALLBACK)

    /** Скачивает Меню приложений с GitHub и ставит на подключённое устройство. Возвращает итоговое сообщение. */
    suspend fun installMenu(ctx: Context): String {
        val tmp = File(ctx.cacheDir, "appmenu.apk")
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val c = open(latestUrl())
                try {
                    if (c.responseCode != 200) return@runCatching false
                    c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    true
                } finally { c.disconnect() }
            }.getOrDefault(false)
        }
        if (!ok) { tmp.delete(); return ctx.getString(R.string.lg_download_failed) }
        // перед установкой убеждаемся, что скачан именно пакет Меню приложений
        val pkg = ctx.packageManager.getPackageArchiveInfo(tmp.path, 0)?.packageName
        if (pkg != MENU_PKG) { tmp.delete(); return ctx.getString(R.string.lg_bad_file) }
        val r = tmp.inputStream().use { Adb.installApk(it, tmp.length(), "-r") }
        tmp.delete()
        return if (r.contains("Success", true)) ctx.getString(R.string.lg_done) else ctx.getString(R.string.lg_failed, r.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(120))
    }
}
