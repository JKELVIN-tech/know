package com.knowapp.android.data.repository

import com.knowapp.android.data.model.JournalEntryOut
import com.knowapp.android.data.model.LedgerOut
import com.knowapp.android.data.model.SummaryOut
import com.knowapp.android.data.model.TrialBalanceOut
import com.knowapp.android.data.model.UserOut
import com.knowapp.android.data.network.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

data class BooksData(
    val summary: SummaryOut,
    val trialBalance: TrialBalanceOut,
    val journal: List<JournalEntryOut>,
)

// The sub admin's books: the journal, the trial balance and each account's ledger, collective or for one staff member
class BooksRepository(private val api: ApiService) {
    private val offline = "Couldn't reach the server. Check your connection."

    suspend fun staff(): Result<List<UserOut>> = withContext(Dispatchers.IO) {
        try {
            val response = api.listUsers()
            val body = response.body()
            if (response.isSuccessful && body != null) Result.success(body.filter { it.role == "staff" })
            else Result.failure(Exception(errorDetail(response.errorBody()?.string(), response.code())))
        } catch (e: IOException) {
            Result.failure(Exception(offline))
        }
    }

    suspend fun load(staffId: Int?, start: String?, end: String?): Result<BooksData> = withContext(Dispatchers.IO) {
        try {
            val summary = api.getSummary(staffId, start, end)
            // A trial balance is cumulative: everything up to the end of the period, not only the period itself
            val trial = api.getTrialBalance(staffId, null, end)
            val journal = api.getJournal(staffId, start, end)
            val s = summary.body()
            val t = trial.body()
            val j = journal.body()
            if (summary.isSuccessful && trial.isSuccessful && journal.isSuccessful && s != null && t != null && j != null) {
                Result.success(BooksData(s, t, j))
            } else {
                val failed = listOf(summary, trial, journal).firstOrNull { !it.isSuccessful }
                Result.failure(Exception(if (failed != null) errorDetail(failed.errorBody()?.string(), failed.code()) else "Couldn't load the books."))
            }
        } catch (e: IOException) {
            Result.failure(Exception(offline))
        }
    }

    suspend fun ledger(accountId: Int, staffId: Int?, end: String?): Result<LedgerOut> = withContext(Dispatchers.IO) {
        try {
            val response = api.getLedger(accountId, staffId, end)
            val body = response.body()
            if (response.isSuccessful && body != null) Result.success(body)
            else Result.failure(Exception(errorDetail(response.errorBody()?.string(), response.code())))
        } catch (e: IOException) {
            Result.failure(Exception(offline))
        }
    }
}
