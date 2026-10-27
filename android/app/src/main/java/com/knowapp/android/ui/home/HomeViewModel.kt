package com.knowapp.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knowapp.android.data.Session
import com.knowapp.android.data.SessionStore
import android.net.Uri
import com.knowapp.android.data.local.OfflineStore
import com.knowapp.android.data.local.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.knowapp.android.data.model.OpeningBalancesOut
import com.knowapp.android.data.model.PendingTransaction
import com.knowapp.android.data.model.StockItemOut
import com.knowapp.android.data.model.TransactionOut
import com.knowapp.android.data.network.NetworkMonitor
import com.knowapp.android.data.repository.AuthRepository
import com.knowapp.android.data.repository.InstitutionsRepository
import com.knowapp.android.data.repository.InstitutionsResult
import com.knowapp.android.data.repository.NewTransaction
import com.knowapp.android.data.repository.NewSale
import com.knowapp.android.data.repository.RecordResult
import com.knowapp.android.data.repository.SaleLine
import com.knowapp.android.ui.receipt.ReceiptBuilder
import com.knowapp.android.data.repository.StockRepository
import com.knowapp.android.data.repository.StockResult
import com.knowapp.android.data.repository.TransactionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import com.knowapp.android.data.DataVersion

data class HomeUiState(
    val loading: Boolean = true,
    val transactions: List<TransactionOut> = emptyList(),
    val pending: List<PendingTransaction> = emptyList(),
    val opening: OpeningBalancesOut = OpeningBalancesOut("0", "0", "0", false),
    val stock: List<StockItemOut> = emptyList(),
    val institutionName: String? = null,
    val lastReceipt: com.knowapp.android.ui.receipt.ReceiptData? = null,
    val saving: Boolean = false,
    val loaded: Boolean = false,
    val recordError: String? = null,
    val refreshing: Boolean = false,
    val message: String? = null,
)

class HomeViewModel(
    private val authRepository: AuthRepository,
    private val transactions: TransactionRepository,
    private val stockRepository: StockRepository,
    private val offlineStore: OfflineStore,
    private val sessionStore: SessionStore,
    private val institutions: InstitutionsRepository,
    private val photos: PhotoStore,
    network: NetworkMonitor,
) : ViewModel() {
    val session: StateFlow<Session?> = authRepository.session

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    init {
        viewModelScope.launch {
            val cached = transactions.cachedHome()
            _state.value = _state.value.copy(
                loading = false,
                loaded = cached.transactions.isNotEmpty() || cached.pending.isNotEmpty() || cached.opening.isSet,
                transactions = cached.transactions,
                pending = cached.pending,
                opening = cached.opening,
            )
            refresh()
        }
        viewModelScope.launch {
            val id = sessionStore.session.value?.institutionId
            val result = institutions.list()
            if (result is InstitutionsResult.Success) {
                _state.value = _state.value.copy(institutionName = result.institutions.firstOrNull { it.id == id }?.name)
            }
        }
        viewModelScope.launch { DataVersion.flow.drop(1).collect { refresh() } }
        // Entries saved offline go to the server as soon as a connection returns
        viewModelScope.launch {
            network.online.drop(1).filter { it }.collect { refresh() }
        }
    }

    private suspend fun loadStock() {
        val uid = sessionStore.session.value?.userId ?: return
        val isTeacher = sessionStore.session.value?.staffType == "teacher"
        if (isTeacher) return
        when (val result = stockRepository.list()) {
            is StockResult.Success -> {
                offlineStore.saveStock(uid, result.items)
                _state.value = _state.value.copy(stock = result.items)
            }
            is StockResult.Failure -> _state.value = _state.value.copy(stock = offlineStore.stock(uid) ?: emptyList())
        }
    }

    suspend fun refresh() {
        if (sessionStore.session.value?.role != "staff") {
            _state.value = _state.value.copy(loading = false)
            return
        }
        if (_state.value.institutionName == null) {
            val id = sessionStore.session.value?.institutionId
            val result = institutions.list()
            if (result is InstitutionsResult.Success) {
                _state.value = _state.value.copy(institutionName = result.institutions.firstOrNull { it.id == id }?.name)
            }
        }
        transactions.syncPending()
        val data = transactions.loadHome()
        _state.value = _state.value.copy(loading = false, loaded = true, transactions = data.transactions, pending = data.pending, opening = data.opening)
        loadStock()
    }

    fun pullRefresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(refreshing = true)
            refresh()
            _state.value = _state.value.copy(refreshing = false)
        }
    }

    fun record(draft: NewTransaction, photo: Uri?, onFinished: (Boolean) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, recordError = null)
            val path = photo?.let { withContext(Dispatchers.IO) { photos.import(it) } }
            when (val result = transactions.record(draft.copy(imagePath = path))) {
                is RecordResult.Saved, is RecordResult.Queued -> {
                    val receipt = if (result is RecordResult.Saved && transactions.lastCreated.isNotEmpty()) {
                        ReceiptBuilder.fromTransactions(transactions.lastCreated, businessName(), servedByName())
                    } else {
                        ReceiptBuilder.fromSale(NewSale(draft.type, draft.method, draft.description, draft.mpesaCode, draft.mpesaPayerName, listOf(SaleLine(draft.categoryType, draft.category, draft.stockItemId, draft.quantity, draft.amount)), java.util.UUID.randomUUID().toString()), businessName(), servedByName())
                    }
                    _state.value = _state.value.copy(saving = false, message = null, lastReceipt = receipt)
                    refresh()
                    onFinished(true)
                }
                is RecordResult.Rejected -> {
                    _state.value = _state.value.copy(saving = false, recordError = result.message)
                    onFinished(false)
                }
            }
        }
    }

    fun recordSale(sale: NewSale, photo: Uri?, onFinished: (Boolean) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, recordError = null)
            val path = photo?.let { withContext(Dispatchers.IO) { photos.import(it) } }
            when (val result = transactions.recordSale(sale, path)) {
                is RecordResult.Saved, is RecordResult.Queued -> {
                    val receipt = if (result is RecordResult.Saved && transactions.lastCreated.isNotEmpty()) {
                        ReceiptBuilder.fromTransactions(transactions.lastCreated, businessName(), servedByName())
                    } else {
                        ReceiptBuilder.fromSale(sale, businessName(), servedByName())
                    }
                    _state.value = _state.value.copy(saving = false, message = null, lastReceipt = receipt)
                    refresh()
                    onFinished(true)
                }
                is RecordResult.Rejected -> {
                    _state.value = _state.value.copy(saving = false, recordError = result.message)
                    onFinished(false)
                }
            }
        }
    }

    fun clearReceipt() {
        _state.value = _state.value.copy(lastReceipt = null)
    }

    // The business shown at the top of every receipt, and who made the sale
    fun businessName(): String = _state.value.institutionName?.takeIf { it.isNotBlank() && !it.contains("(@") } ?: session.value?.fullName ?: "KNOW"

    fun servedByName(): String = session.value?.fullName ?: ""

    fun receiptFor(entries: List<TransactionOut>): com.knowapp.android.ui.receipt.ReceiptData = ReceiptBuilder.fromTransactions(entries, businessName(), servedByName())

    fun updateTransaction(id: Int, fields: Map<String, String>, onFinished: (String?) -> Unit) {
        viewModelScope.launch {
            when (val result = transactions.update(id, fields)) {
                is RecordResult.Rejected -> onFinished(result.message)
                else -> {
                    _state.value = _state.value.copy(message = "Entry updated.")
                    refresh()
                    onFinished(null)
                }
            }
        }
    }

    suspend fun checkReference(code: String) = transactions.checkReference(code)

    fun saveOpening(cash: Double, mpesa: Double, bank: Double, onFinished: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = transactions.setOpening(cash, mpesa, bank)
            result.onSuccess {
                _state.value = _state.value.copy(opening = it, message = "Starting balances saved.")
            }.onFailure {
                _state.value = _state.value.copy(message = it.message)
            }
            onFinished(result.isSuccess)
        }
    }

    fun discardPending(localId: String) {
        viewModelScope.launch {
            transactions.discardPending(localId)
            refresh()
        }
    }

    fun clearRecordError() {
        _state.value = _state.value.copy(recordError = null)
    }

    fun messageShown() {
        _state.value = _state.value.copy(message = null)
    }

    suspend fun logout() {
        authRepository.logout()
    }
}
