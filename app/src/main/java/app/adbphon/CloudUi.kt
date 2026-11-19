@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.adbphon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import app.adbphon.FloorText as Text
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
            // 10.0: окно ожидания входа без прокрутки (две колонки в ландшафте, вторая страница «Ещё» в портрете); выбор облака остаётся списком
            Column(if (!ok || phase == CloudAuth.WAIT || phase == CloudAuth.SAVING || phase == CloudAuth.DONE) Modifier.tvFit() else Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    !ok -> Text(stringResource(R.string.cl_unavailable), fontSize = 14.sp)
                    phase == CloudAuth.WAIT -> CloudWaitBody(ctx)
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

/**
 * 9.4: подсказка для экрана Google «приложение не проверено»: у Google нужные ссылки выглядят как обычный серый текст. Три шага крупно и схема экрана
 * (нарисована программой, без текста и картинок Google): ссылки подсвечены, у каждой номер и стрелка. Блок в окне входа листается вместе с окном.
 */
@Composable
internal fun GoogleUnverifiedGuide(steps: Boolean = true, scheme: Boolean = true, title: Boolean = true, compact: Boolean = false) {
    val hi = androidx.compose.ui.graphics.Color(0xFFFF8F00)      // подсветка ссылок
    val paper = androidx.compose.ui.graphics.Color(0xFFF1F3F4)    // «экран» Google, светлый независимо от темы
    val ink = androidx.compose.ui.graphics.Color(0xFF3C4043)
    val grey = androidx.compose.ui.graphics.Color(0xFF80868B)
    val bar = androidx.compose.ui.graphics.Color(0xFFDADCE0)
    val round = androidx.compose.foundation.shape.CircleShape
    @Composable fun Num(n: Int) {
        androidx.compose.foundation.layout.Box(Modifier.size(26.dp).background(hi, round), contentAlignment = Alignment.Center) {
            Text(n.toString(), color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {   // 10.0: без рамки карточки
        if (title) Text(stringResource(R.string.cl_g_title), fontSize = if (compact) 14.sp else 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        if (steps) listOf(R.string.cl_g_1, R.string.cl_g_2, R.string.cl_g_3).forEachIndexed { i, r ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) { Num(i + 1); Text(stringResource(r), fontSize = if (compact) 13.sp else 15.sp, lineHeight = if (compact) 16.sp else 19.sp) }
        }
        if (scheme)
        // схема экрана: серые полоски вместо текста Google, ссылки подсвечены
        Column(Modifier.fillMaxWidth().background(paper, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(if (compact) 8.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
            Text(stringResource(R.string.cl_g_fake), color = ink, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            if (!compact) repeat(2) { androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(if (it == 0) 0.9f else 0.6f).size(height = 8.dp, width = 0.dp).background(bar, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Num(1)
                androidx.compose.foundation.layout.Box(Modifier.weight(1f, false).border(2.dp, hi, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp)) { Text(stringResource(R.string.cl_g_l1), color = grey, fontSize = 13.sp) }
                HintArrow(34)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Num(2)
                androidx.compose.foundation.layout.Box(Modifier.weight(1f, false).border(2.dp, hi, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp)) { Text(stringResource(R.string.cl_g_l2), color = grey, fontSize = 13.sp) }
                Unit
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Num(3)
                androidx.compose.foundation.layout.Box(Modifier.border(2.dp, hi, androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(androidx.compose.ui.graphics.Color(0xFF1A73E8), androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 5.dp)) {
                    Text(stringResource(R.string.cl_g_b3), color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}


/** Ландшафт (телефон набок, ТВ): окна мастера делятся на две колонки, чтобы обойтись без прокрутки. */
@Composable
internal fun isLandscapeUi(): Boolean =
    androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

/**
 * 10.0: тело окна «ожидание входа» без прокрутки. Главная страница: ожидание, подсказка для Google, «Начать заново» и «Ещё».
 * В ландшафте главная страница в две колонки; «Ещё»: ссылка, другой браузер, разрешение «Поверх других окон».
 */
@Composable
private fun CloudWaitBody(ctx: android.content.Context) {
    val land = isLandscapeUi()
    var more by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val drive = CloudAuth.curType == "drive"
    val blind = drive && CloudOverlay.blindState.value
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    val gap = Arrangement.spacedBy(6.dp)
    @Composable fun spinner() {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.padding(end = 12.dp).size(22.dp), strokeWidth = 2.dp)
            Text(stringResource(if (CloudAuth.url.isEmpty()) R.string.cl_starting else R.string.cl_waiting), fontSize = 14.sp)
        }
    }
    // выбранный браузер не отдаёт текст страницы (стрелки не находят элемент): с Chrome стрелки работают; без Chrome остаётся текстовая подсказка
    @Composable fun chrome() {
        if (!blind) return
        if (CloudAuth.chromeInstalled(ctx)) OutlinedButton({ CloudAuth.openInChrome(ctx) }, Modifier.fillMaxWidth(), contentPadding = pad) { Text(stringResource(R.string.cl_chrome_open), fontSize = 13.sp, maxLines = 1) }
        else Text(stringResource(R.string.cl_chrome_need), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
    }
    // «Начать заново»: новый вход с самого начала (новый адрес и выбор аккаунта); «Ещё»: вторая страница
    @Composable fun buttons() {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
            OutlinedButton({ CloudAuth.restart(ctx) }, Modifier.weight(1f), contentPadding = pad) { Text(stringResource(R.string.cl_restart), fontSize = 13.sp, maxLines = 1) }
            OutlinedButton({ more = true }, Modifier.weight(1f), contentPadding = pad) { Text(stringResource(R.string.cl_more) + " ›", fontSize = 13.sp, maxLines = 1) }
        }
    }
    @Composable fun moreBody() {
        Column(verticalArrangement = gap) {
            if (CloudAuth.url.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = gap) {
                OutlinedButton({ CloudAuth.copyLink(ctx) }, Modifier.weight(1f), contentPadding = pad) { Text(stringResource(R.string.cl_copy_link), fontSize = 13.sp, maxLines = 1) }
                OutlinedButton({ CloudAuth.openChooser(ctx) }, Modifier.weight(1f), contentPadding = pad) { Text(stringResource(R.string.cl_open_other), fontSize = 13.sp, maxLines = 1) }
            }
            // один раз предлагаем разрешение «Поверх других окон»: тогда программа сама возвращается на экран после входа
            val sp = ctx.getSharedPreferences("adbphon", android.content.Context.MODE_PRIVATE)
            var askOverlay by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(!PermFlow.overlayOk(ctx) && !PermFlow.needOverlay(ctx)) }
            if (askOverlay) {
                Text(stringResource(R.string.cl_overlay_hint), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f))
                EqBtnRow(horizontalArrangement = gap) {   // 14.0: кнопки одной высоты при переносе подписи (Btn.kt)
                    OutlinedButton({ sp.edit().putBoolean("cloud_overlay_asked", true).apply(); askOverlay = false
                        runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                        Modifier.weight(1f).fillMaxHeight(), contentPadding = pad) { Text(stringResource(R.string.cl_overlay_btn), fontSize = 12.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                    OutlinedButton({ sp.edit().putBoolean("cloud_overlay_asked", true).apply(); askOverlay = false
                        runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                        Modifier.weight(1f).fillMaxHeight(), contentPadding = pad) { Text(stringResource(R.string.cl_notif_btn), fontSize = 12.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                    TextButton({ sp.edit().putBoolean("cloud_overlay_asked", true).apply(); askOverlay = false }) { Text(stringResource(R.string.cl_overlay_later), fontSize = 12.sp) }
                }
            }
            OutlinedButton({ more = false }, Modifier.fillMaxWidth(), contentPadding = pad) { Text("‹ " + stringResource(R.string.okw_back), fontSize = 13.sp, maxLines = 1) }
        }
    }
    if (more && land && drive) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) { GoogleUnverifiedGuide(scheme = false, title = false, compact = true) }
            Column(Modifier.weight(1f)) { moreBody() }
        }
    } else if (more && drive) {
        Column(verticalArrangement = gap) { GoogleUnverifiedGuide(scheme = false, title = false); moreBody() }
    } else if (more) {
        moreBody()
    } else if (land) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = gap) { spinner(); chrome(); buttons(); if (drive) GoogleUnverifiedGuide(steps = false, scheme = false, compact = true) }
            Column(Modifier.weight(1f), verticalArrangement = gap) { if (drive) GoogleUnverifiedGuide(steps = false, title = false, compact = true) }
        }
    } else {
        Column(verticalArrangement = gap) { spinner(); if (drive) GoogleUnverifiedGuide(steps = false); chrome(); buttons() }
    }
}
