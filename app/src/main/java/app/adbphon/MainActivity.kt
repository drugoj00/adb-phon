@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.adbphon

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.focusable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.layout
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
import androidx.compose.ui.input.pointer.pointerInput
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
    /** Закрытие приложения: Bluetooth выключается, если его включили мы (раздел «Мышь ПК»). */
    override fun onDestroy() {
        if (isFinishing && !PadKeeper.enabled(applicationContext)) { BtHid.disconnectNow("закрытие приложения"); BtPower.leaveNow(applicationContext) }
        super.onDestroy()
    }

    override fun onResume() { super.onResume(); CloudReturn.resumedAt = System.currentTimeMillis(); CloudReturn.cancelNotice(this); PermFlow.onResume(this); Invites.start(this); CallListen.start(this); VpnSession.onStart(this) }
    override fun onPause() { super.onPause(); Invites.stopUi() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermFlow.onPermissionResult(this, requestCode, grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PadKeeper.start(this)
        AutoPairService.ensureEnabled(this)
        AppBackup.autoIfDue(this)
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

/** Режим «во весь экран» (7.0): строка состояния и кнопки навигации Android скрыты, возвращаются свайпом от края. */
object AppFull {
    var on by mutableStateOf(false)
    private var loaded = false
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE)
    fun load(ctx: Context) { if (loaded) return; loaded = true; on = sp(ctx).getBoolean("fullscreen", false) }
    fun set(ctx: Context, v: Boolean) { on = v; sp(ctx).edit().putBoolean("fullscreen", v).apply() }
}

/** Размер шрифта и размер элементов (7.0): множители, применяются ко всему интерфейсу программы. */
object AppScale {
    val steps = listOf(0.85f, 1.0f, 1.15f, 1.3f)
    var font by mutableStateOf(1.0f)
    var ui by mutableStateOf(1.0f)
    private var loaded = false
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE)
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        font = sp(ctx).getFloat("scale_font", 1.0f)
        ui = sp(ctx).getFloat("scale_ui", 1.0f)
    }
    fun setFont(ctx: Context, v: Float) { font = v; sp(ctx).edit().putFloat("scale_font", v).apply() }
    fun setUi(ctx: Context, v: Float) { ui = v; sp(ctx).edit().putFloat("scale_ui", v).apply() }
}

/** Цвета интерфейса (7.0): по умолчанию прежняя бирюзовая схема; можно выбрать другой основной цвет или «цвета из обоев» (Material You, Android 12+). */
object AppColors {
    class Accent(val key: String, val light: Color, val dark: Color)
    val accents = listOf(
        Accent("teal", Color(0xFF0F766E), Color(0xFF5EEAD4)),
        Accent("blue", Color(0xFF1D4ED8), Color(0xFF93C5FD)),
        Accent("purple", Color(0xFF6D28D9), Color(0xFFC4B5FD)),
        Accent("pink", Color(0xFFBE185D), Color(0xFFF9A8D4)),
        Accent("red", Color(0xFFB91C1C), Color(0xFFFCA5A5)),
        Accent("orange", Color(0xFFC2410C), Color(0xFFFDBA74)),
        Accent("green", Color(0xFF15803D), Color(0xFF86EFAC)),
        Accent("gray", Color(0xFF475569), Color(0xFFCBD5E1)),
    )
    var dynamic by mutableStateOf(false)
    var accent by mutableStateOf(0)
    private var loaded = false

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        dynamic = sp(ctx).getBoolean("col_dynamic", false) && Build.VERSION.SDK_INT >= 31
        accent = sp(ctx).getInt("col_accent", 0).coerceIn(0, accents.size - 1)
    }

    fun setDynamic(ctx: Context, v: Boolean) { dynamic = v && Build.VERSION.SDK_INT >= 31; sp(ctx).edit().putBoolean("col_dynamic", dynamic).apply() }
    fun setAccent(ctx: Context, i: Int) { accent = i.coerceIn(0, accents.size - 1); sp(ctx).edit().putInt("col_accent", accent).apply() }

    fun scheme(dark: Boolean, ctx: Context): androidx.compose.material3.ColorScheme {
        if (dynamic && Build.VERSION.SDK_INT >= 31) {
            return if (dark) androidx.compose.material3.dynamicDarkColorScheme(ctx) else androidx.compose.material3.dynamicLightColorScheme(ctx)
        }
        val base = if (dark) Dark else Light
        if (accent == 0) return base
        val ac = accents[accent]
        val c = if (dark) ac.light else ac.light   // контейнеры выбранного цвета одинаковы в обеих темах, основной цвет светлее в тёмной
        return base.copy(primary = if (dark) ac.dark else ac.light, secondaryContainer = c, primaryContainer = c)
    }
}

@Composable
fun AdbPhonApp(vm: MainViewModel = viewModel()) {
    val baseCtx = LocalContext.current
    val uiCtx = remember(vm.lang, baseCtx) { Lang.wrap(baseCtx, vm.lang) }
    remember(baseCtx) { AppColors.load(baseCtx); AppScale.load(baseCtx); AppFull.load(baseCtx); 0 }
    val dark = when (vm.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val fullNow = AppFull.on
    SideEffect {
        (baseCtx as? ComponentActivity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, w.decorView).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (fullNow) hide(androidx.core.view.WindowInsetsCompat.Type.systemBars()) else show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    Feedback.host = (baseCtx as? ComponentActivity)?.window?.decorView
    val dens0 = androidx.compose.ui.platform.LocalDensity.current
    CompositionLocalProvider(LocalContext provides uiCtx, LocalLangCode provides vm.lang, androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(dens0.density * AppScale.ui, dens0.fontScale * AppScale.font)) {
    ImeGateHost {
    MaterialTheme(colorScheme = AppColors.scheme(dark, baseCtx)) {
        Surface(Modifier.fillMaxSize().imeGateKeys().pointerInput(Unit) {
            // пока палец на экране (в том числе прокрутка списков), бегущие строки стоят: нагрузки нет
            awaitPointerEventScope { while (true) { val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial); Marq.touching = e.changes.any { it.pressed } } }
        }, color = MaterialTheme.colorScheme.background) {
            var tab by remember { mutableStateOf(Startup.initialTab(baseCtx)) }
            LaunchedEffect(tab) { Startup.saveLastTab(baseCtx, tab) }
            // если процесс был убит посреди игры, а настройки ориентации остались изменёнными: тихо вернуть прежние значения, как только появится подключение
            LaunchedEffect(vm.connected) { if (vm.connected) OrientAssist.restoreIfPending(baseCtx) }
            var showSettings by remember { mutableStateOf(false) }
            // первый запуск: окна по порядку с включением нужных разрешений и спецвозможностей
            LaunchedEffect(Unit) {
                val on = Build.VERSION.SDK_INT >= 30 && AutoPairService.isEnabled(baseCtx)
                if (!Setup.done(baseCtx)) Setup.open = true
                else if (Build.VERSION.SDK_INT >= 30 && !on && Setup.sp0(baseCtx).getBoolean("acc_was_on", false)) { Setup.only = "acc"; Setup.open = true }   // Android сбрасывает службу при обновлении программы
                if (on) Setup.sp0(baseCtx).edit().putBoolean("acc_was_on", true).apply()
            }
            if (Setup.open) FirstRunSetup()
            if (Setup.adbAsk) AdbFirstAsk()
            if (Setup.devAsk) DevOptionsAsk()
            if (Setup.askKey != null) PermAsk()
            val snack = remember { SnackbarHostState() }
            val snackScope = androidx.compose.runtime.rememberCoroutineScope()
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
            // история вкладок: «Назад» возвращает на прежнюю вкладку (а не всегда на «Связь»); запоминание вкладки и папок «Назад» не трогает
            val tabHist = remember { androidx.compose.runtime.mutableStateListOf<Int>() }
            var lastTab by remember { mutableStateOf(tab) }
            // Правило без кругов: в истории нет текущей вкладки и нет повторов, «Назад» берёт последнюю запись и убирает её, поэтому история только убывает.
            // Раньше «текущая» вкладка оставалась глубже в истории (1→2→1: в истории [..,1,2] при открытой 1), и «Назад» ходил 2, 1, 2, 1 по кругу.
            LaunchedEffect(tab) {
                if (tab != lastTab) {
                    tabHist.removeAll { it == lastTab || it == tab }
                    tabHist.add(lastTab); while (tabHist.size > 8) tabHist.removeAt(0)
                    lastTab = tab
                }
            }
            // «Выход»: полный выход из программы; остановка помощника геймпада, оверлея и ADB. Идёт операция с файлами: сначала вопрос.
            var exitAsk by remember { mutableStateOf(false) }
            val exitNow: () -> Unit = {
                snackScope.launch {
                    runCatching { VirtualPad.stop(true); OverlayPadService.stop(baseCtx) }
                    runCatching { NetKeep.releaseAll() }
                    // идущие операции отменяются и убирают за собой временные файлы (иначе остаётся недокопированный .fmpart), затем останавливается служба операций
                    Fm.jobs.forEach { it.cancelled = true }
                    kotlinx.coroutines.withTimeoutOrNull(3000) { while (Fm.jobs.isNotEmpty()) kotlinx.coroutines.delay(100) }
                    runCatching { baseCtx.stopService(android.content.Intent(baseCtx, FmOpService::class.java)) }
                    runCatching { BtHid.cleanDisconnect("выход", 800) }
                    PadKeeper.stopForExit(baseCtx)
                    runCatching { kotlinx.coroutines.withTimeoutOrNull(20000) { VpnSession.end(baseCtx, "выход из программы") } }   // другой VPN возвращается как был
                    kotlinx.coroutines.delay(1200)
                    vm.shutdown()
                    act?.finishAndRemoveTask()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
            val exitClick: () -> Unit = { if (Fm.jobs.isNotEmpty()) exitAsk = true else exitNow() }
            if (exitAsk) LAlertDialog(
                onDismissRequest = { exitAsk = false },
                title = { Text(stringResource(R.string.exit_busy)) },
                confirmButton = { TextButton({ exitAsk = false; exitNow() }) { Text(stringResource(R.string.exit_label), color = androidx.compose.ui.graphics.Color(0xFFD32F2F)) } },
                dismissButton = { TextButton({ exitAsk = false }) { Text(stringResource(R.string.btn_cancel)) } },
            )
            BackHandler {
                when {
                    showSettings -> showSettings = false
                    tab == 2 && Fm.onBack() -> {}
                    tabHist.removeAll { it == tab }.let { tabHist.isNotEmpty() } -> { val back = tabHist.removeAt(tabHist.lastIndex); lastTab = back; tab = back }
                    // «Связь» по исчерпанию истории: прежняя вкладка в историю НЕ кладётся (иначе следующий «Назад» вернул бы её, и «Назад» ходил бы по кругу)
                    tab != 0 -> { lastTab = 0; tab = 0 }
                    !vm.connected && vm.exitOnBack -> {
                        vm.shutdown()
                        act?.finishAndRemoveTask()
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                    else -> act?.moveTaskToBack(true)
                }
            }
            InviteDialogHost()
            vm.updateRelease?.let { r ->
                LAlertDialog(
                    onDismissRequest = { vm.dismissUpdate() },
                    title = { Text(stringResource(R.string.upd_title)) },
                    text = { Text(stringResource(R.string.upd_text, r.tag, vm.appVersion)) },
                    confirmButton = { TextButton({ vm.installUpdate() }) { Text(stringResource(R.string.upd_yes)) } },
                    dismissButton = { TextButton({ vm.dismissUpdate() }) { Text(stringResource(R.string.upd_later)) } },
                )
            }
            // В альбоме при открытой клавиатуре нижняя панель вкладок прячется: иначе поле ввода остаётся без места и не видно
            val imeOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
            val cfg = androidx.compose.ui.platform.LocalConfiguration.current
            val hideBarForIme = imeOpen && cfg.screenWidthDp > cfg.screenHeightDp
            Scaffold(
                modifier = Modifier.imePadding(),
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snack) },
                bottomBar = {
                  if (!hideBarForIme) androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                        val items = listOf(
                            R.string.tab_connect to Icons.Filled.Link,
                            R.string.tab_apps to Icons.Filled.Apps,
                            R.string.tab_files to Icons.Filled.Folder,
                            R.string.tab_remote to Icons.Filled.SettingsRemote,
                            R.string.tab_more to Icons.Filled.MoreHoriz,
                            R.string.tab_settings to Icons.Filled.Settings,
                        )
                        // телефон (портрет и ландшафт): низкая панель только из значков, название по долгому нажатию; приставка: прежняя панель с подписями
                        val phoneBar = !Marq.tv
                        val landscapeBar = phoneBar && cfg.screenWidthDp > cfg.screenHeightDp
                        val tctx = androidx.compose.ui.platform.LocalContext.current
                        var tabMenuFor by remember { mutableStateOf(-1) }
                        var tabIconFor by remember { mutableStateOf(-1) }
                        if (tabIconFor >= 0) CustomIconPicker(stringResource(items[tabIconFor].first), "t:" + tabIconFor) { tabIconFor = -1 }
                        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(if (landscapeBar) 46.dp else if (phoneBar) 56.dp else 52.dp)) {
                            items.forEachIndexed { i, (label, icon) ->
                                val selected = if (i == items.lastIndex) showSettings else (!showSettings && tab == i)
                                val col = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .7f)
                                val go = { if (i == items.lastIndex) showSettings = true else { tab = i; showSettings = false } }
                                if (phoneBar) {
                                    val name = stringResource(label)
                                    Box(
                                        Modifier.weight(if (landscapeBar) (if (i == items.lastIndex) 0.6f else if (i == 3) 1.3f else 1f) else 1f).fillMaxHeight()
                                            .combinedClickable(onClick = go, onLongClick = { tabMenuFor = i }),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        LDropdownMenu(expanded = tabMenuFor == i, onDismissRequest = { tabMenuFor = -1 }) {
                                            androidx.compose.material3.DropdownMenuItem(text = { Text(name) }, onClick = { tabMenuFor = -1; android.widget.Toast.makeText(tctx, name, android.widget.Toast.LENGTH_SHORT).show() })
                                            androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.ic_change)) }, onClick = { tabMenuFor = -1; tabIconFor = i })
                                        }
                                        if (landscapeBar) {
                                            // ландшафт телефона: значок слева, подпись справа в одну строку; выбранная вкладка залита акцентом
                                            Row(
                                                Modifier.fillMaxHeight().padding(vertical = 3.dp, horizontal = 2.dp).clip(RoundedCornerShape(10.dp))
                                                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else androidx.compose.ui.graphics.Color.Transparent)
                                                    .padding(horizontal = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                BarIcon(i, icon, if (i == items.lastIndex) name else null, col, if (i == items.lastIndex) 30.dp else 24.dp)
                                                if (i != items.lastIndex) Text(name, Modifier.padding(start = 6.dp), fontSize = (13f / (AppScale.font * AppScale.ui)).sp, maxLines = 1, color = col, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                            }
                                        } else {
                                            // портрет телефона: значок и подпись у всех вкладок (и у «Опций») на одной линии; выбранная вкладка — акцентная таблетка вокруг значка и подписи, без полоски у края панели
                                            if (i == items.lastIndex) {
                                                // шестерёнка без подписи: значок на 25 % крупнее, строго по центру кнопки той же высоты, что у остальных вкладок
                                                // ось шестерёнки совпадает с осью правой колонки кнопок «Файлов» (если колонка справа): сдвиг считается от ширины ячейки и общих размеров колонки
                                                val alignToStrip = false   // ось правой колонки теперь у «Выхода» (он в самом правом углу)
                                                Box(
                                                    Modifier.fillMaxSize().layout { m, c ->
                                                        val pl = m.measure(c)
                                                        layout(pl.width, pl.height) { pl.place(if (alignToStrip) pl.width / 2 - FmMetrics.stripCenterFromRight.roundToPx() else 0, 0) }
                                                    },
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Box(
                                                        Modifier.size(width = 52.dp, height = 40.dp).clip(RoundedCornerShape(14.dp))
                                                            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else androidx.compose.ui.graphics.Color.Transparent),
                                                        contentAlignment = Alignment.Center,
                                                    ) { BarIcon(i, icon, name, col, 28.dp) }
                                                }
                                            } else Column(
                                                Modifier.clip(RoundedCornerShape(14.dp))
                                                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else androidx.compose.ui.graphics.Color.Transparent)
                                                    .padding(horizontal = 5.dp, vertical = 3.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                            ) {
                                                BarIcon(i, icon, null, col, 22.dp)
                                                Text(name, fontSize = (10f / (AppScale.font * AppScale.ui)).sp, lineHeight = (12f / (AppScale.font * AppScale.ui)).sp, maxLines = 1, color = col, fontFamily = CondFont, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                            }
                                        }
                                    }
                                } else run {
                                // приставка: значок слева, подпись справа в одну строку; выбранная вкладка залита, у фокуса пульта рамка
                                var fcs by remember { mutableStateOf(false) }
                                Row(
                                    Modifier.weight(1f).fillMaxHeight().padding(2.dp).clip(RoundedCornerShape(10.dp))
                                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else androidx.compose.ui.graphics.Color.Transparent)
                                        .then(if (fcs) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
                                        .onFocusChanged { fcs = it.isFocused }
                                        .clickable { go() },
                                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    BarIcon(i, icon, if (i == items.lastIndex) stringResource(label) else null, col, if (i == items.lastIndex) 35.dp else 28.dp)
                                    if (i != items.lastIndex) Text(stringResource(label), Modifier.padding(start = 8.dp), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = col, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                }
                                }
                                if (i == items.lastIndex) {
                                    // «Выход»: красный значок аварийного выхода в самом правом углу, правее шестерёнки; фокус и ОК пультом, название по долгому нажатию
                                    val exitName = stringResource(R.string.exit_label)
                                    var exFocus by remember { mutableStateOf(false) }
                                    Box(
                                        Modifier.weight(if (landscapeBar) 0.7f else 1f).fillMaxHeight().defaultMinSize(minWidth = 48.dp)
                                            .onFocusChanged { exFocus = it.isFocused }
                                            // как у соседних вкладок: при наведении, выборе пультом и нажатии ячейка слегка темнеет (стандартная подсветка), на приставке ещё и рамка фокуса; сам значок не меняется
                                            .then(if (Marq.tv && exFocus) Modifier.padding(2.dp).border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
                                            .combinedClickable(onClick = exitClick, onLongClick = { android.widget.Toast.makeText(tctx, exitName, android.widget.Toast.LENGTH_SHORT).show() })
                                            .semantics { this.contentDescription = exitName },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        // ось значка совпадает с осью правой колонки кнопок «Файлов» (если колонка справа), как раньше у шестерёнки
                                        val alignExit = false   // кнопки панели вкладок разнесены равномерно: все ячейки одной ширины, значки по центру ячеек
                                        Box(
                                            Modifier.fillMaxSize().layout { m, c ->
                                                val pl = m.measure(c)
                                                layout(pl.width, pl.height) { pl.place(if (alignExit) pl.width / 2 - FmMetrics.stripCenterFromRight.roundToPx() else 0, 0) }
                                            },
                                            contentAlignment = Alignment.Center,
                                        ) { ExitSignIcon(if (Marq.tv) 34.dp else 30.dp) }
                                    }
                                }
                            }
                        }
                    }
                },
            ) { pad ->
                Column(Modifier.padding(pad).fillMaxSize()) {
                    Spacer(Modifier.height(4.dp))
                    if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (showSettings) SettingsScreen(vm) else when (tab) {
                        0 -> ConnectScreen(vm)
                        1 -> NeedConnection(vm) { if (vm.remoteHost != null) LocalOnly(vm) else AppsScreen(vm) }
                        2 -> FileManagerScreen(vm) { tab = 0 }
                        3 -> NeedConnection(vm) { RemoteScreen(vm) }
                        else -> MoreScreen(vm)
                    }
                }
            }
            // окно игры открывается отдельной Activity (только альбом)
            LaunchedEffect(Pad.playing) {
                if (Pad.playing) {
                    // без подключения к ПК окно не открывается: понятное сообщение на текущем экране, а не мигающее окно
                    if (BtHid.hostName == null && !BtHid.dryRun && !BtHid.switching) {
                        Pad.playing = false
                        // второе действие: пробный режим без компьютера (отдельная корутина: смена Pad.playing отменила бы эту)
                        val msg = baseCtx.getString(R.string.pad_no_host); val act2 = baseCtx.getString(R.string.pad_try_nopc)
                        snackScope.launch { if (snack.showSnackbar(msg, actionLabel = act2, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) startPadTrial() }
                    }
                    else baseCtx.startActivity(android.content.Intent(baseCtx, GamepadActivity::class.java))
                }
            }
        }
    }
    }
    }
}

/** Ландшафт: ширина больше высоты (и не совсем узкий экран). Экран делится на две равные половины. */
fun isLandscapeBox(w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp): Boolean = w > h && w >= 480.dp

@Composable
fun LocalOnly(vm: MainViewModel) {
    // «Софт» показывает приложения только этого устройства: если программа сейчас управляет другим, не пустая заглушка, а подсказка и кнопка отключения
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.local_only), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.local_only_hint, vm.names[vm.remoteHost ?: ""] ?: vm.remoteHost.orEmpty()), fontSize = 14.sp)
            OutlinedButton(onClick = { vm.disconnect() }) { Text(stringResource(R.string.btn_disconnect)) }
        }
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
    TabColumn("tab.connect", Modifier.fillMaxSize(), 12.dp, Arrangement.spacedBy(8.dp)) {
        if (android.os.Build.VERSION.SDK_INT >= 30) WifiDebugCard(vm)
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
        InvitesSection()
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
                            val favSpec = CustomIcons.spec("v:" + f.host)
                            if (favSpec != null) CustomIconView(favSpec, 24.dp) else Icon(Icons.Filled.Star, null, tint = if (cur) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
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
                    val hasSu = Adb.suPresent
                    if (!hasSu) Text(stringResource(R.string.root_none), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { vm.connectRoot() }, enabled = !vm.busy && hasSu) { Text(stringResource(R.string.root_connect_btn)) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        androidx.compose.material3.Switch(useRoot && hasSu, { useRoot = it; Cfg.setUseRoot(ctxR, it); if (!it) { Adb.disableRoot(); Hooks.refresh() } }, enabled = hasSu)
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
    var pickDevIcon by remember { mutableStateOf(false) }
    if (pickDevIcon) CustomIconPicker(f.host, "v:" + f.host) { pickDevIcon = false }
    LDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.ic_change)) }, onClick = { onDismiss(); pickDevIcon = true })
        if (vm.isCurrent(f)) androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_disconnect)) }, onClick = { onDismiss(); vm.disconnect() })
        else androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_connect)) }, onClick = { onDismiss(); vm.connectFound(f) })
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.gpi_menu)) }, onClick = { onDismiss(); vm.installGamepadTest(f) })
    }
}

// ---------------------------------------------------------------- приложения

private object IconCache {
    val map = HashMap<String, ImageBitmap>()
}

/** Значок вкладки нижней панели с учётом своего («Изменить значок…» по долгому нажатию). */
@Composable
fun BarIcon(i: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String?, tint: Color, size: androidx.compose.ui.unit.Dp) {
    val cs = CustomIcons.spec("t:$i")
    if (cs != null) CustomIconView(cs, size) else Icon(icon, desc, tint = tint, modifier = Modifier.size(size))
}

@Composable
fun AppIcon(pkg: String, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val ctx = LocalContext.current
    val customApp = CustomIcons.spec("a:" + pkg)
    if (customApp != null) { CustomIconView(customApp, size); return }
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

/** Кнопка для рядов с переносом (ландшафт): ширина как у кнопки в портрете (треть ряда), высота та же. */
@Composable
private fun EqFlowButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick, Modifier.width(112.dp).defaultMinSize(minHeight = 40.dp),
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
    Act.CACHE -> R.string.act_cache
    Act.SAVE -> R.string.act_save
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AppsScreen(vm: MainViewModel) {
    val visible = vm.visibleApps()
    var confirm by remember { mutableStateOf<Act?>(null) }
    var installHub by remember { mutableStateOf(false) }
    var versionsFor by remember { mutableStateOf<AppEntry?>(null) }
    var showBackups by remember { mutableStateOf(false) }
    var showSettingsBk by remember { mutableStateOf(false) }
    // панель действий открывается круглой кнопкой с галочкой, а не сама при выборе приложения
    var showActions by remember { mutableStateOf(false) }
    // долгий тап по приложению: окно с полными сведениями, которые не поместились в строке
    var infoFor by remember { mutableStateOf<AppEntry?>(null) }
    // последнее отмеченное в списке приложение: его сведения показываются справа от списка
    var lastTap by remember { mutableStateOf<String?>(null) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(vm.selected.isEmpty()) { if (vm.selected.isEmpty()) showActions = false }
    // 10.11.2026 (владелица): вошла в «Софт», а связи ADB ещё нет: сразу окно выбора подключения (то же, что вкладка «Связь»); закрывается само, когда связь появилась, или кнопкой
    var connDismissed by remember { mutableStateOf(false) }
    if (!vm.connected && !connDismissed) androidx.compose.ui.window.Dialog(onDismissRequest = { connDismissed = true }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        LangScopePublic {
            androidx.compose.material3.Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.92f), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.conn_choose_title), Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        TextButton({ connDismissed = true }) { Text(stringResource(R.string.btn_close)) }
                    }
                    Box(Modifier.weight(1f)) { ConnectScreen(vm) }
                }
            }
        }
    }
    // компактный вид (просьба владелицы 26.10.2026): без лишней высоты у флажков и чипов, поиск вдвое ниже, строка приложения в две строки
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    val land = isLandscapeBox(maxWidth, maxHeight)
    val roomy = maxHeight >= 560.dp
    val curPkg = lastTap?.takeIf { it in vm.selected } ?: vm.selected.firstOrNull()
    val curApp = vm.apps.firstOrNull { it.pkg == curPkg }
    val ramCtx = LocalContext.current
    val ramScope = rememberCoroutineScope()
    // какое окно активно (как активная панель в «Файлах»): 0 список, 1 сведения; рамка активного окна толще и цветная
    var pane by remember { mutableStateOf(0) }
    val compactLand = land && !roomy
    // строка «Установить APK / Копии / Очистить ОЗУ»; на низком экране телефона в ландшафте она уходит вниз окна сведений
    val installRow: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EqTextButton(stringResource(R.string.act_install)) { installHub = true }
            EqTextButton(stringResource(R.string.act_fullbackups)) { showBackups = true }
            if (vm.remoteHost == null) EqTextButton(stringResource(R.string.act_ram_clean)) { ramScope.launch { vm.message = Ram.clean(ramCtx) } }
        }
    }
    // поиск, фильтры, счётчик и кнопки: в портрете сверху, в ландшафте в правой половине
    val controlsUi: @Composable () -> Unit = {
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
        val filters = listOf(
            Filter.USER to R.string.f_user, Filter.SYSTEM to R.string.f_system, Filter.ALL to R.string.f_all, Filter.DISABLED to R.string.f_disabled,
            Filter.REMOVED to R.string.f_removed, Filter.BLOAT to R.string.f_bloat,
        )
        if (land && roomy) {
            // ландшафт: фильтры переносятся на следующую строку, без горизонтальной прокрутки
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                filters.forEach { (f, s) -> FilterChip(vm.filter == f, { vm.filter = f }, { Text(stringResource(s), fontSize = 12.sp, maxLines = 1) }) }
            }
        } else Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            filters.forEach { (f, s) ->
                FilterChip(vm.filter == f, { vm.filter = f }, { Text(stringResource(s), fontSize = 12.sp, maxLines = 1) })
            }
        }
        if (vm.filter == Filter.BLOAT) {
            Text(stringResource(R.string.bloat_hint), fontSize = 12.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f), modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        Text(stringResource(R.string.apps_count, visible.size, vm.selected.size), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        val canAct = vm.selected.isNotEmpty()
        val prim = MaterialTheme.colorScheme.primary
        val checkBtn: @Composable () -> Unit = {
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
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                EqTextButton(stringResource(R.string.select_all)) { vm.selectAllVisible() }
                checkBtn()
                EqTextButton(stringResource(R.string.select_none)) { vm.clearSelection() }
                if (vm.filter == Filter.BLOAT) EqTextButton(stringResource(R.string.bloat_pick_safe)) { vm.selectSafeBloat() }
            }
            if (!compactLand) Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EqTextButton(stringResource(R.string.act_install)) { installHub = true }
                EqTextButton(stringResource(R.string.act_fullbackups)) { showBackups = true }
                if (vm.remoteHost == null) EqTextButton(stringResource(R.string.act_ram_clean)) { ramScope.launch { vm.message = Ram.clean(ramCtx) } }
            }
        }
    }
    // список приложений: в портрете под кнопками, в ландшафте в левой половине
    val listUi: @Composable (Modifier) -> Unit = { listMod ->
        val ls = rememberSavedLazyList("tab.soft.list")
        val shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
        val sbl = scrollbarLeft()
        val listOn = pane == 0
        Box(
            listMod.padding(horizontal = 8.dp, vertical = 6.dp).clip(shape)
                .border(if (listOn) 2.dp else 1.dp, if (listOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = .5f), shape)
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial); pane = 0 } }
                .onFocusChanged { if (it.hasFocus) pane = 0 },
        ) {
        LazyColumn(
            Modifier.fillMaxSize().verticalScrollbar(ls), state = ls,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = if (sbl) SbMath.RESERVE_DP.dp else 0.dp, end = if (sbl) 0.dp else SbMath.RESERVE_DP.dp, top = 6.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(visible, key = { it.pkg }) { a ->
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = { vm.toggle(a.pkg); lastTap = a.pkg },
                            onLongClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); infoFor = a },
                        )
                        .padding(start = 12.dp, top = 4.dp, end = 3.dp, bottom = 4.dp),   // 10.11.2026: справа 3 dp, галочка у самого края
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(a.pkg, 32.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        // длинное название переносится на несколько строк
                        Text(a.label, fontFamily = UiPrefs.listFont, fontSize = 15.sp, lineHeight = 17.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
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
                            fontFamily = UiPrefs.listFont, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // флажок выбора справа, как в «Файлах»
                    SmallCheck(a.pkg in vm.selected, false) { vm.toggle(a.pkg); lastTap = a.pkg }   // 10.11.2026: маленькая галочка у края, как в «домике»
                }
            }
        }
        ScrollbarHandle(ls, Modifier.align(if (sbl) Alignment.CenterStart else Alignment.CenterEnd))
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
        if (showBackups) FullBackupsDialog({ showBackups = false; vm.loadApps() }, { showBackups = false; showSettingsBk = true })
        if (showSettingsBk) LAlertDialog(
            onDismissRequest = { showSettingsBk = false },
            title = { Text(stringResource(R.string.bk2_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { BackupSectionV2(vm) } },
            confirmButton = { TextButton({ showSettingsBk = false }) { Text(stringResource(R.string.btn_close)) } },
        )
        infoFor?.let { a -> AppInfoDialog(a) { infoFor = null } }
    val actionsUi: @Composable () -> Unit = {
        // в ландшафте кнопки действий видны всегда, когда что-то выбрано; в портрете их открывает кнопка с галочкой
        if ((showActions || land) && vm.selected.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                EqChipRow(listOf(act(Act.CACHE), act(Act.CLEAR), act(Act.SAVE)))
                EqChipRow(listOf(lFull to { vm.fullBackup() }))
            }
        }
    }
    if (land) {
        // ландшафт: список приложений слева (половина), справа сверху те же кнопки, что в портрете (размер и порядок прежние), панель действий под ними
        SplitHalves(Modifier.fillMaxSize(), spacing = 0.dp,
            rest = { Box(Modifier.weight(1f).fillMaxHeight()) { listUi(Modifier.fillMaxSize()) } },
            controls = {
                if (roomy) {
                    // достаточно высоты: окно сведений занимает всё свободное место, кнопки действий прижаты к низу экрана
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        controlsUi()
                        AppInfoPanel(curApp, vm.selected.size, Modifier.weight(1f), scroll = true, active = pane == 1, onActive = { pane = 1 })
                        actionsUi()
                    }
                } else {
                    // телефон в ландшафте: сверху поиск и кнопки, ниже окно сведений на весь остаток высоты; оно прокручивается само, кнопки действий в нём внизу
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        AppInfoPanel(curApp, vm.selected.size, Modifier.weight(1f), scroll = true, big = true, active = pane == 1, onActive = { pane = 1 }, top = { controlsUi(); installRow() }, bottom = { actionsUi() })
                    }
                }
            })
    } else {
        Column(Modifier.fillMaxSize()) {
            controlsUi()
            listUi(Modifier.weight(1f))
            actionsUi()
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
                Column(Modifier.barScroll()) {
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

@Composable
fun rememberAppExtra(a: AppEntry?): AppExtra? {
    val extra by produceState<AppExtra?>(null, a?.pkg) { value = null; if (a != null) value = AppExtras.load(a.pkg, a.paths) }
    return extra
}

/**
 * Сведения о программе: общий вид для панели во вкладке «Софт» и для окна по долгому нажатию.
 * [twoCols]: два блока рядом (ландшафт): слева пакет, версия, состояние, память; справа размеры и пути APK. [big]: крупнее шрифт.
 */
@Composable
fun AppInfoBlocks(a: AppEntry, extra: AppExtra?, twoCols: Boolean, big: Boolean, header: Boolean, full: Boolean = false, stack: Boolean = false) {
    val ctx = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val known = Debloat.known[a.pkg]
    val green = if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
    val blue = if (dark) Color(0xFF64B5F6) else Color(0xFF1565C0)
    val yellow = if (dark) Color(0xFFFFD54F) else Color(0xFFD99A00)
    val red = if (dark) Color(0xFFEF5350) else Color(0xFFD32F2F)
    val orange = if (dark) Color(0xFFFFA726) else Color(0xFFE65100)
    val stText: String
    val stColor: Color
    when {
        !a.system -> { stText = stringResource(R.string.st_user); stColor = green }
        known?.risk == Risk.SAFE -> { stText = stringResource(R.string.tag_system) + ", " + stringResource(Debloat.riskLabel(Risk.SAFE)); stColor = blue }
        known != null -> { stText = stringResource(R.string.tag_system) + ", " + stringResource(Debloat.riskLabel(known.risk)); stColor = red }
        else -> { stText = stringResource(R.string.tag_system); stColor = yellow }
    }
    val enabledS = stringResource(R.string.st_enabled)
    val disabledS = stringResource(R.string.tag_disabled)
    val removedS = stringResource(R.string.tag_removed)
    val abi = extra?.abi
    val state = androidx.compose.ui.text.buildAnnotatedString {
        withStyle(androidx.compose.ui.text.SpanStyle(color = stColor, fontWeight = FontWeight.Bold)) { append(stText) }
        append(", " + (if (a.enabled) enabledS else disabledS))
        if (!a.installed) append(", $removedS")
        if (abi != null) { append(" · "); withStyle(androidx.compose.ui.text.SpanStyle(color = orange, fontWeight = FontWeight.Bold)) { append(abi) } }
    }
    fun sz(v: Long?): String = if (extra == null) "…" else if (v == null) "—" else android.text.format.Formatter.formatShortFileSize(ctx, v)
    val kSp = if (big) 14.sp else 11.sp
    val vSp = if (big) 18.sp else 12.sp
    @Composable fun lineA(k: Int, v: androidx.compose.ui.text.AnnotatedString, lines0: Int = 2) {
        // в окне по долгому нажатию текст виден целиком (окно прокручивается), без «…»
        val lines = if (full) Int.MAX_VALUE else lines0
        if (twoCols || stack) Column {
            Text(stringResource(k), fontSize = kSp, lineHeight = kSp * 1.2f, color = dim)
            Text(v, fontSize = vSp, lineHeight = vSp * 1.2f, maxLines = lines, overflow = TextOverflow.Ellipsis)
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(k), fontSize = kSp, lineHeight = kSp * 1.25f, color = dim, modifier = Modifier.width(if (big) 130.dp else 92.dp))
            Text(v, fontSize = vSp, lineHeight = vSp * 1.2f, maxLines = lines, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
    @Composable fun line(k: Int, v: String, lines: Int = 2) = lineA(k, androidx.compose.ui.text.AnnotatedString(v), lines)
    @Composable fun leftBlock() {
        line(R.string.info_pkg, a.pkg)
        if (a.version.isNotEmpty()) line(R.string.info_ver, a.version)
        lineA(R.string.info_state, state, 3)
        if (known != null) line(R.string.info_cat, stringResource(Debloat.catLabel(known.cat)) + ", " + stringResource(Debloat.riskLabel(known.risk)))
        AppExtras.storageRes(a.paths)?.let { line(R.string.info_storage, stringResource(it)) }
    }
    @Composable fun rightBlock() {
        line(R.string.info_size_apk, sz(extra?.apk))
        line(R.string.info_size_data, sz(extra?.data))
        line(R.string.info_size_cache, sz(extra?.cache))
        if ((extra?.obb ?: 0L) > 0) line(R.string.info_size_obb, sz(extra?.obb))
        if (a.paths.isNotEmpty()) line(R.string.info_paths, a.paths.joinToString("\n"), if (twoCols) 4 else 3)
    }
    Column(verticalArrangement = Arrangement.spacedBy(if (full) 16.dp else if (twoCols) 6.dp else 4.dp)) {   // в окне по долгому нажатию между пунктами зазор, внутри пункта строки вплотную
        if (header) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.runtime.key(a.pkg) { AppIcon(a.pkg, if (big) 36.dp else 32.dp) }
            Text(a.label, fontSize = if (big) 17.sp else 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (twoCols) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { leftBlock() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { rightBlock() }
        } else { leftBlock(); rightBlock() }
    }
}

/** Справа от списка (ландшафт): окно со сведениями о выбранной программе. */
@Composable
fun AppInfoPanel(a: AppEntry?, selectedCount: Int, modifier: Modifier = Modifier, scroll: Boolean = false, big: Boolean = false, active: Boolean = false, onActive: () -> Unit = {}, top: (@Composable () -> Unit)? = null, bottom: (@Composable () -> Unit)? = null) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    val extra = rememberAppExtra(a)
    val sc = rememberScrollState()
    val sbLeft = scrollbarLeft()
    val sbScope = rememberCoroutineScope()
    val stepPx = with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.toPx() }
    var selfFocus by remember { mutableStateOf(false) }
    // рамка как у активной панели в «Файлах»: активное окно (нажатие или фокус пульта) толще и цветное; пультом окно прокручивается стрелками
    Box(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .border(if (active) 2.dp else 1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = .5f), shape)
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial); onActive() } }
            .onFocusChanged { selfFocus = it.isFocused; if (it.hasFocus) onActive() }
            .onPreviewKeyEvent { e ->
                if (!scroll || !selfFocus || e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false
                else when (e.key) {
                    androidx.compose.ui.input.key.Key.DirectionDown -> if (sc.canScrollForward) { sbScope.launch { sc.animateScrollBy(stepPx) }; true } else false
                    androidx.compose.ui.input.key.Key.DirectionUp -> if (sc.canScrollBackward) { sbScope.launch { sc.animateScrollBy(-stepPx) }; true } else false
                    else -> false
                }
            }
            .focusable(),
    ) {
        Column(
            Modifier.then(if (scroll) Modifier.fillMaxSize().verticalScrollbar(sc, sbLeft).verticalScroll(sc) else Modifier)
                .padding(start = if (scroll && sbLeft) (10 + SbMath.RESERVE_DP).dp else 10.dp, end = if (scroll && !sbLeft) (10 + SbMath.RESERVE_DP).dp else 10.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            top?.invoke()
            if (a == null) { Text(stringResource(R.string.info_pick), fontSize = 12.sp, color = dim); return@Column }
            AppInfoBlocks(a, extra, twoCols = false, big = big, header = true)
            if (selectedCount > 1) Text(stringResource(R.string.info_more_sel, selectedCount), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            bottom?.invoke()
        }
        if (scroll) ScrollbarHandle(sc, Modifier.align(if (sbLeft) Alignment.CenterStart else Alignment.CenterEnd))
    }
}

/** Полные сведения о приложении (долгое нажатие): в портрете одно окно, в ландшафте широкое окно с двумя блоками. */
@Composable
fun AppInfoDialog(a: AppEntry, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val copied = stringResource(R.string.copied)
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val land = cfg.screenWidthDp > cfg.screenHeightDp
    val extra = rememberAppExtra(a)
    var pickIcon by remember { mutableStateOf(false) }
    if (pickIcon) CustomIconPicker(a.label, "a:" + a.pkg) { pickIcon = false }
    LAlertDialog(
        onDismissRequest = onClose,
        wide = land,
        title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.runtime.key(a.pkg) { AppIcon(a.pkg, 40.dp) }
            Text(a.label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        } },
        text = { Column(Modifier.then(Modifier.androidxHeightIn((cfg.screenHeightDp * 0.55f).dp)).barScroll()) { AppInfoBlocks(a, extra, twoCols = false, big = true, header = false, full = true, stack = !land) } },
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.btn_close)) } },
        dismissButton = {
            Row {
                TextButton({ pickIcon = true }) { Text(stringResource(R.string.ic_change)) }
                TextButton({
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("package", a.pkg))
                    Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.info_copy)) }
            }
        },
    )
}

// ---------------------------------------------------------------- консоль

@Composable
fun ConsoleScreen(vm: MainViewModel) {
    // 7.0: консоль как у Pulsar (живой сеанс), см. ConsoleUi.kt и Term.kt
    ConsolePage()
}

// ---------------------------------------------------------------- устройство

@Composable
fun DeviceScreen(vm: MainViewModel) {
    var dpi by remember { mutableStateOf("") }
    var reboot by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (vm.deviceInfo.isEmpty()) vm.refreshInfo() }
    TabColumn("tab.device", Modifier.fillMaxSize(), 12.dp, Arrangement.spacedBy(8.dp)) {
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
    Column(if (embedded) Modifier else Modifier.tabScroll(rememberSavedScroll("tab.about")).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        var licOpen by remember { mutableStateOf(false) }
        TextButton(onClick = { licOpen = !licOpen }) { Text(stringResource(R.string.about_licenses)) }
        if (licOpen) { Text(stringResource(R.string.licenses_text), fontSize = 11.sp, fontFamily = FontFamily.Monospace); Text(stringResource(R.string.licenses_brands), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}

private fun Modifier.androidxHeightIn(max: androidx.compose.ui.unit.Dp): Modifier = this.then(Modifier.heightIn(max = max))
