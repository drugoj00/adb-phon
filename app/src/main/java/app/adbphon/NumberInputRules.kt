package app.adbphon

/**
 * 10.0 (cnt, правило 19): чистая часть счётчика «− число +» ([NumberStepper]) и окна ввода числа.
 * Без Android: разбор набранного текста, проверка диапазона, шаг кнопок −/+ (постоянный, без ускорения), подпись с единицей.
 */
object NumberInputRules {
    /** Итог проверки набранного текста. */
    sealed class Check {
        data class Ok(val value: Int) : Check()
        /** Поле пустое. */
        object Empty : Check()
        /** Не число (или слишком длинное число). */
        object NotNumber : Check()
        /** Число вне диапазона. */
        data class OutOfRange(val value: Int) : Check()
    }

    /** Наибольшая длина поля: столько знаков, сколько у самой длинной границы (с минусом). */
    fun maxLen(min: Int, max: Int): Int = maxOf(min.toString().length, max.toString().length)

    /** Оставляет в набранном только цифры (и минус первым знаком, если диапазон допускает отрицательные), не длиннее [maxLen]. */
    fun clean(text: String, min: Int, max: Int): String {
        val minusOk = min < 0
        val sb = StringBuilder()
        for (c in text.trim()) {
            when {
                c in '0'..'9' -> sb.append(c)
                c == '-' && minusOk && sb.isEmpty() -> sb.append(c)
            }
        }
        return sb.toString().take(maxLen(min, max))
    }

    /** Число из текста или null (пусто, только минус, не число, переполнение). */
    fun parse(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty() || t == "-") return null
        return t.toIntOrNull()
    }

    fun check(text: String, min: Int, max: Int): Check {
        val t = text.trim()
        if (t.isEmpty()) return Check.Empty
        val v = parse(t) ?: return Check.NotNumber
        return if (v in min..max) Check.Ok(v) else Check.OutOfRange(v)
    }

    /** Можно ли нажать «Применить». */
    fun canApply(text: String, min: Int, max: Int): Boolean = check(text, min, max) is Check.Ok

    /** Подсказка диапазона показывается красной: текст набран, но не годится. Пустое поле не считается ошибкой. */
    fun isError(text: String, min: Int, max: Int): Boolean = when (check(text, min, max)) { is Check.Ok, Check.Empty -> false; else -> true }

    /** Шаг кнопки − или + (dir = -1 или 1): ровно один шаг за нажатие, на границах значение не выходит из диапазона. */
    fun step(value: Int, dir: Int, step: Int, min: Int, max: Int): Int {
        val s = step.coerceAtLeast(1).toLong()
        val r = value.toLong() + (if (dir < 0) -s else s)
        return r.coerceIn(min.toLong(), max.toLong()).toInt()
    }

    fun canDec(value: Int, min: Int): Boolean = value > min
    fun canInc(value: Int, max: Int): Boolean = value < max

    /** Число с единицей: «300 с», «150%» (знак процента и градуса пишутся слитно), без единицы просто число. */
    fun withUnit(value: Int, unit: String): String = when {
        unit.isEmpty() -> value.toString()
        unit == "%" || unit == "°" -> "$value$unit"
        else -> "$value $unit"
    }

    /** Аргументы подсказки «от %1$s до %2$s»: нижняя граница и верхняя с единицей («от 1 до 300 с»). */
    fun rangeArgs(min: Int, max: Int, unit: String): Pair<String, String> = min.toString() to withUnit(max, unit)
}
