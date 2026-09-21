package com.vitalink.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.vitalink.app.util.AppLanguage
import com.vitalink.app.util.AppLanguageCode

val Teal700  = Color(0xFF0D7A5F)
val Teal900  = Color(0xFF0A5E48)
val Blue400  = Color(0xFF2D9CDB)
val Red500   = Color(0xFFE53935)
val CautionYellow = Color(0xFFF2D500)
val CautionRed    = Color(0xFFFE5C5C)
val BgLight  = Color(0xFFF7F9FC)
val BgDark   = Color(0xFF0F1923)
val SurfDark = Color(0xFF1A2535)

private val Light = lightColorScheme(
    primary            = Teal700,
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFB7EEDE),
    onPrimaryContainer = Color(0xFF00201A),
    secondary          = Blue400,
    onSecondary        = Color.White,
    tertiary           = Red500,
    onTertiary         = Color.White,
    background         = BgLight,
    onBackground       = Color(0xFF1A2332),
    surface            = Color.White,
    onSurface          = Color(0xFF1A2332),
    surfaceVariant     = Color(0xFFECF3F0),
    onSurfaceVariant   = Color(0xFF3D5047),
    error              = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary            = Color(0xFF4DB896),
    onPrimary          = Color(0xFF003828),
    primaryContainer   = Color(0xFF005140),
    onPrimaryContainer = Color(0xFFB7EEDE),
    background         = BgDark,
    onBackground       = Color(0xFFE8F1EE),
    surface            = SurfDark,
    onSurface          = Color(0xFFE8F1EE),
    surfaceVariant     = Color(0xFF3D5047),
)

val AppTypography = Typography(
    headlineLarge  = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 34.sp, lineHeight = 42.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 30.sp, lineHeight = 38.sp),
    headlineSmall  = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 27.sp, lineHeight = 35.sp),
    titleLarge     = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleMedium    = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 29.sp),
    titleSmall     = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 27.sp),
    bodyLarge      = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 20.sp, lineHeight = 30.sp),
    bodyMedium     = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 18.sp, lineHeight = 27.sp),
    bodySmall      = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 17.sp, lineHeight = 25.sp),
    labelLarge     = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 18.sp, lineHeight = 24.sp),
    labelMedium    = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 16.sp, lineHeight = 22.sp),
    labelSmall     = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 15.sp, lineHeight = 20.sp),
)


private val LocalizedTypography = Typography(
    headlineLarge  = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall  = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 25.sp, lineHeight = 33.sp),
    titleLarge     = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium    = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 27.sp),
    titleSmall     = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 25.sp),
    bodyLarge      = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 19.sp, lineHeight = 29.sp),
    bodyMedium     = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 17.sp, lineHeight = 26.sp),
    bodySmall      = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 16.sp, lineHeight = 24.sp),
    labelLarge     = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 16.sp, lineHeight = 23.sp),
    labelMedium    = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 15.sp, lineHeight = 21.sp),
    labelSmall     = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 14.sp, lineHeight = 19.sp),
)

@Composable
fun MyHFGuardTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (dark) Dark else Light
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val win = (view.context as Activity).window
            win.statusBarColor = scheme.primary.toArgb()
            WindowCompat.getInsetsController(win, view).isAppearanceLightStatusBars = !dark
        }
    }
    val typography = if (AppLanguage.current == AppLanguageCode.ENGLISH) {
        AppTypography
    } else {
        LocalizedTypography
    }
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
