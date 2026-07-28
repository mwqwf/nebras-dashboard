package com.nebras.dashboard.ui.honor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.nebras.dashboard.core.NebrasColors
import com.nebras.dashboard.data.AdminStats
import com.nebras.dashboard.ui.widgets.DashCard
import com.nebras.dashboard.ui.widgets.DashEmptyState
import com.nebras.dashboard.ui.widgets.DashSectionTitle

/**
 * لوحة الشرف — نقل `ui/honor/honor_board_screen.dart`.
 * ترتيب المشرفين بحسب الرفع والتعديل في الأسبوع الحاليّ.
 */
@Composable
fun HonorBoardScreen(navController: NavHostController) {
    val board by AdminStats.boardFlow().collectAsStateWithLifecycle(initialValue = emptyList())

    if (board.isEmpty()) {
        DashEmptyState("لا توجد مساهمات هذا الأسبوع بعد")
        return
    }

    val ranked = board.sortedByDescending { score(it) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { DashSectionTitle(title = "لوحة الشرف — هذا الأسبوع") }

        itemsIndexed(ranked, key = { _, row -> "${row["uid"] ?: row["email"]}" }) { index, row ->
            DashCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(medalColor(index).copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = medalLabel(index),
                            style = MaterialTheme.typography.labelLarge,
                            color = medalColor(index),
                        )
                    }
                    Spacer(Modifier.width(12.dp))

                    val photo = row["photoUrl"]?.toString().orEmpty()
                    if (photo.isNotEmpty()) {
                        AsyncImage(
                            model = photo,
                            contentDescription = null,
                            modifier = Modifier.size(38.dp).clip(CircleShape),
                        )
                        Spacer(Modifier.width(10.dp))
                    }

                    Column(Modifier.weight(1f)) {
                        Text(
                            text = row["name"]?.toString()
                                ?: row["email"]?.toString().orEmpty(),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = NebrasColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${count(row, "uploads")} رفع · " +
                                "${count(row, "edits")} تعديل",
                            style = MaterialTheme.typography.labelSmall,
                            color = NebrasColors.textMuted,
                        )
                    }

                    Text(
                        text = score(row).toString(),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = NebrasColors.emerald,
                    )
                }
            }
        }
    }
}

/** النقاط: الرفع يساوي ضعف التعديل (نفس ترجيح نسخة Flutter). */
private fun score(row: Map<String, Any?>): Int =
    count(row, "uploads") * 2 + count(row, "edits")

private fun count(row: Map<String, Any?>, key: String): Int =
    (row[key] as? Number)?.toInt() ?: 0

private fun medalLabel(index: Int): String = when (index) {
    0 -> "🥇"
    1 -> "🥈"
    2 -> "🥉"
    else -> "${index + 1}"
}

private fun medalColor(index: Int) = when (index) {
    0 -> NebrasColors.amber
    1 -> NebrasColors.textMuted
    2 -> NebrasColors.amberLight
    else -> NebrasColors.emerald
}
