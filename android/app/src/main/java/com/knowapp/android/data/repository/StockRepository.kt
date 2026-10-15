package com.knowapp.android.data.repository

import com.knowapp.android.data.model.ProductCategoryOut
import com.knowapp.android.data.model.StockItemOut
import com.knowapp.android.data.network.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

sealed class StockResult {
    data class Success(val items: List<StockItemOut>) : StockResult()
    data class Failure(val message: String) : StockResult()
}

class StockRepository(private val api: ApiService) {
    private val offline = "Couldn't reach the server. Check your connection and try again."

    suspend fun list(): StockResult = withContext(Dispatchers.IO) {
        try {
            val response = api.listStockItems()
            if (response.isSuccessful) {
                StockResult.Success(response.body() ?: emptyList())
            } else {
                StockResult.Failure(errorDetail(response.errorBody()?.string(), response.code()))
            }
        } catch (e: IOException) {
            StockResult.Failure(offline)
        }
    }

    suspend fun categories(): List<ProductCategoryOut> = withContext(Dispatchers.IO) {
        try {
            api.listProductCategories().body() ?: emptyList()
        } catch (e: IOException) {
            emptyList()
        }
    }

    suspend fun createCategory(name: String): Result<ProductCategoryOut> = withContext(Dispatchers.IO) {
        try {
            val response = api.createProductCategory(mapOf("name" to name))
            val body = response.body()
            if (response.isSuccessful && body != null) Result.success(body)
            else Result.failure(Exception(errorDetail(response.errorBody()?.string(), response.code())))
        } catch (e: IOException) {
            Result.failure(Exception(offline))
        }
    }

    suspend fun create(name: String, categoryId: Int, description: String?, unitPrice: Double?, quantity: Double, branchId: Int? = null): Result<StockItemOut> = withContext(Dispatchers.IO) {
        try {
            val body = mapOf<String, Any?>("name" to name, "category_id" to categoryId, "description" to description, "unit_price" to unitPrice, "quantity" to quantity, "branch_id" to branchId)
            val response = api.createStockItem(body)
            val item = response.body()
            if (response.isSuccessful && item != null) Result.success(item)
            else Result.failure(Exception(errorDetail(response.errorBody()?.string(), response.code())))
        } catch (e: IOException) {
            Result.failure(Exception(offline))
        }
    }

    suspend fun update(id: Int, name: String, categoryId: Int, description: String?, unitPrice: Double?): Result<StockItemOut> = withContext(Dispatchers.IO) {
        try {
            val body = mapOf<String, Any?>("name" to name, "category_id" to categoryId, "description" to description, "unit_price" to unitPrice)
            val response = api.updateStockItem(id, body)
            val item = response.body()
            if (response.isSuccessful && item != null) Result.success(item)
            else Result.failure(Exception(errorDetail(response.errorBody()?.string(), response.code())))
        } catch (e: IOException) {
            Result.failure(Exception(offline))
        }
    }

    suspend fun delete(id: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = api.deleteStockItem(id)
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception(errorDetail(response.errorBody()?.string(), response.code())))
        } catch (e: IOException) {
            Result.failure(Exception(offline))
        }
    }
}
