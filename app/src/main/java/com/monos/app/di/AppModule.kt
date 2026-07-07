package com.monos.app.di

import android.content.Context
import com.monos.app.network.DriveDownloaderRepository
import com.monos.app.ui.components.IDisplayServer
import com.monos.app.ui.components.VncDisplayClient
import com.monos.app.ui.components.X11DisplayServer
import com.monos.app.virtualization.ContingencyManager
import com.monos.app.virtualization.ProotRunner
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Clean Service Locator pattern providing Dependency Injection framework.
 * Resolves EGL or VNC rendering strategies dynamically based on user contingency profiles.
 */
class AppModule(private val context: Context) {

    // Network Client provider with logging
    private val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // Google Drive Downloader Repository singleton
    val driveDownloaderRepository: DriveDownloaderRepository by lazy {
        DriveDownloaderRepository(okHttpClient)
    }

    // PRoot Guest VM runner singleton
    val prootRunner: ProotRunner by lazy {
        ProotRunner(context.applicationContext)
    }

    // Contingency Configuration Manager singleton
    val contingencyManager: ContingencyManager by lazy {
        ContingencyManager(context.applicationContext)
    }

    /**
     * Resolves the display server rendering strategy dynamically based on contingency settings.
     */
    fun getDisplayServer(): IDisplayServer {
        val strategy = contingencyManager.getDisplayStrategy()
        return if (strategy == ContingencyManager.STRATEGY_DISPLAY_NDK) {
            X11DisplayServer()
        } else {
            VncDisplayClient()
        }
    }
}
