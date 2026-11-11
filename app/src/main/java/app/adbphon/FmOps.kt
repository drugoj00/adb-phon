package app.adbphon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Что операция сообщает о ходе работы: байты, файлы, имя текущего файла; и можно ли продолжать. */
interface OpProgress {
    val cancelled: Boolean
    fun bytes(n: Long)
    fun file(name: String)
    /** Закончен один элемент (файл или папка). */
    fun item()
}

/**
 * Копирование, перенос и удаление на локальных дисках с учётом хода работы и отмены.
 * Без Android: проверяется обычными тестами на JVM (app/src/test).
 */
object LocalOps {
    class Cancelled : Exception("cancelled")

    fun size(f: File): Long = if (f.isDirectory) f.listFiles()?.sumOf { size(it) } ?: 0L else f.length()
    fun count(f: File): Int = if (f.isDirectory) 1 + (f.listFiles()?.sumOf { count(it) } ?: 0) else 1
    fun fileCount(f: File): Int = if (f.isDirectory) f.listFiles()?.sumOf { fileCount(it) } ?: 0 else 1

    /** [child] лежит внутри [parent] или совпадает с ним. */
    fun isInside(parent: File, child: File): Boolean {
        val a = parent.canonicalPath; val b = child.canonicalPath
        return b == a || b.startsWith(a.trimEnd(File.separatorChar) + File.separator)
    }

    /** Копия дерева. Файл пишется во временный «.fmpart» и переименовывается после полной записи: при отмене или сбое половинчатого файла не остаётся. */
    fun copy(from: File, to: File, p: OpProgress) {
        if (p.cancelled) throw Cancelled()
        if (from.isDirectory) {
            if (isInside(from, to)) throw IOException("into itself")
            to.mkdirs()
            from.listFiles()?.forEach { copy(it, File(to, it.name), p) }
            to.setLastModified(from.lastModified())
            p.item()
            return
        }
        p.file(from.name)
        to.parentFile?.mkdirs()
        val part = File(to.parentFile, to.name + ".fmpart")
        try {
            from.inputStream().use { ins ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var sinceSync = 0L
                    while (true) {
                        if (p.cancelled) throw Cancelled()
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        p.bytes(n.toLong())
                        // на слабых устройствах (3-4 ГБ памяти) много «грязных» страниц кэша мешают системе: сбрасываем на диск каждые 32 МБ
                        sinceSync += n
                        if (sinceSync >= 32L * 1024 * 1024) { runCatching { out.fd.sync() }; sinceSync = 0 }
                    }
                }
            }
            if (part.length() != from.length()) throw IOException("size mismatch")
            if (to.exists()) to.delete()
            if (!part.renameTo(to)) throw IOException("rename failed")
            to.setLastModified(from.lastModified())
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
        p.item()
    }

    /** Одинаковое ли дерево по составу и размерам файлов (перед удалением источника при переносе между томами). */
    fun sameTree(a: File, b: File): Boolean {
        if (a.isDirectory != b.isDirectory) return false
        if (!a.isDirectory) return a.length() == b.length()
        val la = a.listFiles()?.sortedBy { it.name }.orEmpty(); val lb = b.listFiles()?.sortedBy { it.name }.orEmpty()
        return la.size == lb.size && la.indices.all { la[it].name == lb[it].name && sameTree(la[it], lb[it]) }
    }

    /** Перенос: в пределах одного тома мгновенное переименование, иначе копия, сверка и только потом удаление источника. */
    fun move(from: File, to: File, p: OpProgress) {
        if (from.isDirectory && isInside(from, to)) throw IOException("into itself")
        if (!to.exists()) {
            val sz = size(from); val fc = fileCount(from)
            if (from.renameTo(to)) { p.bytes(sz); repeat(maxOf(fc, 1)) { p.item() }; return }
        }
        copy(from, to, p)
        if (!sameTree(from, to)) throw IOException("verify failed")
        delete(from, null)
    }

    /** Удаление с отчётом по элементам (если [p] не null). */
    fun delete(f: File, p: OpProgress?): Boolean {
        if (p?.cancelled == true) throw Cancelled()
        var ok = true
        if (f.isDirectory) f.listFiles()?.forEach { if (!delete(it, p)) ok = false }
        p?.file(f.name)
        if (!f.delete()) ok = false
        p?.item()
        return ok
    }
}

/**
 * Одна длительная операция в «Файлах»: копирование, перенос, удаление, упаковка и т.п.
 * Для полоски: [totalUnits] (байты или элементы; -1: пока неизвестно, полоска бегущая), [doneUnits], скорость и оставшееся время.
 * Значения на экране обновляются не чаще 10 раз в секунду.
 */
class FmJob(val title: String, val total: Int, val bytesMode: Boolean = true) : OpProgress {
    var done by mutableIntStateOf(0)                 // сколько имён из списка обработано
    var current by mutableStateOf("")
    var queued by mutableStateOf(true)
    var totalUnits by mutableLongStateOf(-1L)
    var doneUnits by mutableLongStateOf(0L)
    var filesTotal by mutableIntStateOf(-1)
    var filesDone by mutableIntStateOf(0)
    var speed by mutableFloatStateOf(0f)             // единиц в секунду (сглаженная)
    @Volatile override var cancelled = false

    private val acc = AtomicLong(0)
    private val accFiles = AtomicLong(0)
    private var lastPub = 0L
    private var lastUnits = 0L
    private var lastName = ""

    val fraction: Float? get() = if (totalUnits > 0) (doneUnits.toFloat() / totalUnits).coerceIn(0f, 1f) else null
    val etaSec: Int? get() = if (totalUnits > 0 && speed > 1f) ((totalUnits - doneUnits) / speed).toInt().coerceAtLeast(0) else null

    private fun nowMs() = System.nanoTime() / 1_000_000

    override fun bytes(n: Long) { if (bytesMode) { acc.addAndGet(n); publish(false) } }
    override fun file(name: String) { lastName = name; publish(false) }
    override fun item() { accFiles.incrementAndGet(); if (!bytesMode) acc.incrementAndGet(); publish(false) }

    fun publish(force: Boolean) {
        val t = nowMs()
        if (!force && t - lastPub < 100) return
        val units = acc.get()
        if (lastPub != 0L && t > lastPub) {
            val inst = (units - lastUnits) * 1000f / (t - lastPub)
            speed = if (speed == 0f) inst else speed * 0.7f + inst * 0.3f
        }
        lastPub = t; lastUnits = units
        doneUnits = units; filesDone = accFiles.toInt()
        if (lastName.isNotEmpty()) current = lastName
    }
}

object FmOpsUi {
    fun fmtTime(sec: Int): String = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
}

/** Горизонтальная скруглённая полоска хода работы, бирюзовый градиент. [fraction] null: бегущая полоска (объём ещё считается). */
@Composable
fun FmProgressBar(fraction: Float?, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 10.dp) {
    val shape = RoundedCornerShape(height / 2)
    val prim = BarStyle.used()
    val brush = Brush.horizontalGradient(listOf(prim, prim))
    Box(modifier.fillMaxWidth().height(height).clip(shape).background(BarStyle.freeProgress()).barFrame(prim, shape)) {
        if (fraction != null) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0.02f, 1f)).clip(shape).background(brush))
        } else {
            val tr = rememberInfiniteTransition(label = "fmbar")
            val ph by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x")
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val seg = w * 0.4f; val start = (-0.4f + 1.4f * ph) * w
                drawRoundRect(Brush.horizontalGradient(listOf(prim, prim), startX = start, endX = start + seg), Offset(start, 0f),
                    androidx.compose.ui.geometry.Size(seg, size.height), androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            }
        }
    }
}

/** Нижняя панель «Файлов»: очередь операций со своей полоской у каждой; сворачивается. */
@Composable
fun FmOpsPanel(modifier: Modifier = Modifier) {
    val jobs = Fm.jobs
    if (jobs.isEmpty()) return
    val tv = Marq.tv
    val big = if (tv) 1.35f else 1f
    val landPanel = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    Surface(modifier.widthIn(max = if (landPanel) 1000.dp else if (tv) 760.dp else 560.dp).fillMaxWidth(), shape = RoundedCornerShape(14.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 08.11.2026 (владелица: «в окне копирования много лишнего»): при одной операции заголовок «Операции: N» не показывается; свернуть можно только очередь из нескольких
            if (jobs.size > 1) Row(Modifier.fillMaxWidth().clickable { Fm.opsCollapsed = !Fm.opsCollapsed }, verticalAlignment = Alignment.CenterVertically) {
                Icon(if (Fm.opsCollapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore, null, Modifier.size((20 * big).dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.fm_ops_title, jobs.size), Modifier.padding(start = 4.dp).weight(1f), fontSize = (13 * big).sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                if (Fm.opsCollapsed) jobs.firstOrNull()?.fraction?.let { Text("${(it * 100).toInt()} %", fontSize = (13 * big).sp) }
            }
            if (jobs.size > 1 && Fm.opsCollapsed) jobs.firstOrNull()?.let { FmProgressBar(it.fraction, height = (8 * big).dp) }
            else jobs.toList().forEach { j -> FmJobCard(j, big) }
        }
    }
}

@Composable
private fun FmJobCard(j: FmJob, big: Float) {
    // вся информация над полоской хода: в альбоме одной строкой (название, осталось, скорость, время, отмена), в портрете двумя
    val land = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val parts = ArrayList<String>()
    if (j.totalUnits > 0) {
        val left = (j.totalUnits - j.doneUnits).coerceAtLeast(0L)
        parts += if (j.bytesMode) stringResource(R.string.fm_op_left, fmSize(left), fmSize(j.totalUnits)) else stringResource(R.string.fm_op_left, left.toString(), j.totalUnits.toString())
    } else parts += stringResource(R.string.fm_op_calculating)
    if (j.bytesMode) parts += if (j.speed > 1f) stringResource(R.string.fm_op_speed, fmSize(j.speed.toLong())) else stringResource(R.string.fm_op_speed_wait)
    j.etaSec?.let { parts += stringResource(R.string.fm_op_eta, FmOpsUi.fmtTime(it)) }
    val info = parts.joinToString("  ·  ") { it.replace(' ', ' ') }
    val title = j.title + if (j.queued) "  ·  " + stringResource(R.string.fm_queued) else ""
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (land) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, fontSize = (13 * big).sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(info, Modifier.weight(1f, fill = true), fontSize = (12 * big).sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .85f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            TextButton({ j.cancelled = true }) { Text(stringResource(R.string.fm_cancel_job), fontSize = (13 * big).sp, maxLines = 1) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontSize = (14 * big).sp, fontWeight = FontWeight.Medium, maxLines = 1)
                TextButton({ j.cancelled = true }) { Text(stringResource(R.string.fm_cancel_job), fontSize = (13 * big).sp) }
            }
            Text(info, fontSize = (13 * big).sp, lineHeight = (17 * big).sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .85f))
        }
        FmProgressBar(if (j.queued) 0f else j.fraction, height = (12 * big).dp)
    }
}

/**
 * Служба на время работы очереди: не даёт системе остановить копирование, когда приложение свёрнуто,
 * и показывает в уведомлении Android полоску хода первой операции.
 */
class FmOpService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: kotlinx.coroutines.Job? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") Fm.jobs.forEach { it.cancelled = true }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, getString(R.string.fm_ops_channel), NotificationManager.IMPORTANCE_LOW))
        val first = build(null)
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, first)
        if (loop == null) loop = scope.launch {
            while (true) {
                val j = Fm.jobs.firstOrNull()
                if (j == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); break }
                nm.notify(ID, build(j))
                delay(500)
            }
        }
        return START_NOT_STICKY
    }

    private fun build(j: FmJob?): Notification {
        // значок: корзина у удаления, «копия файлов» у остальных операций (стрелка вниз выглядела как загрузка)
        val isDelete = j != null && (j.title == getString(R.string.fm_deleting) || j.title == getString(R.string.sel_forever_title))
        val b = Notification.Builder(this).setSmallIcon(if (isDelete) R.drawable.ic_stat_trash else R.drawable.ic_stat_files).setOngoing(true).setOnlyAlertOnce(true)
        if (android.os.Build.VERSION.SDK_INT >= 26) b.setChannelId(CH)
        val open = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        b.setContentIntent(open)
        val cancel = PendingIntent.getService(this, 1, Intent(this, FmOpService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)
        b.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), getString(R.string.fm_cancel_job), cancel).build())
        if (j == null) { b.setContentTitle(getString(R.string.fm_ops_title, 1)); b.setProgress(0, 0, true); return b.build() }
        b.setContentTitle(j.title).setContentText(listOfNotNull(j.fraction?.let { "${(it * 100).toInt()} %" }, j.current.ifEmpty { null }).joinToString("  ·  "))
        val f = j.fraction
        if (f == null) b.setProgress(0, 0, true) else b.setProgress(100, (f * 100).toInt(), false)
        return b.build()
    }

    override fun onDestroy() { loop?.cancel(); super.onDestroy() }

    companion object {
        const val CH = "fmops"; const val ID = 4107
        fun start(ctx: Context) { runCatching { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, FmOpService::class.java)) } }
    }
}
