package app.adbphon

import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 23.11.2026: форма как у Material3 по умолчанию (вид окна не меняется), без прямых импортов Button/TextButton (ButtonStyleTest). */
private val PILL_SHAPE = androidx.compose.foundation.shape.CircleShape
private val PILL_TEXT_SHAPE = androidx.compose.foundation.shape.CircleShape

/**
 * 15.0 код 75 (правило владелицы «никаких тихих установок нигде»): состояние окна установки по просьбе другой нашей программы (Меню приложений, Pulsar, Vision и др.).
 * [InstallService] ведёт состояние, [SilentInstallActivity] его показывает: что ставится (название, установлено → новая с кодами, размер), способ, ход, итог или причина.
 */
object SilentInstallState {
    /** 0 подготовка, 1 идёт установка, 2 установлено, 3 не установлено (текст — причина), 4 отменено. */
    class Data(
        val stage: Int, val caller: String, val label: String, val pkg: String, val info: String, val method: String,
        val pct: Int, val text: String, val icon: ImageBitmap?, val newName: String = "", val newCode: Long = 0L,
    )

    var cur by mutableStateOf<Data?>(null)
}

class SilentInstallActivity : ComponentActivity() {
    companion object { @Volatile var visible = false }
    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() { visible = false; super.onStop() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        AppColors.load(app)
        val prefs = app.getSharedPreferences("adbphon", MODE_PRIVATE)
        val lang = prefs.getString("lang", "auto") ?: "auto"
        val theme = prefs.getString("theme", "auto") ?: "auto"
        setContent {
            val dark = when (theme) { "dark" -> true; "light" -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }
            val ui = remember(lang) { Lang.wrap(this@SilentInstallActivity, lang) }
            CompositionLocalProvider(LocalContext provides ui, LocalLangCode provides lang) {
                MaterialTheme(colorScheme = AppColors.scheme(dark, this@SilentInstallActivity)) { SilentScreen { finish() } }
            }
        }
    }
}

@Composable
private fun SilentScreen(close: () -> Unit) {
    val ctx = LocalContext.current
    val d = SilentInstallState.cur
    if (d == null) { androidx.compose.runtime.LaunchedEffect(Unit) { close() }; return }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (d.stage >= 2) close() },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            Modifier.fillMaxWidth(0.9f).widthIn(max = 440.dp).padding(vertical = 16.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.silent_win_title, d.caller), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (d.icon != null) Image(d.icon, null, Modifier.size(48.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.label.ifBlank { d.pkg }, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        if (d.pkg.isNotBlank()) Text(d.pkg, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                    }
                }
                if (d.info.isNotBlank()) Text(d.info, fontSize = 14.sp)
                if (d.method.isNotBlank()) Text(stringResource(R.string.silent_win_method, d.method), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f))
                when (d.stage) {
                    0 -> Text(stringResource(R.string.ii_ph_prepare), fontSize = 14.sp)
                    1 -> if (d.pct >= 0) {
                        Text(stringResource(R.string.silent_win_progress, d.pct), fontSize = 14.sp)
                        ThemedProgress({ d.pct / 100f }, Modifier.fillMaxWidth())   // определённая полоска, без бегущей анимации
                    } else Text(stringResource(R.string.ii_ph_install), fontSize = 14.sp)   // системный установщик ход не сообщает: полоска не рисуется (не выдумывается)
                    2 -> Text(stringResource(R.string.silent_win_done, d.label.ifBlank { d.pkg }, d.newName, d.newCode.toInt()), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    3 -> {
                        Text(stringResource(R.string.ii_fail_generic), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        if (d.text.isNotBlank()) Text(d.text, fontSize = 13.sp)
                    }
                    else -> Text(stringResource(R.string.silent_win_cancelled), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                if (d.stage >= 2) {
                    val launch = if (d.stage == 2) ctx.packageManager.getLaunchIntentForPackage(d.pkg) else null
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(close, shape = PILL_TEXT_SHAPE) { Text(stringResource(R.string.ii_close)) }
                        // «Открыть» только по нажатию
                        if (launch != null) Button({ runCatching { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; close() }, shape = PILL_SHAPE) { Text(stringResource(R.string.fm_open)) }
                    }
                }
            }
        }
    }
}

/**
 * 15.0 код 75: окно ADB-phon для установок, которые идут внутри самой программы без общего окна «Установка» (Tailscale, Меню приложений, установка из копии, файл из системы):
 * сведения (название, установлено → новая, коды, размер), способ, ход (определённый, если ход известен), итог или причина. Окно то же, что для приёма от других программ.
 */
object InstallWindow {
    suspend fun run(ctx: Context, caller: String, f: File, block: suspend (InstRun) -> Pair<Boolean, String>): Boolean {
        val app = ctx.applicationContext
        val af = ApkFile(f.name, Uri.fromFile(f), f.length(), f)
        val meta = withContext(Dispatchers.IO) { runCatching { ApkInfo.readFor(app, af) }.getOrNull() }
        val label = meta?.label ?: f.name
        val pkg = meta?.pkg.orEmpty()
        val mb = apkMb(f.length())
        val cur = meta?.curName
        val info = if (meta == null) "" else if (cur != null) app.getString(R.string.silent_win_info_upd, label, cur, meta.curCode.toInt(), meta.verName, meta.verCode.toInt(), mb)
            else app.getString(R.string.silent_win_info_new, label, meta.verName, meta.verCode.toInt(), mb)
        val method = app.getString(if (Adb.isRoot) R.string.silent_m_root else if (Adb.isConnected) R.string.silent_m_adb else R.string.silent_m_system)
        fun set(stage: Int, pct: Int, text: String) {
            SilentInstallState.cur = SilentInstallState.Data(stage, caller, label, pkg, info, method, pct, text, meta?.icon, meta?.verName.orEmpty(), meta?.verCode ?: 0L)
        }
        set(1, -1, "")
        withContext(Dispatchers.Main) { runCatching { app.startActivity(Intent(app, SilentInstallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) } }
        val run = InstRun()
        val poll = kotlinx.coroutines.GlobalScope.launch(Dispatchers.Default) {
            while (true) { set(1, if (run.all > 0) (run.overall * 100).toInt().coerceIn(0, 100) else -1, ""); kotlinx.coroutines.delay(500) }
        }
        val r = try { block(run) } catch (e: kotlinx.coroutines.CancellationException) { poll.cancel(); throw e } catch (e: Throwable) { false to (e.message ?: e.javaClass.simpleName) }
        poll.cancel()
        set(if (r.first) 2 else 3, 100, r.second)
        return r.first
    }
}

/**
 * 16.0 код 77: обновление самой программы по ADB идёт отдельным процессом, а наш процесс при замене файла завершается. Поэтому перед началом показывается окно ADB-phon
 * (что ставится, способ, ход неизвестен), а цель записывается в настройки: после перезапуска окно сообщает «Установлено» (или что обновление не состоялось).
 */
object SelfInstallNotice {
    private const val KEY = "self_install_target"

    fun begin(ctx: Context, f: File) {
        val app = ctx.applicationContext
        val af = ApkFile(f.name, Uri.fromFile(f), f.length(), f)
        val meta = runCatching { ApkInfo.readFor(app, af) }.getOrNull() ?: return
        val mb = apkMb(f.length())
        val info = if (meta.curName != null) app.getString(R.string.silent_win_info_upd, meta.label, meta.curName, meta.curCode.toInt(), meta.verName, meta.verCode.toInt(), mb)
            else app.getString(R.string.silent_win_info_new, meta.label, meta.verName, meta.verCode.toInt(), mb)
        val method = app.getString(R.string.silent_m_adb)
        SilentInstallState.cur = SilentInstallState.Data(1, "ADB-phon", meta.label, meta.pkg, info, method, -1, "", meta.icon, meta.verName, meta.verCode)
        app.getSharedPreferences("adbphon", Context.MODE_PRIVATE).edit()
            .putString(KEY, listOf(meta.label, meta.verName, meta.verCode, meta.pkg, System.currentTimeMillis(), info.replace("|", "/"), method).joinToString("|")).commit()
        runCatching { app.startActivity(Intent(app, SilentInstallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
    }

    /** Обновление отклонено системой: окно сообщает причину, цель обновления снимается. */
    fun failed(ctx: Context, why: String) {
        val app = ctx.applicationContext
        val d = SilentInstallState.cur
        app.getSharedPreferences("adbphon", Context.MODE_PRIVATE).edit().remove(KEY).apply()
        if (d != null) SilentInstallState.cur = SilentInstallState.Data(3, d.caller, d.label, d.pkg, d.info, d.method, 100, why, d.icon, d.newName, d.newCode)
        runCatching { app.startActivity(Intent(app, SilentInstallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
    }

    /** При запуске: если обновление было начато, показать итог в окне. */
    fun showResult(ctx: Context) {
        val app = ctx.applicationContext
        val sp = app.getSharedPreferences("adbphon", Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, null)
        android.util.Log.i("SelfInstall", "showResult: цель=" + (raw?.take(60) ?: "нет"))
        val p = (raw ?: return).split("|")
        if (p.size < 7) { sp.edit().remove(KEY).apply(); return }
        val code = p[2].toLongOrNull() ?: 0L
        val at = p[4].toLongOrNull() ?: 0L
        val inst = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull() ?: return
        val icode = if (Build.VERSION.SDK_INT >= 28) inst.longVersionCode else @Suppress("DEPRECATION") inst.versionCode.toLong()
        val ok = icode >= code
        android.util.Log.i("SelfInstall", "установлено=$icode цель=$code ok=$ok")
        if (!ok && System.currentTimeMillis() - at < 10 * 60_000L) return   // обновление ещё могло не дойти до замены файла
        sp.edit().remove(KEY).apply()
        SilentInstallState.cur = SilentInstallState.Data(if (ok) 2 else 3, "ADB-phon", p[0], p[3], p[5], p[6], 100, if (ok) "" else app.getString(R.string.silent_win_selfnot), null, inst.versionName.orEmpty(), icode)
        runCatching { app.startActivity(Intent(app, SilentInstallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
    }
}

/** 26.11.2026: у маленьких APK вместо «0.0 МБ» показываются сотые доли. */
internal fun apkMb(bytes: Long): String {
    val v = bytes / 1048576.0
    return when { v >= 0.1 -> "%.1f".format(java.util.Locale.US, v); v >= 0.01 -> "%.2f".format(java.util.Locale.US, v); else -> "<0.01" }
}
