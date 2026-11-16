package app.adbphon

import kotlin.math.roundToInt

/**
 * 10.0 (num, правило 19): чистая часть «ползунок + число» ([NumberSlider]) и «Своё значение…» в вариантах выбора.
 * Без Android: диапазон, единица и масштаб каждой настройки, перевод значения ползунка в показанное число и обратно.
 */
object NumberSliderRules {
    /** Единица у числа. Слова (дни, МБ, мин, с) переводятся в [numUnit], знаки % и ° общие. */
    enum class NumUnit { NONE, PCT, DEG, DAYS, MB, MIN, SEC }

    /**
     * Настройка: показанное число от [min] до [max] в единицах [unit].
     * [scale]: во сколько раз показанное число больше хранимого (доля 0.3 при scale 100 показывается как 30%).
     */
    data class Spec(val min: Int, val max: Int, val unit: NumUnit = NumUnit.NONE, val scale: Float = 1f) {
        init { require(min <= max) { "min > max" }; require(scale > 0f) { "scale <= 0" } }
        val range: IntRange get() = min..max
        /** Диапазон ползунка в хранимых единицах. */
        val sliderRange: ClosedFloatingPointRange<Float> get() = (min / scale)..(max / scale)
    }

    /** Хранимое значение ползунка в показанное число (округление, в пределах диапазона). */
    fun toShown(stored: Float, s: Spec): Int {
        val x = stored * s.scale
        if (x.isNaN()) return s.min
        return x.roundToInt().coerceIn(s.min, s.max)
    }

    /** Введённое число в хранимое значение ползунка (вне диапазона прижимается к границе). */
    fun toStored(shown: Int, s: Spec): Float = shown.coerceIn(s.min, s.max) / s.scale

    /** Значение не совпадает ни с одним готовым вариантом: выбран вариант «Своё значение». */
    fun isCustom(value: Int, presets: Collection<Int>): Boolean = value !in presets

    /** С чего начинать окно ввода «Своё значение»: текущее, если оно в диапазоне, иначе нижняя граница. */
    fun startValue(value: Int, s: Spec): Int = if (value in s.range) value else s.min

    // ---- настройки (показанные числа)
    /** Мелодия звонка: громкость 0..100%. */
    val RING_VOL = Spec(0, 100, NumUnit.PCT)
    /** «Экран Android»: громкость звука 0..1 → 0..100%. */
    val SCREEN_VOL = Spec(0, 100, NumUnit.PCT, 100f)
    /** Терминал: размер шрифта 9..24. */
    val TERM_FONT = Spec(9, 24)
    /** Пульт «Воздух»: чувствительность 0.3..3 → 30..300%. */
    val AIR_SENS = Spec(30, 300, NumUnit.PCT, 100f)
    /** Контраст полосок 0..100%. */
    val BAR_CONTRAST = Spec(0, 100, NumUnit.PCT)
    /** Оттенок (цвет полосок, основной цвет) 0..360°. */
    val HUE = Spec(0, 360, NumUnit.DEG)
    /** Геймпад: мёртвая зона 0..0.4 → 0..40%. */
    val PAD_DEAD = Spec(0, 40, NumUnit.PCT, 100f)
    /** Геймпад: скорость курсора 0.3..3 → 30..300%. */
    val PAD_CSPEED = Spec(30, 300, NumUnit.PCT, 100f)
    /** Геймпад: размер 0.7..1.3 → 70..130%. */
    val PAD_SIZE = Spec(70, 130, NumUnit.PCT, 100f)
    /** Геймпад: прозрачность (непрозрачность) 0.3..1 → 30..100%. */
    val PAD_ALPHA = Spec(30, 100, NumUnit.PCT, 100f)
    /** Геймпад, наклон: угол 10..60°. */
    val TILT_ANGLE = Spec(10, 60, NumUnit.DEG)
    /** Геймпад, наклон: мёртвая зона 0..0.3 → 0..30%. */
    val TILT_DEAD = Spec(0, 30, NumUnit.PCT, 100f)
    /** Геймпад, наклон: сглаживание 0..0.9 → 0..90%. */
    val TILT_SMOOTH = Spec(0, 90, NumUnit.PCT, 100f)
    /** Геймпад: сила обратной вибрации 0..2 → 0..200%. */
    val RUMBLE_GAIN = Spec(0, 200, NumUnit.PCT, 100f)

    /** Корзина: сколько дней хранить (своё значение), 0 = без лимита отдельным вариантом. */
    val TRASH_DAYS = Spec(1, 3650, NumUnit.DAYS)
    val TRASH_DAYS_PRESETS = listOf(7, 30, 90, 0)
    /** Корзина: не класть файлы больше N МБ (своё значение), 0 = без лимита отдельным вариантом. */
    val TRASH_MB = Spec(1, 102400, NumUnit.MB)
    val TRASH_MB_PRESETS = listOf(0, 500, 2048)
    /** Автокопия: сколько последних хранить (BkAuto.keep прижимает к 1..50). */
    val BK_KEEP = Spec(1, 50)
    val BK_KEEP_PRESETS = listOf(3, 5, 10)
    /** Сеанс приглашения: завершать после простоя, минут. */
    val VPN_IDLE = Spec(1, 1440, NumUnit.MIN)
    val VPN_IDLE_PRESETS = listOf(5, 10, 30, 60)

    /** Плеер: позиция в секундах от 0 до длительности трека. */
    fun seek(durMs: Long): Spec = Spec(0, (durMs / 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), NumUnit.SEC)
    fun seekMs(sec: Int): Long = sec.coerceAtLeast(0) * 1000L
}
