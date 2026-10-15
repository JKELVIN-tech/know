package com.knowapp.android.ui.audits

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knowapp.android.data.SessionStore
import com.knowapp.android.data.model.AuditOut
import com.knowapp.android.data.model.TransactionOut
import com.knowapp.android.data.repository.AuditActionResult
import com.knowapp.android.data.repository.AuditRepository
import com.knowapp.android.data.repository.AuditTransactionsResult
import com.knowapp.android.data.repository.AuditsResult
import kotlinx.coroutines.launch

data class AuditsUiState(
    val isLoading: Boolean = true,
    val audits: List<AuditOut> = emptyList(),
    val errorMessage: String? = null,
    val isSubmitting: Boolean = false,
    val submitError: String? = null,
    val canCreate: Boolean = false,
)

class AuditsViewModel(private val repository: AuditRepository, private val sessionStore: SessionStore) : ViewModel() {
    // Only staff submit audits (server-enforced — see backend/app/routers/audits.py's
    // create_audit), and institution_admin/super_admin must pass institution_id.
    var uiState by mutableStateOf(AuditsUiState(canCreate = sessionStore.session.value?.role == "staff"))
        private set

    init {
        refresh()
    }

    fun refresh() {
        uiState = uiState.copy(isLoading = true, errorMessage = null)
        val session = sessionStore.session.value
        val institutionId = if (session?.role == "staff") null else session?.institutionId
        viewModelScope.launch {
            when (val result = repository.list(institutionId)) {
                is AuditsResult.Success -> uiState = uiState.copy(isLoading = false, audits = result.audits)
                is AuditsResult.Failure -> uiState = uiState.copy(isLoading = false, errorMessage = result.message)
            }
        }
    }

    fun createAudit(title: String, periodStartIso: String, periodEndIso: String, summary: String?, onDone: (Boolean) -> Unit) {
        uiState = uiState.copy(isSubmitting = true, submitError = null)
        viewModelScope.launch {
            when (val result = repository.create(title, periodStartIso, periodEndIso, summary)) {
                is AuditActionResult.Success -> {
                    uiState = uiState.copy(isSubmitting = false)
                    refresh()
                    onDone(true)
                }
                is AuditActionResult.Failure -> {
                    uiState = uiState.copy(isSubmitting = false, submitError = result.message)
                    onDone(false)
                }
            }
        }
    }

    fun delete(auditId: Int) {
        viewModelScope.launch {
            repository.deleteDraft(auditId)
            refresh()
        }
    }

    fun finalize(auditId: Int) {
        viewModelScope.launch {
            repository.finalize(auditId)
            refresh()
        }
    }
}

data class AuditDetailUiState(
    val isLoading: Boolean = true,
    val transactions: List<TransactionOut> = emptyList(),
    val errorMessage: String? = null,
)

class AuditDetailViewModel(private val repository: AuditRepository, private val auditId: Int) : ViewModel() {
    var uiState by mutableStateOf(AuditDetailUiState())
        private set

    init {
        viewModelScope.launch {
            when (val result = repository.transactionsFor(auditId)) {
                is AuditTransactionsResult.Success -> uiState = uiState.copy(isLoading = false, transactions = result.transactions)
                is AuditTransactionsResult.Failure -> uiState = uiState.copy(isLoading = false, errorMessage = result.message)
            }
        }
    }
}
