package app.adbphon

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Правило 20: установка APK только текущему пользователю (--user). Без этого на Samsung копия ложится ещё и в «Двойное приложение» (пользователь 95),
 * значки в меню телефона задваиваются. Проверка по исходникам: ни одна команда установки не собирается без --user (или без withUser).
 */
class InstallUserTest {
    private fun sources(): List<File> {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "src/main/java/app/adbphon").exists()) d = d.parentFile
        val root = File(d ?: return emptyList(), "src/main/java/app/adbphon")
        return root.listFiles { f -> f.name.endsWith(".kt") }?.toList().orEmpty()
    }

    @Test fun pmInstallCmdHasUser() {
        assertTrue(pmInstallCmd("/sdcard/a.apk").contains("--user"))
    }

    @Test fun noInstallCommandWithoutUser() {
        val rx = Regex("(pm install|cmd package install|install-existing|install-create|install-multiple)\b")
        val bad = ArrayList<String>()
        for (f in sources()) {
            f.readLines().forEachIndexed { i, line ->
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@forEachIndexed
                if (!rx.containsMatchIn(line)) return@forEachIndexed
                // команды установки, у которых пользователь добавляется общей функцией withUser или указан явно
                if (line.contains("--user") || line.contains("withUser(") || line.contains("install-abandon") || line.contains("install-commit") || line.contains("install-write")) return@forEachIndexed
                // «install-create ${flags}» без --user: допустимо только внутри Adb.installMultiple через withUser
                bad.add(f.name + ":" + (i + 1) + ": " + t.take(120))
            }
        }
        assertTrue("Команды установки без --user:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
