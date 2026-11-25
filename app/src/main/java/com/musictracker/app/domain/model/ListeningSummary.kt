package com.musictracker.app.domain.model

data class ListeningSummary(
    val totalListenedMs: Long = 0L,
    val playCount: Int = 0,
    val skippedCount: Int = 0,
    val skipRate: Float = 0f
)
