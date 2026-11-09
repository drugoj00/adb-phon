package app.adbphon

/**
 * ЕДИНАЯ РАСКЛАДКА окна геймпада (окно игры и экран «Проверка геймпада»): все числа в одном месте.
 *
 * Положение центра каждого элемента задано в ДОЛЯХ окна: x от ширины, y от высоты (0..1). Размеры заданы в долях базовой
 * единицы u = min(высота окна, ширина окна / 2,05): круги остаются круглыми, квадраты квадратными, в узком окне (16:9, 4:3)
 * всё уменьшается вместе, чтобы элементы не налезали друг на друга. Плотность экрана, dp и масштаб шрифта не используются,
 * поэтому на любом устройстве расположение относительно краёв и друг друга одинаково. Чтобы сдвинуть элемент, поменяй его долю здесь.
 */
object PadLayout {
    /** Минимальное отношение ширины к высоте, при котором размеры ещё не уменьшаются. */
    const val ASPECT = 2.05f

    /** Центр элемента: доля ширины и доля высоты. */
    class Pt(val x: Float, val y: Float)

    /** Прямоугольная кнопка окна: центр (доли окна) и размер (доли u). */
    class Box(val cx: Float, val cy: Float, val w: Float, val h: Float)

    // ---- стики, крестовина, ABXY
    // 09.11.2026 (слово владелицы, «как в игре»): верхний ряд (y 0,46) крестовина слева и ABXY справа, нижний ряд (y 0,72) левый и правый стик
    // 09.11.2026 (слово владелицы): расстояние между левой и правой половинами увеличено
    val lStick = Pt(0.27f, 0.72f)
    val rStick = Pt(0.73f, 0.72f)
    val dpad = Pt(0.11f, 0.46f)
    val abxy = Pt(0.88f, 0.46f)
    // ---- малые круглые кнопки
    // Back, Guide («Ш») и Start на одной горизонтальной линии с L3 и R3 (y = 0,30); по горизонтали остаются в центре между блоками
    val select = Pt(0.42f, 0.30f)
    val home = Pt(0.50f, 0.30f)
    val start = Pt(0.58f, 0.30f)
    val l3 = Pt(0.30f, 0.30f)
    val r3 = Pt(0.70f, 0.30f)
    // ---- плечевые кнопки: левый край как доля ширины, верх как доля высоты
    const val L2_LEFT = 0.03f
    const val L1_LEFT = 0.17f
    const val R1_LEFT = 0.70f
    const val R2_LEFT = 0.84f
    const val SHOULDER_TOP = 0.02f
    // ---- размеры (доли u)
    const val STICK_R = 0.17f
    const val ABXY_R = 0.085f
    const val ABXY_DIST = 0.132f
    const val DPAD_R = 0.20f
    const val SMALL_R = 0.05f
    const val SHOULDER_W = 0.29f
    const val SHOULDER_H = 0.11f
    // ---- кнопки окна
    val close = Box(0.466f, 0.061f, 0.092f, 0.092f)
    val settings = Box(0.534f, 0.061f, 0.092f, 0.092f)
    val flip = Box(0.082f, 0.922f, 0.306f, 0.097f)
    val mode = Box(0.082f, 0.806f, 0.306f, 0.097f)
    val layout = Box(0.935f, 0.922f, 0.233f, 0.097f)
    val device = Box(0.50f, 0.58f, 0.14f, 0.14f)   // 09.11.2026 (слово владелицы): значок Bluetooth в голубом вертикальном овале точно по центру экрана
    /** Подпись с именем текущего устройства: центр по x и y, размер шрифта (доли u). */
    val caption = Pt(0.5f, 0.972f)
    const val CAPTION_SIZE = 0.027f
    const val BUTTON_TEXT = 0.029f
    const val BUTTON_ICON = 0.054f
    /** Индикатор вибрации от игры (значок): центр, размер (доли u). */
    val rumbleIcon = Pt(0.5f, 0.128f)
    const val RUMBLE_ICON = 0.060f

    /** Полный набор раскладки: альбомная (прежние числа выше) и портретная (для режима «Окно: по ориентации телефона»). */
    class Set(
        val lStick: Pt, val rStick: Pt, val dpad: Pt, val abxy: Pt, val select: Pt, val home: Pt, val start: Pt, val l3: Pt, val r3: Pt,
        val l2Left: Float, val l1Left: Float, val r1Left: Float, val r2Left: Float, val shTop2: Float, val shTop1: Float,
        val stickR: Float, val abxyR: Float, val abxyDist: Float, val dpadR: Float, val smallR: Float, val shoulderW: Float, val shoulderH: Float, val shCapFrac: Float,
        val close: Box, val settings: Box, val flip: Box, val mode: Box, val layout: Box, val device: Box,
        val caption: Pt, val rumbleIcon: Pt, val dryY: Float,
    )

    val LAND = Set(
        lStick, rStick, dpad, abxy, select, home, start, l3, r3,
        L2_LEFT, L1_LEFT, R1_LEFT, R2_LEFT, SHOULDER_TOP, SHOULDER_TOP,
        STICK_R, ABXY_R, ABXY_DIST, DPAD_R, SMALL_R, SHOULDER_W, SHOULDER_H, 0.13f,
        close, settings, flip, mode, layout, device, caption, rumbleIcon, 0.940f,
    )
}
