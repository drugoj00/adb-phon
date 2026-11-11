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
                    "icons" -> {
                        val d = File(filesDir, "icons")
                        log("icons: файлов ${d.listFiles()?.size ?: 0}, prefs ${getSharedPreferences("icons", MODE_PRIVATE).all.size}, fm.hidden=${getSharedPreferences("fm", MODE_PRIVATE).getStringSet("hidden_btns", null)}, scale_font=${getSharedPreferences("adbphon", MODE_PRIVATE).all["scale_font"]}")
                    }
                    "seedicon" -> {
                        val d = File(filesDir, "icons").also { it.mkdirs() }
                        File(d, "ictest.png").writeBytes(ByteArray(300) { (it * 7).toByte() })
                        getSharedPreferences("icons", MODE_PRIVATE).edit().putString("e:zzz", "i:ictest.png").commit()
                        log("seedicon ok")
                    }
                    "seedcloud" -> {
                        File(filesDir, "clouds.conf").writeText("[testdisk]\ntype = webdav\nurl = http://127.0.0.1/\n")
                        log("seedcloud ok")
                    }
                    "wipecloud" -> { File(filesDir, "clouds.conf").delete(); log("wipecloud ok") }
                    "cloudconf" -> log("cloudconf: " + (if (File(filesDir, "clouds.conf").isFile) File(filesDir, "clouds.conf").readText().replace("\n", " | ") else "нет файла"))
                    "hidebtn" -> {
                        val keys = (intent.getStringExtra("keys") ?: "").split(",").filter { it.isNotBlank() }.toSet()
                        getSharedPreferences("fm", MODE_PRIVATE).edit().putStringSet("hidden_btns", keys).commit()
                        log("hidebtn ok " + keys)
                    }
                    "seticon" -> {
                        val k = intent.getStringExtra("k") ?: ""; val v = intent.getStringExtra("v")
                        getSharedPreferences("icons", MODE_PRIVATE).edit().apply { if (v.isNullOrEmpty()) remove(k) else putString(k, v) }.commit()
                        log("seticon " + k + "=" + v)
                    }
                    "fakeupd" -> {
                        val v = intent.getStringExtra("v").orEmpty()
                        getSharedPreferences("adbphon", MODE_PRIVATE).edit().apply { if (v.isEmpty()) remove("test_fake_release") else putString("test_fake_release", v); if (intent.getStringExtra("k") != "1") remove("upd_dismissed"); remove("upd_last") }.commit()
                        log("fakeupd " + v)
                    }
                    "termline" -> {
                        log("str note=[" + getString(R.string.tc_helper_note) + "] nointr=[" + getString(R.string.tc_helper_nointr) + "]")
                        Term.ensure(this); Thread.sleep(2500)
                        val lines = (intent.getStringExtra("l") ?: "").split("||")
                        for (ln in lines) { Term.sendLine(ln); Thread.sleep(((intent.getStringExtra("w") ?: "2500").toLongOrNull() ?: 2500L)) }
                        if (intent.getBooleanExtra("ctrlc", false)) { Term.interrupt(this); Thread.sleep(1500) }
                        Thread.sleep(500); Term.text.value.takeLast(900).split("\n").forEach { log("T| " + it) }
                        log("termmode=" + Term.mode.value + " running=" + Term.running.value + " last=[" + Term.lastOutput().takeLast(200).replace("\n", " ⏎ ") + "]")
                    }
                    "scrtest" -> {
                        val c = ScreenPcClient(applicationContext)
                        c.connect(intent.getStringExtra("host") ?: "127.0.0.1", intent.getStringExtra("code") ?: "", "A7-test")
                        var n = 0
                        while (n < 80) { Thread.sleep(500); n++; if (c.state == 2 && c.frame != null) break; if (c.state == 0 && n > 3) break }
                        log("scrtest: state=${c.state} frame=${c.frame?.width}x${c.frame?.height} host=${c.hostW}x${c.hostH} status=${c.status}")
                        if (c.state == 2) { c.mouse(0.5f, 0.5f, 0); c.send('g'); Thread.sleep(1500); log("scrtest: clip=${c.clipFromPc != null} monitors=${c.monitors.size}") }
                        c.disconnect()
                    }
                    "wipeicons" -> {
                        File(filesDir, "icons").listFiles()?.forEach { it.delete() }
                        getSharedPreferences("icons", MODE_PRIVATE).edit().clear().commit()
                        getSharedPreferences("fm", MODE_PRIVATE).edit().remove("hidden_btns").commit()
                        log("wipeicons ok")
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
