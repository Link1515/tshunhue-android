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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import tw.terry.tshunhue.domain.CatalogScope
import tw.terry.tshunhue.ui.screens.BrowseScreen
import tw.terry.tshunhue.ui.screens.CatalogScreen
import tw.terry.tshunhue.ui.screens.FrameDetailsScreen
import tw.terry.tshunhue.ui.screens.SettingsScreen

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
            composable("browse") { BrowseScreen(state, onOpenCategory = { sourceUrl, categoryId -> viewModel.openCategory(sourceUrl, categoryId); navController.navigate("category") }, onSettings = { navController.navigate("settings") }) }
            composable("search") { CatalogScreen("搜尋", CatalogScope.All, state, viewModel, onDetails = { navController.navigate("details") }, onSettings = { navController.navigate("settings") }, initiallyFocused = true) }
            composable("favorites") { CatalogScreen("收藏", CatalogScope.Favorites, state, viewModel, onDetails = { navController.navigate("details") }, onSettings = { navController.navigate("settings") }) }
            composable("recents") { CatalogScreen("最近使用", CatalogScope.Recents, state, viewModel, onDetails = { navController.navigate("details") }, onSettings = { navController.navigate("settings") }) }
            composable("category") { CatalogScreen("分類", state.selectedScope, state, viewModel, onDetails = { navController.navigate("details") }, onSettings = { navController.navigate("settings") }) }
            composable("details") { FrameDetailsScreen(state.selectedFrame, state.favoriteIds, onBack = { navController.popBackStack() }, onFavorite = viewModel::toggleFavorite, onTransfer = viewModel::recordRecent) }
            composable("settings") { SettingsScreen(state, viewModel, onBack = { navController.popBackStack() }) }
        }
    }
}
