package app.adbphon

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 14.0 (владелица: «папки открываются с задержкой»): после установки или обновления через `adb install` и установщик Android программа остаётся в состоянии «verify»
 * (код не компилируется, пока не пройдёт ночное обслуживание), и на слабой приставке раскладка и отрисовка списков идут в 2–3 раза медленнее
 * (замеры: раскрытие 12 строк 330–440 мс против 160–200 мс, галочка 140–230 мс против 70–120 мс).
 * Если у программы уже есть привилегированный доступ (root или ADB, подключённые заранее), один раз на версию в фоне запускается
 * `cmd package compile -m speed-profile -f <пакет>`: около 12 секунд работы системы, данные и настройки не трогаются.
 * Без привилегий и подключений ничего не делается и ничего не запрашивается; результат виден после следующего запуска программы.
 */
object SelfCompile {
    suspend fun maybe(ctx: Context) {
        val vc = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0L)
        val sp = ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE)
        if (vc == 0L || sp.getLong("selfcompile_vc", 0L) == vc) return
        if (!Adb.isConnected) return
        sp.edit().putLong("selfcompile_vc", vc).apply()
        withContext(Dispatchers.IO) { runCatching { Adb.shell("cmd package compile -m speed-profile -f " + ctx.packageName + " 2>&1", 300_000, fast = true) } }
    }
}
