package com.nebras.dashboard.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.google.firebase.firestore.Query
import com.nebras.dashboard.core.FirestoreRefs
import com.nebras.dashboard.core.NebrasColors
import com.nebras.dashboard.data.ContentRepository
import com.nebras.dashboard.ui.widgets.DashBadge
import com.nebras.dashboard.ui.widgets.DashCard
import com.nebras.dashboard.ui.widgets.DashEmptyState
import com.nebras.dashboard.ui.widgets.DashKeyValueRow
import com.nebras.dashboard.ui.widgets.DashLoading
import com.nebras.dashboard.ui.widgets.DashTextAction
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val CONTENT_REPORTS = "content_reports"
private const val TAKEDOWN_PENDING = "content_takedown_pending"

private data class ReportRow(val id: String, val data: Map<String, Any?>)

/**
 * بلاغات المحتوى الرسميّ — نقل `ui/reports/reports_screen.dart`.
 *
 * الإجراءات: حذف المحتوى المُبلَّغ عنه (تعاقبيّ عبر [ContentRepository]) مع
 * رفع علامة الإخفاء العالميّ، أو رفض البلاغ وإزالة العلامة فيعود المحتوى.
 */
@Composable
fun ReportsScreen(navController: NavHostController) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<ReportRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf<ReportRow?>(null) }

    LaunchedEffect(reloadKey) {
        loading = true
        rows = runCatching {
            FirestoreRefs.db.collection(CONTENT_REPORTS)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(200)
                .get()
                .await()
                .documents
                .map { ReportRow(it.id, it.data ?: emptyMap()) }
        }.getOrDefault(emptyList())
        loading = false
    }

    if (loading) {
        DashLoading()
        return
    }
    if (rows.isEmpty()) {
        DashEmptyState("لا توجد بلاغات")
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(rows, key = { it.id }) { report ->
            val contentId = report.data["contentId"]?.toString().orEmpty()
            DashCard {
                Text(
                    text = report.data["contentTitle"]?.toString().orEmpty(),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = NebrasColors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                DashBadge(
                    text = arabicReason(report.data["reasonCode"]?.toString()),
                    color = NebrasColors.rose,
                )
                Spacer(Modifier.height(6.dp))
                report.data["contentType"]?.toString()?.let {
                    DashKeyValueRow(label = "النوع", value = it)
                }
                report.data["note"]?.toString()?.takeIf { it.isNotBlank() }?.let {
                    DashKeyValueRow(label = "ملاحظة", value = it)
                }

                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (contentId.isNotEmpty()) {
                        DashTextAction(
                            text = "حذف المحتوى",
                            danger = true,
                            onClick = { confirmDelete = report },
                        )
                    }
                    DashTextAction(
                        text = "رفض البلاغ",
                        onClick = {
                            scope.launch {
                                // رفض البلاغ يرفع الإخفاء العالميّ فيعود المحتوى.
                                if (contentId.isNotEmpty()) {
                                    runCatching {
                                        FirestoreRefs.db.collection(TAKEDOWN_PENDING)
                                            .document(contentId).delete().await()
                                    }
                                }
                                runCatching {
                                    FirestoreRefs.db.collection(CONTENT_REPORTS)
                                        .document(report.id).delete().await()
                                }
                                reloadKey++
                            }
                        },
                    )
                }
            }
        }
    }

    confirmDelete?.let { report ->
        val contentId = report.data["contentId"]?.toString().orEmpty()
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("حذف المحتوى المُبلَّغ عنه") },
            text = {
                Text(
                    "سيُحذف «${report.data["contentTitle"]}» نهائياً مع ملفّاته، " +
                        "وسيُزال البلاغ. لا يمكن التراجع.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            runCatching { ContentRepository.removeFile(contentId) }
                            runCatching {
                                FirestoreRefs.db.collection(TAKEDOWN_PENDING)
                                    .document(contentId).delete().await()
                            }
                            runCatching {
                                FirestoreRefs.db.collection(CONTENT_REPORTS)
                                    .document(report.id).delete().await()
                            }
                            confirmDelete = null
                            reloadKey++
                        }
                    },
                ) {
                    Text("حذف نهائياً", color = NebrasColors.rose)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("إلغاء") }
            },
        )
    }
}

/** ترجمة رمز سبب البلاغ — نفس نصوص التطبيق. */
private fun arabicReason(code: String?): String = when (code) {
    "copyright" -> "حقوق نشر"
    "sexual" -> "محتوى جنسي"
    "child_safety" -> "سلامة الأطفال"
    "violence" -> "عنف"
    "terrorism" -> "إرهاب"
    "other" -> "أخرى"
    else -> code ?: "غير محدَّد"
}
