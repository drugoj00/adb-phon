package app.adbphon

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** Склейка настоящих split-частей: запускается, только если задана папка с образцами (переменная REPACK_SAMPLES); результат проверяют aapt2 и apksigner на ПК. */
class RepackSampleTest {
    private fun key(): RepackKey {
        val ks = KeyStore.getInstance("PKCS12")
        File("src/main/assets/repack_key.p12").inputStream().use { ks.load(it, "adbphon".toCharArray()) }
        val a = ks.aliases().nextElement()
        return RepackKey(ks.getKey(a, "adbphon".toCharArray()) as PrivateKey, ks.getCertificateChain(a).map { it as X509Certificate })
    }

    @Test fun mergeSamples() {
        val dir = System.getenv("REPACK_SAMPLES")
        assumeTrue(dir != null && File(dir).isDirectory)
        val out = File(System.getenv("REPACK_OUT") ?: (dir + "/out")); out.mkdirs()
        for (app in File(dir!!).listFiles()!!.filter { it.isDirectory && it.name != "out" }) {
            val base = File(app, "base.apk")
            if (!base.exists()) continue
            val others = app.listFiles()!!.filter { it.name.startsWith("split_") && it.name.endsWith(".apk") }.sortedBy { it.name }
            assertNull(RepackCore.mergeToApk(base, others, File(out, app.name + "-unsigned.apk"), null))
            assertNull(RepackCore.mergeToApk(base, others, File(out, app.name + "-own.apk"), key()))
        }
    }
}
