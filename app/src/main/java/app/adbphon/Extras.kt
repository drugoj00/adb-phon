package app.adbphon

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------- права приложения

@Composable
fun PermsDialog(vm: MainViewModel) {
    val app = vm.permsFor ?: return
    LAlertDialog(
        onDismissRequest = { vm.closePerms() },
        title = { Text(stringResource(R.string.perms_title, app.label), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            if (vm.perms.isEmpty()) Text(stringResource(R.string.perms_empty)) else LazyColumn {
                items(vm.perms, key = { it.first }) { (name, granted) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name.removePrefix("android.permission."), Modifier.weight(1f), fontSize = 12.sp)
                        Switch(granted, { vm.setPerm(app.pkg, name, it) })
                    }
                }
            }
        },
        confirmButton = { TextButton({ vm.closePerms() }) { Text(stringResource(R.string.btn_close)) } },
    )
}

@Composable
fun SpecialDialog(vm: MainViewModel) {
    val app = vm.specialFor ?: return
    val items = listOf(
        R.string.sp_access_on to "access_on", R.string.sp_pin to "pin", R.string.sp_unpin to "unpin", R.string.sp_access_off to "access_off", R.string.sp_restricted to "restricted", R.string.sp_secure to "secure",
        R.string.sp_overlay to "overlay", R.string.sp_notif to "notif", R.string.sp_usage to "usage",
        R.string.sp_files to "files", R.string.sp_install to "install", R.string.sp_settings to "settings", R.string.sp_battery to "battery",
    )
    LAlertDialog(
        onDismissRequest = { vm.closeSpecial() },
        title = { Text(stringResource(R.string.special_title, app.label), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEach { (label, kind) ->
                    OutlinedButton({ vm.special(app.pkg, kind) }, Modifier.fillMaxWidth()) { Text(stringResource(label), fontSize = 13.sp, maxLines = 2) }
                }
            }
        },
        confirmButton = { TextButton({ vm.closeSpecial() }) { Text(stringResource(R.string.btn_close)) } },
    )
}

@Composable
fun VersionsDialog(vm: MainViewModel) {
    if (!vm.versionsOpen) return
    LAlertDialog(
        onDismissRequest = { vm.closeVersions() },
        title = { Text(stringResource(R.string.ver_title), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.ver_current, vm.appVersion), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.ver_github), fontWeight = FontWeight.Bold)
                when (vm.githubState) {
                    "loading" -> Text(stringResource(R.string.ver_loading), fontSize = 13.sp)
                    "error" -> Text(stringResource(R.string.ver_gh_error), fontSize = 13.sp)
                    else -> if (vm.githubVersions.isEmpty()) Text(stringResource(R.string.ver_gh_none), fontSize = 13.sp)
                }
                vm.githubVersions.forEach { v ->
                    OutlinedButton({ vm.installVersion(v) }, Modifier.fillMaxWidth()) { Text(v.label, fontSize = 12.sp, maxLines = 2) }
                }
                Text(stringResource(R.string.ver_saved), fontWeight = FontWeight.Bold)
                if (vm.localVersions.isEmpty()) Text(stringResource(R.string.rb_none), fontSize = 13.sp)
                vm.localVersions.forEach { v ->
                    OutlinedButton({ vm.installVersion(v) }, Modifier.fillMaxWidth()) { Text(v.label, fontSize = 12.sp, maxLines = 2) }
                }
                if (!vm.connected || vm.remoteHost != null) Text(stringResource(R.string.ver_need_local), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { TextButton({ vm.closeVersions() }) { Text(stringResource(R.string.btn_close)) } },
    )
}

// ---------------------------------------------------------------- файлы

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(vm: MainViewModel) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renameFor by remember { mutableStateOf<String?>(null) }
    var deleteFor by remember { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { if (vm.files.isEmpty()) vm.listDir() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.upDir() }) { Icon(Icons.Filled.ArrowUpward, stringResource(R.string.files_up)) }
            Text(vm.path, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            IconButton({ text = ""; newFolder = true }) { Icon(Icons.Filled.CreateNewFolder, stringResource(R.string.files_new)) }
            IconButton({ vm.listDir() }) { Icon(Icons.Filled.Refresh, null) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("/sdcard", "/sdcard/Download", "/sdcard/DCIM", "/sdcard/Android/data", "/data/local/tmp").forEach { p ->
                AssistChip({ vm.listDir(p) }, { Text(p.substringAfterLast('/').ifEmpty { "/" }, fontSize = 12.sp) })
            }
        }
        vm.clip?.let { c ->
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.clip_hint, c.first.substringAfterLast('/')), Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton({ vm.paste() }) { Text(stringResource(R.string.files_paste), fontSize = 12.sp) }
            }
        }
        if (vm.files.isEmpty()) Text(stringResource(R.string.files_empty), Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(vm.files, key = { it.name }) { f ->
                Box {
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { if (f.isDir) vm.openDir(f.name) else menuFor = f.name }, onLongClick = { menuFor = f.name })
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (f.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (f.isDir) f.date else humanSize(f.size) + " · " + f.date, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                        }
                    }
                    DropdownMenu(menuFor == f.name, { menuFor = null }) {
                        DropdownMenuItem({ Text(stringResource(R.string.file_copy)) }, { vm.setClip(f.name, false); menuFor = null })
                        DropdownMenuItem({ Text(stringResource(R.string.file_move)) }, { vm.setClip(f.name, true); menuFor = null })
                        DropdownMenuItem({ Text(stringResource(R.string.file_rename)) }, { text = f.name; renameFor = f.name; menuFor = null })
                        DropdownMenuItem({ Text(stringResource(R.string.file_delete)) }, { deleteFor = f.name; menuFor = null })
                    }
                }
            }
        }
    }
    if (newFolder || renameFor != null) {
        LAlertDialog(
            onDismissRequest = { newFolder = false; renameFor = null },
            title = { Text(stringResource(if (newFolder) R.string.files_new else R.string.file_rename)) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(stringResource(R.string.file_name)) }) },
            confirmButton = {
                TextButton({
                    if (newFolder) vm.newFolder(text) else renameFor?.let { vm.renameFile(it, text) }
                    newFolder = false; renameFor = null
                }) { Text(stringResource(R.string.btn_yes)) }
            },
            dismissButton = { TextButton({ newFolder = false; renameFor = null }) { Text(stringResource(R.string.btn_cancel)) } },
        )
    }
    deleteFor?.let { name ->
        LAlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text(stringResource(R.string.file_delete)) },
            text = { Text(stringResource(R.string.file_delete_q, name)) },
            confirmButton = { TextButton({ vm.deleteFile(name); deleteFor = null }) { Text(stringResource(R.string.btn_yes)) } },
            dismissButton = { TextButton({ deleteFor = null }) { Text(stringResource(R.string.btn_cancel)) } },
        )
    }
}

private fun humanSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

// ---------------------------------------------------------------- пульт

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(vm: MainViewModel) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadScreenSize() }
    DisposableEffect(Unit) { onDispose { vm.setHelper(false) } }
    var mode by rememberSaveable { mutableStateOf(0) } // 0 кнопки, 1 касание, 2 мышь, 3 воздух
    // настоящий курсор нужен в «Мыши» и «Воздухе»: включаем помощника сам, выключатель внизу остаётся для ручного отключения
    LaunchedEffect(mode) { if (mode >= 1 && !vm.helperOn) vm.setHelper(true) }
    val cursor = remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    var sens by rememberSaveable { mutableStateOf(1.0f) }
    val labels = listOf(R.string.pad_keys, R.string.pad_touch, R.string.pad_mouse, R.string.pad_air)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        vm.remoteHost?.let { Text(stringResource(R.string.target_remote, it), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                SegmentedButton(
                    selected = mode == i, onClick = { mode = i },
                    shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                    icon = {},
                ) { Text(stringResource(l), fontSize = 10.sp, maxLines = 1, softWrap = false) }
            }
        }
        when (mode) {
            0 -> {
                RemoteKeys(vm)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.remote_text)) })
                    Button({ vm.typeText(text); text = "" }, enabled = text.isNotBlank()) { Text(stringResource(R.string.btn_send)) }
                }
            }
            1 -> {
                Text(stringResource(R.string.remote_pad), fontSize = 12.sp)
                PadArea(vm) { TouchPad(vm, false, cursor) }
                QuickKeys(vm)
            }
            2 -> {
                Text(stringResource(R.string.pad_mouse_hint), fontSize = 12.sp)
                PadArea(vm) { TouchPad(vm, true, cursor) }
                Row(Modifier.fillMaxWidth().height(34.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val cxm = vm.screenW / 2; val sy = vm.screenH
                    // обе кнопки одной высоты, подпись в две строки: «Прокрутка / вниз» и «Прокрутка / вверх»
                    // толстые стрелки по краям, подпись по центру
                    OutlinedButton({ if (vm.helperOn) vm.hScroll(-3) else vm.swipe(cxm, sy * 7 / 10, cxm, sy * 3 / 10, 300) }, Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Icon(Icons.Filled.ArrowDownward, null, Modifier.size(22.dp))
                        Text(stringResource(R.string.pad_scroll_down).replace(" ", "\n"), Modifier.weight(1f), fontSize = 12.sp, maxLines = 2, textAlign = TextAlign.Center)
                        Icon(Icons.Filled.ArrowDownward, null, Modifier.size(22.dp))
                    }
                    OutlinedButton({ if (vm.helperOn) vm.hScroll(3) else vm.swipe(cxm, sy * 3 / 10, cxm, sy * 7 / 10, 300) }, Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Icon(Icons.Filled.ArrowUpward, null, Modifier.size(22.dp))
                        Text(stringResource(R.string.pad_scroll_up).replace(" ", "\n"), Modifier.weight(1f), fontSize = 12.sp, maxLines = 2, textAlign = TextAlign.Center)
                        Icon(Icons.Filled.ArrowUpward, null, Modifier.size(22.dp))
                    }
                }
                QuickKeys(vm)
                HelperSwitch(vm)
            }
            else -> {
                AirControls(vm, cursor, sens)
                RemoteKeys(vm) {
                    Text(stringResource(R.string.air_sens), fontSize = 12.sp)
                    Slider(sens, { sens = it }, Modifier.height(24.dp), valueRange = 0.3f..3f, colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = .35f)))
                }
                HelperSwitch(vm)
                Text(stringResource(R.string.air_hint), fontSize = 12.sp)
            }
        }
    }
}

/**
 * Окно для пальца занимает половину ширины экрана, справа (по умолчанию) или слева; на пустой половине кнопки выбора стороны.
 * Так же и для альбомного управляемого экрана (ТВ-приставка).
 */
@Composable
private fun PadArea(vm: MainViewModel, pad: @Composable () -> Unit) {
    // одна кнопка на пустой половине: «Окно / слева (или справа) / стрелка» — куда окно переместится по нажатию
    @Composable fun sideButton() {
        val toLeft = vm.padRight
        FilledTonalButton({ vm.changePadRight(!vm.padRight) }, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.pad_win), fontSize = 12.sp, maxLines = 1)
                Text(stringResource(if (toLeft) R.string.pad_to_left else R.string.pad_to_right), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Icon(if (toLeft) Icons.Filled.ArrowBack else Icons.Filled.ArrowForward, null, Modifier.size(22.dp))
            }
        }
    }
    // окно для пальца всегда на половине ширины сбоку, даже если управляемый экран альбомный (ТВ-приставка)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (vm.padRight) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { sideButton() }
            Box(Modifier.weight(1f)) { pad() }
        } else {
            Box(Modifier.weight(1f)) { pad() }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { sideButton() }
        }
    }
}

@Composable
private fun HelperSwitch(vm: MainViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(vm.helperOn, { vm.setHelper(it) })
        Text(stringResource(R.string.helper_switch), fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
    if (!vm.helperOn) Text(stringResource(R.string.helper_hint), fontSize = 11.sp)
}

/** Короткий ряд под сенсорной панелью: назад, домой, недавние, меню. */
@Composable
private fun QuickKeys(vm: MainViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextKey(vm, R.string.k_back, 4, 11); TextKey(vm, R.string.k_home, 3, 11); TextKey(vm, R.string.k_recents, 187, 11); TextKey(vm, R.string.k_menu, 82, 11)
    }
}

private val KEY_H = 56.dp      // блок со стрелками остаётся высоким
private val HALF_H = 28.dp     // все остальные кнопки панели вдвое ниже

@Composable
private fun RowScope.TextKey(vm: MainViewModel, label: Int, code: Int, size: Int = 13, weight: Float = 1f, h: androidx.compose.ui.unit.Dp = HALF_H) {
    OutlinedButton({ vm.key(code) }, Modifier.weight(weight).height(h), shape = RoundedCornerShape(if (h == HALF_H) 10.dp else 14.dp), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) {
        Text(stringResource(label), fontSize = size.sp, maxLines = if (h == HALF_H) 1 else 2, textAlign = TextAlign.Center, lineHeight = (size + 2).sp)
    }
}

@Composable
private fun RowScope.IconKey(vm: MainViewModel, icon: ImageVector, code: Int) {
    OutlinedButton({ vm.key(code) }, Modifier.weight(1f).height(HALF_H), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(0.dp)) {
        Icon(icon, null, Modifier.size(22.dp))
    }
}

@Composable
private fun RowScope.ArrowKey(vm: MainViewModel, icon: ImageVector, code: Int) {
    Button({ vm.key(code) }, Modifier.weight(1f).height(KEY_H), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(0.dp)) {
        Icon(icon, null, Modifier.size(34.dp))
    }
}

@Composable
private fun RowScope.OkKey(vm: MainViewModel) {
    Button({ vm.key(23) }, Modifier.weight(1f).height(KEY_H), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(0.dp)) {
        Text(stringResource(R.string.k_ok), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/** Общий блок кнопок пульта для всех режимов: сетка из трёх равных столбцов. */
@Composable
private fun RemoteKeys(vm: MainViewModel, afterVolume: (@Composable () -> Unit)? = null) {
    val gap = Arrangement.spacedBy(8.dp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = gap) {
        // над джойстиком: питание (1 столбец) и микрофон (2 столбца)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            TextKey(vm, R.string.k_power, 26); TextKey(vm, R.string.k_mic, 91, weight = 2f)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            TextKey(vm, R.string.k_recents, 187, h = KEY_H); ArrowKey(vm, Icons.Filled.KeyboardArrowUp, 19); TextKey(vm, R.string.k_home, 3, h = KEY_H)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            ArrowKey(vm, Icons.Filled.KeyboardArrowLeft, 21); OkKey(vm); ArrowKey(vm, Icons.Filled.KeyboardArrowRight, 22)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            TextKey(vm, R.string.k_menu, 82, h = KEY_H); ArrowKey(vm, Icons.Filled.KeyboardArrowDown, 20); TextKey(vm, R.string.k_back, 4, h = KEY_H)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            TextKey(vm, R.string.k_vol_down, 25); TextKey(vm, R.string.k_vol_up, 24); TextKey(vm, R.string.k_mute, 164, 11)
        }
        // кнопки плеера идут сразу под громкостью, а «Чувствительность» с ползунком (во вкладке «Воздух») — после них
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            IconKey(vm, Icons.Filled.SkipPrevious, 88); IconKey(vm, Icons.Filled.PlayArrow, 85); IconKey(vm, Icons.Filled.SkipNext, 87)
        }
        afterVolume?.invoke()
    }
}

/** Аэромышь: пока кнопка удержана, гироскоп двигает курсор (рыскание и тангаж телефона). */
@Composable
private fun AirControls(vm: MainViewModel, cursor: androidx.compose.runtime.MutableState<Offset>, sens: Float) {
    val ctx = LocalContext.current
    var holding by remember { mutableStateOf(false) }
    var latched by remember { mutableStateOf(false) }   // короткое нажатие включает воздух до следующего нажатия
    val active = holding || latched
    val gyro = remember { (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_GYROSCOPE) }
    DisposableEffect(active, sens) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        if (!active || gyro == null) {
            onDispose { }
        } else {
            var last = 0L
            var accX = 0f
            var accY = 0f
            var evN = 0; var maxYaw = 0f; var maxPitch = 0f; var sent = 0; var logT = 0L
            // вектор силы тяжести в осях телефона: по нему находим «вертикаль», и поворот вправо-влево работает при любом хвате
            // (экраном вверх, как пульт, или стоя, как камера)
            val g = floatArrayOf(0f, 0f, 9.8f)
            val gravSensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val gl = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    // для акселерометра (если гравитационного датчика нет) сглаживаем, чтобы выделить гравитацию
                    val a = if (e.sensor.type == Sensor.TYPE_GRAVITY) 1f else 0.1f
                    for (i in 0..2) g[i] += a * (e.values[i] - g[i])
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    if (last != 0L) {
                        val dt = (e.timestamp - last) / 1e9f
                        val gn = kotlin.math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]).coerceAtLeast(0.1f)
                        // скорость поворота вокруг вертикали (рыскание) и вокруг короткой оси телефона (тангаж), рад/с
                        val yaw = -(e.values[0] * g[0] + e.values[1] * g[1] + e.values[2] * g[2]) / gn
                        val pitch = -e.values[0]
                        evN++
                        if (kotlin.math.abs(yaw) > maxYaw) maxYaw = kotlin.math.abs(yaw)
                        if (kotlin.math.abs(pitch) > maxPitch) maxPitch = kotlin.math.abs(pitch)
                        if (e.timestamp - logT > 1_000_000_000L) {
                            AppLog.i("ADBphon", "air: событий=$evN макс.рыскание=${"%.3f".format(maxYaw)} макс.тангаж=${"%.3f".format(maxPitch)} отправлено=$sent помощник=${vm.helperOn} экран=${vm.screenW}x${vm.screenH}")
                            logT = e.timestamp; evN = 0; maxYaw = 0f; maxPitch = 0f; sent = 0
                        }
                        val c = cursor.value
                        if (vm.helperOn) {
                            // около 40° поворота ≈ весь экран: один отсчёт мыши даёт ≈1,4 пикселя
                            val kc = sens * vm.screenW * 0.9f
                            accX += yaw * dt * kc
                            accY += pitch * dt * kc
                            val ix = accX.toInt(); val iy = accY.toInt()
                            if (ix != 0 || iy != 0) { accX -= ix; accY -= iy; sent += kotlin.math.abs(ix) + kotlin.math.abs(iy); vm.hMove(ix, iy) }
                        } else {
                            val k = 1.8f * sens
                            cursor.value = Offset((c.x + yaw * dt * k).coerceIn(0f, 1f), (c.y + pitch * dt * k).coerceIn(0f, 1f))
                        }
                    }
                    last = e.timestamp
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            // приставка прячет курсор, если мышь молчит: лёгкое «дрожание» при нажатии сразу показывает его
            if (vm.helperOn) { vm.hMove(1, 0); vm.hMove(-1, 0) }
            sm.registerListener(l, gyro, SensorManager.SENSOR_DELAY_GAME)
            if (gravSensor != null) sm.registerListener(gl, gravSensor, SensorManager.SENSOR_DELAY_GAME)
            onDispose { sm.unregisterListener(l); sm.unregisterListener(gl) }
        }
    }
    if (gyro == null) {
        Text(stringResource(R.string.air_no_sensor), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        return
    }
    // состояние режима меняем осознанно: при смене режима всё выключаем
    LaunchedEffect(vm.airToggle) { latched = false; holding = false }
    // «Клик», кружок выбора режима и кнопка «Воздух вкл / Воздух выкл» в одном ряду.
    // Кружок: бирюзовый — «переключение» (касание включает до следующего касания), цвета фона темы — «удержание» (работает, пока держишь)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button({ if (vm.helperOn) vm.hClick() else { val c = cursor.value; vm.tap((c.x * vm.screenW).toInt(), (c.y * vm.screenH).toInt()) } },
            Modifier.weight(1f).height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E), contentColor = Color.White)) { Text(stringResource(R.string.air_click)) }
        Box(
            Modifier.size(36.dp)
                .background(if (vm.airToggle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background, androidx.compose.foundation.shape.CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                .clickable { vm.changeAirToggle(!vm.airToggle) },
        )
        Box(
            Modifier.weight(1f).height(52.dp)
                .background(if (active) Color(0xFF6D28D9) else Color(0xFFFF9800), RoundedCornerShape(26.dp))
                .pointerInput(Unit) {
                    // палец забираем себе целиком: иначе прокрутка страницы «перехватывает» касание и кнопка отпускается сама
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        if (vm.airToggle) {
                            latched = !latched
                            AppLog.i("ADBphon", "air: переключение, включено=" + latched)
                        } else {
                            latched = false; holding = true
                            AppLog.i("ADBphon", "air: удержание начато")
                        }
                        do {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { it.consume() }
                        } while (ev.changes.any { it.pressed })
                        if (!vm.airToggle) {
                            holding = false
                            AppLog.i("ADBphon", "air: удержание закончено")
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(if (active) R.string.air_state_on else R.string.air_state_off).replace(' ', '\n'),
                fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 17.sp, maxLines = 2, textAlign = TextAlign.Center,
                color = if (active) Color.White else Color(0xFF2B1700),
            )
        }
    }
}

@Composable
private fun TouchPad(vm: MainViewModel, mouse: Boolean, cursor: androidx.compose.runtime.MutableState<Offset>) {
    var w by remember { mutableStateOf(1) }
    var h by remember { mutableStateOf(1) }
    // окно для пальца всегда квадратное, с контуром; положение пальца переводится в координаты экрана пропорционально
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
            .onSizeChanged { w = it.width.coerceAtLeast(1); h = it.height.coerceAtLeast(1) }
            .pointerInput(vm.screenW, vm.screenH, mouse) {
                if (mouse) {
                    detectTapGestures(
                        onTap = { if (vm.helperOn) vm.hClick() else vm.tap((cursor.value.x * vm.screenW).toInt(), (cursor.value.y * vm.screenH).toInt()) },
                        onLongPress = { if (vm.helperOn) vm.hLongClick() else vm.longPress((cursor.value.x * vm.screenW).toInt(), (cursor.value.y * vm.screenH).toInt()) },
                    )
                } else {
                    detectTapGestures(
                        onTap = { o -> vm.tap((o.x / w * vm.screenW).toInt(), (o.y / h * vm.screenH).toInt()) },
                        onLongPress = { o -> vm.longPress((o.x / w * vm.screenW).toInt(), (o.y / h * vm.screenH).toInt()) },
                    )
                }
            }
            .pointerInput(vm.screenW, vm.screenH, mouse) {
                if (mouse) {
                    // один отсчёт мыши на устройстве даёт ≈1,4 пикселя: палец вдоль всего окна ≈ вдоль всего экрана; остатки копим
                    var mx = 0f; var my = 0f
                    detectDragGestures { c, d ->
                        c.consume()
                        if (vm.helperOn) {
                            mx += d.x / w * vm.screenW * 0.7f; my += d.y / h * vm.screenH * 0.7f
                            val ix = mx.toInt(); val iy = my.toInt()
                            if (ix != 0 || iy != 0) { mx -= ix; my -= iy; vm.hMove(ix, iy) }
                        } else {
                            val o = cursor.value
                            cursor.value = Offset((o.x + d.x / w * 1.6f).coerceIn(0f, 1f), (o.y + d.y / h * 1.6f).coerceIn(0f, 1f))
                        }
                    }
                } else {
                    var sx = 0f; var sy = 0f; var ex = 0f; var ey = 0f; var t0 = 0L; var tLast = 0L
                    fun px(v: Float) = (v / w * vm.screenW).toInt().coerceIn(0, vm.screenW - 1)
                    fun py(v: Float) = (v / h * vm.screenH).toInt().coerceIn(0, vm.screenH - 1)
                    detectDragGestures(
                        onDragStart = { o ->
                            sx = o.x; sy = o.y; ex = o.x; ey = o.y; t0 = System.currentTimeMillis(); tLast = 0L
                            // с помощником палец ведётся по экрану прямо во время движения
                            if (vm.touchViaHelper) vm.hTouch("td", px(sx), py(sy))
                        },
                        onDrag = { c, d ->
                            c.consume(); ex += d.x; ey += d.y
                            val now = System.currentTimeMillis()
                            if (vm.touchViaHelper && now - tLast >= 16) { tLast = now; vm.hTouch("tm", px(ex), py(ey)) }
                        },
                        onDragEnd = {
                            if (vm.touchViaHelper) {
                                vm.hTouch("tu", px(ex), py(ey))
                            } else {
                                val ms = (System.currentTimeMillis() - t0).toInt().coerceIn(80, 800)
                                vm.swipe(px(sx), py(sy), px(ex), py(ey), ms)
                            }
                        },
                        onDragCancel = { if (vm.touchViaHelper) vm.hTouch("tu", px(ex), py(ey)) },
                    )
                }
            },
    ) {
        if (mouse) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(cursor.value.x * size.width, cursor.value.y * size.height)
                drawCircle(Color(0xFFEF4444), radius = 9.dp.toPx(), center = c)
                drawCircle(Color.White, radius = 9.dp.toPx(), center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
        }
    }
}

// ---------------------------------------------------------------- «Ещё»: консоль, телефон, о программе

@Composable
fun MoreScreen(vm: MainViewModel) {
    var sub by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(R.string.sub_console, R.string.sub_device, R.string.sub_about).forEachIndexed { i, s ->
                FilterChip(sub == i, { sub = i }, { Text(stringResource(s), maxLines = 1) })
            }
        }
        when (sub) {
            0 -> NeedConnection(vm) { ConsoleScreen(vm) }
            1 -> NeedConnection(vm) { DeviceScreen(vm) }
            else -> AboutScreen(vm)
        }
    }
}

// ---------------------------------------------------------------- расширение вкладки «Телефон»

@Composable
fun DeviceExtras(vm: MainViewModel) {
    LaunchedEffect(Unit) { vm.refreshMonitor() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.mon_title), fontWeight = FontWeight.Bold)
            Text(vm.monitor, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            OutlinedButton({ vm.refreshMonitor() }) { Text(stringResource(R.string.dev_refresh)) }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip({ vm.screenshot() }, { Text(stringResource(R.string.shot), fontSize = 12.sp) })
                AssistChip({ vm.record() }, { Text(stringResource(R.string.rec), fontSize = 12.sp) })
                AssistChip({ vm.clearAllCaches() }, { Text(stringResource(R.string.clear_cache), fontSize = 12.sp) })
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val groups = listOf(
                listOf(R.string.q_dark_on to "cmd uimode night yes", R.string.q_dark_off to "cmd uimode night no"),
                listOf(R.string.q_wifi_on to "svc wifi enable", R.string.q_wifi_off to "svc wifi disable"),
                listOf(R.string.q_data_on to "svc data enable", R.string.q_data_off to "svc data disable"),
                listOf(R.string.q_bt_on to "svc bluetooth enable", R.string.q_bt_off to "svc bluetooth disable"),
                listOf(R.string.q_rot_auto to "settings put system accelerometer_rotation 1", R.string.q_rot_off to "settings put system accelerometer_rotation 0"),
                listOf(R.string.q_touch_on to "settings put system show_touches 1", R.string.q_touch_off to "settings put system show_touches 0"),
                listOf(R.string.q_pointer to "settings put system pointer_location 1", R.string.q_pointer_off to "settings put system pointer_location 0"),
                listOf(
                    R.string.q_font_small to "settings put system font_scale 0.85",
                    R.string.q_font_norm to "settings put system font_scale 1.0",
                    R.string.q_font_big to "settings put system font_scale 1.3",
                ),
                listOf(
                    R.string.q_sleep_30s to "settings put system screen_off_timeout 30000",
                    R.string.q_sleep_2m to "settings put system screen_off_timeout 120000",
                    R.string.q_sleep_30m to "settings put system screen_off_timeout 1800000",
                ),
            )
            groups.forEach { g ->
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    g.forEach { (label, cmd) -> AssistChip({ vm.quick(cmd) }, { Text(stringResource(label), fontSize = 12.sp) }) }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.set_theme), fontWeight = FontWeight.Medium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("auto" to R.string.theme_auto, "light" to R.string.theme_light, "dark" to R.string.theme_dark).forEach { (code, label) ->
                FilterChip(vm.theme == code, { vm.changeTheme(code) }, { Text(stringResource(label), maxLines = 1) })
            }
        }
        Text(stringResource(R.string.set_lang), fontWeight = FontWeight.Medium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(vm.lang == "auto", { vm.changeLang("auto") }, { Text(stringResource(R.string.lang_auto), maxLines = 1) })
            listOf("ru" to "Русский", "uk" to "Українська", "en" to "English").forEach { (code, name) ->
                FilterChip(vm.lang == code, { vm.changeLang(code) }, { Text(name, maxLines = 1) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(vm.keepScreenOn, { vm.changeKeepScreenOn(it) })
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.set_keep_on), fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.set_keep_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(vm.exitOnBack, { vm.changeExitOnBack(it) })
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.set_exit_back), fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.set_exit_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(vm.logOn, { vm.changeLog(it) })
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.log_switch), fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.log_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            }
        }
        val ctx = LocalContext.current
        val copied = stringResource(R.string.copied)
        BackupSection(vm)
        CopiesSection()
        Text(stringResource(R.string.set_version, vm.appVersion), fontWeight = FontWeight.Medium)
        Text(stringResource(R.string.ver_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        OutlinedButton({ vm.openVersions() }) { Text(stringResource(R.string.ver_btn), fontSize = 12.sp, maxLines = 1) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton({ vm.showLog() }) { Text(stringResource(R.string.log_show), fontSize = 12.sp, maxLines = 1) }
            OutlinedButton({
                val text = (AppLog.lastCrash().takeIf { it.isNotBlank() }?.let { "== СБОЙ ==\n$it\n\n" }.orEmpty()) + AppLog.tail(300)
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ADB-phon log", text))
                Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.log_copy), fontSize = 12.sp, maxLines = 1) }
            OutlinedButton({ vm.saveLog() }, enabled = vm.connected) { Text(stringResource(R.string.log_save), fontSize = 12.sp, maxLines = 1) }
            OutlinedButton({ AppLog.clear(); vm.hideLog() }) { Text(stringResource(R.string.log_clear), fontSize = 12.sp, maxLines = 1) }
        }
    }
    VersionsDialog(vm)
    RestoreDialog(vm)
    vm.logText?.let { txt ->
        val crash = AppLog.lastCrash()
        LAlertDialog(
            onDismissRequest = { vm.hideLog() },
            title = { Text(stringResource(R.string.log_show)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (crash.isNotBlank()) {
                        Text(stringResource(R.string.log_crash), fontWeight = FontWeight.Bold)
                        Text(crash.take(1500), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                    Text(txt.ifBlank { stringResource(R.string.log_empty) }, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            },
            confirmButton = { TextButton({ vm.hideLog() }) { Text(stringResource(R.string.btn_close)) } },
        )
    }
}

@Composable
fun BackupSection(vm: MainViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.chooseBackupFolder(uri) }
    val folder = vm.backupFolder
    Text(stringResource(R.string.bk_title), fontWeight = FontWeight.Medium)
    Text(
        if (folder == null) stringResource(R.string.bk_no_folder) else stringResource(R.string.bk_folder, SettingsBackup.folderLabel(folder)),
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f),
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton({ picker.launch(folder) }) { Text(stringResource(R.string.bk_pick), fontSize = 12.sp, maxLines = 1) }
        OutlinedButton({ vm.makeBackup() }, enabled = folder != null) { Text(stringResource(R.string.bk_make), fontSize = 12.sp, maxLines = 1) }
        OutlinedButton({ vm.openRestore() }, enabled = folder != null) { Text(stringResource(R.string.bk_restore), fontSize = 12.sp, maxLines = 1) }
    }
}

@Composable
fun RestoreDialog(vm: MainViewModel) {
    val items = vm.backupItems ?: return
    LAlertDialog(
        onDismissRequest = { vm.closeRestore() },
        title = { Text(stringResource(R.string.bk_restore_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (items.isEmpty()) Text(stringResource(R.string.bk_none), fontSize = 13.sp)
                items.forEach { it0 ->
                    OutlinedButton({ vm.restoreBackup(it0) }, Modifier.fillMaxWidth()) { Text(it0.name, fontSize = 12.sp, maxLines = 1) }
                }
            }
        },
        confirmButton = { TextButton({ vm.closeRestore() }) { Text(stringResource(R.string.btn_close)) } },
    )
}


/** Копии приложений перед удалением и окно сохранённых копий (там же ставится любая версия). */
@Composable
fun CopiesSection() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Cfg.backupOnDelete(ctx)) }
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(on, { on = it; Cfg.setBackupOnDelete(ctx, it) })
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.set_backups), fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.backups_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            }
        }
        OutlinedButton({ open = true }) { Text(stringResource(R.string.backups_open), fontSize = 12.sp, maxLines = 1) }
    }
    if (open) BackupsDialog { open = false }
}
