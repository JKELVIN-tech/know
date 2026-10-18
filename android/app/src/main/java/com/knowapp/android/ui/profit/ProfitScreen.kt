package com.knowapp.android.ui.profit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knowapp.android.data.model.CostItemOut
import com.knowapp.android.data.model.ProfitItemOut
import com.knowapp.android.data.model.ProfitTotals
import com.knowapp.android.ui.components.SimpleDropdown
import com.knowapp.android.ui.components.kes
import com.knowapp.android.ui.theme.DangerRed
import com.knowapp.android.ui.theme.DisplayFontFamily
import com.knowapp.android.ui.theme.IncomeGreen
import com.knowapp.android.ui.theme.WarnAmber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private fun toneOf(value: String?): Color {
    val n = value?.toBigDecimalOrNull() ?: return Color.Unspecified
    return when {
        n.signum() > 0 -> IncomeGreen
        n.signum() < 0 -> DangerRed
        else -> Color.Unspecified
    }
}

private fun pct(margin: String?) = if (margin == null) "\u2014" else "$margin%"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfitScreen(viewModel: ProfitViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var pickDate by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CostItemOut?>(null) }
    var costText by remember { mutableStateOf("") }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }

    if (pickDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = state.day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { viewModel.setDay(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = picker) }
    }

    editing?.let { item ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Cost of ${item.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = costText, onValueChange = { costText = it }, label = { Text("Cost of one unit") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Used for sales from now on. Past sales keep the cost they had.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.saveCost(item.itemId, costText) { ok -> if (ok) editing = null } }) { Text("Save cost") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Daily profit") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val data = state.data
        LazyColumn(modifier = Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = { viewModel.shift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day") }
                        OutlinedButton(onClick = { pickDate = true }) { Text(state.day.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))) }
                        IconButton(onClick = { viewModel.shift(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day") }
                    }
                    TextButton(onClick = viewModel::today) { Text("Today") }
                    SimpleDropdown(label = "Show", options = listOf(ALL_BRANCHES_LABEL) + state.branches.map { it.name }, selected = state.branchFilter, onSelected = viewModel::setBranch)
                }
            }
            if (data != null) {
                item { Summary(data.totals) }
                if ((data.totals.unknownUnits.toBigDecimalOrNull()?.signum() ?: 0) > 0) {
                    item { Text("${data.totals.unknownUnits} units were sold without a known cost, so their profit is not counted. Set their cost below.", style = MaterialTheme.typography.bodySmall, color = WarnAmber) }
                }
                if (data.branches.isEmpty()) item { Text("No stock sales on this day.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(data.branches, key = { it.branchId ?: -1 }) { branch ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(branch.branchName, style = MaterialTheme.typography.titleMedium, fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold)
                                Text(kes(branch.totals.profit), color = toneOf(branch.totals.profit), fontWeight = FontWeight.Bold)
                            }
                            Text("Sales ${kes(branch.totals.revenue)} \u00B7 cost ${kes(branch.totals.cost)} \u00B7 margin ${pct(branch.totals.margin)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            branch.items.forEach { ItemRow(it) }
                        }
                    }
                }
            } else if (state.loading) {
                item { Text("Loading\u2026", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item {
                val missing = state.costs.count { it.avgCost == null }
                Text(if (missing > 0) "Cost prices ($missing without a cost)" else "Cost prices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Restocks update the cost automatically. Set it by hand for stock you already had.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.costs, key = { it.itemId }) { c ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(c.name, fontWeight = FontWeight.SemiBold)
                        Text("${c.branchName ?: "Unassigned"} \u00B7 price ${kes(c.unitPrice)} \u00B7 ${c.quantity} in stock", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { costText = c.avgCost ?: ""; editing = c }) { Text(if (c.avgCost == null) "Set cost" else kes(c.avgCost), color = if (c.avgCost == null) WarnAmber else MaterialTheme.colorScheme.primary) }
                }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun Summary(t: ProfitTotals) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Profit", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(kes(t.profit), fontSize = 28.sp, fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, color = toneOf(t.profit))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Sales", kes(t.revenue))
                Stat("Cost", kes(t.cost))
                Stat("Margin", pct(t.margin))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ItemRow(i: ProfitItemOut) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text(i.name, fontWeight = FontWeight.SemiBold)
            val flag = if ((i.unknownUnits.toBigDecimalOrNull()?.signum() ?: 0) > 0) " \u00B7 ${i.unknownUnits} without cost" else ""
            Text("${i.units} sold \u00B7 sales ${kes(i.revenue)} \u00B7 cost ${kes(i.cost)}$flag", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
            Text(kes(i.profit), color = toneOf(i.profit), fontWeight = FontWeight.Bold)
            Text(pct(i.margin), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
