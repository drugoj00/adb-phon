package app.adbphon

/**
 * Библиотека ADB (libadb-android 3.1.1) при открытии службы выделяет буфер по числу символов строки, а кладёт в него UTF-8 байты:
 * любая не-ASCII буква в команде (кириллица в пути) вызывает BufferOverflowException («Buffer Overflow Exeption»).
 * Поэтому не-ASCII символы заменяются на запись $'\xHH' оболочки (mksh на Android 8–16), с учётом кавычек вокруг.
 * Строки без не-ASCII символов возвращаются как есть.
 */
object AdbText {
    private const val BS = '\\'
    private const val SQ = '\''
    private const val DQ = '"'

    fun safe(cmd: String): String {
        if (cmd.all { it.code < 128 }) return cmd
        val sb = StringBuilder(cmd.length + 32)
        var st = 0            // 0: вне кавычек, 1: внутри '...', 2: внутри "..."
        var i = 0
        while (i < cmd.length) {
            val c = cmd[i]
            if (c.code >= 128) {
                var j = i
                while (j < cmd.length && cmd[j].code >= 128) j++
                val esc = cmd.substring(i, j).toByteArray(Charsets.UTF_8).joinToString("") { BS + "x" + "%02x".format(it.toInt() and 0xff) }
                when (st) {
                    1 -> sb.append(SQ).append('$').append(SQ).append(esc).append(SQ).append(SQ)
                    2 -> sb.append(DQ).append('$').append(SQ).append(esc).append(SQ).append(DQ)
                    else -> sb.append('$').append(SQ).append(esc).append(SQ)
                }
                i = j
                continue
            }
            when (st) {
                0 -> when {
                    c == BS && i + 1 < cmd.length && cmd[i + 1].code >= 128 -> { i++; continue }   // обратная косая перед не-ASCII вне кавычек равна самому символу
                    c == BS && i + 1 < cmd.length -> { sb.append(c).append(cmd[i + 1]); i += 2; continue }
                    c == SQ -> { st = 1; sb.append(c) }
                    c == DQ -> { st = 2; sb.append(c) }
                    else -> sb.append(c)
                }
                1 -> { if (c == SQ) st = 0; sb.append(c) }
                else -> when {
                    c == BS && i + 1 < cmd.length && cmd[i + 1].code < 128 -> { sb.append(c).append(cmd[i + 1]); i += 2; continue }
                    c == DQ -> { st = 0; sb.append(c) }
                    else -> sb.append(c)
                }
            }
            i++
        }
        return sb.toString()
    }
}
