package com.nebras.dashboard.services

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * استقبال رسائل FCM — مقابل `nebrasFcmBackgroundHandler` ومستمع
 * `FirebaseMessaging.onMessage` معاً: في أندرويد الأصليّ تصل رسائل
 * **data-only** إلى [onMessageReceived] في المقدّمة والخلفيّة على السواء،
 * فمعالج واحد يكفي.
 *
 * الخادم يرسل بلا كتلة notification عمداً، فلا يعرضها النظام تلقائيّاً؛
 * [NotificationsService.showFromData] وحده يقرّر العرض (احترام الكتم، تجاهل
 * رسائلي أنا، وعدم الإزعاج وشاشة الدردشة مفتوحة).
 */
class DashboardMessagingService : FirebaseMessagingService() {

    private companion object {
        const val TAG = "NebrasDashFCM"
    }

    override fun onMessageReceived(message: RemoteMessage) {
        NotificationsService.showFromData(this, message.data)
    }

    override fun onNewToken(token: String) {
        // تجديد الرمز: يُكتب في وثيقة العضو (نظير onTokenRefresh في Dart).
        Log.d(TAG, "FCM token refreshed")
        NotificationsService.saveToken(token)
    }
}
