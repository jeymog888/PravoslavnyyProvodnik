package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A dialog owns a new Android view. Its whole layout must use the app's requested text scale. */
@Composable
internal fun ChurchChangeDialog(message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val requestedDensity = LocalDensity.current
    // Compose 1.7's dialog measures against screenHeightDp, which includes system bars
    // on edge-to-edge Android 15. Keep the footer inside the usable screen as well.
    val systemInsets = WindowInsets.safeDrawing.asPaddingValues()
    val availableHeight = (LocalConfiguration.current.screenHeightDp.dp -
        systemInsets.calculateTopPadding() - systemInsets.calculateBottomPadding()).coerceAtLeast(0.dp)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDensity provides requestedDensity) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large,
                modifier = Modifier.heightIn(max = availableHeight).padding(24.dp).widthIn(max = 560.dp).fillMaxWidth()
                    .testTag("church_change_confirmation").semantics { paneTitle = "Смена храма" }) {
                Column(Modifier.padding(24.dp)) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .testTag("church_change_message"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Сменить храм?", style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() })
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(16.dp))
                    if (requestedDensity.fontScale > 1.5f) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ConfirmChurchButton(onConfirm, Modifier.fillMaxWidth())
                            CancelChurchButton(onDismiss, Modifier.fillMaxWidth())
                        }
                    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        CancelChurchButton(onDismiss)
                        ConfirmChurchButton(onConfirm)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmChurchButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp).testTag("confirm_church_change")) {
        Text("Сменить храм")
    }
}

@Composable
private fun CancelChurchButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp).testTag("cancel_church_change")) {
        Text("Остаться")
    }
}
