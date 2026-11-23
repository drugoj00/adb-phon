package app.adbphon

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 10.2: ADB-phon как установщик APK в системном выборе («Что использовать? Установщик пакетов / Install», «Открыть в системе» в X-plore и т.п.).
 * Другое приложение отдаёт файл (ACTION_VIEW или ACTION_INSTALL_PACKAGE, content:// или file://, тип application/vnd.android.package-archive
 * и пакеты из частей apks/xapk/apkm): файл при необходимости копируется во временную папку, затем открывается то же окно «Установка» с версиями,
 * что и в «Файлах» (имя, значок, версия установленной → новая, размер, подпись, предупреждение о понижении). «Установить»: при ADB или root
 * ставит ADB-phon без системных окон (с ходом установки), без них своей сессией PackageInstaller с подтверждением системы.
 * Окно «Установить через…» для этого входа не показывается: человек уже выбрал ADB-phon в системном списке. Неявных ACTION_VIEW на APK нет:
 * петля в самого себя невозможна (свой компонент исключён и из списка «сторонних установщиков»).
 */
object ApkIntake {
    class Pending(val file: File, val ext: String)

    /** Файл готов; [MainActivity] открывает вкладку «Файлы» и окно установки, затем сбрасывает. */
    var pending by mutableStateOf<Pending?>(null)

    private fun dir(ctx: Context) = File(ctx.cacheDir, "intake").apply { mkdirs() }

    /** Убрать временные копии: все старше [olderMs] (по умолчанию час); копия, которую ждёт окно, не трогается. */
    fun cleanup(ctx: Context, olderMs: Long = 60 * 60_000L) {
        val keep = pending?.file
        val now = System.currentTimeMillis()
        dir(ctx).listFiles()?.forEach { f -> if (f != keep && now - f.lastModified() > olderMs) f.delete() }
    }

    private fun displayName(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    /** Расширение пакета по имени, а если имя ничего не говорит, по типу (ACTION_VIEW с типом, но без «.apk» в имени). */
    fun extOf(name: String, mime: String?): String {
        val e = name.substringAfterLast('.', "").lowercase()
        if (e in setOf("apk", "apks", "xapk", "apkm")) return e
        return when (mime?.lowercase()) {
            "application/x-apks" -> "apks"
            "application/vnd.apkm" -> "apkm"
            "application/xapk-package-archive" -> "xapk"
            else -> "apk"
        }
    }

    /** Подготовить файл: читаемый file:// берётся как есть, остальное копируется в cacheDir/intake. */
    fun prepare(ctx: Context, uri: Uri, mime: String?): Pending {
        cleanup(ctx)
        val name = displayName(ctx, uri) ?: "app.apk"
        val ext = extOf(name, mime)
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            if (f.isFile && f.canRead()) return Pending(f, ext)
        }
        val safe = name.replace(Regex("[^\\w.\\- ]"), "_").let { if (it.substringAfterLast('.', "").lowercase() == ext) it else "$it.$ext" }
        val out = File(dir(ctx), System.currentTimeMillis().toString(36) + "_" + safe)
        ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
        if (out.length() == 0L) { out.delete(); error("empty") }
        return Pending(out, ext)
    }

    /** Открыть окно «Установка» (вызывает [MainActivity] после перехода на вкладку «Файлы»). */
    fun show(ctx: Context, p: Pending) {
        Fm.sctx = ctx.applicationContext
        val pane = FmPane(9, p.file.parentFile?.path ?: "/")
        pane.src = "local"
        val item = FmItem(p.file.name, false, p.file.length(), "")
        if (ApkInstall.isBundle(p.ext)) Fm.installBundleViaAdb(pane, item) else Fm.installViaAdb(pane, item)
    }
}

/**
 * 10.2: единое поведение «ADB-phon без ADB и root»: сначала окно «Установка» с версиями и сведениями о пакете, затем своя сессия PackageInstaller
 * (подтверждение даёт системный установщик). Раньше выбор «ADB-phon с подтверждением» из окон «Установить через…» (обновление программы и модуля,
 * откат версии, «Установка APK», копии, Меню приложений) отдавал файл сессии сразу, без окна. Окно рисуется в корне экрана ([InstallGateHost]).
 */
object InstallGate {
    var confirm by mutableStateOf<FmDlg.InstallConfirm?>(null)
    /** Наборы файлов, для которых окно «Установка» уже готовится или показано: повторный вызов (второе нажатие, пока на слабом устройстве читаются сведения) второе окно не открывает. */
    private val preparing = java.util.Collections.synchronizedSet(HashSet<String>())
    private fun fileKey(files: List<File>) = files.joinToString("|") { it.path }

    /** Окно закрыто («Отмена», касание вне окна, «Назад»): те же файлы можно открыть снова. */
    fun dismiss() { confirm = null; preparing.clear() }

    fun sessionWithInfo(ctx: Context, files: List<File>) {
        if (files.isEmpty()) return
        val fk = fileKey(files)
        // 22.11.2026 (для 18.0): повторный вызов для тех же файлов игнорируется; раньше второй вызов подменял окно уже нажатой «Установить» новым таким же окном
        if (!preparing.add(fk)) return
        Fm.scope.launch {
            var shown = false
            try {
                val app = ctx.applicationContext
                val key = withContext(Dispatchers.IO) { SessionInstall.guardKey(app, files) }
                if (InstallGuard.isActive(key)) { Fm.say(Fm.str(R.string.ii_busy)); return@launch }
                if (InstallGuard.recentSuccess(app, listOf(key)) != null) { Fm.say(Fm.str(R.string.ii_already_short)); return@launch }
                val afs = files.map { ApkFile(it.name, Uri.fromFile(it), it.length(), it) }
                val metas = withContext(Dispatchers.IO) { afs.associate { a -> a.name to runCatching { ApkInfo.readFor(app, a) }.getOrNull() } }
                if (confirm != null) return@launch
                confirm = FmDlg.InstallConfirm(afs, metas) { _ -> confirm = null; preparing.remove(fk); Fm.installFilesViaSession(files) }
                shown = true
            } finally { if (!shown) preparing.remove(fk) }
        }
    }
}

@androidx.compose.runtime.Composable
fun InstallGateHost() { InstallGate.confirm?.let { d -> InstallConfirmDialog(d) { InstallGate.dismiss() } } }

// ApkIntakeActivity (11.0, код 60) перенесена в ApkIntakeUi.kt: компактное окно вместо перехода в главный экран.
