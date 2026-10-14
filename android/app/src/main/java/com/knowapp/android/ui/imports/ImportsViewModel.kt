package com.knowapp.android.ui.imports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knowapp.android.data.model.ApproveBody
import com.knowapp.android.data.model.MessageImportOut
import com.knowapp.android.data.model.StockItemOut
import com.knowapp.android.data.repository.ImportsRepository
import com.knowapp.android.data.repository.StockRepository
import com.knowapp.android.data.repository.StockResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ImportsUiState(
    val loading: Boolean = true,
    val items: List<MessageImportOut> = emptyList(),
    val stock: List<StockItemOut> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val confirmForce: Pair<Int, ApproveBody>? = null,
)

class ImportsViewModel(
    private val repository: ImportsRepository,
    private val stockRepository: StockRepository,
    private val pendingShare: MutableStateFlow<String?>,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportsUiState())
    val state: StateFlow<ImportsUiState> = _state

    private val splitter = Regex("(?=\\b[A-Z0-9]{8,12}\\s+Confirmed)")

    init {
        viewModelScope.launch {
            val result = stockRepository.list()
            if (result is StockResult.Success) _state.value = _state.value.copy(stock = result.items)
            pendingShare.value?.let { pendingShare.value = null; scan(it) } ?: refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            repository.list()
                .onSuccess { _state.value = _state.value.copy(loading = false, items = it) }
                .onFailure { _state.value = _state.value.copy(loading = false, message = it.message) }
        }
    }

    fun scan(text: String) {
        val parts = text.split(splitter).map { it.trim() }.filter { it.isNotEmpty() }.take(50)
        if (parts.isEmpty()) { _state.value = _state.value.copy(message = "Paste at least one message."); return }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            repository.scan(parts)
                .onSuccess { _state.value = _state.value.copy(message = "${it.items.size} detected" + if (it.skipped > 0) ", ${it.skipped} not recognised" else ".") }
                .onFailure { _state.value = _state.value.copy(message = it.message) }
            _state.value = _state.value.copy(busy = false)
            refresh()
        }
    }

    fun approve(id: Int, body: ApproveBody) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, confirmForce = null)
            repository.approve(id, body)
                .onSuccess { _state.value = _state.value.copy(message = "Saved to books.") }
                .onFailure {
                    val msg = it.message ?: "Could not save."
                    _state.value = if (msg.contains("already recorded", ignoreCase = true)) _state.value.copy(confirmForce = id to body, message = null) else _state.value.copy(message = msg)
                }
            _state.value = _state.value.copy(busy = false)
            refresh()
        }
    }

    fun ignore(id: Int) {
        viewModelScope.launch {
            repository.ignore(id).onFailure { _state.value = _state.value.copy(message = it.message) }
            refresh()
        }
    }

    fun dismissForce() { _state.value = _state.value.copy(confirmForce = null) }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}
