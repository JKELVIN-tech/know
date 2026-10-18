package com.knowapp.android.ui.profit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knowapp.android.data.DataVersion
import com.knowapp.android.data.model.BranchOut
import com.knowapp.android.data.model.CostItemOut
import com.knowapp.android.data.model.ProfitDayOut
import com.knowapp.android.data.repository.ExchangeRepository
import com.knowapp.android.data.repository.ProfitRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

const val ALL_BRANCHES_LABEL = "All branches (collective)"

data class ProfitUiState(
    val day: LocalDate = LocalDate.now(ZoneId.of("Africa/Nairobi")),
    val branches: List<BranchOut> = emptyList(),
    val branchFilter: String = ALL_BRANCHES_LABEL,
    val data: ProfitDayOut? = null,
    val costs: List<CostItemOut> = emptyList(),
    val loading: Boolean = true,
    val message: String? = null,
)

// Profit is owner-only: the server refuses everyone else, and the day runs midnight to midnight in Kenya
class ProfitViewModel(private val profit: ProfitRepository, private val exchange: ExchangeRepository) : ViewModel() {
    private val _state = MutableStateFlow(ProfitUiState())
    val state: StateFlow<ProfitUiState> = _state

    init {
        viewModelScope.launch {
            exchange.branches().onSuccess { _state.value = _state.value.copy(branches = it) }
            load()
        }
        viewModelScope.launch { DataVersion.flow.drop(1).collect { load(quiet = true) } }
    }

    private suspend fun load(quiet: Boolean = false) {
        if (!quiet) _state.value = _state.value.copy(loading = true)
        val s = _state.value
        val branchId = s.branches.firstOrNull { it.name == s.branchFilter }?.id
        profit.daily(s.day.toString(), branchId)
            .onSuccess { _state.value = _state.value.copy(data = it, loading = false) }
            .onFailure { _state.value = _state.value.copy(loading = false, message = it.message) }
        profit.costs().onSuccess { _state.value = _state.value.copy(costs = it) }
    }

    fun setDay(day: LocalDate) {
        _state.value = _state.value.copy(day = day)
        viewModelScope.launch { load() }
    }

    fun shift(days: Long) = setDay(_state.value.day.plusDays(days))

    fun today() = setDay(LocalDate.now(ZoneId.of("Africa/Nairobi")))

    fun setBranch(name: String) {
        _state.value = _state.value.copy(branchFilter = name)
        viewModelScope.launch { load() }
    }

    fun saveCost(itemId: Int, value: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val cost = value.trim()
            if ((cost.toDoubleOrNull() ?: -1.0) < 0.0) {
                _state.value = _state.value.copy(message = "Enter a cost of zero or more.")
                return@launch onDone(false)
            }
            profit.setCost(itemId, cost)
                .onSuccess { _state.value = _state.value.copy(message = "Cost saved."); onDone(true) }
                .onFailure { _state.value = _state.value.copy(message = it.message); onDone(false) }
        }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}
