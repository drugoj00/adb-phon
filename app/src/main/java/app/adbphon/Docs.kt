package app.adbphon

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipFile

/** Встроенные TXT-редактор, просмотр PDF и просмотр Office (docx, xlsx, pptx, odt, ods, odp) из «Файлов». */
object DocKind {
    val TEXT = setOf("txt", "log", "md", "json", "xml", "html", "htm", "csv", "ini", "cfg", "conf", "yml", "yaml", "kt", "java", "py", "js", "css", "sh", "bat", "properties", "c", "cpp", "h", "gradle", "kts", "toml", "srt", "vtt")
    val OFFICE = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp")
    fun of(ext: String): String? = when { ext in TEXT -> "text"; ext == "pdf" -> "pdf"; ext in OFFICE -> "office"; else -> null }
}

/** Вызов из «Файлов»: открыть документ встроенным просмотрщиком. */
fun openDoc(ctx: Context, p: FmPane, item: FmItem, kind: String) {
    MediaSrc.fs = p.smb; MediaSrc.local = p.src != "smb"; MediaSrc.src = p.src
    MediaSrc.entries = listOf(MediaEntry(item.name, FmIo.join(p.path, item.name))); MediaSrc.index = 0
    val cls = when (kind) { "text" -> TextEditorActivity::class.java; "pdf" -> PdfViewerActivity::class.java; else -> OfficeViewerActivity::class.java }
    ctx.startActivity(Intent(ctx, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private abstract class DocActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val lang = newBase.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getString("lang", "auto") ?: "auto"
        super.attachBaseContext(Lang.wrap(newBase, lang))
    }
}

// ------------------------------------------------------------------ текстовый редактор

class TextEditorActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val lang = newBase.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getString("lang", "auto") ?: "auto"
        super.attachBaseContext(Lang.wrap(newBase, lang))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) { Surface(Modifier.fillMaxSize()) { TextEditor { finish() } } } }
    }
}

private val CHARSETS = listOf("UTF-8", "windows-1251", "IBM866")
private const val EDIT_LIMIT = 8L * 1024 * 1024

private fun detect(b: ByteArray): String {
    val dec = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    return if (runCatching { dec.decode(java.nio.ByteBuffer.wrap(b)) }.isSuccess) "UTF-8" else "windows-1251"
}

@Composable
fun TextEditor(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val e = MediaSrc.entries.firstOrNull() ?: run { onClose(); return }
    var file by remember { mutableStateOf<File?>(null) }
    var raw by remember { mutableStateOf<ByteArray?>(null) }
    var enc by remember { mutableStateOf("UTF-8") }
    var readOnly by remember { mutableStateOf(false) }
    var wrap by remember { mutableStateOf(ctx.getSharedPreferences("fm", Context.MODE_PRIVATE).getBoolean("ed_wrap", true)) }
    var size by remember { mutableFloatStateOf(14f) }
    var dirty by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var find by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var r by remember { mutableStateOf("") }
    var backup by remember { mutableStateOf(true) }
    var tv by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var failed by remember { mutableStateOf(false) }
    var privPath by remember { mutableStateOf<String?>(null) }   // 9.0: файл взят через root/ADB, сохранение пишет обратно тем же способом
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                // 9.0: читаем файл любого типа до конца потоком (работает и для служебных файлов с нулевой длиной), а если обычному приложению
                // файл не отдаётся (Android/data, /data и т.п.) или источник ADB, берём его через root/ADB (копия в кэше, только для чтения)
                val viaAdb = MediaSrc.privNeeded(e.path)
                var f = MediaSrc.file(ctx, e)
                file = f
                if (viaAdb) privPath = e.path
                readOnly = (!MediaSrc.local && MediaSrc.fs !is KeepFs)
                fun readAll(x: File): ByteArray = x.inputStream().use { i -> if (x.length() > EDIT_LIMIT) { readOnly = true; i.readNBytes(1 shl 20) } else i.readBytes() }
                var bytes = runCatching { readAll(f) }.getOrElse { ByteArray(0) }
                if (bytes.isEmpty() && !viaAdb && MediaSrc.local && !FmIo.remote && FmIo.ensurePriv() == null) {
                    val tmp = File(File(ctx.cacheDir, "play").apply { mkdirs() }, e.name)
                    runCatching { tmp.delete(); Adb.pull(e.path, tmp) }
                    if (tmp.length() > 0) { f = tmp; file = f; privPath = e.path; bytes = readAll(tmp) }
                }
                raw = bytes; enc = detect(bytes)
                if (bytes.isEmpty()) msg = ctx.getString(R.string.ed_empty)
            }.onFailure { msg = ctx.getString(R.string.ed_read_failed, it.message ?: ""); failed = true }
        }
    }
    val text = remember(raw, enc) { raw?.let { String(it, Charset.forName(enc)) } }
    val edScope = androidx.compose.runtime.rememberCoroutineScope()
    fun save() {
        val f = file ?: return
        val pp = privPath
        if (pp != null) {
            // файл из Android/data, /data и т.п.: пишем в копию и отправляем на место через root (если есть), иначе ADB
            msg = ctx.getString(R.string.ed_saving_net)
            edScope.launch {
                val err = withContext(Dispatchers.IO) { runCatching {
                    // 9.0: безопасная запись: копия .bak, отправка во временный файл рядом, проверка размера, затем перенос содержимого на место (владелец и права файла не меняются), проверка размера после записи
                    val bytes = tv.text.toByteArray(Charset.forName(enc))
                    f.writeBytes(bytes); FmIo.ensurePriv()
                    fun q(x: String) = "'" + x.replace("'", "'\\''") + "'"
                    val tmp = pp + ".adbphon-tmp"
                    if (backup) Adb.shell("cp -f " + q(pp) + " " + q(pp + ".bak") + " 2>/dev/null")
                    f.inputStream().use { i -> Adb.pushStream(i, bytes.size.toLong(), tmp) }
                    val sz1 = Adb.shell("stat -c %s " + q(tmp) + " 2>&1").trim().toLongOrNull()
                    if (sz1 != bytes.size.toLong()) { Adb.shell("rm -f " + q(tmp)); error("size " + sz1 + " != " + bytes.size) }
                    val r = Adb.shell("cat " + q(tmp) + " > " + q(pp) + " 2>&1; echo rc=$?")
                    Adb.shell("rm -f " + q(tmp))
                    val sz2 = Adb.shell("stat -c %s " + q(pp) + " 2>&1").trim().toLongOrNull()
                    if (!r.contains("rc=0") || sz2 != bytes.size.toLong()) error("write check failed: " + r.take(120) + " size=" + sz2)
                }.exceptionOrNull() }
                if (err == null) { dirty = false; msg = ctx.getString(R.string.ed_saved) } else msg = ctx.getString(R.string.ed_save_failed, err.message ?: "")
            }
            return
        }
        val net = if (MediaSrc.local) null else MediaSrc.fs as? KeepFs
        runCatching {
            if (backup && f.exists() && net == null) f.copyTo(File(f.path + ".bak"), overwrite = true)
            f.writeBytes(tv.text.toByteArray(Charset.forName(enc)))
        }.onSuccess {
            if (net == null) { dirty = false; msg = ctx.getString(R.string.ed_saved) }
            else {
                // файл на сетевом источнике: безопасная замена на сервере (временное имя, проверка размера, переименование)
                msg = ctx.getString(R.string.ed_saving_net)
                edScope.launch { val err = withContext(Dispatchers.IO) { runCatching { net.upload(f, e.path, true) }.exceptionOrNull() }; if (err == null) { dirty = false; msg = ctx.getString(R.string.ed_saved) } else msg = ctx.getString(R.string.ed_save_failed, err.message ?: "") }
            }
        }.onFailure { msg = ctx.getString(R.string.ed_save_failed, it.message ?: "") }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("✕", fontSize = 18.sp) }
            Text(e.name + if (dirty) " *" else "", Modifier.weight(1f), fontSize = 14.sp, maxLines = 1)
            if (!readOnly) TextButton({ save() }, enabled = dirty) { Text(stringResource(R.string.ed_save), fontSize = 13.sp) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton({ enc = CHARSETS[(CHARSETS.indexOf(enc).coerceAtLeast(0) + 1) % CHARSETS.size] }) { Text(enc, fontSize = 12.sp) }
            TextButton({ wrap = !wrap; ctx.getSharedPreferences("fm", Context.MODE_PRIVATE).edit().putBoolean("ed_wrap", wrap).apply() }) { Text(stringResource(if (wrap) R.string.ed_wrap_on else R.string.ed_wrap_off), fontSize = 12.sp) }
            TextButton({ size = (size - 2f).coerceAtLeast(8f) }) { Text("A−", fontSize = 12.sp) }
            TextButton({ size = (size + 2f).coerceAtMost(40f) }) { Text("A+", fontSize = 12.sp) }
            TextButton({ find = true }) { Text(stringResource(R.string.ed_find), fontSize = 12.sp, maxLines = 1, softWrap = false) }
        }
        if (readOnly) Text(stringResource(R.string.ed_readonly), Modifier.padding(horizontal = 8.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        msg?.let { Text(it, Modifier.padding(horizontal = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
        if (text != null) {
            // поле редактора на Compose: надёжная раскладка, прокрутка по вертикали (и по горизонтали без переноса)
            var tfv by remember(text) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(text)) }
            tv = tfv
            Box(Modifier.fillMaxWidth().weight(1f).barScroll().then(if (!wrap) Modifier.horizontalScroll(rememberScrollState()) else Modifier).padding(horizontal = 8.dp)) {
                androidx.compose.material3.TextField(
                    value = tfv, onValueChange = { nv -> if (!readOnly) { if (nv.text != tfv.text) dirty = true; tfv = nv; tv = nv } },
                    modifier = if (wrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = 300.dp), readOnly = readOnly,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = size.sp),
                    colors = androidx.compose.material3.TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                )
            }
        } else if (!failed) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
    }
    if (find) {
        LAlertDialog(onDismissRequest = { find = false }, title = { Text(stringResource(R.string.ed_find)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(q, { q = it }, singleLine = true, label = { Text(stringResource(R.string.ed_find_what), maxLines = 1) })
                    if (!readOnly) OutlinedTextField(r, { r = it }, singleLine = true, label = { Text(stringResource(R.string.ed_replace_with), maxLines = 1) })
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) { androidx.compose.material3.Checkbox(backup, { backup = it }); Text(stringResource(R.string.ed_backup), fontSize = 13.sp) }
                }
            },
            confirmButton = {
                Row {
                    TextButton({
                        val i = tv.text.indexOf(q, tv.selection.end.coerceAtLeast(0)).let { if (it < 0) tv.text.indexOf(q) else it }
                        if (i >= 0 && q.isNotEmpty()) tv = tv.copy(selection = androidx.compose.ui.text.TextRange(i, i + q.length)) else msg = ctx.getString(R.string.ed_not_found)
                        find = false
                    }) { Text(stringResource(R.string.ed_find_next)) }
                    if (!readOnly) TextButton({
                        if (q.isNotEmpty()) { val n = tv.text.split(q).size - 1; tv = androidx.compose.ui.text.input.TextFieldValue(tv.text.replace(q, r)); msg = ctx.getString(R.string.ed_replaced, n); dirty = true }
                        find = false
                    }) { Text(stringResource(R.string.ed_replace_all)) }
                }
            },
            dismissButton = { TextButton({ find = false }) { Text(stringResource(R.string.btn_close)) } })
    }
}

// ------------------------------------------------------------------ PDF

class PdfViewerActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val lang = newBase.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getString("lang", "auto") ?: "auto"
        super.attachBaseContext(Lang.wrap(newBase, lang))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) { Surface(Modifier.fillMaxSize()) { PdfViewer { finish() } } } }
    }
}

@Composable
fun PdfViewer(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val e = MediaSrc.entries.firstOrNull() ?: run { onClose(); return }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val state = rememberLazyListState()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { val f = MediaSrc.file(ctx, e); val d = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY); pfd = d; renderer = PdfRenderer(d) }.onFailure { err = it.message }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { runCatching { renderer?.close(); pfd?.close() } } }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("✕", fontSize = 18.sp) }
            Text(e.name, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1)
            val n = renderer?.pageCount ?: 0
            if (n > 0) Text("${state.firstVisibleItemIndex + 1}/$n", fontSize = 12.sp)
            TextButton({ zoom = (zoom - 0.25f).coerceAtLeast(0.5f) }) { Text("−") }
            TextButton({ zoom = (zoom + 0.25f).coerceAtMost(3f) }) { Text("+") }
        }
        val r = renderer
        if (r == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (err != null) Text(stringResource(R.string.doc_open_failed, err ?: ""), color = MaterialTheme.colorScheme.error) else androidx.compose.material3.CircularProgressIndicator() }
        else BoxWithWidth { wPx ->
            val lock = remember { Any() }
            LazyColumn(Modifier.fillMaxSize().background(Color(0xFF424242)).verticalScrollbar(state), state = state, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(r.pageCount) { i ->
                    var bmp by remember(i, zoom) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(i, zoom) {
                        bmp = withContext(Dispatchers.IO) {
                            synchronized(lock) {
                                runCatching { r.openPage(i).use { pg ->
                                    val w = (wPx * zoom).toInt(); val h = (w.toFloat() * pg.height / pg.width).toInt()
                                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(AColor.WHITE); pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                                } }.getOrNull()
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().then(if (zoom > 1f) Modifier.horizontalScrollCompat() else Modifier), contentAlignment = Alignment.TopCenter) {
                        bmp?.let { Image(it.asImageBitmap(), null, Modifier.then(if (zoom > 1f) Modifier else Modifier.fillMaxWidth())) } ?: Box(Modifier.fillMaxWidth().padding(60.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(color = Color.White) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxWithWidth(content: @Composable (Int) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) { content(constraints.maxWidth) }
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier = this.horizontalScroll(rememberScrollState())

// ------------------------------------------------------------------ Office (только просмотр)

class OfficeViewerActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val lang = newBase.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getString("lang", "auto") ?: "auto"
        super.attachBaseContext(Lang.wrap(newBase, lang))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) { Surface(Modifier.fillMaxSize()) { OfficeViewer { finish() } } } }
    }
}

object OfficeParse {
    private fun xml(z: ZipFile, name: String): String? = z.getEntry(name)?.let { z.getInputStream(it).use { s -> s.readBytes().toString(Charsets.UTF_8) } }
    private fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Страницы просмотра: (заголовок, текст). docx: один раздел; xlsx: лист на страницу; pptx: слайд на страницу. */
    fun read(f: File): List<Pair<String, String>> {
        ZipFile(f).use { z ->
            val ext = f.extension.lowercase()
            return when (ext) {
                "docx" -> listOf(f.name to docx(xml(z, "word/document.xml") ?: error("no document.xml")))
                "xlsx" -> xlsx(z)
                "pptx" -> pptx(z)
                else -> listOf(f.name to odf(xml(z, "content.xml") ?: error("no content.xml")))
            }
        }
    }

    private fun docx(x: String): String {
        val sb = StringBuilder()
        Regex("<w:p[ >].*?</w:p>|<w:tbl>|</w:tr>|</w:tc>", RegexOption.DOT_MATCHES_ALL).findAll(x).forEach { m ->
            val s = m.value
            when {
                s == "</w:tr>" -> sb.append('\n')
                s == "</w:tc>" -> sb.append("\t")
                s.startsWith("<w:p") -> {
                    val bullet = s.contains("<w:numPr>")
                    val t = Regex("<w:t[^>]*>(.*?)</w:t>|<w:tab/>|<w:br/>", RegexOption.DOT_MATCHES_ALL).findAll(s).joinToString("") { it.groupValues[1].ifEmpty { if (it.value.contains("tab")) "\t" else "\n" } }
                    sb.append(if (bullet) "• " else "").append(unesc(t)).append('\n')
                }
            }
        }
        return sb.toString().trim()
    }

    private fun xlsx(z: ZipFile): List<Pair<String, String>> {
        val shared = xml(z, "xl/sharedStrings.xml")?.let { x -> Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(x).map { m -> unesc(Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(m.groupValues[1]).joinToString("") { it.groupValues[1] }) }.toList() } ?: emptyList()
        val names = xml(z, "xl/workbook.xml")?.let { x -> Regex("<sheet [^>]*name=\"([^\"]*)\"").findAll(x).map { unesc(it.groupValues[1]) }.toList() } ?: emptyList()
        val out = ArrayList<Pair<String, String>>()
        var i = 1
        while (true) {
            val x = xml(z, "xl/worksheets/sheet$i.xml") ?: break
            val rows = Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(x).map { r ->
                Regex("<c ([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL).findAll(r.groupValues[1]).joinToString("\t") { c ->
                    val attrs = c.groupValues[1]; val body = c.groupValues[2]
                    val v = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                    val t = Regex("t=\"(\\w+)\"").find(attrs)?.groupValues?.get(1)
                    when { t == "s" -> shared.getOrNull(v?.toIntOrNull() ?: -1).orEmpty(); t == "inlineStr" -> unesc(Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1).orEmpty()); else -> unesc(v.orEmpty()) }
                }
            }.joinToString("\n")
            out.add((names.getOrNull(i - 1) ?: "Sheet$i") to rows); i++
        }
        return out
    }

    private fun pptx(z: ZipFile): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var i = 1
        while (true) {
            val x = xml(z, "ppt/slides/slide$i.xml") ?: break
            val paras = Regex("<a:p>(.*?)</a:p>", RegexOption.DOT_MATCHES_ALL).findAll(x).map { p -> unesc(Regex("<a:t>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL).findAll(p.groupValues[1]).joinToString("") { it.groupValues[1] }) }.filter { it.isNotBlank() }.joinToString("\n")
            out.add("#$i" to paras); i++
        }
        return out
    }

    private fun odf(x: String): String = unesc(x.replace(Regex("</text:p>|</text:h>|</table:table-row>"), "\n").replace(Regex("</table:table-cell>"), "\t").replace(Regex("<[^>]+>"), "")).trim()
}

@Composable
fun OfficeViewer(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val e = MediaSrc.entries.firstOrNull() ?: run { onClose(); return }
    var pages by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var cur by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { pages = OfficeParse.read(MediaSrc.file(ctx, e)) }.onFailure { err = it.message } } }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("✕", fontSize = 18.sp) }
            Text(e.name, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1)
        }
        val p = pages
        if (p == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (err != null) Text(stringResource(R.string.doc_open_failed, err ?: ""), color = MaterialTheme.colorScheme.error) else androidx.compose.material3.CircularProgressIndicator() }
        else {
            if (p.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll2Plain().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                p.forEachIndexed { i, (t, _) -> TextButton({ cur = i }) { Text(t, fontSize = 12.sp, color = if (i == cur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) } }
            }
            Text(stringResource(R.string.doc_readonly_office), Modifier.padding(horizontal = 8.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            Box(Modifier.fillMaxSize().barScroll().horizontalScroll2Plain().padding(10.dp)) {
                Text(p.getOrNull(cur)?.second.orEmpty().ifBlank { stringResource(R.string.doc_empty) }, fontSize = 14.sp, fontFamily = if (e.name.endsWith("xlsx") || e.name.endsWith("ods")) FontFamily.Monospace else null)
            }
        }
    }
}

@Composable
private fun Modifier.horizontalScroll2Plain(): Modifier = this.horizontalScroll(rememberScrollState())
