package bluebit.core.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import bluebit.core.provider.ProviderDiagnostic
import bluebit.core.ui.components.BatteryCard
import kotlinx.coroutines.launch
import bluebit.core.ui.components.ConnectionIndicator
import bluebit.core.ui.components.PrimaryButton
import bluebit.core.ui.components.SecondaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    deviceName: String,
    deviceAddress: String,
    connected: Boolean,
    bondState: String,
    signalStrength: String,
    lastConnected: String,
    battery: Int,
    isCharging: Boolean,
    model: String,
    deviceType: String,
    firmwareVersion: String,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onToggleConnection: () -> Unit,
    onSettings: () -> Unit,
    diagnostics: List<ProviderDiagnostic> = emptyList(),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(deviceName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConnectionIndicator(connected = connected)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BatteryCard(battery = battery, modifier = Modifier.weight(1f))
                Card(modifier = Modifier.weight(1f)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(text = signalStrength, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Signal",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            DetailItem(label = "Address", value = deviceAddress)
            DetailItem(label = "Bond State", value = bondState)
            DetailItem(label = "Model", value = model)
            DetailItem(label = "Device Type", value = deviceType)
            DetailItem(label = "Firmware", value = firmwareVersion)
            DetailItem(label = "Last Connected", value = lastConnected)
            if (isCharging) {
                DetailItem(label = "Charging", value = "Yes")
            }

            Spacer(modifier = Modifier.height(8.dp))

            PrimaryButton(
                text = if (connected) "Disconnect" else "Connect",
                onClick = onToggleConnection,
                modifier = Modifier.fillMaxWidth(),
            )
            val scope = rememberCoroutineScope()
            diagnostics.forEach { diagnostic ->
                SecondaryButton(
                    text = diagnostic.label,
                    onClick = {
                        scope.launch {
                            diagnostic.action(deviceAddress)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                diagnostic.result?.let { resultFlow ->
                    val result by resultFlow.collectAsState()
                    result?.let { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            SecondaryButton(
                text = "Sync",
                onClick = onSync,
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryButton(
                text = "Settings",
                onClick = onSettings,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DetailItem(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
