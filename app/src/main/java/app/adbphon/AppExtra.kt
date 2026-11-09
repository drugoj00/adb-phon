package app.adbphon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Дополнительные сведения о программе для панели справа от списка: размеры, архитектура. null = не удалось узнать. */
data class AppExtra(val apk: Long?, val data: Long?, val cache: Long?, val obb: Long, val abi: String?)

object AppExtras {
    @Volatile private var diskAt = 0L
    @Volatile private var disk: Map<String, LongArray> = emptyMap()   // пакет -> [данные, кэш]

    /** Размеры данных и кэша всех программ одним запросом `dumpsys diskstats` (хранится минуту). */
    private suspend fun loadDisk() {
        if (disk.isNotEmpty() && System.currentTimeMillis() - diskAt < 60_000) return
        val out = runCatching { Adb.shell("dumpsys diskstats", 30_000, fast = true) }.getOrDefault("")
        fun arr(name: String): List<String>? =
            Regex(Regex.escape(name) + ": \\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(out)?.groupValues?.get(1)?.split(',')?.map { it.trim() }
        val names = arr("Package Names")?.map { it.trim('"') } ?: return
        val data = arr("App Data Sizes") ?: return
        val cache = arr("Cache Sizes")
        disk = names.indices.associate { i -> names[i] to longArrayOf(data.getOrNull(i)?.toLongOrNull() ?: 0L, cache?.getOrNull(i)?.toLongOrNull() ?: 0L) }
        diskAt = System.currentTimeMillis()
    }

    suspend fun load(pkg: String, paths: List<String>): AppExtra = withContext(Dispatchers.IO) {
        val connected = Adb.isConnected
        if (connected) loadDisk()
        var apk: Long? = paths.sumOf { runCatching { File(it).length() }.getOrDefault(0L) }.takeIf { it > 0 }
        var obb = 0L
        var abi: String? = null
        if (connected) {
            fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
            val cmd = (if (apk == null && paths.isNotEmpty()) "stat -c %s " + paths.joinToString(" ") { q(it) } + " 2>/dev/null; " else "") +
                "echo ===; du -sk /sdcard/Android/obb/$pkg 2>/dev/null; echo ===; dumpsys package $pkg 2>/dev/null | grep -m1 primaryCpuAbi"
            val out = runCatching { Adb.shell(cmd, 20_000, fast = true) }.getOrDefault("")
            val parts = out.split("===")
            if (apk == null) apk = parts.getOrNull(0).orEmpty().lines().mapNotNull { it.trim().toLongOrNull() }.sum().takeIf { it > 0 }
            obb = (parts.getOrNull(1).orEmpty().trim().substringBefore('\t').substringBefore(' ').toLongOrNull() ?: 0L) * 1024
            abi = parts.getOrNull(2).orEmpty().substringAfter("primaryCpuAbi=", "").trim().takeIf { it.isNotEmpty() && it != "null" }
        }
        val d = disk[pkg]
        AppExtra(apk, d?.get(0), d?.get(1), obb, abi)
    }

    /** Где лежит программа: по пути её APK. */
    fun storageRes(paths: List<String>): Int? {
        val p = paths.firstOrNull() ?: return null
        return when {
            p.startsWith("/data/app") -> R.string.st_internal
            p.contains("/mnt/expand/") -> R.string.st_sd
            listOf("/system", "/product", "/vendor", "/system_ext", "/odm", "/oem", "/apex").any { p.startsWith(it) } -> R.string.st_rom
            else -> null
        }
    }
}
