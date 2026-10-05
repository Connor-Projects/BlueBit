package bluebit.core.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import bluebit.core.ui.components.BatteryCard
import bluebit.core.ui.components.ConnectionIndicator
import bluebit.core.ui.components.PrimaryButton
import bluebit.core.ui.components.SecondaryButton
import bluebit.core.ui.components.SectionHeader
import bluebit.core.ui.components.StatisticCard

@Composable
fun HomeScreen(
    deviceName: String,
    connected: Boolean,
    battery: Int,
    lastSync: String,
    steps: String,
    distance: String,
    activeMinutes: String,
    onSync: () -> Unit,
    onConnect: () -> Unit,
    onDeviceSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionHeader(title = "Home")

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = deviceName, style = MaterialTheme.typography.headlineSmall)
                ConnectionIndicator(connected = connected)
                Text(
                    text = "Last sync: $lastSync",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PrimaryButton(
                        text = if (connected) "Disconnect" else "Connect",
                        onClick = onConnect,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "Sync",
                        onClick = onSync,
                        modifier = Modifier.weight(1f),
                    )
                }
                SecondaryButton(
                    text = "Device Settings",
                    onClick = onDeviceSettings,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BatteryCard(
                battery = battery,
                modifier = Modifier.weight(1f),
            )
            StatisticCard(
                label = "Steps",
                value = steps,
                modifier = Modifier.weight(1f),
            )
        }

        StatisticCard(
            label = "Distance",
            value = distance,
            modifier = Modifier.fillMaxWidth(),
        )
        StatisticCard(
            label = "Active Minutes",
            value = activeMinutes,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
