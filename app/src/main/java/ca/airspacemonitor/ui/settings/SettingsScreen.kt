package ca.airspacemonitor.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ca.airspacemonitor.AirspaceApp
import ca.airspacemonitor.data.AltitudeUnit
import ca.airspacemonitor.data.DistanceUnit

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as AirspaceApp
    val vm: SettingsViewModel = viewModel(
        factory = viewModelFactory { initializer { SettingsViewModel(app, app.container) } },
    )
    val settings by vm.settings.collectAsState()
    val scroll = rememberScrollState()
    var newHost by remember { mutableStateOf("") }
    var tileTemplate by remember(settings.tileServerTemplate) {
        mutableStateOf(settings.tileServerTemplate)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        // ---- API hosts -----------------------------------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ADS-B API hosts (failover order)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Tried top-to-bottom; after 3 consecutive failures the next host is used.",
                    style = MaterialTheme.typography.bodySmall,
                )
                settings.hosts.forEachIndexed { index, host ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. $host", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.moveHostUp(index) }, enabled = index > 0) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
                        }
                        IconButton(
                            onClick = { vm.moveHostDown(index) },
                            enabled = index < settings.hosts.size - 1,
                        ) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
                        }
                        IconButton(onClick = { vm.removeHost(index) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newHost,
                        onValueChange = { newHost = it },
                        label = { Text("https://host/v2") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            vm.addHost(newHost)
                            newHost = ""
                        },
                        enabled = newHost.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("Add")
                    }
                }
            }
        }

        // ---- Units ---------------------------------------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Units", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.distanceUnit == DistanceUnit.KM,
                        onClick = { vm.setDistanceUnit(DistanceUnit.KM) },
                        label = { Text("km + km/h") },
                    )
                    FilterChip(
                        selected = settings.distanceUnit == DistanceUnit.NM,
                        onClick = { vm.setDistanceUnit(DistanceUnit.NM) },
                        label = { Text("nm + kt") },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.altitudeUnit == AltitudeUnit.FT,
                        onClick = { vm.setAltitudeUnit(AltitudeUnit.FT) },
                        label = { Text("feet") },
                    )
                    FilterChip(
                        selected = settings.altitudeUnit == AltitudeUnit.M,
                        onClick = { vm.setAltitudeUnit(AltitudeUnit.M) },
                        label = { Text("meters") },
                    )
                }
            }
        }

        // ---- Notifications -------------------------------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Cleared notifications", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Cheerful chime when an aircraft leaves your area — " +
                                "distinct tones for the watch layer and the warning zone.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = settings.allClearEnabled, onCheckedChange = vm::setAllClear)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Record event history", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Keep a log of enter/exit episodes and recorded positions (History tab, " +
                                "exportable as CSV). Events older than 7 days are pruned automatically.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = settings.historyEnabled, onCheckedChange = vm::setHistoryEnabled)
                }
            }
        }

        // ---- Tiles ---------------------------------------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Tile server (OpenStreetMap)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Optional override. Template with {z}/{x}/{y}, e.g. " +
                        "https://tile.openstreetmap.org/{z}/{x}/{y}.png. Blank = default.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = tileTemplate,
                        onValueChange = { tileTemplate = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { vm.setTileTemplate(tileTemplate) }) { Text("Apply") }
                }
            }
        }

        // ---- Battery -------------------------------------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Battery optimization", style = MaterialTheme.typography.titleSmall)
                Text(
                    "For reliable multi-hour background monitoring, the app should be exempt " +
                        "from battery optimization. Android may otherwise throttle the " +
                        "monitoring service while the screen is off. Declining is fine — " +
                        "monitoring just may be less reliable on some devices.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val exempt = vm.isIgnoringBatteryOptimizations()
                OutlinedButton(onClick = vm::requestBatteryExemption) {
                    Text(if (exempt) "Exempted ✓ (tap to review)" else "Request exemption")
                }
            }
        }

        // ---- About / data sources ------------------------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Data sources", style = MaterialTheme.typography.titleSmall)
                Text(
                    "ADS-B data: adsb.lol (ODbL), airplanes.live, adsb.fi. " +
                        "Elevation: Open-Meteo. Map tiles: © OpenStreetMap contributors.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { uriHandler.openUri("https://www.adsb.lol/") }) {
                        Text("adsb.lol", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { uriHandler.openUri("https://airplanes.live/") }) {
                        Text("airplanes.live", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { uriHandler.openUri("https://adsb.fi/") }) {
                        Text("adsb.fi", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { uriHandler.openUri("https://www.openstreetmap.org/copyright") }) {
                        Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}