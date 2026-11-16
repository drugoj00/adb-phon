package app.adbphon

import com.android.apksig.ApkSigner
import java.io.File
import java.io.OutputStream
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Ключ для подписи собранного APK. */
class RepackKey(val key: PrivateKey, val chain: List<X509Certificate>)

/**
 * Склейка split-частей (основная часть + части процессора, плотности экрана, языков) в один APK. Чистый код без Android.
 * Подпись APK покрывает содержимое, поэтому оригинальная подпись у склеенного APK невозможна: он либо подписывается ключом [RepackKey], либо остаётся без подписи.
 */
object RepackCore {
    private fun isSigFile(n: String): Boolean {
        val u = n.uppercase()
        return u == "META-INF/MANIFEST.MF" || (u.startsWith("META-INF/") && !u.removePrefix("META-INF/").contains('/') && (u.endsWith(".SF") || u.endsWith(".RSA") || u.endsWith(".DSA") || u.endsWith(".EC")))
    }

    private class Counting(val o: OutputStream) : OutputStream() {
        var n = 0L
        override fun write(b: Int) { o.write(b); n++ }
        override fun write(b: ByteArray, off: Int, len: Int) { o.write(b, off, len); n += len }
        override fun flush() = o.flush()
        override fun close() = o.close()
    }

    /**
     * Собирает один APK из [base] и [others] в [dest]. [key] = null: без подписи. Возвращает null при успехе, иначе короткое описание причины.
     */
    fun mergeToApk(base: File, others: List<File>, dest: File, key: RepackKey?): String? {
        val tmp = if (key != null) File(dest.parentFile, dest.name + ".unsigned.tmp") else dest
        try {
            val zips = ArrayList<ZipFile>()
            try {
                val zb = ZipFile(base).also { zips.add(it) }
                val zo = others.map { ZipFile(it).also { z -> zips.add(z) } }
                // манифест основной части без признаков «часть»
                val manifest = zb.getEntry("AndroidManifest.xml")?.let { e -> zb.getInputStream(e).use { it.readBytes() } } ?: return "нет AndroidManifest.xml"
                val newManifest = AxmlEdit.stripSplit(manifest) ?: return "манифест не разобран"
                // таблица ресурсов: основная + части
                val arscBase = zb.getEntry("resources.arsc")?.let { e -> zb.getInputStream(e).use { it.readBytes() } }
                val arscParts = zo.mapNotNull { z -> z.getEntry("resources.arsc")?.let { e -> z.getInputStream(e).use { it.readBytes() } } }
                val arsc = if (arscBase == null) null else if (arscParts.isEmpty()) arscBase else ArscMerge.merge(arscBase, arscParts) ?: return "таблица ресурсов не склеена"

                tmp.parentFile?.mkdirs()
                val seen = HashSet<String>()
                val cnt = Counting(tmp.outputStream().buffered())
                ZipOutputStream(cnt).use { out ->
                    out.setLevel(6)
                    fun putStored(name: String, data: ByteArray, align: Int) {
                        val nameLen = name.toByteArray(Charsets.UTF_8).size
                        var pad = ((align - ((cnt.n + 30 + nameLen) % align)) % align).toInt()
                        if (pad in 1..3) pad += align
                        val e = ZipEntry(name)
                        e.method = ZipEntry.STORED; e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
                        e.crc = CRC32().also { it.update(data) }.value
                        if (pad > 0) { val ex = ByteArray(pad); ex[0] = 0x35; ex[1] = 0xD9.toByte(); ex[2] = (pad - 4).toByte(); ex[3] = 0; e.extra = ex }
                        out.putNextEntry(e); out.write(data); out.closeEntry()
                    }
                    fun copy(z: ZipFile, e: ZipEntry) {
                        val ne = ZipEntry(e.name)
                        ne.time = e.time
                        out.putNextEntry(ne)
                        z.getInputStream(e).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                    // манифест и таблица ресурсов первыми; таблица без сжатия и с выравниванием на 4 байта (требование Android 11 и новее)
                    out.putNextEntry(ZipEntry("AndroidManifest.xml")); out.write(newManifest); out.closeEntry(); seen.add("AndroidManifest.xml")
                    if (arsc != null) { putStored("resources.arsc", arsc, 4); seen.add("resources.arsc") }
                    for ((i, z) in (listOf(zb) + zo).withIndex()) {
                        for (e in z.entries()) {
                            if (e.isDirectory) continue
                            val n = e.name
                            if (n == "AndroidManifest.xml" || n == "resources.arsc" || n == "stamp-cert-sha256" || isSigFile(n)) continue
                            if (i > 0 && n.startsWith("META-INF/")) continue
                            if (!seen.add(n)) continue
                            copy(z, e)
                        }
                    }
                }
            } finally { zips.forEach { runCatching { it.close() } } }
            if (key == null) return null
            val cfg = ApkSigner.SignerConfig.Builder("adbphon", key.key, key.chain).build()
            if (dest.exists()) dest.delete()
            ApkSigner.Builder(listOf(cfg)).setInputApk(tmp).setOutputApk(dest).setMinSdkVersion(26)
                .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(false).build().sign()
            return null
        } catch (e: Throwable) {
            runCatching { dest.delete() }
            return generateSequence(e) { it.cause }.joinToString(" <- ") { it.javaClass.simpleName + ": " + (it.message ?: "") }.take(400)
        } finally {
            if (key != null) runCatching { tmp.delete() }
        }
    }
}
