package ru.liturgyguide.ui

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF245B4B), onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EEE8), onPrimaryContainer = Color(0xFF173F31),
    secondary = Color(0xFF59666B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6EBED), onSecondaryContainer = Color(0xFF334047),
    background = Color(0xFFF3F4F6), onBackground = Color(0xFF202629),
    surface = Color.White, onSurface = Color(0xFF202629),
    surfaceContainerLow = Color(0xFFF7F8FA), surfaceContainer = Color(0xFFECEFF1), surfaceContainerHigh = Color(0xFFE3E7E9),
    surfaceVariant = Color(0xFFEBEFF0), onSurfaceVariant = Color(0xFF566167),
    outline = Color(0xFF7A858A), outlineVariant = Color(0xFFD6DEE1)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFAFD0BE), onPrimary = Color(0xFF123A2A),
    primaryContainer = Color(0xFF29473A), onPrimaryContainer = Color(0xFFD9EDE1),
    secondary = Color(0xFFBECACF), onSecondary = Color(0xFF243239),
    secondaryContainer = Color(0xFF354349), onSecondaryContainer = Color(0xFFDDE7EB),
    background = Color(0xFF141719), onBackground = Color(0xFFECEFED),
    surface = Color(0xFF1C2022), onSurface = Color(0xFFECEFED),
    surfaceContainerLow = Color(0xFF23282A), surfaceContainer = Color(0xFF2A3033), surfaceContainerHigh = Color(0xFF333A3D),
    surfaceVariant = Color(0xFF2A3235), onSurfaceVariant = Color(0xFFBBC6CA),
    outline = Color(0xFF8D999E), outlineVariant = Color(0xFF434D52)
)
// Interface typography stays quiet and familiar. Book text supplies its own serif face.
private val GuideTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 27.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 25.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 23.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp)
)

@Composable
fun GuideTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = colors, typography = GuideTypography,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)),
        content = content
    )
}
