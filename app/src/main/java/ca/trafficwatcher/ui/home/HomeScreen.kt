package ca.trafficwatcher.ui.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ca.trafficwatcher.TrafficApp
import ca.trafficwatcher.data.AltitudeUnit
import ca.trafficwatcher.data.DistanceUnit
import ca.trafficwatcher.domain.GeoMath
import ca.trafficwatcher.domain.GeoPoint
import ca.trafficwatcher.domain.GeofenceMode
import ca.trafficwatcher.domain.Tier
import ca.trafficwatcher.domain.WatchMode
import ca.trafficwatcher.domain.Units
import ca.trafficwatcher.service.ProfileRun
import ca.trafficwatcher.ui.map.AircraftPin
import ca.trafficwatcher.ui.map.FenceSpec
import ca.trafficwatcher.ui.map.TrafficMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as TrafficApp
    val vm: HomeViewModel = viewModel(
        factory = viewModelFactory { initializer { HomeViewModel(app, app.container) } },
    )

    val settings by vm.settings.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val running by vm.monitorState.running.collectAsState()
    val runs by vm.monitorState.runs.collectAsState()
    val gpsFixAgeMs by vm.monitorState.gpsFixAgeMs.collectAsState()
    val gpsUnavailable by vm.monitorState.gpsUnavailable.collectAsState()
    val phonePoint by vm.monitorState.phonePoint.collectAsState()

    var recenterTick by remember { mutableIntStateOf(0) }

    // ---- Permissions ------------------------------------------------------
    var pendingStartProfileId by remember { mutableStateOf<Long?>(null) }

    fun requiredPermissions(): List<String> {
        val list = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return list
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val allGranted = results.values.all { it }
        val id = pendingStartProfileId
        if (id != null) {
            if (allGranted) {
                vm.setProfileMonitoring(id, true)
            }
            pendingStartProfileId = null
        }
    }

    fun requestStart(profileId: Long) {
        pendingStartProfileId = profileId
        val missing = requiredPermissions()
        if (missing.isEmpty()) {
            vm.setProfileMonitoring(profileId, true)
            pendingStartProfileId = null
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ---- First-run disclaimer --------------------------------------------
    if (!settings.disclaimerAcknowledged) {
        DisclaimerDialog { vm.acknowledgeDisclaimer() }
        return
    }

    val runList = runs.values.sortedBy { it.profile.name }
    val anyFollowPhone = runList.any {
        it.profile.geofenceMode == GeofenceMode.FOLLOW_PHONE ||
            it.profile.watch?.mode == WatchMode.FOLLOW_PHONE
    }
    val totalTracked = runList.sumOf { it.tracked.size }

    // Recenter once when the first meaningful target appears (profiles load async).
    val mapTarget = firstMapTarget(runList)
    var lastAutoTarget by remember { mutableStateOf<GeoPoint?>(null) }
    LaunchedEffect(mapTarget) {
        if (mapTarget != null && lastAutoTarget == null) {
            recenterTick++
            lastAutoTarget = mapTarget
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Status banner
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when {
                            !running -> "Monitoring OFF"
                            runList.size == 1 -> "Monitoring ON — ${runList.first().profile.name}"
                            else -> "Monitoring ON — ${runList.size} profiles"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (running) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (running) {
                        Text(
                            text = "$totalTracked tracked",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { recenterTick++ }) {
                        Icon(Icons.Filled.GpsFixed, contentDescription = "Recenter map")
                    }
                    Icon(
                        if (android.os.Build.VERSION.SDK_INT < 33 ||
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                        ) Icons.Filled.Notifications else Icons.Filled.NotificationsOff,
                        contentDescription = "Notification permission",
                        tint = if (android.os.Build.VERSION.SDK_INT < 33 ||
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                        ) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                }
                val lastPoll = runList.mapNotNull { it.lastPollMs }.maxOrNull()
                val pollText = lastPoll?.let {
                    "Last poll: " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))
                } ?: "No poll yet"
                Text(pollText, style = MaterialTheme.typography.bodySmall)
                if (running && anyFollowPhone) {
                    when {
                        gpsUnavailable -> Text(
                            "GPS unavailable — grant location permission or enable location.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        gpsFixAgeMs != null && gpsFixAgeMs!! > 30_000 -> Text(
                            "Warning: GPS fix is ${(gpsFixAgeMs!! / 1000).toInt()} s old.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                runList.firstOrNull { it.lastError != null }?.let { run ->
                    Text(
                        "Poll error (${run.profile.name}): ${run.lastError}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Map
            Box(modifier = Modifier.weight(1f)) {
                val fences = runList.flatMap { run -> fencesFor(run, phonePoint) }
                val pins = mergePins(runList, settings)
                val trails = runList.fold(emptyMap<String, List<GeoPoint>>()) { acc, run ->
                    acc + run.trails
                }
                val gpsRef = runList.firstOrNull { it.profile.geofenceMode == GeofenceMode.FOLLOW_PHONE }?.referencePoint
                TrafficMap(
                    modifier = Modifier.fillMaxSize(),
                    tileTemplate = settings.tileServerTemplate,
                    fences = fences,
                    aircraft = pins,
                    trails = trails,
                    gpsPoint = if (anyFollowPhone) (phonePoint ?: gpsRef) else null,
                    recenterRequest = recenterTick,
                    recenterPoint = mapTarget,
                    initialCenter = mapTarget,
                )
            }

            // Profile checkboxes (tap to start/stop that profile) + Stop all
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    val monitored = runs.containsKey(profile.id)
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        tonalElevation = 2.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 4.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = monitored,
                                onCheckedChange = { checked ->
                                    if (checked) requestStart(profile.id) else vm.setProfileMonitoring(profile.id, false)
                                },
                            )
                            Text(
                                text = profile.name + if (monitored) {
                                    val n = runs[profile.id]?.tracked?.size ?: 0
                                    " ($n)"
                                } else {
                                    ""
                                },
                                maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            if (running) {
                ExtendedFloatingActionButton(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 6.dp),
                    onClick = { vm.stopAll() },
                    icon = { Icon(Icons.Filled.Stop, contentDescription = null) },
                    text = { Text("Stop all") },
                )
            }
        }
    }
}

private fun firstMapTarget(runs: List<ProfileRun>): GeoPoint? {
    val run = runs.firstOrNull() ?: return null
    return run.referencePoint
        ?: run.profile.centerLat?.let { lat -> run.profile.centerLon?.let { lon -> GeoPoint(lat, lon) } }
        ?: run.profile.polygon?.takeIf { it.isNotEmpty() }?.let { poly ->
            GeoPoint(poly.sumOf { it.lat } / poly.size, poly.sumOf { it.lon } / poly.size)
        }
}

private fun fencesFor(run: ProfileRun, phonePoint: GeoPoint?): List<FenceSpec> {
    val profile = run.profile
    val reference = run.referencePoint
        ?: profile.centerLat?.let { lat -> profile.centerLon?.let { lon -> GeoPoint(lat, lon) } }
        ?: profile.polygon?.takeIf { it.isNotEmpty() }?.let { poly ->
            GeoPoint(poly.sumOf { it.lat } / poly.size, poly.sumOf { it.lon } / poly.size)
        }

    // Mandatory warning zone (base geofence) — red.
    val warning: FenceSpec? = when (profile.geofenceMode) {
        GeofenceMode.POLYGON -> profile.polygon?.takeIf { it.size >= 3 }?.let { FenceSpec.Poly(it, Tier.WARNING) }
        GeofenceMode.FIXED_CIRCLE -> profile.centerLat?.let { lat ->
            profile.centerLon?.let { lon ->
                FenceSpec.Circle(GeoPoint(lat, lon), profile.radiusKm ?: 5.0, Tier.WARNING)
            }
        }
        GeofenceMode.FOLLOW_PHONE -> reference?.let {
            FenceSpec.Circle(it, profile.radiusKm ?: 5.0, Tier.WARNING)
        }
    }

    // Optional outer watch layer — blue.
    val watch: FenceSpec? = profile.watch?.let { w ->
        when (w.mode) {
            WatchMode.POLYGON -> w.polygon?.takeIf { it.size >= 3 }?.let { FenceSpec.Poly(it, Tier.WATCH) }
            WatchMode.FIXED_CIRCLE -> {
                val center = w.centerLat?.let { lat -> w.centerLon?.let { lon -> GeoPoint(lat, lon) } } ?: reference
                center?.let { FenceSpec.Circle(it, w.radiusKm, Tier.WATCH) }
            }
            WatchMode.FOLLOW_PHONE -> phonePoint?.let { FenceSpec.Circle(it, w.radiusKm, Tier.WATCH) }
            WatchMode.OFFSET -> reference?.let {
                val warningRadiusKm = when (profile.geofenceMode) {
                    GeofenceMode.POLYGON ->
                        profile.polygon?.takeIf { it.size >= 3 }?.let { GeoMath.polygonBoundingCircle(it).second }
                    else -> profile.radiusKm
                } ?: 0.0
                FenceSpec.Circle(it, warningRadiusKm + w.offsetHkm, Tier.WATCH)
            }
        }
    }
    return listOfNotNull(warning, watch)
}

private fun mergePins(
    runs: List<ProfileRun>,
    settings: ca.trafficwatcher.data.AppSettings,
): List<AircraftPin> {
    val byHex = LinkedHashMap<String, AircraftPin>()
    for (run in runs) {
        for (m in run.tracked) {
            val hex = m.aircraft.hex
            if (byHex.containsKey(hex)) continue
            byHex[hex] = AircraftPin(
                hex = hex,
                position = GeoPoint(m.aircraft.lat ?: 0.0, m.aircraft.lon ?: 0.0),
                trackDeg = m.aircraft.trackDeg,
                callsign = m.aircraft.callsign ?: m.aircraft.hex,
                label = pinLabel(m, settings),
            )
        }
    }
    return byHex.values.toList()
}

private fun pinLabel(m: ca.trafficwatcher.domain.MatchedAircraft, settings: ca.trafficwatcher.data.AppSettings): String {
    val parts = ArrayList<String>(3)
    if (m.tier == Tier.WARNING) parts.add("WARNING")
    if (m.altMslFt != null) {
        parts.add(
            when (settings.altitudeUnit) {
                AltitudeUnit.FT -> "${Math.round(m.altMslFt)}ft"
                AltitudeUnit.M -> "${Math.round(Units.feetToMeters(m.altMslFt))}m"
            },
        )
    }
    if (m.aircraft.groundSpeedKt != null) {
        val gsKt = m.aircraft.groundSpeedKt
        parts.add(
            when (settings.distanceUnit) {
                DistanceUnit.KM -> "${Math.round(Units.knotsToKmh(gsKt))}km/h"
                DistanceUnit.NM -> "${Math.round(gsKt)}kt"
            },
        )
    }
    m.aircraft.seenPosSec?.takeIf { it >= 30.0 }?.let {
        parts.add("age ${Math.round(it)}s")
    }
    return parts.joinToString(" · ")
}

@Composable
private fun DisclaimerDialog(onAcknowledge: () -> Unit) {
    var checked by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { /* must be acknowledged before use */ },
        title = { Text("Before you fly") },
        text = {
            Column {
                Text(
                    "Advisory only. Many low-altitude aircraft (GA, helicopters, ultralights, " +
                        "gliders) do not transmit ADS-B and CANNOT appear here. You must always " +
                        "maintain visual line of sight and give way to crewed aircraft " +
                        "(CARs Part IX).",
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "ADS-B data comes from free, crowdsourced community feeds " +
                        "and may be incomplete, delayed, or inaccurate. Never rely on this app " +
                        "for collision avoidance.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { checked = it })
                    Text("I understand")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAcknowledge, enabled = checked) {
                Text("Continue")
            }
        },
    )
}