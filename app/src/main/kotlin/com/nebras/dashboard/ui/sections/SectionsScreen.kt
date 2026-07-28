package com.nebras.dashboard.ui.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.nebras.dashboard.auth.AuthController
import com.nebras.dashboard.core.NebrasColors
import com.nebras.dashboard.data.SectionLevel
import com.nebras.dashboard.data.SectionsRepository
import com.nebras.dashboard.ui.widgets.DashBadge
import com.nebras.dashboard.ui.widgets.DashCard
import com.nebras.dashboard.ui.widgets.DashEmptyState
import com.nebras.dashboard.ui.widgets.DashErrorState
import com.nebras.dashboard.ui.widgets.DashLoading
import com.nebras.dashboard.ui.widgets.DashPrimaryButton
import com.nebras.dashboard.ui.widgets.DashSearchField
import kotlinx.coroutines.launch

private val LEVEL_TABS = listOf(
    SectionLevel.main to "رئيسية",
    SectionLevel.sub to "فرعية",
    SectionLevel.secondary to "ثانوية",
)

/**
 * إدارة الأقسام بمستوياتها الثلاثة — نقل `ui/sections/sections_screen.dart`.
 *
 * ⚠️ **حذف القسم تعاقبيّ شامل**: [SectionsRepository.remove] يحذف القسم
 * وكلّ أبنائه **وكلّ المحتوى تحته** (وثائق ومرايا وملفّات تخزين). الحوار
 * يوضّح ذلك صراحةً قبل التنفيذ — لا تُخفّف هذه الدلالة.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionsScreen(
    navController: NavHostController,
    authController: AuthController,
) {
    val scope = rememberCoroutineScope()

    var tabIndex by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var deleting by remember { mutableStateOf<Map<String, Any?>?>(null) }

    // قوائم الآباء لاختيارها عند الإنشاء.
    var parents by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }

    val level = LEVEL_TABS[tabIndex].first

    LaunchedEffect(level, search, reloadKey) {
        loading = true
        error = null
        runCatching {
            when (level) {
                SectionLevel.main -> SectionsRepository.listMain(search)
                SectionLevel.sub -> SectionsRepository.listSub(search = search)
                SectionLevel.secondary -> SectionsRepository.listSecondary(search = search)
            }
        }.onSuccess { rows = it }
            .onFailure { error = it.message ?: "تعذّر تحميل الأقسام" }

        // الآباء المتاحون للمستوى الحاليّ.
        parents = runCatching {
            when (level) {
                SectionLevel.main -> emptyList()
                SectionLevel.sub -> SectionsRepository.listMain()
                SectionLevel.secondary -> SectionsRepository.listSub()
            }
        }.getOrDefault(emptyList())

        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        SecondaryTabRow(selectedTabIndex = tabIndex) {
            LEVEL_TABS.forEachIndexed { index, (_, label) ->
                Tab(
                    selected = tabIndex == index,
                    onClick = { tabIndex = index },
                    text = { Text(label) },
                )
            }
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            DashSearchField(
                value = search,
                onValueChange = { search = it },
                placeholder = "ابحث في الأقسام…",
            )
            Spacer(Modifier.height(10.dp))
            DashPrimaryButton(
                text = "إضافة قسم",
                icon = Icons.Default.Add,
                onClick = { creating = true },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))

            when {
                loading -> DashLoading()
                error != null -> DashErrorState(error!!) { reloadKey++ }
                rows.isEmpty() -> DashEmptyState("لا توجد أقسام في هذا المستوى")
                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(rows, key = { "${it["id"]}" }) { row ->
                        SectionCard(
                            row = row,
                            onEdit = { editing = row },
                            onDelete = { deleting = row },
                            onToggleListed = {
                                scope.launch {
                                    val listed = (row["is_listed"] ?: true) == true
                                    runCatching {
                                        SectionsRepository.update(
                                            level = level,
                                            id = row["id"],
                                            isListed = !listed,
                                        )
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

    if (creating) {
        CreateSectionDialog(
            level = level,
            parents = parents,
            onDismiss = { creating = false },
            onCreate = { name, parentId ->
                scope.launch {
                    runCatching {
                        when (level) {
                            SectionLevel.main -> SectionsRepository.createMain(name = name)
                            SectionLevel.sub -> SectionsRepository.createSub(
                                name = name,
                                mainSection = parentId,
                            )
                            SectionLevel.secondary -> SectionsRepository.createSecondary(
                                name = name,
                                subSection = parentId,
                            )
                        }
                    }
                    creating = false
                    reloadKey++
                }
            },
        )
    }

    editing?.let { row ->
        EditSectionDialog(
            row = row,
            onDismiss = { editing = null },
            onSave = { name ->
                scope.launch {
                    runCatching {
                        SectionsRepository.update(level = level, id = row["id"], name = name)
                    }
                    editing = null
                    reloadKey++
                }
            },
        )
    }

    deleting?.let { row ->
        DeleteSectionDialog(
            name = row["name"]?.toString().orEmpty(),
            level = level,
            onDismiss = { deleting = null },
            onConfirm = {
                scope.launch {
                    runCatching { SectionsRepository.remove(level, row["id"]) }
                    deleting = null
                    reloadKey++
                }
            },
        )
    }
}

@Composable
private fun SectionCard(
    row: Map<String, Any?>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleListed: () -> Unit,
) {
    val listed = (row["is_listed"] ?: true) == true
    DashCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = row["name"]?.toString().orEmpty(),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = NebrasColors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "المعرّف: ${row["id"]}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NebrasColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!listed) {
                    Spacer(Modifier.height(6.dp))
                    DashBadge(text = "مخفي", color = NebrasColors.amber)
                }
            }
            IconButton(onClick = onToggleListed) {
                Icon(
                    imageVector = if (listed) {
                        Icons.Default.Visibility
                    } else {
                        Icons.Default.VisibilityOff
                    },
                    contentDescription = if (listed) "إخفاء" else "إظهار",
                    tint = NebrasColors.textMuted,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "تعديل", tint = NebrasColors.emerald)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "حذف", tint = NebrasColors.rose)
            }
        }
    }
}
