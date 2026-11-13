package app.adbphon

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * 7.6: общий выбор установщика вне «Файлов» (окно «Установить через…»): установка из «Установки APK», из интернета, обновление программы,
 * «Версии / откатить…», восстановление из копии, Меню приложений. Включено «Спрашивать, чем устанавливать» (по умолчанию): окно показывается всегда.
 * Выключено: запомненный способ, иначе способ по умолчанию (ADB-phon при ADB/root, иначе системный) без окна выбора.
 */
object ViaAsk {
    class Req(val count: Int, val done: (InstOpt?) -> Unit)
    var req by mutableStateOf<Req?>(null)

    /** Способы для окна выбора; при нескольких файлах за раз ставит только ADB-phon. */
    fun options(ctx: Context, count: Int): List<InstOpt> = ApkInstall.options(ctx, "apk", Adb.isConnected)
        .filter { it.id != "archive" && it.id != "market" }
        .map { o -> if (count > 1 && o.id != "adb") InstOpt(o.id, o.label, ctx.getString(R.string.via_one_by_one), o.pkg, o.icon, o.ai, o.rank, false, o.recommended) else o }

    /** Способ без окна выбора (когда «Спрашивать» выключено). */
    private fun silentChoice(ctx: Context, count: Int): InstOpt? {
        val opts = options(ctx, count)
        val sid = ApkInstall.saved(ctx, "apk")
        return sid?.let { s -> opts.firstOrNull { it.id == s && it.enabled } }
            ?: opts.firstOrNull { it.id == (if (Adb.isConnected) "adb" else "system") && it.enabled }
            ?: opts.firstOrNull { it.enabled }
    }

    /** Спросить, чем устанавливать; null — отмена. */
    suspend fun ask(ctx: Context, count: Int): InstOpt? {
        if (!UiPrefs.askInstaller) return silentChoice(ctx, count)
        return suspendCancellableCoroutine { cont ->
            val r = Req(count) { o -> req = null; if (cont.isActive) cont.resume(o) }
            req = r
            cont.invokeOnCancellation { if (req === r) req = null }
        }
    }

    /** Запуск стороннего или системного установщика на файле. */
    fun launchExternal(ctx: Context, file: File, o: InstOpt): Boolean = runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val c = o.component
        if (c != null) i.setClassName(c.substringBefore('/'), c.substringAfter('/')) else if (o.pkg != null) i.setPackage(o.pkg)
        ctx.startActivity(i)
        true
    }.getOrDefault(false)

    /** Спросить и выполнить: ADB-phon, то есть [ours], либо запуск выбранного установщика на первом файле. Возвращает текст итога. */
    suspend fun installOrAsk(ctx: Context, files: List<File>, ours: suspend () -> String): String {
        val o = ask(ctx, files.size) ?: return ctx.getString(R.string.via_cancelled)
        if (o.id == "adb") return ours()
        val f = files.firstOrNull() ?: return ctx.getString(R.string.fm_no_app)
        return ctx.getString(if (launchExternal(ctx, f, o)) R.string.via_started else R.string.fm_no_app)
    }
}

/** Окно «Установить через…» для [ViaAsk]; включается один раз в корне экрана. */
@Composable
fun ViaAskHost() {
    val r = ViaAsk.req ?: return
    val ctx = LocalContext.current
    val opts = remember(r) { ViaAsk.options(ctx, r.count) }
    val tv = Marq.tv
    LAlertDialog(
        onDismissRequest = { r.done(null) },
        title = { Text(stringResource(R.string.inst_title), maxLines = 1) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).barScroll(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val first = opts.indexOfFirst { it.enabled }
                val fr = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
                opts.forEachIndexed { idx, o ->
                    Row(
                        (if (idx == first) Modifier.focusRequester(fr) else Modifier).fillMaxWidth().graphicsLayer { alpha = if (o.enabled) 1f else .55f }
                            .then(if (o.recommended) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = .10f), RoundedCornerShape(10.dp)) else Modifier)
                            .clickable(enabled = o.enabled) { r.done(o) }.heightIn(min = if (tv) 64.dp else 52.dp).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val sz = if (tv) 44.dp else 36.dp
                        val bmp = remember(o.id) { o.ai?.let { ai -> runCatching { val d = ai.loadIcon(ctx.packageManager); android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888).also { b -> d.setBounds(0, 0, 96, 96); d.draw(android.graphics.Canvas(b)) } }.getOrNull() } }
                        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size(sz)) else if (o.pkg != null && o.id == "system") AppIcon(o.pkg, sz) else if (o.icon != null) Icon(o.icon, null, Modifier.size(sz), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(o.label, fontSize = if (tv) 18.sp else 15.sp, maxLines = 2)
                            if (o.recommended) Text(stringResource(R.string.inst_recommended), fontSize = if (tv) 13.sp else 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            o.hint?.let { Text(it, fontSize = if (tv) 14.sp else 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f), maxLines = 5) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ r.done(null) }) { Text(stringResource(R.string.btn_cancel)) } },
    )
}
