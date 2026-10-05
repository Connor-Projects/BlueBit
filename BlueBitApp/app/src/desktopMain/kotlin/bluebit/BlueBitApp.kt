package bluebit

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import bluebit.core.bluetooth.NoOpBleConnection
import bluebit.core.bluetooth.NoOpBleScanner
import bluebit.core.devices.InMemoryDeviceProfileRepository
import bluebit.core.provider.ProviderContext
import bluebit.core.provider.ProviderRegistry
import bluebit.core.ui.theme.BlueBitTheme
import bluebit.fitbit.FitbitProvider
import bluebit.fitcloud.FitCloudProvider

fun main() = application {
    val scanner = NoOpBleScanner()
    val profileRepository = InMemoryDeviceProfileRepository(emptyList())
    val connectionFactory: (bluebit.core.bluetooth.BleDevice) -> bluebit.core.bluetooth.BleConnection = {
        NoOpBleConnection()
    }
    val fitbitProvider = FitbitProvider(
        profileRepository = profileRepository,
        connectionFactory = connectionFactory,
        diagnosticsFactory = { emptyList() },
    )
    val fitcloudProvider = FitCloudProvider(connectionFactory)
    val providerRegistry = ProviderRegistry(listOf(fitbitProvider, fitcloudProvider))
    val providerContext = ProviderContext(providerRegistry, scanner)

    Window(
        onCloseRequest = ::exitApplication,
        title = "BlueBit",
        state = rememberWindowState(width = 420.dp, height = 760.dp),
    ) {
        BlueBitTheme {
            BlueBitAppShell(
                providerContext = providerContext,
                watchdogDumpAction = {},
            )
        }
    }
}
