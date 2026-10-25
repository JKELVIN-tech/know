package com.knowapp.android.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.knowapp.android.data.model.TransactionOut
import com.knowapp.android.ui.components.AmountText
import com.knowapp.android.ui.components.AppCard
import com.knowapp.android.ui.components.Badge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    viewModel: TransactionsViewModel,
    onBack: () -> Unit,
) {
    val state = viewModel.uiState
    var detail by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<TransactionOut?>(null) }
    var editing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<TransactionOut?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("All entries") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
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
                state.transactions.isEmpty() -> Text(
                    "No transactions yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.transactions.sortedByDescending { it.transactionDate }, key = { it.id }) { txn ->
                        com.knowapp.android.ui.home.TransactionRow(txn) { detail = txn }
                    }
                }
            }
        }
    }

    detail?.let { t -> com.knowapp.android.ui.home.DetailSheet(t, onEdit = { detail = null; editing = t }) { detail = null } }
    editing?.let { t -> com.knowapp.android.ui.home.EditSheet(t, onDismiss = { editing = null }, onSave = { fields, done -> viewModel.update(t.id, fields) { error -> if (error == null) editing = null; done(error) } }) }
}

@Composable
private fun TransactionRow(txn: TransactionOut) {
    // Matches .txn-row: category badge-style tag, description, amount
    // colored by --in (income) / --out (expense).
    AppCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Badge(text = txn.category)
        txn.description?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        AmountText(
            amountKes = txn.amount,
            isIncome = txn.type == "income",
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
