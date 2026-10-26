@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.adbphon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** Поиск приложений в сети (F-Droid, GitHub) и установка по прямой ссылке. */
@Composable
fun NetDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("fdroid") }
    var results by remember { mutableStateOf<List<NetApp>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // скачивает файлы по очереди и ставит первый, который встал (для приложений с несколькими сборками под процессоры)
    fun installFrom(urls: List<String>) {
        scope.launch {
            busy = true
            var done = false
            for (u in urls) {
                status = ctx.getString(R.string.net_downloading, u.substringAfterLast('/'), 0)
                val f = NetSearch.download(ctx, u) { mb -> status = ctx.getString(R.string.net_downloading, u.substringAfterLast('/'), mb) }
                if (f == null) continue
                if (!Adb.isConnected) { status = ctx.getString(R.string.conn_need); busy = false; return@launch }
                status = ctx.getString(R.string.net_installing)
                val (ok, _) = Batch.installViaAdb(ctx, listOf(f))
                if (ok > 0) { status = ctx.getString(R.string.net_done); done = true; Hooks.reloadApps(); break }
            }
            if (!done) status = ctx.getString(R.string.net_failed)
            busy = false
        }
    }

    fun doSearch() {
        if (query.isBlank() || busy) return
        scope.launch {
            busy = true
            status = ctx.getString(R.string.net_searching)
            results = runCatching { NetSearch.search(query, source) }.getOrElse { status = ctx.getString(R.string.net_no_network); emptyList() }
            if (results.isNotEmpty()) status = ctx.getString(R.string.net_found, results.size) else if (status == ctx.getString(R.string.net_searching)) status = ctx.getString(R.string.net_nothing)
            busy = false
        }
    }

    val fields = OutlinedTextFieldDefaults.colors(
        focusedTextColor = t.text, unfocusedTextColor = t.text, focusedBorderColor = t.accent, unfocusedBorderColor = t.border,
        cursorColor = t.accent, focusedLabelColor = t.accent, unfocusedLabelColor = t.sub,
    )
    HDialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().padding(24.dp).clip(RoundedCornerShape(20.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(20.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.pnet_title), color = t.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.net_hint), color = t.hint, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    query, { query = it }, label = { Text(stringResource(R.string.net_query)) }, singleLine = true, colors = fields,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), modifier = Modifier.weight(1f),
                )
                Chip(stringResource(R.string.net_search), true) { doSearch() }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("F-Droid", source == "fdroid") { source = "fdroid" }
                Chip("GitHub", source == "github") { source = "github" }
                Chip(stringResource(R.string.cancel), false) { if (!busy) onClose() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    link, { link = it }, label = { Text(stringResource(R.string.net_link)) }, singleLine = true, colors = fields, modifier = Modifier.weight(1f),
                )
                Chip(stringResource(R.string.net_get), false) { if (link.startsWith("http") && !busy) installFrom(listOf(link.trim())) }
            }
            if (status.isNotEmpty()) Text(status, color = t.accent, fontSize = 14.sp)
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(results, key = { it.source + it.id }) { app ->
                    var focused by remember { mutableStateOf(false) }
                    val (hm, hover) = rememberHover()
                    Column(
                        Modifier.fillMaxWidth()
                            .then(hm)
                            .onFocusChanged { focused = it.isFocused }
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (focused || hover) t.tileFocus else t.tile)
                            .border(if (focused) 3.dp else 0.dp, if (focused) t.accent else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable(enabled = !busy) {
                                scope.launch {
                                    busy = true
                                    status = ctx.getString(R.string.net_resolving)
                                    val urls = runCatching { NetSearch.apkUrls(app) }.getOrDefault(emptyList())
                                    busy = false
                                    if (urls.isEmpty()) status = ctx.getString(R.string.net_no_apk) else installFrom(urls)
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(app.name, color = t.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (app.summary.isNotBlank()) Text(app.summary, color = t.sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
