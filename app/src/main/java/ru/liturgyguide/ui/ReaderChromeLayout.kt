package ru.liturgyguide.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

internal enum class ReaderChrome { NORMAL, COMPACT, SIDEBAR }
internal data class ReaderChromeLayout(val kind: ReaderChrome, val panelWidth: Dp = 0.dp,
    val fitsReadingBudget: Boolean = true, val estimatedReadingHeight: Dp? = null)

/** Only choose placement. Every sample uses the current font converter and available width. */
@Composable
internal fun readerChromeLayout(width: Dp, height: Dp, fullTitle: String, shortTitle: String,
    status: List<String>, primary: List<String>, compactStatus: List<String>, compactPrimary: List<String>,
    soundless: Boolean): ReaderChromeLayout {
    val density = LocalDensity.current
    val text = rememberTextMeasurer()
    val typography = MaterialTheme.typography
    val w = with(density) { width.roundToPx() }
    val h = with(density) { height.roundToPx() }
    fun px(value: Int) = with(density) { value.dp.roundToPx() }
    fun size(value: String, style: TextStyle, available: Int) = text.measure(value, style,
        constraints = Constraints(maxWidth = available.coerceAtLeast(1))).size
    fun tallest(values: List<String>, style: TextStyle, available: Int) = values.maxOf { size(it, style, available).height }
    fun button(values: List<String>, available: Int, style: TextStyle = typography.labelLarge, padding: Int = 12) =
        max(px(48), tallest(values, style, available - px(padding * 2)) + px(16))
    fun navigation(title: String) = max(px(48), size(title, typography.titleMedium, w - px(146)).height + px(8))
    val modes = listOf("Оригинал", "По-русски", "Смысл")
    fun modesFit(available: Int): Boolean = modes.all { text.measure(it, typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        constraints = Constraints(maxWidth = (available / 3 - px(14)).coerceAtLeast(1))).lineCount == 1 }
    fun modesHeight(available: Int) = button(modes, available / 3 - px(6), typography.bodySmall, 4) + px(18)
    // The future microphone has no UI/callback yet, but its full label already participates
    // in the available-space decision. Stage 4 will not add an unbudgeted row.
    val directSamples = listOf("Уточнить", "Микрофон")
    val normalInner = w - px(32)
    val noteWidth = if (soundless) size("Без звука", typography.bodySmall, w).width + px(8) else 0
    val secondaryWidth = normalInner / 3
    val normalSecondary = maxOf(button(directSamples, secondaryWidth, padding = 4),
        button(listOf("К выбранной фразе"), secondaryWidth, typography.bodySmall, 8),
        button(listOf("Пауза"), secondaryWidth, padding = 8),
        button(listOf("Управление"), secondaryWidth, padding = 4))
    val normalFooter = tallest(status, typography.bodySmall, normalInner - noteWidth) +
        max(px(56), button(primary, normalInner)) +
        normalSecondary + px(24)
    val normalHeader = navigation(fullTitle) + modesHeight(normalInner)
    if (modesFit(normalInner) && h - normalHeader - normalFooter - px(16) >= px(120))
        return ReaderChromeLayout(ReaderChrome.NORMAL)

    val chooserStyle = typography.labelLarge.merge(typography.bodySmall)
    fun chooser(available: Int) = max(px(48),
        max(px(18), tallest(modes, chooserStyle, available - px(26))) + px(12))
    fun oneLine(values: List<String>, style: TextStyle, available: Int) = available > 0 && values.all {
        text.measure(it, style, constraints = Constraints(maxWidth = available)).lineCount == 1
    }
    val sideHeader = navigation(shortTitle)
    for (fraction in listOf(.34f, .38f, .42f)) {
        val panel = (w * fraction).toInt()
        val inner = panel - px(16)
        val panelHeight = chooser(inner) +
            tallest(compactStatus, typography.bodySmall, inner) +
            button(compactPrimary, inner, padding = 6) +
            button(directSamples + "Пауза", inner - px(48), padding = 4) +
            px(28)
        val bodyHeight = h - sideHeader - px(8)
        if (w >= h && w - panel - px(24) >= px(240) &&
            oneLine(modes, chooserStyle, inner - px(26)) &&
            oneLine(compactPrimary, typography.labelLarge, inner - px(12)) &&
            oneLine(directSamples, typography.labelLarge, inner - px(56)) &&
            bodyHeight >= max(px(120), panelHeight))
            return ReaderChromeLayout(ReaderChrome.SIDEBAR, panelWidth = with(density) { panel.toDp() })
    }

    val inner = w - px(16)
    val compactFooter = tallest(compactStatus, typography.bodySmall, inner) +
        button(compactPrimary, inner, padding = 6) + button(directSamples, inner - px(48), padding = 4) + px(16)
    // The stage gets the full row: 304 - 16 padding - 18 chevron = 270dp at a 320dp viewport.
    val withStage = max(px(48), size(shortTitle, typography.titleMedium, w - px(50)).height) +
        chooser(w - px(112))
    val remaining = h - withStage - compactFooter - px(8)
    // Keep the stage visible even if this candidate does not fit. The first physical
    // witness must expose that failure; hiding the title needs a separate product decision.
    return ReaderChromeLayout(ReaderChrome.COMPACT, fitsReadingBudget = remaining >= px(120),
        estimatedReadingHeight = with(density) { remaining.toDp() })
}
