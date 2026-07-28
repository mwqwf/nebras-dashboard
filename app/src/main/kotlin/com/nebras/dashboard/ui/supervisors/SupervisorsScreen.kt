package com.nebras.dashboard.ui.supervisors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.google.firebase.database.FirebaseDatabase
import com.nebras.dashboard.auth.AuthController
import com.nebras.dashboard.core.NebrasColors
import com.nebras.dashboard.ui.widgets.DashBadge
import com.nebras.dashboard.ui.widgets.DashCard
import com.nebras.dashboard.ui.widgets.DashEmptyState
import com.nebras.dashboard.ui.widgets.DashErrorState
import com.nebras.dashboard.ui.widgets.DashLoading
import com.nebras.dashboard.ui.widgets.DashTextAction
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val DASHBOARD_USERS = "dashboard_users"

private data class SupervisorRow(
    val uid: String,
    val email: String,
    val name: String,
    val role: String,
    val blocked: Boolean,
)

/**
 * إدارة المشرفين — نقل `ui/supervisors/supervisors_screen.dart`.
 *
 * الأدوار تُكتب في **Realtime Database** تحت `dashboard_users` (قواعد RTDB
 * تسمح بذلك للمالك)، و`auth/check` يزامن الـ claims تلقائياً عند الدخول
 * التالي — لا تُغيّر هذا المسار.
 */
@Composable
fun SupervisorsScreen(
    navController: NavHostController,
    authController: AuthController,
) {
    val auth by authController.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var rows by remember { mutableStateOf<List<SupervisorRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var confirming by remember { mutableStateOf<Pair<SupervisorRow, String>?>(null) }

    LaunchedEffect(reloadKey) {
        loading = true
        error = null
        runCatching {
            val snapshot = FirebaseDatabase.getInstance()
                .getReference(DASHBOARD_USERS)
                .get()
                .await()
            snapshot.children.mapNotNull { child ->
                val uid = child.key ?: return@mapNotNull null
                SupervisorRow(
                    uid = uid,
                    email = child.child("email").value?.toString().orEmpty(),
                    name = child.child("name").value?.toString().orEmpty(),
                    role = child.child("role").value?.toString() ?: "supervisor",
                    blocked = child.child("blocked").value == true,
                )
            }
        }.onSuccess { rows = it }
            .onFailure { error = it.message ?: "تعذّر تحميل المشرفين" }
        loading = false
    }

    if (!auth.isOwner) {
        DashEmptyState("هذه الصفحة متاحة للمالك فقط")
        return
    }
    if (loading) {
        DashLoading()
        return
    }
    error?.let {
        DashErrorState(it) { reloadKey++ }
        return
    }
    if (rows.isEmpty()) {
        DashEmptyState("لا يوجد مشرفون")
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(rows, key = { it.uid }) { row ->
            DashCard {
                Column {
                    Row {
                        Text(
                            text = row.name.ifEmpty { row.email },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = NebrasColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (row.role == "owner") {
                            DashBadge(text = "مالك 👑", color = NebrasColors.amber)
                        }
                        if (row.blocked) {
                            Spacer(Modifier.height(0.dp))
                            DashBadge(text = "محظور", color = NebrasColors.rose)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = row.email,
                        style = MaterialTheme.typography.labelSmall,
                        color = NebrasColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (row.uid != auth.user?.uid) {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DashTextAction(
                                text = if (row.role == "owner") "إنزال إلى مشرف" else "ترقية مالكاً",
                                onClick = {
                                    confirming = row to
                                        if (row.role == "owner") "supervisor" else "owner"
                                },
                            )
                            DashTextAction(
                                text = if (row.blocked) "رفع الحظر" else "حظر",
                                danger = !row.blocked,
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            FirebaseDatabase.getInstance()
                                                .getReference("$DASHBOARD_USERS/${row.uid}/blocked")
                                                .setValue(!row.blocked)
                                                .await()
                                        }
                                        reloadKey++
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    confirming?.let { (row, newRole) ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("تغيير الدور") },
            text = {
                Text(
                    if (newRole == "owner") {
                        "سيصبح «${row.name.ifEmpty { row.email }}» مالكاً بكامل الصلاحيات."
                    } else {
                        "سيصبح «${row.name.ifEmpty { row.email }}» مشرفاً عاديّاً."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                FirebaseDatabase.getInstance()
                                    .getReference("$DASHBOARD_USERS/${row.uid}/role")
                                    .setValue(newRole)
                                    .await()
                            }
                            confirming = null
                            reloadKey++
                        }
                    },
                ) {
                    Text("تأكيد")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("إلغاء") }
            },
        )
    }
}
