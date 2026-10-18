package com.knowapp.android.data.repository

import com.knowapp.android.data.DataVersion
import com.knowapp.android.data.model.CostBody
import com.knowapp.android.data.model.CostItemOut
import com.knowapp.android.data.model.ProfitDayOut
import com.knowapp.android.data.network.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException

class ProfitRepository(private val api: ApiService) {
    private val offline = "Couldn't reach the server. Check your connection and try again."

    private suspend fun <T> call(block: suspend () -> Response<T>): Result<T> = withContext(Dispatchers.IO) {
        try {
            val response = block()
            val body = response.body()
            if (response.isSuccessful && body != null) Result.success(body)
            else Result.failure(Exception(errorDetail(response.errorBody()?.string(), response.code())))
        } catch (e: IOException) {
            Result.failure(Exception(offline))
        }
    }

    suspend fun daily(day: String, branchId: Int?): Result<ProfitDayOut> = call { api.profitDaily(day, branchId) }

    suspend fun costs(): Result<List<CostItemOut>> = call { api.profitCosts() }

    suspend fun setCost(itemId: Int, cost: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = api.setCost(itemId, CostBody(cost))
            if (response.isSuccessful) { DataVersion.bump(); Result.success(Unit) }
            else Result.failure(Exception(errorDetail(response.errorBody()?.string(), response.code())))
        } catch (e: IOException) {
            Result.failure(Exception(offline))
        }
    }
}
