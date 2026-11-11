package app.adbphon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Выбор целевого Android-устройства для автоматического сопряжения. Устройства: избранные, последнее и найденные в сети (где ADB уже разрешён). */
@Composable
fun BtAutoDialog(vm: MainViewModel, mode: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var addr by remember { mutableStateOf("") }
    val targets = remember(vm.favs.size, vm.found.size) {
        val list = LinkedHashMap<String, Triple<String, String, Int>>()
        vm.favs.forEach { if (it.host.isNotBlank()) list["${it.host}:${it.port}"] = Triple(it.name.ifBlank { it.host }, it.host, it.port) }
        vm.lastDevice?.let { if (!it.self) list.putIfAbsent("${it.host}:${it.port}", Triple(vm.names[it.host] ?: it.host, it.host, it.port)) }
        vm.found.forEach { if (!it.self) list.putIfAbsent("${it.host}:${it.port}", Triple(vm.names[it.host] ?: it.host, it.host, it.port)) }
        list.values.toList()
    }
    fun parse(): Pair<String, Int>? {
        val t = addr.trim(); if (t.isEmpty()) return null
        val h = t.substringBefore(':'); val p = t.substringAfter(':', "5555").toIntOrNull() ?: 5555
        return h to p
    }
    LAlertDialog(
        onDismissRequest = onClose, title = { HintTitle(stringResource(R.string.bta_title), stringResource(R.string.bta_hint)) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).barScroll(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (targets.isEmpty()) Text(stringResource(R.string.bta_none), fontSize = 13.sp)
                targets.forEach { (name, h, p) ->
                    Column(Modifier.fillMaxWidth().clickable { onClose(); BtAuto.connect(ctx, h, p, mode) }.padding(vertical = 4.dp)) {
                        Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text("$h:$p", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                        TextButton({ onClose(); BtAuto.remotePower(ctx, h, p, true) }) { Text(stringResource(R.string.bta_power_on), fontSize = 12.sp) }
                    }
                }
                OutlinedButton({ vm.scanDevices() }, Modifier.fillMaxWidth()) { Text(stringResource(if (vm.scanning) R.string.bta_scanning else R.string.bta_scan), fontSize = 13.sp) }
                OutlinedTextField(addr, { addr = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.bta_addr), maxLines = 1) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button({ parse()?.let { onClose(); BtAuto.connect(ctx, it.first, it.second, mode) } }, enabled = parse() != null) { Text(stringResource(R.string.bta_go), fontSize = 12.sp) }
                    OutlinedButton({ parse()?.let { onClose(); BtAuto.remotePower(ctx, it.first, it.second, true) } }, enabled = parse() != null) { Text(stringResource(R.string.bta_power_on), fontSize = 12.sp) }
                    OutlinedButton({ parse()?.let { onClose(); BtAuto.remotePower(ctx, it.first, it.second, false) } }, enabled = parse() != null) { Text(stringResource(R.string.bta_power_off), fontSize = 12.sp) }
                }
                Text(stringResource(R.string.bta_not_android), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
            }
        },
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.fm_close)) } },
    )
}
