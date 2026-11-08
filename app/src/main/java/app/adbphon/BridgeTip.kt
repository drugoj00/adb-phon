package app.adbphon

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Адреса, которые программа открывает сама. Страница ADB-phon Моста задаётся ОДНОЙ строкой здесь (или значением "bridge_url" в настройках программы). */
object AppLinks {
    /** Страница последнего выпуска ADB-phon Моста (для ПК). Репозиторий ещё не создан: ссылка заранее. */
    const val BRIDGE_URL = "https://github.com/drugoj00/adb-phon-bridge/releases/latest"

    fun bridge(ctx: Context): String =
        ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getString("bridge_url", null)?.takeIf { it.startsWith("http") } ?: BRIDGE_URL
}

/**
 * Подсказка в разделе «Ещё → Геймпад»: для игр на ПК с вибрацией и Xbox-контроллером нужен ADB-phon Мост. Во всю ширину, закрывается крестиком
 * (выбор запоминается).
 */
@Composable
fun BridgeTip(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.applicationContext.getSharedPreferences("adbphon", Context.MODE_PRIVATE) }
    var hidden by remember { mutableStateOf(prefs.getBoolean("bridge_tip_hidden", false)) }
    if (hidden) return
    // светлая карточка в светлой теме и тёмная в тёмной: заметна, но не сливается с кнопками программы
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val bg = if (dark) androidx.compose.ui.graphics.Color(0xFF123A36) else androidx.compose.ui.graphics.Color(0xFFDDF3EE)
    val fg = if (dark) androidx.compose.ui.graphics.Color(0xFFCFF5EE) else androidx.compose.ui.graphics.Color(0xFF0B3B36)
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = bg) {
        Box(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 40.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.SportsEsports, null, Modifier.size(28.dp), tint = fg)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.bridge_tip_title), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = fg)
                    Text(stringResource(R.string.bridge_tip_text), fontSize = 12.sp, color = fg.copy(alpha = .9f))
                    Button({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppLinks.bridge(ctx))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                        Text(stringResource(R.string.bridge_tip_open), fontSize = 13.sp)
                    }
                }
            }
            IconButton({ hidden = true; prefs.edit().putBoolean("bridge_tip_hidden", true).apply() }, Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Filled.Close, stringResource(R.string.bridge_tip_hide), tint = fg)
            }
        }
    }
}
