package com.nebras.dashboard.core

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit

/**
 * خطأ موسَّع من طبقة الـ API — يحمل رمز الحالة والرسالة العربيّة الجاهزة للعرض،
 * مطابق لما يبنيه `_authedFetch.js` في لوحة الويب.
 */
class ApiException(
    override val message: String,
    val status: Int = 0,
    val reason: String? = null,
    val payload: Any? = null,
) : Exception(message) {
    override fun toString(): String = "ApiException($status): $message"
}

/**
 * ترميز/فكّ ترميز JSON بـ `org.json` — بديل `jsonEncode`/`jsonDecode`.
 * القيم المفكوكة أنواع Kotlin عاديّة: [Map]، [List]، [String]، [Boolean]،
 * [Int]/[Long]/[Double]، أو `null` (نظير `dynamic` في Dart).
 */
object JsonCodec {

    fun parse(text: String): Any? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return unwrap(JSONTokener(t).nextValue())
    }

    fun encode(value: Any?): String = when (val v = wrap(value)) {
        JSONObject.NULL -> "null"
        is JSONObject -> v.toString()
        is JSONArray -> v.toString()
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }

    /** يحوّل بنية `org.json` إلى أنواع Kotlin عاديّة (بعمق). */
    fun unwrap(value: Any?): Any? = when {
        value == null || value === JSONObject.NULL -> null
        value is JSONObject -> {
            val out = LinkedHashMap<String, Any?>(value.length())
            val keys = value.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = unwrap(value.opt(k))
            }
            out
        }
        value is JSONArray -> {
            val out = ArrayList<Any?>(value.length())
            for (i in 0 until value.length()) out.add(unwrap(value.opt(i)))
            out
        }
        else -> value
    }

    /** يحوّل أنواع Kotlin إلى بنية `org.json` (بعمق). */
    fun wrap(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray -> value
        is Map<*, *> -> {
            val o = JSONObject()
            for ((k, v) in value) o.put(k.toString(), wrap(v))
            o
        }
        is Iterable<*> -> {
            val a = JSONArray()
            for (v in value) a.put(wrap(v))
            a
        }
        is Array<*> -> {
            val a = JSONArray()
            for (v in value) a.put(wrap(v))
            a
        }
        is String, is Boolean, is Number -> value
        else -> value.toString()
    }
}

/**
 * طبقة مشتركة لإرسال طلبات JSON مصادَقة بـ `Authorization: Bearer <ID token>`
 * إلى نقاط الـ API الإداريّة للوحة (`/api/admin`, `/api/notify`,
 * `/api/auth`). نظير `authedJson` / `rawAuthedFetch`.
 */
object ApiClient {

    private val jsonMedia = "application/json".toMediaType()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // بلا مهلة كلّيّة: بعض نقاط المحرّكات (bootstrap/diagnose) بطيئة عمداً،
        // ونسخة Flutter (http.Client) لا تفرض مهلة كلّيّة أيضاً.
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun url(path: String): HttpUrl {
        if (path.startsWith("http")) return path.toHttpUrl()
        val base = AppConfig.backendBaseUrl.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return (base + p).toHttpUrl()
    }

    /** رمز Firebase ID طازج للمستخدم الحاليّ (أو null إن لم يُسجَّل الدخول). */
    private suspend fun freshBearer(forceRefresh: Boolean = false): String? {
        return try {
            val user = FirebaseAuth.getInstance().currentUser ?: return null
            user.getIdToken(forceRefresh).await().token
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** طلب JSON مصادَق. يرمي [ApiException] إن لم يكن 2xx. */
    suspend fun authedJson(
        path: String,
        method: String = "GET",
        body: Any? = null,
        query: Map<String, String>? = null,
    ): Any? {
        var uri = url(path)
        if (!query.isNullOrEmpty()) {
            val b = uri.newBuilder()
            for ((k, v) in query) b.setQueryParameter(k, v)
            uri = b.build()
        }

        val token = freshBearer()
            ?: throw ApiException(
                "يجب تسجيل الدخول لإجراء هذه العملية.",
                status = 401,
                reason = "not_signed_in",
            )

        val encoded: RequestBody? = body?.let { JsonCodec.encode(it).toRequestBody(jsonMedia) }
        val empty: RequestBody = ByteArray(0).toRequestBody(jsonMedia)

        val builder = Request.Builder()
            .url(uri)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $token")

        when (method.uppercase()) {
            "GET" -> builder.get()
            "POST" -> builder.post(encoded ?: empty)
            "PATCH" -> builder.patch(encoded ?: empty)
            "PUT" -> builder.put(encoded ?: empty)
            "DELETE" -> builder.delete(encoded)
            else -> builder.get()
        }

        val response: Response = try {
            withContext(Dispatchers.IO) { http.newCall(builder.build()).execute() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw ApiException(
                "تعذّر الاتصال بالخادم — تأكد من اتصال الإنترنت ثمّ أعد المحاولة.",
                status = 0,
                reason = "network_error",
            )
        }

        var status = 0
        var payload: Any? = null
        response.use { res ->
            status = res.code
            val ct = res.header("content-type") ?: ""
            val text = try {
                res.body?.string() ?: ""
            } catch (_: Exception) {
                ""
            }
            if (ct.contains("application/json") && text.isNotEmpty()) {
                payload = try {
                    JsonCodec.parse(text)
                } catch (_: Exception) {
                    null
                }
            }
        }

        if (status < 200 || status >= 300) {
            val map = payload as? Map<*, *>
            throw ApiException(
                buildErrorMessage(status, payload),
                status = status,
                reason = if (map != null) (map["reason"] ?: map["error"])?.toString() else null,
                payload = payload,
            )
        }

        return payload
    }

    /**
     * نسخة خامّ ترجع [Response] مباشرة (لرفع multipart إن لزم). المستدعي
     * مسؤول عن إغلاق الاستجابة.
     */
    suspend fun rawAuthed(request: Request): Response {
        val token = freshBearer()
        val req = if (token != null) {
            request.newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            request
        }
        return withContext(Dispatchers.IO) { http.newCall(req).execute() }
    }

    private fun buildErrorMessage(status: Int, payload: Any?): String {
        val map = payload as? Map<*, *>
        val serverMsg = if (map != null && map["message"] != null) {
            "${map["message"]}".trim()
        } else {
            ""
        }
        if (serverMsg.isNotEmpty()) return serverMsg

        val reason = map?.get("reason")?.toString()
        val forceSignOut = map?.get("forceSignOut") == true

        if (status == 401) return "يجب تسجيل الدخول لإجراء هذه العملية."
        if (status == 403) {
            if (reason == "access_suspended" || forceSignOut) {
                return "تم تعليق وصولك من قبل الإدارة."
            }
            if (reason == "role_not_allowed") return "صلاحيّاتك لا تسمح بإتمام هذه العملية."
            if (reason == "not_in_admins") return "حسابك غير مسجَّل كعضو في إدارة لوحة التحكّم."
            return "الوصول مرفوض."
        }
        if (status == 404) return "لم يُعثر على العنصر المطلوب."
        if (status == 409) return "تعارض في البيانات — حاول مرّة أخرى."
        if (status == 501) {
            return "خاصيّة الكتابة عبر السيرفر غير مفعّلة لهذا المشروع — يحتاج المسؤول إلى ضبط مفاتيح الخدمة."
        }
        if (status == 400) {
            val r = map?.get("reason") ?: "bad_request"
            return "طلب غير صحيح ($r)."
        }
        if (status >= 500) return "حدث خطأ غير متوقَّع على الخادم — حاول مرّة أخرى لاحقاً."
        return "تعذّر إتمام العملية (HTTP $status)."
    }
}
