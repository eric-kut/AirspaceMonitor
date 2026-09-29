package ca.airspacemonitor.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import ca.airspacemonitor.AirspaceApp
import ca.airspacemonitor.data.AppSettings
import ca.airspacemonitor.data.DisplayFormats
import ca.airspacemonitor.domain.CeilingRef
import ca.airspacemonitor.domain.GeofenceMode

@Composable
fun ProfilesScreen(navController: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as AirspaceApp
    val vm: ProfilesViewModel = viewModel(
        factory = viewModelFactory { initializer { ProfilesViewModel(app.container) } },
    )
    val profiles by vm.profiles.collectAsState()
    val settings by vm.settings.collectAsState()

    Scaffold(
        floatingActionButtonPosition = FabPosition.Start,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { navController.navigate("editor/new") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New profile") },
            )
        },
    ) { padding ->
        if (profiles.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No profiles yet.", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(profile.name, fontWeight = FontWeight.Bold)
                            Text(describe(profile, settings), style = MaterialTheme.typography.bodySmall)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                IconButton(onClick = { navController.navigate("editor/${profile.id}") }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit")
                                }
                                IconButton(onClick = { vm.duplicate(profile.id) }) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate")
                                }
                                IconButton(onClick = { vm.delete(profile.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun describe(profile: ca.airspacemonitor.domain.Profile, settings: AppSettings): String {
    val altUnit = settings.altitudeUnit
    val distUnit = settings.distanceUnit
    val fence = when (profile.geofenceMode) {
        GeofenceMode.FOLLOW_PHONE ->
            "follows phone, ${DisplayFormats.formatDistance(profile.radiusKm ?: 0.0, distUnit)} radius"
        GeofenceMode.FIXED_CIRCLE ->
            "circle ${DisplayFormats.formatDistance(profile.radiusKm ?: 0.0, distUnit)} @ " +
                formatCoord(profile.centerLat ?: 0.0) + ", " + formatCoord(profile.centerLon ?: 0.0)
        GeofenceMode.POLYGON -> "polygon, ${profile.polygon?.size ?: 0} vertices"
    }
    val ceiling = when (profile.ceilingRef) {
        CeilingRef.ASL -> "${DisplayFormats.formatAltitude(profile.ceilingValue, altUnit)} ASL"
        CeilingRef.AGL -> "${DisplayFormats.formatAltitude(profile.ceilingValue, altUnit)} AGL"
    }
    val watch = profile.watch?.let { w ->
        when (w.mode) {
            ca.airspacemonitor.domain.WatchMode.OFFSET ->
                " · watch +${DisplayFormats.formatDistance(w.offsetHkm, distUnit)} / +" +
                    DisplayFormats.formatAltitude(w.offsetV, altUnit)
            ca.airspacemonitor.domain.WatchMode.FOLLOW_PHONE ->
                " · watch ${DisplayFormats.formatDistance(w.radiusKm, distUnit)}"
            ca.airspacemonitor.domain.WatchMode.FIXED_CIRCLE ->
                " · watch ${DisplayFormats.formatDistance(w.radiusKm, distUnit)} circle"
            ca.airspacemonitor.domain.WatchMode.POLYGON ->
                " · watch polygon ${w.polygon?.size ?: 0} vtx"
        }
    } ?: ""
    val flags = if (profile.warningVoiceEnabled || profile.watchVoiceEnabled) " · voice" else ""
    val cooldown = profile.alertCooldownMin
    val cooldownText = if (cooldown % 1.0 == 0.0) cooldown.toInt().toString() else cooldown.toString()
    return "warning $fence · $ceiling$watch · poll ${profile.pollIntervalSec}s · cooldown ${cooldownText}min$flags"
}