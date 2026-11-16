package app.adbphon

import android.content.Context

/**
 * 10.0: понятные тексты (ru/uk/en, строки cld_*) для ошибок облаков и сетевых источников вместо сырых английских
 * («Failed to lsd with 2 errors…», «HTTP 402», «UnknownHostException»). Вид ошибки определяет [NetErrMap].
 */
object NetErrText {
    fun say(ctx: Context, k: NetErrMap.Kind): String = ctx.getString(when (k) {
        NetErrMap.Kind.YANDEX_WEBDAV_FREE -> R.string.cld_e_yandex_free
        NetErrMap.Kind.PAYMENT -> R.string.cld_e_payment
        NetErrMap.Kind.SPACE -> R.string.cld_e_space
        NetErrMap.Kind.RATE -> R.string.cld_e_rate
        NetErrMap.Kind.AUTH -> R.string.cld_e_auth
        NetErrMap.Kind.FORBIDDEN -> R.string.cld_e_forbidden
        NetErrMap.Kind.NOT_FOUND -> R.string.cld_e_notfound
        NetErrMap.Kind.TLS -> R.string.cld_e_tls
        NetErrMap.Kind.TIMEOUT -> R.string.cld_e_timeout
        NetErrMap.Kind.NO_NET -> R.string.cld_e_nonet
        NetErrMap.Kind.SERVER -> R.string.cld_e_server
    })

    /** Понятный текст для ошибки сетевого источника (WebDAV, FTP, SFTP, SMB по сети) или null, если ошибка не сетевая или не распознана. */
    fun forThrowable(ctx: Context, e: Throwable): String? {
        if (e is CloudApiError || !NetErrMap.isNetThrowable(e)) return null
        val raw = NetErrMap.throwableText(e)
        // связь ADB с этим же телефоном идёт через 127.0.0.1: её обрыв объясняют другие сообщения программы
        if ("127.0.0.1" in raw || "localhost" in raw) return null
        val k = NetErrMap.classify(raw) ?: return null
        AppLog.i("ADBphon", "сетевая ошибка ${k.name}: ${raw.replace('\n', ' ').take(200)}")
        return say(ctx, k)
    }

    /** Сообщение про WebDAV Яндекса на бесплатном тарифе: рядом нужна кнопка «Подключить свой ключ» (вход в браузере). */
    fun isYandexFree(ctx: Context, msg: String): Boolean = msg.isNotEmpty() && msg.contains(ctx.getString(R.string.cld_e_yandex_free))
}
