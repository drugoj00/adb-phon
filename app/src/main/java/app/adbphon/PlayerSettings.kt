package app.adbphon

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource

/**
 * 10.1: настройки встроенного видеоплеера (группа «Плеер»). Хранятся в prefs «adbphon»; музыка в фоне прежняя (prefs «fm», ключ bg_music).
 * Плеер читает значения на лету (жесты при каждом касании), поэтому изменения действуют сразу.
 */
object PlayerPrefs {
    const val FILE = "adbphon"
    const val HIDE = "vp_hide"        // секунды до скрытия панели: 3 / 5 (по умолчанию) / 10 / 0 = не скрывать
    const val KEEP = "vp_size_keep"   // запоминать режим кнопки «Под экран» между видео и запусками (по умолчанию вкл)
    const val SIZE = "vp_resize"      // запомненный режим: 0 по размеру, 1 заполнить, 2 растянуть
    const val VOL = "vp_g_vol"        // жест громкости (по умолчанию вкл)
    const val BRI = "vp_g_bri"        // жест яркости (по умолчанию вкл)
    const val SWAP = "vp_g_swap"      // false: слева яркость, справа громкость; true: наоборот
    const val SEEK = "vp_g_seek"      // перемотка ведением (по умолчанию вкл)
    const val FLING = "vp_g_fling"    // следующий/предыдущий файл броском (по умолчанию вкл)
    const val SENS = "vp_seek_sec"    // секунд видео на всю ширину экрана при перемотке ведением (по умолчанию 180)
    val ALL = listOf(HIDE, KEEP, SIZE, VOL, BRI, SWAP, SEEK, FLING, SENS)

    /** Счётчик сбросов: экраны настроек перечитывают значения. */
    var rev by mutableIntStateOf(0)

    fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun reset(ctx: Context) {
        prefs(ctx).edit().apply { ALL.forEach { remove(it) } }.apply()
        ctx.getSharedPreferences("fm", Context.MODE_PRIVATE).edit().putBoolean("bg_music", false).apply()
        rev++
    }
}

/** «Скрывать панель через»: 3 / 5 (по умолчанию) / 10 секунд или не скрывать (повторное касание скроет). */
@Composable
fun PlayerPanelHideSetting() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = PlayerPrefs.prefs(ctx)
    key(PlayerPrefs.rev) {
        var v by remember { mutableStateOf(prefs.getInt(PlayerPrefs.HIDE, 5)) }
        val never = stringResource(R.string.vp_hide_never)
        val names = listOf(3, 5, 10).associateWith { stringResource(R.string.vp_hide_sec, it) } + (0 to never)
        fun pick(sec: Int) = EqChoice(names.getValue(sec), v == sec) { v = sec; prefs.edit().putInt(PlayerPrefs.HIDE, sec).apply() }
        HintTitle(stringResource(R.string.set_vp_hide), stringResource(R.string.set_vp_hide_hint))
        EqChoices(listOf(pick(3), pick(5), pick(10), pick(0)), perRow = 2)
    }
}

/** «Запоминать размер кадра»: один переключатель. Выкл: ничего не запоминается, каждое видео открывается «по размеру». */
@Composable
fun PlayerKeepSizeSetting() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = PlayerPrefs.prefs(ctx)
    key(PlayerPrefs.rev) {
        var on by remember { mutableStateOf(prefs.getBoolean(PlayerPrefs.KEEP, true)) }
        SettingSwitch(on, { on = it; prefs.edit().putBoolean(PlayerPrefs.KEEP, it).apply { if (!it) remove(PlayerPrefs.SIZE) }.apply() }, stringResource(R.string.set_vp_keep), stringResource(R.string.set_vp_keep_hint))
    }
}

/** «Жесты»: громкость, яркость, сторона, перемотка ведением, файл броском, чувствительность перемотки. */
@Composable
fun PlayerGesturesSetting() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = PlayerPrefs.prefs(ctx)
    key(PlayerPrefs.rev) {
        var vol by remember { mutableStateOf(prefs.getBoolean(PlayerPrefs.VOL, true)) }
        var bri by remember { mutableStateOf(prefs.getBoolean(PlayerPrefs.BRI, true)) }
        var swap by remember { mutableStateOf(prefs.getBoolean(PlayerPrefs.SWAP, false)) }
        var seek by remember { mutableStateOf(prefs.getBoolean(PlayerPrefs.SEEK, true)) }
        var fling by remember { mutableStateOf(prefs.getBoolean(PlayerPrefs.FLING, true)) }
        var sens by remember { mutableStateOf(prefs.getInt(PlayerPrefs.SENS, 180)) }
        SettingSwitch(vol, { vol = it; prefs.edit().putBoolean(PlayerPrefs.VOL, it).apply() }, stringResource(R.string.set_vp_gest_vol), stringResource(R.string.set_vp_gest_vol_hint))
        SettingSwitch(bri, { bri = it; prefs.edit().putBoolean(PlayerPrefs.BRI, it).apply() }, stringResource(R.string.set_vp_gest_bri), stringResource(R.string.set_vp_gest_bri_hint))
        HintTitle(stringResource(R.string.set_vp_side), stringResource(R.string.set_vp_side_hint))
        EqChoices(listOf(
            EqChoice(stringResource(R.string.set_vp_side_a), !swap) { swap = false; prefs.edit().putBoolean(PlayerPrefs.SWAP, false).apply() },
            EqChoice(stringResource(R.string.set_vp_side_b), swap) { swap = true; prefs.edit().putBoolean(PlayerPrefs.SWAP, true).apply() },
        ), perRow = 1)
        SettingSwitch(seek, { seek = it; prefs.edit().putBoolean(PlayerPrefs.SEEK, it).apply() }, stringResource(R.string.set_vp_gest_seek), stringResource(R.string.set_vp_gest_seek_hint))
        SettingSwitch(fling, { fling = it; prefs.edit().putBoolean(PlayerPrefs.FLING, it).apply() }, stringResource(R.string.set_vp_gest_fling), stringResource(R.string.set_vp_gest_fling_hint))
        HintTitle(stringResource(R.string.set_vp_sens), stringResource(R.string.set_vp_sens_hint))
        EqChoices(listOf(60, 120, 180, 300).map { s -> EqChoice(stringResource(R.string.vp_sens_sec, s), sens == s) { sens = s; prefs.edit().putInt(PlayerPrefs.SENS, s).apply() } }, perRow = 2)
    }
}

/** «Сброс настроек плеера». */
@Composable
fun PlayerResetSetting() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    HintTitle(stringResource(R.string.set_vp_reset), stringResource(R.string.set_vp_reset_hint))
    EqButtons(listOf(EqBtn(stringResource(R.string.set_vp_reset_btn)) { PlayerPrefs.reset(ctx) }))
}
