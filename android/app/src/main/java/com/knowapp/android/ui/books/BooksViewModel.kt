package com.knowapp.android.ui.books

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knowapp.android.data.model.AccountBalanceOut
import com.knowapp.android.data.model.LedgerOut
import com.knowapp.android.data.model.UserOut
import com.knowapp.android.data.repository.AuditActionResult
import com.knowapp.android.data.repository.AuditRepository
import com.knowapp.android.data.repository.BooksData
import com.knowapp.android.data.repository.BooksRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import com.knowapp.android.data.DataVersion
import kotlinx.coroutines.flow.drop

enum class BooksPeriod(val label: String) {
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    LAST_30("Last 30 days"),
    ALL("All time"),
    CUSTOM("Custom range"),
}

enum class BooksView(val label: String) { JOURNAL("Journal"), TRIAL("Trial balance"), ACCOUNTS("Final accounts") }

data class BooksUiState(
    val loading: Boolean = true,
    val staff: List<UserOut> = emptyList(),
    val selectedStaffId: Int? = null,
    val period: BooksPeriod = BooksPeriod.THIS_MONTH,
    val view: BooksView = BooksView.JOURNAL,
    val data: BooksData? = null,
    val error: String? = null,
    val ledgerAccount: AccountBalanceOut? = null,
    val ledger: LedgerOut? = null,
    val ledgerLoading: Boolean = false,
    val customStart: LocalDate? = null,
    val customEnd: LocalDate? = null,
    val message: String? = null,
) {
    val staffName: String? get() = staff.firstOrNull { it.id == selectedStaffId }?.fullName
}

class BooksViewModel(private val repository: BooksRepository, private val audits: AuditRepository) : ViewModel() {
    private val _state = MutableStateFlow(BooksUiState())
    val state: StateFlow<BooksUiState> = _state

    init {
        viewModelScope.launch {
            repository.staff().onSuccess { _state.value = _state.value.copy(staff = it) }
            load()
        }
        viewModelScope.launch { DataVersion.flow.drop(1).collect { load() } }
    }

    private fun bounds(period: BooksPeriod): Pair<String?, String?> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        fun start(d: LocalDate) = d.atStartOfDay(zone).toInstant().toString()
        fun end(d: LocalDate) = d.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1).toString()
        return when (period) {
            BooksPeriod.THIS_MONTH -> start(today.withDayOfMonth(1)) to end(today)
            BooksPeriod.LAST_MONTH -> {
                val first = today.withDayOfMonth(1).minusMonths(1)
                start(first) to end(first.withDayOfMonth(first.lengthOfMonth()))
            }
            BooksPeriod.LAST_30 -> start(today.minusDays(29)) to end(today)
            BooksPeriod.ALL -> null to null
            BooksPeriod.CUSTOM -> {
                val s = _state.value
                if (s.customStart != null && s.customEnd != null) start(s.customStart) to end(s.customEnd) else null to null
            }
        }
    }

    private suspend fun load() {
        val s = _state.value
        _state.value = s.copy(loading = true, error = null)
        val (start, end) = bounds(s.period)
        repository.load(s.selectedStaffId, start, end)
            .onSuccess { _state.value = _state.value.copy(loading = false, data = it) }
            .onFailure { _state.value = _state.value.copy(loading = false, error = it.message) }
    }

    fun selectStaff(staffId: Int?) {
        _state.value = _state.value.copy(selectedStaffId = staffId)
        viewModelScope.launch { load() }
    }

    fun selectPeriod(period: BooksPeriod) {
        _state.value = _state.value.copy(period = period)
        viewModelScope.launch { load() }
    }

    fun messageShown() {
        _state.value = _state.value.copy(message = null)
    }

    // A custom range is saved as an audit (title plus period) before the books are generated for it
    fun saveCustomRange(start: LocalDate, end: LocalDate, title: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            if (title.isBlank()) {
                _state.value = _state.value.copy(message = "Enter a title for this audit.")
                return@launch onDone(false)
            }
            val zone = ZoneId.systemDefault()
            val startIso = start.atStartOfDay(zone).toInstant().toString()
            val endIso = end.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1).toString()
            val summary = _state.value.staffName?.let { "Books viewed for $it" }
            when (val result = audits.create(title.trim(), startIso, endIso, summary)) {
                is AuditActionResult.Success -> {
                    _state.value = _state.value.copy(period = BooksPeriod.CUSTOM, customStart = start, customEnd = end, message = "Saved to Audits.")
                    load()
                    onDone(true)
                }
                is AuditActionResult.Failure -> {
                    _state.value = _state.value.copy(message = result.message)
                    onDone(false)
                }
            }
        }
    }

    fun selectView(view: BooksView) {
        _state.value = _state.value.copy(view = view)
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    fun openLedger(account: AccountBalanceOut) {
        _state.value = _state.value.copy(ledgerAccount = account, ledger = null, ledgerLoading = true)
        viewModelScope.launch {
            val (_, end) = bounds(_state.value.period)
            repository.ledger(account.id, _state.value.selectedStaffId, end)
                .onSuccess { _state.value = _state.value.copy(ledger = it, ledgerLoading = false) }
                .onFailure { _state.value = _state.value.copy(ledgerLoading = false) }
        }
    }

    fun closeLedger() {
        _state.value = _state.value.copy(ledgerAccount = null, ledger = null)
    }
}
