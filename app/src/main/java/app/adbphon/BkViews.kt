package app.adbphon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Окна простого пути копий (bk2): строка папки, итог «Сохранено», запрос пароля, список копий для восстановления. На приставке текст крупнее. */

/** Основной текст: на ТВ не мельче 16 sp. */
fun bkMainSp(): TextUnit = if (Marq.tv) 16.sp else 14.sp
/** Пояснения и подписи. */
fun bkDimSp(): TextUnit = if (Marq.tv) 14.sp else 12.sp

/** Откуда открывать окно выбора: сама папка, если она уже есть, иначе ближайшая существующая (папка по умолчанию создаётся при первой записи). */
fun bkPickStart(p: BkPlace): BkPlace {
    if (!p.local) return p
    var f: File? = File(p.path)
    while (f != null && !f.isDirectory) f = f.parentFile
    return p.copy(path = f?.absolutePath ?: "/sdcard")
}

/** «Папка для копий: полный путь» и кнопка «Изменить…» (окно выбора места, выбранное запоминается). */
@Composable
fun BkPlaceRow(label: String, place: BkPlace, onChange: () -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = bkDimSp(), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
            Text(BkStore.display(ctx, place), fontSize = bkMainSp(), color = MaterialTheme.colorScheme.primary)
        }
        TextButton(onChange) { Text(stringResource(R.string.bk2_change), fontSize = bkMainSp()) }
    }
}

/** Выбор папки для копий [key] (настройки или копии с данными): выбранное место запоминается сразу. */
@Composable
fun BkPickDirDialog(key: String, onPicked: (BkPlace) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    BkPickDialog(stringResource(R.string.bk2_pick_dir), bkPickStart(BkStore.last(ctx, key)), null, { p, _ ->
        BkStore.setLast(ctx, key, p)
        if (key == BkStore.KEY_SETTINGS) BkAuto.schedule(ctx)   // для сетевой папки задание автокопии ждёт сеть
        onPicked(p)
    }, onClose)
}

/**
 * Итог: «Сохранено: путь, размер» (или ошибка) и кнопки «Открыть папку», «Поделиться», «Закрыть»; при ошибке «Выбрать другую папку»
 * и, если нет доступа ко всем файлам, «Дать доступ».
 */
@Composable
fun BkResultDialog(r: BkResult, onPickOther: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LAlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(stringResource(if (r.failed && r.names.isEmpty()) R.string.bk2_not_saved else R.string.bk2_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).barScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.text, fontSize = bkMainSp(), color = if (r.failed && r.names.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                if (status.isNotEmpty()) Text(status, fontSize = bkDimSp(), color = MaterialTheme.colorScheme.primary)
                val btns = ArrayList<EqBtn>()
                if (r.names.isNotEmpty()) {
                    btns += EqBtn(stringResource(R.string.bk2_open_folder), !busy) { onClose(); BkNav.openFolder(ctx, r.place, r.names.last()) }
                    btns += EqBtn(stringResource(R.string.bk2_share), !busy) {
                        busy = true
                        scope.launch {
                            runCatching { BkFlow.share(ctx, r.place, r.names) { status = it } }.onFailure { status = BkFlow.errText(ctx, r.place, it) }.onSuccess { status = "" }
                            busy = false
                        }
                    }
                }
                if (btns.isNotEmpty()) EqButtons(btns)
                if (r.failed) {
                    val fix = ArrayList<EqBtn>()
                    fix += EqBtn(stringResource(R.string.bk2_pick_other), !busy) { onPickOther() }
                    if (BkFlow.needsAccess(r.place)) fix += EqBtn(stringResource(R.string.bk2_give_access), !busy) { Fm.askAccess(ctx) }
                    EqButtons(fix)
                }
            }
        },
        confirmButton = { TextButton({ if (!busy) onClose() }) { Text(stringResource(R.string.btn_close)) } },
    )
}
