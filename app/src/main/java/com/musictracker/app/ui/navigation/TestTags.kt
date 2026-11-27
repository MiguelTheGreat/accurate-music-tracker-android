package com.musictracker.app.ui.navigation

import com.musictracker.app.data.preferences.ThemeMode
import com.musictracker.app.domain.model.StatsTimeframe

/**
 * Centralized UI test tag contract matching PROJECT.md verbatim.
 */
object TestTags {
    // Navigation
    const val NAV_BAR = "NAV_BAR"
    const val NAV_TAB_HOME = "NAV_TAB_HOME"
    const val NAV_TAB_STATS = "NAV_TAB_STATS"
    const val NAV_TAB_SETTINGS = "NAV_TAB_SETTINGS"

    // Home
    const val HOME_PERMISSION_BANNER = "HOME_PERMISSION_BANNER"
    const val HOME_PERMISSION_ENABLE_BTN = "HOME_PERMISSION_ENABLE_BTN"
    const val HOME_NOW_TRACKING_CARD = "HOME_NOW_TRACKING_CARD"
    const val HOME_LIVE_COUNTER = "HOME_LIVE_COUNTER"
    const val HOME_STAT_TIME = "HOME_STAT_TIME"
    const val HOME_STAT_PLAYS = "HOME_STAT_PLAYS"
    const val HOME_STAT_SKIP_RATE = "HOME_STAT_SKIP_RATE"
    const val HOME_RECENT_LIST = "HOME_RECENT_LIST"
    fun homeRecentItem(eventId: Long): String = "HOME_RECENT_ITEM_$eventId"

    // Stats
    const val STATS_TIMEFRAME_CONTROL = "STATS_TIMEFRAME_CONTROL"
    const val STATS_TIMEFRAME_TODAY = "STATS_TIMEFRAME_TODAY"
    const val STATS_TIMEFRAME_7D = "STATS_TIMEFRAME_7D"
    const val STATS_TIMEFRAME_30D = "STATS_TIMEFRAME_30D"
    const val STATS_TIMEFRAME_ALL = "STATS_TIMEFRAME_ALL"
    const val STATS_TOP_TRACKS_LIST = "STATS_TOP_TRACKS_LIST"
    const val STATS_TOP_ARTISTS_LIST = "STATS_TOP_ARTISTS_LIST"
    fun statsTrackItem(trackId: Long): String = "STATS_TRACK_ITEM_$trackId"
    fun statsArtistItem(index: Int): String = "STATS_ARTIST_ITEM_$index"

    fun statsTimeframe(timeframe: StatsTimeframe): String = when (timeframe) {
        StatsTimeframe.TODAY -> STATS_TIMEFRAME_TODAY
        StatsTimeframe.SEVEN_DAYS -> STATS_TIMEFRAME_7D
        StatsTimeframe.THIRTY_DAYS -> STATS_TIMEFRAME_30D
        StatsTimeframe.ALL_TIME -> STATS_TIMEFRAME_ALL
    }

    // Settings
    const val SETTINGS_NOTIF_STATUS = "SETTINGS_NOTIF_STATUS"
    const val SETTINGS_NOTIF_BTN = "SETTINGS_NOTIF_BTN"
    const val SETTINGS_BATTERY_STATUS = "SETTINGS_BATTERY_STATUS"
    const val SETTINGS_BATTERY_BTN = "SETTINGS_BATTERY_BTN"
    const val SETTINGS_ALLOWLIST_SEARCH = "SETTINGS_ALLOWLIST_SEARCH"
    fun settingsAllowlistSwitch(packageName: String): String =
        "SETTINGS_ALLOWLIST_SWITCH_$packageName"
    const val SETTINGS_EXPORT_JSON_BTN = "SETTINGS_EXPORT_JSON_BTN"
    const val SETTINGS_EXPORT_SQLITE_BTN = "SETTINGS_EXPORT_SQLITE_BTN"
    const val SETTINGS_REDUCE_TRANSPARENCY_SWITCH = "SETTINGS_REDUCE_TRANSPARENCY_SWITCH"
    const val SETTINGS_EXPORT_FEEDBACK = "SETTINGS_EXPORT_FEEDBACK"
    const val SETTINGS_CLEAN_LIBRARY_BTN = "SETTINGS_CLEAN_LIBRARY_BTN"
    const val SETTINGS_CLEAN_LIBRARY_FEEDBACK = "SETTINGS_CLEAN_LIBRARY_FEEDBACK"
    const val SETTINGS_THEME_CONTROL = "SETTINGS_THEME_CONTROL"
    const val SETTINGS_THEME_SYSTEM = "SETTINGS_THEME_SYSTEM"
    const val SETTINGS_THEME_LIGHT = "SETTINGS_THEME_LIGHT"
    const val SETTINGS_THEME_DARK = "SETTINGS_THEME_DARK"
    const val SETTINGS_THEME_AMOLED = "SETTINGS_THEME_AMOLED"

    fun settingsThemeMode(mode: ThemeMode): String = when (mode) {
        ThemeMode.SYSTEM -> SETTINGS_THEME_SYSTEM
        ThemeMode.LIGHT -> SETTINGS_THEME_LIGHT
        ThemeMode.DARK -> SETTINGS_THEME_DARK
        ThemeMode.AMOLED -> SETTINGS_THEME_AMOLED
    }
}
