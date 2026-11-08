package ru.liturgyguide.ui

import android.Manifest
import android.content.pm.PackageManager
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
import ru.liturgyguide.service.ChurchDirectory
import ru.liturgyguide.service.ChurchLocator
import ru.liturgyguide.service.NearbyChurch
import java.util.Locale

@Composable
fun LocateChurchCard(churches: List<Parish>, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val locator = remember(context) { ChurchLocator(context) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var suggested by remember { mutableStateOf<List<NearbyChurch>>(emptyList()) }
    fun locate() {
        busy = true; message = null; suggested = emptyList()
        locator.locate { location, error ->
            busy = false
            if (location == null) message = error
            else {
                suggested = ChurchDirectory.nearby(churches, location.latitude, location.longitude, location.accuracy)
                message = if (suggested.isEmpty()) "Среди добавленных храмов поблизости ничего не найдено. Можно добавить свой храм"
                    else if (location.accuracy > 500f) "Место определено приблизительно. Проверьте название и адрес храма"
                    else "Проверьте название и адрес, затем выберите храм"
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.any { it }) locate()
        else message = "Можно продолжить без геолокации: выберите храм ниже"
    }
    DisposableEffect(locator, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { locator.stop(); busy = false } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); locator.stop() }
    }
    OutlinedCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Добавленные храмы рядом", style = MaterialTheme.typography.titleMedium)
            Text("Сравним ваше место с храмами, сохраненными на телефоне. Выбор остается за вами", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
                else permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(if (busy) "Определяем местоположение" else "Подсказать ближайший храм")
            }
            if (busy) TextButton(onClick = { locator.stop(); busy = false }) { Text("Отмена") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            suggested.forEach { result ->
                val church = result.church
                OutlinedButton(onClick = { onSelect(church.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(church.shortName)
                        Text(listOf(church.city, church.address).filter(String::isNotBlank).distinct().joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        Text("Примерно ${String.format(Locale.forLanguageTag("ru"), "%.1f", result.distanceMeters / 1000)} км", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
