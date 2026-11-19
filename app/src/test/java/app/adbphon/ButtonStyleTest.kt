package app.adbphon

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 14.0: форма кнопок одна на всех (AppUi.BTN_RADIUS, Btn.kt). Material3 вызывается только через обёртки пакета app.adbphon (Haptics.kt, Btn.kt).
 * Явный `import androidx.compose.material3.OutlinedButton` (и Button, TextButton, FilledTonalButton, ElevatedButton) перекрывает обёртку, а вызов по полному имени
 * обходит её: кнопка снова становится «таблеткой» (полный овал). Проверка по исходникам.
 */
class ButtonStyleTest {
    private fun sources(): List<File> {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "src/main/java/app/adbphon").exists()) d = d.parentFile
        val root = File(d ?: return emptyList(), "src/main/java/app/adbphon")
        return root.listFiles { f -> f.name.endsWith(".kt") }?.toList().orEmpty()
    }

    /** Свои обёртки; Integrity.kt правится отдельно (две текстовые кнопки окна «официальный источник»), убрать из списка после слияния правки. */
    private val skip = setOf("Haptics.kt", "Btn.kt", "Integrity.kt")

    @Test fun noDirectMaterialButtons() {
        val rx = Regex("androidx\\.compose\\.material3\\.(Button|OutlinedButton|TextButton|FilledTonalButton|ElevatedButton)\\b")
        val bad = ArrayList<String>()
        for (f in sources()) {
            if (f.name in skip) continue
            f.readLines().forEachIndexed { i, line ->
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@forEachIndexed
                if (rx.containsMatchIn(line)) bad.add(f.name + ":" + (i + 1) + ": " + t.take(120))
            }
        }
        assertTrue("Кнопки Material3 напрямую (нужны обёртки Haptics.kt и Btn.kt, форма AppUi.BTN_SHAPE): убрать import и вызов по полному имени:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
