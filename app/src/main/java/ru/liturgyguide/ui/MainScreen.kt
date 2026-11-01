package ru.liturgyguide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.liturgyguide.model.Church
import ru.liturgyguide.model.ChurchDay
import ru.liturgyguide.service.CalendarEngine

@Composable
fun MainScreen(
    calendarEngine: CalendarEngine,
    onStartService: () -> Unit,
    onSelectStage: () -> Unit,
    onSettings: () -> Unit
) {
    val church = Church()
    val today = calendarEngine.getToday()
    val dateText = "${today.date} (${today.dayOfWeek})"
    val feastText = today.feast ?: ""
    val fastingText = today.fasting ?: ""
    val scheduleText = today.scheduleToday ?: "Расписание не указано"
    val serviceText = today.typicalService

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Church name header
        Text(
            text = church.name,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = church.city,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Today's info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = dateText, fontSize = 18.sp, fontWeight = FontWeight.Medium)

                if (feastText.isNotEmpty()) {
                    Text(
                        text = feastText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (fastingText.isNotEmpty()) {
                    Text(
                        text = fastingText,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Text(text = "Служба: $serviceText", fontSize = 14.sp)
                Text(text = "Расписание: $scheduleText", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Main CTA
        FilledButton(
            modifier = Modifier.fillMaxWidth().height(64.dp),
            onClick = onStartService,
            colors = ButtonDefaults.filledButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(
                text = "Начать распознавание",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onSelectStage
        ) {
            Text(text = "Выбрать этап вручную", fontSize = 16.sp)
        }

        TextButton(onClick = onSettings) {
            Text(text = "Настройки храма", fontSize = 14.sp)
        }

        Text(
            text = "Священник: ${church.priest}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}