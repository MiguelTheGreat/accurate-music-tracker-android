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
    );

    companion object {
        fun fromRoute(route: String?): AppDestination? {
            if (route == null) return null
            return entries.firstOrNull { it.route == route }
        }
    }
}

/**
 * Returns the directional tab transition delta between [fromRoute] and [toRoute]:
 * - `+1` when moving to a tab on the right (higher ordinal)
 * - `-1` when moving to a tab on the left (lower ordinal)
 * - `0` when the routes match or either route is unknown
 */
internal fun tabTransitionDirection(fromRoute: String?, toRoute: String?): Int {
    val from = AppDestination.fromRoute(fromRoute) ?: return 0
    val to = AppDestination.fromRoute(toRoute) ?: return 0
    return when {
        to.ordinal > from.ordinal -> 1
        to.ordinal < from.ordinal -> -1
        else -> 0
    }
}

