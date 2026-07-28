package com.nebras.dashboard.core

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.firestore.FirebaseFirestore
import com.nebras.dashboard.data.AdminStats
import com.nebras.dashboard.data.ContentRepository
import com.nebras.dashboard.data.SectionsRepository
import com.nebras.dashboard.data.UnifiedFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * مُحدِّد الخدمات للوحة — بديل `get_it`/`provider` بلا أيّ مكتبة حقن.
 *
 * لوحة نبراس في Flutter لم تكن تستعمل `get_it`: المستودعات كانت مفردات
 * (`X.instance`) تُنشأ كسولاً، والتخزين المحلّي كان `SharedPreferences`.
 * هنا نُبقي نفس الشكل: المستودعات `object` تُستورد مباشرة، وهذا المُحدِّد
 * يقدّم ما يحتاج سياقاً فعليّاً (التخزين المحلّي، نطاق المهام الخلفيّة).
 *
 * [install] يُستدعى مرّة واحدة من `DashboardApplication.onCreate` قبل أيّ
 * نشاط أو خدمة، فكلّ ما تحت يكون جاهزاً في أيّ نقطة دخول (بما فيها خدمة
 * FCM التي تعمل والتطبيق مغلق).
 */
object ServiceLocator {

    /** ملفّ تفضيلات اللوحة — نفس أسماء مفاتيح نسخة Flutter حرفاً بحرف. */
    private const val PREFS_NAME: String = "nebras_dashboard_prefs"

    /** ملفّ `shared_preferences` الذي كتبته نسخة Flutter (للترحيل مرّة واحدة). */
    private const val FLUTTER_PREFS_NAME: String = "FlutterSharedPreferences"
    private const val FLUTTER_KEY_PREFIX: String = "flutter."
    private const val MIGRATED_FLAG: String = "_migrated_from_flutter"

    lateinit var appContext: Context
        private set

    /** نطاق للمهام الخلفيّة القصيرة التي لا تتبع دورة حياة شاشة. */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    /** التخزين المحلّي (بديل `SharedPreferences` في Dart). */
    val prefs: SharedPreferences by lazy { prefsOf(appContext) }

    /**
     * نفس الملفّ لكن بسياق صريح — تستعمله خدمة FCM قبل أن تلمس [appContext]
     * (احتياط: خدمة الرسائل قد تُنشَأ في أضيق الحالات قبل اكتمال التهيئة).
     */
    fun prefsOf(context: Context): SharedPreferences {
        val store = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migrateFromFlutterOnce(context.applicationContext, store)
        return store
    }

    val firestore: FirebaseFirestore get() = FirestoreRefs.db

    // ─── المستودعات (كلّها مفردات `object` — تُقدَّم هنا للتوحيد فقط) ───

    val contentRepository: ContentRepository get() = ContentRepository

    val sectionsRepository: SectionsRepository get() = SectionsRepository

    val unifiedFirestore: UnifiedFirestore get() = UnifiedFirestore

    val adminStats: AdminStats get() = AdminStats

    fun install(context: Context) {
        appContext = context.applicationContext
        // قراءة واحدة تُهيّئ الملفّ وتُنجز الترحيل قبل أوّل استعمال.
        prefs
    }

    /**
     * ترحيل مفاتيح `flutter.` من ملفّ shared_preferences القديم إلى ملفّنا
     * (بلا حذف الأصل، للتراجع). يُنفَّذ مرّة واحدة فقط: من رقّى اللوحة من
     * نسخة Flutter يبقى داخلاً بجلسته ووضع العرض الذي اختاره.
     */
    private fun migrateFromFlutterOnce(context: Context, store: SharedPreferences) {
        if (store.getBoolean(MIGRATED_FLAG, false)) return
        runCatching {
            val legacy = context.getSharedPreferences(FLUTTER_PREFS_NAME, Context.MODE_PRIVATE)
            val editor = store.edit()
            for ((key, value) in legacy.all) {
                if (!key.startsWith(FLUTTER_KEY_PREFIX)) continue
                val plain = key.removePrefix(FLUTTER_KEY_PREFIX)
                when (value) {
                    is Boolean -> editor.putBoolean(plain, value)
                    is String -> editor.putString(plain, value)
                    is Int -> editor.putInt(plain, value)
                    is Long -> editor.putLong(plain, value)
                    is Float -> editor.putFloat(plain, value)
                    else -> Unit
                }
            }
            editor.putBoolean(MIGRATED_FLAG, true).apply()
        }.onFailure {
            // فشل الترحيل ليس خطأً قاتلاً: تُعامَل اللوحة كتثبيت جديد.
            store.edit().putBoolean(MIGRATED_FLAG, true).apply()
        }
    }
}
