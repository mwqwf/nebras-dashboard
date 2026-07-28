package com.nebras.dashboard.ui.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.nebras.dashboard.core.NebrasColors
import com.nebras.dashboard.data.SectionLevel
import com.nebras.dashboard.ui.content.SectionDropdown
import com.nebras.dashboard.ui.widgets.DashTextAction
import com.nebras.dashboard.ui.widgets.DashTextField

/** حوار إنشاء قسم في المستوى الحاليّ (مع اختيار الأب عند الحاجة). */
@Composable
fun CreateSectionDialog(
    level: SectionLevel,
    parents: List<Map<String, Any?>>,
    onDismiss: () -> Unit,
    onCreate: (name: String, parentId: Any?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf<Map<String, Any?>?>(null) }
    val needsParent = level != SectionLevel.main

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (level) {
                    SectionLevel.main -> "قسم رئيسي جديد"
                    SectionLevel.sub -> "قسم فرعي جديد"
                    SectionLevel.secondary -> "قسم ثانوي جديد"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DashTextField(value = name, onValueChange = { name = it }, label = "اسم القسم")
                if (needsParent) {
                    SectionDropdown(
                        label = if (level == SectionLevel.sub) {
                            "القسم الرئيسي"
                        } else {
                            "القسم الفرعي"
                        },
                        options = parents,
                        selected = parent,
                        onSelect = { parent = it },
                    )
                }
            }
        },
        confirmButton = {
            DashTextAction(
                text = "إنشاء",
                onClick = {
                    if (name.isBlank()) return@DashTextAction
                    if (needsParent && parent == null) return@DashTextAction
                    onCreate(name.trim(), parent?.get("id"))
                },
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

/** حوار تعديل اسم القسم. */
@Composable
fun EditSectionDialog(
    row: Map<String, Any?>,
    onDismiss: () -> Unit,
    onSave: (name: String) -> Unit,
) {
    var name by remember(row) { mutableStateOf(row["name"]?.toString().orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل القسم") },
        text = {
            DashTextField(value = name, onValueChange = { name = it }, label = "اسم القسم")
        },
        confirmButton = {
            DashTextAction(
                text = "حفظ",
                onClick = { if (name.isNotBlank()) onSave(name.trim()) },
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

/**
 * تأكيد الحذف — يوضّح صراحةً أنّ الحذف **تعاقبيّ شامل** (القسم + أبناؤه +
 * كلّ المحتوى تحته وملفّاته)، وهي دلالة ثابتة لا تُخفَّف.
 */
@Composable
fun DeleteSectionDialog(
    name: String,
    level: SectionLevel,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val scopeText = when (level) {
        SectionLevel.main ->
            "سيُحذف القسم «$name» وكلّ أقسامه الفرعية والثانوية، " +
                "وكلّ المحتوى تحتها مع ملفّاته من التخزين."
        SectionLevel.sub ->
            "سيُحذف القسم «$name» وكلّ أقسامه الثانوية، " +
                "وكلّ المحتوى تحتها مع ملفّاته من التخزين."
        SectionLevel.secondary ->
            "سيُحذف القسم «$name» وكلّ المحتوى تحته مع ملفّاته من التخزين."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("حذف تعاقبيّ") },
        text = { Text("$scopeText\n\nلا يمكن التراجع عن هذا الإجراء.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("حذف نهائياً", color = NebrasColors.rose)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
