@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.adbphon

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Сохранённая копия приложения: все APK одной версии (без данных приложения). */
data class AppCopy(val pkg: String, val label: String, val versionName: String, val versionCode: Long, val savedAt: Long, val dir: File) {
    val files: List<File> get() = dir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".apk") }?.sortedBy { if (it.name == "base.apk") "" else it.name }.orEmpty()
    val size: Long get() = files.sumOf { it.length() }
}

/**
 * Копии приложений: делаются перед удалением и при понижении версии, из них же можно поставить любую версию.
 * Лежат в AppMenuBackups на внутренней памяти (если есть право записи), иначе в папке самой программы.
 */
object AppBackups {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun publicRoot() = File(Environment.getExternalStorageDirectory(), "AppMenuBackups")
    private fun privateRoot(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "backups")

    /** Куда писать новые копии. */
    fun root(ctx: Context): File {
        val pub = publicRoot()
        if (runCatching { pub.mkdirs(); pub.isDirectory && pub.canWrite() }.getOrDefault(false)) return pub
        return privateRoot(ctx).apply { mkdirs() }
    }

    private fun versionCode(pi: android.content.pm.PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()

    /** Установленная версия: название и код; null — не установлено. */
    fun installedVersion(ctx: Context, pkg: String): Pair<String, Long>? = runCatching {
        val pi = ctx.packageManager.getPackageInfo(pkg, 0)
        (pi.versionName ?: "?") to versionCode(pi)
    }.getOrNull()

    private fun isPlainSystem(ai: ApplicationInfo) = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0

    private fun writeMeta(dir: File, label: String, versionName: String, code: Long) {
        File(dir, "meta.txt").writeText(listOf(label.replace('\n', ' '), versionName.replace('\n', ' '), code.toString(), System.currentTimeMillis().toString()).joinToString("\n"))
    }

    private fun readCopy(pkg: String, dir: File): AppCopy? {
        val m = runCatching { File(dir, "meta.txt").readLines() }.getOrNull() ?: return null
        if (m.size < 4) return null
        return AppCopy(pkg, m[0], m[1], m[2].toLongOrNull() ?: return null, m[3].toLongOrNull() ?: 0L, dir)
    }

    /** Копирует APK установленного приложения. Уже сохранённая версия не копируется повторно. */
    fun save(ctx: Context, pkg: String): AppCopy? {
        val pm = ctx.packageManager
        val pi = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: return null
        val ai = pi.applicationInfo ?: return null
        if (isPlainSystem(ai)) return null
        val code = versionCode(pi)
        val parent = File(root(ctx), pkg).apply { mkdirs() }
        val dir = File(parent, code.toString())
        readCopy(pkg, dir)?.let { return it }
        val tmp = File(parent, "$code.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val label = pm.getApplicationLabel(ai).toString()
        return runCatching {
            (listOf(ai.sourceDir) + (ai.splitSourceDirs ?: emptyArray())).forEach { File(it).copyTo(File(tmp, File(it).name), true) }
            writeMeta(tmp, label, pi.versionName ?: "?", code)
            dir.deleteRecursively()
            tmp.renameTo(dir)
            readCopy(pkg, dir)
        }.onFailure { tmp.deleteRecursively() }.getOrNull()
    }

    /** Кладёт в архив копий готовый APK (скачанный или выбранный). */
    fun importApk(ctx: Context, apk: File): AppCopy? {
        val pm = ctx.packageManager
        val pi = pm.getPackageArchiveInfo(apk.path, 0) ?: return null
        val ai = pi.applicationInfo ?: return null
        ai.sourceDir = apk.path
        ai.publicSourceDir = apk.path
        val code = versionCode(pi)
        val dir = File(File(root(ctx), pi.packageName).apply { mkdirs() }, code.toString())
        readCopy(pi.packageName, dir)?.let { return it }
        return runCatching {
            dir.deleteRecursively()
            dir.mkdirs()
            apk.copyTo(File(dir, "base.apk"), true)
            writeMeta(dir, pm.getApplicationLabel(ai).toString(), pi.versionName ?: "?", code)
            readCopy(pi.packageName, dir)
        }.getOrNull()
    }

    /** Все копии (по одному пакету или вообще все), новые версии первыми. */
    fun list(ctx: Context, pkg: String?): List<AppCopy> {
        val out = ArrayList<AppCopy>()
        for (root in listOf(publicRoot(), privateRoot(ctx)).distinct()) {
            val pkgs = if (pkg != null) listOf(File(root, pkg)) else root.listFiles()?.filter { it.isDirectory }.orEmpty()
            for (p in pkgs) p.listFiles()?.filter { it.isDirectory && !it.name.endsWith(".tmp") }?.forEach { d -> readCopy(p.name, d)?.let { out.add(it) } }
        }
        return out.distinctBy { it.pkg to it.versionCode }.sortedWith(compareBy<AppCopy> { it.label.lowercase() }.thenByDescending { it.versionCode })
    }

    fun delete(copy: AppCopy) { copy.dir.deleteRecursively() }

    /** Перед удалением: сохраняет копии (если включено) и затем выполняет [then] в основном потоке. */
    fun saveThen(ctx: Context, pkgs: List<String>, then: () -> Unit) {
        if (!Cfg.backupOnDelete(ctx) || pkgs.isEmpty()) { then(); return }
        val app = ctx.applicationContext
        scope.launch {
            var n = 0
            pkgs.forEach { if (runCatching { save(app, it) }.getOrNull() != null) n++ }
            withContext(Dispatchers.Main) {
                if (n > 0) Toast.makeText(app, app.getString(R.string.backup_made, n), Toast.LENGTH_SHORT).show()
                then()
            }
        }
    }

    /**
     * Ставит выбранную версию. Возвращает "ok", "system" (подтвердить в окне системы), "need_adb" или "fail:<причина>".
     * Понижение версии: сначала обычная установка с флагом разрешения понижения; если система отказала, приложение
     * удаляется с сохранением данных и ставится нужная версия; при неудаче возвращается прежняя версия из копии.
     */
    suspend fun installFiles(ctx: Context, pkg: String, files: List<File>, code: Long): String = withContext(Dispatchers.IO) {
        val cur = installedVersion(ctx, pkg)?.second
        val down = cur != null && code < cur
        if (!Adb.isConnected) return@withContext "need_adb"
        if (!down) {
            val r = Adb.installFiles(files, "-r")
            return@withContext if (r.contains("Success", true)) "ok" else "fail:" + r.take(140)
        }
        val ai = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
        val system = ai != null && ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val safe = runCatching { save(ctx, pkg) }.getOrNull()
        var r = Adb.installFiles(files, "-r -d")
        if (r.contains("Success", true)) return@withContext "ok"
        if (system) return@withContext "fail:" + r.take(140)
        val u = Adb.shell("pm uninstall -k $pkg")
        if (u.contains("Success", true)) {
            r = Adb.installFiles(files, "-r")
            if (r.contains("Success", true)) return@withContext "ok"
            safe?.let { Adb.installFiles(it.files, "-r") }
        }
        "fail:" + r.take(140)
    }
}

/** Версия из каталога F-Droid. */
data class FdVersion(val name: String, val code: Long, val url: String)

private fun when_(ms: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ms))
private fun mb(b: Long): String = String.format(Locale.US, "%.1f", b / 1048576.0)

private fun resultText(ctx: Context, r: String): String = when {
    r == "ok" -> ctx.getString(R.string.ver_done)
    r == "system" -> ctx.getString(R.string.net_system_dialog)
    r == "need_adb" -> ctx.getString(R.string.ver_need_adb)
    else -> ctx.getString(R.string.ver_failed) + r.removePrefix("fail").let { if (it.length > 1) ": " + it.removePrefix(":") else "" }
}

/** Версии приложения: сохранённые копии, версии из F-Droid и файл APK; установка любой, в том числе более старой. */
@Composable
fun VersionsDialog(pkg: String, label: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val copies = remember(tick) { AppBackups.list(ctx, pkg) }
    val installed = remember(tick) { AppBackups.installedVersion(ctx, pkg) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var fd by remember { mutableStateOf<List<FdVersion>?>(null) }
    var browser by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    fun run(block: suspend () -> String) {
        if (busy) return
        scope.launch {
            busy = true
            status = ctx.getString(R.string.ver_working)
            status = try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { ctx.getString(R.string.ver_failed) + ": " + (e.message ?: "") }
            busy = false
            tick++
        }
    }

    if (browser) FileBrowser(
        onApks = { list ->
            browser = false
            run {
                val f0 = list.firstOrNull() ?: return@run ctx.getString(R.string.ver_failed)
                val f = Batch.materialize(f0).file ?: return@run ctx.getString(R.string.ver_failed)
                val copy = withContext(Dispatchers.IO) { AppBackups.importApk(ctx, f) } ?: return@run ctx.getString(R.string.ver_not_apk)
                if (copy.pkg != pkg) return@run ctx.getString(R.string.ver_other_app, copy.pkg)
                resultText(ctx, AppBackups.installFiles(ctx, pkg, copy.files, copy.versionCode))
            }
        },
        onClose = { browser = false },
    )

    HDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.88f).clip(RoundedCornerShape(24.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(24.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.pver_title, label), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (installed != null) stringResource(R.string.ver_installed, installed.first, installed.second) else stringResource(R.string.ver_not_installed),
                color = t.sub, fontSize = 14.sp,
            )
            Text(stringResource(R.string.pver_hint), color = t.hint, fontSize = 12.sp)
            if (status.isNotEmpty()) Text(status, color = t.accent, fontSize = 14.sp)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(stringResource(R.string.ver_fdroid), false, Modifier.focusRequester(first)) {
                    run {
                        fd = null
                        fd = NetSearch.fdroidVersions(pkg)
                        if (fd.isNullOrEmpty()) ctx.getString(R.string.ver_fdroid_none) else ""
                    }
                }
                Chip(stringResource(R.string.ver_file), false) { if (!busy) browser = true }
                Chip(stringResource(R.string.close), true) { onClose() }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Text(stringResource(R.string.ver_copies), color = t.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                if (copies.isEmpty()) item { Text(stringResource(R.string.ver_no_copies), color = t.hint, fontSize = 13.sp) }
                items(copies, key = { it.dir.path }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(t.tile).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${c.versionName} (${c.versionCode})", color = t.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("${when_(c.savedAt)} · ${mb(c.size)} " + stringResource(R.string.mb), color = t.hint, fontSize = 12.sp)
                        }
                        Chip(stringResource(R.string.ver_install), installed?.second == c.versionCode) {
                            run { resultText(ctx, AppBackups.installFiles(ctx, pkg, c.files, c.versionCode)) }
                        }
                        Chip(stringResource(R.string.ver_delete_copy), false) { AppBackups.delete(c); tick++ }
                    }
                }
                val list = fd
                if (list != null && list.isNotEmpty()) {
                    item { Text(stringResource(R.string.ver_fdroid_list), color = t.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
                    items(list, key = { it.url }) { v ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(t.tile).padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("${v.name} (${v.code})", color = t.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Chip(stringResource(R.string.ver_download_install), installed?.second == v.code) {
                                run {
                                    val apk = NetSearch.download(ctx, v.url) { mbDone -> status = ctx.getString(R.string.net_downloading, v.name, mbDone.toInt()) }?.file
                                        ?: return@run ctx.getString(R.string.net_failed)
                                    val copy = withContext(Dispatchers.IO) { AppBackups.importApk(ctx, apk) } ?: return@run ctx.getString(R.string.ver_not_apk)
                                    resultText(ctx, AppBackups.installFiles(ctx, pkg, copy.files, copy.versionCode))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Список всех сохранённых копий (в том числе удалённых приложений): выбери приложение, чтобы увидеть версии. */
@Composable
fun BackupsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    var tick by remember { mutableIntStateOf(0) }
    val groups = remember(tick) { AppBackups.list(ctx, null).groupBy { it.pkg } }
    var open by remember { mutableStateOf<Pair<String, String>?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    open?.let { VersionsDialog(it.first, it.second) { open = null; tick++ } }
    HDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.8f).fillMaxHeight(0.85f).clip(RoundedCornerShape(24.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(24.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.backups_title), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.backups_where, AppBackups.root(ctx).absolutePath), color = t.hint, fontSize = 12.sp)
            Chip(stringResource(R.string.close), true, Modifier.focusRequester(first)) { onClose() }
            if (groups.isEmpty()) Text(stringResource(R.string.backups_empty), color = t.sub, fontSize = 14.sp)
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(groups.entries.toList(), key = { it.key }) { (pkg, list) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(t.tile).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(list.first().label, color = t.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(pkg + " · " + stringResource(R.string.backups_n, list.size) + if (AppBackups.installedVersion(ctx, pkg) == null) " · " + stringResource(R.string.ver_not_installed) else "", color = t.hint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Chip(stringResource(R.string.backups_open_versions), false) { open = pkg to list.first().label }
                    }
                }
            }
        }
    }
}
