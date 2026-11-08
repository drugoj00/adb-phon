@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package app.adbphon

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Одна запись списка просмотра: имя и функция, дающая локальный файл или адрес для потока. */
class MediaEntry(val name: String, val path: String)

/**
 * Встроенный просмотр фото, воспроизведение аудио и видео из «Файлов». Локальные файлы открываются напрямую, WebDAV идёт потоком по HTTP,
 * SMB/FTP/SFTP файл сначала копируется во временную папку (потоком они не читаются). Только системные кодеки (ExoPlayer без ffmpeg).
 */
object MediaSrc {
    /** Источник текущей панели на момент открытия: нужен просмотрщикам, которые живут отдельно от списка. */
    @Volatile var fs: RemoteFs? = null
    @Volatile var local = true
    @Volatile var entries: List<MediaEntry> = emptyList()
    @Volatile var index = 0

    /** Файл на диске: свой для локального источника, иначе копия во временной папке. */
    suspend fun file(ctx: Context, e: MediaEntry): File = withContext(Dispatchers.IO) {
        if (local) File(e.path) else {
            val d = File(ctx.cacheDir, "play").apply { mkdirs() }
            File(d, e.name).also { out -> if (!out.exists() || out.length() == 0L) fs!!.copyTo(e.path, out) }
        }
    }

    /** Адрес для плеера и заголовки запроса (для WebDAV с логином). */
    suspend fun uri(ctx: Context, e: MediaEntry): Pair<Uri, Map<String, String>> {
        val dav = fs?.raw as? DavFs
        if (!local && dav != null) return Uri.parse(dav.urlFor(e.path)) to dav.authHeaders()
        return Uri.fromFile(file(ctx, e)) to emptyMap()
    }
}

/** Вызов из «Файлов»: открыть файл встроенным просмотрщиком. */
fun openBuiltin(ctx: Context, p: FmPane, item: FmItem, cat: String, full: String) {
    val list = Fm.shown(p).filter { !it.isDir && openCat(it.ext) == cat }
    MediaSrc.fs = p.smb; MediaSrc.local = p.src != "smb"
    val at = list.indexOfFirst { it.name == item.name }
    // файла нет в списке своей категории (расширение другое, строка из раскрытой папки): играет только он
    MediaSrc.entries = if (at < 0) listOf(MediaEntry(item.name, FmIo.join(p.path, item.name))) else list.map { MediaEntry(it.name, FmIo.join(p.path, it.name)) }
    MediaSrc.index = at.coerceAtLeast(0)
    when (cat) {
        "photo" -> ctx.startActivity(Intent(ctx, PhotoViewerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "video" -> ctx.startActivity(Intent(ctx, VideoPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else -> AudioService.play(ctx)
    }
}

// ------------------------------------------------------------------ фото

class PhotoViewerActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val lang = newBase.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getString("lang", "auto") ?: "auto"
        super.attachBaseContext(Lang.wrap(newBase, lang))
    }

    private var pagerKey: ((Int) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        padImmersive()
        setContent { MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) { PhotoViewer(onClose = { finish() }) { f -> pagerKey = f } } }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { pagerKey?.invoke(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { pagerKey?.invoke(1); return true }
        }
        return super.dispatchKeyEvent(e)
    }
}

private fun decodeScaled(f: File, max: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1; while (o.outWidth / s > max * 1.5f || o.outHeight / s > max * 1.5f) s *= 2
    val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
    val rot = runCatching {
        when (android.media.ExifInterface(f.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
    }.getOrDefault(0f)
    return if (rot == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(rot) }, true)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewer(onClose: () -> Unit, keyHook: ((Int) -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val list = MediaSrc.entries
    val pager = rememberPagerState(MediaSrc.index) { list.size.coerceAtLeast(1) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var slide by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var rot by remember { mutableFloatStateOf(0f) }
    keyHook { d -> scope.launch { pager.animateScrollToPage((pager.currentPage + d).coerceIn(0, list.size - 1)) } }
    LaunchedEffect(slide) { while (slide) { delay(3000); pager.animateScrollToPage((pager.currentPage + 1) % list.size) } }
    LaunchedEffect(pager.currentPage) { rot = 0f }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { it }) { page ->
            val e = list.getOrNull(page)
            var bmp by remember(page) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(page) { if (e != null) bmp = withContext(Dispatchers.IO) { runCatching { decodeScaled(MediaSrc.file(ctx, e), 2048) }.getOrNull() } }
            var scale by remember(page) { mutableFloatStateOf(1f) }
            var off by remember(page) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
            Box(Modifier.fillMaxSize().pointerInput(page) {
                detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 6f); off = if (scale > 1f) off + pan else androidx.compose.ui.geometry.Offset.Zero }
            }.pointerInput(page) { detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; off = androidx.compose.ui.geometry.Offset.Zero } else scale = 2.5f }) }, contentAlignment = Alignment.Center) {
                bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = off.x; translationY = off.y; rotationZ = if (page == pager.currentPage) rot else 0f }, contentScale = ContentScale.Fit) }
                    ?: androidx.compose.material3.CircularProgressIndicator(color = Color.White)
            }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp).background(Color.Black.copy(alpha = .35f), androidx.compose.foundation.shape.RoundedCornerShape(24.dp)), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.Filled.Close, null, tint = Color.White) }
            Text(list.getOrNull(pager.currentPage)?.name.orEmpty() + "   ${pager.currentPage + 1}/${list.size}", Modifier.weight(1f), color = Color.White, fontSize = 14.sp, maxLines = 1)
            IconButton({ rot = (rot + 90f) % 360f }) { Icon(Icons.Filled.RotateRight, null, tint = Color.White) }
            IconButton({ slide = !slide }) { Icon(Icons.Filled.Slideshow, null, tint = if (slide) MaterialTheme.colorScheme.primary else Color.White) }
            IconButton({
                val e = list.getOrNull(pager.currentPage) ?: return@IconButton
                scope.launch { info = withContext(Dispatchers.IO) { exifText(ctx, MediaSrc.file(ctx, e)) } }
            }) { Icon(Icons.Filled.Info, null, tint = Color.White) }
        }
        info?.let { t -> LAlertDialog(onDismissRequest = { info = null }, title = { Text(stringResource(R.string.ph_info)) }, text = { Text(t, fontSize = 13.sp) }, confirmButton = { TextButton({ info = null }) { Text(stringResource(R.string.btn_close)) } }) }
    }
}

private fun exifText(ctx: Context, f: File): String {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
    val ex = runCatching { android.media.ExifInterface(f.path) }.getOrNull()
    fun a(t: String) = ex?.getAttribute(t)
    val sz = f.length()
    return listOfNotNull(
        f.name, "${o.outWidth}×${o.outHeight}  ·  ${fmSize(sz)}",
        a(android.media.ExifInterface.TAG_DATETIME)?.let { "📅 $it" },
        listOfNotNull(a(android.media.ExifInterface.TAG_MAKE), a(android.media.ExifInterface.TAG_MODEL)).joinToString(" ").takeIf { it.isNotBlank() },
        a(android.media.ExifInterface.TAG_F_NUMBER)?.let { "f/$it" }, a(android.media.ExifInterface.TAG_EXPOSURE_TIME)?.let { "${it} s" }, a(android.media.ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { "ISO $it" },
        ex?.let { e -> FloatArray(2).takeIf { e.getLatLong(it) }?.let { "GPS ${"%.5f".format(it[0])}, ${"%.5f".format(it[1])}" } },
    ).joinToString("\n")
}

// ------------------------------------------------------------------ видео

class VideoPlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var key = ""
    override fun attachBaseContext(newBase: Context) {
        val lang = newBase.getSharedPreferences("adbphon", Context.MODE_PRIVATE).getString("lang", "auto") ?: "auto"
        super.attachBaseContext(Lang.wrap(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        padImmersive()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val e = MediaSrc.entries.getOrNull(MediaSrc.index) ?: run { finish(); return }
        key = "vpos_" + e.path.hashCode()
        val pv = PlayerView(this).apply { setShowNextButton(true); setShowPreviousButton(true); setShowShuffleButton(true); setRepeatToggleModes(3) }
        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
                // вертикальный жест: слева яркость, справа громкость (кнопки и пульт обрабатывает сам плеер)
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val start = down.changes.firstOrNull { it.pressed } ?: continue
                        var last = start.position.y; val left = start.position.x < size.width / 2
                        while (true) {
                            val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            val dy = ch.position.y - last
                            if (kotlin.math.abs(dy) > 14f && kotlin.math.abs(ch.position.x - start.position.x) < 80f) { adjust(left, -dy / size.height); last = ch.position.y }
                        }
                    }
                }
            }) {
                AndroidView({ pv }, Modifier.fillMaxSize())
                if (info.isNotEmpty()) androidx.compose.material3.Text(info, Modifier.align(Alignment.TopStart).padding(10.dp).background(Color(0x66000000), androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp), color = Color.White, fontSize = 13.sp, maxLines = 1)
            }
        }
        mainScope.launch {
            val all = MediaSrc.entries; val start = MediaSrc.index.coerceIn(0, (all.size - 1).coerceAtLeast(0))
            val dav = MediaSrc.fs?.raw as? DavFs
            val headers = if (!MediaSrc.local && dav != null) dav.authHeaders() else emptyMap()
            val http = DefaultHttpDataSource.Factory().apply { if (headers.isNotEmpty()) setDefaultRequestProperties(headers) }
            val p = ExoPlayer.Builder(this@VideoPlayerActivity).setMediaSourceFactory(DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this@VideoPlayerActivity, http))).build()
            player = p; pv.player = p
            suspend fun itemFor(x: MediaEntry): MediaItem {
                val (uri, _) = MediaSrc.uri(this@VideoPlayerActivity, x)
                val subs = if (MediaSrc.local) subtitlesFor(x.path) else emptyList()
                return MediaItem.Builder().setUri(uri).setMediaId(x.path).setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(x.name).build()).setSubtitleConfigurations(subs).build()
            }
            fun show() {
                val id = p.currentMediaItem?.mediaId
                val i = all.indexOfFirst { it.path == id }
                info = if (i >= 0) "${i + 1}/${all.size}  ·  " + all[i].name else ""
            }
            p.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { show() }
                override fun onPlayerError(e: androidx.media3.common.PlaybackException) { android.util.Log.e("ADBphonMedia", "video error " + e.errorCodeName + ": " + e.message, e); if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare() } }
            })
            p.repeatMode = Player.REPEAT_MODE_OFF
            if (MediaSrc.local || dav != null) {
                p.setMediaItems(all.map { itemFor(it) }, start, getSharedPreferences("adbphon", MODE_PRIVATE).getLong(key, 0L))
                p.prepare(); p.play(); show()
            } else {
                // сеть без потокового чтения: файлы копируются по одному в кэш и добавляются в очередь (порядок: с выбранного, затем остальные)
                val order = all.drop(start) + all.take(start)
                p.setMediaItem(itemFor(order[0])); p.prepare(); p.play(); show()
                launch { for (x in order.drop(1)) { runCatching { itemFor(x) }.getOrNull()?.let { p.addMediaItem(it) } } }
            }
        }
    }

    private var info by androidx.compose.runtime.mutableStateOf("")
    private var wasPlaying = false

    private val mainScope = MainScope()

    private fun subtitlesFor(path: String): List<MediaItem.SubtitleConfiguration> {
        val f = File(path); val base = f.nameWithoutExtension
        return (f.parentFile?.listFiles { x -> (x.nameWithoutExtension == base || x.name.startsWith("$base.")) && x.extension.lowercase() in setOf("srt", "vtt", "ass", "ssa") } ?: emptyArray()).map { s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(s)).setMimeType(when (s.extension.lowercase()) { "vtt" -> MimeTypes.TEXT_VTT; "ass", "ssa" -> MimeTypes.TEXT_SSA; else -> MimeTypes.APPLICATION_SUBRIP })
                .setLanguage("und").setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT).build()
        }
    }

    private fun adjust(brightness: Boolean, frac: Float) {
        if (brightness) {
            val lp = window.attributes; val cur = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
            lp.screenBrightness = (cur + frac).coerceIn(0.02f, 1f); window.attributes = lp
        } else {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            volAcc += frac * max * 1.5f
            if (kotlin.math.abs(volAcc) >= 1f) { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) + volAcc.toInt()).coerceIn(0, max), 0); volAcc = 0f }
        }
    }
    private var volAcc = 0f

    /** Сворачивание: пауза и запомненная позиция первого файла; возврат: продолжает с того же места. Закрытие («Назад») освобождает плеер в onDestroy. */
    override fun onStop() {
        player?.let { wasPlaying = it.isPlaying; it.pause() }
        super.onStop()
    }
    override fun onStart() { super.onStart(); if (wasPlaying) player?.play() }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }
}

// ------------------------------------------------------------------ аудио: служба с MediaSession и мини-плеер

object AudioState {
    var active by mutableStateOf(false)
    var title by mutableStateOf("")
    var playing by mutableStateOf(false)
    var pos by mutableStateOf(0L)
    var dur by mutableStateOf(0L)
    var index by mutableStateOf(0)
    var count by mutableStateOf(0)
    var cover by mutableStateOf<Bitmap?>(null)
}

class AudioService : Service() {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var titles: List<String> = emptyList()
    private val scope = kotlinx.coroutines.MainScope()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "play" -> launchSafe { start() }
            "toggle" -> player?.let { if (it.isPlaying) it.pause() else it.play() }
            "next" -> player?.seekToNextMediaItem()
            "prev" -> player?.seekToPreviousMediaItem()
            "seek" -> player?.seekTo(intent.getLongExtra("pos", 0L))
            "stop" -> { AudioState.active = false; stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun launchSafe(b: suspend () -> Unit) { scope.launch { runCatching { b() }.onFailure { android.util.Log.e("ADBphonMedia", "service", it) } } }

    private suspend fun start() {
        val entries = MediaSrc.entries; val idx = MediaSrc.index
        titles = entries.map { it.name.substringBeforeLast('.') }
        // WebDAV идёт потоком, остальные удалённые источники: копия выбранного файла, плейлист из одного трека
        val streaming = MediaSrc.local || MediaSrc.fs?.raw is DavFs
        val list = if (streaming) entries else listOf(entries[idx]); val start = if (streaming) idx else 0
        val items = list.map { e -> MediaItem.Builder().setUri(MediaSrc.uri(this, e).first).setMediaId(e.name).build() }
        val headers = (MediaSrc.fs?.raw as? DavFs)?.takeIf { !MediaSrc.local }?.authHeaders().orEmpty()
        val http = DefaultHttpDataSource.Factory().apply { if (headers.isNotEmpty()) setDefaultRequestProperties(headers) }
        player?.release()
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this, http))).build()
        player = p
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { AudioState.playing = isPlaying; refresh() }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) { loadCover(p.currentMediaItemIndex, list); refresh() }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) { android.util.Log.e("ADBphonMedia", "audio error " + e.errorCodeName + ": " + e.message, e); AudioState.playing = false }
            override fun onPlaybackStateChanged(state: Int) { android.util.Log.i("ADBphonMedia", "audio state " + state); if (state == Player.STATE_ENDED) { AudioState.playing = false; refresh() } }
        })
        p.setMediaItems(items, start, 0L); p.prepare(); p.play()
        AudioState.active = true; AudioState.count = items.size
        ensureSession(); loadCover(start, list); refresh()
        launchSafe { while (player != null) { player?.let { AudioState.pos = it.currentPosition; AudioState.dur = it.duration.coerceAtLeast(0L) }; delay(500) } }
    }

    private fun loadCover(i: Int, list: List<MediaEntry>) {
        AudioState.cover = null
        val e = list.getOrNull(i) ?: return
        launchSafe {
            AudioState.cover = withContext(Dispatchers.IO) {
                runCatching { val r = MediaMetadataRetriever(); try { r.setDataSource(MediaSrc.file(this@AudioService, e).path); r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } } finally { r.release() } }.getOrNull()
            }
        }
    }

    private fun ensureSession() {
        if (session != null) return
        session = MediaSession(this, "ADB-phon").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { player?.play() }
                override fun onPause() { player?.pause() }
                override fun onSkipToNext() { player?.seekToNextMediaItem() }
                override fun onSkipToPrevious() { player?.seekToPreviousMediaItem() }
                override fun onSeekTo(pos: Long) { player?.seekTo(pos) }
            })
            isActive = true
        }
    }

    private fun act(a: String) = PendingIntent.getService(this, a.hashCode(), Intent(this, AudioService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun refresh() {
        val p = player ?: return
        val idx = p.currentMediaItemIndex.coerceAtLeast(0)
        AudioState.index = idx; AudioState.title = titles.getOrNull(idx).orEmpty()
        session?.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(if (p.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, p.currentPosition, 1f).build())
        session?.setMetadata(android.media.MediaMetadata.Builder().putString(android.media.MediaMetadata.METADATA_KEY_TITLE, AudioState.title).putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, p.duration.coerceAtLeast(0L)).apply { AudioState.cover?.let { putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }.build())
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("audio", getString(R.string.au_channel), NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "audio").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(AudioState.title).setOngoing(p.isPlaying).setOnlyAlertOnce(true)
            .setLargeIcon(AudioState.cover)
            .addAction(android.R.drawable.ic_media_previous, "prev", act("prev"))
            .addAction(if (p.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, "toggle", act("toggle"))
            .addAction(android.R.drawable.ic_media_next, "next", act("next"))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "stop", act("stop"))
            .setStyle(Notification.MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0, 1, 2)).build()
        runCatching { startForeground(4420, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
    }

    override fun onDestroy() {
        runCatching { player?.stop() }
        player?.release(); player = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        session?.release(); session = null
        AudioState.active = false; AudioState.playing = false
        super.onDestroy()
    }

    companion object {
        fun play(ctx: Context) { ctx.startForegroundService(Intent(ctx, AudioService::class.java).setAction("play")) }
        fun cmd(ctx: Context, a: String, pos: Long? = null) { ctx.startService(Intent(ctx, AudioService::class.java).setAction(a).apply { if (pos != null) putExtra("pos", pos) }) }
    }
}

/** Мини-плеер внизу «Файлов»: обложка, название бегущей строкой, предыдущий/пауза/следующий, перемотка и время. */
@Composable
fun MiniPlayer(modifier: Modifier = Modifier) {
    if (!AudioState.active) return
    val ctx = LocalContext.current
    var seeking by remember { mutableStateOf<Float?>(null) }
    Surface(modifier.fillMaxWidth().padding(6.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), tonalElevation = 8.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val c = AudioState.cover
                if (c != null) Image(c.asImageBitmap(), null, Modifier.size(40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                else FileTypeIcon("a.mp3", "mp3", null, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(AudioState.title, fontSize = 14.sp, maxLines = 1, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    Text(fmt(AudioState.pos) + " / " + fmt(AudioState.dur) + "   ${AudioState.index + 1}/${AudioState.count}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), maxLines = 1)
                }
                IconButton({ AudioService.cmd(ctx, "prev") }, Modifier.size(36.dp)) { Icon(Icons.Filled.SkipPrevious, null) }
                IconButton({ AudioService.cmd(ctx, "toggle") }, Modifier.size(40.dp)) { Icon(if (AudioState.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, Modifier.size(28.dp)) }
                IconButton({ AudioService.cmd(ctx, "next") }, Modifier.size(36.dp)) { Icon(Icons.Filled.SkipNext, null) }
                IconButton({ AudioService.cmd(ctx, "stop") }, Modifier.size(32.dp)) { Icon(Icons.Filled.Close, null, Modifier.size(18.dp)) }
            }
            Slider(seeking ?: (if (AudioState.dur > 0) AudioState.pos.toFloat() / AudioState.dur else 0f), { seeking = it }, Modifier.fillMaxWidth().height(20.dp),
                onValueChangeFinished = { seeking?.let { AudioService.cmd(ctx, "seek", (it * AudioState.dur).toLong()) }; seeking = null })
        }
    }
}

private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
