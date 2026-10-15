package com.knowapp.android.ui.audits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.knowapp.android.data.model.AuditOut
import com.knowapp.android.ui.components.AppCard
import com.knowapp.android.ui.components.Badge
import com.knowapp.android.ui.theme.PillShape
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import androidx.compose.material3.AlertDialog

private val displayDateFormat = DateTimeFormatter.ofPattern("d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditsScreen(
    viewModel: AuditsViewModel,
    currentUserId: Int?,
    onBack: () -> Unit,
    onOpenAudit: (Int) -> Unit,
) {
    val state = viewModel.uiState
    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingFinalize by remember { mutableStateOf<Int?>(null) }
    var pendingDelete by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audits") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            if (state.canCreate) {
                FloatingActionButton(onClick = { showCreateDialog = true }, shape = PillShape) {
                    Icon(Icons.Filled.Add, contentDescription = "New audit")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.errorMessage != null -> Text(
                    text = state.errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                state.audits.isEmpty() -> Text(
                    "No audits submitted yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.audits, key = { it.id }) { audit ->
                        AuditRow(
                            audit,
                            canFinalize = audit.submittedById == currentUserId,
                            onOpen = { onOpenAudit(audit.id) },
                            onFinalize = { pendingFinalize = audit.id },
                            onDelete = { pendingDelete = audit.id },
                        )
                    }
                }
            }
        }
    }

    pendingFinalize?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingFinalize = null },
            title = { Text("Finalize this audit?") },
            text = { Text("Finalizing locks every entry in this period so it can no longer be edited or deleted.") },
            confirmButton = { TextButton(onClick = { viewModel.finalize(id); pendingFinalize = null }) { Text("Finalize") } },
            dismissButton = { TextButton(onClick = { pendingFinalize = null }) { Text("Cancel") } },
        )
    }
    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this draft audit?") },
            text = { Text("No entries are touched.") },
            confirmButton = { TextButton(onClick = { viewModel.delete(id); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }

    if (showCreateDialog) {
        CreateAuditDialog(
            isSubmitting = state.isSubmitting,
            errorMessage = state.submitError,
            onDismiss = { showCreateDialog = false },
            onSubmit = { title, start, end, summary ->
                viewModel.createAudit(title, start, end, summary) { success ->
                    if (success) showCreateDialog = false
                }
            },
        )
    }
}

@Composable
private fun AuditRow(audit: AuditOut, canFinalize: Boolean, onOpen: () -> Unit, onFinalize: () -> Unit, onDelete: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(audit.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Badge(
            text = if (audit.status == "finalized") "Finalized" else "Draft",
            modifier = Modifier.padding(top = 6.dp),
        )
        audit.summary?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpen, shape = PillShape, modifier = Modifier.fillMaxWidth()) {
                Text("View transactions")
            }
            if (audit.status != "finalized" && canFinalize) {
                Button(
                    onClick = onFinalize,
                    shape = PillShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Finalize")
                }
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete draft") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateAuditDialog(
    isSubmitting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (title: String, startIso: String, endIso: String, summary: String?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    var showRangePicker by remember { mutableStateOf(false) }
    val rangeState = rememberDateRangePickerState()

    val startMillis = rangeState.selectedStartDateMillis
    val endMillis = rangeState.selectedEndDateMillis

    Dialog(onDismissRequest = onDismiss) {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text("New audit", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )
            OutlinedButton(
                onClick = { showRangePicker = true },
                shape = PillShape,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) {
                Text(periodLabel(startMillis, endMillis))
            }
            OutlinedTextField(
                value = summary,
                onValueChange = { summary = it },
                label = { Text("Summary (optional)") },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            if (errorMessage != null) {
                Text(
                    errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Button(
                onClick = {
                    val startIso = millisToIso(startMillis)
                    val endIso = millisToIso(endMillis)
                    if (title.isNotBlank() && startIso != null && endIso != null) {
                        onSubmit(title.trim(), startIso, endIso, summary.trim().ifBlank { null })
                    }
                },
                enabled = !isSubmitting && title.isNotBlank() && startMillis != null && endMillis != null,
                shape = PillShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Text(if (isSubmitting) "Submitting…" else "Submit audit")
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("Cancel")
            }
        }
    }

    if (showRangePicker) {
        DatePickerDialog(
            onDismissRequest = { showRangePicker = false },
            confirmButton = { TextButton(onClick = { showRangePicker = false }) { Text("Done") } },
            dismissButton = { TextButton(onClick = { showRangePicker = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state = rangeState, modifier = Modifier.padding(8.dp))
        }
    }
}

private fun periodLabel(startMillis: Long?, endMillis: Long?): String {
    if (startMillis == null) return "Pick audit period"
    val start = Instant.ofEpochMilli(startMillis).atZone(ZoneOffset.UTC).format(displayDateFormat)
    if (endMillis == null) return "$start – pick end date"
    val end = Instant.ofEpochMilli(endMillis).atZone(ZoneOffset.UTC).format(displayDateFormat)
    return "$start – $end"
}

private fun millisToIso(millis: Long?): String? {
    if (millis == null) return null
    return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay().toString()
}
