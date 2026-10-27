package app.adbphon

import android.content.Context
import java.io.File

/** Хранит копии прошлых версий самого ADB-phon, чтобы можно было откатиться после обновления. */
object VersionKeeper {
    private const val KEEP = 5

    fun dir(ctx: Context): File = (ctx.getExternalFilesDir("versions") ?: File(ctx.filesDir, "versions")).also { it.mkdirs() }

    /** Файлы прошлых версий (все .apk в хранилище, кроме текущей версии): сколько и сколько байт. */
    fun oldFiles(ctx: Context): List<File> {
        val cur = runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
            "ADB-phon-${pi.versionName}-$code.apk"
        }.getOrNull()
        return (dir(ctx).listFiles { x -> x.name.endsWith(".apk") } ?: emptyArray()).filter { it.name != cur }
    }

    /** Удаляет файлы прошлых версий; текущая версия остаётся. Возвращает число удалённых файлов. */
    fun clearOld(ctx: Context): Int = oldFiles(ctx).count { runCatching { it.delete() }.getOrDefault(false) }

    /** При каждом запуске: если текущей версии ещё нет в хранилище, копируем её APK (старые удаляем, оставляя 5). */
    fun backupSelf(ctx: Context) {
        Thread {
            runCatching {
                val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
                val f = File(dir(ctx), "ADB-phon-${pi.versionName}-$code.apk")
                if (!f.exists()) {
                    File(ctx.applicationInfo.sourceDir).copyTo(f)
                    AppLog.i("ADBphon", "сохранена копия версии ${pi.versionName} (код $code)")
                }
                dir(ctx).listFiles { x -> x.name.startsWith("ADB-phon-") && !x.name.startsWith("ADB-phon-github-") && x.name.endsWith(".apk") }
                    ?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
            }.onFailure { AppLog.e("ADBphon", "backupSelf", it) }
        }.start()
    }
}
