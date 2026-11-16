package app.adbphon

/*
 * 10.0 (bk3): единое окно «Создать копию…» / «Восстановить…». Здесь чистая логика без Android (проверяется BkPlanTest):
 *  - что можно включить в копию и чем снимаются данные каждого приложения ([BkPlan]);
 *  - что лежит в файле копии: настройки ADB-phon, APK, данные приложения, чужая системная копия ([BkDetect]);
 *  - что разрешено восстановить при выбранных галочках «Приложение (APK)» и «Данные» ([BkPlan.restoreBlock]);
 *  - понятный вид ошибки по тексту исключения форматов (английские тексты исключений людям не показываются).
 */

/** Части копии. */
enum class BkPart { SETTINGS, APK, DATA }

/** Чем снимаются данные приложения. */
enum class BkDataWay {
    /** root: все данные (внутренние, внешние, obb). */
    ROOT,
    /** ADB без root: системная копия (bu backup), приложение её разрешает. */
    SYSTEM,
    /** Нет ни root, ни ADB. */
    NO_PRIV,
    /** Приложение запрещает системную копию (allowBackup=false), без root недоступно. */
    NOT_ALLOWED,
    /** Android 12+ и приложение для Android 12+: без root внутренние данные недоступны. */
    SDK12,
}

/** Сведения о приложении для решения «как снимать данные» (из PackageManager). */
data class BkAppFacts(val pkg: String, val targetSdk: Int, val allowBackup: Boolean, val debuggable: Boolean)

/** Выбор в окне «Создать копию…». */
data class BkWhat(
    val settings: Boolean,
    val apk: Boolean,
    val data: Boolean,
    val apps: Set<String>,
)

/** Одно приложение в копии: что снимать. [dataWay] null: данные не просили. */
data class BkJob(val pkg: String, val apk: Boolean, val data: Boolean, val dataWay: BkDataWay?)

object BkPlan {
    /** Что отмечено при открытии: из «Софта» с выбранными приложениями: APK и данные этих приложений; из настроек: только настройки. */
    fun initial(preselected: Set<String>): BkWhat =
        if (preselected.isNotEmpty()) BkWhat(settings = false, apk = true, data = true, apps = preselected)
        else BkWhat(settings = true, apk = false, data = false, apps = emptySet())

    /** Способ снять данные приложения на этом устройстве. */
    fun dataWay(root: Boolean, adb: Boolean, deviceSdk: Int, f: BkAppFacts?): BkDataWay = when {
        root -> BkDataWay.ROOT
        !adb -> BkDataWay.NO_PRIV
        f != null && !f.allowBackup -> BkDataWay.NOT_ALLOWED
        f != null && deviceSdk >= 31 && f.targetSdk >= 31 && !f.debuggable -> BkDataWay.SDK12
        else -> BkDataWay.SYSTEM
    }

    /** Данные снимаются полностью или частично (внешние данные и obb снимаются и по ADB, даже когда внутренние недоступны). */
    fun dataPossible(w: BkDataWay) = w != BkDataWay.NO_PRIV

    /** Внутренние данные (настройки и база приложения) действительно попадут в копию. */
    fun internalPossible(w: BkDataWay) = w == BkDataWay.ROOT || w == BkDataWay.SYSTEM

    enum class Problem { NOTHING, NO_APPS, NEED_PASS }

    /** Что мешает создать копию (null: можно). [enc] и [pass]: шифрование включено, введён ли пароль. */
    fun problem(w: BkWhat, enc: Boolean, pass: String): Problem? = when {
        !w.settings && !w.apk && !w.data -> Problem.NOTHING
        (w.apk || w.data) && w.apps.isEmpty() && !w.settings -> Problem.NO_APPS
        enc && pass.isEmpty() -> Problem.NEED_PASS
        else -> null
    }

    /**
     * Задания по приложениям. Приложение без APK и без доступных данных пропускается (в [skipped]).
     * APK своего устройства читается без root и ADB, поэтому APK всегда возможен.
     */
    fun jobs(w: BkWhat, ways: Map<String, BkDataWay>, skipped: MutableList<String>? = null): List<BkJob> {
        if (!w.apk && !w.data) return emptyList()
        return w.apps.sorted().mapNotNull { p ->
            val way = if (w.data) ways[p] ?: BkDataWay.NO_PRIV else null
            val data = way != null && dataPossible(way)
            if (!w.apk && !data) { skipped?.add(p); null } else BkJob(p, w.apk, data, way)
        }
    }

    /** Какие галочки поставить при восстановлении копии приложения. */
    fun restoreDefaults(hasApk: Boolean, hasData: Boolean, installed: Boolean, installedOlder: Boolean): Pair<Boolean, Boolean> {
        val apk = hasApk && (!installed || installedOlder || !hasData)
        return apk to hasData
    }

    enum class Block { NOTHING, NO_PRIV, USER_MISSING, NOT_INSTALLED, SIGNER_MISMATCH, OTHER_USER_NEEDS_PRIV }

    /**
     * Что мешает восстановить выбранное (null: можно). [codes]: итог [BkCompat.check]. [user0]: восстановление в основного пользователя.
     * APK без root и ADB ставится системным установщиком (только основной пользователь), данные без root или ADB не возвращаются.
     * Приложения нет: данные можно вернуть, только если вместе с ними ставится APK из копии.
     */
    fun restoreBlock(codes: Set<BkCompat.Code>, wantApk: Boolean, wantData: Boolean, priv: Boolean, user0: Boolean): Block? {
        if (!wantApk && !wantData) return Block.NOTHING
        if (BkCompat.Code.USER_MISSING in codes) return Block.USER_MISSING
        if (BkCompat.Code.SIGNER_MISMATCH in codes) return Block.SIGNER_MISMATCH
        if (wantData && !priv) return Block.NO_PRIV
        if (wantApk && !priv && !user0) return Block.OTHER_USER_NEEDS_PRIV
        if (wantData && !wantApk && BkCompat.Code.NOT_INSTALLED in codes) return Block.NOT_INSTALLED
        return null
    }
}

/** Что лежит в файле копии. [parts] пусто и [foreign]/[unknown] false: содержимое ещё не известно (зашифровано). */
data class BkContent(
    val parts: Set<BkPart>,
    /** null: неизвестно. */
    val encrypted: Boolean?,
    /** Файл .ab, но не копия ADB-phon (системная копия `adb backup`). */
    val foreign: Boolean = false,
    /** Не копия: формат не распознан. */
    val unknown: Boolean = false,
    val meta: BkMeta? = null,
) {
    val settings get() = BkPart.SETTINGS in parts
    val app get() = BkPart.APK in parts || BkPart.DATA in parts || (!settings && !foreign && !unknown)
}

object BkDetect {
    private val ADBPB = "ADBPB".toByteArray(Charsets.US_ASCII)
    private val AB = "ANDROID BACKUP\n".toByteArray(Charsets.US_ASCII)

    /** Файл по имени похож на копию (для списка в папке). */
    fun isCopyName(name: String): Boolean {
        val l = name.lowercase()
        return !name.startsWith(".") && (l.endsWith(BkNames.SETTINGS_EXT) || l.endsWith(BkNames.DATA_EXT))
    }

    /** Части по списку частей из сведений копии ("apk", "internal", "ext", "obb", "ab"…). */
    fun partsOf(metaParts: List<String>): Set<BkPart> {
        val s = LinkedHashSet<BkPart>()
        if ("apk" in metaParts) s += BkPart.APK
        if (metaParts.any { it != "apk" }) s += BkPart.DATA
        return s
    }

    private fun starts(b: ByteArray, p: ByteArray) = b.size >= p.size && (p.indices).all { b[it] == p[it] }

    /**
     * Содержимое по началу файла [head] (для копии настроек хватает 8 байт, для копии приложения нужны первые килобайты: сведения
     * лежат первой записью). [pass]: пароль для зашифрованной копии приложения (null: только заголовок).
     */
    fun detect(name: String, head: ByteArray, pass: CharArray? = null): BkContent {
        if (starts(head, ADBPB)) return BkContent(setOf(BkPart.SETTINGS), encrypted = head.size > 6 && head[6].toInt() == 1)
        if (starts(head, AB)) {
            val enc = runCatching { AbFormat.isEncrypted(head.inputStream()) }.getOrNull() ?: return BkContent(emptySet(), null, unknown = true)
            if (enc && (pass == null || pass.isEmpty())) return BkContent(emptySet(), true)
            return try {
                val m = BkPack.readMeta(head.inputStream(), pass)
                BkContent(partsOf(m.parts), enc, meta = m)
            } catch (e: AbFormat.WrongPass) { BkContent(emptySet(), true)
            } catch (e: AbFormat.NeedPass) { BkContent(emptySet(), true)
            } catch (e: AbFormat.BadFormat) {
                // поток кончился раньше сведений (обрезанное начало) или внутри чужая системная копия
                if (e.message == "not an ADB-phon data copy") BkContent(emptySet(), enc, foreign = true) else BkContent(emptySet(), enc, unknown = true)
            } catch (e: Exception) { BkContent(emptySet(), enc, unknown = true) }
        }
        // по имени: короткое начало или пустой файл
        return BkContent(emptySet(), null, unknown = true)
    }

    /** Вид ошибки чтения файла копии по исключению форматов: для понятного текста на языке программы. */
    enum class Err { NOT_OURS, NEWER, DAMAGED, NEED_PASS, WRONG_PASS, OTHER }

    /** Исключения копии настроек ([AppBackup]) сравниваются по имени класса: чистая логика не тянет Android. */
    fun errKind(e: Throwable): Err {
        val n = e.javaClass.simpleName
        if (e is AbFormat.NeedPass || n == "NeedPass") return Err.NEED_PASS
        if (e is AbFormat.WrongPass || n == "WrongPass") return Err.WRONG_PASS
        val bad = e is AbFormat.BadFormat || n == "BadFormat"
        val m = e.message.orEmpty().lowercase()
        return when {
            bad && (m.startsWith("not an") || m.contains("unknown encryption")) -> Err.NOT_OURS
            bad && (m.contains("newer") || m.contains("unsupported version")) -> Err.NEWER
            bad -> Err.DAMAGED
            m.contains("file shorter than expected") -> Err.DAMAGED
            else -> Err.OTHER
        }
    }
}
