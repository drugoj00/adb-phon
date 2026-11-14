package app.adbphon

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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

/** Звонок собеседнику из списка «Люди» (voice = только голосом). Сообщение «занято»/«не началось» показывается подсказкой. */
fun startCall(ctx: Context, p: CallPeople.Person, voice: Boolean) {
    when (Calls.dial(ctx, p, voice)) {
        "busy" -> Toast.makeText(ctx, ctx.getString(R.string.call_busy_now), Toast.LENGTH_SHORT).show()
        "fail" -> Toast.makeText(ctx, ctx.getString(R.string.call_no_start), Toast.LENGTH_SHORT).show()
    }
}

/**
 * Звонок устройству из списка «Устройства» (строки «Видеозвонок» и «Аудиозвонок» и пункты меню долгого нажатия).
 * У Windows-ПК код программы известен (он нужен и для файлов и экрана); у Android-устройства ищем человека в «Люди» по адресу или имени,
 * иначе спрашиваем код один раз (он запоминается в «Люди»).
 */
fun devCall(ctx: Context, name: String, ips: List<String>, code: String?, voice: Boolean) {
    val p: CallPeople.Person? = if (code != null && CallPeople.validCode(code)) CallPeople.Person(name, Invites.fmt(code), ips.firstOrNull().orEmpty())
    else CallPeople.all(ctx).let { all -> all.firstOrNull { it.ip.isNotEmpty() && ips.any { ip -> ip == it.ip.substringBefore(':') } } ?: all.firstOrNull { it.name.equals(name, ignoreCase = true) } }
    if (p == null) { Fm.dlg = FmDlg.CallCode(name, ips.firstOrNull().orEmpty(), voice); return }
    when (Calls.dial(ctx, p, voice)) {
        "busy" -> Fm.say(Fm.str(R.string.call_busy_now))
        "fail" -> Fm.say(Fm.str(R.string.call_no_start))
    }
}

/** Окно «Код программы» для устройства, которого нет в «Люди»: код сохраняется в «Люди» и сразу начинается звонок. */
@Composable
fun CallCodeDialog(name: String, ip: String, voice: Boolean, close: () -> Unit) {
    val ctx = LocalContext.current
    var code by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    LAlertDialog(
        onDismissRequest = close, ime = true,
        title = { Text(stringResource(R.string.dev_call_code_title), fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.dev_call_code_hint, name), fontSize = 13.sp)
                CField(stringResource(R.string.call_add_code), code, { code = it; bad = false })
                if (bad) Text(stringResource(R.string.call_add_bad), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton({
                if (CallPeople.validCode(code)) {
                    CallPeople.add(ctx, name, code); CallPeople.learnIp(ctx, code, ip)
                    close()
                    val p = CallPeople.all(ctx).firstOrNull { Invites.norm(it.code) == Invites.norm(code) }
                    if (p != null) startCall(ctx, p, voice)
                } else bad = true
            }) { Text(stringResource(R.string.dev_call_go)) }
        },
        dismissButton = { TextButton(close) { Text(stringResource(R.string.call_cancel)) } },
    )
}

/** 7.0: «Люди» (имя + код программы) и кнопки «Видео» и «Голосом»; выключатель фонового приёма звонков. Показывается под «Подключением по интернету». */
@Composable
fun PeopleSection() {
    val ctx = LocalContext.current
    var add by remember { mutableStateOf(false) }
    var ring by remember { mutableStateOf(false) }
    var listen by remember { mutableStateOf(CallListen.enabled(ctx)) }
    var myName by remember { mutableStateOf(Calls.myName(ctx)) }
    val people = CallPeople.all(ctx)
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.call_title), fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                HintTitle(stringResource(R.string.call_listen), stringResource(R.string.call_hint), Modifier.weight(1f), titleSize = 13.sp, titleWeight = null)
                Switch(listen, { listen = it; CallListen.setEnabled(ctx, it) })
            }
            CField(stringResource(R.string.call_myname), myName, { myName = it; Calls.setMyName(ctx, it) })
            people.toList().forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(p.code, fontSize = 11.sp, color = dim)
                    }
                    OutlinedButton({ startCall(ctx, p, false) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp), modifier = Modifier.heightIn(min = 34.dp)) { Text(stringResource(R.string.call_btn_video), fontSize = 12.sp) }
                    OutlinedButton({ startCall(ctx, p, true) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp), modifier = Modifier.heightIn(min = 34.dp)) { Text(stringResource(R.string.call_btn_voice), fontSize = 12.sp) }
                    TextButton({ CallPeople.remove(ctx, p) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("✕", fontSize = 15.sp) }
                }
            }
            OutlinedButton({ add = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.call_add)) }
            OutlinedButton({ ring = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.ring_btn)) }
        }
    }
    if (add) AddPersonDialog { add = false }
    if (ring) RingtoneDialog { ring = false }
}

@Composable
private fun AddPersonDialog(close: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    LAlertDialog(
        onDismissRequest = close, ime = true,
        title = { Text(stringResource(R.string.call_add_title), fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CField(stringResource(R.string.call_add_name), name, { name = it })
                CField(stringResource(R.string.call_add_code), code, { code = it; bad = false })
                if (bad) Text(stringResource(R.string.call_add_bad), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton({ if (CallPeople.validCode(code)) { CallPeople.add(ctx, name, code); close() } else bad = true }) { Text(stringResource(R.string.call_add_save)) } },
        dismissButton = { TextButton(close) { Text(stringResource(R.string.call_cancel)) } },
    )
}

/** «Настройки»: «Моя аватарка» и «Картинка вместо видео» (аватарка собеседника / своя картинка / только буква). */
@Composable
fun CallPicSettings() {
    val ctx = LocalContext.current
    var ver by remember { mutableStateOf(0) }
    var mode by remember { mutableStateOf(CallPics.mode(ctx)) }
    val bad = stringResource(R.string.cp_bad)
    val pickAv = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) { if (CallPics.setAvatar(ctx, u)) ver++ else Toast.makeText(ctx, bad, Toast.LENGTH_SHORT).show() } }
    val pickPic = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) { if (CallPics.setPic(ctx, u)) { CallPics.setMode(ctx, "own"); mode = "own"; ver++ } else Toast.makeText(ctx, bad, Toast.LENGTH_SHORT).show() } }
    val tick = ver
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HintTitle(stringResource(R.string.cp_avatar), stringResource(R.string.cp_avatar_hint))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val bmp = remember(tick) { CallPics.avatarBitmap(ctx) }
            if (bmp != null) androidx.compose.foundation.Image(bmp.asImageBitmap(), null, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
            OutlinedButton({ pickAv.launch("image/*") }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(stringResource(R.string.cp_pick), fontSize = 13.sp) }
            if (bmp != null) OutlinedButton({ CallPics.clearAvatar(ctx); ver++ }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(stringResource(R.string.cp_remove), fontSize = 13.sp) }
        }
        HintTitle(stringResource(R.string.cp_pic_mode), stringResource(R.string.cp_pic_hint))
        EqChoices(listOf(
            EqChoice(stringResource(R.string.cp_mode_peer), mode == "peer") { mode = "peer"; CallPics.setMode(ctx, "peer") },
            EqChoice(stringResource(R.string.cp_mode_own), mode == "own") { if (CallPics.hasPic(ctx)) { mode = "own"; CallPics.setMode(ctx, "own") } else pickPic.launch("image/*") },
            EqChoice(stringResource(R.string.cp_mode_letter), mode == "letter") { mode = "letter"; CallPics.setMode(ctx, "letter") },
        ), perRow = 3, fontSp = 12)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ pickPic.launch("image/*") }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(stringResource(R.string.cp_pic_pick), fontSize = 13.sp) }
            if (CallPics.hasPic(ctx)) OutlinedButton({ CallPics.clearPic(ctx); if (mode == "own") { mode = "peer"; CallPics.setMode(ctx, "peer") }; ver++ }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(stringResource(R.string.cp_remove), fontSize = 13.sp) }
        }
    }
}

/** «Мелодия звонка…»: мелодия входящего видеозвонка и голосового, гудок ожидания, громкость, предпрослушивание, свой файл. */
@Composable
fun RingtoneDialog(close: () -> Unit) {
    val ctx = LocalContext.current
    var cfg by remember { mutableStateOf(Ringtones.cfg(ctx)) }
    var ver by remember { mutableStateOf(0) }
    var prev by remember { mutableStateOf("") }          // что сейчас предпрослушивается: video | audio | wait
    var note by remember { mutableStateOf("") }
    var pickKind by remember { mutableStateOf("video") }
    val failTxt = stringResource(R.string.ring_file_fail)
    fun commit() { Ringtones.save(ctx, cfg); ver++ }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val r = Ringtones.importFile(ctx, uri, pickKind)
            if (r != null) {
                if (pickKind == "audio") { cfg.audioFile = r.first; cfg.audioFileName = r.second; cfg.audio = "file" } else { cfg.videoFile = r.first; cfg.videoFileName = r.second; cfg.video = "file" }
                commit()
            }
        }
    }
    DisposableEffect(Unit) { onDispose { Ringtones.stop() } }
    fun stopPreview() { Ringtones.stop(); prev = "" }
    fun preview(kind: String) {
        if (prev == kind) { stopPreview(); return }
        Ringtones.stop(); note = ""
        val id = when (kind) { "audio" -> cfg.audio; "wait" -> cfg.wait; else -> cfg.video }
        val f = Ringtones.path(ctx, id, if (kind == "wait") "video" else kind, cfg)
        if (f == null) { prev = ""; return }
        val fb = Ringtones.path(ctx, if (kind == "audio") "m1" else if (kind == "wait") "w1" else "m2", kind, Ringtones.Cfg())
        prev = kind
        Ringtones.play(ctx, f, cfg.vol, fb) { note = failTxt }
    }
    @Composable
    fun row(kind: String, title: String, ids: List<String>, withFile: Boolean) {
        var open by remember { mutableStateOf(false) }
        val cur = when (kind) { "audio" -> cfg.audio; "wait" -> cfg.wait; else -> cfg.video }
        val fname = if (kind == "audio") cfg.audioFileName else if (kind == "video") cfg.videoFileName else ""
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    OutlinedButton({ open = true }, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { Text(Ringtones.label(ctx, cur, fname) + "  ▾", fontSize = 13.sp, maxLines = 1) }
                    LDropdownMenu(open, { open = false }) {
                        ids.forEach { id ->
                            DropdownMenuItem({ Text((if (id == cur) "✓ " else "") + Ringtones.label(ctx, id, fname), fontSize = 14.sp) }, {
                                open = false; stopPreview()
                                if (id == "file" && withFile && (if (kind == "audio") cfg.audioFile else cfg.videoFile).isBlank()) { pickKind = kind; picker.launch("audio/*") }
                                else { if (kind == "audio") cfg.audio = id else if (kind == "wait") cfg.wait = id else cfg.video = id; commit() }
                            })
                        }
                        if (withFile) DropdownMenuItem({ Text(stringResource(R.string.ring_file), fontSize = 14.sp) }, { open = false; stopPreview(); pickKind = kind; picker.launch("audio/*") })
                    }
                }
                TextButton({ preview(kind) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(if (prev == kind) R.string.ring_stop else R.string.ring_preview), fontSize = 12.sp) }
            }
        }
    }
    LAlertDialog(
        onDismissRequest = { stopPreview(); close() },
        title = { Text(stringResource(R.string.ring_title), fontSize = 17.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val tick = ver   // перерисовка при изменении настроек
                if (tick < 0) return@Column
                row("video", stringResource(R.string.ring_video), Ringtones.RING_IDS, true)
                row("audio", stringResource(R.string.ring_audio), Ringtones.RING_IDS, true)
                row("wait", stringResource(R.string.ring_wait), Ringtones.WAIT_IDS, false)
                Text(stringResource(R.string.ring_vol, cfg.vol), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Slider(cfg.vol.toFloat(), { cfg.vol = it.toInt(); Ringtones.save(ctx, cfg); ver++ }, valueRange = 0f..100f, steps = 19)
                if (note.isNotEmpty()) Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                Collapsible(stringResource(R.string.hint_more)) {
                    Text(stringResource(R.string.ring_wait_note), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                    Text(stringResource(R.string.ring_hint), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                }
            }
        },
        confirmButton = { TextButton({ stopPreview(); close() }) { Text(stringResource(R.string.btn_close)) } },
    )
}
