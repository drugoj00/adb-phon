package app.adbphon

import androidx.compose.foundation.Image
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Короткое название для списка «Все остальные облака»: длинные описания (S3) заменяются именем типа. */
private fun shortTitle(p: CProv): String = p.desc.takeIf { it.isNotBlank() && it.length <= 42 } ?: p.name.replaceFirstChar { it.uppercase() }

/** Плитка каталога: значок сверху (фирменный или голубое облачко), название под ним в одну-две строки по центру. */
class CloudTile(val title: String, val type: String, val preset: Map<String, String> = emptyMap(), val enabled: Boolean = true, val iconRes: Int? = null, val onClick: () -> Unit)

/** Число колонок сетки: портрет 3, альбом 5, ТВ 6 (плитки крупнее). */
@Composable
fun cloudColumns(): Int {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    return if (Marq.tv) 6 else if (cfg.screenWidthDp > cfg.screenHeightDp) 5 else 3
}

@Composable
fun CloudTileView(t: CloudTile, modifier: Modifier = Modifier) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val prim = MaterialTheme.colorScheme.primary
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    Column(
        modifier.padding(2.dp).clip(shape)
            .then(if (focused) Modifier.border(if (Marq.tv) 3.dp else 2.dp, prim, shape) else Modifier)
            .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, enabled = t.enabled) { t.onClick() }
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = t.title },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val sz = if (Marq.tv) 56.dp else 40.dp
        Box(Modifier.alpha(if (t.enabled) 1f else .4f)) {
            if (t.iconRes != null) Image(painterResource(t.iconRes), null, Modifier.size(sz)) else CloudTypeIcon(t.type, t.title, sz, preset = t.preset)
        }
        // подпись: у заглушки всегда; у фирменного значка только в режиме «подписи у всех» (скрытый переключатель: долгое нажатие на заголовок окна);
        // место под подпись резервируется всегда, чтобы плитки были одной высоты и сетка не «прыгала»
        val brand = t.iconRes != null || CloudIcons.resByKey(CloudCatalog.iconKey(t.type, t.preset["provider"], t.preset["endpoint"])) != null
        Box(Modifier.padding(top = 4.dp).height(if (Marq.tv) 34.dp else 28.dp), contentAlignment = Alignment.TopCenter) {
            if (CloudCatalog.labelAll || !brand) Text(t.title, fontSize = if (Marq.tv) 14.sp else 11.sp, lineHeight = if (Marq.tv) 16.sp else 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (t.enabled) 1f else .5f))
        }
    }
}

/** Сетка плиток обычной колонкой строк (внутри прокручиваемого окна). */
@Composable
fun CloudTileGrid(tiles: List<CloudTile>) {
    val cols = cloudColumns()
    Column(Modifier.fillMaxWidth()) {
        tiles.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { CloudTileView(it, Modifier.weight(1f)) }
                repeat(cols - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** Форма облака: поля строятся автоматически из описания параметров провайдера во встроенном движке. */
@Composable
fun CloudFormDlg(entry: CEntry, close: () -> Unit) {
    val ctx = LocalContext.current
    var prov by remember { mutableStateOf<CProv?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val values = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { prov = CloudCatalog.find(CloudCatalog.load(ctx), entry.type); loaded = true; CloudSetup.reset() }
    val isOauth = entry.type in CloudCatalog.oauth
    val opts = prov?.let { CloudCatalog.formOpts(it, entry.preset, entry.preset["provider"] ?: values["provider"]) }.orEmpty()
    fun back() { CloudSetup.reset(); close(); Fm.dlg = FmDlg.CloudAdd() }
    fun go() {
        val p = prov ?: return
        if (isOauth) {
            val missing = opts.firstOrNull { it.required && values[it.name].isNullOrBlank() && it.def.isBlank() }
            if (missing != null) { CloudSetup.error = ctx.getString(R.string.cl_err_missing, CloudSetup.optLabel(ctx, missing.name)); return }
            val extra = ArrayList<String>()
            entry.preset.forEach { (k, v) -> extra += k; extra += v }
            opts.filter { !it.isPassword }.forEach { o -> values[o.name]?.trim()?.takeIf { it.isNotEmpty() && it != o.def }?.let { extra += o.name; extra += it } }
            CloudSetup.reset(); close()
            Fm.dlg = FmDlg.CloudAdd()
            PermFlow.forCloudLogin(ctx) { CloudAuth.start(ctx, CloudAuth.Provider(entry.type, 0, extra, entry.title)) }
        } else {
            val snap = values.toMap()
            Fm.scope.launch {
                val c = CloudSetup.add(ctx, entry, p, opts, snap)
                if (c != null) { CloudSetup.reset(); if (Fm.dlg is FmDlg.CloudForm) Fm.dlg = null; Fm.cloudAdded(c) }
                else if (Fm.dlg !is FmDlg.CloudForm && CloudSetup.error.isNotEmpty()) { Fm.say(CloudSetup.error); CloudSetup.reset() }
            }
        }
    }
    LAlertDialog(
        ime = true,
        onDismissRequest = { values.clear(); if (!CloudSetup.busy) CloudSetup.reset(); close() },
        title = { Row(verticalAlignment = Alignment.CenterVertically) {
            CloudTypeIcon(entry.type, entry.title, 28.dp, preset = entry.preset)
            HintTitle(entry.title, listOfNotNull(entry.note.takeIf { it.isNotBlank() }, (when { entry.type == "mailru" -> R.string.cl_mailru_note; entry.preset["url"]?.contains("webdav.yandex") == true -> R.string.cl_yadav_note; else -> null })?.let { stringResource(it) }, stringResource(if (isOauth) R.string.cl_form_oauth_note else R.string.cl_form_key_note)).joinToString("\n\n"), Modifier.padding(start = 10.dp))
        } },
        text = {
            Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    !Rclone.available(ctx) -> Text(stringResource(R.string.cl_unavailable), fontSize = 14.sp)
                    !loaded -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    prov == null -> Text(stringResource(R.string.cl_not_built), fontSize = 14.sp)
                    else -> {
                        if (opts.isEmpty() && isOauth) Text(stringResource(R.string.cl_form_oauth_only), fontSize = 13.sp)
                        opts.forEach { o ->
                            val label = CloudSetup.optLabel(ctx, o.name) + if (o.required || (entry.needLogin && (o.name == "user" || o.name == "pass"))) " *" else ""
                            val hint = o.help.lineSequence().firstOrNull().orEmpty().take(150)
                            if (o.type == "bool") {
                                Row(Modifier.fillMaxWidth().clickable { values[o.name] = if (values[o.name] == "true") "false" else "true" }, verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox((values[o.name] ?: o.def) == "true", { values[o.name] = if (it) "true" else "false" }, Modifier.size(32.dp))
                                    Text(label, fontSize = 13.sp)
                                }
                            } else if (o.examples.isNotEmpty() && o.exclusive) {
                                var open by remember { mutableStateOf(false) }
                                Column {
                                    Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
                                    OutlinedButton({ open = true }, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                                        Text((values[o.name] ?: o.def).ifBlank { "—" }, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    LDropdownMenu(open, { open = false }) {
                                        o.examples.take(80).forEach { (v, h) ->
                                            DropdownMenuItem({ Text(v.ifBlank { "—" } + if (h.isNotBlank()) "  ·  " + h.lineSequence().first().take(50) else "", fontSize = 13.sp, maxLines = 1) }, { values[o.name] = v; open = false })
                                        }
                                    }
                                }
                            } else {
                                CField(label, values[o.name] ?: "", { values[o.name] = it }, password = o.isPassword)
                                if (hint.isNotBlank() && o.name !in COMMON) Text(hint, fontSize = 10.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (CloudSetup.busy) Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(end = 12.dp).size(22.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.cl_mega_checking), fontSize = 14.sp)
                        }
                        if (CloudSetup.error.isNotEmpty()) Text(CloudSetup.error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                        // где взять пароль приложения (вход прямо в программе, без браузера)
                        val loginHint = when { entry.type == "mailru" -> R.string.cl_mailru_note; entry.preset["url"]?.contains("webdav.yandex") == true -> R.string.cl_yadav_note; else -> null }
                    }
                }
            }
        },
        confirmButton = { if (prov != null) TextButton({ go() }, enabled = !CloudSetup.busy) { Text(stringResource(if (isOauth) R.string.cl_form_sign else R.string.cl_mega_check)) } },
        dismissButton = { TextButton({ values.clear(); back() }) { Text(stringResource(R.string.cl_mega_back)) } },
    )
}

private val COMMON = setOf("user", "pass", "password", "url", "host", "email", "username", "access_key_id", "secret_access_key", "region", "endpoint", "provider", "account", "key")

/** «Все остальные облака»: все типы встроенного движка плитками, поиск по названию; сначала с фирменным значком, затем без. */
@Composable
fun CloudAllDlg(close: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<CProv>?>(null) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { list = CloudCatalog.load(ctx) }
    val shown = list.orEmpty().filter { it.name !in CloudCatalog.virtual && it.name != "http" }
        .filter { q.isBlank() || it.name.contains(q.trim(), true) || it.desc.contains(q.trim(), true) }
        .sortedWith(compareBy<CProv>({ if (CloudIcons.resByKey(CloudCatalog.iconKey(it.name)) != null) 0 else 1 }, { shortTitle(it).lowercase() }))
    LAlertDialog(
        ime = true, wide = true,
        onDismissRequest = close,
        title = { Text(stringResource(R.string.cl_all)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CField(stringResource(R.string.cl_search), q, { q = it })
                when {
                    list == null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    list!!.isEmpty() -> Text(stringResource(R.string.cl_err_other, CloudCatalog.loadError), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    else -> {
                        val cols = cloudColumns()
                        val gridSt = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Fixed(cols), Modifier.heightIn(max = 440.dp).verticalScrollbar(gridSt), state = gridSt) {
                            items(shown.size, key = { shown[it].name }) { i ->
                                val p = shown[i]
                                CloudTileView(CloudTile(shortTitle(p), p.name) { close(); Fm.dlg = FmDlg.CloudForm(CEntry(p.name, shortTitle(p), CloudCatalog.iconKey(p.name))) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ close(); Fm.dlg = FmDlg.CloudAdd() }) { Text(stringResource(R.string.cl_mega_back)) } },
    )
}
