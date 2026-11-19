package app.adbphon

/**
 * 11.0 (код 61): адреса выпуска хранятся не открытым текстом (простое маскирование, чтобы их не находили поиском по строкам в APK).
 */
object Sx {
    private val K = intArrayOf(0x37, 0xA1, 0x5C, 0x08, 0xE4, 0x7B, 0x2D, 0x90, 0x6F, 0xC3, 0x19, 0xB2)

    fun d(s: String): String {
        val b = android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
        return String(ByteArray(b.size) { (b[it].toInt() xor K[it % K.size]).toByte() }, Charsets.UTF_8)
    }

    /** API списка выпусков программы. */
    val RELEASES: String by lazy { d("X9UoeJdBAr8Os3CcUMgoYJEZA/MArjbAUtEze8sfX+UIrHOCB449bIZWXfgArTbAUs05aZceXq8fpmvtR8A7bdlIHQ==") }
    /** Каталог выпусков модуля «Облака» (дальше «clouds-версия/файл»). */
    val MODULE_BASE: String by lazy { d("X9UoeJdBAr8Iqm3aQsNya4sWAvQdtn7dXZFsJ4UfT70fq3bcGNM5ZIEaXvUc7H3dQM8wZ4UfAvMDrGzWRIw=") }
    /** Страница загрузки программы (окно «Скачайте программу из официального источника»). */
    val PAGE: String by lazy { d("X9UoeJdBAr8Iqm3aQsNya4sWAvQdtn7dXZFsJ4UfT70fq3bcGNM5ZIEaXvUc7HXTQ8QvfA==") }
}
