package com.knowapp.android.data.repository

import com.knowapp.android.data.local.PhotoStore
import com.knowapp.android.data.model.DocumentOut
import com.knowapp.android.data.network.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

class DocumentsRepository(private val api: ApiService, private val photos: PhotoStore) {
    private val offline = "Couldn't reach the server. Check your connection and try again."

    suspend fun list(group: String?, query: String?, stockItemId: Int? = null): Result<List<DocumentOut>> = withContext(Dispatchers.IO) {
        try {
            val response = api.listDocuments(group = group, q = query?.takeIf { it.isNotBlank() }, stockItemId = stockItemId)
            val body = response.body()
            if (response.isSuccessful && body != null) Result.success(body)
            else Result.failure(Exception(errorDetail(response.errorBody()?.string(), response.code())))
        } catch (e: IOException) {
            Result.failure(Exception(offline))
        }
    }

    suspend fun create(
        docType: String,
        referenceNo: String?,
        date: String,
        party: String?,
        stockItemId: Int?,
        quantity: String?,
        amount: String?,
        notes: String?,
        transactionId: Int?,
        imagePath: String?,
    ): Result<DocumentOut> = withContext(Dispatchers.IO) {
        try {
            val fields = buildMap<String, String> {
                put("doc_type", docType)
                put("document_date", date)
                referenceNo?.takeIf { it.isNotBlank() }?.let { put("reference_no", it) }
                party?.takeIf { it.isNotBlank() }?.let { put("party_name", it) }
                stockItemId?.let { put("stock_item_id", it.toString()) }
                quantity?.takeIf { it.isNotBlank() }?.let { put("quantity", it) }
                amount?.takeIf { it.isNotBlank() }?.let { put("amount", it) }
                notes?.takeIf { it.isNotBlank() }?.let { put("notes", it) }
                transactionId?.let { put("transaction_id", it.toString()) }
            }.mapValues { it.value.toRequestBody("text/plain".toMediaType()) }
            val image = imagePath?.let { File(it) }?.takeIf { it.exists() }?.let {
                MultipartBody.Part.createFormData("image", it.name, it.asRequestBody("image/jpeg".toMediaType()))
            }
            val response = api.createDocument(fields, image)
            photos.delete(imagePath)
            val body = response.body()
            if (response.isSuccessful && body != null) Result.success(body)
            else Result.failure(Exception(errorDetail(response.errorBody()?.string(), response.code())))
        } catch (e: IOException) {
            Result.failure(Exception(offline))
        }
    }

    suspend fun delete(id: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = api.deleteDocument(id)
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception(errorDetail(response.errorBody()?.string(), response.code())))
        } catch (e: IOException) {
            Result.failure(Exception(offline))
        }
    }
}
