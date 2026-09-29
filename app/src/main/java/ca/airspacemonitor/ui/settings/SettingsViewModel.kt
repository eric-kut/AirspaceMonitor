package ca.airspacemonitor.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.airspacemonitor.data.AltitudeUnit
import ca.airspacemonitor.data.AppSettings
import ca.airspacemonitor.data.DistanceUnit
import ca.airspacemonitor.data.network.AdsBClient
import ca.airspacemonitor.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val appContext: Context,
    private val container: AppContainer,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        container.settingsStore.settings
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** Latest test result per host index; cleared whenever the host list is edited. */
    private val _hostTests = MutableStateFlow<Map<Int, AdsBClient.HostTestResult>>(emptyMap())
    val hostTests: StateFlow<Map<Int, AdsBClient.HostTestResult>> = _hostTests.asStateFlow()

    private val _testingHosts = MutableStateFlow(false)
    val testingHosts: StateFlow<Boolean> = _testingHosts.asStateFlow()

    fun setDistanceUnit(u: DistanceUnit) = viewModelScope.launch { container.settingsStore.setDistanceUnit(u) }

    fun setAltitudeUnit(u: AltitudeUnit) = viewModelScope.launch { container.settingsStore.setAltitudeUnit(u) }

    fun setAllClear(enabled: Boolean) = viewModelScope.launch { container.settingsStore.setAllClearEnabled(enabled) }

    fun setHistoryEnabled(enabled: Boolean) = viewModelScope.launch { container.settingsStore.setHistoryEnabled(enabled) }

    fun setTileTemplate(template: String) = viewModelScope.launch { container.settingsStore.setTileServerTemplate(template) }

    fun setVoiceRefMode(mode: ca.airspacemonitor.data.VoiceRefMode) =
        viewModelScope.launch { container.settingsStore.setVoiceRefMode(mode) }

    fun moveHostUp(index: Int) {
        if (index <= 0) return
        val hosts = settings.value.hosts.toMutableList()
        val tmp = hosts[index - 1]
        hosts[index - 1] = hosts[index]
        hosts[index] = tmp
        _hostTests.value = emptyMap()
        viewModelScope.launch { container.settingsStore.setHosts(hosts) }
    }

    fun moveHostDown(index: Int) {
        val hosts = settings.value.hosts.toMutableList()
        if (index < 0 || index >= hosts.size - 1) return
        val tmp = hosts[index + 1]
        hosts[index + 1] = hosts[index]
        hosts[index] = tmp
        _hostTests.value = emptyMap()
        viewModelScope.launch { container.settingsStore.setHosts(hosts) }
    }

    fun addHost(host: String) {
        val trimmed = host.trim().removeSuffix("/")
        if (!trimmed.startsWith("http")) return
        val hosts = settings.value.hosts + trimmed
        _hostTests.value = emptyMap()
        viewModelScope.launch { container.settingsStore.setHosts(hosts) }
    }

    fun removeHost(index: Int) {
        val hosts = settings.value.hosts.toMutableList()
        if (index !in hosts.indices || hosts.size <= 1) return
        hosts.removeAt(index)
        _hostTests.value = emptyMap()
        viewModelScope.launch { container.settingsStore.setHosts(hosts) }
    }

    /**
     * Pings every configured host with a real point query (one request each,
     * ~1s apart). Queried near the first profile's center when one exists so
     * the aircraft count reflects the user's actual airspace.
     */
    fun testHosts() {
        if (_testingHosts.value) return
        _testingHosts.value = true
        viewModelScope.launch {
            try {
                val hosts = settings.value.hosts
                val center = container.profileRepository.profiles.first()
                    .firstOrNull { it.centerLat != null && it.centerLon != null }
                val lat = center?.centerLat ?: 51.4700  // fallback: busy airspace
                val lon = center?.centerLon ?: -0.4543
                hosts.forEachIndexed { index, host ->
                    val result = withContext(Dispatchers.IO) {
                        container.adsbClient.testHost(host, lat, lon, 250.0)
                    }
                    _hostTests.value = _hostTests.value + (index to result)
                }
            } finally {
                _testingHosts.value = false
            }
        }
    }

    fun requestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${appContext.packageName}")
        }
        runCatching { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); appContext.startActivity(intent) }
    }

    fun isIgnoringBatteryOptimizations(): Boolean =
        Build.VERSION.SDK_INT >= 23 &&
            (appContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
                ?.isIgnoringBatteryOptimizations(appContext.packageName) == true
}