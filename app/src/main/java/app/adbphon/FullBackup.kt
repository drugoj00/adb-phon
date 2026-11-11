@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.adbphon

import android.content.Context
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Описание сохранённой полной копии (из meta.json в папке копии). */
data class FullCopy(
    val dir: String, val pkg: String, val label: String, val versionName: String, val versionCode: Long,
    val time: String, val mode: String, val parts: List<String>, val notes: List<String>, val sizeKb: Long,
)

/**
 * Полная копия приложения вместе с данными. Лежит в /sdcard/ADB-phon/Backups/<пакет>/<дата_время>/.
 * С root (su): APK, /data/data и /data/user_de приложения, внешние данные и obb: всё через tar.
 * Через ADB без root: APK, внешние данные и obb через tar, внутренние данные через системное резервное копирование (bu backup,
 * формат .ab), если приложение разрешает (с Android 12 для приложений с targetSdk 31 и выше внутренние данные без root недоступны).
 * Системное окно подтверждения резервной копии программа нажимает сама (uiautomator и input tap).
 */
object FullBackup {
    const val BASE = "/sdcard/ADB-phon/Backups"
    private const val UI = "/sdcard/.adbphon_ui.xml"
    private const val LONG = 3_600_000L

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private fun clean(s: String) = s.replace(Regex("[\"'\\\\\\n\\r]"), " ").trim()

    private suspend fun size(path: String): Long =
        runCatching { Adb.shell("stat -c %s ${q(path)} 2>/dev/null || echo 0").trim().toLongOrNull() ?: 0L }.getOrDefault(0L)

    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /**
     * Выполняет команду bu (backup или restore), которой нужно подтверждение в системном окне. Сама команда идёт в обычном
     * потоке оболочки и в конце создаёт файл-флаг (поток иногда не закрывается сам), а параллельно программа раз в секунду
     * смотрит экран (uiautomator) и нажимает кнопку подтверждения. Возвращает true, если флаг появился до истечения времени.
     */
    private suspend fun runWithConfirm(cmd: String, flag: String, timeoutMs: Long): Boolean {
        val button = Regex("""<node[^>]*resource-id="com\.android\.backupconfirm:id/button_allow"[^>]*bounds="\[(\d+),(\d+)]\[(\d+),(\d+)]"""")
        runCatching { Adb.shell("rm -f ${q(flag)}") }
        // старое окно подтверждения хранит прежний токен и «съедает» нажатие: перед новым запуском закрываем приложение окна
        runCatching { Adb.shell("am force-stop com.android.backupconfirm") }
        bg.launch { runCatching { Adb.shell("$cmd; echo 1 > ${q(flag)}", timeoutMs + 30_000) } }
        val end = System.currentTimeMillis() + timeoutMs
        var finished = false
        while (System.currentTimeMillis() < end) {
            if (size(flag) > 0) { finished = true; break }
            val xml = runCatching { Adb.shell("uiautomator dump $UI >/dev/null 2>&1; cat $UI 2>/dev/null", 30_000) }.getOrDefault("")
            val m = button.find(xml)
            if (m != null) {
                val x = (m.groupValues[1].toInt() + m.groupValues[3].toInt()) / 2
                val y = (m.groupValues[2].toInt() + m.groupValues[4].toInt()) / 2
                runCatching { Adb.shell("input tap $x $y") }
                delay(2500)
            }
            delay(600)
        }
        runCatching { Adb.shell("rm -f ${q(flag)}") }
        return finished
    }

    private suspend fun prop(cmd: String) = runCatching { Adb.shell(cmd).trim() }.getOrDefault("")

    /** Копия одного приложения. [step] получает короткие сообщения о ходе работы. Возвращает итоговую строку (что вошло в копию). */
    suspend fun backup(ctx: Context, a: AppEntry, step: (String) -> Unit): String {
        val root = Adb.isRoot
        val pkg = a.pkg
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val dir = "$BASE/$pkg/$ts"
        Adb.shell("mkdir -p ${q(dir)}")
        val parts = mutableListOf<String>()
        val notes = mutableListOf<String>()

        step(ctx.getString(R.string.fbk_step_apk, a.label))
        val paths = Adb.shell("pm path $pkg").lines().map { it.removePrefix("package:").trim() }.filter { it.endsWith(".apk") }
        for (p in paths) Adb.shell("cp ${q(p)} ${q(dir + "/" + p.substringAfterLast('/'))}", 120_000)
        if (paths.isNotEmpty()) parts += "apk"

        val dump = Adb.shell("dumpsys package $pkg", 30_000)
        val versionName = Regex("versionName=(\\S+)").find(dump)?.groupValues?.get(1) ?: a.version
        val versionCode = Regex("versionCode=(\\d+)").find(dump)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val targetSdk = Regex("targetSdk=(\\d+)").find(dump)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val allowBackup = Regex("flags=\\[[^\\]]*ALLOW_BACKUP").containsMatchIn(dump)
        val debuggable = Regex("flags=\\[[^\\]]*DEBUGGABLE").containsMatchIn(dump)
        val sdk = prop("getprop ro.build.version.sdk").toIntOrNull() ?: 0

        step(ctx.getString(R.string.fbk_step_ext, a.label))
        if (Adb.shell("[ -d /sdcard/Android/data/$pkg ] && echo Y").trim() == "Y") {
            val out = Adb.shell("tar -cf ${q("$dir/ext.tar")} -C /sdcard/Android/data $pkg 2>&1 | tail -2", LONG)
            if (size("$dir/ext.tar") > 0) parts += "ext" else notes += "ext: " + out.take(120)
        }
        if (Adb.shell("[ -d /sdcard/Android/obb/$pkg ] && echo Y").trim() == "Y") {
            val out = Adb.shell("tar -cf ${q("$dir/obb.tar")} -C /sdcard/Android/obb $pkg 2>&1 | tail -2", LONG)
            if (size("$dir/obb.tar") > 0) parts += "obb" else notes += "obb: " + out.take(120)
        }

        step(ctx.getString(R.string.fbk_step_data, a.label))
        if (root) {
            Adb.shell("am force-stop $pkg")
            val out = Adb.shell(
                "cd /data/data/$pkg && ls -A | grep -v -x -E 'cache|code_cache|lib' > ${q("$dir/.list")}; tar -cf ${q("$dir/internal.tar")} -T ${q("$dir/.list")} 2>&1 | tail -2; rm -f ${q("$dir/.list")}", LONG,
            )
            if (size("$dir/internal.tar") > 0) parts += "internal" else notes += "internal: " + out.take(120)
            if (Adb.shell("[ -d /data/user_de/0/$pkg ] && echo Y").trim() == "Y") {
                Adb.shell("cd /data/user_de/0/$pkg && ls -A | grep -v -x -E 'cache|code_cache|lib' > ${q("$dir/.list")}; tar -cf ${q("$dir/internal_de.tar")} -T ${q("$dir/.list")} 2>&1 | tail -2; rm -f ${q("$dir/.list")}", LONG)
                if (size("$dir/internal_de.tar") > 0) parts += "internal_de"
            }
        } else if (!allowBackup) {
            notes += "no_backup"
        } else if (sdk >= 31 && targetSdk >= 31 && !debuggable) {
            notes += "sdk12"
        } else {
            val ok = runWithConfirm("bu backup -noapk -noobb -noshared -nosystem $pkg | cat > ${q("$dir/data.ab")}", "$dir/.done", 240_000)
            val sz = size("$dir/data.ab")
            if (ok && sz > 100) parts += "ab" else {
                notes += if (!ok) "ab_timeout" else "ab_empty"
                Adb.shell("rm -f ${q("$dir/data.ab")}")
            }
        }

        val mode = if (root) "root" else "adb"
        val json = JSONObject().put("pkg", pkg).put("label", clean(a.label)).put("versionName", versionName).put("versionCode", versionCode)
            .put("time", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())).put("mode", mode)
            .put("parts", org.json.JSONArray(parts)).put("notes", org.json.JSONArray(notes)).toString()
        // в команде оболочки только ASCII: библиотека ADB считает длину в символах, а не в байтах, и ломается на кириллице
        val ascii = json.map { if (it.code < 128) it.toString() else "\\u" + "%04x".format(it.code) }.joinToString("")
        Adb.shell("printf '%s' ${q(ascii)} > ${q("$dir/meta.json")}")
        return ctx.getString(R.string.fbk_done_one, a.label, partsText(ctx, parts), notesText(ctx, notes))
    }

    private fun partsText(ctx: Context, parts: List<String>): String =
        parts.map {
            when (it) {
                "apk" -> ctx.getString(R.string.fbk_p_apk)
                "ext" -> ctx.getString(R.string.fbk_p_ext)
                "obb" -> "obb"
                "internal", "internal_de" -> ctx.getString(R.string.fbk_p_internal)
                "ab" -> ctx.getString(R.string.fbk_p_ab)
                else -> it
            }
        }.distinct().joinToString(", ")

    private fun notesText(ctx: Context, notes: List<String>): String {
        if (notes.isEmpty()) return ""
        val t = notes.map {
            when {
                it == "no_backup" -> ctx.getString(R.string.fbk_n_no_backup)
                it == "sdk12" -> ctx.getString(R.string.fbk_n_sdk12)
                it == "ab_timeout" -> ctx.getString(R.string.fbk_n_ab_timeout)
                it == "ab_empty" -> ctx.getString(R.string.fbk_n_ab_empty)
                it == "need_root" -> ctx.getString(R.string.fbk_n_need_root)
                it == "no_data_dir" -> ctx.getString(R.string.fbk_n_no_data_dir)
                it == "need_root" -> ctx.getString(R.string.fbk_n_need_root)
                it == "no_data_dir" -> ctx.getString(R.string.fbk_n_no_data_dir)
                else -> it
            }
        }
        return " (" + t.joinToString("; ") + ")"
    }

    /** Все сохранённые копии, новые сверху. */
    suspend fun list(): List<FullCopy> {
        val out = Adb.shell(
            "for d in $BASE/*/*/; do [ -f \"\${d}meta.json\" ] || continue; echo \"@@\$d\"; cat \"\${d}meta.json\"; echo; du -sk \"\$d\" | cut -f1; done 2>/dev/null",
            60_000,
        )
        val res = ArrayList<FullCopy>()
        for (block in out.split("@@").drop(1)) {
            val lines = block.lines()
            val dir = lines.firstOrNull()?.trim()?.trimEnd('/') ?: continue
            val json = lines.drop(1).firstOrNull { it.trim().startsWith("{") } ?: continue
            val kb = lines.drop(1).lastOrNull { it.trim().all { c -> c.isDigit() } && it.isNotBlank() }?.trim()?.toLongOrNull() ?: 0L
            runCatching {
                val o = JSONObject(json)
                fun arr(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                res += FullCopy(dir, o.getString("pkg"), o.optString("label"), o.optString("versionName"), o.optLong("versionCode"),
                    o.optString("time"), o.optString("mode"), arr("parts"), arr("notes"), kb)
            }
        }
        return res.sortedByDescending { it.dir.substringAfterLast('/') }
    }

    suspend fun delete(c: FullCopy) { Adb.shell("rm -rf ${q(c.dir)}", 120_000) }

    /** Восстановление копии: установка APK при необходимости, затем данные. Возвращает итоговую строку. */
    suspend fun restore(ctx: Context, c: FullCopy, step: (String) -> Unit): String {
        val root = Adb.isRoot
        val pkg = c.pkg
        val dir = c.dir
        val done = mutableListOf<String>()
        val notes = mutableListOf<String>()

        step(ctx.getString(R.string.fbk_rs_apk, c.label))
        val cur = Adb.shell("dumpsys package $pkg | grep -m1 versionCode=", 30_000)
        val curCode = Regex("versionCode=(\\d+)").find(cur)?.groupValues?.get(1)?.toLongOrNull()
        if ("apk" in c.parts && (curCode == null || curCode != c.versionCode)) {
            val tmp = "/data/local/tmp/adbphon-restore"
            Adb.shell("rm -rf $tmp; mkdir -p $tmp; cp ${q(dir)}/*.apk $tmp/", 120_000)
            val files = Adb.shell("ls $tmp/*.apk").lines().map { it.trim() }.filter { it.endsWith(".apk") }
            suspend fun install(flags: String) =
                if (files.size == 1) Adb.shell("pm install $flags --user 0 ${files[0]}", LONG)
                else Adb.shell("pm install-multiple $flags --user 0 ${files.joinToString(" ")}", LONG)
            var r = install("-r")
            if (r.contains("DOWNGRADE", true)) r = install("-r -d")
            Adb.shell("rm -rf $tmp")
            if (r.contains("Success")) done += "apk" else { notes += "install: " + r.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(150); return ctx.getString(R.string.fbk_rs_fail, c.label, notes.joinToString("; ")) }
        }

        step(ctx.getString(R.string.fbk_rs_data, c.label))
        Adb.shell("am force-stop $pkg")
        if ("internal" in c.parts) {
            if (!root) notes += "need_root" else {
                val uid = Adb.shell("stat -c %u /data/data/$pkg 2>/dev/null").trim()
                if (uid.isEmpty()) notes += "no_data_dir" else {
                    Adb.shell("find /data/data/$pkg -mindepth 1 -maxdepth 1 ! -name lib -exec rm -rf {} + ; tar -xf ${q("$dir/internal.tar")} -C /data/data/$pkg && chown -R $uid:$uid /data/data/$pkg; /system/bin/restorecon -RF /data/data/$pkg 2>/dev/null || restorecon -RF /data/data/$pkg", LONG)
                    done += "internal"
                    if ("internal_de" in c.parts) {
                        val ude = Adb.shell("stat -c %u /data/user_de/0/$pkg 2>/dev/null").trim().ifEmpty { uid }
                        Adb.shell("find /data/user_de/0/$pkg -mindepth 1 -maxdepth 1 ! -name lib -exec rm -rf {} + ; tar -xf ${q("$dir/internal_de.tar")} -C /data/user_de/0/$pkg && chown -R $ude:$ude /data/user_de/0/$pkg; /system/bin/restorecon -RF /data/user_de/0/$pkg 2>/dev/null || restorecon -RF /data/user_de/0/$pkg", LONG)
                    }
                }
            }
        }
        if ("ab" in c.parts) {
            val ok = runWithConfirm("cat ${q("$dir/data.ab")} | bu restore", "$dir/.done", 240_000)
            if (ok) done += "ab" else notes += "ab_timeout"
        }
        if ("ext" in c.parts) {
            Adb.shell("tar -xf ${q("$dir/ext.tar")} -C /sdcard/Android/data 2>&1 | tail -2", LONG)
            done += "ext"
        }
        if ("obb" in c.parts) {
            Adb.shell("tar -xf ${q("$dir/obb.tar")} -C /sdcard/Android/obb 2>&1 | tail -2", LONG)
            done += "obb"
        }
        return ctx.getString(R.string.fbk_rs_done, c.label, partsText(ctx, done), notesText(ctx, notes))
    }
}

private fun mb(kb: Long): String = if (kb >= 1024) "%.1f".format(Locale.US, kb / 1024.0) + " MB" else "$kb KB"

/** Окно «Копии с данными»: список по приложениям и датам, восстановление и удаление. */
@Composable
fun FullBackupsDialog(onClose: () -> Unit, onSettings: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val t = LocalTh.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<FullCopy>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var confirmRestore by remember { mutableStateOf<FullCopy?>(null) }

    LaunchedEffect(tick) {
        loaded = false
        items = runCatching { FullBackup.list() }.getOrDefault(emptyList())
        loaded = true
    }

    fun run(block: suspend () -> String) {
        if (busy) return
        scope.launch {
            busy = true
            status = try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { ctx.getString(R.string.fbk_failed) + ": " + (e.message ?: "") }
            busy = false
            tick++
        }
    }

    confirmRestore?.let { c ->
        LAlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text(stringResource(R.string.fbk_restore_title, c.label)) },
            text = { Text(stringResource(R.string.fbk_restore_warn, c.time)) },
            confirmButton = { androidx.compose.material3.TextButton({ confirmRestore = null; run { FullBackup.restore(ctx, c) { status = it } } }) { Text(stringResource(R.string.btn_yes)) } },
            dismissButton = { androidx.compose.material3.TextButton({ confirmRestore = null }) { Text(stringResource(R.string.btn_cancel)) } },
        )
    }

    HDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.88f).clip(RoundedCornerShape(24.dp)).background(t.bg).border(1.dp, t.border, RoundedCornerShape(24.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.fbk_title), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.fbk_hint), color = t.hint, fontSize = 12.sp)
            if (busy || status.isNotEmpty()) Text(status.ifEmpty { stringResource(R.string.ver_working) }, color = t.accent, fontSize = 14.sp)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(stringResource(R.string.close), true) { onClose() }
                if (onSettings != null) Chip(stringResource(R.string.fbk_settings), false) { onSettings() }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (loaded && items.isEmpty()) item { Text(stringResource(R.string.fbk_none), color = t.hint, fontSize = 13.sp) }
                items(items, key = { it.dir }) { c ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(t.tile).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.label.ifEmpty { c.pkg }, color = t.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${c.pkg} · ${c.versionName} · ${c.time} · ${mb(c.sizeKb)} · " + (if (c.mode == "root") "root" else "ADB"), color = t.hint, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val inc = c.parts.map {
                            when (it) { "apk" -> ctx.getString(R.string.fbk_p_apk); "ext" -> ctx.getString(R.string.fbk_p_ext); "internal", "internal_de" -> ctx.getString(R.string.fbk_p_internal); "ab" -> ctx.getString(R.string.fbk_p_ab); else -> it }
                        }.distinct().joinToString(", ")
                        Text(stringResource(R.string.fbk_includes, inc), color = t.sub, fontSize = 12.sp)
                        if (c.notes.isNotEmpty()) Text(c.notes.joinToString("; ") { n ->
                            when (n) { "no_backup" -> ctx.getString(R.string.fbk_n_no_backup); "sdk12" -> ctx.getString(R.string.fbk_n_sdk12); "ab_timeout" -> ctx.getString(R.string.fbk_n_ab_timeout); "ab_empty" -> ctx.getString(R.string.fbk_n_ab_empty); "need_root" -> ctx.getString(R.string.fbk_n_need_root); "no_data_dir" -> ctx.getString(R.string.fbk_n_no_data_dir); else -> n }
                        }, color = t.hint, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Chip(stringResource(R.string.fbk_restore), false) { if (!busy) confirmRestore = c }
                            Chip(stringResource(R.string.fbk_delete), false) { run { FullBackup.delete(c); ctx.getString(R.string.fbk_deleted) } }
                        }
                    }
                }
            }
        }
    }
}
