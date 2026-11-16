package app.adbphon

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import app.adbphon.FloorText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// ======================================================================= свои значки: новые места (10.0)

/**
 * Ключи своих значков для новых мест (настройки «icons», входят в копию настроек вместе с остальными ключами этого файла):
 * «u:remote:<номер>» подвкладки «Пульта», «u:more:<номер>» подвкладки «Ещё», «k:<путь>» закладки файлового менеджера.
 * Строки «Устройства в сети» («Связь») берут прежний ключ устройства «v:<адрес>» (тот же, что в меню устройства).
 */
object MiscIconKeys {
    fun remote(i: Int) = "u:remote:$i"
    fun more(i: Int) = "u:more:$i"
    fun bookmark(path: String) = "k:$path"
    fun device(host: String) = "v:$host"
}

/**
 * Долгое нажатие поверх элемента, который сам обрабатывает обычное нажатие (SegmentedButton, кнопка с clickable):
 * касание держат дольше системного порога (без сдвига) или удерживают ОК пульта. Короткое нажатие проходит к элементу как раньше,
 * после долгого отпускание поглощается, чтобы обычное нажатие не сработало.
 */
fun Modifier.miscLongPress(onLong: () -> Unit): Modifier = composed {
    val cb = rememberUpdatedState(onLong)
    val keyHeld = remember { booleanArrayOf(false) }
    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var done = false
                while (!done) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val ch = ev.changes.firstOrNull { it.id == down.id }
                    if (ch == null || !ch.pressed || (ch.position - down.position).getDistance() > slop) done = true
                }
                true
            }
            if (ended == null) {
                cb.value()
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    ev.changes.forEach { it.consume() }
                    if (ev.changes.none { it.pressed }) break
                }
            }
        }
    }.onPreviewKeyEvent { e ->
        val n = e.nativeKeyEvent
        val ok = n.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || n.keyCode == android.view.KeyEvent.KEYCODE_ENTER || n.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
        when {
            !ok -> false
            n.action == android.view.KeyEvent.ACTION_DOWN && n.repeatCount == 0 -> { keyHeld[0] = false; false }
            n.action == android.view.KeyEvent.ACTION_DOWN && n.repeatCount == 1 -> { keyHeld[0] = true; cb.value(); true }
            n.action == android.view.KeyEvent.ACTION_DOWN && n.repeatCount > 1 && keyHeld[0] -> true
            n.action == android.view.KeyEvent.ACTION_UP && keyHeld[0] -> { keyHeld[0] = false; true }
            else -> false
        }
    }
}

/** Свой значок по ключу (ничего не рисует, если значок не задан): для подвкладок и строк. */
@Composable
fun MiscIconGlyph(key: String, size: Dp, tint: Color, modifier: Modifier = Modifier) {
    val cs = CustomIcons.spec(key) ?: return
    CustomIconGlyph(cs, size, tint, modifier)
}

/** Меню долгого нажатия с одним пунктом «Изменить значок…» и окно выбора значка. */
@Composable
fun MiscIconMenu(expanded: Boolean, onDismiss: () -> Unit, title: String, key: String) {
    var pick by remember { mutableStateOf(false) }
    if (pick) CustomIconPicker(title, key) { pick = false }
    LDropdownMenu(expanded, onDismiss) {
        DropdownMenuItem({ Text(stringResource(R.string.ic_change), fontSize = 14.sp) }, { onDismiss(); pick = true })
    }
}

/** Строка с долгим нажатием «Изменить значок…» и своим значком слева (если задан). Для закладок файлового менеджера. */
@Composable
fun MiscIconRow(key: String, title: String, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().miscLongPress { menu = true }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MiscIconGlyph(key, 20.dp, MaterialTheme.colorScheme.primary)
        content()
        MiscIconMenu(menu, { menu = false }, title, key)
    }
}

// ======================================================================= приглашения Tailscale с Android (10.0)

/** Токен API Tailscale: шифруется ключом Android Keystore (как пароли подключений в NetStore). В копию настроек не входит (на другом устройстве не расшифруется). */
object TsApiToken {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("ts_api", Context.MODE_PRIVATE)
    fun get(c: Context): String? = sp(c).getString("tok", null)?.let { runCatching { NetStore.decrypt(it) }.getOrNull() }?.takeIf { it.isNotBlank() }
    fun has(c: Context) = get(c) != null
    fun set(c: Context, token: String) { sp(c).edit().putString("tok", NetStore.encrypt(TsInviteApi.normToken(token))).apply() }
    fun clear(c: Context) { sp(c).edit().remove("tok").apply() }

    /** Настоящий транспорт (HttpURLConnection). В журнал и окна токен не попадает. */
    val http = TsInviteApi.Http { url, headers, body ->
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 12000; c.readTimeout = 20000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(64_000) } }.getOrNull()
            code to text
        } finally { c.disconnect() }
    }
}

/** Понятный текст ошибки отправки приглашения (код ответа API по-русски/по-украински/по-английски). */
fun miscInviteErrorText(ctx: Context, e: TsInviteApi.Result.Error): String {
    val head = when (e.kind) {
        "email" -> ctx.getString(R.string.misc_inv_e_email)
        "token" -> ctx.getString(R.string.misc_inv_e_token)
        "auth" -> ctx.getString(R.string.misc_inv_e_auth)
        "forbidden" -> ctx.getString(R.string.misc_inv_e_forbidden)
        "notfound" -> ctx.getString(R.string.misc_inv_e_notfound)
        "exists" -> ctx.getString(R.string.misc_inv_e_exists, e.http)
        "bad" -> ctx.getString(R.string.misc_inv_e_bad, e.http)
        "rate" -> ctx.getString(R.string.misc_inv_e_rate)
        "server" -> ctx.getString(R.string.misc_inv_e_server, e.http)
        "net" -> ctx.getString(R.string.misc_inv_e_net)
        else -> ctx.getString(R.string.misc_inv_e_other, e.http)
    }
    val detail = e.message?.takeIf { it.isNotBlank() && e.kind != "net" }?.let { "\n" + ctx.getString(R.string.misc_inv_e_detail, it) }.orEmpty()
    return head + detail
}

/** Окно «Нужен токен API Tailscale»: три строки справки, поле с глазиком, «Сохранить». */
@Composable
fun TsTokenDialog(onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    var tok by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    val had = remember { TsApiToken.has(ctx) }
    LAlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.misc_tok_title), fontSize = 17.sp) },
        text = {
            Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.misc_tok_step1), fontSize = 13.sp)
                Text(stringResource(R.string.misc_tok_step2), fontSize = 13.sp)
                Text(stringResource(R.string.misc_tok_step3), fontSize = 13.sp)
                TextButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TsInviteApi.KEYS_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                    Text(stringResource(R.string.misc_tok_open), fontSize = 13.sp)
                }
                val pw = rememberPw()
                OutlinedTextField(tok, { tok = it; bad = false }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.misc_tok_field), maxLines = 1) },
                    visualTransformation = pw.transform, trailingIcon = { PwEyeIcon(pw.shown) { pw.shown = !pw.shown } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (bad) Text(stringResource(R.string.misc_tok_bad), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.misc_tok_note), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                if (had) TextButton({ TsApiToken.clear(ctx); onClose() }) { Text(stringResource(R.string.misc_tok_delete), fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton({
                if (TsInviteApi.validToken(tok) && runCatching { TsApiToken.set(ctx, tok) }.isSuccess) { tok = ""; onSaved() } else bad = true
            }) { Text(stringResource(R.string.misc_tok_save)) }
        },
        dismissButton = { TextButton(onClose) { Text(stringResource(R.string.btn_cancel)) } },
    )
}

/** Окно «Пригласить в сеть Tailscale»: адрес почты → POST user-invites. Без токена сначала окно токена. */
@Composable
fun TsInviteSendDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<TsInviteApi.Result?>(null) }
    var tokenDlg by remember { mutableStateOf(!TsApiToken.has(ctx)) }
    var tokenFirst by remember { mutableStateOf(tokenDlg) }

    if (tokenDlg) {
        TsTokenDialog(onClose = { tokenDlg = false; if (tokenFirst && !TsApiToken.has(ctx)) onClose() }, onSaved = { tokenDlg = false; tokenFirst = false; result = null })
        return
    }
    fun send() {
        busy = true; result = null
        scope.launch {
            val tok = TsApiToken.get(ctx)
            val r = withContext(Dispatchers.IO) { TsInviteApi.send(TsApiToken.http, tok, email) }
            busy = false; result = r
            if (r is TsInviteApi.Result.Error && r.kind == "net") AppLog.i("ADBphon", "ts invite: нет связи (" + r.message + ")")
        }
    }
    val res = result
    LAlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { HintTitle(stringResource(R.string.misc_inv_title), stringResource(R.string.misc_inv_hint), titleSize = 17.sp) },
        text = {
            Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(email, { email = it.replace("\n", ""); if (result !is TsInviteApi.Result.Sent) result = null }, Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                    label = { Text(stringResource(R.string.misc_inv_email), maxLines = 1) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                if (busy) Text(stringResource(R.string.misc_inv_sending), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                when (res) {
                    is TsInviteApi.Result.Sent -> {
                        Text(stringResource(if (res.emailSent || res.inviteUrl == null) R.string.misc_inv_sent else R.string.misc_inv_sent_nomail, res.email), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                        res.inviteUrl?.let { u ->
                            TextButton({
                                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("invite", u))
                                android.widget.Toast.makeText(ctx, ctx.getString(R.string.misc_inv_link_copied), android.widget.Toast.LENGTH_SHORT).show()
                            }) { Text(stringResource(R.string.misc_inv_copy_link), fontSize = 13.sp) }
                        }
                    }
                    is TsInviteApi.Result.Error -> {
                        Text(miscInviteErrorText(ctx, res), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                        if (res.kind == "auth" || res.kind == "forbidden" || res.kind == "token") TextButton({ tokenDlg = true }) { Text(stringResource(R.string.misc_inv_token_btn), fontSize = 13.sp) }
                    }
                    null -> {}
                }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.End) {
                    TextButton({ tokenDlg = true }, enabled = !busy) { Text(stringResource(R.string.misc_inv_token_btn), fontSize = 12.sp) }
                }
            }
        },
        confirmButton = {
            if (res is TsInviteApi.Result.Sent) TextButton(onClose) { Text(stringResource(R.string.btn_close)) }
            else TextButton({ send() }, enabled = !busy && TsInviteApi.validEmail(email)) { Text(stringResource(R.string.misc_inv_send)) }
        },
        dismissButton = { if (res !is TsInviteApi.Result.Sent) TextButton(onClose, enabled = !busy) { Text(stringResource(R.string.btn_cancel)) } },
    )
}
