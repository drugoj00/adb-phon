package app.adbphon

import androidx.compose.foundation.layout.imePadding

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/** Язык интерфейса, выбранный в настройках: "auto" (как в системе), "ru", "uk", "en". */
object Lang {
    val codes = listOf("auto", "ru", "uk", "en")

    private fun locale(code: String): Locale? = when (code) {
        "ru" -> Locale("ru")
        "uk" -> Locale("uk")
        "en" -> Locale("en")
        else -> null
    }

    /** Ресурсы на выбранном языке (для строк, которые берёт не интерфейс, а ViewModel). */
    fun resources(ctx: Context, code: String): Resources {
        val l = locale(code) ?: return ctx.resources
        val cfg = Configuration(ctx.resources.configuration)
        cfg.setLocale(l)
        return ctx.createConfigurationContext(cfg).resources
    }

    /** Контекст для интерфейса: остаётся прежней Activity, но строки берутся на выбранном языке. */
    fun wrap(base: Context, code: String): Context {
        if (locale(code) == null) return base
        val res = resources(base, code)
        return object : ContextWrapper(base) {
            override fun getResources(): Resources = res
        }
    }
}

/** Код выбранного языка; нужен окнам-диалогам, у которых Compose подставляет свой контекст. */
val LocalLangCode = androidx.compose.runtime.staticCompositionLocalOf { "auto" }

@androidx.compose.runtime.Composable
fun LangScopePublic(content: @androidx.compose.runtime.Composable () -> Unit) = LangScope(content)

@androidx.compose.runtime.Composable
private fun LangScope(content: @androidx.compose.runtime.Composable () -> Unit) {
    val base = androidx.compose.ui.platform.LocalContext.current
    val code = LocalLangCode.current
    val ctx = androidx.compose.runtime.remember(base, code) { Lang.wrap(base, code) }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides ctx) { content() }
}

/** AlertDialog, который показывает строки на языке, выбранном в настройках. */
@androidx.compose.runtime.Composable
fun LAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @androidx.compose.runtime.Composable () -> Unit,
    dismissButton: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    title: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    text: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    wide: Boolean = false,
    ime: Boolean = false,
) {
    androidx.compose.material3.AlertDialog(
        modifier = (if (wide) androidx.compose.ui.Modifier.fillMaxWidth(.94f) else androidx.compose.ui.Modifier).let { if (ime) it.imePadding() else it },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = !wide, decorFitsSystemWindows = !ime),
        onDismissRequest = onDismissRequest,
        confirmButton = { LangScope(confirmButton) },
        dismissButton = dismissButton?.let { d -> { LangScope(d) } },
        title = title?.let { t -> { LangScope(t) } },
        text = text?.let { t -> { LangScope(t) } },
    )
}

/** Dialog с языком из настроек программы (Compose в окнах подставляет контекст системы). */
@androidx.compose.runtime.Composable
fun LDialog(onDismissRequest: () -> Unit, properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties(), content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) { LangScope(content) }
}

/** DropdownMenu с языком из настроек программы. */
@androidx.compose.runtime.Composable
fun LDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.material3.DropdownMenu(expanded, onDismissRequest, modifier) {
        val scope = this
        LangScope { scope.content() }
    }
}
