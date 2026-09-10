package ca.trafficwatcher.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.trafficwatcher.data.AltitudeUnit
import ca.trafficwatcher.data.AppSettings
import ca.trafficwatcher.data.DistanceUnit
import ca.trafficwatcher.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val appContext: Context,
    private val container: AppContainer,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        container.settingsStore.settings
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setDistanceUnit(u: DistanceUnit) = viewModelScope.launch { container.settingsStore.setDistanceUnit(u) }

    fun setAltitudeUnit(u: AltitudeUnit) = viewModelScope.launch { container.settingsStore.setAltitudeUnit(u) }

    fun setAllClear(enabled: Boolean) = viewModelScope.launch { container.settingsStore.setAllClearEnabled(enabled) }

    fun setHistoryEnabled(enabled: Boolean) = viewModelScope.launch { container.settingsStore.setHistoryEnabled(enabled) }

    fun setTileTemplate(template: String) = viewModelScope.launch { container.settingsStore.setTileServerTemplate(template) }

    fun moveHostUp(index: Int) {
        if (index <= 0) return
        val hosts = settings.value.hosts.toMutableList()
        val tmp = hosts[index - 1]
        hosts[index - 1] = hosts[index]
        hosts[index] = tmp
        viewModelScope.launch { container.settingsStore.setHosts(hosts) }
    }

    fun moveHostDown(index: Int) {
        val hosts = settings.value.hosts.toMutableList()
        if (index < 0 || index >= hosts.size - 1) return
        val tmp = hosts[index + 1]
        hosts[index + 1] = hosts[index]
        hosts[index] = tmp
        viewModelScope.launch { container.settingsStore.setHosts(hosts) }
    }

    fun addHost(host: String) {
        val trimmed = host.trim().removeSuffix("/")
        if (!trimmed.startsWith("http")) return
        val hosts = settings.value.hosts + trimmed
        viewModelScope.launch { container.settingsStore.setHosts(hosts) }
    }

    fun removeHost(index: Int) {
        val hosts = settings.value.hosts.toMutableList()
        if (index !in hosts.indices || hosts.size <= 1) return
        hosts.removeAt(index)
        viewModelScope.launch { container.settingsStore.setHosts(hosts) }
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