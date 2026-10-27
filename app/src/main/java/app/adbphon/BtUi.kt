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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
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

@SuppressLint("MissingPermission")
@Composable
fun BtScreen() {
    val ctx = LocalContext.current
    if (!BtHid.supported) {
        Text(stringResource(R.string.bt_old), Modifier.padding(16.dp))
        return
    }
    fun hasPerm() = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(hasPerm()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; if (ok) BtHid.start(ctx) }
    LaunchedEffect(granted) { if (granted) BtHid.start(ctx) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!granted) {
            Text(stringResource(R.string.bt_perm_hint), fontSize = 13.sp)
            Button({ ask.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text(stringResource(R.string.bt_perm_btn)) }
            return@Column
        }
        val err = BtHid.error
        if (err != null) {
            Text(stringResource(if (err == "bt_off") R.string.bt_off else R.string.bt_unsupported), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            OutlinedButton({ BtHid.start(ctx) }) { Text(stringResource(R.string.bt_retry)) }
            return@Column
        }
        val host = BtHid.hostName
        if (host == null) BtPairing(ctx) else BtControls(host)
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun BtPairing(ctx: Context) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.bt_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
        Text(stringResource(if (BtHid.registered) R.string.bt_ready else R.string.bt_preparing), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        Button({
            runCatching { ctx.startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)) }
        }) { Text(stringResource(R.string.bt_visible)) }
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val bonded: List<BluetoothDevice> = runCatching { adapter?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
        if (bonded.isNotEmpty()) {
            Text(stringResource(R.string.bt_paired), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 13.sp)
            bonded.forEach { d ->
                TextButton({ BtHid.connect(d) }, Modifier.fillMaxWidth()) {
                    Text((runCatching { d.name }.getOrNull() ?: d.address) + "  ·  " + d.address, fontSize = 13.sp, maxLines = 1, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.BtControls(host: String) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.bt_connected, host), Modifier.weight(1f), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, maxLines = 1)
        TextButton({ BtHid.disconnect() }) { Text(stringResource(R.string.ssh_disconnect), fontSize = 12.sp) }
    }
    // тачпад + полоса прокрутки справа
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { BtHid.click(1) }, onDoubleTap = { BtHid.click(1, 2) }, onLongPress = { BtHid.click(2) })
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag -> change.consume(); BtHid.move(drag.x * SENS / 2f, drag.y * SENS / 2f) }
                },
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.bt_pad_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
        ) { Text("⇅", fontSize = 18.sp) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton({ BtHid.click(1) }, Modifier.weight(1f)) { Text(stringResource(R.string.bt_left), fontSize = 12.sp, maxLines = 1) }
        OutlinedButton({ BtHid.click(4) }, Modifier.weight(1f)) { Text(stringResource(R.string.bt_middle), fontSize = 12.sp, maxLines = 1) }
        OutlinedButton({ BtHid.click(2) }, Modifier.weight(1f)) { Text(stringResource(R.string.bt_right), fontSize = 12.sp, maxLines = 1) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Enter" to 0x28, "Esc" to 0x29, "⌫" to 0x2A, "Tab" to 0x2B, "←" to 0x50, "↑" to 0x52, "↓" to 0x51, "→" to 0x4F, "Del" to 0x4C, "Home" to 0x4A, "End" to 0x4D, "PgUp" to 0x4B, "PgDn" to 0x4E).forEach { (l, c) ->
            OutlinedButton({ BtHid.key(0, c) }) { Text(l, fontSize = 12.sp, maxLines = 1) }
        }
        OutlinedButton({ BtHid.key(8, 0) }) { Text("Win", fontSize = 12.sp, maxLines = 1) }
        OutlinedButton({ BtHid.key(1, 0x06) }) { Text("Ctrl+C", fontSize = 12.sp, maxLines = 1) }
        OutlinedButton({ BtHid.key(1, 0x19) }) { Text("Ctrl+V", fontSize = 12.sp, maxLines = 1) }
        OutlinedButton({ BtHid.key(4, 0x3D) }) { Text("Alt+F4", fontSize = 12.sp, maxLines = 1) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text(stringResource(R.string.bt_text_hint)) })
        Button({ BtHid.typeText(text); text = "" }, enabled = text.isNotEmpty()) { Text(stringResource(R.string.btn_send)) }
    }
}
