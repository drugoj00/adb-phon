package app.adbphon

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Filter { USER, SYSTEM, BLOAT, DISABLED, REMOVED, ALL }

enum class Act { DISABLE, ENABLE, UNINSTALL, RESTORE, STOP, CLEAR, SAVE }

data class Fav(val name: String, val host: String, val port: Int, val kind: String)

data class FileEntry(val name: String, val isDir: Boolean, val size: Long, val date: String)

data class AppEntry(
    val pkg: String,
    val label: String,
    val system: Boolean,
    val enabled: Boolean,
    val installed: Boolean,
    val version: String,
    val paths: List<String>,
)

/** Пакеты, без которых телефон перестаёт работать: действия над ними блокируются. */
val PROTECTED = setOf(
    "android", "com.android.systemui", "com.android.settings", "com.android.shell",
    "com.android.phone", "com.android.providers.settings", "com.android.providers.telephony",
    "com.android.permissioncontroller", "com.android.packageinstaller", "com.google.android.packageinstaller",
    "com.android.inputdevices", "com.android.server.telecom", "com.android.bluetooth",
    "app.adbphon",
)


class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>().applicationContext

    var connected by mutableStateOf(false)
    /** Подключение начато кнопкой/меню (а не фоновым автоподключением): только тогда после него открывается вкладка «Софт». */
    var manualConnect by mutableStateOf(false)
    private var manualPending = false
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    var apps by mutableStateOf<List<AppEntry>>(emptyList())
    var query by mutableStateOf("")
    var filter by mutableStateOf(Filter.USER)
    var selected by mutableStateOf<Set<String>>(emptySet())

    val consoleLines = mutableStateListOf<String>()
    var deviceInfo by mutableStateOf("")

    private fun t(id: Int, vararg a: Any) = Lang.resources(ctx, lang).getString(id, *a)

    private fun fail(e: Throwable) {
        // соединение оборвалось: показываем «Не подключено» вместо мёртвых экранов
        if (connected && !Adb.isConnected) {
            connected = false
            remoteHost = null
            helperOn = false
        }
        message = t(R.string.msg_failed, e.message ?: e.javaClass.simpleName)
        manualConnect = false
        manualPending = false
    }

    // ---------- подключение ----------

    /** Адрес чужого устройства (Android TV, другой телефон); null = этот телефон. */
    var remoteHost by mutableStateOf<String?>(null)

    private fun hostOf(h: String): String = h.trim().ifEmpty { "127.0.0.1" }

    fun connectTcp(host: String, port: Int = 5555, manual: Boolean = true) = launchBusy {
        if (manual) { Adb.autoOff = false; manualPending = true }
        Adb.connectLocal(port, hostOf(host)).onSuccess { onConnected(host) }.onFailure { if (manual) fail(it) else AppLog.i("ADBphon", "автоподключение к последнему устройству не удалось: ${it.message}") }
    }

    /** Этот телефон через root (Magisk) вместо ADB. */
    fun connectRoot() = launchBusy {
        Adb.autoOff = false
        manualPending = true
        if (Adb.tryRoot(manual = true)) onConnected("") else message = t(R.string.root_fail)
    }

    fun pair(host: String, port: Int, code: String) = launchBusy {
        Adb.pair(port, code.trim(), hostOf(host)).onSuccess { message = t(R.string.msg_paired) }.onFailure { fail(it) }
    }

    fun connectWireless(host: String, manualPort: Int?, manual: Boolean = true) = launchBusy {
        if (manual) { Adb.autoOff = false; manualPending = true }
        Adb.connectWireless(ctx, manualPort, hostOf(host)).onSuccess { onConnected(host) }.onFailure { if (manual) fail(it) else AppLog.i("ADBphon", "автоподключение к последнему устройству не удалось: ${it.message}") }
    }

    fun disconnect() = launchBusy {
        Adb.autoOff = true   // сам не подключать обратно, пока человек не выберет устройство
        manualConnect = false
        manualPending = false
        Adb.helperStop()
        helperOn = false
        Adb.disconnect()
        connected = false
        remoteHost = null
    }

    private suspend fun onConnected(host: String) {
        remoteHost = host.trim().ifEmpty { null }
        connected = true
        if (manualPending) { manualPending = false; manualConnect = true }
        val model = runCatching {
            Adb.shell("getprop ro.product.manufacturer; getprop ro.product.model").lines().filter { it.isNotBlank() }
                .joinToString(" ").trim().replaceFirstChar { it.uppercase() }
        }.getOrDefault("")
        val shown = names[remoteHost ?: ""]?.takeIf { it.isNotBlank() } ?: model.ifBlank { t(R.string.net_name_unknown) }
        val addr = remoteHost ?: t(R.string.net_self)
        message = t(R.string.msg_connected_to, shown, addr)
        if (model.isNotBlank()) {
            val keys = if (remoteHost == null) Discovery.myAddresses(ctx) else setOf(remoteHost!!)
            keys.forEach { setName(it, model) }
        }
        if (remoteHost == null) { loadApps(); runCatching { Storage.grant(ctx) } }
        refreshInfo()
        listDir("/sdcard")
    }

    // ---------- поиск устройств в сети ----------

    val found = mutableStateListOf<Found>()
    var scanning by mutableStateOf(false)
    var scanned by mutableStateOf(false)
    var lastDevice by mutableStateOf<Found?>(null)

    private val prefs get() = ctx.getSharedPreferences("adbphon", android.content.Context.MODE_PRIVATE)

    init {
        Hooks.refresh = { if (Adb.isConnected) { if (!connected && !busy) launchBusy { onConnected(Adb.currentHost) } } else connected = false }
        Hooks.reloadApps = { loadApps() }
        prefs.getString("last", null)?.split("|")?.takeIf { it.size == 4 }?.let {
            lastDevice = Found(it[0], it[1].toIntOrNull() ?: 5555, it[2], it[3] == "1")
        }
        // вкладки управления должны показываться только при настоящем подключении: при запуске пробуем последнее чужое устройство
        lastDevice?.takeIf { !it.self && !Adb.autoOff && !Adb.isConnected }?.let { connectFound(it, manual = false) }
    }

    private fun remember(f: Found) {
        lastDevice = f
        prefs.edit().putString("last", "${f.host}|${f.port}|${f.kind}|${if (f.self) 1 else 0}").apply()
    }

    fun scanDevices() {
        if (scanning) return
        viewModelScope.launch {
            scanning = true
            found.clear()
            val add: (Found) -> Unit = { f ->
                viewModelScope.launch {
                    if (found.none { it.host == f.host && it.port == f.port }) { found.add(f); resolveName(f.host) }
                }
            }
            runCatching {
                kotlinx.coroutines.coroutineScope {
                    val a = launch { Discovery.scanMdns(ctx, 6000, add) }
                    val b = launch { Discovery.scanTcp(ctx, add) }
                    a.join(); b.join()
                }
            }
            scanning = false
            scanned = true
        }
    }

    /** Подключиться к найденному устройству: свой телефон идёт через localhost, чужие по IP. */
    fun connectFound(f: Found, manual: Boolean = true) {
        val host = if (f.self) "" else f.host
        if (f.kind == "tcp") connectTcp(host, f.port, manual) else connectWireless(host, f.port, manual)
        if (manual) remember(f)
    }

    // ---------- избранные устройства ----------

    /** Имена устройств по IP: из памяти, из системы и из имени в сети (роутер). */
    val names = androidx.compose.runtime.mutableStateMapOf<String, String>()

    private fun loadNames() {
        prefs.getString("names", "").orEmpty().split("\n").forEach { line ->
            val p = line.split("\t")
            if (p.size == 2 && p[1].isNotBlank()) names[p[0]] = p[1]
        }
        val self = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim().replaceFirstChar { it.uppercase() }
        Discovery.myAddresses(ctx).forEach { names[it] = self }
    }

    private fun setName(host: String, name: String) {
        if (name.isBlank()) return
        names[host] = name
        prefs.edit().putString("names", names.entries.joinToString("\n") { it.key + "\t" + it.value.replace('\t', ' ').replace('\n', ' ') }).apply()
    }

    /** Имя из сети (DHCP/mDNS через роутер); в фоне, чтобы не тормозить поиск. */
    private fun resolveName(host: String) {
        if (names.containsKey(host)) return
        viewModelScope.launch(Dispatchers.IO) {
            val n = runCatching { java.net.InetAddress.getByName(host).canonicalHostName }.getOrNull()
            if (n != null && n != host && !n.all { it.isDigit() || it == '.' }) {
                withContext(Dispatchers.Main) { if (!names.containsKey(host)) names[host] = n.substringBefore('.') }
            }
        }
    }

    var theme by mutableStateOf("auto")
    var lang by mutableStateOf("auto")

    fun changeTheme(v: String) {
        theme = v
        prefs.edit().putString("theme", v).apply()
    }

    fun changeLang(v: String) {
        lang = v
        prefs.edit().putString("lang", v).apply()
    }

    /** Сторона окна для пальца во вкладках «Касание» и «Мышь»: справа (по умолчанию) или слева. */
    var padRight by mutableStateOf(true)

    /** «Воздух»: true — касание включает до следующего касания, false — работает, пока держишь кнопку. */
    var airToggle by mutableStateOf(true)

    fun changeAirToggle(v: Boolean) {
        airToggle = v
        prefs.edit().putBoolean("air_toggle", v).apply()
    }

    fun changePadRight(right: Boolean) {
        padRight = right
        prefs.edit().putBoolean("pad_right", right).apply()
    }

    var keepScreenOn by mutableStateOf(false)
    var exitOnBack by mutableStateOf(true)
    var ramSound by mutableStateOf(true)
    fun changeRamSound(on: Boolean) {
        ramSound = on
        prefs.edit().putBoolean("ram_sound", on).apply()
    }

    fun changeKeepScreenOn(on: Boolean) {
        keepScreenOn = on
        prefs.edit().putBoolean("keep_on", on).apply()
    }

    fun changeExitOnBack(on: Boolean) {
        exitOnBack = on
        prefs.edit().putBoolean("exit_back", on).apply()
    }

    /** Полный выход: помощник, соединение. Вызывается перед закрытием процесса. */
    fun shutdown() {
        kotlinx.coroutines.runBlocking {
            Adb.helperStop()
            Adb.disconnect()
        }
        connected = false
        remoteHost = null
        helperOn = false
    }

    val favs = mutableStateListOf<Fav>()

    init {
        loadNames()
        loadFavs()
        theme = prefs.getString("theme", "auto") ?: "auto"
        lang = prefs.getString("lang", "auto") ?: "auto"
        padRight = prefs.getBoolean("pad_right", true)
        airToggle = prefs.getBoolean("air_toggle", true)
        keepScreenOn = prefs.getBoolean("keep_on", false)
        exitOnBack = prefs.getBoolean("exit_back", true)
        ramSound = prefs.getBoolean("ram_sound", true)
    }

    private fun loadFavs() {
        favs.clear()
        prefs.getString("favs", "").orEmpty().split("\n").filter { it.isNotBlank() }.forEach { line ->
            val p = line.split("\t")
            if (p.size == 4) favs.add(Fav(p[0], p[1], p[2].toIntOrNull() ?: 5555, p[3]))
        }
    }

    private fun persistFavs() {
        prefs.edit().putString("favs", favs.joinToString("\n") { "${it.name.replace('\t', ' ').replace('\n', ' ')}\t${it.host}\t${it.port}\t${it.kind}" }).apply()
    }

    fun saveFav(name: String, host: String, port: Int, kind: String) {
        val clean = name.trim().ifBlank { host }
        favs.removeAll { it.host == host && it.port == port }
        favs.add(Fav(clean, host, port, kind))
        persistFavs()
    }

    fun removeFav(f: Fav) {
        favs.remove(f)
        persistFavs()
    }

    fun favAsFound(f: Fav) = Found(f.host, f.port, f.kind, f.host in Discovery.myAddresses(ctx))

    fun connectFav(f: Fav) = connectFound(favAsFound(f))

    /** Это устройство сейчас подключено (для пункта меню «Отключить» / «Подключить»). */
    fun isCurrent(f: Found): Boolean = connected && (if (f.self) remoteHost == null else remoteHost == f.host)

    // ---------- пульт ----------

    private var lastInputErr = 0L

    /** Лончер иногда «залипает»: стрелки доходят, но выделение не двигается. Перезапуск лончера (force-stop + «Домой») это лечит. */
    fun restartLauncher() = launchBusy {
        val out = runCatching { Adb.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME") }.getOrDefault("")
        val pkg = out.lines().map { it.trim() }.lastOrNull { it.contains('/') }?.substringBefore('/')
        if (pkg.isNullOrBlank() || pkg == "android") {
            message = t(R.string.launcher_unknown)
        } else {
            runCatching { Adb.shell("am force-stop $pkg; input keyevent 3") }
            message = t(R.string.launcher_restarted, pkg)
        }
    }

    /** Быстрые клавиши: помощник без виртуальной мыши (только касание и клавиши). Запускается, когда открыты «Кнопки» или «Свайп». */
    fun useKeyHelper() {
        viewModelScope.launch {
            if (!connected || Adb.helperKeys && !helperOn) return@launch
            Adb.helperStop()
            helperOn = false
            Adb.helperStart(ctx, true)
        }
    }

    /** Команда ввода (кнопка, касание, свайп): если устройство ответило ошибкой — показываем причину, а не молчим. */
    private fun sendInput(cmd: String) {
        viewModelScope.launch {
            val out = runCatching { Adb.shell(cmd + " 2>&1") }.getOrElse { it.message ?: it.javaClass.simpleName }.trim()
            val bad = out.contains("Exception", true) || out.contains("denied", true) || out.contains("not found", true) ||
                out.contains("INJECT_EVENTS", true) || out.contains("error", true) || out.contains("Stream closed", true)
            if (bad && System.currentTimeMillis() - lastInputErr > 4000) {
                lastInputErr = System.currentTimeMillis()
                message = if (out.contains("INJECT_EVENTS", true) || out.contains("SecurityException", true)) t(R.string.input_denied)
                else t(R.string.input_failed, out.take(180))
            }
        }
    }

    fun key(code: Int) {
        if (Adb.helperKeys) Adb.helperSend("k $code") else sendInput("input keyevent $code")
    }

    /** Долгое нажатие кнопки (например OK на приставке). */
    fun keyLong(code: Int) {
        if (Adb.helperKeys) Adb.helperSend("kl $code") else sendInput("input keyevent --longpress $code")
    }

    fun typeText(text: String) {
        if (text.isBlank()) return
        val safe = text.replace("'", "").replace("\\", "").replace(" ", "%s")
        viewModelScope.launch { runCatching { Adb.shell("input text '$safe'") } }
    }

    var screenW by mutableStateOf(1080)
    var screenH by mutableStateOf(2400)

    fun loadScreenSize() {
        viewModelScope.launch {
            // сразу после подключения первый запрос бывает пустым: пробуем до трёх раз
            repeat(3) { attempt ->
                val out = runCatching { Adb.shell("wm size") }.getOrDefault("")
                val m = Regex("(\\d+)x(\\d+)").findAll(out).lastOrNull()
                if (m != null) {
                    screenW = m.groupValues[1].toInt(); screenH = m.groupValues[2].toInt()
                    return@launch
                }
                if (attempt < 2) kotlinx.coroutines.delay(800)
            }
        }
    }

    var helperOn by mutableStateOf(false)

    fun setHelper(on: Boolean) = launchBusy {
        if (on) {
            Adb.helperStart(ctx).onSuccess { helperOn = true }.onFailure { helperOn = false; fail(it) }
        } else {
            Adb.helperStop()
            helperOn = false
        }
    }

    private var showTouchesPrev: String? = null

    /** Белые кружки на экране устройства в местах касаний (настройка «Показывать касания»); при выходе возвращается прежнее значение. */
    fun setShowTouches(on: Boolean) {
        viewModelScope.launch {
            runCatching {
                if (on) {
                    if (showTouchesPrev == null) {
                        val cur = Adb.shell("settings get system show_touches").trim()
                        showTouchesPrev = if (cur == "1") "1" else "0"
                    }
                    Adb.shell("settings put system show_touches 1")
                } else {
                    val p = showTouchesPrev ?: return@runCatching
                    Adb.shell("settings put system show_touches $p")
                    showTouchesPrev = null
                }
            }
        }
    }

    fun hMove(dx: Int, dy: Int) {
        if (dx != 0 || dy != 0) Adb.helperSend("m $dx $dy")
    }

    fun hClick() {
        Adb.helperSend("b 1")
        Adb.helperSend("b 0")
    }

    fun hLongClick() {
        viewModelScope.launch {
            Adb.helperSend("b 1")
            kotlinx.coroutines.delay(700)
            Adb.helperSend("b 0")
        }
    }

    fun hScroll(n: Int) = Adb.helperSend("w $n")

    /** Настоящее касание через помощника: быстро и плавно. Если помощник касаться не умеет, остаётся медленный `input`. */
    val touchViaHelper: Boolean get() = helperOn && Adb.helperTouch

    fun hTouch(action: String, x: Int, y: Int) = Adb.helperSend("$action $x $y")

    /** Реальный курсор в центр экрана: сначала в угол, затем на половину (один отсчёт мыши ≈ 1,4 px при обычной скорости указателя). */
    fun hCenter() {
        viewModelScope.launch {
            Adb.helperSend("m -6000 -6000")
            kotlinx.coroutines.delay(250)
            Adb.helperSend("m ${(screenW * 0.36f).toInt()} ${(screenH * 0.36f).toInt()}")
        }
    }

    fun tap(x: Int, y: Int) {
        if (touchViaHelper) { hTouch("tap", x, y); return }
        viewModelScope.launch { runCatching { Adb.shell("input tap $x $y") } }
    }

    fun longPress(x: Int, y: Int) {
        if (touchViaHelper) { hTouch("lp", x, y); return }
        viewModelScope.launch { runCatching { Adb.shell("input swipe $x $y $x $y 700") } }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) {
        if (touchViaHelper) {
            viewModelScope.launch {
                // протянуть палец по прямой за указанное время, шагами по ~16 мс
                hTouch("td", x1, y1)
                val steps = (ms / 16).coerceIn(2, 40)
                for (i in 1..steps) {
                    kotlinx.coroutines.delay((ms / steps).toLong().coerceAtLeast(1))
                    hTouch("tm", x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps)
                }
                hTouch("tu", x2, y2)
            }
            return
        }
        viewModelScope.launch { runCatching { Adb.shell("input swipe $x1 $y1 $x2 $y2 $ms") } }
    }

    // ---------- приложения ----------

    fun loadApps() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.Default) {
                val pm = ctx.packageManager
                val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
                pm.getInstalledPackages(flags).mapNotNull { pi ->
                    val ai = pi.applicationInfo ?: return@mapNotNull null
                    val paths = buildList {
                        ai.sourceDir?.let { add(it) }
                        ai.splitSourceDirs?.let { addAll(it) }
                    }
                    AppEntry(
                        pkg = pi.packageName,
                        label = pm.getApplicationLabel(ai).toString(),
                        system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                        enabled = ai.enabled,
                        installed = ai.flags and FLAG_INSTALLED != 0,
                        version = pi.versionName ?: "",
                        paths = paths,
                    )
                }.sortedBy { it.label.lowercase() }
            }
            AppLog.i("ADBphon", "apps loaded: ${list.size}")
            apps = list
            selected = selected.filter { id -> list.any { it.pkg == id } }.toSet()
        }
    }

    fun visibleApps(): List<AppEntry> {
        val q = query.trim().lowercase()
        return apps.filter { a ->
            when (filter) {
                Filter.USER -> !a.system && a.installed
                Filter.SYSTEM -> a.system && a.installed
                Filter.BLOAT -> a.installed && Debloat.known.containsKey(a.pkg)
                Filter.DISABLED -> !a.enabled && a.installed
                Filter.REMOVED -> !a.installed
                Filter.ALL -> true
            } && (q.isEmpty() || a.label.lowercase().contains(q) || a.pkg.lowercase().contains(q))
        }
    }

    fun toggle(pkg: String) {
        selected = if (pkg in selected) selected - pkg else selected + pkg
    }

    fun selectAllVisible() {
        selected = visibleApps().map { it.pkg }.toSet()
    }

    /** В списке «Мусор» отмечает безопасные включённые приложения (защищённые пропускаются). */
    fun selectSafeBloat() {
        selected = visibleApps().filter { Debloat.known[it.pkg]?.risk == Risk.SAFE && it.enabled && it.pkg !in PROTECTED }
            .map { it.pkg }.toSet()
    }

    fun clearSelection() {
        selected = emptySet()
    }

    /** Действие над выбранными приложениями; возвращает пропущенные защищённые. */
    fun run(act: Act) {
        val targets = apps.filter { it.pkg in selected }
        val skipped = targets.filter { it.pkg in PROTECTED && act != Act.SAVE && act != Act.STOP }.map { it.pkg }
        val work = targets.filter { it.pkg !in skipped }
        launchBusy {
            var ok = 0
            var bad = 0
            // перед удалением обычных приложений сохраняем их APK (можно вернуть или поставить старую версию)
            if (act == Act.UNINSTALL && remoteHost == null && Cfg.backupOnDelete(ctx)) {
                val saved = withContext(Dispatchers.IO) { work.filter { !it.system }.count { runCatching { AppBackups.save(ctx, it.pkg) }.getOrNull() != null } }
                if (saved > 0) AppLog.i("ADBphon", "copies saved: $saved")
            }
            for (a in work) {
                val out = runCatching { Adb.shell(command(act, a)) }
                val text = out.getOrDefault("")
                val good = out.isSuccess && !text.contains("Failure", true) && !text.contains("Exception", true) && !text.contains("Error", true)
                if (good) ok++ else bad++
            }
            message = buildString {
                append(if (act == Act.SAVE) t(R.string.saved_to) + " — " else "")
                append(t(R.string.result_ok_fail, ok, bad))
                if (skipped.isNotEmpty()) append("\n" + t(R.string.protected_skipped, skipped.joinToString(", ")))
            }
            loadApps()
        }
    }

    private fun command(act: Act, a: AppEntry): String = when (act) {
        Act.DISABLE -> "pm disable-user --user 0 ${a.pkg}"
        Act.ENABLE -> "pm enable --user 0 ${a.pkg}"
        Act.UNINSTALL -> if (a.system) "pm uninstall --user 0 ${a.pkg}" else "pm uninstall ${a.pkg}"
        Act.RESTORE -> "cmd package install-existing --user 0 ${a.pkg}"
        Act.STOP -> "am force-stop ${a.pkg}"
        Act.CLEAR -> "pm clear ${a.pkg}"
        Act.SAVE -> buildString {
            append("mkdir -p /sdcard/Download/ADB-phon")
            a.paths.forEachIndexed { i, p ->
                val suffix = if (i == 0) "" else "-split$i"
                append(" && cp '$p' '/sdcard/Download/ADB-phon/${a.pkg}-${a.version}$suffix.apk'")
            }
        }
    }

    fun installApk(uri: Uri) = launchBusy {
        val cr = ctx.contentResolver
        val tmp = java.io.File(ctx.cacheDir, "install.apk")
        cr.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        val result = runCatching {
            tmp.inputStream().use { Adb.installApk(it, tmp.length()) }
        }
        tmp.delete()
        message = t(R.string.install_result, result.getOrElse { it.message ?: "error" })
        loadApps()
    }

    // ---------- консоль ----------

    fun runConsole(cmd: String) {
        if (cmd.isBlank()) return
        consoleLines.add("$ $cmd")
        launchBusy {
            val out = runCatching { Adb.shell(cmd) }.getOrElse { it.message ?: "error" }
            if (out.isNotEmpty()) consoleLines.add(out)
        }
    }

    fun clearConsole() = consoleLines.clear()

    // ---------- устройство ----------

    fun refreshInfo() {
        viewModelScope.launch {
            val out = runCatching {
                Adb.shell(
                    "echo Model: \$(getprop ro.product.manufacturer) \$(getprop ro.product.model); " +
                        "echo Android: \$(getprop ro.build.version.release) \\(API \$(getprop ro.build.version.sdk)\\); " +
                        "echo Build: \$(getprop ro.build.display.id); " +
                        "echo CPU: \$(getprop ro.product.cpu.abi); " +
                        "echo Screen: \$(wm size | tail -1), \$(wm density | tail -1); " +
                        "echo Storage: \$(df /data | tail -1)"
                )
            }.getOrElse { it.message ?: "error" }
            deviceInfo = out
        }
    }

    /** Одиночная команда быстрых настроек. */
    fun quick(cmd: String) = launchBusy {
        val out = runCatching { Adb.shell(cmd) }.getOrElse { it.message ?: "error" }
        message = out.ifBlank { "OK" }
        refreshInfo()
    }

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            try {
                block()
            } catch (e: Throwable) {
                AppLog.e("ADBphon", "launchBusy error", e)
                fail(e)
            } finally {
                busy = false
            }
        }
    }


    // ---------- права приложений ----------

    var permsFor by mutableStateOf<AppEntry?>(null)
    var perms by mutableStateOf<List<Pair<String, Boolean>>>(emptyList())

    fun loadPerms(a: AppEntry) = launchBusy {
        val out = Adb.shell("dumpsys package ${a.pkg}")
        val list = mutableListOf<Pair<String, Boolean>>()
        var inRuntime = false
        for (line in out.lines()) {
            val t = line.trim()
            if (t.startsWith("runtime permissions:")) { inRuntime = true; continue }
            if (!inRuntime) continue
            val m = Regex("^([\\w.]+): granted=(true|false)").find(t)
            if (m != null) list.add(m.groupValues[1] to (m.groupValues[2] == "true")) else if (list.isNotEmpty() || t.isEmpty().not()) break
        }
        perms = list.distinctBy { it.first }
        permsFor = a
    }

    fun setPerm(pkg: String, perm: String, grant: Boolean) = launchBusy {
        val out = Adb.shell("pm ${if (grant) "grant" else "revoke"} $pkg $perm; echo rc=\$?")
        AppLog.i("ADBphon", "setPerm $perm grant=$grant -> $out")
        if (out.contains("rc=0")) perms = perms.map { if (it.first == perm) it.first to grant else it } else message = out
    }

    fun closePerms() {
        permsFor = null
    }

    // ---------- особые права (спецвозможности и др.) ----------

    var specialFor by mutableStateOf<AppEntry?>(null)

    fun openSpecial(a: AppEntry) { specialFor = a }
    fun closeSpecial() { specialFor = null }

    /** Служба приложения по действию (спецвозможности, слушатель уведомлений): мгновенно, через PackageManager. */
    private fun serviceOf(pkg: String, action: String): android.content.ComponentName? = runCatching {
        ctx.packageManager.queryIntentServices(android.content.Intent(action).setPackage(pkg), 0)
            .firstOrNull()?.serviceInfo?.let { android.content.ComponentName(it.packageName, it.name) }
    }.getOrNull()

    fun special(pkg: String, kind: String) = launchBusy {
        val accC = serviceOf(pkg, "android.accessibilityservice.AccessibilityService")
        val nlC = serviceOf(pkg, "android.service.notification.NotificationListenerService")
        val acc = accC?.flattenToString()
        val accShort = accC?.flattenToShortString()
        val cmd: String = when (kind) {
            "access_on" -> {
                if (acc == null) { message = t(R.string.sp_none); return@launchBusy }
                "E=\$(settings get secure enabled_accessibility_services); " +
                    "case \":\$E:\" in *\":$acc:\"*|*\":$accShort:\"*) ;; *) if [ \"\$E\" = null ] || [ -z \"\$E\" ]; then N=$acc; else N=\"\$E:$acc\"; fi; " +
                    "settings put secure enabled_accessibility_services \"\$N\";; esac; settings put secure accessibility_enabled 1; echo OK $acc"
            }
            "access_off" -> {
                if (acc == null) { message = t(R.string.sp_none); return@launchBusy }
                "E=\$(settings get secure enabled_accessibility_services); " +
                    "N=\$(echo \":\$E:\" | sed \"s#:$acc:#:#g; s#:$accShort:#:#g\" | sed 's#^:##; s#:\$##'); [ -z \"\$N\" ] && N=null; " +
                    "settings put secure enabled_accessibility_services \"\$N\"; echo OK $acc"
            }
            "pin" -> {
                if (acc == null) { message = t(R.string.sp_none); return@launchBusy }
                val sp = ctx.getSharedPreferences("adbphon", android.content.Context.MODE_PRIVATE)
                val list = BootReceiver.pinned(ctx).filter { it != accShort }.toMutableList().apply { if (acc !in this) add(acc) }
                sp.edit().putString("pinned_access", list.joinToString(",")).apply()
                // приложению нужно право записи защищённых настроек: выдаём его по ADB самому себе
                Adb.shell("pm grant app.adbphon android.permission.WRITE_SECURE_SETTINGS")
                "settings put secure accessibility_enabled 1; echo OK pinned $acc"
            }
            "unpin" -> {
                val sp = ctx.getSharedPreferences("adbphon", android.content.Context.MODE_PRIVATE)
                sp.edit().putString("pinned_access", BootReceiver.pinned(ctx).filter { it != acc && it != accShort }.joinToString(",")).apply()
                "echo OK unpinned"
            }
            "restricted" -> "appops set $pkg ACCESS_RESTRICTED_SETTINGS allow && echo OK"
            "secure" -> "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS && echo OK"
            "overlay" -> "appops set $pkg SYSTEM_ALERT_WINDOW allow && echo OK"
            "usage" -> "appops set $pkg GET_USAGE_STATS allow && echo OK"
            "files" -> "appops set $pkg MANAGE_EXTERNAL_STORAGE allow && echo OK"
            "install" -> "appops set $pkg REQUEST_INSTALL_PACKAGES allow && echo OK"
            "battery" -> "dumpsys deviceidle whitelist +$pkg"
            "settings" -> "appops set $pkg WRITE_SETTINGS allow && echo OK"
            "notif" -> {
                if (nlC == null) { message = t(R.string.sp_none); return@launchBusy }
                "cmd notification allow_listener ${nlC.flattenToString()}; echo OK"
            }
            else -> return@launchBusy
        }
        val out = Adb.shell(cmd)
        AppLog.i("ADBphon", "special $kind $pkg -> $out")
        message = if (out.contains("Exception") || out.contains("Error") || out.contains("denied")) out.lines().first() else t(R.string.sp_done)
    }

    // ---------- версии самой программы (откат / обновление) ----------

    /** Одна версия: либо файл на телефоне ([path]), либо выпуск на GitHub ([rel]). */
    data class Ver(val label: String, val path: String?, val rel: Updater.Release?)

    var versionsOpen by mutableStateOf(false)
    var localVersions by mutableStateOf<List<Ver>>(emptyList())
    var githubVersions by mutableStateOf<List<Ver>>(emptyList())
    var githubState by mutableStateOf("") // "" — не загружали, "loading", "ok", "error"
    val appVersion: String get() = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()

    fun closeVersions() { versionsOpen = false }

    fun openVersions() {
        versionsOpen = true
        localVersions = (VersionKeeper.dir(ctx).listFiles { f -> f.name.endsWith(".apk") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { Ver(it.name.removePrefix("ADB-phon-").removeSuffix(".apk"), it.absolutePath, null) }
        githubState = "loading"
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { Updater.fetch() } }
                .onSuccess { list ->
                    githubVersions = list.map { Ver("${it.tag} · ${it.date} · ${it.size / 1024 / 1024} МБ", null, it) }
                    githubState = "ok"
                }
                .onFailure { AppLog.e("ADBphon", "github releases", it); githubState = "error" }
        }
    }

    /** Ставит выбранную версию поверх текущей. Нужно подключение к этому же телефону по ADB. */
    fun installVersion(v: Ver) {
        if (!connected || remoteHost != null) { message = t(R.string.ver_need_local); return }
        launchBusy {
            versionsOpen = false
            val path = v.path ?: run {
                message = t(R.string.ver_downloading, v.rel!!.tag)
                withContext(Dispatchers.IO) { runCatching { Updater.download(ctx, v.rel!!) } }
                    .getOrElse { AppLog.e("ADBphon", "download", it); message = t(R.string.ver_dl_failed, it.message ?: "error"); return@launchBusy }
                    .absolutePath
            }
            val src = path.replace("/storage/emulated/0", "/sdcard")
            val f = "'" + src.replace("'", "'\''") + "'"
            // системный сервер не читает /sdcard, поэтому сначала копия в /data/local/tmp;
            // команда уходит в отдельный процесс, потому что наше приложение будет перезаписано
            val tmp = "/data/local/tmp/adbphon-rb.apk"
            val lg = "/data/local/tmp/adbphon-rollback.log"
            val script = "cp $f $tmp && { pm install -r -d --user 0 $tmp > $lg 2>&1; grep -q DOWNGRADE $lg && " +
                "{ pm uninstall -k --user 0 ${ctx.packageName} >> $lg 2>&1; pm install -r --user 0 $tmp >> $lg 2>&1; }; }; rm -f $tmp"
            // сначала файл-сценарий, потом его запуск: так отдельный процесс надёжно переживает закрытие нашего потока
            val line = "setsid nohup sh -c \"$script\" > /dev/null 2>&1 < /dev/null &\nsleep 2\n"
            val b64 = android.util.Base64.encodeToString(line.toByteArray(), android.util.Base64.NO_WRAP)
            Adb.shell("echo $b64 | base64 -d > /data/local/tmp/adbphon-install.sh")
            Adb.shell("sh /data/local/tmp/adbphon-install.sh")
            message = t(R.string.rb_started)
        }
    }

    // ---------- резервная копия настроек ----------

    var backupFolder by mutableStateOf<Uri?>(SettingsBackup.folder(getApplication<Application>().applicationContext))
    var backupItems by mutableStateOf<List<SettingsBackup.Item>?>(null) // null — окно восстановления закрыто

    fun chooseBackupFolder(uri: Uri) {
        runCatching { SettingsBackup.setFolder(ctx, uri) }
            .onSuccess { backupFolder = uri; message = t(R.string.bk_folder_set, SettingsBackup.folderLabel(uri)) }
            .onFailure { AppLog.e("ADBphon", "backup folder", it); message = t(R.string.bk_failed, it.message ?: "error") }
    }

    fun makeBackup() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { SettingsBackup.backup(ctx) } }
                .onSuccess { message = t(R.string.bk_done, it) }
                .onFailure { AppLog.e("ADBphon", "backup", it); message = t(R.string.bk_failed, it.message ?: "error") }
        }
    }

    fun openRestore() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { SettingsBackup.list(ctx) } }
                .onSuccess { backupItems = it }
                .onFailure { AppLog.e("ADBphon", "backup list", it); message = t(R.string.bk_failed, it.message ?: "error") }
        }
    }

    fun closeRestore() { backupItems = null }

    fun restoreBackup(item: SettingsBackup.Item) {
        backupItems = null
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { SettingsBackup.restore(ctx, item) } }
                .onSuccess { reloadSettings(); message = t(R.string.bk_restored, item.name) }
                .onFailure { AppLog.e("ADBphon", "restore", it); message = t(R.string.bk_failed, it.message ?: "error") }
        }
    }

    /** Перечитывает настройки из хранилища после восстановления. */
    private fun reloadSettings() {
        theme = prefs.getString("theme", "auto") ?: "auto"
        lang = prefs.getString("lang", "auto") ?: "auto"
        keepScreenOn = prefs.getBoolean("keep_on", false)
        exitOnBack = prefs.getBoolean("exit_back", true)
        ramSound = prefs.getBoolean("ram_sound", true)
        padRight = prefs.getBoolean("pad_right", true)
        airToggle = prefs.getBoolean("air_toggle", true)
        logOn = prefs.getBoolean("log_on", false)
        AppLog.setEnabled(ctx, logOn)
        names.clear()
        loadNames()
        loadFavs()
    }

    // ---------- журнал ----------

    var logOn by mutableStateOf(AppLog.enabled)
    var logText by mutableStateOf<String?>(null)

    fun changeLog(on: Boolean) {
        logOn = on
        AppLog.setEnabled(ctx, on)
    }

    fun showLog() { logText = AppLog.tail(300).ifBlank { "" } }
    fun hideLog() { logText = null }

    fun saveLog() = launchBusy {
        val src = AppLog.path()
        if (src.isEmpty()) { message = t(R.string.log_empty); return@launchBusy }
        val name = "adbphon-log-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".txt"
        val s = src.replace("/storage/emulated/0", "/sdcard")
        Adb.shell("mkdir -p /sdcard/Download/ADB-phon && cp '$s' /sdcard/Download/ADB-phon/$name; cp '${s.substringBeforeLast('/')}/crash.txt' /sdcard/Download/ADB-phon/adbphon-crash.txt 2>/dev/null")
        message = t(R.string.log_saved, "Download/ADB-phon/$name")
    }

    // ---------- файлы ----------

    var path by mutableStateOf("/sdcard")
    var files by mutableStateOf<List<FileEntry>>(emptyList())
    var clip by mutableStateOf<Pair<String, Boolean>?>(null)

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun listDir(p: String = path) = launchBusy {
        val out = Adb.shell("ls -la ${q(p.trimEnd('/') + "/")}")
        val rx = Regex("^([dl-])\\S+\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+(\\d{4}-\\d\\d-\\d\\d)\\s+(\\d\\d:\\d\\d)\\s+(.+)$")
        files = out.lines().mapNotNull { rx.find(it.trim()) }.map {
            val (type, size, date, time, name) = it.destructured
            FileEntry(name.substringBefore(" -> "), type != "-", size.toLongOrNull() ?: 0, "$date $time")
        }.filter { it.name != "." && it.name != ".." }
            .sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
        path = p
        if (out.contains("Permission denied") || out.contains("No such file")) message = out.lines().firstOrNull { it.isNotBlank() }
    }

    fun openDir(name: String) = listDir(path.trimEnd('/') + "/" + name)

    fun upDir() {
        val up = path.trimEnd('/').substringBeforeLast('/', "")
        listDir(if (up.isEmpty()) "/" else up)
    }

    private fun full(name: String) = path.trimEnd('/') + "/" + name

    fun deleteFile(name: String) = launchBusy {
        Adb.shell("rm -rf ${q(full(name))}")
        listDir(path)
    }

    fun renameFile(name: String, newName: String) = launchBusy {
        if (newName.isNotBlank() && '/' !in newName) Adb.shell("mv ${q(full(name))} ${q(full(newName))}")
        listDir(path)
    }

    fun newFolder(name: String) = launchBusy {
        if (name.isNotBlank() && '/' !in name) Adb.shell("mkdir -p ${q(full(name))}")
        listDir(path)
    }

    fun setClip(name: String, move: Boolean) {
        clip = full(name) to move
    }

    fun paste() {
        val c = clip ?: return
        launchBusy {
            Adb.shell("${if (c.second) "mv" else "cp -r"} ${q(c.first)} ${q(path.trimEnd('/') + "/")}")
            if (c.second) clip = null
            listDir(path)
        }
    }

    // ---------- снимок, запись, мониторинг ----------

    var monitor by mutableStateOf("")

    fun screenshot() = launchBusy {
        val f = "/sdcard/Download/ADB-phon/screen-\$(date +%Y%m%d-%H%M%S).png"
        val out = Adb.shell("mkdir -p /sdcard/Download/ADB-phon && screencap -p $f && ls /sdcard/Download/ADB-phon | tail -1")
        message = t(R.string.shot_saved, "Download/ADB-phon/" + out.lines().lastOrNull().orEmpty())
    }

    fun record() = launchBusy {
        val f = "/sdcard/Download/ADB-phon/rec-\$(date +%Y%m%d-%H%M%S).mp4"
        Adb.shell("mkdir -p /sdcard/Download/ADB-phon && screenrecord --time-limit 30 $f")
        message = t(R.string.shot_saved, "Download/ADB-phon")
    }

    fun refreshMonitor() {
        viewModelScope.launch {
            monitor = runCatching {
                Adb.shell(
                    "head -3 /proc/meminfo; echo; df -h /data | tail -1; echo; cat /proc/loadavg; echo; " +
                        "dumpsys battery | grep -E 'level|status|plugged'; echo; top -n 1 -b 2>/dev/null | head -12"
                )
            }.getOrElse { it.message ?: "error" }
        }
    }

    fun clearAllCaches() = quick("pm trim-caches 999G")

    fun consumeMessage() {
        message = null
    }

    companion object {
        /** ApplicationInfo.FLAG_INSTALLED (скрытая константа). */
        const val FLAG_INSTALLED = 0x00800000
    }
}
