package com.musictracker.app.domain.model

sealed interface ExportResult {
    data class Success(
        val bytesWritten: Long,
        val trackCount: Int,
        val listenEventCount: Int
    ) : ExportResult

    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : ExportResult
}
