package app.adbphon

import android.content.Context
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Раздел «Резервная копия» в настройках (группа «Обновления и копии»). 10.0 (bk3, слово владелицы): одна пара действий
 * «Создать копию…» (окно выбора, что включить: настройки ADB-phon, APK, данные приложений, пароль, пользователь, папка; BkOne.kt)
 * и «Восстановить…» (любой тип копии из папки), ниже автокопия по расписанию и откат последнего восстановления.
 * Прежние строки «Папка для копий», «Папка для копий с данными», «Шифровать паролем» переехали в окно «Создать копию…».
 */
@Composable
fun BackupSectionV2(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var create by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf(false) }
    // кнопки первой группы настроек («Резервная копия», «Восстановить из копии») открывают окна сразу
    androidx.compose.runtime.LaunchedEffect(SetHero.req) { when (SetHero.take()) { SetHero.MAKE -> create = true; SetHero.RESTORE -> restore = true } }
    var info by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var auto by remember { mutableStateOf(BkAuto.mode(ctx)) }
    var keep by remember { mutableStateOf(BkAuto.keep(ctx)) }
    var withData by remember { mutableStateOf(BkAuto.withData(ctx)) }
    var autoRes by remember { mutableStateOf(BkAuto.lastResult(ctx)) }
    var autoBusy by remember { mutableStateOf(false) }
    var autoStatus by remember { mutableStateOf("") }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f)

    // 10.0 (set): строки-действия; одна пара «Создать копию…» и «Восстановить…» (bk3)
    SetAction(SetIcons.backup, stringResource(R.string.bk2_make), stringResource(R.string.set_h_bk_make)) { create = true }
    SetAction(SetIcons.restore, stringResource(R.string.bk2_restore), stringResource(R.string.set_h_bk_restore)) { restore = true }

    // ---- автокопия: место и пароль из последней копии, сделанной окном «Создать копию…»
    Text(stringResource(R.string.bk2_auto), fontWeight = FontWeight.Medium, fontSize = if (Marq.tv) 16.sp else 13.sp)
    EqChoices(listOf(
        EqChoice(stringResource(R.string.bk2_auto_off), auto == 0) { auto = 0; BkAuto.setMode(ctx, 0) },
        EqChoice(stringResource(R.string.bk2_auto_day), auto == 1) { auto = 1; BkAuto.setMode(ctx, 1) },
        EqChoice(stringResource(R.string.bk2_auto_week), auto == 2) { auto = 2; BkAuto.setMode(ctx, 2) },
    ), fontSp = if (Marq.tv) 16 else 13)
    if (auto != 0) {
        val place = BkStore.last(ctx, BkStore.KEY_SETTINGS)
        Text(stringResource(R.string.bkp_keep), fontSize = bkDimSp())
        EqChoices(NumberSliderRules.BK_KEEP_PRESETS.map { n -> EqChoice(n.toString(), keep == n) { keep = n; BkAuto.setKeep(ctx, n) } } +
            numCustomChoice(keep, NumberSliderRules.BK_KEEP_PRESETS, NumberSliderRules.BK_KEEP, stringResource(R.string.bkp_keep), { n -> keep = n; BkAuto.setKeep(ctx, n) }), fontSp = if (Marq.tv) 16 else 13)   // 10.0 (num): «Своё значение…»
        EqChoices(listOf(
            EqChoice(stringResource(R.string.bkp_auto_only_settings), !withData) { withData = false; BkAuto.setWithData(ctx, false) },
            EqChoice(stringResource(R.string.bkp_auto_with_data), withData) { withData = true; BkAuto.setWithData(ctx, true) },
        ), perRow = 1, fontSp = if (Marq.tv) 16 else 13)
        val df = remember { java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()) }
        val next = BkSchedule.next(BkAuto.last(ctx), auto, System.currentTimeMillis())
        Text(stringResource(R.string.bk2_in_folder, BkStore.display(ctx, place)), fontSize = bkDimSp(), color = dim)
        Text(stringResource(R.string.bkp_auto_info, stringResource(if (BkStore.encOn(ctx)) R.string.bkp_auto_pass_yes else R.string.bkp_auto_pass_no),
            next?.let { df.format(java.util.Date(it)) } ?: "?"), fontSize = bkDimSp(), color = dim)
        if (withData) Text(stringResource(R.string.bkp_auto_data_info, BkData.autoApps(ctx).size), fontSize = bkDimSp(), color = dim)
        if (place.local && BkStore.needAllFiles()) Text(stringResource(R.string.bkp_err_all_files, place.path), fontSize = bkDimSp(), color = MaterialTheme.colorScheme.error)
        autoRes?.let { Text(stringResource(R.string.bkp_auto_last, it), fontSize = bkDimSp(), color = dim) }
        if (autoStatus.isNotEmpty()) Text(autoStatus, fontSize = bkDimSp(), color = MaterialTheme.colorScheme.primary)
        SetAction(SetIcons.run, stringResource(R.string.bkp_run_now), enabled = !autoBusy) {
            autoBusy = true
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { BkAuto.run(ctx, true) { autoStatus = it } } }
                autoStatus = ""; autoBusy = false; autoRes = BkAuto.lastResult(ctx)
            }
        }
    }
    if (AppBackup.hasRollback(ctx)) SetAction(SetIcons.undo, stringResource(R.string.bk2_rollback)) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { AppBackup.rollback(ctx) } }
            info = if (r.isSuccess) ctx.getString(R.string.bk2_rolled_back) to false else ctx.getString(R.string.bk2_bad_file, r.exceptionOrNull()?.let { bkErrText(ctx, it) } ?: "?") to true
        }
    }

    if (create) BkCreateDialog(vm, emptySet()) { create = false; autoRes = BkAuto.lastResult(ctx) }
    if (restore) BkRestoreDialog { restore = false }
    info?.let { (text, error) ->
        LAlertDialog(
            onDismissRequest = { info = null }, title = { Text(stringResource(R.string.bk2_title)) },
            text = { Text(text, fontSize = bkMainSp(), color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
            confirmButton = { if (!error) androidx.compose.material3.TextButton({ restartApp(ctx) }) { Text(stringResource(R.string.bk2_restart)) } else androidx.compose.material3.TextButton({ info = null }) { Text(stringResource(R.string.btn_close)) } },
            dismissButton = if (!error) ({ androidx.compose.material3.TextButton({ info = null }) { Text(stringResource(R.string.btn_close)) } }) else null,
        )
    }
}

/** После восстановления: перезапуск, чтобы все настройки применились. */
fun restartApp(ctx: Context) {
    val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    ctx.startActivity(i)
    Runtime.getRuntime().exit(0)
}