package app.adbphon

import android.content.Context
import android.app.Activity
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Стенд проверки сетевых источников без интерфейса (по образцу других *TestActivity):
 * `am start -n app.adbphon/.NetTestActivity --es kind smb --es host 192.168.1.100:4445 --es user u --es pass p --es path smb://192.168.1.100:4445/TEST/ --es plan "list;sleep 15;list;copy f.bin" [--ez legacy true]`.
 * Шаги плана: list, sleep N, copy ИМЯ, ping, mark ТЕКСТ. Время каждого шага пишется в logcat (тег NetTest). legacy: прежние таймауты SMB без обёртки постоянной связи.
 */
class NetTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        FmIo.app = applicationContext; Fm.sctx = applicationContext
        val kind = i.getStringExtra("kind") ?: "smb"
        val host = i.getStringExtra("host") ?: ""
        val c = NetConn(kind, host, i.getIntExtra("port", 0), i.getStringExtra("user") ?: "", i.getStringExtra("pass") ?: "", path = i.getStringExtra("cpath") ?: "", smb = i.getStringExtra("smbver") ?: "")
        if (i.getBooleanExtra("fmreset", false) || i.getBooleanExtra("fmrestore", false)) { Log.i("NetTest", FmTestState.run(applicationContext, i.getBooleanExtra("fmreset", false))); finish(); return }
        i.getStringExtra("rootshow")?.let { v -> getSharedPreferences("fm", Context.MODE_PRIVATE).edit().putStringSet("root_show", v.split(',').filter { it.isNotBlank() }.toSet()).commit(); Log.i("NetTest", "ROOTSHOW $v"); finish(); return }
        i.getStringExtra("cloudbig")?.let { v -> CloudCatalog.bigRows = v == "1"; Log.i("NetTest", "cloudbig=" + v); finish(); return }
        if (i.getBooleanExtra("tofront", false)) { Thread { Thread.sleep(i.getIntExtra("delay", 4) * 1000L); android.os.Handler(android.os.Looper.getMainLooper()).post { CloudReturn.toFront(applicationContext, i.getBooleanExtra("skipdirect", false)) } }.start(); finish(); return }
        i.getStringExtra("fakeadd")?.let { v -> CloudTestHook.fakeAdd = if (v == "0") null else NetStore.list(applicationContext).firstOrNull { it.kind == "cloud" }; Log.i("NetTest", "fakeadd=" + v + " " + (CloudTestHook.fakeAdd != null)); finish(); return }
        i.getStringExtra("fakequota")?.let { v -> CloudErr.fakeQuota = v == "1"; Log.i("NetTest", "fakequota=" + v); finish(); return }
        if (i.getBooleanExtra("save", false)) { NetStore.save(this, c); Log.i("NetTest", "SAVED ${c.title}"); finish(); return }
        if (i.getBooleanExtra("forget", false)) { NetStore.remove(this, c); Log.i("NetTest", "FORGOT ${c.title}"); finish(); return }
        val legacy = i.getBooleanExtra("legacy", false)
        val path = i.getStringExtra("path") ?: "/"
        val plan = (i.getStringExtra("plan") ?: "list").split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val smbmax = i.getStringExtra("smbmax")
        val fs: RemoteFs = if (kind == "smb" && (legacy || smbmax != null)) { if (legacy) SmbFs(host, c.user, c.pass, legacy = true, maxVer = smbmax) else KeepFs { SmbFs(host, c.user, c.pass, maxVer = smbmax) } } else c.fs()
        val keep = fs as? KeepFs
        // источник считается открытым в панели: keepalive и Wi-Fi lock работают как в приложении
        if (keep != null) { Fm.panes[0].src = "smb"; Fm.panes[0].smb = keep; Fm.panes[0].net = c }
        Log.i("NetTest", "START kind=$kind host=$host legacy=$legacy keep=${keep != null} plan=$plan")
        CoroutineScope(Dispatchers.IO).launch {
            var n = 0
            for (step in plan) {
                n++
                val t0 = System.currentTimeMillis()
                val parts = step.split(' ', limit = 2)
                val r = runCatching {
                    when (parts[0]) {
                        "list" -> "n=" + fs.list(path).size
                        // «rcerr»: только строки reason/message/domain/code/status (без токенов), для разбора ошибок облака
                        "rcerr" -> { val r = Rclone.run(applicationContext, parts[1].split(' ') + listOf("--log-level", "ERROR")); "code=${r.code} outlen=${r.out.length} " + (r.err + System.lineSeparator() + r.out).lines().filter { l -> Regex("reason|message|domain|status|ERROR", RegexOption.IGNORE_CASE).containsMatchIn(l) && !l.contains("token", true) }.joinToString(" | ") { it.trim().take(220) }.take(1500) }
                        "rcgrep" -> { val a = parts[1].split('|'); val r = Rclone.run(applicationContext, listOf("help", "backend", a[0])); a[0] + ": " + r.out.lines().filter { l -> a.drop(1).any { l.contains(it, true) } }.joinToString(" ; ") { it.trim().take(90) }.take(900) }
                        "cdel" -> { val e = (fs as CloudFs).list(path).firstOrNull { it.name == parts[1] }; fs.deletePath(path.trimEnd('/') + "/" + parts[1], e?.isDir ?: false, false, e?.size ?: -1L) ?: "deleted(trash)" }
                        "cdelp" -> (fs as CloudFs).deletePath(path.trimEnd('/') + "/" + parts[1], false, true) ?: "deleted(permanent)"
                        "tlist" -> CloudTrash.list(applicationContext, host).joinToString(" ; ") { it.origPath + " " + it.size }.ifEmpty { "(trash empty)" }
                        "trest" -> CloudTrash.restore(applicationContext, host, parts[1]) ?: "restored"
                        // корзина программы (остальные облака): ctlist, ctrest ИМЯ|ПУТЬ, ctperm ИМЯ|ПУТЬ, ctclean, ctdir (удаление папки), ctmode
                        "ctlist" -> CloudTrash.listOwn(applicationContext, host).let { l -> "total=${l.total} n=${l.items.size} :: " + l.items.joinToString(" ; ") { "${it.id}|${it.origPath}|dir=${it.isDir}|size=${it.size}|stored=${it.stored}" } }
                        "ctrest" -> CloudTrash.listOwn(applicationContext, host).items.firstOrNull { it.origPath.trim('/') == parts[1].trim('/') }?.let { CloudTrash.restoreOwn(applicationContext, host, it) ?: "restored" } ?: "not in trash"
                        "ctperm" -> CloudTrash.listOwn(applicationContext, host).items.firstOrNull { it.origPath.trim('/') == parts[1].trim('/') }?.let { CloudTrash.purgeItem(applicationContext, host, it) ?: "purged" } ?: "not in trash"
                        "ctclean" -> CloudTrash.cleanOwn(applicationContext, host) ?: "cleaned"
                        "cddelp" -> (fs as CloudFs).deletePath(path.trimEnd('/') + "/" + parts[1], true, true) ?: "deleted(dir permanent)"
                        "cddel" -> (fs as CloudFs).deletePath(path.trimEnd('/') + "/" + parts[1], true, false) ?: "deleted(dir)"
                        "ctmode" -> "mode=" + CloudTrash.mode(CloudIcons.typeOfRemote(applicationContext, host))
                        "rcdel" -> { Rclone.deleteRemote(applicationContext, parts[1]); "removed ${parts[1]}" }
                        // rtc ИМЯ|КБ|conc: файл в облако, в корзину программы, восстановление (conc=1: параллельно идут списки как в дереве), проверка корня, чистка
                        "rtc" -> {
                            val a = parts[1].split('|'); val kb = a[1].toInt(); val conc = a.getOrNull(2) == "1"; val old = a.getOrNull(3) == "old"
                            val ctx = applicationContext; val cf = fs as CloudFs
                            val name = "adbphon_trash_test_" + a[0] + "_" + (System.currentTimeMillis() % 1000000) + ".txt"
                            val d = File(cacheDir, "rtc.bin"); d.writeBytes(ByteArray(kb * 1024) { (it % 251).toByte() }); cf.upload(d, "/" + name, false); d.delete()
                            val e1 = cf.deletePath("/" + name, false, false, kb * 1024L)
                            val ti = CloudTrash.listOwn(ctx, host).items.firstOrNull { it.origPath == name }
                            if (ti == null) "ALARM нет в корзине после удаления del=$e1 name=$name"
                            else {
                                suspend fun doRestore(): String? {
                                    if (!old) return CloudTrash.restoreOwn(ctx, host, ti)
                                    // прежний порядок: перенос и сразу purge подпапки корзины
                                    val m = Rclone.run(ctx, listOf("moveto", host + ":.adbphon-trash/" + ti.id + "/" + ti.stored, "$host:$name") + Rclone.common, 600_000)
                                    Rclone.run(ctx, listOf("purge", host + ":.adbphon-trash/" + ti.id, "--mega-hard-delete") + Rclone.common, 600_000)
                                    return if (m.ok) null else "moveto fail"
                                }
                                val e2 = if (conc) kotlinx.coroutines.coroutineScope {
                                    val j1 = async { runCatching { cf.list("/") } }; val j2 = async { runCatching { CloudTrash.listOwn(ctx, host) } }
                                    val r = doRestore(); j1.await(); j2.await(); r
                                } else doRestore()
                                val st = Rclone.run(ctx, listOf("lsjson", "$host:$name", "--stat", "--no-mimetype", "--no-modtime") + Rclone.common)
                                val still = CloudTrash.listOwn(ctx, host).items.any { it.origPath == name }
                                if (st.ok) cf.deletePath("/" + name, false, true)
                                (if (st.ok && e2 == null && !still) "OK" else "ПРОБЛЕМА") + " name=$name kb=$kb conc=$conc del=$e1 restore=$e2 statCode=${st.code} stillInTrash=$still"
                            }
                        }
                        "kpr" -> { val c = applicationContext; when (parts.getOrNull(1)) { "resume" -> { PadKeeper.setPaused(c, false); PadKeeper.start(c) }; "pause" -> PadKeeper.setPaused(c, true) }; "paused=${PadKeeper.paused(c)} adb=${Adb.isConnected} host=${BtHid.hostName}" }
                        // fmgo ПАНЕЛЬ|путь: открыть локальную папку в панели (стенд проверки выбора)
                        "fmgo" -> { val a = parts[1].split('|'); val pp = Fm.panes[a[0].toInt()]; withContext(Dispatchers.Main) { if (pp.src != "local") Fm.setSource(pp, "local"); fmLeaveRoot(pp); Fm.load(pp, a[1], remember = false) }; "ok" }
                        // стенд виртуального контроллера: adbself (ADB к себе по 5555), vpad (запуск помощника), vpadbtn БИТ (нажать и отпустить), vpadstick (стик вправо), vpadstop (убрать помощника), vpadinfo
                        "adbself" -> Adb.connectWireless(applicationContext, 5555).let { "adb=${Adb.isConnected} ok=${it.isSuccess} err=${it.exceptionOrNull()?.message}" }
                        "vpad" -> { val ok = VirtualPad.start(applicationContext); "start=$ok mode=${VirtualPad.mode} alive=${VirtualPad.helperAlive()}" }
                        "vpadinfo" -> Adb.shell("dumpsys input | grep -i -B1 -A6 'Xbox 360' | head -24; ls -l /data/local/tmp | grep -i vpad; cat /data/local/tmp/vpad.ver", 15_000).replace('\n', '|')
                        "vpadbtn" -> { val bit = parts[1].toInt(); VirtualPad.send(ByteArray(9).also { it[0] = 128.toByte(); it[1] = 128.toByte(); it[2] = 128.toByte(); it[3] = 128.toByte(); it[6] = 8 }, 1 shl bit); delay(800); VirtualPad.send(ByteArray(9).also { it[0] = 128.toByte(); it[1] = 128.toByte(); it[2] = 128.toByte(); it[3] = 128.toByte(); it[6] = 8 }, 0); "sent bit=$bit" }
                        "vpadstick" -> { VirtualPad.send(ByteArray(9).also { it[0] = 255.toByte(); it[1] = 128.toByte(); it[2] = 128.toByte(); it[3] = 128.toByte(); it[6] = 8 }, 0); delay(1500); VirtualPad.send(ByteArray(9).also { it[0] = 128.toByte(); it[1] = 128.toByte(); it[2] = 128.toByte(); it[3] = 128.toByte(); it[6] = 8 }, 0); "stick sent" }
                        "vpadstop" -> { VirtualPad.stop(true); delay(1500); Adb.shell("ls /data/local/tmp | grep -i vpad; dumpsys input | grep -ci 'Xbox 360'", 10_000).replace('\n', '|') }
                        "overlay" -> { if (parts.getOrNull(1) == "stop") OverlayPadService.stop(applicationContext) else OverlayPadService.start(applicationContext); delay(6000); "overlay running=${OverlayPadService.running} mode=${VirtualPad.mode}" }
                        // fmopen ПАНЕЛЬ|id|id…: показать «домик» панели и раскрыть узлы дерева (L|путь, N|вид|хост|логин|путь, T|хост)
                        "ftdel" -> { FmTrash.failed.clear(); val r = FmIo.delete(FmPane(9, "/"), parts[1]); "r=" + r + " failed=" + FmTrash.failed.joinToString { it.first + ":" + it.second } }
                        "ftlist" -> FmTrash.list().joinToString(" ; ") { "${it.id}|${it.name}|${it.origPath}|dir=${it.isDir}|size=${it.size}|${it.vol}|age=${(System.currentTimeMillis() - it.deletedAt) / 1000}s" }.ifEmpty { "(empty)" }
                        "ftrest" -> FmTrash.list().firstOrNull { it.name == parts[1] }?.let { FmTrash.restore(it) ?: "restored" } ?: "not in trash"
                        "ftperm" -> FmTrash.list().firstOrNull { it.name == parts[1] }?.let { FmTrash.purge(it) ?: "purged" } ?: "not in trash"
                        "ftclean" -> FmTrash.clean() ?: "cleaned"
                        "ftage" -> { val a = parts[1].split('|'); val it0 = FmTrash.list().first { it.name == a[0] }; val mf = File(it0.dir, "meta.json"); val m = org.json.JSONObject(mf.readText()); m.put("deletedAt", System.currentTimeMillis() - a[1].toLong() * 86_400_000L); mf.writeText(m.toString()); "aged" }
                        "ftauto" -> "purged=" + FmTrash.autoClean()
                        "ftset" -> { val a = parts[1].split('|'); val cx = applicationContext; when (a[0]) { "on" -> FmDel.setTrashOn(cx, a[1] == "1"); "days" -> FmDel.setKeepDays(cx, a[1].toInt()); "max" -> FmDel.setMaxMb(cx, a[1].toInt()); "conf" -> FmDel.setConfirm(cx, a[1].toInt()) }; "on=${FmDel.trashOn} days=${FmDel.keepDays} max=${FmDel.maxMb} conf=${FmDel.confirm}" }
                        "srv" -> { val a = parts[1].split('|'); val dir = File(a[2]).apply { mkdirs() }; CoroutineScope(Dispatchers.IO).launch { runCatching { Rclone.run(applicationContext, listOf("serve", a[0], dir.absolutePath, "--addr", "127.0.0.1:" + a[1], "--user", "t", "--pass", "t"), 3_600_000L) { l -> Log.i("NetTest", "SRV " + l.take(200)) } } }; delay(2500); "serving ${a[0]} on ${a[1]}" }
                        "ntls" -> { NetTrash.ensure(applicationContext, c); val root = NetTrash.rootPath(c, fs); "root=$root :: " + fs.list(if (parts.size > 1) NetTrash.abs(c, fs, parts[1]) else root).joinToString(" ; ") { (if (it.isDir) "[d]" else "") + it.name } }
                        "ntdel" -> { val ap = NetTrash.abs(c, fs, parts[1]); val e = fs.list(ap.substringBeforeLast('/').ifEmpty { "/" }).firstOrNull { it.name == parts[1].substringAfterLast('/') }; NetTrash.delete(applicationContext, c, fs, ap, e?.isDir ?: false, false, e?.size ?: -1L) ?: "moved to trash" }
                        "ntperm" -> { val ap = NetTrash.abs(c, fs, parts[1]); val e = fs.list(ap.substringBeforeLast('/').ifEmpty { "/" }).firstOrNull { it.name == parts[1].substringAfterLast('/') }; NetTrash.delete(applicationContext, c, fs, ap, e?.isDir ?: false, true, e?.size ?: -1L) ?: "deleted forever" }
                        "nttrash" -> { val rid = NetTrash.ensure(applicationContext, c); val l = CloudTrash.listOwn(applicationContext, rid); "rid=$rid total=${l.total} :: " + l.items.joinToString(" ; ") { "${it.id}|${it.name}|${it.origPath}|dir=${it.isDir}|size=${it.size}" } }
                        "ntrest" -> { val rid = NetTrash.ensure(applicationContext, c); CloudTrash.listOwn(applicationContext, rid).items.firstOrNull { it.name == parts[1] }?.let { CloudTrash.restoreOwn(applicationContext, rid, it) ?: "restored" } ?: "not in trash" }
                        "ntclean" -> { val rid = NetTrash.ensure(applicationContext, c); CloudTrash.cleanOwn(applicationContext, rid) ?: "cleaned" }
                        "tpurge" -> CloudTrash.list(applicationContext, host).firstOrNull { it.name == parts[1] }?.let { CloudTrash.purgeAny(applicationContext, host, it) ?: "purged forever" } ?: "not in trash"
                        "ftfail" -> { FmTrash.forceFail = parts[1] == "1"; "forceFail=" + FmTrash.forceFail }
                        "ftplan" -> { val pp = FmPane(9, parts[1].substringBeforeLast('/')); val pl = Fm.planDelete(pp, listOf(parts[1].substringAfterLast('/'))); "perm=${pl.perm} big=${pl.big} none=${pl.none}" }
                        "fmopen" -> { val a = parts[1].split(','); val pp = Fm.panes[a[0].toInt()]; withContext(Dispatchers.Main) { fmShowRoot(pp); a.drop(1).chunked(1).forEach { }; var i = 1; while (i < a.size) { val id = a[i]; if (id !in pp.rootOpen) pp.rootOpen.add(id); i++ } }; "open=" + pp.rootOpen.size }
                        "rclog" -> { val t0 = System.currentTimeMillis(); val r = Rclone.run(applicationContext, parts[1].split('|'), 300_000) { l -> Log.i("NetTest", "RC+" + (System.currentTimeMillis() - t0) + " " + l.take(240)) }; "code=${r.code}" }
                        "rcq" -> { val r = Rclone.run(applicationContext, parts[1].split('|')); "code=${r.code} out=" + r.out.trim().take(1800).replace('\n', '|') + " err=" + r.err.trim().take(300).replace('\n', '|') }
                        "rc" -> { val r = Rclone.run(applicationContext, parts[1].split(' ')); "code=${r.code} out=" + r.out.trim().take(300).replace("\n", "|") + " err=" + r.err.trim().take(300).replace("\n", "|") }
                        "rcput" -> { val a = parts[1].split(' '); RcloneConf.put(applicationContext, a[0], a[1], null, a.drop(2).map { it.substringBefore('=') to it.substringAfter('=') }); "ok" }
                        "up" -> { val d = File(cacheDir, "nettest_up.bin"); d.writeBytes(ByteArray(3_000_000) { (it % 251).toByte() }); (fs as CloudFs).upload(d, path.trimEnd('/') + "/" + parts[1], false); d.delete(); "uploaded" }
                        "apps" -> { val l = AppsFs().list("apps://" + AppsFs().dirName(parts[1])); "n=${l.size} tiny=" + l.filter { it.name.contains("Tiny") }.joinToString { it.name } }
                        "favs" -> NetStore.list(applicationContext).joinToString(" ; ") { it.kind + "|" + it.host + "|" + it.user + "|path=" + it.path + "|name=" + it.name + "|smb=" + it.smb }
                        "appexp" -> { val pk = parts[1]; val l = AppsFs().list("apps://" + AppsFs().dirName("installed")) + AppsFs().list("apps://" + AppsFs().dirName("system")); val e = l.firstOrNull { AppsFs.pkgOf(it.name) == pk } ?: error("нет $pk"); val d = File(cacheDir, e.name); AppsFs().copyTo(e.path, d); val z = if (e.name.endsWith(".apks")) java.util.zip.ZipFile(d).use { zf -> zf.entries().asSequence().map { it.name }.toList() } else emptyList(); val r = e.name + " bytes=" + d.length() + " zip=" + z; d.delete(); r }
                        "editsim" -> { val ctx = applicationContext; val old = NetStore.list(ctx).first { it.kind == "smb" && it.host == "192.168.1.1" }
                            val nw = old.copy(name = "Router", path = "Seagate_Wireless"); NetStore.update(ctx, old, nw)
                            val a = NetStore.list(ctx).filter { it.host.startsWith("192.168.1.1") }.joinToString(";") { it.host + "|" + it.name + "|" + it.path }
                            NetStore.update(ctx, nw, old); val b = NetStore.list(ctx).filter { it.host.startsWith("192.168.1.1") }.joinToString(";") { it.host + "|" + it.name + "|" + it.path }
                            "после правки: $a ; после возврата: $b" }
                        "ping" -> { fs.ping(path); "pong" }
                        "sleep" -> { delay((parts.getOrNull(1)?.toLongOrNull() ?: 1L) * 1000); "slept" }
                        // ---- проверка записи на сетевые источники ----
                        // mkf ИМЯ|МБ|СЕМЯ: файл в кэше с воспроизводимым содержимым; sha ИМЯ; up ИМЯ|ПУТЬ|replace(0/1); upc ИМЯ|ПУТЬ|МС (отмена через МС);
                        // dl ПУТЬ|ИМЯ (скачать и SHA-256); rls ПАПКА (сырой список, видны временные файлы); mk ПУТЬ; mv А|Б; rm ПУТЬ|isDir; st ПУТЬ
                        // модуль облаков: cmsrc АДРЕС|SHA (отладочный источник, пусто = обычный), cmstate, cmclean
                        "cmsrc" -> { val a = parts.getOrNull(1).orEmpty().split('|'); CloudModule.setDebugSource(applicationContext, a.getOrNull(0), a.getOrNull(1)); "source=" + a.getOrNull(0) }
                        "cmstate" -> "state=" + CloudModule.state(applicationContext) + " version=" + CloudModule.installedVersion(applicationContext) + " engine=" + CloudModule.engine(applicationContext)?.absolutePath + " cacheFiles=" + (CloudModule.dir(applicationContext).listFiles()?.map { it.name } ?: emptyList<String>())
                        "cmclean" -> { CloudModule.cleanup(applicationContext); "cleaned" }
                        // флаг общего ключа: skurl АДРЕС (отладочный), skreset, skcheck (результат и время), skstate
                        "skurl" -> { SharedKey.setDebugUrl(applicationContext, parts.getOrNull(1)); "url=" + parts.getOrNull(1) }
                        "skreset" -> { SharedKey.resetCache(applicationContext); "reset" }
                        "skcheck" -> "open=" + SharedKey.isOpen(applicationContext) + " cache=" + SharedKey.cachedState(applicationContext)
                        "skstate" -> SharedKey.cachedState(applicationContext)
                        // мастер «Свой ключ»: clip ТЕКСТ (положить в буфер), okget ТИП (есть ли сохранённый ключ, без значений), okclear ТИП
                        "clip" -> { OwnKeyWizard.setClip(applicationContext, parts.getOrNull(1).orEmpty()); "clip set" }
                        "okget" -> OwnKeys.get(applicationContext, parts[1]).let { if (it == null) "none" else "saved idLen=${it.first.length} secretLen=${it.second.length}" }
                        "okclear" -> { OwnKeys.clear(applicationContext, parts[1]); "cleared" }
                        // распаковка архива в папку кэша: arcx ФАЙЛ|ПАПКА|ПАРОЛЬ (пусто = без пароля); результат: число файлов и общая SHA-256 (имена и содержимое)
                        "arcx" -> { val a = parts[1].split('|'); val src = File(a[0]); val dst = File(a[1]); dst.deleteRecursively(); dst.mkdirs()
                            val af = ArchiveFs(src, src.name, ArchiveFs.PrevState("local", null, null, "", ""), File(cacheDir, "arcx_tmp").apply { mkdirs() })
                            a.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { af.password = it.toCharArray() }
                            val t1 = System.currentTimeMillis()
                            suspend fun walk(path: String, d: File) { for (e in af.list(path)) { if (e.isDir) { File(d, e.name).mkdirs(); walk(e.path, File(d, e.name)) } else { d.mkdirs(); af.extractTo(e.path, File(d, e.name), null) } } }
                            walk("/", dst)
                            val md = java.security.MessageDigest.getInstance("SHA-256"); var n = 0; var size = 0L
                            dst.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dst).path.replace('\\', '/') }.forEach { f -> n++; size += f.length(); md.update(f.relativeTo(dst).path.replace('\\', '/').toByteArray()); f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val r = i.read(b); if (r < 0) break; md.update(b, 0, r) } } }
                            af.invalidate(); dst.deleteRecursively()
                            "files=$n size=$size sha=" + md.digest().joinToString("") { "%02x".format(it) }.take(16) + " in ${System.currentTimeMillis() - t1} ms" }
                        // вход SSH по ключу: sshkey ФАЙЛ|ФРАЗА|ПОЛЬЗОВАТЕЛЬ|КОМАНДА (сервер 127.0.0.1:2299); пустой файл = по паролю из ФРАЗЫ
                        "sshkey" -> { val a = parts[1].split('|'); val kb = a[0].takeIf { it.isNotEmpty() }?.let { File(it).readBytes() }
                            Ssh.forgetHostKey(applicationContext, "127.0.0.1", 2299)
                            val r = Ssh.connect(applicationContext, "127.0.0.1", 2299, a[2], a[1], kb)
                            if (r.isFailure) "FAIL " + r.exceptionOrNull()?.message else { val o = Ssh.exec(a.getOrElse(3) { "echo ok" }); Ssh.disconnect(); "connected, out=" + o.trim().take(120) } }
                        // перенос между источниками: mkfs ПАПКА_СЕРВЕРА|ИМЯ|МБ|СЕМЯ (файл прямо в папку тестового сервера), xcopy ОТКУДА|ПУТЬ|ИМЯ|КУДА|ПУТЬ|move(0/1)
                        // источник задаётся как sftp:2222, ftp:2121, dav:8089 (тестовые серверы, логин t/t) или cloud:ИМЯ_ЗАПИСИ:ПОЛЬЗОВАТЕЛЬ; local: обычная папка телефона
                        "mkfs" -> { val a = parts[1].split('|'); val d = File(filesDir, a[0]).apply { mkdirs() }; val f = File(d, a[1]); val rnd = java.util.Random(a[3].toLong()); val buf = ByteArray(1 shl 20)
                            f.outputStream().use { o -> repeat(a[2].toInt()) { rnd.nextBytes(buf); o.write(buf) } }; "size=${f.length()}" }
                        "xcopy" -> { val a = parts[1].split('|')
                            suspend fun side(spec: String, path: String): FmPane {
                                val q = spec.split(':'); val pn = FmPane(9, path)
                                if (q[0] == "local") { pn.src = "local"; return pn }
                                val c = when (q[0]) { "dav" -> NetConn("dav", "http://127.0.0.1:" + q[1], 0, "t", "t"); "cloud" -> NetConn("cloud", q[1], 0, q.getOrElse(2) { "" }, ""); else -> NetConn(q[0], "127.0.0.1", q[1].toInt(), "t", "t") }
                                val f = c.fs(); if (f is KeepFs) f.connect() else if (f is CloudFs) f.connect()
                                pn.src = "smb"; pn.smb = f; pn.net = c; return pn
                            }
                            val sp = side(a[0], a[1]); val dp = side(a[3], a[4])
                            val t1 = System.currentTimeMillis()
                            val err = FmIo.copyMove(sp, dp, a[2], false, a[5] == "1", null, null, false)
                            val after = runCatching { dp.smb?.list(a[4])?.firstOrNull { it.name == a[2] }?.size }.getOrNull() ?: runCatching { File(a[4], a[2]).length() }.getOrNull()
                            val srcLeft = runCatching { sp.smb?.list(a[1])?.any { it.name == a[2] } }.getOrNull() ?: File(a[1], a[2]).exists()
                            "err=$err destSize=$after srcStillThere=$srcLeft in ${System.currentTimeMillis() - t1} ms" }
                        // подсказка про слетевший вход: cerr ТИП|ТЕКСТ_ОШИБКИ (временный свой ключ ставится и снимается; значение не живое)
                        "cerr" -> { val a = parts[1].split('|', limit = 2); val had = OwnKeys.get(applicationContext, a[0]); if (had == null) OwnKeys.set(applicationContext, a[0], "TEST-PLACEHOLDER-ID", "TEST-PLACEHOLDER-SECRET")
                            val e = CloudErr.fromText(applicationContext, a[1], a[0]); if (had == null) OwnKeys.clear(applicationContext, a[0])
                            "kind=${e.kind} publishButton=${CloudErr.offersPublish(applicationContext, e.message.orEmpty())} :: ${e.message}" }
                        "mkf" -> { val a = parts[1].split('|'); val f = File(cacheDir, a[0]); val rnd = java.util.Random(a[2].toLong()); val md = java.security.MessageDigest.getInstance("SHA-256"); val buf = ByteArray(1 shl 20)
                            f.outputStream().use { o -> repeat((a[1].toDouble() * 1).toInt().coerceAtLeast(1)) { rnd.nextBytes(buf); o.write(buf); md.update(buf) } }
                            "size=${f.length()} sha=" + md.digest().joinToString("") { "%02x".format(it) }.take(16) }
                        "sha" -> { val f = File(cacheDir, parts[1]); val md = java.security.MessageDigest.getInstance("SHA-256"); f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }; "size=${f.length()} sha=" + md.digest().joinToString("") { "%02x".format(it) }.take(16) }
                        "wup" -> { val a = parts[1].split('|'); val k = fs as KeepFs; val t1 = System.currentTimeMillis(); k.upload(File(cacheDir, a[0]), a[1], a.getOrNull(2) == "1", null); "uploaded in ${System.currentTimeMillis() - t1} ms, remote size=" + k.stat(a[1])?.size }
                        "upc" -> { val a = parts[1].split('|'); val k = fs as KeepFs
                            val pr = object : OpProgress { @Volatile override var cancelled = false; var n = 0L; override fun bytes(n: Long) { this.n += n }; override fun file(name: String) {}; override fun item() {} }
                            val job = CoroutineScope(Dispatchers.IO).launch { delay(a[2].toLong()); pr.cancelled = true }
                            val r = runCatching { k.upload(File(cacheDir, a[0]), a[1], true, pr) }; job.cancel()
                            "cancelled=${pr.cancelled} sent=${pr.n} result=" + (r.exceptionOrNull()?.javaClass?.simpleName ?: "ok") }
                        "dl" -> { val a = parts[1].split('|'); val f = File(cacheDir, a[1]); val t1 = System.currentTimeMillis(); fs.copyTo(a[0], f); val md = java.security.MessageDigest.getInstance("SHA-256"); f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }; "downloaded in ${System.currentTimeMillis() - t1} ms size=${f.length()} sha=" + md.digest().joinToString("") { "%02x".format(it) }.take(16) }
                        "rls" -> ((fs as KeepFs).raw).list(parts[1]).joinToString(" ; ") { (if (it.isDir) "[d]" else "") + it.name + ":" + it.size }
                        "st" -> (fs as KeepFs).stat(parts[1]).let { if (it == null) "none" else "dir=${it.isDir} size=${it.size}" }
                        "mk" -> { (fs as KeepFs).mkdirRemote(parts[1]); "made" }
                        "mv" -> { val a = parts[1].split('|'); (fs as KeepFs).renameRemote(a[0], a[1], false); "renamed" }
                        "wdel" -> { val a = parts[1].split('|'); (fs as KeepFs).removeRemote(a[0], a.getOrNull(1) == "1"); "removed" }
                        "lclean" -> { cacheDir.listFiles()?.filter { it.name.startsWith("wt_") }?.forEach { it.delete() }; if (parts.size > 1) filesDir.listFiles()?.filter { it.name.startsWith("wt_srv_") }?.forEach { it.deleteRecursively() }; "cleaned" }
                        "copy" -> { val d = File(cacheDir, "nettest.bin"); fs.copyTo(path.trimEnd('/') + "/" + parts[1], d); "bytes=${d.length()}".also { d.delete() } }
                        "mark" -> parts.getOrNull(1).orEmpty()
                        else -> "?"
                    }
                }
                val dt = System.currentTimeMillis() - t0
                Log.i("NetTest", "STEP $n '$step' ${if (r.isSuccess) "OK ${r.getOrNull()}" else "FAIL ${r.exceptionOrNull()?.javaClass?.simpleName}: ${r.exceptionOrNull()?.message?.take(120)}"} t=${dt}ms state=${keep?.state}")
            }
            Log.i("NetTest", "DONE")
            runOnUiThread { if (!isFinishing && !intent.getBooleanExtra("stay", false)) finish() }
            if (keep != null) { Fm.panes[0].src = "local"; Fm.panes[0].smb = null; Fm.panes[0].net = null }
            runCatching { fs.close() }
            runOnUiThread { finish() }
        }
    }
}


/** Проверка «первого запуска»: временно сбрасывает запомненные вкладку и папки приложения (копия сохраняется) и возвращает их обратно. Только для стенда. */
object FmTestState {
    private val ADB_KEYS = listOf("last_tab", "start_tab", "start_folder")

    fun run(ctx: Context, reset: Boolean): String {
        val fm = ctx.getSharedPreferences("fm", Context.MODE_PRIVATE)
        val ad = ctx.getSharedPreferences("adbphon", Context.MODE_PRIVATE)
        val bk = ctx.getSharedPreferences("nettest_backup", Context.MODE_PRIVATE)
        if (reset) {
            if (bk.contains("saved")) { fm.edit().clear().commit(); ad.edit().apply { ADB_KEYS.forEach { remove(it) } }.commit(); return "RESET again (backup kept)" }
            val o = org.json.JSONObject()
            fun dump(name: String, sp: android.content.SharedPreferences, only: List<String>?) {
                val a = org.json.JSONObject()
                for ((k, v) in sp.all) { if (only != null && k !in only) continue
                    a.put(k, org.json.JSONObject().put("t", when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is Set<*> -> "s"; else -> "x" }).put("v", if (v is Set<*>) org.json.JSONArray(v.toList()) else v.toString())) }
                o.put(name, a)
            }
            dump("fm", fm, null); dump("ad", ad, ADB_KEYS)
            bk.edit().putString("saved", o.toString()).commit()
            fm.edit().clear().commit(); ad.edit().apply { ADB_KEYS.forEach { remove(it) } }.commit()
            return "RESET done"
        }
        val txt = bk.getString("saved", null) ?: return "RESTORE: nothing saved"
        val o = org.json.JSONObject(txt)
        fun load(name: String, sp: android.content.SharedPreferences, clear: List<String>?) {
            val a = o.getJSONObject(name); val e = sp.edit()
            if (clear == null) e.clear() else clear.forEach { e.remove(it) }
            for (k in a.keys()) { val x = a.getJSONObject(k); val v = x.get("v")
                when (x.getString("t")) { "b" -> e.putBoolean(k, v.toString().toBoolean()); "i" -> e.putInt(k, v.toString().toInt()); "l" -> e.putLong(k, v.toString().toLong()); "f" -> e.putFloat(k, v.toString().toFloat())
                    "s" -> { val ja = x.getJSONArray("v"); e.putStringSet(k, (0 until ja.length()).map { ja.getString(it) }.toSet()) }; else -> e.putString(k, v.toString()) } }
            e.commit()
        }
        load("fm", fm, null); load("ad", ad, ADB_KEYS)
        bk.edit().clear().commit()
        return "RESTORE done"
    }
}
