package app.adbphon

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Права доступа к файлам и папкам (chmod, chown) через root или ADB. 08.11.2026: «в файлах root должно быть всё, в том числе выдача разрешений папкам и файлам». */
@Composable
fun FmPermsDialog(p: FmPane, names: List<String>, close: () -> Unit) {
    val paths = remember { names.map { if (it.isEmpty()) p.path else FmIo.join(p.path, it) } }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var isDir by remember { mutableStateOf(false) }
    var bits by remember { mutableStateOf(0) }
    var owner by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }
    var octal by remember { mutableStateOf("") }
    var recursive by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var changedOwner by remember { mutableStateOf(false) }
    var fix by remember { mutableStateOf<String?>(null) }
    var failText by remember { mutableStateOf("") }
    var retry by remember { mutableStateOf(0) }
    val multi = paths.size > 1
    fun setBits(v: Int) { bits = v and 4095; octal = "%o".format(bits).padStart(3, '0') }
    LaunchedEffect(retry) {
        loading = true; failed = false; fix = null; failText = ""
        val r = FmIo.permStat(p, paths[0])
        val info = r.info
        if (info == null) { failed = true; fix = r.fix; failText = r.err.orEmpty() } else { isDir = info.isDir; owner = info.owner; group = info.group; setBits(info.mode) }
        loading = false
    }
    val prim = MaterialTheme.colorScheme.primary
    LAlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(stringResource(R.string.perm_title), fontSize = 18.sp) },
        ime = true,
        text = {
            Column(Modifier.widthIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (multi) stringResource(R.string.perm_multi, paths.size) else paths[0], fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                when {
                    loading -> Text(stringResource(R.string.perm_loading), fontSize = 14.sp)
                    failed && fix != null -> {
                        Text(stringResource(if (fix == "wifi") R.string.fm_fix_wifi_text else R.string.fm_fix_pair_text), fontSize = 14.sp)
                        androidx.compose.material3.Button({ if (fix == "wifi") { Fm.enableWifiDebug(p); retry++ } else { close(); Fm.goConnect?.invoke() } }) {
                            Text(stringResource(if (fix == "wifi") R.string.fm_fix_wifi_btn else R.string.fm_fix_pair_btn), fontSize = 13.sp)
                        }
                    }
                    failed -> Text(failText.ifEmpty { stringResource(R.string.perm_err_read) }, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace)
                    else -> {
                        val cols = listOf(R.string.perm_read, R.string.perm_write, R.string.perm_exec)
                        val rows = listOf(R.string.fm_owner, R.string.perm_group, R.string.perm_others)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("", Modifier.width(84.dp))
                            cols.forEach { Text(stringResource(it), Modifier.weight(1f), fontSize = 11.sp, color = prim, maxLines = 1) }
                        }
                        rows.forEachIndexed { r, label ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(label), Modifier.width(84.dp), fontSize = 13.sp, maxLines = 1)
                                for (c in 0..2) {
                                    val bit = 1 shl (8 - (r * 3 + c))
                                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox((bits and bit) != 0, { on -> setBits(if (on) bits or bit else bits and bit.inv()) }, Modifier.size(34.dp))
                                    }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            listOf("SUID" to 2048, "SGID" to 1024, "Sticky" to 512).forEach { (n, bit) ->
                                Row(Modifier.weight(1f).clickable { setBits(bits xor bit) }, verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox((bits and bit) != 0, { on -> setBits(if (on) bits or bit else bits and bit.inv()) }, Modifier.size(34.dp))
                                    Text(n, fontSize = 11.sp, maxLines = 1)
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PermBox(octal, { v -> val t = v.filter { it in '0'..'7' }.take(4); octal = t; t.toIntOrNull(8)?.let { bits = it and 4095 } }, Modifier.width(64.dp), mono = true)
                            listOf("644", "755", "777", "600").forEach { pr ->
                                Text(pr, Modifier.clickable { octal = pr; setBits(pr.toInt(8)) }.padding(horizontal = 6.dp, vertical = 6.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = prim)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Column(Modifier.weight(1f)) { Text(stringResource(R.string.fm_owner), fontSize = 10.sp, color = prim); PermBox(owner, { owner = it.take(40); changedOwner = true }, Modifier.fillMaxWidth()) }
                            Column(Modifier.weight(1f)) { Text(stringResource(R.string.perm_group), fontSize = 10.sp, color = prim); PermBox(group, { group = it.take(40); changedOwner = true }, Modifier.fillMaxWidth()) }
                        }
                        if (isDir || multi) Row(Modifier.fillMaxWidth().clickable { recursive = !recursive }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(recursive, { recursive = it }, Modifier.size(34.dp))
                            Text(stringResource(R.string.perm_recursive), fontSize = 12.sp, maxLines = 2)
                        }
                        if (err.isNotEmpty()) Text(err, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                busy = true; err = ""
                scope.launch {
                    val res = FmIo.applyPerms(p, paths, octal.padStart(3, '0'), if (changedOwner) owner else "", if (changedOwner) group else "", recursive)
                    busy = false
                    if (res == null) { Fm.say(Fm.str(R.string.perm_done)); Fm.load(p, p.path, remember = false); close() } else err = res
                }
            }, enabled = !loading && !failed && !busy && octal.isNotEmpty()) { Text(stringResource(R.string.fm_apply)) }
        },
        dismissButton = {
            Row {
                if (failed) TextButton({ retry++ }) { Text(stringResource(R.string.perm_retry)) }
                TextButton({ if (!busy) close() }) { Text(stringResource(R.string.btn_cancel)) }
            }
        },
    )
}

/** Низкое поле ввода (обычное поле материала слишком высокое для компактного окна). */
@Composable
private fun PermBox(value: String, onChange: (String) -> Unit, modifier: Modifier, mono: Boolean = false) {
    androidx.compose.foundation.text.BasicTextField(value, onChange, modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
        singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary))
}
