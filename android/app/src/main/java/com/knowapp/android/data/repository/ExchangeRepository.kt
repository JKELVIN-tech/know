package com.knowapp.android.data.repository

import com.knowapp.android.data.model.BranchOut
import com.knowapp.android.data.model.TransferBody
import com.knowapp.android.data.model.TransferOut
import com.knowapp.android.data.network.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException
import com.knowapp.android.data.DataVersion

class ExchangeRepository(private val api: ApiService) {
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

    suspend fun branches(): Result<List<BranchOut>> = call { api.exchangeBranches() }

    suspend fun transfers(): Result<List<TransferOut>> = call { api.exchangeTransfers() }

    suspend fun send(body: TransferBody): Result<TransferOut> = call { api.sendTransfer(body) }.also { if (it.isSuccess) DataVersion.bump() }

    suspend fun act(id: Int, action: String): Result<TransferOut> = call {
        when (action) {
            "receive" -> api.receiveTransfer(id)
            "reject" -> api.rejectTransfer(id)
            else -> api.cancelTransfer(id)
        }
    }.also { if (it.isSuccess) DataVersion.bump() }
}
