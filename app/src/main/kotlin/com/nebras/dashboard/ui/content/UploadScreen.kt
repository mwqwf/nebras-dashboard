package com.nebras.dashboard.ui.content

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.nebras.dashboard.auth.AuthController
import com.nebras.dashboard.core.NebrasColors
import com.nebras.dashboard.data.AdminStats
import com.nebras.dashboard.data.ContentRepository
import com.nebras.dashboard.data.SectionsRepository
import com.nebras.dashboard.data.contentTypeFromMime
import com.nebras.dashboard.ui.widgets.DashCard
import com.nebras.dashboard.ui.widgets.DashPrimaryButton
import com.nebras.dashboard.ui.widgets.DashSecondaryButton
import com.nebras.dashboard.ui.widgets.DashSectionTitle
import com.nebras.dashboard.ui.widgets.DashTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ملفّ مختار في الطابور مع حالته. */
internal data class PendingUpload(
    val uri: Uri,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    var title: String = "",
    var progress: Double = 0.0,
    var status: String = "idle", // idle | uploading | done | failed
    var error: String? = null,
)

/**
 * شاشة الرفع المتعدّد — نقل `ui/content/upload_screen.dart`.
 *
 * ⚠️ **الترتيب مقصود**: الرفع إلى Storage يتمّ لكلّ ملفّ عبر
 * [ContentRepository.prepareUpload]، ثمّ تُكتب وثائق Firestore بـ
 * [ContentRepository.commitPrepared] **بترتيب الاختيار حرفياً** — هذا ما
 * يحفظ `selectionOrder` الذي يعتمده ترتيب العرض في التطبيق.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(
    navController: NavHostController,
    authController: AuthController,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val queue = remember { mutableListOf<PendingUpload>().toMutableStateList() }
    var mainSections by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var subSections by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var secondarySections by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }

    var selectedMain by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var selectedSub by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var selectedSecondary by remember { mutableStateOf<Map<String, Any?>?>(null) }

    var author by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    // تحميل الأقسام الرئيسية مرّة واحدة.
    LaunchedEffect(Unit) {
        runCatching { SectionsRepository.listMain() }.onSuccess { mainSections = it }
    }
    LaunchedEffect(selectedMain) {
        selectedSub = null
        selectedSecondary = null
        subSections = selectedMain?.let { main ->
            runCatching { SectionsRepository.listSub(mainSection = main["id"]) }
                .getOrDefault(emptyList())
        } ?: emptyList()
    }
    LaunchedEffect(selectedSub) {
        selectedSecondary = null
        secondarySections = selectedSub?.let { sub ->
            runCatching { SectionsRepository.listSecondary(subSection = sub["id"]) }
                .getOrDefault(emptyList())
        } ?: emptyList()
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        uris.forEach { uri ->
            readFileMeta(context, uri)?.let(queue::add)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DashSectionTitle(title = "رفع محتوى جديد") }

        // ── اختيار الأقسام ──
        item {
            SectionDropdown(
                label = "القسم الرئيسي",
                options = mainSections,
                selected = selectedMain,
                onSelect = { selectedMain = it },
            )
        }
        item {
            SectionDropdown(
                label = "القسم الفرعي",
                options = subSections,
                selected = selectedSub,
                enabled = selectedMain != null,
                onSelect = { selectedSub = it },
            )
        }
        item {
            SectionDropdown(
                label = "القسم الثانوي (اختياري)",
                options = secondarySections,
                selected = selectedSecondary,
                enabled = selectedSub != null,
                onSelect = { selectedSecondary = it },
            )
        }

        item {
            DashTextField(value = author, onValueChange = { author = it }, label = "المؤلّف")
        }
        item {
            DashTextField(
                value = description,
                onValueChange = { description = it },
                label = "الوصف",
                singleLine = false,
                minLines = 3,
            )
        }

        // ── الطابور ──
        item {
            DashSecondaryButton(
                text = "اختيار ملفّات",
                icon = Icons.Default.CloudUpload,
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (queue.isNotEmpty()) {
            item { DashSectionTitle(title = "الطابور (${queue.size})") }
            items(queue, key = { it.uri.toString() }) { pending ->
                UploadQueueCard(
                    pending = pending,
                    enabled = !uploading,
                    onTitleChange = { newTitle ->
                        val index = queue.indexOfFirst { it.uri == pending.uri }
                        if (index >= 0) queue[index] = queue[index].copy(title = newTitle)
                    },
                    onRemove = { queue.removeAll { it.uri == pending.uri } },
                )
            }
        }

        item {
            DashPrimaryButton(
                text = "رفع ${queue.size} ملفّاً",
                loading = uploading,
                enabled = queue.isNotEmpty() && selectedSub != null,
                onClick = {
                    scope.launch {
                        uploading = true
                        resultMessage = null
                        val ok = uploadAll(
                            context = context,
                            queue = queue,
                            mainSection = selectedMain?.get("id"),
                            subsection = selectedSub?.get("id"),
                            secondarySubsection = selectedSecondary?.get("id"),
                            author = author,
                            description = description,
                        )
                        uploading = false
                        resultMessage = if (ok > 0) {
                            "تمّ رفع $ok من ${queue.size}"
                        } else {
                            "تعذّر الرفع"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        resultMessage?.let { message ->
            item {
                Text(
                    text = message,
                    color = NebrasColors.emerald,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * ينفّذ الرفع: **رفع Storage لكلّ ملفّ ثمّ كتابة Firestore بترتيب الاختيار**.
 * يُعيد عدد الملفّات التي نجحت.
 */
private suspend fun uploadAll(
    context: Context,
    queue: SnapshotStateList<PendingUpload>,
    mainSection: Any?,
    subsection: Any?,
    secondarySubsection: Any?,
    author: String,
    description: String,
): Int {
    var succeeded = 0
    val prepared = mutableListOf<Map<String, Any?>>()

    for (index in queue.indices) {
        val pending = queue[index]
        queue[index] = pending.copy(status = "uploading", progress = 0.0)
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(pending.uri)?.use { it.readBytes() }
            }.getOrNull()
        }
        if (bytes == null) {
            queue[index] = queue[index].copy(status = "failed", error = "تعذّر قراءة الملفّ")
            continue
        }

        val metadata = mapOf(
            "title" to pending.title.ifEmpty { pending.filename.substringBeforeLast('.') },
            "author" to author,
            "description" to description,
            "content_type" to contentTypeFromMime(pending.mimeType),
            "main_section" to mainSection,
            "subsection" to subsection,
            "secondary_subsection" to secondarySubsection,
            "is_listed" to true,
            // ترتيب الاختيار — يعتمده عرض المحتوى داخل الأقسام في التطبيق.
            "selectionOrder" to index,
        )

        runCatching {
            ContentRepository.prepareUpload(
                bytes = bytes,
                filename = pending.filename,
                mimeType = pending.mimeType,
                metadata = metadata,
                onProgress = { p ->
                    val i = queue.indexOfFirst { it.uri == pending.uri }
                    if (i >= 0) queue[i] = queue[i].copy(progress = p)
                },
            )
        }.onSuccess { prepared.add(it) }
            .onFailure {
                queue[index] = queue[index].copy(
                    status = "failed",
                    error = it.message ?: "فشل الرفع",
                )
            }
    }

    // الكتابة بترتيب الاختيار حرفياً (لا بترتيب انتهاء الرفع).
    for (payload in prepared) {
        runCatching { ContentRepository.commitPrepared(payload) }
            .onSuccess { succeeded++ }
    }
    if (succeeded > 0) runCatching { AdminStats.bumpUpload() }

    queue.indices.forEach { i ->
        if (queue[i].status != "failed") {
            queue[i] = queue[i].copy(status = "done", progress = 1.0)
        }
    }
    return succeeded
}

@Composable
private fun UploadQueueCard(
    pending: PendingUpload,
    enabled: Boolean,
    onTitleChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    DashCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = pending.filename,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = NebrasColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = humanSize(pending.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = NebrasColors.textMuted,
                )
            }
            if (enabled) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Close, contentDescription = "إزالة")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        DashTextField(
            value = pending.title,
            onValueChange = onTitleChange,
            label = "العنوان",
            enabled = enabled,
        )
        if (pending.status == "uploading") {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { pending.progress.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        }
        pending.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = NebrasColors.rose, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** قائمة منسدلة لاختيار قسم — تعرض `name` وتُعيد السجلّ كاملاً. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SectionDropdown(
    label: String,
    options: List<Map<String, Any?>>,
    selected: Map<String, Any?>?,
    onSelect: (Map<String, Any?>?) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = selected?.get("name")?.toString().orEmpty()

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = { },
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option["name"]?.toString().orEmpty()) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** يقرأ اسم الملفّ وحجمه ونوعه من الـ Uri المختار. */
internal fun readFileMeta(context: Context, uri: Uri): PendingUpload? = runCatching {
    val resolver = context.contentResolver
    var name = "file"
    var size = 0L
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
        }
    }
    PendingUpload(
        uri = uri,
        filename = name,
        mimeType = resolver.getType(uri) ?: "application/octet-stream",
        sizeBytes = size,
        title = name.substringBeforeLast('.'),
    )
}.getOrNull()

internal fun humanSize(bytes: Long): String {
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
        String.format(java.util.Locale.US, "%.1f %s", size, units[unit])
    }
}
