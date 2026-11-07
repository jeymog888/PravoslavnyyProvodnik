package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    dark: Boolean, textSize: Int, onDarkChange: (Boolean) -> Unit,
    onTextSizeChange: (Int) -> Unit, onSoundSettings: () -> Unit,
    onSources: () -> Unit, onBack: () -> Unit,
    churchName: String?, onChurch: () -> Unit
) {
    Scaffold(topBar = { Column(Modifier.statusBarsPadding()) { PageHeader("Удобно в храме", onBack) } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("settings_content"),
            contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Меньше отвлечений", style = MaterialTheme.typography.headlineLarge)
                    Text("Настройте экран один раз. Ваш выбор сохранится.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                OutlinedCard {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Default.DarkMode, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Темный экран", style = MaterialTheme.typography.titleMedium)
                            Text("Спокойные цвета при слабом свете", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = dark, onCheckedChange = onDarkChange, modifier = Modifier.testTag("dark_switch").semantics { contentDescription = "Темный экран" })
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Размер текста", style = MaterialTheme.typography.titleLarge)
                    Text("Учитывается и размер шрифта в настройках телефона", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf("Обычный", "Крупный", "Очень крупный").forEachIndexed { index, label ->
                        OutlinedCard(onClick = { onTextSizeChange(index) }, modifier = Modifier.fillMaxWidth().testTag("text_size_$index").semantics { selected = textSize == index; role = Role.RadioButton },
                            colors = CardDefaults.outlinedCardColors(containerColor = if (textSize == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = textSize == index, onClick = null)
                                Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.NotificationsOff, null)
                        Text("Тишина во время службы", style = MaterialTheme.typography.titleLarge)
                        Text("Включите \"Не беспокоить\" в настройках телефона. Там же выберите, какие важные звонки и будильники оставить.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onSoundSettings, modifier = Modifier.fillMaxWidth()) { Text("Открыть настройки звука") }
                        Text("После службы выключите режим, если он больше не нужен", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                OutlinedCard(onClick = onChurch, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Eyebrow("МОЙ ХРАМ")
                        Text(churchName ?: "Выбрать храм", style = MaterialTheme.typography.titleMedium)
                        Text("Адрес, расписание и контакты", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                TextButton(onClick = onSources, modifier = Modifier.fillMaxWidth().testTag("open_sources")) {
                    Text("О текстах и возможностях", Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                }
                Text("Феория · 0.2\nЛитургия Иоанна Златоуста", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SourcesScreen(sourceUrl: String?, contentNote: String?, onOpenUrl: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { Column(Modifier.statusBarsPadding()) { PageHeader("О путеводителе", onBack) } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                FeoriaBrand()
                Spacer(Modifier.height(16.dp))
                Text("Название происходит от греческого θεωρία - \"созерцание\". Феория помогает внимательнее следить за службой и понимать ее смысл.", style = MaterialTheme.typography.bodyLarge)
            }
            item {
                Text("Понимание начинается с точных слов", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(16.dp))
                Text(contentNote ?: "Это краткий путеводитель по литургии Иоанна Златоуста", style = MaterialTheme.typography.bodyLarge)
            }
            item {
                Text("Текст и объяснение", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("Основные молитвы приведены целиком, другие - характерными фрагментами. Подпись \"Смысл по-русски\" означает редакционное объяснение, а не утвержденный богослужебный перевод. Источники открываются из карточек. Для открытия сайта нужен интернет.")
                sourceUrl?.let { url -> TextButton(onClick = { onOpenUrl(url) }) { Text("Открыть чин литургии") } }
            }
            item {
                Text("Подсказка по звуку", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("На совместимых телефонах можно послушать короткий фрагмент и поискать знакомую фразу. Эхо, хор и церковнославянская речь могут мешать распознаванию. Подсказку нужно сверить с тем, что происходит в храме.")
            }
            item {
                Text("Ваши данные", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("Приложение использует только распознавание на устройстве, не сохраняет аудио и не отправляет его на свой сервер. Микрофон включается по вашей кнопке и останавливается при уходе с экрана. Геолокация запрашивается только для подсказки храма и не сохраняется. Выбранный этап, храм и настройки экрана хранятся на телефоне.")
            }
            item {
                Text("Живая служба может отличаться", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("Песнопения и чтения меняются по дню, а продолжительность - по ходу службы. Режим \"Авто\" использует примерную длительность частей и может расходиться с реальной службой. Он включается отдельно и допускает ручную поправку. По вопросам участия в таинствах и местных обычаев лучше обратиться к священнику своего храма.")
            }
        }
    }
}
