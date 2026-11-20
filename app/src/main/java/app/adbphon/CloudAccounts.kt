package app.adbphon

import java.security.MessageDigest

/**
 * 9.4: учёт аккаунтов облаков без Android: имя записи движка, замена вместо дубля, раздел конфигурации.
 * Повторное добавление того же аккаунта (та же почта) обновляет токен и подключение, а не создаёт второе.
 */
object CloudAccounts {
    /** Облака со входом через браузер, у которых логин (почта) обязан прийти: без него подключение не создаётся. */
    val loginTypes = setOf("drive", "onedrive", "dropbox", "yandex", "box")

    fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Имя записи в конфигурации движка: одно и то же для одной почты (тип + почта), без почты по началу токена. */
    fun name(type: String, email: String?, token: String): String = "acc_" + sha1(type + ":" + (email?.takeIf { it.isNotBlank() } ?: token.take(80))).take(10)

    /** Логин не получен у облака, где он обязателен: подключение «Аккаунт» без имени не создаётся. */
    fun needLogin(type: String, email: String?): Boolean = type in loginTypes && email.isNullOrBlank()

    /** Та же запись: у облака ключ — имя записи движка (название может поменяться), у остальных вид, адрес и логин. */
    fun isSame(a: NetConn, b: NetConn): Boolean = a.kind == b.kind && a.host == b.host && (a.kind == "cloud" || a.user == b.user)

    fun exists(list: List<NetConn>, c: NetConn): Boolean = list.any { isSame(it, c) }

    /** Список после сохранения: запись заменяется по ключу, а не дублируется. */
    fun upsert(list: List<NetConn>, c: NetConn): List<NetConn> = list.filterNot { isSame(it, c) } + c

    private const val NL = "\n"
    fun cutSection(text: String, name: String): String = text.replace(Regex("(?ms)^[\\[]" + Regex.escape(name) + "[\\]][^\\n]*\\n.*?(?=^[\\[]|\\z)"), "")

    /** Текст конфигурации после записи раздела [name]: прежний раздел с этим именем заменяется. */
    fun confReplace(old: String, name: String, type: String, token: String?, extra: List<Pair<String, String>>): String {
        val sec = buildString {
            append("[$name]").append(NL).append("type = $type").append(NL)
            if (token != null) append("token = ").append(token.replace(NL, "")).append(NL)
            extra.forEach { (k, v) -> append("$k = $v").append(NL) }
            append(NL)
        }
        return (cutSection(old, name).trimEnd() + NL + NL + sec).trimStart()
    }
}

/** 9.4: что сворачивается вместе с узлом дерева «Устройства» (все уровни вложенности). Без Android: проверяется юнит-тестом. */
object TreeOpen {
    private const val ROOT_MARK = "\u0001root"

    /** Корень тома телефона для пути: «/sdcard», «/storage/emulated/N», «/storage/self/primary», «/storage/<том>»; null: путь вне томов. */
    fun volumeRoot(path: String): String? {
        val t = path.trimEnd('/')
        if (t == "/sdcard" || t.startsWith("/sdcard/")) return "/sdcard"
        val m = Regex("^(/storage/emulated/\\d+|/storage/self/primary|/storage/[^/]+)(/.*)?$").find(t) ?: return null
        return m.groupValues[1].takeIf { it != "/storage/emulated" && it != "/storage/self" }
    }

    /**
     * 20.11.2026 (владелица: после перезапуска под «Карта памяти» стояла одна раскрытая «Download» без остальных папок): при восстановлении раскрытых узлов
     * у каждой раскрытой локальной папки («L|путь») раскрыты и ВСЕ её предки до корня тома, иначе цепочка рвётся и дерево строится не от корня. Порядок: предки раньше потомков;
     * повторов нет; узлы других видов (N|, D|, группы) остаются как есть.
     */
    fun withAncestors(open: Collection<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (id in open) {
            if (id.startsWith("L|")) {
                val path = id.removePrefix("L|").trimEnd('/')
                val root = volumeRoot(path)
                if (root != null && path != root) {
                    val chain = ArrayList<String>()
                    var cur = path.substringBeforeLast('/', "")
                    while (cur.length >= root.length && cur.isNotEmpty()) {
                        chain += "L|$cur"
                        if (cur == root) break
                        cur = cur.substringBeforeLast('/', "")
                    }
                    chain.reversed().forEach { out += it }
                }
            }
            out += id
        }
        return out.toList()
    }
    private fun kind(o: String) = o.take(2)
    private fun src(o: String) = when (kind(o)) { "N|" -> o.split('|').take(4).joinToString("|"); "L|", "D|" -> kind(o); else -> o }
    private fun path(o: String) = when (kind(o)) { "N|" -> o.split('|', limit = 5).getOrNull(4).orEmpty(); "L|", "D|" -> o.drop(2); else -> "" }

    /**
     * Раскрытые узлы из [open], лежащие внутри узла [id]: тот же источник и путь глубже; корень сетевого источника или «Root» (путь «/»)
     * содержит всё внутри источника (кроме собственных томов [ownRoots]); [trashOf] даёт имя корзины облака (T|…) для аккаунта, или null.
     */
    fun inside(open: List<String>, id: String, ownRoots: List<String>, trashOf: (String) -> String? = { null }): List<String> {
        val k = kind(id)
        if (k != "L|" && k != "N|" && k != "D|") return emptyList()
        val pi = path(id).trimEnd('/')
        val res = ArrayList<String>()
        for (o in open) {
            if (o == id) continue
            val ko = kind(o)
            if (ko == "T|") { if (k == "N|" && (pi.isEmpty() || pi == ROOT_MARK) && trashOf(id) == o.removePrefix("T|")) res += o; continue }
            if ((ko != "L|" && ko != "N|" && ko != "D|") || src(o) != src(id)) continue
            val po = path(o).trimEnd('/')
            val within = when {
                k == "N|" && (pi.isEmpty() || pi == ROOT_MARK) -> true
                pi.isEmpty() -> k == "L|" && ownRoots.none { po == it || po.startsWith("$it/") }
                else -> po.startsWith("$pi/")
            }
            if (within) res += o
        }
        return res
    }
}
