package ca.trafficwatcher.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.trafficwatcher.di.AppContainer
import ca.trafficwatcher.domain.Profile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfilesViewModel(private val container: AppContainer) : ViewModel() {

    val profiles: StateFlow<List<Profile>> =
        container.profileRepository.profiles
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun duplicate(id: Long) = viewModelScope.launch { container.profileRepository.duplicate(id) }

    fun delete(id: Long) = viewModelScope.launch { container.profileRepository.delete(id) }
}