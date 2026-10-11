package com.knowapp.android.ui.documents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.knowapp.android.data.DocumentCatalog
import com.knowapp.android.data.model.DocumentOut
import com.knowapp.android.data.model.resolveMediaUrl
import com.knowapp.android.ui.components.SkeletonRows
import com.knowapp.android.ui.components.kes
import com.knowapp.android.ui.components.relativeDay
import com.knowapp.android.ui.theme.DangerRed
import com.knowapp.android.ui.theme.DisplayFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(viewModel: DocumentsViewModel, onBack: () -> Unit, onNew: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var open by remember { mutableStateOf<DocumentOut?>(null) }
    var deleting by remember { mutableStateOf<DocumentOut?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Source documents", fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picking = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New document") },
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
            if (state.itemId != null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Documents for", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(state.itemName ?: "this item", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        TextButton(onClick = viewModel::clearItem) { Text("Show all") }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        label = { Text("Search number, supplier or customer") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = state.group == null, onClick = { viewModel.setGroup(null) }, label = { Text("All") })
                        DocumentCatalog.groups.forEach { g ->
                            FilterChip(selected = state.group == g.key, onClick = { viewModel.setGroup(g.key) }, label = { Text(g.name) })
                        }
                    }
                }
            }
            when {
                state.loading && state.documents.isEmpty() -> item { SkeletonRows() }
                state.error != null && state.documents.isEmpty() -> item {
                    Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error ?: "", color = DangerRed)
                        TextButton(onClick = viewModel::refresh) { Text("Try again") }
                    }
                }
                state.documents.isEmpty() -> item {
                    Column(
                        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(22.dp)).padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(34.dp))
                        Text("No documents yet", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                        Text("Tap New document and photograph an invoice, delivery note or count sheet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                else -> items(state.documents, key = { it.id }) { doc -> DocumentCard(doc) { open = doc } }
            }
        }
    }

    if (picking) {
        DocumentMenuSheet(
            onDismiss = { picking = false },
            onOpenAll = { picking = false },
            onPick = { key -> picking = false; onNew(key) },
        )
    }

    open?.let { doc ->
        val canDelete = viewModel.isAdmin || doc.recordedById == viewModel.userId
        Dialog(onDismissRequest = { open = null }) {
            Column(
                modifier = Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(doc.docTypeName, fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                resolveMediaUrl(doc.imagePath)?.let {
                    AsyncImage(model = it, contentDescription = "Attached document", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)))
                }
                listOfNotNull(
                    doc.referenceNo?.let { "Number: $it" },
                    doc.partyName?.let { "Party: $it" },
                    "Date: ${doc.documentDate}",
                    doc.stockItemName?.let { "Item: $it${doc.quantity?.let { q -> " × $q" } ?: ""}" },
                    doc.amount?.let { "Amount: ${kes(it)}" },
                    doc.notes?.let { "Notes: $it" },
                    doc.recordedByName?.let { "Recorded by $it" },
                ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (canDelete) TextButton(onClick = { deleting = doc; open = null }) { Text("Delete", color = DangerRed) }
                    TextButton(onClick = { open = null }) { Text("Close") }
                }
            }
        }
    }

    deleting?.let { doc ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this document?") },
            text = { Text("${doc.docTypeName}${doc.referenceNo?.let { " $it" } ?: ""} and its photo will be removed.") },
            confirmButton = { TextButton(onClick = { deleting = null; viewModel.delete(doc) }) { Text("Delete", color = DangerRed) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DocumentCard(doc: DocumentOut, onClick: () -> Unit) {
    val photo = resolveMediaUrl(doc.imagePath)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (photo != null) {
            AsyncImage(model = photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
        } else {
            Box(modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(doc.docTypeName, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                listOfNotNull(doc.referenceNo, doc.partyName).joinToString(" · ").ifBlank { "No number or party" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(relativeDay(doc.documentDate + "T00:00:00Z"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        doc.amount?.let { Text(kes(it), fontWeight = FontWeight.Bold, fontSize = 13.sp) }
    }
}
