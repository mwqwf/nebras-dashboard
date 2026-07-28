package com.nebras.dashboard.ui.content_audit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.nebras.dashboard.core.NebrasColors
import com.nebras.dashboard.data.ContentRepository
import com.nebras.dashboard.data.ContentRow
import com.nebras.dashboard.ui.widgets.DashBadge
import com.nebras.dashboard.ui.widgets.DashCard
import com.nebras.dashboard.ui.widgets.DashEmptyState
import com.nebras.dashboard.ui.widgets.DashErrorState
import com.nebras.dashboard.ui.widgets.DashLoading
import com.nebras.dashboard.ui.widgets.DashSectionTitle

/** عيب مكتشَف في وثيقة محتوى. */
private data class AuditIssue(val row: ContentRow, val problems: List<String>)

private data class AuditState(
    val issues: List<AuditIssue> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * تدقيق المحتوى — نقل `ui/content_audit/content_audit_screen.dart`.
 *
 * يكشف الوثائق التي **لن تظهر في التطبيق**: بلا رابط مصدر، أو بلا قسم
 * فرعيّ، أو بلا عنوان — وهي نفس الشروط التي يفرضها حارس
 * `ContentCompliance` في التطبيق.
 */
@Composable
fun ContentAuditScreen(navController: NavHostController) {
    val state by produceState(AuditState()) {
        value = runCatching { ContentRepository.listFiles() }
            .fold(
                onSuccess = { rows -> AuditState(issues = audit(rows), total = rows.size, loading = false) },
                onFailure = {
                    AuditState(loading = false, error = it.message ?: "تعذّر التدقيق")
                },
            )
    }

    when {
        state.loading -> {
            DashLoading()
            return
        }
        state.error != null -> {
            DashErrorState(state.error!!)
            return
        }
        state.issues.isEmpty() -> {
            DashEmptyState("لا توجد مشاكل — ${state.total} وثيقة سليمة ✅")
            return
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            DashSectionTitle(
                title = "مشاكل مكتشَفة (${state.issues.size})",
                subtitle = "من أصل ${state.total} وثيقة",
            )
        }
        items(state.issues, key = { it.row.id }) { issue ->
            DashCard {
                Text(
                    text = issue.row.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = NebrasColors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    issue.problems.forEach { problem ->
                        DashBadge(text = problem, color = NebrasColors.rose)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "المعرّف: ${issue.row.id}",
                    style = MaterialTheme.typography.labelSmall,
                    color = NebrasColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** نفس شروط ظهور المحتوى في التطبيق — أيّ إخلال يعني وثيقة لا تُعرض. */
private fun audit(rows: List<ContentRow>): List<AuditIssue> = rows.mapNotNull { row ->
    val problems = buildList {
        val url = row.fileUrl.trim()
        if (url.isEmpty()) {
            add("بلا رابط مصدر")
        } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
            add("رابط غير صالح")
        }
        if (row.title.isBlank()) add("بلا عنوان")
        val subsection = row.metadata["subsection"]?.toString()?.trim().orEmpty()
        if (subsection.isEmpty()) add("بلا قسم فرعي")
        if (row.uploadStatus != "completed") add("رفع غير مكتمل")
    }
    if (problems.isEmpty()) null else AuditIssue(row, problems)
}
