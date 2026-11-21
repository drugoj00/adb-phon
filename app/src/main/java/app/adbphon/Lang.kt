package app.adbphon

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
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
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides ctx) {
        // 10.5: текст всплывающих окон жирнее (значения и основной текст полужирные, кнопки жирные), размер прежний
        val th = androidx.compose.material3.MaterialTheme.typography
        val semi = androidx.compose.ui.text.font.FontWeight.SemiBold
        val bold = androidx.compose.runtime.remember(th) {
            th.copy(
                bodyLarge = th.bodyLarge.copy(fontWeight = semi), bodyMedium = th.bodyMedium.copy(fontWeight = semi), bodySmall = th.bodySmall.copy(fontWeight = semi),
                titleLarge = th.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), titleMedium = th.titleMedium.copy(fontWeight = semi), titleSmall = th.titleSmall.copy(fontWeight = semi),
                headlineSmall = th.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                labelLarge = th.labelLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), labelMedium = th.labelMedium.copy(fontWeight = semi),
            )
        }
        // 11.0: окна считают масштаб от системного размера напрямую (а не от того, что досталось от родителя): так окна всегда того же размера, что и экран
        val dm = base.resources.displayMetrics
        val absDensity = androidx.compose.ui.unit.Density(dm.density * AppScale.densMul(), base.resources.configuration.fontScale * AppScale.fontMul())
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides absDensity) {
        androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.MaterialTheme.colorScheme, shapes = androidx.compose.material3.MaterialTheme.shapes, typography = bold) {
            ClickSoundHost {
                val st = androidx.compose.material3.LocalTextStyle.current
                val w = st.fontWeight?.weight ?: 400
                androidx.compose.material3.ProvideTextStyle(if (w < 600) st.copy(fontWeight = semi) else st) { TvFontScope(true, content) }
            }
        }
        }   // absDensity
    }   // 10.0 (tvfont): окна на ТВ: «Размер шрифта» и минимум размеров (TvFont.kt)
}

/** AlertDialog, который показывает строки на языке, выбранном в настройках. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@androidx.compose.runtime.Composable
fun LAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @androidx.compose.runtime.Composable () -> Unit,
    dismissButton: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    title: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    text: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    wide: Boolean = false,
    ime: Boolean = false,
    /** 17.0: куда уходит фокус при входе в окно клавишей/пультом (окна удаления: «Отмена»). */
    enterFocus: androidx.compose.ui.focus.FocusRequester? = null,
) {
    androidx.compose.material3.AlertDialog(
        modifier = (if (wide) androidx.compose.ui.Modifier.fillMaxWidth(.94f) else androidx.compose.ui.Modifier).let { if (ime) it.imePadding() else it }.imeGateKeys()
            .let { if (enterFocus != null) it.focusProperties { enter = { enterFocus } }.focusGroup() else it },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = !wide, decorFitsSystemWindows = !ime),
        onDismissRequest = onDismissRequest,
        confirmButton = { LangScope(confirmButton) },
        dismissButton = dismissButton?.let { d -> { LangScope(d) } },
        title = title?.let { t -> { LangScope { SelectableArea(t) } } },
        text = text?.let { t -> { LangScope { SelectableArea(t) } } },
    )
}

/** Dialog с языком из настроек программы (Compose в окнах подставляет контекст системы). */
@androidx.compose.runtime.Composable
fun LDialog(onDismissRequest: () -> Unit, properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties(), content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.imeGateKeys()) { LangScope { SelectableArea(content) } } }
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
