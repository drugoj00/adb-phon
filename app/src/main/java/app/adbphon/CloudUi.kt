@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.adbphon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * «Добавить облако»: выбор облака → страница входа открывается в браузере (логин и пароль вводит владелец сам, программа их не видит) →
 * имя «почта (облако)» → список файлов. Если rclone на устройстве нет (32-разрядные приставки), показывается пояснение.
 */
@Composable
fun CloudAddDlg(close: () -> Unit) {
    val ctx = LocalContext.current
    val phase = CloudAuth.phase
    val ok = Rclone.available(ctx)
    // закончили: открыть подключённое облако
    LaunchedEffect(phase) {
        if (phase == CloudAuth.DONE) {
            val c = CloudAuth.added
            CloudAuth.reset(); close()
            if (c != null) Fm.cloudAdded(c)
        }
    }
    LAlertDialog(
        ime = true, wide = true,
        onDismissRequest = { if (phase == CloudAuth.IDLE || phase == CloudAuth.FAIL) { CloudAuth.reset(); close() } else close() },
        title = { Text(stringResource(R.string.cl_title), Modifier.combinedClickable(onClick = {}, onLongClick = { CloudCatalog.labelAll = !CloudCatalog.labelAll })) },
        text = {
            Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    !ok -> Text(stringResource(R.string.cl_unavailable), fontSize = 14.sp)
                    phase == CloudAuth.WAIT -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(end = 12.dp), strokeWidth = 2.dp)
                            Text(stringResource(if (CloudAuth.url.isEmpty()) R.string.cl_starting else R.string.cl_waiting), fontSize = 14.sp)
                        }
                        if (CloudAuth.url.isNotEmpty()) OutlinedButton({ CloudAuth.openBrowser(ctx) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.cl_open_again)) }
                        if (CloudAuth.url.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton({ CloudAuth.copyLink(ctx) }, Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text(stringResource(R.string.cl_copy_link), fontSize = 13.sp, maxLines = 1) }
                            OutlinedButton({ CloudAuth.openChooser(ctx) }, Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text(stringResource(R.string.cl_open_other), fontSize = 13.sp, maxLines = 1) }
                        }
                        // один раз предлагаем разрешение «Поверх других окон»: тогда программа сама возвращается на экран после входа
                        val sp = ctx.getSharedPreferences("adbphon", android.content.Context.MODE_PRIVATE)
                        var askOverlay by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(!PermFlow.overlayOk(ctx) && !PermFlow.needOverlay(ctx)) }
                        if (askOverlay) {
                            Text(stringResource(R.string.cl_overlay_hint), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton({ sp.edit().putBoolean("cloud_overlay_asked", true).apply(); askOverlay = false
                                    runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                                    Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text(stringResource(R.string.cl_overlay_btn), fontSize = 12.sp, maxLines = 2) }
                                OutlinedButton({ sp.edit().putBoolean("cloud_overlay_asked", true).apply(); askOverlay = false
                                    runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                                    Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text(stringResource(R.string.cl_notif_btn), fontSize = 12.sp, maxLines = 2) }
                                TextButton({ sp.edit().putBoolean("cloud_overlay_asked", true).apply(); askOverlay = false }) { Text(stringResource(R.string.cl_overlay_later), fontSize = 12.sp) }
                            }
                        }
                    }
                    phase == CloudAuth.SAVING || phase == CloudAuth.DONE -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.cl_saving), fontSize = 14.sp)
                    }
                    else -> {
                        if (phase == CloudAuth.FAIL) Text(stringResource(R.string.cl_failed, CloudAuth.message), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                        // вход прошёл, но облако не открылось (или общий ключ отклонён): сразу мастер своего ключа для этого облака
                        if (phase == CloudAuth.FAIL) CloudAuth.ownKeyFor?.let { kt -> OutlinedButton({ CloudAuth.reset(); close(); Fm.dlg = FmDlg.OwnKey(kt) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.ok_connect_own)) } }
                        Text(stringResource(R.string.cl_pick), fontSize = 13.sp)
                        val provs by androidx.compose.runtime.produceState<List<CProv>?>(null) { value = runCatching { CloudCatalog.load(ctx) }.getOrNull() }
                        fun builtIn(t: String) = provs == null || provs!!.any { it.name == t }
                        // порядок: Google Диск, MEGA, Dropbox, OneDrive, Яндекс Диск, Box, pCloud, затем остальные с фирменным значком, затем без значка
                        fun oauthTile(type: String): CloudTile {
                            val pr = CloudAuth.providers.first { it.type == type }
                            return CloudTile(ctx.getString(pr.titleRes), type) {
                                // Google Диск общим пробным ключом: перед НОВЫМ входом читается флаг «набор открыт» (см. SharedKey); закрыто: сразу мастер «Свой ключ»
                                if (type == "drive" && OwnKeys.get(ctx, "drive") == null) {
                                    Fm.scope.launch {
                                        if (SharedKey.isOpen(ctx)) PermFlow.forCloudLogin(ctx) { CloudAuth.start(ctx, pr) }
                                        else { CloudAuth.reset(); close(); Fm.dlg = FmDlg.OwnKey("drive", 0, true) }
                                    }
                                } else if (type == "yandex" && OwnKeys.get(ctx, "yandex") == null) {
                                    // Яндекс: общего ключа у программы нет, общий ключ движка Яндекс отклоняет, поэтому сразу мастер «Свой ключ»
                                    CloudAuth.reset(); close(); Fm.dlg = FmDlg.OwnKey("yandex")
                                } else PermFlow.forCloudLogin(ctx) { CloudAuth.start(ctx, pr) }
                            }
                        }
                        fun entryTile(e: CEntry) = CloudTile(e.title, e.type, e.preset, builtIn(e.type)) { CloudAuth.reset(); close(); Fm.dlg = FmDlg.CloudForm(e) }
                        val rest = CloudCatalog.ordered(CloudCatalog.popular)
                        val (withIcon, noIcon) = rest.filter { it.type != "pcloud" }.partition { CloudCatalog.hasIcon(it) }
                        val tiles = ArrayList<CloudTile>()
                        tiles += oauthTile("drive")
                        tiles += CloudTile(ctx.getString(R.string.cl_mega), "mega") { CloudAuth.reset(); MegaAuth.reset(); close(); Fm.dlg = FmDlg.MegaAdd() }
                        tiles += oauthTile("dropbox"); tiles += oauthTile("onedrive"); tiles += oauthTile("yandex"); tiles += oauthTile("box")
                        // Яндекс Диск по WebDAV с паролем приложения: вход логином и паролем прямо в программе, без браузера
                        tiles += entryTile(CEntry("webdav", ctx.getString(R.string.cl_yadav), "yandex", mapOf("url" to "https://webdav.yandex.ru", "vendor" to "other"), needLogin = true)).let { CloudTile(it.title, it.type, it.preset, true, null, it.onClick) }
                        rest.firstOrNull { it.type == "pcloud" }?.let { tiles += entryTile(it) }
                        withIcon.forEach { tiles += entryTile(it) }
                        tiles += CloudTile(ctx.getString(R.string.cl_nextcloud_short), "webdav", mapOf("provider" to "nextcloud"), iconRes = R.drawable.ic_cl_nextcloud) { CloudAuth.reset(); close(); Fm.dlg = FmDlg.Source() }
                        noIcon.forEach { tiles += entryTile(it) }
                        CloudTileGrid(tiles)
                        Text(stringResource(R.string.cl_sec_s3), fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                        CloudTileGrid(CloudCatalog.ordered(CloudCatalog.s3).map { entryTile(it) })
                        OutlinedButton({ CloudAuth.reset(); close(); Fm.dlg = FmDlg.CloudAll() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.cl_all) + " ›", maxLines = 1) }
                        Row(Modifier.fillMaxWidth().clickable { CloudAuth.reset(); close(); Fm.dlg = FmDlg.OwnKeyPick() }.padding(vertical = 8.dp)) {
                            Text(stringResource(R.string.ok_link) + " ›", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        Row(Modifier.fillMaxWidth().clickable { CloudAuth.reset(); close(); Fm.dlg = FmDlg.Source() }.padding(vertical = 8.dp)) {
                            Text(stringResource(R.string.cl_other) + " ›", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (phase == CloudAuth.WAIT || phase == CloudAuth.SAVING) TextButton({ CloudAuth.reset() }) { Text(stringResource(R.string.btn_cancel)) }
            else TextButton({ CloudAuth.reset(); close() }) { Text(stringResource(R.string.fm_close)) }
        },
    )
}


/** Облако: меняется только название (адрес и вход не редактируются, облако добавляется заново). */
@Composable
fun CloudRenameDlg(conn: NetConn, close: () -> Unit) {
    val ctx = LocalContext.current
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(conn.user) }
    LAlertDialog(
        onDismissRequest = close, title = { Text(stringResource(R.string.cl_rename_title)) },
        text = { androidx.compose.material3.OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.fm_net_name), maxLines = 1) }) },
        confirmButton = { TextButton({ if (name.isNotBlank()) { Fm.applyNetEdit(ctx, conn, conn.copy(user = name.trim())); close() } }) { Text(stringResource(R.string.fm_net_save_btn)) } },
        dismissButton = { TextButton(close) { Text(stringResource(R.string.btn_cancel)) } },
    )
}

/** MEGA: почта и пароль (скрыт), проверка входа и списка корня, понятные ошибки. Пароль не показывается и не пишется в журнал. */
@Composable
fun MegaAddDlg(close: () -> Unit) {
    val ctx = LocalContext.current
    var email by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var pass by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ok = Rclone.available(ctx)
    fun go() {
        // проверка идёт в фоне приложения, а не в окне: «Назад» и «Закрыть» работают в любой момент, результат придёт и после закрытия окна
        val e = email; val pw = pass
        Fm.scope.launch {
            val c = MegaAuth.add(ctx, e, pw)
            if (c != null) { MegaAuth.reset(); if (Fm.dlg is FmDlg.MegaAdd) Fm.dlg = null; Fm.cloudAdded(c) }
            else if (Fm.dlg !is FmDlg.MegaAdd && MegaAuth.error.isNotEmpty()) { Fm.say(MegaAuth.error); MegaAuth.reset() }
        }
    }
    LAlertDialog(
        ime = true,
        onDismissRequest = { pass = ""; if (!MegaAuth.busy) MegaAuth.reset(); close() },
        title = { Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_cl_mega), null, Modifier.size(28.dp))
            HintTitle(stringResource(R.string.cl_mega), stringResource(R.string.cl_mega_note), Modifier.padding(start = 10.dp))
        } },
        text = {
            Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!ok) Text(stringResource(R.string.cl_unavailable), fontSize = 14.sp)
                else {
                    CField(stringResource(R.string.cl_mega_email), email, { email = it }, type = androidx.compose.ui.text.input.KeyboardType.Email)
                    CField(stringResource(R.string.cl_mega_pass), pass, { pass = it }, password = true)
                    if (MegaAuth.busy) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp).size(22.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.cl_mega_checking), fontSize = 14.sp)
                    }
                    if (MegaAuth.error.isNotEmpty()) Text(MegaAuth.error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { if (ok) TextButton({ go() }, enabled = !MegaAuth.busy) { Text(stringResource(R.string.cl_mega_check)) } },
        dismissButton = { TextButton({ pass = ""; if (!MegaAuth.busy) MegaAuth.reset(); close(); Fm.dlg = FmDlg.CloudAdd() }) { Text(stringResource(R.string.cl_mega_back)) } },
    )
}
