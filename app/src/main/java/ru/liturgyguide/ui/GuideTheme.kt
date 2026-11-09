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
    primary = Color(0xFF345B52), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4EEEA), onPrimaryContainer = Color(0xFF203D32),
    secondary = Color(0xFF626E75), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9ECEF), onSecondaryContainer = Color(0xFF354047),
    background = Color(0xFFF3F2EF), onBackground = Color(0xFF252A2D),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF252A2D),
    surfaceContainerLow = Color(0xFFFFFFFF), surfaceContainer = Color(0xFFECEBE7), surfaceContainerHigh = Color(0xFFE3E5E3),
    surfaceVariant = Color(0xFFECEDE9), onSurfaceVariant = Color(0xFF5C6265),
    outline = Color(0xFF7B8182), outlineVariant = Color(0xFFD5D8D5)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFAFCCB7), onPrimary = Color(0xFF18382C),
    primaryContainer = Color(0xFF293F34), onPrimaryContainer = Color(0xFFD4E7D7),
    secondary = Color(0xFFD4BF92), onSecondary = Color(0xFF3D321D),
    secondaryContainer = Color(0xFF3C3526), onSecondaryContainer = Color(0xFFE8D7B1),
    background = Color(0xFF171A1D), onBackground = Color(0xFFE1E6DC),
    surface = Color(0xFF171A1D), onSurface = Color(0xFFE1E6DC),
    surfaceContainerLow = Color(0xFF222629), surfaceContainer = Color(0xFF2A2E31), surfaceContainerHigh = Color(0xFF33383B),
    surfaceVariant = Color(0xFF292F31), onSurfaceVariant = Color(0xFFBAC4B7),
    outline = Color(0xFF8A9687), outlineVariant = Color(0xFF434B4D)
)
private val GuideTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 36.sp, lineHeight = 42.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 23.sp, lineHeight = 29.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 25.sp),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 1.sp)
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
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)),
        content = content
    )
}
