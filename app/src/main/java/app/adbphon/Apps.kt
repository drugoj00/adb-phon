package app.adbphon

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Группа «Приложения» корневого экрана: приложения телефона как файлы в обычной панели «Файлов».
 * Папки: Установленные (пользовательские), Системные, Отключённые, Удалённые (сняты для пользователя, но остались в системе).
 * Файл = APK (или .apks, если у приложения есть split-части); копирование в другую панель = экспорт APK. Источник данных: PackageManager (то же, что вкладка «Софт»);
 * действия, требующие прав (отключить, удалить системное, установить заново), идут через связь ADB или root.
 */
class AppsFs : RemoteFs {
    private val ctx get() = FmIo.app
    override val root = "apps://"
    override val label = "apps"

    companion object {
        val keys = listOf("installed" to R.string.ap_installed, "system" to R.string.ap_system, "disabled" to R.string.ap_disabled, "removed" to R.string.ap_removed)
        private val pkgRe = Regex("\\(([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)\\)(?: [^/]*)?\\.apks?$")
        fun pkgOf(name: String): String? = pkgRe.find(name.substringAfterLast('/'))?.groupValues?.get(1)
        /** Папка по имени (локализованному) или по ключу. */
        /** Копии из последнего показа «Удалённых»: имя строки -> копия. */
        @Volatile var copyMap: Map<String, BkCopy> = emptyMap()
        fun copyOfName(name: String): BkCopy? = copyMap[name]
        fun keyOfSegment(seg: String): String? = keys.firstOrNull { it.first == seg || Fm.str(it.second) == seg }?.first
    }

    private fun folderOf(path: String): String? = keyOfSegment(path.removePrefix("apps://").trim('/').substringBefore('/'))
    fun dirName(key: String) = Fm.str(keys.first { it.first == key }.second)

    @Suppress("DEPRECATION")
    private fun packages(key: String): List<PackageInfo> {
        val pm = ctx.packageManager
        val all = pm.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.GET_META_DATA)
        fun installed(a: ApplicationInfo) = a.flags and 0x00800000 != 0     // FLAG_INSTALLED
        fun sys(a: ApplicationInfo) = a.flags and ApplicationInfo.FLAG_SYSTEM != 0
        fun on(pi: PackageInfo) = pi.applicationInfo?.enabled != false && pm.getApplicationEnabledSetting(pi.packageName).let { it != PackageManager.COMPONENT_ENABLED_STATE_DISABLED && it != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER }
        return all.filter { pi ->
            val a = pi.applicationInfo ?: return@filter false
            when (key) {
                "installed" -> installed(a) && !sys(a) && on(pi)
                "system" -> installed(a) && sys(a) && on(pi)
                "disabled" -> installed(a) && !on(pi)
                else -> !installed(a)
            }
        }
    }

    override suspend fun list(path: String): List<FsEntry> = withContext(Dispatchers.IO) {
        val key = folderOf(path) ?: return@withContext keys.map { (k, _) -> FsEntry(dirName(k), "apps://" + dirName(k), true, 0) }
        val pm = ctx.packageManager
        val dir = "apps://" + dirName(key)
        val apps = packages(key).map { pi ->
            val a = pi.applicationInfo!!
            val splits = a.splitSourceDirs.orEmpty()
            val size = (listOfNotNull(a.sourceDir) + splits).sumOf { File(it).length() }
            val label = runCatching { pm.getApplicationLabel(a).toString() }.getOrDefault(pi.packageName).replace('/', '-')
            val ver = pi.versionName?.takeIf { it.isNotBlank() }?.replace('/', '-') ?: ""
            FsEntry("$label (${pi.packageName})" + (if (ver.isNotEmpty()) " $ver" else "") + if (splits.isNotEmpty()) ".apks" else ".apk", "$dir/${pi.packageName}", false, size)
        }
        if (key != "removed") return@withContext apps.sortedBy { it.name.lowercase() }
        // «Удалённые»: ещё и сохранённые копии APK (бэкапы ADB-phon) тех приложений, которых на телефоне уже нет
        val copies = AppCopies.all(ctx).filter { !AppActions.isInstalled(it.pkg) }
        val map = HashMap<String, BkCopy>()
        val seen = HashSet(apps.map { it.name })
        val ce = copies.map { c ->
            var n = AppCopies.nameOf(c)
            var i = 2
            while (!seen.add(n)) { n = AppCopies.nameOf(c, i++) }
            map[n] = c
            FsEntry(n, "$dir/" + AppCopies.PREFIX + c.id, false, c.size)
        }
        AppsFs.copyMap = map
        (apps + ce).sortedBy { it.name.lowercase() }
    }

    /** Экспорт: base.apk как есть, при split-частях zip со всеми частями. */
    override suspend fun copyTo(path: String, dest: File) {
        withContext(Dispatchers.IO) {
            val last = path.substringAfterLast('/')
            if (last.startsWith(AppCopies.PREFIX)) {
                val c = AppCopies.byId(ctx, last.removePrefix(AppCopies.PREFIX)) ?: error(Fm.str(R.string.ap_copy_gone))
                val parts = c.files
                if (parts.isEmpty()) error(Fm.str(R.string.ap_no_apk))
                if (parts.size == 1) parts[0].copyTo(dest, true)
                else ZipOutputStream(dest.outputStream()).use { z -> parts.forEach { f -> z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
                return@withContext
            }
            val pkg = pkgOf(path.substringAfterLast('/') + ".apk") ?: path.substringAfterLast('/')
            val a = ctx.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            val parts = (listOfNotNull(a.sourceDir) + a.splitSourceDirs.orEmpty()).map { File(it) }.filter { it.isFile }
            if (parts.isEmpty()) error(Fm.str(R.string.ap_no_apk))
            if (parts.size == 1) parts[0].copyTo(dest, true)
            else ZipOutputStream(dest.outputStream()).use { z -> parts.forEach { f -> z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
        }
    }

    override suspend fun head(path: String, n: Int): ByteArray = ByteArray(0)
    override fun parent(path: String): String? { val t = path.removePrefix("apps://").trim('/'); return if (t.isEmpty()) null else "apps://" }
    override suspend fun roots(): List<Pair<String, String>> = listOf("apps" to root)
}

/** Действия над приложением из меню строки. Возвращают короткое сообщение для пользователя. */
object AppActions {
    private fun ctx() = FmIo.app
    private suspend fun adb(cmd: String): String? = if (AutoAdb.ensure()) Adb.shell(cmd, 30_000, fast = true) else null

    fun info(pkg: String) { runCatching { ctx().startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    fun open(pkg: String): String? {
        val i = ctx().packageManager.getLaunchIntentForPackage(pkg) ?: return Fm.str(R.string.ap_no_launch)
        runCatching { ctx().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { return it.message }
        return null
    }

    suspend fun stop(pkg: String): String = if (adb("am force-stop $pkg") != null) Fm.str(R.string.ap_done) else {
        runCatching { (ctx().getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).killBackgroundProcesses(pkg) }; Fm.str(R.string.ap_stop_soft)
    }

    suspend fun setEnabled(pkg: String, on: Boolean): String {
        val out = adb(if (on) "pm enable $pkg" else "pm disable-user --user 0 $pkg") ?: return AutoAdb.hint()
        return if (out.contains("Exception", true) || out.contains("Error", true) || out.contains("Unknown", true)) out.lines().first { it.isNotBlank() } else Fm.str(R.string.ap_done)
    }

    /** Обычное приложение: системное окно удаления; системное и прочее: pm uninstall --user 0 через ADB или root. */
    suspend fun uninstall(pkg: String, system: Boolean): String {
        if (!system) { saveCopy(pkg); runCatching { ctx().startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; return Fm.str(R.string.ap_done) }
        val out = adb("pm uninstall --user 0 $pkg") ?: return AutoAdb.hint()
        return if (out.contains("Success")) Fm.str(R.string.ap_done) else out.lines().firstOrNull { it.isNotBlank() } ?: Fm.str(R.string.ap_done)
    }

    suspend fun reinstall(pkg: String): String {
        val out = adb("cmd package install-existing $pkg") ?: return AutoAdb.hint()
        return if (out.contains("installed", true)) Fm.str(R.string.ap_done) else out.lines().firstOrNull { it.isNotBlank() } ?: Fm.str(R.string.ap_done)
    }

    /** Перед удалением обычного приложения: копия APK в архив копий (если включено в настройках). */
    suspend fun saveCopy(pkg: String) {
        if (!Cfg.backupOnDelete(ctx())) return
        withContext(Dispatchers.IO) { runCatching { AppBackups.save(ctx(), pkg) } }
    }

    /** Очистка кэша: с root полностью, по ADB внешний кэш (внутренний чужого приложения без root недоступен). Возвращает (успех, сообщение). */
    suspend fun clearCache(pkg: String): Pair<Boolean, String> {
        if (!AutoAdb.ensure()) return false to AutoAdb.hint()
        if (Adb.isRoot) {
            Adb.shell("rm -rf /data/data/$pkg/cache/* /data/data/$pkg/code_cache/* /data/user_de/0/$pkg/cache/* /data/user_de/0/$pkg/code_cache/* /sdcard/Android/data/$pkg/cache/* /sdcard/Android/data/$pkg/code_cache/* 2>&1", 60_000, fast = true)
            return true to Fm.str(R.string.ap_done)
        }
        Adb.shell("rm -rf /sdcard/Android/data/$pkg/cache/* /sdcard/Android/data/$pkg/code_cache/* 2>&1", 30_000, fast = true)
        // на части прошивок `pm clear --cache-only` зависает: ограничиваем 8 секундами
        val o = Adb.shell("timeout 8 pm clear --cache-only $pkg 2>&1", 20_000, fast = true)
        return if (o.contains("Success")) true to Fm.str(R.string.ap_done) else true to Fm.str(R.string.ap_cache_part)
    }

    suspend fun clearData(pkg: String): Pair<Boolean, String> {
        if (!AutoAdb.ensure()) return false to AutoAdb.hint()
        val o = Adb.shell("pm clear $pkg 2>&1", 60_000, fast = true)
        return if (o.contains("Success")) true to Fm.str(R.string.ap_done) else false to (o.lines().firstOrNull { it.isNotBlank() } ?: "?")
    }

    /** Одно действие пакетной обработки. Возвращает (успех, сообщение). */
    suspend fun runOne(action: String, pkg: String): Pair<Boolean, String> {
        if (action != "export" && action != "forget" && !isInstalled(pkg)) return false to Fm.str(R.string.ap_err_not_installed)
        return when (action) {
            "stop" -> true to stop(pkg)
            "disable", "enable" -> {
                if (!AutoAdb.ensure()) return false to AutoAdb.hint()
                val r = setEnabled(pkg, action == "enable")
                (r == Fm.str(R.string.ap_done)) to r
            }
            "uninstall" -> {
                if (isSystem(pkg)) return false to Fm.str(R.string.ap_err_system)
                if (!AutoAdb.ensure()) return false to AutoAdb.hint()
                saveCopy(pkg)
                val o = Adb.shell("pm uninstall --user 0 $pkg 2>&1", 60_000, fast = true)
                if (o.contains("Success")) true to Fm.str(R.string.ap_done) else false to (o.lines().firstOrNull { it.isNotBlank() } ?: "?")
            }
            "cache" -> clearCache(pkg)
            "data" -> clearData(pkg)
            else -> false to "?"
        }
    }

    /** Общая область для действий над приложениями: окна и меню закрываются сразу, а работа продолжается. */
    val bgScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)

    /** После действий: панели «Приложений» и дерево на «домике» перечитываются. */
    fun refresh() {
        bgScope.launch {
            Fm.panes.forEach { pp -> if (pp.src == "smb" && pp.smb is AppsFs && !pp.root) Fm.load(pp, remember = false) }
            FmRootCfg.kids.keys.filter { it.startsWith("A|") }.forEach { id -> launch { FmRootCfg.loadKids(id, emptyList()) } }
        }
    }

    /** Установка из сохранённой копии: тихо через ADB/root, иначе сессией PackageInstaller с подтверждением системы. */
    suspend fun installCopy(c: BkCopy): String {
        val files = c.files
        if (files.isEmpty()) return Fm.str(R.string.ap_copy_gone)
        if (Adb.isConnected) {
            val r = AppBackups.installFiles(ctx(), c.pkg, files, c.code)
            refresh()
            return if (r == "ok" || r == "system") Fm.str(R.string.ap_done) else Fm.str(R.string.ap_install_fail, r.removePrefix("fail:"))
        }
        Fm.installFilesViaSession(files)
        return Fm.str(R.string.inst_installing)
    }

    fun isSystem(pkg: String): Boolean = runCatching { ctx().packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).flags and ApplicationInfo.FLAG_SYSTEM != 0 }.getOrDefault(false)
    fun isEnabled(pkg: String): Boolean = runCatching { ctx().packageManager.getApplicationEnabledSetting(pkg).let { it != PackageManager.COMPONENT_ENABLED_STATE_DISABLED && it != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER } }.getOrDefault(true)
    fun isInstalled(pkg: String): Boolean = runCatching { ctx().packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).flags and 0x00800000 != 0 }.getOrDefault(false)
    fun dataDir(pkg: String): String = "/sdcard/Android/data/$pkg"
}

/** Значки приложений: ленивая загрузка в фоне и общий кэш (небольшой, чтобы не нагружать приставку). */
private val appIconCache = LruCache<String, ImageBitmap>(120)

@Composable
fun AppsRowIcon(name: String, size: Dp) {
    val pkg = AppsFs.pkgOf(name)
    val bmp by produceState<ImageBitmap?>(pkg?.let { appIconCache.get(it) }, pkg) {
        if (pkg != null && value == null) value = withContext(Dispatchers.IO) {
            runCatching { FmIo.app.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()?.also { appIconCache.put(pkg, it) }
        }
    }
    val b = bmp
    if (b != null) Image(b, null, Modifier.size(size)) else Box(Modifier.size(size)) { Icon(Icons.Filled.Android, null, Modifier.size(size), tint = androidx.compose.ui.graphics.Color(0xFF9CCC65)) }
}

/** Сохранённая копия APK (бэкап ADB-phon): kind 0 = архив копий (AppMenuBackups), 1 = «Копия с данными» (ADB-phon/Backups). */
class BkCopy(val id: String, val kind: Int, val pkg: String, val label: String, val ver: String, val code: Long, val time: Long, val dir: File) {
    val files: List<File> get() = dir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".apk") }?.sortedBy { if (it.name == "base.apk") "" else it.name }.orEmpty()
    val size: Long get() = files.sumOf { it.length() }
}

object AppCopies {
    const val PREFIX = "bk~"
    private fun enc(f: File) = f.absolutePath.replace('/', '|')

    /** Все сохранённые копии APK, читаются файлами напрямую (ADB не нужен). */
    fun all(ctx: Context): List<BkCopy> {
        val out = ArrayList<BkCopy>()
        runCatching { AppBackups.list(ctx, null).forEach { out += BkCopy(enc(it.dir), 0, it.pkg, it.label, it.versionName, it.versionCode, it.savedAt, it.dir) } }
        runCatching {
            File(FullBackup.BASE).listFiles()?.filter { it.isDirectory }?.forEach { pd ->
                pd.listFiles()?.filter { it.isDirectory }?.forEach { d ->
                    val mf = File(d, "meta.json")
                    if (!mf.isFile) return@forEach
                    runCatching {
                        val o = JSONObject(mf.readText())
                        val c = BkCopy(enc(d), 1, o.getString("pkg"), o.optString("label").ifBlank { o.getString("pkg") }, o.optString("versionName"), o.optLong("versionCode"), d.lastModified(), d)
                        if (c.files.isNotEmpty()) out += c
                    }
                }
            }
        }
        return out
    }

    fun byId(ctx: Context, id: String): BkCopy? = all(ctx).firstOrNull { it.id == id }

    fun nameOf(c: BkCopy, n: Int = 1): String {
        val tag = Fm.str(R.string.ap_copy_tag)
        val t = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(c.time))
        val ver = c.ver.replace('/', '-').takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        return c.label.replace('/', '-') + " (" + c.pkg + ")" + ver + " · $tag $t" + (if (n > 1) " ($n)" else "") + if (c.files.size > 1) ".apks" else ".apk"
    }

    /** Удаляет папку копии целиком; если файлы создала оболочка и приложение не может их стереть, пробует через ADB/root. */
    suspend fun delete(c: BkCopy): Boolean = withContext(Dispatchers.IO) {
        var ok = runCatching { c.dir.deleteRecursively() }.getOrDefault(false)
        if (!ok && c.dir.exists() && Adb.isConnected) { runCatching { Adb.shell("rm -rf '" + c.dir.absolutePath.replace("'", "'\\''") + "'", 60_000) }; ok = !c.dir.exists() }
        c.dir.parentFile?.let { pd -> if (pd.isDirectory && pd.list().isNullOrEmpty()) pd.delete() }
        ok
    }
}

/** Пункты меню строки приложения (внутри DropdownMenu). */
@Composable
fun AppMenuItems(p: FmPane, f: FmItem, other: FmPane, copy: Boolean = true, close: () -> Unit) {
    val pkg = AppsFs.pkgOf(f.name) ?: return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    @Composable fun item(l: Int, run: () -> Unit) = DropdownMenuItem({ Text(stringResource(l), fontSize = 14.sp) }, { close(); run() })
    val bk = AppsFs.copyOfName(f.name)
    if (bk != null) {
        // сохранённая копия APK: установить, копировать (экспорт), удалить навсегда
        item(R.string.ap_install_copy) { scope.launchSay { AppActions.installCopy(bk) } }
        if (copy) item(R.string.fm_copy) { Fm.copyMove(false, p, other, listOf(f.name)) }
        item(R.string.ap_forget) {
            Fm.dlg = FmDlg.AppAsk(R.string.ap_forget, Fm.str(R.string.ap_forget_q, f.name.substringBefore(" ·")), R.string.ap_forget) {
                if (AppCopies.delete(bk)) { AppActions.refresh(); Fm.str(R.string.ap_done) } else Fm.str(R.string.ap_copy_gone)
            }
        }
        return
    }
    val removed = !AppActions.isInstalled(pkg)
    val system = AppActions.isSystem(pkg)
    val enabled = AppActions.isEnabled(pkg)
    val label = f.name.substringBefore(" (")
    if (removed) item(R.string.ap_reinstall) { scope.launchSay { AppActions.reinstall(pkg) } }
    else {
        item(R.string.ap_launch) { AppActions.open(pkg)?.let { Fm.say(it) } }
        item(R.string.ap_stop) { scope.launchSay { AppActions.stop(pkg) } }
        item(if (enabled) R.string.ap_disable else R.string.ap_enable) { scope.launchSay { AppActions.setEnabled(pkg, !enabled) } }
        item(R.string.ap_clear_cache) {
            Fm.dlg = FmDlg.AppAsk(R.string.ap_clear_cache, Fm.str(R.string.ap_clear_cache_q, label), R.string.ap_clear_cache) { AppActions.clearCache(pkg).second }
        }
        item(R.string.ap_clear_data) {
            Fm.dlg = FmDlg.AppAsk(R.string.ap_clear_data, Fm.str(R.string.ap_clear_data_q, label), R.string.ap_clear_data) { AppActions.clearData(pkg).second }
        }
    }
    item(R.string.ap_info) { AppActions.info(pkg) }
    if (copy) item(R.string.fm_copy) { Fm.copyMove(false, p, other, listOf(f.name)) }
    if (!removed) item(R.string.ap_uninstall) {
        if (system) Fm.dlg = FmDlg.AppConfirm(Fm.str(R.string.ap_warn_system, f.name.substringBefore(" (")), pkg) else scope.launchSay { AppActions.uninstall(pkg, false) }
    }
    item(R.string.ap_data) {
        val d = AppActions.dataDir(pkg)
        if (File(d).isDirectory) { Fm.setSource(other, "local"); Fm.load(other, d) } else Fm.say(Fm.str(R.string.ap_no_data))
    }
}

/** Пункты меню для нескольких выделенных приложений (пакетные действия с общим подтверждением). */
@Composable
fun AppBatchMenuItems(p: FmPane, names: List<String>, other: FmPane, close: () -> Unit) {
    @Composable fun item(l: Int, act: String) = DropdownMenuItem({ Text(stringResource(l), fontSize = 14.sp) }, { close(); Fm.dlg = FmDlg.AppBatch(act, l, names, other) })
    Text(stringResource(R.string.fm_selected, names.size), Modifier.padding(horizontal = 12.dp, vertical = 2.dp), fontSize = 11.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
    item(R.string.ap_disable, "disable")
    item(R.string.ap_enable, "enable")
    item(R.string.ap_stop, "stop")
    item(R.string.ap_clear_cache, "cache")
    item(R.string.ap_export, "export")
    item(R.string.ap_uninstall, "uninstall")
    if (names.any { AppsFs.copyOfName(it) != null }) item(R.string.ap_forget, "forget")
}

private fun kotlinx.coroutines.CoroutineScope.launchSay(block: suspend () -> String) { AppActions.bgScope.launch { Fm.say(runCatching { block() }.getOrElse { it.message ?: "?" }) } }

/** Подтверждение удаления системного приложения (понятное предупреждение, выполняется через ADB или root). */
@Composable
fun AppConfirmDlg(d: FmDlg.AppConfirm, close: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LAlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.ap_uninstall)) }, text = { Text(d.text, fontSize = 14.sp) },
        confirmButton = { TextButton({ close(); scope.launchSay { AppActions.uninstall(d.pkg, true) } }) { Text(stringResource(R.string.ap_uninstall)) } },
        dismissButton = { TextButton(close) { Text(stringResource(R.string.btn_cancel)) } })
}

/** Общее окно подтверждения опасного действия над приложением (очистка кэша и данных, удаление копии навсегда). */
@Composable
fun AppAskDlg(d: FmDlg.AppAsk, close: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LAlertDialog(onDismissRequest = close, title = { Text(stringResource(d.title)) }, text = { Text(d.text, fontSize = 14.sp) },
        confirmButton = { TextButton({ close(); scope.launchSay { d.run() } }) { Text(stringResource(d.ok)) } },
        dismissButton = { TextButton(close) { Text(stringResource(R.string.btn_cancel)) } })
}

/** Пакетное действие: подтверждение со списком, ход работы, сводка «Выполнено N из M» и причины отказов. */
@Composable
fun AppBatchDlg(d: FmDlg.AppBatch, close: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var phase by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    var step by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    var okN by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    val fails = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val total = d.names.size
    val dest = androidx.compose.runtime.remember { if (d.other.src == "local" && !d.other.root && d.other.path.startsWith("/")) d.other.path else "/sdcard/Download" }
    val title = stringResource(d.label)
    fun start() {
        phase = 1
        scope.launch {
            for ((i, n) in d.names.withIndex()) {
                step = i + 1
                val label = n.substringBefore(" (")
                val pkg = AppsFs.pkgOf(n)
                val r: Pair<Boolean, String> = withContext(Dispatchers.IO) {
                    runCatching {
                        val bk = AppsFs.copyOfName(n)
                        when {
                            pkg == null -> false to "?"
                            d.action == "forget" -> if (bk == null) false to Fm.str(R.string.ap_err_not_installed) else (AppCopies.delete(bk) to Fm.str(R.string.ap_done))
                            d.action == "export" -> {
                                val dir = File(dest).also { it.mkdirs() }
                                var out = File(dir, n)
                                var k = 2
                                while (out.exists()) { out = File(dir, n.substringBeforeLast('.') + " ($k)." + n.substringAfterLast('.')); k++ }
                                val path = if (bk != null) "apps://x/" + AppCopies.PREFIX + bk.id else "apps://x/$pkg"
                                AppsFs().copyTo(path, out)
                                true to out.name
                            }
                            bk != null -> false to Fm.str(R.string.ap_err_copy)
                            else -> AppActions.runOne(d.action, pkg)
                        }
                    }.getOrElse { false to (it.message ?: "?") }
                }
                if (r.first) okN++ else fails += "$label: ${r.second}"
            }
            AppActions.refresh()
            if (d.action == "export") Fm.panes.forEach { pp -> if (pp.src == d.other.src && pp.path == d.other.path) Fm.load(pp, remember = false) }
            phase = 2
        }
    }
    LAlertDialog(
        onDismissRequest = { if (phase != 1) close() },
        title = { Text(title) },
        text = {
            androidx.compose.foundation.layout.Column {
                when (phase) {
                    0 -> {
                        Text(stringResource(R.string.ap_batch_q, title, total), fontSize = 14.sp)
                        d.names.take(6).forEach { Text("• " + it.substringBefore(" ("), fontSize = 12.sp, maxLines = 1) }
                        if (total > 6) Text(stringResource(R.string.ap_batch_more, total - 6), fontSize = 12.sp)
                        if (d.action == "export") Text(stringResource(R.string.ap_batch_dest, dest), fontSize = 12.sp)
                    }
                    1 -> {
                        Text(stringResource(R.string.ap_batch_run, step, total), fontSize = 14.sp)
                        ThemedProgress({ step.toFloat() / total }, Modifier.fillMaxWidth())
                    }
                    else -> {
                        Text(stringResource(R.string.ap_batch_result, okN, total), fontSize = 15.sp)
                        fails.take(8).forEach { Text(it, fontSize = 12.sp, maxLines = 2) }
                        if (fails.size > 8) Text(stringResource(R.string.ap_batch_more, fails.size - 8), fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            when (phase) {
                0 -> TextButton({ start() }) { Text(stringResource(R.string.btn_run)) }
                1 -> {}
                else -> TextButton(close) { Text(stringResource(R.string.btn_close)) }
            }
        },
        dismissButton = { if (phase == 0) TextButton(close) { Text(stringResource(R.string.btn_cancel)) } },
    )
}
