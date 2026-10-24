package com.knowapp.android.data.repository

import com.knowapp.android.data.model.BillingStatusOut
import com.knowapp.android.data.network.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class BillingRepository(private val api: ApiService) {
    suspend fun status(): Result<BillingStatusOut> = withContext(Dispatchers.IO) {
        try {
            val response = api.billingStatus()
            val body = response.body()
            if (response.isSuccessful && body != null) Result.success(body)
            else Result.failure(Exception(errorDetail(response.errorBody()?.string(), response.code())))
        } catch (e: IOException) {
            Result.failure(Exception("Couldn't reach the server. Check your connection and try again."))
        }
    }
}
