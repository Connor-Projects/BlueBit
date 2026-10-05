package bluebit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import bluebit.core.connection.ConnectionState
import bluebit.core.bluetooth.BleConnection
import androidx.compose.ui.Modifier
import bluebit.core.bluetooth.BleDevice
import bluebit.core.provider.DeviceCapabilities
import bluebit.core.provider.ProviderContext
import bluebit.core.provider.ProviderDiagnostic
import bluebit.core.pairing.PairedDeviceCoordinator
import bluebit.core.ui.components.BlueBitBottomNavigation
import bluebit.core.ui.screens.ActivityScreen
import bluebit.core.ui.screens.DeviceDetailScreen
import bluebit.core.ui.screens.DevicesScreen
import bluebit.core.ui.screens.HomeScreen
import bluebit.core.ui.screens.Screen
import bluebit.core.ui.screens.SettingsScreen
import bluebit.core.ui.screens.UiDevice
import bluebit.core.ui.theme.BlueBitTheme

@Composable
fun BlueBitAppShell(
    providerContext: ProviderContext,
    bondState: String = "UNKNOWN",
    onConnect: (String) -> Unit = {},
    watchdogDumpAction: suspend (String) -> Unit = {},
    pairedDeviceCoordinator: PairedDeviceCoordinator? = null,
) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
    var debugLogging by remember { mutableStateOf(false) }

    val discoveredDevices = remember { mutableStateListOf<UiDevice>() }
    val scanner = providerContext.scanner
    val isScanning by scanner.scanningState().collectAsState(initial = false)

    LaunchedEffect(scanner) {
        scanner.scanResults().collect { result ->
            val provider = providerContext.registry.resolve(result)
            val profile = provider?.identify(result)
            val uiDevice = UiDevice(
                name = result.name ?: "Unknown (${result.address})",
                address = result.address,
                status = "RSSI ${result.rssi} dBm",
                battery = null,
                deviceType = profile?.name ?: provider?.name ?: "Unknown",
                lastSeen = "Just now",
                rssi = result.rssi,
                recognised = provider != null,
                manufacturerDataSummary = result.manufacturerData.entries.joinToString { "0x${it.key.toString(16).uppercase()}=${it.value.size}b" },
                providerId = provider?.id ?: "",
                capabilities = provider?.capabilities(BleDevice(result.address, result.name)) ?: DeviceCapabilities(),
            )
            val existingIndex = discoveredDevices.indexOfFirst { it.address == result.address }
            if (existingIndex >= 0) {
                discoveredDevices[existingIndex] = uiDevice
            } else {
                discoveredDevices.add(uiDevice)
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (currentScreen !is Screen.DeviceDetail) {
                BlueBitBottomNavigation(
                    selected = when (currentScreen) {
                        is Screen.Home -> Screen.Home
                        is Screen.Devices -> Screen.Devices
                        is Screen.Activity -> Screen.Activity
                        else -> Screen.Settings
                    },
                    onSelect = { currentScreen = it },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (val screen = currentScreen) {
                is Screen.Home -> HomeScreen(
                    deviceName = "",
                    connected = false,
                    battery = 0,
                    lastSync = "",
                    steps = "",
                    distance = "",
                    activeMinutes = "",
                    onSync = {},
                    onConnect = {},
                    onDeviceSettings = {},
                )

                is Screen.Devices -> DevicesScreen(
                    devices = discoveredDevices.toList(),
                    isScanning = isScanning,
                    onScan = {
                        if (isScanning) {
                            scanner.stopScan()
                        } else {
                            discoveredDevices.clear()
                            scanner.startScan()
                        }
                    },
                    onUnfilteredScan = {
                        if (isScanning) {
                            scanner.stopScan()
                        } else {
                            discoveredDevices.clear()
                            scanner.startUnfilteredScan()
                        }
                    },
                    onDeviceClick = { currentScreen = Screen.DeviceDetail(it.name, it.address) },
                    onConnect = onConnect,
                )

                is Screen.Activity -> ActivityScreen(
                    steps = "",
                    distance = "",
                    activeMinutes = "",
                    calories = "",
                )

                is Screen.Settings -> SettingsScreen(
                    debugLoggingEnabled = debugLogging,
                    onDebugLoggingToggle = { debugLogging = it },
                    onBleDiagnostics = {},
                    onDiscoveryDiagnostics = {},
                )

                is Screen.DeviceDetail -> {
                    val selectedDevice = discoveredDevices.find { it.address == screen.deviceAddress }
                    val provider = remember(selectedDevice) {
                        selectedDevice?.providerId?.let { providerContext.registry.findById(it) }
                    }
                    val connectionState by provider?.connectionState?.collectAsState() ?: remember { mutableStateOf(ConnectionState.Disconnected) }
                    val isConnected = connectionState is ConnectionState.Connected
                    val diagnostics = selectedDevice?.capabilities?.diagnostics ?: emptyList()
                    val allDiagnostics = remember(selectedDevice) {
                        val list = listOf(
                            ProviderDiagnostic(
                                id = "dump_ble_log",
                                label = "Dump BLE Log to File",
                                action = watchdogDumpAction,
                            )
                        ) + diagnostics
                        println("BlueBitDiagnostics: count=${list.size}, ids=${list.map { it.id }}, providerId=${selectedDevice?.providerId}, recognised=${selectedDevice?.recognised}")
                        list
                    }
                    val scope = rememberCoroutineScope()
                    var activeConnection by remember { mutableStateOf<BleConnection?>(null) }

                    DeviceDetailScreen(
                        deviceName = screen.deviceName,
                        deviceAddress = screen.deviceAddress,
                        connected = isConnected,
                        bondState = bondState,
                        signalStrength = "",
                        lastConnected = "",
                        battery = 0,
                        isCharging = false,
                        model = "",
                        deviceType = selectedDevice?.deviceType ?: "",
                        firmwareVersion = "",
                        onBack = { currentScreen = Screen.Devices },
                        onSync = {},
                        onToggleConnection = {
                            val p = provider ?: return@DeviceDetailScreen
                            val device = BleDevice(screen.deviceAddress, screen.deviceName)
                            scope.launch {
                                if (isConnected) {
                                    pairedDeviceCoordinator?.userDisconnect(p.id, screen.deviceAddress)
                                    activeConnection?.let { p.disconnect(it) }
                                    activeConnection = null
                                } else {
                                    pairedDeviceCoordinator?.userConnect(p.id, screen.deviceAddress)
                                    val conn = p.createConnection(device)
                                    activeConnection = conn
                                    p.connect(device, conn)
                                }
                            }
                        },
                        onSettings = {},
                        diagnostics = allDiagnostics,
                    )
                }
            }
        }
    }
}
