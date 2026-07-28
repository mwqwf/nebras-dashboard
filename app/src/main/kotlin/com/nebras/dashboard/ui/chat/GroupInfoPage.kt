package com.nebras.dashboard.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import coil3.compose.AsyncImage
import com.nebras.dashboard.core.NebrasColors
import com.nebras.dashboard.data.ChatGroupMeta
import com.nebras.dashboard.data.ChatMember
import com.nebras.dashboard.data.ChatRepository
import com.nebras.dashboard.ui.content.readFileMeta
import com.nebras.dashboard.ui.widgets.DashBadge
import com.nebras.dashboard.ui.widgets.DashCard
import com.nebras.dashboard.ui.widgets.DashSectionTitle
import com.nebras.dashboard.ui.widgets.DashTextAction
import com.nebras.dashboard.ui.widgets.DashTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * صفحة معلومات المجموعة — نقل `ui/chat/group_info_page.dart`.
 * اسم/صورة المجموعة والقفل (للمشرفين)، وقائمة الأعضاء بأدوارهم.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoPage(
    meta: ChatGroupMeta,
    members: List<ChatMember>,
    isOwner: Boolean,
    canModerate: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var memberDialog by remember { mutableStateOf<ChatMember?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val fileMeta = readFileMeta(context, uri) ?: return@launch
            val cached = withContext(Dispatchers.IO) {
                runCatching {
                    val out = File(context.cacheDir, "group_${fileMeta.filename}")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use(input::copyTo)
                    }
                    out
                }.getOrNull()
            } ?: return@launch
            runCatching {
                ChatRepository.setGroupPhotoFromPath(cached.absolutePath, fileMeta.filename)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NebrasColors.surface)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "رجوع")
            }
            Text(
                text = "معلومات المجموعة",
                style = MaterialTheme.typography.titleMedium,
                color = NebrasColors.textPrimary,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        if (meta.photoUrl.isNotEmpty()) {
                            AsyncImage(
                                model = meta.photoUrl,
                                contentDescription = null,
                                modifier = Modifier.size(88.dp).clip(CircleShape),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(88.dp)
                                    .clip(CircleShape)
                                    .background(NebrasColors.emerald.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = meta.name.take(1).ifEmpty { "ن" },
                                    color = NebrasColors.emerald,
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                            }
                        }
                        if (canModerate) {
                            IconButton(
                                onClick = { photoPicker.launch(arrayOf("image/*")) },
                                modifier = Modifier.align(Alignment.BottomEnd),
                            ) {
                                Icon(
                                    Icons.Default.PhotoCamera,
                                    contentDescription = "تغيير الصورة",
                                    tint = NebrasColors.emerald,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = meta.name.ifEmpty { "دردشة الإدارة" },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = NebrasColors.textPrimary,
                    )
                    if (canModerate) {
                        DashTextAction(text = "تغيير الاسم", onClick = { renaming = true })
                    }
                }
            }

            if (canModerate) {
                item {
                    DashCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (meta.locked) {
                                    Icons.Default.Lock
                                } else {
                                    Icons.Default.LockOpen
                                },
                                contentDescription = null,
                                tint = if (meta.locked) {
                                    NebrasColors.amber
                                } else {
                                    NebrasColors.textMuted
                                },
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "قفل المجموعة",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NebrasColors.textPrimary,
                                )
                                Text(
                                    text = "عند القفل يُرسل المشرفون فقط",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NebrasColors.textMuted,
                                )
                            }
                            Switch(
                                checked = meta.locked,
                                onCheckedChange = { locked ->
                                    scope.launch { ChatRepository.setLocked(locked) }
                                },
                            )
                        }
                    }
                }
            }

            item { DashSectionTitle(title = "الأعضاء (${members.size})") }

            items(members, key = { it.uid }) { member ->
                DashCard(onClick = if (isOwner) ({ memberDialog = member }) else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (member.displayPhoto.isNotEmpty()) {
                            AsyncImage(
                                model = member.displayPhoto,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(NebrasColors.surfaceAlt),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = member.displayName.take(1),
                                    color = NebrasColors.textMuted,
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = member.displayName,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = NebrasColors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (member.isOwner) {
                                    Spacer(Modifier.width(6.dp))
                                    Text("👑")
                                }
                                if (member.isChatModerator) {
                                    Spacer(Modifier.width(6.dp))
                                    DashBadge(text = "مشرف", color = NebrasColors.emerald)
                                }
                            }
                            Text(
                                text = member.email,
                                style = MaterialTheme.typography.labelSmall,
                                color = NebrasColors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (member.isOnline) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(NebrasColors.emerald),
                            )
                        }
                    }
                }
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(meta.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("اسم المجموعة") },
            text = {
                DashTextField(value = name, onValueChange = { name = it }, label = "الاسم")
            },
            confirmButton = {
                DashTextAction(
                    text = "حفظ",
                    onClick = {
                        scope.launch { ChatRepository.setGroupName(name.trim()) }
                        renaming = false
                    },
                )
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("إلغاء") } },
        )
    }

    // إدارة دور العضو في الدردشة — للمالك فقط.
    memberDialog?.let { member ->
        AlertDialog(
            onDismissRequest = { memberDialog = null },
            title = { Text(member.displayName) },
            text = {
                Text(
                    if (member.isChatModerator) {
                        "هذا العضو مشرف في الدردشة."
                    } else {
                        "ترقية العضو مشرفاً تمنحه التثبيت والقفل وحذف الرسائل."
                    },
                )
            },
            confirmButton = {
                DashTextAction(
                    text = if (member.isChatModerator) "إزالة الإشراف" else "ترقية مشرفاً",
                    onClick = {
                        scope.launch {
                            ChatRepository.setChatRole(
                                member.uid,
                                if (member.isChatModerator) null else "moderator",
                            )
                        }
                        memberDialog = null
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { memberDialog = null }) { Text("إغلاق") }
            },
        )
    }
}
