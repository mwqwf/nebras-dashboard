package com.nebras.dashboard.ui.chat

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.nebras.dashboard.auth.AuthController
import com.nebras.dashboard.core.NebrasColors
import com.nebras.dashboard.data.ChatAttachment
import com.nebras.dashboard.data.ChatGroupMeta
import com.nebras.dashboard.data.ChatMember
import com.nebras.dashboard.data.ChatMessage
import com.nebras.dashboard.data.ChatMessageType
import com.nebras.dashboard.data.ChatReplyRef
import com.nebras.dashboard.data.ChatRepository
import com.nebras.dashboard.data.chatTypeLabel
import com.nebras.dashboard.ui.content.readFileMeta
import com.nebras.dashboard.ui.widgets.DashEmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * دردشة الإدارة — نقل `ui/chat/chat_screen.dart`.
 *
 * كلّ ميزات الأصل: الرسائل الحيّة، التفاعلات، «متصل الآن»، «يكتب…»،
 * ✓✓ القراءة، التثبيت، القفل، صلاحيات المالك 👑، الردّ و«ردّ بشكل خاص»،
 * والملاحظات الصوتيّة.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    navController: NavHostController,
    authController: AuthController,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth by authController.state.collectAsStateWithLifecycle()

    val messages by remember { ChatRepository.messagesStream() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val members by remember { ChatRepository.membersStream() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val meta by remember { ChatRepository.metaStream() }
        .collectAsStateWithLifecycle(initialValue = ChatGroupMeta.fallback)

    var draft by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<ChatReplyRef?>(null) }
    var openVideo by remember { mutableStateOf<ChatAttachment?>(null) }
    var showGroupInfo by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }

    val recorder = remember { VoiceRecorder(context) }
    val listState = rememberLazyListState()

    val me = members.firstOrNull { it.uid == ChatRepository.uid }
    val canModerate = auth.isOwner || me?.isChatModerator == true
    val locked = meta.locked && !canModerate

    // العضويّة + الحضور الدوريّ + تعليم القراءة (نظير نبضات نسخة Flutter).
    LaunchedEffect(Unit) {
        runCatching { ChatRepository.upsertSelf() }
        while (true) {
            runCatching { ChatRepository.presenceTick() }
            delay(30_000)
        }
    }
    LaunchedEffect(messages.size) {
        runCatching { ChatRepository.markRead() }
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val meta2 = readFileMeta(context, uri) ?: return@launch
            // ننسخ إلى الكاش أوّلاً — إذن الـ Uri قد يُلغى بعد لحظات.
            val cached = withContext(Dispatchers.IO) {
                runCatching {
                    val out = File(context.cacheDir, "chat_send_${meta2.filename}")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use(input::copyTo)
                    }
                    out
                }.getOrNull()
            } ?: return@launch
            runCatching {
                ChatRepository.sendAttachmentFromPath(
                    filePath = cached.absolutePath,
                    filename = meta2.filename,
                    contentType = meta2.mimeType,
                    type = typeForMime(meta2.mimeType),
                    replyTo = replyTo,
                )
            }
            replyTo = null
        }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && recorder.start()) recording = true
    }

    if (openVideo != null) {
        ChatVideoPlayerPage(attachment = openVideo!!, onClose = { openVideo = null })
        return
    }

    if (showGroupInfo) {
        GroupInfoPage(
            meta = meta,
            members = members,
            isOwner = auth.isOwner,
            canModerate = canModerate,
            onClose = { showGroupInfo = false },
        )
        return
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // ── رأس المجموعة ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NebrasColors.surface)
                .clickable { showGroupInfo = true }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Group, contentDescription = null, tint = NebrasColors.emerald)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = meta.name.ifEmpty { "دردشة الإدارة" },
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = NebrasColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = presenceLine(members),
                    style = MaterialTheme.typography.labelSmall,
                    color = NebrasColors.textMuted,
                    maxLines = 1,
                )
            }
            if (meta.locked) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "المجموعة مقفلة",
                    tint = NebrasColors.amber,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // ── الرسالة المثبَّتة ──
        meta.pinned?.let { pinned ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NebrasColors.sidebarActive)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = null,
                    tint = NebrasColors.emerald,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = pinned.preview,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = NebrasColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (canModerate) {
                    IconButton(onClick = { scope.launch { ChatRepository.setPinned(null) } }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "إلغاء التثبيت",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        // ── الرسائل ──
        Box(Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                DashEmptyState("لا توجد رسائل بعد")
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            isRead = isReadByOthers(message, members),
                            canModerate = canModerate,
                            onReply = { replyTo = replyRefOf(message) },
                            onReplyPrivately = {
                                replyTo = replyRefOf(message)
                                draft = "@${message.senderName} "
                            },
                            onReact = { emoji ->
                                scope.launch {
                                    ChatRepository.setReaction(message.id, emoji)
                                }
                            },
                            onDeleteForMe = {
                                scope.launch { ChatRepository.deleteForMe(message.id) }
                            },
                            onDeleteForEveryone = {
                                scope.launch { ChatRepository.deleteForEveryone(message) }
                            },
                            onPin = {
                                scope.launch { ChatRepository.setPinned(replyRefOf(message)) }
                            },
                            onOpenVideo = { openVideo = message.attachment },
                        )
                    }
                }
            }
        }

        // ── اقتباس الردّ قيد التحرير ──
        replyTo?.let { reply ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NebrasColors.surfaceAlt)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "ردّ على ${reply.senderName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NebrasColors.emerald,
                    )
                    Text(
                        text = reply.preview,
                        style = MaterialTheme.typography.labelSmall,
                        color = NebrasColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { replyTo = null }) {
                    Icon(Icons.Default.Close, contentDescription = "إلغاء الردّ")
                }
            }
        }

        // ── شريط الإدخال ──
        if (locked) {
            Text(
                text = "🔒 المجموعة مقفلة — الإرسال متاح للمشرفين فقط",
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NebrasColors.surface)
                    .padding(16.dp)
                    .navigationBarsPadding(),
                style = MaterialTheme.typography.bodySmall,
                color = NebrasColors.textMuted,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NebrasColors.surface)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.AttachFile, contentDescription = "إرفاق ملفّ")
                }

                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        scope.launch { runCatching { ChatRepository.typingTick() } }
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("اكتب رسالة…") },
                    maxLines = 4,
                    shape = RoundedCornerShape(20.dp),
                )

                if (draft.isBlank()) {
                    IconButton(
                        onClick = {
                            if (recording) {
                                val file = recorder.stop()
                                recording = false
                                if (file != null) {
                                    scope.launch {
                                        runCatching {
                                            ChatRepository.sendAttachmentFromPath(
                                                filePath = file.absolutePath,
                                                filename = file.name,
                                                contentType = "audio/mp4",
                                                type = ChatMessageType.VOICE,
                                                replyTo = replyTo,
                                            )
                                        }
                                        replyTo = null
                                    }
                                }
                            } else {
                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (recording) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (recording) "إيقاف التسجيل" else "تسجيل صوتي",
                            tint = if (recording) NebrasColors.rose else NebrasColors.textMuted,
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            val text = draft
                            draft = ""
                            val reply = replyTo
                            replyTo = null
                            scope.launch {
                                runCatching { ChatRepository.sendText(text, reply) }
                            }
                        },
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "إرسال",
                            tint = NebrasColors.emerald,
                        )
                    }
                }
            }
        }
    }
}

/** سطر الحضور: «يكتب…» له الأولويّة ثمّ عدد المتّصلين. */
private fun presenceLine(members: List<ChatMember>): String {
    val typing = members.filter { it.isTyping && it.uid != ChatRepository.uid }
    if (typing.isNotEmpty()) {
        return if (typing.size == 1) {
            "${typing.first().displayName} يكتب…"
        } else {
            "${typing.size} يكتبون…"
        }
    }
    val online = members.count { it.isOnline }
    return if (online > 0) "$online متصل الآن" else "${members.size} عضواً"
}

/** ✓✓: قرأها عضو واحد آخر على الأقلّ بعد وقت الإرسال. */
private fun isReadByOthers(message: ChatMessage, members: List<ChatMember>): Boolean =
    members.any { it.uid != message.senderId && it.lastReadAtMs >= message.sentAtMs }

private fun replyRefOf(message: ChatMessage): ChatReplyRef = ChatReplyRef(
    messageId = message.id,
    senderId = message.senderId,
    senderName = message.senderName,
    preview = message.preview,
    type = message.type,
)

private fun typeForMime(mime: String): ChatMessageType = when {
    mime.startsWith("image/") -> ChatMessageType.IMAGE
    mime.startsWith("video/") -> ChatMessageType.VIDEO
    mime.startsWith("audio/") -> ChatMessageType.AUDIO
    else -> ChatMessageType.FILE
}
