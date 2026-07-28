package com.nebras.dashboard.ui.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nebras.dashboard.core.NebrasColors
import kotlin.math.max

/** شريحة في مخطّط — تسمية وقيمة ولون. */
data class ChartSlice(val label: String, val value: Float, val color: Color)

/** لوحة ألوان المخطّطات — مشتقّة من هويّة اللوحة. */
val CHART_PALETTE = listOf(
    NebrasColors.emerald,
    NebrasColors.amber,
    Color(0xFF60A5FA),
    Color(0xFFA78BFA),
    NebrasColors.rose,
    Color(0xFF34D399),
    Color(0xFFFBBF24),
)

/**
 * مخطّط أعمدة أفقيّ — **بديل `fl_chart`** مرسوم بـ Compose `Canvas`.
 * أفقيّ لأنّ التسميات عربيّة طويلة ويصعب قراءتها مائلة تحت الأعمدة.
 */
@Composable
fun BarChart(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
    maxBars: Int = 8,
) {
    val data = slices.sortedByDescending { it.value }.take(maxBars)
    if (data.isEmpty()) return
    val maxValue = max(data.maxOf { it.value }, 1f)

    Column(modifier.fillMaxWidth()) {
        data.forEach { slice ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = slice.label,
                    modifier = Modifier.width(96.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = NebrasColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(NebrasColors.surfaceAlt),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(slice.value / maxValue)
                            .height(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(slice.color),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = slice.value.toInt().toString(),
                    modifier = Modifier.width(48.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = NebrasColors.textPrimary,
                )
            }
        }
    }
}

/** مخطّط حلقيّ (donut) لتوزيع الأنواع — بديل PieChart في fl_chart. */
@Composable
fun DonutChart(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
) {
    val data = slices.filter { it.value > 0f }
    if (data.isEmpty()) return
    val total = data.sumOf { it.value.toDouble() }.toFloat()

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(132.dp)) {
            val stroke = 26f
            val inset = stroke / 2f
            var startAngle = -90f
            data.forEach { slice ->
                val sweep = slice.value / total * 360f
                drawArc(
                    color = slice.color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke),
                )
                startAngle += sweep
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            data.forEach { slice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(slice.color))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${slice.label} (${slice.value.toInt()})",
                        style = MaterialTheme.typography.labelSmall,
                        color = NebrasColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** مخطّط خطّيّ بسيط للاتّجاه الزمنيّ. */
@Composable
fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = NebrasColors.emerald,
) {
    if (values.size < 2) return
    val maxValue = max(values.max(), 1f)

    Canvas(modifier.fillMaxWidth().height(120.dp)) {
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = stepX * index
            val y = size.height - (value / maxValue) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = color, style = Stroke(width = 3f))
    }
}
