package app.adbphon

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 26.11.2026 (ТОДО «облака долго открываются»): последний прочитанный список каждой облачной папки лежит в закрытом кэше программы.
 * После перезапуска папка показывается из него сразу, а свежий список читается следом и заменяет показанный. Паролей и токенов здесь нет,
 * только имена, размеры и даты. Удалили облако из программы: его списки удаляются.
 */
object CloudListCache {
    private const val MAX_FILES = 600
    private const val MAX_ITEMS = 5000

    private fun hash(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun dir(ctx: Context, remote: String) = File(File(ctx.cacheDir, "cloudlists"), hash(remote))
    private fun file(ctx: Context, remote: String, path: String) = File(dir(ctx, remote), hash(path.trim('/')))

    fun get(ctx: Context, remote: String, path: String): List<FsEntry>? = runCatching {
        val f = file(ctx, remote, path)
        if (!f.isFile) return null
        val a = JSONArray(f.readText())
        (0 until a.length()).map { val o = a.getJSONObject(it); FsEntry(o.getString("n"), o.getString("p"), o.getBoolean("d"), o.getLong("s"), o.optString("t")) }
    }.getOrNull()

    @Synchronized
    fun put(ctx: Context, remote: String, path: String, list: List<FsEntry>) {
        runCatching {
            val f = file(ctx, remote, path)
            if (list.size > MAX_ITEMS) { f.delete(); return }
            f.parentFile?.mkdirs()
            val a = JSONArray()
            list.forEach { a.put(JSONObject().put("n", it.name).put("p", it.path).put("d", it.isDir).put("s", it.size).put("t", it.date)) }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(a.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            val all = f.parentFile?.listFiles().orEmpty()
            if (all.size > MAX_FILES) all.sortedBy { it.lastModified() }.take(all.size - MAX_FILES).forEach { it.delete() }
        }
    }

    fun drop(ctx: Context, remote: String) { runCatching { dir(ctx, remote).deleteRecursively() } }
}
