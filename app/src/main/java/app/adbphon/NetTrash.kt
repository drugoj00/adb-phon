package app.adbphon

import android.content.Context

/**
 * Корзина для сетевых источников (SMB, SFTP, WebDAV, FTP/FTPS). Работает так же, как корзина облаков (скрытая папка `.adbphon-trash`
 * в корне источника, подпапки `ГГГГММДД-ЧЧММСС-n` с `meta.json`, перенос серверной командой в пределах источника), потому что
 * весь перенос, список и удаление идут через тот же движок, что и у облаков: для каждого сетевого подключения в его настройках
 * заводится скрытая запись («net_…» и её «корень источника»), и дальше источник для корзины неотличим от облака.
 *
 * Корень источника: SMB: общий ресурс (шара) из записи; SFTP: домашняя папка; WebDAV: адрес записи; FTP: корневая папка входа.
 * SMB без общего ресурса в записи (виден список шар) корзины не имеет: удалять там нечего и некуда переносить.
 */
object NetTrash {
    /** Метка корня источника в идентификаторах узлов дерева («Сетевой диск»). */
    const val ROOT_MARK = "\u0001root"
    private val done = HashMap<String, String>()

    fun rid(c: NetConn): String =
        if (c.kind == "cloud") c.host else "net_" + (c.kind + "_" + c.host + "_" + c.user).replace(Regex("[^A-Za-z0-9_]"), "_").take(64)

    fun isRid(s: String) = s.startsWith("net_")
    fun forget(rid: String) { done.remove(rid) }

    fun share(c: NetConn): String? = c.path.trim().trim('/').substringBefore('/').takeIf { it.isNotEmpty() }

    /** Есть ли у этого сетевого подключения корзина. */
    fun supported(c: NetConn): Boolean = when (c.kind) {
        "smb" -> share(c) != null
        "dav" -> c.smb != WifiExplorer.TAG     // устройства «Wi-Fi проводника» без корзины
        "sftp", "ftp", "ftps" -> true
        else -> false
    }

    /** Корень источника в путях программы («smb://хост/шара/», домашняя папка SFTP, база WebDAV, «/» для FTP). */
    fun rootPath(c: NetConn, fs: RemoteFs): String = if (c.kind == "smb") "smb://" + c.host.trim().trim('/') + "/" + share(c) + "/" else fs.root

    private fun norm(p: String) = p.trimEnd('/').ifEmpty { "/" }

    /** Путь программы в путь внутри источника (без начала «/»); null: путь вне корня источника. */
    fun rel(c: NetConn, fs: RemoteFs, path: String): String? {
        val root = norm(rootPath(c, fs)); val p = norm(path)
        if (p == root) return ""
        val pre = if (root.endsWith("/")) root else "$root/"
        return if (p.startsWith(pre)) p.removePrefix(pre).trim('/') else null
    }

    fun abs(c: NetConn, fs: RemoteFs, rel: String): String {
        val root = rootPath(c, fs)
        return (if (root.endsWith("/")) root else "$root/") + rel.trim('/')
    }

    fun isRoot(c: NetConn, fs: RemoteFs, path: String): Boolean = norm(path) == norm(rootPath(c, fs))

    /** Записать (если изменилось) скрытую настройку источника в движке. Возвращает имя, под которым источник виден корзине. */
    suspend fun ensure(ctx: Context, c: NetConn): String {
        val rid = rid(c)
        // корзина сетевых источников работает без движка облаков (см. LanEngine): достаточно запомнить подключение
        if (c.kind != "cloud") { LanEngine.register(c); return rid }
        val sig = listOf(c.kind, c.host, c.port, c.user, c.pass, c.path, c.smb).joinToString("\u0000")
        if (done[rid] == sig) return rid
        val pw = if (c.pass.isEmpty()) "" else Rclone.run(ctx, listOf("obscure", c.pass)).out.trim()
        val base = rid + "_b"
        val auth = buildList { if (c.user.isNotBlank()) add("user" to c.user) else if (c.kind == "smb") add("user" to "guest"); if (pw.isNotEmpty()) add("pass" to pw) }
        when (c.kind) {
            "sftp" -> RcloneConf.put(ctx, base, "sftp", null, listOf("host" to c.host, "port" to (if (c.port > 0) c.port else 22).toString()) + auth + listOf("set_modtime" to "false", "disable_hashcheck" to "true"))
            "dav" -> RcloneConf.put(ctx, base, "webdav", null, listOf("url" to (if (c.host.contains("://")) c.host else "http://" + c.host), "vendor" to "other") + auth)
            "ftp", "ftps" -> RcloneConf.put(ctx, base, "ftp", null, listOf("host" to c.host, "port" to (if (c.port > 0) c.port else 21).toString(), "explicit_tls" to (c.kind == "ftps").toString(), "no_check_certificate" to "true", "disable_epsv" to "false") + auth)
            "smb" -> RcloneConf.put(ctx, base, "smb", null, listOf("host" to c.host.trim().trim('/')) + auth + (if (c.port > 0) listOf("port" to c.port.toString()) else emptyList()))
            else -> return rid
        }
        RcloneConf.put(ctx, rid, "alias", null, listOf("remote" to (if (c.kind == "smb") "$base:${share(c)}" else "$base:")))
        done[rid] = sig
        return rid
    }

    /** Все сохранённые сетевые подключения с корзиной: записать их настройки (дёшево, если ничего не менялось). */
    suspend fun ensureAll(ctx: Context) {
        for (c in NetStore.list(ctx)) if (c.kind != "cloud" && supported(c)) runCatching { ensure(ctx, c) }
    }

    /** Удаление на сетевом источнике: [permanent] = навсегда, иначе в корзину источника. null: готово, иначе текст ошибки. */
    suspend fun delete(ctx: Context, c: NetConn, fs: RemoteFs, path: String, isDir: Boolean, permanent: Boolean, size: Long): String? {
        if (!supported(c)) return ctx.getString(R.string.nt_none)
        val rel = rel(c, fs, path)?.takeIf { it.isNotEmpty() } ?: return ctx.getString(R.string.nt_none)
        val id = ensure(ctx, c)
        // тип и размер неизвестны (удаление из дерева «домика»): один запрос списка родительской папки
        var dir = isDir; var sz = size
        if (!isDir && size < 0) runCatching { fs.list(path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }).firstOrNull { it.name == path.trimEnd('/').substringAfterLast('/') } }.getOrNull()?.let { dir = it.isDir; sz = it.size }
        val inTrash = rel == CloudTrash.DIR || rel.startsWith(CloudTrash.DIR + "/")
        val res = if (!permanent && !inTrash) CloudTrash.moveIn(ctx, id, rel, dir, sz) else CloudTrash.purgeRel(ctx, id, rel, dir)
        if (res == null) CloudTrash.invalidate(id)
        return res
    }
}
