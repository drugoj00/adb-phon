package app.adbphon

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val CARD_URL = "https://secure.wayforpay.com/button/bba92579a4647"
const val USDT_ADDR = "TXk32CLkwJryTqhmkw6urcM5CacJgPsSpJ"
const val GITHUB_URL = "https://github.com/drugoj00/adb-phon"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        setContent { AdbPhonApp() }
    }
}

private val Dark = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF00201C),
    background = Color(0xFF0B0B10),
    surface = Color(0xFF14141B),
    surfaceVariant = Color(0xFF1E1E28),
    onBackground = Color(0xFFE5E7EB),
    onSurface = Color(0xFFE5E7EB),
    secondaryContainer = Color(0xFF0F766E),
    onSecondaryContainer = Color.White,
)
private val Light = lightColorScheme(
    primary = Color(0xFF0F766E),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEF2F6),
    secondaryContainer = Color(0xFF0F766E),
    onSecondaryContainer = Color.White,
    surfaceContainerHighest = Color(0xFFE4EEEC),
)

@Composable
fun AdbPhonApp(vm: MainViewModel = viewModel()) {
    val baseCtx = LocalContext.current
    val uiCtx = remember(vm.lang, baseCtx) { Lang.wrap(baseCtx, vm.lang) }
    val dark = when (vm.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    SideEffect {
        (baseCtx as? ComponentActivity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, w.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        }
    }
    CompositionLocalProvider(LocalContext provides uiCtx, LocalLangCode provides vm.lang) {
    MaterialTheme(colorScheme = if (dark) Dark else Light) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var tab by remember { mutableStateOf(0) }
            var showSettings by remember { mutableStateOf(false) }
            val snack = remember { SnackbarHostState() }
            LaunchedEffect(vm.connected) { if (vm.connected && tab == 0) tab = if (vm.remoteHost != null) 3 else 1 }
            LaunchedEffect(vm.message) {
                vm.message?.let { snack.showSnackbar(it); vm.consumeMessage() }
            }
            val act = baseCtx as? ComponentActivity
            LaunchedEffect(vm.keepScreenOn) {
                act?.window?.let { w ->
                    if (vm.keepScreenOn) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            BackHandler {
                when {
                    showSettings -> showSettings = false
                    tab != 0 -> tab = 0
                    !vm.connected && vm.exitOnBack -> {
                        vm.shutdown()
                        act?.finishAndRemoveTask()
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                    else -> act?.moveTaskToBack(true)
                }
            }
            Scaffold(
                modifier = Modifier.imePadding(),
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snack) },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        val items = listOf(
                            R.string.tab_connect to Icons.Filled.Link,
                            R.string.tab_apps to Icons.Filled.Apps,
                            R.string.tab_files to Icons.Filled.Folder,
                            R.string.tab_remote to Icons.Filled.SettingsRemote,
                            R.string.tab_more to Icons.Filled.MoreHoriz,
                        )
                        items.forEachIndexed { i, (label, icon) ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { tab = i; showSettings = false },
                                icon = { Icon(icon, null) },
                                label = { Text(stringResource(label), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                },
            ) { pad ->
                Column(Modifier.padding(pad).statusBarsPadding().fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (showSettings) stringResource(R.string.sub_settings) else "ADB-phon", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        IconButton({ showSettings = !showSettings }) {
                            Icon(Icons.Filled.Settings, stringResource(R.string.sub_settings), tint = if (showSettings) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
                        }
                    }
                    if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (showSettings) SettingsScreen(vm) else when (tab) {
                        0 -> ConnectScreen(vm)
                        1 -> NeedConnection(vm) { if (vm.remoteHost != null) LocalOnly() else AppsScreen(vm) }
                        2 -> NeedConnection(vm) { FilesScreen(vm) }
                        3 -> NeedConnection(vm) { RemoteScreen(vm) }
                        else -> MoreScreen(vm)
                    }
                }
            }
        }
    }
    }
}

@Composable
fun LocalOnly() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.local_only), fontSize = 16.sp)
    }
}

@Composable
fun NeedConnection(vm: MainViewModel, content: @Composable () -> Unit) {
    if (vm.connected) content() else Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.conn_need), fontSize = 16.sp)
    }
}

// ---------------------------------------------------------------- подключение

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConnectScreen(vm: MainViewModel) {
    val haptic = LocalHapticFeedback.current
    val dropLink: () -> Unit = { if (vm.connected) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.disconnect() } }
    var pairPort by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var connPort by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var favFor by remember { mutableStateOf<Found?>(null) }
    var favName by remember { mutableStateOf("") }
    val askName: (Found) -> Unit = { favName = vm.names[it.host].orEmpty(); favFor = it }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(if (vm.connected) R.string.conn_ok else R.string.conn_no),
            fontSize = 20.sp, fontWeight = FontWeight.Bold,
            color = if (vm.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
        )
        LaunchedEffect(Unit) { if (!vm.connected && !vm.scanned) vm.scanDevices() }
        if (vm.connected) Text(stringResource(R.string.conn_hold_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        if (vm.favs.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.fav_title), fontWeight = FontWeight.Bold)
                    vm.favs.toList().forEach { f ->
                        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { vm.connectFav(f) }, onLongClick = dropLink).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(f.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(f.host + if (f.kind == "wifi") ":" + f.port else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                            }
                            IconButton({ vm.removeFav(f) }) { Icon(Icons.Filled.Delete, stringResource(R.string.fav_remove)) }
                        }
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.net_title), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { vm.scanDevices() }, enabled = !vm.scanning) {
                        Text(stringResource(if (vm.scanning) R.string.net_scanning else R.string.net_scan))
                    }
                }
                vm.lastDevice?.let { f ->
                    Text(stringResource(R.string.net_last), fontSize = 12.sp)
                    FoundRow(vm, f, askName, dropLink)
                }
                vm.found.forEach { f -> FoundRow(vm, f, askName, dropLink) }
                if (vm.scanned && !vm.scanning && vm.found.isEmpty()) Text(stringResource(R.string.net_none), fontSize = 13.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                host, { host = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.weight(1f),
                label = { Text(stringResource(R.string.host_label)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            IconButton({ askName(Found(host, 5555, "tcp", false)) }, enabled = host.isNotBlank()) {
                Icon(Icons.Filled.StarBorder, stringResource(R.string.fav_save))
            }
        }
        favFor?.let { f ->
            LAlertDialog(
                onDismissRequest = { favFor = null },
                title = { Text(stringResource(R.string.fav_save)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            favName, { favName = it }, singleLine = true,
                            label = { Text(stringResource(R.string.fav_name)) },
                            placeholder = { Text(stringResource(R.string.fav_name_hint)) },
                        )
                        Text(f.host + if (f.kind == "wifi") ":" + f.port else "", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                    }
                },
                confirmButton = { TextButton({ vm.saveFav(favName, f.host, f.port, f.kind); favFor = null }) { Text(stringResource(R.string.btn_yes)) } },
                dismissButton = { TextButton({ favFor = null }) { Text(stringResource(R.string.btn_cancel)) } },
            )
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.conn_title_wifi), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.conn_steps_wifi), fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        pairPort, { pairPort = it.filter(Char::isDigit) }, Modifier.weight(1f),
                        label = { Text(stringResource(R.string.pair_port)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        pairCode, { pairCode = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f),
                        label = { Text(stringResource(R.string.pair_code)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Button(
                    onClick = { vm.pair(host, pairPort.toIntOrNull() ?: 0, pairCode) },
                    enabled = !vm.busy && pairPort.isNotEmpty() && pairCode.length == 6,
                ) { Text(stringResource(R.string.btn_pair)) }
                OutlinedTextField(
                    connPort, { connPort = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.conn_port)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Button(onClick = { vm.connectWireless(host, connPort.toIntOrNull()) }, enabled = !vm.busy) {
                    Text(stringResource(R.string.btn_connect))
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.conn_title_tcp), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.conn_steps_tcp), fontSize = 14.sp)
                Button(onClick = { vm.connectTcp(host, 5555) }, enabled = !vm.busy) { Text(stringResource(R.string.btn_connect_tcp, host.ifBlank { "localhost" })) }
            }
        }
        if (vm.connected) OutlinedButton(onClick = { vm.disconnect() }) { Text(stringResource(R.string.btn_disconnect)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FoundRow(vm: MainViewModel, f: Found, onStar: (Found) -> Unit, onDrop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = { vm.connectFound(f) }, onLongClick = onDrop).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            val addr = f.host + if (f.kind == "wifi") ":" + f.port else ""
            Text(addr, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = (vm.names[f.host] ?: stringResource(R.string.net_name_unknown)) + if (f.self) " · " + stringResource(R.string.net_self) else ""
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton({ onStar(f) }) { Icon(Icons.Filled.StarBorder, stringResource(R.string.fav_save)) }
    }
}

// ---------------------------------------------------------------- приложения

private object IconCache {
    val map = HashMap<String, ImageBitmap>()
}

@Composable
fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(IconCache.map[pkg], pkg) {
        if (value == null) {
            value = withContext(Dispatchers.Default) {
                runCatching {
                    ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
                }.getOrNull()?.also { IconCache.map[pkg] = it }
            }
        }
    }
    Box(Modifier.size(40.dp)) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize()) }
    }
}

private fun actLabel(a: Act): Int = when (a) {
    Act.DISABLE -> R.string.act_disable
    Act.ENABLE -> R.string.act_enable
    Act.UNINSTALL -> R.string.act_uninstall
    Act.RESTORE -> R.string.act_restore
    Act.STOP -> R.string.act_stop
    Act.CLEAR -> R.string.act_clear
    Act.SAVE -> R.string.act_save
}

@Composable
fun AppsScreen(vm: MainViewModel) {
    val visible = vm.visibleApps()
    var confirm by remember { mutableStateOf<Act?>(null) }
    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.installApk(it) }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            vm.query, { vm.query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = { Text(stringResource(R.string.search_hint)) }, singleLine = true,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                Filter.USER to R.string.f_user, Filter.SYSTEM to R.string.f_system, Filter.DISABLED to R.string.f_disabled,
                Filter.REMOVED to R.string.f_removed, Filter.ALL to R.string.f_all,
            ).forEach { (f, s) ->
                FilterChip(vm.filter == f, { vm.filter = f }, { Text(stringResource(s), fontSize = 12.sp, maxLines = 1) })
            }
        }
        Text(stringResource(R.string.apps_count, visible.size, vm.selected.size), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ vm.selectAllVisible() }) { Text(stringResource(R.string.select_all), fontSize = 12.sp, maxLines = 1) }
            TextButton({ vm.clearSelection() }) { Text(stringResource(R.string.select_none), fontSize = 12.sp, maxLines = 1) }
            TextButton({ pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }) {
                Text(stringResource(R.string.act_install), fontSize = 12.sp, maxLines = 1)
            }
        }
        LazyColumn(Modifier.weight(1f), state = rememberLazyListState()) {
            items(visible, key = { it.pkg }) { a ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.toggle(a.pkg) }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(a.pkg in vm.selected, { vm.toggle(a.pkg) })
                    AppIcon(a.pkg)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(a.pkg, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                        val tags = buildList {
                            if (a.system) add(stringResource(R.string.tag_system))
                            if (!a.enabled) add(stringResource(R.string.tag_disabled))
                            if (!a.installed) add(stringResource(R.string.tag_removed))
                            if (a.version.isNotEmpty()) add("v${a.version}")
                        }
                        Text(tags.joinToString(" · "), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        PermsDialog(vm)
        SpecialDialog(vm)
        if (vm.selected.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (vm.selected.size == 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        AssistChip(onClick = { vm.apps.firstOrNull { it.pkg in vm.selected }?.let { vm.loadPerms(it) } }, label = { Text(stringResource(R.string.act_perms), fontSize = 12.sp, maxLines = 1) })
                        AssistChip(onClick = { vm.apps.firstOrNull { it.pkg in vm.selected }?.let { vm.openSpecial(it) } }, label = { Text(stringResource(R.string.act_special), fontSize = 12.sp, maxLines = 1) })
                    }
                }
                listOf(Act.DISABLE, Act.ENABLE, Act.UNINSTALL, Act.RESTORE, Act.STOP, Act.CLEAR, Act.SAVE).chunked(4).forEach { row ->
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { act ->
                            AssistChip(onClick = { confirm = act }, label = { Text(stringResource(actLabel(act)), fontSize = 12.sp, maxLines = 1) })
                        }
                    }
                }
            }
        }
    }
    confirm?.let { act ->
        val label = stringResource(actLabel(act))
        val hasSystem = vm.apps.any { it.pkg in vm.selected && it.system }
        LAlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.confirm_title, label, vm.selected.size)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if ((act == Act.UNINSTALL || act == Act.DISABLE) && hasSystem) Text(stringResource(R.string.confirm_system))
                    if (act == Act.CLEAR) Text(stringResource(R.string.confirm_clear))
                }
            },
            confirmButton = { TextButton({ confirm = null; vm.run(act) }) { Text(stringResource(R.string.btn_yes)) } },
            dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.btn_cancel)) } },
        )
    }
}

// ---------------------------------------------------------------- консоль

@Composable
fun ConsoleScreen(vm: MainViewModel) {
    var cmd by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.consoleLines.size) {
        if (vm.consoleLines.isNotEmpty()) listState.animateScrollToItem(vm.consoleLines.size - 1)
    }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            items(vm.consoleLines.size) { i ->
                val line = vm.consoleLines[i]
                Text(
                    line, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    color = if (line.startsWith("$ ")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                cmd, { cmd = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text(stringResource(R.string.console_hint)) },
            )
            Button(onClick = { vm.runConsole(cmd); cmd = "" }, enabled = !vm.busy && cmd.isNotBlank()) { Text(stringResource(R.string.btn_run)) }
        }
        TextButton(onClick = { vm.clearConsole() }) { Text(stringResource(R.string.btn_clear)) }
    }
}

// ---------------------------------------------------------------- устройство

@Composable
fun DeviceScreen(vm: MainViewModel) {
    var dpi by remember { mutableStateOf("") }
    var reboot by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (vm.deviceInfo.isEmpty()) vm.refreshInfo() }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dev_info), fontWeight = FontWeight.Bold)
                Text(vm.deviceInfo, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                OutlinedButton(onClick = { vm.refreshInfo() }) { Text(stringResource(R.string.dev_refresh)) }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dev_quick), fontWeight = FontWeight.Bold)
                val anim = { v: String ->
                    "settings put global window_animation_scale $v; settings put global transition_animation_scale $v; settings put global animator_duration_scale $v"
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip({ vm.quick(anim("0")) }, { Text(stringResource(R.string.anim_off), fontSize = 12.sp) })
                    AssistChip({ vm.quick(anim("0.5")) }, { Text(stringResource(R.string.anim_half), fontSize = 12.sp) })
                    AssistChip({ vm.quick(anim("1")) }, { Text(stringResource(R.string.anim_normal), fontSize = 12.sp) })
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip({ vm.quick("settings put global stay_on_while_plugged_in 7") }, { Text(stringResource(R.string.stay_on), fontSize = 12.sp) })
                    AssistChip({ vm.quick("settings put global stay_on_while_plugged_in 0") }, { Text(stringResource(R.string.stay_off), fontSize = 12.sp) })
                }
                OutlinedTextField(
                    dpi, { dpi = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.dpi_label), maxLines = 1) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { dpi.toIntOrNull()?.takeIf { it in 120..800 }?.let { vm.quick("wm density $it") } }) {
                        Text(stringResource(R.string.dpi_apply))
                    }
                    OutlinedButton(onClick = { vm.quick("wm density reset") }) { Text(stringResource(R.string.dpi_reset)) }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dev_reboot), fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip({ reboot = "reboot" }, { Text(stringResource(R.string.reboot_normal), fontSize = 12.sp) })
                    AssistChip({ reboot = "reboot recovery" }, { Text(stringResource(R.string.reboot_recovery), fontSize = 12.sp) })
                    AssistChip({ reboot = "reboot bootloader" }, { Text(stringResource(R.string.reboot_boot), fontSize = 12.sp) })
                }
            }
        }
        DeviceExtras(vm)
    }
    reboot?.let { cmd ->
        LAlertDialog(
            onDismissRequest = { reboot = null },
            title = { Text(stringResource(R.string.dev_reboot) + "?") },
            text = { Text(cmd) },
            confirmButton = { TextButton({ reboot = null; vm.quick(cmd) }) { Text(stringResource(R.string.btn_yes)) } },
            dismissButton = { TextButton({ reboot = null }) { Text(stringResource(R.string.btn_cancel)) } },
        )
    }
}

// ---------------------------------------------------------------- о программе

@Composable
fun AboutScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val copied = stringResource(R.string.copied)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.logo), null, Modifier.size(64.dp))
            Column {
                Text("ADB-phon", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(vm.appVersion, fontSize = 13.sp)
            }
        }
        Text(stringResource(R.string.about_text))
        Text(stringResource(R.string.about_warn), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.about_free), fontWeight = FontWeight.Bold)
        Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CARD_URL))) }) { Text(stringResource(R.string.about_card)) }
        OutlinedButton(onClick = {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("USDT", USDT_ADDR))
            Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show()
        }) { Text(stringResource(R.string.about_usdt)) }
        Text(USDT_ADDR, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))) }) { Text(stringResource(R.string.about_github)) }
    }
}
