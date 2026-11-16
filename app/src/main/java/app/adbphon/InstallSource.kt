package app.adbphon

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 10.0 (wad72, 4PDA): откуда установлено приложение. Чистое сопоставление пакета-установщика с источником,
 * без Android (проверяется юнит-тестом InstallSrcMapTest).
 */
object InstallSrcMap {
    enum class Src { PLAY, RUSTORE, GALAXY, HUAWEI, XIAOMI, AMAZON, FDROID, SHELL, FILE, PREINSTALLED, OTHER, UNKNOWN }

    private val known = mapOf(
        "com.android.vending" to Src.PLAY, "com.google.android.feedback" to Src.PLAY,
        "ru.vk.store" to Src.RUSTORE, "ru.vk.store.tv" to Src.RUSTORE,
        "com.sec.android.app.samsungapps" to Src.GALAXY,
        "com.huawei.appmarket" to Src.HUAWEI,
        "com.xiaomi.mipicks" to Src.XIAOMI, "com.xiaomi.market" to Src.XIAOMI, "com.xiaomi.discover" to Src.XIAOMI,
        "com.amazon.venezia" to Src.AMAZON, "com.amazon.appmanager" to Src.AMAZON,
        "org.fdroid.fdroid" to Src.FDROID, "org.fdroid.basic" to Src.FDROID, "org.fdroid.fdroid.privileged" to Src.FDROID,
        "com.aurora.store" to Src.FDROID, "com.looker.droidify" to Src.FDROID, "com.machiav3lli.fdroid" to Src.FDROID, "in.sunilpaulmathew.izzyondroid" to Src.FDROID,
        "com.android.shell" to Src.SHELL, "app.adbphon" to Src.SHELL, "adb" to Src.SHELL,
        "com.google.android.packageinstaller" to Src.FILE, "com.android.packageinstaller" to Src.FILE,
        "com.samsung.android.packageinstaller" to Src.FILE, "com.miui.packageinstaller" to Src.FILE,
    )

    fun of(pkg: String?): Src? = pkg?.takeIf { it.isNotBlank() }?.let { known[it] }

    /**
     * [installing]: кто записан установщиком (installingPackageName / getInstallerPackageName); [initiating]: кто начал установку (Android 11+);
     * [system]: приложение системное (встроено в прошивку). Магазин важнее пакетного установщика: APK, открытый из магазина, считается из магазина.
     */
    fun decide(installing: String?, initiating: String?, system: Boolean): Src {
        val a = of(installing)
        val b = of(initiating)
        return when {
            a != null && a != Src.FILE -> a
            a == Src.FILE && b != null && b != Src.FILE -> b
            a == Src.FILE -> Src.FILE
            !installing.isNullOrBlank() -> Src.OTHER
            b != null -> b
            !initiating.isNullOrBlank() -> Src.OTHER
            system -> Src.PREINSTALLED
            else -> Src.UNKNOWN
        }
    }
}

/** Источник установки приложений этого телефона: PackageManager, кэш на время показа списка «Софт», фильтр по источнику. */
object InstallSource {
    class Info(val src: InstallSrcMap.Src, val installer: String?, val initiator: String?)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Info>()
    /** Выбранный фильтр во вкладке «Софт» (null: все источники). */
    var filter by mutableStateOf<InstallSrcMap.Src?>(null)

    /** Список приложений перечитан: источники считаются заново (обновление могло прийти из другого магазина). */
    fun reset() { cache.clear() }

    @Suppress("DEPRECATION")
    fun info(ctx: Context, pkg: String, system: Boolean): Info = cache.getOrPut(pkg) {
        val pm = ctx.packageManager
        var inst: String? = null
        var init: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { val s = pm.getInstallSourceInfo(pkg); inst = s.installingPackageName; init = s.initiatingPackageName }
        }
        if (inst == null) inst = runCatching { pm.getInstallerPackageName(pkg) }.getOrNull()
        Info(InstallSrcMap.decide(inst, init, system), inst, init)
    }

    fun matches(ctx: Context, pkg: String, system: Boolean): Boolean { val f = filter ?: return true; return info(ctx, pkg, system).src == f }

    fun label(ctx: Context, s: InstallSrcMap.Src): String = ctx.getString(labelRes(s))
    fun labelRes(s: InstallSrcMap.Src): Int = when (s) {
        InstallSrcMap.Src.PLAY -> R.string.cld_is_play
        InstallSrcMap.Src.RUSTORE -> R.string.cld_is_rustore
        InstallSrcMap.Src.GALAXY -> R.string.cld_is_galaxy
        InstallSrcMap.Src.HUAWEI -> R.string.cld_is_huawei
        InstallSrcMap.Src.XIAOMI -> R.string.cld_is_xiaomi
        InstallSrcMap.Src.AMAZON -> R.string.cld_is_amazon
        InstallSrcMap.Src.FDROID -> R.string.cld_is_fdroid
        InstallSrcMap.Src.SHELL -> R.string.cld_is_shell
        InstallSrcMap.Src.FILE -> R.string.cld_is_file
        InstallSrcMap.Src.PREINSTALLED -> R.string.cld_is_pre
        InstallSrcMap.Src.OTHER -> R.string.cld_is_other
        InstallSrcMap.Src.UNKNOWN -> R.string.cld_is_unknown
    }
    /** Короткая метка в строке списка (несколько знаков). */
    fun shortRes(s: InstallSrcMap.Src): Int? = when (s) {
        InstallSrcMap.Src.PLAY -> R.string.cld_iss_play
        InstallSrcMap.Src.RUSTORE -> R.string.cld_iss_rustore
        InstallSrcMap.Src.GALAXY -> R.string.cld_iss_galaxy
        InstallSrcMap.Src.HUAWEI -> R.string.cld_iss_huawei
        InstallSrcMap.Src.XIAOMI -> R.string.cld_iss_xiaomi
        InstallSrcMap.Src.AMAZON -> R.string.cld_iss_amazon
        InstallSrcMap.Src.FDROID -> R.string.cld_iss_fdroid
        InstallSrcMap.Src.SHELL -> R.string.cld_iss_shell
        InstallSrcMap.Src.FILE -> R.string.cld_iss_file
        InstallSrcMap.Src.OTHER -> R.string.cld_iss_other
        InstallSrcMap.Src.PREINSTALLED, InstallSrcMap.Src.UNKNOWN -> null   // встроенные и неизвестные без метки: строка не загромождается
    }

    /** Полная строка для окна сведений: источник и пакеты установщика. */
    fun describe(ctx: Context, pkg: String, system: Boolean): String {
        val i = info(ctx, pkg, system)
        val sb = StringBuilder(label(ctx, i.src))
        i.installer?.let { sb.append(" (").append(it).append(')') }
        if (i.initiator != null && i.initiator != i.installer) sb.append("\n").append(ctx.getString(R.string.cld_is_started_by, i.initiator))
        return sb.toString()
    }
}

/** Маленькая метка источника справа в строке приложения («Play», «APK», «ADB»…). */
@Composable
fun InstallSourceBadge(pkg: String, system: Boolean) {
    val ctx = LocalContext.current
    val s = remember(pkg) { InstallSource.info(ctx, pkg, system).src }
    val r = InstallSource.shortRes(s) ?: return
    val cs = MaterialTheme.colorScheme
    Box(Modifier.padding(start = 4.dp).background(cs.secondaryContainer, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) {
        Text(stringResource(r), fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1, color = cs.onSecondaryContainer)
    }
}

/** Фильтр «Источник» в панели фильтров вкладки «Софт»: выпадающий список с числом приложений у каждого источника. */
@Composable
fun InstallSourceFilterChip(apps: List<AppEntry>) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val cur = InstallSource.filter
    Box {
        FilterChip(cur != null, { open = true }, {
            Text((if (cur == null) stringResource(R.string.cld_is_filter) else stringResource(R.string.cld_is_filter_on, InstallSource.label(ctx, cur))) + " ▾", fontSize = 12.sp, maxLines = 1)
        })
        LDropdownMenu(open, { open = false }) {
            val counts = remember(apps, open) { if (!open) emptyMap() else apps.groupingBy { InstallSource.info(ctx, it.pkg, it.system).src }.eachCount() }
            DropdownMenuItem({ Text(stringResource(R.string.cld_is_all) + " (" + apps.size + ")", fontSize = 14.sp) }, { InstallSource.filter = null; open = false })
            InstallSrcMap.Src.values().forEach { s ->
                val n = counts[s] ?: 0
                if (n > 0 || s == cur) DropdownMenuItem({ Text(InstallSource.label(ctx, s) + " ($n)", fontSize = 14.sp) }, { InstallSource.filter = s; open = false })
            }
        }
    }
}
