package app.adbphon

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 10.0 (tvfont): чистая логика крупного текста в окнах на ТВ и приставках (без Compose, проверяется юнит-тестами).
 *
 * Роль текста определяется по заданному размеру (в sp до множителя «Размер шрифта»):
 * от 14 sp и без размера: основной текст (минимум 18 sp на экране); 11..14: подписи и пояснения (16 sp); меньше 11: мелкие технические строки (14 sp).
 * Минимум считается «на экране», то есть уже с множителем шрифта: при «Мелком» размер в окне не опускается ниже минимума,
 * при «Крупном» и «Очень крупном» минимум растёт вместе с множителем (текст ещё крупнее).
 */
object TvFontRules {
    enum class Role(val minSp: Float) { MAIN(18f), CAPTION(16f), TECH(14f) }

    const val MAIN_FROM = 14f
    const val CAPTION_FROM = 11f
    /** Межстрочный интервал не меньше этой доли размера (иначе строки крупного текста налезают друг на друга). */
    const val LINE_MIN = 1.15f
    const val LINE_SET = 1.2f
    /** Множитель «Размер шрифта» в окнах на ТВ не меньше 1: «Мелкий» на ТВ не делает окна мельче обычного; сверху ограничен, чтобы окна без прокрутки помещались. */
    const val TV_FONT_MIN = 1.0f
    const val TV_FONT_MAX = 1.6f

    /** Роль по заданному размеру; NaN или нет размера: основной текст. */
    fun roleOf(requestedSp: Float): Role = when {
        requestedSp.isNaN() || requestedSp >= MAIN_FROM -> Role.MAIN
        requestedSp >= CAPTION_FROM -> Role.CAPTION
        else -> Role.TECH
    }

    /** Наименьший размер в sp окна (до множителя [mult]), чтобы на экране было не меньше минимума роли; при [mult] больше 1 минимум растёт вместе с ним. */
    fun floorSp(role: Role, mult: Float): Float = role.minSp / min(if (mult > 0f) mult else 1f, 1f)

    /** Размер для окна на ТВ: заданный размер, поднятый до минимума своей роли. */
    fun sizeSp(requestedSp: Float, mult: Float): Float {
        val f = floorSp(roleOf(requestedSp), mult)
        return if (requestedSp.isNaN()) f else max(requestedSp, f)
    }

    /** Эффективный размер на экране (для проверки): размер окна умножается на множитель шрифта. */
    fun effectiveSp(requestedSp: Float, mult: Float): Float = sizeSp(requestedSp, mult) * mult

    /** Межстрочный интервал после увеличения: заданный, если его хватает, иначе [LINE_SET] от размера; NaN: интервал не задан, остаётся как есть. */
    fun lineSp(sizeSp: Float, lineSp: Float): Float = if (lineSp.isNaN() || lineSp >= sizeSp * LINE_MIN) lineSp else sizeSp * LINE_SET

    /** Множитель шрифта в окнах на ТВ по настройке «Размер шрифта». */
    fun tvMult(appFont: Float): Float = min(max(appFont, TV_FONT_MIN), TV_FONT_MAX)

    /**
     * Масштаб шрифта (Density.fontScale), который надо дать окну. [sysScale]: системный масштаб; [curScale]: тот, что есть сейчас.
     * Окна (диалоги, меню) сбрасывают масштаб программы на системный, основной экран уже умножен на «Размер шрифта».
     * На ТВ результат всегда sys * [tvMult] (повторный вызов ничего не меняет); на телефоне и магнитоле масштаб не трогаем, если [phones] выключен.
     */
    fun fontScale(sysScale: Float, curScale: Float, appFont: Float, tv: Boolean, phones: Boolean): Float = when {
        tv -> sysScale * tvMult(appFont)
        !phones -> curScale
        abs(curScale - sysScale) > 0.001f -> curScale   // основной экран: множитель уже есть
        else -> sysScale * appFont
    }
}
