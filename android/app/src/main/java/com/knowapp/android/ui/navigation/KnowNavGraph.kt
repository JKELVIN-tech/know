package com.knowapp.android.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.currentBackStackEntryAsState
import com.knowapp.android.data.ThemeMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.knowapp.android.AppContainer
import com.knowapp.android.ui.accounts.AccountsScreen
import com.knowapp.android.ui.accounts.AccountsViewModel
import com.knowapp.android.ui.books.BooksScreen
import com.knowapp.android.ui.books.BooksViewModel
import com.knowapp.android.ui.settings.SettingsScreen
import com.knowapp.android.ui.statements.StatementsScreen
import com.knowapp.android.ui.statements.StatementsViewModel
import com.knowapp.android.ui.audits.AuditDetailScreen
import com.knowapp.android.ui.audits.AuditDetailViewModel
import com.knowapp.android.ui.audits.AuditsScreen
import com.knowapp.android.ui.audits.AuditsViewModel
import com.knowapp.android.ui.home.HomeScreen
import com.knowapp.android.ui.home.HomeViewModel
import com.knowapp.android.ui.institutions.InstitutionsScreen
import com.knowapp.android.ui.institutions.InstitutionsViewModel
import com.knowapp.android.ui.login.LoginScreen
import com.knowapp.android.ui.login.LoginViewModel
import com.knowapp.android.ui.market.CategoryDetailScreen
import com.knowapp.android.ui.market.CategoryDetailViewModel
import com.knowapp.android.ui.market.MarketAnalysisScreen
import com.knowapp.android.ui.market.MarketAnalysisViewModel
import com.knowapp.android.ui.news.NewsScreen
import com.knowapp.android.ui.news.NewsViewModel
import com.knowapp.android.ui.stock.StockScreen
import com.knowapp.android.ui.stock.StockViewModel
import com.knowapp.android.ui.transactions.TransactionsScreen
import com.knowapp.android.ui.transactions.TransactionsViewModel

/** Simple factory-per-screen — matches the rest of the app's manual-DI approach. */
private fun <T> vmFactory(create: () -> T) = object : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : androidx.lifecycle.ViewModel> create(modelClass: Class<VM>): VM = create() as VM
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

// Every destination on the bar is its own page; what appears depends on who is signed in
private fun tabsFor(role: String?, staffType: String?): List<Tab> = when (role) {
    "super_admin" -> listOf(
        Tab(Routes.OVERVIEW, "Overview", Icons.Outlined.Dashboard),
        Tab(Routes.INSTITUTIONS, "Institutions", Icons.Outlined.Business),
        Tab(Routes.ACCOUNTS, "Accounts", Icons.Outlined.Group),
        Tab(Routes.MARKET, "Market", Icons.Outlined.Insights),
        Tab(Routes.SETTINGS, "Settings", Icons.Outlined.Settings),
    )
    "institution_admin" -> listOf(
        Tab(Routes.BOOKS, "Books", Icons.Outlined.AccountBalance),
        Tab(Routes.STOCK, "Stock", Icons.Outlined.Inventory2),
        Tab(Routes.NEWS, "News", Icons.Outlined.Article),
        Tab(Routes.ACCOUNTS, "Accounts", Icons.Outlined.Group),
        Tab(Routes.SETTINGS, "Settings", Icons.Outlined.Settings),
    )
    else -> buildList {
        add(Tab(Routes.HOME, "Home", Icons.Outlined.Home))
        if (staffType != "teacher") add(Tab(Routes.STOCK, "Stock", Icons.Outlined.Inventory2))
        add(Tab(Routes.NEWS, "News", Icons.Outlined.Article))
        add(Tab(Routes.STATEMENTS, "Statements", Icons.Outlined.Receipt))
        add(Tab(Routes.SETTINGS, "Settings", Icons.Outlined.Settings))
    }
}

private fun startFor(role: String?): String = when (role) {
    "super_admin" -> Routes.OVERVIEW
    "institution_admin" -> Routes.BOOKS
    else -> Routes.HOME
}

@Composable
fun KnowNavGraph(container: AppContainer) {
    val navController = rememberNavController()
    val session by container.sessionStore.session.collectAsState()
    val tabs = tabsFor(session?.role, session?.staffType)
    val startDestination = if (session == null) Routes.LOGIN else startFor(session?.role)
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = session != null && tabs.any { it.route == currentRoute }

    androidx.compose.runtime.LaunchedEffect(session) {
        if (session == null && currentRoute != null && currentRoute != Routes.LOGIN) {
            navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
        }
    }

    val shared by container.pendingShare.collectAsState()
    androidx.compose.runtime.LaunchedEffect(shared, session) {
        if (shared != null && session?.role == "staff" && currentRoute != Routes.IMPORTS) navController.navigate(Routes.IMPORTS) { launchSingleTop = true }
    }

    fun goTab(route: String) {
        navController.navigate(route) {
            popUpTo(startFor(session?.role)) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { goTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label, fontSize = 11.sp, maxLines = 1, softWrap = false) },
                        )
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { outerPadding ->
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier.padding(outerPadding).consumeWindowInsets(outerPadding),
    ) {
        composable(Routes.LOGIN) {
            val viewModel: LoginViewModel = viewModel(factory = vmFactory { LoginViewModel(container.authRepository) })
            LoginScreen(
                viewModel = viewModel,
                onLoginSuccess = {
                    navController.navigate(startFor(container.sessionStore.session.value?.role)) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            val viewModel: HomeViewModel = viewModel(
                factory = vmFactory {
                    HomeViewModel(
                        container.authRepository,
                        container.transactionRepository,
                        container.stockRepository,
                        container.offlineStore,
                        container.sessionStore,
                        container.institutionsRepository,
                        container.photoStore,
                        container.networkMonitor,
                    )
                },
            )
            val hideBalances by container.themeStore.hideBalances.collectAsState()
            HomeScreen(
                viewModel = viewModel,
                hideBalances = hideBalances,
                onToggleHideBalances = { container.themeStore.setHideBalances(!hideBalances) },
                lastMethod = container.themeStore.lastMethod,
                onMethodChosen = { container.themeStore.rememberMethod(it) },
                onSeeAll = { goTab(Routes.STATEMENTS) },
                onToggleTheme = { makeDark -> container.themeStore.set(if (makeDark) ThemeMode.DARK else ThemeMode.LIGHT) },
            )
        }
        composable(Routes.IMPORTS) {
            val viewModel: com.knowapp.android.ui.imports.ImportsViewModel = viewModel(
                factory = vmFactory { com.knowapp.android.ui.imports.ImportsViewModel(container.importsRepository, container.stockRepository, container.pendingShare) },
            )
            com.knowapp.android.ui.imports.ImportsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                updateManager = container.updateManager,
                themeStore = container.themeStore,
                profile = viewModel(factory = vmFactory { com.knowapp.android.ui.settings.ProfileViewModel(container.profileRepository, container.photoStore) }),
                onSignOut = {
                    container.authRepository.logout()
                    navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(Routes.STATEMENTS) {
            val viewModel: StatementsViewModel = viewModel(factory = vmFactory { StatementsViewModel(container.transactionRepository, container.booksRepository, container.institutionsRepository, container.sessionStore, session?.role == "institution_admin") })
            StatementsScreen(viewModel = viewModel, onBack = { navController.popBackStack() }, showBack = session?.role == "institution_admin", onViewAudits = { navController.navigate(Routes.AUDITS) })
        }
        composable(Routes.BOOKS) {
            val viewModel: BooksViewModel = viewModel(factory = vmFactory { BooksViewModel(container.booksRepository, container.auditRepository) })
            BooksScreen(viewModel = viewModel, onBack = { navController.popBackStack() }, showBack = false, onViewAudits = { navController.navigate(Routes.AUDITS) }, onViewStatements = { navController.navigate(Routes.STATEMENTS) })
        }
        composable(Routes.STOCK) {
            val viewModel: StockViewModel = viewModel(factory = vmFactory { StockViewModel(container.stockRepository, container.documentsRepository, container.exchangeRepository, session?.role == "institution_admin", session?.userId) })
            StockScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                showBack = false,
                onOpenDocuments = { navController.navigate(Routes.DOCUMENTS) },
                onNewDocument = { type -> navController.navigate(Routes.documentNew(type)) },
                onOpenItemDocuments = { itemId -> navController.navigate(Routes.documents(itemId)) },
                onNewItemDocument = { itemId, type -> navController.navigate(Routes.documentNew(type, itemId)) },
            )
        }
        composable(
            Routes.DOCUMENTS_PATTERN,
            arguments = listOf(navArgument("itemId") { type = NavType.IntType; defaultValue = -1 }),
        ) { entry ->
            val itemId = entry.arguments?.getInt("itemId")?.takeIf { it >= 0 }
            val viewModel: com.knowapp.android.ui.documents.DocumentsViewModel = viewModel(
                factory = vmFactory { com.knowapp.android.ui.documents.DocumentsViewModel(container.documentsRepository, container.stockRepository, container.sessionStore, itemId) },
            )
            com.knowapp.android.ui.documents.DocumentsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onNew = { type -> navController.navigate(Routes.documentNew(type, itemId)) },
            )
        }
        composable(
            Routes.DOCUMENT_NEW,
            arguments = listOf(
                navArgument("docType") { type = NavType.StringType },
                navArgument("itemId") { type = NavType.IntType; defaultValue = -1 },
            ),
        ) { entry ->
            val docType = entry.arguments?.getString("docType") ?: return@composable
            val itemId = entry.arguments?.getInt("itemId")?.takeIf { it >= 0 }
            val viewModel: com.knowapp.android.ui.documents.NewDocumentViewModel = viewModel(
                factory = vmFactory {
                    com.knowapp.android.ui.documents.NewDocumentViewModel(container.documentsRepository, container.photoStore, container.stockRepository, container.transactionRepository)
                },
            )
            com.knowapp.android.ui.documents.NewDocumentScreen(
                viewModel = viewModel,
                docType = docType,
                preselectedItemId = itemId,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }
        composable(Routes.NEWS) {
            val viewModel: NewsViewModel = viewModel(factory = vmFactory { NewsViewModel(container.postsRepository, container.photoStore, container.sessionStore, container.profileRepository) })
            NewsScreen(viewModel = viewModel, onBack = { navController.popBackStack() }, showBack = false)
        }
        composable(Routes.AUDITS) {
            val viewModel: AuditsViewModel = viewModel(factory = vmFactory { AuditsViewModel(container.auditRepository, container.sessionStore) })
            AuditsScreen(
                viewModel = viewModel,
                currentUserId = session?.userId,
                onBack = { navController.popBackStack() },
                onOpenAudit = { auditId -> navController.navigate(Routes.auditDetail(auditId)) },
            )
        }
        composable(
            Routes.AUDIT_DETAIL,
            arguments = listOf(navArgument("auditId") { type = NavType.IntType }),
        ) { backStackEntry ->
            val auditId = backStackEntry.arguments?.getInt("auditId") ?: return@composable
            val viewModel: AuditDetailViewModel = viewModel(factory = vmFactory { AuditDetailViewModel(container.auditRepository, auditId) })
            AuditDetailScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.OVERVIEW) {
            val viewModel: com.knowapp.android.ui.admin.AdminOverviewViewModel = viewModel(
                factory = vmFactory { com.knowapp.android.ui.admin.AdminOverviewViewModel(container.adminRepository) },
            )
            com.knowapp.android.ui.admin.AdminOverviewScreen(
                viewModel = viewModel,
                onOpenReports = { navController.navigate(Routes.REPORTS) },
                onOpenInstitutions = { goTab(Routes.INSTITUTIONS) },
                onOpenAccounts = { goTab(Routes.ACCOUNTS) },
            )
        }
        composable(Routes.REPORTS) {
            val viewModel: com.knowapp.android.ui.admin.ReportsViewModel = viewModel(
                factory = vmFactory { com.knowapp.android.ui.admin.ReportsViewModel(container.adminRepository) },
            )
            com.knowapp.android.ui.admin.ReportsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.INSTITUTIONS) {
            val viewModel: InstitutionsViewModel = viewModel(factory = vmFactory { InstitutionsViewModel(container.adminRepository, container.institutionsRepository, container.usersRepository) })
            InstitutionsScreen(viewModel = viewModel, onBack = { navController.popBackStack() }, showBack = false)
        }
        composable(Routes.ACCOUNTS) {
            val viewModel: AccountsViewModel = viewModel(
                factory = vmFactory { AccountsViewModel(container.usersRepository, container.institutionsRepository, container.adminRepository, container.sessionStore) },
            )
            AccountsScreen(viewModel = viewModel, onBack = { navController.popBackStack() }, showBack = false)
        }
        composable(Routes.MARKET) {
            val viewModel: MarketAnalysisViewModel = viewModel(
                factory = vmFactory { MarketAnalysisViewModel(container.marketAnalysisRepository, container.productCategoriesRepository) },
            )
            MarketAnalysisScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                showBack = false,
                onOpenCategory = { categoryId -> navController.navigate(Routes.categoryDetail(categoryId)) },
            )
        }
        composable(
            Routes.CATEGORY_DETAIL,
            arguments = listOf(navArgument("categoryId") { type = NavType.IntType }),
        ) { backStackEntry ->
            val categoryId = backStackEntry.arguments?.getInt("categoryId") ?: return@composable
            val viewModel: CategoryDetailViewModel = viewModel(factory = vmFactory { CategoryDetailViewModel(container.marketAnalysisRepository, categoryId) })
            CategoryDetailScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
    }
    }
}
