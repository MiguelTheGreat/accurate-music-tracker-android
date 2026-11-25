package com.musictracker.app.data.export

import com.musictracker.app.domain.model.ExportResult
import java.io.OutputStream

interface DataExporter {
    suspend fun exportJson(outputStream: OutputStream): ExportResult
    suspend fun exportSqliteBackup(outputStream: OutputStream): ExportResult
}
