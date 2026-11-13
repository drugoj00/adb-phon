package app.adbphon

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.drawable.Icon
import android.util.Rational
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
    private var vw = 0
    private var vh = 0
    private var vrot = 0
    private var pendingDir = ""
    private var fullOn = false
    private var recKind = ""
    private val saver by lazy { CallSaver(this) { path, kind, _ -> onSaved(path, kind) } }
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
        if (c == null) { closeTask(); return }
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
        col.addView(tv(getString(if (c.voice) R.string.call_ring_in_title_voice else R.string.call_in_title), 15f, mint).apply { setPadding(0, dp(16), 0, 0) })
        col.addView(tv(c.peerName, 24f, Color.WHITE, true).apply { setPadding(0, dp(4), 0, dp(10)) })
        col.addView(tv(getString(if (c.voice) R.string.call_in_hint_voice else R.string.call_in_hint), 13f, Color.parseColor("#9FB8B4")).apply { setPadding(dp(8), 0, dp(8), 0) })
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
                "localdecline" -> { ended = true; closeTask() }
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
        ui.postDelayed({ closeTask() }, 1500)
    }

    private fun answer() {
        val c = call ?: return
        if (c.state == "over") { closeTask(); return }
        Calls.answered(applicationContext, c)
        begin()
    }

    private fun decline() {
        val c = call ?: return
        ended = true
        Calls.decline(applicationContext, c)
        Calls.finish(applicationContext, c)
        closeTask()
    }

    // ---------------------------------------------------------------- разговор
    private fun missing(): Array<String> =
        if (Calls.fakeMedia.isNotEmpty()) emptyArray()
        else (if (call?.voice == true) listOf(Manifest.permission.RECORD_AUDIO) else listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

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
            javaScriptEnabled = true; textZoom = 100; domStorageEnabled = false; allowFileAccess = false; allowContentAccess = false
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
        applyOutput(outputs().firstOrNull { it.id == (getSharedPreferences("call_ui", Context.MODE_PRIVATE).getString("out", "auto") ?: "auto") })
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
        val rc = Ringtones.cfg(this)
        val cfg = JSONObject().put("role", if (c.outgoing) "caller" else "callee").put("lang", langCode()).put("peer", c.peerName).put("test", false)
            .put("side", if (Cfg.rightHanded(this)) "right" else "left").put("avatar", CallPics.avatarB64(this)).put("picMode", CallPics.mode(this)).put("pic", if (CallPics.mode(this) == "own") CallPics.picDataUri(this) else "").put("pip", pipAvailable()).put("voice", c.voice).put("waitTone", rc.wait).put("ringVol", rc.vol).put("prefs", pagePrefs()).put("spkOff", Calls.spkOff).put("vcodec", Calls.vcodec).put("lowforce", Calls.lowForce).put("fw", Calls.fakeW).put("fh", Calls.fakeH)
        if (Calls.fakeMedia.isNotEmpty()) cfg.put("fake", Calls.fakeMedia)
        if (!c.outgoing) cfg.put("offer", c.offer)
        js("tvoStart(" + cfg.toString() + ")")
        sendOutputs()
        updatePipParams()
    }

    override fun onResume() {
        super.onResume()
        if (pageReady) {
            js("tvoSetSide(" + JSONObject.quote(if (Cfg.rightHanded(this)) "right" else "left") + ")")
            js("tvoSetPic(" + JSONObject.quote(CallPics.mode(this)) + "," + JSONObject.quote(if (CallPics.mode(this) == "own") CallPics.picDataUri(this) else "") + ")")
        }
    }

    // ---------------------------------------------------------------- настройки страницы, выходы звука, сохранение, экран
    /** Настройки страницы (зеркало, вид, качество, формат, выбор микрофона): хранятся здесь, в странице хранилище браузера выключено. */
    private fun pagePrefs(): JSONObject {
        val o = JSONObject()
        getSharedPreferences("call_ui", Context.MODE_PRIVATE).all.forEach { (k, v) -> if (k.startsWith("tvo") && v is String) o.put(k, v) }
        return o
    }

    private class Out(val id: String, val label: String, val dev: AudioDeviceInfo?)

    private fun outputs(): List<Out> {
        val res = ArrayList<Out>()
        res += Out("auto", getString(R.string.call_out_auto), null)
        if (Build.VERSION.SDK_INT < 23) return res
        runCatching {
            val devs = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            fun add(id: String, label: Int, vararg types: Int) { val d = devs.firstOrNull { it.type in types }; if (d != null) res += Out(id, getString(label), d) }
            add("speaker", R.string.call_out_speaker, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            add("earpiece", R.string.call_out_earpiece, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            add("wired", R.string.call_out_wired, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add("usb", R.string.call_out_usb, AudioDeviceInfo.TYPE_USB_HEADSET)
            add("bt", R.string.call_out_bt, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        }
        return res
    }

    private fun sendOutputs() {
        val sel = getSharedPreferences("call_ui", Context.MODE_PRIVATE).getString("out", "auto") ?: "auto"
        val a = org.json.JSONArray(); val list = outputs()
        list.forEach { a.put(JSONObject().put("id", it.id).put("label", it.label)) }
        js("tvoSetOutputs(" + a.toString() + "," + JSONObject.quote(if (list.any { it.id == sel }) sel else "auto") + ")")
    }

    @Suppress("DEPRECATION")
    private fun applyOutput(o: Out?) {
        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (o == null || o.id == "auto") {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
                val headset = Build.VERSION.SDK_INT >= 23 && am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                am.isSpeakerphoneOn = !headset
            } else if (Build.VERSION.SDK_INT >= 31 && o.dev != null) am.setCommunicationDevice(o.dev)
            else am.isSpeakerphoneOn = o.id == "speaker"
        }
    }

    private fun onSaved(path: String, kind: String) {
        if (path.isEmpty()) js("tvoToast(" + JSONObject.quote(getString(R.string.call_save_fail)) + ")")
        else js("tvoRecSaved(" + JSONObject.quote(path) + "," + JSONObject.quote(kind) + ")")
    }

    private fun pickDir(t: String) {
        val kind = if (t == "video" || t == "audio") t else "shot"
        pendingDir = kind
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 7701)
        }.onFailure { js("tvoToast(" + JSONObject.quote(getString(R.string.call_dir_bad)) + ")") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7701 && resultCode == RESULT_OK && data?.data != null) {
            val ok = saver.setDir(pendingDir, data.data!!)
            js("tvoToast(" + JSONObject.quote(if (ok) getString(R.string.call_dir_set, saver.dirName(pendingDir)) else getString(R.string.call_dir_bad)) + ")")
        }
    }

    private fun openLast() {
        val u = saver.lastUri
        if (u == null) { js("tvoToast(" + JSONObject.quote(getString(R.string.call_open_none)) + ")"); return }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, saver.lastMime.ifEmpty { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            .onFailure { js("tvoToast(" + JSONObject.quote(getString(R.string.call_open_none)) + ")") }
    }

    /** «На весь экран»: системные панели скрываются (по свайпу показываются на время), «Назад» сначала выходит из полного экрана. */
    @Suppress("DEPRECATION")
    private fun setCallFull(on: Boolean) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                val c = window.insetsController ?: return
                if (on) { c.hide(android.view.WindowInsets.Type.systemBars()); c.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
                else c.show(android.view.WindowInsets.Type.systemBars())
            } else {
                window.decorView.systemUiVisibility = if (on) (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE) else View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    // ---------------------------------------------------------------- картинка в картинке
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "app.adbphon.PIP_END" -> js("tvoHangup()")
                "app.adbphon.PIP_REC_V" -> js("document.getElementById('bRec').click()")
                "app.adbphon.PIP_REC_A" -> js("document.getElementById('bRecA').click()")
                "app.adbphon.PIP_SHOT" -> js("document.getElementById('bShot').click()")
            }
        }
    }
    private var pipReg = false

    private fun pipAvailable(): Boolean = Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val rot = vrot % 180 != 0
        val w = if (rot) vh else vw; val h = if (rot) vw else vh
        // допустимые пропорции окошка от 1:2,39 до 2,39:1; без видео собеседника (аудиозвонок) окошко 16:9
        val ratio = if (w > 0 && h > 0) Rational(w, h).let { r -> val f = r.toDouble(); if (f > 2.39) Rational(239, 100) else if (f < 0.42) Rational(42, 100) else r } else Rational(16, 9)
        // кнопки-значки в окошке (подписи только для доступности). Система даёт не больше getMaxNumPictureInPictureActions() кнопок, «Развернуть» показывает сама;
        // порядок важности: завершить, запись видео, скриншот, запись звука
        val acts = ArrayList<RemoteAction>()
        runCatching {
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            fun act(code: Int, action: String, icon: Int, label: Int) {
                acts += RemoteAction(Icon.createWithResource(this, icon), getString(label), getString(label), PendingIntent.getBroadcast(this, code, Intent(action).setPackage(packageName), flags))
            }
            act(4502, "app.adbphon.PIP_END", R.drawable.ic_pip_end, R.string.call_pip_end)
            // система даёт не больше трёх кнопок (на A14 и x96-2 проверено/ожидается 3): вторая кнопка — запись видео, а в голосовом звонке и пока идёт запись звука — запись звука; остальное в полном окне
            val audioRec = (call?.voice == true && recKind != "av") || recKind == "a"
            if (audioRec) act(4505, "app.adbphon.PIP_REC_A", if (recKind == "a") R.drawable.ic_pip_audio_stop else R.drawable.ic_pip_audio, if (recKind == "a") R.string.call_pip_rec_a_stop else R.string.call_pip_rec_a)
            else act(4503, "app.adbphon.PIP_REC_V", if (recKind == "av") R.drawable.ic_pip_rec_stop else R.drawable.ic_pip_rec, if (recKind == "av") R.string.call_pip_rec_v_stop else R.string.call_pip_rec_v)
            act(4504, "app.adbphon.PIP_SHOT", R.drawable.ic_pip_shot, R.string.call_pip_shot)
            val max = runCatching { getMaxNumPictureInPictureActions() }.getOrDefault(3)
            while (acts.size > max && acts.size > 1) acts.removeAt(acts.size - 1)
        }
        val b = PictureInPictureParams.Builder().setAspectRatio(ratio).setActions(acts)
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(web != null && !ended)
        return b.build()
    }

    /** Кнопки окошка шлют широковещательные сообщения: приёмник нужен и при автоматическом входе в окошко (Android 12+). */
    private fun ensurePipReceiver() {
        if (pipReg) return
        runCatching { androidx.core.content.ContextCompat.registerReceiver(this, pipReceiver, IntentFilter().apply { addAction("app.adbphon.PIP_END"); addAction("app.adbphon.PIP_REC_V"); addAction("app.adbphon.PIP_REC_A"); addAction("app.adbphon.PIP_SHOT") }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED); pipReg = true }
    }

    private fun updatePipParams() { if (!pipAvailable() || web == null) return; ensurePipReceiver(); runCatching { setPictureInPictureParams(pipParams()!!) }.onFailure { android.util.Log.w("TvoCall", "pip params: $it") } }

    private fun enterPip() {
        if (!pipAvailable() || web == null || ended) return
        runCatching {
            ensurePipReceiver()
            android.util.Log.i("TvoCall", "pip: кнопок в окошке не больше " + runCatching { getMaxNumPictureInPictureActions() }.getOrDefault(-1) + ", rec=" + recKind)
            val ok = enterPictureInPictureMode(pipParams()!!)
            android.util.Log.i("TvoCall", "pip: вход в окошко " + (if (ok) "принят" else "отклонён системой") + ", isInPip=" + isInPictureInPictureMode)
        }.onFailure { android.util.Log.w("TvoCall", "pip: $it") }
    }

    /** Кнопка «Дом» во время звонка: окно уходит в маленькое окошко, звонок не обрывается. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        android.util.Log.i("TvoCall", "pip: onUserLeaveHint web=" + (web != null) + " ended=" + ended + " inPip=" + isInPictureInPictureMode)
        // Android 12+: окошко включает сама система (setAutoEnterEnabled, параметры задаются заранее в updatePipParams); явный вход здесь система отклоняет
        if (Build.VERSION.SDK_INT < 31 && web != null && !ended && !isInPictureInPictureMode) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        js("tvoPip(" + isInPictureInPictureMode + ")")
    }

    /** «Подогнать экран»: на телефоне окна подгонять нечем, поэтому экран поворачивается под пропорции видео собеседника (с учётом поворота кнопкой). */
    private fun fitScreen() {
        val rot = vrot % 180 != 0
        val w = if (rot) vh else vw; val h = if (rot) vw else vh
        if (w <= 0 || h <= 0) return
        requestedOrientation = if (h > w) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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
                updatePipParams()
                val why = m.optString("why"); android.util.Log.i("TvoCall", "ENDED $why")
                Calls.finish(applicationContext, c)
                // в окошке (PiP) обычный closeTask() на части прошивок (приставка Android 9) оставляет замёрзшее окошко: убираем задачу целиком
                ui.postDelayed({ if (!isFinishing) { if (isInPictureInPictureMode) runCatching { finishAndRemoveTask() }.onFailure { closeTask() } else closeTask() } }, if (why == "ended" || why == "gone") 1200L else 4500L)
            }
            "log" -> android.util.Log.i("TvoCall", "page: " + m.optString("m"))
            "pref" -> getSharedPreferences("call_ui", Context.MODE_PRIVATE).edit().putString(m.optString("k"), m.optString("v")).apply()
            "rec" -> saver.onChunk(m.optString("k"), m.optString("first") == "1", m.optString("ext"), m.optString("peer"), m.optString("b64"))
            "pickdir" -> pickDir(m.optString("t"))
            "openfolder" -> openLast()
            "output" -> { val id = m.optString("id"); getSharedPreferences("call_ui", Context.MODE_PRIVATE).edit().putString("out", id).apply(); applyOutput(outputs().firstOrNull { it.id == id }) }
            "vsize" -> { vw = m.optInt("w"); vh = m.optInt("h"); vrot = m.optInt("rot"); updatePipParams() }
            "fitwin" -> fitScreen()
            "recstate" -> { recKind = m.optString("k"); updatePipParams() }
            "full" -> { fullOn = m.optBoolean("on"); setCallFull(fullOn) }
            "pip" -> enterPip()
            "bg" -> moveTaskToBack(true)   // «В фоне»: окно уходит, звонок идёт (служба и уведомление «Видеозвонок» возвращают в окно)
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

    /** 8.1: закрыть окно звонка вместе с его задачей (taskAffinity пустая): иначе после звонка в списке задач оставалась невидимая задача */
    private fun closeTask() { try { finishAndRemoveTask() } catch (e: Throwable) { try { finish() } catch (_: Throwable) { } } }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullOn) { js("tvoFull(false)"); return }
        if (web != null && !ended) js("tvoHangup()") else if (web == null && !ended && call?.state == "ring") decline() else closeTask()
    }

    override fun onDestroy() {
        val c = call
        if (c != null && !ended && web != null && !hangupSent) { hangupSent = true; Calls.send(applicationContext, c, "hangup") }
        if (c != null && c.state != "over") { if (!c.outgoing && web == null && c.state == "ring") Calls.decline(applicationContext, c); Calls.finish(applicationContext, c) }
        restoreAudio()
        runCatching { saver.abort() }
        if (pipReg) runCatching { unregisterReceiver(pipReceiver) }
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
            if (i.hasExtra("lowforce")) Calls.lowForce = i.getBooleanExtra("lowforce", false)
            if (i.hasExtra("fw")) { Calls.fakeW = i.getIntExtra("fw", 0); Calls.fakeH = i.getIntExtra("fh", 0) }
            if (i.hasExtra("vcodec")) Calls.vcodec = i.getStringExtra("vcodec") ?: ""
            if (i.hasExtra("spkoff")) Calls.spkOff = i.getBooleanExtra("spkoff", false)
            if (i.getBooleanExtra("resetprefs", false)) getSharedPreferences("call_ui", Context.MODE_PRIVATE).edit().clear().apply()
            i.getStringExtra("avatarfile")?.let { CallPics.setAvatarFile(this, java.io.File(it)) }
            i.getStringExtra("picmode")?.let { CallPics.setMode(this, it) }
            i.getStringExtra("auto")?.let { Calls.auto = if (it == "answer" || it == "decline") it else "" }
            if (i.hasExtra("ringoff")) {
                // только для проверок: мелодии и гудок выключены (без звука), громкость 0
                val rc = Ringtones.cfg(this)
                if (i.getBooleanExtra("ringoff", false)) { rc.video = "off"; rc.audio = "off"; rc.wait = "off"; rc.vol = 0 } else { rc.video = "m2"; rc.audio = "m1"; rc.wait = "w1"; rc.vol = 70 }
                Ringtones.save(this, rc)
            }
            if (i.getBooleanExtra("listen", false)) CallListen.start(this)
            if (i.getBooleanExtra("showcode", false)) android.util.Log.i("TvoCall", "mycode=" + Invites.myCode(this) + " name=" + Calls.myName(this))
            if (i.getBooleanExtra("hangup", false)) Calls.current?.let { Calls.decline(applicationContext, it); Calls.finish(applicationContext, it) }
            i.getStringExtra("code")?.let { code -> Calls.dial(this, CallPeople.Person(i.getStringExtra("name") ?: code, code, i.getStringExtra("ip") ?: ""), i.getBooleanExtra("voice", false)) }
        }
        finish()
    }
}
