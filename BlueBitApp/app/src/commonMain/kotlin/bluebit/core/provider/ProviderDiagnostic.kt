package bluebit.core.provider

import kotlinx.coroutines.flow.StateFlow

data class ProviderDiagnostic(
    val id: String,
    val label: String,
    val action: suspend (deviceAddress: String) -> Unit,
    /** Latest human-readable result produced by [action], shown under the diagnostic button. */
    val result: StateFlow<String?>? = null,
)
