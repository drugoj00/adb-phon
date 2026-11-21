package app.adbphon

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Разбор значка APK без Android (ApkIconRes) на настоящих файлах. Папка с APK берётся из переменной APK_CORPUS (по умолчанию C:\Программы\АНДРОИД, до 400 файлов);
 * если папки нет, тест пропускается. Подробный список (что найдено у каждого файла) пишется в APK_CORPUS_OUT, если она задана.
 */
class ApkIconResTest {
    @Test fun corpusFindsIcons() {
        val root = File(System.getenv("APK_CORPUS") ?: "C:\\Программы\\АНДРОИД")
        assumeTrue(root.isDirectory)
        val files = root.walkTopDown().filter { it.isFile && it.extension.equals("apk", true) && it.length() < 300L * 1024 * 1024 }.take(400).toList()
        assumeTrue(files.isNotEmpty())
        var bmp = 0; var other = 0; var noIcon = 0; var bad = 0
        val report = StringBuilder()
        for (f in files) {
            val line = try {
                ZipFile(f).use { z ->
                    val r = ApkIconRes.resolve(z)
                    val tag = when {
                        r.root is ApkIconRes.D.Bmp -> { bmp++; "BM" }
                        r.root != null -> { other++; "XM" }
                        r.trace.startsWith("в манифесте нет ссылки") -> { noIcon++; "NO" }
                        else -> { bad++; "--" }
                    }
                    tag + " " + f.name + " :: " + r.trace
                }
            } catch (e: Throwable) { bad++; "ER " + f.name + " :: " + e.javaClass.simpleName + ": " + e.message }
            report.append(line).append("\n")
        }
        System.getenv("APK_CORPUS_OUT")?.let { File(it).writeText(report.toString()) }
        println("APK: всего ${files.size}, картинка $bmp, адаптивный/вектор/слои $other, без значка в манифесте $noIcon, не найдено $bad")
        assertTrue("значок найден менее чем у 95% файлов с значком: bmp=$bmp other=$other bad=$bad", (bmp + other) * 100 >= (bmp + other + bad) * 95)
    }
}
