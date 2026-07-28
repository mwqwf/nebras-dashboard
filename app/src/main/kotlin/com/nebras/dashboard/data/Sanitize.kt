package com.nebras.dashboard.data

// نظائر `nebrasUnifiedSanitize.js`.

/**
 * أقصى طول لحقل الوصف المخزَّن (بالأحرف) — يمنع تضخّم الوثيقة وانهيار قراءة
 * الجوال. مطابق لـ `MAX_CONTENT_DESCRIPTION_CHARS`.
 */
const val K_MAX_CONTENT_DESCRIPTION_CHARS: Int = 8000

/**
 * إزالة القيم `null` بعمق (نظير `stripUndefinedDeep` — نعامل `null`
 * كـ `undefined`: لا نكتبها إلى Firestore).
 */
fun stripNullsDeep(value: Any?): Any? {
    if (value is List<*>) {
        return value.mapNotNull { stripNullsDeep(it) }
    }
    if (value is Map<*, *>) {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in value) {
            val cleaned = stripNullsDeep(v)
            if (cleaned != null) out["$k"] = cleaned
        }
        return out
    }
    return value
}

/** نسخة مريحة ترجع خريطة جاهزة للكتابة في Firestore. */
@Suppress("UNCHECKED_CAST")
fun stripNullsDeepMap(value: Map<String, Any?>): Map<String, Any> =
    (stripNullsDeep(value) as Map<String, Any>)

private fun clampString(value: String, max: Int): String {
    if (value.length <= max) return value
    return value.substring(0, max).trimEnd() + "…"
}

/** يقصّ حقول الوصف الضخمة (`description` في الجذر وداخل `metadata`) قبل الكتابة. */
fun clampContentTextFields(payload: Map<String, Any?>): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>(payload)
    val desc = out["description"]
    if (desc is String) {
        out["description"] = clampString(desc, K_MAX_CONTENT_DESCRIPTION_CHARS)
    }
    val md = out["metadata"]
    if (md is Map<*, *>) {
        val m = LinkedHashMap<String, Any?>()
        for ((k, v) in md) m["$k"] = v
        val mdDesc = m["description"]
        if (mdDesc is String) {
            m["description"] = clampString(mdDesc, K_MAX_CONTENT_DESCRIPTION_CHARS)
        }
        out["metadata"] = m
    }
    return out
}
