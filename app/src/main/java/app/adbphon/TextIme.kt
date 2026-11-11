package app.adbphon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Служебный вводный метод «ADB-phon: ввод текста по ADB» (11.11.2026). Без окна и без клавиатуры: нужен только затем,
 * чтобы ДРУГОЕ устройство (Видеоокно Android в «Файлах → Устройства») набрало здесь текст в любой раскладке (кириллица, ї є ґ, эмодзи).
 * `input text` в Android умеет только латиницу, а буфер обмена на Samsung и новых версиях Android из оболочки не вставляется.
 * Порядок (команды выполняет ADB-phon управляющего устройства одной строкой оболочки): ime enable + ime set на этот метод →
 * `am broadcast -a app.adbphon.IME_TEXT -p app.adbphon --es b64 ТЕКСТ_UTF8_BASE64` → приёмник ниже вставляет текст в поле,
 * где стоит курсор (commitText; перевод строки — клавиша Enter) и отвечает кодом 1 → прежняя клавиатура возвращается (ime set), метод выключается.
 * Пока этот метод не выбран текущим, у приёмника нет соединения с полем и он ничего не вставляет (отвечает 0), поэтому посторонние
 * широковещательные сообщения вне этого короткого окна безвредны.
 */
class TextIme : InputMethodService() {
    private var rx: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val ic = currentInputConnection
                var ok = false
                if (ic != null) {
                    val t = runCatching { String(Base64.decode(i.getStringExtra("b64") ?: "", Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault("")
                    if (t.isNotEmpty()) {
                        ic.beginBatchEdit()
                        val parts = t.replace("\r\n", "\n").split('\n')
                        parts.forEachIndexed { n, p ->
                            if (p.isNotEmpty()) ic.commitText(p, 1)
                            if (n < parts.size - 1) {
                                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                            }
                        }
                        ic.endBatchEdit()
                        ok = true
                    }
                }
                if (isOrderedBroadcast) setResultCode(if (ok) 1 else 0)
            }
        }
        rx = r
        ContextCompat.registerReceiver(this, r, IntentFilter("app.adbphon.IME_TEXT"), ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onDestroy() {
        rx?.let { runCatching { unregisterReceiver(it) } }; rx = null
        super.onDestroy()
    }

    override fun onEvaluateInputViewShown(): Boolean = false
    override fun onEvaluateFullscreenMode(): Boolean = false
    override fun onCreateInputView(): View? = null
}
