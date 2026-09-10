package ca.trafficwatcher.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ca.trafficwatcher.TrafficApp
import ca.trafficwatcher.data.AppSettings
import ca.trafficwatcher.data.db.EventEntity
import ca.trafficwatcher.di.AppContainer
import ca.trafficwatcher.domain.GeoMath
import ca.trafficwatcher.domain.GeoPoint
import ca.trafficwatcher.domain.Pipeline
import ca.trafficwatcher.domain.AircraftTracker
import ca.trafficwatcher.domain.Units
import ca.trafficwatcher.domain.MatchedAircraft
import ca.trafficwatcher.domain.Profile
import ca.trafficwatcher.domain.GeofenceMode
import ca.trafficwatcher.domain.History
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that polls the ADS-B feed, runs the filter pipeline and
 * raises local notifications. Uses a coroutine poll loop with a partial wake
 * lock (WorkManager's 15-minute minimum is unusable for this use case).
 *
 * Several profiles can be monitored at once: each gets its own poll loop,
 * tracker and trails; a single shared status notification aggregates them.
 */
class MonitoringService : Service() {

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val ACTION_START = "ca.trafficwatcher.action.START"
        const val ACTION_STOP = "ca.trafficwatcher.action.STOP"
        const val ACTION_STOP_PROFILE = "ca.trafficwatcher.action.STOP_PROFILE"

        fun start(context: Context, profileId: Long) {
            val intent = Intent(context, MonitoringService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROFILE_ID, profileId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopProfile(context: Context, profileId: Long) {
            context.startService(
                Intent(context, MonitoringService::class.java)
                    .setAction(ACTION_STOP_PROFILE)
                    .putExtra(EXTRA_PROFILE_ID, profileId),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MonitoringService::class.java).setAction(ACTION_STOP),
            )
        }

        /** Android 14+ requires the runtime location permission for a location-type FGS. */
        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        /** Recorded history is pruned to this window on every monitoring start. */
        const val HISTORY_RETENTION_DAYS = 7

        /** Upper bound on the ADS-B query circle (km) so a far-away watch layer can't explode the request. */
        const val MAX_QUERY_RADIUS_KM = 120.0
    }

    private class Runtime(
        val profile: Profile,
        val tracker: AircraftTracker = AircraftTracker(),
        val trails: HashMap<String, ArrayDeque<Pair<Long, GeoPoint>>> = HashMap(),
    ) {
        var job: Job? = null
    }

    private lateinit var container: AppContainer
    private lateinit var notifier: Notifier
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var settingsJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isForeground = false

    @Volatile private var currentSettings: AppSettings = AppSettings()
    @Volatile private var latestFix: Location? = null
    private var gpsListener: LocationListener? = null

    private val runtimes = ConcurrentHashMap<Long, Runtime>()

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        container = (application as TrafficApp).container
        notifier = Notifier(this)

        when (intent?.action) {
            ACTION_STOP, Notifier.ACTION_STOP_MONITORING -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
            ACTION_STOP_PROFILE -> {
                val id = intent.getLongExtra(EXTRA_PROFILE_ID, -1L)
                if (id >= 0) stopOneProfile(id)
                return START_STICKY
            }
            else -> {
                val profileId = intent?.getLongExtra(EXTRA_PROFILE_ID, -1L) ?: -1L
                if (profileId < 0) {
                    stopMonitoring()
                    return START_NOT_STICKY
                }
                startMonitoringFor(profileId)
            }
        }
        return START_STICKY
    }

    private fun startMonitoringFor(profileId: Long) {
        scope.launch {
            val profile = container.profileRepository.get(profileId) ?: return@launch

            // Re-adding a running profile restarts its loop (e.g. after an edit).
            runtimes.remove(profileId)?.job?.cancel()

            notifier.ensureBaseChannels()
            if (!isForeground && !enterForeground(profile)) {
                stopSelf()
                return@launch
            }
            if (wakeLock == null) acquireWakeLock()
            if (settingsJob == null) collectSettings()

            container.eventRepository.pruneOlderThanDays(HISTORY_RETENTION_DAYS)

            val runtime = Runtime(profile)
            runtimes[profile.id] = runtime

            val state = container.monitorState
            state.setRunning(true)
            state.addRun(profile)

            updateGpsListener()
            runtime.job = scope.launch { pollLoop(runtime) }
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrafficWatcher:monitor").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun enterForeground(profile: Profile): Boolean {
        val notification = notifier.statusNotification(profile.name, null, 0)
        return try {
            if (hasLocationPermission(this)) {
                ServiceCompat.startForeground(
                    this,
                    Notifier.NOTIF_ID_STATUS,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            } else {
                // Type checks are relaxed for the plain overload; if the platform
                // still refuses (no location permission at all), degrade gracefully.
                startForeground(Notifier.NOTIF_ID_STATUS, notification)
            }
            isForeground = true
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun updateGpsListener() {
        val needsGps = runtimes.values.any {
            it.profile.geofenceMode == GeofenceMode.FOLLOW_PHONE ||
                it.profile.watch?.mode == ca.trafficwatcher.domain.WatchMode.FOLLOW_PHONE
        }
        if (needsGps) {
            if (gpsListener == null) startLocationUpdatesIfAllowed()
        } else {
            stopLocationUpdates()
        }
    }

    private fun collectSettings() {
        settingsJob = scope.launch {
            container.settingsStore.settings.collect { currentSettings = it }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdatesIfAllowed() {
        container.monitorState.setGpsUnavailable(false)
        if (!hasLocationPermission(this)) {
            container.monitorState.setGpsUnavailable(true)
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = LocationListener { location ->
            latestFix = location
            container.monitorState.setPhonePoint(GeoPoint(location.latitude, location.longitude))
        }
        gpsListener = listener
        val providers = lm.getProviders(true).ifEmpty { listOf(LocationManager.GPS_PROVIDER) }
        var registered = false
        for (provider in providers) {
            try {
                lm.requestLocationUpdates(provider, 3_000L, 0f, listener, mainLooper)
                registered = true
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        // Prime with a recent cached fix if one exists.
        for (provider in providers) {
            val last = runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
            if (last != null) {
                if (latestFix == null || last.time > latestFix!!.time) latestFix = last
            }
        }
        if (!registered) container.monitorState.setGpsUnavailable(true)
    }

    private fun stopLocationUpdates() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        gpsListener?.let { runCatching { lm.removeUpdates(it) } }
        gpsListener = null
    }

    private suspend fun pollLoop(runtime: Runtime) {
        val profile = runtime.profile
        val state = container.monitorState
        var consecutiveFailures = 0

        while (coroutineContext.isActive) {
            val nowMs = System.currentTimeMillis()
            val intervalMs = profile.pollIntervalSec * 1_000L

            // Reference point: GPS fix (FOLLOW_PHONE), profile center or polygon centroid.
            val polygonCircle = profile.polygon?.let { GeoMath.polygonBoundingCircle(it) }
            val (reference, _) = when (profile.geofenceMode) {
                GeofenceMode.FOLLOW_PHONE -> {
                    val fix = latestFix
                    state.setGpsFixAge(fix?.let { nowMs - it.time })
                    (fix?.let { GeoPoint(it.latitude, it.longitude) }) to (profile.radiusKm ?: 0.0)
                }
                GeofenceMode.FIXED_CIRCLE ->
                    (profile.centerLat?.let { lat -> profile.centerLon?.let { lon -> GeoPoint(lat, lon) } }) to
                        (profile.radiusKm ?: 0.0)
                GeofenceMode.POLYGON ->
                    polygonCircle?.first to ((polygonCircle?.second ?: 0.0) * 1.10)
            }

            // The query must cover the watch layer too, or aircraft in the outer
            // ring are never fetched and can never alert.
            val phoneFix = latestFix?.let { fix -> GeoPoint(fix.latitude, fix.longitude) }
            val queryRadiusKm = Pipeline.queryRadiusKm(profile, reference, phoneFix)
                .coerceAtMost(MAX_QUERY_RADIUS_KM)

            var aircraft: List<ca.trafficwatcher.domain.Aircraft> = emptyList()
            if (reference != null) {
                val queryRadiusNm = Units.kmToNm(queryRadiusKm)
                val outcome = container.adsbClient.queryPoint(reference.lat, reference.lon, queryRadiusNm)
                if (outcome.error != null) {
                    consecutiveFailures++
                    state.updateRun(profile.id) { it.copy(lastError = outcome.error) }
                } else {
                    consecutiveFailures = 0
                    state.updateRun(profile.id) { it.copy(lastError = null) }
                    aircraft = outcome.aircraft
                }

                // Test-mode injection keeps working with data off.
                container.testInjector.aircraftFor(reference, nowMs)?.let { aircraft = aircraft + it }
            } else {
                state.updateRun(profile.id) { it.copy(lastError = "No GPS fix") }
            }

            val pollTs = System.currentTimeMillis()
            state.updateRun(profile.id) { it.copy(referencePoint = reference, lastPollMs = pollTs) }

            val filterReference = reference
                ?: profile.centerLat?.let { lat -> profile.centerLon?.let { lon -> GeoPoint(lat, lon) } }
                ?: polygonCircle?.first
            val phoneReference = profile.watch
                ?.takeIf { it.mode == ca.trafficwatcher.domain.WatchMode.FOLLOW_PHONE }
                ?.let { latestFix?.let { fix -> GeoPoint(fix.latitude, fix.longitude) } }
            if (filterReference != null) {
                val result = Pipeline.filter(aircraft, profile, filterReference, phoneReference)
                val snoozed = container.snoozeStore.isSnoozed(nowMs)
                val cycle = runtime.tracker.process(
                    sightings = result.matched,
                    nowMs = System.currentTimeMillis(),
                    cooldownMs = profile.alertCooldownMin * 60_000L,
                    alertsSuppressed = snoozed,
                )

                val settings = currentSettings
                cycle.alerts.forEach { alert ->
                    notifier.postAlert(alert.aircraft, profile, settings.distanceUnit, settings.altitudeUnit)
                }
                cycle.gone.forEach { notifier.cancelAlert(profile.id, it) }
                if (settings.allClearEnabled && cycle.departures.isNotEmpty()) {
                    notifier.postCleared(profile, cycle.departures)
                }

                recordHistory(profile, result.matched, cycle)

                updateTrails(runtime, result.matched, System.currentTimeMillis())
                state.updateRun(profile.id) {
                    it.copy(
                        tracked = result.matched,
                        trails = runtime.trails.mapValues { (_, points) -> points.map { p -> p.second } },
                    )
                }

                postAggregateStatus(pollTs)
            }

            // Backoff: widen delay on consecutive failures, capped at 120 s.
            val delayMs = if (consecutiveFailures > 0) {
                val factor = 1L shl minOf(consecutiveFailures, 7)
                minOf(intervalMs * factor, 120_000L)
            } else {
                intervalMs
            }
            delay(delayMs)
        }
    }

    private fun postAggregateStatus(pollTs: Long) {
        val names = runtimes.values.joinToString(", ") { it.profile.name }
        val totalTracked = container.monitorState.runs.value.values.sumOf { it.tracked.size }
        notifier.postStatus(names, pollTs, totalTracked)
    }

    private fun recordHistory(profile: Profile, matched: List<MatchedAircraft>, cycle: AircraftTracker.CycleResult) {
        if (!currentSettings.historyEnabled) return
        val events = ArrayList<EventEntity>(matched.size + cycle.gone.size)
        val nowMs = System.currentTimeMillis()
        for (alert in cycle.alerts) {
            if (!alert.isNew) continue
            val m = alert.aircraft
            events.add(
                EventEntity(
                    tsMs = nowMs,
                    profileId = profile.id,
                    profileName = profile.name,
                    hex = m.aircraft.hex,
                    callsign = m.aircraft.callsign,
                    eventType = History.EVENT_ENTER,
                    tier = m.tier.name,
                    lat = m.aircraft.lat,
                    lon = m.aircraft.lon,
                    altMslFt = m.altMslFt,
                    aglFt = m.aglFt,
                    distanceKm = m.distanceKm,
                ),
            )
        }
        for (m in matched) {
            events.add(
                EventEntity(
                    tsMs = nowMs,
                    profileId = profile.id,
                    profileName = profile.name,
                    hex = m.aircraft.hex,
                    callsign = m.aircraft.callsign,
                    eventType = History.EVENT_POSITION,
                    tier = m.tier.name,
                    lat = m.aircraft.lat,
                    lon = m.aircraft.lon,
                    altMslFt = m.altMslFt,
                    aglFt = m.aglFt,
                    distanceKm = m.distanceKm,
                ),
            )
        }
        for (hex in cycle.gone) {
            events.add(
                EventEntity(
                    tsMs = nowMs,
                    profileId = profile.id,
                    profileName = profile.name,
                    hex = hex,
                    callsign = null,
                    eventType = History.EVENT_EXIT,
                    tier = null,
                    lat = null,
                    lon = null,
                    altMslFt = null,
                    aglFt = null,
                    distanceKm = null,
                ),
            )
        }
        if (events.isNotEmpty()) {
            scope.launch { container.eventRepository.record(events) }
        }
    }

    private fun updateTrails(runtime: Runtime, matched: List<MatchedAircraft>, nowMs: Long) {
        val window = 60_000L
        val seen = matched.map { it.aircraft.hex }.toHashSet()
        for (m in matched) {
            val dq = runtime.trails.getOrPut(m.aircraft.hex) { ArrayDeque() }
            dq.addLast(nowMs to GeoPoint(m.aircraft.lat ?: continue, m.aircraft.lon ?: continue))
            while (dq.isNotEmpty() && nowMs - dq.first().first > window) dq.removeFirst()
        }
        runtime.trails.keys.retainAll(seen)
    }

    private fun stopOneProfile(profileId: Long) {
        val runtime = runtimes.remove(profileId) ?: return
        runtime.job?.cancel()
        runtime.tracker.trackedHexes().forEach { notifier.cancelAlert(profileId, it) }
        container.monitorState.removeRun(profileId)
        updateGpsListener()
        if (runtimes.isEmpty()) stopMonitoring()
    }

    private fun stopMonitoring() {
        runtimes.values.forEach { runtime ->
            runtime.job?.cancel()
            runtime.tracker.trackedHexes()
                .forEach { notifier.cancelAlert(runtime.profile.id, it) }
        }
        runtimes.clear()
        stopLocationUpdates()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        container.monitorState.reset()
        container.testInjector.clear()
        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        runtimes.values.forEach { it.job?.cancel() }
        runtimes.clear()
        stopLocationUpdates()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (this::container.isInitialized) container.monitorState.reset()
        scope.cancel()
        super.onDestroy()
    }
}