package ru.liturgyguide.ui

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.ChurchLocator

@Composable
fun LocateChurchCard(churches: List<Parish>, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val locator = remember(context) { ChurchLocator(context) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var suggested by remember { mutableStateOf<Parish?>(null) }
    fun locate() {
        busy = true; message = null; suggested = null
        locator.locate { location, error ->
            busy = false
            if (location == null) message = error
            else if (!location.hasAccuracy() || location.accuracy > 500f) message = "Местоположение слишком приблизительное. Выберите храм из списка"
            else {
                val nearest = churches.mapNotNull { church ->
                    val lat = church.latitude ?: return@mapNotNull null
                    val lon = church.longitude ?: return@mapNotNull null
                    val distance = FloatArray(1)
                    Location.distanceBetween(location.latitude, location.longitude, lat, lon, distance)
                    church to distance[0]
                }.minByOrNull { it.second }
                if (nearest != null && nearest.second <= 500f) suggested = nearest.first
                else message = "Рядом не найден один из добавленных храмов. Пока доступны два храма из списка"
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.any { it }) locate()
        else message = "Можно продолжить без геолокации: выберите храм ниже"
    }
    DisposableEffect(locator, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { locator.stop(); busy = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); locator.stop() }
    }
    OutlinedCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Вы уже рядом с храмом?", style = MaterialTheme.typography.titleMedium)
            Text("Местоположение поможет предложить храм из списка. Выбор подтвердите сами.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
                else permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(if (busy) "Определяем местоположение" else "Подсказать ближайший храм")
            }
            if (busy) TextButton(onClick = { locator.stop(); busy = false }) { Text("Отмена") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            suggested?.let { church ->
                Text("Похоже, вы рядом: ${church.shortName}", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { onSelect(church.id); suggested = null; message = "Храм выбран" }) { Text("Да, я здесь") }
            }
        }
    }
}
