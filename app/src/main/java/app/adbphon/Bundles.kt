package app.adbphon

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/** Файл OBB из пакета: откуда брать в архиве и куда класть в памяти устройства (путь от корня, например Android/obb/пакет/main.1.пакет.obb). */
data class ObbItem(val zip: File, val entry: String, val dest: String)

/**
 * Пакеты из нескольких частей: APKS (bundletool, SAI), APKM (APKMirror), XAPK и ZIP с APK и OBB.
 * Из частей выбираются нужные этому устройству: процессор, плотность экрана, языки системы; остальное не ставится.
 */
object Bundles {
    private val abis = listOf("x86_64", "arm64_v8a", "armeabi_v7a", "armeabi", "x86", "mips64", "mips")
    private val densities = listOf("ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240, "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640)
    private val isoLanguages by lazy { Locale.getISOLanguages().toSet() }
    /** Старые коды языков, которые использует Android (иврит, индонезийский, идиш, яванский). */
    private val oldLanguageCodes = setOf("in", "iw", "ji", "jw")

    private class Kind(val abi: String?, val dpi: String?, val lang: String?)

    private fun norm(file: String) = file.lowercase().removeSuffix(".apk").replace('-', '_').replace('.', '_')

    private fun kindOf(file: String): Kind {
        val n = norm(file)
        val abi = abis.firstOrNull { n.contains(it) }
        val dpi = densities.firstOrNull { Regex("(^|_)${it.first}($|_)").containsMatchIn(n) }?.first
        val rest = n.split('_').filter { it.isNotEmpty() } - setOf("base", "master", "split", "config", "arm64", "v8a", "armeabi", "v7a", "x86", "64", "dpi", "nodpi") -
            densities.map { it.first }.toSet()
        val lang = rest.firstOrNull { it in isoLanguages || it in oldLanguageCodes }?.takeIf { abi == null && dpi == null }
        return Kind(abi, dpi, lang)
    }

    private fun deviceAbi(): List<String> = Build.SUPPORTED_ABIS.map { it.lowercase().replace('-', '_') }

    private fun deviceDpiIndex(ctx: Context): Int {
        val d = ctx.resources.displayMetrics.densityDpi
        return densities.indexOfFirst { d <= it.second }.let { if (it < 0) densities.lastIndex else it }
    }

    private fun deviceLanguages(): Set<String> {
        val out = HashSet<String>()
        out.add("en")
        runCatching {
            val l = android.os.LocaleList.getDefault()
            for (i in 0 until l.size()) out.add(l[i].language)
        }
        out.add(Locale.getDefault().language)
        return out
    }

    /** Из имён частей оставляет нужные этому устройству. */
    internal fun select(ctx: Context, names: List<String>): List<String> {
        if (names.size <= 1) return names
        val kinds = names.associateWith { kindOf(it.substringAfterLast('/')) }
        val keep = ArrayList<String>()
        // основная часть и части без привязки (в том числе дополнительные модули)
        keep += names.filter { kinds.getValue(it).let { k -> k.abi == null && k.dpi == null && k.lang == null } }
        // процессор: первый из поддерживаемых устройством, который есть в пакете
        val abiParts = names.filter { kinds.getValue(it).abi != null }
        if (abiParts.isNotEmpty()) {
            val best = deviceAbi().firstOrNull { a -> abiParts.any { kinds.getValue(it).abi == a } }
            if (best != null) keep += abiParts.filter { kinds.getValue(it).abi == best }
        }
        // плотность экрана: точная, иначе ближайшая выше, иначе самая большая
        val dpiParts = names.filter { kinds.getValue(it).dpi != null }
        if (dpiParts.isNotEmpty()) {
            val want = deviceDpiIndex(ctx)
            val have = dpiParts.map { p -> densities.indexOfFirst { it.first == kinds.getValue(p).dpi } }.distinct().sorted()
            val pick = have.firstOrNull { it >= want } ?: have.last()
            keep += dpiParts.filter { densities.indexOfFirst { d -> d.first == kinds.getValue(it).dpi } == pick }
        }
        // языки системы и английский
        val langs = deviceLanguages()
        keep += names.filter { kinds.getValue(it).lang?.let { l -> l in langs } == true }
        return keep.distinct()
    }

    // ---- toc.pb (BuildApksResult из bundletool): какие части входят в вариант для версии Android этого устройства ----
    private fun pbFields(b: ByteArray): List<Triple<Int, Int, Any>> {
        val out = ArrayList<Triple<Int, Int, Any>>()
        var p = 0
        fun varint(): Long {
            var r = 0L
            var sh = 0
            while (true) {
                val x = b[p++].toInt() and 0xff
                r = r or ((x and 0x7f).toLong() shl sh)
                if (x and 0x80 == 0) break
                sh += 7
            }
            return r
        }
        while (p < b.size) {
            val tag = varint()
            val num = (tag shr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> out.add(Triple(num, 0, varint()))
                2 -> { val len = varint().toInt(); out.add(Triple(num, 2, b.copyOfRange(p, p + len))); p += len }
                1 -> p += 8
                5 -> p += 4
                else -> return out
            }
        }
        return out
    }

    private fun pbBytes(f: List<Triple<Int, Int, Any>>, num: Int): List<ByteArray> = f.filter { it.first == num && it.second == 2 }.map { it.third as ByteArray }

    /** Пути частей выбранного варианта (самый новый вариант, не новее Android устройства); null, если разобрать не удалось. */
    internal fun tocPaths(toc: ByteArray, sdk: Int): List<String>? = runCatching {
        class V(val minSdk: Long, val paths: List<String>, val splits: Boolean)
        // BuildApksResult: variant = 1; Variant: targeting = 1, apk_set = 2; ApkSet: apk_description = 2; ApkDescription: path = 2
        val variants = pbBytes(pbFields(toc), 1).map { vb ->
            val vf = pbFields(vb)
            // targeting(1) -> sdk_version_targeting(1) -> value(1) -> min(1) -> value(1)
            var cur: List<ByteArray> = pbBytes(vf, 1)
            for (n in listOf(1, 1, 1)) cur = cur.flatMap { pbBytes(pbFields(it), n) }
            val min = cur.firstNotNullOfOrNull { b -> pbFields(b).firstOrNull { it.first == 1 && it.second == 0 }?.third as? Long } ?: 1L
            val paths = ArrayList<String>()
            var splits = false
            for (apkSet in pbBytes(vf, 2)) for (ad in pbBytes(pbFields(apkSet), 2)) {
                val af = pbFields(ad)
                pbBytes(af, 2).firstOrNull()?.let { val path = String(it, Charsets.UTF_8); paths.add(path); if (path.startsWith("splits/")) splits = true }
            }
            V(min, paths, splits)
        }
        val ok = variants.filter { it.minSdk <= sdk && it.paths.isNotEmpty() }
        val best = ok.maxWithOrNull(compareBy<V> { it.minSdk }.thenBy { if (it.splits) 1 else 0 }) ?: return@runCatching null
        best.paths
    }.getOrNull()

    private fun isBaseName(n: String): Boolean {
        val s = norm(n.substringAfterLast('/'))
        return s == "base" || s == "base_master" || s == "universal"
    }

    /**
     * Если ZIP — многочастный пакет, распаковывает нужные части в [workDir] и возвращает один элемент списка установки; иначе null.
     * OBB не распаковываются сразу (они бывают огромными): копируются в память устройства при установке прямо из [zipFile].
     */
    fun tryOpen(ctx: Context, zipFile: File, workDir: File, fallbackName: String): ApkFile? {
        val zf = runCatching { ZipFile(zipFile) }.getOrNull() ?: return null
        zf.use { z ->
            val all = z.entries().toList().filter { !it.isDirectory }
            val apks = all.filter { it.name.lowercase().endsWith(".apk") }
            val obbs = all.filter { it.name.lowercase().endsWith(".obb") }
            if (apks.isEmpty()) return null
            val lowNames = all.map { it.name.substringAfterLast('/').lowercase() }
            val hasMeta = "toc.pb" in lowNames || "info.json" in lowNames || "manifest.json" in lowNames
            val baseLike = apks.any { isBaseName(it.name) }
            val multi = apks.size > 1 && (hasMeta || baseLike || apks.any { it.name.lowercase().contains("config") || it.name.lowercase().contains("split") })
            if (!(hasMeta && apks.isNotEmpty() && (apks.size > 1 || obbs.isNotEmpty() || "toc.pb" in lowNames)) && !multi && !(apks.size == 1 && obbs.isNotEmpty())) return null

            // что ставить: части (splits/), иначе один самостоятельный APK (standalones/ или universal), иначе все части из корня
            var cand = apks.map { it.name }
            // у APKS от bundletool есть варианты под разные версии Android (base-master и base-master_2): берём тот, что подходит этому устройству
            all.firstOrNull { it.name.equals("toc.pb", true) }?.let { te ->
                val paths = tocPaths(z.getInputStream(te).use { it.readBytes() }, Build.VERSION.SDK_INT)
                if (!paths.isNullOrEmpty()) {
                    val inToc = cand.filter { n -> paths.any { it == n || it.endsWith("/" + n) || n.endsWith("/" + it) } }
                    if (inToc.isNotEmpty()) cand = inToc
                }
            }
            cand = when {
                cand.any { it.startsWith("splits/") } -> cand.filter { it.startsWith("splits/") }
                cand.any { it.startsWith("standalones/") } -> cand.filter { it.startsWith("standalones/") }.let { st ->
                    val k = st.associateWith { kindOf(it.substringAfterLast('/')) }
                    val abi = deviceAbi().firstOrNull { a -> k.values.any { it.abi == a } }
                    val byAbi = st.filter { k.getValue(it).abi == abi || abi == null }.ifEmpty { st }
                    val want = deviceDpiIndex(ctx)
                    val withDpi = byAbi.filter { k.getValue(it).dpi != null }
                    val best = withDpi.minByOrNull { n -> kotlin.math.abs(densities.indexOfFirst { it.first == k.getValue(n).dpi } - want) }
                    listOf(best ?: byAbi.first())
                }
                else -> cand
            }
            val chosen = select(ctx, cand)
            if (chosen.isEmpty()) return null

            // манифест XAPK: название, пакет
            var pkgFromManifest: String? = null
            var labelFromManifest: String? = null
            all.firstOrNull { it.name.substringAfterLast('/').equals("manifest.json", true) }?.let { me ->
                runCatching {
                    val j = JSONObject(z.getInputStream(me).bufferedReader().use { it.readText() })
                    pkgFromManifest = j.optString("package_name").takeIf { it.isNotBlank() }
                    labelFromManifest = j.optString("name").takeIf { it.isNotBlank() }
                }
            }

            workDir.mkdirs()
            val parts = ArrayList<File>()
            for ((i, n) in chosen.withIndex()) {
                val ze = z.getEntry(n) ?: continue
                // имя части должно быть уникальным и оканчиваться на .apk
                val out = File(workDir, "p${i}_" + n.substringAfterLast('/').replace(Regex("[^\\w.\\-]"), "_"))
                z.getInputStream(ze).use { input -> out.outputStream().use { input.copyTo(it) } }
                parts.add(out)
            }
            if (parts.isEmpty()) return null
            val baseFile = parts.firstOrNull { f -> chosen.any { isBaseName(it) && f.name.endsWith(it.substringAfterLast('/').replace(Regex("[^\\w.\\-]"), "_")) } } ?: parts.maxByOrNull { it.length() }!!
            val pm = ctx.packageManager
            val info = pm.getPackageArchiveInfo(baseFile.path, 0)
            var pkg = pkgFromManifest ?: info?.packageName
            var label = labelFromManifest
            if (label == null && info?.applicationInfo != null) {
                info.applicationInfo!!.sourceDir = baseFile.path
                info.applicationInfo!!.publicSourceDir = baseFile.path
                label = runCatching { pm.getApplicationLabel(info.applicationInfo!!).toString() }.getOrNull()
            }
            val obbItems = obbs.map { o ->
                val low = o.name.replace('\\', '/')
                val dest = if (low.lowercase().startsWith("android/")) low else "Android/obb/${pkg ?: "unknown"}/" + low.substringAfterLast('/')
                ObbItem(zipFile, o.name, dest)
            }
            // исходный ZIP нужен до конца установки (OBB читаются из него): оставляем рядом с частями
            val keepZip = File(workDir, "bundle_source.zip")
            if (zipFile.absolutePath != keepZip.absolutePath) {
                if (!zipFile.renameTo(keepZip)) runCatching { zipFile.copyTo(keepZip, true) }
            }
            val obbFinal = if (keepZip.exists()) obbItems.map { it.copy(zip = keepZip) } else obbItems
            val total = parts.sumOf { it.length() } + obbs.sumOf { it.size.coerceAtLeast(0) }
            val name = (label ?: fallbackName) + "  (" + ctx.getString(R.string.bundle_parts, parts.size) + (if (obbFinal.isNotEmpty()) ", OBB" else "") + ")"
            return ApkFile(name, Uri.fromFile(baseFile), total, null, null, parts, obbFinal, pkg)
        }
    }

    /** Копирует OBB в память устройства. Возвращает число скопированных файлов или -1, если нет доступа к памяти. */
    fun copyObb(items: List<ObbItem>): Int {
        var n = 0
        for (o in items) {
            try {
                ZipFile(o.zip).use { z ->
                    val ze = z.getEntry(o.entry) ?: return@use
                    val out = File(Environment.getExternalStorageDirectory(), o.dest)
                    out.parentFile?.mkdirs()
                    z.getInputStream(ze).use { i -> out.outputStream().use { i.copyTo(it) } }
                    n++
                }
            } catch (e: java.io.IOException) {
                return -1
            } catch (e: SecurityException) {
                return -1
            }
        }
        return n
    }

    /**
     * Копирует OBB: сначала напрямую, а если системе это запрещено (Android 11 и новее) и ADB подключён,
     * передаёт файлы через ADB. Возвращает число файлов или -1.
     */
    suspend fun copyObbAny(items: List<ObbItem>): Int = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val n = copyObb(items)
        if (n >= 0 || !Adb.isConnected) return@withContext n
        var done = 0
        for (o in items) {
            try {
                ZipFile(o.zip).use { z ->
                    val ze = z.getEntry(o.entry) ?: return@use
                    z.getInputStream(ze).use { Adb.pushStream(it, ze.size, "/sdcard/" + o.dest) }
                    done++
                }
            } catch (e: Exception) {
                return@withContext -1
            }
        }
        done
    }

    /** Убирает скопированные OBB (если установка не удалась). */
    fun deleteObb(items: List<ObbItem>) {
        for (o in items) runCatching { File(Environment.getExternalStorageDirectory(), o.dest).delete() }
    }

    /**
     * Установка по ADB: тихо, без окон. OBB копируются до установки (иначе, например, при обновлении самой программы
     * её процесс завершится раньше), при неудаче убираются. Возвращает true при успехе.
     */
    suspend fun installAdb(ctx: Context, f: ApkFile): Boolean {
        val parts = f.parts ?: return false
        if (f.obb.isNotEmpty()) {
            val n = copyObbAny(f.obb)
            if (n < 0) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(ctx, ctx.getString(R.string.bundle_no_storage), Toast.LENGTH_LONG).show()
                }
                return false
            }
        }
        val r = Adb.installFiles(parts, "-r")
        if (!r.contains("Success", true)) { deleteObb(f.obb); return false }
        return true
    }
}
