package com.nebras.dashboard.services

import android.net.Uri
import com.nebras.dashboard.core.ApiClient

/** المحرّكات الثلاثة — نظير `enum EngineKind` في Dart. */
enum class EngineKind { internetArchive, noor, hindawi }

/**
 * أغلفة نقاط الـ API الخادمية للوحة (كلّها محميّة بـ Bearer Firebase ID token).
 * تطابق `internetArchive.js` / `noorLibrary.js` / `hindawiLibrary.js` /
 * `crawl4ai.js` / `supervisors.js` / `admin.js`.
 *
 * كلّ دالّة `suspend` وتُرجع الحمولة المفكوكة (Map/List/قيمة أوّليّة أو null)
 * تماماً كما كانت `Future<dynamic>` في Dart.
 */
object ServerApi {

    /** نظير `ServerApi.instance` في Dart — نفس المفرد. */
    val instance: ServerApi get() = this

    // ─── محرّك عام (Internet Archive / Noor / Hindawi) ───────

    /** مسار الأساس لكل محرّك. */
    fun engineBase(kind: EngineKind): String = when (kind) {
        EngineKind.internetArchive -> "/api/admin/internet-archive/engine"
        EngineKind.noor -> "/api/admin/noor-library/engine"
        EngineKind.hindawi -> "/api/admin/hindawi-library/engine"
    }

    suspend fun engineStatus(kind: EngineKind, logLimit: Int = 30): Any? {
        val base = engineBase(kind)
        val path = if (kind == EngineKind.internetArchive) {
            "$base/status"
        } else {
            "$base/status?limit=$logLimit"
        }
        return ApiClient.authedJson(path)
    }

    suspend fun engineStart(kind: EngineKind): Any? =
        ApiClient.authedJson("${engineBase(kind)}/start", method = "POST", body = emptyMap<String, Any?>())

    suspend fun engineStop(kind: EngineKind): Any? =
        ApiClient.authedJson("${engineBase(kind)}/stop", method = "POST", body = emptyMap<String, Any?>())

    suspend fun engineTick(kind: EngineKind): Any? =
        ApiClient.authedJson("${engineBase(kind)}/tick", method = "POST", body = emptyMap<String, Any?>())

    /** إعادة الضبط. IA يستعمل {type}، Noor/Hindawi يستعملان {mode}. */
    suspend fun engineReset(kind: EngineKind, type: String = "cursor"): Any? {
        val base = engineBase(kind)
        if (kind == EngineKind.internetArchive) {
            return ApiClient.authedJson(
                "$base/reset",
                method = "POST",
                body = mapOf("type" to type),
            )
        }
        return ApiClient.authedJson("$base/reset", method = "POST", body = mapOf("mode" to type))
    }

    // ─── Internet Archive (خاص) ─────────────────────────────

    suspend fun iaBootstrap(): Any? = ApiClient.authedJson(
        "/api/admin/internet-archive/engine/bootstrap",
        method = "POST",
        body = emptyMap<String, Any?>(),
    )

    suspend fun iaDiagnose(): Any? = ApiClient.authedJson(
        "/api/admin/internet-archive/engine/diagnose",
        method = "POST",
        body = emptyMap<String, Any?>(),
    )

    suspend fun iaSectionsTree(): Any? =
        ApiClient.authedJson("/api/admin/internet-archive/sections")

    suspend fun iaSearch(args: Map<String, Any?>): Any? = ApiClient.authedJson(
        "/api/admin/internet-archive/search",
        method = "POST",
        body = args,
    )

    suspend fun iaPreview(args: Map<String, Any?>): Any? = ApiClient.authedJson(
        "/api/admin/internet-archive/preview",
        method = "POST",
        body = args,
    )

    suspend fun iaImport(args: Map<String, Any?>): Any? = ApiClient.authedJson(
        "/api/admin/internet-archive/import",
        method = "POST",
        body = args,
    )

    // ─── Noor / Hindawi diagnose ────────────────────────────

    suspend fun librariesDiagnose(source: String): Any? =
        ApiClient.authedJson("/api/admin/libraries/diagnose?source=$source")

    suspend fun noorSectionsTree(): Any? =
        ApiClient.authedJson("/api/admin/noor-library/sections")

    suspend fun noorPreview(url: String): Any? = ApiClient.authedJson(
        "/api/admin/noor-library/preview",
        method = "POST",
        body = mapOf("url" to url),
    )

    suspend fun noorImport(payload: Map<String, Any?>): Any? = ApiClient.authedJson(
        "/api/admin/noor-library/import",
        method = "POST",
        body = payload,
    )

    suspend fun noorJobs(limit: Int = 30): Any? =
        ApiClient.authedJson("/api/admin/noor-library/jobs?limit=$limit")

    // ─── crawl4ai ───────────────────────────────────────────

    suspend fun crawl4aiStatus(): Any? =
        ApiClient.authedJson("/api/admin/crawl4ai/status")

    suspend fun crawl4aiControl(action: String): Any? = ApiClient.authedJson(
        "/api/admin/crawl4ai/control",
        method = "POST",
        body = mapOf("action" to action),
    )

    suspend fun crawl4aiEnqueue(url: String): Any? = ApiClient.authedJson(
        "/api/admin/crawl4ai/crawl",
        method = "POST",
        body = mapOf("url" to url),
    )

    suspend fun crawl4aiJobs(limit: Int = 50): Any? =
        ApiClient.authedJson("/api/admin/crawl4ai/jobs?limit=$limit")

    // ─── المشرفون ───────────────────────────────────────────

    suspend fun listSupervisors(): Any? =
        ApiClient.authedJson("/api/admin/supervisors")

    suspend fun setSupervisorBlocked(
        uid: String,
        isBlocked: Boolean,
        mode: String = "temporary",
    ): Any? = ApiClient.authedJson(
        "/api/admin/supervisors/${Uri.encode(uid)}",
        method = "PATCH",
        body = mapOf("isBlocked" to isBlocked, "mode" to mode),
    )

    suspend fun removeSupervisor(uid: String): Any? = ApiClient.authedJson(
        "/api/admin/supervisors/${Uri.encode(uid)}",
        method = "DELETE",
    )

    // ─── إحصاءات / تقارير / تدقيق ───────────────────────────

    suspend fun aggregatesOverview(): Any? =
        ApiClient.authedJson("/api/admin/aggregates/overview")

    suspend fun aggregatesBySource(): Any? =
        ApiClient.authedJson("/api/admin/aggregates/by-source")

    suspend fun reports(query: Map<String, String>? = null): Any? =
        ApiClient.authedJson("/api/admin/reports", query = query)

    /** إجراء على بلاغ: {action:'dismiss'|'delete', reportId, contentId, contentType?} */
    suspend fun reportAction(body: Map<String, Any?>): Any? =
        ApiClient.authedJson("/api/admin/reports", method = "POST", body = body)

    /** قنوات ومنشورات «مجتمع نبراس» التي تحتاج إلى إشراف. */
    suspend fun communityModeration(): Any? =
        ApiClient.authedJson("/api/admin/community")

    /** تعليق قناة أو فك تعليقها، وحذف منشور مخالف وفق صلاحية المستخدم. */
    suspend fun communityAction(body: Map<String, Any?>): Any? =
        ApiClient.authedJson("/api/admin/community", method = "POST", body = body)

    suspend fun contentAudit(query: Map<String, String>? = null): Any? =
        ApiClient.authedJson("/api/admin/content-audit", query = query)

    /** حذف عنصر مُعلَّم من تدقيق المحتوى. */
    suspend fun contentAuditDelete(contentId: String, contentType: String): Any? =
        ApiClient.authedJson(
            "/api/admin/content-audit",
            method = "POST",
            body = mapOf(
                "action" to "delete",
                "contentId" to contentId,
                "contentType" to contentType,
            ),
        )

    // ─── إشعارات FCM ────────────────────────────────────────

    suspend fun notify(body: Map<String, Any?>): Any? =
        ApiClient.authedJson("/api/notify", method = "POST", body = body)
}
