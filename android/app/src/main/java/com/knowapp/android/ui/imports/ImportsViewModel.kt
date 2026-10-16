package com.knowapp.android.ui.imports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knowapp.android.data.MpesaParser
import com.knowapp.android.data.local.DetectedMessage
import com.knowapp.android.data.local.DetectedStore
import com.knowapp.android.data.model.ApproveBody
import com.knowapp.android.data.model.StockItemOut
import com.knowapp.android.data.repository.NewTransaction
import com.knowapp.android.data.repository.RecordResult
import com.knowapp.android.data.repository.StockRepository
import com.knowapp.android.data.repository.StockResult
import com.knowapp.android.data.repository.TransactionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ImportsUiState(
    val loading: Boolean = false,
    val items: List<DetectedMessage> = emptyList(),
    val stock: List<StockItemOut> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val confirmForce: Pair<Int, ApproveBody>? = null,
)

// Detection runs on the phone, and saving goes through the same offline-capable path as a normal entry
class ImportsViewModel(
    private val transactions: TransactionRepository,
    private val stockRepository: StockRepository,
    private val store: DetectedStore,
    private val pendingShare: MutableStateFlow<String?>,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportsUiState(items = store.load()))
    val state: StateFlow<ImportsUiState> = _state

    private val splitter = Regex("(?=\\b[A-Z0-9]{8,12}\\s+Confirmed)")
    private val banks = listOf("KCB", "EQUITY", "CO-OPERATIVE", "CO-OP", "COOP", "ABSA", "STANBIC", "NCBA", "FAMILY BANK", "I&M", "DTB", "DIAMOND TRUST", "STANDARD CHARTERED", "SIDIAN", "HF GROUP", "GULF AFRICAN")

    init {
        viewModelScope.launch {
            val result = stockRepository.list()
            if (result is StockResult.Success) _state.value = _state.value.copy(stock = result.items)
        }
        pendingShare.value?.let { pendingShare.value = null; scan(it) }
    }

    private fun update(items: List<DetectedMessage>, message: String? = _state.value.message) {
        store.save(items)
        _state.value = _state.value.copy(items = items, message = message)
    }

    fun scan(text: String) {
        val parts = text.split(splitter).map { it.trim() }.filter { it.isNotEmpty() }.take(50)
        if (parts.isEmpty()) { _state.value = _state.value.copy(message = "Paste at least one message."); return }
        var items = _state.value.items
        var added = 0
        var skipped = 0
        for (part in parts) {
            val parsed = MpesaParser.parse(part)
            val code = parsed.code
            if (code == null || parsed.amount == null) { skipped++; continue }
            if (items.any { it.code == code }) continue
            val lower = part.lowercase()
            val reversal = "reversal" in lower
            val bank = banks.any { b -> parsed.party?.uppercase()?.contains(b) == true }
            val reason = when {
                reversal -> "reversal_needs_review"
                parsed.incoming == null -> "missing_fields"
                bank -> "bank_bound"
                else -> null
            }
            val kind = when {
                reversal -> "reversal"
                parsed.incoming == true -> "received"
                parsed.incoming == false -> "sent"
                else -> "unknown"
            }
            items = items + DetectedMessage(
                id = (items.maxOfOrNull { it.id } ?: 0) + 1, code = code, kind = kind,
                direction = when (parsed.incoming) { true -> "in"; false -> "out"; null -> null },
                amount = parsed.amount, counterparty = parsed.party,
                status = if (reason == null) "detected" else "needs_review", reason = reason,
            )
            added++
        }
        update(items, "$added detected" + if (skipped > 0) ", $skipped not recognised" else ".")
    }

    fun approve(id: Int, body: ApproveBody) {
        val item = _state.value.items.firstOrNull { it.id == id } ?: return
        if (item.amount == null || item.direction == null || item.kind == "reversal") {
            _state.value = _state.value.copy(message = "This message cannot be posted automatically; enter it manually.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, confirmForce = null)
            if (!body.force && transactions.checkReference(item.code) != null) {
                _state.value = _state.value.copy(busy = false, confirmForce = id to body)
                return@launch
            }
            val isStock = body.categoryType == "STOCK"
            val draft = NewTransaction(
                type = if (item.direction == "in") "income" else "expense",
                method = "mpesa",
                categoryType = body.categoryType,
                category = if (isStock) _state.value.stock.firstOrNull { it.id == body.stockItemId }?.name else body.category?.trim()?.ifBlank { "Other" } ?: "Other",
                description = body.description?.trim()?.ifBlank { null },
                amount = item.amount,
                stockItemId = if (isStock) body.stockItemId else null,
                quantity = if (isStock) body.quantity else null,
                mpesaCode = item.code,
                mpesaPayerName = item.counterparty,
            )
            when (val result = transactions.record(draft)) {
                RecordResult.Saved -> update(_state.value.items.map { if (it.id == id) it.copy(status = "imported") else it }, "Saved to books.")
                RecordResult.Queued -> update(_state.value.items.map { if (it.id == id) it.copy(status = "imported") else it }, "Saved on this phone. It will sync when you're online.")
                is RecordResult.Rejected -> _state.value = _state.value.copy(message = result.message)
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun ignore(id: Int) {
        update(_state.value.items.map { if (it.id == id) it.copy(status = "ignored") else it })
    }

    fun dismissForce() { _state.value = _state.value.copy(confirmForce = null) }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}
