package com.nebras.dashboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nebras.dashboard.auth.AuthController
import com.nebras.dashboard.core.DashboardTheme
import com.nebras.dashboard.services.NotificationsService
import com.nebras.dashboard.services.ShareReceiver
import com.nebras.dashboard.ui.DashboardApp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * المضيف الوحيد لواجهة Compose — مقابل `NebrasDashboardApp` في `app.dart`.
 *
 * `AuthController` مربوط بدورة حياة النشاط (نظير `ChangeNotifierProvider`
 * فوق الجذر في `main.dart`)، وشاشة الدخول/اللوحة يختارهما `DashboardApp`
 * وفق حالته.
 */
class MainActivity : ComponentActivity() {

    private val authController: AuthController by viewModels()

    /**
     * إذن الإشعارات على أندرويد 13+ — نظير
     * `FirebaseMessaging.instance.requestPermission()` في `NotificationsService.init`.
     */
    private val notificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // «مشاركة إلى نبراس»: قد تصل المشاركة مع Intent الإقلاع.
        ShareReceiver.handleIntent(this, intent)
        requestNotificationsPermissionIfNeeded()

        // الإشعارات تُهيَّأ بعد اعتماد الحساب فقط (نظير استدعائها من
        // DashboardShell في نسخة Flutter): قبل الاعتماد لا عضويّة ولا token.
        lifecycleScope.launch {
            authController.state
                .map { it.authorized }
                .distinctUntilChanged()
                .collect { authorized ->
                    if (authorized) NotificationsService.init(applicationContext)
                }
        }

        setContent {
            DashboardTheme {
                DashboardApp(authController = authController)
            }
        }
    }

    /** اللوحة `singleTop`: المشاركات تصل هنا وهي مفتوحة. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ShareReceiver.handleIntent(this, intent)
    }

    override fun onResume() {
        super.onResume()
        NotificationsService.appInForeground = true
    }

    override fun onPause() {
        NotificationsService.appInForeground = false
        super.onPause()
    }

    private fun requestNotificationsPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching {
                notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
