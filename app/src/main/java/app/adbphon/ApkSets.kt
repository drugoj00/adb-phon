package app.adbphon

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 17.0 (код 83): набор из частей APK, лежащих в одной папке (base.apk и split_config.*.apk, как после копирования из системы или распаковки APKS).
 * Нажатие на любую часть и «Установить APK → Выбрать папку» собирают набор того же пакета и ставят его одной сессией (install-multiple);
 * из языковых, процессорных и плотностных частей берутся нужные этому устройству ([Bundles.select]). Раньше каждая часть шла отдельным файлом
 * и система отвечала INSTALL_FAILED_MISSING_SPLIT.
 */
object ApkSets {
    private const val MAX_SIBLINGS = 300

    private class Member(val file: File, val head: ApkIconRes.ManifestHead)

    /** Набор вокруг файла [main] из его папки; null: файл одиночный (рядом нет других частей того же пакета или нет основной части). */
    fun setAround(ctx: Context, main: File): ApkFile? {
        val dir = main.parentFile ?: return null
        val mainHead = ApkIconRes.manifestHead(main.path) ?: return null
        val sibs = dir.listFiles { f -> f.isFile && f.name.endsWith(".apk", true) }.orEmpty().take(MAX_SIBLINGS)
        val group = ArrayList<Member>()
        for (f in sibs) {
            val h = if (f.path == main.path) mainHead else ApkIconRes.manifestHead(f.path)
            if (h != null && h.pkg == mainHead.pkg) group += Member(f, h)
        }
        return build(ctx, group, main)
    }

    /** Из членов группы одного пакета собирает набор; [main] - то, на что нажали (если это основная часть, остальные основные части игнорируются). */
    private fun build(ctx: Context, group: List<Member>, main: File?): ApkFile? {
        if (group.size < 2) return null
        val bases = group.filter { it.head.split == null }
        val base = when {
            bases.size == 1 -> bases[0]
            main != null -> bases.firstOrNull { it.file.path == main.path } ?: return null
            else -> return null
        }
        val splits = group.filter { it.head.split != null }
        if (splits.isEmpty()) return null
        val names = splits.map { it.head.split!! }
        val chosen = Bundles.select(ctx, names).toSet()
        val parts = listOf(base.file) + splits.filter { it.head.split!! in chosen }.map { it.file }
        val total = parts.sumOf { it.length() }
        return ApkFile(base.file.name, Uri.fromFile(base.file), total, null, null, parts, emptyList(), base.head.pkg, Bundles.missingLanguage(names), true)
    }

    /**
     * Список найденных APK (папка, дерево, архив): файлы одного пакета с несколькими частями заменяются одним набором; остальное без изменений.
     * Файлы без пути на устройстве (дерево SAF) читаются через дескриптор; части набора копируются во временную папку.
     */
    fun groupList(ctx: Context, list: List<ApkFile>): List<ApkFile> {
        if (list.size < 2 || list.any { it.parts != null }) return list
        val heads = LinkedHashMap<ApkFile, ApkIconRes.ManifestHead>()
        for (f in list.take(MAX_SIBLINGS)) {
            val head = when {
                f.file != null -> ApkIconRes.manifestHead(f.file.path)
                f.uri.scheme == "file" -> f.uri.path?.let { ApkIconRes.manifestHead(it) }
                f.uri != Uri.EMPTY && f.fetch == null -> runCatching {
                    ctx.contentResolver.openFileDescriptor(f.uri, "r")?.use { ApkIconRes.manifestHead("/proc/self/fd/" + it.fd) }
                }.getOrNull()
                else -> null
            }
            if (head != null) heads[f] = head
        }
        val byPkg = heads.entries.groupBy({ it.value.pkg }, { it })
        val out = ArrayList<ApkFile>()
        val used = HashSet<ApkFile>()
        for (f in list) {
            if (f in used) continue
            val h = heads[f]
            val members = h?.let { byPkg[it.pkg] }.orEmpty()
            if (h == null || members.size < 2 || members.none { it.value.split == null } || members.all { it.value.split == null }) { out += f; continue }
            val local = members.map { e ->
                val af = e.key
                val file = af.file ?: af.uri.takeIf { it.scheme == "file" }?.path?.let { File(it) } ?: copyOf(ctx, af)
                Member(file ?: return@map null, e.value)
            }
            if (local.any { it == null }) { out += f; continue }
            val set = build(ctx, local.filterNotNull(), null)
            if (set == null) { out += f; continue }
            members.forEach { used += it.key }
            out += set
        }
        return out
    }

    private fun copyOf(ctx: Context, f: ApkFile): File? = runCatching {
        val dir = File(ctx.cacheDir, "batch").apply { mkdirs() }
        val out = File(dir, "set_" + System.nanoTime().toString(36) + "_" + f.name.replace(Regex("[^\\w.\\- ]"), "_"))
        ctx.contentResolver.openInputStream(f.uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
        out
    }.getOrNull()
}
