package com.nebras.dashboard.core

// بحث عربيّ متسامح (تطبيع + مطابقة كل الكلمات) — نظير مبسَّط لـ `utils/search.js`.

private val HARAKAT = Regex("[ً-ْٰ]") // التشكيل + الألف الخنجريّة
private val ALEF_FORMS = Regex("[أإآٱ]") // أ إ آ ٱ
private val WHITESPACE = Regex("\\s+")

private fun normalizeArabic(input: String): String {
    var s = input.lowercase().trim()
    // إزالة التشكيل.
    s = HARAKAT.replace(s, "")
    // تطبيع الألف بأشكالها والهمزات.
    s = ALEF_FORMS.replace(s, "ا")
    s = s.replace('ى', 'ي') // ألف مقصورة → ياء
    s = s.replace('ة', 'ه') // تاء مربوطة → هاء
    s = s.replace("ـ", "") // تطويل
    return s
}

fun tokenize(query: String?): List<String> {
    val q = normalizeArabic(query ?: "")
    if (q.isEmpty()) return emptyList()
    return q.split(WHITESPACE).filter { it.isNotEmpty() }
}

/** هل تطابق كل الكلمات حقولَ العنصر (AND)؟ */
fun matchesAllTokens(tokens: List<String>, haystack: List<String>): Boolean {
    if (tokens.isEmpty()) return true
    val blob = normalizeArabic(haystack.joinToString("   "))
    for (t in tokens) {
        if (!blob.contains(t)) return false
    }
    return true
}

/** تصفية وترتيب بسيط حسب الصِلة. */
fun <T> filterAndRank(
    items: List<T>,
    tokens: List<String>,
    fields: (T) -> List<String>,
): List<T> {
    if (tokens.isEmpty()) return items.toList()
    val scored = ArrayList<Pair<T, Int>>()
    for (item in items) {
        val blob = normalizeArabic(fields(item).joinToString("   "))
        var ok = true
        var score = 0
        for (t in tokens) {
            val idx = blob.indexOf(t)
            if (idx < 0) {
                ok = false
                break
            }
            score += idx
        }
        if (ok) scored.add(item to score)
    }
    return scored.sortedBy { it.second }.map { it.first }
}
