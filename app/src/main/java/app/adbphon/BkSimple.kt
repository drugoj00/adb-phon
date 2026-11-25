package app.adbphon

import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * 19.11.2026 (слово владелицы: «обычный бекап, как в любой программе»): простая копия настроек.
 * «Создать копию» (в «Опциях» сразу под поиском): необязательная галочка «Защитить паролем» (выключена), затем обычное системное окно
 * выбора места и имени файла (SAF), ОДИН файл .adbpb: настройки, устройства, подключения и облака вместе с паролями подключений, без APK и без
 * модуля облаков. «Восстановить из копии»: обычное системное окно выбора файла, пароль спрашивается только если файл зашифрован
 * (сохранённый пароль пробуется первым), подтверждение «N настроек, N подключений, N облаков, N устройств». Старые файлы .adbpb (с паролем и без)
 * читаются как раньше. На устройствах без системного окна (часть приставок): создание пишет в «Загрузки», восстановление открывает прежний список копий.
 * Окна копий приложений («Софт») остались прежними (BkOne.kt).
 */
object BkSimple {
    var create by mutableStateOf(false)
    var restore by mutableStateOf(false)
    /** Нет системного окна выбора файла (часть приставок): прежний список копий из папки. */
    var legacy by mutableStateOf(false)
}

@Composable
fun BkSimpleHost() {
    if (BkSimple.create) BkSimpleCreate { BkSimple.create = false }
    if (BkSimple.restore) BkSimpleRestore { BkSimple.restore = false }
    if (BkSimple.legacy) BkRestoreDialog { BkSimple.legacy = false }
}

/** Два заметных действия «Создать копию» и «Восстановить из копии» (портрет: над группами, две половины: над пунктами группы). */
@Composable
fun SetBackupQuick() {
    SetAction(SetIcons.backup, stringResource(R.string.bk2_make_now)) { BkSimple.create = true }
    SetAction(SetIcons.restore, stringResource(R.string.set2_hero_restore)) { BkSimple.restore = true }
}

private fun uriName(ctx: android.content.Context, uri: Uri): String =
    runCatching { ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
        ?: uri.lastPathSegment.orEmpty()

@Composable
private fun BsDim(s: String, color: Color = Color.Unspecified) =
    Text(s, fontSize = bkDimSp(), color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface.copy(alpha = .65f) else color)

@Composable
private fun BsBusy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        BsDim(text)
    }
}

// =====================================================================================================================
// Создать копию
// =====================================================================================================================

@Composable
fun BkSimpleCreate(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var protect by remember { mutableStateOf(BkStore.encOn(ctx) && BkStore.pass(ctx).isNotEmpty()) }
    var pass by remember { mutableStateOf(BkStore.pass(ctx)) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    /** [uri] null: системного окна нет, копия пишется в «Загрузки». */
    fun write(uri: Uri?) {
        if (busy) return
        busy = true
        val pw = if (protect) pass.toCharArray() else null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val (data, sum) = AppBackup.create(ctx, pw)
                    AppBackup.unseal(data, pw)   // самопроверка до записи
                    val shown = if (uri != null) {
                        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) } ?: throw IOException("cannot open file")
                        val back = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("cannot read file")
                        if (!back.contentEquals(data)) throw IOException("file check failed")
                        uriName(ctx, uri)
                    } else AppBackup.saveDefault(ctx, data).absolutePath
                    BkStore.setEncOn(ctx, protect)
                    BkStore.setPass(ctx, if (protect) pass else "")
                    ctx.getString(R.string.bkp_saved, shown, BkStore.size(ctx, data.size.toLong())) + "\n" +
                        ctx.getString(R.string.bkp_sum, sum.settings, sum.links, sum.clouds, sum.devices) +
                        (if (pw != null) "\n" + ctx.getString(R.string.bkp_enc_settings_yes) else "") + sum.skippedNote(ctx)
                }
            }
            busy = false
            done = r.fold({ it to false }, { e -> AppLog.e("ADBphon", "simple backup", e); ctx.getString(R.string.bk2_failed, bkErrText(ctx, e)) to true })
        }
    }

    val saf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if (uri != null) write(uri) }

    fun choose() {
        if (busy) return
        if (protect && pass.isEmpty()) { err = ctx.getString(R.string.bk3_enc_need_pass); return }
        err = null
        try { saf.launch(AppBackup.defaultName()) } catch (e: ActivityNotFoundException) { write(null) }
    }

    val d = done
    if (d != null) {
        LAlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.bk2_title)) },
            text = { Column(Modifier.heightIn(max = 420.dp).barScroll()) { Text(d.first, fontSize = bkMainSp(), color = if (d.second) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) } },
            confirmButton = { TextButton(onClose) { Text(stringResource(R.string.btn_close)) } },
        )
        return
    }
    LAlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        ime = true,
        title = { Text(stringResource(R.string.bk2_make_now)) },
        text = {
            Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BsDim(stringResource(R.string.bks_hint))
                CheckRow(protect, enabled = !busy, onClick = { protect = !protect; err = null }) {
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        Text(stringResource(R.string.bks_protect), fontSize = bkMainSp())
                        BsDim(stringResource(R.string.bks_protect_sub))
                    }
                }
                if (protect) {
                    val pw = rememberPw()
                    OutlinedTextField(pass, { pass = it; err = null }, Modifier.fillMaxWidth(), singleLine = true, enabled = !busy, label = { Text(stringResource(R.string.bkp_pass), maxLines = 1) },
                        visualTransformation = pw.transform, trailingIcon = { PwEyeIcon(pw.shown) { pw.shown = !pw.shown } })
                } else BsDim(stringResource(R.string.bks_plain_note), Color(0xFFB26A00))
                err?.let { Text(it, fontSize = bkMainSp(), color = MaterialTheme.colorScheme.error) }
                if (busy) BsBusy(stringResource(R.string.bks_saving))
            }
        },
        confirmButton = { TextButton({ choose() }, enabled = !busy) { Text(stringResource(R.string.bks_pick)) } },
        dismissButton = { TextButton({ if (!busy) onClose() }, enabled = !busy) { Text(stringResource(R.string.btn_cancel)) } },
    )
}

// =====================================================================================================================
// Восстановить из копии
// =====================================================================================================================

private sealed class BsRs {
    class Pass(val data: ByteArray, val wrong: Boolean) : BsRs()
    class Confirm(val json: JSONObject, val name: String) : BsRs()
    class Info(val text: String, val error: Boolean, val restart: Boolean = false, val module: Boolean = false) : BsRs()
}

@Composable
fun BkSimpleRestore(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf<BsRs?>(null) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    /** Разбор файла: сохранённый пароль первым; пароль спрашивается только если файл зашифрован и тот не подошёл. */
    suspend fun prepare(data: ByteArray, pw: CharArray?, savedTried: Boolean) {
        busy = true
        val r = try { withContext(Dispatchers.Default) { runCatching { AppBackup.parse(data, pw) } } } finally { busy = false }
        r.onSuccess { st = BsRs.Confirm(it, name) }.onFailure { e ->
            st = when (e) {
                is AppBackup.NeedPass -> BsRs.Pass(data, false)
                is AppBackup.WrongPass -> BsRs.Pass(data, !savedTried)
                else -> BsRs.Info(ctx.getString(R.string.bk2_bad_file, bkErrText(ctx, e)), true)
            }
        }
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { onClose(); return@rememberLauncherForActivityResult }
        name = uriName(ctx, uri)
        busy = true
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                runCatching {
                    val size = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
                    if (size > 64L * 1024 * 1024) throw IOException("too large")
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("cannot read file")
                }
            }
            busy = false
            val b = data.getOrElse { e -> st = BsRs.Info(ctx.getString(R.string.bk2_bad_file, bkErrText(ctx, e)), true); return@launch }
            if (b.size < 8 || !b.copyOfRange(0, 5).contentEquals(AppBackup.MAGIC)) { st = BsRs.Info(ctx.getString(R.string.bks_not_ours), true); return@launch }
            prepare(b, BkStore.pass(ctx).takeIf { it.isNotEmpty() }?.toCharArray(), true)
        }
    }
    LaunchedEffect(Unit) {
        // без системного окна выбора (часть приставок): прежний список копий из папки
        try { open.launch(arrayOf("*/*")) } catch (e: ActivityNotFoundException) { BkSimple.legacy = true; onClose() }
    }

    when (val s = st) {
        null -> {}
        is BsRs.Pass -> {
            var p by remember { mutableStateOf("") }
            LAlertDialog(
                onDismissRequest = { if (!busy) onClose() },
                ime = true,
                title = { Text(stringResource(R.string.bkp_enter_pass)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BsDim(name)
                        val pw = rememberPw()
                        OutlinedTextField(p, { p = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = !busy, label = { Text(stringResource(R.string.bkp_pass), maxLines = 1) },
                            visualTransformation = pw.transform, trailingIcon = { PwEyeIcon(pw.shown) { pw.shown = !pw.shown } })
                        // красная ошибка только когда проверка завершилась неверным паролем; пока идёт проверка её нет
                        if (busy) BsBusy(stringResource(R.string.bkp_pass_checking))
                        else if (s.wrong) Text(stringResource(R.string.bkp_wrong_pass), fontSize = bkDimSp(), color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = { TextButton({ if (!busy && p.isNotEmpty()) scope.launch { prepare(s.data, p.toCharArray(), false) } }, enabled = !busy && p.isNotEmpty()) { Text(stringResource(R.string.btn_yes)) } },
                dismissButton = { TextButton({ if (!busy) onClose() }, enabled = !busy) { Text(stringResource(R.string.btn_cancel)) } },
            )
        }
        is BsRs.Confirm -> LAlertDialog(
            onDismissRequest = { if (!busy) onClose() },
            title = { Text(stringResource(R.string.set2_hero_restore)) },
            text = {
                Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BsDim(s.name)
                    val sm = AppBackup.summarize(s.json)
                    Text(stringResource(R.string.bk2_confirm, sm.settings, sm.links, sm.clouds, sm.devices), fontSize = bkMainSp())
                    if (busy) BsBusy(stringResource(R.string.bkp_working))
                }
            },
            confirmButton = {
                TextButton({
                    if (busy) return@TextButton
                    val j = s.json; busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { AppBackup.restore(ctx, j) } }
                        busy = false
                        st = r.fold(
                            { sm ->
                                val needMod = sm.clouds > 0 && CloudModule.state(ctx) != CloudModule.State.OK
                                BsRs.Info(ctx.getString(R.string.bk2_restored, sm.settings, sm.links, sm.clouds, sm.devices) + "\n" + ctx.getString(R.string.bk2_restart_hint) +
                                    (if (needMod) "\n" + ctx.getString(R.string.bk4_mod_hint) else ""), false, restart = true, module = needMod)
                            },
                            { e -> AppLog.e("ADBphon", "simple restore", e); BsRs.Info(ctx.getString(R.string.bk2_bad_file, bkErrText(ctx, e)), true) },
                        )
                    }
                }, enabled = !busy) { Text(stringResource(R.string.bkp_restore_btn)) }
            },
            dismissButton = { TextButton({ if (!busy) onClose() }, enabled = !busy) { Text(stringResource(R.string.btn_cancel)) } },
        )
        is BsRs.Info -> LAlertDialog(
            // 24.11.2026: после успешного восстановления закрытие окна тоже перезапускает программу, чтобы все настройки применились
            onDismissRequest = { if (s.restart) restartApp(ctx) else onClose() },
            title = { Text(stringResource(R.string.set2_hero_restore)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).barScroll()) {
                    Text(s.text, fontSize = bkMainSp(), color = if (s.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (s.module) {
                        val ph = CloudModule.phase
                        if (ph in 1..3) BsDim(stringResource(R.string.bk4_mod_progress, CloudModule.progress))
                        else if (ph == 4 && CloudModule.error.isNotEmpty()) Text(CloudModule.error, fontSize = bkMainSp(), color = MaterialTheme.colorScheme.error)
                        TextButton({ scope.launch { CloudModule.fetchAndInstall(ctx.applicationContext) } }, enabled = ph !in 1..3) { Text(stringResource(R.string.bk4_mod_get), fontSize = bkMainSp()) }
                    }
                }
            },
            confirmButton = {
                if (s.restart) TextButton({ restartApp(ctx) }) { Text(stringResource(R.string.bk2_restart)) }
                else TextButton(onClose) { Text(stringResource(R.string.btn_close)) }
            },
            dismissButton = if (s.restart) ({ TextButton({ restartApp(ctx) }) { Text(stringResource(R.string.btn_close)) } }) else null,
        )
    }
}
