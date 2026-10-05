package bluebit.core.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import bluebit.core.ui.screens.Screen

@Composable
fun BlueBitBottomNavigation(selected: Screen, onSelect: (Screen) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == Screen.Home,
            onClick = { onSelect(Screen.Home) },
            icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
            label = { Text("Home") },
        )
        NavigationBarItem(
            selected = selected == Screen.Devices,
            onClick = { onSelect(Screen.Devices) },
            icon = { Icon(Icons.Default.Devices, contentDescription = "Devices") },
            label = { Text("Devices") },
        )
        NavigationBarItem(
            selected = selected == Screen.Activity,
            onClick = { onSelect(Screen.Activity) },
            icon = { Icon(Icons.Default.DirectionsRun, contentDescription = "Activity") },
            label = { Text("Activity") },
        )
        NavigationBarItem(
            selected = selected == Screen.Settings,
            onClick = { onSelect(Screen.Settings) },
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings") },
        )
    }
}
