package com.nebras.dashboard.data

import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.UploadTask
import kotlinx.coroutines.tasks.await
import java.io.File

/** نتيجة الرفع — نظير `SmartUploadResult` في `smartUpload.js`. */
data class UploadResult(
    val url: String,
    val path: String,
    val contentType: String,
    val size: Long,
    val filename: String,
)

private val UNSAFE_SEGMENT = Regex("[#$\\[\\]./\\\\:*?\"<>|]+")
private val FOLDER_EDGES = Regex("^/+|/+$")

private fun sanitizeSegment(name: String): String {
    // إزالة أحرف التحكّم (0x00-0x1F و 0x7F) دون regex لتفادي مشاكل الترميز.
    val buffer = StringBuilder()
    for (ch in name.trim()) {
        val cu = ch.code
        if (cu <= 0x1F || cu == 0x7F) continue
        buffer.append(ch)
    }
    var s = buffer.toString()
    s = UNSAFE_SEGMENT.replace(s, "_")
    if (s.length > 180) s = s.substring(0, 180)
    return if (s.isEmpty()) "file" else s
}

/**
 * رفع بايتات إلى دلو Nebras (مثل `smartUpload` → Storage). الترتيب الثابت:
 * Storage أولاً ثم نُعيد رابط التنزيل ليُكتب في Firestore.
 *
 * [onProgress] نسبة مئويّة 0..100. [isAborted] لإلغاء الرفع.
 */
suspend fun smartUpload(
    bytes: ByteArray,
    filename: String,
    contentType: String = "application/octet-stream",
    folder: String = "dashboard/uploads",
    onProgress: ((Double) -> Unit)? = null,
    isAborted: (() -> Boolean)? = null,
    onTaskCreated: ((UploadTask) -> Unit)? = null,
): UploadResult {
    val storage = FirebaseStorage.getInstance()
    val safeName = sanitizeSegment(filename)
    val cleanFolder = FOLDER_EDGES.replace(folder, "")
    val path = "$cleanFolder/${System.currentTimeMillis()}_$safeName"
    val ref = storage.getReference(path)
    val ct = if (contentType.trim().isEmpty()) "application/octet-stream" else contentType

    val task = ref.putBytes(bytes, StorageMetadata.Builder().setContentType(ct).build())
    onTaskCreated?.invoke(task)

    task.addOnProgressListener { snap ->
        if (isAborted != null && isAborted()) {
            task.cancel()
            return@addOnProgressListener
        }
        val total = if (snap.totalByteCount <= 0) bytes.size.toLong() else snap.totalByteCount
        if (total > 0) onProgress?.invoke((snap.bytesTransferred.toDouble() / total) * 100.0)
    }

    try {
        task.await()
    } catch (e: StorageException) {
        if (e.errorCode == StorageException.ERROR_CANCELED) {
            throw IllegalStateException("تمّ إلغاء الرفع.")
        }
        throw e
    }

    if (isAborted != null && isAborted()) {
        throw IllegalStateException("تمّ إلغاء الرفع.")
    }

    val url = ref.downloadUrl.await().toString()
    return UploadResult(
        url = url,
        path = path,
        contentType = ct,
        size = bytes.size.toLong(),
        filename = safeName,
    )
}

/**
 * رفع ملفّ من المسار (بثّ — لا يُحمَّل كاملاً في الذاكرة). يُستعمل للملفّات
 * الكبيرة في الرفع المتعدّد لتفادي OOM.
 */
suspend fun smartUploadFile(
    file: File,
    filename: String,
    contentType: String = "application/octet-stream",
    folder: String = "dashboard/uploads",
    onProgress: ((Double) -> Unit)? = null,
    isAborted: (() -> Boolean)? = null,
    onTaskCreated: ((UploadTask) -> Unit)? = null,
): UploadResult {
    val storage = FirebaseStorage.getInstance()
    val safeName = sanitizeSegment(filename)
    val cleanFolder = FOLDER_EDGES.replace(folder, "")
    val path = "$cleanFolder/${System.currentTimeMillis()}_$safeName"
    val ref = storage.getReference(path)
    val ct = if (contentType.trim().isEmpty()) "application/octet-stream" else contentType
    val size = file.length()

    val task = ref.putFile(
        Uri.fromFile(file),
        StorageMetadata.Builder().setContentType(ct).build(),
    )
    onTaskCreated?.invoke(task)

    task.addOnProgressListener { snap ->
        if (isAborted != null && isAborted()) {
            task.cancel()
            return@addOnProgressListener
        }
        val total = if (snap.totalByteCount <= 0) size else snap.totalByteCount
        if (total > 0) onProgress?.invoke((snap.bytesTransferred.toDouble() / total) * 100.0)
    }

    try {
        task.await()
    } catch (e: StorageException) {
        if (e.errorCode == StorageException.ERROR_CANCELED) {
            throw IllegalStateException("تمّ إلغاء الرفع.")
        }
        throw e
    }

    if (isAborted != null && isAborted()) {
        throw IllegalStateException("تمّ إلغاء الرفع.")
    }

    val url = ref.downloadUrl.await().toString()
    return UploadResult(
        url = url,
        path = path,
        contentType = ct,
        size = size,
        filename = safeName,
    )
}
