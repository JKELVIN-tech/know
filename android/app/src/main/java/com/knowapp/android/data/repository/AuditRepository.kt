package com.knowapp.android.data.repository

import com.knowapp.android.data.model.AuditOut
import com.knowapp.android.data.model.TransactionOut
import com.knowapp.android.data.network.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class AuditsResult {
    data class Success(val audits: List<AuditOut>) : AuditsResult()
    data class Failure(val message: String) : AuditsResult()
}

sealed class AuditActionResult {
    data class Success(val audit: AuditOut) : AuditActionResult()
    data class Failure(val message: String) : AuditActionResult()
}

sealed class AuditTransactionsResult {
    data class Success(val transactions: List<TransactionOut>) : AuditTransactionsResult()
    data class Failure(val message: String) : AuditTransactionsResult()
}

class AuditRepository(private val api: ApiService) {
    suspend fun list(institutionId: Int? = null): AuditsResult = withContext(Dispatchers.IO) {
        try {
            val response = api.listAudits(institutionId)
            if (response.isSuccessful) AuditsResult.Success(response.body() ?: emptyList())
            else AuditsResult.Failure("Couldn't load audits (code ${response.code()}).")
        } catch (e: java.io.IOException) {
            AuditsResult.Failure("Couldn't reach the server. Check your connection and try again.")
        }
    }

    suspend fun create(title: String, periodStartIso: String, periodEndIso: String, summary: String?): AuditActionResult = withContext(Dispatchers.IO) {
        try {
            val response = api.createAudit(
                mapOf("title" to title, "period_start" to periodStartIso, "period_end" to periodEndIso, "summary" to summary),
            )
            if (response.isSuccessful && response.body() != null) AuditActionResult.Success(response.body()!!)
            else AuditActionResult.Failure(errorFor(response.code()))
        } catch (e: java.io.IOException) {
            AuditActionResult.Failure("Couldn't reach the server. Check your connection and try again.")
        }
    }

    suspend fun finalize(auditId: Int): AuditActionResult = withContext(Dispatchers.IO) {
        try {
            val response = api.finalizeAudit(auditId)
            if (response.isSuccessful && response.body() != null) AuditActionResult.Success(response.body()!!)
            else AuditActionResult.Failure(errorFor(response.code()))
        } catch (e: java.io.IOException) {
            AuditActionResult.Failure("Couldn't reach the server. Check your connection and try again.")
        }
    }

    suspend fun deleteDraft(auditId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = api.deleteAudit(auditId)
            if (response.isSuccessful) Result.success(Unit) else Result.failure(Exception(errorFor(response.code())))
        } catch (e: java.io.IOException) {
            Result.failure(Exception("Couldn't reach the server. Check your connection and try again."))
        }
    }

    suspend fun transactionsFor(auditId: Int): AuditTransactionsResult = withContext(Dispatchers.IO) {
        try {
            val response = api.getAuditTransactions(auditId)
            if (response.isSuccessful) AuditTransactionsResult.Success(response.body() ?: emptyList())
            else AuditTransactionsResult.Failure("Couldn't load this audit's transactions (code ${response.code()}).")
        } catch (e: java.io.IOException) {
            AuditTransactionsResult.Failure("Couldn't reach the server. Check your connection and try again.")
        }
    }

    private fun errorFor(code: Int): String = when (code) {
        400 -> "Check the dates and try again — the period end must be on or after the start."
        403 -> "You don't have permission to do that."
        404 -> "That audit couldn't be found."
        else -> "Something went wrong (code $code). Please try again."
    }
}
