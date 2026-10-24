package com.knowapp.android.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knowapp.android.data.local.PhotoStore
import com.knowapp.android.data.model.resolveMediaUrl
import com.knowapp.android.data.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProfileUiState(
    val name: String = "",
    val bio: String = "",
    val savedBio: String = "",
    val username: String = "",
    val email: String? = null,
    val role: String = "",
    val staffType: String? = null,
    val institutionName: String? = null,
    val memberSince: String? = null,
    val avatarUrl: String? = null,
    val newPhoto: Uri? = null,
    val removePhoto: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
)

class ProfileViewModel(private val repository: ProfileRepository, private val photos: PhotoStore) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state

    init {
        viewModelScope.launch {
            repository.me().onSuccess {
                _state.value = _state.value.copy(
                    name = it.fullName,
                    bio = it.bio ?: "",
                    savedBio = (it.bio ?: "").trim(),
                    username = it.username,
                    email = it.email,
                    role = it.role,
                    staffType = it.staffType,
                    institutionName = it.institutionName,
                    memberSince = it.createdAt,
                    avatarUrl = resolveMediaUrl(it.avatarPath),
                )
            }
        }
    }

    fun onBioChange(text: String) {
        if (text.length <= 300) _state.value = _state.value.copy(bio = text, message = null)
    }

    fun onPhotoPicked(uri: Uri) {
        _state.value = _state.value.copy(newPhoto = uri, removePhoto = false, message = null)
    }

    fun removePhoto() {
        _state.value = _state.value.copy(newPhoto = null, removePhoto = true, message = null)
    }

    fun save() {
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, message = null)
            val s = _state.value
            val path = s.newPhoto?.let { withContext(Dispatchers.IO) { photos.import(it) } }
            repository.update(s.bio.trim(), path, s.removePhoto && path == null)
                .onSuccess {
                    _state.value = _state.value.copy(
                        saving = false,
                        bio = it.bio ?: "",
                        savedBio = (it.bio ?: "").trim(),
                        avatarUrl = resolveMediaUrl(it.avatarPath),
                        newPhoto = null,
                        removePhoto = false,
                        message = "Profile saved.",
                    )
                }
                .onFailure { _state.value = _state.value.copy(saving = false, message = it.message ?: "Couldn't save your profile.") }
        }
    }
}
