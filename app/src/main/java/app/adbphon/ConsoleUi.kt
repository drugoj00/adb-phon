package app.adbphon

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/** «Мои команды»: сохранённые команды с именем (prefs «adbphon», ключ my_cmds, входят в резервную копию настроек). */
object MyCmds {
    class Item(val name: String, val cmd: String)
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE)
    fun load(ctx: Context): List<Item> = runCatching {
        val a = JSONArray(sp(ctx).getString("my_cmds", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Item(o.optString("n"), o.optString("c")) }.filter { it.name.isNotBlank() && it.cmd.isNotBlank() }
    }.getOrDefault(emptyList())
    fun save(ctx: Context, list: List<Item>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("n", it.name).put("c", it.cmd)) }
        sp(ctx).edit().putString("my_cmds", a.toString()).apply()
    }
}


/** Поле и кнопка: в одну строку, на узком экране (меньше 400 dp) поле на всю ширину, кнопка под ним. */
@Composable
private fun FieldBtnRow(field: @Composable (Modifier) -> Unit, btn: @Composable () -> Unit) {
    if (LocalConfiguration.current.screenWidthDp < 400) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) { field(Modifier.fillMaxWidth()); btn() }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { field(Modifier.weight(1f)); btn() }
    }
}

/**
 * «Ещё → Консоль» (7.0, в точности по образцу консоли Pulsar): окно терминала с живым сеансом (root, ADB или помощник),
 * строка ввода с кнопкой «Выполнить», история (↑ ↓ и список), клавиши Ctrl+C, Ctrl+D, Ctrl+Z, Tab, Esc, быстрые символы,
 * копирование всего вывода и последнего вывода, вставка, очистка, перезапуск сеанса, выделение слова долгим нажатием,
 * сохранение удачной команды («Мои команды»: запуск одним нажатием). Размер шрифта и цвета окна — в «Настройках».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConsolePage() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE) }
    val clip = LocalClipboardManager.current
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    var input by remember { mutableStateOf("") }
    var hist by remember { mutableStateOf((prefs.getString("console_hist", "") ?: "").split('\u0001').filter { it.isNotBlank() }) }
    var histPos by remember { mutableStateOf(-1) }
    val fontSp = Term.fontSp(ctx)
    var saveName by remember { mutableStateOf("") }
    var savedNote by remember { mutableStateOf("") }
    var mine by remember { mutableStateOf(MyCmds.load(ctx)) }
    val text by Term.text
    val running by Term.running
    val mode by Term.mode
    val scroll = rememberScrollState()
    val fgC = Color(Term.fg(ctx)); val bgC = Color(Term.bg(ctx))
    var selA by remember { mutableStateOf(-1) }
    var selB by remember { mutableStateOf(-1) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var copiedNote by remember { mutableStateOf("") }
    LaunchedEffect(text.length) { if (selA >= text.length) { selA = -1; selB = -1 } }
    LaunchedEffect(Unit) { Term.ensure(ctx) }
    LaunchedEffect(text) { kotlinx.coroutines.delay(30); scroll.scrollTo(scroll.maxValue) }
    fun send() {
        val c = input.trim()
        if (c.isNotEmpty()) {
            hist = (listOf(c) + hist.filter { it != c }).take(30)
            prefs.edit().putString("console_hist", hist.joinToString("\u0001")).apply()
        }
        Term.sendLine(input)
        input = ""; histPos = -1
    }
    fun histMove(d: Int) {
        if (hist.isEmpty()) return
        histPos = (histPos + d).coerceIn(-1, hist.size - 1)
        input = if (histPos < 0) "" else hist[histPos]
    }
    fun addTxt(s: String) { input += s }
    val toSave = input.ifBlank { hist.firstOrNull().orEmpty() }
    TabColumn("tab.console", Modifier.fillMaxSize(), 12.dp, Arrangement.spacedBy(8.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.sub_console), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(when {
                    !running && Term.wanted().isEmpty() -> R.string.tc_no_access
                    !running -> R.string.tc_stopped
                    mode == "root" -> R.string.tc_via_root
                    mode == "helper" -> R.string.tc_via_helper
                    else -> R.string.tc_via_adb
                }), style = MaterialTheme.typography.labelMedium)
                // окно вывода: фон и текст по настройкам, контур цвета темы; выделение своё: фон выделения = цвет текста, текст в нём = цвет фона
                val shape = RoundedCornerShape(6.dp)
                Box(Modifier.fillMaxWidth().height(if (wide) 420.dp else 300.dp).background(bgC, shape)
                    .border(2.dp, MaterialTheme.colorScheme.onSurface, shape).clip(shape).verticalScroll(scroll).padding(8.dp)) {
                    val styled = remember(text, selA, selB, fgC, bgC) {
                        buildAnnotatedString {
                            append(text)
                            if (selA >= 0 && selB > selA) addStyle(SpanStyle(color = bgC, background = fgC), selA.coerceAtMost(text.length), selB.coerceAtMost(text.length))
                        }
                    }
                    Text(styled, color = fgC, fontFamily = FontFamily.Monospace, fontSize = fontSp.sp, lineHeight = (fontSp + 4).sp,
                        onTextLayout = { layout = it },
                        modifier = Modifier
                            .pointerInput(Unit) { detectTapGestures(onTap = { selA = -1; selB = -1 }) }
                            .pointerInput(Unit) {
                                var aS = 0; var aE = 0
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { pos -> layout?.let { l -> val o = l.getOffsetForPosition(pos); val w = l.getWordBoundary(o); aS = w.start; aE = w.end; selA = aS; selB = aE } },
                                    onDrag = { change, _ -> change.consume(); layout?.let { l -> val o = l.getOffsetForPosition(change.position); selA = minOf(aS, o); selB = maxOf(aE, o) } },
                                    onDragEnd = {}, onDragCancel = {}
                                )
                            })
                }
                if (selA >= 0 && selB > selA) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { val part = text.substring(selA.coerceAtMost(text.length), selB.coerceAtMost(text.length)); clip.setText(AnnotatedString(part)); copiedNote = ctx.getString(R.string.tc_copied_pv, part.take(40)); selA = -1; selB = -1 }) { Text(stringResource(R.string.tc_copy_sel)) }
                    OutlinedButton(onClick = { selA = -1; selB = -1 }) { Text(stringResource(R.string.tc_unsel)) }
                } else Text(stringResource(R.string.tc_select_hint), style = MaterialTheme.typography.bodySmall)
                if (copiedNote.isNotEmpty()) Text(copiedNote, style = MaterialTheme.typography.labelMedium)
                val kbOpts = KeyboardOptions(imeAction = ImeAction.Send, autoCorrect = false)
                val kbActs = KeyboardActions(onSend = { send() })
                FieldBtnRow(field = { fm ->
                    OutlinedTextField(value = input, onValueChange = { input = it; histPos = -1 }, label = { Text(stringResource(R.string.tc_input)) }, singleLine = true,
                        modifier = fm, keyboardOptions = kbOpts, keyboardActions = kbActs)
                }) { Button(enabled = running, onClick = { send() }) { Text("⏎ " + stringResource(R.string.tc_run)) } }
                // управляющие клавиши
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(enabled = running, onClick = { Term.interrupt(ctx) }) { Text("Ctrl+C") }
                    OutlinedButton(enabled = running, onClick = { Term.sendKey(4) }) { Text("Ctrl+D") }
                    OutlinedButton(enabled = running, onClick = { Term.sendKey(26) }) { Text("Ctrl+Z") }
                    OutlinedButton(enabled = running, onClick = { Term.sendKey(9) }) { Text("Tab") }
                    OutlinedButton(enabled = running, onClick = { Term.sendKey(27) }) { Text("Esc") }
                    OutlinedButton(onClick = { histMove(1) }) { Text("↑") }
                    OutlinedButton(onClick = { histMove(-1) }) { Text("↓") }
                }
                // символы, которых не хватает на экранной клавиатуре
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (s in listOf("/", "|", "~", "-", ">", "<", "&", "$", "\"", "'", "*", ";", "\\", "#")) {
                        OutlinedButton(onClick = { addTxt(s) }) { Text(s) }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { clip.setText(AnnotatedString(text)); copiedNote = ctx.getString(R.string.tc_copied_pv, text.trim().take(40)) }) { Text(stringResource(R.string.tc_copy)) }
                    OutlinedButton(onClick = { val o = Term.lastOutput(); if (o.isNotEmpty()) { clip.setText(AnnotatedString(o)); copiedNote = ctx.getString(R.string.tc_copied_pv, o.take(40)) } else copiedNote = ctx.getString(R.string.tc_nothing) }) { Text(stringResource(R.string.tc_copy_last)) }
                    OutlinedButton(onClick = { clip.getText()?.text?.let { addTxt(it.lineSequence().firstOrNull().orEmpty()) } }) { Text(stringResource(R.string.tc_paste)) }
                    OutlinedButton(onClick = { Term.clear() }) { Text(stringResource(R.string.tc_clear)) }
                    OutlinedButton(onClick = { Term.stop(quiet = true); Term.ensure(ctx) }) { Text(stringResource(R.string.tc_restart)) }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.tc_save_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.tc_save_hint), style = MaterialTheme.typography.bodySmall)
                if (toSave.isNotBlank()) Text(toSave, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                FieldBtnRow(field = { fm ->
                    OutlinedTextField(value = saveName, onValueChange = { saveName = it }, label = { Text(stringResource(R.string.tc_my_name)) }, singleLine = true, modifier = fm)
                }) {
                    OutlinedButton(enabled = saveName.isNotBlank() && toSave.isNotBlank(), onClick = {
                        mine = mine.filter { it.name != saveName.trim() } + MyCmds.Item(saveName.trim(), toSave.trim()); MyCmds.save(ctx, mine)
                        savedNote = ctx.getString(R.string.tc_saved, saveName.trim()); saveName = ""
                    }) { Text(stringResource(R.string.tc_save)) }
                }
                if (savedNote.isNotEmpty()) Text(savedNote, style = MaterialTheme.typography.bodySmall)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.tc_my_title), style = MaterialTheme.typography.titleSmall)
                if (mine.isEmpty()) Text(stringResource(R.string.tc_my_empty), style = MaterialTheme.typography.bodySmall)
                for (m in mine) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.bodyMedium)
                        Text(m.cmd, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), maxLines = 2)
                    }
                    Button(enabled = running, onClick = { Term.sendLine(m.cmd) }) { Text("▶") }
                    OutlinedButton(onClick = { input = m.cmd }) { Text("✎") }
                    OutlinedButton(onClick = { mine = mine.filter { it.name != m.name }; MyCmds.save(ctx, mine) }) { Text("✕") }
                }
            }
        }
        if (hist.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.tc_hist), style = MaterialTheme.typography.titleSmall)
                for (h in hist.take(12)) Text(h, Modifier.fillMaxWidth().clickable { input = h }.padding(vertical = 6.dp), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), maxLines = 2)
            }
        }
    }
}

/** «Окно терминала» в «Настройках» (как у Pulsar): размер шрифта, цвет окна и цвет текста (готовые цвета и свой HEX), предпросмотр и сброс. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TermSettings() {
    val ctx = LocalContext.current
    var bg by remember { mutableStateOf(Term.bg(ctx)) }
    var fg by remember { mutableStateOf(Term.fg(ctx)) }
    var fs by remember { mutableStateOf(Term.fontSp(ctx).toFloat()) }
    var hexBg by remember { mutableStateOf("") }
    var hexFg by remember { mutableStateOf("") }
    val bgs = listOf(0xFF1B3F72, 0xFF0B2545, 0xFF10151A, 0xFF000000, 0xFF14532D, 0xFF3B0764, 0xFF475569, 0xFFF8FAFC)
    val fgs = listOf(0xFFFFFFFF, 0xFFEAF2FF, 0xFFFDE68A, 0xFF86EFAC, 0xFF67E8F9, 0xFFFDBA74, 0xFFCBD5E1, 0xFF0F172A)
    fun parse(h: String): Int? = runCatching { android.graphics.Color.parseColor(if (h.startsWith("#")) h else "#$h") }.getOrNull()?.let { it or 0xFF000000.toInt() }
    @Composable
    fun swatches(list: List<Long>, cur: Int, set: (Int) -> Unit) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            for (c in list) {
                val ci = c.toInt()
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(ci))
                    .border(if (cur == ci) 4.dp else 1.dp, if (cur == ci) MaterialTheme.colorScheme.onSurface else Color.Gray, CircleShape)
                    .clickable { set(ci) })
            }
        }
    }
    Text(stringResource(R.string.tc_set_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.fillMaxWidth().background(Color(bg), shape).border(2.dp, MaterialTheme.colorScheme.onSurface, shape).padding(10.dp)) {
        Text("a14xm:/sdcard \$ ls -l\ntotal 0\ndrwxrwx--x 2 root sdcard_rw DCIM", color = Color(fg), fontFamily = FontFamily.Monospace, fontSize = fs.toInt().sp)
    }
    Text(stringResource(R.string.tc_set_size) + ": " + fs.toInt(), Modifier.padding(top = 8.dp))
    Slider(value = fs, onValueChange = { fs = it }, onValueChangeFinished = { Term.setFontSp(ctx, fs.toInt()) }, valueRange = 9f..24f, steps = 14)
    Text(stringResource(R.string.tc_set_bg), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    swatches(bgs, bg) { bg = it; Term.setBg(ctx, it) }
    Box(Modifier.padding(top = 6.dp)) {
        val p = parse(hexBg)
        FieldBtnRow(field = { fm ->
            OutlinedTextField(value = hexBg, onValueChange = { s -> hexBg = s.filter { it.isLetterOrDigit() || it == '#' }.take(7) }, label = { Text(stringResource(R.string.tc_hex)) }, singleLine = true, modifier = fm)
        }) { Button(enabled = p != null && hexBg.length >= 6, onClick = { bg = p!!; Term.setBg(ctx, p) }) { Text(stringResource(R.string.tc_apply)) } }
    }
    Text(stringResource(R.string.tc_set_fg), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    swatches(fgs, fg) { fg = it; Term.setFg(ctx, it) }
    Box(Modifier.padding(top = 6.dp)) {
        val p = parse(hexFg)
        FieldBtnRow(field = { fm ->
            OutlinedTextField(value = hexFg, onValueChange = { s -> hexFg = s.filter { it.isLetterOrDigit() || it == '#' }.take(7) }, label = { Text(stringResource(R.string.tc_hex)) }, singleLine = true, modifier = fm)
        }) { Button(enabled = p != null && hexFg.length >= 6, onClick = { fg = p!!; Term.setFg(ctx, p) }) { Text(stringResource(R.string.tc_apply)) } }
    }
    OutlinedButton(onClick = { bg = Term.BG_DEFAULT; fg = Term.FG_DEFAULT; fs = 13f; Term.setBg(ctx, bg); Term.setFg(ctx, fg); Term.setFontSp(ctx, 13) }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.tc_set_reset)) }
}
