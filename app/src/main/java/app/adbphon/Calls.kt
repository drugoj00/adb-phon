package app.adbphon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import androidx.compose.runtime.mutableStateListOf
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Видеозвонок (7.0): тот же протокол, что в «Телефон в окне со звуком» (видеозвонок.ps1, связь_интернет.ps1).
 * Сообщение о звонке: {v:1, t:"call", k:offer|answer|decline|busy|hangup, id, n:имя, e:срок(unix), fc:код звонящего, fi:адрес Tailscale, s:описание соединения}
 * идёт тем же шифром v1 (AES-256-CBC + HMAC-SHA256, PBKDF2 по коду получателя), что и приглашения: напрямую на порт 47321 («TVO1 <шифр>» → «OK»)
 * или через ретранслятор ntfy.sh (тема tvo-<хэш кода>). Сам разговор (WebRTC, VP8 + opus) ведёт страница assets/call.html в WebView.
 */
object CallPeople {
    class Person(val name: String, val code: String, val ip: String)

    private val list = mutableStateListOf<Person>()
    private var loaded = false
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("invites", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Person> {
        if (!loaded) {
            loaded = true
            runCatching {
                val a = JSONArray(sp(ctx).getString("people", "[]"))
                for (i in 0 until a.length()) { val o = a.getJSONObject(i); list.add(Person(o.optString("name"), o.optString("code"), o.optString("ip"))) }
            }
        }
        return list
    }

    private fun save(ctx: Context) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("name", it.name).put("code", it.code).put("ip", it.ip)) }
        sp(ctx).edit().putString("people", a.toString()).apply()
    }

    fun validCode(c: String): Boolean = Invites.norm(c).let { n -> n.length == 12 && n.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" } }

    fun add(ctx: Context, name: String, code: String) {
        all(ctx)
        val c = Invites.fmt(code)
        val i = list.indexOfFirst { Invites.norm(it.code) == Invites.norm(c) }
        val p = Person(name.trim().ifEmpty { c }, c, if (i >= 0) list[i].ip else "")
        if (i >= 0) list[i] = p else list.add(p)
        save(ctx)
    }

    fun remove(ctx: Context, p: Person) { all(ctx); list.remove(p); save(ctx) }

    /** Адрес Tailscale собеседника берём из его же сообщения (fi), чтобы дальше слать напрямую. */
    fun learnIp(ctx: Context, code: String, ip: String) {
        if (ip.isEmpty()) return
        all(ctx)
        val i = list.indexOfFirst { Invites.norm(it.code) == Invites.norm(code) }
        if (i >= 0 && list[i].ip != ip) { list[i] = Person(list[i].name, list[i].code, ip); save(ctx) }
    }
}

object Calls {
    const val NOTIF_RING = 4440
    const val NOTIF_LISTEN = 4441
    private const val TAG = "TvoCall"

    /** voice = голосовой звонок (камера выключена, включается кнопкой посреди звонка); в сообщении offer это поле «vo»:1, как у Windows-версии 4.0. */
    class Call(val id: String, val outgoing: Boolean, val peerName: String, val peerCode: String, var peerIp: String, val offer: String?, val expires: Long, val voice: Boolean = false) {
        @Volatile var state = "ring"   // ring | active | over
        internal val queue = ArrayList<Pair<String, String>>()
        internal var listener: ((String, String) -> Unit)? = null
    }

    @Volatile var current: Call? = null
    /** Только для проверок: «av» или «a» = вместо камеры и микрофона синтетический поток (см. CallTestActivity). */
    @Volatile var fakeMedia = ""
    /** Только для проверок: «answer» / «decline» = входящий звонок обрабатывается сам. */
    @Volatile var auto = ""
    /** Только для проверок: звук собеседника в звонке выключен с самого начала (тесты без звука). */
    @Volatile var spkOff = false
    /** Только для проверок: кодек видео vp8 | vp9 | h264. */
    @Volatile var vcodec = ""
    @Volatile var lowForce = false
    @Volatile var fakeW = 0
    @Volatile var fakeH = 0

    private val seen = LinkedHashMap<String, Long>()
    private val main = Handler(Looper.getMainLooper())
    private fun now() = System.currentTimeMillis() / 1000
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("invites", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- имя и адрес
    fun myName(ctx: Context): String {
        val s = sp(ctx).getString("call_name", null)
        if (!s.isNullOrBlank()) return s
        val dn = runCatching { Settings.Global.getString(ctx.contentResolver, "device_name") }.getOrNull()
        return (dn?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android").take(40)
    }
    fun setMyName(ctx: Context, v: String) { sp(ctx).edit().putString("call_name", v.take(40)).apply() }

    /** Адрес Tailscale этого устройства (100.64.0.0/10) или пусто. */
    fun myIp(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }.flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().map { it.address }.firstOrNull { (it[0].toInt() and 255) == 100 && (it[1].toInt() and 255) in 64..127 }
            ?.joinToString(".") { (it.toInt() and 255).toString() }
    }.getOrNull().orEmpty()

    /** Только для проверок на устройстве: `settings put global adbphon_call_test 1` (сторонние программы это сделать не могут). */
    fun testGate(ctx: Context): Boolean = runCatching { Settings.Global.getString(ctx.contentResolver, "adbphon_call_test") == "1" }.getOrDefault(false)

    /** Адрес в локальной сети (только для проверок без Tailscale). */
    private fun lanIp(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull().orEmpty()

    // ---------------------------------------------------------------- шифр и доставка
    private fun seal(code: String, plain: String): String {
        val r = SecureRandom()
        val salt = ByteArray(16).also { r.nextBytes(it) }; val iv = ByteArray(16).also { r.nextBytes(it) }
        val b = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(("tvo-key-v1:" + Invites.norm(code)).toCharArray(), salt, 120000, 512)).encoded
        val enc = b.copyOfRange(0, 32); val mk = b.copyOfRange(32, 64)
        val ct = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(enc, "AES"), IvParameterSpec(iv)) }.doFinal(plain.toByteArray(Charsets.UTF_8))
        val body = byteArrayOf(1) + salt + iv + ct
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(mk, "HmacSHA256")) }.doFinal(body)
        return Base64.encodeToString(body + mac, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun directSend(ip: String, env: String): Boolean = runCatching {
        Socket().use { s ->
            val host = ip.substringBefore(':'); val port = ip.substringAfter(':', "").toIntOrNull() ?: Invites.PORT   // «адрес» или «адрес:порт» (порт нужен проверкам через adb reverse)
            s.connect(InetSocketAddress(host, port), 3000); s.soTimeout = 12000   // получатель сначала расшифровывает (PBKDF2), на слабой приставке это несколько секунд
            s.getOutputStream().apply { write(("TVO1 $env\n").toByteArray()); flush() }
            BufferedReader(InputStreamReader(s.getInputStream())).readLine()?.trim() == "OK"
        }
    }.getOrElse { android.util.Log.i(TAG, "direct $ip: $it"); false }

    private fun relaySend(code: String, env: String): Boolean = runCatching {
        val c = URL("https://ntfy.sh/" + Invites.topic(code)).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        c.outputStream.use { it.write(env.toByteArray(Charsets.UTF_8)) }
        val ok = c.responseCode in 200..299; c.disconnect(); ok
    }.getOrElse { android.util.Log.i(TAG, "relay: $it"); false }

    /** Отправка собеседнику (в своём потоке): напрямую по адресу Tailscale, иначе через ретранслятор. */
    fun send(ctx: Context, c: Call, kind: String, sdp: String? = null) {
        val app = ctx.applicationContext
        Thread {
            runCatching {
                val o = JSONObject().put("v", 1).put("t", "call").put("k", kind).put("id", c.id).put("n", myName(app)).put("e", now() + 90)
                    .put("fc", Invites.myCode(app)).put("fi", myIp().ifEmpty { if (testGate(app)) lanIp() else "" })
                if (!sdp.isNullOrEmpty()) o.put("s", sdp)
                if (kind == "offer" && c.voice) o.put("vo", 1)
                val t0 = System.currentTimeMillis()
                val env = seal(c.peerCode, o.toString())
                android.util.Log.i(TAG, "seal ${System.currentTimeMillis() - t0} ms")
                var via = "none"
                if (c.peerIp.isNotEmpty() && directSend(c.peerIp, env)) via = "direct" else if (relaySend(c.peerCode, env)) via = "relay"
                android.util.Log.i(TAG, "send $kind id=${c.id} via=$via envelope=${env.length}")
            }.onFailure { android.util.Log.w(TAG, "send $kind: $it") }
        }.start()
    }

    // ---------------------------------------------------------------- приём
    /** Сообщение t="call", уже расшифрованное (вызывает Invites.handle; поток любой). */
    fun onMessage(app: Context, o: JSONObject) {
        val k = o.optString("k")
        val id = o.optString("id").filter { it.isLetterOrDigit() }
        if (id.isEmpty()) return
        val fc = o.optString("fc"); val fi = o.optString("fi"); val n = o.optString("n").ifBlank { fc }
        val s = o.optString("s")
        synchronized(this) {
            val key = "$id:$k"
            if (seen.containsKey(key)) return
            seen[key] = now(); if (seen.size > 200) seen.remove(seen.keys.first())
        }
        if (CallPeople.validCode(fc)) CallPeople.learnIp(app, fc, fi)
        android.util.Log.i(TAG, "recv $k id=$id from=$n")
        if (k == "offer") {
            val left = o.optLong("e") - now()
            if (left < 5 || s.isEmpty() || !CallPeople.validCode(fc)) return
            val busy = current != null
            val c = Call(id, false, n, fc, fi, s, now() + minOf(45L, left), o.optInt("vo") == 1)
            if (busy) { send(app, c, "busy"); return }
            current = c
            ring(app, c)
            return
        }
        if (k != "answer" && k != "decline" && k != "busy" && k != "hangup") return
        val c = current ?: return
        if (c.id != id) return
        main.post {
            if (k == "hangup" && c.state == "ring" && !c.outgoing) { over(app, c); cancelRing(app) }
            c.listener?.invoke(k, s) ?: synchronized(c.queue) { c.queue.add(k to s) }
        }
    }

    /** Подписка окна звонка: сначала приходит всё, что накопилось, пока окна не было. */
    fun listen(c: Call, l: (String, String) -> Unit) {
        c.listener = l
        val q = synchronized(c.queue) { val x = ArrayList(c.queue); c.queue.clear(); x }
        q.forEach { l(it.first, it.second) }
    }

    // ---------------------------------------------------------------- исходящий
    /** → «ok», «busy» или «fail». */
    fun dial(ctx: Context, p: CallPeople.Person, voice: Boolean = false): String {
        val app = ctx.applicationContext
        if (current != null) return "busy"
        if (!CallPeople.validCode(p.code)) return "fail"
        val id = (1..12).map { "0123456789abcdef"[SecureRandom().nextInt(16)] }.joinToString("")
        val c = Call(id, true, p.name, p.code, p.ip, null, now() + 70, voice)
        current = c
        c.state = "active"
        return runCatching {
            CallListen.begin(app)
            app.startActivity(Intent(app, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "ok"
        }.getOrElse { current = null; "fail" }
    }

    // ---------------------------------------------------------------- входящий: уведомление и окно
    private fun ringChannel(app: Context): String {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 7.6: мелодию звонка играет сама программа (выбор в «Мелодия звонка…»), поэтому у канала своего звука нет, остаётся вибрация
        runCatching { nm.deleteNotificationChannel("call_ring_v1") }
        val ch = NotificationChannel("call_ring_v2", app.getString(R.string.call_title), NotificationManager.IMPORTANCE_HIGH)
        ch.setSound(null, null)
        ch.enableVibration(true); ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
        return "call_ring_v2"
    }

    private fun ring(app: Context, c: Call) {
        runCatching {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = ringChannel(app)
            val full = PendingIntent.getActivity(app, 4440, Intent(app, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val ans = PendingIntent.getActivity(app, 4441, Intent(app, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("answer", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val dec = PendingIntent.getBroadcast(app, 4442, Intent(app, CallReceiver::class.java).setAction("app.adbphon.CALL_DECLINE"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(app, ch).setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(app.getString(if (c.voice) R.string.call_notif_ring_voice else R.string.call_notif_ring, c.peerName)).setContentText(app.getString(if (c.voice) R.string.call_in_hint_voice else R.string.call_in_hint))
                .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX).setOngoing(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setTimeoutAfter(maxOf(5L, c.expires - now()) * 1000)
                .setFullScreenIntent(full, true).setContentIntent(full)
                .addAction(android.R.drawable.sym_action_call, app.getString(R.string.call_answer), ans)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, app.getString(R.string.call_decline), dec).build()
            nm.notify(NOTIF_RING, n)
        }.onFailure { android.util.Log.w(TAG, "ring notify: $it") }
        Ringtones.playRing(app, c.voice)
        // приложение на экране или есть право «поверх других окон»: окно открывается сразу (из фона Android иначе показывает только уведомление)
        runCatching {
            if (Invites.uiActive || Settings.canDrawOverlays(app)) app.startActivity(Intent(app, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        main.postDelayed({ if (current === c && c.state == "ring") { over(app, c); cancelRing(app); c.listener?.invoke("timeout", "") ?: synchronized(c.queue) { c.queue.add("timeout" to "") } } }, maxOf(5L, c.expires - now()) * 1000)
    }

    fun cancelRing(app: Context) { Ringtones.stop(); runCatching { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_RING) } }

    /** Человек нажал «Ответить» (в окне или в уведомлении). */
    fun answered(app: Context, c: Call) { c.state = "active"; cancelRing(app); CallListen.begin(app) }

    fun decline(app: Context, c: Call) {
        if (c.state != "ring") return
        send(app, c, "decline"); over(app, c); cancelRing(app)
        main.post { c.listener?.invoke("localdecline", "") }   // отклонено кнопкой в уведомлении: окно «Звонит» закрывается
    }

    private fun over(app: Context, c: Call) { c.state = "over"; if (current === c) current = null }

    /** Звонок закончен (окно закрыто). */
    fun finish(app: Context, c: Call) {
        c.state = "over"; c.listener = null
        if (current === c) current = null
        cancelRing(app); CallListen.end(app)
    }
}

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "app.adbphon.CALL_DECLINE") Calls.current?.let { Calls.decline(context.applicationContext, it) }
    }
}

/** Фоновый приём: служба переднего плана слушает порт 47321 и ретранслятор (поток ntfy.sh), чтобы звонок доходил и при закрытом окне программы. */
object CallListen {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("invites", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = sp(ctx).getBoolean("call_listen", true)
    fun setEnabled(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("call_listen", v).apply()
        if (v) start(ctx) else if (Calls.current == null) ctx.applicationContext.stopService(Intent(ctx.applicationContext, CallListenService::class.java))
    }
    /** При открытии программы и после загрузки. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!enabled(app)) return
        runCatching { androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, CallListenService::class.java)) }
            .onFailure { AppLog.e("ADBphon", "call listen start", it) }
    }
    /** Начался звонок: служба нужна, даже если фоновый приём выключен; тип службы расширяется до микрофона и камеры. */
    fun begin(ctx: Context) {
        val app = ctx.applicationContext
        runCatching { androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, CallListenService::class.java).putExtra("incall", true)) }
            .onFailure { AppLog.e("ADBphon", "call listen begin", it) }
    }
    fun end(ctx: Context) {
        val app = ctx.applicationContext
        if (enabled(app)) start(app) else runCatching { app.stopService(Intent(app, CallListenService::class.java)) }
    }
}

class CallListenService : Service() {
    companion object {
        /** Служба звонков работает и уже показывает своё уведомление переднего плана (по нему PadKeeperService решает, можно ли снять своё). */
        @Volatile var fg = false
    }
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var conn: HttpURLConnection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun notification(inCall: Boolean): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("call_listen", getString(R.string.call_listen_notif), NotificationManager.IMPORTANCE_MIN))
        val target = if (inCall) CallActivity::class.java else MainActivity::class.java
        val open = PendingIntent.getActivity(this, 4443, Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, "call_listen").setSmallIcon(R.drawable.ic_stat_call).setOngoing(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN).setContentIntent(open)
        if (inCall) b.setContentTitle(getString(if (Calls.current?.voice == true) R.string.call_incall_notif_voice else R.string.call_incall_notif, Calls.current?.peerName ?: ""))
        else {
            b.setContentTitle(getString(R.string.call_listen_notif))
            val off = PendingIntent.getService(this, 4444, Intent(this, CallListenService::class.java).setAction("off"), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.call_listen_off), off)
        }
        return b.build()
    }

    private fun foreground(inCall: Boolean) {
        val n = notification(inCall)
        var t = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        if (inCall && Build.VERSION.SDK_INT >= 30) {
            if (granted(android.Manifest.permission.RECORD_AUDIO)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (granted(android.Manifest.permission.CAMERA) && Calls.current?.voice != true) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        runCatching { ServiceCompat.startForeground(this, Calls.NOTIF_LISTEN, n, t) }.onFailure {
            android.util.Log.w("TvoCall", "startForeground type=$t: $it")
            runCatching { ServiceCompat.startForeground(this, Calls.NOTIF_LISTEN, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0) }.onFailure { fg = false; return }
        }
        fg = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "off") {
            getSharedPreferences("invites", MODE_PRIVATE).edit().putBoolean("call_listen", false).apply()
            if (Calls.current == null) { fg = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        }
        foreground(intent?.getBooleanExtra("incall", false) == true || Calls.current != null)
        Invites.ensureServer(applicationContext)
        if (!running) {
            running = true
            thread = Thread { stream() }.apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        fg = false
        running = false
        runCatching { conn?.disconnect() }
        super.onDestroy()
    }

    /** Поток ntfy.sh: соединение держится открытым, сообщение приходит сразу (не раз в 20 секунд). При обрыве — повтор с паузой. */
    private fun stream() {
        val app = applicationContext
        val sp = app.getSharedPreferences("invites", MODE_PRIVATE)
        var wait = 2000L
        while (running) {
            try {
                val last = sp.getLong("relay_last", 0L)
                val url = URL("https://ntfy.sh/" + Invites.topic(Invites.myCode(app)) + "/json?since=" + (if (last > 0) last.toString() else "2m"))
                val c = url.openConnection() as HttpURLConnection
                conn = c; c.connectTimeout = 10000; c.readTimeout = 110000
                var mx = last
                c.inputStream.bufferedReader().use { r ->
                    wait = 2000L
                    while (running) {
                        val l = r.readLine() ?: break
                        val o = runCatching { JSONObject(l) }.getOrNull() ?: continue
                        if (o.optString("event") != "message") continue
                        mx = maxOf(mx, o.optLong("time"))
                        runCatching { Invites.handle(app, o.optString("message")) }
                        if (mx > sp.getLong("relay_last", 0L)) sp.edit().putLong("relay_last", mx).apply()
                    }
                }
                c.disconnect()
            } catch (e: Throwable) {
                if (running) android.util.Log.w("TvoCall", "stream: $e")
            }
            if (!running) break
            runCatching { Thread.sleep(wait) }
            wait = minOf(wait * 2, 30000L)
        }
    }
}
