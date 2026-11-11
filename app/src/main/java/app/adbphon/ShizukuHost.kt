package app.adbphon

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Parcel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.parcelablelist.ParcelableListSlice
import rikka.shizuku.Shizuku
import rikka.shizuku.server.ServerConstants

/**
 * 7.0: совместимость с Shizuku. ADB-phon запускает сервер Shizuku (rikka.shizuku.server.ShizukuService из этого же APK, процесс
 * adbphon_shizuku) от имени shell или root, а сама выступает «менеджером»: принимает binder сервера через ShizukuProvider,
 * показывает окно разрешения приложениям и список выданных доступов. Приложения с Shizuku API (App Manager, Hail, Swift Backup...)
 * получают права shell как у настоящего Shizuku. Собственный помощник ADB-phon (порт 38247) от этого не зависит.
 */
object ShizukuHost {
    private const val FLAG_ALLOWED = 1 shl 1
    private const val FLAG_DENIED = 1 shl 2
    private const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED
    private const val PROC = "adbphon_shizuku"
    /** Завершает уже запущенный сервер(ы): процесс называется ровно adbphon_shizuku (pkill -x на Samsung его не находил, были два сервера). */
    private const val KILL = "ps -A -o PID,ARGS | grep -E '^ *[0-9]+ +adbphon_shizuku\$' | while read p r; do kill \$p; done;"

    /** Меняется при приходе/потере binder: окна перерисовываются. */
    var tick by mutableIntStateOf(0)
    var up by mutableStateOf(false)
        private set
    private var inited = false

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE)

    /** Включать сервер сам после перезагрузки: по умолчанию включено, но действует только если сервер хоть раз запускали. */
    fun auto(ctx: Context) = sp(ctx).getBoolean("shz_auto", true)
    fun setAuto(ctx: Context, v: Boolean) { sp(ctx).edit().putBoolean("shz_auto", v).apply() }
    fun everStarted(ctx: Context) = sp(ctx).getBoolean("shz_ever", false)

    @Synchronized
    fun init() {
        if (inited) return
        inited = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky { up = true; tick++ }
            Shizuku.addBinderDeadListener { up = false; tick++ }
        }
    }

    fun running(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** Версия API сервера и uid, под которым он работает (2000 = shell, 0 = root). */
    fun info(): String? = runCatching {
        if (!Shizuku.pingBinder()) null else "API ${Shizuku.getVersion()} · uid ${Shizuku.getUid()}"
    }.getOrNull()

    private fun startCommand(ctx: Context): String {
        val apk = ctx.applicationInfo.sourceDir
        return "$KILL sleep 0.5; " +
            "nohup setsid /system/bin/app_process -Djava.class.path='$apk' /system/bin --nice-name=$PROC " +
            "rikka.shizuku.server.ShizukuService >/dev/null 2>&1 </dev/null & sleep 2; echo started"
    }

    /** Запуск сервера командой через ADB-phon (shell или root). Возвращает текст результата для пользователя (пусто = успех). */
    suspend fun start(ctx: Context): String = withContext(Dispatchers.IO) {
        init()
        if (!Adb.isConnected) return@withContext "no_adb"
        if (running()) return@withContext ""
        val r = runCatching { Adb.shell(startCommand(ctx), 20_000L) }
        if (r.isFailure) return@withContext "fail"
        repeat(40) { if (running()) { sp(ctx).edit().putBoolean("shz_ever", true).apply(); tick++; return@withContext "" }; delay(250) }
        "no_answer"
    }

    suspend fun stop(ctx: Context) = withContext(Dispatchers.IO) {
        runCatching { if (running()) Shizuku.exit() }
        delay(300)
        if (Adb.isConnected) runCatching { Adb.shell("$KILL true", 8_000L) }
        up = false; tick++
    }

    /** После перезагрузки: root (если уже давали) либо отладка по Wi-Fi (Android 11+); true — повторять попытку не нужно. */
    suspend fun boot(ctx: Context): Boolean {
        val c = ctx.applicationContext
        init()
        if (!auto(c) || !everStarted(c)) return true
        if (running()) return true
        if (c.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getBoolean("root_ok", false) && !Adb.isRoot) Adb.tryRoot()
        if (!Adb.isRoot) {
            if (android.os.Build.VERSION.SDK_INT < 30 || !WifiDebug.canWrite(c)) return true   // автозапуск без root невозможен
            runCatching { android.provider.Settings.Global.putInt(c.contentResolver, "adb_wifi_enabled", 1) }
            delay(1500)
            if (!Adb.isConnected) Adb.connectWireless(c, null)
            if (!Adb.isConnected) return false
        }
        val r = start(c)
        if (r.isNotEmpty()) return false
        if (!Adb.isRoot && AdbServer.offAfter) runCatching { android.provider.Settings.Global.putInt(c.contentResolver, "adb_wifi_enabled", 0) }
        return true
    }

    // ---------------------------------------------------------------- доступы приложений

    /** Приложения, которым доступ выдан или которые его просят (список даёт сам сервер). */
    suspend fun apps(): List<PackageInfo> = withContext(Dispatchers.IO) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService")
            data.writeInt(-1)
            Shizuku.getBinder()!!.transact(ServerConstants.BINDER_TRANSACTION_getApplications, data, reply, 0)
            reply.readException()
            @Suppress("UNCHECKED_CAST")
            (ParcelableListSlice.CREATOR.createFromParcel(reply) as ParcelableListSlice<PackageInfo>).list ?: emptyList()
        } catch (e: Throwable) { AppLog.e("ADBphon", "shizuku apps", e); emptyList() }
        finally { reply.recycle(); data.recycle() }
    }

    fun granted(uid: Int): Boolean = runCatching { (Shizuku.getFlagsForUid(uid, MASK_PERMISSION) and FLAG_ALLOWED) == FLAG_ALLOWED }.getOrDefault(false)
    fun grant(uid: Int) { runCatching { Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, FLAG_ALLOWED) } }
    fun revoke(uid: Int) { runCatching { Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, 0) } }
}
