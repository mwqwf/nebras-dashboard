package com.nebras.dashboard

import android.app.Application
import com.google.firebase.FirebaseApp
import com.nebras.dashboard.core.ServiceLocator
import com.nebras.dashboard.services.NotificationsService

/**
 * نقطة الإقلاع — مقابل `main()` في `lib/main.dart`.
 *
 * الترتيب مقصود ومطابق لنسخة Flutter:
 *   1. تهيئة Firebase من `google-services.json` (يُختار التطبيق المطابق
 *      لـ applicationId تلقائيّاً).
 *   2. تهيئة مُحدِّد الخدمات (التخزين المحلّي + نطاق المهام الخلفيّة) قبل أيّ
 *      نشاط أو خدمة FCM.
 *   3. إنشاء قناة الإشعارات — رسائل اللوحة data-only فنعرضها محليّاً، والقناة
 *      يجب أن توجد قبل وصول أوّل رسالة والتطبيق مغلق.
 *
 * لا يوجد نظير لـ `onBackgroundMessage`: في أندرويد الأصليّ تصل رسائل
 * data-only إلى `DashboardMessagingService` في الحالتين.
 */
class DashboardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        ServiceLocator.install(this)
        NotificationsService.ensureChannel(this)
    }
}
