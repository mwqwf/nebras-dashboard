package com.nebras.dashboard.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.nebras.dashboard.auth.AuthController
import com.nebras.dashboard.core.NebrasColors
import com.nebras.dashboard.data.AdminStats
import com.nebras.dashboard.data.ContentRepository
import com.nebras.dashboard.data.SectionsRepository
import com.nebras.dashboard.data.contentTypeFromMime
import com.nebras.dashboard.services.ShareReceiver
import com.nebras.dashboard.ui.widgets.DashCard
import com.nebras.dashboard.ui.widgets.DashEmptyState
import com.nebras.dashboard.ui.widgets.DashPrimaryButton
import com.nebras.dashboard.ui.widgets.DashSectionTitle
import com.nebras.dashboard.ui.widgets.DashTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * الرفع السريع — نقل `ui/content/quick_upload_screen.dart`.
 *
 * مدخلها الأساسيّ «مشاركة إلى نبراس»: ملفّ يصل من تطبيق آخر عبر
 * [ShareReceiver] فيُملأ النموذج تلقائياً، ويكفي اختيار القسم والضغط رفع.
 */
@Composable
fun QuickUploadScreen(
    navController: NavHostController,
    authController: AuthController,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val shared by ShareReceiver.pending.collectAsStateWithLifecycle()

    var mainSections by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var subSections by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var selectedMain by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var selectedSub by remember { mutableStateOf<Map<String, Any?>?>(null) }

    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { SectionsRepository.listMain() }.onSuccess { mainSections = it }
    }
    LaunchedEffect(selectedMain) {
        selectedSub = null
        subSections = selectedMain?.let { main ->
            runCatching { SectionsRepository.listSub(mainSection = main["id"]) }
                .getOrDefault(emptyList())
        } ?: emptyList()
    }
    // ملء العنوان تلقائياً من اسم الملفّ المشترَك.
    LaunchedEffect(shared) {
        shared.firstOrNull()?.let { file ->
            if (title.isEmpty()) title = file.name.substringBeforeLast('.')
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DashSectionTitle(title = "رفع سريع") }

        if (shared.isEmpty()) {
            item {
                DashEmptyState(
                    "لا يوجد ملفّ مشترَك.\nشارك ملفّاً من أيّ تطبيق واختر «لوحة نبراس».",
                )
            }
            return@LazyColumn
        }

        items(shared.size) { index ->
            val file = shared[index]
            DashCard {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = NebrasColors.textPrimary,
                )
                Text(
                    text = file.mime.ifEmpty { "غير معروف" },
                    style = MaterialTheme.typography.bodySmall,
                    color = NebrasColors.textMuted,
                )
            }
        }

        item { DashTextField(value = title, onValueChange = { title = it }, label = "العنوان") }
        item { DashTextField(value = author, onValueChange = { author = it }, label = "المؤلّف") }
        item {
            DashTextField(
                value = description,
                onValueChange = { description = it },
                label = "الوصف",
                singleLine = false,
                minLines = 3,
            )
        }
        item {
            SectionDropdown(
                label = "القسم الرئيسي",
                options = mainSections,
                selected = selectedMain,
                onSelect = { selectedMain = it },
            )
        }
        item {
            SectionDropdown(
                label = "القسم الفرعي",
                options = subSections,
                selected = selectedSub,
                enabled = selectedMain != null,
                onSelect = { selectedSub = it },
            )
        }

        item {
            DashPrimaryButton(
                text = "رفع",
                loading = busy,
                enabled = selectedSub != null && title.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        message = null
                        var ok = 0
                        shared.forEachIndexed { index, sharedFile ->
                            val bytes = withContext(Dispatchers.IO) {
                                runCatching { File(sharedFile.path).readBytes() }.getOrNull()
                            } ?: return@forEachIndexed
                            runCatching {
                                ContentRepository.createContent(
                                    bytes = bytes,
                                    filename = sharedFile.name,
                                    mimeType = sharedFile.mime.ifEmpty {
                                        "application/octet-stream"
                                    },
                                    metadata = mapOf(
                                        "title" to title,
                                        "author" to author,
                                        "description" to description,
                                        "content_type" to contentTypeFromMime(sharedFile.mime),
                                        "main_section" to selectedMain?.get("id"),
                                        "subsection" to selectedSub?.get("id"),
                                        "is_listed" to true,
                                        "selectionOrder" to index,
                                    ),
                                )
                            }.onSuccess { ok++ }
                        }
                        if (ok > 0) {
                            runCatching { AdminStats.bumpUpload() }
                            ShareReceiver.consume()
                        }
                        busy = false
                        message = if (ok > 0) "تمّ رفع $ok ملفّاً" else "تعذّر الرفع"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        message?.let {
            item {
                Text(it, color = NebrasColors.emerald, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
