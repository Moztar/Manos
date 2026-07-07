package com.monos.app.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.regex.Pattern

private const val TAG = "DriveDownloaderRepo"

class DriveDownloaderRepository(
    private var httpClient: OkHttpClient = OkHttpClient()
) {
    private val _downloadState = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
    val downloadState = _downloadState.asStateFlow()

    private var isPaused = false

    // Simple in-memory cookie storage to persist download warning confirmations
    private val cookieStore = HashMap<String, List<Cookie>>()

    init {
        // Re-initialize OkHttpClient with cookie storage support
        httpClient = httpClient.newBuilder()
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    cookieStore[url.host] = cookies
                    Log.d(TAG, "Cookies saved from response: ${cookies.joinToString { it.name }}")
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    return cookieStore[url.host] ?: emptyList()
                }
            })
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun pauseDownload() {
        isPaused = true
        val current = _downloadState.value
        if (current is DownloadStatus.Downloading) {
            _downloadState.value = DownloadStatus.Paused(
                progress = current.progress,
                downloadedBytes = current.downloadedBytes,
                totalBytes = current.totalBytes
            )
            Log.i(TAG, "Download paused at ${current.downloadedBytes} bytes.")
        }
    }

    /**
     * Downloads a file from Google Drive.
     * Automatically handles Google Drive large file virus warning redirections and extracts cookies.
     *
     * @param fileId Google Drive File ID.
     * @param destinationFile Local destination file path.
     */
    fun downloadDriveFile(
        fileId: String,
        destinationFile: File
    ): Flow<DownloadStatus> = flow {
        isPaused = false
        
        val startByte = if (destinationFile.exists()) destinationFile.length() else 0L
        Log.i(TAG, "Initiating download. Local progress: $startByte bytes.")

        // Primary Google Drive export file URL
        var downloadUrl = "https://docs.google.com/uc?export=download&id=$fileId"
        
        // 1. Initial Probe request to check if it points to a virus check warning HTML
        var request = Request.Builder().url(downloadUrl).get().build()
        
        try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body
                val contentType = body?.contentType()?.toString() ?: ""
                
                // If Google returns HTML instead of a binary stream, we must parse the virus scan confirmation token
                if (contentType.contains("text/html", ignoreCase = true)) {
                    val htmlContent = body?.string() ?: ""
                    val confirmToken = extractConfirmToken(htmlContent)
                    
                    if (!confirmToken.isNullOrBlank()) {
                        Log.i(TAG, "Google Drive large file redirect token found: $confirmToken")
                        downloadUrl = "https://docs.google.com/uc?export=download&id=$fileId&confirm=$confirmToken"
                    } else {
                        Log.w(TAG, "Failed to parse confirmation token from HTML content. Attempting raw download...")
                    }
                }
            }

            // 2. Perform actual download connection with range headers to support resuming
            val downloadRequestBuilder = Request.Builder()
                .url(downloadUrl)
                .get()

            if (startByte > 0) {
                downloadRequestBuilder.addHeader("Range", "bytes=$startByte-")
                Log.d(TAG, "Requesting byte Range: bytes=$startByte-")
            }

            request = downloadRequestBuilder.build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    if (code == 416) {
                        Log.i(TAG, "Range 416: File is already fully downloaded.")
                        emit(DownloadStatus.Success(destinationFile.absolutePath))
                        _downloadState.value = DownloadStatus.Success(destinationFile.absolutePath)
                        return@flow
                    }
                    val errorMsg = "Drive Downloader failed with code $code: ${response.message}"
                    Log.e(TAG, errorMsg)
                    emit(DownloadStatus.Error(errorMsg))
                    _downloadState.value = DownloadStatus.Error(errorMsg)
                    return@flow
                }

                val body = response.body
                if (body == null) {
                    val error = "Empty response body received from Google Drive"
                    emit(DownloadStatus.Error(error))
                    _downloadState.value = DownloadStatus.Error(error)
                    return@flow
                }

                val responseLength = body.contentLength()
                val totalBytes = if (startByte > 0) {
                    val contentRange = response.header("Content-Range")
                    val parsedTotal = contentRange?.substringAfterLast("/")?.toLongOrNull()
                    parsedTotal ?: (startByte + responseLength)
                } else {
                    responseLength
                }

                Log.i(TAG, "Streaming data. Size: $responseLength bytes. Target: $totalBytes bytes.")

                RandomAccessFile(destinationFile, "rw").use { raf ->
                    raf.seek(startByte)
                    val inputStream = body.byteStream()
                    val buffer = ByteArray(1024 * 128) // 128KB chunk buffers
                    var totalDownloaded = startByte
                    var read: Int
                    var lastUpdateTimestamp = 0L

                    while (inputStream.read(buffer).also { read = it } != -1) {
                        if (isPaused) {
                            emit(DownloadStatus.Paused(
                                progress = totalDownloaded.toFloat() / totalBytes,
                                downloadedBytes = totalDownloaded,
                                totalBytes = totalBytes
                            ))
                            body.close()
                            return@flow
                        }

                        raf.write(buffer, 0, read)
                        totalDownloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTimestamp > 150) {
                            val progress = totalDownloaded.toFloat() / totalBytes
                            val currentStatus = DownloadStatus.Downloading(
                                progress = progress,
                                downloadedBytes = totalDownloaded,
                                totalBytes = totalBytes
                            )
                            emit(currentStatus)
                            _downloadState.value = currentStatus
                            lastUpdateTimestamp = now
                        }
                    }

                    if (totalDownloaded >= totalBytes) {
                        Log.i(TAG, "Download successfully completed.")
                        val successStatus = DownloadStatus.Success(destinationFile.absolutePath)
                        emit(successStatus)
                        _downloadState.value = successStatus
                    } else {
                        val pauseStatus = DownloadStatus.Paused(
                            progress = totalDownloaded.toFloat() / totalBytes,
                            downloadedBytes = totalDownloaded,
                            totalBytes = totalBytes
                        )
                        emit(pauseStatus)
                        _downloadState.value = pauseStatus
                    }
                }
            }
        } catch (e: Exception) {
            val error = "Downloader exception: ${e.message}"
            Log.e(TAG, error, e)
            emit(DownloadStatus.Error(error))
            _downloadState.value = DownloadStatus.Error(error)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Regex utility to parse the Google Drive virus confirmation token.
     * Looks for confirm=XXXX parameter patterns in redirect warnings.
     */
    private fun extractConfirmToken(html: String): String? {
        val pattern = Pattern.compile("confirm=([a-zA-Z0-9_-]+)")
        val matcher = pattern.matcher(html)
        return if (matcher.find()) {
            matcher.group(1)
        } else {
            null
        }
    }
}
