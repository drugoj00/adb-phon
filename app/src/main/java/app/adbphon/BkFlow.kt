package app.adbphon

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Простой путь резервных копий (10.0, bk2): «Создать копию» сразу пишет файл с датой в запомненную папку, «Восстановить…» сразу показывает
 * список копий оттуда. Здесь логика без окон: создание копии настроек, понятные ошибки, «что внутри» для списка, «Открыть папку», «Поделиться».
 */

/** Итог создания: место, имена записанных файлов, текст «Сохранено: путь, размер». [failed]: была ошибка (показывается «Выбрать другую папку»). */
class BkResult(val place: BkPlace, val names: List<String>, val text: String, val failed: Boolean)

object BkFlow {
    /** Копия настроек в [place]: проверка до записи, запись с проверкой размера, для памяти устройства повторное чтение. */
    suspend fun makeSettings(ctx: Context, place: BkPlace, pass: CharArray?, step: (String) -> Unit): BkResult = withContext(Dispatchers.IO) {
        val (data, sum) = AppBackup.create(ctx, pass)
        AppBackup.unseal(data, pass)
        val free = BkStore.freeSpace(place)
        if (free >= 0 && free < data.size + 256 * 1024L) throw IOException(ctx.getString(R.string.bk2_err_space, BkStore.size(ctx, data.size.toLong()), BkStore.size(ctx, free)))
        if (!place.local) step(ctx.getString(R.string.bkp_step_upload, BkStore.display(ctx, place)))
        val o = BkStore.open(ctx, place)
        try {
            val taken = runCatching { BkStore.list(o).map { it.name }.toSet() }.getOrDefault(emptySet())
            val n = BkNames.unique(BkNames.settings(BkNames.stamp(System.currentTimeMillis()))) { it in taken }
            val tmp = File(BkStore.tmpDir(ctx), n)
            val size = try { tmp.writeBytes(data); BkStore.write(o, tmp, n) } finally { tmp.delete() }
            BkStore.localFile(o, n)?.let { AppBackup.unseal(it.readBytes(), pass) }
            val enc = if (pass != null && pass.isNotEmpty()) "\n" + ctx.getString(R.string.bkp_enc_settings_yes) else ""
            BkResult(place, listOf(n), ctx.getString(R.string.bkp_saved, BkStore.display(ctx, place, n), BkStore.size(ctx, size)) + "\n" +
                ctx.getString(R.string.bkp_sum, sum.settings, sum.links, sum.clouds, sum.devices) + enc, false)
        } finally { o.close() }
    }

    /** Понятный текст ошибки: нет места, нет доступа (текст уже готов в [BkStore]), сеть недоступна. */
    fun errText(ctx: Context, place: BkPlace, e: Throwable): String {
        val m = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        val low = m.lowercase()
        return when {
            low.contains("enospc") || low.contains("no space") -> ctx.getString(R.string.bk2_err_space_short, BkStore.display(ctx, place))
            place.local -> ctx.getString(R.string.bk2_failed, m)
            else -> ctx.getString(R.string.bk2_err_net, BkStore.display(ctx, place), m)
        }
    }

    /** Ошибка из-за доступа ко всем файлам: в окне ошибки есть кнопка «Дать доступ». */
    fun needsAccess(place: BkPlace) = place.local && BkStore.needAllFiles()

    /** «Что внутри» для строки списка: копия настроек читается целиком (она маленькая), у копии с данными только начало со сведениями. */
    suspend fun inside(ctx: Context, o: BkStore.Open, e: FsEntry): BkInside = withContext(Dispatchers.IO) {
        val base = BkInside.fromName(e.name)
        runCatching {
            if (!base.data) {
                if (e.size > 8L * 1024 * 1024) return@runCatching base
                val b = BkStore.head(o, e.name, e.size.toInt() + 1)
                if (AppBackup.isEncrypted(b)) base.copy(encrypted = true)
                else AppBackup.summarize(AppBackup.parse(b, null)).let { s -> base.copy(encrypted = false, settings = s.settings, links = s.links, clouds = s.clouds) }
            } else {
                val b = BkStore.head(o, e.name, 256 * 1024)
                if (AbFormat.isEncrypted(b.inputStream())) base.copy(encrypted = true)
                else BkPack.readMeta(b.inputStream(), null).let { m -> base.copy(encrypted = false, label = m.label.ifEmpty { m.pkg }, user = m.user) }
            }
        }.getOrDefault(base)
    }

    fun insideFmt(ctx: Context) = BkInside.Fmt(
        counts = { s, l, c -> ctx.getString(R.string.bk2_in_counts, s, l, c) },
        app = { label, user -> ctx.getString(R.string.bk2_in_app, label, user) },
        encrypted = ctx.getString(R.string.bk2_in_enc),
        auto = ctx.getString(R.string.bk2_in_auto),
    )

    /** «Поделиться»: файл из памяти отдаётся сразу, из сети и облака сначала скачивается во временную папку. */
    suspend fun share(ctx: Context, place: BkPlace, names: List<String>, step: (String) -> Unit) {
        val files = withContext(Dispatchers.IO) {
            val o = BkStore.open(ctx, place)
            try {
                names.map { n ->
                    BkStore.localFile(o, n) ?: File(File(BkStore.tmpDir(ctx), "share").apply { mkdirs() }, n).also { f ->
                        step(ctx.getString(R.string.bkp_step_download, BkStore.display(ctx, place, n)))
                        BkStore.fetch(o, n, f)
                    }
                }
            } finally { o.close() }
        }
        val uris = ArrayList(files.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) })
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        i.setType("application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Старые скачанные для «Поделиться» файлы убираются при следующем создании копии. */
    fun cleanShare(ctx: Context) { runCatching { File(BkStore.tmpDir(ctx), "share").deleteRecursively() } }
}

/** «Открыть папку»: вкладка «Файлы» ADB-phon в папке копий (память, SD-карта, USB, сеть, облако), файл копии в фокусе. */
object BkNav {
    /** Переход на вкладку «Файлы» с закрытием настроек (ставит MainActivity). */
    var goFiles: (() -> Unit)? = null

    fun openFolder(ctx: Context, p: BkPlace, focus: String?) {
        val go = goFiles ?: return
        go()
        Fm.scope.launch {
            var n = 0
            while (!Fm.inited && n < 50) { delay(100); n++ }   // «Файлы» открываются впервые: подождать их запуска
            if (!Fm.inited) return@launch
            val pane = Fm.cur
            fmLeaveRoot(pane)
            if (p.local) {
                if (pane.src != "local") Fm.setSource(pane, "local")
                Fm.load(pane, p.path, pick = focus)
            } else BkStore.conn(ctx, p)?.let { c -> Fm.connectNet(pane, c, p.path) }
        }
    }
}
