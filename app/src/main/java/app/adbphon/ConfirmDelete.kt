package app.adbphon

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight

/**
 * 17.0: общее окно подтверждения удаления для мест вне файлового менеджера (копии версий, принятые по Wi-Fi файлы и т.п.):
 * что именно удаляется, «Удалить» и «Отмена». Фокус по умолчанию на «Отмена»; «Удалить» срабатывает только по нажатию (OK пульта, касание).
 */
@Composable
fun ConfirmDeleteAlert(what: String, onYes: () -> Unit, onNo: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(12) { kotlinx.coroutines.delay(60); if (runCatching { cancelFocus.requestFocus() }.isSuccess) return@LaunchedEffect } }
    LAlertDialog(
        onDismissRequest = onNo,
        enterFocus = cancelFocus,
        title = { Text(stringResource(R.string.cd_delete_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(what, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.dd_s_perm), color = Color(0xFFC62828))
            }
        },
        confirmButton = { TextButton(onYes) { Text(stringResource(R.string.fm_delete), color = Color(0xFFC62828)) } },
        dismissButton = { TextButton(onNo, Modifier.focusRequester(cancelFocus)) { Text(stringResource(R.string.btn_cancel)) } },
    )
}
