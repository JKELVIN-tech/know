package com.knowapp.android.ui.institutions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knowapp.android.data.model.InstitutionStatsOut
import com.knowapp.android.data.model.UserOut
import com.knowapp.android.data.repository.AdminRepository
import com.knowapp.android.data.repository.InstitutionActionResult
import com.knowapp.android.data.repository.InstitutionsRepository
import com.knowapp.android.data.repository.UserActionResult
import com.knowapp.android.data.repository.UsersRepository
import com.knowapp.android.data.repository.UsersResult
import com.knowapp.android.ui.components.daysSince
import kotlinx.coroutines.launch

enum class InstitutionSort(val label: String) {
    NAME("Name A to Z"),
    NEWEST("Newest first"),
    ACTIVE("Most active"),
    QUIET("Quiet first"),
}

data class InstitutionsUiState(
    val isLoading: Boolean = true,
    val items: List<InstitutionStatsOut> = emptyList(),
    val users: List<UserOut> = emptyList(),
    val errorMessage: String? = null,
    val query: String = "",
    val sort: InstitutionSort = InstitutionSort.NAME,
    val selectedId: Int? = null,
    val isSubmitting: Boolean = false,
    val submitError: String? = null,
    val message: String? = null,
) {
    val selected: InstitutionStatsOut? get() = items.firstOrNull { it.id == selectedId }

    val visible: List<InstitutionStatsOut>
        get() {
            val q = query.trim()
            val filtered = items.filter { q.isEmpty() || it.name.contains(q, true) || it.type.contains(q, true) || (it.region ?: "").contains(q, true) }
            return when (sort) {
                InstitutionSort.NAME -> filtered.sortedBy { it.name.lowercase() }
                InstitutionSort.NEWEST -> filtered.sortedByDescending { it.createdAt }
                InstitutionSort.ACTIVE -> filtered.sortedByDescending { it.entries30d }
                InstitutionSort.QUIET -> filtered.sortedByDescending { daysSince(it.lastActivityAt) ?: Long.MAX_VALUE }
            }
        }
}

class InstitutionsViewModel(
    private val adminRepository: AdminRepository,
    private val institutionsRepository: InstitutionsRepository,
    private val usersRepository: UsersRepository,
) : ViewModel() {
    var uiState by mutableStateOf(InstitutionsUiState())
        private set

    init {
        refresh()
    }

    fun refresh() {
        uiState = uiState.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            adminRepository.institutions()
                .onSuccess { uiState = uiState.copy(isLoading = false, items = it) }
                .onFailure { uiState = uiState.copy(isLoading = false, errorMessage = it.message) }
            when (val result = usersRepository.list()) {
                is UsersResult.Success -> uiState = uiState.copy(users = result.users)
                is UsersResult.Failure -> Unit
            }
        }
    }

    fun setQuery(q: String) { uiState = uiState.copy(query = q) }
    fun setSort(s: InstitutionSort) { uiState = uiState.copy(sort = s) }
    fun select(id: Int?) { uiState = uiState.copy(selectedId = id) }
    fun clearSubmitError() { uiState = uiState.copy(submitError = null) }
    fun messageShown() { uiState = uiState.copy(message = null) }

    fun create(name: String, type: String, address: String?, region: String?, partnerCode: String?, onDone: (Boolean) -> Unit) {
        uiState = uiState.copy(isSubmitting = true, submitError = null)
        viewModelScope.launch {
            when (val result = institutionsRepository.create(name, type, address, region, partnerCode)) {
                is InstitutionActionResult.Success -> {
                    uiState = uiState.copy(isSubmitting = false, message = "${result.institution.name} added.")
                    refresh()
                    onDone(true)
                }
                is InstitutionActionResult.Failure -> {
                    uiState = uiState.copy(isSubmitting = false, submitError = result.message)
                    onDone(false)
                }
            }
        }
    }

    fun createAccount(
        username: String,
        fullName: String,
        password: String,
        role: String,
        staffType: String?,
        institutionId: Int?,
        email: String,
        onDone: (Boolean) -> Unit,
    ) {
        uiState = uiState.copy(isSubmitting = true, submitError = null)
        viewModelScope.launch {
            when (val result = usersRepository.create(username, fullName, password, role, staffType, institutionId, email)) {
                is UserActionResult.Success -> {
                    uiState = uiState.copy(isSubmitting = false)
                    refresh()
                    onDone(true)
                }
                is UserActionResult.Failure -> {
                    uiState = uiState.copy(isSubmitting = false, submitError = result.message)
                    onDone(false)
                }
            }
        }
    }
}
