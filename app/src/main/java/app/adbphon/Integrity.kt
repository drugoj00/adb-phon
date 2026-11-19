package app.adbphon

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Проверка подлинности копии (только в выпускной сборке). Отпечаток нашего ключа выпуска вшит при сборке (в виде двух долей, открытой строки в программе нет).
 * Если копию переделали и подписали чужим ключом (или изменили DEX и ресурсы), опасные действия (связь по ADB и root, установка, обновление программы и модуля
 * «Облака») не выполняются, а на экране появляется окно «Скачайте программу из официального источника». Программа при этом не падает и открывается, чтобы
 * человек понял причину.
 *
 * 14.0: правило «никаких ложных тревог на настоящей копии».
 *  - Подделкой считается только ДОСТОВЕРНОЕ расхождение: два независимых способа чтения подписи (1 PackageManager, 2 файл APK, 3 сертификат из META-INF)
 *    или контрольные суммы DEX и ресурсов (4: каталог архива и сами данные записей), и итог подтверждён повторной полной проверкой через несколько секунд.
 *    Сбой чтения, занятый менеджер пакетов, нечитаемый файл: это «неизвестно», а не «подделка».
 *  - Положительный итог помнится 10 минут, подтверждённый отрицательный 1 минуту (потом проверка идёт заново); неподтверждённых итогов не хранится.
 *  - Находки «среды» (Xposed, LSPosed, Frida, root, эмулятор) только записываются в журнал и ничего не отключают: копия-то настоящая. Раньше строка
 *    «lsposed» искалась в карте памяти процесса целиком и находилась в ПУТИ чужого приложения: пока программа читала названия и значки установленных
 *    приложений, APK менеджера LSPosed (/data/app/…/org.lsposed.manager-…/base.apk) на 1–2 секунды появлялся в /proc/self/maps, и связь по ADB и окно
 *    подлинности срабатывали на настоящей копии. Теперь смотрятся только подгруженные библиотеки (.so), и находка ничего не блокирует.
 *  - Живая отладка (подключённый отладчик, ptrace, флаг debuggable) по-прежнему останавливает опасные действия (но окна «Скачайте программу…» не вызывает,
 *    копия настоящая), и тоже только после подтверждения повтором.
 *  - Любой отрицательный итог оставляет в обычном журнале короткую строку (номера проверок и безопасные детали).
 */
object Integrity {
    @Volatile private var app: Context? = null
    /** Номера сработавших проверок последнего итога (для журнала): 1 подпись PackageManager, 2 файл APK, 3 META-INF, 4 суммы, 5 живая отладка, T метка стенда. */
    @Volatile var why = ""
    /** Определён эмулятор: только отметка, ничего не ограничивается. */
    @Volatile var emulator = false
    /** Найдена среда подмены (Xposed, LSPosed, Frida): только отметка, ничего не ограничивается. */
    @Volatile var hooked = ""

    /** Итог полной проверки: [bad] опасные действия закрыты, [foreign] причина в подписи или суммах (окно «Скачайте программу…»). */
    private class Verdict(val at: Long, val bad: Boolean, val foreign: Boolean, val ttl: Long, val why: String)
    @Volatile private var verdict: Verdict? = null
    private val lock = Any()
    private val kicking = java.util.concurrent.atomic.AtomicBoolean(false)
    private const val TTL_OK = 600_000L
    private const val TTL_BAD = 60_000L
    private const val TTL_UNKNOWN = 30_000L
    private const val CONFIRM_MS = 6_000L

    fun init(ctx: Context) {
        app = ctx.applicationContext
        emulator = runCatching { emu() }.getOrDefault(false)
    }

    /** Предварительная полная проверка в фоне: первое опасное действие не ждёт чтения файла. */
    fun warm(ctx: Context) {
        init(ctx)
        if (!release()) return
        Thread { runCatching { Thread.sleep(3000); verify(ctx.applicationContext) } }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    private fun release() = BuildConfig.G1.isNotEmpty()
    private fun now() = android.os.SystemClock.elapsedRealtime()
    private fun onMain() = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()

    // отпечаток из двух долей: прямой порядок (G1 xor G2) и обратный (H1 xor H2)
    private fun fpA(): String = xor(BuildConfig.G1, BuildConfig.G2)
    private fun fpB(): String = xor(BuildConfig.H1, BuildConfig.H2).reversed()

    private fun xor(a: String, m: String): String {
        val sb = StringBuilder(a.length)
        var i = 0
        while (i + 1 < a.length) {
            val x = a.substring(i, i + 2).toInt(16)
            val k = m.substring((i % m.length) / 2 * 2, (i % m.length) / 2 * 2 + 2).toInt(16)
            sb.append("%02X".format(x xor k)); i += 2
        }
        return sb.toString()
    }

    /** true: копия наша (или это не выпускная сборка) либо проверка ещё не дала достоверного отрицательного итога. Дёшево: только чтение памяти
     * (его зовёт `Adb.isConnected` из десятков мест интерфейса на каждый клик); сама проверка идёт в [safe] и в фоне. */
    fun ok(ctx: Context? = null): Boolean {
        if (!release()) return true
        if (testBad()) { why = "T"; return false }
        val v = verdict ?: return true
        if (v.bad && now() - v.at >= v.ttl) kick(ctx ?: app)   // подтверждённый отрицательный итог устарел: проверить заново в фоне
        if (v.bad) why = v.why
        return !v.bad
    }

    /** Для окна на экране: итог в порядке. */
    fun statusOk(): Boolean = ok()

    /** Окно «Скачайте программу из официального источника» нужно показать: достоверное расхождение подписи или сумм (не среда, не отладка). */
    fun foreign(): Boolean = (release() && testBad()) || verdict?.foreign == true

    /** Полная проверка перед опасным действием. Неподтверждённого отрицательного итога не бывает: первый отрицательный итог проверяется ещё раз через
     * несколько секунд (поток вызывающего ждёт, главный поток не ждёт никогда: ему отвечает последний известный итог, проверка идёт в фоне). */
    fun safe(ctx: Context? = null): Boolean {
        if (!release()) return true
        if (testBad()) { why = "T"; return false }
        val c = (ctx?.applicationContext ?: app) ?: return true
        val v = verdict
        if (v != null && now() - v.at < v.ttl) return !v.bad
        if (onMain()) { kick(c); return v?.bad != true }
        return !verify(c).bad
    }

    private fun kick(c: Context?) {
        c ?: return
        if (!kicking.compareAndSet(false, true)) return
        Thread { try { runCatching { verify(c.applicationContext) } } finally { kicking.set(false) } }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    /** Одна полная проверка с подтверждением. Выполняется под замком: параллельные вызовы ждут итог, а не повторяют работу. */
    private fun verify(c: Context): Verdict = synchronized(lock) {
        val cur = verdict
        if (cur != null && now() - cur.at < cur.ttl) return@synchronized cur
        val a = round(c)
        noteHook(a.hook)
        val res: Verdict
        if (!a.negative) {
            res = Verdict(now(), false, false, if (a.known) TTL_OK else TTL_UNKNOWN, "")
            if (!a.known) noteOnce("unk", "проверить не удалось: " + a.text())
        } else {
            val again = cur?.bad == true   // повторная проверка уже подтверждённого итога: ждать подтверждения не нужно
            if (!again) note("сомнение: " + a.text() + "; повтор через ${CONFIRM_MS / 1000} с")
            val b = if (again) a else { Thread.sleep(CONFIRM_MS); round(c) }
            if (b.negative) {
                res = Verdict(now(), true, b.foreign, TTL_BAD, b.why())
                if (!again || cur?.why != b.why()) note("подтверждено: why=" + b.why() + " (" + b.text() + ")")
            } else {
                res = Verdict(now(), false, false, TTL_OK, "")
                note(if (again) "отрицательный итог снят: повторная проверка в порядке (" + b.text() + ")" else "не подтверждено, ложная тревога снята (повтор: " + b.text() + ")")
            }
        }
        verdict = res
        why = res.why
        res
    }

    /** Итог одной проверки по всем пунктам. null: проверить не удалось (это не признак подделки). */
    private class Round(val r1: Boolean?, val r2: Boolean?, val r3: Boolean?, val r4: Boolean?, val dbg: String, val hook: String, val det: List<String>) {
        /** Подпись: достоверно только если не совпала минимум двумя независимыми способами; суммы: достоверно, если не совпали и по каталогу архива, и по самим данным. */
        val foreign: Boolean get() = listOf(r1, r2, r3).count { it == false } >= 2 || r4 == false
        val negative: Boolean get() = foreign || dbg.isNotEmpty()
        val known: Boolean get() = r1 != null || r2 != null || r3 != null || r4 != null
        fun why(): String = buildList {
            if (r1 == false) add("1"); if (r2 == false) add("2"); if (r3 == false) add("3"); if (r4 == false) add("4"); if (dbg.isNotEmpty()) add("5")
        }.joinToString("+")
        fun text(): String {
            fun s(r: Boolean?) = when (r) { true -> "да"; false -> "нет"; null -> "?" }
            return "1=" + s(r1) + " 2=" + s(r2) + " 3=" + s(r3) + " 4=" + s(r4) + (if (dbg.isNotEmpty()) " отладка: $dbg" else "") + (if (det.isNotEmpty()) "; " + det.joinToString(", ") else "")
        }
    }

    private fun round(c: Context): Round {
        val det = ArrayList<String>()
        val fl = testFlaky()
        val r1 = if (fl == "sig") false else runCatching { sigPm(c, det) }.getOrNull()
        val r2 = if (fl == "sig") false else runCatching { sigArchive(c, det) }.getOrNull()
        val r3 = runCatching { sigMeta(c, det) }.getOrNull()
        val r4 = runCatching { sums(c, det = det) }.getOrNull()
        val dbg = if (fl == "dbg") "TracerPid=1 (тест)" else runCatching { liveDebug() }.getOrDefault("")
        val hook = runCatching { hookScan() }.getOrDefault("")
        return Round(r1, r2, r3, r4, dbg, hook, det)
    }

    /** Стенд: метка integ_flaky рядом с stands_on один раз портит итог ближайшей проверки (в метке «sig» или «dbg»), чтобы на устройстве проверить подтверждение
     * повтором; метка снимается сама. */
    private fun testFlaky(): String {
        val c = app ?: return ""
        return runCatching {
            if (!StandGuard.allowed(c)) return@runCatching ""
            val f = File(c.getExternalFilesDir(null), "integ_flaky")
            if (!f.exists()) return@runCatching ""
            val kind = f.readText().trim().ifEmpty { "sig" }
            f.delete()
            kind
        }.getOrDefault("")
    }

    /** Проверка файла APK по пути (для стенда): подпись из самого файла, сертификат из META-INF и (если есть) контрольные суммы. null: не удалось прочитать. */
    fun fileOk(ctx: Context, path: String): Boolean? {
        if (!release()) return true
        val a = runCatching { sigArchive(ctx, ArrayList(), path) }.getOrNull()
        val b = runCatching { sigMeta(ctx, ArrayList(), path) }.getOrNull()
        val s = runCatching { sums(ctx, path, ArrayList()) }.getOrNull()
        if (a == false || b == false || s == false) return false
        if (a == null && b == null) return null
        return true
    }

    /** Стенд: одна свежая проверка по всем пунктам (без кэшей и подтверждения) одной строкой. */
    fun report(ctx: Context): String {
        val r = round(ctx.applicationContext)
        return r.text() + (if (r.hook.isNotEmpty()) " среда: " + r.hook else "") + " foreign=" + r.foreign + " negative=" + r.negative
    }

    // ---- общее чтение сертификатов
    @Suppress("DEPRECATION")
    private fun flag() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    /** Для разбора файла APK: на Android 9 getPackageArchiveInfo собирает подписи только при GET_SIGNATURES (с одним GET_SIGNING_CERTIFICATES signingInfo пуст),
     * поэтому на 28+ просим оба признака. */
    @Suppress("DEPRECATION")
    private fun flagArchive() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES else PackageManager.GET_SIGNATURES

    /** Все сертификаты пакета: текущие подписанты и (если есть) история смены ключа. null: данных нет. */
    @Suppress("DEPRECATION")
    private fun certsOf(pi: PackageInfo?): List<ByteArray>? {
        pi ?: return null
        val out = ArrayList<ByteArray>()
        val si = if (Build.VERSION.SDK_INT >= 28) pi.signingInfo else null
        if (si != null) {
            si.apkContentsSigners?.forEach { out.add(it.toByteArray()) }
            if (!si.hasMultipleSigners()) si.signingCertificateHistory?.forEach { out.add(it.toByteArray()) }
        }
        if (out.isEmpty()) pi.signatures?.forEach { out.add(it.toByteArray()) }   // нет signingInfo (Android 8 и ниже, часть прошивок): подписи по-старому
        return out.ifEmpty { null }
    }

    private fun sha(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    // ---- проверка 1: подпись установленной программы (PackageManager). true: совпала, false: не совпала, null: не удалось получить
    private fun sigPm(ctx: Context, det: MutableList<String>): Boolean? {
        val certs = certsOf(ctx.packageManager.getPackageInfo(ctx.packageName, flag())) ?: return null
        val ok = certs.any { same(sha(it), fpA()) }
        if (!ok) det.add("pm=" + hex(sha(certs.first())).take(8))
        return ok
    }

    // ---- проверка 2: подпись прямо из файла APK (другой путь чтения, другая доля)
    private fun sigArchive(ctx: Context, det: MutableList<String>, path: String = ctx.packageCodePath): Boolean? {
        val certs = certsOf(ctx.packageManager.getPackageArchiveInfo(path, flagArchive())) ?: return null
        val ok = certs.any { same(sha(it), fpB()) }
        if (!ok) det.add("apk=" + hex(sha(certs.first())).take(8))
        return ok
    }

    // ---- проверка 3: сертификат из META-INF файла APK (разбор самого файла)
    private fun sigMeta(ctx: Context, det: MutableList<String>, path: String = ctx.packageCodePath): Boolean? {
        ZipFile(path).use { z ->
            val es = z.entries().asSequence().filter { it.name.startsWith("META-INF/") && (it.name.endsWith(".RSA") || it.name.endsWith(".DSA") || it.name.endsWith(".EC")) }.toList()
            // подпись только схемой v2/v3 без META-INF: тогда эта проверка опирается на две остальные
            if (es.isEmpty()) return Build.VERSION.SDK_INT >= 24
            val cf = java.security.cert.CertificateFactory.getInstance("X.509")
            var seen = 0
            var ok = false
            var first = ""
            for (e in es) {
                val certs = runCatching { z.getInputStream(e).use { cf.generateCertificates(it) } }.getOrNull() ?: continue
                for (c in certs) {
                    val d = sha(c.encoded)
                    if (seen == 0) first = hex(d).take(8)
                    seen++
                    if (same(d, fpB())) ok = true
                }
            }
            if (seen == 0) return null
            if (!ok) det.add("meta=$first")
            return ok
        }
    }

    // ---- проверка 4: контрольные суммы DEX и ресурсов (assets/ic.bin дописывает finalize_apk.py).
    // false только при достоверном расхождении: суммы в каталоге архива не те, И суммы, посчитанные по самим данным записей, тоже не те (два независимых способа),
    // либо нет записи/лишний DEX/нечитаемый ic.bin. Каталог расходится, а данные верны: противоречие, не подделка (null).
    private fun sums(ctx: Context, path: String = ctx.packageCodePath, det: MutableList<String>): Boolean? {
        ZipFile(path).use { z ->
            val ic = z.getEntry("assets/ic.bin") ?: return true   // сборка без выпускного шага: проверка сумм не действует
            val want = z.getInputStream(ic).bufferedReader().use { it.readText() }
            val expect = want.split(';').filter { it.contains('=') }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            if (expect.isEmpty()) { det.add("ic=пусто"); return false }
            var dirBad = ""
            for ((name, crc) in expect) {
                val e = z.getEntry(name) ?: run { det.add("нет $name"); return false }
                if (!java.lang.Long.toHexString(e.crc).equals(crc, ignoreCase = true) && dirBad.isEmpty()) dirBad = name
            }
            val dex = z.entries().asSequence().map { it.name }.filter { it.startsWith("classes") && it.endsWith(".dex") }.toList()
            dex.firstOrNull { it !in expect }?.let { det.add("лишний $it"); return false }
            if (dirBad.isEmpty()) return true
            // каталог расходится с ic.bin: второй независимый способ, CRC32 по самим данным записей
            val buf = ByteArray(64 * 1024)
            for ((name, crc) in expect) {
                val e = z.getEntry(name)!!
                val calc = CRC32()
                z.getInputStream(e).use { s -> while (true) { val n = s.read(buf); if (n < 0) break; calc.update(buf, 0, n) } }
                if (!java.lang.Long.toHexString(calc.value).equals(crc, ignoreCase = true)) { det.add("сумма $name"); return false }
            }
            det.add("каталог≠данные $dirBad")
            return null
        }
    }

    // ---- живая отладка: подключённый отладчик, трассировка (ptrace), флаг debuggable. "" — нет; сбой чтения — не отладка.
    private fun liveDebug(): String {
        if (android.os.Debug.isDebuggerConnected()) return "отладчик"
        val c = app
        if (c != null && (c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) return "флаг debuggable"
        val st = runCatching { File("/proc/self/status").readText() }.getOrNull() ?: return ""
        val tp = Regex("TracerPid:\\s*(\\d+)").find(st)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        if (tp == 0L) return ""
        val who = runCatching { File("/proc/$tp/comm").readText().trim() }.getOrDefault("")
        return "TracerPid=$tp" + if (who.isNotEmpty()) " ($who)" else ""
    }

    // ---- среда подмены (Frida, Xposed, LSPosed): только отметка. В карте памяти смотрятся лишь подгруженные библиотеки (.so): пути чужих APK
    // (например, /data/app/…/org.lsposed.manager-…/base.apk, который появляется в карте, пока программа читает названия и значки приложений) ничего не значат.
    private fun hookScan(): String {
        for (n in arrayOf(String(charArrayOf('d','e','.','r','o','b','v','.','x','p','o','s','e','d','.','X','p','o','s','e','d','B','r','i','d','g','e')), "org." + "lsposed.lspd.core.Main")) {
            if (runCatching { Class.forName(n); true }.getOrDefault(false)) return "класс " + n.substringAfterLast('.')
        }
        val maps = runCatching { File("/proc/self/maps").readText() }.getOrNull() ?: return ""
        val tokens = listOf("fri" + "da-agent", "fri" + "da-gadget", "libgadget", "gum-js", "lsp" + "osed", "lsp" + "d", "edx" + "p", "libriru")
        for (line in maps.lineSequence()) {
            val i = line.indexOf('/')
            if (i < 0) continue
            val path = line.substring(i).trim()
            if (!(path.endsWith(".so") || path.endsWith(".so (deleted)"))) continue
            tokens.firstOrNull { path.contains(it) }?.let { return "библиотека $it" }
        }
        return ""
    }

    private fun emu(): Boolean {
        val f = Build.FINGERPRINT.orEmpty(); val h = Build.HARDWARE.orEmpty(); val p = Build.PRODUCT.orEmpty()
        return f.startsWith("generic") || f.contains("sdk_gphone") || h == "goldfish" || h == "ranchu" || p.startsWith("sdk_") || p == "vbox86p"
    }

    // ---- журнал: короткая строка при любом отрицательном итоге (всегда пишется в журнал программы, файл небольшой с ротацией) и в logcat (тег ADBphon)
    private fun stamp(): String {
        val up = now()
        val age = runCatching { up - android.os.Process.getStartElapsedRealtime() }.getOrDefault(-1L)
        return "[процесс ${age / 1000} с, система ${up / 1000} с]"
    }

    private fun note(s: String) {
        val m = "подлинность: $s ${stamp()}"
        android.util.Log.w("ADBphon", m)
        runCatching { AppLog.note(m) }
    }

    private val noted = java.util.Collections.synchronizedSet(HashSet<String>())
    private fun noteOnce(key: String, s: String) { if (noted.add(key)) note(s) }

    private fun noteHook(h: String) {
        hooked = h
        if (h.isNotEmpty() && noted.add("hook:$h")) {
            val m = "среда: $h (только отметка, ничего не отключается) ${stamp()}"
            android.util.Log.w("ADBphon", m)
            runCatching { AppLog.note(m) }
        }
    }

    /** Стенд: метка integ_bad рядом с stands_on заставляет считать копию чужой (проверка окна на устройстве).
     * Результат кэшируется на 5 секунд: это два File.exists() (и они звали ok() из Adb.isConnected на каждый клик по всему интерфейсу,
     * то есть без кэша — два файловых обращения на главном потоке на каждое нажатие по всей программе, измерено ~5 мс/вызов). */
    @Volatile private var testBadAt = 0L
    @Volatile private var testBadRes = false
    private fun testBad(): Boolean {
        val t = now()
        if (testBadAt != 0L && t - testBadAt < 5_000L) return testBadRes
        val c = app
        val r = c != null && runCatching { StandGuard.allowed(c) && File(c.getExternalFilesDir(null), "integ_bad").exists() }.getOrDefault(false)
        testBadAt = t; testBadRes = r
        if (r) noteOnce("T", "стенд: метка integ_bad (копия считается чужой)")
        return r
    }

    private fun same(digest: ByteArray, expectHex: String): Boolean = MessageDigest.isEqual(hex(digest).lowercase().toByteArray(), expectHex.lowercase().toByteArray())
    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
}

/** Окно «Скачайте программу из официального источника»: один раз за запуск, и только при достоверном расхождении подписи или контрольных сумм
 * (после повторной проверки). Программа не падает и остаётся рабочей, кроме опасных действий. */
@androidx.compose.runtime.Composable
fun IntegrityHost() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var bad by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1500)
        bad = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { !Integrity.safe(ctx) && Integrity.foreign() }
    }
    if (!bad) return
    LAlertDialog(
        onDismissRequest = { bad = false },
        title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.integrity_title)) },
        text = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.integrity_text)) },
        confirmButton = {
            TextButton({ runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(Sx.PAGE)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.integrity_open))
            }
        },
        dismissButton = { TextButton({ bad = false }) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.close)) } },
    )
}
