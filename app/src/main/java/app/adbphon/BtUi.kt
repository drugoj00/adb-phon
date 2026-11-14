package app.adbphon

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private const val SENS = 1.8f   // чувствительность тачпада

/** Подсказка «Телефон становится Bluetooth-мышью…» свёрнута в одну строку; раскрытие запоминается на время работы приложения. */
private var btHintOpen by mutableStateOf(false)

@SuppressLint("MissingPermission")
@Composable
fun BtScreen(vm: MainViewModel, pad: Boolean = false) {
    val ctx = LocalContext.current
    if (!BtHid.supported) {
        Text(stringResource(R.string.bt_old), Modifier.padding(16.dp))
        return
    }
    fun hasPerm() = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(hasPerm()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; if (ok) BtHid.start(ctx) }
    LaunchedEffect(Unit) { BtAuto.pauseReconnect = false; BtAuto.userStopped = false; if (PadKeeper.paused(ctx)) { PadKeeper.setPaused(ctx, false); PadKeeper.start(ctx) } }
    LaunchedEffect(granted, pad) { if (granted) { BtHid.sectionEnter(); Pad.fg = true; BtPower.enter(ctx); BtHid.useMode(ctx, if (pad) "pad" else "mouse"); BtHid.start(ctx) } }
    // вход и выход из раздела, сворачивание: Bluetooth включается сам и выключается, только если его включили мы
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner, granted, pad) {
        val ob = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (!granted) return@LifecycleEventObserver
            // потеря фокуса, сворачивание, выключение экрана: сразу «всё отпущено», позже HID отключается
            // открыто окно игры (отдельная Activity): раздел остаётся «внутри», отключения нет
            if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE && !Pad.playing && !OverlayPadService.running) { Pad.fg = false; Pad.releaseAll() }
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { if (!OverlayPadService.running) Pad.fg = true; BtPower.refresh(ctx); BtPower.tick++; if (BtPower.on) BtHid.start(ctx) else BtHid.error = "bt_off" }
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP && !Pad.playing && !OverlayPadService.running) { BtPower.leave(ctx); BtHid.sectionLeave() }
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START) { BtHid.sectionEnter(); BtPower.enter(ctx); BtHid.useMode(ctx, if (pad) "pad" else "mouse"); BtHid.start(ctx) }
        }
        owner.lifecycle.addObserver(ob)
        onDispose { owner.lifecycle.removeObserver(ob); if (granted && !Pad.playing && !OverlayPadService.running) { BtPower.leave(ctx); BtHid.sectionLeave() } }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    val land = isLandscapeBox(maxWidth, maxHeight)
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!granted) {
            Text(stringResource(R.string.bt_perm_hint), fontSize = 13.sp)
            Button({ ask.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text(stringResource(R.string.bt_perm_btn)) }
            return@Column
        }
        // Bluetooth выключили при открытом экране: сразу показываем блок с кнопкой «Включить»
        DisposableEffect(Unit) {
            val rx = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    val st = i?.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)
                    if (st == android.bluetooth.BluetoothAdapter.STATE_OFF || st == android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF) {
                        BtHid.onAdapterOff(ctx); BtHid.error = "bt_off"
                    }
                    BtPower.onSystemState(ctx)
                    if (st == android.bluetooth.BluetoothAdapter.STATE_ON) { BtHid.useMode(ctx, if (pad) "pad" else "mouse"); BtHid.start(ctx) }
                }
            }
            runCatching { ctx.registerReceiver(rx, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)) }
            onDispose { runCatching { ctx.unregisterReceiver(rx) } }
        }
        // страховка от пропущенных уведомлений: раз в 2 с интерфейс сверяется с реальным состоянием адаптера
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(2000)
                val real = BtPower.isOn(ctx)
                if (real != BtPower.on) BtPower.refresh(ctx)
                if (real && (BtHid.error == "bt_off" || !BtHid.registered)) { BtHid.useMode(ctx, if (pad) "pad" else "mouse"); BtHid.start(ctx); BtPower.tick++ }
                if (!real && BtHid.error != "bt_off") { BtHid.onAdapterOff(ctx); BtHid.error = "bt_off" }
                if (BtPower.busy && real == BtPower.on && !BtPower.switching()) BtPower.refresh(ctx)
            }
        }
        // ландшафт со списком устройств: переключатели стоят справа вместе с подсказкой и статусом, слева только список
        if (BtHid.hostName == null && !(land && BtHid.error == null)) BtTopBar(ctx, land)
        val err = BtHid.error
        if (err == "bt_off") {
            BtOffBlock(ctx)
            return@Column
        }
        if (err != null) {
            Text(stringResource(R.string.bt_unsupported), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            OutlinedButton({ BtHid.start(ctx) }) { Text(stringResource(R.string.bt_retry)) }
            return@Column
        }
        val host = BtHid.hostName
        // при входе подключаемся к последнему устройству сами, при потере повторяем
        LaunchedEffect(host == null, BtHid.registered, pad) { if (host == null) BtAuto.reconnectLoop(ctx) }
        if (host == null) BtPairing(ctx, land, pad, vm) else if (pad) PadConnected(host, land) else BtControls(host, land)
    }
    }
}

/** Строка сверху: переключатель Bluetooth (одно нажатие включает или выключает без запроса) и параметры автоматики. */
@Composable
private fun BtTopBar(ctx: Context, land: Boolean = false) {
    var opts by remember { mutableStateOf(land) }
    var autoOn by remember { mutableStateOf(BtPower.autoOn(ctx)) }
    var autoOff by remember { mutableStateOf(BtPower.autoOff(ctx)) }
    LaunchedEffect(Unit) { BtPower.refresh(ctx) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Switch(BtPower.on, { BtPower.toggle(ctx) }, enabled = !BtPower.busy)
            Text(stringResource(if (BtPower.busy) R.string.bt_pwr_busy else R.string.bt_pwr_title), Modifier.padding(start = 10.dp).weight(1f), fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            if (!land) androidx.compose.material3.IconButton({ opts = !opts }) { Icon(if (opts) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, stringResource(R.string.bt_pwr_opts)) }
        }
        if (opts) {
            Row(Modifier.fillMaxWidth().clickable { autoOn = !autoOn; BtPower.setAutoOn(ctx, autoOn) }, verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(autoOn, { autoOn = it; BtPower.setAutoOn(ctx, it) }); Text(stringResource(R.string.bt_pwr_auto_on), fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth().clickable { autoOff = !autoOff; BtPower.setAutoOff(ctx, autoOff) }, verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(autoOff, { autoOff = it; BtPower.setAutoOff(ctx, it) }); Text(stringResource(R.string.bt_pwr_auto_off), fontSize = 13.sp)
            }
            var keeper by remember { mutableStateOf(PadKeeper.enabled(ctx)) }
            Row(Modifier.fillMaxWidth().clickable { keeper = !keeper; PadKeeper.setEnabled(ctx, keeper) }, verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(keeper, { keeper = it; PadKeeper.setEnabled(ctx, it) }); Text(stringResource(R.string.kpr_setting), fontSize = 13.sp)
            }
            var autoLast by remember { mutableStateOf(BtAuto.autoLast(ctx)) }
            Row(Modifier.fillMaxWidth().clickable { autoLast = !autoLast; BtAuto.setAutoLast(ctx, autoLast) }, verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(autoLast, { autoLast = it; BtAuto.setAutoLast(ctx, it) }); Text(stringResource(R.string.bta_auto_last), fontSize = 13.sp)
            }
            var gamePrep by remember { mutableStateOf(GameHostPrep.enabled(ctx)) }
            Row(Modifier.fillMaxWidth().clickable { gamePrep = !gamePrep; GameHostPrep.setEnabled(ctx, gamePrep) }, verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(gamePrep, { gamePrep = it; GameHostPrep.setEnabled(ctx, it) }); Text(stringResource(R.string.gph_setting), fontSize = 13.sp)
            }
        }
    }
}

/** Bluetooth выключен: кнопка включает его сразу, системный запрос только если ни ADB, ни root недоступны. */
@SuppressLint("MissingPermission")
@Composable
private fun BtOffBlock(ctx: Context) {
    var denied by remember { mutableStateOf(false) }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        BtPower.refresh(ctx)
        if (r.resultCode == android.app.Activity.RESULT_OK) { denied = false; BtHid.start(ctx) } else denied = true
    }
    fun prompt() { runCatching { enable.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }.onFailure { denied = true } }
    Column(Modifier.fillMaxWidth().barScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.bt_off_title), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
        if (BtPower.busy) Text(stringResource(R.string.bt_pwr_busy), fontSize = 13.sp)
        else {
            Text(stringResource(R.string.bt_off_hint), fontSize = 13.sp)
            Button({ BtPower.toggle(ctx) }) { Text(stringResource(R.string.bt_enable)) }
        }
        if (BtPower.needPrompt && !BtPower.busy) {
            // запасной путь: ни ADB, ни root недоступны
            Text(stringResource(R.string.bt_pwr_fallback), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
            OutlinedButton({ prompt() }) { Text(stringResource(R.string.bt_pwr_prompt)) }
        }
        if (denied) {
            Text(stringResource(R.string.bt_enable_denied), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
            OutlinedButton({ runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                Text(stringResource(R.string.bt_open_settings))
            }
        }
    }
}

/** Пояснение в названии раздела: нажатие на название раскрывает или сворачивает (единый способ, как везде в программе). */
@Composable
private fun BtHintLine(pad: Boolean = false) {
    HintTitle(stringResource(if (pad) R.string.sub_pad else R.string.sub_bt), stringResource(if (pad) R.string.bt_pad_hint_pair else R.string.bt_hint), titleWeight = androidx.compose.ui.text.font.FontWeight.Bold)
}

@SuppressLint("MissingPermission")
@Composable
private fun BtPairing(ctx: Context, land: Boolean = false, pad: Boolean = false, vm: MainViewModel? = null) {
    var autoDlg by remember { mutableStateOf(false) }
    if (autoDlg && vm != null) BtAutoDialog(vm, if (pad) "pad" else "mouse") { autoDlg = false }
    val hintBlock: @Composable () -> Unit = {
        if (pad) BridgeTip()
        if (land) BtTopBar(ctx, true)
        BtHintLine(pad)
        BtAuto.status?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium) }
        if (vm != null) OutlinedButton({ autoDlg = true }, enabled = !BtAuto.busy) { Text(stringResource(R.string.bta_btn)) }
        if (pad) OutlinedButton({ runCatching { ctx.startActivity(Intent(ctx, GamepadTestActivity::class.java)) } }) { Text(stringResource(R.string.gpt_open)) }
        if (pad) PadTrialButton()
        if (pad) PadLocalButton()
        Text(stringResource(if (BtHid.registered) R.string.bt_ready else R.string.bt_preparing), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        Button({
            runCatching { ctx.startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)) }
        }) { Text(stringResource(R.string.bt_visible)) }
    }
    val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    var bondTick by remember { mutableStateOf(0) }
    var forgetFor by remember { mutableStateOf<BtRow?>(null) }
    val bonded: List<BtRow> = remember(BtAuto.busy, BtHid.hostName, BtPower.on, BtPower.tick, bondTick) {
        // последнее подключённое (или недавно появившееся) устройство первым
        BtHid.sortLastFirst(ctx, runCatching { adapter?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
            .map { d -> BtRow(d, d.address, runCatching { d.name }.getOrNull() ?: d.address) })
    }
    val listBlock: @Composable () -> Unit = {
        if (bonded.isNotEmpty()) {
            Text(stringResource(R.string.bt_paired), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 13.sp)
            bonded.forEach { d ->
              NoSelBox {
                var rowMenu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // 9.0: нажатие подключает, долгое нажатие даёт «Копировать название / адрес»
                    Box(Modifier.weight(1f).heightIn(min = 40.dp).pointerInput(d.address) { detectTapGestures(onTap = { BtAuto.manualConnect(ctx, d) }, onLongPress = { rowMenu = true }) }.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                        Text(d.name + "  ·  " + d.address, fontSize = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, textAlign = TextAlign.Start)
                        LDropdownMenu(rowMenu, { rowMenu = false }) {
                            androidx.compose.material3.DropdownMenuItem({ Text(stringResource(R.string.copy_name)) }, { rowMenu = false; copyText(ctx, d.name) })
                            androidx.compose.material3.DropdownMenuItem({ Text(stringResource(R.string.copy_addr)) }, { rowMenu = false; copyText(ctx, d.address) })
                        }
                    }
                    // 9.0: убрать любое сопряжённое устройство из списка
                    androidx.compose.material3.IconButton({ forgetFor = d }) { androidx.compose.material3.Icon(Icons.Filled.Delete, stringResource(R.string.bt_forget_one), tint = MaterialTheme.colorScheme.error) }
                }
              }
            }
        }
    }
    forgetFor?.let { d ->
        LAlertDialog(onDismissRequest = { forgetFor = null },
            title = { Text(stringResource(R.string.bt_forget_one)) },
            text = { Text(stringResource(R.string.bt_forget_one_q, d.name, d.address)) },
            confirmButton = { TextButton({ forgetFor = null; BtAuto.forgetDevice(ctx, d.address) { bondTick++ } }) { Text(stringResource(R.string.bt_forget_yes)) } },
            dismissButton = { TextButton({ forgetFor = null }) { Text(stringResource(R.string.btn_cancel)) } })
    }
    if (land) {
        // ландшафт (правша): слева список устройств, справа подсказка в одну строку, статус и «Сделать видимым»; левша зеркально
        SplitHalves(Modifier.fillMaxSize(), spacing = 12.dp,
            rest = { Column(Modifier.weight(1f).fillMaxHeight().barScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) { listBlock() } },
            controls = { Column(Modifier.weight(1f).fillMaxHeight().barScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) { hintBlock() } })
    } else {
        Column(Modifier.fillMaxSize().barScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) { hintBlock(); listBlock() }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.BtControls(host: String, land: Boolean = false) {
    TapGuardOff()
    var text by remember { mutableStateOf("") }
    val padCtx = androidx.compose.ui.platform.LocalContext.current
    val hostRow: @Composable () -> Unit = {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.bt_connected, host), Modifier.fillMaxWidth(), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, maxLines = 2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton({ BtAuto.forget(padCtx) }) { Text(stringResource(R.string.bta_forget), fontSize = 12.sp) }
        }
    }
    }
    // тачпад + полоса прокрутки справа
    val padRow: @Composable (Modifier) -> Unit = { padMod ->
    Row(padMod.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { Feedback.tick(padCtx); BtHid.click(1) }, onDoubleTap = { Feedback.tick(padCtx); BtHid.click(1, 2) }, onLongPress = { Feedback.tick(padCtx); BtHid.click(2) })
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag -> change.consume(); BtHid.move(drag.x * SENS / 2f, drag.y * SENS / 2f) }
                },
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.bt_pad_hint), Modifier.fillMaxWidth().padding(horizontal = 12.dp), fontSize = 11.sp, lineHeight = 15.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary) }
        var acc by remember { mutableStateOf(0f) }
        Box(
            Modifier.width(44.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        acc += drag.y
                        val step = 14f
                        while (acc >= step) { BtHid.wheel(-1); acc -= step }
                        while (acc <= -step) { BtHid.wheel(1); acc += step }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Text("⇅", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSecondaryContainer) }
    }
    }
    // ряд кнопок мыши: левая, средняя, правая
    val mouseRowUi: @Composable () -> Unit = {
    androidx.compose.runtime.CompositionLocalProvider(LocalFeedbackOn provides true) {
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button({ BtHid.click(1) }, Modifier.weight(1f), contentPadding = pad) { Text(stringResource(R.string.bt_left), fontSize = 12.sp, maxLines = 1) }
        Button(
            { BtHid.click(4) }, Modifier.weight(1f), contentPadding = pad,
            colors = ButtonDefaults.buttonColors(containerColor = primary.copy(alpha = 0.14f), contentColor = primary),
            border = BorderStroke(2.dp, primary),
        ) { Text(stringResource(R.string.bt_middle), fontSize = 12.sp, maxLines = 1) }
        Button({ BtHid.click(2) }, Modifier.weight(1f), contentPadding = pad) { Text(stringResource(R.string.bt_right), fontSize = 12.sp, maxLines = 1) }
    }
    }
    }
    // стрелки и клавиши
    val keysUi: @Composable () -> Unit = {
    androidx.compose.runtime.CompositionLocalProvider(LocalFeedbackOn provides true) {
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
    // клавиши: ровными рядами по 5, всё помещается на экране
    // ряд стрелок: вверх и вниз как у остальных клавиш, влево и вправо шире
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("←" to 0x50, "↓" to 0x51, "↑" to 0x52, "→" to 0x4F).forEach { (l, c) ->
            val w = if (l == "←" || l == "→") 1.5f else 1f
            OutlinedButton({ BtHid.key(0, c) }, Modifier.weight(w).height(36.dp), contentPadding = pad) { Text(l, fontSize = 12.sp, maxLines = 1) }
        }
    }
    val keys = listOf(
        "Enter" to { BtHid.key(0, 0x28) }, "Esc" to { BtHid.key(0, 0x29) }, "⌫" to { BtHid.key(0, 0x2A) }, "Tab" to { BtHid.key(0, 0x2B) }, "Del" to { BtHid.key(0, 0x4C) },
        "Home" to { BtHid.key(0, 0x4A) }, "End" to { BtHid.key(0, 0x4D) }, "Win" to { BtHid.key(8, 0) }, "PgUp" to { BtHid.key(0, 0x4B) }, "PgDn" to { BtHid.key(0, 0x4E) },
        "Ctrl+C" to { BtHid.key(1, 0x06) }, "Ctrl+V" to { BtHid.key(1, 0x19) }, "Alt+F4" to { BtHid.key(4, 0x3D) },
    )
    keys.chunked(5).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { (l, act) -> OutlinedButton({ act() }, Modifier.weight(1f).height(36.dp), contentPadding = pad) { Text(l, fontSize = 12.sp, maxLines = 1) } }
        }
    }
    }
    }
    // ввод текста
    val textUi: @Composable () -> Unit = {
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(stringResource(R.string.bt_text_hint), fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) })
    Button({ BtHid.typeText(text); text = "" }, Modifier.fillMaxWidth(), enabled = text.isNotEmpty()) { Text(stringResource(R.string.btn_send)) }
    }
    // портрет: кнопки мыши и клавиши одним блоком, как раньше
    val buttonsUi: @Composable () -> Unit = { mouseRowUi(); keysUi() }
    // ландшафт: поле текста и «Отправить» в одну строку (экономия высоты под окно курсора)
    val textRowUi: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text(stringResource(R.string.bt_text_hint), fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) })
            Button({ BtHid.typeText(text); text = "" }, enabled = text.isNotEmpty()) { Text(stringResource(R.string.btn_send)) }
        }
    }
    if (!land) {
        hostRow(); padRow(Modifier.weight(1f)); buttonsUi(); textUi()
    } else {
        // ландшафт (правша): ПРАВАЯ половина = окно курсора и под ним ряд кнопок мыши; ЛЕВАЯ = подключение, стрелки и клавиши, поле текста. Левша: зеркально (SplitHalves)
        SplitHalves(Modifier.weight(1f).fillMaxWidth(), spacing = 12.dp,
            rest = {
                Column(Modifier.weight(1f).fillMaxHeight().barScroll(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    hostRow(); keysUi(); textRowUi()
                }
            },
            controls = {
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    // окно вписывается в свободную высоту за вычетом ряда кнопок мыши (кнопка 40 dp, больше при крупном шрифте) и отступа, не ниже 150 dp
                    val fs = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
                    val padH = (maxHeight - 40.dp * fs - 6.dp).coerceAtLeast(150.dp)
                    // правая половина вписана по высоте: окно считается из свободной высоты, прокрутки нет
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        padRow(Modifier.height(padH)); mouseRowUi()
                    }
                }
            })
    }
}
