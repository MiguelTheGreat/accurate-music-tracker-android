package com.musictracker.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.musictracker.app.data.repository.ListeningRepository
import com.musictracker.app.di.AppContainer
import com.musictracker.app.domain.clock.TrackerClock
import com.musictracker.app.domain.tracking.PlaySessionEngine
import com.musictracker.app.tracking.SystemPermissionHelper
import com.musictracker.app.ui.common.startOfTodayEpochMs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val listeningRepository: ListeningRepository,
    private val playSessionEngine: PlaySessionEngine,
    private val systemPermissionHelper: SystemPermissionHelper,
    private val clock: TrackerClock,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) : ViewModel() {

    private val isNotificationAccessGrantedFlow = MutableStateFlow(
        systemPermissionHelper.isNotificationAccessGranted()
    )
    private val isBannerDismissedFlow = MutableStateFlow(false)
    private val isFullHistoryUnlockedFlow = MutableStateFlow(false)
    private val todayStartEpochMsFlow = MutableStateFlow(
        startOfTodayEpochMs(clock.currentTimeMillis(), zoneId)
    )

    private val todaySummaryFlow = todayStartEpochMsFlow.flatMapLatest { sinceEpochMs ->
        listeningRepository.observeSummary(sinceEpochMs)
    }

    private val recentHistoryFlow = isFullHistoryUnlockedFlow.flatMapLatest { unlocked ->
        val queryLimit = if (unlocked) FULL_HISTORY_LIMIT else INITIAL_RECENT_HISTORY_LIMIT + 1
        listeningRepository.observeRecentHistory(limit = queryLimit)
    }

    private val historyStateFlow = combine(
        recentHistoryFlow,
        isFullHistoryUnlockedFlow
    ) { items, unlocked ->
        val hasMore = !unlocked && items.size > INITIAL_RECENT_HISTORY_LIMIT
        val visibleItems = if (unlocked) items else items.take(INITIAL_RECENT_HISTORY_LIMIT)
        Triple(visibleItems, unlocked, hasMore)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        playSessionEngine.nowTracking,
        todaySummaryFlow,
        historyStateFlow,
        isNotificationAccessGrantedFlow,
        isBannerDismissedFlow
    ) { nowTracking, todaySummary, historyState, hasNotifAccess, isDismissed ->
        val (visibleHistory, unlocked, hasMore) = historyState
        HomeUiState(
            isNotificationAccessGranted = hasNotifAccess,
            isPermissionBannerDismissed = isDismissed,
            nowTracking = nowTracking,
            todaySummary = todaySummary,
            recentHistory = visibleHistory,
            isFullHistoryUnlocked = unlocked,
            hasMoreHistory = hasMore,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HomeUiState(
            isNotificationAccessGranted = isNotificationAccessGrantedFlow.value,
            isLoading = true
        )
    )

    fun refreshPermissionAndDay() {
        val hasAccess = systemPermissionHelper.isNotificationAccessGranted()
        isNotificationAccessGrantedFlow.value = hasAccess
        if (hasAccess) {
            isBannerDismissedFlow.value = false
        }
        todayStartEpochMsFlow.value = startOfTodayEpochMs(clock.currentTimeMillis(), zoneId)
    }

    fun dismissPermissionBanner() {
        isBannerDismissedFlow.value = true
    }

    fun unlockFullHistory() {
        isFullHistoryUnlockedFlow.value = true
    }

    fun collapseHistory() {
        isFullHistoryUnlockedFlow.value = false
    }

    companion object {
        private const val INITIAL_RECENT_HISTORY_LIMIT = 10
        private const val FULL_HISTORY_LIMIT = 500

        fun factory(appContainer: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(
                        listeningRepository = appContainer.listeningRepository,
                        playSessionEngine = appContainer.playSessionEngine,
                        systemPermissionHelper = appContainer.systemPermissionHelper,
                        clock = appContainer.clock
                    ) as T
                }
            }
    }
}
