package com.nebras.dashboard.services

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.nebras.dashboard.MainActivity
import com.nebras.dashboard.core.FirestoreRefs
import com.nebras.dashboard.core.ServiceLocator
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * إشعارات لوحة نبراس — دردشة الإدارة + المحتوى الجديد.
 *
 * الخادم يرسل رسائل FCM **data-only** (بلا كتلة notification) كي يتحكّم
 * التطبيق بالعرض: نبني الإشعار محليّاً عبر [NotificationCompat]، ونحترم
 * الكتم، ولا نُظهر إشعاراً لرسائلي أنا أو وشاشة الدردشة مفتوحة.
 *
 * فرق منصّة واحد عن نسخة Flutter: في أندرويد الأصليّ تصل رسائل data-only
 * إلى `DashboardMessagingService` في المقدّمة والخلفيّة معاً، فلا حاجة إلى
 * مستمع `onMessage` منفصل — [appInForeground] يميّز الحالتين.
 */
object NotificationsService {

    const val ADMIN_CHAT_TOPIC: String = "nebras_admin_chat"
    const val MODERATOR_REPORTS_TOPIC: String = "nebras_moderator_reports"
    const val CHAT_MUTE_PREF: String = "nebras_chat_muted"

    /** قناة الإشعارات — نفس المعرّف المصرَّح به في AndroidManifest. */
    const val CHANNEL_ID: String = "nebras_dashboard_chat"
    private const val CHANNEL_NAME: String = "دردشة الإدارة"
    private const val CHANNEL_DESCRIPTION: String =
        "رسائل مجموعة إدارة نبراس وإشعارات المحتوى."

    private var inited: Boolean = false

    /**
     * شاشة الدردشة مفتوحة الآن — نظير `ChatScreen.isOpen` في Dart. تضبطها
     * شاشة الدردشة عند الدخول/الخروج.
     */
    @Volatile
    var chatScreenOpen: Boolean = false

    /** اللوحة في المقدّمة — يضبطها `MainActivity` في onResume/onPause. */
    @Volatile
    var appInForeground: Boolean = false

    /** إنشاء قناة الإشعارات — يُستدعى من `DashboardApplication`. */
    fun ensureChannel(context: Context) {
        runCatching {
            val channel = NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(CHANNEL_NAME)
                .setDescription(CHANNEL_DESCRIPTION)
                .build()
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }
    }

    /** تهيئة كاملة — تُستدعى بعد اعتماد الحساب (نظير استدعاء DashboardShell). */
    fun init(context: Context) {
        if (inited) return
        inited = true
        runCatching {
            ensureChannel(context)
            syncSubscription()
            saveToken()
        }
        // الإشعارات كماليّة — لا تُسقط اللوحة.
    }

    /** مزامنة الاشتراك في topic الدردشة مع تفضيل الكتم. */
    fun syncSubscription() {
        val muted = isMuted()
        runCatching {
            val messaging = FirebaseMessaging.getInstance()
            if (muted) {
                messaging.unsubscribeFromTopic(ADMIN_CHAT_TOPIC)
            } else {
                messaging.subscribeToTopic(ADMIN_CHAT_TOPIC)
            }
            // بلاغات المحتوى ليست رسائل دردشة: لا يجعل كتم الدردشة المشرف يفوّت
            // مخالفة تحتاج إلى استجابة سريعة.
            messaging.subscribeToTopic(MODERATOR_REPORTS_TOPIC)
        }
    }

    fun isMuted(): Boolean =
        runCatching { ServiceLocator.prefs.getBoolean(CHAT_MUTE_PREF, false) }.getOrDefault(false)

    fun setMuted(muted: Boolean) {
        runCatching { ServiceLocator.prefs.edit().putBoolean(CHAT_MUTE_PREF, muted).apply() }
        syncSubscription()
    }

    /** حفظ token في وثيقة العضو (لاستهداف مستقبلي مباشر إن لزم). */
    fun saveToken() {
        ServiceLocator.appScope.launch {
            runCatching {
                val token = FirebaseMessaging.getInstance().token.await()
                if (!token.isNullOrEmpty()) saveToken(token)
            }
        }
    }

    /**
     * كتابة token في `admin_chat_members/{uid}` — نظير
     * `ChatRepository.saveFcmToken`. تُستدعى عند الإقلاع وعند تجديد الرمز.
     */
    fun saveToken(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (uid.isEmpty() || token.isEmpty()) return
        runCatching {
            FirestoreRefs.chatMembers
                .document(uid)
                .set(mapOf("fcmToken" to token), SetOptions.merge())
        }
    }

    /**
     * عرض إشعار محلّي من حمولة data (خلفية أو مقدمة).
     *
     * [foreground] افتراضه حالة اللوحة الفعليّة: في أندرويد الأصليّ تصل
     * الرسالة إلى الخدمة نفسها في الحالتين.
     */
    fun showFromData(
        context: Context,
        data: Map<String, String>,
        foreground: Boolean = appInForeground,
    ) {
        val title = (data["title"] ?: "").trim()
        val body = (data["body"] ?: "").trim()
        if (title.isEmpty() && body.isEmpty()) return
        val isModerationAlert = data["kind"] == "content_report"

        // لا إشعار لرسائلي أنا.
        val senderId = data["senderId"] ?: ""
        val myUid = runCatching {
            FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        }.getOrDefault("")
        if (senderId.isNotEmpty() && senderId == myUid) return

        // احترام الكتم (يشمل الخلفيّة — رسائل data-only لا يعرضها النظام وحده).
        runCatching {
            val muted = ServiceLocator.prefsOf(context).getBoolean(CHAT_MUTE_PREF, false)
            if (!isModerationAlert && muted) return
        }

        // في المقدمة وشاشة الدردشة مفتوحة: المستخدم يرى الرسالة أصلاً.
        if (foreground && !isModerationAlert && chatScreenOpen) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        runCatching {
            ensureChannel(context)
            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pending = PendingIntent.getActivity(
                context,
                0,
                open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                // أيقونة النظام: لا نُضيف موارد جديدة إلى المشروع.
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title.ifEmpty { "نبراس" })
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            NotificationManagerCompat.from(context).notify(
                (System.currentTimeMillis() / 1000L).toInt(),
                notification,
            )
        }
    }

    // ─── إرسال إشعارات عبر خادم اللوحة (fire-and-forget) ──────

    /** إشعار أعضاء الدردشة برسالة جديدة (يُستدعى بعد نجاح الإرسال). */
    fun notifyChatMessage(senderName: String, preview: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        ServiceLocator.appScope.launch {
            runCatching {
                ServerApi.notify(
                    mapOf(
                        "title" to senderName,
                        "body" to preview,
                        "topic" to ADMIN_CHAT_TOPIC,
                        "data" to mapOf("kind" to "admin_chat", "senderId" to uid),
                    ),
                )
            }
        }
    }

    /** إشعار متابعي قسم بمحتوى جديد (يُستدعى بعد نجاح الرفع السريع). */
    fun notifySectionUpload(sectionId: String, sectionName: String, title: String) {
        if (sectionId.isEmpty()) return
        ServiceLocator.appScope.launch {
            runCatching {
                ServerApi.notify(
                    mapOf(
                        "title" to "جديد في $sectionName ✨",
                        "body" to title,
                        "topic" to "nebras_section_$sectionId",
                        "data" to mapOf("kind" to "section_content", "sectionId" to sectionId),
                    ),
                )
            }
        }
    }
}
