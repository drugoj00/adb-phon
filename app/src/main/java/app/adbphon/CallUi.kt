package app.adbphon

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

/** 7.0: «Люди» (имя + код программы) и кнопка «Позвонить»; выключатель фонового приёма звонков. Показывается под «Подключением по интернету». */
@Composable
fun PeopleSection() {
    val ctx = LocalContext.current
    var add by remember { mutableStateOf(false) }
    var listen by remember { mutableStateOf(CallListen.enabled(ctx)) }
    var myName by remember { mutableStateOf(Calls.myName(ctx)) }
    val people = CallPeople.all(ctx)
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f)
    val busyMsg = stringResource(R.string.call_busy_now)
    val failMsg = stringResource(R.string.call_no_start)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.call_title), fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.call_listen), Modifier.weight(1f), fontSize = 13.sp)
                Switch(listen, { listen = it; CallListen.setEnabled(ctx, it) })
            }
            CField(stringResource(R.string.call_myname), myName, { myName = it; Calls.setMyName(ctx, it) })
            if (people.isEmpty()) Text(stringResource(R.string.call_empty), fontSize = 12.sp, color = dim)
            people.toList().forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(p.code, fontSize = 11.sp, color = dim)
                    }
                    OutlinedButton({
                        when (Calls.dial(ctx, p)) {
                            "busy" -> Toast.makeText(ctx, busyMsg, Toast.LENGTH_SHORT).show()
                            "fail" -> Toast.makeText(ctx, failMsg, Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(stringResource(R.string.call_btn), fontSize = 13.sp) }
                    TextButton({ CallPeople.remove(ctx, p) }) { Text("✕", fontSize = 15.sp) }
                }
            }
            OutlinedButton({ add = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.call_add)) }
            Text(stringResource(R.string.call_hint), fontSize = 12.sp, color = dim)
        }
    }
    if (add) AddPersonDialog { add = false }
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
