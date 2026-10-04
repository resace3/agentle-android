package dev.agentle.app.shell

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentle.connectors.api.sensors.SensorCheck
import dev.agentle.connectors.api.sensors.SensorGroup
import dev.agentle.connectors.api.sensors.SensorPermission
import dev.agentle.connectors.api.sensors.SensorState
import dev.agentle.connectors.api.sensors.SensorStatus

/** Every sensor the phone lists, grouped, with whether the app can read it right now and its latest value. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SensorsScreen(viewModel: SensorsViewModel, modifier: Modifier = Modifier) {
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.checkAgain() }
    val missing = statuses.filter { it.state == SensorState.NEEDS_PERMISSION }.map { it.sensor.kind.permission }.distinct()
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "summary") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(SensorCheck.summarize(statuses).line(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Android sends sensor data only to the app on screen, so this check runs while this tab is open. " +
                            "Sensors marked ready report only when something happens, like a step or a tilt.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::checkAgain) { Text("Check again") }
                        missing.forEach { permission ->
                            OutlinedButton(onClick = { askPermission.launch(requestable(permission)) }) {
                                Text("Allow ${permissionName(permission)}")
                            }
                        }
                    }
                }
            }
        }
        SensorGroup.entries.forEach { group ->
            val inGroup = statuses.filter { it.sensor.kind.group == group }
            if (inGroup.isNotEmpty()) {
                item(key = group.name) {
                    Text(group.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                }
                items(inGroup, key = { it.sensor.id }) { SensorRow(it) }
            }
        }
    }
}

@Composable
private fun SensorRow(status: SensorStatus) {
    val sensor = status.sensor
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    sensor.kind.label + if (sensor.wakeUp) " (wake-up)" else "",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    status.state.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (status.state.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                listOf(sensor.name, sensor.vendor).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status.value?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (status.state == SensorState.NEEDS_PERMISSION) {
                Text("Needs the ${permissionName(sensor.kind.permission)} permission.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun permissionName(permission: SensorPermission): String = when (permission) {
    SensorPermission.ACTIVITY_RECOGNITION -> "physical activity"
    SensorPermission.BODY_SENSORS -> "body sensors"
    SensorPermission.NONE -> "no"
}

/** Heart sensors are granted through `health.READ_HEART_RATE` from Android 16 for apps that target it. */
private fun requestable(permission: SensorPermission): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && permission.alternatives.isNotEmpty()) {
        permission.alternatives.first()
    } else {
        permission.androidPermission.orEmpty()
    }
