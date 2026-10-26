package app.adbphon

import android.content.Context
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
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { LangScope(confirmButton) },
        dismissButton = dismissButton?.let { d -> { LangScope(d) } },
        title = title?.let { t -> { LangScope(t) } },
        text = text?.let { t -> { LangScope(t) } },
    )
}
