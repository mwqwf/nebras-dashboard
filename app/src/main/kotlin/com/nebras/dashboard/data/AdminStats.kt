package com.nebras.dashboard.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.nebras.dashboard.core.FsPaths
import com.nebras.dashboard.core.nebrasFirestore
import com.nebras.dashboard.core.snapshotsFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * عدّادات مساهمة المشرفين — تغذّي «لوحة الشرف» 🏆.
 *
 * `admin_stats/{uid}`: {name, photo, uploads, edits, weekly.{yyyy_ww},
 * lastUploadAt}. كلّ مشرف يزيد عدّاداته هو فقط (قاعدة أمان مطابقة)،
 * والكتابة أفضل-جهد: فشلها لا يعطّل الرفع أبداً.
 */
object AdminStats {

    private val coll: CollectionReference
        get() = nebrasFirestore().collection(FsPaths.adminStats)

    /**
     * مفتاح أسبوع بصيغة `yyyy_ww` (أسبوع تقريبي من مطلع السنة — ثابت
     * ومتّسق بين الأعضاء، والغرض ترتيب نسبي لا تقويم دقيق).
     */
    fun weekKey(at: LocalDate = LocalDate.now()): String {
        val startOfYear = LocalDate.of(at.year, 1, 1)
        val week = (ChronoUnit.DAYS.between(startOfYear, at) / 7) + 1
        return "${at.year}_${week.toString().padStart(2, '0')}"
    }

    private fun identity(): Map<String, Any> {
        val u = FirebaseAuth.getInstance().currentUser
        val trimmed = u?.displayName?.trim()
        val name = if (!trimmed.isNullOrEmpty()) {
            u.displayName!!
        } else {
            u?.email?.substringBefore('@') ?: "مستخدم"
        }
        return mapOf("name" to name, "photo" to (u?.photoUrl?.toString() ?: ""))
    }

    /** +1 رفع (إجمالي + أسبوعي). */
    suspend fun bumpUpload() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        try {
            val payload = HashMap<String, Any>(identity())
            payload["uploads"] = FieldValue.increment(1)
            // خريطة متداخلة (لا مفتاحاً منقوطاً): set+merge يدمجها بدون مسح
            // أسابيع سابقة.
            payload["weekly"] = mapOf(weekKey() to FieldValue.increment(1))
            payload["lastUploadAt"] = FieldValue.serverTimestamp()
            coll.document(uid).set(payload, SetOptions.merge()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** +1 تعديل محتوى. */
    suspend fun bumpEdit() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        try {
            val payload = HashMap<String, Any>(identity())
            payload["edits"] = FieldValue.increment(1)
            coll.document(uid).set(payload, SetOptions.merge()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** بثّ لوحة الشرف (تنازلياً حسب إجمالي الرفعات). */
    fun boardFlow(): Flow<List<Map<String, Any?>>> =
        coll.orderBy("uploads", Query.Direction.DESCENDING)
            .limit(50)
            .snapshotsFlow()
            .map { s ->
                s.documents.map { d ->
                    val m = d.dataMap()
                    m["uid"] = d.id
                    m
                }
            }
}
