package com.knowapp.android.ui.documents

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knowapp.android.data.DocumentCatalog
import com.knowapp.android.ui.components.PhotoPicker
import com.knowapp.android.ui.components.SimpleDropdown
import com.knowapp.android.ui.components.kes
import com.knowapp.android.ui.theme.DangerRed
import com.knowapp.android.ui.theme.DisplayFontFamily
import com.knowapp.android.ui.theme.PillShape
import com.knowapp.android.ui.theme.SmallRadius
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val NONE = "None"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewDocumentScreen(viewModel: NewDocumentViewModel, docType: String, preselectedItemId: Int?, onBack: () -> Unit, onSaved: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val type = DocumentCatalog.byKey(docType)

    var reference by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var picking by remember { mutableStateOf(false) }
    var party by remember { mutableStateOf("") }
    var itemName by remember { mutableStateOf(NONE) }
    var quantity by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var entryLabel by remember { mutableStateOf(NONE) }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var warning by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    // Opened from a stock item: that item is already chosen
    androidx.compose.runtime.LaunchedEffect(state.stock) {
        if (preselectedItemId != null && itemName == NONE) {
            state.stock.firstOrNull { it.id == preselectedItemId }?.let { itemName = it.name }
        }
    }

    val entryOptions = state.entries.associateBy { "${it.category} · ${kes(it.amount)} · ${it.transactionDate.take(10)}" }

    fun submit() {
        viewModel.save(
            docType = docType,
            reference = reference.trim(),
            date = date.toString(),
            party = party.trim(),
            stockItemId = state.stock.firstOrNull { it.name == itemName }?.id,
            quantity = quantity.trim(),
            amount = amount.trim(),
            notes = notes.trim(),
            transactionId = entryOptions[entryLabel]?.id,
            photo = photo,
            onDone = onSaved,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New document", fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(18.dp)).padding(16.dp)) {
                Text(type?.name ?: "Document", fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("From ${type?.issuedBy ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(top = 2.dp))
                Text(type?.purpose ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(top = 6.dp))
            }

            Text("WHICH STOCK ITEM IS THIS FOR?", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Optional, but linking the document to an item keeps your stock records traceable, so you can later see every document behind that item.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SimpleDropdown("Stock item", listOf(NONE) + state.stock.map { it.name }, itemName, { itemName = it })
            if (itemName != NONE) {
                OutlinedTextField(
                    value = quantity, onValueChange = { quantity = it }, label = { Text("Quantity on the document") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), shape = SmallRadius,
                )
            }
            Text("PHOTO OF THE DOCUMENT", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PhotoPicker(photo, { photo = it })

            OutlinedTextField(value = reference, onValueChange = { reference = it }, label = { Text("Document number") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = SmallRadius)
            OutlinedButton(onClick = { picking = true }, shape = SmallRadius, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Date: $date", modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedTextField(value = party, onValueChange = { party = it }, label = { Text(type?.partyLabel ?: "Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = SmallRadius)

            OutlinedTextField(
                value = amount, onValueChange = { amount = it }, label = { Text("Amount in KES (optional)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), shape = SmallRadius,
            )
            if (entryOptions.isNotEmpty()) {
                SimpleDropdown("Link to a recorded entry (optional)", listOf(NONE) + entryOptions.keys, entryLabel, { entryLabel = it })
            }
            OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth(), shape = SmallRadius)

            (formError ?: state.error)?.let {
                Text(it, color = DangerRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().background(DangerRed.copy(alpha = 0.12f), SmallRadius).padding(12.dp))
            }

            Button(
                onClick = {
                    formError = null
                    val bad = (amount.isNotBlank() && amount.toBigDecimalOrNull() == null) || (quantity.isNotBlank() && quantity.toBigDecimalOrNull() == null)
                    if (bad) formError = "Check the amount and quantity: numbers only."
                    // No photo and no number is a warning to confirm, not a refusal
                    else if (photo == null && reference.isBlank()) warning = true
                    else submit()
                },
                enabled = !state.saving,
                shape = PillShape,
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) { Text(if (state.saving) "Saving…" else "Save document", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        }
    }

    if (warning) {
        AlertDialog(
            onDismissRequest = { warning = false },
            title = { Text("Before you save") },
            text = { Text("This document has no photo and no document number, so it will be hard to prove later.") },
            confirmButton = { TextButton(onClick = { warning = false; submit() }) { Text("Save anyway") } },
            dismissButton = { TextButton(onClick = { warning = false }) { Text("Go back") } },
        )
    }

    if (picking) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState) }
    }
}
