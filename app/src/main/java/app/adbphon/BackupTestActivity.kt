package app.adbphon

import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.io.File

/**
 * Проверка резервной копии на устройстве без интерфейса (для разработчика):
 *   am start -n app.adbphon/.BackupTestActivity --es cmd selftest
 *   ... --es cmd seed                       создать тестовые подключения и настройки
 *   ... --es cmd make --es pass ФРАЗА --es file /sdcard/Download/test.adbpb
 *   ... --es cmd restore --es pass ФРАЗА --es file /sdcard/Download/test.adbpb
 *   ... --es cmd dump                       показать число настроек, подключений и первый пароль в открытом виде (только длина)
 * Результат в logcat, тег ADBphonBK.
 */
class BackupTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cmd = intent.getStringExtra("cmd").orEmpty()
        val pass = intent.getStringExtra("pass")?.toCharArray()
        val file = intent.getStringExtra("file") ?: "/sdcard/Download/test.adbpb"
        fun log(s: String) { Log.i("ADBphonBK", s) }
        Thread {
            runCatching {
                when (cmd) {
                    "selftest" -> AppBackup.selfTest(this).forEach { log(it) }
                    "seed" -> {
                        NetStore.save(this, NetConn("ftp", "127.0.0.1", 2121, "test", "pass123"))
                        NetStore.save(this, NetConn("dav", "http://127.0.0.1:8081/", 0, "test", "pass123"))
                        getSharedPreferences("pad", MODE_PRIVATE).edit().putFloat("dead", 0.27f).putFloat("cspeed", 1.7f).apply()
                        getSharedPreferences("adbphon", MODE_PRIVATE).edit().putString("start_tab", "4").putString("favs", "Тест\t192.168.1.50\t5555\ttcp").apply()
                        log("seed ok")
                    }
                    "make" -> {
                        val (data, sum) = AppBackup.create(this, pass)
                        File(file).writeBytes(data)
                        log("make ok ${data.size} байт, настроек ${sum.settings}, подключений ${sum.links}, облаков ${sum.clouds}, устройств ${sum.devices}, секреты ${sum.secrets}, зашифровано ${AppBackup.isEncrypted(data)}")
                    }
                    "restore" -> {
                        val j = AppBackup.parse(File(file).readBytes(), pass)
                        val sum = AppBackup.restore(this, j)
                        log("restore ok настроек ${sum.settings}, подключений ${sum.links}, облаков ${sum.clouds}, устройств ${sum.devices}")
                    }
                    "dump" -> {
                        val l = NetStore.list(this)
                        log("dump: подключений ${l.size}: " + l.joinToString { "${it.kind} ${it.host} user=${it.user} passLen=${it.pass.length}" })
                        log("dump: pad.dead=${getSharedPreferences("pad", MODE_PRIVATE).getFloat("dead", -1f)} cspeed=${getSharedPreferences("pad", MODE_PRIVATE).getFloat("cspeed", -1f)} start_tab=${getSharedPreferences("adbphon", MODE_PRIVATE).getString("start_tab", "-")} favs=${getSharedPreferences("adbphon", MODE_PRIVATE).getString("favs", "-")?.take(40)?.replace('\n', '|')}")
                    }
                    "unseed" -> {
                        NetStore.replaceAll(this, emptyList())
                        getSharedPreferences("pad", MODE_PRIVATE).edit().remove("dead").remove("cspeed").apply()
                        getSharedPreferences("adbphon", MODE_PRIVATE).edit().remove("start_tab").remove("favs").apply()
                        log("unseed ok")
                    }
                    "wrongpass" -> {
                        val r = runCatching { AppBackup.parse(File(file).readBytes(), pass) }.exceptionOrNull()
                        log("wrongpass: ${r?.javaClass?.simpleName}")
                    }
                    else -> log("unknown cmd")
                }
            }.onFailure { log("ERROR " + it.javaClass.simpleName + ": " + it.message) }
            runOnUiThread { finish() }
        }.start()
    }
}
