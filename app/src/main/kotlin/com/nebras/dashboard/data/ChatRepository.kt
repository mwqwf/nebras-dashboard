package com.nebras.dashboard.data

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.nebras.dashboard.core.ChatPaths
import com.nebras.dashboard.core.FirestoreRefs
import com.nebras.dashboard.core.snapshotsFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * دردشة الإدارة الجماعيّة — خاصّة بلوحة نبراس فقط (لا يقرأها تطبيق الجوال
 * العامّ إطلاقاً، وتحميها قواعد Firestore/Storage بدور owner/supervisor).
 *
 * المجموعات (أسماؤها في `core/FirestoreRefs.kt` → [ChatPaths]):
 *   • `admin_chat_messages/{msgId}` — الرسائل (زمن حقيقي عبر snapshots)
 *     مع خريطة تفاعلات `reactions: {uid: emoji}`.
 *   • `admin_chat_members/{uid}`   — الأعضاء (عضويّة تلقائيّة) + حضور
 *     (lastActiveAtMs) + قراءة (lastReadAtMs) + كتابة (typingAtMs).
 *   • `admin_chat_meta/group`      — هويّة المجموعة (اسم/صورة/قفل/تثبيت)،
 *     يكتبها المالك وحده.
 */
object ChatRepository {

    /** نظير `ChatRepository.instance` في Dart — الكائن نفسه (مفرد كسول). */
    val instance: ChatRepository get() = this

    private val messages get() = FirestoreRefs.chatMessages
    private val members get() = FirestoreRefs.chatMembers
    private val meta get() = FirestoreRefs.chatGroupMeta

    /** معرّف المستخدم الحاليّ — تحتاجه الواجهة لتمييز رسائلي وحضوري. */
    val uid: String get() = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    /**
     * تنفيذ صامت عند الفشل (fire-and-forget) — نظير `try { } catch (_) { }`
     * في Dart، مع تمرير إلغاء الكوروتين كما هو كي لا يُبتلع.
     */
    private suspend fun quiet(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // أفضل جهد.
        }
    }

    // ─── الرسائل ─────────────────────────────────────────────

    /**
     * بثّ آخر [limit] رسالة (تنازليّاً) — الرسائل المحذوفة "عندي" تُرشَّح هنا.
     *
     * الترتيب على `sentAtMs` (طابع محلّي يُكتب مع كلّ رسالة) وليس على
     * serverTimestamp: الحقل موجود فوراً حتى في الكتابات المعلَّقة، فتظهر
     * رسالتك لحظيّاً وتعمل الدردشة دون انقطاع أثناء ضعف الشبكة.
     */
    fun messagesStream(limit: Int = 60): Flow<List<ChatMessage>> {
        val me = uid
        return messages
            .orderBy("sentAtMs", Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .snapshotsFlow()
            .map { snap ->
                snap.documents
                    .map { ChatMessage.fromDoc(it) }
                    .filter { !it.hiddenFor.contains(me) }
            }
    }

    private fun senderFields(): Map<String, Any?> {
        val u = FirebaseAuth.getInstance().currentUser
        val display = u?.displayName
        val name = if (display != null && display.trim().isNotEmpty()) {
            display
        } else {
            u?.email?.substringBefore('@') ?: "مستخدم"
        }
        return mapOf(
            "senderId" to (u?.uid ?: ""),
            "senderName" to name,
            "senderPhoto" to (u?.photoUrl?.toString() ?: ""),
        )
    }

    suspend fun sendText(text: String, replyTo: ChatReplyRef? = null) {
        val t = text.trim()
        if (t.isEmpty()) return
        val data = LinkedHashMap<String, Any?>(senderFields())
        data["type"] = "text"
        data["text"] = t
        data["att"] = null
        data["replyTo"] = replyTo?.toMap()
        data["sentAtMs"] = System.currentTimeMillis()
        data["createdAt"] = FieldValue.serverTimestamp()
        data["deleted"] = false
        data["deletedBy"] = ""
        data["hiddenFor"] = emptyList<String>()
        data["reactions"] = emptyMap<String, String>()
        messages.add(data).await()
    }

    /**
     * رفع ملفّ من مسار على الجهاز (بثّ — يدعم الملفّات الكبيرة جدّاً دون
     * تحميلها في الذاكرة) ثم إرسال رسالة تشير إليه. الترتيب الثابت:
     * Storage أولاً ثم وثيقة Firestore.
     */
    suspend fun sendAttachmentFromPath(
        filePath: String,
        filename: String,
        contentType: String,
        type: ChatMessageType,
        caption: String = "",
        durationMs: Long? = null,
        replyTo: ChatReplyRef? = null,
        onProgress: ((Double) -> Unit)? = null,
        isAborted: (() -> Boolean)? = null,
    ) {
        val up = smartUploadFile(
            file = File(filePath),
            filename = filename,
            contentType = contentType,
            folder = ChatPaths.storageFolder,
            onProgress = onProgress,
            isAborted = isAborted,
        )
        val data = LinkedHashMap<String, Any?>(senderFields())
        data["type"] = chatTypeToString(type)
        data["text"] = caption.trim()
        data["att"] = ChatAttachment(
            url = up.url,
            path = up.path,
            name = filename,
            size = up.size,
            contentType = up.contentType,
            durationMs = durationMs,
        ).toMap()
        data["replyTo"] = replyTo?.toMap()
        data["sentAtMs"] = System.currentTimeMillis()
        data["createdAt"] = FieldValue.serverTimestamp()
        data["deleted"] = false
        data["deletedBy"] = ""
        data["hiddenFor"] = emptyList<String>()
        data["reactions"] = emptyMap<String, String>()
        messages.add(data).await()
    }

    /** حذف عندي فقط — تبقى الرسالة ظاهرة للبقيّة (مطابق لواتساب). */
    suspend fun deleteForMe(messageId: String) {
        messages.document(messageId)
            .update("hiddenFor", FieldValue.arrayUnion(uid))
            .await()
    }

    /**
     * حذف عند الجميع — للمرسِل نفسه أو للمالك (صلاحيّة استثنائيّة). تُمسح
     * الحمولة ويُحذف مرفق Storage (بأفضل جهد) وتبقى وثيقة "تم حذف الرسالة".
     */
    suspend fun deleteForEveryone(msg: ChatMessage) {
        messages.document(msg.id).update(
            "deleted", true,
            "deletedBy", uid,
            "deletedAt", FieldValue.serverTimestamp(),
            "text", "",
            "att", null,
        ).await()
        val path = msg.attachment?.path ?: ""
        if (path.isNotEmpty()) {
            quiet {
                // أفضل جهد — الوثيقة حُذفت منطقيّاً على أيّ حال.
                FirebaseStorage.getInstance().getReference(path).delete().await()
            }
        }
    }

    // ─── التفاعلات (إيموجي مثل واتساب) ───────────────────────

    /** ضبط تفاعلي على رسالة: emoji جديد يستبدل السابق، و null يزيله. */
    suspend fun setReaction(messageId: String, emoji: String?) {
        val value: Any = if (emoji.isNullOrEmpty()) FieldValue.delete() else emoji
        messages.document(messageId).update("reactions.$uid", value).await()
    }

    // ─── هويّة المجموعة (المالك فقط) ─────────────────────────

    fun metaStream(): Flow<ChatGroupMeta> = meta.snapshotsFlow().map { d ->
        if (d.exists()) ChatGroupMeta.fromDoc(d) else ChatGroupMeta.fallback
    }

    suspend fun setGroupName(name: String) {
        meta.set(
            mapOf(
                "name" to name.trim(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).await()
    }

    /** رفع صورة المجموعة وتحديث الهويّة. */
    suspend fun setGroupPhotoFromPath(filePath: String, filename: String) {
        val up = smartUploadFile(
            file = File(filePath),
            filename = filename,
            contentType = guessContentType(filename),
            folder = ChatPaths.metaFolder,
        )
        meta.set(
            mapOf(
                "photoUrl" to up.url,
                "photoPath" to up.path,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).await()
    }

    suspend fun setLocked(locked: Boolean) {
        meta.set(
            mapOf(
                "locked" to locked,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).await()
    }

    /** تثبيت رسالة (null لإلغاء التثبيت) — صلاحيّة المالك. */
    suspend fun setPinned(ref: ChatReplyRef?) {
        meta.set(
            mapOf(
                "pinned" to ref?.toMap(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).await()
    }

    // ─── الأعضاء والحضور ─────────────────────────────────────

    /**
     * إضافة/تحديث العضو الحالي تلقائيّاً — تُستدعى عند فتح اللوحة (أي أنّ
     * كلّ من يدخل تطبيق الإدارة ينضمّ للمجموعة دون أيّ خطوة يدويّة).
     * صامتة عند الفشل (fire-and-forget): انقطاع الشبكة أو قواعد لم تُنشر
     * بعد يجب ألّا يُسقط اللوحة — شاشة الدردشة تعرض الخطأ الفعلي عند فتحها.
     *
     * [role]: دور اللوحة الحالي (owner/supervisor) — يُخزَّن ليُعرض وسام
     * المالك 👑 لبقيّة الأعضاء.
     */
    suspend fun upsertSelf(role: String? = null) {
        val u = FirebaseAuth.getInstance().currentUser ?: return
        val display = u.displayName
        val name = if (display != null && display.trim().isNotEmpty()) {
            display
        } else {
            u.email?.substringBefore('@') ?: "مستخدم"
        }
        quiet {
            val data = LinkedHashMap<String, Any?>()
            data["name"] = name
            data["email"] = u.email ?: ""
            data["photo"] = u.photoUrl?.toString() ?: ""
            if (role != null) data["role"] = role
            data["lastSeenAt"] = FieldValue.serverTimestamp()
            data["lastActiveAtMs"] = System.currentTimeMillis()
            members.document(u.uid).set(data, SetOptions.merge()).await()

            val doc = members.document(u.uid).get().await()
            val stored: Map<String, Any?> = doc.data ?: emptyMap()
            if (!stored.containsKey("joinedAt")) {
                members.document(u.uid)
                    .set(mapOf("joinedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
                    .await()
            }
        }
    }

    /** حفظ FCM token في وثيقتي (تكتبه خدمة الإشعارات عند الإقلاع/التجديد). */
    suspend fun saveFcmToken(token: String) {
        val me = uid
        if (me.isEmpty() || token.isEmpty()) return
        quiet {
            members.document(me).set(mapOf("fcmToken" to token), SetOptions.merge()).await()
        }
    }

    /** نبضة حضور دوريّة — تجعل العضو «متصلاً الآن» لبقيّة المجموعة. */
    suspend fun presenceTick() {
        val me = uid
        if (me.isEmpty()) return
        quiet {
            members.document(me).set(
                mapOf(
                    "lastActiveAtMs" to System.currentTimeMillis(),
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                ),
                SetOptions.merge(),
            ).await()
        }
    }

    /** تحديث مؤشّر القراءة — كلّ رسالة أقدم من هذا الطابع تُعتبر مقروءة منّي. */
    suspend fun markRead() {
        val me = uid
        if (me.isEmpty()) return
        quiet {
            members.document(me).set(
                mapOf("lastReadAtMs" to System.currentTimeMillis()),
                SetOptions.merge(),
            ).await()
        }
    }

    /** إشارة «يكتب الآن…» (تُستدعى مقنَّنة أثناء الكتابة). */
    suspend fun typingTick() {
        val me = uid
        if (me.isEmpty()) return
        quiet {
            members.document(me).set(
                mapOf("typingAtMs" to System.currentTimeMillis()),
                SetOptions.merge(),
            ).await()
        }
    }

    /** اختيار صورة شخصيّة للمجموعة (تظهر مع اسمي في الدردشة تلقائيّاً). */
    suspend fun setMyPhotoFromPath(filePath: String, filename: String) {
        val up = smartUploadFile(
            file = File(filePath),
            filename = filename,
            contentType = guessContentType(filename),
            folder = ChatPaths.avatarsFolder,
        )
        members.document(uid).set(
            mapOf(
                "customPhoto" to up.url,
                "customPhotoPath" to up.path,
            ),
            SetOptions.merge(),
        ).await()
    }

    /** إزالة صورتي المخصّصة (يعود لصورة Google). */
    suspend fun clearMyPhoto() {
        members.document(uid).set(
            mapOf(
                "customPhoto" to "",
                "customPhotoPath" to "",
            ),
            SetOptions.merge(),
        ).await()
    }

    /** تغيير اسمي المعروض في المجموعة (ويُعلِّم اكتمال إعداد الملفّ الشخصي). */
    suspend fun setMyName(name: String) {
        members.document(uid).set(
            mapOf(
                "customName" to name.trim(),
                "profileSet" to true,
            ),
            SetOptions.merge(),
        ).await()
    }

    /** جلب وثيقتي (لملء حوار الملفّ الشخصي وفحص أوّل مرّة). */
    suspend fun fetchSelf(): ChatMember? {
        val me = uid
        if (me.isEmpty()) return null
        return try {
            val doc = members.document(me).get().await()
            if (doc.exists()) ChatMember.fromDoc(doc) else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** تعيين/إزالة مشرف مجموعة ⭐ (صلاحيّات داخل الدردشة فقط) — للمالك. */
    suspend fun setChatRole(uid: String, role: String?) {
        val value: Any = if (role.isNullOrEmpty()) FieldValue.delete() else role
        members.document(uid).set(mapOf("chatRole" to value), SetOptions.merge()).await()
    }

    /**
     * تحديث دور التطبيق في وثيقة العضو (بعد ترقية/إنزال في RTDB) — للمالك،
     * كي يظهر وسام 👑 فوراً دون انتظار دخول المرقَّى.
     */
    suspend fun setMemberAppRole(uid: String, role: String) {
        quiet {
            // أفضل جهد — upsertSelf للمرقَّى سيصحّحها عند أوّل فتح.
            members.document(uid).set(mapOf("role" to role), SetOptions.merge()).await()
        }
    }

    /**
     * إزالة عضو من المجموعة — يستدعيها المالك عند إزالة/إيقاف مشرف من
     * شاشة إدارة المشرفين (الإزالة التلقائيّة المقابلة للإضافة التلقائيّة).
     */
    suspend fun removeMember(uid: String) {
        quiet {
            // أفضل جهد — فقدان الصلاحيّة يمنع وصوله للدردشة على أيّ حال.
            members.document(uid).delete().await()
        }
    }

    fun membersStream(): Flow<List<ChatMember>> = members
        .orderBy("name")
        .snapshotsFlow()
        .map { s -> s.documents.map { ChatMember.fromDoc(it) } }

    // ─── تنزيل الوسائط قبل التشغيل (يعمل بلا اتصال) ──────────

    /**
     * عميل تنزيل مستقلّ عن [com.nebras.dashboard.core.ApiClient]: مهلة قراءة
     * طويلة تكفي لمقاطع الفيديو الكبيرة، وبلا أيّ ترويسة مصادقة (روابط
     * التنزيل من Storage موقّعة بالـ token داخل الرابط نفسه).
     */
    private val mediaHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * تنزيل مرفق إلى `cacheDir` وإعادة الملفّ — **قبل** تسليمه للمشغّل.
     *
     * سبب الوجود: تشغيل الصوت/الفيديو من رابط الشبكة مباشرةً يفشل عند ضعف
     * الاتصال أو انقطاعه؛ بالتنزيل أوّلاً تعمل الوسائط لاحقاً بلا اتصال.
     * الملفّ المخزَّن يُعاد استعماله كما هو إن وُجد (المفتاح مشتقّ من مسار
     * Storage — أو الرابط عند غيابه — فلا يتعارض ملفّان).
     *
     * يرمي [IllegalStateException] برسالة عربيّة إن تعذّر التنزيل ولا نسخة
     * مخزَّنة.
     */
    suspend fun cachedMediaFile(
        context: Context,
        url: String,
        storagePath: String = "",
        filename: String = "",
    ): File = withContext(Dispatchers.IO) {
        require(url.isNotEmpty() || storagePath.isNotEmpty()) { "لا يوجد ملفّ للتنزيل." }

        val dir = File(context.cacheDir, "chat_media")
        if (!dir.exists()) dir.mkdirs()

        val key = cacheKey(if (storagePath.isNotEmpty()) storagePath else url)
        val ext = mediaExtension(filename, url)
        val target = File(dir, key + ext)
        if (target.isFile && target.length() > 0L) return@withContext target

        val tmp = File(dir, "$key$ext.part")
        if (tmp.exists()) tmp.delete()

        // المسار المفضَّل: Storage SDK (يحترم قواعد الأمان ويجدّد الرمز).
        val ref = try {
            if (storagePath.isNotEmpty()) {
                FirebaseStorage.getInstance().getReference(storagePath)
            } else {
                FirebaseStorage.getInstance().getReferenceFromUrl(url)
            }
        } catch (_: Exception) {
            null
        }

        var ok = false
        if (ref != null) {
            ok = try {
                ref.getFile(tmp).await()
                tmp.isFile && tmp.length() > 0L
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }

        if (!ok) {
            if (tmp.exists()) tmp.delete()
            if (url.isEmpty()) throw IllegalStateException("تعذّر تنزيل الملفّ.")
            httpDownload(url, tmp)
        }

        if (!tmp.renameTo(target)) {
            // بعض أنظمة الملفّات ترفض الإزاحة فوق ملفّ قائم — ننسخ ثم نحذف.
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        target
    }

    /** اختصار: تنزيل مرفق رسالة بحسب مساره واسمه. */
    suspend fun cachedMediaFile(context: Context, attachment: ChatAttachment): File =
        cachedMediaFile(
            context = context,
            url = attachment.url,
            storagePath = attachment.path,
            filename = attachment.name,
        )

    /** حذف كلّ الوسائط المخزَّنة مؤقّتاً (تُستدعى من إعدادات المجموعة عند الحاجة). */
    suspend fun clearMediaCache(context: Context) = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "chat_media")
        if (dir.isDirectory) dir.listFiles()?.forEach { it.delete() }
        Unit
    }

    private fun httpDownload(url: String, dest: File) {
        val request = Request.Builder().url(url).get().build()
        mediaHttp.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("تعذّر تنزيل الملفّ (${resp.code}).")
            }
            val body = resp.body ?: throw IllegalStateException("تعذّر تنزيل الملفّ.")
            dest.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        if (!dest.isFile || dest.length() <= 0L) {
            throw IllegalStateException("تعذّر تنزيل الملفّ.")
        }
    }

    /** مفتاح ثابت وآمن لاسم الملفّ (بصمة SHA-256 مقصوصة). */
    private fun cacheKey(source: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format(Locale.US, "%02x", b))
        return sb.substring(0, 40)
    }

    /** امتداد يُبقي المشغّل قادراً على استنتاج الصيغة (ExoPlayer يعتمد عليه). */
    private fun mediaExtension(filename: String, url: String): String {
        val fromName = shortExt(filename)
        if (fromName.isNotEmpty()) return ".$fromName"
        val path = url.substringBefore('?').substringBefore('#')
        val fromUrl = shortExt(path.substringAfterLast('/'))
        return if (fromUrl.isNotEmpty()) ".$fromUrl" else ".bin"
    }

    private fun shortExt(name: String): String {
        if (!name.contains('.')) return ""
        val raw = name.substringAfterLast('.').lowercase()
        val clean = raw.filter { it.isLetterOrDigit() }
        return if (clean.isEmpty() || clean.length > 5) "" else clean
    }
}
