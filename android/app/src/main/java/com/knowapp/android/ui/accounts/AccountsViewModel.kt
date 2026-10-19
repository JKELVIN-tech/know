package com.knowapp.android.ui.accounts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knowapp.android.data.SessionStore
import com.knowapp.android.data.model.InstitutionOut
import com.knowapp.android.data.model.UserOut
import com.knowapp.android.data.repository.AdminRepository
import com.knowapp.android.data.repository.InstitutionsRepository
import com.knowapp.android.data.repository.InstitutionsResult
import com.knowapp.android.data.repository.UserActionResult
import com.knowapp.android.data.repository.UsersRepository
import com.knowapp.android.data.repository.UsersResult
import kotlinx.coroutines.launch

enum class RoleFilter(val label: String) { ALL("All roles"), ADMINS("Admins"), STAFF("Staff") }
enum class StatusFilter(val label: String) { ALL("Any status"), ACTIVE("Active"), INACTIVE("Deactivated") }

data class AccountsUiState(
    val isLoading: Boolean = true,
    val users: List<UserOut> = emptyList(),
    val institutions: List<InstitutionOut> = emptyList(),
    val isSuperAdmin: Boolean = false,
    val errorMessage: String? = null,
    val isSubmitting: Boolean = false,
    val submitError: String? = null,
    val query: String = "",
    val roleFilter: RoleFilter = RoleFilter.ALL,
    val statusFilter: StatusFilter = StatusFilter.ALL,
    val institutionFilter: Int? = null,
    val selectedId: Int? = null,
    val message: String? = null,
) {
    val selected: UserOut? get() = users.firstOrNull { it.id == selectedId }

    val visible: List<UserOut>
        get() = users.filter { u ->
            val q = query.trim()
            (q.isEmpty() || u.fullName.contains(q, true) || u.username.contains(q, true) || (u.institutionName ?: "").contains(q, true)) &&
                when (roleFilter) {
                    RoleFilter.ALL -> true
                    RoleFilter.ADMINS -> u.role != "staff"
                    RoleFilter.STAFF -> u.role == "staff"
                } &&
                when (statusFilter) {
                    StatusFilter.ALL -> true
                    StatusFilter.ACTIVE -> u.isActive
                    StatusFilter.INACTIVE -> !u.isActive
                } &&
                (institutionFilter == null || u.institutionId == institutionFilter)
        }.sortedBy { it.fullName.lowercase() }
}

class AccountsViewModel(
    private val usersRepository: UsersRepository,
    private val institutionsRepository: InstitutionsRepository,
    private val adminRepository: AdminRepository,
    sessionStore: SessionStore,
) : ViewModel() {
    var uiState by mutableStateOf(AccountsUiState(isSuperAdmin = sessionStore.session.value?.role == "super_admin"))
        private set

    init {
        refresh()
        if (uiState.isSuperAdmin) {
            viewModelScope.launch {
                when (val result = institutionsRepository.list()) {
                    is InstitutionsResult.Success -> uiState = uiState.copy(institutions = result.institutions)
                    is InstitutionsResult.Failure -> Unit
                }
            }
        }
    }

    fun refresh() {
        uiState = uiState.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            when (val result = usersRepository.list()) {
                is UsersResult.Success -> uiState = uiState.copy(isLoading = false, users = result.users)
                is UsersResult.Failure -> uiState = uiState.copy(isLoading = false, errorMessage = result.message)
            }
        }
    }

    fun setQuery(q: String) { uiState = uiState.copy(query = q) }
    fun setRoleFilter(f: RoleFilter) { uiState = uiState.copy(roleFilter = f) }
    fun setStatusFilter(f: StatusFilter) { uiState = uiState.copy(statusFilter = f) }
    fun setInstitutionFilter(id: Int?) { uiState = uiState.copy(institutionFilter = id) }
    fun select(id: Int?) { uiState = uiState.copy(selectedId = id) }
    fun messageShown() { uiState = uiState.copy(message = null) }

    fun create(
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

    fun clearSubmitError() { uiState = uiState.copy(submitError = null) }

    fun setActive(userId: Int, active: Boolean) {
        viewModelScope.launch {
            when (val result = usersRepository.setActive(userId, active)) {
                is UserActionResult.Success -> uiState = uiState.copy(message = if (active) "Account reactivated." else "Account deactivated.")
                is UserActionResult.Failure -> uiState = uiState.copy(message = result.message)
            }
            refresh()
        }
    }

    fun resetPassword(userId: Int, password: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            adminRepository.resetPassword(userId, password)
                .onSuccess { onDone(true) }
                .onFailure {
                    uiState = uiState.copy(message = it.message ?: "Couldn't reset the password.")
                    onDone(false)
                }
        }
    }

    fun rename(userId: Int, name: String) {
        viewModelScope.launch {
            adminRepository.rename(userId, name)
                .onSuccess { uiState = uiState.copy(message = "Name updated."); refresh() }
                .onFailure { uiState = uiState.copy(message = it.message ?: "Couldn't update the name.") }
        }
    }
}
