package com.nebras.dashboard.data

import kotlin.random.Random

/**
 * نظائر `nebrasMobileUploadSchema.js` — حقول مرآة Firestore المتوافقة مع
 * تطبيق نبراس (Content.fromJson) وترتيب «الأقدم أوّلاً».
 */

private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

/** معرّف ملفّ بصيغة `fb_<epoch>_…` ليتوافق ترتيب التطبيق (الأقدم أولاً). */
fun generateNebrasFileId(): String {
    val sb = StringBuilder(8)
    repeat(8) { sb.append(ID_CHARS[Random.nextInt(ID_CHARS.length)]) }
    return "fb_${System.currentTimeMillis()}_$sb"
}

/**
 * يبني الحقول المتوافقة مع الجوال من الميتاداتا ورابط التنزيل.
 * المفاتيح ذات القيمة null تُحذف لاحقاً عبر [stripNullsDeep].
 */
fun buildMobileCompatibleFields(
    metadata: Map<String, Any?>,
    downloadUrl: String,
): Map<String, Any?> {
    val contentTypeRaw = (metadata["content_type"] ?: "document").toString().trim().lowercase()
    val contentType = if (contentTypeRaw.isEmpty()) "document" else contentTypeRaw

    val sourceFields = LinkedHashMap<String, Any?>()
    sourceFields["sourceUrl"] = downloadUrl
    sourceFields["source_url"] = downloadUrl
    sourceFields["file_url"] = downloadUrl
    if (contentType == "audio") sourceFields["audio_url"] = downloadUrl
    if (contentType == "video") sourceFields["video_url"] = downloadUrl

    val out = LinkedHashMap<String, Any?>()
    out["id"] = metadata["id"]
    out["title"] = metadata["title"]
    out["description"] = metadata["description"]
    out["author"] = metadata["author"]
    out["thumbnail"] = metadata["thumbnail"]
    out["content_type"] = metadata["content_type"]
    out["subsection"] = metadata["subsection"]
    out["subsection_name"] = metadata["subsection_name"] ?: metadata["subsection_title"]
    out["secondary_subsection"] = metadata["secondary_subsection"]
    out["secondary_subsection_name"] =
        metadata["secondary_subsection_name"] ?: metadata["secondary_subsection_title"]
    out["main_section"] = metadata["main_section"]
    out["main_section_id"] = metadata["main_section_id"]
    out["main_section_name"] = metadata["main_section_name"]
    out.putAll(sourceFields)
    return out
}
