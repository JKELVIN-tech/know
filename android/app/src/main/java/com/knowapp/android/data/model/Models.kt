package com.knowapp.android.data.model

import com.google.gson.annotations.SerializedName

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("user_id") val userId: Int,
    val role: String,
    @SerializedName("staff_type") val staffType: String? = null,
    @SerializedName("full_name") val fullName: String,
    @SerializedName("institution_id") val institutionId: Int? = null,
)

data class ApiErrorBody(
    val detail: String? = null,
)

data class TransactionOut(
    val id: Int,
    @SerializedName("institution_id") val institutionId: Int,
    val type: String, // "income" | "expense"
    val method: String, // "cash" | "mpesa" | "bank"
    @SerializedName("category_type") val categoryType: String, // "STOCK" | "OTHER"
    val category: String,
    val description: String? = null,
    val amount: String, // Decimal serialized as string by FastAPI/Pydantic
    @SerializedName("stock_item_id") val stockItemId: Int? = null,
    val quantity: String? = null,
    @SerializedName("mpesa_code") val mpesaCode: String? = null,
    @SerializedName("mpesa_payer_name") val mpesaPayerName: String? = null,
    @SerializedName("transaction_date") val transactionDate: String,
    @SerializedName("image_path") val imagePath: String? = null,
    @SerializedName("recorded_by_id") val recordedById: Int,
    @SerializedName("created_at") val createdAt: String,
)

data class StockItemOut(
    val id: Int,
    @SerializedName("institution_id") val institutionId: Int,
    @SerializedName("category_id") val categoryId: Int? = null,
    @SerializedName("category_name") val categoryName: String? = null,
    @SerializedName("branch_id") val branchId: Int? = null,
    @SerializedName("branch_name") val branchName: String? = null,
    val name: String,
    val description: String? = null,
    @SerializedName("unit_price") val unitPrice: String? = null,
    val quantity: String,
    @SerializedName("created_at") val createdAt: String,
)

data class PostOut(
    val id: Int,
    @SerializedName("institution_id") val institutionId: Int,
    @SerializedName("author_id") val authorId: Int,
    @SerializedName("author_name") val authorName: String? = null,
    @SerializedName("author_avatar_path") val authorAvatarPath: String? = null,
    @SerializedName("author_bio") val authorBio: String? = null,
    @SerializedName("author_institution") val authorInstitution: String? = null,
    @SerializedName("comment_count") val commentCount: Int = 0,
    val title: String,
    val body: String,
    @SerializedName("image_path") val imagePath: String? = null,
    @SerializedName("created_at") val createdAt: String,
)

/** Matches Api.posts.imageUrl / Api.institutions.logoUrl in frontend/js/api.js:
 * image_path is either already a full URL or a path relative to the API host. */
fun resolveMediaUrl(path: String?): String? {
    if (path.isNullOrBlank()) return null
    return if (path.startsWith("http://") || path.startsWith("https://")) path else "${com.knowapp.android.BuildConfig.API_BASE_URL}/$path"
}

data class AuditOut(
    val id: Int,
    @SerializedName("institution_id") val institutionId: Int,
    val title: String,
    @SerializedName("period_start") val periodStart: String,
    @SerializedName("period_end") val periodEnd: String,
    val summary: String? = null,
    val status: String, // "draft" | "finalized"
    @SerializedName("submitted_by_id") val submittedById: Int,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("finalized_at") val finalizedAt: String? = null,
)

data class InstitutionOut(
    val id: Int,
    val name: String,
    val type: String,
    val address: String? = null,
    val region: String? = null,
    @SerializedName("logo_path") val logoPath: String? = null,
    @SerializedName("created_at") val createdAt: String,
)

data class UserOut(
    val id: Int,
    val username: String,
    @SerializedName("full_name") val fullName: String,
    val role: String, // "super_admin" | "institution_admin" | "staff"
    @SerializedName("staff_type") val staffType: String? = null,
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("institution_id") val institutionId: Int? = null,
    @SerializedName("institution_name") val institutionName: String? = null,
    @SerializedName("last_active_at") val lastActiveAt: String? = null,
    @SerializedName("share_audits") val shareAudits: Boolean,
    @SerializedName("created_at") val createdAt: String,
    val bio: String? = null,
    @SerializedName("avatar_path") val avatarPath: String? = null,
)

data class CategoryInsightOut(
    @SerializedName("category_id") val categoryId: Int,
    @SerializedName("category_name") val categoryName: String,
    @SerializedName("institution_count") val institutionCount: Int,
    @SerializedName("average_price") val averagePrice: Double,
    @SerializedName("median_price") val medianPrice: Double,
    @SerializedName("min_price") val minPrice: Double,
    @SerializedName("max_price") val maxPrice: Double,
    @SerializedName("total_quantity_sold") val totalQuantitySold: String,
)

data class RegionBreakdownEntry(
    val region: String,
    @SerializedName("institution_count") val institutionCount: Int,
    @SerializedName("average_price") val averagePrice: Double,
    @SerializedName("median_price") val medianPrice: Double,
    @SerializedName("min_price") val minPrice: Double,
    @SerializedName("max_price") val maxPrice: Double,
)

data class TrendPointOut(
    val month: String,
    @SerializedName("average_price") val averagePrice: Double,
    @SerializedName("institution_count") val institutionCount: Int,
)

data class CategoryDetailOut(
    @SerializedName("category_id") val categoryId: Int,
    @SerializedName("category_name") val categoryName: String,
    @SerializedName("institution_count") val institutionCount: Int,
    @SerializedName("average_price") val averagePrice: Double,
    @SerializedName("median_price") val medianPrice: Double,
    @SerializedName("min_price") val minPrice: Double,
    @SerializedName("max_price") val maxPrice: Double,
    @SerializedName("total_quantity_sold") val totalQuantitySold: String,
    @SerializedName("regional_breakdown") val regionalBreakdown: List<RegionBreakdownEntry>,
    val trend: List<TrendPointOut>,
)

data class ProductCategoryOut(
    val id: Int,
    val name: String,
    @SerializedName("created_at") val createdAt: String,
)




data class OpeningBalancesOut(
    val cash: String,
    val mpesa: String,
    val bank: String,
    @SerializedName("is_set") val isSet: Boolean,
)

data class MoneyPositionOut(val key: String, val name: String, val balance: String)

data class SummaryOut(
    val money: List<MoneyPositionOut>,
    @SerializedName("total_money") val totalMoney: String,
    val income: String,
    val expenses: String,
    val net: String,
)

data class AccountBalanceOut(
    val id: Int,
    val code: Int,
    val key: String,
    val name: String,
    val type: String,
    val debit: String,
    val credit: String,
    val balance: String,
)

data class TrialBalanceOut(
    val accounts: List<AccountBalanceOut>,
    @SerializedName("total_debit") val totalDebit: String,
    @SerializedName("total_credit") val totalCredit: String,
    val balanced: Boolean,
)

data class PendingTransaction(
    val localId: String,
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
    val createdAt: String,
    val failed: Boolean = false,
    val error: String? = null,
    val imagePath: String? = null,
)


data class PostCommentOut(
    val id: Int,
    @SerializedName("post_id") val postId: Int,
    @SerializedName("author_id") val authorId: Int,
    @SerializedName("author_name") val authorName: String? = null,
    @SerializedName("author_avatar_path") val authorAvatarPath: String? = null,
    val body: String,
    @SerializedName("created_at") val createdAt: String,
)


data class ReferenceCheckOut(
    val exists: Boolean,
    val mine: Boolean = false,
    val date: String? = null,
    val amount: String? = null,
)


data class DocumentOut(
    val id: Int,
    @SerializedName("doc_type") val docType: String,
    @SerializedName("doc_type_name") val docTypeName: String,
    @SerializedName("reference_no") val referenceNo: String? = null,
    @SerializedName("document_date") val documentDate: String,
    @SerializedName("party_name") val partyName: String? = null,
    @SerializedName("stock_item_id") val stockItemId: Int? = null,
    @SerializedName("stock_item_name") val stockItemName: String? = null,
    val quantity: String? = null,
    val amount: String? = null,
    val notes: String? = null,
    @SerializedName("image_path") val imagePath: String? = null,
    @SerializedName("transaction_id") val transactionId: Int? = null,
    @SerializedName("recorded_by_id") val recordedById: Int,
    @SerializedName("recorded_by_name") val recordedByName: String? = null,
    @SerializedName("created_at") val createdAt: String,
)


data class QuietInstitutionOut(
    val id: Int,
    val name: String,
    @SerializedName("last_activity_at") val lastActivityAt: String? = null,
)

data class ActivityItemOut(
    val id: Int,
    val action: String,
    @SerializedName("actor_username") val actorUsername: String? = null,
    val detail: String? = null,
    @SerializedName("target_type") val targetType: String? = null,
    @SerializedName("created_at") val createdAt: String,
)

data class AdminOverviewOut(
    val institutions: Int,
    @SerializedName("accounts_total") val accountsTotal: Int,
    @SerializedName("accounts_active") val accountsActive: Int,
    @SerializedName("accounts_inactive") val accountsInactive: Int,
    val staff: Int,
    @SerializedName("institution_admins") val institutionAdmins: Int,
    @SerializedName("super_admins") val superAdmins: Int,
    @SerializedName("new_accounts_30d") val newAccounts30d: Int,
    @SerializedName("open_reports") val openReports: Int,
    @SerializedName("entries_7d") val entries7d: Int,
    @SerializedName("quiet_institutions") val quietInstitutions: List<QuietInstitutionOut> = emptyList(),
    @SerializedName("recent_activity") val recentActivity: List<ActivityItemOut> = emptyList(),
)

data class InstitutionStatsOut(
    val id: Int,
    val name: String,
    val type: String,
    val region: String? = null,
    val address: String? = null,
    @SerializedName("logo_path") val logoPath: String? = null,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("staff_count") val staffCount: Int = 0,
    @SerializedName("admin_count") val adminCount: Int = 0,
    @SerializedName("active_accounts") val activeAccounts: Int = 0,
    @SerializedName("inactive_accounts") val inactiveAccounts: Int = 0,
    @SerializedName("entries_30d") val entries30d: Int = 0,
    @SerializedName("last_activity_at") val lastActivityAt: String? = null,
)

data class ReportPostOut(
    val id: Int,
    @SerializedName("institution_name") val institutionName: String? = null,
    @SerializedName("author_name") val authorName: String? = null,
    val title: String,
    val body: String,
    @SerializedName("image_path") val imagePath: String? = null,
    @SerializedName("created_at") val createdAt: String,
)

data class ReportOut(
    val id: Int,
    @SerializedName("post_id") val postId: Int,
    val post: ReportPostOut? = null,
    @SerializedName("reporter_name") val reporterName: String? = null,
    val reason: String? = null,
    val status: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("resolved_at") val resolvedAt: String? = null,
    val resolution: String? = null,
)


data class JournalLineOut(
    @SerializedName("account_id") val accountId: Int,
    @SerializedName("account_name") val accountName: String,
    val debit: String,
    val credit: String,
)

data class JournalEntryOut(
    val id: Int,
    @SerializedName("entry_date") val entryDate: String,
    val memo: String? = null,
    @SerializedName("transaction_id") val transactionId: Int? = null,
    @SerializedName("recorded_by_name") val recordedByName: String? = null,
    val reference: String? = null,
    val lines: List<JournalLineOut> = emptyList(),
)

data class LedgerLineOut(
    @SerializedName("entry_id") val entryId: Int,
    @SerializedName("entry_date") val entryDate: String,
    val memo: String? = null,
    @SerializedName("recorded_by_name") val recordedByName: String? = null,
    val reference: String? = null,
    val debit: String,
    val credit: String,
    @SerializedName("running_balance") val runningBalance: String,
)

data class LedgerOut(val account: AccountBalanceOut, val lines: List<LedgerLineOut> = emptyList())


data class MessageImportOut(
    val id: Int,
    val code: String,
    val kind: String,
    val direction: String? = null,
    val amount: String? = null,
    val counterparty: String? = null,
    @SerializedName("bank_hint") val bankHint: String? = null,
    @SerializedName("message_time") val messageTime: String? = null,
    val status: String,
    val reason: String? = null,
    @SerializedName("transaction_id") val transactionId: Int? = null,
)

data class ImportRequestBody(val messages: List<String>)

data class ImportResultOut(val items: List<MessageImportOut> = emptyList(), val skipped: Int = 0)

data class ApproveBody(
    @SerializedName("category_type") val categoryType: String,
    val category: String? = null,
    @SerializedName("stock_item_id") val stockItemId: Int? = null,
    val quantity: String? = null,
    val description: String? = null,
    val force: Boolean = false,
)


data class BranchOut(val id: Int, val name: String)

data class TransferOut(
    val id: Int,
    @SerializedName("item_name") val itemName: String,
    val quantity: String,
    @SerializedName("from_branch_id") val fromBranchId: Int,
    @SerializedName("from_branch_name") val fromBranchName: String,
    @SerializedName("to_branch_id") val toBranchId: Int,
    @SerializedName("to_branch_name") val toBranchName: String,
    val status: String,
    val note: String? = null,
    val direction: String,
)

data class TransferBody(
    @SerializedName("item_id") val itemId: Int,
    val quantity: String,
    @SerializedName("to_branch_id") val toBranchId: Int,
    @SerializedName("from_branch_id") val fromBranchId: Int? = null,
    val note: String? = null,
)
