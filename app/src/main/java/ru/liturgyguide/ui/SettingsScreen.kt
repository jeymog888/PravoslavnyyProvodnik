package ru.liturgyguide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.liturgyguide.model.Church

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onEnableDnd: () -> Unit = {}
) {
    val church = Church()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top bar
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Назад"
            )
        }

        Text(
            text = "Настройки",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // Church info
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Храм", style = MaterialTheme.typography.titleMedium)
                Text(text = church.name, style = MaterialTheme.typography.bodyLarge)
                Text(text = church.address, style = MaterialTheme.typography.bodyMedium)
                Text(text = "Тел: ${church.phone}", style = MaterialTheme.typography.bodySmall)
                Text(text = "Сайт: ${church.website}", style = MaterialTheme.typography.bodySmall)
            }
        }

        // DND mode
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Режим \"В храме\"", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Включает режим \"Не беспокоить\" на время службы. Будильники и звонки от избранных контактов будут пропущены.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                FilledButton(onClick = onEnableDnd) {
                    Text("Настроить тишину")
                }
            }
        }

        // About
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "О приложении", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Проводник по богослужению v0.1.0",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Храм Всецарицы, Красногорск",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        TextButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Назад")
        }
    }
}