package com.nebras.dashboard.services

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * حمولة «مشاركة إلى نبراس»: ملفّ (مسار منسوخ إلى cache) أو نصّ/رابط.
 *
 * [path] و[name] فارغتان عند مشاركة نصّ فقط، و[text] فارغ (null) عند
 * مشاركة ملفّ بلا تعليق — نظير `SharedPayload{path, text}` في Dart.
 */
data class SharedFile(
    val path: String,
    val name: String,
    val mime: String,
    val text: String?,
) {
    val isEmpty: Boolean get() = path.isEmpty() && text.isNullOrEmpty()
}

/**
 * استقبال المشاركات الواردة — بديل أصليّ خفيف عن MethodChannel وعن حزمة
 * receive_sharing_intent: نقرأ الـ Intent مباشرة (`ACTION_SEND` /
 * `ACTION_SEND_MULTIPLE`) وننسخ كلّ `content://` إلى ملفّ في الكاش (المُرسِل
 * قد يُلغي إذن القراءة بعد لحظات)، ثمّ نعرض النتيجة كـ [StateFlow] تستهلكه
 * شاشة الرفع السريع.
 */
object ShareReceiver {

    private val _pending = MutableStateFlow<List<SharedFile>>(emptyList())

    /** آخر مشاركة وصلت — تستهلكها الواجهة ثمّ تستدعي [consume]. */
    val pending: StateFlow<List<SharedFile>> = _pending.asStateFlow()

    /** يُستدعى من `MainActivity.onCreate` و`onNewIntent`. */
    fun handleIntent(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
        val type = intent.type.orEmpty()
        val out = ArrayList<SharedFile>()

        val uris: List<Uri> = if (action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        }

        for ((index, uri) in uris.withIndex()) {
            // النصّ المرافق (إن وُجد) يُرفق بأوّل ملفّ فقط — نظير حمولة Dart
            // الواحدة التي تحمل path و text معاً.
            val copied = copyToCache(context, uri, type, if (index == 0) text else null)
            if (copied != null) out.add(copied)
        }

        if (out.isEmpty() && !text.isNullOrEmpty()) {
            out.add(
                SharedFile(
                    path = "",
                    name = "",
                    mime = type.ifEmpty { "text/plain" },
                    text = text,
                ),
            )
        }

        if (out.isNotEmpty()) _pending.value = out
    }

    /** تصفير الحمولة بعد استهلاكها (كي لا تُفتح شاشة الرفع مرّتين). */
    fun consume() {
        _pending.value = emptyList()
    }

    private fun copyToCache(
        context: Context,
        uri: Uri,
        mime: String,
        text: String?,
    ): SharedFile? = runCatching {
        val resolver = context.contentResolver
        val name = queryDisplayName(resolver, uri) ?: "ملف مشترك"
        val target = File(context.cacheDir, "shared_${System.currentTimeMillis()}_$name")
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use(input::copyTo)
        } ?: return@runCatching null
        if (!target.exists() || target.length() == 0L) return@runCatching null
        SharedFile(
            path = target.absolutePath,
            name = name,
            mime = mime.ifEmpty { resolver.getType(uri).orEmpty() },
            text = text,
        )
    }.getOrNull()

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index < 0) null else cursor.getString(index)
                }
        }.getOrNull()
}
