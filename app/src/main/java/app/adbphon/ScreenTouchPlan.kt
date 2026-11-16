package app.adbphon

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Режим «Кнопки на экран» (10.0, kbtn): чистая логика без Android. Состояние экранных кнопок и стиков оверлея превращается в штрихи одного жеста
 * для AccessibilityService.dispatchGesture. Каждый новый жест обязан продолжить ВСЕ ещё зажатые штрихи прошлого жеста (иначе система отменяет их),
 * продолжение начинается точно в конечной точке прошлого штриха, поэтому координаты целые пиксели.
 */
object StIds {
    const val A = "A"; const val B = "B"; const val X = "X"; const val Y = "Y"
    const val LB = "LB"; const val RB = "RB"; const val LT = "LT"; const val RT = "RT"
    const val START = "ST"; const val BACK = "BK"
    const val DU = "DU"; const val DR = "DR"; const val DD = "DD"; const val DL = "DL"
    const val LS = "LS"; const val RS = "RS"

    /** Порядок меток в окне расстановки (стики первыми, у них есть радиус). */
    val all = listOf(LS, RS, A, B, X, Y, LB, RB, LT, RT, START, BACK, DU, DR, DD, DL)
    val sticks = setOf(LS, RS)

    /** Бит кнопки Pad (или -2 / -3 для курков) → метка; null для кнопок без цели (Guide и т.п.). */
    fun ofBit(bit: Int): String? = when (bit) {
        0 -> A; 1 -> B; 3 -> X; 4 -> Y; 6 -> LB; 7 -> RB; 11 -> START; 10 -> BACK
        -2 -> LT; -3 -> RT
        else -> null
    }

    /** Крестовина Pad.hat (0 вверх, по часовой, 8 нейтраль) → зажатые направления. */
    fun ofHat(hat: Int): Set<String> = buildSet {
        if (hat == 7 || hat == 0 || hat == 1) add(DU)
        if (hat in 1..3) add(DR)
        if (hat in 3..5) add(DD)
        if (hat in 5..7) add(DL)
    }
}

/** Цель касания в пикселях экрана; [r] радиус хода стика (для кнопок 0). */
data class StTarget(val x: Float, val y: Float, val r: Float = 0f)

/** Входное состояние: зажатые кнопки, короткие нажатия с прошлого шага (нажали и отпустили между шагами) и отклонения стиков −1..1. */
data class StInput(val held: Set<String>, val taps: Set<String> = emptySet(), val sticks: Map<String, Pair<Float, Float>> = emptyMap()) {
    companion object {
        /** Из состояния Pad (биты кнопок, курки, крестовина, оси 0..3: левый X,Y, правый X,Y). */
        fun fromPad(buttons: Int, lt: Boolean, rt: Boolean, hat: Int, axes: FloatArray, taps: Set<String> = emptySet()): StInput {
            val held = HashSet<String>()
            for (bit in 0..15) if (buttons and (1 shl bit) != 0) StIds.ofBit(bit)?.let { held.add(it) }
            if (lt) held.add(StIds.LT); if (rt) held.add(StIds.RT)
            held.addAll(StIds.ofHat(hat))
            val st = mapOf(StIds.LS to (axes[0] to axes[1]), StIds.RS to (axes[2] to axes[3]))
            return StInput(held, taps, st)
        }
    }
}

/** Один штрих жеста: от (x0,y0) до (x1,y1) за [durMs]; [cont] id продолжаемого штриха прошлого жеста или -1; [willContinue] палец остаётся прижатым. */
data class StStroke(val id: Int, val ctl: String, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val durMs: Long, val cont: Int, val willContinue: Boolean)

class ScreenTouchPlan(
    val tapMs: Long = 50L,          // короткое нажатие
    val moveMs: Long = 16L,         // шаг перемещения стика
    val holdMs: Long = 1L,          // продолжение без движения
    val maxStrokes: Int = 10,       // Android гарантирует не меньше 10 одновременных штрихов
    val dead: Float = 0.08f,        // мёртвая зона стика
) {
    private data class Live(val stroke: Int, val x: Float, val y: Float)

    private val live = LinkedHashMap<String, Live>()
    private var nextId = 1

    /** Метки, у которых сейчас прижат палец (после последнего отправленного жеста). */
    val down: Set<String> get() = live.keys.toSet()

    /** Система отменила жест (или его не удалось отправить): все пальцы подняты, следующий шаг прижмёт зажатое заново. */
    fun reset() { live.clear() }

    private fun px(v: Float) = v.roundToInt().toFloat()

    /** Желаемая точка касания для метки или null (не зажата, нет цели, стик в мёртвой зоне). */
    private fun want(ctl: String, inp: StInput, t: Map<String, StTarget>): Pair<Float, Float>? {
        val tg = t[ctl] ?: return null
        if (ctl in StIds.sticks) {
            val (dx0, dy0) = inp.sticks[ctl] ?: return null
            var dx = dx0; var dy = dy0
            val m = hypot(dx, dy)
            if (m < dead) return null
            if (m > 1f) { dx /= m; dy /= m }
            return px(tg.x + dx * tg.r) to px(tg.y + dy * tg.r)
        }
        return if (ctl in inp.held) px(tg.x) to px(tg.y) else null
    }

    /**
     * Штрихи следующего жеста. Пустой список: менять нечего (зажатые пальцы так и лежат, отправлять жест не нужно).
     * Иначе в жесте: продолжения всех прижатых штрихов (подъём отпущенных, сдвиг стиков, удержание остальных), новые прижатия и короткие нажатия.
     */
    fun step(inp: StInput, targets: Map<String, StTarget>): List<StStroke> {
        val out = ArrayList<StStroke>()
        var changed = false
        val nextLive = LinkedHashMap<String, Live>()
        // 1) продолжить всё, что прижато: поднять, сдвинуть или удержать
        for ((ctl, l) in live) {
            val w = want(ctl, inp, targets)
            val id = nextId++
            if (w == null) {
                out.add(StStroke(id, ctl, l.x, l.y, l.x, l.y, holdMs, l.stroke, false)); changed = true
            } else {
                val moved = w.first != l.x || w.second != l.y
                out.add(StStroke(id, ctl, l.x, l.y, w.first, w.second, if (moved) moveMs else holdMs, l.stroke, true))
                if (moved) changed = true
                nextLive[ctl] = Live(id, w.first, w.second)
            }
        }
        // 2) новые прижатия (стик: касание в центре и сразу сдвиг, как палец на экранном джойстике игры)
        for (ctl in StIds.all) {
            if (ctl in live || out.size >= maxStrokes) continue
            val w = want(ctl, inp, targets) ?: continue
            val tg = targets.getValue(ctl)
            val id = nextId++
            val (sx, sy) = if (ctl in StIds.sticks) px(tg.x) to px(tg.y) else w
            val dur = if (sx != w.first || sy != w.second) moveMs else holdMs
            out.add(StStroke(id, ctl, sx, sy, w.first, w.second, dur, -1, true)); changed = true
            nextLive[ctl] = Live(id, w.first, w.second)
        }
        // 3) короткие нажатия: кнопку нажали и отпустили между шагами, касание целиком в этом жесте
        for (ctl in StIds.all) {
            if (ctl !in inp.taps || ctl in live || ctl in nextLive || ctl in StIds.sticks || out.size >= maxStrokes) continue
            val tg = targets[ctl] ?: continue
            out.add(StStroke(nextId++, ctl, px(tg.x), px(tg.y), px(tg.x), px(tg.y), tapMs, -1, false)); changed = true
        }
        if (!changed) return emptyList()           // ничего не изменилось: прежний жест держит пальцы, nextId не важен
        live.clear(); live.putAll(nextLive)
        return out
    }
}

/** Профиль раскладки: метка → точка в долях экрана (x, y от ширины и высоты, радиус от меньшей стороны). Строка вида «A:0.5,0.6,0;LS:...». */
object StProfile {
    fun encode(t: Map<String, StTarget>, w: Int, h: Int): String {
        val m = minOf(w, h).toFloat().coerceAtLeast(1f)
        return t.entries.joinToString(";") { (k, v) -> "$k:${fmt(v.x / w)},${fmt(v.y / h)},${fmt(v.r / m)}" }
    }

    fun decode(s: String?, w: Int, h: Int): Map<String, StTarget> {
        if (s.isNullOrBlank()) return emptyMap()
        val m = minOf(w, h).toFloat()
        val out = LinkedHashMap<String, StTarget>()
        for (part in s.split(';')) {
            val i = part.indexOf(':'); if (i <= 0) continue
            val k = part.substring(0, i); if (k !in StIds.all) continue
            val n = part.substring(i + 1).split(',').mapNotNull { it.trim().toFloatOrNull() }
            if (n.size < 2 || n.any { it.isNaN() || it < 0f || it > 1.5f }) continue
            out[k] = StTarget(n[0].coerceIn(0f, 1f) * w, n[1].coerceIn(0f, 1f) * h, (n.getOrNull(2) ?: 0f) * m)
        }
        return out
    }

    /** Ключ профиля: пакет игры и ориентация (координаты в портрете и в альбоме разные). */
    fun key(pkg: String, w: Int, h: Int) = "p:$pkg:" + (if (h > w) "port" else "land")

    private fun fmt(v: Float) = ((v * 10000f).roundToInt() / 10000f).toString()

    /** Метки, чья точка лежит под окном самого оверлея (касание попадёт в оверлей, а не в игру). rect: left, top, right, bottom. */
    fun covered(t: Map<String, StTarget>, rects: List<FloatArray>): Set<String> =
        t.filter { (_, v) -> rects.any { r -> v.x >= r[0] && v.x < r[2] && v.y >= r[1] && v.y < r[3] } }.keys
}
