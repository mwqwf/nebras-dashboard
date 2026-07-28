package com.nebras.dashboard.core

import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** أسماء مجموعات Firestore — تطابق ما تكتبه لوحة الويب ويقرأه تطبيق الجوال. */
object FsPaths {
    const val sections: String = "sections_unified"
    const val uploads: String = "dashboard_uploads"
    const val contentFiles: String = "content_unified_files"
    const val contentYoutube: String = "content_unified_youtube"

    /** عدّادات مساهمة المشرفين (لوحة الشرف 🏆). */
    const val adminStats: String = "admin_stats"
}

/**
 * الوصول إلى قاعدة Firestore المسمّاة `default` (مثل `getFirestore(app,'default')`
 * في الويب و`FirebaseFirestore.instanceFor(databaseId:'default')` في Flutter).
 *
 * ⚠️ الاسم صريح `default` وليس `(default)` — لا تستعمل `FirebaseFirestore.getInstance()`
 * المجرّدة في أيّ مكان من اللوحة.
 */
fun nebrasFirestore(): FirebaseFirestore =
    FirebaseFirestore.getInstance(FirebaseApp.getInstance(), AppConfig.firestoreDatabaseId)

/** مراجع جاهزة للمجموعات الموحّدة (اختصار فوق [nebrasFirestore]). */
object FirestoreRefs {

    val db: FirebaseFirestore get() = nebrasFirestore()

    /** وثائق الأقسام الثلاث: `sections_unified/{main|sub|secondary}`. */
    val sections: CollectionReference get() = db.collection(FsPaths.sections)

    fun sectionsDoc(level: String): DocumentReference = sections.document(level)

    /** رفعات اللوحة (المجموعة الأساسيّة). */
    val uploads: CollectionReference get() = db.collection(FsPaths.uploads)

    /** مرآة المحتوى الموحّد التي يقرأها تطبيق الجوال. */
    val contentFiles: CollectionReference get() = db.collection(FsPaths.contentFiles)

    /** سجلّات يوتيوب القديمة — قراءة/حذف فقط (البثّ من يوتيوب مقطوع نهائيّاً). */
    val contentYoutube: CollectionReference get() = db.collection(FsPaths.contentYoutube)

    val adminStats: CollectionReference get() = db.collection(FsPaths.adminStats)

    // ─── دردشة الإدارة ───────────────────────────────────────

    val chatMessages: CollectionReference get() = db.collection(ChatPaths.messages)

    val chatMembers: CollectionReference get() = db.collection(ChatPaths.members)

    val chatGroupMeta: DocumentReference get() = db.collection(ChatPaths.meta).document("group")
}

/**
 * مسارات دردشة الإدارة — مُعرَّفة هنا كي تصل إليها [FirestoreRefs] دون اعتماد
 * دائريّ على طبقة `data`.
 *
 *   • `admin_chat_messages/{msgId}` — الرسائل (زمن حقيقي عبر snapshots)
 *     مع خريطة تفاعلات `reactions: {uid: emoji}`.
 *   • `admin_chat_members/{uid}`   — الأعضاء (عضويّة تلقائيّة) + حضور
 *     (lastActiveAtMs) + قراءة (lastReadAtMs) + كتابة (typingAtMs).
 *   • `admin_chat_meta/group`      — هويّة المجموعة (اسم/صورة/قفل/تثبيت)،
 *     يكتبها المالك وحده.
 */
object ChatPaths {
    const val messages: String = "admin_chat_messages"
    const val members: String = "admin_chat_members"
    const val meta: String = "admin_chat_meta"

    /** مجلّد مرفقات الدردشة في Storage (قاعدة خاصّة به في storage.rules). */
    const val storageFolder: String = "dashboard/chat"
    const val avatarsFolder: String = "dashboard/chat/avatars"
    const val metaFolder: String = "dashboard/chat/meta"
}

/**
 * بثّ استعلام Firestore كـ [Flow] — نظير `.snapshots()` في Dart.
 * أخطاء المستمع تُغلق التدفّق بالخطأ نفسه (كما في Dart).
 */
fun Query.snapshotsFlow(): Flow<QuerySnapshot> = callbackFlow {
    val registration = addSnapshotListener { snap, error ->
        if (error != null) {
            close(error)
        } else if (snap != null) {
            trySend(snap)
        }
    }
    awaitClose { registration.remove() }
}

/** بثّ وثيقة Firestore كـ [Flow] — نظير `DocumentReference.snapshots()`. */
fun DocumentReference.snapshotsFlow(): Flow<DocumentSnapshot> = callbackFlow {
    val registration = addSnapshotListener { snap, error ->
        if (error != null) {
            close(error)
        } else if (snap != null) {
            trySend(snap)
        }
    }
    awaitClose { registration.remove() }
}
