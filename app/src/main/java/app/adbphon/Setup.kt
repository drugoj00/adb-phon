package app.adbphon

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Один шаг первого запуска: что включить, зачем и что не будет работать без этого. */
class SetupStep(val key: String, val title: Int, val why: Int, val visible: (Context) -> Boolean, val ok: (Context) -> Boolean, val go: (Activity) -> Unit)

/**
 * Первый запуск: окна по порядку, каждое с короткой причиной и «что не заработает». «Включить» открывает нужную страницу
 * системы, после возврата проверка идёт сама и показывается следующий шаг. «Пропустить» откладывает шаг до следующего открытия
 * «Проверить разрешения» (Настройки). Мастер сам не открывается повторно, когда пройден.
 */
object Setup {
    var open by mutableStateOf(false)
    /** Растёт, когда разрешение выдано без ухода из программы (через ADB): окно мастера перепроверяет шаги. */
    var bump by mutableIntStateOf(0)
    /** Первое автоподключение ADB: один раз коротко объясняем, что сейчас откроются настройки Android; дальше без вопросов. */
    var adbAsk by mutableStateOf(false)
    /** Окно «нужно разрешение» в момент запуска функции: показывается, когда пользователь пропустил первый запуск, а функции разрешение нужно. */
    var askKey by mutableStateOf<String?>(null)
    private val lastAsk = HashMap<String, Long>()

    /** true, если разрешение есть. Иначе (не чаще раза в 15 секунд на разрешение) показывает окно с пояснением и кнопкой «Включить»; вызывающий код продолжает без него или ждёт возврата. */
    fun need(ctx: Context, key: String): Boolean {
        val st = steps.firstOrNull { it.key == key } ?: return true
        if (!st.visible(ctx) || st.ok(ctx)) return true
        val now = System.currentTimeMillis()
        if (askKey == null && now - (lastAsk[key] ?: 0L) > 15_000L) { lastAsk[key] = now; askKey = key }
        return false
    }
    /** Параметры разработчика выключены: показываем, как их включить, и после включения продолжаем подключение сами. */
    var devAsk by mutableStateOf(false)
    /** Если задан, мастер показывает только этот шаг (например, служба спецвозможностей отключилась после обновления программы). */
    var only: String? = null
    fun sp0(ctx: Context) = sp(ctx)
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE)
    fun done(ctx: Context) = sp(ctx).getBoolean("setup_done_v1", false)
    fun markDone(ctx: Context) { sp(ctx).edit().putBoolean("setup_done_v1", true).apply() }

    /** Есть ли в системе экран, который откроет это действие (на ТВ-приставках многих страниц настроек нет: кнопка «Включить» молчала). */
    private fun canOpen(a: Activity, i: Intent): Boolean {
        val ri = a.packageManager.resolveActivity(i, 0) ?: return false
        return ri.activityInfo != null && ri.activityInfo.packageName != "android"   // «android» = ResolverActivity, то есть обработчика нет
    }

    /** Открывает страницу системы; если её нет на этом устройстве, выдаёт разрешение через ADB (если он подключён) или говорит, что делать. */
    private fun page(a: Activity, action: String, withPkg: Boolean = true, adbCmd: String? = null) {
        val i = Intent(action).apply { if (withPkg) data = Uri.parse("package:" + a.packageName); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (canOpen(a, i) && runCatching { a.startActivity(i) }.isSuccess) return
        val det = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (adbCmd == null && canOpen(a, det) && runCatching { a.startActivity(det) }.isSuccess) return
        if (adbCmd != null) {
            fallbackScope.launch {
                val ok = Adb.isConnected && runCatching { Adb.shell(adbCmd.replace("%pkg", a.packageName), 10_000L) }.isSuccess
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (ok) bump++
                    // ADB не подключён: на приставках есть общий список «Экономия заряда батареи», открываем его
                    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (!ok && action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS && canOpen(a, list) && runCatching { a.startActivity(list) }.isSuccess) return@withContext
                    android.widget.Toast.makeText(a, a.getString(if (ok) R.string.su_via_adb else R.string.su_no_page), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        } else android.widget.Toast.makeText(a, a.getString(R.string.su_no_page), android.widget.Toast.LENGTH_LONG).show()
    }
    private val fallbackScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    val steps: List<SetupStep> = listOf(
        SetupStep("files", R.string.su_files_t, R.string.su_files_w, { true },
            { c -> if (Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager() else c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED },
            { a -> if (Build.VERSION.SDK_INT >= 30) page(a, Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION) else a.requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 7702) }),
        SetupStep("notif", R.string.su_notif_t, R.string.su_notif_w, { Build.VERSION.SDK_INT >= 33 },
            { c -> c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED },
            { a -> a.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7703) }),
        SetupStep("acc", R.string.su_acc_t, R.string.su_acc_w, { Build.VERSION.SDK_INT >= 30 },
            { c -> AutoPairService.ensureEnabled(c) },
            { a -> a.getSharedPreferences("adbphon", Context.MODE_PRIVATE).edit().putBoolean("acc_wanted", true).apply(); page(a, Settings.ACTION_ACCESSIBILITY_SETTINGS, withPkg = false) }),
        SetupStep("overlay", R.string.su_overlay_t, R.string.su_overlay_w, { true },
            { c -> runCatching { Settings.canDrawOverlays(c) }.getOrDefault(false) },
            { a -> page(a, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, adbCmd = "appops set %pkg SYSTEM_ALERT_WINDOW allow") }),
        SetupStep("battery", R.string.su_batt_t, R.string.su_batt_w, { true },
            { c -> runCatching { (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName) }.getOrDefault(false) },
            { a -> page(a, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, adbCmd = "dumpsys deviceidle whitelist +%pkg") }),
        SetupStep("install", R.string.su_install_t, R.string.su_install_w, { true },
            { c -> c.packageManager.canRequestPackageInstalls() },
            { a -> page(a, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) }),
        SetupStep("bt", R.string.su_bt_t, R.string.su_bt_w, { Build.VERSION.SDK_INT >= 31 },
            { c -> c.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED },
            { a -> a.requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), 7704) }),
        SetupStep("write", R.string.su_write_t, R.string.su_write_w, { true },
            { c -> runCatching { Settings.System.canWrite(c) }.getOrDefault(false) },
            { a -> page(a, Settings.ACTION_MANAGE_WRITE_SETTINGS) }),
    )
}

@Composable
fun FirstRunSetup() {
    val ctx = LocalContext.current
    val act = remember { PermFlow.findActivity(ctx) } ?: run { Setup.open = false; return }
    var tick by remember { mutableIntStateOf(0) }
    val skipped = remember { mutableStateListOf<String>() }
    // шаги, где «Включить» уже нажимали, а разрешения всё ещё нет: показываем подсказку про «ограниченные настройки»
    val tried = remember { mutableStateListOf<String>() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val ob = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(ob)
        onDispose { owner.lifecycle.removeObserver(ob) }
    }
    @Suppress("UNUSED_EXPRESSION") tick
    @Suppress("UNUSED_EXPRESSION") Setup.bump
    val all = Setup.steps.filter { it.visible(act) && (Setup.only == null || it.key == Setup.only) }
    val todo = all.filter { !it.ok(act) }
    val cur = todo.firstOrNull { it.key !in skipped }
    if (cur == null) {
        // всё выдано или пропущено: пройденный мастер больше сам не открывается
        if (Setup.only == null) Setup.markDone(act)
        Setup.only = null; Setup.open = false
        return
    }
    val n = all.indexOf(cur) + 1
    LAlertDialog(
        onDismissRequest = { skipped.add(cur.key) },
        title = { Text(stringResource(R.string.su_title, n, all.size, stringResource(cur.title)), fontSize = 17.sp) },
        text = {
            Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(cur.why), fontSize = 14.sp)
                if (cur.key in tried) {
                    Text(stringResource(R.string.su_retry), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    TextButton({ val di = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + act.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); val ri = act.packageManager.resolveActivity(di, 0); if (ri?.activityInfo != null && ri.activityInfo.packageName != "android") runCatching { act.startActivity(di) } else android.widget.Toast.makeText(act, act.getString(R.string.su_no_page), android.widget.Toast.LENGTH_LONG).show() }) { Text(stringResource(R.string.su_open_app)) }
                }
            }
        },
        confirmButton = { TextButton({ if (cur.key !in tried) tried.add(cur.key); cur.go(act) }) { Text(stringResource(R.string.su_enable)) } },
        dismissButton = { TextButton({ skipped.add(cur.key) }) { Text(stringResource(R.string.su_skip), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f)) } },
    )
}

@Composable
fun PermAsk() {
    val key = Setup.askKey ?: return
    val step = Setup.steps.firstOrNull { it.key == key } ?: run { Setup.askKey = null; return }
    val ctx = LocalContext.current
    val act = remember { PermFlow.findActivity(ctx) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val ob = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME && step.ok(ctx)) Setup.askKey = null }
        owner.lifecycle.addObserver(ob)
        onDispose { owner.lifecycle.removeObserver(ob) }
    }
    LAlertDialog(
        onDismissRequest = { Setup.askKey = null },
        title = { Text(stringResource(step.title).replaceFirstChar { it.uppercase() }, fontSize = 17.sp) },
        text = { Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(stringResource(step.why), fontSize = 14.sp) } },
        confirmButton = { TextButton({ if (act != null) step.go(act) }) { Text(stringResource(R.string.su_enable)) } },
        dismissButton = { TextButton({ Setup.askKey = null }) { Text(stringResource(R.string.su_adb_no), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f)) } },
    )
}

@Composable
fun AdbFirstAsk() {
    val ctx = LocalContext.current
    LAlertDialog(
        onDismissRequest = { Setup.adbAsk = false },
        title = { Text(stringResource(R.string.su_adb_t), fontSize = 17.sp) },
        text = { Text(stringResource(R.string.su_adb_w), fontSize = 14.sp) },
        confirmButton = { TextButton({ Setup.adbAsk = false; Setup.sp0(ctx).edit().putBoolean("adb_auto_ok", true).apply(); Pairing.startAutoFlow(ctx) }) { Text(stringResource(R.string.su_adb_go)) } },
        dismissButton = { TextButton({ Setup.adbAsk = false }) { Text(stringResource(R.string.su_adb_no), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f)) } },
    )
}

@Composable
fun DevOptionsAsk() {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val ob = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) }.getOrDefault(0) == 1) {
                Setup.devAsk = false; Pairing.startAutoFlow(ctx)   // включили: подключение продолжается само
            }
        }
        owner.lifecycle.addObserver(ob)
        onDispose { owner.lifecycle.removeObserver(ob) }
    }
    LAlertDialog(
        onDismissRequest = { Setup.devAsk = false },
        title = { Text(stringResource(R.string.dev_t), fontSize = 17.sp) },
        text = { Text(stringResource(R.string.dev_w), fontSize = 14.sp) },
        confirmButton = { TextButton({ runCatching { ctx.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text(stringResource(R.string.dev_open)) } },
        dismissButton = { TextButton({ Setup.devAsk = false }) { Text(stringResource(R.string.su_adb_no), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f)) } },
    )
}

@Composable
fun WifiDebugCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var on by remember { mutableStateOf(WifiDebug.isOn(ctx)) }
    var busy by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { on = WifiDebug.isOn(ctx); kotlinx.coroutines.delay(1000) } }
    androidx.compose.material3.Card(Modifier.fillMaxWidth().clickable { Pairing.openWifiPage(ctx) }) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.wd_title), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(on, { v -> if (!busy) { busy = true; scope.launch { WifiDebug.set(ctx, v); on = WifiDebug.isOn(ctx); busy = false } } })
            }
            Text(stringResource(R.string.wd_hint_direct), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
        }
    }
}

/** Прямое включение и выключение «Отладки по Wi-Fi» (Settings.Global.adb_wifi_enabled). */
object WifiDebug {
    fun isOn(ctx: Context) = runCatching { Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1 }.getOrDefault(false)
    fun canWrite(ctx: Context) = ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    /** Пользователь сам выключил отладку по Wi-Fi переключателем: сами её больше не включаем, пока он не включит её снова (или не нажмёт строку «Включить отладку»). */
    fun userOff(ctx: Context) = ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getBoolean("wd_user_off", false)
    fun setUserOff(ctx: Context, v: Boolean) { ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE).edit().putBoolean("wd_user_off", v).apply() }

    /** Возвращает true, если действие выполнено сразу; false, если запущен путь через настройки Android (служба сама нажмёт переключатель). */
    suspend fun set(ctx: Context, on: Boolean): Boolean {
        setUserOff(ctx, !on)
        if (canWrite(ctx)) { runCatching { Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", if (on) 1 else 0) }; return true }
        if (!on && Adb.isConnected && Adb.currentHost.isEmpty()) {
            runCatching { Adb.shell("settings put global adb_wifi_enabled 0") }
            return true
        }
        if (Build.VERSION.SDK_INT >= 30 && AutoPairService.isEnabled(ctx)) { Pairing.startAutoFlowSwitch(ctx, on); return false }
        runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return false
    }
}

/** Автоподключение ADB для любого места программы, где оно нужно: root, затем беспроводная отладка; если нужно сопряжение, запускается автоматический путь. */
object AutoAdb {
    /** true, если связь есть (или появилась сразу). false: связи нет, при возможности запущено автосопряжение. */
    suspend fun ensure(): Boolean {
        if (Adb.isConnected) return true
        val fix = FmIo.ensurePriv() ?: return true
        if (fix == "pair" || fix == "wifi") Fm.autoPairOnce()
        return false
    }

    /** Текст для пользователя, когда связи нет. */
    fun hint(): String = Fm.str(if (Build.VERSION.SDK_INT >= 30 && AutoPairService.isEnabled(FmIo.app)) R.string.adb_auto_started else R.string.ap_need_adb)
}
