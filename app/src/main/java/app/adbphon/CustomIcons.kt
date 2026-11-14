package app.adbphon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Свои иконки (7.0): для отдельной папки, для расширения файла и для категории файлов (фото, видео, музыка...).
 * Хранятся в настройках «icons»: ключ «f:путь» (папка), «p:путь» (файл), «e:расширение», «c:КАТЕГОРИЯ»; значение «b:имя» (значок из набора)
 * или «i:файл» (своё изображение, копия 128x128 лежит в files/icons).
 */
object CustomIcons {
    class Spec(val vec: ImageVector?, val bmp: Bitmap?)

    val builtin: List<Pair<String, ImageVector>> = listOf(
        "folder" to Icons.Filled.Folder, "star" to Icons.Filled.Star, "fav" to Icons.Filled.Favorite, "home" to Icons.Filled.Home,
        "work" to Icons.Filled.Work, "school" to Icons.Filled.School, "movie" to Icons.Filled.Movie, "photo" to Icons.Filled.Photo,
        "music" to Icons.Filled.MusicNote, "book" to Icons.Filled.Book, "games" to Icons.Filled.SportsEsports, "download" to Icons.Filled.Download,
        "cloud" to Icons.Filled.Cloud, "lock" to Icons.Filled.Lock, "build" to Icons.Filled.Build, "android" to Icons.Filled.Android,
        "code" to Icons.Filled.Code, "doc" to Icons.Filled.Description, "cart" to Icons.Filled.ShoppingCart, "pets" to Icons.Filled.Pets,
        "flight" to Icons.Filled.Flight, "food" to Icons.Filled.Restaurant, "archive" to Icons.Filled.Archive, "people" to Icons.Filled.People,
        "image" to Icons.Filled.Image, "video" to Icons.Filled.Videocam, "public" to Icons.Filled.Public, "settings" to Icons.Filled.Settings,
    )

    /** Растёт при каждом изменении: значки перерисовываются. */
    var ver by mutableStateOf(0)
    private var ctxRef: Context? = null
    private val cache = HashMap<String, Bitmap?>()

    fun init(c: Context) { ctxRef = c.applicationContext }
    /** Сбросить все значки с ключами на [prefix] (например «s:» — кнопки панели). */
    fun clearPrefix(c: Context, prefix: String) { val s = sp(c); val e = s.edit(); s.all.keys.filter { it.startsWith(prefix) }.forEach { e.remove(it) }; e.apply(); ver++ }
    /** После восстановления из копии: забыть загруженные картинки и перерисовать значки. */
    fun reload() { cache.clear(); ver++ }

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("icons", Context.MODE_PRIVATE)

    fun raw(c: Context, key: String): String? = sp(c).getString(key, null)

    fun set(c: Context, key: String, value: String?) {
        sp(c).edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        ver++
    }

    private fun resolve(c: Context, v: String?): Spec? {
        if (v == null) return null
        if (v.startsWith("b:")) { val id = v.substring(2); return builtin.firstOrNull { it.first == id }?.let { Spec(it.second, null) } }
        if (v.startsWith("i:")) {
            val name = v.substring(2)
            val b = cache.getOrPut(name) { runCatching { BitmapFactory.decodeFile(File(File(c.filesDir, "icons"), name).absolutePath) }.getOrNull() }
            return b?.let { Spec(null, it) }
        }
        return null
    }

    @Composable
    fun spec(key: String): Spec? {
        ver.let { }
        val c = LocalContext.current
        return resolve(c, raw(c, key))
    }

    /** Значок для файла: по полному пути (один файл), затем по расширению, затем по категории. */
    @Composable
    fun specFor(ext: String, localPath: String?, cat: FCat): Spec? {
        ver.let { }
        val c = LocalContext.current
        val sp = sp(c)
        if (sp.all.isEmpty()) return null
        return (if (localPath != null) resolve(c, sp.getString("p:$localPath", null)) else null)
            ?: resolve(c, sp.getString("e:$ext", null)) ?: resolve(c, sp.getString("c:" + cat.name, null))
    }

    /** Копирует выбранное изображение в files/icons (128x128 по центру) и возвращает значение «i:имя». */
    fun importImage(c: Context, uri: Uri): String? = runCatching {
        val src = c.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) } ?: return null
        val side = minOf(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val out = Bitmap.createScaledBitmap(sq, 128, 128, true)
        val dir = File(c.filesDir, "icons").also { it.mkdirs() }
        val name = "ic" + System.currentTimeMillis() + ".png"
        File(dir, name).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        "i:$name"
    }.getOrNull()
}

/** Значок внутри кнопки (без своей подложки): значок из набора красится в [tint], картинка рисуется как есть. */
@Composable
fun CustomIconGlyph(spec: CustomIcons.Spec, size: Dp, tint: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    if (spec.bmp != null) Image(spec.bmp.asImageBitmap(), null, modifier.size(size).clip(RoundedCornerShape(size * 0.2f)))
    else Icon(spec.vec!!, null, modifier.size(size), tint = tint)
}

/** Значок категории (по умолчанию стандартный значок типа файла), с учётом своей иконки «c:КАТЕГОРИЯ». */
@Composable
fun CategoryIcon(cat: FCat, ext: String, size: Dp, modifier: Modifier = Modifier) {
    val cs = CustomIcons.spec("c:" + cat.name)
    if (cs != null) CustomIconView(cs, size, modifier) else FileGlyph(cat, ext, size, modifier)
}

@Composable
fun CustomIconView(spec: CustomIcons.Spec, size: Dp, modifier: Modifier = Modifier) {
    if (spec.bmp != null) {
        Image(spec.bmp.asImageBitmap(), null, modifier.size(size).clip(RoundedCornerShape(size * 0.2f)))
    } else {
        Box(modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(MaterialTheme.colorScheme.primary.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            Icon(spec.vec!!, null, Modifier.size(size * 0.66f), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Выбор иконки для ключа [key]: значок из набора, своё изображение или сброс. */
@Composable
fun CustomIconPicker(title: String, key: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var chooseSrc by androidx.compose.runtime.remember { mutableStateOf(false) }
    if (chooseSrc) {
        ImageSourceDialog(
            onPicked = { uri ->
                val r = CustomIcons.importImage(ctx, uri)
                if (r != null) CustomIcons.set(ctx, key, r)
                else android.widget.Toast.makeText(ctx, ctx.getString(R.string.imgsrc_noaccess), android.widget.Toast.LENGTH_LONG).show()
                onClose()
            },
            onClose = { chooseSrc = false },
        )
        return
    }
    LAlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.ic_title) + ": " + title, fontSize = 16.sp, maxLines = 1) },
        text = {
            Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CustomIcons.builtin.chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { (id, vec) ->
                            Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .12f))
                                .clickable { CustomIcons.set(ctx, key, "b:$id"); onClose() }, contentAlignment = Alignment.Center) {
                                Icon(vec, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        repeat(5 - row.size) { Box(Modifier.size(46.dp)) }
                    }
                }
            }
        },
        confirmButton = { TextButton({ chooseSrc = true }) { Text(stringResource(R.string.ic_file)) } },
        dismissButton = {
            Row {
                TextButton({ CustomIcons.set(ctx, key, null); onClose() }) { Text(stringResource(R.string.ic_reset)) }
                TextButton(onClose) { Text(stringResource(R.string.btn_close)) }
            }
        },
    )
}

/** Окно «Иконки типов файлов»: категория, расширение, выбор иконки. */
@Composable
fun FileTypeIconsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var pickKey by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    var pickTitle by androidx.compose.runtime.remember { mutableStateOf("") }
    var ext by androidx.compose.runtime.remember { mutableStateOf("") }
    val names = listOf(
        FCat.PHOTO to R.string.cat_photo, FCat.VIDEO to R.string.cat_video, FCat.AUDIO to R.string.cat_audio, FCat.PDF to R.string.cat_pdf,
        FCat.DOC to R.string.cat_doc, FCat.XLS to R.string.cat_xls, FCat.PPT to R.string.cat_ppt, FCat.TEXT to R.string.cat_text,
        FCat.ARCHIVE to R.string.cat_archive, FCat.APK to R.string.cat_apk, FCat.CODE to R.string.cat_code, FCat.OTHER to R.string.cat_other,
    )
    LAlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.ic_types)) },
        text = {
            Column(Modifier.barScroll(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                names.forEach { (cat, res) ->
                    val label = stringResource(res)
                    val key = "c:" + cat.name
                    Row(Modifier.fillMaxWidth().clickable { pickKey = key; pickTitle = label }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val sp = CustomIcons.spec(key)
                        if (sp != null) CustomIconView(sp, 32.dp) else FileGlyph(cat, "", 32.dp)
                        Text(label, fontSize = 15.sp)
                    }
                }
                androidx.compose.material3.OutlinedTextField(ext, { ext = it.trim().lowercase().removePrefix(".") }, singleLine = true, label = { Text(stringResource(R.string.ic_ext)) }, modifier = Modifier.fillMaxWidth())
                TextButton({ if (ext.isNotBlank()) { pickKey = "e:$ext"; pickTitle = "." + ext } }, enabled = ext.isNotBlank()) { Text(stringResource(R.string.ic_pick_ext)) }
            }
        },
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.btn_close)) } },
    )
    pickKey?.let { k -> CustomIconPicker(pickTitle, k) { pickKey = null } }
}
