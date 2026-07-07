package com.monos.app.network

sealed interface DownloadStatus {
    object Idle : DownloadStatus
    
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : DownloadStatus

    data class Paused(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : DownloadStatus

    data class Success(val filePath: String) : DownloadStatus

    data class Error(val message: String) : DownloadStatus
}
