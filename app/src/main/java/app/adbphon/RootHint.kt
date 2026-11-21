package app.adbphon

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 17.0 (код 80): подсказка, как выдать root. В новых версиях программ управления root приложение получает root только из самой программы
 * управления (вкладка «Суперпользователь»), окна запроса при первом su нет: su просто отказывает. Если режим root включён, su есть на устройстве,
 * а root не выдан, показывается окно с кнопками «Открыть программу управления root» и «Повторить».
 * Само появление окна: Adb.tryRoot (отказ su при включённом режиме root; по нажатию «Подключить через root» всегда, само при запуске один раз за сеанс).
 */
object RootHint {
    /** Тестовая проверка (TEST_HOOK): файл root_force.txt в папке файлов приложения (Android/data/app.adbphon/files) = su считается присутствующим. В выпуске false. */
    const val TEST_HOOK = false
    fun forceSu(): Boolean = TEST_HOOK && runCatching { java.io.File(FmIo.app.getExternalFilesDir(null), "root_force.txt").exists() }.getOrDefault(false)

    /** Известные программы управления root (по порядку попыток запуска). */
    val MANAGERS = listOf(
        "com.topjohnwu.magisk", "io.github.huskydg.magisk", "io.github.vvb2060.magisk",
        "me.weishu.kernelsu", "com.rifsxd.ksunext", "me.bmax.apatch",
    )

    var show by mutableStateOf(false)
    /** 0: обычный текст; 1: программа управления root не найдена; 2: повторная проверка снова отказала. */
    var note by mutableStateOf(0)
    private var autoShown = false

    /** Вызывается при отказе su. [manual]: человек сам нажал «Подключить через root» или «Повторить». */
    fun report(manual: Boolean) {
        if (!manual) { if (autoShown) return; autoShown = true }
        note = if (show) 2 else 0
        show = true
        AppLog.i("ADBphon", "root: su отказал, приложению не выдан root; показана подсказка")
    }

    fun granted() { show = false; note = 0 }

    /** Запускает первую найденную программу управления root. false: ни одной нет. */
    fun openManager(ctx: Context): Boolean {
        val pm = ctx.packageManager
        for (p in MANAGERS) {
            val i = runCatching { pm.getLaunchIntentForPackage(p) }.getOrNull() ?: continue
            if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) {
                AppLog.i("ADBphon", "root: открыта программа управления root ($p)")
                return true
            }
        }
        AppLog.i("ADBphon", "root: программа управления root не найдена")
        return false
    }
}

@Composable
fun RootHintHost(vm: MainViewModel) {
    if (!RootHint.show) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LAlertDialog(
        onDismissRequest = { RootHint.show = false },
        title = { Text(stringResource(R.string.roothint_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.roothint_text), fontSize = 14.sp)
                when (RootHint.note) {
                    1 -> Text(stringResource(R.string.roothint_not_found), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    2 -> Text(stringResource(R.string.roothint_still), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
                Button({ RootHint.note = if (RootHint.openManager(ctx)) 0 else 1 }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.roothint_open)) }
                OutlinedButton({ vm.connectRoot() }, Modifier.fillMaxWidth(), enabled = !vm.busy) { Text(stringResource(R.string.roothint_retry)) }
            }
        },
        confirmButton = { TextButton({ RootHint.show = false }) { Text(stringResource(R.string.btn_close)) } },
    )
}
