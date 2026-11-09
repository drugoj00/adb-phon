package app.adbphon

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Автосопряжение с беспроводной отладкой (Android 11+): служба сама открывает нужные пункты настроек,
 * читает код сопряжения и адрес из окна «Подключение с кодом» и подключается. Работает только в окнах настроек
 * и только пока пользователь нажал «Подключить автоматически».
 */
class AutoPairService : AccessibilityService() {
    private var lastPair = 0L
    private var lastClick = 0L
    private var lastScroll = 0L
    private var scrollDown = true
    private var wasWanted = false
    private var connectTryAt = 0L

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            // страницы настроек статичны: без новых событий проверяем окно раз в секунду, пока идёт автоподключение
            if (Pairing.autoWanted) evaluate() else { wasWanted = false; connectTryAt = 0L }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() { Log.i("TVApps", "AutoPairService подключена"); handler.removeCallbacks(tick); handler.post(tick) }
    override fun onDestroy() { handler.removeCallbacks(tick); super.onDestroy() }
    override fun onInterrupt() {}

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (n == null || depth > 40) return
        out.add(n)
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1)
    }

    private fun label(n: AccessibilityNodeInfo): String = (n.text?.toString().orEmpty() + " " + n.contentDescription?.toString().orEmpty()).trim()

    private fun clickUp(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        var hops = 0
        while (c != null && hops < 6) {
            if (c.isClickable && c.isEnabled) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            c = c.parent
            hops++
        }
        return false
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) { if (Pairing.autoWanted) evaluate() }

    private fun evaluateOff() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString()?.contains("settings", true) != true) return
        val now = System.currentTimeMillis()
        if (now - lastClick < 900) return
        val nodes = ArrayList<AccessibilityNodeInfo>(); collect(root, nodes)
        val labels = nodes.map { it to label(it) }
        val row = labels.firstOrNull { (n, t) -> isWifiDebugTitle(t) && n.isVisibleToUser }
        if (row != null) {
            var sw: AccessibilityNodeInfo? = null
            var c: AccessibilityNodeInfo? = row.first
            var hops = 0
            while (c != null && hops < 5 && sw == null) {
                val sub = ArrayList<AccessibilityNodeInfo>(); collect(c, sub)
                sw = sub.firstOrNull { it.className?.contains("Switch") == true && it.isVisibleToUser }
                c = c.parent; hops++
            }
            lastClick = now
            if (sw != null && sw.isChecked) { Log.i("TVApps", "AutoPair: выключить беспроводную отладку"); clickUp(sw) }
            else { Pairing.wantSwitchOn = null; Pairing.autoWanted = false; wasWanted = false; runCatching { startActivity(android.content.Intent(applicationContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) } }
            return
        }
        val onDev = labels.any { (_, t) -> t.contains("разработчик", true) || t.contains("розробник", true) || t.contains("developer", true) }
        if (onDev && now - lastScroll > 1000) {
            val sc = nodes.firstOrNull { it.isScrollable && it.isVisibleToUser }
            if (sc != null) { lastScroll = now; sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
        }
    }

    /** Режим «открыть страницу отладки по Wi-Fi»: находим строку в параметрах разработчика и нажимаем её (переключатель не трогаем), листая вниз. */
    private fun evaluateOpen() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString()?.contains("settings", true) != true) return
        val now = System.currentTimeMillis()
        val nodes = ArrayList<AccessibilityNodeInfo>(); collect(root, nodes)
        val labels = nodes.map { it to label(it) }
        if (labels.any { (_, t) -> PAIR.any { t.contains(it, true) } }) { Pairing.openOnly = false; Pairing.autoWanted = false; wasWanted = false; return }   // страница открыта
        if (now - lastClick < 900) return
        val row = labels.firstOrNull { (n, t) -> isWifiDebugTitle(t) && n.isVisibleToUser }
        if (row != null) { lastClick = now; clickUp(row.first); return }
        val onDev = labels.any { (_, t) -> t.contains("разработчик", true) || t.contains("розробник", true) || t.contains("developer", true) }
        if (onDev && now - lastScroll > 450) {
            val sc = nodes.firstOrNull { it.isScrollable && it.isVisibleToUser }
            if (sc != null) { lastScroll = now; sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
        }
    }

    private fun evaluate() {
        if (Pairing.openOnly) { evaluateOpen(); return }
        // режим «выключить отладку по Wi-Fi»: находим переключатель и выключаем
        if (Pairing.wantSwitchOn == false) { evaluateOff(); return }
        // связь уже появилась (в том числе после включения переключателя, когда пара давно сделана): закончить и вернуть программу
        if (Adb.isConnected) {
            Pairing.autoWanted = false
            runCatching { startActivity(android.content.Intent(applicationContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            Fm.onAdbReady()
            return
        }
        // новый запуск автоподключения: страница открыта заново с начала (CLEAR_TASK), листаем вниз до пункта «Беспроводная отладка»
        if (!wasWanted) { wasWanted = true; scrollDown = true }
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (!pkg.contains("settings", true)) return
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collect(root, nodes)
        val labels = nodes.map { it to label(it) }

        // 1. окно с кодом: шесть цифр и адрес «ip:порт»
        val code = labels.map { it.second }.firstOrNull { Regex("\\d{6}").matches(it.replace(" ", "")) }?.replace(" ", "")
        val ipPort = labels.map { it.second }.firstNotNullOfOrNull { Regex("(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{3,5})").find(it) }
        val now = System.currentTimeMillis()
        if (code != null && ipPort != null) {
            if (now - lastPair > 8000) {
                lastPair = now
                Log.i("TVApps", "AutoPair: код найден, порт ${ipPort.groupValues[2]}")
                Pairing.autoPair(applicationContext, ipPort.groupValues[1], ipPort.groupValues[2].toInt(), code)
            }
            return
        }

        if (now - lastClick < 900) return
        fun act(tag: String, n: AccessibilityNodeInfo): Boolean {
            lastClick = now
            Log.i("TVApps", "AutoPair: $tag")
            return clickUp(n)
        }
        // 2. всплывающий вопрос «Разрешить беспроводную отладку в этой сети?»
        val allow = labels.firstOrNull { (n, t) -> n.className?.contains("Button") == true && t.lowercase() in ALLOW }
        val askText = labels.any { (_, t) -> WIRELESS.any { w -> t.contains(w, true) } || t.contains("network", true) || t.contains("сети", true) || t.contains("мереж", true) }
        if (allow != null && askText) { act("разрешить", allow.first); return }
        // 3. страница «Беспроводная отладка»: пункт «Подключение устройства с кодом сопряжения»
        val pairItem = labels.firstOrNull { (_, t) -> PAIR.any { t.contains(it, true) } }
        if (pairItem != null) {
            // пара с этим ключом могла быть сделана раньше: сначала пробуем просто подключиться, чтобы не плодить новые записи «ADB-phon» в списке подключённых устройств
            if (connectTryAt == 0L) {
                connectTryAt = now
                Log.i("TVApps", "AutoPair: сначала пробую подключиться без нового сопряжения")
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    repeat(3) { if (!Adb.isConnected) { Adb.connectWireless(applicationContext, null); if (!Adb.isConnected) kotlinx.coroutines.delay(1200) } }
                }
                return
            }
            if (now - connectTryAt < 9000) return
            act("пункт кода", pairItem.first); return
        }
        // 4. строка «Беспроводная отладка» в разделе для разработчиков: переключатель этой строки и сама строка
        val row = labels.firstOrNull { (n, t) -> isWifiDebugTitle(t) && n.isVisibleToUser }
        if (row != null) {
            // переключатель в этой же строке (до четырёх уровней вверх)
            var sw: AccessibilityNodeInfo? = null
            var c: AccessibilityNodeInfo? = row.first
            var hops = 0
            while (c != null && hops < 5 && sw == null) {
                val sub = ArrayList<AccessibilityNodeInfo>(); collect(c, sub)
                sw = sub.firstOrNull { it.className?.contains("Switch") == true && it.isVisibleToUser }
                c = c.parent; hops++
            }
            if (sw != null && !sw.isChecked) { act("включить беспроводную отладку", sw); return }
            act("открыть страницу беспроводной отладки", row.first)
            return
        }
        // 5. строки «Беспроводная отладка» на экране нет: у Samsung она далеко внизу параметров разработчика, листаем список
        //    сначала вниз, дойдя до конца вверх (страницу пересматриваем каждую секунду). Листаем только в параметрах разработчика.
        val onDev = labels.any { (_, t) -> t.contains("разработчик", true) || t.contains("розробник", true) || t.contains("developer", true) }
        if (onDev && now - lastScroll > 1000) {
            val sc = nodes.firstOrNull { it.isScrollable && it.isVisibleToUser }
            if (sc != null) {
                lastScroll = now
                val ok = sc.performAction(if (scrollDown) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                Log.i("TVApps", "AutoPair: листаю ${if (scrollDown) "вниз" else "вверх"}: $ok")
                if (!ok) scrollDown = !scrollDown
            }
        }
    }

    companion object {
        private val ALLOW = setOf("allow", "разрешить", "дозволити", "ok", "ок", "always allow on this network", "всегда разрешать в этой сети")
        // пункт «подключить с кодом» (у Samsung «с помощью кода подключения»); пункт с QR-кодом сюда не входит
        private val PAIR = listOf(
            "pairing code", "connection code", "кодом сопряжения", "кода сопряжения", "кода подключения", "кодом подключения",
            "коду підключення", "кодом підключення", "кодом сполучення", "коду сполучення", "с помощью кода",
        )
        private val WIRELESS = listOf("Wireless debugging", "Беспроводная отладка", "Отладка по Wi", "Бездротове налагодження", "Налагодження по Wi")

        /** Название пункта «Беспроводная отладка» у разных производителей и на разных языках: известные названия, а для остальных общее правило «Wi-Fi/беспроводн… + отладка/debug», короткий заголовок без слов про режим и USB. */
        fun isWifiDebugTitle(t: String): Boolean {
            if (WIRELESS.any { w -> t.equals(w, true) || t.startsWith(w, true) }) return true
            val l = t.lowercase()
            if (l.length > 34 || "usb" in l || "режим" in l || "режим" in l || "mode" in l || "підключ" in l || "подключ" in l) return false
            val wifi = listOf("wi-fi", "wi‑fi", "wifi", "wlan", "wireless", "беспровод", "бездрот", "вай", "по сети", "по мережі", "network").any { it in l }
            val dbg = listOf("debug", "отладк", "налагод", "налад").any { it in l }
            return wifi && dbg
        }

        fun isEnabled(ctx: Context): Boolean {
            val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val me = ComponentName(ctx, AutoPairService::class.java).flattenToString()
            return s.split(':').any { it == me || it == ComponentName(ctx, AutoPairService::class.java).flattenToShortString() }
        }
    }
}
