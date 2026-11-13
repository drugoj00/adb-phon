package app.adbphon

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Base64
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сохранение скриншота и записи звонка (7.6). Страница звонка присылает куски записи (сообщение rec: вид P/V/A/E, первый, расширение, имя, base64),
 * они сразу пишутся в файл, чтобы запись не копилась в памяти.
 * Куда: у каждого вида своя папка (скриншоты, видео, звук): выбранная владельцем (системный выбор папки, запоминается) либо по умолчанию
 * Pictures / Movies / Music → «ADB-phon» → «Звонки» (через MediaStore; на Android 9 и старше обычной папкой).
 */
class CallSaver(private val ctx: Context, private val saved: (String, String, String) -> Unit) {
    private var out: OutputStream? = null
    private var kind = ""            // shot | video | audio
    private var display = ""
    private var uri: Uri? = null     // MediaStore-запись (IS_PENDING), закрывается в finishOne
    private var file: File? = null
    private var mime = ""

    var lastUri: Uri? = null; private set
    var lastMime: String = ""; private set

    private fun sp() = ctx.applicationContext.getSharedPreferences("call_ui", Context.MODE_PRIVATE)

    fun dirName(kind: String): String {
        val u = sp().getString("dir_$kind", "") ?: ""
        if (u.isEmpty()) return ""
        return runCatching { Uri.parse(u).lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: u }.getOrDefault(u)
    }

    fun setDir(kind: String, tree: Uri): Boolean = runCatching {
        ctx.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        sp().edit().putString("dir_$kind", tree.toString()).apply(); true
    }.getOrDefault(false)

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())
    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_').take(40).ifEmpty { "call" }

    private fun mimeFor(kind: String, ext: String) = when (kind) {
        "shot" -> "image/png"
        "audio" -> if (ext == "webm") "audio/webm" else "audio/mp4"
        else -> if (ext == "webm") "video/webm" else "video/mp4"
    }

    /** Открывает файл для записи: выбранная папка, иначе общая папка по умолчанию. */
    private fun open(k: String, ext: String, peer: String): Boolean {
        close()
        kind = k; mime = mimeFor(k, ext)
        val name = "Звонок_${safe(peer)}_${stamp()}.$ext"
        // 1. выбранная папка
        val tree = (sp().getString("dir_$k", "") ?: "").takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
        if (tree != null) {
            val ok = runCatching {
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val doc = DocumentsContract.createDocument(ctx.contentResolver, parent, mime, name)!!
                out = ctx.contentResolver.openOutputStream(doc)!!
                uri = null; file = null; lastUri = doc
                display = dirName(k) + "/" + name
            }.isSuccess
            if (ok) return true
            android.util.Log.w("TvoCall", "save: выбранная папка не открылась, пишу в папку по умолчанию")
        }
        // 2. по умолчанию
        return runCatching {
            val sub = when (k) { "shot" -> "Pictures"; "audio" -> "Music"; else -> "Movies" } + "/ADB-phon/Звонки"
            if (Build.VERSION.SDK_INT >= 29) {
                val col = when (k) { "shot" -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY); "audio" -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY); else -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) }
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, sub); put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val u = ctx.contentResolver.insert(col, cv)!!
                out = ctx.contentResolver.openOutputStream(u)!!
                uri = u; file = null; lastUri = u
            } else {
                val base = Environment.getExternalStoragePublicDirectory(when (k) { "shot" -> Environment.DIRECTORY_PICTURES; "audio" -> Environment.DIRECTORY_MUSIC; else -> Environment.DIRECTORY_MOVIES })
                val dir = File(base, "ADB-phon/Звонки").apply { mkdirs() }
                val f = File(dir, name)
                out = f.outputStream(); file = f; uri = null; lastUri = Uri.fromFile(f)
            }
            lastMime = mime
            display = "$sub/$name"
        }.isSuccess
    }

    private fun close() {
        runCatching { out?.flush(); out?.close() }
        out = null
        uri?.let { u -> runCatching { ctx.contentResolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) } }
        file?.let { f -> runCatching { android.media.MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), null, null) } }
        uri = null; file = null
    }

    /** k: P — снимок (весь файл), V/A — первый или следующий кусок записи, E — запись закончена. */
    fun onChunk(k: String, first: Boolean, ext0: String, peer: String, b64: String) {
        val ext = ext0.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "bin" }
        runCatching {
            when (k) {
                "P" -> {
                    if (!open("shot", "png", peer)) { saved("", "fail", ""); return }
                    out!!.write(Base64.decode(b64, Base64.DEFAULT)); val d = display; close(); saved(d, "shot", "")
                }
                "V", "A" -> {
                    if (first || out == null) { if (!open(if (k == "V") "video" else "audio", ext, peer)) { saved("", "fail", ""); return } }
                    if (b64.isNotEmpty()) out!!.write(Base64.decode(b64, Base64.DEFAULT))
                }
                "E" -> { if (out != null) { val d = display; val kd = kind; close(); saved(d, kd, "") } }
            }
        }.onFailure { android.util.Log.w("TvoCall", "save: $it"); close(); saved("", "fail", "") }
    }

    fun abort() { close() }
}
