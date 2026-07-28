package com.nebras.dashboard.ui.chat

import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.nebras.dashboard.core.NebrasColors
import com.nebras.dashboard.data.ChatAttachment
import com.nebras.dashboard.data.ChatRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * وسائط الدردشة — نقل `ui/chat/chat_media.dart`.
 *
 * ⚠️ **قاعدة ثابتة**: الوسائط تُنزَّل إلى ملفّ عبر
 * [ChatRepository.cachedMediaFile] **قبل** التشغيل، فتعمل بلا اتصال ولا
 * تُعاد كلّ مرّة. هذا ما أصلح علّة «الصوتيات لا تفتح».
 */

/** حالة تنزيل مرفق. */
private sealed interface MediaState {
    data object Idle : MediaState
    data object Loading : MediaState
    data class Ready(val file: File) : MediaState
    data class Failed(val message: String) : MediaState
}

/** يجلب ملفّ المرفق من الكاش (أو ينزّله) ويعيد حالته. */
@Composable
private fun rememberMediaFile(attachment: ChatAttachment, autoLoad: Boolean): MediaState {
    val context = LocalContext.current
    var state by remember(attachment.path, attachment.url) {
        mutableStateOf<MediaState>(MediaState.Idle)
    }
    LaunchedEffect(attachment.path, attachment.url, autoLoad) {
        if (!autoLoad || state != MediaState.Idle) return@LaunchedEffect
        state = MediaState.Loading
        state = runCatching { ChatRepository.cachedMediaFile(context, attachment) }
            .fold(
                onSuccess = { MediaState.Ready(it) },
                onFailure = { MediaState.Failed(it.message ?: "تعذّر التنزيل") },
            )
    }
    return state
}

/** فقاعة صورة — تُنزَّل ثمّ تُعرض من الملفّ المحلّيّ. */
@Composable
fun ChatImageBubble(attachment: ChatAttachment, modifier: Modifier = Modifier) {
    val state = rememberMediaFile(attachment, autoLoad = true)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(10.dp))
            .background(NebrasColors.surfaceAlt),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is MediaState.Ready -> AsyncImage(
                model = state.file,
                contentDescription = attachment.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            is MediaState.Failed -> Text(
                text = state.message,
                color = NebrasColors.rose,
                style = MaterialTheme.typography.bodySmall,
            )
            else -> CircularProgressIndicator(strokeWidth = 2.dp)
        }
    }
}

/**
 * فقاعة صوت/رسالة صوتيّة — تشغيل داخل الفقاعة بعد تنزيل الملفّ.
 * لكلّ فقاعة مشغّلها الخاصّ (يُحرَّر عند الخروج) كي لا تتداخل الأصوات.
 */
@OptIn(UnstableApi::class)
@Composable
fun ChatAudioBubble(attachment: ChatAttachment, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var requested by remember(attachment.path) { mutableStateOf(false) }
    val state = rememberMediaFile(attachment, autoLoad = requested)

    val player = remember { ExoPlayer.Builder(context).build() }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(attachment.durationMs ?: 0L) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // تجهيز المشغّل فور جاهزيّة الملفّ.
    LaunchedEffect(state) {
        val ready = state as? MediaState.Ready ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(ready.file.toURI().toString()))
        player.prepare()
        player.play()
    }

    // تحديث الموضع أثناء التشغيل.
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = player.currentPosition.coerceAtLeast(0)
            if (player.duration > 0) durationMs = player.duration
            delay(300)
        }
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                when {
                    state is MediaState.Ready -> {
                        if (player.isPlaying) player.pause() else player.play()
                    }
                    !requested -> requested = true
                }
            },
        ) {
            when (state) {
                is MediaState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                is MediaState.Ready -> Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "إيقاف" else "تشغيل",
                    tint = NebrasColors.emerald,
                )
                else -> Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "تشغيل",
                    tint = NebrasColors.emerald,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            LinearProgressIndicator(
                progress = {
                    if (durationMs > 0) {
                        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = NebrasColors.emerald,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${formatClock(positionMs)} / ${formatClock(durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = NebrasColors.textMuted,
            )
        }
        if (state is MediaState.Failed) {
            Text(
                text = state.message,
                color = NebrasColors.rose,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** فقاعة فيديو — نقرة تفتح مشغّلاً بملء الشاشة. */
@Composable
fun ChatVideoBubble(
    attachment: ChatAttachment,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .clickable(onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = "تشغيل الفيديو",
            tint = Color.White,
            modifier = Modifier.size(44.dp),
        )
        Text(
            text = attachment.name,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** صفحة مشغّل الفيديو الكاملة داخل الدردشة. */
@OptIn(UnstableApi::class)
@Composable
fun ChatVideoPlayerPage(attachment: ChatAttachment, onClose: () -> Unit) {
    val context = LocalContext.current
    val state = rememberMediaFile(attachment, autoLoad = true)
    val player = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(player) { onDispose { player.release() } }

    LaunchedEffect(state) {
        val ready = state as? MediaState.Ready ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(ready.file.toURI().toString()))
        player.prepare()
        player.play()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (state) {
            is MediaState.Ready -> AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> PlayerView(ctx).apply { this.player = player } },
                onRelease = { it.player = null },
            )
            is MediaState.Failed -> Text(
                text = state.message,
                modifier = Modifier.align(Alignment.Center),
                color = NebrasColors.rose,
            )
            else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        ) {
            Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
        }
    }
}

/** فقاعة ملفّ عامّ — زرّ تنزيل يفتح الملفّ بعد جلبه. */
@Composable
fun ChatFileBubble(attachment: ChatAttachment, modifier: Modifier = Modifier) {
    var requested by remember(attachment.path) { mutableStateOf(false) }
    val state = rememberMediaFile(attachment, autoLoad = requested)

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = NebrasColors.textMuted,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.bodySmall,
                color = NebrasColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = humanBytes(attachment.size),
                style = MaterialTheme.typography.labelSmall,
                color = NebrasColors.textMuted,
            )
        }
        when (state) {
            is MediaState.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            is MediaState.Ready -> Text(
                text = "جاهز",
                style = MaterialTheme.typography.labelSmall,
                color = NebrasColors.emerald,
            )
            else -> IconButton(onClick = { requested = true }) {
                Icon(Icons.Default.Download, contentDescription = "تنزيل")
            }
        }
    }
}

/**
 * مسجّل الملاحظات الصوتيّة — بديل حزمة `record` بـ [MediaRecorder].
 * يسجّل بصيغة m4a (AAC) وهي مدعومة في كلّ الأجهزة ومتوافقة مع المشغّل.
 */
class VoiceRecorder(private val context: android.content.Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isRecording: Boolean get() = recorder != null

    fun start(): Boolean = runCatching {
        val dir = File(context.cacheDir, "chat_voice").apply { if (!exists()) mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setAudioEncodingBitRate(64_000)
        rec.setAudioSamplingRate(44_100)
        rec.setOutputFile(file.absolutePath)
        rec.prepare()
        rec.start()
        recorder = rec
        outputFile = file
        true
    }.getOrDefault(false)

    /** يوقف التسجيل ويُعيد الملفّ الناتج، أو null عند الفشل/القصر الشديد. */
    fun stop(): File? {
        val rec = recorder ?: return null
        recorder = null
        runCatching {
            rec.stop()
            rec.release()
        }.onFailure {
            runCatching { rec.release() }
            outputFile?.delete()
            return null
        }
        val file = outputFile
        outputFile = null
        return file?.takeIf { it.exists() && it.length() > 0 }
    }

    fun cancel() {
        val rec = recorder ?: return
        recorder = null
        runCatching {
            rec.stop()
            rec.release()
        }.onFailure { runCatching { rec.release() } }
        outputFile?.delete()
        outputFile = null
    }
}

internal fun formatClock(millis: Long): String {
    if (millis <= 0) return "0:00"
    val total = millis / 1000
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}

internal fun humanBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = listOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unit = 0
    while (size >= 1024 && unit < units.size - 1) {
        size /= 1024
        unit++
    }
    return if (size >= 10 || unit == 0) {
        "${size.toLong()} ${units[unit]}"
    } else {
        String.format(Locale.US, "%.1f %s", size, units[unit])
    }
}
