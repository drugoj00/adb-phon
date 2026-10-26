package app.adbphon

import android.content.Context
import android.net.Uri
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

/**
 * Достаёт APK из архива любого распространённого вида: ZIP, 7z, RAR, TAR (в том числе .tar.gz, .tar.bz2, .tar.xz).
 * Вид определяется по первым байтам файла, а не по расширению. Пакеты из нескольких частей (APKS, APKM, XAPK) собираются в один элемент.
 */
object Archives {
    private fun isApk(name: String) = name.lowercase().endsWith(".apk")

    fun extractApks(ctx: Context, uri: Uri): List<ApkFile> {
        val dir = File(ctx.cacheDir, "batch")
        dir.deleteRecursively()
        dir.mkdirs()
        val src = File(dir, "_source.bin")
        ctx.contentResolver.openInputStream(uri)?.use { i -> src.outputStream().use { o -> i.copyTo(o) } } ?: return emptyList()
        val head = ByteArray(8)
        src.inputStream().use { it.read(head) }

        // пакет из нескольких частей (APKS, APKM, XAPK, ZIP с APK и OBB): одним элементом списка
        if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            Bundles.tryOpen(ctx, src, File(dir, "bundle"), ctx.getString(R.string.bundle_default))?.let { return listOf(it) }
        }

        val out = ArrayList<ApkFile>()
        var idx = 0
        fun add(entryName: String, write: (OutputStream) -> Unit) {
            val name = entryName.substringAfterLast('/').substringAfterLast('\\')
            if (!isApk(name)) return
            val f = File(dir, "${idx++}_" + name.replace(Regex("[^\\w.\\- ]"), "_"))
            f.outputStream().use(write)
            out.add(ApkFile(name, Uri.fromFile(f), f.length(), f))
        }

        runCatching {
            when {
                head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> {
                    java.util.zip.ZipInputStream(FileInputStream(src)).use { z ->
                        var e = z.nextEntry
                        while (e != null) {
                            if (!e.isDirectory) add(e.name) { o -> z.copyTo(o) }
                            z.closeEntry()
                            e = z.nextEntry
                        }
                    }
                }
                head[0] == '7'.code.toByte() && head[1] == 'z'.code.toByte() && head[2] == 0xBC.toByte() -> {
                    @Suppress("DEPRECATION")
                    SevenZFile(src).use { sz ->
                        var e = sz.nextEntry
                        val buf = ByteArray(64 * 1024)
                        while (e != null) {
                            if (!e.isDirectory) add(e.name) { o ->
                                while (true) {
                                    val n = sz.read(buf)
                                    if (n < 0) break
                                    o.write(buf, 0, n)
                                }
                            }
                            e = sz.nextEntry
                        }
                    }
                }
                head[0] == 'R'.code.toByte() && head[1] == 'a'.code.toByte() && head[2] == 'r'.code.toByte() -> {
                    com.github.junrar.Archive(src).use { a ->
                        var h = a.nextFileHeader()
                        while (h != null) {
                            if (!h.isDirectory) {
                                val header = h
                                add(header.fileName) { o -> a.extractFile(header, o) }
                            }
                            h = a.nextFileHeader()
                        }
                    }
                }
                else -> {
                    // TAR, в том числе упакованный gzip, bzip2 или xz
                    BufferedInputStream(FileInputStream(src)).use { raw ->
                        val comp = runCatching { CompressorStreamFactory().createCompressorInputStream(raw) }.getOrNull()
                        BufferedInputStream(comp ?: raw).use { s ->
                            ArchiveStreamFactory().createArchiveInputStream<org.apache.commons.compress.archivers.ArchiveInputStream<*>>(s).use { ais ->
                                var e = ais.nextEntry
                                while (e != null) {
                                    if (!e.isDirectory) add(e.name) { o -> ais.copyTo(o) }
                                    e = ais.nextEntry
                                }
                            }
                        }
                    }
                }
            }
        }
        src.delete()
        return out.sortedBy { it.name.lowercase() }
    }
}
