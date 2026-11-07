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
    primary = Color(0xFF284E43), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0EADF), onPrimaryContainer = Color(0xFF203D32),
    secondary = Color(0xFF766037), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0E5CD), onSecondaryContainer = Color(0xFF514022),
    background = Color(0xFFF7F7F2), onBackground = Color(0xFF232D28),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF232D28),
    surfaceContainerLow = Color(0xFFFFFFFF), surfaceContainer = Color(0xFFF0F2EB), surfaceContainerHigh = Color(0xFFE9EDE5),
    surfaceVariant = Color(0xFFEDEFE7), onSurfaceVariant = Color(0xFF596159),
    outline = Color(0xFF7D857B), outlineVariant = Color(0xFFD7DCD0)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFAFCCB7), onPrimary = Color(0xFF18382C),
    primaryContainer = Color(0xFF293F34), onPrimaryContainer = Color(0xFFD4E7D7),
    secondary = Color(0xFFD4BF92), onSecondary = Color(0xFF3D321D),
    secondaryContainer = Color(0xFF3C3526), onSecondaryContainer = Color(0xFFE8D7B1),
    background = Color(0xFF151C18), onBackground = Color(0xFFE1E6DC),
    surface = Color(0xFF151C18), onSurface = Color(0xFFE1E6DC),
    surfaceContainerLow = Color(0xFF202923), surfaceContainer = Color(0xFF273029), surfaceContainerHigh = Color(0xFF2E3930),
    surfaceVariant = Color(0xFF252E27), onSurfaceVariant = Color(0xFFBAC4B7),
    outline = Color(0xFF8A9687), outlineVariant = Color(0xFF3D4A3D)
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
