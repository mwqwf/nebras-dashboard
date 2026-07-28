package com.nebras.dashboard.core

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * ألوان لوحة نبراس (مستخرجة من `app.css` عبر `theme.dart`): خلفية داكنة،
 * أخضر زمرّدي رئيسي، كهرماني ثانوي.
 */
object NebrasColors {
    val bg: Color = Color(0xFF0D0D0F)
    val surface: Color = Color(0xFF1A1A1E)
    val surfaceAlt: Color = Color(0xFF27272A)
    val sidebarBg: Color = Color(0xFF0D0D0F)
    val sidebarHover: Color = Color(0xFF1A1A1E)
    val sidebarActive: Color = Color(0xFF0F2A1E)
    val border: Color = Color(0xFF27272A)

    val emerald: Color = Color(0xFF10B981)
    val emeraldDark: Color = Color(0xFF059669)
    val amber: Color = Color(0xFFF59E0B)
    val amberLight: Color = Color(0xFFFBBF24)
    val rose: Color = Color(0xFFF43F5E)
    val textPrimary: Color = Color(0xFFFAFAFA)
    val textMuted: Color = Color(0xFFA1A1AA)
}

/**
 * الأبعاد الثابتة المنقولة من `buildNebrasTheme()` — تستعملها الشاشات كي
 * تطابق بطاقات/حقول/أزرار نسخة Flutter حرفيّاً.
 */
object NebrasDims {
    /** نصف قطر البطاقة (CardTheme). */
    val cardRadius = 14.dp

    /** نصف قطر الحقول والأزرار (InputDecoration / Buttons). */
    val fieldRadius = 10.dp

    /** سماكة إطار الحقل عند التركيز. */
    val focusedBorderWidth = 1.5.dp

    /** حشوة الزرّ الممتلئ (FilledButton). */
    val buttonPaddingHorizontal = 18.dp
    val buttonPaddingVertical = 12.dp

    val cardShape = RoundedCornerShape(cardRadius)
    val fieldShape = RoundedCornerShape(fieldRadius)
}

/**
 * مخطّط ألوان داكن مطابق لـ `ColorScheme.dark(...)` في `theme.dart`
 * (اللوحة داكنة دائماً — لا وضع فاتح في نسخة Flutter).
 */
val NebrasDarkColorScheme: ColorScheme = darkColorScheme(
    primary = NebrasColors.emerald,
    onPrimary = Color.Black,
    primaryContainer = NebrasColors.emeraldDark,
    onPrimaryContainer = Color.White,
    secondary = NebrasColors.amber,
    onSecondary = Color.Black,
    secondaryContainer = NebrasColors.amberLight,
    onSecondaryContainer = Color.Black,
    tertiary = NebrasColors.amberLight,
    onTertiary = Color.Black,
    background = NebrasColors.bg, // scaffoldBackgroundColor
    onBackground = NebrasColors.textPrimary,
    surface = NebrasColors.surface,
    onSurface = NebrasColors.textPrimary,
    surfaceVariant = NebrasColors.surfaceAlt,
    onSurfaceVariant = NebrasColors.textMuted,
    surfaceContainer = NebrasColors.surface,
    surfaceContainerHigh = NebrasColors.surfaceAlt,
    surfaceContainerHighest = NebrasColors.surfaceAlt,
    surfaceContainerLow = NebrasColors.surface,
    surfaceContainerLowest = NebrasColors.bg,
    outline = NebrasColors.border,
    outlineVariant = NebrasColors.border,
    error = NebrasColors.rose,
    onError = Color.White,
    scrim = Color.Black,
)

/**
 * عائلة الخطّ. نسخة Flutter كانت تجلب «IBM Plex Sans Arabic» عبر
 * `google_fonts`؛ نظير Compose (`GoogleFont.Provider`) يحتاج مصفوفة شهادات
 * في `res/values` — و`res/` خارج نطاق هذا الملف — فنستعمل خطّ النظام الذي
 * يعرض العربيّة عرضاً سليماً. (انظر ملاحظة التسليم في التقرير.)
 */
val NebrasFontFamily: FontFamily = FontFamily.Default

private fun typographyWith(family: FontFamily): Typography {
    val d = Typography()
    return Typography(
        displayLarge = d.displayLarge.copy(fontFamily = family),
        displayMedium = d.displayMedium.copy(fontFamily = family),
        displaySmall = d.displaySmall.copy(fontFamily = family),
        headlineLarge = d.headlineLarge.copy(fontFamily = family),
        headlineMedium = d.headlineMedium.copy(fontFamily = family),
        headlineSmall = d.headlineSmall.copy(fontFamily = family),
        titleLarge = d.titleLarge.copy(fontFamily = family),
        titleMedium = d.titleMedium.copy(fontFamily = family),
        titleSmall = d.titleSmall.copy(fontFamily = family),
        bodyLarge = d.bodyLarge.copy(fontFamily = family),
        bodyMedium = d.bodyMedium.copy(fontFamily = family),
        bodySmall = d.bodySmall.copy(fontFamily = family),
        labelLarge = d.labelLarge.copy(fontFamily = family),
        labelMedium = d.labelMedium.copy(fontFamily = family),
        labelSmall = d.labelSmall.copy(fontFamily = family),
    )
}

val NebrasTypography: Typography = typographyWith(NebrasFontFamily)

/**
 * سمة اللوحة — نظير `buildNebrasTheme()` + غلاف `Directionality(rtl)` في
 * `app.dart`. اللوحة عربيّة RTL خالصة وداكنة دائماً (لا وضع فاتح ولا اتّباع
 * لسمة النظام — مطابق لنسخة Flutter).
 *
 * [layoutDirection] يُتجاوَز فقط عند عرض محتوى لاتينيّ بحت (مسار/رابط).
 */
@Composable
fun DashboardTheme(
    layoutDirection: LayoutDirection = LayoutDirection.Rtl,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = NebrasDarkColorScheme,
        typography = NebrasTypography,
    ) {
        CompositionLocalProvider(
            LocalLayoutDirection provides layoutDirection,
            content = content,
        )
    }
}
