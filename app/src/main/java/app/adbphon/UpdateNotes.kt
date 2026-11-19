package app.adbphon

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 10.2 (правило 23): окно «Вышла новая версия» показывает список «Что нового» из описания выпуска на GitHub ([Updater.notes]):
 * на языке интерфейса (RU, UK или EN), прокручивается (на ТВ стрелками пульта, у краёв фокус переходит на кнопки «Обновить» и «Позже»).
 * Нет текста: окно как раньше, без списка.
 */
@Composable
fun UpdateNotesDialog(r: Updater.Release, current: String, onYes: () -> Unit, onLater: () -> Unit) {
    val lang = Updater.langOf(LocalLangCode.current)
    val notes = androidx.compose.runtime.remember(r.tag, r.body, lang) { Updater.notes(r.body, lang) }
    val st = rememberScrollState()
    val scope = rememberCoroutineScope()
    LAlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.upd_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.upd_text, r.tag, current))
                if (notes.isNotEmpty()) {
                    Text(stringResource(R.string.upd_whatsnew), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Column(
                        Modifier.heightIn(max = 280.dp).verticalScroll(st).focusable()
                            .onKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (e.key) {
                                    Key.DirectionDown -> if (st.canScrollForward) { scope.launch { st.animateScrollBy(160f) }; true } else false
                                    Key.DirectionUp -> if (st.canScrollBackward) { scope.launch { st.animateScrollBy(-160f) }; true } else false
                                    else -> false
                                }
                            },
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        notes.forEach { line ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("•", color = MaterialTheme.colorScheme.primary)
                                Text(line, fontSize = 14.sp, modifier = Modifier.padding(end = 4.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onYes) { Text(stringResource(R.string.upd_yes)) } },
        dismissButton = { TextButton(onLater) { Text(stringResource(R.string.upd_later)) } },
    )
}
