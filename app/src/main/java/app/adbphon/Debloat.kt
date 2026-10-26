package app.adbphon

enum class Risk { SAFE, CAREFUL }

enum class BloatCat { SOCIAL, BIXBY, SAMSUNG, GOOGLE, MICROSOFT, PARTNER }

data class Bloat(val cat: BloatCat, val risk: Risk)

/**
 * Известные «лишние» приложения, которые производители кладут в систему. Список только подсказывает:
 * показываются те, что реально есть на телефоне, а отключение всегда обратимо (фильтр «Отключённые» → «Включить»).
 */
object Debloat {
    private fun group(cat: BloatCat, risk: Risk, vararg pkgs: String) = pkgs.map { it to Bloat(cat, risk) }

    val known: Map<String, Bloat> = (
        group(BloatCat.SOCIAL, Risk.SAFE,
            "com.facebook.appmanager", "com.facebook.services", "com.facebook.system", "com.facebook.katana") +
        group(BloatCat.BIXBY, Risk.SAFE,
            "com.samsung.android.bixby.wakeup", "com.samsung.android.app.spage") +
        group(BloatCat.BIXBY, Risk.CAREFUL,
            "com.samsung.android.bixby.agent", "com.samsung.android.visionintelligence",
            "com.samsung.android.bixbyvision.framework") +
        group(BloatCat.SAMSUNG, Risk.SAFE,
            "com.samsung.android.game.gamehome", "com.samsung.android.game.gametools", "com.samsung.android.arzone",
            "com.samsung.android.aremoji", "com.sec.android.mimage.avatarstickers", "com.samsung.android.stickercenter",
            "com.samsung.android.app.tips", "com.samsung.android.kidsinstaller", "com.samsung.android.app.watchmanagerstub",
            "com.samsung.android.livestickers", "com.samsung.android.voc", "com.android.egg") +
        group(BloatCat.SAMSUNG, Risk.CAREFUL,
            "com.samsung.android.game.gos", "com.samsung.android.themestore", "com.sec.android.app.samsungapps",
            "com.sec.android.easyMover", "com.samsung.android.dynamiclock", "com.sec.android.daemonapp",
            "com.samsung.android.rubin.app", "com.samsung.android.app.sbrowseredge") +
        group(BloatCat.GOOGLE, Risk.SAFE,
            "com.google.android.videos", "com.google.android.music", "com.google.android.apps.magazines",
            "com.google.android.apps.docs.editors.docs", "com.google.android.apps.docs.editors.sheets",
            "com.google.android.apps.docs.editors.slides", "com.google.android.apps.tachyon",
            "com.google.android.apps.podcasts", "com.google.android.apps.youtube.music") +
        group(BloatCat.GOOGLE, Risk.CAREFUL,
            "com.google.android.youtube", "com.google.android.apps.wellbeing", "com.google.android.apps.googleassistant") +
        group(BloatCat.MICROSOFT, Risk.SAFE,
            "com.microsoft.skydrive", "com.microsoft.office.officehubrow", "com.microsoft.office.outlook",
            "com.microsoft.office.word", "com.microsoft.office.excel", "com.microsoft.office.powerpoint",
            "com.linkedin.android") +
        group(BloatCat.MICROSOFT, Risk.CAREFUL, "com.microsoft.appmanager") +
        group(BloatCat.PARTNER, Risk.SAFE,
            "com.netflix.mediaclient", "com.netflix.partner.activation", "com.amazon.mShop.android.shopping",
            "com.spotify.music", "com.zhiliaoapp.musically")
        ).toMap()

    fun catLabel(c: BloatCat): Int = when (c) {
        BloatCat.SOCIAL -> R.string.bl_social
        BloatCat.BIXBY -> R.string.bl_bixby
        BloatCat.SAMSUNG -> R.string.bl_samsung
        BloatCat.GOOGLE -> R.string.bl_google
        BloatCat.MICROSOFT -> R.string.bl_microsoft
        BloatCat.PARTNER -> R.string.bl_partner
    }

    fun riskLabel(r: Risk): Int = if (r == Risk.SAFE) R.string.bl_safe else R.string.bl_careful
}
