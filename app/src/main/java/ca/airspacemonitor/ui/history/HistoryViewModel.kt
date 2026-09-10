package ca.airspacemonitor.ui.history

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.airspacemonitor.data.AppSettings
import ca.airspacemonitor.di.AppContainer
import ca.airspacemonitor.domain.History
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class HistoryViewModel(
    private val appContext: Context,
    private val container: AppContainer,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        container.settingsStore.settings
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val episodes: StateFlow<List<History.Episode>> =
        container.eventRepository.lite
            .map { History.episodes(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _expandedKeys = MutableStateFlow<Set<String>>(emptySet())
    val expandedKeys: StateFlow<Set<String>> = _expandedKeys.asStateFlow()

    fun episodeKey(e: History.Episode) = "${e.profileId}:${e.hex}:${e.enterMs}"

    fun toggle(key: String) {
        _expandedKeys.value = if (key in _expandedKeys.value) {
            _expandedKeys.value - key
        } else {
            _expandedKeys.value + key
        }
    }

    fun clear() = viewModelScope.launch {
        container.eventRepository.clear()
    }

    /** Builds the CSV off the main thread and shares it via a FileProvider URI. */
    fun exportCsv(context: Context) {
        viewModelScope.launch {
            val csv = History.csv(container.eventRepository.all())
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "airspacemonitor_history.csv")
            file.writeText(csv)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "AirspaceMonitor history")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(share, "Export AirspaceMonitor history")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(chooser) }
        }
    }
}