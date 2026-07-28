package com.nebras.dashboard.ui.content

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.nebras.dashboard.auth.AuthController
import com.nebras.dashboard.core.NebrasColors
import com.nebras.dashboard.data.ContentRepository
import com.nebras.dashboard.data.ContentRow
import com.nebras.dashboard.ui.widgets.DashBadge
import com.nebras.dashboard.ui.widgets.DashCard
import com.nebras.dashboard.ui.widgets.DashEmptyState
import com.nebras.dashboard.ui.widgets.DashErrorState
import com.nebras.dashboard.ui.widgets.DashKeyValueRow
import com.nebras.dashboard.ui.widgets.DashLoading
import com.nebras.dashboard.ui.widgets.DashSearchField
import com.nebras.dashboard.ui.widgets.DashTextField
import com.nebras.dashboard.ui.widgets.DashTextAction
import kotlinx.coroutines.launch

/** فلاتر نوع المحتوى — نفس القيم المخزَّنة في Firestore. */
private val TYPE_FILTERS = listOf(
    null to "الكل",
    "document" to "مستندات",
    "audio" to "صوت",
    "video" to "فيديو",
    "image" to "صور",
    "article" to "مقالات",
)

/**
 * شاشة إدارة المحتوى — نقل `ui/content/content_files_screen.dart`.
 *
 * ⚠️ دلالات ثابتة منقولة كما هي: **الحذف تعاقبيّ** عبر
 * [ContentRepository.removeFile] (يحذف الوثيقة ومرآتها وملفّات التخزين)،
 * و**النقل** عبر `updateContent(move = true)` يُعيد بناء المسار القانونيّ
 * ويصفّر القديم.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentFilesScreen(
    navController: NavHostController,
    authController: AuthController,
) {
    val scope = rememberCoroutineScope()

    var search by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<ContentRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    var editing by remember { mutableStateOf<ContentRow?>(null) }
    var deleting by remember { mutableStateOf<ContentRow?>(null) }

    LaunchedEffect(search, typeFilter, reloadKey) {
        loading = true
        error = null
        runCatching { ContentRepository.listFiles(search = search, contentType = typeFilter) }
            .onSuccess { rows = it }
            .onFailure { error = it.message ?: "تعذّر تحميل المحتوى" }
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        DashSearchField(
            value = search,
            onValueChange = { search = it },
            placeholder = "ابحث في المحتوى…",
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(TYPE_FILTERS) { (value, label) ->
                FilterChip(
                    selected = typeFilter == value,
                    onClick = { typeFilter = value },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        when {
            loading -> DashLoading()
            error != null -> DashErrorState(error!!) { reloadKey++ }
            rows.isEmpty() -> DashEmptyState("لا يوجد محتوى مطابق")
            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(rows, key = { it.id }) { row ->
                    ContentRowCard(
                        row = row,
                        onEdit = { editing = row },
                        onDelete = { deleting = row },
                        onToggleListed = {
                            scope.launch {
                                runCatching {
                                    ContentRepository.updateContent(
                                        fileId = row.id,
                                        isListed = !row.isListed,
                                    )
                                }
                                reloadKey++
                            }
                        },
                    )
                }
            }
        }
    }

    // ── حوار التعديل ──
    editing?.let { row ->
        EditContentDialog(
            row = row,
            onDismiss = { editing = null },
            onSave = { title, description, author ->
                scope.launch {
                    runCatching {
                        ContentRepository.updateContent(
                            fileId = row.id,
                            title = title,
                            description = description,
                            author = author,
                        )
                    }
                    editing = null
                    reloadKey++
                }
            },
        )
    }

    // ── تأكيد الحذف (تعاقبيّ) ──
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("حذف المحتوى") },
            text = {
                Text(
                    "سيُحذف «${row.title}» نهائياً مع ملفّاته من التخزين. " +
                        "لا يمكن التراجع عن هذا الإجراء.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            runCatching { ContentRepository.removeFile(row.id) }
                            deleting = null
                            reloadKey++
                        }
                    },
                ) {
                    Text("حذف", color = NebrasColors.rose)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("إلغاء") }
            },
        )
    }
}

@Composable
private fun ContentRowCard(
    row: ContentRow,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleListed: () -> Unit,
) {
    DashCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = NebrasColors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DashBadge(text = row.contentType)
                    if (!row.isListed) {
                        DashBadge(text = "مخفي", color = NebrasColors.amber)
                    }
                    if (row.uploadStatus != "completed") {
                        DashBadge(text = row.uploadStatus, color = NebrasColors.rose)
                    }
                }
            }
            IconButton(onClick = onToggleListed) {
                Icon(
                    imageVector = if (row.isListed) {
                        Icons.Default.Visibility
                    } else {
                        Icons.Default.VisibilityOff
                    },
                    contentDescription = if (row.isListed) "إخفاء" else "إظهار",
                    tint = NebrasColors.textMuted,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "تعديل", tint = NebrasColors.emerald)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "حذف", tint = NebrasColors.rose)
            }
        }

        if (row.author.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            DashKeyValueRow(label = "المؤلّف", value = row.author)
        }
        if (row.hasEngagement) {
            DashKeyValueRow(
                label = "التفاعل",
                value = "${row.viewCount} مشاهدة · ${row.playCount} تشغيل · " +
                    "${row.completeCount} إكمال",
            )
        }
    }
}

@Composable
private fun EditContentDialog(
    row: ContentRow,
    onDismiss: () -> Unit,
    onSave: (title: String, description: String, author: String) -> Unit,
) {
    var title by remember(row.id) { mutableStateOf(row.title) }
    var description by remember(row.id) { mutableStateOf(row.description) }
    var author by remember(row.id) { mutableStateOf(row.author) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل المحتوى") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DashTextField(value = title, onValueChange = { title = it }, label = "العنوان")
                DashTextField(value = author, onValueChange = { author = it }, label = "المؤلّف")
                DashTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = "الوصف",
                    singleLine = false,
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            DashTextAction(text = "حفظ", onClick = { onSave(title, description, author) })
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        },
    )
}
