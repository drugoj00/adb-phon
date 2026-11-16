package app.adbphon

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 10.0: свой микробраузер на системном WebView ТОЛЬКО для входа в облака, где сервис пускает вход во встроенный WebView (проверено вживую: Яндекс; см. [types]).
 * Для Google Диска не используется: Google блокирует OAuth во встроенных WebView (disallowed_useragent), User-Agent не подменяется, там остаётся внешний браузер.
 * Окно программы с заголовком и кнопками «Назад», «Начать заново», «Закрыть»; адресной строки и вкладок нет. Cookie и кэш этого окна живут только в нём и стираются при
 * «Начать заново» и при выходе. Стрелки-подсказки ищут элемент через JavaScript по тем же правилам (HintRules), что и во внешнем браузере. Нет системного WebView:
 * [open] возвращает false, и вызывающий код откатывается на внешний браузер с текстовой подсказкой.
 */
object CloudWeb {
    /** Облака, для которых вход во встроенный WebView проверен и включён. */
    val types = setOf("yandex")

    /**
     * Решение «окно программы или внешний браузер»: окно только для проверенных облаков (см. [types]), только по https и только если системный WebView есть.
     * Чистая функция: на ней держится откат без WebView (юнит-тест CloudWebTest); false: внешний браузер и текстовая подсказка.
     */
    fun shouldUse(type: String?, url: String, webViewPresent: Boolean): Boolean = (type == null || type in types) && url.startsWith("https://") && webViewPresent

    /** Имитация «WebView нет» для проверок отката: файл-маркер в папке данных программы (ничего в системе не отключается). */
    private fun simulatedOff(ctx: Context): Boolean = runCatching {
        java.io.File(ctx.filesDir, "cloudweb_off").exists() || ctx.getExternalFilesDir(null)?.let { java.io.File(it, "cloudweb_off").exists() } == true
    }.getOrDefault(false)

    fun available(ctx: Context): Boolean = !simulatedOff(ctx) && runCatching { WebView.getCurrentWebViewPackage() != null }.getOrDefault(false)

    /** Открыть страницу в микробраузере; false: WebView нет или адрес не https (нужен внешний браузер). */
    fun open(ctx: Context, url: String, service: String?, wizard: Int?, login: Boolean): Boolean {
        if (!shouldUse(null, url, available(ctx))) return false
        return runCatching {
            ctx.startActivity(Intent(ctx, CloudWebActivity::class.java).putExtra("url", url).putExtra("service", service).putExtra("wizard", wizard ?: -1).putExtra("login", login)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }
}

class CloudWebActivity : Activity() {
    private var web: WebView? = null
    private var hv: CloudOverlay.HintView? = null
    private var titleOut: OutlineView? = null
    private val main = Handler(Looper.getMainLooper())
    private var service: String? = null
    private var wizard: Int? = null
    private var login = false
    private var url0 = ""
    private var alive = true

    private val tick = object : Runnable {
        override fun run() {
            if (!alive) return
            if (login && CloudAuth.phase != CloudAuth.WAIT) { CloudReturn.toFront(applicationContext, skipDirect = false); finish(); return }
            scan()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        readIntent(intent)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bg = if (night) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
        val d = resources.displayMetrics.density
        val tv = Marq.tv
        val w = try { GateWebView(this) } catch (e: Throwable) { null }
        if (w == null) { fallback(); return }
        web = w
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.saveFormData = false
        w.settings.setSupportMultipleWindows(false)
        w.isFocusable = true; w.isFocusableInTouchMode = true
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url; val host = u.host.orEmpty()
                // разрешены https и возврат на локальный адрес входа (127.0.0.1: приёмник движка внутри устройства, сеть не нужна); остальное (intent:, market: и т.п.) закрыто
                return !(u.scheme == "https" || (u.scheme == "http" && (host == "127.0.0.1" || host == "localhost")))
            }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) { if (r.isForMainFrame) titleOut?.text = getString(R.string.cw_error) }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(v: WebView, t: String?) { titleOut?.text = (Uri_host(v.url) + (if (t.isNullOrBlank()) "" else "  ·  $t")).take(80) }
        }
        // 10.0: панель без подложки: прозрачная накладка поверх страницы, кнопки только с контуром (чёрная и белая обводка), текст белый с чёрной обводкой
        fun btn(textRes: Int, onClick: () -> Unit) = OutlineView(this, d, tv, true).apply { text = getString(textRes); setOnClickListener { onClick() } }
        val titleV = OutlineView(this, d, tv, false)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt())
            addView(btn(R.string.cw_back) { if (w.canGoBack()) w.goBack() else finish() })
            addView(titleV, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btn(R.string.cl_restart) { restart() })
            addView(btn(R.string.cw_close) { finish() })
        }
        titleOut = titleV
        val frame = FrameLayout(this)
        frame.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val lang = HintRules.lang(this)
        val steps = service?.let { s -> HintRules.steps(this, s, wizard).map { it.labelFor(lang) to it.textFor(lang) } }.orEmpty()
        if (steps.isNotEmpty()) {
            val h = CloudOverlay.HintView(this, tv, steps)
            h.isClickable = false; h.isFocusable = false; h.translationZ = 100f   // выше WebView (его отрисовка может идти поверх соседних слоёв)
            hv = h
            frame.addView(h, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        bar.translationZ = 101f
        frame.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        frame.setBackgroundColor(bg)
        setContentView(frame)
        wipe()
        w.loadUrl(url0)
        w.requestFocus()
        main.postDelayed(tick, 1500)
    }

    @Suppress("FunctionName") private fun Uri_host(u: String?): String = runCatching { android.net.Uri.parse(u).host.orEmpty() }.getOrDefault("")

    private fun readIntent(i: Intent?) {
        url0 = i?.getStringExtra("url").orEmpty(); service = i?.getStringExtra("service"); wizard = i?.getIntExtra("wizard", -1)?.takeIf { it >= 0 }; login = i?.getBooleanExtra("login", false) ?: false
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i); setIntent(i); readIntent(i)
        wipe(); web?.loadUrl(url0)
    }

    private fun fallback() {
        // системного WebView нет или он не запустился: внешний браузер и текстовая подсказка
        if (url0.isNotBlank()) CloudAuth.openUrl(applicationContext, url0)
        service?.let { if (!login) CloudOverlay.arm(applicationContext, it, wizard) }
        finish()
    }

    private fun restart() {
        wipe()
        if (login) CloudAuth.restart(applicationContext) else { web?.loadUrl(url0) }
    }

    /** Cookie, кэш, история, данные форм и хранилище этого окна стираются (они не общие с браузером). */
    private fun wipe() {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush()
            web?.clearCache(true); web?.clearHistory(); web?.clearFormData(); WebStorage.getInstance().deleteAllData()
        }
    }

    private fun scan() {
        val w = web ?: return; val h = hv ?: return
        val id = service ?: return
        val steps = HintRules.steps(this, id, wizard)
        if (steps.isEmpty()) return
        val arr = JSONArray()
        steps.forEachIndexed { i, s -> arr.put(JSONObject().put("i", i).put("x", s.exact).put("f", JSONArray(s.find))) }
        val js = "(function(S){var out={};var els=document.querySelectorAll('a,button,span,div,label,li,h1,h2,h3,h4,input,td,th,p,[role]');var vw=window.innerWidth,vh=window.innerHeight;" +
            "for(var k=0;k<S.length;k++){var s=S[k],best=null,ba=1e18;for(var j=0;j<els.length;j++){var e=els[j];" +
            "var t=((e.innerText||e.value||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.getAttribute('placeholder')||'')).trim().toLowerCase();if(!t||t.length>300)continue;" +
            "var ok=false;for(var m=0;m<s.f.length;m++){var p=s.f[m].toLowerCase();if(s.x?(t===p):(t.indexOf(p)>=0)){ok=true;break;}}if(!ok)continue;" +
            "var r=e.getBoundingClientRect();if(r.width<4||r.height<4||r.bottom<0||r.top>vh||r.right<0||r.left>vw)continue;" +
            "var cs=getComputedStyle(e);if(cs.visibility==='hidden'||cs.display==='none')continue;var a=r.width*r.height;if(a<ba){ba=a;best=r;}}" +
            "if(best)out[s.i]=[best.left,best.top,best.right,best.bottom];}return JSON.stringify({d:window.devicePixelRatio,o:out});})($arr)"
        w.evaluateJavascript(js) { raw ->
            runCatching {
                val s = JSONTokener(raw).nextValue() as? String ?: return@runCatching
                val o = JSONObject(s); val dpr = o.optDouble("d", 1.0); val m = o.getJSONObject("o")
                val loc = IntArray(2); w.getLocationOnScreen(loc)
                val found = HashMap<Int, Rect>()
                for (k in m.keys()) { val a = m.getJSONArray(k); found[k.toInt()] = Rect((loc[0] + a.getDouble(0) * dpr).toInt(), (loc[1] + a.getDouble(1) * dpr).toInt(), (loc[0] + a.getDouble(2) * dpr).toInt(), (loc[1] + a.getDouble(3) * dpr).toInt()) }
                h.setFound(found)
            }
        }
    }

    override fun onKeyDown(code: Int, e: KeyEvent?): Boolean {
        if (code == KeyEvent.KEYCODE_BACK) { val w = web; if (w != null && w.canGoBack()) w.goBack() else finish(); return true }
        return super.onKeyDown(code, e)
    }

    override fun onDestroy() {
        alive = false; main.removeCallbacks(tick)
        wipe()
        runCatching { (web?.parent as? ViewGroup)?.removeView(web); web?.destroy() }
        web = null
        super.onDestroy()
    }
}

/**
 * Кнопка или подпись панели окна входа: без подложки. Кнопка: контур (чёрная и белая линии), при фокусе пультом жёлтое кольцо; текст белый с чёрной обводкой.
 * Рисуется вручную: стандартный TextView при смене цвета в onDraw запускает бесконечную перерисовку.
 */
internal class OutlineView(ctx: Context, private val d: Float, tv: Boolean, private val button: Boolean) : View(ctx) {
    var text: String = ""
        set(v) { field = v; requestLayout(); invalidate() }
    private val size = (if (tv) 18f else 14f) * d
    private val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = size; isFakeBoldText = true; strokeJoin = android.graphics.Paint.Join.ROUND }
    private val bp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
    private val hPad = (if (button) 14f else 4f) * d
    private val minH = ((if (tv) 48 else 38) * d).toInt()

    init { if (button) { isFocusable = true; isClickable = true } }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val want = Math.ceil((tp.measureText(text) + 2 * hPad + 4 * d).toDouble()).toInt()
        val w = when (MeasureSpec.getMode(wSpec)) { MeasureSpec.EXACTLY -> MeasureSpec.getSize(wSpec); MeasureSpec.AT_MOST -> minOf(want, MeasureSpec.getSize(wSpec)); else -> want }
        setMeasuredDimension(w, minH)
    }

    override fun onDraw(c: android.graphics.Canvas) {
        if (button) {
            val r = android.graphics.RectF(3 * d, 3 * d, width - 3 * d, height - 3 * d)
            bp.strokeWidth = 5 * d; bp.color = 0xFF000000.toInt(); c.drawRoundRect(r, 10 * d, 10 * d, bp)
            bp.strokeWidth = 2.5f * d; bp.color = if (isFocused) 0xFFFFD600.toInt() else 0xFFFFFFFF.toInt(); c.drawRoundRect(r, 10 * d, 10 * d, bp)
        }
        val shown = android.text.TextUtils.ellipsize(text, android.text.TextPaint(tp), (width - 2 * hPad).coerceAtLeast(0f), android.text.TextUtils.TruncateAt.END).toString()
        val tw = tp.measureText(shown)
        val x = if (button) (width - tw) / 2 else hPad
        val y = height / 2f - (tp.ascent() + tp.descent()) / 2
        tp.style = android.graphics.Paint.Style.STROKE; tp.strokeWidth = 3.5f * d; tp.color = 0xFF000000.toInt(); c.drawText(shown, x, y, tp)
        tp.style = android.graphics.Paint.Style.FILL; tp.color = 0xFFFFFFFF.toInt(); c.drawText(shown, x, y, tp)
    }

    override fun onFocusChanged(gain: Boolean, dir: Int, prev: Rect?) { super.onFocusChanged(gain, dir, prev); invalidate() }
}

/**
 * WebView окна входа с тем же правилом клавиатуры, что в остальных окнах программы (ImeGate): пока управляют стрелки пульта, поле страницы, на которое перешёл фокус,
 * клавиатуру не открывает (она закрывала бы стрелки и кнопки); «ОК» на поле открывает клавиатуру; «Назад» закрывает клавиатуру (её забирает сама клавиатура), поле остаётся в фокусе.
 * Касание по телефону работает как обычно. На приставке до первого касания управляет пульт.
 */
internal class GateWebView(ctx: Context) : WebView(ctx) {
    /** Последнее действие: стрелки или ОК пульта (касание сбрасывает). */
    private var dpad = Marq.tv
    /** Клавиатуре разрешено открыться для текущего поля. */
    private var allowed = false
    private var allowedAt = 0L
    /** Страница просила клавиатуру для поля, которое сейчас в фокусе (поле в фокусе, ввод ещё не включён). */
    private var editorFocused = false
    private val imm get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager

    private fun gated() = dpad && !allowed

    /** WebView отдаёт соединение ввода только когда в фокусе поле страницы; пока ввод не включён (пульт), соединение не отдаётся и клавиатура не работает. */
    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs)
        editorFocused = ic != null
        return if (gated()) null else ic
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        val ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER
        val arrow = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
        if (e.action == KeyEvent.ACTION_DOWN && (ok || arrow)) dpad = true
        if (arrow && e.action == KeyEvent.ACTION_DOWN && !imeVisible() ) { allowed = false; editorFocused = false }
        if (ok && editorFocused && dpad) {
            // ОК на поле: включить ввод и открыть клавиатуру (Enter в страницу не уходит, иначе пустая форма отправилась бы)
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                allowed = true; allowedAt = System.currentTimeMillis()
                imm.restartInput(this); imm.showSoftInput(this, 0)
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) { dpad = false; allowed = false }
        return super.onTouchEvent(ev)
    }

    private fun imeVisible() = androidx.core.view.ViewCompat.getRootWindowInsets(this)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true

    private val watch = object : Runnable {
        override fun run() {
            val vis = imeVisible()
            // клавиатура выехала сама, пока ввод не включён: закрыть
            if (gated() && vis) imm.hideSoftInputFromWindow(windowToken, 0)
            // клавиатуру закрыли кнопкой «Назад»: ввод выключается, стрелки снова ходят по странице
            if (dpad && allowed && !vis && System.currentTimeMillis() - allowedAt > 1500) allowed = false
            postDelayed(this, 300)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); postDelayed(watch, 300) }
    override fun onDetachedFromWindow() { removeCallbacks(watch); super.onDetachedFromWindow() }
}
