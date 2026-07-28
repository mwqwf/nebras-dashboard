package com.nebras.dashboard.auth

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.nebras.dashboard.core.ApiClient
import com.nebras.dashboard.core.ApiException
import com.nebras.dashboard.core.AppConfig
import com.nebras.dashboard.core.ServiceLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** دور المستخدم في اللوحة — نظير `enum DashRole` في Dart. */
enum class DashRole { owner, supervisor }

/**
 * حالة المصادقة كما تراها الشاشات — نظير حقول `AuthProvider` العامّة.
 *
 * [isLoading]: فحص صلاحيّة جارٍ (يُخفي شاشة الدخول حتى لا تظهر لموقَّعٍ
 * ينتظر ردّ الخادم). [isBusy]: عمليّة تفاعليّة جارية (دخول Google، طلب رمز
 * المالك أو التحقّق منه) — تُعطِّل الأزرار فقط.
 */
data class AuthUiState(
    val user: FirebaseUser? = null,
    val authorized: Boolean = false,
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val needsOwnerCode: Boolean = false,
    val role: DashRole? = null,
    val isBlocked: Boolean = false,
    val viewMode: String = "admin", // admin | moderator
    val errorMessage: String? = null,
) {
    val isOwner: Boolean get() = role == DashRole.owner
    val isSignedIn: Boolean get() = user != null

    /** اختصارات عرض (الشاشات تستعمل `user?.email` في Dart). */
    val email: String get() = user?.email.orEmpty()
    val displayName: String get() = user?.displayName.orEmpty()
    val photoUrl: String get() = user?.photoUrl?.toString().orEmpty()
}

/**
 * وحدة التحكّم بالمصادقة — نقل `auth/auth_provider.dart` حرفيّاً
 * (`ChangeNotifier` ⇐ `ViewModel` + [StateFlow]).
 *
 * بديل حزمة `google_sign_in`: **Credential Manager** + `googleid` بنفس
 * `serverClientId` المسجَّل في Firebase ([AppConfig.googleServerClientId]).
 */
class AuthController : ViewModel() {

    private companion object {
        const val TAG = "AuthController"
        const val KEY_VIEW_MODE = "nebras_view_mode"
        fun keyAuthorized(uid: String) = "nebras_authorized_$uid"
        fun keyRole(uid: String) = "nebras_role_$uid"
    }

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    private val credentialManager: CredentialManager by lazy {
        CredentialManager.create(ServiceLocator.appContext)
    }

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private val authListener = FirebaseAuth.AuthStateListener { fa ->
        onAuthStateChanged(fa.currentUser)
    }

    init {
        val savedMode = runCatching {
            ServiceLocator.prefs.getString(KEY_VIEW_MODE, null)
        }.getOrNull() ?: "admin"
        _state.update { it.copy(viewMode = savedMode) }
        auth.addAuthStateListener(authListener)
    }

    override fun onCleared() {
        auth.removeAuthStateListener(authListener)
        super.onCleared()
    }

    private fun onAuthStateChanged(u: FirebaseUser?) {
        if (u == null) {
            _state.update {
                it.copy(
                    user = null,
                    authorized = false,
                    needsOwnerCode = false,
                    role = null,
                    isBlocked = false,
                    isLoading = false,
                )
            }
            return
        }
        _state.update { it.copy(user = u) }
        // جلسة دائمة (مثل التطبيق العام): إن سبق اعتماد هذا الحساب على هذا
        // الجهاز ندخل فوراً دون انتظار الخادم، ثم نتحقّق بصمت في الخلفيّة —
        // لا يُطرد المستخدم إلا برفض صريح (إزالة/إيقاف)، لا بانقطاع شبكة.
        viewModelScope.launch {
            val prefs = ServiceLocator.prefs
            val cachedAuthorized = prefs.getBoolean(keyAuthorized(u.uid), false)
            if (cachedAuthorized) {
                _state.update {
                    it.copy(
                        authorized = true,
                        role = mapRole(prefs.getString(keyRole(u.uid), null)),
                        isLoading = false,
                    )
                }
                checkAuthorization(background = true)
            } else {
                checkAuthorization()
            }
        }
    }

    private suspend fun idToken(force: Boolean = false): String? = try {
        auth.currentUser?.getIdToken(force)?.await()?.token
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * استدعاء `/api/auth/check` لتحديد الصلاحيّة.
     *
     * [background]: فحص صامت بعد دخول متفائل من الكاش — لا يعرض تحميلاً،
     * ولا يُسقط الاعتماد عند خطأ شبكة/خادم عابر؛ يُسقطه فقط عند ردّ صريح
     * بعدم الصلاحيّة (إزالة/إيقاف من الإدارة).
     */
    private suspend fun checkAuthorization(background: Boolean = false) {
        if (!background) {
            _state.update { it.copy(isLoading = true) }
        }
        try {
            val token = idToken()
            val res = ApiClient.authedJson(
                "/api/auth/check",
                method = "POST",
                body = mapOf("idToken" to token),
            )
            val map = (res as? Map<*, *>) ?: emptyMap<Any?, Any?>()
            when {
                map["authorized"] == true -> {
                    val userMap = map["user"] as? Map<*, *>
                    val role = mapRole(userMap?.get("role"))
                    _state.update {
                        it.copy(
                            authorized = true,
                            needsOwnerCode = false,
                            isBlocked = false,
                            role = role,
                        )
                    }
                    cacheAuthorization(true)
                    // الخادم زامن الـ custom claims أثناء الفحص — نجدّد التوكن ليحملها
                    // فوراً (يهمّ قواعد Firestore/Storage، وخاصّة ترقية مالك جديدة).
                    try {
                        auth.currentUser?.getIdToken(true)?.await()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }

                map["blocked"] == true -> {
                    _state.update {
                        it.copy(authorized = false, isBlocked = true, needsOwnerCode = false)
                    }
                    cacheAuthorization(false)
                }

                map["needsOwnerCode"] == true -> {
                    _state.update { it.copy(authorized = false, needsOwnerCode = true) }
                    cacheAuthorization(false)
                }

                else -> {
                    _state.update { it.copy(authorized = false) }
                    cacheAuthorization(false)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // خطأ عابر (شبكة/خادم): في الفحص الصامت نُبقي الجلسة كما هي.
            if (!background) {
                val message = if (e is ApiException) e.message else e.toString()
                _state.update { it.copy(authorized = false, errorMessage = message) }
            }
        } finally {
            _state.update { it.copy(isLoading = false) }
        }
    }

    /** حفظ/مسح الاعتماد المحلّي الدائم لهذا الحساب. */
    private fun cacheAuthorization(ok: Boolean) {
        val uid = _state.value.user?.uid ?: return
        runCatching {
            val editor = ServiceLocator.prefs.edit()
            if (ok) {
                editor.putBoolean(keyAuthorized(uid), true)
                editor.putString(
                    keyRole(uid),
                    if (_state.value.role == DashRole.owner) "owner" else "supervisor",
                )
            } else {
                editor.remove(keyAuthorized(uid))
                editor.remove(keyRole(uid))
            }
            editor.apply()
        }
    }

    private fun mapRole(r: Any?): DashRole? {
        val s = "$r"
        if (s == "owner") return DashRole.owner
        if (s == "supervisor" || s == "admin" || s == "moderator") {
            return DashRole.supervisor
        }
        return null
    }

    // ─── تسجيل الدخول بـ Google ──────────────────────────────

    /**
     * دخول تفاعليّ بحساب Google.
     *
     * [activityContext]: سياق النشاط (`LocalContext.current` في Compose) —
     * حوار اختيار الحساب في Credential Manager يحتاج نشاطاً. عند تمرير null
     * نعود إلى سياق التطبيق (يعمل على أغلب الأجهزة لكنّه ليس المفضَّل).
     */
    fun signInWithGoogle(activityContext: Context? = null) {
        _state.update { it.copy(errorMessage = null, isLoading = true, isBusy = true) }
        viewModelScope.launch {
            try {
                val ctx = activityContext ?: ServiceLocator.appContext
                val option = GetGoogleIdOption.Builder()
                    .setServerClientId(AppConfig.googleServerClientId)
                    .setFilterByAuthorizedAccounts(false)
                    .build()
                val response = credentialManager.getCredential(
                    ctx,
                    GetCredentialRequest.Builder().addCredentialOption(option).build(),
                )
                val credential = response.credential
                if (credential !is CustomCredential ||
                    credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    throw IllegalStateException("Unexpected credential type")
                }
                val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
                auth.signInWithCredential(firebaseCredential).await()
                // مستمع حالة المصادقة سيُطلق checkAuthorization.
                _state.update { it.copy(isBusy = false) }
            } catch (_: GetCredentialCancellationException) {
                // ألغى المستخدم.
                _state.update { it.copy(isLoading = false, isBusy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "google sign-in failed: ${e.javaClass.simpleName}")
                _state.update {
                    it.copy(
                        errorMessage = "فشل تسجيل الدخول بـ Google: $e",
                        isLoading = false,
                        isBusy = false,
                    )
                }
            }
        }
    }

    // ─── رمز اعتماد المالك ───────────────────────────────────

    /** يطلب إرسال رمز إلى بريد المالك. يُرجع رسالة للحالة. */
    suspend fun requestOwnerCode(): String {
        _state.update { it.copy(isBusy = true) }
        try {
            val token = idToken()
            val res = ApiClient.authedJson(
                "/api/auth/request-code",
                method = "POST",
                body = mapOf("idToken" to token),
            )
            val map = (res as? Map<*, *>) ?: emptyMap<Any?, Any?>()
            if (map["ok"] == true) {
                return if (map["delivered"] == true) {
                    "تم إرسال رمز التحقّق إلى بريد المالك."
                } else {
                    "تم توليد الرمز (تحقّق من إعداد البريد لدى المالك)."
                }
            }
            if (map["reason"] == "rate_limited") {
                return "طلب متكرّر بسرعة — انتظر ${map["retryAfterSec"] ?: ""} ثانية."
            }
            if (map["reason"] == "already_authorized") {
                checkAuthorization()
                return "حسابك مُعتمَد بالفعل."
            }
            return "تعذّر إرسال الرمز."
        } finally {
            _state.update { it.copy(isBusy = false) }
        }
    }

    /** يتحقّق من الرمز ويعتمد الحساب. يُرجع true عند النجاح. */
    suspend fun verifyOwnerCode(code: String): Boolean {
        _state.update { it.copy(isBusy = true) }
        try {
            val token = idToken()
            val res = ApiClient.authedJson(
                "/api/auth/verify-code",
                method = "POST",
                body = mapOf("idToken" to token, "code" to code.trim()),
            )
            val map = (res as? Map<*, *>) ?: emptyMap<Any?, Any?>()
            if (map["ok"] == true) {
                _state.update { it.copy(needsOwnerCode = false) }
                checkAuthorization()
                return true
            }
            val reason = "${map["reason"]}"
            val message = when (reason) {
                "no_code" -> "لم يُطلب رمز بعد."
                "expired" -> "انتهت صلاحيّة الرمز — اطلب رمزاً جديداً."
                "invalid" -> "الرمز غير صحيح."
                "too_many_attempts" -> "محاولات كثيرة — اطلب رمزاً جديداً."
                else -> "تعذّر التحقّق من الرمز."
            }
            _state.update { it.copy(errorMessage = message) }
            return false
        } finally {
            _state.update { it.copy(isBusy = false) }
        }
    }

    // ─── أخرى ────────────────────────────────────────────────

    fun toggleViewMode() {
        val next = if (_state.value.viewMode == "admin") "moderator" else "admin"
        setViewMode(next)
    }

    fun setViewMode(mode: String) {
        _state.update { it.copy(viewMode = mode) }
        runCatching { ServiceLocator.prefs.edit().putString(KEY_VIEW_MODE, mode).apply() }
    }

    fun signOut() {
        // تسجيل الخروج الصريح يمسح الاعتماد المحلّي — فيُطلب الدخول مجدّداً.
        cacheAuthorization(false)
        viewModelScope.launch {
            runCatching {
                auth.signOut()
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            }
        }
    }

    fun refreshAuthorization() {
        viewModelScope.launch { checkAuthorization() }
    }
}
