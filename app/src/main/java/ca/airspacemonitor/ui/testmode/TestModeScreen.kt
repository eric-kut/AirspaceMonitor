package ca.airspacemonitor.ui.testmode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.platform.LocalContext
import ca.airspacemonitor.AirspaceApp

@Composable
fun TestModeScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as AirspaceApp
    val vm: TestModeViewModel = viewModel(
        factory = viewModelFactory { initializer { TestModeViewModel(app.container) } },
    )
    val running by vm.running.collectAsState()
    val injected by vm.injectedSpec.collectAsState()

    var hex by remember { mutableStateOf("test01") }
    var callsign by remember { mutableStateOf("TEST01") }
    var altitude by remember { mutableStateOf("2500") }
    var distance by remember { mutableStateOf("2") }
    var bearing by remember { mutableStateOf("45") }

    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Test mode", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        Card(
            colors = if (running) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            else CardDefaults.cardColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    if (running) "Monitoring is running — injected aircraft will be processed."
                    else "Monitoring is NOT running. Start it on the Map tab first.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (injected != null) {
                    Text(
                        "Aircraft injected: ${injected?.callsign ?: injected?.hex} stays in the " +
                            "pipeline for 10 minutes (or until removed).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Text(
            "Injects a synthetic aircraft into the monitoring pipeline to verify " +
                "end-to-end alerting without real traffic. Works with airplane mode " +
                "and data off. Default position: 2 km NE of the profile reference point.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = callsign,
                onValueChange = { callsign = it },
                label = { Text("Callsign") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = hex,
                onValueChange = { hex = it },
                label = { Text("ICAO hex") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = altitude,
            onValueChange = { altitude = it },
            label = { Text("Altitude (ft MSL)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = distance,
                onValueChange = { distance = it },
                label = { Text("Distance (km)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = bearing,
                onValueChange = { bearing = it },
                label = { Text("Bearing (°)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    vm.inject(
                        hex = hex,
                        callsign = callsign,
                        altitudeFt = altitude.toDoubleOrNull() ?: 2500.0,
                        distanceKm = distance.toDoubleOrNull() ?: 2.0,
                        bearingDeg = bearing.toDoubleOrNull() ?: 45.0,
                    )
                },
                enabled = running,
            ) { Text("Inject aircraft") }
            OutlinedButton(onClick = vm::clear, enabled = injected != null) { Text("Remove") }
        }

        Text(
            "Expect a heads-up notification within 2 poll cycles (default ~24 s). " +
                "“Map” opens the adsbexchange ICAO page for the injected hex.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}