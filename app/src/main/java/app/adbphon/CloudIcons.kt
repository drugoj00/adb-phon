package app.adbphon

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.ConcurrentHashMap

/**
 * Значки подключений: у облаков встроенные фирменные значки сервиса (векторные, drawable ic_cl_<ключ>); у тех, чьего логотипа нет в открытых
 * источниках, облако с первой буквой названия; у SMB, SFTP, FTP и WebDAV нейтральные значки типа. Установленные приложения не проверяются.
 * Тип облака (type, provider, endpoint) читается из настройки самого облака и запоминается в памяти.
 */
object CloudIcons {
    private class Info(val type: String, val provider: String?, val endpoint: String?, val url: String? = null)
    private val infos = ConcurrentHashMap<String, Info>()

    fun resByKey(key: String?): Int? {
        if (key == null) return null
        val app = FmIo.app
        val id = app.resources.getIdentifier("ic_cl_$key", "drawable", app.packageName)
        return id.takeIf { it != 0 }
    }

    /** Значок по типу движка (кнопки «Добавить облако»). */
    fun resOf(type: String?): Int? = type?.let { resByKey(CloudCatalog.iconKey(it)) }

    private fun info(ctx: Context, c: NetConn): Info? {
        if (c.kind != "cloud") return null
        infos[c.host]?.let { return it }
        val i = runCatching {
            val f = Rclone.conf(ctx)
            if (!f.exists()) null
            else Regex("(?ms)^\\[" + Regex.escape(c.host) + "\\][^\\n]*\\n(.*?)(?=^\\[|\\z)").find(f.readText())?.groupValues?.get(1)?.let { sec ->
                fun v(k: String) = Regex("(?m)^" + k + "\\s*=\\s*(.*?)\\s*$").find(sec)?.groupValues?.get(1)
                v("type")?.let { Info(it, v("provider"), v("endpoint"), v("url")) }
            }
        }.getOrNull()
        if (i != null) infos[c.host] = i
        return i
    }

    /** То же по имени раздела настройки (без NetConn). */
    fun typeOfRemote(ctx: Context, remote: String): String? = info(ctx, NetConn("cloud", remote, 0, "", ""))?.type

    /** Тип облака по имени раздела настройки; null: не облако или раздела нет. */
    fun typeOf(ctx: Context, c: NetConn): String? = info(ctx, c)?.type

    fun keyOf(ctx: Context, c: NetConn): String? = info(ctx, c)?.let { CloudCatalog.iconKey(it.type, it.provider, it.endpoint) }

    fun brandRes(ctx: Context, c: NetConn): Int? = resByKey(keyOf(ctx, c))

    /** Название сервиса для облака без своего фирменного значка (запасное голубое облачко): из каталога облаков; у остальных null (сервис виден по значку). */
    fun serviceCaption(ctx: Context, c: NetConn): String? {
        if (c.kind != "cloud") return null
        val i = info(ctx, c) ?: return null
        if (resByKey(CloudCatalog.iconKey(i.type, i.provider, i.endpoint)) != null) return null
        return CloudCatalog.serviceName(i.type, i.provider, i.url)
    }

    /** Нейтральный значок типа подключения. */
    fun kindIcon(kind: String): ImageVector = when (kind) {
        "sftp" -> Icons.Filled.Terminal
        "ftp", "ftps" -> Icons.Filled.SwapVert
        "dav", "cloud" -> Icons.Filled.Cloud
        else -> Icons.Filled.Dns
    }

    fun kindTint(kind: String): Color = when (kind) {
        "cloud", "dav" -> Color(0xFF4FC3F7)
        else -> Color(0xFF42A5F5)
    }
}

/**
 * Облако-заглушка для облаков без открытого логотипа: аккуратное голубое облачко без текста (название пишется под значком).
 * Цельная форма из дуг с ровной нижней кромкой, занимает почти всю площадь значка (x 0.03..0.97, y 0.12..0.90), лёгкий градиент, у всех одинаковое.
 */
object CloudStub {
    private const val TOP = 0.12f; private const val BOTTOM = 0.90f

    fun cloud(w: Float, h: Float): android.graphics.Path {
        val p = android.graphics.Path()
        p.addRoundRect(android.graphics.RectF(0.03f * w, 0.50f * h, 0.97f * w, 0.90f * h), 0.20f * w, 0.20f * h, android.graphics.Path.Direction.CW)
        for ((cx, cy, r) in listOf(Triple(0.50f, 0.42f, 0.30f), Triple(0.26f, 0.58f, 0.21f), Triple(0.74f, 0.58f, 0.21f))) {
            val c = android.graphics.Path(); c.addCircle(cx * w, cy * h, r * w, android.graphics.Path.Direction.CW)
            p.op(c, android.graphics.Path.Op.UNION)
        }
        return p
    }

    fun draw(canvas: android.graphics.Canvas, w: Float, h: Float) {
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        fill.shader = android.graphics.LinearGradient(0f, TOP * h, 0f, BOTTOM * h, 0xFF4FC3F7.toInt(), 0xFF29A9E8.toInt(), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawPath(cloud(w, h), fill)
    }
}

/** Заглушка: голубое облачко без текста (см. [CloudStub]). */
@Composable
fun CloudStubIcon(size: Dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        drawIntoCanvas { CloudStub.draw(it.nativeCanvas, this.size.width, this.size.height) }
    }
}

/** Значок облака по типу движка: фирменный, иначе облако с буквой. */
@Composable
fun CloudTypeIcon(type: String, title: String, size: Dp, modifier: Modifier = Modifier, preset: Map<String, String> = emptyMap()) {
    val res = CloudIcons.resByKey(CloudCatalog.iconKey(type, preset["provider"], preset["endpoint"]))
    if (res != null) Image(painterResource(res), null, modifier.size(size))
    else CloudStubIcon(size, modifier)
}

/** Значок подключения: фирменный у известных облаков, облако с буквой у остальных, нейтральный значок типа у SMB, SFTP, FTP, WebDAV. */
@Composable
fun NetBrandIcon(c: NetConn, size: Dp, modifier: Modifier = Modifier, tint: Color? = null) {
    val ctx = LocalContext.current
    val res = remember(c.kind, c.host) { CloudIcons.brandRes(ctx, c) }
    val stub = remember(c.kind, c.host) { c.kind == "cloud" && res == null && CloudIcons.typeOf(ctx, c) != null }
    if (res != null) Image(painterResource(res), null, modifier.size(size))
    else if (stub) CloudStubIcon(size, modifier)
    else Icon(CloudIcons.kindIcon(c.kind), null, modifier.size(size), tint = tint ?: CloudIcons.kindTint(c.kind))
}
