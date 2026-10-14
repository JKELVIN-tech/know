package com.knowapp.android.ui.navigation

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val TRANSACTIONS = "transactions"
    const val STOCK = "stock"
    const val SETTINGS = "settings"
    const val BOOKS = "books"
    const val STATEMENTS = "statements"
    const val DOCUMENTS = "documents"
    const val IMPORTS = "imports"
    const val OVERVIEW = "overview"
    const val REPORTS = "reports"
    const val DOCUMENTS_PATTERN = "documents?itemId={itemId}"
    const val DOCUMENT_NEW = "documents/new/{docType}?itemId={itemId}"
    const val NEWS = "news"
    const val AUDITS = "audits"
    const val AUDIT_DETAIL = "audit_detail/{auditId}"
    const val INSTITUTIONS = "institutions"
    const val ACCOUNTS = "accounts"
    const val MARKET = "market"
    const val CATEGORY_DETAIL = "category_detail/{categoryId}"

    fun documents(itemId: Int? = null) = if (itemId == null) "documents" else "documents?itemId=$itemId"
    fun documentNew(docType: String, itemId: Int? = null) = if (itemId == null) "documents/new/$docType" else "documents/new/$docType?itemId=$itemId"
    fun auditDetail(auditId: Int) = "audit_detail/$auditId"
    fun categoryDetail(categoryId: Int) = "category_detail/$categoryId"
}
