package com.nebras.dashboard.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nebras.dashboard.core.NebrasColors
import com.nebras.dashboard.data.ChatMember
import com.nebras.dashboard.data.ChatRepository
import com.nebras.dashboard.ui.content.readFileMeta
import com.nebras.dashboard.ui.widgets.DashSecondaryButton
import com.nebras.dashboard.ui.widgets.DashTextAction
import com.nebras.dashboard.ui.widgets.DashTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * حوار الملفّ الشخصيّ داخل الدردشة — نقل `ui/chat/profile_dialog.dart`.
 * يسمح للعضو بتعيين اسم وصورة خاصّين بالدردشة (يغلبان بيانات Google).
 */
@Composable
fun ProfileDialog(
    member: ChatMember,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(member.uid) { mutableStateOf(member.displayName) }
    var photoUrl by remember(member.uid) { mutableStateOf(member.displayPhoto) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val meta = readFileMeta(context, uri) ?: return@launch
            val cached = withContext(Dispatchers.IO) {
                runCatching {
                    val out = File(context.cacheDir, "profile_${meta.filename}")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use(input::copyTo)
                    }
                    out
                }.getOrNull()
            } ?: return@launch
            runCatching {
                ChatRepository.setMyPhotoFromPath(cached.absolutePath, meta.filename)
            }
            photoUrl = cached.absolutePath
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ملفّي في الدردشة") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (photoUrl.isNotEmpty()) {
                    AsyncImage(
                        model = photoUrl,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp).clip(CircleShape),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(NebrasColors.surfaceAlt),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = name.take(1),
                            style = MaterialTheme.typography.titleLarge,
                            color = NebrasColors.textMuted,
                        )
                    }
                }

                DashSecondaryButton(
                    text = "تغيير الصورة",
                    onClick = { photoPicker.launch(arrayOf("image/*")) },
                )
                if (member.customPhoto.isNotEmpty()) {
                    DashTextAction(
                        text = "إزالة الصورة المخصّصة",
                        danger = true,
                        onClick = {
                            scope.launch { runCatching { ChatRepository.clearMyPhoto() } }
                            photoUrl = member.photo
                        },
                    )
                }

                DashTextField(value = name, onValueChange = { name = it }, label = "الاسم المعروض")
            }
        },
        confirmButton = {
            DashTextAction(
                text = "حفظ",
                onClick = {
                    scope.launch { runCatching { ChatRepository.setMyName(name.trim()) } }
                    onDismiss()
                },
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
}
