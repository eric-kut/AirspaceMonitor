package ca.airspacemonitor.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import ca.airspacemonitor.data.AltitudeUnit
import ca.airspacemonitor.data.DistanceUnit
import ca.airspacemonitor.data.DisplayFormats
import ca.airspacemonitor.domain.CeilingRef
import ca.airspacemonitor.domain.GeoMath
import ca.airspacemonitor.domain.GeoPoint
import ca.airspacemonitor.domain.GeofenceMode
import ca.airspacemonitor.domain.Tier
import ca.airspacemonitor.domain.Units
import ca.airspacemonitor.domain.WatchMode
import ca.airspacemonitor.ui.map.FenceSpec
import ca.airspacemonitor.ui.map.AirspaceMap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(navController: NavController, profileId: Long?) {
    val context = LocalContext.current
    val app = context.applicationContext as AirspaceApp
    val vm: ProfileEditorViewModel = viewModel(
        key = profileId?.toString() ?: "new",
        factory = viewModelFactory { initializer { ProfileEditorViewModel(app, app.container) } },
    )
    vm.load(profileId)

    val s by vm.state.collectAsState()
    if (!s.loaded) return

    // ---- Shared geometry ---------------------------------------------------
    val center = runCatching { GeoPoint(s.centerLatText.toDouble(), s.centerLonText.toDouble()) }.getOrNull()
    val centroid = s.polygon.takeIf { it.isNotEmpty() }?.let { poly ->
        GeoPoint(poly.sumOf { it.lat } / poly.size, poly.sumOf { it.lon } / poly.size)
    }
    val warningFence: FenceSpec? = when (s.geofenceMode) {
        GeofenceMode.POLYGON -> if (s.polygon.size >= 3) FenceSpec.Poly(s.polygon, Tier.WARNING) else null
        else -> center?.let { FenceSpec.Circle(it, s.radiusKm, Tier.WARNING) }
    }
    val watchCenter = runCatching {
        GeoPoint(
            s.watchCenterLatText.ifBlank { s.centerLatText }.toDouble(),
            s.watchCenterLonText.ifBlank { s.centerLonText }.toDouble(),
        )
    }.getOrNull()
    val warningExtentKm = when (s.geofenceMode) {
        GeofenceMode.POLYGON ->
            s.polygon.takeIf { it.size >= 3 }?.let { GeoMath.polygonBoundingCircle(it).second } ?: 0.0
        else -> s.radiusKm
    }
    val watchFence: FenceSpec? = if (!s.watchEnabled) {
        null
    } else {
        when (s.watchMode) {
            WatchMode.POLYGON -> if (s.watchPolygon.size >= 3) FenceSpec.Poly(s.watchPolygon, Tier.WATCH) else null
            WatchMode.FIXED_CIRCLE -> watchCenter?.let { FenceSpec.Circle(it, s.watchRadiusKm, Tier.WATCH) }
            WatchMode.OFFSET -> {
                val buffered = if (s.geofenceMode == GeofenceMode.POLYGON && s.polygon.size >= 3) {
                    GeoMath.offsetPolygon(s.polygon, s.watchOffsetHkm)
                } else {
                    null
                }
                when {
                    buffered != null -> FenceSpec.Poly(buffered, Tier.WATCH)
                    else -> (center ?: centroid)?.let {
                        FenceSpec.Circle(it, warningExtentKm + s.watchOffsetHkm, Tier.WATCH)
                    }
                }
            }
            WatchMode.FOLLOW_PHONE -> null
        }
    }
    val greyWarningFence: FenceSpec? = when (warningFence) {
        null -> null
        is FenceSpec.Circle -> warningFence.copy(grey = true)
        is FenceSpec.Poly -> warningFence.copy(grey = true)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = if (profileId == null) "New profile" else "Edit profile",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { vm.save { navController.popBackStack() } }) { Text("Save") }
        }

        // ---- Warning-zone map (pinned: NOT inside the scrolling column, so map
        // gestures pan/zoom the map instead of scrolling the page) ------------
        val warningCaption = when (s.geofenceMode) {
            GeofenceMode.POLYGON ->
                "Tap the map to add a WARNING vertex (${s.polygon.size}/64). Long-press near a vertex to delete it."
            GeofenceMode.FIXED_CIRCLE ->
                "Tap the map to move the warning zone center, or use your GPS position below."
            GeofenceMode.FOLLOW_PHONE ->
                "The warning zone follows your GPS position while monitoring." +
                    if (center != null) " Preview center: ${formatCoord(center.lat)}, ${formatCoord(center.lon)}" else ""
        }
        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
            Column {
                AirspaceMap(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    fences = listOfNotNull(warningFence, watchFence),
                    vertices = if (s.geofenceMode == GeofenceMode.POLYGON) s.polygon else emptyList(),
                    gpsPoint = null,
                    onMapTap = vm::onMapTap,
                    onMapLongPress = vm::onMapLongPress,
                    initialCenter = center ?: centroid ?: GeoPoint(45.4215, -75.6972),
                    initialZoom = 13.5,
                )
                Text(
                    warningCaption,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }

        Row(modifier = Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.geofenceMode == GeofenceMode.POLYGON) {
                OutlinedButton(onClick = vm::undoVertex, enabled = s.polygon.isNotEmpty()) {
                    Icon(Icons.Filled.Undo, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Undo warning vertex (${s.polygon.size})")
                }
            }
        }

        // ---- Everything below scrolls ---------------------------------------
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = s.name,
                onValueChange = vm::setName,
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // ---- Warning zone (mandatory) -----------------------------------
            Text("Warning zone — mandatory", style = MaterialTheme.typography.titleMedium)
            Text(
                "Aircraft inside this zone and at or below its ceiling trigger the urgent alarm. " +
                    "Tap the map above to place it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Warning zone type", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = s.geofenceMode == GeofenceMode.FOLLOW_PHONE,
                    onClick = { vm.setMode(GeofenceMode.FOLLOW_PHONE) },
                    label = { Text("Follow phone") },
                )
                FilterChip(
                    selected = s.geofenceMode == GeofenceMode.FIXED_CIRCLE,
                    onClick = { vm.setMode(GeofenceMode.FIXED_CIRCLE) },
                    label = { Text("Fixed circle") },
                )
                FilterChip(
                    selected = s.geofenceMode == GeofenceMode.POLYGON,
                    onClick = { vm.setMode(GeofenceMode.POLYGON) },
                    label = { Text("Polygon") },
                )
            }

            if (s.geofenceMode != GeofenceMode.POLYGON) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = s.centerLatText,
                        onValueChange = vm::setCenterLat,
                        label = { Text("Center lat") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = s.centerLonText,
                        onValueChange = vm::setCenterLon,
                        label = { Text("Center lon") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (s.geofenceMode == GeofenceMode.FIXED_CIRCLE) {
                    OutlinedButton(onClick = vm::useMyGpsPosition) { Text("Use my GPS position") }
                }
                Text("Radius: ${displayDistance(s.radiusKm, s.distanceUnit)}", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = s.radiusKm.toFloat(),
                    onValueChange = { vm.setRadius((it * 2).toInt() / 2.0) },
                    valueRange = 2f..30f,
                    steps = 55,
                )
            }

            CeilingSection(
                label = "Warning ceiling",
                valueText = s.ceilingValueText,
                onValue = vm::setCeilingValue,
                altitudeUnit = s.altitudeUnit,
                ref = s.ceilingRef,
                onRef = vm::setCeilingRef,
                terrainText = s.terrainText,
                onTerrain = vm::setTerrain,
                onFetch = vm::fetchElevation,
                elevationStatus = s.elevationStatus,
            )
            if (s.ceilingRef == CeilingRef.AGL) {
                Text(
                    "AGL is approximated as aircraft altitude (MSL) minus this profile terrain " +
                        "elevation (from the ~90 m Open-Meteo DEM). Terrain varies within your zone, " +
                        "so treat AGL figures as approximate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- Timing ---------------------------------------------------
            HorizontalDivider()
            Text("Poll interval: ${s.pollIntervalSec} s", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = s.pollIntervalSec.toFloat(),
                onValueChange = { vm.setPollInterval(it.toInt()) },
                valueRange = 5f..60f,
                steps = 54,
            )
            Text(
                "Re-alert cooldown: " + formatMinutes(s.cooldownMin) + " min",
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(
                value = s.cooldownMin.toFloat(),
                onValueChange = { vm.setCooldown((it * 2).toInt() / 2.0) },
                valueRange = 1f..10f,
                steps = 17,
            )

            // ---- Alerts ---------------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sound", modifier = Modifier.weight(1f))
                Switch(checked = s.soundEnabled, onCheckedChange = vm::setSound)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Vibration", modifier = Modifier.weight(1f))
                Switch(checked = s.vibrationEnabled, onCheckedChange = vm::setVibration)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Spoken voice — warning layer")
                    Text(
                        "Replaces the alarm tone with a synthesized announcement.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.warningVoiceEnabled, onCheckedChange = vm::setWarningVoice)
            }

            // ---- Watch layer (optional) -------------------------------------
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Watch layer — optional", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A wider/higher buffer around the warning zone. Aircraft in the watch layer " +
                            "but outside the warning zone get a plain notification instead of the alarm.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = s.watchEnabled, onCheckedChange = vm::setWatchEnabled)
            }
            if (s.watchEnabled) {
                Text("Watch layer type", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = s.watchMode == WatchMode.OFFSET,
                        onClick = { vm.setWatchMode(WatchMode.OFFSET) },
                        label = { Text("Offset") },
                    )
                    FilterChip(
                        selected = s.watchMode == WatchMode.FOLLOW_PHONE,
                        onClick = { vm.setWatchMode(WatchMode.FOLLOW_PHONE) },
                        label = { Text("Follow phone") },
                    )
                    FilterChip(
                        selected = s.watchMode == WatchMode.FIXED_CIRCLE,
                        onClick = { vm.setWatchMode(WatchMode.FIXED_CIRCLE) },
                        label = { Text("Fixed circle") },
                    )
                    FilterChip(
                        selected = s.watchMode == WatchMode.POLYGON,
                        onClick = { vm.setWatchMode(WatchMode.POLYGON) },
                        label = { Text("Polygon") },
                    )
                }
                when (s.watchMode) {
                    WatchMode.OFFSET -> {
                        Text(
                            "The watch layer is the warning zone grown by these margins.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Horizontal offset: ${displayDistance(s.watchOffsetHkm, s.distanceUnit)}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Slider(
                            value = s.watchOffsetHkm.toFloat(),
                            onValueChange = { vm.setWatchOffsetHkm((it * 2).toInt() / 2.0) },
                            valueRange = 0.5f..20.0f,
                        )
                        OutlinedTextField(
                            value = s.watchOffsetVText,
                            onValueChange = vm::setWatchOffsetV,
                            label = { Text("Vertical offset (${DisplayFormats.altitudeSuffix(s.altitudeUnit)})") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        effectiveWatchCeilingText(s)?.let {
                            Text(
                                "Effective watch ceiling: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    WatchMode.FOLLOW_PHONE, WatchMode.FIXED_CIRCLE -> {
                        Text("Watch radius: ${displayDistance(s.watchRadiusKm, s.distanceUnit)}", style = MaterialTheme.typography.titleSmall)
                        Slider(
                            value = s.watchRadiusKm.toFloat(),
                            onValueChange = { vm.setWatchRadius((it * 2).toInt() / 2.0) },
                            valueRange = 2f..30f,
                            steps = 55,
                        )
                        if (s.watchMode == WatchMode.FIXED_CIRCLE) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = s.watchCenterLatText,
                                    onValueChange = vm::setWatchCenterLat,
                                    label = { Text("Watch center lat") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = s.watchCenterLonText,
                                    onValueChange = vm::setWatchCenterLon,
                                    label = { Text("Watch center lon") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Text(
                                "Blank = same center as the warning zone. Tap the watch map below to set it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        CeilingSection(
                            label = "Watch ceiling",
                            valueText = s.watchCeilingText,
                            onValue = vm::setWatchCeilingValue,
                            altitudeUnit = s.altitudeUnit,
                            ref = s.watchCeilingRef,
                            onRef = vm::setWatchCeilingRef,
                            terrainText = s.terrainText,
                            onTerrain = vm::setTerrain,
                            onFetch = vm::fetchElevation,
                            elevationStatus = s.elevationStatus,
                        )
                    }
                    WatchMode.POLYGON -> {
                        Text(
                            "Watch polygon: ${s.watchPolygon.size} vertex(s). Tap the watch map below to add " +
                                "vertices; long-press near a vertex to delete it.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        CeilingSection(
                            label = "Watch ceiling",
                            valueText = s.watchCeilingText,
                            onValue = vm::setWatchCeilingValue,
                            altitudeUnit = s.altitudeUnit,
                            ref = s.watchCeilingRef,
                            onRef = vm::setWatchCeilingRef,
                            terrainText = s.terrainText,
                            onTerrain = vm::setTerrain,
                            onFetch = vm::fetchElevation,
                            elevationStatus = s.elevationStatus,
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Spoken voice — watch layer")
                        Text(
                            "Replaces the watch notification sound with a synthesized announcement.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = s.watchVoiceEnabled, onCheckedChange = vm::setWatchVoice)
                }

                // ---- Watch map: grey warning zone as reference, watch layer editable
                if (s.watchMode != WatchMode.FOLLOW_PHONE) {
                    val watchCaption = when (s.watchMode) {
                        WatchMode.POLYGON ->
                            "Tap to add a WATCH vertex (${s.watchPolygon.size}/64). Long-press near a vertex to delete it."
                        WatchMode.FIXED_CIRCLE -> "Tap the map to move the watch center."
                        else -> "The watch layer is derived from the warning zone (grey reference)."
                    }
                    Card {
                        Column {
                            AirspaceMap(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(240.dp),
                                fences = listOfNotNull(
                                    greyWarningFence,
                                    watchFence,
                                ),
                                vertices = if (s.watchMode == WatchMode.POLYGON) s.watchPolygon else emptyList(),
                                gpsPoint = null,
                                onMapTap = vm::onWatchMapTap,
                                onMapLongPress = vm::onWatchMapLongPress,
                                initialCenter = center ?: centroid ?: GeoPoint(45.4215, -75.6972),
                                initialZoom = 13.5,
                            )
                            Text(
                                watchCaption,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                    if (s.watchMode == WatchMode.POLYGON) {
                        OutlinedButton(onClick = vm::undoWatchVertex, enabled = s.watchPolygon.isNotEmpty()) {
                            Icon(Icons.Filled.Undo, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Undo watch vertex (${s.watchPolygon.size})")
                        }
                    }
                }
            }

            s.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    s.saveDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = vm::onSaveDialogCancel,
            title = { Text(if (dialog.isNameDialog) "Can't save profile" else "Fix the profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        dialog.message,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (dialog.isNameDialog) {
                        OutlinedTextField(
                            value = dialog.nameText,
                            onValueChange = vm::onSaveDialogNameChanged,
                            label = { Text("Profile name") },
                            singleLine = true,
                            isError = dialog.nameTaken,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Edit the name and tap OK to save, or Cancel to go back.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { vm.onSaveDialogOk { navController.popBackStack() } }) {
                    Text(if (dialog.isNameDialog) "OK" else "Go back")
                }
            },
            dismissButton = {
                if (dialog.isNameDialog) {
                    OutlinedButton(onClick = vm::onSaveDialogCancel) { Text("Cancel") }
                }
            },
        )
    }
}

/** Summary of warning ceiling + vertical offset, in the global display unit. */
private fun effectiveWatchCeilingText(s: ProfileEditorViewModel.UiState): String? {
    val ceiling = parseDecimal(s.ceilingValueText) ?: return null
    val offset = parseDecimal(s.watchOffsetVText) ?: return null
    val ceilingFt = if (s.altitudeUnit == AltitudeUnit.FT) ceiling else Units.metersToFeet(ceiling)
    val offsetFt = if (s.altitudeUnit == AltitudeUnit.FT) offset else Units.metersToFeet(offset)
    return DisplayFormats.formatAltitude(ceilingFt + offsetFt, s.altitudeUnit)
}

/** Distance with the global unit suffix; the slider always works in km. */
private fun displayDistance(km: Double, unit: DistanceUnit): String =
    DisplayFormats.formatDistance(km, unit)

/** "2" for whole minutes, "1.5" for halves — no trailing ".0". */
private fun formatMinutes(min: Double): String =
    if (min % 1.0 == 0.0) min.toInt().toString() else min.toString()

@Composable
private fun CeilingSection(
    label: String,
    valueText: String,
    onValue: (String) -> Unit,
    altitudeUnit: AltitudeUnit,
    ref: CeilingRef,
    onRef: (CeilingRef) -> Unit,
    terrainText: String,
    onTerrain: (String) -> Unit,
    onFetch: () -> Unit,
    elevationStatus: String?,
) {
    Text("Altitude ceiling", style = MaterialTheme.typography.titleSmall)
    Text(
        "Aircraft at or below this altitude trigger alerts for this layer.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = valueText,
        onValueChange = onValue,
        label = { Text("$label (${DisplayFormats.altitudeSuffix(altitudeUnit)})") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = ref == CeilingRef.ASL, onClick = { onRef(CeilingRef.ASL) }, label = { Text("ASL") })
        FilterChip(selected = ref == CeilingRef.AGL, onClick = { onRef(CeilingRef.AGL) }, label = { Text("AGL") })
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = terrainText,
            onValueChange = onTerrain,
            label = { Text("Terrain elevation (m)") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onFetch) { Text("Fetch from Open-Meteo") }
    }
    elevationStatus?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}