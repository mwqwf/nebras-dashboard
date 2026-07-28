package com.nebras.dashboard.ui.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.nebras.dashboard.core.NebrasColors
import com.nebras.dashboard.data.ContentRepository
import com.nebras.dashboard.data.ContentRow
import com.nebras.dashboard.ui.widgets.DashCard
import com.nebras.dashboard.ui.widgets.DashEmptyState
import com.nebras.dashboard.ui.widgets.DashErrorState
import com.nebras.dashboard.ui.widgets.DashLoading
import com.nebras.dashboard.ui.widgets.DashSectionTitle
import com.nebras.dashboard.ui.widgets.DashStatCard

private data class StatsState(
    val rows: List<ContentRow> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * الإحصاءات — نقل `ui/statistics/statistics_screen.dart`.
 * أرقام إجماليّة + توزيع الأنواع + أعلى المحتوى مشاهدةً.
 */
@Composable
fun StatisticsScreen(navController: NavHostController) {
    val state by produceState(StatsState()) {
        value = runCatching { ContentRepository.listFiles() }
            .fold(
                onSuccess = { StatsState(rows = it, loading = false) },
                onFailure = {
                    StatsState(loading = false, error = it.message ?: "تعذّر تحميل الإحصاءات")
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
        state.rows.isEmpty() -> {
            DashEmptyState("لا توجد بيانات بعد")
            return
        }
    }

    val rows = state.rows
    val totalViews = rows.sumOf { it.viewCount }
    val totalPlays = rows.sumOf { it.playCount }
    val totalCompletes = rows.sumOf { it.completeCount }

    val byType = rows.groupBy { it.contentType }
        .map { (type, list) -> type to list.size }
        .sortedByDescending { it.second }

    val typeSlices = byType.mapIndexed { index, (type, count) ->
        ChartSlice(
            label = arabicType(type),
            value = count.toFloat(),
            color = CHART_PALETTE[index % CHART_PALETTE.size],
        )
    }

    val topViewed = rows.filter { it.viewCount > 0 }
        .sortedByDescending { it.viewCount }
        .take(8)
    val viewSlices = topViewed.mapIndexed { index, row ->
        ChartSlice(
            label = row.title,
            value = row.viewCount.toFloat(),
            color = CHART_PALETTE[index % CHART_PALETTE.size],
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DashStatCard(
                    label = "إجمالي المحتوى",
                    value = rows.size.toString(),
                    icon = Icons.Default.FolderOpen,
                    modifier = Modifier.weight(1f),
                )
                DashStatCard(
                    label = "المشاهدات",
                    value = totalViews.toString(),
                    icon = Icons.Default.Visibility,
                    accent = NebrasColors.amber,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DashStatCard(
                    label = "مرّات التشغيل",
                    value = totalPlays.toString(),
                    icon = Icons.Default.PlayArrow,
                    modifier = Modifier.weight(1f),
                )
                DashStatCard(
                    label = "مرّات الإكمال",
                    value = totalCompletes.toString(),
                    icon = Icons.Default.TaskAlt,
                    accent = NebrasColors.amberLight,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item { DashSectionTitle(title = "توزيع الأنواع") }
        item { DashCard { DonutChart(slices = typeSlices) } }

        if (viewSlices.isNotEmpty()) {
            item { DashSectionTitle(title = "الأكثر مشاهدة") }
            item { DashCard { BarChart(slices = viewSlices) } }
        }

        item { DashSectionTitle(title = "تفاصيل الأنواع") }
        items(byType) { (type, count) ->
            DashCard {
                Row {
                    Text(
                        text = arabicType(type),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NebrasColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NebrasColors.emerald,
                    )
                }
            }
        }
    }
}

/** تسمية عربيّة لنوع المحتوى — نفس نصوص نسخة Flutter. */
private fun arabicType(type: String): String = when (type) {
    "document" -> "مستندات"
    "audio" -> "صوت"
    "video" -> "فيديو"
    "image" -> "صور"
    "article" -> "مقالات"
    "youtube" -> "يوتيوب"
    else -> type
}
