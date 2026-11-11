package app.adbphon

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Добавить устройство» в группе «Устройства»: Windows-ПК (адрес Tailscale и код программы ПК) или Android (телефон, планшет, приставка:
 * адрес сети, порт ADB по умолчанию 5555, второй адрес по желанию). Для уже сохранённого — правка. Кнопка «Приглашение и мой код…»
 * открывает окно приглашений (Tailscale-сеть по приглашению с ПК, «Мой код программы»).
 */
@Composable
fun DevAddDialog(editPc: PcHost?, editAdb: AdbDev?, close: () -> Unit) {
    val ctx = LocalContext.current
    var android by remember { mutableStateOf(editAdb != null) }
    var name by remember { mutableStateOf(editPc?.name ?: editAdb?.name ?: "") }
    var addr by remember { mutableStateOf(editPc?.ip ?: editAdb?.addr ?: "") }
    var alt by remember { mutableStateOf(editAdb?.alt ?: "") }
    var code by remember { mutableStateOf(editPc?.code ?: "") }
    var err by remember { mutableStateOf("") }
    var inv by remember { mutableStateOf(false) }
    val fixed = editPc != null || editAdb != null
    if (inv) { InvitesDialog { inv = false }; return }
    LAlertDialog(
        onDismissRequest = close, ime = true,
        title = { HintTitle(stringResource(R.string.dev_add_title), stringResource(if (android) R.string.vw_hint_andr else R.string.pc_add_hint), titleSize = 18.sp) },
        text = {
            Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!fixed) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!android) Button({ android = false }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.dev_kind_pc), fontSize = 13.sp) } else OutlinedButton({ android = false }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.dev_kind_pc), fontSize = 13.sp) }
                    if (android) Button({ android = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.dev_kind_andr), fontSize = 13.sp) } else OutlinedButton({ android = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.dev_kind_andr), fontSize = 13.sp) }
                }
                CField(stringResource(R.string.pc_name), name, { name = it })
                CField(stringResource(if (android) R.string.dev_addr_andr else R.string.spc_ip), addr, { addr = it.trim() }, type = KeyboardType.Uri)
                if (android) CField(stringResource(R.string.dev_alt), alt, { alt = it.trim() }, type = KeyboardType.Uri)
                else CField(stringResource(R.string.spc_code), code, { code = it.uppercase() })
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                TextButton({ inv = true }) { Text(stringResource(R.string.dev_invite), fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton({
                if (android) {
                    if (addr.isBlank() || addr.contains(' ')) { err = ctx.getString(R.string.dev_need_addr); return@TextButton }
                    val a = AdbDev.norm(addr)
                    val l = androidSync(ctx).filter { it.key != editAdb?.key && it.key != "andr:$a" }.toMutableList()
                    l.add(AdbDev(name.trim().ifBlank { addr }, a, if (alt.isBlank()) "" else AdbDev.norm(alt)))
                    saveAndroid(ctx, l); AdbLinks.drop(editAdb?.addrs.orEmpty()); AdbStatus.up.remove("andr:$a")
                } else {
                    if (addr.isBlank() || code.filter { it.isLetterOrDigit() }.length != 12) { err = ctx.getString(R.string.spc_need_fields); return@TextButton }
                    val l = loadHosts(ctx).filter { it.ip != addr && it.ip != editPc?.ip }.toMutableList()
                    l.add(PcHost(name.trim().ifBlank { addr }, addr, code))
                    saveHosts(ctx, l)
                }
                FmRootCfg.ver++
                close()
            }) { Text(stringResource(R.string.fm_apply)) }
        },
        dismissButton = { TextButton(close) { Text(stringResource(R.string.btn_cancel)) } },
    )
}

/** Консоль Android-устройства: своя оболочка ADB на этом устройстве (не на этом телефоне). Ctrl+C прерывает команду. */
@Composable
fun AndroidConsoleDialog(d: AdbDev, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var out by remember { mutableStateOf("") }
    var line by remember { mutableStateOf("") }
    var ses by remember { mutableStateOf<AdbLink.Session?>(null) }
    var err by remember { mutableStateOf("") }
    val hist = remember { ArrayList<String>() }
    var hi by remember { mutableStateOf(0) }
    val sc = rememberScrollState()
    val ansi = remember { Regex("\u001B\\[[0-9;?]*[a-zA-Z]") }
    DisposableEffect(Unit) {
        val job = scope.launch(Dispatchers.IO) {
            try {
                val l = adbLinkFor(d.addrs)
                val s = l.session(); ses = s
                val i = s.input; val b = ByteArray(4096)
                val dec = java.io.InputStreamReader(i, Charsets.UTF_8); val cb = CharArray(2048)
                while (true) { val n = dec.read(cb); if (n < 0) break; val t = String(cb, 0, n).replace("\r", "").replace(ansi, ""); out = (out + t).takeLast(60_000) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { err = e.message ?: e.javaClass.simpleName }
        }
        onDispose { job.cancel(); ses?.close() }
    }
    LaunchedEffect(out.length) { sc.scrollTo(sc.maxValue) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).androidx_imePad().padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(d.title + " · " + stringResource(R.string.dev_console), fontSize = 15.sp, modifier = Modifier.weight(1f))
                OutlinedButton({ ses?.let { s -> scope.launch(Dispatchers.IO) { runCatching { s.write(byteArrayOf(3)) } } } }) { Text("Ctrl+C", fontSize = 12.sp) }
                OutlinedButton({ out = "" }) { Text(stringResource(R.string.acon_clear), fontSize = 12.sp) }
                OutlinedButton(close) { Text(stringResource(R.string.btn_close), fontSize = 12.sp) }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp).background(Color(0xFF101418))) {
                Text(if (err.isNotEmpty()) err else if (ses == null && out.isEmpty()) stringResource(R.string.spc_connecting) else out, color = if (err.isNotEmpty()) Color(0xFFEF9A9A) else Color(0xFFE0E0E0), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    modifier = Modifier.fillMaxSize().verticalScroll(sc).padding(6.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(line, { line = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.acon_hint), fontSize = 11.sp) })
                OutlinedButton({ if (hist.isNotEmpty()) { hi = (hi - 1).coerceAtLeast(0); line = hist[hi] } }) { Text("↑", fontSize = 14.sp) }
                Button({
                    val t = line; line = ""
                    if (t.isNotBlank()) { hist.add(t); hi = hist.size }
                    out += "$ $t\n"
                    ses?.let { s -> scope.launch(Dispatchers.IO) { runCatching { s.line(t) } } }
                }) { Text(stringResource(R.string.spc_send)) }
            }
        }
    }
}

private fun Modifier.androidx_imePad(): Modifier = this.imePadding()
