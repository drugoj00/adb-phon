package app.adbphon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/**
 * Прослойка для модулей, перенесённых из «Меню приложений»: там цвета и кнопки свои, здесь берутся из темы ADB-phon.
 */
data class TC(
    val dark: Boolean,
    val bg: Color, val tile: Color, val tileFocus: Color, val text: Color, val sub: Color, val hint: Color,
    val border: Color, val accent: Color, val chipSel: Color,
)

object LocalTh {
    val current: TC
        @Composable get() {
            val c = MaterialTheme.colorScheme
            val dark = c.background.luminance() < 0.5f
            return TC(
                dark, c.background, c.surfaceVariant.copy(alpha = if (dark) 0.5f else 0.7f), c.secondaryContainer, c.onBackground,
                c.onBackground.copy(alpha = 0.72f), c.onBackground.copy(alpha = 0.55f), c.outline, c.primary, c.primaryContainer,
            )
        }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable
fun rememberHover(): Pair<Modifier, Boolean> {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    return Modifier.hoverable(src).pointerHoverIcon(PointerIcon.Hand) to hovered
}

/** Кнопка-«таблетка» перенесённых окон. */
@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = LocalTh.current
    var focused by remember { mutableStateOf(false) }
    val (hm, hover) = rememberHover()
    Box(
        modifier
            .then(hm)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) t.chipSel else if (hover) t.tileFocus else t.tile)
            .border(if (focused) 3.dp else 1.dp, if (focused) t.accent else t.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = t.text, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
}

/** Окно, которое показывает строки на языке, выбранном в настройках ADB-phon. */
@Composable
fun HDialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(), content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        LangScopePublic {
            androidx.compose.material3.Surface(color = Color.Transparent) { content() }
        }
    }
}

/** Связь перенесённых модулей с моделью: обновить признак подключения и список приложений. */
object Hooks {
    @Volatile var refresh: () -> Unit = {}
    @Volatile var reloadApps: () -> Unit = {}
}

/** Замены того, что в «Меню приложений» хранится в Cfg. */
object Cfg {
    private fun sp(ctx: android.content.Context) = ctx.getSharedPreferences("adbphon", android.content.Context.MODE_PRIVATE)
    fun backupOnDelete(ctx: android.content.Context): Boolean = sp(ctx).getBoolean("backup_on_delete", true)
    fun setBackupOnDelete(ctx: android.content.Context, v: Boolean) { sp(ctx).edit().putBoolean("backup_on_delete", v).apply() }
    /** Пробовать права root (Magisk) для этого телефона вместо ADB. */
    fun useRoot(ctx: android.content.Context): Boolean = sp(ctx).getBoolean("use_root", true)
    fun setUseRoot(ctx: android.content.Context, v: Boolean) { sp(ctx).edit().putBoolean("use_root", v).apply() }
}

object Conn {
    val connected: Boolean get() = Adb.isConnected
    fun refresh() = Hooks.refresh()
}

/**
 * Подключение без ввода кода. Android 11+: служба спецвозможностей сама проходит по настройкам беспроводной отладки.
 * Android 10 и старше: беспроводной отладки нет, сетевой ADB включается один раз командой с компьютера, дальше программа
 * сама цепляется к порту 5555 (как в «Меню приложений»).
 */
@Composable
fun AutoConnectCard(vm: MainViewModel) {
    if (vm.connected) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val old = android.os.Build.VERSION.SDK_INT < 30
    var autoOn by remember { mutableStateOf(!old && AutoPairService.isEnabled(ctx)) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!old) Pairing.start(ctx)
        var n = 0
        while (true) {
            if (!old) autoOn = AutoPairService.isEnabled(ctx)
            // тихие попытки: порт 5555 и (Android 11+) уже сопряжённое устройство по беспроводной отладке
            if (!Adb.isConnected && !vm.busy && Cfg.useRoot(ctx) && !Adb.rootSuppressed && n == 0) Adb.tryRoot()
            if (!Adb.isConnected && !vm.busy && n % 2 == 0) {
                Adb.connectLocal(5555)
                if (!Adb.isConnected && !old && n % 8 == 0) Adb.connectWireless(ctx, null)
            }
            if (Adb.isConnected) Hooks.refresh()
            n++
            kotlinx.coroutines.delay(1800)
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (!old) Pairing.stop() } }
    androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            if (old) {
                Text(androidx.compose.ui.res.stringResource(R.string.old_title), fontWeight = FontWeight.Bold)
                Text(androidx.compose.ui.res.stringResource(R.string.old_hint), fontSize = 14.sp)
                Text(androidx.compose.ui.res.stringResource(R.string.old_waiting), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                androidx.compose.material3.OutlinedButton({
                    (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("adb", "adb tcpip 5555"))
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.copied), android.widget.Toast.LENGTH_SHORT).show()
                }) { Text(androidx.compose.ui.res.stringResource(R.string.old_copy)) }
            } else {
                Text(androidx.compose.ui.res.stringResource(R.string.autopair_title), fontWeight = FontWeight.Bold)
                Text(androidx.compose.ui.res.stringResource(R.string.autopair_hint), fontSize = 14.sp)
                if (!autoOn) androidx.compose.material3.OutlinedButton({
                    runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text(androidx.compose.ui.res.stringResource(R.string.autopair_enable)) }
                androidx.compose.material3.Button(onClick = {
                    Pairing.autoStart()
                    Pairing.start(ctx)
                    val tries = listOf(
                        android.content.Intent("android.settings.ADB_WIRELESS_SETTINGS"),
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                    )
                    for (i in tries) { if (runCatching { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) break }
                }, enabled = autoOn && !vm.busy) { Text(androidx.compose.ui.res.stringResource(R.string.autopair_run)) }
            }
        }
    }
}
