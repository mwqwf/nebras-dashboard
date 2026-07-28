package com.nebras.dashboard.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nebras.dashboard.auth.AuthController
import com.nebras.dashboard.core.NebrasColors
import com.nebras.dashboard.ui.widgets.DashCard
import com.nebras.dashboard.ui.widgets.DashPrimaryButton
import com.nebras.dashboard.ui.widgets.DashSecondaryButton
import com.nebras.dashboard.ui.widgets.DashTextField
import kotlinx.coroutines.launch

/**
 * شاشة الدخول — نقل `ui/login/login_screen.dart`.
 *
 * ثلاث حالات بنفس ترتيب الأصل: محظور، أو داخل ويحتاج رمز اعتماد المالك،
 * أو غير مسجَّل.
 */
@Composable
fun LoginScreen(authController: AuthController) {
    val state by authController.state.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        DashCard(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(24.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.AutoStories,
                    contentDescription = null,
                    tint = NebrasColors.emerald,
                    modifier = Modifier.size(52.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "مرحبًا بك في لوحة تحكّم نبراس",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "سجّل الدخول بحساب Google لإدارة المنصّة.",
                    textAlign = TextAlign.Center,
                    color = NebrasColors.textMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(28.dp))

                when {
                    state.isBlocked -> BlockedView(authController)
                    state.isSignedIn && state.needsOwnerCode ->
                        OwnerCodeView(authController, state.email)
                    else -> SignInView(authController, state.isLoading || state.isBusy)
                }

                state.errorMessage?.let { error ->
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = error,
                        textAlign = TextAlign.Center,
                        color = NebrasColors.rose,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SignInView(authController: AuthController, isLoading: Boolean) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isLoading) {
            Box(Modifier.padding(8.dp)) { CircularProgressIndicator() }
        } else {
            DashPrimaryButton(
                text = "المتابعة عبر Google",
                icon = Icons.AutoMirrored.Filled.Login,
                onClick = { authController.signInWithGoogle(context) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "نقرأ فقط بياناتك الأساسيّة من Google. لا نخزّن أيّ كلمة مرور هنا.",
            textAlign = TextAlign.Center,
            color = NebrasColors.textMuted,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
        )
    }
}

/**
 * اعتماد المالك: الحساب الجديد يطلب رمزاً يصل إلى المالك ثمّ يتحقّق منه.
 */
@Composable
private fun OwnerCodeView(authController: AuthController, email: String) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "حسابك ($email) جديد ويحتاج اعتماد المالك.",
            textAlign = TextAlign.Center,
            color = NebrasColors.textMuted,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
        )
        Spacer(Modifier.height(16.dp))

        DashSecondaryButton(
            text = "إرسال رمز جديد",
            icon = Icons.Default.MarkEmailRead,
            enabled = !busy,
            onClick = {
                scope.launch {
                    busy = true
                    info = runCatching { authController.requestOwnerCode() }
                        .getOrElse { "تعذّر إرسال الرمز: ${it.message}" }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))

        DashTextField(
            value = code,
            onValueChange = { code = it },
            label = "رمز التحقّق",
            enabled = !busy,
        )

        Spacer(Modifier.height(12.dp))

        DashPrimaryButton(
            text = "تحقّق وتابع",
            loading = busy,
            enabled = code.trim().isNotEmpty(),
            onClick = {
                scope.launch {
                    busy = true
                    runCatching { authController.verifyOwnerCode(code) }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        info?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                text = it,
                textAlign = TextAlign.Center,
                color = NebrasColors.emerald,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            )
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = authController::signOut) {
            Text("إلغاء وتسجيل الخروج")
        }
    }
}

@Composable
private fun BlockedView(authController: AuthController) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Block,
            contentDescription = null,
            tint = NebrasColors.rose,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "تم تعليق وصولك من قبل الإدارة.",
            textAlign = TextAlign.Center,
            color = NebrasColors.rose,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = authController::signOut) {
            Text("تسجيل الخروج")
        }
    }
}
