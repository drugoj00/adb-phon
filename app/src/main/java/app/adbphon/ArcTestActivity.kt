package app.adbphon

import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.io.File

/**
 * Проверка архивов на устройстве без интерфейса (для разработчика):
 *   am start -n app.adbphon/.ArcTestActivity --es dir /sdcard/test_arc --es out /sdcard/test_arc_out --es pass Secret123
 * Для каждого файла папки: список, проверка, извлечение в out/<имя>; для zip ещё добавление, переименование и удаление внутри на копии.
 * Результат в logcat, тег ADBphonARC.
 */
class ArcTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(intent.getStringExtra("dir") ?: "/sdcard/test_arc")
        val out = File(intent.getStringExtra("out") ?: "/sdcard/test_arc_out")
        val pass = intent.getStringExtra("pass")?.toCharArray()
        fun log(s: String) { Log.i("ADBphonARC", s) }
        val pr = object : OpProgress { var b = 0L; override val cancelled = false; override fun bytes(n: Long) { b += n }; override fun file(name: String) {}; override fun item() {} }
        Thread {
            out.deleteRecursively(); out.mkdirs()
            for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
                if (Arc.kindOf(f.name) == null) continue
                val t0 = System.currentTimeMillis()
                try {
                    val needsPass = f.name.startsWith("aes") || f.name.startsWith("zc") || f.name.startsWith("enc")
                    val rd = try { Arc.open(f, null, if (needsPass) pass else null) } catch (e: ArcNeedPassword) { log("${f.name}: ArcNeedPassword (как и ожидалось без пароля), открываю с паролем"); Arc.open(f, null, pass) }
                    rd.use {
                        val files = it.entries.count { e -> !e.isDir }
                        log("${f.name}: формат ${it.kind}, записей ${it.entries.size}, файлов $files, кодировка ${it.charset}, имена: ${it.entries.map { e -> e.path }.take(6)}")
                        pr.b = 0
                        val n = Arc.test(it, pr)
                        log("${f.name}: тест OK, файлов $n, байт ${pr.b}")
                        val d = File(out, f.name); Arc.extractAll(it, d, pr)
                        log("${f.name}: извлечено в ${d.path}, ${System.currentTimeMillis() - t0} мс")
                    }
                } catch (e: Throwable) { log("${f.name}: ОШИБКА ${e.javaClass.simpleName}: ${e.message}") }
            }
            // изменение ZIP: копия, переименование, удаление, добавление
            runCatching {
                val src = File(dir, "ru.zip"); val z = File(out, "edit.zip"); src.copyTo(z, true)
                val add = File(out, "added.txt").also { it.writeText("добавлено в архив") }
                ZipEdit.apply(z, out, null, null, 6, delete = setOf("подпапка/вложенная"), rename = mapOf("русский файл.txt" to "переименован.txt"), addFiles = listOf(add to "новая папка/added.txt"))
                Arc.open(z).use { r -> log("edit.zip: ${r.entries.map { it.path }}"); Arc.test(r, pr); log("edit.zip: тест OK") }
            }.onFailure { log("edit.zip: ОШИБКА ${it}") }
            // создание из папки
            runCatching {
                val s = File(dir, "src_for_create")
                if (s.isDirectory) for (fmt in ArcCreate.formats) {
                    val o = File(out, "created.$fmt"); ArcCreate.create(fmt, o, listOf(s), 6, null, out, pr)
                    Arc.open(o).use { r -> log("created.$fmt: ${o.length()} байт, записей ${r.entries.size}, тест ${Arc.test(r, pr)}") }
                }
            }.onFailure { log("create: ОШИБКА ${it}") }
            log("ГОТОВО")
        }.start()
        finish()
    }
}
