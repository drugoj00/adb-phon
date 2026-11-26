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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
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

/** 23.11.2026: форма как у Material3 по умолчанию (вид окна не меняется), без прямых импортов Button/TextButton (ButtonStyleTest). */
private val PILL_SHAPE = androidx.compose.foundation.shape.CircleShape
private val PILL_TEXT_SHAPE = androidx.compose.foundation.shape.CircleShape

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
        runCatching { Marq.init(app) }
        AppColors.load(app)
        val prefs = app.getSharedPreferences("adbphon", MODE_PRIVATE)
        val lang = prefs.getString("lang", "auto") ?: "auto"
        val theme = prefs.getString("theme", "auto") ?: "auto"
        val mime = intent?.type
        setContent {
            val dark = when (theme) { "dark" -> true; "light" -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }
            val ui = remember(lang) { Lang.wrap(this@ApkIntakeActivity, lang) }
            CompositionLocalProvider(LocalContext provides ui, LocalLangCode provides lang) {
                // 22.11.2026: на ТВ и приставках крупный шрифт окна (TvFontScope); на телефоне ничего не меняется
                MaterialTheme(colorScheme = AppColors.scheme(dark, this@ApkIntakeActivity)) { TvFontScope(true) { IntakeScreen(uri, mime) { finish() } } }
            }
        }
    }
}

private class IntakeData(val files: List<ApkFile>, val metas: Map<String, ApkMeta?>, val meta: ApkMeta?, val name: String, val keys: List<String>)

/** Ключи защиты от повторного запуска: системная сессия и «adb:»-путь (ADB/root) одного и того же пакета и файла. */
private fun IntakeData.allKeys(): List<String> = keys + keys.map { "adb:$it" }

@Composable
private fun IntakeScreen(uri: Uri, mime: String?, close: () -> Unit) {
    val ctx = LocalContext.current
    val act = ctx as? Activity ?: (ctx as? android.content.ContextWrapper)?.baseContext as? Activity
    val app = ctx.applicationContext
    // 0 читаю файл, 1 сведения и «Установить», 2 идёт установка, 3 итог, 4 нужен доступ, 5 файл не прочитался
    var stage by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<IntakeData?>(null) }
    var resultOk by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }
    val run = remember { InstRun() }
    val viaAdb = Adb.isConnected && !FmIo.remote
    // 23.11.2026 (18.0): способ фиксируется при нажатии «Установить»: помощник ADB мог подняться во время установки системной сессией, а подпись менялась на «ADB»
    var lockedVia by remember { mutableStateOf<Boolean?>(null) }
    val methodName = stringResource(if (lockedVia == false) R.string.silent_m_system else if (Adb.isRoot && !FmIo.remote) R.string.silent_m_root else if (lockedVia ?: viaAdb) R.string.silent_m_adb else R.string.silent_m_system)
    val openedAt = remember { System.currentTimeMillis() }
    val cancelledText = stringResource(R.string.fm_cancelled)
    val mainFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // установка этого же пакета и файла уже идёт (в том числе начата окном, которое система пересоздала): второй запуск не нужен
    val activeNow by InstallGuard.active.collectAsState()
    val busyNow = data?.let { d -> activeNow.isNotEmpty() && InstallGuard.anyActive(d.allKeys()) } == true
    var sawBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val d = withContext(Dispatchers.IO) {
            runCatching {
                val p = ApkIntake.prepare(app, uri, mime)
                val list = if (p.ext == "apk") listOf(ApkFile(p.file.name, Uri.fromFile(p.file), p.file.length(), p.file))
                else Archives.extractApks(app, Uri.fromFile(p.file))
                if (list.isEmpty()) error("empty")
                val metas = list.associate { a -> a.name to runCatching { ApkInfo.readFor(app, a) }.getOrNull() }
                val keys = list.map { a -> SessionInstall.guardKey(app, a.parts ?: listOfNotNull(a.file)) }
                IntakeData(list, metas, metas.values.firstNotNullOfOrNull { it }, p.file.name, keys)
            }.getOrNull()
        }
        if (d == null) { stage = 5; return@LaunchedEffect }
        data = d
        // 22.11.2026 (18.0): тот же пакет и файл только что удачно установлены (окно пересоздано системой, повторный Intent): сразу итог, а не «Установить»
        if (InstallGuard.recentSuccess(ctx, d.allKeys()) != null) { resultOk = true; resultText = ""; stage = 3 } else stage = 1
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

    // установку, начатую другим экземпляром окна, довёл он: когда она закончилась, здесь показывается её итог (а не «Установить» ещё раз)
    LaunchedEffect(busyNow) {
        val d = data ?: return@LaunchedEffect
        if (busyNow) { sawBusy = true; return@LaunchedEffect }
        if (sawBusy && stage == 1) {
            sawBusy = false
            val f = InstallGuard.finished(d.allKeys(), openedAt)
            if (f != null) { resultOk = f.ok; resultText = if (f.cancelled) cancelledText else if (f.ok) "" else f.text; stage = 3 }
        }
    }

    // пульт: фокус на главную кнопку окна (Установить / Закрыть), чтобы хватало одного OK
    LaunchedEffect(stage, busyNow) {
        if (stage == 1 || stage == 3 || stage == 5) { kotlinx.coroutines.delay(150); runCatching { mainFocus.requestFocus() } }
    }

    fun go(wipe: Boolean) {
        val d = data ?: return
        if (stage != 1) return                         // повторное нажатие, пока идёт установка, игнорируется
        // 15.0 код 75: это только СПОСОБ (через ADB без системного запроса или системным установщиком); окно с сведениями, способом, ходом и итогом показано всегда
        val silent = viaAdb
        lockedVia = silent
        if (!silent && !app.packageManager.canRequestPackageInstalls()) { stage = 4; return }
        if (InstallGuard.anyActive(d.allKeys())) return
        stage = 2
        run.ok = 0; run.bad = 0; run.fails.clear(); run.okItems.clear(); run.finished = false; run.running = true
        run.total = d.files.size; run.index = 1; run.done = 0L; run.phase = 1
        run.all = d.files.sumOf { it.size.coerceAtLeast(1L) }.coerceAtLeast(1L)
        run.name = d.meta?.label ?: d.name
        // установка идёт в общем потоке программы: закрытие или пересоздание окна (на слабом устройстве система выгружает окно, пока открыт системный установщик) её не обрывает
        InstallGuard.scope.launch {
            if (silent) {
                val begun = d.keys.map { "adb:$it" }.filter { InstallGuard.tryBegin(it) }
                if (begun.size != d.keys.size) { begun.forEach { InstallGuard.end(it) }; stage = 1; return@launch }
                var ok = false; var text = ""
                try {
                    val r = installWithProgress(app, d.files, d.metas, run, wipe)
                    ok = r.second == 0 && r.first > 0
                    text = run.fails.joinToString("\n")
                } finally { begun.forEach { InstallGuard.end(it, InstallGuard.Done(ok, false, text, System.currentTimeMillis())) } }
                resultOk = ok; resultText = text
            } else {
                var last: SessionInstall.Result? = null
                var base = 0L
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        d.files.withIndex().all { (idx, a) ->
                            val parts = a.parts ?: listOfNotNull(a.file)
                            run.index = idx + 1; run.name = d.metas[a.name]?.label ?: a.name
                            val sz = a.size.coerceAtLeast(1L)
                            val one = if (a.obb.isNotEmpty() && Bundles.copyObbAny(a.obb) < 0) false
                            else SessionInstall.install(app, parts) { ph, dn, _ -> run.phase = ph; run.done = base + dn.coerceAtMost(sz) }
                                .also { r -> last = r; if (!r.ok) Bundles.deleteObb(a.obb) }.ok
                            base += sz
                            one
                        }
                    }.getOrDefault(false)
                }
                if (last?.busy == true) { run.running = false; stage = 1; return@launch }   // такая установка уже идёт: окно показывает ожидание
                resultOk = ok
                resultText = if (ok) "" else if (last?.cancelled == true) cancelledText else last?.message.orEmpty()
            }
            run.running = false
            stage = 3
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (stage != 2) close() },
        contentAlignment = Alignment.Center,
    ) {
        // 22.11.2026 (Galaxy A7: экран 320 dp при крупном шрифте): окно не выше 85% экрана, кнопки закреплены внизу и видны всегда, прокручивается только содержимое над ними
        val maxH = maxHeight * 0.85f
        Surface(
            Modifier.fillMaxWidth(0.94f).widthIn(max = 440.dp).heightIn(max = maxH).padding(vertical = 8.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp,
        ) {
            val d = data
            val m = d?.meta
            val already = d != null && stage == 1 && !busyNow && d.metas.isNotEmpty() && d.metas.values.all { it?.kind == ApkMeta.Kind.SAME }
            val launch = if (m != null && (already || (stage == 3 && resultOk))) app.packageManager.getLaunchIntentForPackage(m.pkg) else null
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp)) {
                Column(Modifier.weight(1f, fill = false).barScroll(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (stage == 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                            Text(stringResource(R.string.ii_ph_prepare), fontSize = 14.sp, lineHeight = 17.sp)
                        }
                    }
                    if (stage == 5) Text(stringResource(R.string.intake_fail), fontSize = 14.sp, lineHeight = 17.sp)
                    if (d != null && stage in 1..4) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (m?.icon != null) Image(m.icon, null, Modifier.size(40.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m?.label ?: d.name, fontSize = 16.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold)
                                if (m != null) Text(m.pkg, fontSize = 11.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                            }
                        }
                    }
                    if (d != null && stage == 1) {
                        if (busyNow) {
                            Text(stringResource(R.string.ii_busy_wait), fontSize = 13.sp, lineHeight = 16.sp)
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        } else if (already && m != null) {
                            // повторный запрос того же пакета и той же версии, которая уже стоит: установка не предлагается
                            Text(stringResource(R.string.ii_already, m.verName, m.verCode.toInt()), fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
                        } else if (m != null) {
                            val kindText = stringResource(when (m.kind) {
                                ApkMeta.Kind.NEW -> R.string.ii_new; ApkMeta.Kind.UPDATE -> R.string.ii_update
                                ApkMeta.Kind.SAME -> R.string.ii_same; ApkMeta.Kind.DOWNGRADE -> R.string.ii_down
                            })
                            Text(kindText, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (m.curName != null) stringResource(R.string.ii_ver_change, m.curName, m.curCode.toInt(), m.verName, m.verCode.toInt())
                                else stringResource(R.string.ii_ver_new, m.verName, m.verCode.toInt()),
                                fontSize = 13.sp, lineHeight = 16.sp,
                            )
                            val bad = MaterialTheme.colorScheme.error
                            val mbSize = apkMb(d.files.sumOf { it.size })
                            // 17.0 (код 83): размер всего набора, число частей и предупреждение об отсутствии языка устройства; 22.11.2026: размер и число частей в одной строке
                            val nParts = d.files.singleOrNull()?.parts?.size
                            Text(stringResource(R.string.silent_win_size, mbSize) + (if (nParts != null) " · " + stringResource(R.string.ii_parts, nParts) else ""), fontSize = 12.sp, lineHeight = 15.sp)
                            d.files.firstNotNullOfOrNull { it.missLang }?.let { ml -> Text(stringResource(R.string.ii_lang_missing, ml), fontSize = 12.sp, lineHeight = 15.sp, color = Color(0xFFE65100), fontWeight = FontWeight.Medium) }
                            Text(stringResource(R.string.silent_win_method, methodName), fontSize = 12.sp, lineHeight = 15.sp)
                            if (m.sigDiffers) Text(stringResource(R.string.ii_sig_diff), fontSize = 12.sp, lineHeight = 15.sp, color = bad)
                            if (m.needsNewerAndroid) Text(stringResource(R.string.ii_minsdk_bad, m.minSdk, android.os.Build.VERSION.SDK_INT), fontSize = 12.sp, lineHeight = 15.sp, color = bad)
                            if (m.kind == ApkMeta.Kind.DOWNGRADE) Text(stringResource(if (m.downgradeNeedsWipe) R.string.ii_down_wipe_short else R.string.ii_down_title), fontSize = 12.sp, lineHeight = 15.sp, color = bad)
                        }
                    }
                    if (stage == 2) {
                        // «Идёт установка…» видно сразу и держится до итога; повторно нажимать не нужно
                        Text(stringResource(R.string.ii_installing_now), fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.silent_win_method, methodName), fontSize = 12.sp, lineHeight = 15.sp)
                        Text(
                            stringResource(when (run.phase) { 1 -> R.string.ii_ph_upload; 3 -> R.string.ii_ph_obb; 4 -> R.string.ii_ph_system; else -> R.string.ii_ph_install }) +
                                (if (run.total > 1) "  ·  " + stringResource(R.string.ii_prog_count, run.index.coerceAtLeast(1), run.total) else ""),
                            fontSize = 13.sp, lineHeight = 16.sp,
                        )
                        ThemedProgress({ run.overall }, Modifier.fillMaxWidth())   // 20.11.2026: без бегущей полоски (этапы установки)
                        Text(stringResource(if (run.phase == 4) R.string.ii_ph_system_hint else R.string.ii_slow_hint), fontSize = 12.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f))
                    }
                    if (stage == 3) {
                        if (resultOk) {
                            Text(stringResource(R.string.ver_done), fontSize = 16.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            if (m != null) Text(stringResource(R.string.silent_win_done, m.label, m.verName, m.verCode.toInt()), fontSize = 13.sp, lineHeight = 16.sp)
                        } else {
                            Text(stringResource(R.string.ii_fail_generic), fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            if (resultText.isNotBlank()) Text(resultText, fontSize = 12.sp, lineHeight = 15.sp)
                        }
                    }
                    if (stage == 4) {
                        Text(stringResource(R.string.ic_need_title), fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.ic_need_perm), fontSize = 13.sp, lineHeight = 16.sp)
                    }
                }
                // закреплённые кнопки: всегда видны, прокручивается только содержимое выше
                Spacer(Modifier.height(4.dp))
                when (stage) {
                    1 -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        if (busyNow || already) {
                            TextButton(close, Modifier.focusRequester(mainFocus), shape = PILL_TEXT_SHAPE) { Text(stringResource(R.string.ii_close)) }
                            if (launch != null) Button({ runCatching { act?.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; close() }, shape = PILL_SHAPE) { Text(stringResource(R.string.fm_open)) }
                        } else {
                            TextButton(close, shape = PILL_TEXT_SHAPE) { Text(stringResource(R.string.btn_cancel)) }
                            when {
                                m != null && m.curPlainSystem && m.kind == ApkMeta.Kind.DOWNGRADE -> {}
                                m != null && m.downgradeNeedsWipe -> Button({ go(true) }, Modifier.focusRequester(mainFocus), shape = PILL_SHAPE) { Text(stringResource(R.string.ii_confirm_wipe), fontSize = 13.sp) }
                                else -> Button({ go(false) }, Modifier.focusRequester(mainFocus), shape = PILL_SHAPE) { Text(stringResource(R.string.ii_confirm_go)) }
                            }
                        }
                    }
                    3 -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(close, Modifier.focusRequester(mainFocus), shape = PILL_TEXT_SHAPE) { Text(stringResource(R.string.ii_close)) }
                        // «Открыть» только по нажатию
                        if (launch != null) Button({ runCatching { act?.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; close() }, shape = PILL_SHAPE) { Text(stringResource(R.string.fm_open)) }
                    }
                    5 -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(close, Modifier.focusRequester(mainFocus), shape = PILL_TEXT_SHAPE) { Text(stringResource(R.string.ii_close)) } }
                    4 -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                        // оба действия только по нажатию
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                            TextButton({
                                runCatching { act?.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName))) }
                            }, shape = PILL_TEXT_SHAPE) { Text(stringResource(R.string.ic_open_perm), fontSize = 13.sp) }
                            TextButton({
                                runCatching { act?.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; close()
                            }, shape = PILL_TEXT_SHAPE) { Text(stringResource(R.string.ic_open_app), fontSize = 13.sp) }
                        }
                        TextButton(close, shape = PILL_TEXT_SHAPE) { Text(stringResource(R.string.btn_cancel)) }
                    }
                    else -> {}
                }
            }
        }
    }
}
