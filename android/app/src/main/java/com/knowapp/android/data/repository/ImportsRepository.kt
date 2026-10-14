package com.knowapp.android.data.repository

import com.knowapp.android.data.model.ApproveBody
import com.knowapp.android.data.model.ImportRequestBody
import com.knowapp.android.data.model.ImportResultOut
import com.knowapp.android.data.model.MessageImportOut
import com.knowapp.android.data.network.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException

class ImportsRepository(private val api: ApiService) {
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

    suspend fun scan(messages: List<String>): Result<ImportResultOut> = call { api.scanImports(ImportRequestBody(messages)) }

    suspend fun list(): Result<List<MessageImportOut>> = call { api.listImports() }

    suspend fun approve(id: Int, body: ApproveBody): Result<MessageImportOut> = call { api.approveImport(id, body) }

    suspend fun ignore(id: Int): Result<MessageImportOut> = call { api.ignoreImport(id) }
}
