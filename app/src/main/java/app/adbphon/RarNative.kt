package app.adbphon

/**
 * Родная часть для RAR 5 (официальная библиотека UnRAR, только распаковка, около 0,2 МБ на архитектуру).
 * Список записей: «имя \u0001 размер \u0001 упакованный \u0001 время DOS \u0001 crc \u0001 флаги»; ошибка открытия — один элемент «!NN».
 */
object RarNative {
    private val ok: Boolean = try { System.loadLibrary("unrar_jni"); true } catch (_: Throwable) { false }
    val available: Boolean get() = ok
    @JvmStatic external fun list(path: String, password: String?): Array<String>
    @JvmStatic external fun extract(path: String, index: Int, outFile: String, password: String?): Int
}
