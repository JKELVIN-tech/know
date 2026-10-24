package com.knowapp.android.ui.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knowapp.android.data.model.BillingStatusOut
import com.knowapp.android.data.repository.BillingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SubscriptionUiState(val loading: Boolean = true, val status: BillingStatusOut? = null, val error: String? = null)

// Preview only: payments are not switched on, so this screen just reports the trial
class SubscriptionViewModel(private val billing: BillingRepository) : ViewModel() {
    private val _state = MutableStateFlow(SubscriptionUiState())
    val state: StateFlow<SubscriptionUiState> = _state

    init { load() }

    fun load() {
        _state.value = SubscriptionUiState(loading = true)
        viewModelScope.launch {
            billing.status()
                .onSuccess { _state.value = SubscriptionUiState(loading = false, status = it) }
                .onFailure { _state.value = SubscriptionUiState(loading = false, error = it.message) }
        }
    }
}
