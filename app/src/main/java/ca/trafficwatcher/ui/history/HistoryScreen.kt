package ca.trafficwatcher.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import ca.trafficwatcher.TrafficApp
import ca.trafficwatcher.domain.History
import ca.trafficwatcher.domain.Tier
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as TrafficApp
    val vm: HistoryViewModel = viewModel(
        factory = viewModelFactory { initializer { HistoryViewModel(app, app.container) } },
    )
    val episodes by vm.episodes.collectAsState()
    val expandedKeys by vm.expandedKeys.collectAsState()
    val settings by vm.settings.collectAsState()
    val recording = settings.historyEnabled

    var showClearDialog by remember { mutableStateOf(false) }
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear history?") },
            text = { Text("All recorded events will be deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clear()
                    showClearDialog = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        floatingActionButton = {
            Row(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExtendedFloatingActionButton(
                    onClick = { vm.exportCsv(context) },
                    icon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                    text = { Text("Export CSV") },
                )
                Card(
                    onClick = { showClearDialog = true },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Clear")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
        ) {
            Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                if (recording) {
                    "Episodes of aircraft entering each monitored area. Tap an episode for its recorded path."
                } else {
                    "Recording is off. Enable it in Settings."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            if (episodes.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No recorded events yet.", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(episodes, key = { vm.episodeKey(it) }) { episode ->
                        val key = vm.episodeKey(episode)
                        EpisodeCard(
                            episode = episode,
                            expanded = key in expandedKeys,
                            toggle = { vm.toggle(key) },
                        )
                    }
                }
            }
        }
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault())
private val hmFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

@Composable
private fun EpisodeCard(
    episode: History.Episode,
    expanded: Boolean,
    toggle: () -> Unit,
) {
    val e = episode
    Card(modifier = Modifier.fillMaxWidth(), onClick = toggle) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        (e.callsign ?: e.hex.uppercase()),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(e.profileName, style = MaterialTheme.typography.bodySmall)
                }
                TierBadge(e.maxTier)
            }
            val enter = timeFmt.format(Instant.ofEpochMilli(e.enterMs))
            val exit = e.exitMs?.let { hmFmt.format(Instant.ofEpochMilli(it)) } ?: "still inside"
            val duration = e.exitMs?.let { formatDuration(it - e.enterMs) } ?: ""
            Text(
                "$enter — $exit${if (duration.isNotEmpty()) " ($duration)" else ""} · ${e.points.size} position(s)",
                style = MaterialTheme.typography.bodySmall,
            )
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Recorded path (chronological):",
                    style = MaterialTheme.typography.titleSmall,
                )
                PathPreview(e)
            }
        }
    }
}

@Composable
private fun TierBadge(tier: Tier) {
    val (bg, fg) = if (tier == Tier.WARNING) {
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = bg)) {
        Text(
            if (tier == Tier.WARNING) "WARNING" else "WATCH",
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun PathPreview(episode: History.Episode) {
    val points = episode.points.takeLast(20)
    Column {
        points.forEach { p ->
            val t = hmFmt.format(Instant.ofEpochMilli(p.tsMs))
            val pos = listOfNotNull(
                p.lat?.let { "%.4f".format(it) },
                p.lon?.let { "%.4f".format(it) },
            ).joinToString(", ")
            val alt = p.altMslFt?.let { "${Math.round(it)}ft" } ?: "alt ?"
            val dist = p.distanceKm?.let { "%.1f km".format(it) } ?: ""
            Text(
                "$t · $pos · $alt ${if (p.tier == Tier.WARNING) "· WARNING" else ""} $dist",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
        if (episode.points.size > points.size) {
            Text(
                "+ ${episode.points.size - points.size} earlier positions (see CSV export)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes < 60) "${minutes}min" else "${minutes / 60}h${"%02d".format(minutes % 60)}m"
}