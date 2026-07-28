package com.nebras.dashboard.data

import com.google.firebase.storage.FirebaseStorage
import com.nebras.dashboard.core.filterAndRank
import com.nebras.dashboard.core.tokenize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.time.Instant
import kotlin.random.Random

/** مستوى القسم. */
enum class SectionLevel { main, sub, secondary }

fun levelKey(l: SectionLevel): String = when (l) {
    SectionLevel.main -> "main"
    SectionLevel.sub -> "sub"
    SectionLevel.secondary -> "secondary"
}

fun sameSectionId(a: Any?, b: Any?): Boolean {
    if (a == null || b == null) return false
    return a.toString() == b.toString()
}

private fun makeSectionId(): Long = System.currentTimeMillis() + Random.nextInt(1000)

/** طابع زمني UTC بصيغة ISO-8601 — نظير `DateTime.now().toUtc().toIso8601String()`. */
private fun nowIsoUtc(): String = Instant.now().toString()

/** إدارة الأقسام (Nebras فقط) — نظير قسم الأقسام في `moderator.js`. */
object SectionsRepository {

    private val fs = UnifiedFirestore

    // ─── قراءة ───────────────────────────────────────────────

    suspend fun listMain(search: String = ""): List<Map<String, Any?>> {
        val all = fs.readSectionsLevel("main")
        return applyFilters(all, search = search)
    }

    suspend fun listSub(
        mainSection: Any? = null,
        search: String = "",
    ): List<Map<String, Any?>> {
        val all = fs.readSectionsLevel("sub")
        return applyFilters(all, search = search, mainSection = mainSection)
    }

    suspend fun listSecondary(
        subSection: Any? = null,
        search: String = "",
    ): List<Map<String, Any?>> {
        val all = fs.readSectionsLevel("secondary")
        return applyFilters(all, search = search, subSection = subSection)
    }

    private fun applyFilters(
        list: List<Map<String, Any?>>,
        search: String = "",
        mainSection: Any? = null,
        subSection: Any? = null,
    ): List<Map<String, Any?>> {
        var out = list.toList()
        val tokens = tokenize(search)
        if (tokens.isNotEmpty()) {
            out = filterAndRank(out, tokens) { x ->
                listOf(
                    "${x["name"] ?: ""}",
                    "${x["description"] ?: ""}",
                    "${x["id"] ?: ""}",
                )
            }
        }
        if (mainSection != null && "$mainSection".isNotEmpty()) {
            out = out.filter { sameSectionId(it["main_section"], mainSection) }
        }
        if (subSection != null && "$subSection".isNotEmpty()) {
            out = out.filter { sameSectionId(it["sub_section"], subSection) }
        }
        if (tokens.isEmpty()) {
            out = out.sortedWith { a, b ->
                val ao = (a["order_index"] as? Number)?.toInt() ?: 0
                val bo = (b["order_index"] as? Number)?.toInt() ?: 0
                if (ao != bo) {
                    ao.compareTo(bo)
                } else {
                    val an = "${a["id"]}".toLongOrNull()
                    val bn = "${b["id"]}".toLongOrNull()
                    if (an != null && bn != null) {
                        bn.compareTo(an)
                    } else {
                        "${b["id"]}".compareTo("${a["id"]}")
                    }
                }
            }
        }
        return out
    }

    // ─── إنشاء/تعديل ─────────────────────────────────────────

    private suspend fun uploadThumb(
        level: String,
        id: Any?,
        bytes: ByteArray?,
        filename: String?,
    ): String? {
        if (bytes == null) return null
        val res = smartUpload(
            bytes = bytes,
            filename = "${System.currentTimeMillis()}_${filename ?: "thumb"}",
            folder = "sections/$level/$id",
            contentType = "image/jpeg",
        )
        return res.url
    }

    suspend fun createMain(
        name: String,
        orderIndex: Int = 0,
        isListed: Boolean = true,
        thumbBytes: ByteArray? = null,
        thumbName: String? = null,
    ): Map<String, Any?> {
        val id = makeSectionId()
        val thumbUrl = uploadThumb("main", id, thumbBytes, thumbName)
        val payload = linkedMapOf<String, Any?>(
            "id" to id,
            "name" to name.trim(),
            "order_index" to orderIndex,
            "is_listed" to isListed,
            "thumbnail" to thumbUrl,
            "created_at" to nowIsoUtc(),
        )
        fs.setSectionRecord("main", id, payload)
        return payload
    }

    suspend fun createSub(
        name: String,
        mainSection: Any?,
        isListed: Boolean = true,
        thumbBytes: ByteArray? = null,
        thumbName: String? = null,
    ): Map<String, Any?> {
        val id = makeSectionId()
        val thumbUrl = uploadThumb("sub", id, thumbBytes, thumbName)
        val payload = linkedMapOf<String, Any?>(
            "id" to id,
            "name" to name.trim(),
            "main_section" to ("$mainSection".toLongOrNull() ?: mainSection),
            "is_listed" to isListed,
            "thumbnail" to thumbUrl,
            "created_at" to nowIsoUtc(),
        )
        fs.setSectionRecord("sub", id, payload)
        return payload
    }

    suspend fun createSecondary(
        name: String,
        subSection: Any?,
        isListed: Boolean = true,
        thumbBytes: ByteArray? = null,
        thumbName: String? = null,
    ): Map<String, Any?> {
        val id = makeSectionId()
        val thumbUrl = uploadThumb("secondary", id, thumbBytes, thumbName)
        val payload = linkedMapOf<String, Any?>(
            "id" to id,
            "name" to name.trim(),
            "sub_section" to ("$subSection".toLongOrNull() ?: subSection),
            "is_listed" to isListed,
            "thumbnail" to thumbUrl,
            "created_at" to nowIsoUtc(),
        )
        fs.setSectionRecord("secondary", id, payload)
        return payload
    }

    /** تعديل قسم. الحقول الممرَّرة فقط تُحدَّث (null = لا تغيّر). */
    suspend fun update(
        level: SectionLevel,
        id: Any?,
        name: String? = null,
        description: String? = null,
        orderIndex: Int? = null,
        isListed: Boolean? = null,
        // main_section (sub) أو sub_section (secondary)
        parentSection: Any? = null,
        thumbBytes: ByteArray? = null,
        thumbName: String? = null,
    ): Map<String, Any?> {
        val lk = levelKey(level)
        val current = fs.getSectionRecord(lk, id) ?: throw IllegalStateException("Section not found")
        val patch = LinkedHashMap<String, Any?>()
        if (name != null) patch["name"] = name.trim()
        if (description != null) patch["description"] = description.trim()
        if (orderIndex != null) patch["order_index"] = orderIndex
        if (isListed != null) patch["is_listed"] = isListed
        if (parentSection != null) {
            if (level == SectionLevel.sub) patch["main_section"] = parentSection
            if (level == SectionLevel.secondary) patch["sub_section"] = parentSection
        }
        if (thumbBytes != null) {
            patch["thumbnail"] = uploadThumb(lk, id, thumbBytes, thumbName)
        }
        val next = LinkedHashMap<String, Any?>(current)
        next.putAll(patch)
        fs.setSectionRecord(lk, id, next)
        return next
    }

    // ─── حذف تعاقبي (يطابق removeMain/Sub/Secondary) ──────────

    suspend fun removeMain(id: Any?) {
        val mainRow = fs.getSectionRecord("main", id)
        val subItems = fs.readSectionsLevel("sub")
        val secItems = fs.readSectionsLevel("secondary")
        val fileRows = fs.listFileRowsMerged()
        val videos = fs.listYoutubeRecords()

        val subRows = subItems.filter { sameSectionId(it["main_section"], id) }
        val subIds = subRows.map { it["id"] }
        val secondaryRows = secItems.filter { sec ->
            subIds.any { subId -> sameSectionId(sec["sub_section"], subId) }
        }
        val secondaryIds = secondaryRows.map { it["id"] }

        fun belongs(row: Map<String, Any?>): Boolean {
            val md = asStringKeyMap(row["metadata"]) ?: emptyMap()
            val mainMatch = sameSectionId(md["main_section"], id) ||
                sameSectionId(md["main_section_id"], id) ||
                sameSectionId(row["main_section"], id) ||
                sameSectionId(row["main_section_id"], id)
            val subMatch = subIds.any { subId ->
                sameSectionId(md["subsection"], subId) || sameSectionId(row["subsection"], subId)
            }
            val secMatch = secondaryIds.any { secId ->
                sameSectionId(md["secondary_subsection"], secId) ||
                    sameSectionId(row["secondary_subsection"], secId)
            }
            return mainMatch || subMatch || secMatch
        }

        val filesToDelete = fileRows.filter { belongs(it) }
        val videosToDelete = videos.filter { belongs(it) }
        val urls = ArrayList<String>()
        urls.addAll(collectAssetUrls(mainRow ?: emptyMap()))
        subRows.forEach { urls.addAll(collectAssetUrls(it)) }
        secondaryRows.forEach { urls.addAll(collectAssetUrls(it)) }
        filesToDelete.forEach { urls.addAll(collectAssetUrls(it)) }
        videosToDelete.forEach { urls.addAll(collectAssetUrls(it)) }
        deleteAssets(urls)

        for (row in filesToDelete) {
            fs.deleteFileMirrorBoth(row["fileId"] ?: row["id"])
        }
        for (row in videosToDelete) {
            fs.deleteYoutubeRecord(row["id"])
        }
        for (sec in secondaryRows) {
            fs.deleteSectionRecord("secondary", sec["id"])
        }
        for (sub in subRows) {
            fs.deleteSectionRecord("sub", sub["id"])
        }
        fs.deleteSectionRecord("main", id)
    }

    suspend fun removeSub(id: Any?) {
        val subRow = fs.getSectionRecord("sub", id)
        val secItems = fs.readSectionsLevel("secondary")
        val secondaryRows = secItems.filter { sameSectionId(it["sub_section"], id) }
        val secondaryIds = secondaryRows.map { it["id"] }
        val fileRows = fs.listFileRowsMerged()
        val videos = fs.listYoutubeRecords()

        fun belongs(row: Map<String, Any?>): Boolean {
            val md = asStringKeyMap(row["metadata"]) ?: emptyMap()
            val subMatch = sameSectionId(md["subsection"], id) ||
                sameSectionId(row["subsection"], id)
            val secMatch = secondaryIds.any { secId ->
                sameSectionId(md["secondary_subsection"], secId) ||
                    sameSectionId(row["secondary_subsection"], secId)
            }
            return subMatch || secMatch
        }

        val filesToDelete = fileRows.filter { belongs(it) }
        val videosToDelete = videos.filter { belongs(it) }
        val urls = ArrayList<String>()
        urls.addAll(collectAssetUrls(subRow ?: emptyMap()))
        secondaryRows.forEach { urls.addAll(collectAssetUrls(it)) }
        filesToDelete.forEach { urls.addAll(collectAssetUrls(it)) }
        videosToDelete.forEach { urls.addAll(collectAssetUrls(it)) }
        deleteAssets(urls)

        for (row in filesToDelete) {
            fs.deleteFileMirrorBoth(row["fileId"] ?: row["id"])
        }
        for (row in videosToDelete) {
            fs.deleteYoutubeRecord(row["id"])
        }
        for (sec in secondaryRows) {
            fs.deleteSectionRecord("secondary", sec["id"])
        }
        fs.deleteSectionRecord("sub", id)
    }

    suspend fun removeSecondary(id: Any?) {
        val secRow = fs.getSectionRecord("secondary", id)
        val fileRows = fs.listFileRowsMerged()
        val videos = fs.listYoutubeRecords()

        fun belongs(row: Map<String, Any?>): Boolean {
            val md = asStringKeyMap(row["metadata"]) ?: emptyMap()
            return sameSectionId(md["secondary_subsection"], id) ||
                sameSectionId(row["secondary_subsection"], id)
        }

        val filesToDelete = fileRows.filter { belongs(it) }
        val videosToDelete = videos.filter { belongs(it) }
        val urls = ArrayList<String>()
        urls.addAll(collectAssetUrls(secRow ?: emptyMap()))
        filesToDelete.forEach { urls.addAll(collectAssetUrls(it)) }
        videosToDelete.forEach { urls.addAll(collectAssetUrls(it)) }
        deleteAssets(urls)

        for (row in filesToDelete) {
            fs.deleteFileMirrorBoth(row["fileId"] ?: row["id"])
        }
        for (row in videosToDelete) {
            fs.deleteYoutubeRecord(row["id"])
        }
        fs.deleteSectionRecord("secondary", id)
    }

    suspend fun remove(level: SectionLevel, id: Any?) = when (level) {
        SectionLevel.main -> removeMain(id)
        SectionLevel.sub -> removeSub(id)
        SectionLevel.secondary -> removeSecondary(id)
    }

    private fun collectAssetUrls(row: Map<String, Any?>): List<String> {
        val md = asStringKeyMap(row["metadata"]) ?: emptyMap()
        val raw = listOf(
            row["thumbnail"],
            row["image"],
            row["imageUrl"],
            row["thumbnailUrl"],
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
                // لا نوقف الحذف الجذري بسبب ملف مفقود.
            }
        }
    }
}
