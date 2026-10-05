package bluebit.core.ui.screens

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import bluebit.core.ui.components.DeviceCard
import bluebit.core.ui.components.PrimaryButton
import bluebit.core.ui.components.SecondaryButton
import bluebit.core.ui.components.SectionHeader

@Composable
fun DevicesScreen(
    devices: List<UiDevice>,
    isScanning: Boolean,
    onScan: () -> Unit,
    onUnfilteredScan: () -> Unit = {},
    onDeviceClick: (UiDevice) -> Unit,
    onConnect: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        SectionHeader(title = "Devices")

        PrimaryButton(
            text = if (isScanning) "Stop Scan" else "Scan for Devices",
            onClick = onScan,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        SecondaryButton(
            text = if (isScanning) "Stop Scan" else "Scan All BLE Devices",
            onClick = onUnfilteredScan,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(devices, key = { it.address }) { device ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DeviceCard(
                        device = device,
                        onClick = { onDeviceClick(device) },
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "Connect",
                        onClick = { onConnect(device.address) },
                    )
                }
            }
        }
    }
}
