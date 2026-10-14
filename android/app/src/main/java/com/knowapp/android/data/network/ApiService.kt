package com.knowapp.android.data.network

import com.knowapp.android.data.model.AuditOut
import com.knowapp.android.data.model.CategoryDetailOut
import com.knowapp.android.data.model.CategoryInsightOut
import com.knowapp.android.data.model.InstitutionOut
import com.knowapp.android.data.model.AdminOverviewOut
import com.knowapp.android.data.model.DocumentOut
import com.knowapp.android.data.model.InstitutionStatsOut
import com.knowapp.android.data.model.JournalEntryOut
import com.knowapp.android.data.model.LedgerOut
import com.knowapp.android.data.model.ReportOut
import com.knowapp.android.data.model.OpeningBalancesOut
import com.knowapp.android.data.model.PostCommentOut
import com.knowapp.android.data.model.ReferenceCheckOut
import com.knowapp.android.data.model.SummaryOut
import com.knowapp.android.data.model.TrialBalanceOut
import com.knowapp.android.data.model.PostOut
import com.knowapp.android.data.model.ProductCategoryOut
import com.knowapp.android.data.model.StockItemOut
import com.knowapp.android.data.model.TokenResponse
import com.knowapp.android.data.model.TransactionOut
import com.knowapp.android.data.model.UserOut
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.Part
import retrofit2.http.PartMap
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.PUT
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    // /api/auth/login expects application/x-www-form-urlencoded
    // (FastAPI's OAuth2PasswordRequestForm), not JSON.
    @FormUrlEncoded
    @POST("/api/auth/login")
    suspend fun login(@FieldMap fields: Map<String, String>): Response<TokenResponse>

    @POST("/api/auth/logout")
    suspend fun logout(): Response<Unit>

    @GET("/api/transactions")
    suspend fun listTransactions(
        @Query("institution_id") institutionId: Int? = null,
        @Query("category_type") categoryType: String? = null,
        @Query("staff_id") staffId: Int? = null,
    ): Response<List<TransactionOut>>

    @FormUrlEncoded
    @POST("/api/transactions")
    suspend fun createTransaction(@FieldMap fields: Map<String, String>): Response<TransactionOut>

    @Multipart
    @POST("/api/transactions")
    suspend fun createTransactionWithPhoto(
        @PartMap fields: Map<String, @JvmSuppressWildcards RequestBody>,
        @Part image: MultipartBody.Part?,
    ): Response<TransactionOut>

    @POST("/api/stock")
    suspend fun createStockItem(@Body body: Map<String, @JvmSuppressWildcards Any?>): Response<StockItemOut>

    @PATCH("/api/stock/{itemId}")
    suspend fun updateStockItem(@Path("itemId") itemId: Int, @Body body: Map<String, @JvmSuppressWildcards Any?>): Response<StockItemOut>

    @DELETE("/api/stock/{itemId}")
    suspend fun deleteStockItem(@Path("itemId") itemId: Int): Response<Unit>

    @Multipart
    @POST("/api/posts")
    suspend fun createPost(
        @PartMap fields: Map<String, @JvmSuppressWildcards RequestBody>,
        @Part image: MultipartBody.Part?,
    ): Response<PostOut>

    @GET("/api/posts/{postId}/comments")
    suspend fun listComments(@Path("postId") postId: Int): Response<List<PostCommentOut>>

    @POST("/api/posts/{postId}/comments")
    suspend fun addComment(@Path("postId") postId: Int, @Body body: Map<String, String>): Response<PostCommentOut>

    @DELETE("/api/posts/comments/{commentId}")
    suspend fun deleteComment(@Path("commentId") commentId: Int): Response<Unit>

    @DELETE("/api/posts/{postId}")
    suspend fun deletePost(@Path("postId") postId: Int): Response<Unit>

    @POST("/api/posts/{postId}/report")
    suspend fun reportPost(@Path("postId") postId: Int, @Body body: Map<String, String>): Response<Unit>

    @GET("/api/transactions/reference-check")
    suspend fun checkReference(@Query("code") code: String): Response<ReferenceCheckOut>

    @Multipart
    @POST("/api/documents")
    suspend fun createDocument(
        @PartMap fields: Map<String, @JvmSuppressWildcards RequestBody>,
        @Part image: MultipartBody.Part?,
    ): Response<DocumentOut>

    @GET("/api/documents")
    suspend fun listDocuments(
        @Query("group") group: String? = null,
        @Query("q") q: String? = null,
        @Query("stock_item_id") stockItemId: Int? = null,
    ): Response<List<DocumentOut>>

    @DELETE("/api/documents/{documentId}")
    suspend fun deleteDocument(@Path("documentId") documentId: Int): Response<Unit>

    @GET("/api/admin/overview")
    suspend fun adminOverview(): Response<AdminOverviewOut>

    @GET("/api/admin/institutions")
    suspend fun adminInstitutions(): Response<List<InstitutionStatsOut>>

    @GET("/api/moderation/reports")
    suspend fun listReports(@Query("status_filter") status: String? = null): Response<List<ReportOut>>

    @POST("/api/moderation/reports/{reportId}/resolve")
    suspend fun resolveReport(@Path("reportId") reportId: Int, @Query("action") action: String): Response<ResponseBody>

    @POST("/api/users/{userId}/reset-password")
    suspend fun resetPassword(@Path("userId") userId: Int, @Body body: Map<String, String>): Response<UserOut>

    @PATCH("/api/users/{userId}")
    suspend fun updateUser(@Path("userId") userId: Int, @Body body: Map<String, @JvmSuppressWildcards Any?>): Response<UserOut>

    @GET("/api/users/me")
    suspend fun getMe(): Response<UserOut>

    @Multipart
    @PATCH("/api/users/me/profile")
    suspend fun updateProfile(
        @PartMap fields: Map<String, @JvmSuppressWildcards RequestBody>,
        @Part avatar: MultipartBody.Part?,
    ): Response<UserOut>

    @GET("/api/accounting/opening-balances")
    suspend fun getOpeningBalances(): Response<OpeningBalancesOut>

    @PUT("/api/accounting/opening-balances")
    suspend fun setOpeningBalances(@Body body: Map<String, Double>): Response<OpeningBalancesOut>

    @GET("/api/accounting/summary")
    suspend fun getSummary(
        @Query("staff_id") staffId: Int? = null,
        @Query("start_date") start: String? = null,
        @Query("end_date") end: String? = null,
    ): Response<SummaryOut>

    @GET("/api/accounting/trial-balance")
    suspend fun getTrialBalance(
        @Query("staff_id") staffId: Int? = null,
        @Query("start_date") start: String? = null,
        @Query("end_date") end: String? = null,
    ): Response<TrialBalanceOut>

    @GET("/api/accounting/journal")
    suspend fun getJournal(
        @Query("staff_id") staffId: Int? = null,
        @Query("start_date") start: String? = null,
        @Query("end_date") end: String? = null,
        @Query("limit") limit: Int = 500,
    ): Response<List<JournalEntryOut>>

    @GET("/api/accounting/ledger/{accountId}")
    suspend fun getLedger(
        @Path("accountId") accountId: Int,
        @Query("staff_id") staffId: Int? = null,
        @Query("end_date") end: String? = null,
    ): Response<LedgerOut>

    // Scoped server-side to the caller's institution automatically.
    @GET("/api/stock")
    suspend fun listStockItems(@Query("institution_id") institutionId: Int? = null): Response<List<StockItemOut>>

    @GET("/api/posts")
    suspend fun listPosts(@Query("institution_id") institutionId: Int? = null): Response<List<PostOut>>

    @GET("/api/audits")
    suspend fun listAudits(@Query("institution_id") institutionId: Int? = null): Response<List<AuditOut>>

    @POST("/api/audits")
    suspend fun createAudit(@Body body: Map<String, String?>): Response<AuditOut>

    @GET("/api/audits/{auditId}/transactions")
    suspend fun getAuditTransactions(@Path("auditId") auditId: Int): Response<List<TransactionOut>>

    @POST("/api/audits/{auditId}/finalize")
    suspend fun finalizeAudit(@Path("auditId") auditId: Int): Response<AuditOut>

    @GET("/api/institutions")
    suspend fun listInstitutions(): Response<List<InstitutionOut>>

    @POST("/api/institutions")
    suspend fun createInstitution(@Body body: Map<String, String?>): Response<InstitutionOut>

    @GET("/api/users")
    suspend fun listUsers(): Response<List<UserOut>>

    @POST("/api/users")
    suspend fun createUser(@Body body: Map<String, String?>): Response<UserOut>

    @PATCH("/api/users/{userId}/deactivate")
    suspend fun deactivateUser(@Path("userId") userId: Int): Response<UserOut>

    @PATCH("/api/users/{userId}/reactivate")
    suspend fun reactivateUser(@Path("userId") userId: Int): Response<UserOut>

    @GET("/api/product-categories")
    suspend fun listProductCategories(): Response<List<ProductCategoryOut>>

    @POST("/api/product-categories")
    suspend fun createProductCategory(@Body body: Map<String, String>): Response<ProductCategoryOut>

    @DELETE("/api/product-categories/{categoryId}")
    suspend fun deleteProductCategory(@Path("categoryId") categoryId: Int): Response<Unit>

    @GET("/api/market-analysis/categories")
    suspend fun listCategoryInsights(): Response<List<CategoryInsightOut>>

    @GET("/api/market-analysis/categories/{categoryId}")
    suspend fun getCategoryDetail(@Path("categoryId") categoryId: Int): Response<CategoryDetailOut>

    @POST("/api/imports")
    suspend fun scanImports(@Body body: com.knowapp.android.data.model.ImportRequestBody): Response<com.knowapp.android.data.model.ImportResultOut>

    @GET("/api/imports")
    suspend fun listImports(): Response<List<com.knowapp.android.data.model.MessageImportOut>>

    @POST("/api/imports/{importId}/approve")
    suspend fun approveImport(@Path("importId") importId: Int, @Body body: com.knowapp.android.data.model.ApproveBody): Response<com.knowapp.android.data.model.MessageImportOut>

    @POST("/api/imports/{importId}/ignore")
    suspend fun ignoreImport(@Path("importId") importId: Int): Response<com.knowapp.android.data.model.MessageImportOut>
}
