package app.adbphon

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Корзина сетевых источников (SMB, SFTP, FTP/FTPS, WebDAV) без движка облаков: те же команды, что корзина облаков отдаёт движку
 * (lsjson, moveto, copyto, copy, deletefile, rmdir, purge), исполняются здесь собственными клиентами источника (переименование внутри шары:
 * SMB renameTo, SFTP rename, FTP RNFR/RNTO, WebDAV MOVE). Так модуль «ADB-phon Облака» нужен только для облачных дисков.
 * Источник узнаётся по имени «net_…» в адресе команды («net_…:папка/файл»).
 */
object LanEngine {
    private val conns = ConcurrentHashMap<String, NetConn>()
    private val spec = Regex("^(net_[A-Za-z0-9_]*):(.*)$")

    fun register(c: NetConn) { conns[NetTrash.rid(c)] = c }

    /** Команда адресована сетевому источнику (есть аргумент вида «net_…:путь»). */
    fun handles(args: List<String>): Boolean = args.any { spec.matches(it) }

    private fun ok(out: String = "") = Rclone.Result(0, out, "")
    private fun fail(code: Int, msg: String) = Rclone.Result(code, "", msg)
    private fun notFound(msg: String = "directory not found") = Rclone.Result(3, "", msg)

    private class Target(val rid: String, val rel: String, val c: NetConn, val fs: KeepFs) {
        fun abs(r: String = rel): String = NetTrash.abs(c, fs, r)
        fun child(r: String, name: String) = abs(r).trimEnd('/') + "/" + name
    }

    private fun target(ctx: Context, a: String): Target {
        val m = spec.matchEntire(a) ?: error("bad spec: $a")
        val rid = m.groupValues[1]
        val c = conns[rid] ?: NetStore.list(ctx).firstOrNull { NetTrash.rid(it) == rid }?.also { conns[rid] = it } ?: error("нет источника $rid")
        val fs = FmRootCfg.fsFor(c) as? KeepFs ?: error("нет соединения $rid")
        return Target(rid, m.groupValues[2].trim('/'), c, fs)
    }

    private suspend fun statOf(t: Target, rel: String): FsEntry? = if (rel.isEmpty()) FsEntry("", t.abs(""), true, 0) else t.fs.stat(t.abs(rel))

    private suspend fun ensureParents(t: Target, rel: String) {
        val parts = rel.trim('/').split('/').dropLast(1)
        var cur = ""
        for (p in parts) { cur = if (cur.isEmpty()) p else "$cur/$p"; t.fs.ensureDir(t.abs(cur)) }
    }

    private suspend fun purgeTree(t: Target, rel: String) {
        val e = statOf(t, rel) ?: return
        if (!e.isDir) { t.fs.removeRemote(t.abs(rel), false); return }
        for (k in t.fs.list(t.abs(rel))) purgeTree(t, if (rel.isEmpty()) k.name else "$rel/${k.name}")
        if (rel.isNotEmpty()) t.fs.removeRemote(t.abs(rel), true)
    }

    private suspend fun walk(t: Target, rel: String, prefix: String, out: JSONArray, recursive: Boolean) {
        for (k in t.fs.list(t.abs(rel))) {
            val r = if (prefix.isEmpty()) k.name else "$prefix/${k.name}"
            out.put(JSONObject().put("Path", r).put("Name", k.name).put("Size", if (k.isDir) -1L else k.size).put("IsDir", k.isDir))
            if (recursive && k.isDir) walk(t, if (rel.isEmpty()) k.name else "$rel/${k.name}", r, out, true)
        }
    }

    suspend fun run(ctx: Context, args: List<String>): Rclone.Result {
    return try {
        val cmd = args.firstOrNull { !it.startsWith("-") } ?: ""
        val pos = args.dropWhile { it != cmd }.drop(1).let { l ->
            val out = ArrayList<String>(); var i = 0
            while (i < l.size) { val x = l[i]; if (x.startsWith("--")) { if (x in setOf("--include", "--contimeout", "--timeout", "--retries", "--low-level-retries")) i++ } else out += x; i++ }
            out
        }
        when (cmd) {
            "lsjson" -> {
                val t = target(ctx, pos[0])
                val e = statOf(t, t.rel) ?: return notFound()
                if ("--stat" in args) ok(JSONObject().put("Path", t.rel).put("Name", t.rel.substringAfterLast('/')).put("Size", e.size).put("IsDir", e.isDir).toString())
                else {
                    if (!e.isDir) return notFound()
                    val a = JSONArray(); walk(t, t.rel, "", a, "-R" in args); ok(a.toString())
                }
            }
            "deletefile" -> { val t = target(ctx, pos[0]); if (statOf(t, t.rel) == null) notFound("object not found") else { t.fs.removeRemote(t.abs(), false); ok() } }
            "rmdir" -> { val t = target(ctx, pos[0]); if (statOf(t, t.rel) == null) notFound() else { t.fs.removeRemote(t.abs(), true); ok() } }
            "purge" -> { val t = target(ctx, pos[0]); if (statOf(t, t.rel) == null) notFound() else { purgeTree(t, t.rel); ok() } }
            "copyto" -> {
                // локальный файл в источник (описание элемента корзины)
                val t = target(ctx, pos[1]); ensureParents(t, t.rel)
                t.fs.upload(File(pos[0]), t.abs(), true)
                ok()
            }
            "copy" -> {
                // meta.json всех подпапок корзины в локальную папку (одной командой)
                val t = target(ctx, pos[0]); val dest = File(pos[1])
                val e = statOf(t, t.rel) ?: return notFound()
                if (!e.isDir) return notFound()
                for (k in t.fs.list(t.abs(t.rel))) if (k.isDir) {
                    val meta = t.child(if (t.rel.isEmpty()) k.name else t.rel + "/" + k.name, "meta.json")
                    val f = File(dest, k.name + "/meta.json"); f.parentFile?.mkdirs()
                    runCatching { t.fs.copyTo(meta, f) }.onFailure { f.delete() }
                }
                ok()
            }
            "moveto" -> {
                val a = target(ctx, pos[0]); val b = target(ctx, pos[1])
                val e = statOf(a, a.rel) ?: return notFound("object not found")
                ensureParents(b, b.rel)
                val from = if (e.isDir) a.abs().trimEnd('/') + "/" else a.abs()
                val to = if (e.isDir) b.abs().trimEnd('/') + "/" else b.abs()
                a.fs.renameRemote(from, to, false)
                ok()
            }
            else -> fail(2, "неизвестная команда: $cmd")
        }
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { fail(1, e.message ?: e.javaClass.simpleName) }
    }
}
