package app.adbphon

import android.content.Context
import android.content.pm.PackageInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Раздел настроек «Shizuku»: статус, запуск и остановка сервера, автозапуск, список приложений с доступом. */
@Composable
fun ShizukuSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { ShizukuHost.init() }
    @Suppress("UNUSED_EXPRESSION") ShizukuHost.tick
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(ShizukuHost.auto(ctx)) }
    var appsDlg by remember { mutableStateOf(false) }
    val on = ShizukuHost.up || ShizukuHost.running()

    HintTitle(stringResource(R.string.shz_title), stringResource(R.string.shz_hint))
    Text(
        if (on) stringResource(R.string.shz_status_on, ShizukuHost.info() ?: "") else stringResource(R.string.shz_status_off),
        fontSize = 13.sp, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .7f),
    )
    if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .8f))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({
            if (busy) return@OutlinedButton
            busy = true
            scope.launch {
                val r = ShizukuHost.start(ctx)
                msg = when (r) {
                    "" -> ctx.getString(R.string.shz_started)
                    "no_adb" -> ctx.getString(R.string.shz_err_no_adb)
                    "no_answer" -> ctx.getString(R.string.shz_err_no_answer)
                    else -> ctx.getString(R.string.shz_err_fail)
                }
                busy = false
            }
        }, Modifier.weight(1f), enabled = !busy && !on) { Text(stringResource(R.string.shz_start)) }
        OutlinedButton({
            if (busy) return@OutlinedButton
            busy = true
            scope.launch { ShizukuHost.stop(ctx); msg = ""; busy = false }
        }, Modifier.weight(1f), enabled = !busy && on) { Text(stringResource(R.string.shz_stop)) }
    }
    SettingSwitch(auto, { auto = it; ShizukuHost.setAuto(ctx, it) }, stringResource(R.string.shz_auto), stringResource(R.string.shz_auto_hint))
    OutlinedButton({ appsDlg = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.shz_apps)) }
    if (appsDlg) ShizukuAppsDialog(on) { appsDlg = false }
}

private class ShzRow(val pi: PackageInfo, val label: String, val uid: Int)

@Composable
private fun ShizukuAppsDialog(on: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val rows = remember { mutableStateListOf<ShzRow>() }
    val allowed = remember { mutableStateListOf<Int>() }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(on) {
        if (on) {
            val pm = ctx.packageManager
            val list = ShizukuHost.apps().map { pi ->
                ShzRow(pi, runCatching { pi.applicationInfo?.loadLabel(pm)?.toString() }.getOrNull() ?: pi.packageName, pi.applicationInfo?.uid ?: -1)
            }.filter { it.uid >= 0 }.sortedBy { it.label.lowercase() }
            rows.clear(); rows.addAll(list)
            allowed.clear(); allowed.addAll(list.filter { ShizukuHost.granted(it.uid) }.map { it.uid })
        }
        loaded = true
    }
    LAlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.shz_apps), fontSize = 17.sp) },
        text = {
            Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!on) Text(stringResource(R.string.shz_apps_off), fontSize = 14.sp)
                rows.forEach { r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.label, fontSize = 15.sp, maxLines = 1)
                            Text(r.pi.packageName, fontSize = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                        }
                        Switch(r.uid in allowed, { v ->
                            if (v) { ShizukuHost.grant(r.uid); if (r.uid !in allowed) allowed.add(r.uid) } else { ShizukuHost.revoke(r.uid); allowed.remove(r.uid) }
                        })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.btn_close)) } },
    )
}
