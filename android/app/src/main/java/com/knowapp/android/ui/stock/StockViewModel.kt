package com.knowapp.android.ui.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knowapp.android.data.model.BranchOut
import com.knowapp.android.data.model.ProductCategoryOut
import com.knowapp.android.data.model.TransferBody
import com.knowapp.android.data.model.TransferOut
import com.knowapp.android.data.repository.ExchangeRepository
import com.knowapp.android.data.model.StockItemOut
import com.knowapp.android.data.repository.DocumentsRepository
import com.knowapp.android.data.repository.StockRepository
import com.knowapp.android.data.repository.StockResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.knowapp.android.data.DataVersion
import kotlinx.coroutines.flow.drop

const val NEW_CATEGORY = "+ New category…"
const val ALL_BRANCHES = "All branches (collective)"
const val NO_BRANCH = "Unassigned (shared)"

data class StockUiState(
    val loading: Boolean = true,
    val items: List<StockItemOut> = emptyList(),
    val categories: List<ProductCategoryOut> = emptyList(),
    val error: String? = null,
    val message: String? = null,
    val saving: Boolean = false,
    val docCounts: Map<Int, Int> = emptyMap(),
    val branches: List<BranchOut> = emptyList(),
    val branchFilter: String = ALL_BRANCHES,
    val transfers: List<TransferOut> = emptyList(),
    val pendingCount: Int = 0,
    val exchangeBusy: Boolean = false,
)

class StockViewModel(
    private val repository: StockRepository,
    private val documents: DocumentsRepository,
    private val exchange: ExchangeRepository,
    val isOwner: Boolean,
    val meId: Int?,
) : ViewModel() {
    private val _state = MutableStateFlow(StockUiState())
    val state: StateFlow<StockUiState> = _state

    init {
        refresh()
        loadExchange()
        viewModelScope.launch { DataVersion.flow.drop(1).collect { refresh(quiet = true); loadExchange() } }
    }

    fun setBranchFilter(name: String) {
        _state.value = _state.value.copy(branchFilter = name)
    }

    fun loadExchange() {
        viewModelScope.launch {
            exchange.branches().onSuccess { _state.value = _state.value.copy(branches = it) }
            exchange.transfers().onSuccess { list ->
                _state.value = _state.value.copy(transfers = list, pendingCount = list.count { it.status == "pending" && (isOwner || it.direction == "incoming") })
            }
        }
    }

    fun sendTransfer(fromBranchId: Int?, itemId: Int, quantity: String, toBranchId: Int, note: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val qty = quantity.trim()
            if ((qty.toDoubleOrNull() ?: 0.0) <= 0.0) {
                _state.value = _state.value.copy(message = "Enter a quantity above zero.")
                return@launch onDone(false)
            }
            _state.value = _state.value.copy(exchangeBusy = true)
            exchange.send(TransferBody(itemId, qty, toBranchId, fromBranchId, note.trim().ifBlank { null }))
                .onSuccess { _state.value = _state.value.copy(message = "Stock sent. It reaches the other branch when they confirm."); onDone(true) }
                .onFailure { _state.value = _state.value.copy(message = it.message); onDone(false) }
            _state.value = _state.value.copy(exchangeBusy = false)
            refresh()
            loadExchange()
        }
    }

    fun actOnTransfer(id: Int, action: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(exchangeBusy = true)
            exchange.act(id, action).onFailure { _state.value = _state.value.copy(message = it.message) }
            _state.value = _state.value.copy(exchangeBusy = false)
            refresh()
            loadExchange()
        }
    }

    fun refresh(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) _state.value = _state.value.copy(loading = true, error = null)
            val categories = repository.categories()
            when (val result = repository.list()) {
                is StockResult.Success -> _state.value = _state.value.copy(loading = false, items = result.items, categories = categories)
                is StockResult.Failure -> _state.value = _state.value.copy(loading = false, error = result.message, categories = categories)
            }
            // How many documents back each item
            documents.list(null, null).onSuccess { docs ->
                _state.value = _state.value.copy(docCounts = docs.mapNotNull { it.stockItemId }.groupingBy { it }.eachCount())
            }
        }
    }

    fun messageShown() {
        _state.value = _state.value.copy(message = null)
    }

    private fun fail(text: String, onDone: (Boolean) -> Unit) {
        _state.value = _state.value.copy(saving = false, message = text)
        onDone(false)
    }

    fun save(
        editing: StockItemOut?,
        name: String,
        categoryName: String,
        newCategory: String,
        description: String,
        price: String,
        quantity: String,
        assignBranch: String,
        lowAt: String,
        onDone: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            if (name.isBlank()) return@launch fail("Enter a name for the item.", onDone)
            val priceValue = price.trim().takeIf { it.isNotEmpty() }?.let { it.toDoubleOrNull() ?: return@launch fail("Enter a valid price.", onDone) }
            val quantityValue = if (editing == null) (quantity.trim().ifEmpty { "0" }.toDoubleOrNull() ?: return@launch fail("Enter a valid quantity.", onDone)) else 0.0
            if (editing == null && quantityValue < 0) return@launch fail("Quantity cannot be negative.", onDone)
            val lowValue = lowAt.trim().takeIf { it.isNotEmpty() }?.let { it.toDoubleOrNull()?.takeIf { v -> v >= 0 } ?: return@launch fail("Enter a valid low stock level.", onDone) }

            _state.value = _state.value.copy(saving = true)
            val categoryId: Int = if (categoryName == NEW_CATEGORY) {
                if (newCategory.isBlank()) return@launch fail("Enter a name for the new category.", onDone)
                val created = repository.createCategory(newCategory.trim())
                created.getOrNull()?.id ?: return@launch fail(created.exceptionOrNull()?.message ?: "Couldn't create the category.", onDone)
            } else {
                _state.value.categories.firstOrNull { it.name == categoryName }?.id ?: return@launch fail("Choose a category.", onDone)
            }

            val description = description.trim().ifBlank { null }
            val result = if (editing == null) repository.create(name.trim(), categoryId, description, priceValue, quantityValue, if (isOwner) _state.value.branches.firstOrNull { it.name == _state.value.branchFilter }?.id else null, lowValue)
            else repository.update(editing.id, name.trim(), categoryId, description, priceValue, _state.value.branches.firstOrNull { it.name == assignBranch }?.id, lowValue)

            result.onSuccess {
                _state.value = _state.value.copy(saving = false, message = if (editing == null) "Item added." else "Item updated.")
                refresh()
                onDone(true)
            }.onFailure { fail(it.message ?: "Couldn't save the item.", onDone) }
        }
    }

    fun delete(item: StockItemOut, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            repository.delete(item.id)
                .onSuccess {
                    _state.value = _state.value.copy(message = "Item deleted.")
                    refresh()
                    onDone(true)
                }
                .onFailure {
                    _state.value = _state.value.copy(message = it.message ?: "Couldn't delete the item.")
                    onDone(false)
                }
        }
    }
}
