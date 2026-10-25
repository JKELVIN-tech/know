package com.knowapp.android.ui.transactions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knowapp.android.data.model.TransactionOut
import com.knowapp.android.data.repository.TransactionRepository
import com.knowapp.android.data.repository.RecordResult
import com.knowapp.android.data.repository.TransactionsResult
import kotlinx.coroutines.launch

data class TransactionsUiState(
    val isLoading: Boolean = true,
    val transactions: List<TransactionOut> = emptyList(),
    val errorMessage: String? = null,
)

class TransactionsViewModel(private val repository: TransactionRepository) : ViewModel() {
    var uiState by mutableStateOf(TransactionsUiState())
        private set

    init {
        refresh()
    }

    fun refresh() {
        uiState = uiState.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            when (val result = repository.list()) {
                is TransactionsResult.Success -> {
                    uiState = uiState.copy(isLoading = false, transactions = result.transactions)
                }
                is TransactionsResult.Failure -> {
                    uiState = uiState.copy(isLoading = false, errorMessage = result.message)
                }
            }
        }
    }

    fun update(id: Int, fields: Map<String, String>, onFinished: (String?) -> Unit) {
        viewModelScope.launch {
            when (val result = repository.update(id, fields)) {
                is RecordResult.Rejected -> onFinished(result.message)
                else -> {
                    onFinished(null)
                    refresh()
                }
            }
        }
    }
}
