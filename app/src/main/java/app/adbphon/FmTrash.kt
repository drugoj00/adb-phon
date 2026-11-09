package app.adbphon

import android.content.Context
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
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Настройки удаления и корзины файлового менеджера. Хранятся в общих настройках программы («adbphon»), поэтому входят в резервные копии настроек.
 * Подтверждение удаления: «Всегда» (по умолчанию), «Только безвозвратное» (удаление навсегда и очистка корзины), «Выключено».
 */
object FmDel {
    const val ALWAYS = 0
    const val PERM = 1
    const val OFF = 2
    var confirm by mutableIntStateOf(ALWAYS)
    /** Корзина файлового менеджера для телефона и карты памяти: включена по умолчанию. */
    var trashOn by mutableStateOf(true)
    /** Сколько дней хранить в корзине (0 = без ограничения). */
    var keepDays by mutableIntStateOf(30)
    /** Не класть в корзину элементы больше стольки мегабайт (0 = без ограничения). */
    var maxMb by mutableIntStateOf(0)

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        val p = sp(ctx)
        confirm = p.getInt("del_confirm", ALWAYS).coerceIn(ALWAYS, OFF)
        trashOn = p.getBoolean("ftrash_on", true)
        keepDays = p.getInt("ftrash_days", 30).coerceAtLeast(0)
        maxMb = p.getInt("ftrash_max_mb", 0).coerceAtLeast(0)
    }

    fun setConfirm(ctx: Context, v: Int) { confirm = v; sp(ctx).edit().putInt("del_confirm", v).apply() }
    fun setTrashOn(ctx: Context, v: Boolean) { trashOn = v; sp(ctx).edit().putBoolean("ftrash_on", v).apply() }
    fun setKeepDays(ctx: Context, v: Int) { keepDays = v; sp(ctx).edit().putInt("ftrash_days", v).apply() }
    fun setMaxMb(ctx: Context, v: Int) { maxMb = v; sp(ctx).edit().putInt("ftrash_max_mb", v).apply() }

    /** Нужно ли окно подтверждения: [permanent] = удаление навсегда (минуя корзину) или очистка корзины. */
    fun needAsk(permanent: Boolean): Boolean = when (confirm) { ALWAYS -> true; PERM -> permanent; else -> false }
}

/** Как будет удалено: [perm] = навсегда (хотя бы часть), [big] элементов больше лимита корзины, [none] элементов там, где корзины нет. */
class DelPlan(val perm: Boolean, val big: Int = 0, val none: Int = 0)

/**
 * Корзина файлового менеджера для телефона и карты памяти. У каждого тома своя скрытая папка «.adbphon-trash» в корне тома:
 * для каждого удалённого элемента подпапка «ГГГГММДД-ЧЧММСС-n» с самим элементом (под прежним именем) и файлом meta.json
 * {origPath, name, isDir, size, deletedAt, volume}. Перенос делается переименованием в пределах тома (мгновенно, без копирования).
 * Папка скрыта из списков, поиска и счётчиков; в ней лежит «.nomedia», чтобы галерея и плееры её не показывали.
 */
object FmTrash {
    const val DIR = ".adbphon-trash"
    enum class Why { OK, OFF, NOVOL, INSIDE, BIG }

    class FItem(val id: String, val dir: File, val vol: String, val name: String, val origPath: String, val isDir: Boolean, val size: Long, val deletedAt: Long) {
        val file: File get() = File(dir, name)
        /** Прежняя папка без начала пути тома (том показывается отдельно). */
        val folder: String get() = origPath.trimEnd('/').substringBeforeLast('/', "").removePrefix(vol).ifEmpty { "/" }
        val volLabel: String get() = if (vol == "/storage/emulated/0") Fm.str(R.string.fm_internal) else (Fm.vols.firstOrNull { it.path == vol }?.label ?: vol.substringAfterLast('/'))
    }

    /** Меняется при любой правке корзины: дерево и окно перечитываются. */
    var ver by mutableIntStateOf(0)
    /** Число и размер всего в корзине (-1: ещё не считали). */
    var count by mutableIntStateOf(-1)
    var total by mutableLongStateOf(0L)
    /** Элементы узла дерева «домика» по идентификатору строки. */
    val tk = HashMap<String, FItem>()
    /** Пути, которые не удалось перенести в корзину при текущем удалении. */
    val failed: MutableList<Pair<String, String>> = java.util.Collections.synchronizedList(ArrayList())

    fun canon(path: String): File {
        val f = File(path)
        val par = f.parentFile ?: return f
        return File(runCatching { par.canonicalPath }.getOrDefault(par.absolutePath), f.name)
    }

    /** Корень тома, где лежит путь: внутренняя память или карта (USB и прочее не поддерживается). */
    fun volumeOf(path: String): String? {
        val c = canon(path).path
        if (c.startsWith("/storage/emulated/0/")) return "/storage/emulated/0"
        val m = Regex("^/storage/([^/]+)/.+").find(c) ?: return null
        val n = m.groupValues[1]
        if (n == "emulated" || n == "self") return null
        return "/storage/$n"
    }

    fun roots(): List<String> =
        (listOf("/storage/emulated/0") + Fm.vols.filter { it.ok }.map { runCatching { File(it.path).canonicalPath }.getOrDefault(it.path) } +
            // карта, на которой корзина уже есть, находится и без списка томов (он заполняется позже)
            File("/storage").listFiles().orEmpty().filter { it.isDirectory && it.name != "emulated" && it.name != "self" && File(it, DIR).isDirectory }.map { it.path })
            .distinct().filter { File(it).isDirectory }

    /** Можно ли положить [f] в корзину; иначе причина. Вызывать вне главного потока (считает размер). */
    fun why(f: File): Why {
        if (!FmDel.trashOn) return Why.OFF
        val c = canon(f.path).path
        if (c.contains("/$DIR/") || c.endsWith("/$DIR")) return Why.INSIDE
        if (volumeOf(c) == null) return Why.NOVOL
        if (FmDel.maxMb > 0 && LocalOps.size(f) > FmDel.maxMb * 1_048_576L) return Why.BIG
        return Why.OK
    }

    /** Перенос [f] в корзину своего тома. null: готово, иначе текст ошибки (ничего не удалено). */
    @Volatile var forceFail = false   // только для проверки: имитирует сбой переноса в корзину
    fun moveIn(f: File, j: OpProgress?): String? {
        if (forceFail) return Fm.str(R.string.ft_err_move)
        val vol = volumeOf(f.path) ?: return Fm.str(R.string.ft_err_novol)
        val root = File(vol, DIR)
        if (!root.isDirectory && !root.mkdirs()) return Fm.str(R.string.fm_err_denied)
        runCatching { File(root, ".nomedia").let { if (!it.exists()) it.createNewFile() } }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var n = 1
        var dir: File
        do { dir = File(root, "$ts-$n"); n++ } while (dir.exists())
        if (!dir.mkdirs()) return Fm.str(R.string.fm_err_denied)
        val isDir = f.isDirectory
        val size = if (isDir) LocalOps.size(f) else f.length()
        val cnt = LocalOps.count(f)
        val meta = JSONObject().put("origPath", canon(f.path).path).put("name", f.name).put("isDir", isDir).put("size", size).put("deletedAt", System.currentTimeMillis()).put("volume", vol)
        try { File(dir, "meta.json").writeText(meta.toString()) } catch (e: Throwable) { dir.deleteRecursively(); return e.message ?: Fm.str(R.string.fm_err_denied) }
        j?.file(f.name)
        val target = File(dir, f.name)
        val ok = try { f.renameTo(target) } catch (e: Throwable) { false }
        // проверка после: исходного больше нет, в корзине есть; иначе всё откатывается и ничего не пропадает
        val moved = ok && FmIo.statLocal(target.path) != null && FmIo.statLocal(f.path) == null
        if (!moved) {
            // источник на месте (перенос не удался): запись корзины убирается, ничего не потеряно
            if (FmIo.statLocal(target.path) == null) { File(dir, "meta.json").delete(); dir.delete() }
            return Fm.str(R.string.ft_err_move)
        }
        repeat(cnt) { j?.item() }
        return null
    }

    private fun readItem(d: File, vol: String): FItem? = try {
        val m = JSONObject(File(d, "meta.json").readText())
        val name = m.getString("name")
        if (FmIo.statLocal(File(d, name).path) == null) null
        else FItem(d.name, d, vol, name, m.getString("origPath"), m.optBoolean("isDir"), m.optLong("size"), m.optLong("deletedAt"))
    } catch (e: Throwable) { null }

    fun listSync(): List<FItem> = roots().flatMap { vol ->
        File(vol, DIR).listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { readItem(it, vol) }
    }.sortedByDescending { it.deletedAt }

    suspend fun list(): List<FItem> = withContext(Dispatchers.IO) { listSync() }

    suspend fun refreshSum() {
        val l = list()
        count = l.size; total = l.sumOf { maxOf(it.size, 0L) }
    }

    /** Корзина изменилась: перечитать дерево, окно и счётчик; панели и узлы дерева тоже. */
    fun bump(reloadPanes: Boolean = false, vararg parents: String) {
        FmRootCfg.kids.remove("K|")
        parents.forEach { FmRootCfg.kids.remove("L|$it") }
        ver++
        if (reloadPanes) Fm.panes.forEach { pp -> if (pp.src == "local" && !pp.root) Fm.load(pp, remember = false) }
    }

    fun sumText(): String = when {
        count < 0 -> "…"
        count == 0 -> "0"
        else -> count.toString() + "  ·  " + fmSize(total)
    }

    private fun freeName(orig: File): File {
        val nm = orig.name
        val dot = nm.lastIndexOf('.')
        val (base, ext) = if (dot > 0 && !orig.isDirectory) nm.substring(0, dot) to nm.substring(dot) else nm to ""
        val tag = Fm.str(R.string.tr_restored_tag).trim('(', ')')
        var cand = File(orig.parentFile, "$base ($tag)$ext")
        var i = 2
        while (FmIo.statLocal(cand.path) != null) { cand = File(orig.parentFile, "$base ($tag $i)$ext"); i++ }
        return cand
    }

    /** Возврат в прежнюю папку (создаётся, если её нет); при занятом имени к нему добавляется «(восстановлено)». null: готово, иначе ошибка, а элемент остаётся в корзине. */
    suspend fun restore(it: FItem): String? = withContext(Dispatchers.IO) {
        val src = it.file
        if (FmIo.statLocal(src.path) == null) return@withContext Fm.str(R.string.tr_restore_fail)
        val orig = File(it.origPath)
        val parent = orig.parentFile ?: return@withContext Fm.str(R.string.tr_restore_fail)
        if (!parent.isDirectory && !parent.mkdirs()) return@withContext Fm.str(R.string.tr_restore_fail) + " " + Fm.str(R.string.fm_err_denied)
        val target = if (FmIo.statLocal(orig.path) != null) freeName(orig) else orig
        val ok = try { src.renameTo(target) } catch (e: Throwable) { false }
        // проверка после: на месте появился файл, в корзине его нет; только тогда запись корзины удаляется
        if (!ok || FmIo.statLocal(target.path) == null || FmIo.statLocal(src.path) != null) return@withContext Fm.str(R.string.tr_restore_fail)
        LocalOps.delete(it.dir, null)
        null
    }

    /** Удаление элемента из корзины навсегда. */
    suspend fun purge(it: FItem): String? = withContext(Dispatchers.IO) { if (LocalOps.delete(it.dir, null) || !it.dir.exists()) null else Fm.str(R.string.fm_err_denied) }

    /** Вся корзина навсегда (вместе с повреждёнными записями). */
    suspend fun clean(): String? = withContext(Dispatchers.IO) {
        var bad = false
        for (vol in roots()) File(vol, DIR).listFiles().orEmpty().filter { it.isDirectory }.forEach { d -> if (!LocalOps.delete(d, null) && d.exists()) bad = true }
        if (bad) Fm.str(R.string.fm_err_denied) else null
    }

    /** Автоочистка: из корзины уходит всё, что лежит дольше выбранного срока (младшее не трогается). */
    suspend fun autoClean(): Int {
        val days = FmDel.keepDays
        if (days <= 0) return 0
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        var n = 0
        for (it in list()) if (it.deletedAt in 1 until cutoff && purge(it) == null) n++
        if (n > 0) { refreshSum(); bump() }
        return n
    }

    /** Запуск автоочистки: при старте и дальше раз в сутки. */
    fun startAutoClean() {
        Fm.scope.launch {
            delay(8_000)
            while (true) {
                runCatching { autoClean() }
                runCatching { refreshSum() }
                delay(24 * 3_600_000L)
            }
        }
    }
}

/** Окно «Корзина файлов»: список (имя, прежняя папка, размер, дата, том) с «Восстановить» и «Удалить навсегда», очистка корзины с подтверждением. */
@Composable
fun FTrashDlg(close: () -> Unit, initConfirm: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) } // "": вся корзина; иначе id элемента
    var busy by remember { mutableStateOf(false) }
    val items = remember { mutableStateListOf<FmTrash.FItem>() }
    val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    suspend fun load() {
        loading = true
        val l = FmTrash.list()
        items.clear(); items.addAll(l)
        FmTrash.count = l.size; FmTrash.total = l.sumOf { maxOf(it.size, 0L) }
        loading = false
    }
    fun reload() { scope.launch { load() } }
    fun run(cf: String) {
        if (busy) return
        busy = true
        scope.launch {
            val e = if (cf.isEmpty()) FmTrash.clean() else items.firstOrNull { x -> x.id == cf }?.let { x -> FmTrash.purge(x) }
            busy = false; confirm = null
            if (e != null) msg = e else { msg = ""; Fm.say(ctx.getString(if (cf.isEmpty()) R.string.tr_cleaned else R.string.tr_deleted_forever)); FmTrash.bump(); reload() }
        }
    }
    fun ask(cf: String) { if (FmDel.needAsk(true)) confirm = cf else run(cf) }
    LaunchedEffect(Unit) { load(); if (initConfirm != null) ask(initConfirm) }
    LAlertDialog(
        wide = true, onDismissRequest = close,
        title = { Text(stringResource(R.string.ft_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (loading) stringResource(R.string.tr_counting) else if (items.isEmpty()) stringResource(R.string.tr_empty) else stringResource(R.string.tr_total, items.size, fmSize(items.sumOf { maxOf(it.size, 0L) })),
                        Modifier.weight(1f), fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                    TextButton({ ask("") }, enabled = !busy && !loading && items.isNotEmpty() && confirm == null) { Text(stringResource(R.string.tr_clean), fontSize = 13.sp) }
                }
                if (loading || busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text(stringResource(R.string.rt_loading), Modifier.padding(start = 8.dp), fontSize = 13.sp) }
                if (msg.isNotEmpty()) Text(msg, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                confirm?.let { cf ->
                    Text(if (cf.isEmpty()) stringResource(R.string.tr_confirm) else stringResource(R.string.tr_confirm_one, items.firstOrNull { x -> x.id == cf }?.name.orEmpty()), fontSize = 13.sp, color = Color(0xFFC62828))
                }
                Column(Modifier.weight(1f, fill = false).barScroll(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items.forEach { it ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(it.name, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                val sz = if (it.size >= 0 && !it.isDir) fmSize(it.size) else if (it.isDir) stringResource(R.string.cf_folder) + (if (it.size > 0) " " + fmSize(it.size) else "") else ""
                                val whenTxt = if (it.deletedAt > 0) dateFmt.format(Date(it.deletedAt)) else ""
                                Text(shortMiddle(it.folder, 38), fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                                Text(listOf(sz, whenTxt, it.volLabel).filter { s -> s.isNotEmpty() }.joinToString("  ·  "), fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                            }
                            IconButton({
                                if (busy) return@IconButton
                                busy = true
                                scope.launch {
                                    val e = FmTrash.restore(it)
                                    busy = false
                                    if (e != null) msg = e else { msg = ""; Fm.say(ctx.getString(R.string.tr_restored)); FmTrash.bump(true, File(it.origPath).parent.orEmpty()); reload() }
                                }
                            }, enabled = !busy) { Icon(Icons.Filled.Restore, stringResource(R.string.tr_restore), tint = MaterialTheme.colorScheme.primary) }
                            IconButton({ if (!busy) ask(it.id) }, enabled = !busy) { Icon(Icons.Filled.DeleteForever, stringResource(R.string.tr_perm), tint = Color(0xFFC62828)) }
                        }
                    }
                    Text(stringResource(R.string.ft_note), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
                }
            }
        },
        confirmButton = {
            if (confirm != null) TextButton({ run(confirm ?: return@TextButton) }, enabled = !busy && !loading) { Text(stringResource(if (confirm == "") R.string.tr_clean_yes else R.string.tr_perm_yes), color = Color(0xFFC62828)) }
        },
        dismissButton = { TextButton(if (confirm != null) ({ confirm = null }) else close) { Text(stringResource(if (confirm != null) R.string.btn_cancel else R.string.btn_close)) } },
    )
}

/** Настройки раздела «Файлы»: подтверждение удаления и корзина файлового менеджера. */
@Composable
fun FilesDeleteSection() {
    val ctx = LocalContext.current
    val fw = androidx.compose.ui.text.font.FontWeight.Medium
    Text(stringResource(R.string.ds_confirm), fontWeight = fw)
    EqChoices(listOf(
        EqChoice(stringResource(R.string.ds_c_always), FmDel.confirm == FmDel.ALWAYS) { FmDel.setConfirm(ctx, FmDel.ALWAYS) },
        EqChoice(stringResource(R.string.ds_c_perm), FmDel.confirm == FmDel.PERM) { FmDel.setConfirm(ctx, FmDel.PERM) },
        EqChoice(stringResource(R.string.ds_c_off), FmDel.confirm == FmDel.OFF) { FmDel.setConfirm(ctx, FmDel.OFF) },
    ), perRow = 1)
    val off = FmDel.confirm == FmDel.OFF
    Text(stringResource(if (off) R.string.ds_off_warn else R.string.ds_hint), fontSize = 12.sp, color = if (off) Color(0xFFC62828) else MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
    SettingSwitch(FmDel.trashOn, { FmDel.setTrashOn(ctx, it) }, stringResource(R.string.ft_switch), stringResource(R.string.ft_switch_hint))
    if (FmDel.trashOn) {
        Text(stringResource(R.string.ft_keep), fontWeight = fw)
        EqChoices(listOf(
            EqChoice(stringResource(R.string.ft_d7), FmDel.keepDays == 7) { FmDel.setKeepDays(ctx, 7) },
            EqChoice(stringResource(R.string.ft_d30), FmDel.keepDays == 30) { FmDel.setKeepDays(ctx, 30) },
            EqChoice(stringResource(R.string.ft_d90), FmDel.keepDays == 90) { FmDel.setKeepDays(ctx, 90) },
            EqChoice(stringResource(R.string.ft_inf), FmDel.keepDays == 0) { FmDel.setKeepDays(ctx, 0) },
        ), perRow = 2)
        Text(stringResource(R.string.ft_max), fontWeight = fw)
        EqChoices(listOf(
            EqChoice(stringResource(R.string.ft_inf), FmDel.maxMb == 0) { FmDel.setMaxMb(ctx, 0) },
            EqChoice(stringResource(R.string.ft_m500), FmDel.maxMb == 500) { FmDel.setMaxMb(ctx, 500) },
            EqChoice(stringResource(R.string.ft_m2g), FmDel.maxMb == 2048) { FmDel.setMaxMb(ctx, 2048) },
        ), perRow = 3)
        Text(stringResource(R.string.ft_space_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
    }
}
