package app.adbphon

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import app.adbphon.FloorText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Выбор папки системой для съёмного тома [uuid]: запускает окно, проверяет выбранный корень и запоминает доступ.
 * [onDone] получает [SafAccess.OK] или код ошибки (для окна и настройки).
 */
private class SafPick(val launch: (String) -> Unit)

@Composable
private fun rememberSafPick(onDone: (String, Int) -> Unit): SafPick {
    val cur = remember { arrayOf("") }
    val done = androidx.compose.runtime.rememberUpdatedState(onDone)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        if (r.resultCode == Activity.RESULT_OK && uri != null) done.value(cur[0], SafAccess.accept(cur[0], uri))
    }
    return remember {
        SafPick { uuid ->
            cur[0] = uuid
            try { launcher.launch(SafAccess.pickIntent(uuid)) }
            catch (e: ActivityNotFoundException) { done.value(uuid, -1) }
            catch (e: Throwable) { done.value(uuid, SafAccess.ERR_FAIL) }
        }
    }
}

@Composable
private fun safErrText(code: Int, label: String): String? = when (code) {
    SafAccess.OK -> null
    -1 -> stringResource(R.string.saf_err_nopicker)
    SafAccess.ERR_FAIL -> stringResource(R.string.saf_err_fail)
    else -> stringResource(R.string.saf_err_not_root, label)
}

/** «Нужен доступ к карте памяти»: подсказка свёрнута, «Выбрать» открывает системное окно выбора, неверный выбор объясняется и повторяется. */
@Composable
fun SafNeedDialog(d: FmDlg.SafNeed, close: () -> Unit) {
    var err by remember { mutableIntStateOf(SafAccess.OK) }
    val pick = rememberSafPick { _, code ->
        if (code == SafAccess.OK) { d.done(true); close() } else err = code
    }
    LAlertDialog(
        onDismissRequest = { d.done(false); close() },
        title = { Text(stringResource(R.string.saf_need_title, d.label), maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.saf_need_text), fontSize = 14.sp)
                Collapsible(stringResource(R.string.saf_how)) {
                    Text(stringResource(R.string.saf_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f))
                }
                safErrText(err, d.label)?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton({ pick.launch(d.uuid) }) { Text(stringResource(if (err == SafAccess.OK) R.string.saf_pick else R.string.saf_pick_again)) } },
        dismissButton = { TextButton({ d.done(false); close() }) { Text(stringResource(R.string.btn_cancel)) } },
    )
}

/** Настройка «Доступ к карте памяти» (группа «Файлы»): тома, у которых доступ выдан или не выдан, «Выдать доступ» и «Забыть доступ». */
@Composable
fun SafAccessSetting() {
    val ctx = LocalContext.current
    remember { runCatching { FmIo.app }.onFailure { FmIo.app = ctx.applicationContext } }
    var tick by remember { mutableIntStateOf(0) }
    var errUuid by remember { mutableStateOf("") }
    var errCode by remember { mutableIntStateOf(SafAccess.OK) }
    val pick = rememberSafPick { uuid, code -> tick++; errUuid = uuid; errCode = code }
    @Suppress("UNUSED_VARIABLE") val t = tick
    val rows = SafAccess.volumes(ctx).toMutableList().also { l ->
        SafAccess.granted().filter { u -> l.none { it.first.equals(u, true) } }.forEach { l.add(it to it) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HintTitle(stringResource(R.string.set_saf_title), stringResource(R.string.set_saf_hint))
        if (rows.isEmpty()) Text(stringResource(R.string.saf_none), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
        for ((uuid, label) in rows) {
            val on = SafAccess.tree(uuid) != null
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, fontWeight = FontWeight.Medium, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(stringResource(if (on) R.string.saf_state_on else R.string.saf_state_off), fontSize = 13.sp,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .65f), modifier = Modifier.weight(1f))
                    if (on) OutlinedButton({ SafAccess.forget(uuid); tick++ }) { Text(stringResource(R.string.saf_forget), maxLines = 1) }
                    else OutlinedButton({ pick.launch(uuid) }) { Text(stringResource(R.string.saf_grant), maxLines = 1) }
                }
                if (errUuid == uuid) safErrText(errCode, label)?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
