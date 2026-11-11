package app.adbphon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PersistableBundle
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Приглашения «Принять и соединить» (7.0): тот же формат и тот же порядок, что в «Телефон в окне со звуком» (связь_интернет.ps1).
 * У телефона свой «Код программы» (XXXX-XXXX-XXXX). Приглашение приходит (1) напрямую по Tailscale/локальной сети на порт 47321
 * («TVO1 <шифр>», ответ «OK»), (2) через публичный ретранслятор ntfy.sh (тема из хэша кода; в нём только шифр AES-256-CBC + HMAC-SHA256,
 * ключи PBKDF2 из кода получателя), (3) вставкой текста «У меня приглашение…». Ключ входа одноразовый и живёт не больше часа; в журнал
 * и в окна он не попадает.
 */
object Invites {
    const val PORT = 47321
    private const val ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val TS_PKG = "com.tailscale.ipn"

    class Incoming(val type: String, val from: String, val key: String?, val expires: Long, val hash: String)

    var incoming by mutableStateOf<Incoming?>(null)
    @Volatile var uiActive = false
    @Volatile private var serverOn = false
    private var pollJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("invites", Context.MODE_PRIVATE)
    private fun now() = System.currentTimeMillis() / 1000

    // ---------------------------------------------------------------- код программы
    fun norm(c: String) = c.filter { it.isLetterOrDigit() }.uppercase()
    fun fmt(c: String): String { val n = norm(c); return if (n.length == 12) n.substring(0, 4) + "-" + n.substring(4, 8) + "-" + n.substring(8, 12) else c }
    fun myCode(ctx: Context): String {
        val old = sp(ctx).getString("code", null)
        if (old != null && norm(old).length == 12) return fmt(old)
        val r = SecureRandom()
        val c = (1..12).map { ALPHA[r.nextInt(ALPHA.length)] }.joinToString("")
        sp(ctx).edit().putString("code", fmt(c)).apply()
        return fmt(c)
    }
    fun topic(code: String): String = "tvo-" + sha256Hex("tvo-topic-v1:" + norm(code)).substring(0, 32)
    private fun sha256Hex(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- шифр (как Inv-Seal / Inv-Open на Windows)
    private fun keys(code: String, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val spec = PBEKeySpec(("tvo-key-v1:" + norm(code)).toCharArray(), salt, 120000, 512)
        val b = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return b.copyOfRange(0, 32) to b.copyOfRange(32, 64)
    }

    fun open(code: String, envelope: String): String? = runCatching {
        val all = Base64.decode(envelope.trim(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        if (all.size < 1 + 16 + 16 + 16 + 32 || all[0].toInt() != 1) return null
        val body = all.copyOfRange(0, all.size - 32); val mac = all.copyOfRange(all.size - 32, all.size)
        val salt = all.copyOfRange(1, 17); val iv = all.copyOfRange(17, 33); val ct = all.copyOfRange(33, all.size - 32)
        val (encKey, macKey) = keys(code, salt)
        val m = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(macKey, "HmacSHA256")) }.doFinal(body)
        var diff = 0; for (i in 0 until 32) diff = diff or (m[i].toInt() xor mac[i].toInt())
        if (diff != 0) return null
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(iv)) }
        String(c.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    /** Текст или ссылка tvo://join?d=… → шифр. */
    fun envelopeOf(text: String): String? {
        Regex("tvo://join\\?d=([A-Za-z0-9_\\-]{60,})").find(text)?.let { return it.groupValues[1] }
        val t = text.trim()
        return if (t.length >= 60 && t.matches(Regex("[A-Za-z0-9_\\-]+"))) t else null
    }

    // ---------------------------------------------------------------- приём
    private fun seen(ctx: Context, h: String) = sp(ctx).getString("seen", "").orEmpty().split(',').contains(h)
    fun markSeen(ctx: Context, h: String) {
        val l = (sp(ctx).getString("seen", "").orEmpty().split(',').filter { it.isNotBlank() } + h).takeLast(60)
        sp(ctx).edit().putString("seen", l.joinToString(",")).apply()
    }

    /** true, если это наше приглашение (открылось нашим кодом, не просрочено или уже было показано). */
    fun handle(ctx: Context, envelope: String): Boolean {
        val plain = open(myCode(ctx), envelope) ?: return false
        val o = runCatching { JSONObject(plain) }.getOrNull() ?: return false
        if (o.optInt("v") != 1 || !o.has("e") || !o.has("n")) return false
        val type = o.optString("t")
        if (type == "call") { Calls.onMessage(ctx.applicationContext, o); return true }   // видеозвонок (7.0): свой разбор, не приглашение
        if (type == "join" && !o.has("k")) return false
        val hash = sha256Hex(envelope.trim()).substring(0, 16)
        if (seen(ctx, hash)) return true
        if (o.optLong("e") < now()) return false
        if (incoming?.hash == hash) return true
        incoming = Incoming(type, o.optString("n"), o.optString("k", "").ifEmpty { null }, o.optLong("e"), hash)
        if (!uiActive) notify(ctx.applicationContext, o.optString("n"))
        return true
    }

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        myCode(app)
        uiActive = true
        ensureServer(app)
        if (pollJob?.isActive != true) {
            pollJob = scope.launch {
                while (uiActive) { runCatching { pollRelay(app) }; delay(20_000) }
            }
        }
        schedule(app)
    }

    /** Приёмник на порту 47321 (прямая доставка по Tailscale/локальной сети); запускается и из службы приёма звонков. */
    fun ensureServer(ctx: Context) {
        val app = ctx.applicationContext
        myCode(app)
        if (!serverOn) {
            serverOn = true
            Thread {
                try {
                    ServerSocket(PORT).use { ss ->
                        while (true) {
                            val s = ss.accept()
                            Thread {
                                runCatching {
                                    s.soTimeout = 4000
                                    val line = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).readLine()?.take(8192)
                                    val ok = line != null && line.startsWith("TVO1 ") && handle(app, line.substring(5))
                                    s.getOutputStream().apply { write((if (ok) "OK\n" else "NO\n").toByteArray()); flush() }
                                }
                                runCatching { s.close() }
                            }.start()
                        }
                    }
                } catch (e: Throwable) { serverOn = false; AppLog.e("ADBphon", "invite server", e) }
            }.apply { isDaemon = true; start() }
        }
    }

    fun stopUi() { uiActive = false }

    /** Проверка ретранслятора: возвращает число найденных приглашений. */
    fun pollRelay(ctx: Context): Int {
        val last = sp(ctx).getLong("relay_last", 0L)
        val url = URL("https://ntfy.sh/" + topic(myCode(ctx)) + "/json?poll=1&since=" + (if (last > 0) last.toString() else "30m"))
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 12000
        var n = 0; var mx = last
        try {
            c.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { l ->
                    val o = runCatching { JSONObject(l) }.getOrNull() ?: return@forEach
                    if (o.optString("event") != "message") return@forEach
                    mx = maxOf(mx, o.optLong("time"))
                    if (handle(ctx, o.optString("message"))) n++
                }
            }
        } finally { c.disconnect() }
        if (mx > last) sp(ctx).edit().putLong("relay_last", mx).apply()
        return n
    }

    private fun schedule(ctx: Context) {
        runCatching {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (js.getPendingJob(4431) != null) return
            val ji = JobInfo.Builder(4431, ComponentName(ctx, InviteJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15 * 60 * 1000L).setPersisted(true).build()
            js.schedule(ji)
        }
    }

    private fun notify(ctx: Context, from: String) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("invites", ctx.getString(R.string.inv_channel), NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(ctx, 4430, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, "invites").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(ctx.getString(R.string.inv_notif_title)).setContentText(ctx.getString(R.string.inv_notif_text, from))
                .setAutoCancel(true).setContentIntent(open).build()
            nm.notify(4430, n)
        }
    }

    // ---------------------------------------------------------------- принять
    /** Возвращает: ok, expired, noinstall, manual, fail:<текст>. VPN-конфликт проверяется до вызова (см. VpnGuard). */
    suspend fun accept(ctx: Context, inc: Incoming, say: (String) -> Unit): String {
        val app = ctx.applicationContext
        if (now() > inc.expires) return "expired"
        if (inc.type == "link") { markSeen(app, inc.hash); return "ok" }
        val key = inc.key ?: return "fail:key"
        if (!TsInstall.installed(app)) {
            say(app.getString(R.string.inv_step_dl))
            val r = TsInstall.ensure(app) { say(it) }
            if (r != "ok") return "noinstall:" + r
        }
        say(app.getString(R.string.inv_step_login))
        ensureA11y(app)
        TsLogin.begin(key)
        runCatching { app.startActivity(app.packageManager.getLaunchIntentForPackage(TS_PKG)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        var up = false
        for (i in 0 until 90) {
            delay(1000)
            if (TsLogin.result == "ok") { up = true; break }
            if (TsLogin.result == "badkey") break
            if (TsLogin.result.isEmpty() && !TsLogin.wanted) break
            if (VpnGuard.tailscaleUp(app) && TsLogin.result.isEmpty() && i > 25 && TsLogin.keyDone()) { up = true; break }
            if (now() > inc.expires) break
        }
        if (up) { TsLogin.end(); markSeen(app, inc.hash); return "ok" }
        // запасной путь: ключ в буфер на минуту (метка «чувствительное»), дальше человек вставляет его в Tailscale
        withContext(Dispatchers.Main) {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("key", key)
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            cm.setPrimaryClip(clip)
        }
        scope.launch { delay(60_000); withContext(Dispatchers.Main) { runCatching { (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).clearPrimaryClip() } } }
        return "manual"
    }

    fun decline(ctx: Context, inc: Incoming) { markSeen(ctx, inc.hash); incoming = null }

    /** Служба спецвозможностей ADB-phon нужна для входа по ключу: включаем сами (право WRITE_SECURE_SETTINGS есть у программы или выдаётся по ADB/root). */
    private suspend fun ensureA11y(app: Context) {
        if (AutoPairService.isEnabled(app)) return
        val me = android.content.ComponentName(app, AutoPairService::class.java).flattenToString()
        if (app.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != android.content.pm.PackageManager.PERMISSION_GRANTED && Adb.isConnected) {
            runCatching { Adb.shell("pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS") }
        }
        runCatching {
            val cr = app.contentResolver
            val cur = android.provider.Settings.Secure.getString(cr, "enabled_accessibility_services").orEmpty().split(":").filter { it.isNotBlank() }
            if (me !in cur) android.provider.Settings.Secure.putString(cr, "enabled_accessibility_services", (cur + me).joinToString(":"))
            android.provider.Settings.Secure.putInt(cr, "accessibility_enabled", 1)
        }
        for (i in 0 until 8) { if (AutoPairService.isEnabled(app)) break; delay(500) }
    }
}

class InviteJobService : JobService() {
    override fun onStartJob(p: JobParameters?): Boolean {
        Thread {
            runCatching { Invites.pollRelay(applicationContext) }
            jobFinished(p, false)
        }.start()
        return true
    }
    override fun onStopJob(p: JobParameters?) = false
}

// ---------------------------------------------------------------- другой VPN
object VpnGuard {
    class Info(val pkg: String, val label: String)

    private fun cm(c: Context) = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Suppress("DEPRECATION")
    fun vpnActive(c: Context): Boolean = runCatching {
        val m = cm(c); m.allNetworks.any { m.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrDefault(false)

    /** Tailscale поднят: есть VPN-сеть и это приложение держит её (по имени пакета в dumpsys, если ADB есть; иначе достаточно любой VPN-сети после старта). */
    suspend fun tailscaleUp(c: Context): Boolean {
        if (!vpnActive(c)) return false
        if (!Adb.isConnected) return true
        val o = runCatching { Adb.shell("dumpsys connectivity | grep -i -E 'tailscale|tun0' | head -3") }.getOrDefault("")
        return o.contains("tailscale", true) || o.isNotBlank()
    }

    suspend fun detect(c: Context): Info? {
        val active = vpnActive(c)
        var pkg: String? = null
        if (Adb.isConnected) {
            pkg = runCatching { Adb.shell("settings get secure always_on_vpn_app").trim() }.getOrNull()?.takeIf { it.isNotEmpty() && it != "null" && !it.startsWith("Exception") }
            if (pkg == null && active) pkg = runCatching { Adb.shell("dumpsys connectivity | grep -o -E 'VpnConfig.*user=[A-Za-z0-9_.]+' | head -1") }.getOrNull()
                ?.let { Regex("user=([A-Za-z0-9_.]+)").find(it)?.groupValues?.get(1) }
        }
        if (pkg == "com.tailscale.ipn") pkg = null
        if (pkg == null && !active) return null
        val p = pkg ?: "?"
        val label = runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
        return Info(p, label)
    }

    /** Выключает другой VPN: снимает «всегда включённый», запрещает ему VPN и останавливает. Нужен ADB. Запоминает, что вернуть. */
    suspend fun disable(c: Context, info: Info): Boolean {
        if (!Adb.isConnected || info.pkg == "?") return false
        val wasAlways = runCatching { Adb.shell("settings get secure always_on_vpn_app").trim() }.getOrDefault("") == info.pkg
        c.applicationContext.getSharedPreferences("invites", Context.MODE_PRIVATE).edit().putString("vpn_restore", info.pkg + "|" + (if (wasAlways) "1" else "0")).apply()
        runCatching { Adb.shell("settings put secure always_on_vpn_app null; appops set ${info.pkg} ACTIVATE_VPN deny; am force-stop ${info.pkg}; echo OK") }
        delay(1000)
        return !vpnActive(c)
    }

    fun pendingRestore(c: Context): Pair<String, Boolean>? {
        val s = c.applicationContext.getSharedPreferences("invites", Context.MODE_PRIVATE).getString("vpn_restore", null) ?: return null
        val p = s.split('|'); return p[0] to (p.getOrNull(1) == "1")
    }

    suspend fun restore(c: Context): Boolean {
        val (pkg, always) = pendingRestore(c) ?: return false
        if (!Adb.isConnected) return false
        runCatching { Adb.shell("appops set $pkg ACTIVATE_VPN allow" + (if (always) "; settings put secure always_on_vpn_app $pkg" else "") + "; echo OK") }
        c.applicationContext.getSharedPreferences("invites", Context.MODE_PRIVATE).edit().remove("vpn_restore").apply()
        return true
    }
}

// ---------------------------------------------------------------- Tailscale: установка и вход по ключу
object TsInstall {
    private const val BASE = "https://pkgs.tailscale.com/stable/"
    fun installed(c: Context) = runCatching { c.packageManager.getPackageInfo("com.tailscale.ipn", 0); true }.getOrDefault(false)

    private fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection; c.connectTimeout = 10000; c.readTimeout = 20000
        return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    /** ok или текст ошибки. Скачивание официального установщика, проверка SHA-256, установка через ADB (или окно установки Android). */
    suspend fun ensure(c: Context, say: (String) -> Unit): String = withContext(Dispatchers.IO) {
        runCatching {
            val page = get(BASE)
            val name = Regex("tailscale-android-universal-([0-9.]+)\\.apk").findAll(page).map { it.value to it.groupValues[1] }
                .maxByOrNull { p -> p.second.split('.').joinToString("") { it.padStart(4, '0') } }?.first ?: return@runCatching "no package"
            val want = get("$BASE$name.sha256").trim().take(64).lowercase()
            val f = File(c.cacheDir, name)
            if (!f.isFile || sha(f) != want) {
                val con = URL(BASE + name).openConnection() as HttpURLConnection; con.connectTimeout = 15000; con.readTimeout = 30000
                con.inputStream.use { i -> FileOutputStream(f).use { o -> i.copyTo(o, 64 * 1024) } }
            }
            if (sha(f) != want) { f.delete(); return@runCatching "sha256" }
            say(c.getString(R.string.inv_step_inst))
            if (Adb.isConnected) {
                f.inputStream().use { Adb.installApk(it, f.length(), "-r") }
            } else {
                val uri = androidx.core.content.FileProvider.getUriForFile(c, c.packageName + ".files", f)
                withContext(Dispatchers.Main) {
                    c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
                for (i in 0 until 120) { if (installed(c)) break; delay(1000) }
            }
            runCatching { f.delete() }
            if (installed(c)) "ok" else "install"
        }.getOrElse { it.message ?: "error" }
    }

    private fun sha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Вход Tailscale по ключу через службу спецвозможностей (AutoPairService вызывает [step] на каждое событие, пока [wanted]).
 * Нажимает «Get Started», «OK» в окне согласия VPN, «Try again», находит пункт с «auth key», вставляет ключ в поле и подтверждает.
 * Ключ хранится только в памяти и стирается в [end].
 */
object TsLogin {
    @Volatile var wanted = false
    /** Результат последнего входа: «ok» (Tailscale показал Connected), «badkey» (приложение отвергло ключ), пусто — ещё идёт. */
    @Volatile var result = ""
    /** Системное «Назад» (его задаёт служба спецвозможностей): нужно, чтобы закрыть браузер, который Tailscale открывает для обычного входа. */
    @Volatile var back: (() -> Unit)? = null
    @Volatile private var key: String? = null
    private var lastAct = 0L
    private var tries = 0
    private var keyTyped = false

    fun begin(k: String) { key = k; wanted = true; tries = 0; keyTyped = false; result = "" }
    fun end() { key = null; wanted = false; keyTyped = false }
    fun keyDone() = keyTyped

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, d: Int = 0) {
        if (n == null || d > 40) return
        out.add(n); for (i in 0 until n.childCount) collect(n.getChild(i), out, d + 1)
    }
    private fun label(n: AccessibilityNodeInfo) = (n.text?.toString().orEmpty() + " " + n.contentDescription?.toString().orEmpty()).trim().lowercase()
    private fun clickUp(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n; var h = 0
        while (c != null && h < 6) { if (c.isClickable && c.isEnabled) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK); c = c.parent; h++ }
        return false
    }

    /**
     * Реальный путь в Tailscale для Android (проверен на приставке, Android 9, версия 1.104.5): «Get Started» → окно VPN «ОК» →
     * страница «Welcome to Tailscale» → «Open settings» (шестерёнка) → «Accounts» → меню (три точки) → «Use an auth key» → поле ключа → «Add account».
     * Каждая страница узнаётся по своим подписям, поэтому порядок не важен и повторы безопасны.
     */
    fun step(root: AccessibilityNodeInfo?) {
        if (!wanted || root == null) return
        val t = System.currentTimeMillis(); if (t - lastAct < 900) return
        val pkg = root.packageName?.toString() ?: return
        val nodes = ArrayList<AccessibilityNodeInfo>(); collect(root, nodes)
        val labels = nodes.map { it to label(it) }
        fun has(rx: String) = labels.any { Regex(rx).containsMatchIn(it.second) }
        fun click(vararg rx: String): Boolean {
            for ((n, l) in labels) if (n.isVisibleToUser && rx.any { Regex(it).containsMatchIn(l) } && clickUp(n)) { lastAct = t; return true }
            return false
        }
        if (pkg == "com.android.vpndialogs") { click("^ok$", "^ок$", "^ok ", "^accept$", "^allow$"); return }
        if (pkg == "com.android.permissioncontroller" || pkg == "com.google.android.permissioncontroller" || pkg == "com.android.packageinstaller") { click("^allow$", "^разрешить$"); return }
        // Tailscale сразу после «ОК» VPN открывает браузер с обычным входом: закрываем его «Назад», дальше идём по пути с ключом
        if (pkg != "com.tailscale.ipn") {
            if (!keyTyped && has("login\\.tailscale\\.com")) { back?.invoke(); lastAct = t }
            return
        }
        val k = key
        if (has("invalid key|not in the correct format")) { result = "badkey"; wanted = false; key = null; click("^ok$"); return }
        if (has("we use storage")) { click("^continue$"); return }
        if (keyTyped && has("^connected$") && has("exit node")) { result = "ok"; wanted = false; key = null; return }
        if (has("add an account using an auth key")) {
            val edit = nodes.firstOrNull { it.isEditable && it.isVisibleToUser }
            if (edit != null && k != null && !keyTyped) {
                val b = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, k) }
                if (edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) { keyTyped = true; lastAct = t }
                return
            }
            if (keyTyped) click("^add account$")
            return
        }
        // после «Add account» приложение показывает список аккаунтов с «Log out»: вход выполнен
        if (keyTyped && has("^log out$") && has("add another account")) { result = "ok"; wanted = false; key = null; return }
        if (has("use an auth key")) { click("use an auth key"); return }
        if (has("add another account") && has("reauthenticate")) { click("^menu$"); return }
        if (has("dns settings") && has("^accounts$")) { click("^accounts$"); return }
        if (has("welcome to tailscale|log in to join")) { click("open settings"); return }
        if (click("^get started$")) return
        if (click("^try again$") && ++tries < 6) return
    }
}

// ---------------------------------------------------------------- окна
@Composable
fun InviteDialogHost() {
    val ctx = LocalContext.current
    val inc = Invites.incoming ?: return
    val scope = rememberCoroutineScope()
    var busy by remember(inc.hash) { mutableStateOf(false) }
    var status by remember(inc.hash) { mutableStateOf("") }
    var vpn by remember(inc.hash) { mutableStateOf<VpnGuard.Info?>(null) }
    var result by remember(inc.hash) { mutableStateOf<String?>(null) }

    fun go() {
        busy = true
        scope.launch {
            val r = Invites.accept(ctx, inc) { status = it }
            busy = false
            result = r
            if (r == "ok") { delay(2500); Invites.incoming = null }
        }
    }
    fun accept() {
        if (inc.type == "join") {
            scope.launch {
                val v = VpnGuard.detect(ctx)
                if (v != null) { vpn = v } else go()
            }
        } else go()
    }

    val res = result
    if (vpn != null) {
        val v = vpn!!
        LAlertDialog(
            onDismissRequest = { vpn = null },
            title = { Text(stringResource(R.string.inv_vpn_title), fontSize = 17.sp) },
            text = { Text(stringResource(R.string.inv_vpn_text, v.label), fontSize = 14.sp) },
            confirmButton = {
                TextButton({
                    val vv = v; vpn = null
                    scope.launch {
                        val okOff = VpnSession.begin(ctx, vv)
                        if (okOff) go() else result = if (!Adb.isConnected) "noadb" else "vpnfail"
                    }
                }) { Text(stringResource(R.string.inv_vpn_off)) }
            },
            dismissButton = { TextButton({ vpn = null }) { Text(stringResource(R.string.inv_cancel)) } },
        )
        return
    }
    LAlertDialog(
        onDismissRequest = { if (!busy) Invites.incoming = null },
        title = { Text(stringResource(R.string.inv_dlg_title), fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (inc.type == "link") R.string.inv_dlg_link else R.string.inv_dlg_join, inc.from), fontSize = 14.sp)
                if (busy && status.isNotEmpty()) Text(status, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                if (res != null && res != "ok") Text(
                    when {
                        res == "expired" -> stringResource(R.string.inv_expired)
                        res == "manual" -> stringResource(R.string.inv_manual)
                        res == "noadb" -> stringResource(R.string.inv_vpn_need_adb)
                        res == "vpnfail" -> stringResource(R.string.inv_vpn_fail)
                        res.startsWith("noinstall") -> stringResource(R.string.inv_noinstall, res.substringAfter(':', ""))
                        else -> stringResource(R.string.inv_fail, res.substringAfter(':', res))
                    }, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                if (res == "ok") Text(stringResource(R.string.inv_ok, inc.from), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { if (!busy && res != "ok") TextButton({ accept() }) { Text(stringResource(R.string.inv_accept)) } else if (res == "ok") TextButton({ Invites.incoming = null }) { Text(stringResource(R.string.btn_close)) } },
        dismissButton = { if (!busy && res != "ok") TextButton({ Invites.decline(ctx, inc) }) { Text(stringResource(R.string.inv_decline)) } },
    )
}

@Composable
fun InvitesSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var paste by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    val sess = VpnSession.current
    var idleMin by remember { mutableStateOf(VpnSession.idleMinutes(ctx)) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HintTitle(stringResource(R.string.inv_title), stringResource(R.string.inv_hint), titleWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.inv_code, Invites.myCode(ctx)), Modifier.weight(1f), fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                TextButton({
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", Invites.myCode(ctx)))
                }) { Text(stringResource(R.string.inv_copy)) }
            }
            OutlinedButton({ paste = true; bad = false; text = "" }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.inv_have)) }
            if (sess != null) {
                Text(stringResource(R.string.inv_sess_text, sess.label), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                OutlinedButton({ scope.launch { VpnSession.end(ctx, "кнопка") } }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.inv_sess_restore_now)) }
            }
            Text(stringResource(R.string.inv_sess_idle), fontSize = 12.sp)   // подпись над кнопками: на узком экране (A7) в одну строку не помещается
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                listOf(5, 10, 30, 60).forEach { m ->
                    TextButton({ idleMin = m; VpnSession.setIdleMinutes(ctx, m) }) { Text(if (m == idleMin) "[$m]" else "$m", fontSize = 13.sp) }
                }
            }
        }
    }
    if (paste) {
        LAlertDialog(
            onDismissRequest = { paste = false },
            title = { Text(stringResource(R.string.inv_dlg_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.inv_paste_hint), fontSize = 13.sp)
                    OutlinedTextField(text, { text = it; bad = false }, Modifier.fillMaxWidth(), maxLines = 4)
                    if (bad) Text(stringResource(R.string.inv_bad), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton({
                    val env = Invites.envelopeOf(text)
                    if (env != null && Invites.handle(ctx, env)) paste = false else bad = true
                }) { Text(stringResource(R.string.inv_accept_short)) }
            },
            dismissButton = { TextButton({ paste = false }) { Text(stringResource(R.string.inv_cancel)) } },
        )
    }
    PeopleSection()   // 7.0: «Люди» и видеозвонок (CallUi.kt)
}

/**
 * Точка входа «Приглашения» для других экранов (корень файлов: пункт «Добавить компьютер…»): окно с «Мой код программы» (копировать),
 * «У меня приглашение…» (приём) и пояснением, как дать доступ. Вызов: InvitesDialog(onClose = { ... }).
 */
@Composable
fun InvitesDialog(onClose: () -> Unit) {
    LAlertDialog(
        onDismissRequest = onClose,
        title = { HintTitle(stringResource(R.string.inv_title), stringResource(R.string.inv_give_note), titleSize = 17.sp) },
        text = {
            Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InvitesSection()
            }
        },
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.btn_close)) } },
    )
}
