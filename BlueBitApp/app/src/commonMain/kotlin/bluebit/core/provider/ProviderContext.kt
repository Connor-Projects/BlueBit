package bluebit.core.provider

import bluebit.core.bluetooth.BleScanner

class ProviderContext(
    val registry: ProviderRegistry,
    val scanner: BleScanner,
)
