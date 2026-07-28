package com.nebras.dashboard.ui.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
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
import com.nebras.dashboard.auth.AuthController
import com.nebras.dashboard.core.FirestoreRefs
import com.nebras.dashboard.core.NebrasColors
import com.nebras.dashboard.ui.widgets.DashBadge
import com.nebras.dashboard.ui.widgets.DashCard
import com.nebras.dashboard.ui.widgets.DashEmptyState
import com.nebras.dashboard.ui.widgets.DashLoading
import com.nebras.dashboard.ui.widgets.DashTextAction
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val UGC_CONTENTS = "ugc_contents"
private const val UGC_CHANNELS = "ugc_channels"
private const val UGC_REPORTS = "ugc_reports"

private data class UgcRow(val id: String, val data: Map<String, Any?>)

/**
 * إشراف المجتمع — نقل `ui/community/community_moderation_screen.dart`.
 *
 * ⚠️ **دلالة حاسمة**: حذف بلاغ UGC **يحذف المنشور فعلياً** من
 * `ugc_contents` (لا يكتفي بإغلاق البلاغ) — هذه كانت أخطر فجوة في النسخة
 * السابقة، فلا تُخفَّف هنا.
 */
@Composable
fun CommunityModerationScreen(
    navController: NavHostController,
    authController: AuthController,
) {
    val scope = rememberCoroutineScope()
    var tabIndex by remember { mutableIntStateOf(0) }
    var reloadKey by remember { mutableIntStateOf(0) }

    var reports by remember { mutableStateOf<List<UgcRow>>(emptyList()) }
    var posts by remember { mutableStateOf<List<UgcRow>>(emptyList()) }
    var channels by remember { mutableStateOf<List<UgcRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    var confirmDelete by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(reloadKey) {
        loading = true
        reports = fetch(UGC_REPORTS, "createdAt")
        posts = fetch(UGC_CONTENTS, "created_at")
        channels = fetch(UGC_CHANNELS, "createdAt")
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        SecondaryTabRow(selectedTabIndex = tabIndex) {
            listOf("البلاغات", "المنشورات", "القنوات").forEachIndexed { index, label ->
                Tab(
                    selected = tabIndex == index,
                    onClick = { tabIndex = index },
                    text = { Text(label) },
                )
            }
        }

        if (loading) {
            DashLoading()
            return@Column
        }

        when (tabIndex) {
            0 -> ReportsList(
                reports = reports,
                onDeletePost = { contentId, reportId ->
                    confirmDelete = contentId to reportId
                },
                onDismissReport = { reportId ->
                    scope.launch {
                        runCatching {
                            FirestoreRefs.db.collection(UGC_REPORTS).document(reportId)
                                .delete().await()
                        }
                        reloadKey++
                    }
                },
            )

            1 -> PostsList(
                posts = posts,
                onDelete = { id -> confirmDelete = id to "" },
                onToggleStatus = { id, published ->
                    scope.launch {
                        runCatching {
                            FirestoreRefs.db.collection(UGC_CONTENTS).document(id)
                                .update("status", if (published) "hidden" else "published")
                                .await()
                        }
                        reloadKey++
                    }
                },
            )

            else -> ChannelsList(
                channels = channels,
                onToggleSuspend = { id, suspended ->
                    scope.launch {
                        runCatching {
                            FirestoreRefs.db.collection(UGC_CHANNELS).document(id)
                                .update("status", if (suspended) "active" else "suspended")
                                .await()
                        }
                        reloadKey++
                    }
                },
            )
        }
    }

    confirmDelete?.let { (contentId, reportId) ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("حذف المنشور") },
            text = {
                Text(
                    "سيُحذف المنشور نهائياً من مجتمع نبراس مع تعليقاته وتفاعلاته. " +
                        "لا يمكن التراجع.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            // 1) حذف المنشور نفسه — الجوهر الذي كان مفقوداً.
                            runCatching {
                                deletePostCascade(contentId)
                            }
                            // 2) ثمّ إغلاق البلاغ إن جاء الحذف من شاشة البلاغات.
                            if (reportId.isNotEmpty()) {
                                runCatching {
                                    FirestoreRefs.db.collection(UGC_REPORTS)
                                        .document(reportId).delete().await()
                                }
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

/** حذف تعاقبيّ: التعليقات والتفاعلات ثمّ الوثيقة. */
private suspend fun deletePostCascade(contentId: String) {
    val doc = FirestoreRefs.db.collection(UGC_CONTENTS).document(contentId)
    for (sub in listOf("comments", "reactions")) {
        while (true) {
            val snapshot = doc.collection(sub).limit(400).get().await()
            if (snapshot.isEmpty) break
            val batch = FirestoreRefs.db.batch()
            snapshot.documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }
    doc.delete().await()
}

private suspend fun fetch(collection: String, orderBy: String): List<UgcRow> = runCatching {
    FirestoreRefs.db.collection(collection)
        .orderBy(orderBy, Query.Direction.DESCENDING)
        .limit(200)
        .get()
        .await()
        .documents
        .map { UgcRow(it.id, it.data ?: emptyMap()) }
}.getOrDefault(emptyList())

@Composable
private fun ReportsList(
    reports: List<UgcRow>,
    onDeletePost: (contentId: String, reportId: String) -> Unit,
    onDismissReport: (reportId: String) -> Unit,
) {
    if (reports.isEmpty()) {
        DashEmptyState("لا توجد بلاغات")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(reports, key = { it.id }) { report ->
            val contentId = report.data["contentId"]?.toString().orEmpty()
            DashCard {
                Text(
                    text = report.data["contentTitle"]?.toString()
                        ?: report.data["reason"]?.toString()
                        ?: "بلاغ",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = NebrasColors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                DashBadge(
                    text = report.data["reasonCode"]?.toString() ?: "غير محدَّد",
                    color = NebrasColors.rose,
                )
                report.data["note"]?.toString()?.takeIf { it.isNotBlank() }?.let { note ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = NebrasColors.textMuted,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (contentId.isNotEmpty()) {
                        DashTextAction(
                            text = "حذف المنشور",
                            danger = true,
                            onClick = { onDeletePost(contentId, report.id) },
                        )
                    }
                    DashTextAction(
                        text = "رفض البلاغ",
                        onClick = { onDismissReport(report.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PostsList(
    posts: List<UgcRow>,
    onDelete: (String) -> Unit,
    onToggleStatus: (String, Boolean) -> Unit,
) {
    if (posts.isEmpty()) {
        DashEmptyState("لا توجد منشورات")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(posts, key = { it.id }) { post ->
            val published = (post.data["status"] ?: "published") == "published"
            DashCard {
                Text(
                    text = post.data["title"]?.toString().orEmpty(),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = NebrasColors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = post.data["channelName"]?.toString().orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = NebrasColors.textMuted,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!published) DashBadge(text = "مخفي", color = NebrasColors.amber)
                    DashTextAction(
                        text = if (published) "إخفاء" else "إظهار",
                        onClick = { onToggleStatus(post.id, published) },
                    )
                    DashTextAction(
                        text = "حذف",
                        danger = true,
                        onClick = { onDelete(post.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelsList(
    channels: List<UgcRow>,
    onToggleSuspend: (String, Boolean) -> Unit,
) {
    if (channels.isEmpty()) {
        DashEmptyState("لا توجد قنوات")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(channels, key = { it.id }) { channel ->
            val suspended = channel.data["status"]?.toString() == "suspended"
            DashCard {
                Text(
                    text = channel.data["name"]?.toString().orEmpty(),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = NebrasColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (suspended) DashBadge(text = "معلَّقة", color = NebrasColors.rose)
                    DashTextAction(
                        text = if (suspended) "إعادة التفعيل" else "تعليق القناة",
                        danger = !suspended,
                        onClick = { onToggleSuspend(channel.id, suspended) },
                    )
                }
            }
        }
    }
}
