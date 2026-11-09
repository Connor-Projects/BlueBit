package bluebit.core.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A single diagnostic log entry produced by a developer diagnostic operation.
 */
data class DiagnosticEntry(
    val timestampMs: Long,
    val level: DiagnosticLevel,
    val operation: String,
    val status: DiagnosticStatus,
    val message: String,
    val deviceAddress: String? = null,
) {
    val timestamp: String
        get() = java.text.SimpleDateFormat("HH:mm:ss.SS", java.util.Locale.US)
            .format(java.util.Date(timestampMs))

    override fun toString(): String =
        "[$timestamp] [$level] $operation -> $status${if (message.isNotBlank()) ": $message" else ""}"
        .plus(deviceAddress?.let { " (device=$it)" }.orEmpty())
}

enum class DiagnosticLevel { INFO, DEBUG, WARNING, ERROR }

enum class DiagnosticStatus { OK, FAILED, PENDING, UNSUPPORTED }

/**
 * In-memory diagnostic log for developer troubleshooting.
 *
 * Entries are kept in memory for the life of the process. Clearing the visible
 * log here removes all entries; historical HCI captures and research files under
 * Reports/ are never touched by this class.
 */
object DiagnosticLog {

    private val _entries = MutableStateFlow<MutableList<DiagnosticEntry>>(mutableListOf())
    val entries: StateFlow<List<DiagnosticEntry>> = _entries.asStateFlow()

    fun add(
        level: DiagnosticLevel,
        operation: String,
        status: DiagnosticStatus,
        message: String = "",
        deviceAddress: String? = null,
    ) {
        val entry = DiagnosticEntry(
            timestampMs = System.currentTimeMillis(),
            level = level,
            operation = operation,
            status = status,
            message = message,
            deviceAddress = deviceAddress,
        )
        _entries.value = _entries.value.toMutableList().apply { add(entry) }
    }

    fun info(operation: String, message: String = "", deviceAddress: String? = null) =
        add(DiagnosticLevel.INFO, operation, DiagnosticStatus.OK, message, deviceAddress)

    fun error(operation: String, message: String = "", deviceAddress: String? = null) =
        add(DiagnosticLevel.ERROR, operation, DiagnosticStatus.FAILED, message, deviceAddress)

    fun warning(operation: String, message: String = "", deviceAddress: String? = null) =
        add(DiagnosticLevel.WARNING, operation, DiagnosticStatus.OK, message, deviceAddress)

    fun pending(operation: String, message: String = "", deviceAddress: String? = null) =
        add(DiagnosticLevel.DEBUG, operation, DiagnosticStatus.PENDING, message, deviceAddress)

    fun unsupported(operation: String, message: String = "", deviceAddress: String? = null) =
        add(DiagnosticLevel.INFO, operation, DiagnosticStatus.UNSUPPORTED, message, deviceAddress)

    fun clear() {
        _entries.value = mutableListOf()
    }

    fun exportText(): String {
        val sb = StringBuilder()
        sb.appendLine("BlueBit Developer Diagnostics Log")
        sb.appendLine("Exported: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        sb.appendLine("Total entries: ${_entries.value.size}")
        sb.appendLine("")
        _entries.value.forEach { sb.appendLine(it.toString()) }
        return sb.toString()
    }
}
