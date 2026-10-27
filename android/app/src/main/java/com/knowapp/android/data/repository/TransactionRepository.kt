package com.knowapp.android.data.repository

import com.google.gson.JsonParser
import com.knowapp.android.data.SessionStore
import com.knowapp.android.data.local.OfflineStore
import com.knowapp.android.data.local.PhotoStore
import com.knowapp.android.data.model.OpeningBalancesOut
import com.knowapp.android.data.model.PendingTransaction
import com.knowapp.android.data.model.ReferenceCheckOut
import com.knowapp.android.data.model.TransactionOut
import com.knowapp.android.data.network.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import com.knowapp.android.data.DataVersion

sealed class TransactionsResult {
    data class Success(val transactions: List<TransactionOut>) : TransactionsResult()
    data class Failure(val message: String) : TransactionsResult()
}

data class NewTransaction(
    val type: String,
    val method: String,
    val categoryType: String,
    val category: String?,
    val description: String?,
    val amount: String,
    val stockItemId: Int?,
    val quantity: String?,
    val mpesaCode: String?,
    val mpesaPayerName: String?,
    val imagePath: String? = null,
    val batchId: String? = null,
)

// One line of a sale or purchase made of several items
data class SaleLine(
    val categoryType: String,
    val category: String?,
    val stockItemId: Int?,
    val quantity: String?,
    val amount: String,
)

// Several items paid for together: one receipt, one payment
data class NewSale(
    val type: String,
    val method: String,
    val description: String?,
    val mpesaCode: String?,
    val mpesaPayerName: String?,
    val lines: List<SaleLine>,
    val batchId: String,
)

sealed class RecordResult {
    object Saved : RecordResult()
    object Queued : RecordResult()
    data class Rejected(val message: String) : RecordResult()
}

data class HomeData(
    val transactions: List<TransactionOut>,
    val pending: List<PendingTransaction>,
    val opening: OpeningBalancesOut,
)

fun errorDetail(body: String?, code: Int): String {
    if (!body.isNullOrBlank()) {
        try {
            val detail = JsonParser.parseString(body).asJsonObject.get("detail")
            if (detail != null && detail.isJsonPrimitive) return detail.asString
            if (detail != null && detail.isJsonArray && detail.asJsonArray.size() > 0) {
                val first = detail.asJsonArray[0].asJsonObject
                val field = first.get("loc")?.asJsonArray?.lastOrNull()?.asString
                val label = when (field) {
                    "password", "new_password" -> "Password"
                    "username" -> "Username"
                    "full_name" -> "Name"
                    "institution_id" -> "Institution"
                    "role" -> "Role"
                    else -> field?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
                }
                val message = first.get("msg")?.asString?.removePrefix("Value error, ") ?: "isn't valid"
                return if (label != null) "$label: $message" else message
            }
        } catch (e: Exception) {
            // fall through to the generic message
        }
    }
    return "The server rejected this (code $code)."
}

private fun NewTransaction.toFields(): Map<String, String> = buildMap {
    put("type", type)
    put("method", method)
    put("category_type", categoryType)
    put("amount", amount)
    category?.takeIf { it.isNotBlank() }?.let { put("category", it) }
    description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
    stockItemId?.let { put("stock_item_id", it.toString()) }
    quantity?.takeIf { it.isNotBlank() }?.let { put("quantity", it) }
    mpesaCode?.takeIf { it.isNotBlank() }?.let { put("mpesa_code", it) }
    mpesaPayerName?.takeIf { it.isNotBlank() }?.let { put("mpesa_payer_name", it) }
    batchId?.takeIf { it.isNotBlank() }?.let { put("batch_id", it) }
}

private fun PendingTransaction.toFields(): Map<String, String> = NewTransaction(
    type, method, categoryType, category, description, amount, stockItemId, quantity, mpesaCode, mpesaPayerName, batchId = batchId,
).toFields()

class TransactionRepository(
    private val api: ApiService,
    private val store: OfflineStore,
    private val sessionStore: SessionStore,
    private val photos: PhotoStore,
    private val isOnline: () -> Boolean = { true },
) {
    private val pendingLock = Mutex()

    // What the server created for the last save, so a receipt can show real numbers and times
    @Volatile var lastCreated: List<TransactionOut> = emptyList()

    private suspend fun send(fields: Map<String, String>, imagePath: String?): Response<TransactionOut> {
        val parts = fields.mapValues { it.value.toRequestBody("text/plain".toMediaType()) }
        val image = imagePath?.let { File(it) }?.takeIf { it.exists() }?.let {
            MultipartBody.Part.createFormData("image", it.name, it.asRequestBody("image/jpeg".toMediaType()))
        }
        return api.createTransactionWithPhoto(parts, image)
    }

    private fun userId(): Int? = sessionStore.session.value?.userId

    suspend fun list(institutionId: Int? = null, staffId: Int? = null): TransactionsResult = withContext(Dispatchers.IO) {
        try {
            val response = api.listTransactions(institutionId = institutionId, staffId = staffId)
            if (response.isSuccessful) {
                TransactionsResult.Success(response.body() ?: emptyList())
            } else {
                TransactionsResult.Failure("Couldn't load transactions (code ${response.code()}).")
            }
        } catch (e: IOException) {
            TransactionsResult.Failure("Couldn't reach the server. Check your connection and try again.")
        }
    }

    // Whatever is on the phone right now, so screens can paint before the network answers
    suspend fun cachedHome(): HomeData = withContext(Dispatchers.IO) {
        val uid = userId() ?: return@withContext HomeData(emptyList(), emptyList(), OpeningBalancesOut("0", "0", "0", false))
        HomeData(store.transactions(uid) ?: emptyList(), store.pending(uid), store.opening(uid) ?: OpeningBalancesOut("0", "0", "0", false))
    }

    suspend fun loadHome(): HomeData = withContext(Dispatchers.IO) {
        val uid = userId() ?: return@withContext HomeData(emptyList(), emptyList(), OpeningBalancesOut("0", "0", "0", false))
        var transactions = store.transactions(uid) ?: emptyList()
        var opening = store.opening(uid) ?: OpeningBalancesOut("0", "0", "0", false)
        // Offline: skip the network entirely so the screen opens instantly from the cached copy above
        if (isOnline()) {
            try {
                val txResponse = api.listTransactions()
                if (txResponse.isSuccessful) {
                    transactions = txResponse.body() ?: emptyList()
                    store.saveTransactions(uid, transactions)
                }
                val openingResponse = api.getOpeningBalances()
                if (openingResponse.isSuccessful) {
                    opening = openingResponse.body() ?: opening
                    store.saveOpening(uid, opening)
                }
            } catch (e: IOException) {
                // the connection dropped part way: the cached copy is what the screen shows
            }
        }
        HomeData(transactions, store.pending(uid), opening)
    }

    // Was this M-Pesa code or bank reference already recorded? Checks the phone first, then the server when online
    suspend fun checkReference(code: String): ReferenceCheckOut? = withContext(Dispatchers.IO) {
        val clean = code.trim().uppercase()
        if (clean.length < 6) return@withContext null
        val uid = userId()
        if (uid != null) {
            store.pending(uid).firstOrNull { it.mpesaCode?.trim()?.uppercase() == clean && !it.failed }?.let {
                return@withContext ReferenceCheckOut(exists = true, mine = true, date = it.createdAt, amount = it.amount)
            }
            store.transactions(uid)?.firstOrNull { it.mpesaCode?.trim()?.uppercase() == clean }?.let {
                return@withContext ReferenceCheckOut(exists = true, mine = true, date = it.transactionDate, amount = it.amount)
            }
        }
        if (!isOnline()) return@withContext null
        try {
            val response = api.checkReference(clean)
            if (response.isSuccessful) response.body()?.takeIf { it.exists } else null
        } catch (e: IOException) {
            null
        }
    }

    // Editing a saved entry needs the server (it changes totals and stock), so it is never queued offline
    suspend fun update(id: Int, fields: Map<String, String>): RecordResult = withContext(Dispatchers.IO) {
        try {
            val response = api.updateTransaction(id, fields)
            if (response.isSuccessful) {
                DataVersion.bump()
                RecordResult.Saved
            } else {
                RecordResult.Rejected(errorDetail(response.errorBody()?.string(), response.code()))
            }
        } catch (e: java.io.IOException) {
            RecordResult.Rejected("You are offline. Connect to the internet to edit a saved entry.")
        }
    }

    // Online, every item is saved together or not at all; offline, each item is kept on the phone with the same receipt id
    suspend fun recordSale(sale: NewSale, photoPath: String?): RecordResult = withContext(Dispatchers.IO) {
        val uid = userId() ?: return@withContext RecordResult.Rejected("You are signed out. Please sign in again.")
        suspend fun queueAll(): RecordResult {
            sale.lines.forEachIndexed { index, line ->
                enqueue(uid, NewTransaction(sale.type, sale.method, line.categoryType, line.category, sale.description, line.amount, line.stockItemId, line.quantity, sale.mpesaCode, sale.mpesaPayerName, if (index == 0) photoPath else null, sale.batchId))
            }
            DataVersion.bump()
            return RecordResult.Queued
        }
        if (!isOnline()) return@withContext queueAll()
        val body = mapOf(
            "batch_id" to sale.batchId,
            "type" to sale.type,
            "method" to sale.method,
            "description" to sale.description,
            "mpesa_code" to sale.mpesaCode,
            "mpesa_payer_name" to sale.mpesaPayerName,
            "lines" to sale.lines.map { mapOf("category_type" to it.categoryType, "category" to it.category, "stock_item_id" to it.stockItemId, "quantity" to it.quantity, "amount" to it.amount) },
        )
        val payload = com.google.gson.Gson().toJson(body).toRequestBody("application/json".toMediaType())
        val image = photoPath?.let { File(it) }?.takeIf { it.exists() }?.let {
            MultipartBody.Part.createFormData("image", it.name, it.asRequestBody("image/jpeg".toMediaType()))
        }
        try {
            val response = api.createBatch(payload, image)
            when {
                response.code() == 401 -> queueAll()
                response.isSuccessful -> {
                    photos.delete(photoPath)
                    lastCreated = response.body() ?: emptyList()
                    DataVersion.bump()
                    RecordResult.Saved
                }
                else -> RecordResult.Rejected(errorDetail(response.errorBody()?.string(), response.code()))
            }
        } catch (e: IOException) {
            // Nothing is queued here: the same receipt id makes pressing Save again safe
            RecordResult.Rejected("Could not reach the server. Your items are still here. Check your connection and press Save again.")
        }
    }

    suspend fun record(draft: NewTransaction): RecordResult = recordInner(draft).also { if (it !is RecordResult.Rejected) DataVersion.bump() }

    private suspend fun recordInner(draft: NewTransaction): RecordResult = withContext(Dispatchers.IO) {
        val uid = userId() ?: return@withContext RecordResult.Rejected("You are signed out. Please sign in again.")
        // No connection: keep it on the phone straight away instead of waiting for a timeout
        if (!isOnline()) return@withContext enqueue(uid, draft)
        try {
            val response = send(draft.toFields(), draft.imagePath)
            if (response.code() == 401) return@withContext enqueue(uid, draft)
            photos.delete(draft.imagePath)
            if (response.isSuccessful) {
                lastCreated = listOfNotNull(response.body())
                RecordResult.Saved
            } else {
                RecordResult.Rejected(errorDetail(response.errorBody()?.string(), response.code()))
            }
        } catch (e: IOException) {
            enqueue(uid, draft)
        }
    }

    private suspend fun enqueue(uid: Int, draft: NewTransaction): RecordResult {
            pendingLock.withLock {
                val queued = PendingTransaction(
                    localId = UUID.randomUUID().toString(),
                    type = draft.type,
                    method = draft.method,
                    categoryType = draft.categoryType,
                    category = draft.category,
                    description = draft.description,
                    amount = draft.amount,
                    stockItemId = draft.stockItemId,
                    quantity = draft.quantity,
                    mpesaCode = draft.mpesaCode,
                    mpesaPayerName = draft.mpesaPayerName,
                    createdAt = Instant.now().toString(),
                    imagePath = draft.imagePath,
                    batchId = draft.batchId,
                )
                store.savePending(uid, store.pending(uid) + queued)
            }
        return RecordResult.Queued
    }

    // Sends queued entries in order; stops at the first network failure and marks server-rejected ones as failed
    suspend fun syncPending(): Int = syncInner().also { if (it > 0) DataVersion.bump() }

    private suspend fun syncInner(): Int = withContext(Dispatchers.IO) {
        val uid = userId() ?: return@withContext 0
        pendingLock.withLock {
            val queue = store.pending(uid)
            if (queue.isEmpty()) return@withLock 0
            val remaining = mutableListOf<PendingTransaction>()
            var synced = 0
            var stopped = false
            for (item in queue) {
                if (stopped || item.failed) {
                    remaining += item
                    continue
                }
                try {
                    val response = send(item.toFields(), item.imagePath)
                    if (response.isSuccessful) {
                        photos.delete(item.imagePath)
                        synced++
                    } else if (response.code() == 401) {
                        remaining += item
                        stopped = true
                    } else {
                        remaining += item.copy(failed = true, error = errorDetail(response.errorBody()?.string(), response.code()))
                    }
                } catch (e: IOException) {
                    remaining += item
                    stopped = true
                }
            }
            store.savePending(uid, remaining)
            synced
        }
    }

    suspend fun discardPending(localId: String) = withContext(Dispatchers.IO) {
        val uid = userId() ?: return@withContext
        pendingLock.withLock {
            val queue = store.pending(uid)
            queue.firstOrNull { it.localId == localId }?.let { photos.delete(it.imagePath) }
            store.savePending(uid, queue.filter { it.localId != localId })
        }
    }

    suspend fun setOpening(cash: Double, mpesa: Double, bank: Double): Result<OpeningBalancesOut> = withContext(Dispatchers.IO) {
        val uid = userId() ?: return@withContext Result.failure(Exception("You are signed out."))
        try {
            val response = api.setOpeningBalances(mapOf("cash" to cash, "mpesa" to mpesa, "bank" to bank))
            val body = response.body()
            if (response.isSuccessful && body != null) {
                store.saveOpening(uid, body)
                Result.success(body)
            } else {
                Result.failure(Exception(errorDetail(response.errorBody()?.string(), response.code())))
            }
        } catch (e: IOException) {
            Result.failure(Exception("You need a connection to save starting balances."))
        }
    }
}
