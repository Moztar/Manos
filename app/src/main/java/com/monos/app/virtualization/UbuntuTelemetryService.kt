package com.monos.app.virtualization

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "UbuntuTelemetryService"
private const val CHANNEL_ID = "monos_telemetry_channel"
private const val NOTIFICATION_ID = 102

class UbuntuTelemetryService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var focusListener: ((Boolean) -> Unit)? = null
    private var clipboardListener: ((String) -> Unit)? = null
    
    private var serverSocket: ServerSocket? = null
    private var clipboardServerSocket: ServerSocket? = null

    inner class LocalBinder : Binder() {
        fun getService(): UbuntuTelemetryService = this@UbuntuTelemetryService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Socket servers idle"))
        startSocketServer()
        startClipboardSocketServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Ubuntu Telemetry & Clipboard Service running...")
        return START_STICKY
    }

    /**
     * Registers a callback to forward text focus states to the UI thread.
     */
    fun setFocusListener(listener: (Boolean) -> Unit) {
        this.focusListener = listener
    }

    /**
     * Registers a callback to forward incoming guest clipboard updates.
     */
    fun setClipboardListener(listener: (String) -> Unit) {
        this.clipboardListener = listener
    }

    /**
     * Starts the lightweight TCP socket server listening to localhost:9005 for focus.
     */
    private fun startSocketServer() {
        serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    serverSocket = ServerSocket(9005)
                    Log.i(TAG, "AT-SPI2 Socket server listening on 127.0.0.1:9005")
                    updateNotification("Listening for Focus (9005) & Clipboard (9006) on 127.0.0.1")
                    
                    while (isActive) {
                        val clientSocket = serverSocket?.accept() ?: break
                        launch {
                            handleClientConnection(clientSocket)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Telemetry socket server error: ${e.message}")
            }
        }
    }

    /**
     * Starts the lightweight TCP socket server listening to localhost:9006 for clipboard.
     */
    private fun startClipboardSocketServer() {
        serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    clipboardServerSocket = ServerSocket(9006)
                    Log.i(TAG, "Clipboard Sync Socket server listening on 127.0.0.1:9006")
                    
                    while (isActive) {
                        val clientSocket = clipboardServerSocket?.accept() ?: break
                        launch {
                            handleClipboardConnection(clientSocket)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Clipboard socket server error: ${e.message}")
            }
        }
    }

    private suspend fun handleClientConnection(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val reader = socket.getInputStream().bufferedReader()
                Log.d(TAG, "Telemetry client connected")

                while (isActive) {
                    val line = reader.readLine() ?: break
                    
                    if (line.contains("FOCUS_IN:ROLE_TEXT") || line.contains("FOCUS_IN:ROLE_TERMINAL")) {
                        withContext(Dispatchers.Main) {
                            focusListener?.invoke(true)
                        }
                    } else if (line.contains("FOCUS_OUT") || line.contains("ROLE_NONE")) {
                        withContext(Dispatchers.Main) {
                            focusListener?.invoke(false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Telemetry read error: ${e.message}")
            } finally {
                try {
                    socket.close()
                } catch (e: Exception) { }
            }
            Unit
        }
    }

    private suspend fun handleClipboardConnection(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                // Read complete streamed clipboard content payload (UTF-8)
                val textPayload = socket.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
                Log.d(TAG, "Read guest clipboard payload (Size: ${textPayload.length} chars)")
                
                if (textPayload.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        clipboardListener?.invoke(textPayload)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Clipboard connection read error: ${e.message}")
            } finally {
                try {
                    socket.close()
                } catch (e: Exception) { }
            }
            Unit
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Monos Telemetry Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Monos Telemetry Server")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try {
            serverSocket?.close()
        } catch (e: Exception) { }
        try {
            clipboardServerSocket?.close()
        } catch (e: Exception) { }
        Log.i(TAG, "Ubuntu Telemetry Service stopped")
    }
}
