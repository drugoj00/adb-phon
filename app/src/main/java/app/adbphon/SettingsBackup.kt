package app.adbphon

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Резервная копия настроек программы в папку, выбранную пользователем (папка запоминается). */
object SettingsBackup {
    private const val PREFS = "adbphon"
    private const val KEY_TREE = "backup_tree"
    private const val PREFIX = "ADB-phon-settings-"

    data class Item(val name: String, val uri: Uri, val time: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folder(ctx: Context): Uri? = prefs(ctx).getString(KEY_TREE, null)?.let { Uri.parse(it) }

    /** Короткое имя папки для показа: «Download/ADB-phon» вместо длинного адреса. */
    fun folderLabel(uri: Uri): String = runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfter(':') }
        .getOrDefault(uri.toString()).ifBlank { "/" }

    /** Запоминает папку и сохраняет право на неё после перезапуска приложения. */
    fun setFolder(ctx: Context, uri: Uri) {
        folder(ctx)?.let { old ->
            if (old != uri) runCatching { ctx.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs(ctx).edit().putString(KEY_TREE, uri.toString()).apply()
    }

    private fun treeDoc(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** Все настройки в JSON; сама выбранная папка не входит (она своя на каждом устройстве). */
    private fun toJson(ctx: Context): String {
        val o = JSONObject()
        prefs(ctx).all.forEach { (k, v) ->
            if (k == KEY_TREE) return@forEach
            val e = JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                is Set<*> -> e.put("t", "S").put("v", JSONArray(v.map { it.toString() }))
                else -> return@forEach
            }
            o.put(k, e)
        }
        return JSONObject().put("app", "ADB-phon").put("format", 1).put("prefs", o).toString(2)
    }

    /** Создаёт файл копии в выбранной папке и возвращает его имя. */
    fun backup(ctx: Context): String {
        val tree = folder(ctx) ?: throw IllegalStateException("no folder")
        val name = PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".json"
        val doc = DocumentsContract.createDocument(ctx.contentResolver, treeDoc(tree), "application/json", name)
            ?: throw IllegalStateException("cannot create file")
        ctx.contentResolver.openOutputStream(doc)!!.use { it.write(toJson(ctx).toByteArray(Charsets.UTF_8)) }
        return name
    }

    /** Копии настроек в выбранной папке, самые новые первыми. */
    fun list(ctx: Context): List<Item> {
        val tree = folder(ctx) ?: return emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = ArrayList<Item>()
        ctx.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.startsWith(PREFIX) && name.endsWith(".json")) {
                    out += Item(name.removePrefix(PREFIX).removeSuffix(".json"), DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), c.getLong(2))
                }
            }
        }
        return out.sortedByDescending { it.name }
    }

    /** Заменяет настройки программы настройками из файла; папка копий остаётся прежней. */
    fun restore(ctx: Context, item: Item) {
        val text = ctx.contentResolver.openInputStream(item.uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val root = JSONObject(text)
        if (root.optString("app") != "ADB-phon") throw IllegalStateException("not an ADB-phon backup")
        val src = root.getJSONObject("prefs")
        val ed = prefs(ctx).edit()
        prefs(ctx).all.keys.filter { it != KEY_TREE }.forEach { ed.remove(it) }
        src.keys().forEach { k ->
            val e = src.getJSONObject(k)
            when (e.getString("t")) {
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "s" -> ed.putString(k, e.getString("v"))
                "S" -> ed.putStringSet(k, e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
            }
        }
        ed.commit()
    }
}
