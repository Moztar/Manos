package com.monos.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.monos.app.di.AppModule
import com.monos.app.network.DownloadStatus
import com.monos.app.ui.MainScreen
import com.monos.app.ui.X11DisplayViewModel
import com.monos.app.ui.ScreenOrientation
import com.monos.app.ui.theme.MonosTheme
import com.monos.app.virtualization.LocalAdbService
import com.monos.app.virtualization.UbuntuTelemetryService
import com.monos.app.virtualization.ClipboardSyncManager
import com.monos.app.virtualization.ContingencyManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    // Dependency Injection Module
    private lateinit var appModule: AppModule
    private var downloadJob: Job? = null

    // Service binding state for dynamic ADB management
    private var localAdbService: LocalAdbService? = null
    private var isServiceBound = false

    // Graphical display manager ViewModel
    private val displayViewModel = X11DisplayViewModel()

    // Dynamic states collected by Compose
    private var downloadState by mutableStateOf<DownloadStatus>(DownloadStatus.Idle)
    private var isVirtualizationActive by mutableStateOf(false)
    private var isAdbConnected by mutableStateOf(false)
    private var isPhantomDisabled by mutableStateOf(false)

    // Wireless debugging default port configuration
    private val adbPort = 5555 // Typically configured port for testing

    // Real-time bidirectional clipboard sync manager
    private lateinit var clipboardSyncManager: ClipboardSyncManager

    // Telemetry Service bindings
    private var telemetryService: UbuntuTelemetryService? = null
    private var isTelemetryBound = false

    private val telemetryConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as UbuntuTelemetryService.LocalBinder
            telemetryService = binder.getService()
            isTelemetryBound = true
            
            // Hook telemetry focus to ViewModel Auto-Open triggers
            telemetryService?.setFocusListener { autoOpen ->
                displayViewModel.updateAutoOpenSoftKeyboard(autoOpen)
                Log.d(TAG, "Telemetry D-Bus hook triggered: autoOpen=$autoOpen")
            }
            
            // Hook telemetry incoming clipboard updates to sync manager
            telemetryService?.setClipboardListener { text ->
                clipboardSyncManager.updateClipboardFromUbuntu(text)
            }
            Log.i(TAG, "Ubuntu Telemetry Service bound successfully.")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            telemetryService = null
            isTelemetryBound = false
            Log.i(TAG, "Ubuntu Telemetry Service disconnected.")
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LocalAdbService.LocalBinder
            localAdbService = binder.getService()
            isServiceBound = true
            
            // Sync status with service
            isAdbConnected = localAdbService?.isConnected() ?: false
            isPhantomDisabled = localAdbService?.isPhantomProcessDisabled() ?: false
            Log.i(TAG, "Local ADB Service bound. Connected: $isAdbConnected, Phantom: $isPhantomDisabled")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            localAdbService = null
            isServiceBound = false
            Log.i(TAG, "Local ADB Service unbound")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable Immersive System UI (Status/Navigation bars hidden)
        enableFullscreenImmersiveMode()

        // Initialize dependency graph
        appModule = AppModule(this)

        // Initialize Clipboard Sync Manager
        clipboardSyncManager = ClipboardSyncManager(this)

        // Bind Foreground Services
        val bindIntent = Intent(this, LocalAdbService::class.java)
        startService(bindIntent)
        bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        val telemetryIntent = Intent(this, UbuntuTelemetryService::class.java)
        startService(telemetryIntent)
        bindService(telemetryIntent, telemetryConnection, Context.BIND_AUTO_CREATE)

        setContent {
            val displayState by displayViewModel.displayState.collectAsState()
            
            MonosTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        downloadStatus = downloadState,
                        virtualizationActive = isVirtualizationActive,
                        adbConnected = isAdbConnected,
                        phantomProcessDisabled = isPhantomDisabled,
                        displayState = displayState,
                        displayViewModel = displayViewModel,
                        onStartDownload = { fileId -> handleStartDownload(fileId) },
                        onPauseDownload = { handlePauseDownload() },
                        onToggleVirtualization = { handleToggleVirtualization() },
                        onConnectAdb = { handleConnectAdb() },
                        onDisablePhantomKiller = { handleDisablePhantom() }
                    )
                }
            }
        }

        // Screen Orientation Hook: listen to orientation changes in StateFlow
        lifecycleScope.launch {
            displayViewModel.displayState.collect { state ->
                val targetOrientation = if (state.orientation == ScreenOrientation.PORTRAIT) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                if (requestedOrientation != targetOrientation) {
                    requestedOrientation = targetOrientation
                    Log.i(TAG, "Orientation Hook: requestOrientation altered to $requestedOrientation")
                }
            }
        }

        // Periodically monitor virtualization running state
        lifecycleScope.launch {
            while (true) {
                isVirtualizationActive = appModule.prootRunner.isContainerRunning()
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun enableFullscreenImmersiveMode() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        // Configure behavior to show transient bars by swipe
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // Hide system bars (status + navigation)
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        Log.i(TAG, "Immersive screen settings successfully applied.")
    }

    private fun handleStartDownload(fileId: String) {
        val rootfsDir = File(filesDir, "ubuntu_rootfs")
        if (!rootfsDir.exists()) {
            rootfsDir.mkdirs()
        }
        val targetArchive = File(filesDir, "ubuntu_rootfs.tar.gz")

        // Cancel previous download subscription if running
        downloadJob?.cancel()
        downloadJob = lifecycleScope.launch {
            appModule.driveDownloaderRepository.downloadDriveFile(
                fileId = fileId,
                destinationFile = targetArchive
            ).collectLatest { status ->
                downloadState = status
                if (status is DownloadStatus.Success) {
                    Toast.makeText(this@MainActivity, "Download Complete! Extracting rootfs...", Toast.LENGTH_LONG).show()
                    // Initiate background extraction and VM prepare step
                    val isConfigured = appModule.prootRunner.prepareGuestEnvironment(rootfsDir)
                    if (isConfigured) {
                        Log.i(TAG, "Guest environment preparation successful.")
                    }
                }
            }
        }
    }

    private fun handlePauseDownload() {
        appModule.driveDownloaderRepository.pauseDownload()
    }

    private fun handleToggleVirtualization() {
        lifecycleScope.launch {
            val isRunning = appModule.prootRunner.isContainerRunning()
            if (isRunning) {
                appModule.prootRunner.terminateContainer()
                isVirtualizationActive = false
                Toast.makeText(this@MainActivity, "PRoot VM stopped.", Toast.LENGTH_SHORT).show()
            } else {
                val rootfsDir = File(filesDir, "ubuntu_rootfs")
                // Check setup prior to launch
                val launchSuccess = appModule.prootRunner.launchContainer(rootfsDir)
                if (launchSuccess) {
                    isVirtualizationActive = true
                    Toast.makeText(this@MainActivity, "PRoot container active.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to launch container. Verify rootfs.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleConnectAdb() {
        localAdbService?.connectLocalAdb(adbPort) { success ->
            lifecycleScope.launch {
                isAdbConnected = success
                if (success) {
                    Toast.makeText(this@MainActivity, "Local ADB Connected to port $adbPort", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed local ADB connection. Ensure Wireless Debugging is on.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleDisablePhantom() {
        localAdbService?.disablePhantomProcessKiller(adbPort) { success ->
            lifecycleScope.launch {
                isPhantomDisabled = success
                if (success) {
                    Toast.makeText(this@MainActivity, "Phantom Process Killer Disabled!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to disable phantom killer. Verify ADB.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        clipboardSyncManager.startSync()
        
        // Soft Keyboard supresi contingency: Apply WindowManager FLAG_ALT_FOCUSABLE_IM if enabled
        val contingencyManager = appModule.contingencyManager
        if (contingencyManager.getKeyboardStrategy() == ContingencyManager.STRATEGY_KEYBOARD_IM_FLAG) {
            window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            Log.d(TAG, "Contingency Fallback: System ALT_FOCUSABLE_IM flag applied to window.")
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            Log.d(TAG, "Standard IME: ALT_FOCUSABLE_IM flag cleared from window.")
        }
    }

    override fun onPause() {
        super.onPause()
        clipboardSyncManager.stopSync()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        if (isTelemetryBound) {
            unbindService(telemetryConnection)
            isTelemetryBound = false
        }
    }
}
