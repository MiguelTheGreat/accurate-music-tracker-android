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
    private val todayStartEpochMsFlow = MutableStateFlow(
        startOfTodayEpochMs(clock.currentTimeMillis(), zoneId)
    )

    private val todaySummaryFlow = todayStartEpochMsFlow.flatMapLatest { sinceEpochMs ->
        listeningRepository.observeSummary(sinceEpochMs)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        playSessionEngine.nowTracking,
        todaySummaryFlow,
        listeningRepository.observeRecentHistory(limit = RECENT_HISTORY_LIMIT),
        isNotificationAccessGrantedFlow,
        isBannerDismissedFlow
    ) { nowTracking, todaySummary, recentHistory, hasNotifAccess, isDismissed ->
        HomeUiState(
            isNotificationAccessGranted = hasNotifAccess,
            isPermissionBannerDismissed = isDismissed,
            nowTracking = nowTracking,
            todaySummary = todaySummary,
            recentHistory = recentHistory,
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
        isNotificationAccessGrantedFlow.value = systemPermissionHelper.isNotificationAccessGranted()
        todayStartEpochMsFlow.value = startOfTodayEpochMs(clock.currentTimeMillis(), zoneId)
    }

    fun dismissPermissionBanner() {
        isBannerDismissedFlow.value = true
    }

    companion object {
        private const val RECENT_HISTORY_LIMIT = 50

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
