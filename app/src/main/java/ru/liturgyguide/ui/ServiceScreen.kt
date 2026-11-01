package ru.liturgyguide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.ServiceProgress
import ru.liturgyguide.service.ProgressTracker
import ru.liturgyguide.service.StageMatcher
import java.time.LocalTime

@Composable
fun ServiceScreen(
    stageMatcher: StageMatcher,
    progressTracker: ProgressTracker,
    initialStageName: String?,
    onBack: () -> Unit,
    onSelectStage: () -> Unit
) {
    val allStages = stageMatcher.getAllStages()
    var currentIndex by remember { mutableIntStateOf(
        allStages.indexOfFirst { it.name == initialStageName }.coerceAtLeast(0)
    ) }
    val startTime = remember { LocalTime.now() }

    val currentStage = allStages[currentIndex]
    val progress = progressTracker.getProgress(startTime, currentStage.name)
    val nextStage = stageMatcher.getNextStage(currentStage.name)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Top bar
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                        modifier = Modifier.size(24.dp)
                    )
                }
                Text(
                    text = "Литургия Иоанна Златоуста",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Stage header
        item {
            Text(
                text = currentStage.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        // Progress info
        item {
            Text(
                text = "Этап ${progress.stageNumber} из ${progress.totalStages}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Progress bar
        item {
            LinearProgressIndicator(
                progress = progress.progressPercent / 100f,
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )
        }

        // Time info
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Прошло: ${progress.elapsedMinutes} мин",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Осталось: ${progress.remainingMinutes} мин",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Description
        item {
            Text(
                text = currentStage.description,
                style = MaterialTheme.typography.bodyLarge
            )
        }

        // Texts with translations
        items(currentStage.texts) { text ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = text.type,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = text.original,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = text.translation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = text.explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Navigation
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (currentIndex > 0) {
                    OutlinedButton(onClick = { currentIndex-- }) {
                        Text("Назад")
                    }
                } else {
                    Spacer(modifier = Modifier.size(1.dp))
                }

                if (nextStage != null) {
                    Button(onClick = { currentIndex++ }) {
                        Text("Далее: ${nextStage.name}")
                    }
                } else {
                    Button(onClick = onBack) {
                        Text("Завершить")
                    }
                }
            }
        }

        // Select another stage
        item {
            TextButton(
                onClick = onSelectStage,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Выбрать другой этап")
            }
        }
    }
}