package com.nebras.dashboard.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nebras.dashboard.core.NebrasColors
import com.nebras.dashboard.data.ChatMessage
import com.nebras.dashboard.data.ChatMessageType
import java.time.format.DateTimeFormatter
import java.util.Locale

/** الإيموجي المتاحة للتفاعل — نفس مجموعة نسخة Flutter. */
val REACTION_EMOJIS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale("ar"))

/**
 * فقاعة رسالة — نقل `ui/chat/message_bubble.dart`.
 *
 * تعرض: المرسل، الردّ المقتبس، المحتوى بحسب النوع، التفاعلات، الوقت،
 * وعلامة ✓✓ للقراءة على رسائلي. الضغط المطوَّل يفتح قائمة الإجراءات.
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    isRead: Boolean,
    canModerate: Boolean,
    onReply: () -> Unit,
    onReplyPrivately: () -> Unit,
    onReact: (String?) -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onPin: () -> Unit,
    onOpenVideo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val mine = message.isMine

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        if (!mine && message.senderPhoto.isNotEmpty()) {
            AsyncImage(
                model = message.senderPhoto,
                contentDescription = null,
                modifier = Modifier.size(28.dp).clip(CircleShape),
            )
            Spacer(Modifier.width(6.dp))
        }

        Box {
            Column(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 14.dp,
                            topEnd = 14.dp,
                            bottomStart = if (mine) 14.dp else 4.dp,
                            bottomEnd = if (mine) 4.dp else 14.dp,
                        ),
                    )
                    .background(
                        if (mine) {
                            NebrasColors.sidebarActive
                        } else {
                            NebrasColors.surfaceAlt
                        },
                    )
                    .clickable { menuOpen = true }
                    .padding(10.dp),
            ) {
                if (!mine) {
                    Text(
                        text = message.senderName,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = NebrasColors.emerald,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(4.dp))
                }

                // اقتباس الردّ.
                message.replyTo?.let { reply ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.25f))
                            .padding(8.dp),
                    ) {
                        Text(
                            text = reply.senderName,
                            style = MaterialTheme.typography.labelSmall,
                            color = NebrasColors.emerald,
                        )
                        Text(
                            text = reply.preview,
                            style = MaterialTheme.typography.labelSmall,
                            color = NebrasColors.textMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.size(6.dp))
                }

                if (message.deleted) {
                    Text(
                        text = "🚫 حُذفت هذه الرسالة",
                        style = MaterialTheme.typography.bodySmall,
                        color = NebrasColors.textMuted,
                    )
                } else {
                    when (message.type) {
                        ChatMessageType.TEXT -> Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NebrasColors.textPrimary,
                        )
                        ChatMessageType.IMAGE -> message.attachment?.let {
                            ChatImageBubble(it)
                        }
                        ChatMessageType.VIDEO -> message.attachment?.let {
                            ChatVideoBubble(it, onOpen = onOpenVideo)
                        }
                        ChatMessageType.AUDIO, ChatMessageType.VOICE ->
                            message.attachment?.let { ChatAudioBubble(it) }
                        ChatMessageType.FILE -> message.attachment?.let {
                            ChatFileBubble(it)
                        }
                    }
                    // تعليق مرفق (caption).
                    if (message.type != ChatMessageType.TEXT && message.text.isNotEmpty()) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = NebrasColors.textPrimary,
                        )
                    }
                }

                // التفاعلات المجمَّعة.
                if (message.reactions.isNotEmpty()) {
                    Spacer(Modifier.size(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        message.reactions.values.groupingBy { it }.eachCount()
                            .forEach { (emoji, count) ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.Black.copy(alpha = 0.25f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = if (count > 1) "$emoji $count" else emoji,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                    }
                }

                Spacer(Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message.createdAtLocal.format(TIME_FORMATTER),
                        style = MaterialTheme.typography.labelSmall,
                        color = NebrasColors.textMuted,
                    )
                    if (mine) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.DoneAll,
                            contentDescription = if (isRead) "تمّت القراءة" else "أُرسلت",
                            modifier = Modifier.size(14.dp),
                            tint = if (isRead) NebrasColors.emerald else NebrasColors.textMuted,
                        )
                    }
                    if (message.pending) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "…",
                            style = MaterialTheme.typography.labelSmall,
                            color = NebrasColors.textMuted,
                        )
                    }
                }
            }

            // ── قائمة الإجراءات ──
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    REACTION_EMOJIS.forEach { emoji ->
                        Text(
                            text = emoji,
                            modifier = Modifier.clickable {
                                onReact(emoji)
                                menuOpen = false
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                DropdownMenuItem(
                    text = { Text("إزالة تفاعلي") },
                    onClick = {
                        onReact(null)
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("ردّ") },
                    onClick = {
                        onReply()
                        menuOpen = false
                    },
                )
                if (!mine) {
                    DropdownMenuItem(
                        text = { Text("ردّ بشكل خاص") },
                        onClick = {
                            onReplyPrivately()
                            menuOpen = false
                        },
                    )
                }
                if (canModerate) {
                    DropdownMenuItem(
                        text = { Text("تثبيت") },
                        leadingIcon = {
                            Icon(Icons.Default.PushPin, contentDescription = null)
                        },
                        onClick = {
                            onPin()
                            menuOpen = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("حذف لديّ") },
                    onClick = {
                        onDeleteForMe()
                        menuOpen = false
                    },
                )
                if (mine || canModerate) {
                    DropdownMenuItem(
                        text = { Text("حذف للجميع", color = NebrasColors.rose) },
                        onClick = {
                            onDeleteForEveryone()
                            menuOpen = false
                        },
                    )
                }
            }
        }
    }
}
