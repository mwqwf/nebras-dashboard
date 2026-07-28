package com.nebras.dashboard.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebras.dashboard.core.NebrasColors
import com.nebras.dashboard.core.NebrasDims
import org.json.JSONArray
import org.json.JSONObject

/**
 * عارض JSON للتشخيص — نقل `ui/widgets/json_view.dart`.
 * يُظهر الوثيقة الخام كما هي في Firestore لتشخيص أعطال الحقول بسرعة.
 */
@Composable
fun JsonView(
    value: Any?,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val pretty = remember(value) { prettyPrint(value) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(NebrasDims.fieldShape)
            .background(NebrasColors.surfaceAlt)
            .padding(12.dp),
    ) {
        Text(
            text = pretty,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            ),
            color = NebrasColors.textMuted,
            maxLines = maxLines,
        )
    }
}

/** تنسيق مقروء بمسافات بادئة — يتعامل مع الخرائط والقوائم والقيم البسيطة. */
private fun prettyPrint(value: Any?): String = runCatching {
    when (value) {
        null -> "null"
        is JSONObject -> value.toString(2)
        is JSONArray -> value.toString(2)
        is Map<*, *> -> JSONObject(value.mapKeys { it.key.toString() }).toString(2)
        is List<*> -> JSONArray(value).toString(2)
        else -> value.toString()
    }
}.getOrElse { value.toString() }
