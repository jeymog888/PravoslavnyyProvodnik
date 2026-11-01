package ru.liturgyguide.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ru.liturgyguide.LiturgyGuideApplication

enum class Screen {
    MAIN, SERVICE, STAGE_SELECTOR, SETTINGS
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as LiturgyGuideApplication
        val container = app.container

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(container)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(container: ru.liturgyguide.di.AppContainer) {
    var currentScreen by remember { mutableStateOf(Screen.MAIN) }
    var selectedStageName by remember { mutableStateOf<String?>(null) }

    when (currentScreen) {
        Screen.MAIN -> MainScreen(
            calendarEngine = container.getCalendarEngine(),
            onStartService = { currentScreen = Screen.SERVICE },
            onSelectStage = { currentScreen = Screen.STAGE_SELECTOR },
            onSettings = { currentScreen = Screen.SETTINGS }
        )
        Screen.SERVICE -> ServiceScreen(
            stageMatcher = container.getStageMatcher(),
            progressTracker = container.getProgressTracker(),
            initialStageName = selectedStageName,
            onBack = { currentScreen = Screen.MAIN },
            onSelectStage = { currentScreen = Screen.STAGE_SELECTOR }
        )
        Screen.STAGE_SELECTOR -> StageSelector(
            stageMatcher = container.getStageMatcher(),
            onSelect = { name ->
                selectedStageName = name
                currentScreen = Screen.SERVICE
            },
            onBack = { currentScreen = Screen.MAIN }
        )
        Screen.SETTINGS -> SettingsScreen(
            onBack = { currentScreen = Screen.MAIN }
        )
    }
}