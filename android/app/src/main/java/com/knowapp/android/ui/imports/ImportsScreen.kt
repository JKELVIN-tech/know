package com.knowapp.android.ui.imports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.knowapp.android.data.model.ApproveBody
import com.knowapp.android.data.local.DetectedMessage
import com.knowapp.android.ui.components.kes

private fun reasonText(reason: String?): String? = when (reason) {
    "bank_bound" -> "Goes to a bank: only the M-Pesa side is recorded here"
    "reversal_needs_review" -> "Reversal: enter it manually"
    "missing_fields" -> "Missing details: enter it manually"
    "already_recorded" -> "Already recorded"
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportsScreen(viewModel: ImportsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var text by remember { mutableStateOf("") }
    var openId by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }

    val open = state.items.filter { it.status == "detected" || it.status == "needs_review" }
    val done = state.items.filter { it !in open }.take(30)

    state.confirmForce?.let { (id, body) ->
        AlertDialog(
            onDismissRequest = viewModel::dismissForce,
            title = { Text("Already recorded") },
            text = { Text("This M-Pesa code is already in the books. Save anyway?") },
            confirmButton = { TextButton(onClick = { viewModel.approve(id, body.copy(force = true)) }) { Text("Save anyway") } },
            dismissButton = { TextButton(onClick = viewModel::dismissForce) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("M-Pesa messages") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nothing is recorded until you approve it.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Paste M-Pesa messages") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    Button(enabled = !state.busy, onClick = { viewModel.scan(text); text = "" }) { Text("Detect") }
                }
            }
            item { Text("To review (${open.size})", style = MaterialTheme.typography.titleMedium) }
            if (open.isEmpty()) item { Text("Nothing waiting.", style = MaterialTheme.typography.bodySmall) }
            items(open, key = { it.id }) { it ->
                ImportCard(it, expanded = openId == it.id, busy = state.busy, stock = state.stock.map { s -> s.id to s.name },
                    onReview = { openId = if (openId == it.id) null else it.id },
                    onIgnore = { viewModel.ignore(it.id) },
                    onSave = { body -> viewModel.approve(it.id, body); openId = null })
            }
            if (done.isNotEmpty()) item { Text("Already handled", style = MaterialTheme.typography.titleMedium) }
            items(done, key = { -it.id }) { it -> ImportCard(it, expanded = false, busy = true, stock = emptyList(), onReview = {}, onIgnore = {}, onSave = {}, handled = true) }
        }
    }
}

@Composable
private fun ImportCard(
    item: DetectedMessage,
    expanded: Boolean,
    busy: Boolean,
    stock: List<Pair<Int, String>>,
    onReview: () -> Unit,
    onIgnore: () -> Unit,
    onSave: (ApproveBody) -> Unit,
    handled: Boolean = false,
) {
    var asStock by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    var itemId by remember { mutableStateOf<Int?>(null) }
    var note by remember { mutableStateOf("") }
    val sign = when (item.direction) { "in" -> "+"; "out" -> "\u2212"; else -> "" }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${item.kind.replaceFirstChar { it.uppercase() }} \u00B7 ${item.counterparty ?: "\u2014"}", style = MaterialTheme.typography.titleSmall)
                Text(if (item.amount == null) "\u2014" else sign + kes(item.amount), style = MaterialTheme.typography.titleSmall)
            }
            Text(item.code + if (handled) " \u00B7 ${item.status}" else "", style = MaterialTheme.typography.bodySmall)
            reasonText(item.reason)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (!handled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onReview) { Text(if (expanded) "Close" else "Review") }
                    TextButton(onClick = onIgnore) { Text("Ignore") }
                }
            }
            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !asStock, onClick = { asStock = false }, label = { Text("Other") })
                    if (stock.isNotEmpty()) FilterChip(selected = asStock, onClick = { asStock = true }, label = { Text("Stock item") })
                }
                if (asStock) {
                    stock.forEach { (id, name) -> FilterChip(selected = itemId == id, onClick = { itemId = id }, label = { Text(name) }) }
                    OutlinedTextField(value = qty, onValueChange = { qty = it }, label = { Text("Quantity") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy, onClick = {
                    onSave(if (asStock) ApproveBody("STOCK", stockItemId = itemId, quantity = qty, description = note) else ApproveBody("OTHER", category = category, description = note))
                }) { Text("Save to books") }
            }
        }
    }
}
