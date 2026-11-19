package app.adbphon

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import app.adbphon.FloorText as Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 10.0: одно хранилище выбора установщика для всех типов пакетов (.apk, .apks, .xapk, .apkm) и во всех местах установки.
 * Значение: id варианта (adb, session, system, cmp:пакет/класс) или null («Спрашивать каждый раз»).
 * Старые настройки («Спрашивать, чем устанавливать» и запомненное по расширению) переносятся один раз при первом чтении.
 */
object InstallerStore {
    private const val KEY = "installer_choice"
    private var loaded = false
    var current by mutableStateOf<String?>(null)
        private set

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE)

    fun get(ctx: Context): String? {
        if (!loaded) load(ctx)
        return current
    }

    private fun load(ctx: Context) {
        val p = sp(ctx)
        if (p.contains(KEY)) current = InstallerChoice.normalize(p.getString(KEY, null))
        else {
            val legacy = listOf("apk", "apks", "xapk", "apkm").map { p.getString("inst_$it", null) }
            current = InstallerChoice.migrate(p.getBoolean("ask_installer", true), legacy)
            p.edit().putString(KEY, current ?: InstallerChoice.ASK).apply()
        }
        loaded = true
    }

    fun set(ctx: Context, id: String?) {
        val v = InstallerChoice.normalize(id)
        current = v; loaded = true
        sp(ctx).edit().putString(KEY, v ?: InstallerChoice.ASK).apply()
    }

    fun reset(ctx: Context) = set(ctx, null)

    /** Запомнить по правилам окна: галочка снята, значение не меняется. */
    fun afterPick(ctx: Context, picked: String, rememberChecked: Boolean) {
        val v = InstallerChoice.afterPick(get(ctx), picked, rememberChecked)
        if (v != current) set(ctx, v)
    }
}

/** Сборка строк окна «Установить через…»: что это и доступно ли сейчас (с причиной). */
object Installers {
    /** Тихая установка возможна для файла в этой панели «Файлов». */
    fun canQuietFm(p: FmPane): Boolean = Adb.isConnected && (p.src == "adb" || !FmIo.remote)

    /**
     * [count] файлов за раз; [parts] это части одной программы (копия с частями); [adbPane] файл лежит в панели ADB (оболочка);
     * [withArchive] добавить действие «Открыть как архив» («Файлы»).
     */
    fun rows(ctx: Context, ext: String, canQuiet: Boolean, count: Int = 1, parts: Boolean = false, adbPane: Boolean = false, withArchive: Boolean = false): List<InstOpt> {
        val e = if (ext == "apk" || ApkInstall.isBundle(ext)) ext else "apk"
        val bundle = ApkInstall.isBundle(e)
        val one = count <= 1
        val out = ArrayList<InstOpt>()
        // 1. ADB-phon без подтверждений: ADB или root
        out += InstOpt(InstallerChoice.QUIET, ctx.getString(R.string.inst_u_quiet),
            ctx.getString(if (!canQuiet) R.string.inst_u_quiet_d else if (Adb.isRoot) R.string.inst_u_quiet_root else R.string.inst_u_quiet_adb),
            null, Icons.Filled.Terminal, null, 0, canQuiet, canQuiet, if (canQuiet) null else ctx.getString(R.string.inst_u_r_noadb))
        // 2. ADB-phon с подтверждением Android: без ADB и root, по одному пакету (или части одной программы)
        val sesOk = !adbPane && (one || parts)
        out += InstOpt(InstallerChoice.SESSION, ctx.getString(R.string.inst_u_session), ctx.getString(R.string.inst_u_session_d),
            ctx.packageName, null, null, 1, sesOk, !canQuiet && sesOk,
            when { sesOk -> null; adbPane -> ctx.getString(R.string.via_adb_pane_only); else -> ctx.getString(R.string.inst_u_r_one) })
        // 3. Системный установщик Android: только один файл .apk
        val sysOk = !adbPane && !bundle && one && !parts
        out += InstOpt(InstallerChoice.SYSTEM, ctx.getString(R.string.inst_system), ctx.getString(R.string.inst_u_system_d),
            ApkInstall.systemPkg(ctx), Icons.Filled.Android, null, 2, sysOk, false,
            when { sysOk -> null; adbPane -> ctx.getString(R.string.via_adb_pane_only); bundle -> ctx.getString(R.string.inst_u_r_bundle); else -> ctx.getString(R.string.inst_u_r_one) })
        // 4. Сторонние установщики (App Manager, SAI, InstallerX и другие): по одному файлу
        val extOk = !adbPane && one && !parts
        ApkInstall.options(ctx, e, canQuiet).filter { it.id.startsWith("cmp:") }.forEach { o ->
            out += InstOpt(o.id, o.label, ctx.getString(R.string.inst_u_ext_d), o.pkg, null, o.ai, 3, extOk, false,
                when { extOk -> null; adbPane -> ctx.getString(R.string.via_adb_pane_only); else -> ctx.getString(R.string.inst_u_r_one) })
        }
        if (withArchive) out += InstOpt(InstallerChoice.ARCHIVE, ctx.getString(R.string.arc_open_as_archive), ctx.getString(R.string.inst_u_archive_d), null, Icons.Filled.FolderZip, null, 6, true)
        return out
    }

    fun choiceOpts(opts: List<InstOpt>): List<InstallerChoice.Opt> = opts.map { InstallerChoice.Opt(it.id, it.enabled) }

    /** Название запомненного варианта для настроек. */
    fun labelOf(ctx: Context, id: String?): String = when {
        id == null -> ctx.getString(R.string.inst_u_ask)
        id == InstallerChoice.QUIET -> ctx.getString(R.string.inst_u_quiet)
        id == InstallerChoice.SESSION -> ctx.getString(R.string.inst_u_session)
        id == InstallerChoice.SYSTEM -> ctx.getString(R.string.inst_system)
        else -> InstallerChoice.pkgOf(id)?.let { pkg -> runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) } ?: id
    }
}

/**
 * Единое окно «Установить через…»: варианты с пояснением, недоступные серым с причиной, галочка «Запомнить выбор» (включена).
 * Пульт: фокус на запомненном доступном или первом доступном, ОК выбирает, «Назад» закрывает без установки.
 * [settings]: окно из настроек, выбор сразу запоминается (галочки нет), выбрать можно и недоступный сейчас вариант.
 */
@Composable
fun InstallerDialog(opts: List<InstOpt>, fileName: String?, onPick: (InstOpt, Boolean) -> Unit, onDismiss: () -> Unit, settings: Boolean = false) {
    val ctx = LocalContext.current
    val tv = Marq.tv
    val saved = remember { InstallerStore.get(ctx) }
    val choice = remember(opts) { Installers.choiceOpts(opts) }
    val savedIdx = remember(opts) { InstallerChoice.indexOfSaved(choice, saved) }
    val savedOff = saved != null && !settings && (savedIdx < 0 || !opts[savedIdx].enabled)
    val focus = remember(opts) { if (settings && savedIdx >= 0) savedIdx else InstallerChoice.focusIndex(choice, saved) }
    var rememberIt by remember { mutableStateOf(true) }
    val titleSp = if (tv) 20.sp else 17.sp
    val hintSp = if (tv) 16.sp else 14.sp
    LAlertDialog(
        onDismissRequest = onDismiss, wide = tv,
        title = { Text(stringResource(if (settings) R.string.inst_u_default else R.string.inst_title), fontSize = if (tv) 22.sp else 20.sp) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                fileName?.let { Text(it, fontSize = hintSp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f)) }
                if (savedOff) Text(stringResource(R.string.inst_u_saved_off), fontSize = hintSp, color = MaterialTheme.colorScheme.error)
                Column(Modifier.fillMaxWidth().heightIn(max = if (tv) 420.dp else 460.dp).barScroll(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val fr = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
                    opts.forEachIndexed { idx, o ->
                        val can = o.enabled || settings
                        val mark = settings && idx == savedIdx
                        Row(
                            (if (idx == focus) Modifier.focusRequester(fr) else Modifier).fillMaxWidth()
                                .then(if (o.recommended || mark) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = .10f), RoundedCornerShape(10.dp)) else Modifier)
                                .clickable(enabled = can) { onPick(o, rememberIt) }
                                .graphicsLayer { alpha = if (o.enabled) 1f else .6f }
                                .heightIn(min = if (tv) 68.dp else 56.dp).padding(horizontal = 6.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val sz = if (tv) 44.dp else 36.dp
                            val bmp = remember(o.id) { o.ai?.let { ai -> runCatching { val d = ai.loadIcon(ctx.packageManager); android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888).also { b -> d.setBounds(0, 0, 96, 96); d.draw(android.graphics.Canvas(b)) } }.getOrNull() } }
                            if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size(sz))
                            else if (o.pkg != null && o.icon == null) AppIcon(o.pkg, sz)
                            else if (o.pkg != null && o.id == InstallerChoice.SYSTEM) AppIcon(o.pkg, sz)
                            else if (o.icon != null) Icon(o.icon, null, Modifier.size(sz), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(o.label, fontSize = titleSp, fontWeight = FontWeight.Medium)
                                if (mark) Text(stringResource(R.string.inst_u_now), fontSize = hintSp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                else if (o.recommended) Text(stringResource(R.string.inst_recommended), fontSize = hintSp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                o.hint?.let { Text(it, fontSize = hintSp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .75f)) }
                                o.reason?.let { Text(it, fontSize = hintSp, color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
                if (!settings) {
                    // одна строка с одним фокусом: ОК пультом и касание переключают галочку
                    Row(Modifier.fillMaxWidth().toggleable(value = rememberIt, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = { rememberIt = it }).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        BigCheckbox(rememberIt, null)
                        Text(stringResource(R.string.inst_u_remember), fontSize = if (tv) 18.sp else 16.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(stringResource(R.string.inst_u_remember_note), fontSize = hintSp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.btn_cancel), fontSize = if (tv) 18.sp else 16.sp) } },
    )
}

/** Пункт настроек «Установщик по умолчанию»: текущее значение, «Изменить» (то же окно) и «Сбросить» (спрашивать каждый раз). */
@Composable
fun InstallerDefaultSetting() {
    val ctx = LocalContext.current
    var dlg by remember { mutableStateOf(false) }
    remember { InstallerStore.get(ctx) }       // первое чтение: перенос старых настроек
    val now = InstallerStore.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HintTitle(stringResource(R.string.inst_u_default), stringResource(R.string.inst_u_default_hint))
        Text(Installers.labelOf(ctx, now), fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
        // 14.0: кнопки одной высоты при переносе подписи (EqBtnRow, Btn.kt)
        EqBtnRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ dlg = true }, Modifier.weight(1f).fillMaxHeight()) { Text(stringResource(R.string.inst_u_change), maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            OutlinedButton({ InstallerStore.reset(ctx) }, Modifier.weight(1f).fillMaxHeight(), enabled = now != null) { Text(stringResource(R.string.inst_u_reset), maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
    }
    if (dlg) {
        val opts = remember { Installers.rows(ctx, "apk", Adb.isConnected) }
        InstallerDialog(opts, null, onPick = { o, _ -> InstallerStore.set(ctx, o.id); dlg = false }, onDismiss = { dlg = false }, settings = true)
    }
}
