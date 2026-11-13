package app.adbphon

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Один способ установки APK: системный установщик, наша тихая установка через ADB/root, сторонний установщик, открыть как архив. */
class InstOpt(val id: String, val label: String, val hint: String?, val pkg: String?, val icon: ImageVector?, val ai: android.content.pm.ActivityInfo? = null, val rank: Int = 5, val enabled: Boolean = true, val recommended: Boolean = false) {
    /** «пакет/класс» для запуска именно этого действия приложения. */
    val component: String? get() = ai?.let { it.packageName + "/" + it.name }
}

/** Одно действие приложения (активность), умеющее открыть файл. */
class Handler(val ai: android.content.pm.ActivityInfo, val appLabel: String, val label: String)

/**
 * Выбор способа установки APK. Способы собираются при каждом нажатии (сторонние установщики находятся запросом к системе).
 * Если способ один, ставится сразу; запомненный выбор («Запомнить» в окне или в настройках) применяется без окна.
 */
object ApkInstall {
    private const val APK_MIME = "application/vnd.android.package-archive"
    /** Системные установщики Android: считаются одним пунктом «Системный», в «сторонние» не попадают. */
    /** Магазины приложений: в окне открытия APK их не показываем (Google Play, Galaxy Store и подобные предлагали «открыть в магазине»). */
    val STORES = setOf("com.android.vending", "com.google.android.finsky", "com.sec.android.app.samsungapps", "com.xiaomi.mipicks", "com.xiaomi.market", "com.huawei.appmarket", "com.aurora.store", "org.fdroid.fdroid", "com.amazon.venezia", "com.heytap.market", "com.oppo.market", "com.bbk.appstore", "com.apkpure.aegon", "cm.aptoide.pt", "com.lenovo.leos.appstore", "com.tencent.android.qqdownloader")
    private val SYSTEM = setOf("com.google.android.packageinstaller", "com.android.packageinstaller", "com.samsung.android.packageinstaller", "com.android.permissioncontroller", "com.google.android.permissioncontroller", "android")

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE)
    fun group(ext: String) = ext   // запоминание по расширению: .apk, .apks, .apkm, .xapk отдельно
    fun mode(ctx: Context, ext: String): String = sp(ctx).getString("inst_" + group(ext), "ask") ?: "ask"
    /** Запомнить выбор: для APK в «inst_*», для остальных типов в общем списке «Открывать как» (значок файла = значок выбранного приложения). */
    /** Запомненный выбор для расширения (id способа) или null. */
    fun saved(ctx: Context, ext: String): String? {
        if (ext == "apk" || isBundle(ext)) return mode(ctx, ext).takeIf { it != "ask" }
        val v = UiPrefs.openAs[openKey(ext)] ?: return null
        return if (v == "builtin") "builtin" else "cmp:$v"
    }

    /** Сбросить запомненный выбор для расширения. */
    fun forget(ctx: Context, ext: String) {
        if (ext == "apk" || isBundle(ext)) setMode(ctx, ext, "ask")
        else { UiPrefs.openAs.remove(openKey(ext)); sp(ctx).edit().remove("open_as_" + openKey(ext)).apply() }
    }

    fun remember(ctx: Context, ext: String, id: String) {
        if (ext == "apk" || isBundle(ext)) setMode(ctx, ext, id)
        else UiPrefs.setOpenAs(ctx, openKey(ext), if (id == "builtin") "builtin" else id.removePrefix("cmp:"))
    }
    fun setMode(ctx: Context, ext: String, v: String) {
        sp(ctx).edit().putString("inst_" + group(ext), v).apply()
        if (v == "ask") UiPrefs.instMode.remove(ext) else UiPrefs.instMode[ext] = v
    }

    private var sysPkgCache: String? = null
    private fun systemPkg(ctx: Context): String? = sysPkgCache ?: handlers(ctx, "apk").firstOrNull { it.ai.packageName in SYSTEM }?.ai?.packageName?.also { sysPkgCache = it }

    /**
     * Откуда брать значок файлов с этим расширением, если для него запомнена программа: «c:пакет/класс», «i:пакет» или null (рисуется значок типа).
     * Выбор «Встроенный» значок не меняет; «через ADB/root» даёт значок ADB-phon; системный установщик: его значок.
     */
    fun iconRef(ctx: Context, ext: String): String? {
        if (ext == "apk" || isBundle(ext)) {
            val m = UiPrefs.instMode[ext] ?: return null
            return when {
                m == "adb" -> "i:" + ctx.packageName
                m == "system" -> systemPkg(ctx)?.let { "i:$it" }
                m.startsWith("cmp:") -> "c:" + m.removePrefix("cmp:")
                else -> null
            }
        }
        val v = UiPrefs.openAs[openKey(ext)] ?: return null
        if (v == "builtin") return null
        return if (v.contains(Char(47))) "c:$v" else "i:$v"
    }

    fun mimeFor(ext: String): String = if (ext == "apk") APK_MIME else (android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream")

    /** Действия (активности) приложений, умеющие открывать этот тип файла: каждая отдельной записью, а не одна на пакет. */
    fun handlers(ctx: Context, ext: String): List<Handler> = runCatching {
        val pm = ctx.packageManager
        val mimes = if (ext == "apk") listOf(APK_MIME) else if (ext == "apks" || ext == "xapk" || ext == "apkm") listOf("application/x-apks", "application/vnd.apkm", "application/xapk-package-archive") else listOf(mimeFor(ext))
        val seen = LinkedHashMap<String, Handler>()
        for (m in mimes) {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse("content://x/installer.$ext"), m)
            for (r in pm.queryIntentActivities(i, PackageManager.MATCH_ALL)) {
                val ai = r.activityInfo ?: continue
                val k = ai.packageName + "/" + ai.name
                if (k !in seen) seen[k] = Handler(ai, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(ai.packageName, 0)).toString() }.getOrDefault(ai.packageName), r.loadLabel(pm).toString())
            }
        }
        seen.values.toList()
    }.getOrDefault(emptyList())

    /** Что делает действие по имени класса и подписи: установка, анализ, обзор архива, прочее (отправка, защита). */
    fun classify(h: Handler): Pair<Int, String?> {
        fun kind(t: String): Pair<Int, String?>? = when {
            "install" in t || "установ" in t || "packageinstaller" in t || "встанов" in t -> 2 to "inst"
            "explor" in t || "обзор" in t || "browse" in t || "огляд" in t -> 4 to "explore"
            "analy" in t || "анализ" in t || "appinfo" in t || "details" in t || "аналіз" in t -> 3 to "analyze"
            "filepicker" in t || "termux" in t || "send" in t || "share" in t || "protect" in t || "receiver" in t -> 7 to null
            else -> null
        }
        // сначала по имени класса (оно точнее подписи), затем по подписи
        return kind(h.ai.name.lowercase()) ?: kind(h.label.lowercase()) ?: (5 to null)
    }

    /** Приложение-установщик: просит REQUEST_INSTALL_PACKAGES или INSTALL_PACKAGES. */
    private fun isInstaller(ctx: Context, pkg: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val perms = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList().orEmpty()
        "android.permission.REQUEST_INSTALL_PACKAGES" in perms || "android.permission.INSTALL_PACKAGES" in perms
    }.getOrDefault(false)

    fun isBundle(ext: String) = ext == "apks" || ext == "xapk" || ext == "apkm"

    fun options(ctx: Context, ext: String, canAdb: Boolean): List<InstOpt> {
        val all = handlers(ctx, ext)
        val sys = all.firstOrNull { it.ai.packageName in SYSTEM }
        val isApk = ext == "apk"
        val bundle = isBundle(ext)
        val installGroup = isApk || bundle
        val out = ArrayList<InstOpt>()
        val adbLabel = ctx.getString(R.string.inst_adb)
        if (installGroup) {
            // наш способ показывается всегда; без ADB/root он серый с пояснением, нажатие открывает подключение
            // наш способ всегда доступен и рекомендован: тихо при ADB/root, иначе сессией с подтверждением системы
            val ours = InstOpt("adb", ctx.getString(R.string.inst_adb_phon), ctx.getString(if (canAdb) R.string.inst_quiet else R.string.inst_confirm), null, Icons.Filled.Terminal, null, 0, true, true)
            if (isApk) {
                val system = InstOpt("system", ctx.getString(R.string.inst_system), null, sys?.ai?.packageName, Icons.Filled.Android, null, 1, true, false)
                out += listOf(ours, system)
            } else out += ours          // системный установщик пакеты из нескольких частей не умеет: не предлагается
        }
        val cand: List<Handler> = if (!installGroup) all.filter { it.ai.packageName !in SYSTEM && it.ai.packageName != ctx.packageName } else {
            // окно установки: одно приложение = одна строка и только его действие «Установить»
            all.filter { it.ai.packageName !in SYSTEM && it.ai.packageName !in STORES && it.ai.packageName != ctx.packageName }.groupBy { it.ai.packageName }.mapNotNull { (pkg, hs) ->
                hs.firstOrNull { classify(it).second == "inst" } ?: if (isApk && hs.size == 1 && isInstaller(ctx, pkg)) hs.firstOrNull { classify(it).first == 5 } else null
            }.sortedBy { it.appLabel.lowercase() }
        }
        for (h in cand) {
            val (_, kind) = classify(h)
            val title = if (installGroup || h.label.isBlank() || h.label == h.appLabel) h.appLabel else h.appLabel + " — " + h.label
            val tag = when (kind) { "inst" -> ctx.getString(R.string.inst_kind_install); "analyze" -> ctx.getString(R.string.inst_kind_analyze); "explore" -> ctx.getString(R.string.inst_kind_explore); else -> if (installGroup) ctx.getString(R.string.inst_kind_install) else null }
            val hint = if (installGroup) tag else listOfNotNull(tag, h.ai.name.substringAfterLast(Char(46))).joinToString(" · ")
            out += InstOpt("cmp:" + h.ai.packageName + "/" + h.ai.name, title, hint, h.ai.packageName, null, h.ai, if (installGroup) 2 else 5)
        }
        if (Arc.kindOf("x.$ext") != null || ext == "apkm") out += InstOpt("archive", ctx.getString(R.string.arc_open_as_archive), null, null, Icons.Filled.FolderZip, null, 6)
        return if (installGroup) out.sortedBy { it.rank } else out
    }

    /**
     * Нажатие на APK/APKS/XAPK. Спрашивать включено (по умолчанию): окно «Установить через…» всегда, запомненный выбор не применяется.
     * Выключено: запомненный способ, иначе способ по умолчанию без окна (ADB-phon при ADB/root, иначе системный).
     * Файл в панели «ADB» (оболочка) ставится только нашим способом: файл забирается во временную папку.
     */
    fun start(p: FmPane, item: FmItem) {
        val ctx = Fm.sctx
        val adbPane = p.src == "adb"
        val canAdb = Adb.isConnected && (adbPane || (!FmIo.remote && p.src == "local"))
        var opts = options(ctx, item.ext, canAdb)
        if (adbPane) opts = opts.map { o -> if (o.id == "adb" || o.id == "archive") o else InstOpt(o.id, o.label, ctx.getString(R.string.via_adb_pane_only), o.pkg, o.icon, o.ai, o.rank, false, o.recommended) }
        val shown = opts.filter { it.id != "archive" }
        if (shown.isEmpty()) { Fm.openArchiveFor(p, item); return }
        if (!UiPrefs.askInstaller) {
            val saved = saved(ctx, item.ext)
            val o = saved?.let { s -> opts.firstOrNull { it.id == s && it.enabled } }
                ?: opts.firstOrNull { it.id == (if (Adb.isConnected || isBundle(item.ext)) "adb" else "system") && it.enabled }
                ?: shown.firstOrNull { it.enabled }
            if (o != null) { run(p, item, o); return }
        }
        Fm.dlg = FmDlg.InstallPick(p, item, opts)
    }

    /** Полный список способов открыть файл: для APK и пакетов из частей установщики, для остальных встроенный и приложения. */
    fun optionsFor(p: FmPane, item: FmItem): List<InstOpt> {
        val ctx = Fm.sctx; val e = item.ext
        if (e == "apk" || isBundle(e)) return options(ctx, e, Adb.isConnected && !FmIo.remote && p.src == "local")
        val builtin = openCat(e) != null || DocKind.of(e) != null
        val out = ArrayList<InstOpt>()
        if (builtin) out += InstOpt("builtin", ctx.getString(R.string.oa_builtin), null, null, Icons.Filled.Android)
        out += options(ctx, e, false).filter { it.ai != null }.sortedBy { it.label.lowercase() }
        if (Arc.kindOf(item.name) != null && !Arc.opensAsFolder(item.name)) out += InstOpt("archive", ctx.getString(R.string.arc_open_as_archive), null, null, Icons.Filled.FolderZip)
        return out
    }

    /** «Открыть через…» из меню: всегда полный список; галочка «Запомнить» отмечена, если для расширения уже есть выбор. */
    fun showPicker(p: FmPane, item: FmItem) {
        val opts = optionsFor(p, item)
        if (opts.isEmpty()) { Fm.openWith(p, item, null); return }
        Fm.dlg = FmDlg.InstallPick(p, item, opts)
    }

    /** Установленные магазины приложений (порядок как в списке STORES). */
    fun installedStores(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        return STORES.mapNotNull { pkg ->
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()?.let { pkg to it }
        }.distinctBy { it.second }
    }

    private fun storeIntent(store: String, pkg: String): android.content.Intent {
        val uri = when (store) {
            "com.sec.android.app.samsungapps" -> "samsungapps://ProductDetail/$pkg"
            "com.xiaomi.mipicks", "com.xiaomi.market" -> "mimarket://details?id=$pkg"
            "com.huawei.appmarket" -> "appmarket://details?id=$pkg"
            "com.amazon.venezia" -> "amzn://apps/android?p=$pkg"
            "org.fdroid.fdroid" -> "fdroid.app:$pkg"
            else -> "market://details?id=$pkg"
        }
        return android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri)).setPackage(store).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** «Посмотреть в маркете»: пакет APK-файла ищется в установленных магазинах; магазин один: открывается сразу, иначе выбор. */
    fun openMarket(p: FmPane, item: FmItem) {
        val ctx = Fm.sctx
        val pkg = if (p.src == "local") runCatching { ctx.packageManager.getPackageArchiveInfo(FmIo.join(p.path, item.name), 0)?.packageName }.getOrNull() else null
        if (pkg == null) { Fm.say(ctx.getString(R.string.inst_market_nopkg)); return }
        val stores = installedStores(ctx)
        when (stores.size) {
            0 -> Fm.say(ctx.getString(R.string.inst_market_none))
            1 -> launchStore(stores[0].first, pkg)
            else -> Fm.dlg = FmDlg.MarketPick(pkg, stores)
        }
    }

    fun launchStore(store: String, pkg: String) {
        runCatching { Fm.sctx.startActivity(storeIntent(store, pkg)) }.onFailure { Fm.say(Fm.sctx.getString(R.string.inst_market_fail)) }
    }

    /** Нужно ли предлагать выбор для этого файла (медиа, документы, прочие типы с известным MIME), кроме APK и архивов-папок. */
    fun chooseOpen(p: FmPane, item: FmItem): Boolean {
        val e = item.ext
        if (e == "apk" || isBundle(e)) return false
        val builtin = openCat(e) != null || DocKind.of(e) != null
        if (!builtin && android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(e) == null) return false
        val out = optionsFor(p, item)
        if (out.filter { it.id != "archive" }.size < 2) return false
        Fm.dlg = FmDlg.InstallPick(p, item, out)
        return true
    }

    fun run(p: FmPane, item: FmItem, o: InstOpt) {
        when {
            o.id == "market" -> openMarket(p, item)
            o.id == "archive" -> Fm.openArchiveFor(p, item)
            o.id == "builtin" -> Fm.openBuiltinForce(p, item)
            o.id == "adb" && isBundle(item.ext) -> Fm.installBundleViaAdb(p, item)
            o.id == "adb" -> Fm.installViaAdb(p, item)
            else -> Fm.openWith(p, item, o.component ?: o.pkg, if (o.id == "system" || item.ext == "apk") APK_MIME else null)
        }
    }
}

@Composable
fun InstallPickDialog(d: FmDlg.InstallPick, close: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val savedId = remember { ApkInstall.saved(ctx, d.item.ext) }
    var remember by remember { mutableStateOf(savedId != null) }
    val tv = Marq.tv
    LAlertDialog(
        onDismissRequest = close, title = { Text(stringResource(if (d.item.ext == "apk" || d.item.ext == "apks" || d.item.ext == "xapk" || d.item.ext == "apkm") R.string.inst_title else R.string.open_with_title), maxLines = 1) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.heightIn(max = 520.dp).barScroll(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val firstEnabled = d.opts.indexOfFirst { it.enabled && it.id != "archive" }
                    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
                    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
                    d.opts.forEachIndexed { idx, o ->
                        Row((if (idx == firstEnabled) Modifier.focusRequester(fr) else Modifier).fillMaxWidth().graphicsLayer { alpha = if (o.enabled) 1f else .55f }.then(if (o.recommended) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = .10f), RoundedCornerShape(10.dp)) else Modifier).clickable { if (o.id != "market" && (o.id != "archive" || d.item.ext == "apk" || ApkInstall.isBundle(d.item.ext))) { if (remember) ApkInstall.remember(ctx, d.item.ext, o.id) else if (savedId != null) ApkInstall.forget(ctx, d.item.ext) }; close(); ApkInstall.run(d.pane, d.item, o) }.heightIn(min = if (tv) 64.dp else 52.dp).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val sz = if (tv) 44.dp else 36.dp
                            val bmp = remember(o.id) { o.ai?.let { ai -> runCatching { val d = ai.loadIcon(ctx.packageManager); android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888).also { b -> d.setBounds(0, 0, 96, 96); d.draw(android.graphics.Canvas(b)) } }.getOrNull() } }
                            if (bmp != null) androidx.compose.foundation.Image(bmp.asImageBitmap(), null, Modifier.size(sz)) else if (o.pkg != null && o.id == "system") AppIcon(o.pkg, sz) else if (o.icon != null) Icon(o.icon, null, Modifier.size(if (tv) 44.dp else 36.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(o.label, fontSize = if (tv) 18.sp else 15.sp, maxLines = 2)
                                if (o.recommended) Text(stringResource(R.string.inst_recommended), fontSize = if (tv) 13.sp else 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                o.hint?.let { Text(it, fontSize = if (tv) 14.sp else 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f), maxLines = 5) }
                            }
                        }
                    }
                }
                if (!UiPrefs.askInstaller) Row(Modifier.fillMaxWidth().clickable { remember = !remember }.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(remember, { remember = it }); Text(stringResource(R.string.inst_remember_ext, d.item.ext), fontSize = if (tv) 16.sp else 13.sp)
                }
            }
        },
        confirmButton = { TextButton(close) { Text(stringResource(R.string.btn_cancel)) } },
    )
}
