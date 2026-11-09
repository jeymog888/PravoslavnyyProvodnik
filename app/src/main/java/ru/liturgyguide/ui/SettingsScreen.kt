package ru.liturgyguide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(dark: Boolean, textSize: Int, onDarkChange: (Boolean) -> Unit,
    onTextSizeChange: (Int) -> Unit, onSoundSettings: () -> Unit, onSources: () -> Unit,
    onBack: () -> Unit, showBack: Boolean = true, bottomBar: @Composable () -> Unit = {}) {
    Scaffold(topBar = { Column(Modifier.statusBarsPadding()) {
        if (showBack) PageHeader("Настройки", onBack)
    } }, bottomBar = bottomBar) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("settings_content"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            if (!showBack) item {
                Text("Настройки", Modifier.testTag("settings_title").semantics { heading() },
                    style = MaterialTheme.typography.headlineLarge.copy(localeList = LocaleList("ru"),
                        hyphens = Hyphens.Auto, lineBreak = LineBreak.Heading))
            }
            item {
                Text("Экран", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    ListItem(headlineContent = { Text("Темный экран") }, supportingContent = { Text("Меньше света во время службы") },
                        leadingContent = { Icon(Icons.Default.DarkMode, null) },
                        trailingContent = { Switch(checked = dark, onCheckedChange = onDarkChange,
                            modifier = Modifier.testTag("dark_switch").semantics { contentDescription = "Темный экран" }) })
                }
            }
            item {
                Text("Размер текста", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    Column {
                        listOf("Обычный", "Крупный", "Очень крупный").forEachIndexed { index, title ->
                            ListItem(headlineContent = { Text(title) }, trailingContent = { RadioButton(selected = textSize == index, onClick = null) },
                                modifier = Modifier.selectable(selected = textSize == index, role = Role.RadioButton, onClick = { onTextSizeChange(index) }).testTag("text_size_$index"))
                            if (index < 2) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Учитывается и размер шрифта на телефоне", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Text("Тишина", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    GuideActionRow("Не беспокоить", "Открыть настройки телефона", Icons.Default.NotificationsOff, onSoundSettings, "sound_settings")
                }
                Spacer(Modifier.height(12.dp))
                Text("В настройках телефона можно оставить важные звонки и будильники. После службы проверьте, нужен ли режим тишины дальше.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    GuideActionRow("О Феории", "Тексты, источники и возможности", Icons.Default.Info, onSources, "open_sources")
                }
                Text("Феория · 0.6", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text("Основные молитвы приведены целиком, другие - характерными фрагментами. Полный текст означает полноту выбранной молитвы, а не всего этапа. Русский смысл молитв передан редакцией приложения и не является утвержденным богослужебным переводом. Источники открываются из карточек. Для открытия сайта нужен интернет.")
                sourceUrl?.let { url -> TextButton(onClick = { onOpenUrl(url) }) { Text("Открыть чин литургии") } }
            }
            item {
                Text("Как работает текстовый плеер", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("Выберите молитву или чтение и нужную фразу. Переключение оригинала и русского текста сохраняет место. Чтения Писания даны в Синодальном переводе. Плеер приблизительно ведет по фразам, текстам и этапам. После короткого фрагмента может появиться ожидание с пояснением. Если чтения на выбранную дату нет, продолжение требует вашего действия. Плеер не подстраивается под голос или хор. Уход с экрана ставит его на паузу; возврат не запускает движение")
            }
            item {
                Text("Подсказка по звуку", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("На совместимых телефонах можно послушать короткий фрагмент и поискать знакомую фразу. Эхо, хор и церковнославянская речь могут мешать распознаванию. Подсказку нужно сверить с тем, что происходит в храме.")
            }
            item {
                Text("Ваши данные", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("Распознавание выполняется только на устройстве, аудио не сохраняется. Микрофон включается по вашей кнопке и прекращает захват при уходе с экрана. При внешнем поиске название храма или города отправляется в Photon, округленный район поиска - в VK Maps Overpass. Кэш поиска, сохраненные храмы с координатами, место чтения и настройки хранятся на телефоне. Отслеживания местоположения в фоне нет. Без разрешения на местоположение доступны поиск по названию храма или городу, ручное добавление храма и чтение")
            }
            item {
                Text("Живая служба может отличаться", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("Песнопения и чтения меняются по дню, а продолжительность - по ходу службы. Плеер использует приблизительные длительности и может расходиться с реальной службой. Его можно поставить на паузу и уточнить место. По вопросам участия в таинствах и местных обычаев лучше обратиться к священнику своего храма.")
            }
        }
    }
}
