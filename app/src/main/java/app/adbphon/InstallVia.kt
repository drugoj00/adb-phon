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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import app.adbphon.FloorText as Text
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
 * «Версии / откатить…», восстановление из копии, Меню приложений, модуль облаков.
 * 10.0: то же единое окно, что и в «Файлах» ([InstallerDialog]), и тот же запомненный выбор ([InstallerStore], [InstallerChoice]):
 * запомненный и доступный способ применяется сразу, иначе окно.
 */
object ViaAsk {
    class Req(val count: Int, val parts: Boolean, val done: (InstOpt?) -> Unit)
    var req by mutableStateOf<Req?>(null)

    /** Способы для окна выбора; [parts]: файлы это части одной программы (копия с частями). */
    fun options(ctx: Context, count: Int, parts: Boolean = false): List<InstOpt> = Installers.rows(ctx, "apk", Adb.isConnected, count, parts)

    /** Спросить, чем устанавливать; null: отмена. */
    suspend fun ask(ctx: Context, count: Int, parts: Boolean = false): InstOpt? {
        val opts = options(ctx, count, parts)
        val d = InstallerChoice.decide(Installers.choiceOpts(opts), InstallerStore.get(ctx))
        if (d is InstallerChoice.Decision.Auto) return opts.first { it.id == d.id }
        return suspendCancellableCoroutine { cont ->
            val r = Req(count, parts) { o -> req = null; if (cont.isActive) cont.resume(o) }
            req = r
            cont.invokeOnCancellation { if (req === r) req = null }
        }
    }

    /** Запуск стороннего или системного установщика на файле; «ADB-phon с подтверждением»: своя сессия установки. */
    fun launchExternal(ctx: Context, file: File, o: InstOpt): Boolean = launch(ctx, listOf(file), o)

    /** То же для набора файлов: сессия ставит все части одной программы, сторонний установщик получает первый файл. */
    fun launch(ctx: Context, files: List<File>, o: InstOpt): Boolean = runCatching {
        if (o.id == InstallerChoice.SESSION) { InstallGate.sessionWithInfo(ctx, files); return@runCatching true }
        val file = files.first()
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val c = o.component
        if (c != null) i.setClassName(c.substringBefore('/'), c.substringAfter('/')) else if (o.pkg != null) i.setPackage(o.pkg) else ApkInstall.systemPkg(ctx)?.let { i.setPackage(it) }
        ctx.startActivity(i)
        true
    }.getOrDefault(false)

    /** Спросить и выполнить: ADB-phon тихо, то есть [ours], либо выбранный способ на файлах. Возвращает текст итога. */
    suspend fun installOrAsk(ctx: Context, files: List<File>, ours: suspend () -> String): String {
        val o = ask(ctx, files.size, parts = true) ?: return ctx.getString(R.string.via_cancelled)
        if (o.id == InstallerChoice.QUIET) return ours()
        if (files.isEmpty()) return ctx.getString(R.string.fm_no_app)
        if (o.id == InstallerChoice.SESSION) { InstallGate.sessionWithInfo(ctx, files); return ctx.getString(R.string.inst_installing) }
        return ctx.getString(if (launch(ctx, files, o)) R.string.via_started else R.string.fm_no_app)
    }
}

/** Окно «Установить через…» для [ViaAsk]; включается один раз в корне экрана. */
@Composable
fun ViaAskHost() {
    val r = ViaAsk.req ?: return
    val ctx = LocalContext.current
    val opts = remember(r) { ViaAsk.options(ctx, r.count, r.parts) }
    InstallerDialog(opts, null, onPick = { o, rem -> InstallerStore.afterPick(ctx, o.id, rem); r.done(o) }, onDismiss = { r.done(null) })
}
