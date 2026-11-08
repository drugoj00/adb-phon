package app.adbphon

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Компактное поле ввода: подпись над полем, низкая рамка, при фокусе (пульт, клавиатура, касание) рамка цвета интерфейса. */
@Composable
fun CField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, password: Boolean = false, type: KeyboardType = KeyboardType.Text) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val prim = MaterialTheme.colorScheme.primary
    // подпись внутри области поля: касание подписи тоже ставит фокус в это поле (иначе набранный текст уходил в прежнее поле, например в адрес)
    BasicTextField(
        value, onChange, modifier.fillMaxWidth().padding(vertical = 1.dp), singleLine = true, interactionSource = src,
        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
        cursorBrush = SolidColor(prim),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else type, imeAction = ImeAction.Next),
        decorationBox = { inner ->
            Column(Modifier.fillMaxWidth()) {
                Text(label, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (focused) prim else MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
                Box(Modifier.fillMaxWidth().heightIn(min = 30.dp).border(BorderStroke(if (focused) 2.dp else 1.dp, if (focused) prim else MaterialTheme.colorScheme.outline), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.CenterStart) { inner() }
            }
        },
    )
}

/**
 * Окно «Источник» и общее окно правки сетевого диска ([edit] != null: поля заполнены текущими значениями, пароль пустой = оставить прежний, сохранение меняет запись на месте).
 * Альбом: две колонки (слева название, тип, версия SMB, адрес; справа папка, порт, логин, пароль), внизу на всю ширину сохранение, проверка и кнопки; портрет: одна колонка.
 */
@Composable
fun SourceDlg(close: () -> Unit, edit: NetConn? = null) {
    val ctx = LocalContext.current
    val cfg = LocalConfiguration.current
    val land = cfg.screenWidthDp > cfg.screenHeightDp
    var netForm by remember { mutableStateOf(edit != null) }
    var kind by remember { mutableStateOf(edit?.kind ?: "smb") }
    var host by remember { mutableStateOf(edit?.host ?: "") }
    var port by remember { mutableStateOf(edit?.port?.takeIf { it > 0 }?.toString() ?: "") }
    var user by remember { mutableStateOf(edit?.user ?: "") }
    var pass by remember { mutableStateOf("") }
    var smbVer by remember { mutableStateOf(edit?.smb ?: "") }
    var name by remember { mutableStateOf(edit?.name ?: "") }
    var path by remember { mutableStateOf(edit?.path ?: "") }
    var err by remember { mutableStateOf<String?>(null) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val hostEmpty = stringResource(R.string.fm_net_host_empty)
    val okTxt = stringResource(R.string.fm_net_check_ok)
    fun build() = NetConn(kind, host.trim(), port.toIntOrNull() ?: 0, user.trim(), if (pass.isEmpty() && edit != null && edit.kind == kind) edit.pass else pass,
        path = path.trim(), smb = if (kind == "smb") smbVer else "", name = name.trim())
    var saveFav by remember { mutableStateOf(true) }
    var favs by remember { mutableStateOf(NetStore.list(ctx)) }
    var found by remember { mutableStateOf<List<NetHost>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val p = Fm.cur
    @Composable fun opt(title: Int, sub: Int?, on: Boolean, click: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable { click() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(on, { click() })
            Column {
                Text(stringResource(title), fontSize = 14.sp)
                if (sub != null) Text(stringResource(sub), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            }
        }
    }
    fun go(c: NetConn) { close(); Fm.connectNet(p, c) }

    // поля формы
    @Composable fun fName() = CField(stringResource(R.string.fm_net_name), name, { name = it })
    @Composable fun fKind() = EqChoices(listOf("smb" to "SMB", "sftp" to "SFTP", "ftp" to "FTP", "ftps" to "FTPS", "dav" to "WebDAV").map { (k, n) -> EqChoice(n, kind == k) { kind = k; if (k == "dav" && host.isBlank()) host = "https://" } }, perRow = 5, minH = 30.dp, fontSp = 12)
    @Composable fun fSmb() {
        if (kind == "smb") {
            Text(stringResource(R.string.fm_smb_version), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
            EqChoices(listOf("" to stringResource(R.string.fm_smb_ver_auto), "1" to "SMB1", "2" to "SMB2", "3" to "SMB3").map { (k, n) -> EqChoice(n, smbVer == k) { smbVer = k } }, minH = 30.dp, fontSp = 12)
        }
    }
    @Composable fun fHost() = CField(stringResource(if (kind == "dav") R.string.fm_dav_url else R.string.fm_smb_host), host, { host = it }, type = KeyboardType.Uri)
    @Composable fun fPath() = CField(stringResource(if (kind == "smb") R.string.fm_net_path_smb else R.string.fm_net_path), path, { path = it }, type = KeyboardType.Uri)
    @Composable fun fPort() { if (kind == "sftp" || kind == "ftp" || kind == "ftps") CField(stringResource(R.string.fm_net_port, if (kind == "sftp") 22 else 21), port, { port = it.filter { c -> c.isDigit() } }, type = KeyboardType.Number) }
    @Composable fun fUser() = CField(stringResource(R.string.fm_smb_user), user, { user = it })
    @Composable fun fPass() = CField(stringResource(if (edit != null) R.string.fm_net_pass_keep else R.string.fm_smb_pass), pass, { pass = it }, password = true)

    // нижняя часть на всю ширину (портрет): галочка, «Проверить», «Найти в сети» в одной строке; пояснение мелким шрифтом
    @Composable fun bottom() {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (edit == null) Row(Modifier.clickable { saveFav = !saveFav }, verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(saveFav, { saveFav = it }, Modifier.size(32.dp)); Text(stringResource(R.string.fm_net_save), fontSize = 12.sp, maxLines = 1)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CheckBtn(true, testing, { host.isBlank() }, hostEmpty, { err = it }, { testing = it; testMsg = null }, { build() }, okTxt, { testMsg = it }, scope, Modifier.weight(1f))
            if (edit == null) ScanBtn(true, scanning, { scanning = it }, { found = it }, found, scope, ctx, Modifier.weight(1f))
        }
        err?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        testMsg?.let { Text(it, fontSize = 12.sp, color = if (it == okTxt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        Text(stringResource(R.string.fm_net_secure), fontSize = 10.sp, lineHeight = 12.sp, maxLines = 6, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        if (edit == null) found.forEach { h ->
            Column(Modifier.fillMaxWidth().clickable { host = h.ip; kind = h.kinds.firstOrNull() ?: kind; if (kind == "dav") host = (if (h.ports.contains(443)) "https://" else "http://") + h.ip }.padding(vertical = 4.dp)) {
                Text(h.ip + if (h.name.isNotBlank()) "  ·  " + h.name else "", fontSize = 14.sp, maxLines = 1)
                Text(h.kinds.joinToString(" · ") { it.uppercase() }, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    val titleRes = if (edit != null) R.string.fm_net_edit_title else R.string.fm_source
    val body: @Composable () -> Unit = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!netForm) {
                opt(R.string.fm_src_local, R.string.fm_src_local_hint, p.src == "local") { close(); Fm.setSource(p, "local") }
                opt(R.string.fm_src_adb, R.string.fm_src_adb_hint, p.src == "adb") { close(); Fm.setSource(p, "adb") }
                opt(R.string.fm_src_net, R.string.fm_src_net_hint, p.src == "smb") { netForm = true }
                if (favs.isNotEmpty()) {
                    Text(stringResource(R.string.fm_net_favs), fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    favs.forEach { f ->
                        Row(Modifier.fillMaxWidth().clickable { go(f) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            NetBrandIcon(f, if (CloudCatalog.bigRows) (if (Marq.tv) 40.dp else 32.dp) else if (Marq.tv) 30.dp else 22.dp, Modifier.padding(end = 8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                CloudIcons.serviceCaption(ctx, f)?.let { Text(it, fontSize = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f)) }
                            }
                            TextButton({ close(); Fm.dlg = FmDlg.EditNet(f) }) { Text("✎", fontSize = 14.sp) }
                            TextButton({ NetStore.remove(ctx, f); favs = NetStore.list(ctx) }) { Text("✕", fontSize = 14.sp) }
                        }
                    }
                }
            }
            if (netForm) {
                if (land) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { fName(); fKind(); fHost() }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { fPath(); fPort(); fUser(); fPass(); fSmb() }
                    }
                    if (edit == null) found.forEach { h ->
                        Column(Modifier.fillMaxWidth().clickable { host = h.ip; kind = h.kinds.firstOrNull() ?: kind; if (kind == "dav") host = (if (h.ports.contains(443)) "https://" else "http://") + h.ip }.padding(vertical = 3.dp)) {
                            Text(h.ip + if (h.name.isNotBlank()) "  ·  " + h.name else "", fontSize = 13.sp, maxLines = 1)
                        }
                    }
                } else { fName(); fKind(); fSmb(); fHost(); fPath(); fPort(); fUser(); fPass(); bottom() }
            }
        }
    }
    val okBtn: @Composable () -> Unit = {
        if (netForm) TextButton({
            if (host.isBlank()) { err = hostEmpty; return@TextButton }
            val c = build()
            if (edit != null) { Fm.applyNetEdit(ctx, edit, c); close() }
            else { if (saveFav) NetStore.save(ctx, c); go(c) }
        }) { Text(stringResource(if (edit != null) R.string.fm_net_save_btn else R.string.fm_connect)) } else TextButton(close) { Text(stringResource(R.string.fm_close)) }
    }
    val cancelBtn: @Composable () -> Unit = { if (netForm) TextButton(close) { Text(stringResource(R.string.btn_cancel)) } }
    if (!land && netForm) {
        // портрет: тот же компактный каркас (малые отступы), всё помещается без прокрутки
        androidx.compose.ui.window.Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            LangScopePublic {
                androidx.compose.material3.Surface(Modifier.fillMaxWidth(.94f).heightIn(max = (cfg.screenHeightDp - 40).dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(titleRes), Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            if (edit == null) TextButton({ netForm = false }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text("‹ " + stringResource(R.string.fm_src_back), fontSize = 13.sp, maxLines = 1) }
                        }
                        Box(Modifier.weight(1f, fill = false)) { body() }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { cancelBtn(); okBtn() }
                    }
                }
            }
        }
    } else if (land && netForm) {
        // альбом: своя компоновка без лишних отступов (заголовок и кнопки в одной полосе с содержимым), чтобы всё влезало без прокрутки
        androidx.compose.ui.window.Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            LangScopePublic {
                androidx.compose.material3.Surface(Modifier.fillMaxWidth(.94f).heightIn(max = (cfg.screenHeightDp - 16).dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                        (err ?: testMsg)?.let { m -> Text(m, fontSize = 12.sp, maxLines = 2, color = if (m == okTxt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                        Box(Modifier.weight(1f, fill = false)) { body() }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (edit == null) TextButton({ netForm = false }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text("‹ " + stringResource(R.string.fm_src_back), fontSize = 13.sp, maxLines = 1) }
                            else Text(stringResource(titleRes), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            if (edit == null) Row(Modifier.clickable { saveFav = !saveFav }, verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(saveFav, { saveFav = it }, Modifier.size(32.dp)); Text(stringResource(R.string.fm_net_save_short), fontSize = 12.sp, maxLines = 1)
                            }
                            CheckBtn(land, testing, { host.isBlank() }, hostEmpty, { err = it }, { testing = it; testMsg = null }, { build() }, okTxt, { testMsg = it }, scope)
                            if (edit == null) ScanBtn(land, scanning, { scanning = it }, { found = it }, found, scope, ctx)
                            Box(Modifier.weight(1f))
                            cancelBtn(); okBtn()
                        }
                    }
                }
            }
        }
    } else LAlertDialog(onDismissRequest = close, title = { Text(stringResource(titleRes)) }, text = body, confirmButton = okBtn, dismissButton = { cancelBtn() })
}

/** Кнопка «Проверить связь»: подключается с введёнными значениями, показывает результат, соединение закрывает. */
@Composable
private fun CheckBtn(short: Boolean, testing: Boolean, hostBlank: () -> Boolean, hostEmpty: String, setErr: (String?) -> Unit, setTesting: (Boolean) -> Unit, build: () -> NetConn, okTxt: String,
                     setMsg: (String?) -> Unit, scope: kotlinx.coroutines.CoroutineScope, modifier: Modifier = Modifier) {
    OutlinedButton({
        if (hostBlank()) { setErr(hostEmpty); return@OutlinedButton }
        setErr(null); setTesting(true); setMsg(null)
        val c = build()
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching { val fs = c.fs(); try { (fs as? KeepFs)?.connect() ?: (fs as? CloudFs)?.connect(); fs.list(fs.root); okTxt } finally { fs.close() } }.getOrElse { FmIo.nice(it) }
            }
            setMsg(msg); setTesting(false)
        }
    }, modifier, enabled = !testing, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(stringResource(if (short) R.string.fm_net_check_short else R.string.fm_net_check), fontSize = 13.sp, maxLines = 1) }
}

/** Кнопка «Найти устройства в сети». */
@Composable
private fun ScanBtn(short: Boolean, scanning: Boolean, setScanning: (Boolean) -> Unit, setFound: (List<NetHost>) -> Unit, found: List<NetHost>, scope: kotlinx.coroutines.CoroutineScope, ctx: android.content.Context, modifier: Modifier = Modifier) {
    OutlinedButton({
        if (!scanning) { setScanning(true); setFound(emptyList()); scope.launch { setFound(runCatching { NetDiscover.scan(ctx) { setFound(it) } }.getOrDefault(found)); setScanning(false) } }
    }, modifier, enabled = !scanning, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(stringResource(if (scanning) R.string.fm_net_scanning else if (short) R.string.fm_net_scan_short else R.string.fm_net_scan), fontSize = 13.sp, maxLines = 1) }
}
