package com.knowapp.android.ui.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knowapp.android.data.SessionStore
import com.knowapp.android.data.model.DocumentOut
import com.knowapp.android.data.repository.DocumentsRepository
import com.knowapp.android.data.repository.StockRepository
import com.knowapp.android.data.repository.StockResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DocumentsUiState(
    val loading: Boolean = true,
    val documents: List<DocumentOut> = emptyList(),
    val group: String? = null,
    val query: String = "",
    val itemId: Int? = null,
    val itemName: String? = null,
    val error: String? = null,
    val message: String? = null,
)

class DocumentsViewModel(
    private val repository: DocumentsRepository,
    private val stockRepository: StockRepository,
    sessionStore: SessionStore,
    itemId: Int?,
) : ViewModel() {
    val userId: Int? = sessionStore.session.value?.userId
    val isAdmin: Boolean = sessionStore.session.value?.role == "institution_admin"

    private val _state = MutableStateFlow(DocumentsUiState(itemId = itemId))
    val state: StateFlow<DocumentsUiState> = _state

    init {
        refresh()
        if (itemId != null) {
            viewModelScope.launch {
                val result = stockRepository.list()
                if (result is StockResult.Success) _state.value = _state.value.copy(itemName = result.items.firstOrNull { it.id == itemId }?.name)
            }
        }
    }

    fun clearItem() {
        _state.value = _state.value.copy(itemId = null, itemName = null)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            repository.list(_state.value.group, _state.value.query, _state.value.itemId)
                .onSuccess { _state.value = _state.value.copy(loading = false, documents = it) }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.message) }
        }
    }

    fun setGroup(group: String?) {
        _state.value = _state.value.copy(group = group)
        refresh()
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
        refresh()
    }

    fun delete(doc: DocumentOut) {
        viewModelScope.launch {
            repository.delete(doc.id)
                .onSuccess {
                    _state.value = _state.value.copy(message = "Document deleted.")
                    refresh()
                }
                .onFailure { _state.value = _state.value.copy(message = it.message ?: "Couldn't delete the document.") }
        }
    }

    fun messageShown() {
        _state.value = _state.value.copy(message = null)
    }
}
