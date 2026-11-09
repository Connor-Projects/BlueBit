package bluebit.core.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Context.CLIPBOARD_SERVICE
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import bluebit.core.diagnostics.DiagnosticEntry
import bluebit.core.diagnostics.DiagnosticLevel
import bluebit.core.diagnostics.DiagnosticLog
import bluebit.core.diagnostics.DiagnosticStatus
import bluebit.core.ui.components.PrimaryButton
import bluebit.core.ui.components.SecondaryButton
import bluebit.core.ui.components.SectionHeader
import kotlinx.coroutines.launch

import bluebit.core.ui.screens.DiagnosticAction

@Composable
fun DeveloperDiagnosticsScreen(
    diagnosticModeEnabled: Boolean,
    onDiagnosticModeToggle: (Boolean) -> Unit,
    actions: List<DiagnosticAction> = emptyList(),
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val entries by DiagnosticLog.entries.collectAsState(initial = emptyList())
    val categories = actions.groupBy { it.category }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Diagnostic Mode master switch
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Diagnostic Mode",
                        style = MaterialTheme.typography.titleMedium,
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

        if (!diagnosticModeEnabled) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Diagnostic Mode is OFF. Turn it on to expose developer diagnostic tools.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@DeveloperDiagnosticsScreen
        }

        // Category sections
        categories.entries.forEach { (category, categoryActions) ->
            SectionHeader(title = category)
            categoryActions.forEach { action ->
                SecondaryButton(
                    text = if (action.isAvailable) action.label else "${action.label}  (unavailable)",
                    onClick = {
                        if (action.isAvailable) {
                            scope.launch { action.onClick() }
                        } else {
                            DiagnosticLog.warning(
                                operation = action.id,
                                message = "Action not implemented",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = action.isAvailable,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Diagnostic output
        SectionHeader(title = "Diagnostic Output")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (entries.isEmpty()) {
                    Text(
                        text = "No diagnostic events yet. Run a diagnostic operation above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    entries.reversed().take(200).forEach { entry ->
                        val color = when (entry.level) {
                            DiagnosticLevel.ERROR -> MaterialTheme.colorScheme.error
                            DiagnosticLevel.WARNING -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Text(
                            text = entry.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = color,
                        )
                    }
                }
            }
        }

        // Output controls
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton(
                text = "Clear Log",
                onClick = { DiagnosticLog.clear() },
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryButton(
                text = "Copy Log",
                onClick = {
                    val text = DiagnosticLog.exportText()
                    val clipboard = context.getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("BlueBit Diagnostic Log", text))
                    DiagnosticLog.info("Copy Log", "Copied ${text.length} chars to clipboard")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryButton(
                text = "Export Log",
                onClick = {
                    DiagnosticLog.info("Export Log", "GATT reports are saved to the app files dir; use adb pull")
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        FloatingActionButton(
            onClick = { onBack() },
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
            )
        }
    }
}
