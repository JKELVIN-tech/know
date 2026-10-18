package com.knowapp.android.ui.statements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knowapp.android.data.SessionStore
import com.knowapp.android.data.model.OpeningBalancesOut
import com.knowapp.android.data.model.PendingTransaction
import com.knowapp.android.data.model.TransactionOut
import com.knowapp.android.data.model.UserOut
import com.knowapp.android.data.repository.BooksRepository
import com.knowapp.android.data.repository.HomeData
import com.knowapp.android.data.repository.InstitutionsRepository
import com.knowapp.android.data.repository.InstitutionsResult
import com.knowapp.android.data.repository.TransactionRepository
import com.knowapp.android.data.repository.TransactionsResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import com.knowapp.android.data.DataVersion
import kotlinx.coroutines.flow.drop

data class StatementRow(
    val date: LocalDate,
    val time: LocalTime?,
    val category: String,
    val note: String?,
    val method: String,
    val type: String,
    val amount: BigDecimal,
    val reference: String?,
    val party: String?,
    val pending: Boolean,
) {
    val methodLabel: String get() = when (method) { "mpesa" -> "M-Pesa"; "cash" -> "Cash"; "bank" -> "Bank"; else -> method }
}

data class StatementLine(val row: StatementRow, val balance: BigDecimal?)

enum class StatementRange(val label: String) {
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    LAST_7("Last 7 days"),
    LAST_30("Last 30 days"),
    ALL("All time"),
    CUSTOM("Custom"),
}

val STATEMENT_METHODS = listOf("all" to "All", "cash" to "Cash", "mpesa" to "M-Pesa", "bank" to "Bank")

private fun amountOf(raw: String) = raw.toBigDecimalOrNull() ?: BigDecimal.ZERO

data class StatementsUiState(
    val loading: Boolean = true,
    val rows: List<StatementRow> = emptyList(),
    val method: String = "all",
    val range: StatementRange = StatementRange.THIS_MONTH,
    val customFrom: LocalDate = LocalDate.now().withDayOfMonth(1),
    val customTo: LocalDate = LocalDate.now(),
    val refreshing: Boolean = false,
    val isAdmin: Boolean = false,
    val staff: List<UserOut> = emptyList(),
    val selectedStaffId: Int? = null,
    val opening: OpeningBalancesOut = OpeningBalancesOut("0", "0", "0", false),
    val institutionName: String? = null,
    val preparedBy: String = "",
) {
    val staffLabel: String? get() = staff.firstOrNull { it.id == selectedStaffId }?.fullName

    // A running balance only makes sense for a person's own books, not the collective view
    val showBalance: Boolean get() = !isAdmin

    private fun bounds(today: LocalDate): Pair<LocalDate?, LocalDate?> = when (range) {
        StatementRange.THIS_MONTH -> today.withDayOfMonth(1) to today
        StatementRange.LAST_MONTH -> {
            val first = today.withDayOfMonth(1).minusMonths(1)
            first to first.withDayOfMonth(first.lengthOfMonth())
        }
        StatementRange.LAST_7 -> today.minusDays(6) to today
        StatementRange.LAST_30 -> today.minusDays(29) to today
        StatementRange.ALL -> null to null
        StatementRange.CUSTOM -> customFrom to customTo
    }

    val periodText: String
        get() {
            val (from, to) = bounds(LocalDate.now())
            return if (from == null || to == null) "All time" else "$from to $to"
        }

    private fun inMethod(r: StatementRow) = method == "all" || r.method == method

    private fun signed(r: StatementRow) = if (r.type == "income") r.amount else r.amount.negate()

    val visible: List<StatementRow>
        get() {
            val (from, to) = bounds(LocalDate.now())
            return rows
                .filter { inMethod(it) }
                .filter { (from == null || !it.date.isBefore(from)) && (to == null || !it.date.isAfter(to)) }
                .sortedWith(compareBy({ it.date }, { it.time ?: LocalTime.MIN }))
        }

    private val openingBase: BigDecimal
        get() = when (method) {
            "cash" -> amountOf(opening.cash)
            "mpesa" -> amountOf(opening.mpesa)
            "bank" -> amountOf(opening.bank)
            else -> amountOf(opening.cash) + amountOf(opening.mpesa) + amountOf(opening.bank)
        }

    // Balance carried in at the start of the period: starting balance plus everything before it
    val openingBalance: BigDecimal
        get() {
            val (from, _) = bounds(LocalDate.now())
            val prior = if (from == null) emptyList() else rows.filter { inMethod(it) && it.date.isBefore(from) }
            return openingBase + prior.fold(BigDecimal.ZERO) { a, r -> a + signed(r) }
        }

    val income: BigDecimal get() = visible.filter { it.type == "income" }.fold(BigDecimal.ZERO) { a, r -> a + r.amount }
    val expense: BigDecimal get() = visible.filter { it.type == "expense" }.fold(BigDecimal.ZERO) { a, r -> a + r.amount }
    val closingBalance: BigDecimal get() = openingBalance + income - expense

    val lines: List<StatementLine>
        get() {
            var running = openingBalance
            return visible.map { r ->
                running += signed(r)
                StatementLine(r, if (showBalance) running else null)
            }
        }

    val accountLabel: String get() = if (method == "all") "All accounts" else STATEMENT_METHODS.first { it.first == method }.second
    val title: String get() = if (method == "all") "Combined Statement" else "${STATEMENT_METHODS.first { it.first == method }.second} Statement"
}

internal fun zonedOf(iso: String): ZonedDateTime? = try {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
} catch (e: Exception) {
    try {
        Instant.parse(iso).atZone(ZoneId.systemDefault())
    } catch (e2: Exception) {
        null
    }
}

internal fun dateOf(iso: String): LocalDate = zonedOf(iso)?.toLocalDate() ?: LocalDate.parse(iso.take(10))

private fun TransactionOut.toRow() = StatementRow(
    date = dateOf(transactionDate),
    time = zonedOf(transactionDate)?.toLocalTime(),
    category = category,
    note = description?.takeIf { it.isNotBlank() },
    method = method,
    type = type,
    amount = amountOf(amount),
    reference = mpesaCode?.takeIf { it.isNotBlank() },
    party = mpesaPayerName?.takeIf { it.isNotBlank() },
    pending = false,
)

// Entries still waiting to sync are dated by when they were recorded on the phone
private fun PendingTransaction.toRow() = StatementRow(
    date = dateOf(createdAt),
    time = zonedOf(createdAt)?.toLocalTime(),
    category = category ?: "Stock entry",
    note = description?.takeIf { it.isNotBlank() },
    method = method,
    type = type,
    amount = amountOf(amount),
    reference = mpesaCode?.takeIf { it.isNotBlank() },
    party = mpesaPayerName?.takeIf { it.isNotBlank() },
    pending = true,
)

class StatementsViewModel(
    private val transactions: TransactionRepository,
    private val books: BooksRepository,
    private val institutions: InstitutionsRepository,
    private val sessionStore: SessionStore,
    private val isAdmin: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(StatementsUiState(isAdmin = isAdmin, preparedBy = sessionStore.session.value?.fullName ?: ""))
    val state: StateFlow<StatementsUiState> = _state

    private fun rowsOf(data: HomeData) = data.transactions.map { it.toRow() } + data.pending.filter { !it.failed }.map { it.toRow() }

    private suspend fun loadFor(staffId: Int?) {
        _state.value = _state.value.copy(refreshing = true, selectedStaffId = staffId)
        when (val result = transactions.list(staffId = staffId)) {
            is TransactionsResult.Success -> _state.value = _state.value.copy(refreshing = false, loading = false, rows = result.transactions.map { it.toRow() })
            is TransactionsResult.Failure -> _state.value = _state.value.copy(refreshing = false, loading = false)
        }
    }

    fun selectStaff(staffId: Int?) {
        viewModelScope.launch { loadFor(staffId) }
    }

    init {
        viewModelScope.launch {
            val id = sessionStore.session.value?.institutionId
            val result = institutions.list()
            if (result is InstitutionsResult.Success) {
                _state.value = _state.value.copy(institutionName = result.institutions.firstOrNull { it.id == id }?.name)
            }
        }
        if (isAdmin) {
            viewModelScope.launch {
                books.staff().onSuccess { _state.value = _state.value.copy(staff = it) }
                loadFor(null)
            }
        } else viewModelScope.launch {
            // Paint what is on the phone straight away, then update from the server
            val cached = transactions.cachedHome()
            _state.value = _state.value.copy(loading = false, refreshing = true, rows = rowsOf(cached), opening = cached.opening)
            val data = transactions.loadHome()
            _state.value = _state.value.copy(refreshing = false, rows = rowsOf(data), opening = data.opening)
            DataVersion.flow.drop(1).collect {
                val fresh = transactions.loadHome()
                _state.value = _state.value.copy(rows = rowsOf(fresh), opening = fresh.opening)
            }
        }
    }

    fun setCustomFrom(date: LocalDate) {
        _state.value = _state.value.copy(customFrom = date, customTo = if (date.isAfter(_state.value.customTo)) date else _state.value.customTo)
    }

    fun setCustomTo(date: LocalDate) {
        _state.value = _state.value.copy(customTo = date, customFrom = if (date.isBefore(_state.value.customFrom)) date else _state.value.customFrom)
    }

    fun selectMethod(method: String) {
        _state.value = _state.value.copy(method = method)
    }

    fun selectRange(range: StatementRange) {
        _state.value = _state.value.copy(range = range)
    }
}
