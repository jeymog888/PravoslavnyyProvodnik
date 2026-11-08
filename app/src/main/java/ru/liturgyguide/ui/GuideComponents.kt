package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density

/** Navigation stays readable while content keeps the user's full text scale. */
@Composable
internal fun NavigationTextScale(enabled: Boolean = true, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides if (enabled) Density(density.density, density.fontScale.coerceAtMost(1.5f)) else density,
        content = content)
}

@Composable
fun PageHeader(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    NavigationTextScale {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text(title, Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            action?.invoke()
        }
    }
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun ContentUnavailable(onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Не удалось открыть путеводитель", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text("В приложении недоступны тексты службы. Попробуйте открыть их еще раз. Если это не поможет, потребуется переустановка приложения.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Попробовать снова") }
    }
}
