package com.musictracker.app.ui.screens.home

import androidx.compose.runtime.Immutable
import com.musictracker.app.domain.model.ListeningSummary
import com.musictracker.app.domain.model.NowTrackingState
import com.musictracker.app.domain.model.RecentListenItem
import com.musictracker.app.domain.model.SuggestedPlayer

@Immutable
data class HomeUiState(
    val isNotificationAccessGranted: Boolean = true,
    val isPermissionBannerDismissed: Boolean = false,
    val suggestedPlayer: SuggestedPlayer? = null,
    val nowTracking: NowTrackingState? = null,
    val todaySummary: ListeningSummary = ListeningSummary(),
    val recentHistory: List<RecentListenItem> = emptyList(),
    val isFullHistoryUnlocked: Boolean = false,
    val hasMoreHistory: Boolean = false,
    val nowTrackingArtworkPath: String? = null,
    val recentArtworkPaths: Map<Long, String> = emptyMap(),
    val isLoading: Boolean = true
) {
    val showPermissionBanner: Boolean
        get() = !isNotificationAccessGranted && !isPermissionBannerDismissed
}
