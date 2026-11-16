package app.adbphon

import android.content.Context
import java.io.File

/**
 * Окна проверки для разработчика (BackupTestActivity, ArcTestActivity, AndroidTestActivity, NetTestActivity) доступны оболочке adb, а значит и другим программам.
 * Поэтому они работают только при метке `stands_on` в папке данных программы (её создаёт `adb shell touch /sdcard/Android/data/app.adbphon/files/stands_on`; чужая программа туда писать не может).
 */
object StandGuard {
    fun allowed(ctx: Context): Boolean = runCatching { File(ctx.getExternalFilesDir(null), "stands_on").exists() }.getOrDefault(false)
}
