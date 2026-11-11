package app.adbphon

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Хранение настроек сортировки.
 * Для каждой панели своя общая настройка («sortcfg_p0», «sortcfg_p1»), для отдельной папки — своя («sf:источник|путь»).
 * Всё лежит в тех же настройках «fm», поэтому входит в полную резервную копию.
 */
object SortStore {
    private fun sp() = Fm.sctx.applicationContext.getSharedPreferences("fm", Context.MODE_PRIVATE)

    fun srcKey(p: FmPane): String = if (p.src == "smb") p.net?.let { "net:${it.kind}|${it.host}|${it.user}" } ?: "smb" else p.src
    private fun fkey(p: FmPane, dir: String) = "sf:" + srcKey(p) + "|" + dir

    fun folder(p: FmPane, dir: String): SortCfg? = SortCfg.decode(sp().getString(fkey(p, dir), null))
    fun setFolder(p: FmPane, dir: String, cfg: SortCfg) { sp().edit().putString(fkey(p, dir), cfg.encode()).apply(); Fm.sortTick++ }
    fun clearFolder(p: FmPane, dir: String) { sp().edit().remove(fkey(p, dir)).apply(); Fm.sortTick++ }

    fun saveGlobal(id: Int, cfg: SortCfg) { sp().edit().putString("sortcfg_p$id", cfg.encode()).apply() }

    /** Общая настройка панели; если её ещё нет, берётся прежний выбор «по имени / размеру / дате / типу» из старых версий. */
    fun loadGlobal(id: Int): SortCfg {
        SortCfg.decode(sp().getString("sortcfg_p$id", null))?.let { return it }
        val old = sp().getInt("sort", 0); val asc = sp().getBoolean("asc", true)
        val k = listOf(SKey.NAME, SKey.SIZE, SKey.DATE, SKey.EXT).getOrElse(old) { SKey.NAME }
        var c = SortCfg().withKey(SGroup.FILE, k, asc)
        if (k == SKey.DATE) c = c.withKey(SGroup.DIR, SKey.DATE, asc)
        return c
    }
}

/** Теги музыки, видео и фото и число элементов папок: читаются лениво в фоне, результат хранится в памяти. */
object SortMeta {
    private val cache = ConcurrentHashMap<String, SMeta>()
    private val dirCnt = ConcurrentHashMap<String, Int>()

    private fun ck(path: String, it: FmItem) = path + "|" + it.size + "|" + it.date

    fun get(path: String, it: FmItem): SMeta? = cache[ck(path, it)]

    fun count(p: FmPane, path: String): Int = dirCnt[path] ?: p.kids[path]?.size ?: -1

    private fun read(path: String, g: SGroup, it: FmItem, mmr: MediaMetadataRetriever): SMeta = runCatching {
        when (g) {
            SGroup.MUSIC -> {
                mmr.setDataSource(path)
                SMeta(
                    title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.takeIf { s -> s.isNotEmpty() } ?: it.name.substringBeforeLast('.'),
                    artist = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))?.trim()?.takeIf { s -> s.isNotEmpty() },
                    album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()?.takeIf { s -> s.isNotEmpty() },
                    track = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull() ?: -1,
                    durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1,
                )
            }
            SGroup.VIDEO -> {
                mmr.setDataSource(path)
                SMeta(
                    durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1,
                    w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                    h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                )
            }
            SGroup.PHOTO -> {
                val ex = android.media.ExifInterface(path)
                val s = ex.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL) ?: ex.getAttribute(android.media.ExifInterface.TAG_DATETIME)
                val t = s?.let { x -> runCatching { java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse(x)?.time }.getOrNull() }
                SMeta(taken = t ?: File(path).lastModified())
            }
            else -> SMeta()
        }
    }.getOrDefault(SMeta())

    /**
     * Дочитать недостающие теги для текущей папки и раскрытых папок дерева и после этого один раз пересортировать список.
     * Выбор и положение списка не трогаются: строки списка имеют постоянные ключи.
     */
    suspend fun ensure(p: FmPane) {
        if (p.src != "local") { p.tagNote = null; return }
        class Job(val dir: String, val it: FmItem, val g: SGroup, val path: String)
        val lists = ArrayList<Pair<String, List<FmItem>>>()
        lists += p.path to p.items
        for ((k, v) in p.kids.toMap()) if (k in p.expanded) lists += k to v
        val jobs = ArrayList<Job>()
        for ((dir, list) in lists) {
            val cfg = Fm.cfgFor(p, dir)
            if (SGroup.values().none { cfg.needsMeta(it) }) continue
            for (it in list) {
                val g = cfg.groupOf(it.isDir, it.ext)
                if (!cfg.needsMeta(g)) continue
                val path = FmIo.join(dir, it.name)
                val have = if (g == SGroup.DIR) dirCnt.containsKey(path) else cache.containsKey(ck(path, it))
                if (!have) jobs += Job(dir, it, g, path)
            }
        }
        if (jobs.isEmpty()) { p.tagNote = null; return }
        if (cache.size > 60000) cache.clear()
        try {
            var mmr = MediaMetadataRetriever()
            var n = 0
            try {
                withContext(Dispatchers.IO) {
                    for (j in jobs) {
                        currentCoroutineContext().ensureActive()
                        if (j.g == SGroup.DIR) dirCnt[j.path] = runCatching { File(j.path).list()?.size }.getOrNull() ?: -1
                        else {
                            val m = read(j.path, j.g, j.it, mmr)
                            cache[ck(j.path, j.it)] = m
                            if (m.title == null && m.durMs < 0 && j.g == SGroup.MUSIC) { runCatching { mmr.release() }; mmr = MediaMetadataRetriever() }
                        }
                        n++
                        if (n % 150 == 0) {
                            val done = n
                            withContext(Dispatchers.Main) { p.tagNote = Fm.str(R.string.sort_tags, done, jobs.size) }
                        }
                    }
                }
            } finally { runCatching { mmr.release() } }
            Fm.sortTick++
        } finally { p.tagNote = null }
    }
}

// ----------------------------------------------------------------- подписи

@Composable
fun sgName(g: SGroup, header: Boolean = false): String = stringResource(when (g) {
    SGroup.DIR -> R.string.sg_dir; SGroup.FILE -> if (header) R.string.sg_other else R.string.sg_file
    SGroup.PHOTO -> R.string.sg_photo; SGroup.VIDEO -> R.string.sg_video; SGroup.MUSIC -> R.string.sg_music; SGroup.DOC -> R.string.sg_doc
})

/** Значок группы: папка или значок типа (как в списке файлов). */
@Composable
fun SortGroupIcon(g: SGroup, size: androidx.compose.ui.unit.Dp) {
    when (g) {
        SGroup.DIR -> FolderIcon(UiPrefs.folderDefault, false, size)
        SGroup.PHOTO -> CategoryIcon(FCat.PHOTO, "jpg", size)
        SGroup.VIDEO -> CategoryIcon(FCat.VIDEO, "mp4", size)
        SGroup.MUSIC -> CategoryIcon(FCat.AUDIO, "mp3", size)
        SGroup.DOC -> CategoryIcon(FCat.TEXT, "txt", size)
        SGroup.FILE -> CategoryIcon(FCat.OTHER, "bin", size)
    }
}

@Composable
fun skName(k: SKey): String = stringResource(when (k) {
    SKey.NAME -> R.string.sk_name; SKey.EXT -> R.string.sk_ext; SKey.SIZE -> R.string.sk_size; SKey.DATE -> R.string.sk_date
    SKey.COLOR -> R.string.sk_color; SKey.COUNT -> R.string.sk_count; SKey.TITLE -> R.string.sk_title; SKey.ARTIST -> R.string.sk_artist
    SKey.ALBUM -> R.string.sk_album; SKey.TRACK -> R.string.sk_track; SKey.DURATION -> R.string.sk_duration; SKey.RES -> R.string.sk_res
    SKey.TAKEN -> R.string.sk_taken
})

/** Заголовок группы в режиме «Группировать по типу»: нажатие сворачивает и разворачивает блок. */
@Composable
fun FmGroupHeader(g: SGroup, count: Int, closed: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary.copy(alpha = .08f)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (closed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        SortGroupIcon(g, 20.dp)
        androidx.compose.material3.Text(sgName(g, header = true) + "  ·  " + count, Modifier.padding(start = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

// ----------------------------------------------------------------- окно «Сортировка»

@Composable
fun SortDialog(p: FmPane, close: () -> Unit) {
    val tick = Fm.sortTick
    val cfg = remember(tick, p.sortCfg, p.path) { Fm.cfgFor(p, p.path) }
    val folderOn = remember(tick, p.path) { SortStore.folder(p, p.path) != null }
    val counts = remember(p.items) { p.items.groupingBy { SortGroups.base(it.isDir, it.ext) }.eachCount() }
    val firstSel = p.selected.firstOrNull()?.let { n -> p.items.firstOrNull { it.name == n } }
    var tab by remember { mutableStateOf(firstSel?.let { SortGroups.base(it.isDir, it.ext) } ?: cfg.order.firstOrNull { (counts[it] ?: 0) > 0 } ?: SGroup.FILE) }
    fun edit(f: (SortCfg) -> SortCfg) {
        val n = f(cfg)
        if (SortStore.folder(p, p.path) != null) SortStore.setFolder(p, p.path, n) else Fm.setPaneCfg(p, n)
    }
    val t = 14.sp
    LAlertDialog(
        onDismissRequest = close,
        title = { HintTitle(stringResource(R.string.sort_title) + "  ·  " + stringResource(if (p.id == 0) R.string.fm_pane_left else R.string.fm_pane_right), stringResource(R.string.sort_pane_note) + "\n\n" + stringResource(R.string.sort_file_hint), titleSize = 18.sp) },
        text = {
            Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // область действия настройки
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!folderOn, { SortStore.clearFolder(p, p.path) }, { androidx.compose.material3.Text(stringResource(R.string.sort_scope_all), fontSize = 12.sp) })
                    FilterChip(folderOn, { if (!folderOn) SortStore.setFolder(p, p.path, cfg) }, { androidx.compose.material3.Text(stringResource(R.string.sort_scope_dir), fontSize = 12.sp) })
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                // выбор группы
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    cfg.order.forEach { g ->
                        FilterChip(tab == g, { tab = g }, { androidx.compose.material3.Text(sgName(g) + (counts[g]?.let { " $it" } ?: ""), fontSize = 12.sp) }, leadingIcon = { SortGroupIcon(g, 18.dp) })
                    }
                }
                val ks = SortGroups.keys.getValue(tab)
                ks.forEach { k ->
                    Row(Modifier.fillMaxWidth().clickable { edit { it.withKey(tab, k, it.ascOf(tab)) } }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(cfg.keyOf(tab) == k, { edit { it.withKey(tab, k, it.ascOf(tab)) } }, Modifier.size(40.dp))
                        androidx.compose.material3.Text(skName(k), fontSize = t)
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(cfg.ascOf(tab), { edit { it.withKey(tab, it.keyOf(tab), true) } }, { androidx.compose.material3.Text("↑ " + stringResource(R.string.fm_sort_asc), fontSize = 12.sp) })
                    FilterChip(!cfg.ascOf(tab), { edit { it.withKey(tab, it.keyOf(tab), false) } }, { androidx.compose.material3.Text("↓ " + stringResource(R.string.fm_sort_desc), fontSize = 12.sp) })
                }
                if (tab != SGroup.DIR && tab != SGroup.FILE) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = !cfg.grouped) { edit { it.copy(sep = if (tab in it.sep) it.sep - tab else it.sep + tab) } }, verticalAlignment = Alignment.CenterVertically) {
                        Switch(tab in cfg.sep || cfg.grouped, { v -> edit { it.copy(sep = if (v) it.sep + tab else it.sep - tab) } }, enabled = !cfg.grouped)
                        androidx.compose.material3.Text(stringResource(R.string.sort_separate), Modifier.padding(start = 8.dp), fontSize = 13.sp)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                @Composable fun Sw(label: Int, on: Boolean, set: (Boolean) -> Unit) {
                    Row(Modifier.fillMaxWidth().clickable { set(!on) }, verticalAlignment = Alignment.CenterVertically) {
                        Switch(on, set)
                        androidx.compose.material3.Text(stringResource(label), Modifier.padding(start = 8.dp), fontSize = 13.sp)
                    }
                }
                Sw(R.string.sort_grouped, cfg.grouped) { v -> edit { it.copy(grouped = v) } }
                Sw(R.string.sort_natural, cfg.natural) { v -> edit { it.copy(natural = v) } }
                Sw(R.string.sort_nocase, cfg.ignoreCase) { v -> edit { it.copy(ignoreCase = v) } }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                androidx.compose.material3.Text(stringResource(R.string.sort_order), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                cfg.order.forEachIndexed { i, g ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Text("${i + 1}.  " + sgName(g), Modifier.weight(1f), fontSize = t)
                        IconButton({ if (i > 0) edit { c -> c.copy(order = c.order.toMutableList().also { l -> l[i] = l[i - 1]; l[i - 1] = g }) } }, Modifier.size(36.dp), enabled = i > 0) {
                            Icon(Icons.Filled.ArrowUpward, stringResource(R.string.sort_move_up), Modifier.size(18.dp)) }
                        IconButton({ if (i < cfg.order.lastIndex) edit { c -> c.copy(order = c.order.toMutableList().also { l -> l[i] = l[i + 1]; l[i + 1] = g }) } }, Modifier.size(36.dp), enabled = i < cfg.order.lastIndex) {
                            Icon(Icons.Filled.ArrowDownward, stringResource(R.string.sort_move_down), Modifier.size(18.dp)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(close) { androidx.compose.material3.Text(stringResource(R.string.fm_apply)) } },
        dismissButton = { TextButton({ edit { SortCfg() } }) { androidx.compose.material3.Text(stringResource(R.string.sort_reset)) } },
    )
}
