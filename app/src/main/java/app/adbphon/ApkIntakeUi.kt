package app.adbphon

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 11.0 (код 60): установка APK по просьбе другого приложения (системный выбор «чем установить», «Открыть с помощью…»).
 * Компактное окно поверх того приложения, из которого пришли: сведения о пакете, «Установить» / «Отмена», ход, итог «Готово» и «Открыть».
 * Главный экран ADB-phon здесь НИКОГДА не поднимается сам: только по нажатию «Открыть настройки ADB-phon» в окне «Нужен доступ».
 */
class ApkIntakeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val uri = intent?.data ?: intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri == null) { finish(); return }
        val app = applicationContext
        runCatching { SafAccess.init(app) }
        AppColors.load(app)
        val prefs = app.getSharedPreferences("adbphon", MODE_PRIVATE)
        val lang = prefs.getString("lang", "auto") ?: "auto"
        val theme = prefs.getString("theme", "auto") ?: "auto"
        val mime = intent?.type
        setContent {
            val dark = when (theme) { "dark" -> true; "light" -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }
            val ui = remember(lang) { Lang.wrap(this@ApkIntakeActivity, lang) }
            CompositionLocalProvider(LocalContext provides ui, LocalLangCode provides lang) {
                MaterialTheme(colorScheme = AppColors.scheme(dark, this@ApkIntakeActivity)) { IntakeScreen(uri, mime) { finish() } }
            }
        }
    }
}

private class IntakeData(val files: List<ApkFile>, val metas: Map<String, ApkMeta?>, val meta: ApkMeta?, val name: String)

@Composable
private fun IntakeScreen(uri: Uri, mime: String?, close: () -> Unit) {
    val ctx = LocalContext.current
    val act = ctx as? Activity ?: (ctx as? android.content.ContextWrapper)?.baseContext as? Activity
    val app = ctx.applicationContext
    val scope = rememberCoroutineScope()
    // 0 читаю файл, 1 сведения и «Установить», 2 идёт установка, 3 итог, 4 нужен доступ, 5 файл не прочитался
    var stage by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<IntakeData?>(null) }
    var resultOk by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }
    val run = remember { InstRun() }

    LaunchedEffect(Unit) {
        val d = withContext(Dispatchers.IO) {
            runCatching {
                val p = ApkIntake.prepare(app, uri, mime)
                val list = if (p.ext == "apk") listOf(ApkFile(p.file.name, Uri.fromFile(p.file), p.file.length(), p.file))
                else Archives.extractApks(app, Uri.fromFile(p.file))
                if (list.isEmpty()) error("empty")
                val metas = list.associate { a -> a.name to runCatching { ApkInfo.readFor(app, a) }.getOrNull() }
                IntakeData(list, metas, metas.values.firstNotNullOfOrNull { it }, p.file.name)
            }.getOrNull()
        }
        if (d == null) stage = 5 else { data = d; stage = 1 }
    }

    // вернулись из системных настроек «Установка неизвестных приложений»: можно продолжать
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val ob = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && stage == 4 && app.packageManager.canRequestPackageInstalls()) stage = 1
        }
        owner.lifecycle.addObserver(ob)
        onDispose { owner.lifecycle.removeObserver(ob) }
    }

    fun go(wipe: Boolean) {
        val d = data ?: return
        val silent = Adb.isConnected && !FmIo.remote
        if (!silent && !app.packageManager.canRequestPackageInstalls()) { stage = 4; return }
        stage = 2
        scope.launch {
            if (silent) {
                val r = installWithProgress(app, d.files, d.metas, run, wipe)
                resultOk = r.second == 0 && r.first > 0
                resultText = run.fails.joinToString("\n")
            } else {
                var last: SessionInstall.Result? = null
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        d.files.all { a ->
                            val parts = a.parts ?: listOfNotNull(a.file)
                            if (a.obb.isNotEmpty() && Bundles.copyObbAny(a.obb) < 0) false
                            else SessionInstall.install(app, parts).also { r -> last = r; if (!r.ok) Bundles.deleteObb(a.obb) }.ok
                        }
                    }.getOrDefault(false)
                }
                resultOk = ok
                resultText = if (ok) "" else if (last?.cancelled == true) app.getString(R.string.fm_cancelled) else last?.message.orEmpty()
            }
            stage = 3
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (stage != 2) close() },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            Modifier.fillMaxWidth(0.9f).widthIn(max = 440.dp).padding(vertical = 16.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val d = data
                val m = d?.meta
                if (stage == 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        Text(stringResource(R.string.ii_ph_prepare), fontSize = 15.sp)
                    }
                }
                if (stage == 5) {
                    Text(stringResource(R.string.intake_fail), fontSize = 15.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(close) { Text(stringResource(R.string.ii_close)) } }
                }
                if (d != null && stage in 1..4) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (m?.icon != null) Image(m.icon, null, Modifier.size(48.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m?.label ?: d.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            if (m != null) Text(m.pkg, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                        }
                    }
                }
                if (d != null && stage == 1) {
                    if (m != null) {
                        val kindText = stringResource(when (m.kind) {
                            ApkMeta.Kind.NEW -> R.string.ii_new; ApkMeta.Kind.UPDATE -> R.string.ii_update
                            ApkMeta.Kind.SAME -> R.string.ii_same; ApkMeta.Kind.DOWNGRADE -> R.string.ii_down
                        })
                        Text(kindText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (m.curName != null) stringResource(R.string.ii_ver_change, m.curName, m.curCode.toInt(), m.verName, m.verCode.toInt())
                            else stringResource(R.string.ii_ver_new, m.verName, m.verCode.toInt()),
                            fontSize = 14.sp,
                        )
                        val bad = MaterialTheme.colorScheme.error
                        if (m.sigDiffers) Text(stringResource(R.string.ii_sig_diff), fontSize = 13.sp, color = bad)
                        if (m.needsNewerAndroid) Text(stringResource(R.string.ii_minsdk_bad, m.minSdk, android.os.Build.VERSION.SDK_INT), fontSize = 13.sp, color = bad)
                        if (m.kind == ApkMeta.Kind.DOWNGRADE) Text(stringResource(if (m.downgradeNeedsWipe) R.string.ii_down_wipe_short else R.string.ii_down_title), fontSize = 13.sp, color = bad)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(close) { Text(stringResource(R.string.btn_cancel)) }
                        when {
                            m != null && m.curPlainSystem && m.kind == ApkMeta.Kind.DOWNGRADE -> {}
                            m != null && m.downgradeNeedsWipe -> Button({ go(true) }) { Text(stringResource(R.string.ii_confirm_wipe), fontSize = 13.sp) }
                            else -> Button({ go(false) }) { Text(stringResource(R.string.ii_confirm_go)) }
                        }
                    }
                }
                if (stage == 2) {
                    Text(stringResource(when (run.phase) { 1 -> R.string.ii_ph_upload; 3 -> R.string.ii_ph_obb; else -> R.string.ii_ph_install }), fontSize = 14.sp)
                    if (run.phase == 1 && run.all > 0) LinearProgressIndicator(progress = { (run.done.toFloat() / run.all).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (stage == 3) {
                    if (resultOk) {
                        Text(stringResource(R.string.ver_done), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(stringResource(R.string.ii_fail_generic), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        if (resultText.isNotBlank()) Text(resultText, fontSize = 13.sp)
                    }
                    val launch = if (resultOk) m?.pkg?.let { app.packageManager.getLaunchIntentForPackage(it) } else null
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(close) { Text(stringResource(R.string.ii_close)) }
                        // «Открыть» только по нажатию
                        if (launch != null) Button({ runCatching { act?.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; close() }) { Text(stringResource(R.string.fm_open)) }
                    }
                }
                if (stage == 4) {
                    Text(stringResource(R.string.ic_need_title), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ic_need_perm), fontSize = 13.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(close) { Text(stringResource(R.string.btn_cancel)) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        // оба действия только по нажатию
                        TextButton({
                            runCatching { act?.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName))) }
                        }) { Text(stringResource(R.string.ic_open_perm), fontSize = 13.sp) }
                        TextButton({
                            runCatching { act?.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; close()
                        }) { Text(stringResource(R.string.ic_open_app), fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}
