package ru.liturgyguide.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class GuideThemeContrastTest {
    // WCAG relative luminance, tested against the actual production color roles.
    private fun luminance(color: Color): Double {
        fun linear(channel: Float): Double = channel.toDouble().let {
            if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val first = luminance(a)
        val second = luminance(b)
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }

    private fun surfaces(colors: ColorScheme) = mapOf(
        "background" to colors.background, "surface" to colors.surface,
        "surfaceDim" to colors.surfaceDim, "surfaceBright" to colors.surfaceBright,
        "lowest" to colors.surfaceContainerLowest, "low" to colors.surfaceContainerLow,
        "container" to colors.surfaceContainer, "high" to colors.surfaceContainerHigh,
        "highest" to colors.surfaceContainerHighest, "variant" to colors.surfaceVariant,
        "primaryContainer" to colors.primaryContainer, "secondaryContainer" to colors.secondaryContainer
    )

    @Test fun readingAndSecondaryTextRemainReadableOnEverySurface() {
        for ((theme, colors) in mapOf("light" to LightColors, "dark" to DarkColors)) {
            for ((surfaceName, surface) in surfaces(colors)) {
                for ((role, foreground) in mapOf("text" to colors.onSurface, "secondary text" to colors.onSurfaceVariant,
                    "accent text" to colors.primary)) {
                    val ratio = contrast(foreground, surface)
                    assertTrue("$theme $role on $surfaceName: $ratio", ratio >= 4.5)
                }
            }
        }
    }

    @Test fun filledControlsAndInverseMessagesKeepReadableLabels() {
        for ((theme, colors) in mapOf("light" to LightColors, "dark" to DarkColors)) {
            val pairs = mapOf(
                "primary" to (colors.onPrimary to colors.primary),
                "secondary" to (colors.onSecondary to colors.secondary),
                "primary container" to (colors.onPrimaryContainer to colors.primaryContainer),
                "secondary container" to (colors.onSecondaryContainer to colors.secondaryContainer),
                "inverse" to (colors.inverseOnSurface to colors.inverseSurface),
                "inverse action" to (colors.inversePrimary to colors.inverseSurface)
            )
            for ((role, pair) in pairs) {
                val ratio = contrast(pair.first, pair.second)
                assertTrue("$theme $role: $ratio", ratio >= 4.5)
            }
        }
    }

    @Test fun activeControlOutlinesRemainVisibleOnEverySurface() {
        for ((theme, colors) in mapOf("light" to LightColors, "dark" to DarkColors)) {
            for ((role, surface) in surfaces(colors)) {
                val ratio = contrast(colors.outline, surface)
                assertTrue("$theme outline on $role: $ratio", ratio >= 3.0)
            }
        }
    }
}
