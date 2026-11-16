package app.adbphon

import kotlinx.coroutines.launch
import java.io.File

/**
 * 10.0 (fm1): «Папка данных» приложения (Android/data/<пакет>) из меню приложения в «Файлах».
 * Было: проверка File.isDirectory, а на Android 11+ обычному приложению папки Android/data не видны вовсе, поэтому при подключённом ADB
 * всё равно показывалось «Папка данных недоступна без ADB». Теперь: если папка закрыта системой, она открывается через root или ADB
 * (как Android/data в панели), а если открыть нельзя, говорится точная причина.
 */
object FmAppData {
    fun open(other: FmPane, pkg: String) {
        val d = AppActions.dataDir(pkg)
        Fm.scope.launch {
            val restricted = FmIo.restricted(d)
            if (!restricted) {
                if (File(d).isDirectory) { Fm.setSource(other, "local"); Fm.load(other, d) } else Fm.say(Fm.str(R.string.fm1_data_none))
                return@launch
            }
            if (!FmIo.privEnabled) { Fm.say(Fm.str(R.string.fm1_data_priv_off)); return@launch }
            // ADB смотрит на другое устройство: Android/data этого телефона через него не открыть
            if (FmIo.remote && !Adb.isRoot) { Fm.say(Fm.str(R.string.fm1_data_remote)); return@launch }
            val fix = runCatching { FmIo.ensurePriv() }.getOrDefault("pair")
            if (fix != null) { Fm.say(Fm.str(R.string.fm1_data_closed, android.os.Build.VERSION.RELEASE)); return@launch }
            val q = "'" + d.replace("'", "'\\''") + "'"
            val out = runCatching { Adb.shell("[ -d $q ] && echo DIR_OK || echo DIR_NO", 15_000) }.getOrDefault("")
            when {
                out.contains("DIR_OK") -> { Fm.setSource(other, "local"); Fm.load(other, d) }   // ограниченная папка: панель сама переходит на root/ADB
                out.contains("DIR_NO") -> Fm.say(Fm.str(R.string.fm1_data_none))
                else -> Fm.say(Fm.str(R.string.fm1_data_closed, android.os.Build.VERSION.RELEASE))
            }
        }
    }
}
