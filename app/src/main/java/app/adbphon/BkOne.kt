package app.adbphon

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/*
 * 10.0 (bk3, слово владелицы): ОДНА кнопка «Создать копию…» с понятным окном выбора, что включить (настройки ADB-phon, APK приложений,
 * данные приложений, шифрование, пользователь, папка), и ОДНО окно «Восстановить…», которое читает любой тип копии из папки
 * (копия настроек .adbpb, копия приложения .ab с APK и/или данными) и восстанавливает нужное с честными оговорками.
 * Логика выбора и определения типа в [BkPlan] и [BkDetect] (юнит-тесты BkPlanTest), запись и чтение в BkFlow, BkData, BkStore, FullBackup.
 */

/** Окна из «Софта»: создание с выбранными приложениями и восстановление (хост [BkOneHost] стоит в MainActivity). */
object BkOne {
    /** Открыто окно создания: выбранные в «Софте» пакеты (пусто: только настройки). */
    var create by mutableStateOf<Set<String>?>(null)
    var restore by mutableStateOf(false)
}

@Composable
fun BkOneHost(vm: MainViewModel) {
    BkOne.create?.let { pre -> BkCreateDialog(vm, pre) { BkOne.create = null } }
    if (BkOne.restore) BkRestoreDialog { BkOne.restore = false; vm.loadApps() }
}

/** Понятный текст ошибки чтения копии на языке программы (английские тексты исключений форматов людям не показываются). */
fun bkErrText(ctx: Context, e: Throwable): String = when (BkDetect.errKind(e)) {
    BkDetect.Err.NOT_OURS -> ctx.getString(R.string.bk3_err_not_ours)
    BkDetect.Err.NEWER -> ctx.getString(R.string.bk3_err_newer)
    BkDetect.Err.DAMAGED -> ctx.getString(R.string.bk3_err_damaged)
    BkDetect.Err.NEED_PASS -> ctx.getString(R.string.bk3_err_need_pass)
    BkDetect.Err.WRONG_PASS -> ctx.getString(R.string.bk3_err_wrong_pass)
    BkDetect.Err.OTHER -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}

@Composable
private fun Dim(s: String, color: Color = Color.Unspecified, max: Int = Int.MAX_VALUE) =
    Text(s, fontSize = bkDimSp(), maxLines = max, overflow = TextOverflow.Ellipsis, color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface.copy(alpha = .65f) else color)

/** Строка с крупной галочкой: название и пояснение под ним. */
@Composable
private fun ChoiceRow(on: Boolean, title: String, sub: String?, enabled: Boolean = true, onClick: () -> Unit) {
    CheckRow(on, enabled = enabled, onClick = onClick) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(title, fontSize = bkMainSp(), fontWeight = FontWeight.Medium)
            if (!sub.isNullOrEmpty()) Dim(sub)
        }
    }
}

/** Выбор пользователя устройства (если профилей несколько). */
@Composable
private fun UserPick(users: List<BkUser>, sel: Int, onSel: (Int) -> Unit) {
    if (users.size <= 1) return
    Text(stringResource(R.string.bkp_user), fontSize = bkMainSp(), fontWeight = FontWeight.Medium)
    EqChoices(users.map { u -> EqChoice(u.id.toString() + if (u.name.isNotBlank()) " (" + u.name + ")" else "", u.id == sel) { onSel(u.id) } }, perRow = 2, fontSp = if (Marq.tv) 16 else 13)
}

private fun wayText(ctx: Context, w: BkDataWay): String = ctx.getString(when (w) {
    BkDataWay.ROOT -> R.string.bk3_way_root
    BkDataWay.SYSTEM -> R.string.bk3_way_system
    BkDataWay.NO_PRIV -> R.string.bk3_way_no_priv
    BkDataWay.NOT_ALLOWED -> R.string.bk3_way_not_allowed
    BkDataWay.SDK12 -> R.string.bk3_way_sdk12
})

/** Сведения о приложении своего устройства для решения, чем снимать данные. На другом устройстве (ADB) неизвестно. */
private fun factsOf(ctx: Context, pkg: String): BkAppFacts? {
    if (FmIo.remote) return null
    val ai = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return null
    return BkAppFacts(pkg, ai.targetSdkVersion, ai.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0, ai.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
}

// =====================================================================================================================
// «Создать копию…»
// =====================================================================================================================

/**
 * Окно «Создать копию…»: галочки «Настройки ADB-phon», «APK приложений», «Данные приложений» (список приложений с поиском и честной
 * пометкой, чем снимутся данные), шифрование паролем, пользователь (если их несколько), папка (запомненная, «Изменить…»).
 * Настройки пишутся файлом .adbpb, каждое приложение файлом .ab (APK и/или данные) в одну папку; итог «Сохранено: путь, размер».
 */
@Composable
fun BkCreateDialog(vm: MainViewModel, pre: Set<String>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var what by remember { mutableStateOf(BkPlan.initial(pre)) }
    var place by remember { mutableStateOf(BkStore.last(ctx, BkStore.KEY_SETTINGS)) }
    var enc by remember { mutableStateOf(BkStore.encOn(ctx)) }
    var pass by remember { mutableStateOf(BkStore.pass(ctx)) }
    var query by remember { mutableStateOf("") }
    /** null: ещё не проверялось; root или ADB есть. */
    var priv by remember { mutableStateOf<Boolean?>(null) }
    var checking by remember { mutableStateOf(false) }
    var users by remember { mutableStateOf(listOf(BkUser(0, "", true))) }
    var user by remember { mutableStateOf(0) }
    var pickDir by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<BkResult?>(null) }
    var est by remember { mutableStateOf<AppBackup.Estimate?>(null) }
    LaunchedEffect(what.settings) { if (what.settings) est = withContext(Dispatchers.IO) { runCatching { AppBackup.estimate(ctx) }.getOrNull() } }
    // кэш сведений PackageManager (не состояние Compose: заполняется при показе строк)
    val facts = remember { HashMap<String, BkAppFacts?>() }

    LaunchedEffect(Unit) { if (vm.apps.isEmpty()) vm.loadApps() }
    // root и ADB проверяются только когда нужны данные: копия настроек и APK не вызывает запрос root
    LaunchedEffect(what.data) {
        if (!what.data || priv != null) return@LaunchedEffect
        checking = true
        runCatching { FmIo.app }.onFailure { FmIo.app = ctx.applicationContext }
        val fix = withContext(Dispatchers.IO) { runCatching { FmIo.ensurePriv() }.getOrDefault("pair") }
        priv = fix == null
        if (priv == true) {
            val u = withContext(Dispatchers.IO) { BkData.users() }
            val cur = withContext(Dispatchers.IO) { BkData.currentUser() }
            users = u; user = cur.takeIf { c -> u.any { it.id == c } } ?: u.first().id
        }
        checking = false
    }

    fun fact(p: String): BkAppFacts? = if (facts.containsKey(p)) facts[p] else factsOf(ctx, p).also { facts[p] = it }
    fun way(p: String): BkDataWay = BkPlan.dataWay(Adb.isRoot && priv == true, priv == true, android.os.Build.VERSION.SDK_INT, fact(p))

    fun start() {
        if (busy) return
        val prob = BkPlan.problem(what, enc, pass)
        if (prob != null) {
            err = ctx.getString(when (prob) { BkPlan.Problem.NOTHING -> R.string.bk3_err_nothing; BkPlan.Problem.NO_APPS -> R.string.bk3_err_no_apps; BkPlan.Problem.NEED_PASS -> R.string.bk3_enc_need_pass })
            return
        }
        err = null
        // шифрование и пароль запоминаются для следующих копий и автокопии (Keystore, один раз при создании, не на каждый символ)
        BkStore.setEncOn(ctx, enc)
        if (enc) BkStore.setPass(ctx, pass)
        val pw = if (enc) pass.toCharArray() else null
        val p = place
        BkStore.setLast(ctx, BkStore.KEY_SETTINGS, p); BkStore.setLast(ctx, BkStore.KEY_DATA, p)
        BkAuto.schedule(ctx)
        val w = what
        val u = if (priv == true) user else 0
        val ways = if (w.data) w.apps.associateWith { way(it) } else emptyMap()
        val skipped = ArrayList<String>()
        val jobs = BkPlan.jobs(w, ways, skipped)
        val byPkg = vm.apps.associateBy { it.pkg }
        fun label(pkg: String) = byPkg[pkg]?.label ?: pkg
        if (jobs.any { it.data }) BkData.rememberForAuto(ctx, jobs.filter { it.data }.map { it.pkg }, u)
        busy = true; result = null; status = ctx.getString(R.string.bkp_working)
        BkFlow.cleanShare(ctx)
        scope.launch {
            // папка недоступна (нет сети, нет доступа ко всем файлам): сразу понятная ошибка, без долгого сбора
            val preErr = withContext(Dispatchers.IO) {
                runCatching {
                    if (BkFlow.needsAccess(p, ctx)) throw IOException(ctx.getString(R.string.bkp_err_all_files, p.path))
                    if (!(p.local && BkStore.needAllFiles())) BkStore.open(ctx, p).close()
                }
            }.exceptionOrNull()
            if (preErr != null) { busy = false; status = ""; result = BkResult(p, emptyList(), BkFlow.errText(ctx, p, preErr), true); return@launch }
            val lines = ArrayList<String>(); val names = ArrayList<String>(); var failed = false
            if (w.settings) {
                val r = runCatching { BkFlow.makeSettings(ctx, p, pw) { status = it } }
                r.onSuccess { lines += it.text; names += it.names }
                    .onFailure { AppLog.e("ADBphon", "bk3 settings", it); failed = true; lines += BkFlow.errText(ctx, p, it) }
            }
            skipped.forEach { lines += ctx.getString(R.string.bk3_skipped, label(it)) }
            for ((i, j) in jobs.withIndex()) {
                val a = byPkg[j.pkg] ?: AppEntry(j.pkg, j.pkg, false, true, true, "", emptyList())
                val head = if (jobs.size > 1) "(${i + 1}/${jobs.size}) " else ""
                val r = withContext(Dispatchers.IO) {
                    runCatching { BkData.createOne(ctx, a, u, p, BkNames.data(a.label, u, BkNames.stamp(System.currentTimeMillis())), pw, j.apk, j.data) { s -> status = head + s } }
                }
                r.onSuccess { lines += it.text; names += it.name }.onFailure { e ->
                    AppLog.e("ADBphon", "bk3 app ${j.pkg}", e); failed = true
                    val why = if (BkDetect.errKind(e) != BkDetect.Err.OTHER) bkErrText(ctx, e) else BkFlow.errText(ctx, p, e)
                    lines += ctx.getString(R.string.bkp_failed, a.label, why)
                }
            }
            status = ""; busy = false
            result = BkResult(p, names, lines.joinToString("\n\n"), failed)
        }
    }

    val r = result
    if (r != null) {
        if (!pickDir) BkResultDialog(r, { retry = true; pickDir = true }, onClose)
    } else if (!pickDir) LAlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        wide = true,
        title = { Text(stringResource(R.string.bk3_create_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = if (Marq.tv) 520.dp else 560.dp).barScroll(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Dim(stringResource(R.string.bk3_what))
                ChoiceRow(what.settings, stringResource(R.string.bk3_inc_settings), stringResource(R.string.bk3_inc_settings_sub), !busy) { what = what.copy(settings = !what.settings); err = null }
                if (what.settings) est?.let { e -> Dim(stringResource(R.string.bk4_est, BkStore.size(ctx, e.total), BkStore.size(ctx, e.prefs), BkStore.size(ctx, e.icons), BkStore.size(ctx, e.net + e.files)) +
                    (if (e.skipped.isNotEmpty()) "\n" + stringResource(R.string.bk4_skipped, e.skipped.joinToString(", ")) else ""), if (e.total > 8L * 1024 * 1024) Color(0xFFB26A00) else Color.Unspecified) }
                ChoiceRow(what.apk, stringResource(R.string.bk3_inc_apk), stringResource(R.string.bk3_inc_apk_sub), !busy) { what = what.copy(apk = !what.apk); err = null }
                ChoiceRow(what.data, stringResource(R.string.bk3_inc_data), stringResource(R.string.bk3_inc_data_sub), !busy) { what = what.copy(data = !what.data); err = null }
                if (what.data) when {
                    checking || priv == null -> Dim(stringResource(R.string.bk3_priv_wait))
                    priv == false -> Dim(stringResource(R.string.bk3_priv_none), MaterialTheme.colorScheme.error)
                }
                if (what.apk || what.data) {
                    Text(stringResource(R.string.bk3_apps, what.apps.size), fontSize = bkMainSp(), fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.bk3_search), maxLines = 1) })
                    val q = query.trim().lowercase()
                    val list = remember(vm.apps, q) {
                        vm.apps.filter { a -> a.installed && (a.pkg in what.apps || (q.isEmpty() && !a.system) || (q.isNotEmpty() && (a.label.lowercase().contains(q) || a.pkg.lowercase().contains(q)))) }
                            .sortedWith(compareByDescending<AppEntry> { it.pkg in pre }.thenBy { it.label.lowercase() })
                    }
                    when {
                        vm.apps.isEmpty() -> Dim(stringResource(R.string.bk3_apps_loading))
                        list.isEmpty() -> Dim(stringResource(R.string.bk3_no_apps_found))
                        else -> BarLazyColumn(Modifier.fillMaxWidth().heightIn(max = if (Marq.tv) 260.dp else 240.dp)) {
                            items(list.size, key = { list[it].pkg }) { i ->
                                val a = list[i]
                                val on = a.pkg in what.apps
                                val sub = if (what.data && on && priv != null) a.pkg + "\n" + wayText(ctx, way(a.pkg)) else a.pkg
                                CheckRow(on, enabled = !busy, onClick = { what = what.copy(apps = if (on) what.apps - a.pkg else what.apps + a.pkg); err = null }) {
                                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                        Text(a.label, fontSize = bkMainSp(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Dim(sub, max = 3)
                                    }
                                }
                            }
                        }
                    }
                    if (priv == true && what.data) UserPick(users, user) { user = it }
                }
                ChoiceRow(enc, stringResource(R.string.bk3_enc), stringResource(R.string.bk3_enc_sub), !busy) { enc = !enc; err = null }
                if (enc) {
                    val pw = rememberPw()
                    OutlinedTextField(pass, { pass = it; err = null }, Modifier.fillMaxWidth(), singleLine = true, enabled = !busy, label = { Text(stringResource(R.string.bkp_pass), maxLines = 1) },
                        visualTransformation = pw.transform, trailingIcon = { PwEyeIcon(pw.shown) { pw.shown = !pw.shown } })
                } else if (what.settings) Dim(stringResource(R.string.bk3_plain_warn), Color(0xFFB26A00))
                BkPlaceRow(stringResource(R.string.bk3_folder), place) { if (!busy) pickDir = true }
                err?.let { Text(it, fontSize = bkMainSp(), color = MaterialTheme.colorScheme.error) }
                if (status.isNotEmpty()) Text(status, fontSize = bkMainSp(), color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { TextButton({ start() }, enabled = !busy) { Text(stringResource(R.string.bkp_create), fontSize = bkMainSp()) } },
        dismissButton = { TextButton(onClose, enabled = !busy) { Text(stringResource(R.string.btn_cancel), fontSize = bkMainSp()) } },
    )
    if (pickDir) BkPickDirDialog(BkStore.KEY_SETTINGS, { p ->
        pickDir = false; place = p
        if (retry) { retry = false; result = null; start() }
    }) { pickDir = false; retry = false }
}

// =====================================================================================================================
// «Восстановить…»
// =====================================================================================================================

/** Строка списка: место, файл и что внутри ([content] null: ещё читается). [counts]: число настроек, подключений, облаков. */
private class BkRow(val place: BkPlace, val e: FsEntry, val content: BkContent?, val counts: Triple<Int, Int, Int>?)

private sealed class RsSt {
    object List : RsSt()
    class SetPass(val data: ByteArray, val name: String, val wrong: Boolean) : RsSt()
    class SetConfirm(val json: JSONObject, val name: String) : RsSt()
    class AppPass(val file: File, val temp: Boolean, val place: BkPlace, val name: String, val wrong: Boolean) : RsSt()
    class AppCheck(val l: BkData.Loaded) : RsSt()
    class Info(val text: String, val error: Boolean, val restart: Boolean = false, val module: Boolean = false) : RsSt()
}

/** Что внутри строки списка, коротко. */
private fun rowText(ctx: Context, r: BkRow): String {
    val c = r.content ?: return r.e.name
    val l = ArrayList<String>()
    when {
        c.settings -> l += ctx.getString(R.string.bk3_k_settings) + (r.counts?.let { (s, n, cl) -> ": " + ctx.getString(R.string.bk2_in_counts, s, n, cl) } ?: "")
        c.foreign -> l += ctx.getString(R.string.bk3_k_foreign)
        c.unknown -> l += ctx.getString(R.string.bk3_k_unknown)
        c.meta != null -> {
            val m = c.meta
            val parts = ArrayList<String>()
            if (BkPart.APK in c.parts) parts += ctx.getString(R.string.fbk_p_apk)
            if (BkPart.DATA in c.parts) parts += ctx.getString(R.string.bk3_k_data)
            l += ctx.getString(R.string.bk3_k_app, m.label.ifEmpty { m.pkg }, parts.joinToString(", "))
            if (m.user != 0) l += ctx.getString(R.string.bk3_user_n, m.user)
        }
        else -> {
            // зашифрованная копия приложения без сохранённого пароля: приложение из имени файла
            val n = BkInside.fromName(r.e.name)
            l += ctx.getString(R.string.bk3_k_app_enc) + if (n.label.isNotEmpty()) ": " + n.label else ""
            if (n.user > 0) l += ctx.getString(R.string.bk3_user_n, n.user)
        }
    }
    if (c.encrypted == true) l += ctx.getString(R.string.bk2_in_enc)
    if (r.e.name.startsWith(BkNames.AUTO_PREFIX)) l += ctx.getString(R.string.bk2_in_auto)
    return l.joinToString(", ")
}

/** Прочитать, что внутри копии: начало файла (копия настроек целиком, она маленькая), сохранённый пароль открывает зашифрованные копии приложений. */
private suspend fun inspect(ctx: Context, o: BkStore.Open, e: FsEntry, pass: CharArray?): Pair<BkContent, Triple<Int, Int, Int>?> = withContext(Dispatchers.IO) {
    runCatching {
        val settingsName = e.name.lowercase().endsWith(BkNames.SETTINGS_EXT)
        val n = if (settingsName && e.size <= 8L * 1024 * 1024) e.size.toInt() + 1 else 256 * 1024
        val b = BkStore.head(o, e.name, n)
        val c = BkDetect.detect(e.name, b, pass)
        val counts = if (c.settings && c.encrypted == false) runCatching { AppBackup.summarize(AppBackup.parse(b, null)).let { s -> Triple(s.settings, s.links, s.clouds) } }.getOrNull() else null
        c to counts
    }.getOrElse { BkContent(emptySet(), null, unknown = true) to null }
}

/** Есть ли старые копии-папки (до 10.0) на своём устройстве. */
private fun hasOldCopies(): Boolean = !FmIo.remote && runCatching {
    File(FullBackup.BASE).listFiles()?.any { d -> d.isDirectory && !d.name.startsWith(".") && (d.listFiles()?.any { it.isDirectory } == true) } == true
}.getOrDefault(false)

/**
 * Окно «Восстановить…»: список копий из запомненной папки (и прежней папки копий с данными, если она другая), любого типа: копия настроек,
 * копия приложения (APK, данные или вместе), чужая системная копия честно помечается. «Обновить», «Изменить папку», «Выбрать файл…»
 * (любой файл: тип определяется по содержимому), «Старые копии с данными…» (если есть). Дальше по типу: пароль, подтверждение,
 * проверка совместимости с выбором «Приложение (APK)» и «Данные».
 */
@Composable
fun BkRestoreDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf<RsSt>(RsSt.List) }
    var place by remember { mutableStateOf(BkStore.last(ctx, BkStore.KEY_SETTINGS)) }
    var tick by remember { mutableStateOf(0) }
    var rows by remember { mutableStateOf<List<BkRow>?>(null) }
    var listErr by remember { mutableStateOf<String?>(null) }
    var sel by remember { mutableStateOf<BkRow?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var pickDir by remember { mutableStateOf(false) }
    var pickFile by remember { mutableStateOf(false) }
    var old by remember { mutableStateOf(false) }
    var showOld by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf<BkData.Loaded?>(null) }
    DisposableEffect(Unit) { onDispose { loaded?.dispose() } }

    LaunchedEffect(Unit) { old = withContext(Dispatchers.IO) { hasOldCopies() } }
    LaunchedEffect(place, tick) {
        rows = null; listErr = null; sel = null
        val saved = BkStore.pass(ctx).takeIf { it.isNotEmpty() }?.toCharArray()
        // прежняя отдельная папка копий с данными (до единого окна) тоже просматривается, если она другая
        val dplace = BkStore.last(ctx, BkStore.KEY_DATA)
        val places = if (dplace != place) listOf(place, dplace) else listOf(place)
        val found = ArrayList<Pair<BkPlace, FsEntry>>()
        for ((i, p) in places.withIndex()) {
            val o = runCatching { BkStore.open(ctx, p) }.getOrElse { if (i == 0) listErr = BkFlow.errText(ctx, p, it); null } ?: continue
            try {
                val l = runCatching { withContext(Dispatchers.IO) { BkStore.list(o) } }
                // папки ещё нет (создаётся при первой копии): это «копий нет», а не ошибка
                l.onSuccess { es -> es.filter { !it.isDir && it.size > 0 && BkDetect.isCopyName(it.name) }.forEach { found += p to it } }
                    .onFailure { e -> if (i == 0 && !(p.local && !File(p.path).exists())) listErr = BkFlow.errText(ctx, p, e) }
            } finally { o.close() }
        }
        val sorted = found.sortedWith(compareByDescending<Pair<BkPlace, FsEntry>> { BkList.sortKey(it.second) }.thenBy { it.second.name.lowercase() })
        var cur = sorted.map { BkRow(it.first, it.second, null, null) }
        rows = cur
        sel = cur.firstOrNull()
        // что внутри: первые 30 строк, по одной (список виден сразу, подписи появляются по мере чтения)
        for (i in cur.indices.take(30)) {
            val r0 = cur[i]
            val o = runCatching { BkStore.open(ctx, r0.place) }.getOrNull() ?: continue
            val (c, n) = try { inspect(ctx, o, r0.e, saved) } finally { o.close() }
            val r1 = BkRow(r0.place, r0.e, c, n)
            cur = cur.toMutableList().also { it[i] = r1 }
            rows = cur
            if (sel?.e?.name == r0.e.name && sel?.place == r0.place) sel = r1
        }
    }

    /** Копия настроек: расшифровка и проверка без изменений; дальше подтверждение, пароль или понятная ошибка. */
    suspend fun prepareSettings(data: ByteArray, name: String, pw: CharArray?, savedTried: Boolean) {
        busy = true
        val r = try { withContext(Dispatchers.Default) { runCatching { AppBackup.parse(data, pw) } } } finally { busy = false }
        r.onSuccess { st = RsSt.SetConfirm(it, name) }.onFailure { e ->
            st = when (e) {
                is AppBackup.NeedPass -> RsSt.SetPass(data, name, false)
                is AppBackup.WrongPass -> RsSt.SetPass(data, name, !savedTried)
                else -> RsSt.Info(ctx.getString(R.string.bk2_bad_file, bkErrText(ctx, e)), true)
            }
        }
    }

    /** Копия приложения: сведения, сначала с сохранённым паролем; не подошёл: окно пароля без пометки «неверный». */
    fun openApp(file: File, temp: Boolean, p: BkPlace, name: String, pw: CharArray?) {
        scope.launch {
            busy = true
            val savedTried = pw == null
            val r = runCatching { BkData.load(file, temp, p, name, pw ?: BkStore.pass(ctx).takeIf { it.isNotEmpty() }?.toCharArray()) }
            busy = false; status = ""
            r.onSuccess { loaded?.takeIf { it.file != file }?.dispose(); loaded = it; st = RsSt.AppCheck(it) }.onFailure { e ->
                st = when {
                    e is AbFormat.NeedPass -> RsSt.AppPass(file, temp, p, name, false)
                    e is AbFormat.WrongPass -> RsSt.AppPass(file, temp, p, name, !savedTried)
                    e is AbFormat.BadFormat && e.message == "not an ADB-phon data copy" -> { if (temp) file.delete(); RsSt.Info(ctx.getString(R.string.bk3_foreign_text), true) }
                    else -> { if (temp) file.delete(); RsSt.Info(ctx.getString(R.string.bkp_bad_file, bkErrText(ctx, e)), true) }
                }
            }
        }
    }

    /** Выбранный файл: тип по содержимому (имя не важно), дальше нужная ветка. */
    fun choose(p: BkPlace, name: String) {
        if (busy) return
        busy = true; status = ctx.getString(R.string.bkp_step_download, BkStore.display(ctx, p, name))
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val o = BkStore.open(ctx, p)
                    try {
                        val local = BkStore.localFile(o, name)
                        if (local != null) local to false
                        else File(BkStore.tmpDir(ctx), "in_" + System.nanoTime()).also { f -> BkStore.fetch(o, name, f) } to true
                    } finally { o.close() }
                }
            }
            busy = false; status = ""
            val (f, temp) = r.getOrElse { st = RsSt.Info(ctx.getString(R.string.bkp_bad_file, BkFlow.errText(ctx, p, it)), true); return@launch }
            val head = withContext(Dispatchers.IO) { runCatching { f.inputStream().use { i -> val b = ByteArray(16); val n = i.read(b); if (n > 0) b.copyOf(n) else ByteArray(0) } }.getOrDefault(ByteArray(0)) }
            val kind = BkDetect.detect(name, head)
            when {
                kind.settings -> {
                    val data = withContext(Dispatchers.IO) { runCatching { f.readBytes() } }
                    if (temp) f.delete()
                    data.onSuccess { prepareSettings(it, name, BkStore.pass(ctx).takeIf { s -> s.isNotEmpty() }?.toCharArray(), true) }
                        .onFailure { st = RsSt.Info(ctx.getString(R.string.bkp_bad_file, bkErrText(ctx, it)), true) }
                }
                kind.unknown && !String(head, Charsets.ISO_8859_1).startsWith("ANDROID BACKUP") -> { if (temp) f.delete(); st = RsSt.Info(ctx.getString(R.string.bk3_unknown_text), true) }
                else -> openApp(f, temp, p, name, null)
            }
        }
    }

    when (val s = st) {
        RsSt.List -> LAlertDialog(
            onDismissRequest = { if (!busy) onClose() },
            wide = true,
            title = { Text(stringResource(R.string.bk3_restore_title)) },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.bk2_in_folder, BkStore.display(ctx, place)), fontSize = bkDimSp(), color = MaterialTheme.colorScheme.primary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val btns = arrayListOf(
                        EqBtn(stringResource(R.string.bk2_refresh), !busy && rows != null) { tick++ },
                        EqBtn(stringResource(R.string.bk2_change_dir), !busy) { pickDir = true },
                        EqBtn(stringResource(R.string.bkp_pick_file), !busy) { pickFile = true },
                    )
                    if (old) btns += EqBtn(stringResource(R.string.bk3_old_btn), !busy) { showOld = true }
                    EqButtons(btns, perRow = if (btns.size > 3) 2 else 3)
                    if (status.isNotEmpty()) Text(status, fontSize = bkDimSp(), color = MaterialTheme.colorScheme.primary)
                    listErr?.let { Text(it, fontSize = bkDimSp(), color = MaterialTheme.colorScheme.error) }
                    val l = rows
                    when {
                        l == null -> Dim(stringResource(R.string.bkp_working))
                        l.isEmpty() && listErr == null -> Text(stringResource(R.string.bk2_empty), fontSize = bkMainSp(), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                        else -> BarLazyColumn(Modifier.fillMaxWidth().heightIn(max = if (Marq.tv) 300.dp else 320.dp)) {
                            items(l.size) { i ->
                                val r = l[i]
                                val on = sel?.e?.name == r.e.name && sel?.place == r.place
                                CheckRow(on, enabled = !busy, onClick = { sel = r }) {
                                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                        Text(BkList.timeText(r.e).ifEmpty { r.e.name } + "  ·  " + BkStore.size(ctx, r.e.size), fontSize = bkMainSp(), fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                                        Dim(rowText(ctx, r), max = 2)
                                        if (r.place != place) Dim(BkStore.display(ctx, r.place), max = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ sel?.let { choose(it.place, it.e.name) } }, enabled = !busy && sel != null) { Text(stringResource(R.string.bkp_restore_btn), fontSize = bkMainSp()) } },
            dismissButton = { TextButton({ if (!busy) onClose() }) { Text(stringResource(R.string.btn_close), fontSize = bkMainSp()) } },
        )
        is RsSt.SetPass, is RsSt.AppPass -> {
            var p by remember { mutableStateOf("") }
            val name = if (s is RsSt.SetPass) s.name else (s as RsSt.AppPass).name
            val wrong = if (s is RsSt.SetPass) s.wrong else (s as RsSt.AppPass).wrong
            fun back() { if (s is RsSt.AppPass && s.temp) s.file.delete(); st = RsSt.List }
            LAlertDialog(
                onDismissRequest = { if (!busy) back() },
                title = { Text(stringResource(R.string.bkp_enter_pass)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Dim(name)
                        val pw = rememberPw()
                        OutlinedTextField(p, { p = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = !busy, label = { Text(stringResource(R.string.bkp_pass), maxLines = 1) }, visualTransformation = pw.transform, trailingIcon = { PwEyeIcon(pw.shown) { pw.shown = !pw.shown } })
                        // красная ошибка только когда проверка завершилась неверным паролем; пока идёт проверка (3-5 с на слабой приставке) её нет
                        if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Dim(stringResource(R.string.bkp_pass_checking))
                        } else if (wrong) Text(stringResource(R.string.bkp_wrong_pass), fontSize = bkDimSp(), color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = {
                    TextButton({
                        if (busy || p.isEmpty()) return@TextButton
                        if (s is RsSt.SetPass) { val d = s.data; scope.launch { prepareSettings(d, s.name, p.toCharArray(), false) } }
                        else if (s is RsSt.AppPass) openApp(s.file, s.temp, s.place, s.name, p.toCharArray())
                    }, enabled = !busy && p.isNotEmpty()) { Text(stringResource(R.string.btn_yes)) }
                },
                dismissButton = { TextButton({ if (!busy) back() }, enabled = !busy) { Text(stringResource(R.string.btn_cancel)) } },
            )
        }
        is RsSt.SetConfirm -> LAlertDialog(
            onDismissRequest = { if (!busy) st = RsSt.List },
            title = { Text(stringResource(R.string.bk3_k_settings)) },
            text = {
                Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dim(s.name)
                    val sm = AppBackup.summarize(s.json)
                    Text(stringResource(R.string.bk2_confirm, sm.settings, sm.links, sm.clouds, sm.devices), fontSize = bkMainSp())
                    if (busy) Dim(stringResource(R.string.bkp_working))
                }
            },
            confirmButton = {
                TextButton({
                    if (busy) return@TextButton
                    val j = s.json; busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { AppBackup.restore(ctx, j) } }
                        busy = false
                        st = r.fold(
                            { sm ->
                                val needMod = sm.clouds > 0 && CloudModule.state(ctx) != CloudModule.State.OK
                                RsSt.Info(ctx.getString(R.string.bk2_restored, sm.settings, sm.links, sm.clouds, sm.devices) + "\n" + ctx.getString(R.string.bk2_restart_hint) +
                                    (if (needMod) "\n" + ctx.getString(R.string.bk4_mod_hint) else ""), false, restart = true, module = needMod)
                            },
                            { e -> AppLog.e("ADBphon", "bk3 restore settings", e); RsSt.Info(ctx.getString(R.string.bk2_bad_file, bkErrText(ctx, e)), true) },
                        )
                    }
                }, enabled = !busy) { Text(stringResource(R.string.bkp_restore_btn)) }
            },
            dismissButton = { TextButton({ if (!busy) st = RsSt.List }, enabled = !busy) { Text(stringResource(R.string.btn_cancel)) } },
        )
        is RsSt.AppCheck -> BkAppCheckDialog(s.l, { t -> st = if (t == null) RsSt.List else RsSt.Info(t, false) })
        is RsSt.Info -> LAlertDialog(
            // 24.11.2026: после успешного восстановления настроек закрытие окна тоже перезапускает программу
            onDismissRequest = { if (s.restart) restartApp(ctx) else onClose() },
            title = { Text(stringResource(R.string.bk3_restore_title)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).barScroll()) {
                    Text(s.text, fontSize = bkMainSp(), color = if (s.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (s.module) {
                        val ph = CloudModule.phase
                        if (ph in 1..3) Dim(stringResource(R.string.bk4_mod_progress, CloudModule.progress))
                        else if (ph == 4 && CloudModule.error.isNotEmpty()) Text(CloudModule.error, fontSize = bkMainSp(), color = MaterialTheme.colorScheme.error)
                        TextButton({ scope.launch { CloudModule.fetchAndInstall(ctx.applicationContext) } }, enabled = ph !in 1..3) { Text(stringResource(R.string.bk4_mod_get), fontSize = bkMainSp()) }
                    }
                }
            },
            confirmButton = {
                if (s.restart) TextButton({ restartApp(ctx) }) { Text(stringResource(R.string.bk2_restart)) }
                else TextButton(onClose) { Text(stringResource(R.string.btn_close)) }
            },
            dismissButton = {
                if (s.restart) TextButton({ restartApp(ctx) }) { Text(stringResource(R.string.btn_close)) }
                else TextButton({ st = RsSt.List; tick++ }) { Text(stringResource(R.string.bkp_back)) }
            },
        )
    }
    if (pickDir) BkPickDirDialog(BkStore.KEY_SETTINGS, { p -> pickDir = false; place = p }) { pickDir = false }
    // любой файл: тип определяется по содержимому
    if (pickFile) BkPickDialog(stringResource(R.string.bkp_pick_file), bkPickStart(place), "", { p, n -> pickFile = false; if (n != null) choose(p, n) }) { pickFile = false }
    if (showOld) FullBackupsDialog { showOld = false }
}

/**
 * Копия приложения: сведения, пользователь, проверка совместимости с честными оговорками и выбор, что восстановить:
 * «Приложение (APK)» (с root и ADB через pm, без них системным установщиком) и «Данные приложения» (root или ADB).
 * [done]: null назад к списку, иначе итог.
 */
@Composable
private fun BkAppCheckDialog(l: BkData.Loaded, done: (String?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val m = l.meta
    val hasApk = "apk" in m.parts
    val hasData = m.parts.any { it != "apk" }
    var users by remember { mutableStateOf(listOf(BkUser(m.user, "", true))) }
    var user by remember { mutableStateOf(m.user) }
    var target by remember { mutableStateOf<BkTarget?>(null) }
    var wantApk by remember { mutableStateOf(false) }
    var wantData by remember { mutableStateOf(false) }
    var inited by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(user) {
        target = null
        val t = withContext(Dispatchers.IO) { BkData.target(ctx, m, user) }
        if (t.root || t.adb) {
            val u = withContext(Dispatchers.IO) { BkData.users() }
            users = u
            if (u.none { it.id == user }) { user = withContext(Dispatchers.IO) { BkData.currentUser() }; return@LaunchedEffect }
        } else if (user != 0 && m.user != 0) { users = listOf(BkUser(0, "", true)); user = 0; return@LaunchedEffect }
        target = t
        if (!inited) {
            val codes = BkCompat.check(m, t).map { it.code }.toSet()
            val (a, d) = BkPlan.restoreDefaults(hasApk, hasData, t.installed, BkCompat.Code.VERSION_LOWER in codes)
            wantApk = a; wantData = d; inited = true
        }
    }
    val t = target
    val priv = t != null && (t.root || t.adb)
    val issues = t?.let { BkCompat.check(m, it) }.orEmpty()
    val codes = issues.map { it.code }.toSet()
    val block = if (t == null) null else BkPlan.restoreBlock(codes, wantApk, wantData, priv, user == 0)
    val df = remember { java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()) }

    fun run() {
        if (busy || t == null) return
        busy = true
        val a = wantApk; val d = wantData; val u = user; val installed = t.installed
        scope.launch {
            val out = ArrayList<String>()
            withContext(Dispatchers.IO) {
                runCatching {
                    if (a) out += if (priv) BkData.installFromCopy(ctx, l, u) { status = it } else BkData.installNoPriv(ctx, l) { status = it }
                    if (d) {
                        val ok = installed || BkData.target(ctx, m, u).installed
                        out += if (ok) BkData.restore(ctx, l, u) { status = it } else ctx.getString(R.string.bkp_c_not_installed)
                    }
                }.onFailure { AppLog.e("ADBphon", "bk3 restore app", it); out += ctx.getString(R.string.fbk_failed) + ": " + bkErrText(ctx, it) }
            }
            busy = false; status = ""
            done(out.joinToString("\n\n"))
        }
    }

    LAlertDialog(
        onDismissRequest = { if (!busy) done(null) },
        wide = true,
        title = { Text(m.label.ifEmpty { m.pkg }) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).barScroll(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Dim(l.name)
                Text(stringResource(R.string.bkp_meta_line, m.pkg, m.versionName.ifEmpty { "?" }, if (m.time > 0) df.format(java.util.Date(m.time)) else "?"), fontSize = bkDimSp())
                Text(stringResource(R.string.bkp_meta_dev, (m.manufacturer + " " + m.model).trim().ifEmpty { "?" }, m.release.ifEmpty { m.sdk.toString() }, m.user.toString(),
                    when (m.mode) { "root" -> "root"; "apk" -> "APK"; else -> "ADB" }), fontSize = bkDimSp())
                if (m.signer.isNotEmpty()) Dim(stringResource(R.string.bkp_meta_signer, m.signer.take(16) + "…"))
                Dim(if (l.encrypted) stringResource(R.string.bkp_enc_yes) else stringResource(R.string.bkp_enc_no))
                Text(stringResource(R.string.bk3_r_what), fontSize = bkMainSp(), fontWeight = FontWeight.Medium)
                if (hasApk) ChoiceRow(wantApk, stringResource(R.string.bk3_r_apk), stringResource(R.string.bk3_r_apk_sub), !busy && t != null) { wantApk = !wantApk }
                if (hasData) ChoiceRow(wantData, stringResource(R.string.bk3_r_data), stringResource(R.string.bk3_r_data_sub), !busy && t != null) { wantData = !wantData }
                if (wantApk && t != null && !priv) Dim(stringResource(R.string.bk3_r_apk_nopriv))
                if (priv) UserPick(users, user) { if (!busy) user = it }
                if (t == null) Dim(stringResource(R.string.bkp_checking))
                else {
                    // при установке APK из копии пункты «не установлено» и «старая версия» снимаются самой установкой
                    val shown = issues.filter { i ->
                        !(wantApk && (i.code == BkCompat.Code.NOT_INSTALLED || i.code == BkCompat.Code.VERSION_LOWER)) &&
                            !(i.code == BkCompat.Code.NO_PRIV && !wantData) &&
                            !(i.code == BkCompat.Code.ONLY_APK)
                    }
                    if (shown.isEmpty() && block == null) Text(stringResource(R.string.bkp_c_all_ok), fontSize = bkMainSp(), color = MaterialTheme.colorScheme.primary)
                    shown.forEach { i ->
                        val c = when (i.level) { BkCompat.Level.BLOCK -> MaterialTheme.colorScheme.error; BkCompat.Level.WARN -> Color(0xFFB26A00); else -> MaterialTheme.colorScheme.onSurface.copy(alpha = .7f) }
                        Text("• " + BkData.issueText(ctx, i, m, t), fontSize = bkDimSp(), color = c)
                    }
                    block?.let { b ->
                        val txt = when (b) {
                            BkPlan.Block.NOTHING -> R.string.bk3_b_nothing
                            BkPlan.Block.NO_PRIV -> R.string.bkp_c_no_priv
                            BkPlan.Block.USER_MISSING -> R.string.bkp_c_user_missing
                            BkPlan.Block.NOT_INSTALLED -> R.string.bk3_b_not_installed
                            BkPlan.Block.SIGNER_MISMATCH -> R.string.bkp_c_signer_mismatch
                            BkPlan.Block.OTHER_USER_NEEDS_PRIV -> R.string.bk3_b_other_user
                        }
                        Text(stringResource(txt), fontSize = bkMainSp(), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (status.isNotEmpty()) Text(status, fontSize = bkMainSp(), color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { TextButton({ run() }, enabled = !busy && t != null && block == null) { Text(stringResource(R.string.bkp_restore_btn), fontSize = bkMainSp()) } },
        dismissButton = { TextButton({ if (!busy) done(null) }, enabled = !busy) { Text(stringResource(R.string.bkp_back), fontSize = bkMainSp()) } },
    )
}
