package app.adbphon

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Окно видеозвонка (7.0). Два состояния: «Звонит <имя> [Ответить] [Отклонить]» (входящий) и сам разговор: WebView с локальной страницей
 * assets/call.html (WebRTC; кнопки «Звук», «Динамик», «Камера», «Завершить» на самой странице). Обмен описаниями соединения со страницей идёт через мост
 * TvoAndroid.post, дальше их отправляет Calls.send по тому же протоколу, что у Windows-версии.
 */
class CallActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private var call: Calls.Call? = null
    private var web: WebView? = null
    private var pageReady = false
    private var started = false
    private var connected = false
    private var ended = false
    private var hangupSent = false
    private var offerAt = 0L
    private var prevMode = AudioManager.MODE_NORMAL
    private var prevSpeaker = false
    private var audioSet = false
    private val teal = Color.parseColor("#0F766E")
    private val mint = Color.parseColor("#5EEAD4")
    private val bg = Color.parseColor("#0B1514")

    override fun attachBaseContext(newBase: Context) {
        val lang = newBase.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getString("lang", "auto") ?: "auto"
        super.attachBaseContext(Lang.wrap(newBase, lang))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun langCode(): String {
        val pref = getSharedPreferences("adbphon", Context.MODE_PRIVATE).getString("lang", "auto") ?: "auto"
        val l = if (pref != "auto") pref else resources.configuration.locales[0].language
        return if (l == "uk" || l == "en") l else "ru"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = teal; window.navigationBarColor = bg
        root = FrameLayout(this).apply { setBackgroundColor(bg); fitsSystemWindows = true }
        setContentView(root)
        val c = Calls.current
        if (c == null) { finish(); return }
        call = c
        route(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (call != null && call === Calls.current) route(intent)
    }

    private fun route(i: Intent) {
        val c = call ?: return
        if (c.outgoing || c.state == "active") { if (!started) begin() } else if (i.getBooleanExtra("answer", false)) answer() else showRing(c)
    }

    // ---------------------------------------------------------------- входящий звонок
    private fun showRing(c: Calls.Call) {
        if (web != null) return
        root.removeAllViews()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(24), dp(20), dp(24)) }
        fun tv(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        val av = tv((c.peerName.trim().firstOrNull() ?: '?').uppercase(), 44f, Color.WHITE, true).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(teal) }
        }
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(av, LinearLayout.LayoutParams(dp(104), dp(104)))
        col.addView(tv(getString(R.string.call_in_title), 15f, mint).apply { setPadding(0, dp(16), 0, 0) })
        col.addView(tv(c.peerName, 24f, Color.WHITE, true).apply { setPadding(0, dp(4), 0, dp(10)) })
        col.addView(tv(getString(R.string.call_in_hint), 13f, Color.parseColor("#9FB8B4")).apply { setPadding(dp(8), 0, dp(8), 0) })
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun btn(t: String, fill: Boolean, line: Int, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (fill) Color.WHITE else line); minHeight = dp(48); minimumHeight = dp(48)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); if (fill) setColor(line) else { setColor(Color.TRANSPARENT); setStroke(dp(2), line) } }
            isFocusable = true; setOnClickListener { onClick() }
            setOnFocusChangeListener { v, f -> v.scaleX = if (f) 1.06f else 1f; v.scaleY = if (f) 1.06f else 1f }   // пульт приставки: выбранная кнопка крупнее
        }
        val bA = btn(getString(R.string.call_answer), true, teal) { answer() }
        val bD = btn(getString(R.string.call_decline), false, Color.parseColor("#F87171")) { decline() }
        val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(6), 0, dp(6), 0) } }
        row.addView(bA, lp()); row.addView(bD, lp())
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bA.requestFocus()
        Calls.listen(c) { k, _ ->
            if (web == null && !ended && !c.outgoing) when (k) {
                "hangup" -> closeRing(getString(R.string.call_cancelled))
                "timeout" -> closeRing(getString(R.string.call_missed))
                "localdecline" -> { ended = true; finish() }
            }
        }
        if (Calls.auto == "answer") ui.postDelayed({ if (web == null && !ended) answer() }, 2500)
        if (Calls.auto == "decline") ui.postDelayed({ if (web == null && !ended) decline() }, 2500)
    }

    private fun closeRing(msg: String) {
        if (ended) return
        ended = true
        root.removeAllViews()
        root.addView(TextView(this).apply { text = msg; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); gravity = Gravity.CENTER })
        call?.let { Calls.finish(applicationContext, it) }
        ui.postDelayed({ finish() }, 1500)
    }

    private fun answer() {
        val c = call ?: return
        if (c.state == "over") { finish(); return }
        Calls.answered(applicationContext, c)
        begin()
    }

    private fun decline() {
        val c = call ?: return
        ended = true
        Calls.decline(applicationContext, c)
        Calls.finish(applicationContext, c)
        finish()
    }

    // ---------------------------------------------------------------- разговор
    private fun missing(): Array<String> =
        if (Calls.fakeMedia.isNotEmpty()) emptyArray()
        else listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    private fun begin() {
        if (started) return
        started = true
        val m = missing()
        if (m.isNotEmpty()) requestPermissions(m, 77) else startWeb()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 77 && web == null && !ended) startWeb()   // отказ не мешает: страница сама скажет «звонок только со звуком» или «только на приём»
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun startWeb() {
        val c = call ?: return
        root.removeAllViews()
        val w = WebView(this)
        web = w
        w.setBackgroundColor(bg)
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = false; allowFileAccess = false; allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false; setSupportZoom(false); builtInZoomControls = false
        }
        w.addJavascriptInterface(Bridge(), "TvoAndroid")
        w.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(req: PermissionRequest) {
                runOnUiThread {
                    val ok = req.origin.toString().startsWith("https://tvo.call")
                    val res = req.resources.filter {
                        (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE && granted(Manifest.permission.CAMERA)) ||
                            (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE && granted(Manifest.permission.RECORD_AUDIO))
                    }
                    if (ok && res.isNotEmpty()) req.grant(res.toTypedArray()) else req.deny()
                }
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = true
        }
        root.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        routeAudio()
        val html = assets.open("call.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        w.loadDataWithBaseURL("https://tvo.call/", html, "text/html", "utf-8", null)
        Calls.listen(c) { k, s -> fromPeer(k, s) }
    }

    /** Звук собеседника: если нет наушников, через динамик (режим «разговор»). */
    @Suppress("DEPRECATION")
    private fun routeAudio() {
        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            prevMode = am.mode; prevSpeaker = am.isSpeakerphoneOn; audioSet = true
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            val headset = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            if (!headset) am.isSpeakerphoneOn = true
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreAudio() {
        if (!audioSet) return
        audioSet = false
        runCatching { val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager; am.isSpeakerphoneOn = prevSpeaker; am.mode = prevMode }
    }

    private fun js(code: String) { web?.evaluateJavascript(code, null) }

    private fun startPage() {
        val c = call ?: return
        if (!pageReady || web == null || started.not()) return
        val cfg = JSONObject().put("role", if (c.outgoing) "caller" else "callee").put("lang", langCode()).put("peer", c.peerName).put("test", false)
        if (Calls.fakeMedia.isNotEmpty()) cfg.put("fake", Calls.fakeMedia)
        if (!c.outgoing) cfg.put("offer", c.offer)
        js("tvoStart(" + cfg.toString() + ")")
    }

    /** answer / decline / busy / hangup от собеседника → в страницу (как tvoAnswer / tvoPeerSays в Windows-версии). */
    private fun fromPeer(k: String, s: String) {
        when (k) {
            "answer" -> if (s.isNotEmpty()) js("tvoAnswer(" + JSONObject.quote(s) + ")")
            "decline" -> { hangupSent = true; js("tvoPeerSays('declined')") }
            "busy" -> { hangupSent = true; js("tvoPeerSays('busy')") }
            "hangup" -> { hangupSent = true; js("tvoPeerSays('hangup')") }
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun post(json: String) { runOnUiThread { onPage(json) } }
    }

    private fun onPage(json: String) {
        val m = runCatching { JSONObject(json) }.getOrNull() ?: return
        val c = call ?: return
        when (m.optString("type")) {
            "ready" -> { pageReady = true; startPage() }
            "offer" -> { Calls.send(applicationContext, c, "offer", m.optString("sdp")); offerAt = System.currentTimeMillis(); watchNoAnswer() }
            "answer" -> Calls.send(applicationContext, c, "answer", m.optString("sdp"))
            "state" -> if (m.optString("v") == "connected") { connected = true; android.util.Log.i("TvoCall", "CONNECTED") }
            "stats" -> android.util.Log.i("TvoCall", "STATS v=${m.optLong("vBytes")} a=${m.optLong("aBytes")} frames=${m.optLong("vFrames")} ${m.optInt("w")}x${m.optInt("h")} out v=${m.optLong("vOut")} a=${m.optLong("aOut")}")
            "hangup" -> if (!hangupSent) { hangupSent = true; Calls.send(applicationContext, c, "hangup") }
            "ended" -> {
                ended = true
                val why = m.optString("why"); android.util.Log.i("TvoCall", "ENDED $why")
                Calls.finish(applicationContext, c)
                ui.postDelayed({ if (!isFinishing) finish() }, if (why == "ended" || why == "gone") 1200L else 4500L)
            }
            "log" -> android.util.Log.i("TvoCall", "page: " + m.optString("m"))
        }
    }

    /** Звонящий: 70 секунд без ответа — отмена (у собеседника закроется окно «Звонит»). */
    private fun watchNoAnswer() {
        ui.postDelayed({
            val c = call ?: return@postDelayed
            if (!ended && !connected && offerAt > 0 && c.outgoing) {
                if (!hangupSent) { hangupSent = true; Calls.send(applicationContext, c, "hangup") }
                js("tvoPeerSays('noAnswer')")
            }
        }, 70_000)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web != null && !ended) js("tvoHangup()") else if (web == null && !ended && call?.state == "ring") decline() else finish()
    }

    override fun onDestroy() {
        val c = call
        if (c != null && !ended && web != null && !hangupSent) { hangupSent = true; Calls.send(applicationContext, c, "hangup") }
        if (c != null && c.state != "over") { if (!c.outgoing && web == null && c.state == "ring") Calls.decline(applicationContext, c); Calls.finish(applicationContext, c) }
        restoreAudio()
        web?.let { runCatching { it.removeJavascriptInterface("TvoAndroid"); it.destroy() } }
        web = null
        super.onDestroy()
    }
}

/**
 * Только для проверок на устройстве: запускается командой adb и подставляет синтетические камеру и микрофон (настоящие не включаются).
 * Работает, только если на устройстве выставлено `settings put global adbphon_call_test 1` (сторонние программы это сделать не могут).
 * Ключи: --es fake av|a|off, --es auto answer|decline|off, --es code <код> [--es name <имя>] [--es ip <адрес>] (позвонить), --ez hangup true, --ez listen true, --ez showcode true (код в журнал).
 */
class CallTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val on = runCatching { Settings.Global.getString(contentResolver, "adbphon_call_test") == "1" }.getOrDefault(false)
        if (on) {
            val i = intent
            i.getStringExtra("fake")?.let { Calls.fakeMedia = if (it == "av" || it == "a") it else "" }
            i.getStringExtra("auto")?.let { Calls.auto = if (it == "answer" || it == "decline") it else "" }
            if (i.getBooleanExtra("listen", false)) CallListen.start(this)
            if (i.getBooleanExtra("showcode", false)) android.util.Log.i("TvoCall", "mycode=" + Invites.myCode(this) + " name=" + Calls.myName(this))
            if (i.getBooleanExtra("hangup", false)) Calls.current?.let { Calls.decline(applicationContext, it); Calls.finish(applicationContext, it) }
            i.getStringExtra("code")?.let { code -> Calls.dial(this, CallPeople.Person(i.getStringExtra("name") ?: code, code, i.getStringExtra("ip") ?: "")) }
        }
        finish()
    }
}
