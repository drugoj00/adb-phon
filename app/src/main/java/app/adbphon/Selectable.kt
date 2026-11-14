package app.adbphon

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable

/**
 * 9.0: выделение и копирование текста долгим нажатием (раньше нигде нельзя было скопировать текст из окон и страниц).
 * Применяется к страницам вкладок ([TabColumn]) и ко всем окнам ([LAlertDialog], [LDialog]). На ТВ и приставках (без касания) не включается:
 * у выделения свой участок фокуса, пульту он не нужен.
 */
@Composable
fun SelectableArea(content: @Composable () -> Unit) {
    if (Marq.tv) content() else SelectionContainer { content() }
}

/** Строка с собственным долгим нажатием (меню): выделение текста в ней отключено, чтобы два долгих нажатия не конфликтовали. */
@Composable
fun NoSelBox(content: @Composable BoxScope.() -> Unit) {
    if (Marq.tv) Box(content = content) else DisableSelection { Box(content = content) }
}

/** 9.0: копирование текста (название или адрес устройства) в буфер обмена и короткое сообщение «Скопировано». */
fun copyText(ctx: android.content.Context, text: String) {
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
    cm.setPrimaryClip(android.content.ClipData.newPlainText("adbphon", text))
    android.widget.Toast.makeText(ctx, ctx.getString(R.string.copied) + ": " + text.take(60), android.widget.Toast.LENGTH_SHORT).show()
}
