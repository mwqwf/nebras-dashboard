package com.nebras.dashboard.data

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * نماذج دردشة الإدارة الجماعيّة — نظير الجزء العلوي من `chat_repository.dart`.
 *
 * فُصلت عن [ChatRepository] لأنّ الواجهة تستهلكها وحدها (فقاعات الرسائل،
 * صفحة معلومات المجموعة، حوار الملفّ الشخصي) دون أن تلمس طبقة Firestore.
 *
 * مسارات المجموعات معرَّفة في `core/FirestoreRefs.kt` (ChatPaths) — لا تُعاد
 * هنا كي لا يتكرّر التعريف.
 */

/** نافذة اعتبار العضو «متصلاً الآن»: آخر نبضة حضور خلال هذه المدّة. */
val kOnlineWindow: Duration = Duration.ofSeconds(95)

/** نافذة اعتبار العضو «يكتب الآن…». */
val kTypingWindow: Duration = Duration.ofSeconds(6)

/** التفاعلات السريعة (مطابقة لواتساب). */
val kQuickReactions: List<String> = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/**
 * نوع الرسالة — يحدّد شكل الفقاعة في الواجهة.
 * [wire] هو النصّ المخزَّن في Firestore (نظير `enum.name` في Dart) — لا تغيّره.
 */
enum class ChatMessageType(val wire: String) {
    TEXT("text"),
    IMAGE("image"),
    VIDEO("video"),
    AUDIO("audio"),
    VOICE("voice"),
    FILE("file"),
}

fun chatTypeFromString(s: String?): ChatMessageType = when (s) {
    "image" -> ChatMessageType.IMAGE
    "video" -> ChatMessageType.VIDEO
    "audio" -> ChatMessageType.AUDIO
    "voice" -> ChatMessageType.VOICE
    "file" -> ChatMessageType.FILE
    else -> ChatMessageType.TEXT
}

fun chatTypeToString(t: ChatMessageType): String = t.wire

/** تسمية عربيّة مختصرة للنوع (تظهر في معاينة الردّ/التثبيت). */
fun chatTypeLabel(t: ChatMessageType): String = when (t) {
    ChatMessageType.TEXT -> "رسالة"
    ChatMessageType.IMAGE -> "📷 صورة"
    ChatMessageType.VIDEO -> "🎬 فيديو"
    ChatMessageType.AUDIO -> "🎵 مقطع صوتي"
    ChatMessageType.VOICE -> "🎙️ رسالة صوتيّة"
    ChatMessageType.FILE -> "📎 ملفّ"
}

// ─── مساعدات فكّ الوثائق (نظير `'${d['x'] ?? ''}'` في Dart) ──────────────

/** نظير `m.cast<String, dynamic>()` — أيّ خريطة من Firestore مفاتيحها نصوص. */
@Suppress("UNCHECKED_CAST")
private fun asStringMap(v: Any?): Map<String, Any?>? =
    if (v is Map<*, *>) v as Map<String, Any?> else null

/** نظير `'${v ?? fallback}'`: القيمة null وحدها تسقط إلى البديل. */
private fun txt(v: Any?, fallback: String = ""): String = v?.toString() ?: fallback

/** نظير `v is num ? v.toInt() : 0`. */
private fun msOf(v: Any?): Long = if (v is Number) v.toLong() else 0L

/** مرفق رسالة (صورة/فيديو/صوت/ملفّ) مرفوع إلى Storage. */
data class ChatAttachment(
    val url: String,
    /** مسار Storage — يلزم لحذف الملفّ عند «حذف عند الجميع». */
    val path: String,
    val name: String,
    val size: Long,
    val contentType: String,
    /** للرسائل الصوتيّة. */
    val durationMs: Long? = null,
) {
    fun toMap(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["url"] = url
        m["path"] = path
        m["name"] = name
        m["size"] = size
        m["contentType"] = contentType
        // الحقل يُكتب فقط عند وجوده (نظير `if (durationMs != null)` في Dart).
        if (durationMs != null) m["durationMs"] = durationMs
        return m
    }

    companion object {
        fun fromMap(m: Any?): ChatAttachment? {
            val map = asStringMap(m) ?: return null
            val url = txt(map["url"], "")
            if (url.isEmpty()) return null
            return ChatAttachment(
                url = url,
                path = txt(map["path"], ""),
                name = txt(map["name"], "ملفّ"),
                size = (map["size"] as? Number)?.toLong() ?: 0L,
                contentType = txt(map["contentType"], "application/octet-stream"),
                durationMs = (map["durationMs"] as? Number)?.toLong(),
            )
        }
    }
}

/** مقتطف رسالة مُشار إليها (ردّ أو تثبيت) — يُخزَّن ذاتيّاً داخل الوثيقة. */
data class ChatReplyRef(
    val messageId: String,
    val senderId: String,
    val senderName: String,
    val preview: String,
    val type: ChatMessageType,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "messageId" to messageId,
        "senderId" to senderId,
        "senderName" to senderName,
        "preview" to preview,
        "type" to chatTypeToString(type),
    )

    companion object {
        fun fromMap(m: Any?): ChatReplyRef? {
            val map = asStringMap(m) ?: return null
            val id = txt(map["messageId"], "")
            if (id.isEmpty()) return null
            return ChatReplyRef(
                messageId = id,
                senderId = txt(map["senderId"], ""),
                senderName = txt(map["senderName"], ""),
                preview = txt(map["preview"], ""),
                type = chatTypeFromString(txt(map["type"], "text")),
            )
        }
    }
}

/** رسالة دردشة. */
data class ChatMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val senderPhoto: String,
    val type: ChatMessageType,
    val text: String,
    val attachment: ChatAttachment?,
    val replyTo: ChatReplyRef?,
    val createdAt: Instant,
    val sentAtMs: Long,
    val deleted: Boolean,
    val deletedBy: String,
    val hiddenFor: List<String>,
    /** تفاعلات الإيموجي: {uid: emoji}. */
    val reactions: Map<String, String>,
    /** كتابة محليّة لم يؤكّدها الخادم بعد. */
    val pending: Boolean,
) {
    val isMine: Boolean
        get() = senderId == FirebaseAuth.getInstance().currentUser?.uid

    /** وقت الإرسال بتوقيت الجهاز — للعرض والتنسيق (نظير `DateTime` المحلّي). */
    val createdAtLocal: LocalDateTime
        get() = LocalDateTime.ofInstant(createdAt, ZoneId.systemDefault())

    /** نصّ معاينة مختصر (للردود والتثبيت). */
    val preview: String
        get() {
            if (deleted) return "رسالة محذوفة"
            if (type == ChatMessageType.TEXT) {
                return if (text.length > 90) "${text.substring(0, 90)}…" else text
            }
            val label = chatTypeLabel(type)
            val name = attachment?.name ?: ""
            return if (type == ChatMessageType.VOICE || name.isEmpty()) label else "$label $name"
        }

    fun asRef(): ChatReplyRef = ChatReplyRef(
        messageId = id,
        senderId = senderId,
        senderName = senderName,
        preview = preview,
        type = type,
    )

    companion object {
        fun fromDoc(doc: DocumentSnapshot): ChatMessage {
            val d: Map<String, Any?> = doc.data ?: emptyMap()
            val ts = d["createdAt"]
            val sentAtMs = (d["sentAtMs"] as? Number)?.toLong() ?: System.currentTimeMillis()
            val reactions = LinkedHashMap<String, String>()
            asStringMap(d["reactions"])?.forEach { (k, v) ->
                val e = txt(v, "")
                if (e.isNotEmpty()) reactions[k] = e
            }
            return ChatMessage(
                id = doc.id,
                senderId = txt(d["senderId"], ""),
                senderName = txt(d["senderName"], "مستخدم"),
                senderPhoto = txt(d["senderPhoto"], ""),
                type = chatTypeFromString(txt(d["type"], "text")),
                text = txt(d["text"], ""),
                attachment = ChatAttachment.fromMap(d["att"]),
                replyTo = ChatReplyRef.fromMap(d["replyTo"]),
                // الكتابات المعلَّقة (serverTimestamp لم يصل بعد) تعود لوقت الإرسال المحلّي.
                createdAt = if (ts is Timestamp) ts.toDate().toInstant()
                else Instant.ofEpochMilli(sentAtMs),
                sentAtMs = sentAtMs,
                deleted = d["deleted"] == true,
                deletedBy = txt(d["deletedBy"], ""),
                hiddenFor = (d["hiddenFor"] as? List<*>)?.map { txt(it, "") } ?: emptyList(),
                reactions = reactions,
                pending = doc.metadata.hasPendingWrites(),
            )
        }
    }
}

/** عضو في المجموعة. */
data class ChatMember(
    val uid: String,
    /** اسم Google. */
    val name: String,
    /** اسم مختار داخل المجموعة (له الأولويّة). */
    val customName: String,
    val email: String,
    /** صورة Google. */
    val photo: String,
    /** صورة شخصيّة مختارة داخل المجموعة (لها الأولويّة). */
    val customPhoto: String,
    /** owner أو supervisor (دور التطبيق). */
    val role: String,
    /** '' أو moderator (دور داخل المجموعة فقط). */
    val chatRole: String,
    /** أكمل إعداد الملفّ الشخصي لأوّل مرّة. */
    val profileSet: Boolean,
    val lastSeenAt: Instant? = null,
    val lastActiveAtMs: Long = 0L,
    val lastReadAtMs: Long = 0L,
    val typingAtMs: Long = 0L,
) {
    val isOwner: Boolean get() = role == "owner"

    /** مشرف مجموعة: صلاحيّات إشراف داخل الدردشة فقط (لا تمسّ التطبيق). */
    val isChatModerator: Boolean get() = chatRole == "moderator"

    /** الاسم المعروض في المجموعة: المختار أولاً ثم اسم Google. */
    val displayName: String get() = if (customName.isNotEmpty()) customName else name

    /** الصورة المعروضة في المجموعة: المخصّصة أولاً ثم صورة Google. */
    val displayPhoto: String get() = if (customPhoto.isNotEmpty()) customPhoto else photo

    /** آخر ظهور بتوقيت الجهاز — للعرض في صفحة معلومات المجموعة. */
    val lastSeenAtLocal: LocalDateTime?
        get() = lastSeenAt?.let { LocalDateTime.ofInstant(it, ZoneId.systemDefault()) }

    val isOnline: Boolean
        get() = System.currentTimeMillis() - lastActiveAtMs < kOnlineWindow.toMillis()

    val isTyping: Boolean
        get() = System.currentTimeMillis() - typingAtMs < kTypingWindow.toMillis()

    companion object {
        fun fromDoc(doc: DocumentSnapshot): ChatMember {
            val d: Map<String, Any?> = doc.data ?: emptyMap()
            val ts = d["lastSeenAt"]
            return ChatMember(
                uid = doc.id,
                name = txt(d["name"], "مستخدم"),
                customName = txt(d["customName"], ""),
                email = txt(d["email"], ""),
                photo = txt(d["photo"], ""),
                customPhoto = txt(d["customPhoto"], ""),
                role = txt(d["role"], "supervisor"),
                chatRole = txt(d["chatRole"], ""),
                profileSet = d["profileSet"] == true,
                lastSeenAt = if (ts is Timestamp) ts.toDate().toInstant() else null,
                lastActiveAtMs = msOf(d["lastActiveAtMs"]),
                lastReadAtMs = msOf(d["lastReadAtMs"]),
                typingAtMs = msOf(d["typingAtMs"]),
            )
        }
    }
}

/** هويّة المجموعة (يكتبها المالك وحده). */
data class ChatGroupMeta(
    val name: String,
    val photoUrl: String,
    /** عند القفل لا يرسل غير المالك (نمط «إعلانات» في واتساب). */
    val locked: Boolean,
    /** الرسالة المثبَّتة (إن وُجدت). */
    val pinned: ChatReplyRef?,
) {
    companion object {
        val fallback: ChatGroupMeta = ChatGroupMeta(
            name = "مجموعة إدارة نبراس",
            photoUrl = "",
            locked = false,
            pinned = null,
        )

        fun fromDoc(doc: DocumentSnapshot): ChatGroupMeta {
            val d: Map<String, Any?> = doc.data ?: emptyMap()
            val name = txt(d["name"], "").trim()
            return ChatGroupMeta(
                name = if (name.isEmpty()) fallback.name else name,
                photoUrl = txt(d["photoUrl"], ""),
                locked = d["locked"] == true,
                pinned = ChatReplyRef.fromMap(d["pinned"]),
            )
        }
    }
}

/** تخمين MIME من الامتداد (لاختيار نوع الفقاعة وقاعدة Storage الملائمة). */
fun guessContentType(filename: String): String {
    val ext = if (filename.contains('.')) filename.substringAfterLast('.').lowercase() else ""
    return when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "pdf" -> "application/pdf"
        "epub" -> "application/epub+zip"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "doc" -> "application/msword"
        "docx" ->
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" ->
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" ->
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}

/** نوع الرسالة الملائم لمرفق بحسب MIME. */
fun chatTypeForMime(contentType: String): ChatMessageType {
    if (contentType.startsWith("image/")) return ChatMessageType.IMAGE
    if (contentType.startsWith("video/")) return ChatMessageType.VIDEO
    if (contentType.startsWith("audio/")) return ChatMessageType.AUDIO
    return ChatMessageType.FILE
}

/** تنسيق حجم ملفّ للعرض. */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = listOf("بايت", "ك.ب", "م.ب", "غ.ب")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    // أرقام غربيّة دائماً (نظير `toStringAsFixed` في Dart المستقلّ عن اللغة).
    val digits = if (v >= 100 || i == 0) 0 else 1
    return "${String.format(Locale.US, "%.${digits}f", v)} ${units[i]}"
}

/** حِمل زائد للراحة: أحجام Int (نتائج `ByteArray.size` مثلاً). */
fun formatBytes(bytes: Int): String = formatBytes(bytes.toLong())
