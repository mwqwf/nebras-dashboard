package com.nebras.dashboard.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Groups2
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import coil3.compose.AsyncImage
import com.nebras.dashboard.auth.AuthController
import com.nebras.dashboard.core.NebrasColors
import com.nebras.dashboard.ui.DashboardRoutes
import com.nebras.dashboard.ui.widgets.DashBadge
import kotlinx.coroutines.launch

/** عنصر تنقّل في الشريط الجانبيّ. */
private data class NavItem(
    val title: String,
    val icon: ImageVector,
    val route: String,
    val ownerOnly: Boolean = false,
)

/** نفس ترتيب وأسماء عناصر `dashboard_shell.dart` حرفياً. */
private val NAV_ITEMS = listOf(
    NavItem("لوحة القيادة", Icons.Default.Dashboard, DashboardRoutes.HOME),
    NavItem("الأقسام", Icons.Default.AccountTree, DashboardRoutes.SECTIONS),
    NavItem("المحتوى", Icons.Default.FolderOpen, DashboardRoutes.CONTENT_FILES),
    NavItem("رفع متعدد", Icons.Default.CloudUpload, DashboardRoutes.UPLOAD),
    NavItem("الدردشة", Icons.Default.ChatBubbleOutline, DashboardRoutes.CHAT),
    NavItem("لوحة الشرف", Icons.Default.EmojiEvents, DashboardRoutes.HONOR),
    NavItem(
        "إدارة المشرفين",
        Icons.Default.Group,
        DashboardRoutes.SUPERVISORS,
        ownerOnly = true,
    ),
    NavItem("الإحصاءات", Icons.Default.BarChart, DashboardRoutes.STATISTICS),
    NavItem("بلاغات المحتوى", Icons.Default.Flag, DashboardRoutes.REPORTS),
    NavItem("إشراف المجتمع", Icons.Default.Groups2, DashboardRoutes.COMMUNITY),
    NavItem("تدقيق المحتوى", Icons.Default.FactCheck, DashboardRoutes.CONTENT_AUDIT),
)

/**
 * هيكل اللوحة — نقل `ui/shell/dashboard_shell.dart`.
 *
 * على الجوّال يصير الشريط الجانبيّ درجاً منزلقاً (بديل Sidebar الثابت في
 * نسخة الويب/الفلاتر العريضة)، مع نفس العناصر والترتيب والصلاحيات:
 * «إدارة المشرفين» للمالك فقط، وتبديل وضع العرض admin/moderator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardShell(
    navController: NavHostController,
    authController: AuthController,
    content: @Composable (Modifier) -> Unit,
) {
    val state by authController.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: DashboardRoutes.HOME

    val visibleItems = NAV_ITEMS.filter { !it.ownerOnly || state.isOwner }
    val currentTitle = NAV_ITEMS.firstOrNull { it.route == currentRoute }?.title
        ?: "لوحة نبراس"

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = NebrasColors.sidebarBg) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ShellHeader(
                        displayName = state.displayName.ifEmpty { "مشرف" },
                        email = state.email,
                        photoUrl = state.photoUrl,
                        isOwner = state.isOwner,
                    )
                    Spacer(Modifier.height(8.dp))

                    visibleItems.forEach { item ->
                        NavRow(
                            item = item,
                            selected = currentRoute == item.route,
                            onClick = {
                                scope.launch { drawerState.close() }
                                if (currentRoute != item.route) {
                                    navController.navigate(item.route) {
                                        popUpTo(DashboardRoutes.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // تبديل وضع العرض admin ↔ moderator (تفضيل محلّيّ).
                    NavActionRow(
                        title = if (state.viewMode == "admin") {
                            "وضع العرض: مدير"
                        } else {
                            "وضع العرض: مشرف"
                        },
                        icon = Icons.Default.SwapHoriz,
                        onClick = authController::toggleViewMode,
                    )

                    NavActionRow(
                        title = "تسجيل الخروج",
                        icon = Icons.AutoMirrored.Filled.Logout,
                        tint = NebrasColors.rose,
                        onClick = {
                            scope.launch { drawerState.close() }
                            authController.signOut()
                        },
                    )

                    Spacer(Modifier.height(24.dp))
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(currentTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "القائمة")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = NebrasColors.sidebarBg,
                        titleContentColor = NebrasColors.textPrimary,
                        navigationIconContentColor = NebrasColors.textPrimary,
                    ),
                )
            },
        ) { padding ->
            content(Modifier.fillMaxSize().padding(padding))
        }
    }
}

@Composable
private fun ShellHeader(
    displayName: String,
    email: String,
    photoUrl: String,
    isOwner: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (photoUrl.isNotEmpty()) {
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(NebrasColors.emerald.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = displayName.take(1),
                    color = NebrasColors.emerald,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = NebrasColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isOwner) {
                    Spacer(Modifier.width(6.dp))
                    DashBadge(text = "مالك", color = NebrasColors.amber)
                }
            }
            Text(
                text = email,
                style = MaterialTheme.typography.bodySmall,
                color = NebrasColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NavRow(item: NavItem, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) NebrasColors.sidebarActive else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            item.icon,
            contentDescription = null,
            tint = if (selected) NebrasColors.emerald else NebrasColors.textMuted,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (selected) NebrasColors.textPrimary else NebrasColors.textMuted,
        )
    }
}

@Composable
private fun NavActionRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    tint: Color = NebrasColors.textMuted,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}
