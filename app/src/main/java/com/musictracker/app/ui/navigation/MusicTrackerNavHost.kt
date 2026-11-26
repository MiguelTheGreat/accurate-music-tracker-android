package com.musictracker.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.musictracker.app.di.AppContainer
import com.musictracker.app.ui.components.glass.LiquidGlassBackdropHost
import com.musictracker.app.ui.components.glass.LiquidGlassNavBar
import com.musictracker.app.ui.components.glass.LiquidGlassNavItem
import com.musictracker.app.ui.components.glass.ScrollEdgeContainer
import com.musictracker.app.ui.components.glass.rememberNavBarScrollConnection
import com.musictracker.app.ui.screens.home.HomeRoute
import com.musictracker.app.ui.screens.home.HomeViewModel
import com.musictracker.app.ui.screens.settings.SettingsRoute
import com.musictracker.app.ui.screens.settings.SettingsViewModel
import com.musictracker.app.ui.screens.stats.StatsRoute
import com.musictracker.app.ui.screens.stats.StatsViewModel
import com.musictracker.app.ui.theme.LiquidGlassTheme

@Composable
fun MusicTrackerNavHost(
    appContainer: AppContainer,
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier
) {
    val homeViewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(appContainer))
    val statsViewModel: StatsViewModel = viewModel(factory = StatsViewModel.factory(appContainer))
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(appContainer))

    MusicTrackerNavHost(
        navController = navController,
        homeContent = {
            HomeRoute(
                viewModel = homeViewModel,
                clock = appContainer.clock,
                systemPermissionHelper = appContainer.systemPermissionHelper
            )
        },
        statsContent = {
            StatsRoute(viewModel = statsViewModel)
        },
        settingsContent = {
            SettingsRoute(
                viewModel = settingsViewModel,
                systemPermissionHelper = appContainer.systemPermissionHelper
            )
        },
        modifier = modifier
    )
}

@Composable
fun MusicTrackerNavHost(
    homeContent: @Composable () -> Unit,
    statsContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit,
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier
) {
    val accessibility = LiquidGlassTheme.accessibility
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: AppDestination.HOME.route

    var isNavBarMinimized by remember { mutableStateOf(false) }
    val navScrollConnection = rememberNavBarScrollConnection { minimized ->
        isNavBarMinimized = if (accessibility.reduceMotion) false else minimized
    }

    val navItems = remember {
        AppDestination.entries.map { dest ->
            LiquidGlassNavItem(
                id = dest.route,
                label = dest.label,
                icon = dest.icon,
                selectedIcon = dest.selectedIcon,
                testTag = dest.testTag
            )
        }
    }

    LiquidGlassBackdropHost(
        modifier = modifier.fillMaxSize(),
        overlay = {
            ScrollEdgeContainer(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp)
                ) {
                    LiquidGlassNavBar(
                        items = navItems,
                        selectedId = currentRoute,
                        onSelect = { targetRoute ->
                            isNavBarMinimized = false
                            navController.navigate(targetRoute) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        isMinimized = isNavBarMinimized && !accessibility.reduceMotion,
                        modifier = Modifier.testTag(TestTags.NAV_BAR)
                    )
                }
            }
        }
    ) {
        NavHost(
            navController = navController,
            startDestination = AppDestination.HOME.route,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(navScrollConnection)
        ) {
            composable(AppDestination.HOME.route) { homeContent() }
            composable(AppDestination.STATS.route) { statsContent() }
            composable(AppDestination.SETTINGS.route) { settingsContent() }
        }
    }
}
