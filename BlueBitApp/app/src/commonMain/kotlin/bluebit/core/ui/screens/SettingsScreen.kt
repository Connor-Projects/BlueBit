package bluebit.core.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import bluebit.core.ui.components.PrimaryButton
import bluebit.core.ui.components.SectionHeader

@Composable
fun SettingsScreen(
    debugLoggingEnabled: Boolean,
    onDebugLoggingToggle: (Boolean) -> Unit,
    onBleDiagnostics: () -> Unit,
    onDiscoveryDiagnostics: () -> Unit,
    diagnosticModeEnabled: Boolean = false,
    onDiagnosticModeToggle: (Boolean) -> Unit = {},
    onOpenDeveloperDiagnostics: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionHeader(title = "Settings")

        Card(modifier = Modifier.fillMaxWidth()) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Debug Logging",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(
                    checked = debugLoggingEnabled,
                    onCheckedChange = onDebugLoggingToggle,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Diagnostic Mode",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Enables advanced diagnostic tools for development and troubleshooting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = diagnosticModeEnabled,
                    onCheckedChange = onDiagnosticModeToggle,
                )
            }
        }

        PrimaryButton(
            text = "Developer Diagnostics",
            onClick = onOpenDeveloperDiagnostics,
            modifier = Modifier.fillMaxWidth(),
            enabled = diagnosticModeEnabled,
        )

        PrimaryButton(
            text = "BLE Diagnostics",
            onClick = onBleDiagnostics,
            modifier = Modifier.fillMaxWidth(),
        )

        PrimaryButton(
            text = "Discovery Diagnostics",
            onClick = onDiscoveryDiagnostics,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
