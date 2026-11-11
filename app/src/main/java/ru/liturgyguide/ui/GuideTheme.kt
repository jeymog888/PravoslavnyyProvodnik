package ru.liturgyguide.ui

import android.app.Activity
import android.view.View
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

// Slightly warm grey paper reduces the large white reading area without weakening the text.
// Every surface tier is explicit so dialogs and menus use the same quiet palette.
internal val LightColors = lightColorScheme(
    primary = Color(0xFF2C5A49), onPrimary = Color(0xFFF3F5EF),
    primaryContainer = Color(0xFFDCE7DD), onPrimaryContainer = Color(0xFF193E2D),
    secondary = Color(0xFF566157), onSecondary = Color(0xFFF3F5EF),
    secondaryContainer = Color(0xFFDDE3DB), onSecondaryContainer = Color(0xFF303C32),
    background = Color(0xFFE6E8E2), onBackground = Color(0xFF252B26),
    surface = Color(0xFFF0F1ED), onSurface = Color(0xFF252B26),
    surfaceDim = Color(0xFFDCDFD7), surfaceBright = Color(0xFFF0F1ED),
    surfaceContainerLowest = Color(0xFFF4F5F1), surfaceContainerLow = Color(0xFFEAEBE6),
    surfaceContainer = Color(0xFFE2E5DE), surfaceContainerHigh = Color(0xFFDADFD6),
    surfaceContainerHighest = Color(0xFFD2D8CF),
    surfaceVariant = Color(0xFFE2E5DE), onSurfaceVariant = Color(0xFF535F54),
    outline = Color(0xFF6E796F), outlineVariant = Color(0xFFC5CEC3),
    surfaceTint = Color(0xFF2C5A49),
    inverseSurface = Color(0xFF303831), inverseOnSurface = Color(0xFFE6EBE1), inversePrimary = Color(0xFFA8C9B2)
)
internal val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C9B2), onPrimary = Color(0xFF183A27),
    primaryContainer = Color(0xFF304C39), onPrimaryContainer = Color(0xFFD9E9D8),
    secondary = Color(0xFFBBC8B9), onSecondary = Color(0xFF29372C),
    secondaryContainer = Color(0xFF35463A), onSecondaryContainer = Color(0xFFDCE7D9),
    background = Color(0xFF171B18), onBackground = Color(0xFFDDE4D9),
    surface = Color(0xFF1F2420), onSurface = Color(0xFFDDE4D9),
    surfaceDim = Color(0xFF171B18), surfaceBright = Color(0xFF363E37),
    surfaceContainerLowest = Color(0xFF121613), surfaceContainerLow = Color(0xFF272E28),
    surfaceContainer = Color(0xFF2E362F), surfaceContainerHigh = Color(0xFF363F37),
    surfaceContainerHighest = Color(0xFF414B42),
    surfaceVariant = Color(0xFF363F37), onSurfaceVariant = Color(0xFFB8C3B5),
    outline = Color(0xFF909F8E), outlineVariant = Color(0xFF465448),
    surfaceTint = Color(0xFFA8C9B2),
    inverseSurface = Color(0xFFDDE4D9), inverseOnSurface = Color(0xFF29312B), inversePrimary = Color(0xFF2C5A49)
)
// Interface typography stays quiet and familiar. Book text supplies its own serif face.
private val GuideTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 27.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp),
    // Only overlong words in Russian UI headings are hyphenated. Body styles are also
    // used by Church Slavonic prayers and must not inherit a Russian dictionary.
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp,
        localeList = LocaleList("ru"), hyphens = Hyphens.Auto, lineBreak = LineBreak.Simple),
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

/** Material 3 1.3 uses the system theme for its separate sheet window. The app's
 * explicitly selected theme may differ, so match that window to the actual surface. */
@Composable
internal fun GuideSheetSystemBars() {
    val view = LocalView.current
    val lightSurface = MaterialTheme.colorScheme.surfaceContainerLow.luminance() > .5f
    DisposableEffect(view, lightSurface) {
        val apply = Runnable {
            if (view.isAttachedToWindow) {
                val provider = generateSequence(view.parent) { it.parent }
                    .filterIsInstance<DialogWindowProvider>().firstOrNull()
                provider?.window?.let { window ->
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightSurface
                        isAppearanceLightNavigationBars = lightSurface
                    }
                }
            }
        }
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { v.post(apply) }
            override fun onViewDetachedFromWindow(v: View) { v.removeCallbacks(apply) }
        }
        view.addOnAttachStateChangeListener(listener)
        view.post(apply)
        onDispose { view.removeCallbacks(apply); view.removeOnAttachStateChangeListener(listener) }
    }
}
