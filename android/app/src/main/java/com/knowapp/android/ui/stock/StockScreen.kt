package com.knowapp.android.ui.stock

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knowapp.android.data.model.StockItemOut
import com.knowapp.android.ui.components.kes
import com.knowapp.android.ui.components.SimpleDropdown
import com.knowapp.android.ui.theme.DangerRed
import com.knowapp.android.ui.theme.DisplayFontFamily
import com.knowapp.android.ui.theme.PillShape
import com.knowapp.android.ui.theme.SmallRadius
import com.knowapp.android.ui.theme.WarnAmber
import java.math.BigDecimal

private val LOW_STOCK = BigDecimal("5")

private fun qtyOf(item: StockItemOut) = item.quantity.toBigDecimalOrNull() ?: BigDecimal.ZERO

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockScreen(
    viewModel: StockViewModel,
    onBack: () -> Unit,
    showBack: Boolean = true,
    onOpenDocuments: () -> Unit = {},
    onNewDocument: (String) -> Unit = {},
    onOpenItemDocuments: (Int) -> Unit = {},
    onNewItemDocument: (Int, String) -> Unit = { _, _ -> },
) {
    var menuOpen by remember { mutableStateOf(false) }
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<StockItemOut?>(null) }
    var showForm by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    val shown = state.items.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || (it.categoryName ?: "").contains(query, ignoreCase = true) }
    val totalValue = state.items.fold(BigDecimal.ZERO) { a, i -> a + (i.unitPrice?.toBigDecimalOrNull() ?: BigDecimal.ZERO).multiply(qtyOf(i)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stock", fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.Menu, contentDescription = "Source documents") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = null; showForm = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add item") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("Items", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${state.items.size}", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Stock value", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(kes(totalValue), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        }
                    }
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, label = { Text("Search items") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), shape = SmallRadius,
                    )
                }
            }
            when {
                state.loading && state.items.isEmpty() -> item { com.knowapp.android.ui.components.SkeletonRows() }
                state.error != null && state.items.isEmpty() -> item {
                    Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error ?: "", color = DangerRed)
                        TextButton(onClick = viewModel::refresh) { Text("Try again") }
                    }
                }
                shown.isEmpty() -> item {
                    Column(
                        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(18.dp)).padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(34.dp))
                        Text(if (state.items.isEmpty()) "No stock yet" else "No matches", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                        if (state.items.isEmpty()) Text("Add an item to start recording sales and restocks against it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                else -> items(shown, key = { it.id }) { item -> StockCard(item, state.docCounts[item.id] ?: 0) { editing = item; showForm = true } }
            }
        }
    }

    if (menuOpen) {
        com.knowapp.android.ui.documents.DocumentMenuSheet(
            onDismiss = { menuOpen = false },
            onOpenAll = { menuOpen = false; onOpenDocuments() },
            onPick = { key -> menuOpen = false; onNewDocument(key) },
        )
    }

    if (showForm) {
        StockForm(
            editing = editing,
            state = state,
            onDismiss = { showForm = false },
            onSave = { name, category, newCategory, description, price, qty ->
                viewModel.save(editing, name, category, newCategory, description, price, qty) { ok -> if (ok) showForm = false }
            },
            onDelete = { item -> viewModel.delete(item) { ok -> if (ok) showForm = false } },
            docCount = editing?.let { state.docCounts[it.id] ?: 0 } ?: 0,
            onOpenDocuments = { item -> showForm = false; onOpenItemDocuments(item.id) },
            onAttach = { item, type -> showForm = false; onNewItemDocument(item.id, type) },
        )
    }
}

@Composable
private fun StockCard(item: StockItemOut, docCount: Int, onClick: () -> Unit) {
    val qty = qtyOf(item)
    val low = qty <= LOW_STOCK
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                (item.categoryName ?: "Uncategorised") + if (docCount > 0) "  ·  $docCount ${if (docCount == 1) "document" else "documents"}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(item.unitPrice?.let { kes(it) } ?: "No price", fontWeight = FontWeight.Bold)
            Text(
                if (low) "${qty.stripTrailingZeros().toPlainString()} left · low" else "${qty.stripTrailingZeros().toPlainString()} in stock",
                style = MaterialTheme.typography.bodySmall,
                color = if (qty <= BigDecimal.ZERO) DangerRed else if (low) WarnAmber else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StockForm(
    editing: StockItemOut?,
    state: StockUiState,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String) -> Unit,
    onDelete: (StockItemOut) -> Unit,
    docCount: Int,
    onOpenDocuments: (StockItemOut) -> Unit,
    onAttach: (StockItemOut, String) -> Unit,
) {
    var attaching by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val categoryNames = state.categories.map { it.name } + NEW_CATEGORY
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var category by remember { mutableStateOf(editing?.categoryName ?: state.categories.firstOrNull()?.name ?: NEW_CATEGORY) }
    var newCategory by remember { mutableStateOf("") }
    var description by remember { mutableStateOf(editing?.description ?: "") }
    var price by remember { mutableStateOf(editing?.unitPrice?.toBigDecimalOrNull()?.toPlainString() ?: "") }
    var quantity by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (editing == null) "Add stock item" else "Edit item", style = MaterialTheme.typography.titleLarge, fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = SmallRadius)
            SimpleDropdown("Category", categoryNames, category, { category = it })
            if (category == NEW_CATEGORY) {
                OutlinedTextField(value = newCategory, onValueChange = { newCategory = it }, label = { Text("New category name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = SmallRadius)
            }
            OutlinedTextField(
                value = price, onValueChange = { price = it }, label = { Text("Unit price (KES)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), shape = SmallRadius,
            )
            if (editing == null) {
                OutlinedTextField(
                    value = quantity, onValueChange = { quantity = it }, label = { Text("Starting quantity") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), shape = SmallRadius,
                )
            } else {
                Text("Quantity changes through Receiving and Paying, so every change has a record behind it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth(), shape = SmallRadius)

            Button(
                onClick = { onSave(name, category, newCategory, description, price, quantity) },
                enabled = !state.saving,
                shape = PillShape,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.saving) "Saving…" else "Save", fontWeight = FontWeight.SemiBold) }
            if (editing != null) {
                Column(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Source documents", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        if (docCount == 0) "No documents linked to this item yet." else "$docCount ${if (docCount == 1) "document is" else "documents are"} linked to this item.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { attaching = true }, shape = PillShape, modifier = Modifier.weight(1f)) { Text("Attach one") }
                        if (docCount > 0) OutlinedButton(onClick = { onOpenDocuments(editing) }, shape = PillShape, modifier = Modifier.weight(1f)) { Text("View all") }
                    }
                }
            }
            if (editing != null) {
                OutlinedButton(onClick = { confirmDelete = true }, shape = PillShape, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete item", color = DangerRed, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    if (attaching && editing != null) {
        com.knowapp.android.ui.documents.DocumentMenuSheet(
            onDismiss = { attaching = false },
            onOpenAll = { attaching = false; onOpenDocuments(editing) },
            onPick = { key -> attaching = false; onAttach(editing, key) },
        )
    }

    if (confirmDelete && editing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${editing.name}?") },
            text = { Text("Items with recorded sales or restocks can't be deleted, because their history must stay intact.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(editing) }) { Text("Delete", color = DangerRed) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
