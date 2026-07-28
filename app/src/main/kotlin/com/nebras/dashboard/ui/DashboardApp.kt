package com.nebras.dashboard.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nebras.dashboard.auth.AuthController
import com.nebras.dashboard.ui.chat.ChatScreen
import com.nebras.dashboard.ui.community.CommunityModerationScreen
import com.nebras.dashboard.ui.content.ContentFilesScreen
import com.nebras.dashboard.ui.content.QuickUploadScreen
import com.nebras.dashboard.ui.content.UploadScreen
import com.nebras.dashboard.ui.content_audit.ContentAuditScreen
import com.nebras.dashboard.ui.home.HomeScreen
import com.nebras.dashboard.ui.honor.HonorBoardScreen
import com.nebras.dashboard.ui.login.LoginScreen
import com.nebras.dashboard.ui.reports.ReportsScreen
import com.nebras.dashboard.ui.sections.SectionsScreen
import com.nebras.dashboard.ui.shell.DashboardShell
import com.nebras.dashboard.ui.statistics.StatisticsScreen
import com.nebras.dashboard.ui.supervisors.SupervisorsScreen

/** مسارات اللوحة — بديل مسارات SvelteKit/go_router. */
object DashboardRoutes {
    const val HOME = "home"
    const val UPLOAD = "upload"
    const val QUICK_UPLOAD = "quick_upload"
    const val CONTENT_FILES = "content_files"
    const val SECTIONS = "sections"
    const val CHAT = "chat"
    const val STATISTICS = "statistics"
    const val COMMUNITY = "community"
    const val REPORTS = "reports"
    const val CONTENT_AUDIT = "content_audit"
    const val HONOR = "honor"
    const val SUPERVISORS = "supervisors"
}

/**
 * جذر اللوحة — مقابل `app.dart`.
 *
 * ثلاث حالات: تحميل الجلسة، شاشة الدخول (غير مُعتمَد)، ثمّ الهيكل الكامل.
 * الاعتماد يشمل التحقّق من الدور عبر `auth/check` — انظر [AuthController].
 */
@Composable
fun DashboardApp(authController: AuthController) {
    val state by authController.state.collectAsStateWithLifecycle()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when {
            state.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            !state.authorized -> LoginScreen(authController = authController)

            else -> {
                val navController = rememberNavController()
                DashboardShell(
                    navController = navController,
                    authController = authController,
                ) { innerModifier ->
                    DashboardNavHost(
                        navController = navController,
                        authController = authController,
                        modifier = innerModifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardNavHost(
    navController: NavHostController,
    authController: AuthController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = DashboardRoutes.HOME,
        modifier = modifier,
    ) {
        composable(DashboardRoutes.HOME) {
            HomeScreen(navController = navController, authController = authController)
        }
        composable(DashboardRoutes.UPLOAD) {
            UploadScreen(navController = navController, authController = authController)
        }
        composable(DashboardRoutes.QUICK_UPLOAD) {
            QuickUploadScreen(navController = navController, authController = authController)
        }
        composable(DashboardRoutes.CONTENT_FILES) {
            ContentFilesScreen(navController = navController, authController = authController)
        }
        composable(DashboardRoutes.SECTIONS) {
            SectionsScreen(navController = navController, authController = authController)
        }
        composable(DashboardRoutes.CHAT) {
            ChatScreen(navController = navController, authController = authController)
        }
        composable(DashboardRoutes.STATISTICS) {
            StatisticsScreen(navController = navController)
        }
        composable(DashboardRoutes.COMMUNITY) {
            CommunityModerationScreen(
                navController = navController,
                authController = authController,
            )
        }
        composable(DashboardRoutes.REPORTS) {
            ReportsScreen(navController = navController)
        }
        composable(DashboardRoutes.CONTENT_AUDIT) {
            ContentAuditScreen(navController = navController)
        }
        composable(DashboardRoutes.HONOR) {
            HonorBoardScreen(navController = navController)
        }
        composable(DashboardRoutes.SUPERVISORS) {
            SupervisorsScreen(navController = navController, authController = authController)
        }
    }
}
