package app.adbphon

import android.content.Context
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Формат результата перепаковки. */
enum class RFormat { AS_IS, ZIP, APK, APKS, XAPK }

/** Подпись склеенного APK: наша (ключ программы) или без подписи. Оригинальная при склейке невозможна: подпись покрывает содержимое. */
enum class RSign { OWN, NONE }

/**
 * Перепаковка программ из нескольких APK-частей (base + config.*): из .apks/.xapk/.apkm, установленных программ и папок вроде /data/app/.../.
 * Всё, кроме «Один APK», оставляет части нетронутыми, подписи цела. «Один APK» склеивает части для этого устройства (см. [RepackCore]).
 */
object Repack {
    /** Ключ программы для подписи склеенных APK (лежит в ресурсах, общий для всех копий программы). */
    fun loadKey(ctx: Context): RepackKey {
        val ks = KeyStore.getInstance("PKCS12")
        ctx.assets.open("repack_key.p12").use { ks.load(it, "adbphon".toCharArray()) }
        val a = ks.aliases().nextElement()
        return RepackKey(ks.getKey(a, "adbphon".toCharArray()) as PrivateKey, ks.getCertificateChain(a).map { it as X509Certificate })
    }

    /** Основная часть среди [parts]: base.apk, иначе самая крупная часть без привязки к процессору, плотности и языку. */
    fun baseOf(parts: List<File>): File =
        parts.firstOrNull { Bundles.isBasePart(it.name) } ?: parts.filter { !Bundles.isConfigPart(it.name) }.maxByOrNull { it.length() } ?: parts.maxByOrNull { it.length() }!!

    private fun safe(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "app" }

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name); var i = 2
        val dot = name.lastIndexOf('.').let { if (it <= 0) name.length else it }
        while (f.exists()) { f = File(dir, name.substring(0, dot) + " (" + i++ + ")" + name.substring(dot)); if (i > 999) break }
        return f
    }

    private fun zipParts(parts: List<File>, dest: File, extra: Map<String, ByteArray> = emptyMap()) {
        ZipOutputStream(dest.outputStream().buffered()).use { z ->
            // части уже сжаты: кладутся без изменений, байт в байт
            for ((n, b) in extra) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            for (f in parts) { z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
    }

    private fun xapkManifest(ctx: Context, title: String, parts: List<File>, base: File): ByteArray {
        val info = ctx.packageManager.getPackageArchiveInfo(base.path, 0)
        val j = org.json.JSONObject()
        j.put("xapk_version", 2).put("package_name", info?.packageName ?: "").put("name", title)
        j.put("version_code", info?.let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toString() else @Suppress("DEPRECATION") it.versionCode.toString() } ?: "")
        j.put("version_name", info?.versionName ?: "")
        val arr = org.json.JSONArray()
        for (f in parts) arr.put(org.json.JSONObject().put("file", f.name).put("id", if (f == base) "base" else f.name.removeSuffix(".apk")))
        j.put("split_apks", arr)
        return j.toString(2).toByteArray()
    }

    /**
     * Собирает результат в папке [destDir]; возвращает файл или папку результата. Бросает исключение с понятным текстом при неудаче.
     * [parts]: оригинальные APK (основная часть и части). [title]: имя результата без расширения.
     */
    suspend fun build(ctx: Context, parts: List<File>, title: String, destDir: File, fmt: RFormat, sign: RSign, onStep: (String) -> Unit = {}): File = withContext(Dispatchers.IO) {
        require(parts.isNotEmpty()) { "нет файлов" }
        destDir.mkdirs()
        val name = safe(title)
        val base = baseOf(parts)
        when (fmt) {
            RFormat.AS_IS -> {
                if (parts.size == 1) { val d = unique(destDir, name + ".apk"); parts[0].copyTo(d); d }
                else { val d = unique(destDir, name); d.mkdirs(); for (f in parts) f.copyTo(File(d, f.name)); d }
            }
            RFormat.ZIP -> { val d = unique(destDir, "$name.zip"); zipParts(parts, d); d }
            RFormat.APKS -> { val d = unique(destDir, "$name.apks"); zipParts(parts, d); d }
            RFormat.XAPK -> { val d = unique(destDir, "$name.xapk"); zipParts(parts, d, mapOf("manifest.json" to xapkManifest(ctx, title, parts, base))); d }
            RFormat.APK -> {
                if (parts.size == 1) { val d = unique(destDir, "$name.apk"); parts[0].copyTo(d); return@withContext d }
                // для этого устройства: основная часть и нужные части процессора, плотности экрана и языков; прочие части не склеиваются
                val keepNames = Bundles.select(ctx, parts.map { it.name }).toSet()
                val others = parts.filter { it != base && Bundles.isConfigPart(it.name) && it.name in keepNames }
                val d = unique(destDir, "$name.apk")
                onStep("merge")
                val err = RepackCore.mergeToApk(base, others, d, if (sign == RSign.OWN) loadKey(ctx) else null)
                if (err != null) throw IllegalStateException(err)
                d
            }
        }
    }

    /** Части программы из одного zip-пакета (.apks/.xapk/.apkm) в [dir]: все APK из архива без изменений. Возвращает список файлов. */
    suspend fun extractParts(zip: File, dir: File): List<File> = withContext(Dispatchers.IO) {
        dir.mkdirs()
        ZipFile(zip).use { z ->
            val names = z.entries().asSequence().filter { !it.isDirectory && it.name.lowercase().endsWith(".apk") }.map { it.name }.toList()
            // у bundletool несколько вариантов под разные версии Android: берутся части, подходящие этому устройству (как при установке)
            val toc = z.getEntry("toc.pb")?.let { te -> Bundles.tocPaths(z.getInputStream(te).use { it.readBytes() }, android.os.Build.VERSION.SDK_INT) }
            val pick = if (!toc.isNullOrEmpty()) names.filter { n -> toc.any { it == n || it.endsWith("/$n") || n.endsWith("/$it") } }.ifEmpty { names } else names
            val chosen = pick.filter { it.startsWith("splits/") }.ifEmpty { pick.filter { it.startsWith("standalones/") }.ifEmpty { pick } }
            chosen.mapIndexed { i, n ->
                val out = File(dir, n.substringAfterLast('/').let { if (chosen.count { c -> c.substringAfterLast('/') == it } > 1) "p${i}_$it" else it })
                z.getInputStream(z.getEntry(n)).use { ins -> out.outputStream().use { ins.copyTo(it) } }
                out
            }
        }
    }
}
