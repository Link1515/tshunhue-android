package tw.terry.tshunhue.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import tw.terry.tshunhue.domain.CatalogScope
import tw.terry.tshunhue.BuildConfig
import tw.terry.tshunhue.ui.screens.BrowseScreen
import tw.terry.tshunhue.ui.screens.CatalogScreen
import tw.terry.tshunhue.ui.screens.FrameDetailsScreen
import tw.terry.tshunhue.ui.screens.PrivacyPolicyScreen
import tw.terry.tshunhue.ui.screens.AboutScreen
import tw.terry.tshunhue.ui.screens.ReportFrameScreen
import tw.terry.tshunhue.ui.screens.SettingsScreen
import tw.terry.tshunhue.ui.screens.CaptionReviewScreen

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val tabs = listOf(
    Tab("browse", "瀏覽", Icons.Outlined.Home), Tab("search", "搜尋", Icons.Outlined.Search),
    Tab("favorites", "收藏", Icons.Outlined.Favorite), Tab("recents", "最近", Icons.Outlined.History),
)

@Composable
fun TshunhueApp(viewModel: TshunhueViewModel) {
    CompositionLocalProvider(LocalImageRepository provides viewModel.imageRepository) {
        TshunhueAppContent(viewModel)
    }
}

@Composable
private fun TshunhueAppContent(viewModel: TshunhueViewModel) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val navController = rememberNavController()
    val snackbarHost = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshWhenActive()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.message) { state.message?.let { snackbarHost.showSnackbar(it); viewModel.clearMessage() } }
    val route = navController.currentBackStackEntryAsState().value?.destination?.route

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            if (route in tabs.map(Tab::route)) NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = route == tab.route,
                        onClick = { navController.navigate(tab.route) { popUpTo(navController.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } },
                        icon = { Icon(tab.icon, tab.label) }, label = { androidx.compose.material3.Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController, "browse", Modifier.padding(padding)) {
            composable("browse") {
                BrowseScreen(
                    state,
                    onOpenSource = { sourceUrl -> viewModel.openSource(sourceUrl); navController.navigate("source") },
                    onOpenCategory = { sourceUrl, categoryId -> viewModel.openCategory(sourceUrl, categoryId); navController.navigate("category") },
                    onSettings = { navController.navigate("settings") },
                )
            }
            composable("search") { CatalogScreen("搜尋", CatalogScope.All, state, viewModel, onDetails = { navController.navigate("details") }, onSettings = { navController.navigate("settings") }, initiallyFocused = true) }
            composable("favorites") { CatalogScreen("收藏", CatalogScope.Favorites, state, viewModel, onDetails = { navController.navigate("details") }, onSettings = { navController.navigate("settings") }) }
            composable("recents") { CatalogScreen("最近使用", CatalogScope.Recents, state, viewModel, onDetails = { navController.navigate("details") }, onSettings = { navController.navigate("settings") }) }
            composable("source") {
                val sourceName = (state.selectedScope as? CatalogScope.Source)?.let { scope ->
                    state.sources.firstOrNull { it.record.url == scope.sourceUrl }?.name
                } ?: "來源"
                CatalogScreen(sourceName, state.selectedScope, state, viewModel, onDetails = { navController.navigate("details") }, onSettings = { navController.navigate("settings") })
            }
            composable("category") {
                val reviewAction = (state.selectedScope as? CatalogScope.Category)?.takeIf { BuildConfig.DEBUG }?.let {
                    { navController.navigate("caption-review") }
                }
                CatalogScreen(
                    "分類",
                    state.selectedScope,
                    state,
                    viewModel,
                    onDetails = { navController.navigate("details") },
                    onSettings = { navController.navigate("settings") },
                    onReviewCaptions = reviewAction,
                )
            }
            composable("details") {
                FrameDetailsScreen(
                    state.selectedFrame, state.favoriteIds,
                    onBack = { navController.popBackStack() },
                    onFavorite = viewModel::toggleFavorite,
                    onTransfer = viewModel::recordRecent,
                    onReportForm = { navController.navigate("report") },
                )
            }
            composable("settings") {
                SettingsScreen(
                    state, viewModel,
                    onBack = { navController.popBackStack() },
                    onPrivacy = { navController.navigate("privacy") },
                    onAbout = { navController.navigate("about") },
                )
            }
            composable("privacy") { PrivacyPolicyScreen(onBack = { navController.popBackStack() }) }
            composable("about") { AboutScreen(onBack = { navController.popBackStack() }) }
            composable("caption-review") {
                val scope = state.selectedScope as? CatalogScope.Category
                if (BuildConfig.DEBUG && scope != null) {
                    CaptionReviewScreen(
                        catalog = state.catalog,
                        sourceUrl = scope.sourceUrl,
                        categoryId = scope.categoryId,
                        onBack = { navController.popBackStack() },
                        onReport = { frame, correction ->
                            viewModel.prepareCaptionReport(frame, correction)
                            navController.navigate("report")
                        },
                    )
                }
            }
            composable("report") {
                ReportFrameScreen(
                    state.selectedFrame,
                    initialDraft = state.preparedReport,
                    onBack = { viewModel.clearPreparedReport(); navController.popBackStack() },
                )
            }
        }
    }
}
