package app.adbphon

import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/**
 * Стенд проверки «Устройства → Android» без интерфейса: `am start -n app.adbphon/.AndroidTestActivity --es devs "192.168.1.51:5555,192.168.1.138:5555" --es plan "0:list /sdcard;1:list /sdcard"`.
 * Аргументы через «|». Шаги: [N:]list П | mkdir П | put РАЗМЕР|П | get П | mv А Б | rm П | stat П | sh КОМАНДА | par (оба устройства одновременно list /sdcard). Результат в logcat (тег AndroidTest).
 */
class AndroidTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FmIo.app = applicationContext; Fm.sctx = applicationContext
        // Для проверки автоповорота: эта страница просит у системы поворот (land/port/rland/rport/sensor); на устройстве-цели её запускают командой am start
        intent.getStringExtra("orient")?.let { o -> requestedOrientation = when (o) { "land" -> 0; "port" -> 1; "rland" -> 8; "rport" -> 9; else -> 4 } }
        intent.getStringExtra("seed")?.let { v -> saveAndroid(this, v.split(',').filter { it.contains('=') }.map { AdbDev(it.substringBefore('='), it.substringAfter('=')) }); Log.i("AndroidTest", "SEEDED " + loadAndroid(this).size); finish(); return }
        val devs = (intent.getStringExtra("devs") ?: "").split(',').filter { it.isNotBlank() }
        val fss = devs.map { NetConn("andr", it.substringBefore(':'), it.substringAfter(':', "5555").toInt(), "", "").fs() }
        val plan = (intent.getStringExtra("plan") ?: "").split(';').map { it.trim() }.filter { it.isNotEmpty() }
        // шаги экрана (scr/info/type/tap/sleep/stop) показывают видео на этой странице: нужна поверхность
        var sfc: android.view.Surface? = null
        var screen: AndroidScreenClient? = null
        if (plan.any { it.contains("scr") }) {
            val sv = android.view.SurfaceView(this)
            sv.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(h: android.view.SurfaceHolder) { sfc = h.surface; screen?.surface = h.surface }
                override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, hh: Int) { sfc = h.surface; screen?.surface = h.surface }
                override fun surfaceDestroyed(h: android.view.SurfaceHolder) { sfc = null; screen?.surface = null }
            })
            setContentView(sv)
        }
        CoroutineScope(Dispatchers.IO).launch launchScope@{
            for (step in plan) {
                val t0 = System.currentTimeMillis()
                val idx = if (step.length > 2 && step[1] == ':') step[0].digitToInt() else 0
                val s = if (step.length > 2 && step[1] == ':') step.substring(2) else step
                val a = listOf(s.substringBefore(' ')) + s.substringAfter(' ', "").split('|')
                val fs = fss.getOrNull(idx)
                val r = runCatching {
                    when (a[0]) {
                        "list" -> fs!!.list(a[1]).let { "n=" + it.size + " " + it.take(8).joinToString(",") { e -> (if (e.isDir) "[" else "") + e.name + (if (e.isDir) "]" else ":" + e.size) } }
                        "roots" -> fs!!.roots().joinToString(",") { it.second }
                        "mkdir" -> { fs!!.mkdirRemote(a[1]); "ok" }
                        "put" -> {
                            val f = File(cacheDir, "andtest.bin"); val n = a[1].toInt()
                            f.outputStream().use { o -> val b = ByteArray(65536) { (it * 7 % 251).toByte() }; var left = n; while (left > 0) { val k = minOf(left, b.size); o.write(b, 0, k); left -= k } }
                            fs!!.putFile(f, a[2], null); val st = fs.stat(a[2]); "put " + n + " -> size=" + st?.size + " md5=" + md5(f)
                        }
                        "get" -> { val f = File(cacheDir, "andtest_get.bin"); fs!!.copyTo(a[1], f); "got size=" + f.length() + " md5=" + md5(f) }
                        "mv" -> { fs!!.renameRemote(a[1], a[2], false); "ok" }
                        "rm" -> { fs!!.removeRemote(a[1], a.getOrNull(2) == "dir"); "ok" }
                        "stat" -> fs!!.stat(a[1]).toString()
                        "sh" -> AdbLinks.of(devs[idx]).shell(s.removePrefix("sh "))
                        "scr" -> { val c = AndroidScreenClient(listOf(AdbDev.norm(devs[idx]))); screen = c; c.surface = sfc; c.start(); "started" }
                        "info" -> screen?.info() ?: "no screen"
                        "type" -> { screen!!.text(s.removePrefix("type ").trimStart()); "sent" }
                        "tap" -> { screen!!.tap(a[1].toFloat(), a[2].toFloat()); "sent" }
                        "sleep" -> { kotlinx.coroutines.delay(a[1].toLong()); "slept" }
                        "stop" -> { screen?.stop(); "stopped" }
                        "par" -> kotlinx.coroutines.coroutineScope {
                            val rs = fss.map { f -> async { f.list("/sdcard").size } }
                            rs.awaitAll().joinToString(",")
                        }
                        else -> "?"
                    }
                }
                Log.i("AndroidTest", "[" + (System.currentTimeMillis() - t0) + " ms] " + step + " => " + (r.getOrNull() ?: ("ERR " + r.exceptionOrNull()?.javaClass?.simpleName + ": " + r.exceptionOrNull()?.message)))
            }
            Log.i("AndroidTest", "END")
            runOnUiThread { finish() }
        }
    }
    /** Для проверки сопоставления касаний: каждое касание пишется в журнал с координатами в пикселях экрана и размером экрана. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            val m = android.util.DisplayMetrics(); @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(m)
            Log.i("AndroidTest", "TOUCH x=" + ev.rawX.toInt() + " y=" + ev.rawY.toInt() + " screen=" + m.widthPixels + "x" + m.heightPixels)
        }
        return super.dispatchTouchEvent(ev)
    }
    private fun md5(f: File): String = MessageDigest.getInstance("MD5").digest(f.readBytes()).joinToString("") { "%02x".format(it) }.take(12)
}
