package app.adbphon

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Сведения о файле APK и об уже установленной версии этого приложения: то, что показывается перед установкой
 * (версии, подпись, разрешения, совместимость) и по чему выбирается «новая установка / обновление / понижение».
 */
class ApkMeta(
    val label: String, val pkg: String, val verName: String, val verCode: Long, val minSdk: Int,
    val icon: ImageBitmap?, val sigs: Set<String>, val perms: Set<String>,
    /** Установленная версия: null, если приложения на устройстве нет. */
    val curName: String?, val curCode: Long, val curSigs: Set<String>, val curPerms: Set<String>,
    val targetSdk: Int = 0, val curMinSdk: Int = 0, val curTargetSdk: Int = 0, val size: Long = 0L, val curSize: Long = 0L,
    /** Флаги установленного приложения (ApplicationInfo.flags): системное и отлаживаемое. */
    val curFlags: Int = 0,
) {
    enum class Kind { NEW, UPDATE, SAME, DOWNGRADE }

    val kind: Kind get() = when {
        curName == null -> Kind.NEW
        verCode > curCode -> Kind.UPDATE
        verCode == curCode -> Kind.SAME
        else -> Kind.DOWNGRADE
    }
    /** Подписи есть с обеих сторон и не совпадают: поверх установленного приложение не встанет. */
    val sigDiffers: Boolean get() = curName != null && sigs.isNotEmpty() && curSigs.isNotEmpty() && sigs != curSigs
    val sigSame: Boolean get() = curName != null && sigs.isNotEmpty() && curSigs.isNotEmpty() && sigs == curSigs
    val addedPerms: List<String> get() = if (curName == null) emptyList() else (perms - curPerms).sorted()
    val removedPerms: List<String> get() = if (curName == null) emptyList() else (curPerms - perms).sorted()
    val needsNewerAndroid: Boolean get() = minSdk > Build.VERSION.SDK_INT
    /** Установленное приложение системное, и обновлений поверх него нет: понижению не поддаётся. */
    val curPlainSystem: Boolean get() = curName != null && curFlags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 && curFlags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
    val curDebuggable: Boolean get() = curFlags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    /** Android 14 и новее без root не разрешает понижение с сохранением данных (отлаживаемые приложения разрешает): остаётся «удалить с данными и поставить старую». */
    val downgradeNeedsWipe: Boolean get() = kind == Kind.DOWNGRADE && !curPlainSystem && Build.VERSION.SDK_INT >= 34 && !curDebuggable
    /** Android 14+ с root: понижение с сохранением данных идёт через копию данных (tar), удаление, установку старой версии и возврат данных. */
    val downgradeKeepsData: Boolean get() = downgradeNeedsWipe && Adb.isRoot
}

object ApkInfo {
    @Suppress("DEPRECATION")
    private fun flags(): Int = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNATURES or (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)

    private fun sha(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun sigsOf(pi: PackageInfo): Set<String> {
        val a = runCatching { if (Build.VERSION.SDK_INT >= 28) pi.signingInfo?.apkContentsSigners?.map { sha(it.toByteArray()) }?.toSet().orEmpty() else emptySet() }.getOrDefault(emptySet())
        if (a.isNotEmpty()) return a
        return runCatching { @Suppress("DEPRECATION") pi.signatures?.map { sha(it.toByteArray()) }?.toSet().orEmpty() }.getOrDefault(emptySet())
    }

    private fun codeOf(pi: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()

    fun read(ctx: Context, path: String): ApkMeta? = runCatching {
        val pm = ctx.packageManager
        val pi = pm.getPackageArchiveInfo(path, flags()) ?: return@runCatching null
        val ai = pi.applicationInfo ?: return@runCatching null
        ai.sourceDir = path; ai.publicSourceDir = path
        val label = runCatching { ai.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: pi.packageName
        val icon = runCatching {
            val d = ai.loadIcon(pm)
            Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { b -> d.setBounds(0, 0, 96, 96); d.draw(Canvas(b)) }.asImageBitmap()
        }.getOrNull()
        val cur = runCatching { pm.getPackageInfo(pi.packageName, flags()) }.getOrNull()
        ApkMeta(
            label, pi.packageName, pi.versionName ?: "?", codeOf(pi), ai.minSdkVersion, icon, sigsOf(pi),
            pi.requestedPermissions?.toSet().orEmpty(),
            cur?.let { it.versionName ?: "?" }, cur?.let { codeOf(it) } ?: 0L, cur?.let { sigsOf(it) }.orEmpty(), cur?.requestedPermissions?.toSet().orEmpty(),
            ai.targetSdkVersion, cur?.applicationInfo?.minSdkVersion ?: 0, cur?.applicationInfo?.targetSdkVersion ?: 0,
            runCatching { File(path).length() }.getOrDefault(0L), runCatching { File(cur?.applicationInfo?.sourceDir ?: "").length() }.getOrDefault(0L),
            cur?.applicationInfo?.flags ?: 0,
        )
    }.getOrNull()

    /** Сведения о пакете из списка установки; null, если файл ещё не на устройстве (флешка, сетевая папка) или не читается. */
    fun readFor(ctx: Context, f: ApkFile): ApkMeta? {
        val p = f.file?.path ?: f.uri.takeIf { it.scheme == "file" }?.path
        if (p != null) return read(ctx, p)
        if (f.uri == Uri.EMPTY || f.fetch != null) return null
        val pfd = runCatching { ctx.contentResolver.openFileDescriptor(f.uri, "r") }.getOrNull() ?: return null
        return pfd.use { read(ctx, "/proc/self/fd/" + it.fd) }
    }
}

/** Состояние установки пакетов для полосы прогресса: какой файл, сколько байт, итог. */
class InstRun {
    var running by mutableStateOf(false)
    var finished by mutableStateOf(false)
    var index by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var name by mutableStateOf("")
    /** 0 подготовка, 1 передача файла, 2 установка, 3 копирование OBB. */
    var phase by mutableIntStateOf(0)
    var done by mutableLongStateOf(0L)
    var all by mutableLongStateOf(0L)
    var ok by mutableIntStateOf(0)
    var bad by mutableIntStateOf(0)
    val fails = mutableStateListOf<String>()
    /** 7.6: установка уехала «В фоне» (ход ведёт служба, окно можно открыть из уведомления). */
    var background by mutableStateOf(false)
    /** 7.6: «Остановить»: оставшиеся файлы не ставятся (текущий файл дожимается). */
    @Volatile var stop = false
}

private fun mbText(b: Long): String = String.format(Locale.US, "%.1f", b / 1048576.0)

/** Размер файла для таблицы: КБ, если меньше мегабайта, иначе МБ. */
@Composable
private fun sizeLabel(b: Long): String =
    if (b < 1048576L) String.format(Locale.US, "%d", (b + 1023) / 1024) + " " + stringResource(R.string.unit_kb)
    else mbText(b) + " " + stringResource(R.string.unit_mb)

/** Цвета статусов: зелёный (хорошо), красный (плохо), янтарный (внимание) с поправкой на тёмную тему. */
private fun okCol(dark: Boolean) = if (dark) Color(0xFF7BD88F) else Color(0xFF1B7F3B)
private fun badCol(dark: Boolean) = if (dark) Color(0xFFFF8A80) else Color(0xFFC62828)
private fun warnCol(dark: Boolean) = if (dark) Color(0xFFFFCC80) else Color(0xFF9A5B00)

private fun sigShort(set: Set<String>): String = if (set.isEmpty()) "—" else set.sorted().joinToString(" ") { it.take(6) + "…" }

/** Строка таблицы «параметр | установлено | новое»: изменившееся значение подсвечено (старое красным, новое зелёным или янтарным, если стало хуже). */
@Composable
private fun CmpRow(caption: String, cur: String, new: String, changed: Boolean, newCol: Color) {
    val t = LocalTh.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        Text(caption, color = t.hint, fontSize = 11.sp, modifier = Modifier.weight(1.2f))
        Text(cur, color = if (changed) badCol(t.dark) else t.sub, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(new, color = if (changed) newCol else t.sub, fontSize = 11.sp, fontWeight = if (changed) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
    }
}

/**
 * Блок сведений о пакете в окне перед установкой (по образцу App Manager): значок, название, пакет, метка вида установки
 * (новая / обновление / та же / понижение), таблица «Установлено → Новое» с подсветкой различий (версия, код, подпись, размер,
 * Android), явное предупреждение о другой подписи, при [full] ещё списки разрешений (новые и убранные).
 */
@Composable
fun ApkInfoBlock(f: ApkFile, m: ApkMeta?, full: Boolean, modifier: Modifier = Modifier) {
    val t = LocalTh.current
    val sizeText = sizeLabel(f.size)
    if (m == null) {
        Column(modifier) {
            androidx.compose.runtime.CompositionLocalProvider(LocalRowActive provides true) { Text(f.name, color = t.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(sizeText, color = t.hint, fontSize = 11.sp)
        }
        return
    }
    val kind = m.kind
    val ok = okCol(t.dark); val bad = badCol(t.dark); val warn = warnCol(t.dark)
    val kindCol = when (kind) { ApkMeta.Kind.NEW, ApkMeta.Kind.UPDATE -> ok; ApkMeta.Kind.SAME -> t.sub; ApkMeta.Kind.DOWNGRADE -> warn }
    val kindText = stringResource(when (kind) { ApkMeta.Kind.NEW -> R.string.ii_new; ApkMeta.Kind.UPDATE -> R.string.ii_update; ApkMeta.Kind.SAME -> R.string.ii_same; ApkMeta.Kind.DOWNGRADE -> R.string.ii_down })
    Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (m.icon != null) Image(m.icon, null, Modifier.size(40.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            androidx.compose.runtime.CompositionLocalProvider(LocalRowActive provides true) { Text(m.label, color = t.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val parts = f.parts?.size
            Text(m.pkg + (if (parts != null) " · " + stringResource(R.string.ii_parts, parts) else "") + (if (!full) " · " + f.name else ""), color = t.hint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(kindText, color = kindCol, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            val newBytes = if (f.size > 0) f.size else m.size
            val newSizeText = sizeLabel(newBytes)
            if (m.curName == null) {
                Text(stringResource(R.string.ii_ver_new, m.verName, m.verCode) + " · " + newSizeText, color = t.text, fontSize = 12.sp)
            } else if (!full) {
                Text(
                    if (kind == ApkMeta.Kind.SAME) stringResource(R.string.ii_ver_new, m.verName, m.verCode) else stringResource(R.string.ii_ver_change, m.curName, m.curCode, m.verName, m.verCode),
                    color = t.text, fontSize = 12.sp,
                )
            } else {
                val newCol = if (kind == ApkMeta.Kind.DOWNGRADE) warn else ok
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("", modifier = Modifier.weight(1.2f))
                    Text(stringResource(R.string.ii_col_cur), color = t.hint, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.ii_col_new), color = t.hint, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                CmpRow(stringResource(R.string.ii_row_ver), m.curName, m.verName, m.curName != m.verName, newCol)
                CmpRow(stringResource(R.string.ii_row_code), m.curCode.toString(), m.verCode.toString(), m.curCode != m.verCode, newCol)
                val sigCh = m.sigDiffers
                CmpRow(stringResource(R.string.ii_row_sig), sigShort(m.curSigs), if (m.sigSame) stringResource(R.string.ii_sig_same_short) else sigShort(m.sigs), sigCh, if (sigCh) bad else ok)
                val curSizeText = sizeLabel(m.curSize)
                CmpRow(stringResource(R.string.ii_row_size), curSizeText, newSizeText, curSizeText != newSizeText, t.text)
                CmpRow(stringResource(R.string.ii_row_sdk), m.curMinSdk.toString(), m.minSdk.toString(), m.curMinSdk != m.minSdk, if (m.minSdk > m.curMinSdk) warn else ok)
            }
            if (kind == ApkMeta.Kind.DOWNGRADE) {
                if (m.curPlainSystem) Text(stringResource(R.string.ii_down_system), color = bad, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                else if (m.downgradeKeepsData) { if (full) HintTitle(stringResource(R.string.ii_down_root_short), stringResource(R.string.ii_down_root), titleColor = warn, titleSize = 11.sp, titleWeight = FontWeight.Bold, hintColor = warn, hintSize = 11.sp) else Text(stringResource(R.string.ii_down_root_short), color = warn, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                else if (m.downgradeNeedsWipe) { if (full) HintTitle(stringResource(R.string.ii_down_wipe_short), stringResource(R.string.ii_down_wipe), titleColor = bad, titleSize = 11.sp, titleWeight = FontWeight.Bold, hintColor = bad, hintSize = 11.sp) else Text(stringResource(R.string.ii_down_wipe_short), color = bad, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                else HintTitle(stringResource(R.string.ii_down_title), stringResource(R.string.ii_down_note), titleColor = warn, titleSize = 11.sp, titleWeight = FontWeight.Bold, hintColor = warn, hintSize = 11.sp)
            }
            if (m.sigDiffers) Text(stringResource(R.string.ii_sig_diff), color = bad, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            if (m.needsNewerAndroid) Text(stringResource(R.string.ii_minsdk_bad, m.minSdk, Build.VERSION.SDK_INT), color = bad, fontSize = 11.sp)
            if (full) {
                PermList(R.string.ii_perms_added, m.addedPerms, "+ ", ok)
                PermList(R.string.ii_perms_removed, m.removedPerms, "− ", bad)
            }
        }
    }
}

@Composable
private fun PermList(title: Int, list: List<String>, sign: String, col: Color) {
    if (list.isEmpty()) return
    val t = LocalTh.current
    Text(stringResource(title), color = t.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    val shown = list.take(8)
    shown.forEach { Text(sign + it.removePrefix("android.permission."), color = col, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    if (list.size > shown.size) Text(stringResource(R.string.ii_more, list.size - shown.size), color = t.hint, fontSize = 11.sp)
}

/** Полоса установки: общий прогресс по байтам, «n из N», имя файла, этап; после окончания итог и список неудач. */
@Composable
fun InstProgressView(run: InstRun) {
    val t = LocalTh.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (run.finished) {
            Text(
                if (run.bad == 0) stringResource(R.string.ii_done_all) else stringResource(R.string.install_result_n, run.ok, run.bad),
                color = if (run.bad == 0) okCol(t.dark) else t.text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            )
            ThemedProgress({ 1f }, Modifier.fillMaxWidth().height(6.dp), if (run.bad == 0) okCol(t.dark) else t.accent)
            if (run.fails.isNotEmpty()) {
                Text(stringResource(R.string.ii_failed_list), color = t.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                run.fails.forEach { Text("− $it", color = badCol(t.dark), fontSize = 11.sp, maxLines = 12, overflow = TextOverflow.Ellipsis) }
            }
        } else {
            Text(stringResource(R.string.ii_prog_title) + if (run.total > 1) "  ·  " + stringResource(R.string.ii_prog_count, run.index.coerceAtLeast(1), run.total) else "", color = t.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            val frac = (run.done.toFloat() / run.all.coerceAtLeast(1L)).coerceIn(0f, 1f)
            // передача файла: полоска по байтам; установка системой (проверка и запись пакета): бегущая полоска, объём заранее неизвестен
            if (run.phase == 2) (BarStyle.used(t.accent).let { bc -> androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).barFrame(bc, androidx.compose.foundation.shape.RoundedCornerShape(50)), color = bc, trackColor = BarStyle.freeProgress()) })
            else ThemedProgress({ frac }, Modifier.fillMaxWidth().height(6.dp), t.accent)
            if (run.name.isNotEmpty()) Text(run.name, color = t.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(when (run.phase) { 1 -> R.string.ii_ph_upload; 2 -> R.string.ii_ph_install; 3 -> R.string.ii_ph_obb; else -> R.string.ii_ph_prepare }) +
                    (if (run.all > 1) "  " + stringResource(R.string.ii_mb, sizeLabel(run.done), sizeLabel(run.all)) else ""),
                color = t.hint, fontSize = 11.sp,
            )
        }
    }
}

/** Причина отказа системы на языке программы: код INSTALL_FAILED_* переводится, исходный код остаётся в скобках. */
private fun friendlyReason(ctx: Context, raw: String): String {
    val code = Regex("INSTALL_[A-Z_]+").find(raw)?.value
    val res = when (code) {
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" -> R.string.ii_err_incompat
        "INSTALL_FAILED_VERSION_DOWNGRADE" -> R.string.ii_err_downgrade
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" -> R.string.ii_err_storage
        "INSTALL_FAILED_OLDER_SDK" -> R.string.ii_err_sdk
        "INSTALL_FAILED_NO_MATCHING_ABIS" -> R.string.ii_err_abi
        else -> null
    }
    if (res != null) return ctx.getString(res) + " (" + code + ")"
    return raw.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(140) ?: ctx.getString(R.string.ii_fail_generic)
}

/**
 * Понижение на Android 14 и новее без root: система не принимает старую версию ни с флагом -d, ни после «pm uninstall -k».
 * Остаётся удалить приложение вместе с данными и поставить старую версию; текущая версия до этого сохраняется копией
 * (со всеми частями), при неудаче установки возвращается из копии (данные при этом не восстанавливаются).
 * Возвращает "Success" или причину отказа.
 */
private suspend fun wipeDowngrade(ctx: Context, pkg: String, apk: File): String {
    if (Adb.isRoot) return keepDataDowngrade(ctx, pkg, apk)
    val copy = runCatching { AppBackups.save(ctx, pkg) }.getOrNull()
        ?: return "FAIL:" + ctx.getString(R.string.ii_wipe_nocopy)
    val u = Adb.shell("pm uninstall --user 0 $pkg")
    if (!u.contains("Success", true)) return "FAIL:" + ctx.getString(R.string.ii_wipe_uninstall_failed) + ": " + u.lines().firstOrNull { it.isNotBlank() }.orEmpty().take(100)
    val r = Adb.installFiles(listOf(apk), "-r")
    if (r.contains("Success", true)) return "Success"
    val back = Adb.installFiles(copy.files, "-r")
    val why = friendlyReason(ctx, r)
    return "FAIL:" + why + " — " + ctx.getString(if (back.contains("Success", true)) R.string.ii_wipe_restored else R.string.ii_wipe_restore_failed)
}

/**
 * Понижение с сохранением данных при root (Android 14+, где система такое запрещает): полная копия текущей версии и её данных (tar от root, механизм
 * «Копия с данными»), удаление приложения, установка старой версии, возврат данных из копии. Если установка старой версии не вышла, возвращаются
 * текущая версия и её данные из копии. Возвращает "Success" или "FAIL:причина".
 */
private suspend fun keepDataDowngrade(ctx: Context, pkg: String, apk: File): String {
    val label = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    val entry = AppEntry(pkg, label, false, true, true, "", emptyList())
    runCatching { FullBackup.backup(ctx, entry) { } }
    val copy = runCatching { FullBackup.list().firstOrNull { it.pkg == pkg } }.getOrNull()
    if (copy == null || "internal" !in copy.parts || "apk" !in copy.parts) return "FAIL:" + ctx.getString(R.string.ii_wipe_nocopy)
    val u = Adb.shell("pm uninstall --user 0 $pkg")
    if (!u.contains("Success", true)) return "FAIL:" + ctx.getString(R.string.ii_wipe_uninstall_failed) + ": " + u.lines().firstOrNull { it.isNotBlank() }.orEmpty().take(100)
    val r = Adb.installFiles(listOf(apk), "-r")
    if (!r.contains("Success", true)) {
        val back = runCatching { FullBackup.restore(ctx, copy) { } }.getOrNull()
        val ok = back != null && Adb.shell("pm path $pkg").contains("package:")
        return "FAIL:" + friendlyReason(ctx, r) + " — " + ctx.getString(if (ok) R.string.ii_root_restored else R.string.ii_wipe_restore_failed)
    }
    // данные возвращаются в уже установленную старую версию (APK из копии не ставится)
    val res = runCatching { FullBackup.restore(ctx, copy.copy(parts = copy.parts - "apk")) { } }
    return if (res.isSuccess) "Success" else "FAIL:" + ctx.getString(R.string.ii_root_data_failed)
}

/** Установка с отчётом о ходе: файл за файлом; понижение версии идёт через безопасный путь (копия текущей версии, возврат при неудаче). */
suspend fun installWithProgress(ctx: Context, files: List<ApkFile>, metas: Map<String, ApkMeta?>, run: InstRun, wipe: Boolean = false): Pair<Int, Int> =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        run.ok = 0; run.bad = 0; run.fails.clear(); run.finished = false; run.running = true
        run.total = files.size; run.index = 0; run.done = 0L; run.phase = 0
        run.all = files.sumOf { it.size.coerceAtLeast(1L) }.coerceAtLeast(1L)
        var base = 0L
        try {
            for ((i, f0) in files.withIndex()) {
                val meta = metas[f0.name]
                run.index = i + 1; run.name = meta?.label ?: f0.name; run.phase = 0
                if (run.stop) { run.bad++; run.fails.add((meta?.label ?: f0.name) + ": " + ctx.getString(R.string.ii_stopped)); base += f0.size.coerceAtLeast(1L); run.done = base; continue }
                val sz = f0.size.coerceAtLeast(1L)
                var reason: String? = null
                try {
                    if (meta != null && meta.sigDiffers) throw IllegalStateException(ctx.getString(R.string.ii_sig_fail))
                    if (meta != null && meta.kind == ApkMeta.Kind.DOWNGRADE && meta.curPlainSystem) throw IllegalStateException(ctx.getString(R.string.ii_down_system))
                    if (meta != null && meta.downgradeNeedsWipe && !wipe) throw IllegalStateException(ctx.getString(R.string.ii_down_wipe_refused))
                    val parts = f0.parts
                    if (parts != null) {
                        val partsTotal = parts.sumOf { it.length() }.coerceAtLeast(1L)
                        Adb.onInstallBytes = { n -> run.done = base + n.coerceAtMost(partsTotal); if (n >= partsTotal) run.phase = 2 }
                        run.phase = if (f0.obb.isNotEmpty()) 3 else 1
                        val ok = Bundles.installAdb(ctx, f0, if (meta?.kind == ApkMeta.Kind.DOWNGRADE) "-r -d" else "-r") { ph -> run.phase = ph }
                        if (!ok) reason = ctx.getString(R.string.ii_fail_generic)
                    } else {
                        val f = Batch.materialize(f0)
                        val local: File? = f.file ?: if (meta?.kind == ApkMeta.Kind.DOWNGRADE) {
                            File(File(ctx.cacheDir, "batch").apply { mkdirs() }, "dg_" + System.nanoTime() + ".apk").also { out ->
                                ctx.contentResolver.openInputStream(f.uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                            }
                        } else null
                        val total = (local?.length() ?: f.size).coerceAtLeast(1L)
                        Adb.onInstallBytes = { n -> run.done = base + n.coerceAtMost(total); if (n >= total) run.phase = 2 }
                        run.phase = 1
                        val out: String = if (meta != null && meta.downgradeNeedsWipe && wipe && local != null) {
                            wipeDowngrade(ctx, meta.pkg, local)
                        } else if (meta != null && meta.kind == ApkMeta.Kind.DOWNGRADE && local != null) {
                            val r = AppBackups.installFiles(ctx, meta.pkg, listOf(local), meta.verCode)
                            if (r == "ok") "Success" else r.removePrefix("fail").removePrefix(":").ifBlank { r }
                        } else {
                            val input = local?.inputStream() ?: ctx.contentResolver.openInputStream(f.uri)!!
                            input.use { Adb.installApk(it, if (local != null) local.length() else f.size) }
                        }
                        if (!out.contains("Success", true)) reason = if (out.startsWith("FAIL:")) out.removePrefix("FAIL:") else friendlyReason(ctx, out)
                    }
                } catch (e: Exception) {
                    reason = (e.message ?: e.javaClass.simpleName).take(140)
                }
                Adb.onInstallBytes = null
                if (reason == null) run.ok++ else { run.bad++; run.fails.add(run.name + ": " + reason) }
                base += sz; run.done = base
            }
        } finally {
            Adb.onInstallBytes = null
            run.done = run.all; run.running = false; run.finished = true
        }
        run.ok to run.bad
    }

/** Окно хода тихой установки в «Файлах»: полоска и итог; закрыть можно, когда установка закончилась. */
@Composable
fun InstallRunDialog(d: FmDlg.InstallRunDlg, close: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val bgFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(d.run.finished) { kotlinx.coroutines.delay(150); runCatching { bgFocus.requestFocus() } }
    LAlertDialog(
        onDismissRequest = { if (!d.run.running) close() },
        title = null,
        text = { InstProgressView(d.run) },
        confirmButton = {
            if (d.run.finished) androidx.compose.material3.TextButton(close, Modifier.focusRequester(bgFocus)) { Text(stringResource(R.string.ii_close)) }
            else Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (d.run.total > 1 && !d.run.stop) androidx.compose.material3.TextButton({ d.run.stop = true }) { Text(stringResource(R.string.ii_stop)) }
                androidx.compose.material3.TextButton({ InstBg.begin(ctx, d.run); close() }, Modifier.focusRequester(bgFocus)) { Text(stringResource(R.string.ii_bg)) }
            }
        },
    )
}

/** Подтверждение после выбора ADB-phon установщиком: блок версий (как у App Manager) для каждого пакета и кнопки «Установить» / «Отмена». */
@Composable
fun InstallConfirmDialog(d: FmDlg.InstallConfirm, close: () -> Unit) {
    val t = LocalTh.current
    androidx.compose.runtime.LaunchedEffect(Unit) { InstBg.nextBackground = false }
    val goFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); runCatching { goFocus.requestFocus() } }
    LAlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.ii_confirm_title), maxLines = 1) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).barScroll(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                d.files.take(40).forEach { f -> ApkInfoBlock(f, d.metas[f.name], full = d.files.size == 1, modifier = Modifier.fillMaxWidth()) }
                if (d.files.size > 40) Text(stringResource(R.string.ii_more, d.files.size - 40), color = t.hint, fontSize = 11.sp)
            }
        },
        confirmButton = {
            val one = d.files.singleOrNull()?.let { d.metas[it.name] }
            when {
                one != null && one.curPlainSystem && one.kind == ApkMeta.Kind.DOWNGRADE -> {}
                one != null && one.downgradeNeedsWipe -> androidx.compose.material3.TextButton({ close(); d.go(true) }) { Text(stringResource(if (one.downgradeKeepsData) R.string.ii_confirm_keep else R.string.ii_confirm_wipe)) }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    androidx.compose.material3.TextButton({ close(); InstBg.nextBackground = true; d.go(false) }) { Text(stringResource(R.string.ii_bg)) }
                    androidx.compose.material3.TextButton({ close(); d.go(false) }, Modifier.focusRequester(goFocus)) { Text(stringResource(R.string.ii_confirm_go)) }
                }
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(close) { Text(stringResource(R.string.btn_cancel)) } },
    )
}
