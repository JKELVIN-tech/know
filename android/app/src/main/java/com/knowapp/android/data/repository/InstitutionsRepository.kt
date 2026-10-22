package com.knowapp.android.data.repository

import com.knowapp.android.data.model.InstitutionOut
import com.knowapp.android.data.network.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class InstitutionsResult {
    data class Success(val institutions: List<InstitutionOut>) : InstitutionsResult()
    data class Failure(val message: String) : InstitutionsResult()
}

sealed class InstitutionActionResult {
    data class Success(val institution: InstitutionOut) : InstitutionActionResult()
    data class Failure(val message: String) : InstitutionActionResult()
}

class InstitutionsRepository(private val api: ApiService) {
    suspend fun list(): InstitutionsResult = withContext(Dispatchers.IO) {
        try {
            val response = api.listInstitutions()
            if (response.isSuccessful) InstitutionsResult.Success(response.body() ?: emptyList())
            else InstitutionsResult.Failure("Couldn't load institutions (code ${response.code()}).")
        } catch (e: java.io.IOException) {
            InstitutionsResult.Failure("Couldn't reach the server. Check your connection and try again.")
        }
    }

    suspend fun create(name: String, type: String, address: String?, region: String?, partnerCode: String? = null): InstitutionActionResult = withContext(Dispatchers.IO) {
        try {
            val response = api.createInstitution(mapOf("name" to name, "type" to type, "address" to address, "region" to region, "partner_code" to partnerCode))
            if (response.isSuccessful && response.body() != null) InstitutionActionResult.Success(response.body()!!)
            else InstitutionActionResult.Failure(
                if (response.code() == 422) errorDetail(response.errorBody()?.string(), 422) else "Something went wrong (code ${response.code()}).",
            )
        } catch (e: java.io.IOException) {
            InstitutionActionResult.Failure("Couldn't reach the server. Check your connection and try again.")
        }
    }
}
