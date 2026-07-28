package com.nebras.dashboard.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.nebras.dashboard.core.FsPaths
import com.nebras.dashboard.core.nebrasFirestore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

/** خريطة بمفاتيح نصّيّة من أيّ قيمة Firestore (أو null إن لم تكن خريطة). */
internal fun asStringKeyMap(value: Any?): Map<String, Any?>? {
    if (value !is Map<*, *>) return null
    val out = LinkedHashMap<String, Any?>(value.size)
    for ((k, v) in value) out["$k"] = v
    return out
}

/** مثل [asStringKeyMap] لكن ترجع خريطة قابلة للتعديل وفارغة عند عدم التطابق. */
internal fun mutableStringKeyMap(value: Any?): MutableMap<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    if (value is Map<*, *>) {
        for ((k, v) in value) out["$k"] = v
    }
    return out
}

/** بيانات الوثيقة كخريطة قابلة للتعديل (فارغة إن لم توجد). */
internal fun DocumentSnapshot.dataMap(): MutableMap<String, Any?> = mutableStringKeyMap(data)

/**
 * قراءات/كتابات Firestore للمحتوى الموحّد — نظير
 * `nebrasUnifiedFirestoreClient.js` (لكن بصلاحيّات المستخدم عبر Firebase SDK).
 */
object UnifiedFirestore {

    private val db: FirebaseFirestore get() = nebrasFirestore()

    // ─── الأقسام (sections_unified: 3 وثائق main/sub/secondary) ───────────────
    //
    // الأقسام تتغيّر نادراً وتُقرأ كثيراً (منتقيات الرفع/التعديل). نُخزّنها مؤقتاً
    // لتفادي ضرب Firestore عند كل فتح، ونُبطل الكاش عند أي كتابة/حذف قسم.
    private val sectionDocCache = ConcurrentHashMap<String, Map<String, Any?>>()
    private val sectionDocCacheAt = ConcurrentHashMap<String, Long>()
    private const val SECTION_TTL_MS = 30_000L

    private fun invalidateSectionsCache() {
        sectionDocCache.clear()
        sectionDocCacheAt.clear()
    }

    private suspend fun readSectionDoc(level: String): Map<String, Any?> {
        val at = sectionDocCacheAt[level]
        if (at != null && System.currentTimeMillis() - at < SECTION_TTL_MS) {
            return sectionDocCache[level] ?: emptyMap()
        }
        val snap = db.collection(FsPaths.sections).document(level).get().await()
        val data: Map<String, Any?> = if (snap.exists()) snap.dataMap() else emptyMap()
        sectionDocCache[level] = data
        sectionDocCacheAt[level] = System.currentTimeMillis()
        return data
    }

    /** قراءة مستوى أقسام كاملاً كقائمة سجلّات. */
    suspend fun readSectionsLevel(level: String): List<Map<String, Any?>> {
        val data = readSectionDoc(level)
        return data.values.mapNotNull { asStringKeyMap(it) }
    }

    suspend fun readSectionsSubMap(): Map<String, Any?> = readSectionDoc("sub")

    suspend fun getSectionRecord(level: String, id: Any?): Map<String, Any?>? {
        val data = readSectionDoc(level)
        return asStringKeyMap(data["$id"])
    }

    suspend fun setSectionRecord(level: String, id: Any?, value: Map<String, Any?>) {
        val key = "$id"
        val cleaned = stripNullsDeep(value) ?: emptyMap<String, Any>()
        db.collection(FsPaths.sections).document(level)
            .set(mapOf(key to cleaned), SetOptions.merge())
            .await()
        invalidateSectionsCache()
    }

    suspend fun deleteSectionRecord(level: String, id: Any?) {
        val ref = db.collection(FsPaths.sections).document(level)
        val s = ref.get().await()
        if (!s.exists()) return
        ref.update(mapOf("$id" to FieldValue.delete())).await()
        invalidateSectionsCache()
    }

    // ─── يوتيوب (قراءة/حذف فقط — لتنظيف السجلّات القديمة عند حذف الأقسام) ──────

    suspend fun listYoutubeRecords(): List<Map<String, Any?>> {
        val snap = db.collection(FsPaths.contentYoutube).get().await()
        return snap.documents.map { d ->
            val data = d.dataMap()
            data["id"] = data["id"] ?: d.id
            data
        }
    }

    suspend fun deleteYoutubeRecord(id: Any?) {
        db.collection(FsPaths.contentYoutube).document("$id").delete().await()
    }

    // ─── ملفّات المحتوى (dashboard_uploads + content_unified_files) ────────────

    // كاش قصير لصفوف الملفّات — مجموعة المحتوى ضخمة (آلاف الوثائق)، فلا نُعيد
    // قراءتها عند كل بحث/فلتر/تنقّل صفحات. يُبطَل عند أي كتابة/حذف محتوى.
    @Volatile
    private var fileRowsCache: List<Map<String, Any?>>? = null

    @Volatile
    private var fileRowsCacheAt: Long? = null
    private const val FILE_ROWS_TTL_MS = 60_000L

    private fun invalidateFileRowsCache() {
        fileRowsCache = null
        fileRowsCacheAt = null
    }

    /** دمج صفوف الملفّات من المجموعتين (كما يفعل التطبيق عند القراءة). */
    suspend fun listFileRowsMerged(force: Boolean = false): List<Map<String, Any?>> {
        val at = fileRowsCacheAt
        val cached = fileRowsCache
        if (!force && cached != null && at != null &&
            System.currentTimeMillis() - at < FILE_ROWS_TTL_MS
        ) {
            return cached
        }
        val results = coroutineScope {
            val a = async { db.collection(FsPaths.uploads).get().await() }
            val b = async { db.collection(FsPaths.contentFiles).get().await() }
            listOf(a.await(), b.await())
        }
        val rows = ArrayList<Map<String, Any?>>()
        for (snap in results) {
            for (d in snap.documents) {
                val data = d.dataMap()
                data["id"] = data["id"] ?: d.id
                data["fileId"] = data["fileId"] ?: d.id
                rows.add(data)
            }
        }
        fileRowsCache = rows
        fileRowsCacheAt = System.currentTimeMillis()
        return rows
    }

    suspend fun getFileRow(fileId: Any?): Map<String, Any?>? {
        val id = "$fileId"
        val primary = db.collection(FsPaths.uploads).document(id).get().await()
        if (primary.exists()) return primary.dataMap()
        val fallback = db.collection(FsPaths.contentFiles).document(id).get().await()
        return if (fallback.exists()) fallback.dataMap() else null
    }

    /** كتابة وثيقة الملفّ في المجموعتين معاً (writeBatch) — نفس حركة الويب. */
    suspend fun writeFileMirrorBoth(fileId: Any?, payload: Map<String, Any?>) {
        val id = "$fileId"
        val data = stripNullsDeepMap(clampContentTextFields(payload))
        val batch = db.batch()
        batch.set(db.collection(FsPaths.uploads).document(id), data)
        batch.set(db.collection(FsPaths.contentFiles).document(id), data)
        batch.commit().await()
        invalidateFileRowsCache()
    }

    suspend fun deleteFileMirrorBoth(fileId: Any?) {
        val id = "$fileId"
        val batch = db.batch()
        batch.delete(db.collection(FsPaths.uploads).document(id))
        batch.delete(db.collection(FsPaths.contentFiles).document(id))
        batch.commit().await()
        invalidateFileRowsCache()
    }
}
