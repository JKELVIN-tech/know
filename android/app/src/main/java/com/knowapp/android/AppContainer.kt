package com.knowapp.android

import android.content.Context
import com.knowapp.android.data.SessionStore
import com.knowapp.android.data.ThemeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.knowapp.android.data.UpdateManager
import com.knowapp.android.data.local.OfflineStore
import com.knowapp.android.data.local.PhotoStore
import com.knowapp.android.data.network.NetworkMonitor
import com.knowapp.android.data.repository.AdminRepository
import com.knowapp.android.data.repository.BooksRepository
import com.knowapp.android.data.repository.DocumentsRepository
import com.knowapp.android.data.repository.ProfileRepository
import com.knowapp.android.data.network.NetworkModule
import com.knowapp.android.data.repository.AuditRepository
import com.knowapp.android.data.repository.AuthRepository
import com.knowapp.android.data.repository.InstitutionsRepository
import com.knowapp.android.data.repository.MarketAnalysisRepository
import com.knowapp.android.data.repository.PostsRepository
import com.knowapp.android.data.repository.ProductCategoriesRepository
import com.knowapp.android.data.repository.StockRepository
import com.knowapp.android.data.repository.TransactionRepository
import com.knowapp.android.data.repository.UsersRepository

/** One instance built in KnowApplication.onCreate, threaded down to ViewModels. */
class AppContainer(context: Context) {
    val sessionStore = SessionStore(context.applicationContext)
    private val apiService = NetworkModule.buildApiService(sessionStore, context.applicationContext.cacheDir)

    val authRepository = AuthRepository(apiService, sessionStore)
    val offlineStore = OfflineStore(context)
    val photoStore = PhotoStore(context)
    val detectedStore = com.knowapp.android.data.local.DetectedStore(context) { sessionStore.session.value?.userId }
    val networkMonitor = NetworkMonitor(context)
    val updateManager = UpdateManager(context)
    val themeStore = ThemeStore(context)
    val transactionRepository = TransactionRepository(apiService, offlineStore, sessionStore, photoStore) { networkMonitor.online.value }
    val booksRepository = BooksRepository(apiService)
    val adminRepository = AdminRepository(apiService)
    val documentsRepository = DocumentsRepository(apiService, photoStore)
    val profileRepository = ProfileRepository(apiService, photoStore)
    val stockRepository = StockRepository(apiService)
    val postsRepository = PostsRepository(apiService, photoStore)
    val auditRepository = AuditRepository(apiService)
    val institutionsRepository = InstitutionsRepository(apiService)
    val usersRepository = UsersRepository(apiService)
    val marketAnalysisRepository = MarketAnalysisRepository(apiService)
    val productCategoriesRepository = ProductCategoriesRepository(apiService)
    val importsRepository = com.knowapp.android.data.repository.ImportsRepository(apiService)
    val exchangeRepository = com.knowapp.android.data.repository.ExchangeRepository(apiService)
    val profitRepository = com.knowapp.android.data.repository.ProfitRepository(apiService)

    // Text shared into the app from the Messages app, waiting to be detected on the review screen
    val pendingShare = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Entries saved offline reach the server as soon as a connection is back, whichever screen is open
        appScope.launch {
            networkMonitor.online.collect { online ->
                if (online) runCatching { transactionRepository.syncPending() }
            }
        }
    }
}
