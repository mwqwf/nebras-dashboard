package com.nebras.dashboard.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.storage.FirebaseStorage
import com.nebras.dashboard.core.ApiClient
import com.nebras.dashboard.core.filterAndRank
import com.nebras.dashboard.core.tokenize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

fun contentTypeFromMime(mime: String?): String {
    if (mime == null || mime.isEmpty()) return "document"
    if (mime.startsWith("video/")) return "video"
    if (mime.startsWith("audio/")) return "audio"
    return "document"
}

/** صفّ محتوى موحَّد بالشكل الذي تعرضه الواجهة (نظير ناتج `listMyFiles`). */
class ContentRow(val raw: Map<String, Any?>) {

    val id: String get() = "${raw["id"]}"
    val filename: String get() = "${raw["filename"] ?: "untitled"}"
    val fileUrl: String get() = "${raw["file_url"] ?: ""}"
    val fileType: String get() = "${raw["file_type"] ?: ""}"
    val uploadType: String get() = "${raw["upload_type"] ?: "firebase"}"
    val uploadStatus: String get() = "${raw["upload_status"] ?: "completed"}"
    val fileSize: Long get() = (raw["file_size"] as? Number)?.toLong() ?: 0L
    val viewCount: Int get() = (raw["view_count"] as? Number)?.toInt() ?: 0
    val playCount: Int get() = (raw["play_count"] as? Number)?.toInt() ?: 0
    val completeCount: Int get() = (raw["complete_count"] as? Number)?.toInt() ?: 0
    val hasEngagement: Boolean get() = viewCount > 0 || playCount > 0 || completeCount > 0
    val createdAt: Any? get() = metadata["created_at"]
    val metadata: Map<String, Any?> get() = asStringKeyMap(raw["metadata"]) ?: emptyMap()
    val title: String get() = "${metadata["title"] ?: filename}"
    val author: String get() = "${metadata["author"] ?: ""}"
    val description: String get() = "${metadata["description"] ?: ""}"
    val contentType: String get() = "${metadata["content_type"] ?: "document"}"

    val thumbnail: String?
        get() {
            val t = metadata["thumbnail"] ?: raw["thumbnail"]
            val s = "${t ?: ""}".trim()
            return if (s.isEmpty()) null else s
        }

    val isListed: Boolean get() = (metadata["is_listed"] ?: true) == true
}

/** تحويل قيمة `created_at` إلى ميلي-ثانية للترتيب (Timestamp/نصّ ISO/رقم). */
private fun createdAtMillis(v: Any?): Long = when (v) {
    is Timestamp -> v.toDate().time
    is String -> parseDateMillis(v) ?: 0L
    is Number -> v.toLong()
    else -> 0L
}

private fun parseDateMillis(value: String): Long? {
    val s = value.trim()
    if (s.isEmpty()) return null
    runCatching { return Instant.parse(s).toEpochMilli() }
    runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
    runCatching {
        return LocalDateTime.parse(s.replace(' ', 'T'))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    runCatching {
        return LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
    }
    return null
}

object ContentRepository {

    private val fs = UnifiedFirestore

    // ─── قائمة المحتوى ──────────────────────────────────────

    suspend fun listFiles(
        search: String = "",
        mainSection: Any? = null,
        subsection: Any? = null,
        secondarySubsection: Any? = null,
        contentType: String? = null,
        isListed: Boolean? = null,
    ): List<ContentRow> {
        val rows = fs.listFileRowsMerged()
        val subMap = fs.readSectionsSubMap()

        var list: List<Map<String, Any?>> = rows.map { item ->
            val md = asStringKeyMap(item["metadata"]) ?: emptyMap()
            val createdAt = md["created_at"] ?: item["createdAt"]

            fun eng(a: String, b: String): Int {
                val v = item[a] ?: item[b] ?: md[a] ?: md[b]
                val n = if (v is Number) v.toInt() else ("$v".toIntOrNull() ?: 0)
                return if (n > 0) n else 0
            }

            val mergedMeta = LinkedHashMap<String, Any?>(md)
            mergedMeta["created_at"] = createdAt

            linkedMapOf<String, Any?>(
                "id" to (item["fileId"] ?: item["id"]),
                "filename" to (item["filename"] ?: "untitled"),
                "file_type" to (item["fileType"] ?: item["file_type"] ?: ""),
                "file_size" to (item["fileSize"] ?: item["file_size"] ?: 0),
                "file_url" to (item["downloadUrl"] ?: item["file_url"] ?: ""),
                "upload_type" to (item["upload_type"] ?: "firebase"),
                "upload_status" to (item["upload_status"] ?: item["uploadStatus"] ?: "completed"),
                "thumbnail" to (item["thumbnail"] ?: md["thumbnail"]),
                "view_count" to eng("view_count", "viewCount"),
                "play_count" to eng("play_count", "playCount"),
                "complete_count" to eng("complete_count", "completeCount"),
                "metadata" to mergedMeta,
            )
        }

        val tokens = tokenize(search)
        if (tokens.isNotEmpty()) {
            list = filterAndRank(list, tokens) { item ->
                val md = asStringKeyMap(item["metadata"]) ?: emptyMap()
                listOf(
                    "${md["title"] ?: ""}",
                    "${md["description"] ?: ""}",
                    "${md["author"] ?: ""}",
                    "${item["filename"] ?: ""}",
                    "${item["file_url"] ?: ""}",
                )
            }
        }

        if (subsection != null && "$subsection".isNotEmpty()) {
            list = list.filter {
                sameSectionId((asStringKeyMap(it["metadata"]) ?: emptyMap())["subsection"], subsection)
            }
        }
        if (secondarySubsection != null && "$secondarySubsection".isNotEmpty()) {
            list = list.filter {
                sameSectionId(
                    (asStringKeyMap(it["metadata"]) ?: emptyMap())["secondary_subsection"],
                    secondarySubsection,
                )
            }
        }
        if (mainSection != null && "$mainSection".isNotEmpty()) {
            list = list.filter {
                val subId = (asStringKeyMap(it["metadata"]) ?: emptyMap())["subsection"]
                val sub = asStringKeyMap(subMap["$subId"])
                sub != null && sameSectionId(sub["main_section"], mainSection)
            }
        }
        if (contentType != null && contentType.isNotEmpty()) {
            list = list.filter {
                "${(asStringKeyMap(it["metadata"]) ?: emptyMap())["content_type"] ?: ""}" == contentType
            }
        }
        if (isListed != null) {
            list = list.filter {
                ((asStringKeyMap(it["metadata"]) ?: emptyMap())["is_listed"] ?: true) == isListed
            }
        }

        fun ts(item: Map<String, Any?>): Long =
            createdAtMillis((asStringKeyMap(item["metadata"]) ?: emptyMap())["created_at"])

        return list.sortedWith { a, b -> ts(b).compareTo(ts(a)) }.map { ContentRow(it) }
    }

    // ─── إنشاء محتوى جديد (رفع) ─────────────────────────────

    /**
     * يرفع الملفّ إلى Storage ثم يكتب الوثيقة في المجموعتين (مرآة).
     * [metadata] يجب أن يحوي: title, content_type, main_section/subsection… إلخ.
     */
    suspend fun createContent(
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        metadata: Map<String, Any?>,
        thumbBytes: ByteArray? = null,
        thumbName: String? = null,
        onProgress: ((Double) -> Unit)? = null,
        isAborted: (() -> Boolean)? = null,
    ): String {
        val prepared = prepareUpload(
            bytes = bytes,
            filename = filename,
            mimeType = mimeType,
            metadata = metadata,
            thumbBytes = thumbBytes,
            thumbName = thumbName,
            onProgress = onProgress,
            isAborted = isAborted,
        )
        commitPrepared(prepared)
        return "${prepared["fileId"]}"
    }

    /**
     * المرحلة 1 من الرفع المتعدّد: رفع الملفّ (والصورة) إلى Storage فقط، وإرجاع
     * الحمولة الجاهزة للكتابة — **دون** كتابة Firestore (تتمّ لاحقاً بترتيب
     * الاختيار عبر [commitPrepared]). يطابق سلوك الويب: رفع متوازٍ + كتابة
     * القاعدة بترتيب الاختيار حرفيّاً.
     */
    suspend fun prepareUpload(
        bytes: ByteArray,
        filename: String,
        mimeType: String,
        metadata: Map<String, Any?>,
        thumbBytes: ByteArray? = null,
        thumbName: String? = null,
        onProgress: ((Double) -> Unit)? = null,
        isAborted: (() -> Boolean)? = null,
    ): Map<String, Any?> {
        val fileId = generateNebrasFileId()

        var thumbUrl: String? = null
        if (thumbBytes != null) {
            val tRes = smartUpload(
                bytes = thumbBytes,
                filename = "thumbnail_${System.currentTimeMillis()}_${thumbName ?: "thumb"}",
                folder = "dashboard/content/$fileId",
                contentType = "image/jpeg",
            )
            thumbUrl = tRes.url
        }

        val res = smartUpload(
            bytes = bytes,
            filename = filename,
            folder = "dashboard/content/$fileId",
            contentType = if (mimeType.isEmpty()) "application/octet-stream" else mimeType,
            onProgress = onProgress,
            isAborted = isAborted,
        )

        val meta = LinkedHashMap<String, Any?>(metadata)
        if (thumbUrl != null) meta["thumbnail"] = thumbUrl
        meta["content_type"] = meta["content_type"] ?: contentTypeFromMime(mimeType)

        val compatible = buildMobileCompatibleFields(meta, res.url)
        val out = LinkedHashMap<String, Any?>()
        out["fileId"] = fileId
        out["id"] = fileId
        out["downloadUrl"] = res.url
        out["filename"] = filename
        out["fileType"] = mimeType
        out["fileSize"] = bytes.size.toLong()
        out["metadata"] = meta
        out["storagePath"] = res.path
        out["createdAt"] = FieldValue.serverTimestamp()
        out.putAll(compatible)
        return out
    }

    /**
     * مثل [prepareUpload] لكن يرفع من مسار ملفّ (بثّ) بدل بايتات في الذاكرة —
     * آمن للملفّات الكبيرة (يتفادى OOM).
     */
    suspend fun prepareUploadPath(
        filePath: String,
        filename: String,
        mimeType: String,
        metadata: Map<String, Any?>,
        thumbBytes: ByteArray? = null,
        thumbName: String? = null,
        onProgress: ((Double) -> Unit)? = null,
        isAborted: (() -> Boolean)? = null,
    ): Map<String, Any?> {
        val fileId = generateNebrasFileId()

        var thumbUrl: String? = null
        if (thumbBytes != null) {
            val tRes = smartUpload(
                bytes = thumbBytes,
                filename = "thumbnail_${System.currentTimeMillis()}_${thumbName ?: "thumb"}",
                folder = "dashboard/content/$fileId",
                contentType = "image/jpeg",
            )
            thumbUrl = tRes.url
        }

        val res = smartUploadFile(
            file = File(filePath),
            filename = filename,
            folder = "dashboard/content/$fileId",
            contentType = if (mimeType.isEmpty()) "application/octet-stream" else mimeType,
            onProgress = onProgress,
            isAborted = isAborted,
        )

        val meta = LinkedHashMap<String, Any?>(metadata)
        if (thumbUrl != null) meta["thumbnail"] = thumbUrl
        meta["content_type"] = meta["content_type"] ?: contentTypeFromMime(mimeType)

        val compatible = buildMobileCompatibleFields(meta, res.url)
        val out = LinkedHashMap<String, Any?>()
        out["fileId"] = fileId
        out["id"] = fileId
        out["downloadUrl"] = res.url
        out["filename"] = filename
        out["fileType"] = mimeType
        out["fileSize"] = res.size
        out["metadata"] = meta
        out["storagePath"] = res.path
        out["createdAt"] = FieldValue.serverTimestamp()
        out.putAll(compatible)
        return out
    }

    /** رفع ملفّ مفرد من المسار (بثّ) ثم كتابته — لشاشة «رفع ملف» في المحتوى. */
    suspend fun createContentPath(
        filePath: String,
        filename: String,
        mimeType: String,
        metadata: Map<String, Any?>,
        thumbBytes: ByteArray? = null,
        thumbName: String? = null,
        onProgress: ((Double) -> Unit)? = null,
    ): String {
        val prepared = prepareUploadPath(
            filePath = filePath,
            filename = filename,
            mimeType = mimeType,
            metadata = metadata,
            thumbBytes = thumbBytes,
            thumbName = thumbName,
            onProgress = onProgress,
        )
        commitPrepared(prepared)
        return "${prepared["fileId"]}"
    }

    /** المرحلة 2: كتابة الحمولة المُعدّة في المجموعتين (مرآة). */
    suspend fun commitPrepared(prepared: Map<String, Any?>) {
        fs.writeFileMirrorBoth(prepared["fileId"], prepared)
    }

    // ─── تعديل ───────────────────────────────────────────────

    /**
     * تعديل بيانات المحتوى. تمرير حقول null = لا تغيير.
     * [move] إن صحّ يُعاد حساب المسار القانونيّ من الأقسام الممرَّرة.
     */
    suspend fun updateContent(
        fileId: String,
        title: String? = null,
        description: String? = null,
        author: String? = null,
        isListed: Boolean? = null,
        contentType: String? = null,
        move: Boolean = false,
        mainSection: Any? = null,
        subsection: Any? = null,
        secondarySubsection: Any? = null,
        thumbBytes: ByteArray? = null,
        thumbName: String? = null,
    ) {
        val current = fs.getFileRow(fileId)
        if (current == null || current.isEmpty()) throw IllegalStateException("File not found")

        val currentMeta = asStringKeyMap(current["metadata"]) ?: emptyMap()
        val nextMeta = LinkedHashMap<String, Any?>(currentMeta)
        if (title != null) nextMeta["title"] = title.trim()
        if (description != null) nextMeta["description"] = description.trim()
        if (author != null) nextMeta["author"] = author.trim()
        if (isListed != null) nextMeta["is_listed"] = isListed
        if (contentType != null) nextMeta["content_type"] = contentType.trim()
        if (move) {
            nextMeta["main_section"] = mainSection
            nextMeta["subsection"] = subsection
            nextMeta["secondary_subsection"] = secondarySubsection
        }

        val next = LinkedHashMap<String, Any?>(current)
        next["metadata"] = nextMeta

        if (thumbBytes != null) {
            val tRes = smartUpload(
                bytes = thumbBytes,
                filename = "thumbnail_${System.currentTimeMillis()}_${thumbName ?: "thumb"}",
                folder = "dashboard/content/$fileId",
                contentType = "image/jpeg",
            )
            nextMeta["thumbnail"] = tRes.url
        }

        var trail: Map<String, Any?>? = null
        if (move) {
            trail = resolveTrail(
                mainSection = nextMeta["main_section"],
                subsection = nextMeta["subsection"],
                secondarySubsection = nextMeta["secondary_subsection"],
            )
            if (trail["subsection"] == null) {
                throw IllegalStateException(
                    "اختر قسمًا فرعيًّا صالحًا قبل الحفظ — لا يمكن نقل المحتوى إلى قسم رئيسي بلا قسم فرعي.",
                )
            }
            nextMeta.putAll(trail)
        }

        next.putAll(buildUploadMirrorFields(next, nextMeta))
        if (trail != null) {
            for (k in listOf(
                "main_section",
                "main_section_id",
                "main_section_name",
                "subsection",
                "subsection_name",
                "secondary_subsection",
                "secondary_subsection_name",
            )) {
                next[k] = trail[k]
            }
        }

        fs.writeFileMirrorBoth(fileId, next)
    }

    private fun buildUploadMirrorFields(
        current: Map<String, Any?>,
        metadata: Map<String, Any?>,
    ): Map<String, Any?> {
        val contentType =
            "${metadata["content_type"] ?: current["content_type"] ?: ""}".trim().lowercase()
        val sourceUrl = "${
            current["downloadUrl"] ?: current["sourceUrl"] ?: current["file_url"]
                ?: current["audio_url"] ?: current["video_url"] ?: ""
        }".trim()
        return linkedMapOf(
            "id" to (current["fileId"] ?: current["id"]),
            "title" to (metadata["title"] ?: current["title"]),
            "description" to (metadata["description"] ?: current["description"]),
            "author" to (metadata["author"] ?: current["author"]),
            "thumbnail" to (metadata["thumbnail"] ?: current["thumbnail"]),
            "content_type" to (metadata["content_type"] ?: current["content_type"]),
            "subsection" to (metadata["subsection"] ?: current["subsection"]),
            "subsection_name" to (metadata["subsection_name"] ?: current["subsection_name"]),
            "secondary_subsection" to
                (metadata["secondary_subsection"] ?: current["secondary_subsection"]),
            "secondary_subsection_name" to
                (metadata["secondary_subsection_name"] ?: current["secondary_subsection_name"]),
            "main_section" to (metadata["main_section"] ?: current["main_section"]),
            "main_section_id" to (metadata["main_section_id"] ?: current["main_section_id"]),
            "main_section_name" to (metadata["main_section_name"] ?: current["main_section_name"]),
            "sourceUrl" to (if (sourceUrl.isNotEmpty()) sourceUrl else current["sourceUrl"]),
            "file_url" to (if (sourceUrl.isNotEmpty()) sourceUrl else current["file_url"]),
            "audio_url" to (if (contentType == "audio") sourceUrl else current["audio_url"]),
            "video_url" to (if (contentType == "video") sourceUrl else current["video_url"]),
        )
    }

    private fun norm(v: Any?): String? {
        val s = "${v ?: ""}".trim()
        return if (s.isEmpty()) null else s
    }

    private suspend fun resolveTrail(
        mainSection: Any? = null,
        subsection: Any? = null,
        secondarySubsection: Any? = null,
    ): Map<String, Any?> {
        var mainId = norm(mainSection)
        var subId = norm(subsection)
        var secId = norm(secondarySubsection)
        var mainName: String? = null
        var subName: String? = null
        var secName: String? = null

        if (secId != null) {
            val rec = fs.getSectionRecord("secondary", secId)
            if (rec != null) {
                secName = norm(rec["name"])
                val parentSub = norm(rec["sub_section"])
                if (parentSub != null) subId = parentSub
            } else {
                secId = null
            }
        }
        if (subId != null) {
            val rec = fs.getSectionRecord("sub", subId)
            if (rec != null) {
                subName = norm(rec["name"])
                val parentMain = norm(rec["main_section"])
                if (parentMain != null) mainId = parentMain
            } else {
                subId = null
                secId = null
                secName = null
            }
        } else {
            secId = null
            secName = null
        }
        if (mainId != null) {
            val rec = fs.getSectionRecord("main", mainId)
            if (rec != null) {
                mainName = norm(rec["name"])
            } else {
                mainId = null
            }
        }
        return linkedMapOf(
            "main_section" to mainId,
            "main_section_id" to mainId,
            "main_section_name" to mainName,
            "subsection" to subId,
            "subsection_name" to subName,
            "secondary_subsection" to secId,
            "secondary_subsection_name" to secName,
        )
    }

    // ─── حذف ─────────────────────────────────────────────────

    suspend fun removeFile(fileId: String) {
        val item = fs.getFileRow(fileId)
        if (item == null || item.isEmpty()) return

        val isIa = item["__provider"] == "internet_archive" ||
            "${item["__iaIdentifier"] ?: ""}".trim().isNotEmpty()
        if (isIa) {
            ApiClient.authedJson(
                "/api/admin/internet-archive/dmca",
                method = "POST",
                body = mapOf("fileId" to fileId, "reason" to "manual_delete_dashboard"),
            )
            return
        }

        deleteAssets(collectAssetUrls(item))
        val storagePath = "${item["storagePath"] ?: ""}".trim()
        if (storagePath.isNotEmpty()) {
            try {
                FirebaseStorage.getInstance().getReference(storagePath).delete().await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        fs.deleteFileMirrorBoth(fileId)
    }

    private fun collectAssetUrls(row: Map<String, Any?>): List<String> {
        val md = asStringKeyMap(row["metadata"]) ?: emptyMap()
        val raw = listOf(
            row["thumbnail"],
            md["thumbnail"],
            row["file_url"],
            row["audio_url"],
            row["video_url"],
            row["downloadUrl"],
            row["sourceUrl"],
            row["url"],
        )
        val seen = LinkedHashSet<String>()
        for (v in raw) {
            val s = "${v ?: ""}".trim()
            if (s.isNotEmpty()) seen.add(s)
        }
        return seen.toList()
    }

    private suspend fun deleteAssets(urls: List<String>) {
        val storage = FirebaseStorage.getInstance()
        for (url in urls.toSet()) {
            try {
                storage.getReferenceFromUrl(url).delete().await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
}
