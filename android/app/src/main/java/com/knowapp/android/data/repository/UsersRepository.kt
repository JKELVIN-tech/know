package com.knowapp.android.data.repository

import com.knowapp.android.data.model.UserOut
import com.knowapp.android.data.network.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class UsersResult {
    data class Success(val users: List<UserOut>) : UsersResult()
    data class Failure(val message: String) : UsersResult()
}

sealed class UserActionResult {
    data class Success(val user: UserOut) : UserActionResult()
    data class Failure(val message: String) : UserActionResult()
}

class UsersRepository(private val api: ApiService) {
    suspend fun list(): UsersResult = withContext(Dispatchers.IO) {
        try {
            val response = api.listUsers()
            if (response.isSuccessful) UsersResult.Success(response.body() ?: emptyList())
            else UsersResult.Failure("Couldn't load accounts (code ${response.code()}).")
        } catch (e: java.io.IOException) {
            UsersResult.Failure("Couldn't reach the server. Check your connection and try again.")
        }
    }

    suspend fun create(
        username: String,
        fullName: String,
        password: String,
        role: String,
        staffType: String?,
        institutionId: Int?,
    ): UserActionResult = withContext(Dispatchers.IO) {
        try {
            val response = api.createUser(
                mapOf(
                    "username" to username,
                    "full_name" to fullName,
                    "password" to password,
                    "role" to role,
                    "staff_type" to staffType,
                    "institution_id" to institutionId?.toString(),
                ),
            )
            if (response.isSuccessful && response.body() != null) UserActionResult.Success(response.body()!!)
            else UserActionResult.Failure(errorDetail(response.errorBody()?.string(), response.code()))
        } catch (e: java.io.IOException) {
            UserActionResult.Failure("Couldn't reach the server. Check your connection and try again.")
        }
    }

    suspend fun setActive(userId: Int, active: Boolean): UserActionResult = withContext(Dispatchers.IO) {
        try {
            val response = if (active) api.reactivateUser(userId) else api.deactivateUser(userId)
            if (response.isSuccessful && response.body() != null) UserActionResult.Success(response.body()!!)
            else UserActionResult.Failure(errorDetail(response.errorBody()?.string(), response.code()))
        } catch (e: java.io.IOException) {
            UserActionResult.Failure("Couldn't reach the server. Check your connection and try again.")
        }
    }

    private fun errorFor(code: Int): String = when (code) {
        409 -> "That username is already taken."
        403 -> "You don't have permission to do that."
        422 -> "Check the fields — password needs 8+ characters, username 3+."
        else -> "Something went wrong (code $code). Please try again."
    }
}
