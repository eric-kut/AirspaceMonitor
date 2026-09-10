package ca.airspacemonitor.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ca.airspacemonitor.ui.history.HistoryScreen
import ca.airspacemonitor.ui.profiles.ProfileEditorScreen
import ca.airspacemonitor.ui.profiles.ProfilesScreen
import ca.airspacemonitor.ui.settings.SettingsScreen
import ca.airspacemonitor.ui.testmode.TestModeScreen
import ca.airspacemonitor.ui.theme.AirspaceMonitorTheme
import ca.airspacemonitor.ui.home.HomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AirspaceMonitorTheme { AppScaffold() }
        }
    }
}

private data class TopDestination(val route: String, val label: String, val icon: @Composable () -> Unit)

@Composable
fun AppScaffold() {
    val navController = rememberNavController()
    val destinations = listOf(
        TopDestination("home", "Map") { Icon(Icons.Filled.Map, contentDescription = null) },
        TopDestination("profiles", "Profiles") { Icon(Icons.Filled.List, contentDescription = null) },
        TopDestination("history", "History") { Icon(Icons.Filled.History, contentDescription = null) },
        TopDestination("testmode", "Test") { Icon(Icons.Filled.Tune, contentDescription = null) },
        TopDestination("settings", "Settings") { Icon(Icons.Filled.Settings, contentDescription = null) },
    )
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = dest.icon,
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding),
        ) {
            composable("home") { HomeScreen() }
            composable("profiles") { ProfilesScreen(navController) }
            composable("editor/new") { ProfileEditorScreen(navController, profileId = null) }
            composable("editor/{profileId}") { entry ->
                val id = entry.arguments?.getString("profileId")?.toLongOrNull()
                ProfileEditorScreen(navController, profileId = id)
            }
            composable("history") { HistoryScreen() }
            composable("testmode") { TestModeScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}