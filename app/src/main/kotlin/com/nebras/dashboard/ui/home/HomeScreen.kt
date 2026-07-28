package com.nebras.dashboard.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Groups2
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.nebras.dashboard.auth.AuthController
import com.nebras.dashboard.core.NebrasColors
import com.nebras.dashboard.data.ContentRepository
import com.nebras.dashboard.data.ContentRow
import com.nebras.dashboard.data.SectionsRepository
import com.nebras.dashboard.ui.DashboardRoutes
import com.nebras.dashboard.ui.widgets.DashCard
import com.nebras.dashboard.ui.widgets.DashLoading
import com.nebras.dashboard.ui.widgets.DashSectionTitle
import com.nebras.dashboard.ui.widgets.DashStatCard

/** لقطة أرقام لوحة القيادة. */
private data class DashboardSnapshot(
    val files: List<ContentRow> = emptyList(),
    val mainCount: Int = 0,
    val subCount: Int = 0,
    val secondaryCount: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * لوحة القيادة — نقل `ui/home/home_screen.dart`.
 * بطاقات أرقام + إجراءات سريعة + أحدث المحتوى المرفوع.
 */
@Composable
fun HomeScreen(
    navController: NavHostController,
    authController: AuthController,
) {
    val auth by authController.state.collectAsStateWithLifecycle()

    val snapshot by produceState(DashboardSnapshot()) {
        value = runCatching {
            val files = ContentRepository.listFiles()
            DashboardSnapshot(
                files = files,
                mainCount = SectionsRepository.listMain().size,
                subCount = SectionsRepository.listSub().size,
                secondaryCount = SectionsRepository.listSecondary().size,
                loading = false,
            )
        }.getOrElse {
            DashboardSnapshot(loading = false, error = it.message ?: "تعذّر تحميل البيانات")
        }
    }

    if (snapshot.loading) {
        DashLoading()
        return
    }

    val totalViews = snapshot.files.sumOf { it.viewCount }
    val listed = snapshot.files.count { it.isListed }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "أهلاً ${auth.displayName.ifEmpty { "بك" }}",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                ),
                color = NebrasColors.textPrimary,
            )
            snapshot.error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = NebrasColors.rose, style = MaterialTheme.typography.bodySmall)
            }
        }

        // ── بطاقات الأرقام ──
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DashStatCard(
                    label = "إجمالي المحتوى",
                    value = snapshot.files.size.toString(),
                    icon = Icons.Default.FolderOpen,
                    modifier = Modifier.weight(1f),
                )
                DashStatCard(
                    label = "المعروض",
                    value = listed.toString(),
                    icon = Icons.Default.Visibility,
                    accent = NebrasColors.amber,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DashStatCard(
                    label = "الأقسام الرئيسية",
                    value = snapshot.mainCount.toString(),
                    icon = Icons.Default.AccountTree,
                    modifier = Modifier.weight(1f),
                )
                DashStatCard(
                    label = "إجمالي المشاهدات",
                    value = snapshot.totalViewsLabel(totalViews),
                    icon = Icons.Default.Bolt,
                    accent = NebrasColors.amberLight,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DashStatCard(
                    label = "الأقسام الفرعية",
                    value = snapshot.subCount.toString(),
                    icon = Icons.Default.AccountTree,
                    modifier = Modifier.weight(1f),
                )
                DashStatCard(
                    label = "الأقسام الثانوية",
                    value = snapshot.secondaryCount.toString(),
                    icon = Icons.Default.AccountTree,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ── إجراءات سريعة ──
        item { DashSectionTitle(title = "إجراءات سريعة") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAction("رفع محتوى جديد", Icons.Default.CloudUpload) {
                    navController.navigate(DashboardRoutes.UPLOAD)
                }
                QuickAction("إدارة الأقسام", Icons.Default.AccountTree) {
                    navController.navigate(DashboardRoutes.SECTIONS)
                }
                QuickAction("دردشة الإدارة", Icons.Default.ChatBubbleOutline) {
                    navController.navigate(DashboardRoutes.CHAT)
                }
                QuickAction("بلاغات المحتوى", Icons.Default.Flag) {
                    navController.navigate(DashboardRoutes.REPORTS)
                }
                QuickAction("إشراف المجتمع", Icons.Default.Groups2) {
                    navController.navigate(DashboardRoutes.COMMUNITY)
                }
                QuickAction("تدقيق المحتوى", Icons.Default.FactCheck) {
                    navController.navigate(DashboardRoutes.CONTENT_AUDIT)
                }
            }
        }

        // ── أحدث المحتوى ──
        item { DashSectionTitle(title = "أحدث المحتوى") }
        items(snapshot.files.take(10), key = { it.id }) { row ->
            DashCard(onClick = { navController.navigate(DashboardRoutes.CONTENT_FILES) }) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = NebrasColors.textPrimary,
                    maxLines = 2,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(row.contentType)
                        if (row.viewCount > 0) append(" · ${row.viewCount} مشاهدة")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NebrasColors.textMuted,
                )
            }
        }
    }
}

/** تنسيق مختصر للأرقام الكبيرة (1.2 ألف بدل 1200). */
private fun DashboardSnapshot.totalViewsLabel(total: Int): String = when {
    total >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fم", total / 1_000_000.0)
    total >= 1_000 -> String.format(java.util.Locale.US, "%.1f ألف", total / 1_000.0)
    else -> total.toString()
}

@Composable
private fun QuickAction(title: String, icon: ImageVector, onClick: () -> Unit) {
    DashCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(
                icon,
                contentDescription = null,
                tint = NebrasColors.emerald,
                modifier = Modifier.height(20.dp),
            )
            Spacer(Modifier.fillMaxWidth(0f))
            Text(
                text = "   $title",
                style = MaterialTheme.typography.bodyMedium,
                color = NebrasColors.textPrimary,
            )
        }
    }
}
