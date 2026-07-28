package com.nebras.dashboard.core

/**
 * ثوابت التهيئة العامّة للوحة تحكّم نبراس (نسخة Kotlin).
 *
 * في Flutter كانت القيم قابلة للتجاوز وقت البناء عبر `--dart-define`؛ هنا
 * القيم ثابتة في المصدر بنفس القيم الافتراضيّة لمشروع نبراس (nebras-9118c)
 * — لا يوجد نظير لـ dart-define دون تعديل `build.gradle.kts` (خارج النطاق).
 */
object AppConfig {

    /**
     * أصل لوحة الويب المنشورة على Vercel — منه تُستدعى نقاط الـ API الخادمية
     * (محرّكات IA/Noor/Hindawi، المشرفون، التقارير، الإشعارات…). بدون شرطة
     * نهائيّة.
     */
    const val backendBaseUrl: String = "https://nebras-dashboard-main.vercel.app"

    /**
     * Web OAuth client (نوع 3) — مطلوب ليُصدِر Credential Manager على Android
     * رمز ID صالحاً لـ Firebase Auth. نفس قيمة تطبيق نبراس المحمول.
     */
    const val googleServerClientId: String =
        "412379996427-0i55p23iunk93tilvje5442d6e42s4p9.apps.googleusercontent.com"

    /** قاعدة Firestore مُسمّاة باسم `default` (بدون أقواس) في مشروع نبراس. */
    const val firestoreDatabaseId: String = "default"
}
