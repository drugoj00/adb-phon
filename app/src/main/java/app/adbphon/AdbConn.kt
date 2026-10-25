package app.adbphon

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.io.ByteArrayInputStream

/** Менеджер ADB-соединения: ключ и сертификат создаются один раз и хранятся в приложении. */
class AdbConn(context: Context) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val keyFile = File(context.filesDir, "adb_key.pk8")
        val certFile = File(context.filesDir, "adb_cert.der")
        var k: PrivateKey? = null
        var c: Certificate? = null
        if (keyFile.exists() && certFile.exists()) {
            runCatching {
                k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                c = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            }
        }
        if (k == null || c == null) {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
            val pair = gen.generateKeyPair()
            val pub = pair.public
            val priv = pair.private
            val impl = selfSigned(pub.encoded, priv)
            k = priv
            c = impl
            keyFile.writeBytes(priv.encoded)
            certFile.writeBytes(impl.encoded)
        }
        key = k!!
        cert = c!!
    }

    /** Самоподписанный сертификат X.509 v3 вручную (DER): без библиотек с пакетами sun.*. */
    private fun selfSigned(spki: ByteArray, priv: PrivateKey): Certificate {
        fun der(tag: Int, body: ByteArray): ByteArray {
            val len = when {
                body.size < 128 -> byteArrayOf(body.size.toByte())
                body.size < 256 -> byteArrayOf(0x81.toByte(), body.size.toByte())
                else -> byteArrayOf(0x82.toByte(), (body.size shr 8).toByte(), body.size.toByte())
            }
            return byteArrayOf(tag.toByte()) + len + body
        }
        fun seq(vararg p: ByteArray) = der(0x30, p.fold(ByteArray(0)) { a, b -> a + b })
        val sigAlg = seq(byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B), byteArrayOf(0x05, 0x00))
        val cn = seq(der(0x31, seq(byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03), der(0x0C, "ADB-phon".toByteArray()))))
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val now = System.currentTimeMillis()
        val validity = seq(der(0x17, fmt.format(Date(now - 86400000L)).toByteArray()), der(0x17, fmt.format(Date(now + 10L * 365 * 86400000L)).toByteArray()))
        val serial = ByteArray(8).also { SecureRandom().nextBytes(it); it[0] = (it[0].toInt() and 0x3F or 0x01).toByte() }
        val tbs = seq(byteArrayOf(0xA0.toByte(), 0x03, 0x02, 0x01, 0x02), der(0x02, serial), sigAlg, cn, validity, cn, spki)
        val sig = java.security.Signature.getInstance("SHA256withRSA").run { initSign(priv); update(tbs); sign() }
        val cert = seq(tbs, sigAlg, der(0x03, byteArrayOf(0) + sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(cert))
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "ADB-phon"
}
