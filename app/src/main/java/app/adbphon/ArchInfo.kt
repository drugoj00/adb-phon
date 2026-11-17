package app.adbphon

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * 10.5: архитектура программы в списке «Софт» (оранжевая метка в начале второй строки). Определяется по списку lib/&lt;abi&gt; в APK
 * (основной файл и разделённые части), без root; считается в фоне и запоминается по пакету и версии.
 * Код: «arm64», «arm32», «x86_64», «x86», «uni» (несколько архитектур), «none» (нет нативного кода), «» (определить нельзя).
 */
object ArchInfo {
    private val cache = ConcurrentHashMap<String, String>()
    private val gate = Semaphore(1)   // один поток: нагрузка на устройство минимальна
    private var prefs: android.content.SharedPreferences? = null
    private fun sp(ctx: android.content.Context) = prefs ?: ctx.applicationContext.getSharedPreferences("arch_cache", android.content.Context.MODE_PRIVATE).also { prefs = it }
    private fun key(pkg: String, version: String, paths: List<String>) = pkg + "|" + version + "|" + paths.size

    /** Из памяти или с диска (кэш по пакету и версии переживает перезапуск). */
    fun peek(ctx: android.content.Context, pkg: String, version: String, paths: List<String>): String? {
        val k = key(pkg, version, paths)
        cache[k]?.let { return it }
        return sp(ctx).getString(k, null)?.also { cache[k] = it }
    }

    /** Чистая часть (проверяется тестами): множество архитектур из имён записей архива. */
    fun fromEntries(names: Iterable<String>): String {
        val abis = HashSet<String>()
        for (n in names) if (n.startsWith("lib/") && n.endsWith(".so")) {
            val abi = n.substring(4).substringBefore('/')
            if (abi.isNotEmpty()) abis.add(when (abi) { "arm64-v8a" -> "arm64"; "armeabi-v7a", "armeabi" -> "arm32"; else -> abi })
        }
        return when { abis.isEmpty() -> "none"; abis.size == 1 -> abis.first(); else -> "uni" }
    }

    private fun detect(paths: List<String>): String {
        val names = ArrayList<String>(); var readable = false
        for (p in paths) try { ZipFile(p).use { z -> readable = true; for (e in z.entries()) if (e.name.startsWith("lib/")) names.add(e.name) } } catch (e: Throwable) { }
        return if (readable) fromEntries(names) else ""
    }

    suspend fun load(ctx: android.content.Context, pkg: String, version: String, paths: List<String>): String {
        peek(ctx, pkg, version, paths)?.let { return it }
        val k = key(pkg, version, paths)
        return gate.withPermit {
            cache[k] ?: withContext(Dispatchers.IO) {
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                val r = detect(paths)
                kotlinx.coroutines.delay(25)   // пауза между файлами: не занимать диск и процессор подряд
                r
            }.also { cache[k] = it; if (it.isNotEmpty()) sp(ctx).edit().putString(k, it).apply() }
        }
    }
}

/** Подпись архитектуры заглавными буквами ([code] из [ArchInfo]); пусто, если определить нельзя. */
@Composable
fun archLabel(code: String): String = when (code) {
    "arm64" -> "ARM64"
    "arm32" -> "ARM32"
    "x86_64" -> "X86_64"
    "x86" -> "X86"
    "uni" -> "ALL"      // несколько архитектур в APK
    "none" -> "NONE"    // нет нативного кода
    else -> ""
}

/** Оранжевый, читаемый и на светлой, и на тёмной теме (не путать с бирюзой интерфейса). */
@Composable
fun archColor(): Color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFFFA726) else Color(0xFFE65100)

/** Состояние архитектуры программы в строке списка: сначала из памяти, затем считается в фоне. null: ещё считается или это системная программа. */
@Composable
fun rememberArch(pkg: String, version: String, paths: List<String>, system: Boolean): String {
    if (system || paths.isEmpty()) return ""
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var code by remember(pkg, version, paths.size) { mutableStateOf(ArchInfo.peek(ctx, pkg, version, paths) ?: "") }
    LaunchedEffect(pkg, version, paths.size) { if (code.isEmpty()) code = ArchInfo.load(ctx, pkg, version, paths) }
    return code
}
