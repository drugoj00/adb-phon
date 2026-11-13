package app.adbphon

import android.content.Context
import android.util.Log
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Корневые сертификаты (Let's Encrypt, Google Trust Services, Amazon, DigiCert, USERTrust, GlobalSign, Sectigo, HARICA) для старых Android.
 *
 * Сервисы погоды, F-Droid и файлы релизов GitHub (release-assets.githubusercontent.com) используют Let's Encrypt, а на Android 7.1.0 и старше
 * этих корней в системе нет: любой запрос HTTPS заканчивается «Trust anchor for certification path not found», погода висит на «Загрузка…»,
 * обновление не скачивается. Здесь к системным корням ДОБАВЛЯЮТСЯ два публичных корня (проверка цепочки и имени сайта остаётся полной,
 * ничего не отключается). Системное хранилище опрашивается первым; свои корни используются, только если система отказала.
 */
object TlsFix {
    @Volatile private var done = false
    private val logged = java.util.Collections.synchronizedSet(HashSet<String>())

    fun install(ctx: Context) {
        if (done) return
        done = true
        runCatching {
            val cf = CertificateFactory.getInstance("X.509")
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            // корни, которых нет в системе Android 7.1 и старше (Let's Encrypt, Google Trust Services, Amazon, DigiCert G2/G3, USERTrust, GlobalSign R46/E46, Sectigo R46/E46, HARICA)
            listOf(
                R.raw.isrg_root_x1, R.raw.isrg_root_x2, R.raw.gts_root_r1, R.raw.gts_root_r2, R.raw.gts_root_r3, R.raw.gts_root_r4,
                R.raw.digicert_global_root_g2, R.raw.digicert_global_root_g3, R.raw.usertrust_rsa_root, R.raw.usertrust_ecc_root,
                R.raw.amazon_root_ca1, R.raw.amazon_root_ca2, R.raw.amazon_root_ca3, R.raw.amazon_root_ca4,
                R.raw.globalsign_root_r3, R.raw.globalsign_root_r46, R.raw.globalsign_root_e46, R.raw.sectigo_root_r46, R.raw.sectigo_root_e46,
                R.raw.harica_tls_rsa_root_2021, R.raw.harica_tls_ecc_root_2021, R.raw.hellenic_root_2015, R.raw.hellenic_ecc_root_2015,
            ).forEachIndexed { i, id ->
                // один испорченный файл не должен лишать остальные корни
                runCatching { ctx.resources.openRawResource(id).use { ks.setCertificateEntry("own$i", cf.generateCertificate(it)) } }
                    .onFailure { Log.w("ADBphon", "TlsFix: корень $id не загружен: ${it.message}") }
            }
            val sys = tm(null)
            val own = tm(ks)
            val composite = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = sys.checkClientTrusted(chain, authType)
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    try { sys.checkServerTrusted(chain, authType) } catch (e: CertificateException) {
                        own.checkServerTrusted(chain, authType)
                        // системное хранилище отказало, доверено своим корнем: в журнал один раз на каждый корень
                        runCatching {
                            val top = chain.last().issuerX500Principal.name
                            if (logged.add(top)) Log.i("ADBphon", "TlsFix: доверено собственным корнем: " + top.take(80))
                        }
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = sys.acceptedIssuers + own.acceptedIssuers
            }
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, arrayOf<TrustManager>(composite), null)
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
        }.onFailure { Log.w("ADBphon", "TlsFix: ${it.message}") }
    }

    private fun tm(ks: KeyStore?): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(ks)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
