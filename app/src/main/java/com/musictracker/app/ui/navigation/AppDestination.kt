package com.musictracker.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppDestination(
    val route: String,
    val label: String,
    val testTag: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
) {
    HOME(
        route = "home",
        label = "Home",
        testTag = TestTags.NAV_TAB_HOME,
        icon = Icons.Outlined.Home,
        selectedIcon = Icons.Filled.Home
    ),
    STATS(
        route = "stats",
        label = "Stats",
        testTag = TestTags.NAV_TAB_STATS,
        icon = Icons.Outlined.BarChart,
        selectedIcon = Icons.Filled.BarChart
    ),
    SETTINGS(
        route = "settings",
        label = "Settings",
        testTag = TestTags.NAV_TAB_SETTINGS,
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Filled.Settings
    )
}
