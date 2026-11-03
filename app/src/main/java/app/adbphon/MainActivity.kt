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
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val CARD_URL = "https://secure.wayforpay.com/button/bba92579a4647"
const val USDT_ADDR = "TXk32CLkwJryTqhmkw6urcM5CacJgPsSpJ"
const val GITHUB_URL = "https://github.com/drugoj00/adb-phon"
const val ALL_PROGRAMS_URL = "https://drugoj00.github.io/"
const val TELEGRAM_URL = "https://t.me/PhoneInWindowSound"

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
    primaryContainer = Color(0xFF0F766E),
    onPrimaryContainer = Color.White,
)
private val Light = lightColorScheme(
    primary = Color(0xFF0F766E),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEF2F6),
    secondaryContainer = Color(0xFF0F766E),
    onSecondaryContainer = Color.White,
    primaryContainer = Color(0xFF0F766E),
    onPrimaryContainer = Color.White,
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
    Feedback.host = (baseCtx as? ComponentActivity)?.window?.decorView
    CompositionLocalProvider(LocalContext provides uiCtx, LocalLangCode provides vm.lang) {
    MaterialTheme(colorScheme = if (dark) Dark else Light) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var tab by remember { mutableStateOf(0) }
            var showSettings by remember { mutableStateOf(false) }
            val snack = remember { SnackbarHostState() }
            // при запуске остаёмся на «Связь» (в т.ч. если автоподключение уже сработало); на «Софт»/«Пульт» переходим только после ручного подключения
            LaunchedEffect(vm.connected, vm.manualConnect) {
                if (vm.connected && vm.manualConnect) {
                    if (tab == 0) tab = if (vm.remoteHost != null) 3 else 1
                    vm.manualConnect = false
                }
            }
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
            vm.updateRelease?.let { r ->
                LAlertDialog(
                    onDismissRequest = { vm.updateRelease = null },
                    title = { Text(stringResource(R.string.upd_title)) },
                    text = { Text(stringResource(R.string.upd_text, r.tag, vm.appVersion)) },
                    confirmButton = { TextButton({ vm.installUpdate() }) { Text(stringResource(R.string.upd_yes)) } },
                    dismissButton = { TextButton({ vm.updateRelease = null }) { Text(stringResource(R.string.upd_later)) } },
                )
            }
            Scaffold(
                modifier = Modifier.imePadding(),
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snack) },
                bottomBar = {
                    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                        val items = listOf(
                            R.string.tab_connect to Icons.Filled.Link,
                            R.string.tab_apps to Icons.Filled.Apps,
                            R.string.tab_files to Icons.Filled.Folder,
                            R.string.tab_remote to Icons.Filled.SettingsRemote,
                            R.string.tab_more to Icons.Filled.MoreHoriz,
                            R.string.tab_settings to Icons.Filled.Settings,
                        )
                        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(54.dp)) {
                            items.forEachIndexed { i, (label, icon) ->
                                val selected = if (i == items.lastIndex) showSettings else (!showSettings && tab == i)
                                val col = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .7f)
                                Column(
                                    Modifier.weight(1f).fillMaxHeight()
                                        .clickable { if (i == items.lastIndex) showSettings = true else { tab = i; showSettings = false } },
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                                ) {
                                    if (i == items.lastIndex) {
                                        Icon(icon, stringResource(label), tint = col, modifier = Modifier.size(28.dp))
                                    } else {
                                        Icon(icon, null, tint = col, modifier = Modifier.size(22.dp))
                                        Text(stringResource(label), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = col)
                                    }
                                }
                            }
                        }
                    }
                },
            ) { pad ->
                Column(Modifier.padding(pad).statusBarsPadding().fillMaxSize()) {
                    Spacer(Modifier.height(4.dp))
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
    var pairPort by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var connPort by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var favFor by remember { mutableStateOf<Found?>(null) }
    var favName by remember { mutableStateOf("") }
    val askName: (Found) -> Unit = { favName = vm.names[it.host].orEmpty(); favFor = it }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (vm.connected && !Adb.isRoot && vm.remoteHost != null)
                stringResource(R.string.msg_connected_to, vm.names[vm.remoteHost!!] ?: stringResource(R.string.net_name_unknown), vm.remoteHost!!)
            else stringResource(if (!vm.connected) R.string.conn_no else if (Adb.isRoot) R.string.root_on else R.string.conn_ok),
            fontSize = 17.sp, fontWeight = FontWeight.Bold,
            color = if (vm.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
        )
        LaunchedEffect(Unit) { if (!vm.connected && !vm.scanned) vm.scanDevices() }
        Text(stringResource(R.string.conn_hold_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        AutoConnectCard(vm)
        if (vm.favs.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.fav_title), fontWeight = FontWeight.Bold)
                    vm.favs.toList().forEach { f ->
                        var menu by remember { mutableStateOf(false) }
                        val haptic = LocalHapticFeedback.current
                        val cur = vm.isCurrent(vm.favAsFound(f))   // подключённое: чёрный фон, белый текст
                        Box {
                        Row(Modifier.fillMaxWidth().then(if (cur) Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                            .combinedClickable(onClick = { vm.connectFav(f) }, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true })
                            .padding(vertical = 4.dp, horizontal = if (cur) 6.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Star, null, tint = if (cur) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(f.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (cur) MaterialTheme.colorScheme.onPrimary else Color.Unspecified)
                                Text(f.host + if (f.kind == "wifi") ":" + f.port else "", fontSize = 12.sp, color = if (cur) MaterialTheme.colorScheme.onPrimary.copy(alpha = .85f) else MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                            }
                            IconButton({ vm.removeFav(f) }) { Icon(Icons.Filled.Delete, stringResource(R.string.fav_remove), tint = if (cur) MaterialTheme.colorScheme.onPrimary else LocalContentColor.current) }
                        }
                        DeviceMenu(vm, vm.favAsFound(f), menu) { menu = false }
                        }
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.net_title), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { vm.scanDevices() }, enabled = !vm.scanning) {
                        Text(stringResource(if (vm.scanning) R.string.net_scanning else R.string.net_scan))
                    }
                }
                vm.lastDevice?.let { f ->
                    Text(stringResource(R.string.net_last), fontSize = 12.sp)
                    FoundRow(vm, f, askName)
                }
                // устройство из «Последнего подключения» второй раз в списке не показываем
                vm.found.filter { f -> vm.lastDevice?.let { it.host == f.host && it.port == f.port } != true }
                    .forEach { f -> FoundRow(vm, f, askName) }
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
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.conn_title_tcp), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.conn_steps_tcp), fontSize = 14.sp)
                Button(onClick = { vm.connectTcp(host, 5555) }, enabled = !vm.busy) { Text(stringResource(R.string.btn_connect_tcp, host.ifBlank { "localhost" })) }
            }
        }
        if (host.isBlank()) {
            val ctxR = LocalContext.current
            var useRoot by remember { mutableStateOf(Cfg.useRoot(ctxR)) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.root_title), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.root_hint), fontSize = 14.sp)
                    Button(onClick = { vm.connectRoot() }, enabled = !vm.busy) { Text(stringResource(R.string.root_connect_btn)) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        androidx.compose.material3.Switch(useRoot, { useRoot = it; Cfg.setUseRoot(ctxR, it); if (!it) { Adb.disableRoot(); Hooks.refresh() } })
                        Text(stringResource(R.string.root_use_title), fontSize = 14.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        if (vm.connected) OutlinedButton(onClick = { vm.disconnect() }) { Text(stringResource(R.string.btn_disconnect)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FoundRow(vm: MainViewModel, f: Found, onStar: (Found) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val cur = vm.isCurrent(f)   // подключённое устройство: чёрный фон, белый текст
    val fg = if (cur) MaterialTheme.colorScheme.onPrimary else Color.Unspecified
    Box {
    Row(
        Modifier.fillMaxWidth().then(if (cur) Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
            .combinedClickable(onClick = { vm.connectFound(f) }, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true })
            .padding(vertical = 3.dp, horizontal = if (cur) 6.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PhoneAndroid, null, tint = if (cur) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            val addr = f.host + if (f.kind == "wifi") ":" + f.port else ""
            Text(addr, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, color = fg)
            val sub = (vm.names[f.host] ?: stringResource(R.string.net_name_unknown)) + if (f.self) " · " + stringResource(R.string.net_self) else ""
            Text(sub, fontSize = 12.sp, color = if (cur) MaterialTheme.colorScheme.onPrimary.copy(alpha = .85f) else MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton({ onStar(f) }, Modifier.size(36.dp)) { Icon(Icons.Filled.StarBorder, stringResource(R.string.fav_save), tint = if (cur) MaterialTheme.colorScheme.onPrimary else LocalContentColor.current) }
    }
    DeviceMenu(vm, f, menu) { menu = false }
    }
}

/** Подключённое устройство в списке: фон цвета заголовка статуса (основной цвет темы), текст — контрастный цвет на нём. */
private fun Modifier.connectedRowStyle(dark: Boolean): Modifier = this

/** Меню долгого нажатия на устройство: «Отключить», если оно подключено, иначе «Подключить». */
@Composable
fun DeviceMenu(vm: MainViewModel, f: Found, expanded: Boolean, onDismiss: () -> Unit) {
    androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (vm.isCurrent(f)) androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_disconnect)) }, onClick = { onDismiss(); vm.disconnect() })
        else androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_connect)) }, onClick = { onDismiss(); vm.connectFound(f) })
    }
}

// ---------------------------------------------------------------- приложения

private object IconCache {
    val map = HashMap<String, ImageBitmap>()
}

@Composable
fun AppIcon(pkg: String, size: androidx.compose.ui.unit.Dp = 40.dp) {
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
    Box(Modifier.size(size)) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize()) }
    }
}

@Composable
private fun EqChipRow(items: List<Pair<String, () -> Unit>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (label, click) ->
            OutlinedButton(
                onClick = click, modifier = Modifier.weight(1f).height(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                Text(label, fontSize = 12.sp, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.EqTextButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick, Modifier.weight(1f).fillMaxHeight().defaultMinSize(minHeight = 40.dp),
        shape = androidx.compose.ui.graphics.RectangleShape,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(vm: MainViewModel) {
    val visible = vm.visibleApps()
    var confirm by remember { mutableStateOf<Act?>(null) }
    var installHub by remember { mutableStateOf(false) }
    var versionsFor by remember { mutableStateOf<AppEntry?>(null) }
    var showBackups by remember { mutableStateOf(false) }
    // панель действий открывается круглой кнопкой с галочкой, а не сама при выборе приложения
    var showActions by remember { mutableStateOf(false) }
    // долгий тап по приложению: окно с полными сведениями, которые не поместились в строке
    var infoFor by remember { mutableStateOf<AppEntry?>(null) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(vm.selected.isEmpty()) { if (vm.selected.isEmpty()) showActions = false }
    // компактный вид (просьба владелицы 26.10.2026): без лишней высоты у флажков и чипов, поиск вдвое ниже, строка приложения в две строки
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp) {
    Column(Modifier.fillMaxSize()) {
        androidx.compose.foundation.text.BasicTextField(
            value = vm.query, onValueChange = { vm.query = it }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().height(40.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (vm.query.isEmpty()) Text(stringResource(R.string.search_hint), fontSize = 14.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
                    inner()
                }
            },
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                Filter.USER to R.string.f_user, Filter.SYSTEM to R.string.f_system, Filter.ALL to R.string.f_all, Filter.DISABLED to R.string.f_disabled,
                Filter.REMOVED to R.string.f_removed, Filter.BLOAT to R.string.f_bloat,
            ).forEach { (f, s) ->
                FilterChip(vm.filter == f, { vm.filter = f }, { Text(stringResource(s), fontSize = 12.sp, maxLines = 1) })
            }
        }
        if (vm.filter == Filter.BLOAT) {
            Text(stringResource(R.string.bloat_hint), fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f), modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        Text(stringResource(R.string.apps_count, visible.size, vm.selected.size), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        val ramCtx = LocalContext.current
        val ramScope = rememberCoroutineScope()
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                EqTextButton(stringResource(R.string.select_all)) { vm.selectAllVisible() }
                val canAct = vm.selected.isNotEmpty()
                val prim = MaterialTheme.colorScheme.primary
                Box(
                    Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (canAct) prim else prim.copy(alpha = .3f))
                        .border(2.dp, if (canAct) prim else prim.copy(alpha = .3f), androidx.compose.foundation.shape.CircleShape)
                        .clickable(enabled = canAct) { showActions = !showActions },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Check, stringResource(R.string.act_open_panel),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(26.dp),
                    )
                }
                EqTextButton(stringResource(R.string.select_none)) { vm.clearSelection() }
                if (vm.filter == Filter.BLOAT) EqTextButton(stringResource(R.string.bloat_pick_safe)) { vm.selectSafeBloat() }
            }
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EqTextButton(stringResource(R.string.act_install)) { installHub = true }
                EqTextButton(stringResource(R.string.act_fullbackups)) { showBackups = true }
                if (vm.remoteHost == null) EqTextButton(stringResource(R.string.act_ram_clean)) { ramScope.launch { vm.message = Ram.clean(ramCtx) } }
            }
        }
        LazyColumn(Modifier.weight(1f), state = rememberLazyListState()) {
            items(visible, key = { it.pkg }) { a ->
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = { vm.toggle(a.pkg) },
                            onLongClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); infoFor = a },
                        )
                        .padding(horizontal = 12.dp, vertical = 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(a.pkg in vm.selected, { vm.toggle(a.pkg) }, Modifier.size(32.dp))
                    AppIcon(a.pkg, 32.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.label, fontSize = 15.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        val tags = buildList {
                            if (a.system) add(stringResource(R.string.tag_system))
                            if (!a.enabled) add(stringResource(R.string.tag_disabled))
                            if (!a.installed) add(stringResource(R.string.tag_removed))
                            Debloat.known[a.pkg]?.let { b ->
                                add(stringResource(Debloat.catLabel(b.cat)))
                                add(stringResource(Debloat.riskLabel(b.risk)))
                            }
                            if (a.version.isNotEmpty()) add("v${a.version}")
                        }
                        // вторая строка: пакет и версия серым, метки («система», «мусор», «безопасно»…) цветом
                        val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)
                        val acc = MaterialTheme.colorScheme.primary
                        Text(
                            androidx.compose.ui.text.buildAnnotatedString {
                                withStyle(androidx.compose.ui.text.SpanStyle(color = dim)) { append(a.pkg) }
                                if (tags.isNotEmpty()) withStyle(androidx.compose.ui.text.SpanStyle(color = acc)) { append(" · " + tags.joinToString(" · ")) }
                            },
                            fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        PermsDialog(vm)
        SpecialDialog(vm)
        if (installHub) InstallHub { installHub = false; vm.loadApps() }
        versionsFor?.let { a -> VersionsDialog(a.pkg, a.label) { versionsFor = null; vm.loadApps() } }
        vm.launcherGuard?.let { g ->
            LAlertDialog(
                onDismissRequest = { vm.dismissGuard() },
                title = { Text(stringResource(R.string.lg_title)) },
                text = { Text(stringResource(if (g.menuInstalled) R.string.lg_text_installed else R.string.lg_text, g.names.joinToString(", "))) },
                confirmButton = {
                    TextButton({ if (g.menuInstalled) vm.openMenuApp() else vm.installMenuApp() }) {
                        Text(stringResource(if (g.menuInstalled) R.string.lg_open else R.string.lg_install))
                    }
                },
                dismissButton = { TextButton({ vm.dismissGuard() }) { Text(stringResource(R.string.btn_cancel)) } },
            )
        }
        if (showBackups) FullBackupsDialog { showBackups = false; vm.loadApps() }
        infoFor?.let { a -> AppInfoDialog(a) { infoFor = null } }
        if (showActions && vm.selected.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val lPerms = stringResource(R.string.act_perms)
                val lSpecial = stringResource(R.string.act_special)
                val lVersions = stringResource(R.string.act_versions)
                val lFull = stringResource(R.string.act_fullbackup)
                if (vm.selected.size == 1) {
                    EqChipRow(listOf(
                        lPerms to { vm.apps.firstOrNull { it.pkg in vm.selected }?.let { vm.loadPerms(it) }; Unit },
                        lSpecial to { vm.apps.firstOrNull { it.pkg in vm.selected }?.let { vm.openSpecial(it) }; Unit },
                        lVersions to { versionsFor = vm.apps.firstOrNull { it.pkg in vm.selected } },
                    ))
                }
                val actLabels = Act.values().associateWith { stringResource(actLabel(it)) }
                fun act(x: Act) = actLabels.getValue(x) to { confirm = x }
                EqChipRow(listOf(act(Act.DISABLE), act(Act.ENABLE), act(Act.UNINSTALL)))
                EqChipRow(listOf(act(Act.RESTORE), act(Act.STOP)))
                EqChipRow(listOf(act(Act.CLEAR), act(Act.SAVE)))
                EqChipRow(listOf(lFull to { vm.fullBackup() }))
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
                    if (act == Act.UNINSTALL && Cfg.backupOnDelete(LocalContext.current)) Text(stringResource(R.string.confirm_copy_saved))
                }
            },
            confirmButton = { TextButton({ confirm = null; vm.run(act) }) { Text(stringResource(R.string.btn_yes)) } },
            dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.btn_cancel)) } },
        )
    }
}

/** Полные сведения о приложении: имя, пакет, версия, состояние, категория и пути APK. */
@Composable
fun AppInfoDialog(a: AppEntry, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val copied = stringResource(R.string.copied)
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)
    val state = buildList {
        if (a.system) add(stringResource(R.string.tag_system))
        if (!a.enabled) add(stringResource(R.string.tag_disabled))
        if (!a.installed) add(stringResource(R.string.tag_removed))
    }
    val known = Debloat.known[a.pkg]
    LAlertDialog(
        onDismissRequest = onClose,
        title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppIcon(a.pkg, 40.dp)
            Text(a.label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable fun row(k: Int, v: String) {
                    Column { Text(stringResource(k), fontSize = 11.sp, color = dim); Text(v, fontSize = 14.sp) }
                }
                row(R.string.info_pkg, a.pkg)
                if (a.version.isNotEmpty()) row(R.string.info_ver, a.version)
                row(R.string.info_state, if (state.isEmpty()) stringResource(R.string.info_state_user) else state.joinToString(", "))
                if (known != null) row(R.string.info_cat, stringResource(Debloat.catLabel(known.cat)) + ", " + stringResource(Debloat.riskLabel(known.risk)))
                if (a.paths.isNotEmpty()) row(R.string.info_paths, a.paths.joinToString("\n"))
            }
        },
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.btn_close)) } },
        dismissButton = {
            TextButton({
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("package", a.pkg))
                Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.info_copy)) }
        },
    )
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
        OutlinedTextField(
            cmd, { cmd = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text(stringResource(R.string.console_hint)) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.runConsole(cmd); cmd = "" }, modifier = Modifier.weight(1f), enabled = !vm.busy && cmd.isNotBlank()) { Text(stringResource(R.string.btn_run)) }
            OutlinedButton(onClick = { vm.clearConsole() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.btn_clear)) }
        }
    }
}

// ---------------------------------------------------------------- устройство

@Composable
fun DeviceScreen(vm: MainViewModel) {
    var dpi by remember { mutableStateOf("") }
    var reboot by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (vm.deviceInfo.isEmpty()) vm.refreshInfo() }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dev_info), fontWeight = FontWeight.Bold)
                Text(vm.deviceInfo, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                OutlinedButton(onClick = { vm.refreshInfo() }) { Text(stringResource(R.string.dev_refresh)) }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
fun AboutScreen(vm: MainViewModel, embedded: Boolean = false) {
    val ctx = LocalContext.current
    val copied = stringResource(R.string.copied)
    Column(if (embedded) Modifier else Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ALL_PROGRAMS_URL))) }) { Text(stringResource(R.string.about_all)) }
        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_URL))) }) { Text(stringResource(R.string.about_tg)) }
    }
}
