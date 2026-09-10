package ca.airspacemonitor.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.airspacemonitor.di.AppContainer
import ca.airspacemonitor.data.AppSettings
import ca.airspacemonitor.domain.Profile
import ca.airspacemonitor.service.MonitoringService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val appContext: Context,
    private val container: AppContainer,
) : ViewModel() {

    val monitorState = container.monitorState

    val profiles: StateFlow<List<Profile>> =
        container.profileRepository.profiles
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> =
        container.settingsStore.settings
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setProfileMonitoring(profileId: Long, enable: Boolean) {
        if (enable) {
            MonitoringService.start(appContext, profileId)
        } else {
            MonitoringService.stopProfile(appContext, profileId)
        }
    }

    fun stopAll() = MonitoringService.stop(appContext)

    fun acknowledgeDisclaimer() = viewModelScope.launch {
        container.settingsStore.setDisclaimerAcknowledged(true)
    }
}