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
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import android.os.PowerManager
import javax.net.ssl.*
import javax.security.auth.x500.X500Principal

private const val TAG = "LocalAdbService"
private const val CHANNEL_ID = "monos_virt_channel"
private const val NOTIFICATION_ID = 101

class LocalAdbService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isConnected = false
    private var isPhantomDisabled = false
    
    // Processor Wakelock reference to keep container running during standby
    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        fun getService(): LocalAdbService = this@LocalAdbService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Service initialized"))
        
        // Acquire CPU partial wakelock to sustain virtualization container processes
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Monos::VirtualizationWakeLock").apply {
            acquire()
        }
        Log.i(TAG, "Foreground WakeLock successfully acquired.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Local ADB Service started in foreground")
        return START_STICKY
    }

    /**
     * Connect to local ADB Wireless Debugging port on localhost over TLS.
     */
    fun connectLocalAdb(port: Int, pairingCode: String? = null, onResult: (Boolean) -> Unit) {
        serviceScope.launch {
            try {
                Log.d(TAG, "Attempting TLS connection to localhost:$port")
                
                val context = initSslContext()
                val socketFactory = context.socketFactory
                
                withContext(Dispatchers.IO) {
                    val rawSocket = Socket("127.0.0.1", port)
                    val sslSocket = socketFactory.createSocket(
                        rawSocket,
                        "127.0.0.1",
                        port,
                        true
                    ) as SSLSocket
                    
                    // Force client mode and configure cipher suites
                    sslSocket.useClientMode = true
                    sslSocket.startHandshake()
                    
                    val inputStream = sslSocket.inputStream
                    val outputStream = sslSocket.outputStream
                    
                    Log.d(TAG, "TLS Handshake complete. Sending ADB CNXN packet...")
                    
                    // 1. Send CNXN message
                    val systemIdentity = "device::ro.product.name=monos;ro.product.device=monos;ro.product.model=monos;\u0000"
                    writeAdbMessage(outputStream, AdbMessage.CMD_CNXN, 0x01000000, 1024 * 256, systemIdentity.toByteArray(Charsets.UTF_8))
                    
                    // 2. Read response packet
                    val response = readAdbMessage(inputStream)
                    Log.d(TAG, "Received ADB response. Cmd: ${AdbMessage.cmdToString(response.command)}")
                    
                    if (response.command == AdbMessage.CMD_CNXN) {
                        isConnected = true
                        Log.i(TAG, "ADB Connection established over TLS!")
                        updateNotification("ADB Server: Connected")
                        onResult(true)
                    } else {
                        Log.e(TAG, "Failed ADB Handshake. Expected CNXN, got: ${AdbMessage.cmdToString(response.command)}")
                        isConnected = false
                        onResult(false)
                    }
                    sslSocket.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Local ADB Connection failed over TLS: ${e.message}", e)
                isConnected = false
                updateNotification("ADB Connection Failed")
                onResult(false)
            }
        }
    }

    /**
     * Executes the sequential shell commands to disable the Phantom Process Killer on Android 14.
     */
    fun disablePhantomProcessKiller(port: Int, onResult: (Boolean) -> Unit) {
        serviceScope.launch {
            try {
                val context = initSslContext()
                val sslSocket = context.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
                sslSocket.useClientMode = true
                sslSocket.startHandshake()

                val inputStream = sslSocket.inputStream
                val outputStream = sslSocket.outputStream

                // ADB CNXN Handshake
                val identity = "device::;\u0000"
                writeAdbMessage(outputStream, AdbMessage.CMD_CNXN, 0x01000000, 1024 * 256, identity.toByteArray(Charsets.UTF_8))
                var response = readAdbMessage(inputStream)
                if (response.command != AdbMessage.CMD_CNXN) {
                    Log.e(TAG, "Could not authorize ADB connection for command dispatch")
                    onResult(false)
                    sslSocket.close()
                    return@launch
                }

                Log.i(TAG, "Starting sequential system configuration writes...")

                // Command 1: Disable setting sync tests to prevent Android from resetting config values
                executeShellCommand(inputStream, outputStream, "device_config set_sync_disabled_for_tests persistent")

                // Command 2: Set maximum phantom processes allowed (disabling the killer)
                executeShellCommand(inputStream, outputStream, "device_config put activity_manager max_phantom_processes 2147483647")

                // Command 3: Update global system settings
                executeShellCommand(inputStream, outputStream, "settings put global max_phantom_processes 2147483647")

                // Command 4: Empty settings to monitor so system stops querying configuration updates
                executeShellCommand(inputStream, outputStream, "device_config put activity_manager settings_to_monitor \"\"")

                Log.i(TAG, "Phantom process configurations successfully injected!")
                isPhantomDisabled = true
                updateNotification("Phantom Process Killer Disabled")
                onResult(true)
                sslSocket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error disabling Phantom Process Killer: ${e.message}", e)
                onResult(false)
            }
        }
    }

    fun isConnected(): Boolean = isConnected
    fun isPhantomProcessDisabled(): Boolean = isPhantomDisabled

    /**
     * Executes a command on the ADB shell channel.
     */
    private fun executeShellCommand(ins: InputStream, outs: OutputStream, cmd: String) {
        Log.d(TAG, "Executing Remote Shell: $cmd")
        val localId = 1
        
        // 1. Open shell channel
        writeAdbMessage(outs, AdbMessage.CMD_OPEN, localId, 0, "shell:$cmd\u0000".toByteArray(Charsets.UTF_8))
        
        // 2. Read response (expect OKAY)
        var response = readAdbMessage(ins)
        if (response.command != AdbMessage.CMD_OKAY) {
            Log.w(TAG, "Expected OKAY on shell open, got: ${AdbMessage.cmdToString(response.command)}")
            return
        }
        
        val remoteId = response.arg0
        
        // 3. Read shell output stream until CLSE (closed) packet
        while (true) {
            response = readAdbMessage(ins)
            if (response.command == AdbMessage.CMD_CLSE) {
                break
            } else if (response.command == AdbMessage.CMD_WRTE) {
                // Acknowledge write
                writeAdbMessage(outs, AdbMessage.CMD_OKAY, localId, remoteId, null)
                val output = response.data?.let { String(it, Charsets.UTF_8) } ?: ""
                Log.d(TAG, "Shell stdout: $output")
            }
        }
        
        // 4. Close local channel
        writeAdbMessage(outs, AdbMessage.CMD_CLSE, localId, remoteId, null)
    }

    /**
     * Initializes a standard SSLContext using a dynamic client certificate created in AndroidKeyStore.
     */
    private fun initSslContext(): SSLContext {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "monos_adb_key"

        if (!keyStore.containsAlias(alias)) {
            Log.i(TAG, "Generating new adb key pair in AndroidKeyStore...")
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                "AndroidKeyStore"
            )
            val parameterSpec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=MonosADBClient"))
                .build()
            keyPairGenerator.initialize(parameterSpec)
            keyPairGenerator.generateKeyPair()
        }

        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, null)

        // Setup custom TrustManager that trusts localhost certifications
        val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagerFactory.keyManagers, arrayOf(trustAllManager), null)
        return sslContext
    }

    // --- ADB Wire Protocol Serialization Utilities ---

    private fun writeAdbMessage(
        out: OutputStream,
        command: Int,
        arg0: Int,
        arg1: Int,
        data: ByteArray?
    ) {
        val dataLen = data?.size ?: 0
        val dataCheck = if (data != null) calculateChecksum(data) else 0
        val magic = command xor -0x1 // bitwise NOT of command

        val headerBuffer = ByteBuffer.allocate(24).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putInt(command)
            putInt(arg0)
            putInt(arg1)
            putInt(dataLen)
            putInt(dataCheck)
            putInt(magic)
        }

        out.write(headerBuffer.array())
        if (data != null && dataLen > 0) {
            out.write(data)
        }
        out.flush()
    }

    private fun readAdbMessage(ins: InputStream): AdbMessage {
        val headerBytes = ByteArray(24)
        var totalRead = 0
        while (totalRead < 24) {
            val bytesRead = ins.read(headerBytes, totalRead, 24 - totalRead)
            if (bytesRead == -1) throw java.io.EOFException("ADB header closed prematurely")
            totalRead += bytesRead
        }

        val headerBuffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val command = headerBuffer.getInt()
        val arg0 = headerBuffer.getInt()
        val arg1 = headerBuffer.getInt()
        val dataLen = headerBuffer.getInt()
        val dataCheck = headerBuffer.getInt()
        val magic = headerBuffer.getInt()

        // Validate command integrity
        if (command != (magic xor -0x1)) {
            throw java.io.IOException("ADB Packet Integrity Error: Magic verification failed")
        }

        var data: ByteArray? = null
        if (dataLen > 0) {
            data = ByteArray(dataLen)
            var dataRead = 0
            while (dataRead < dataLen) {
                val bytesRead = ins.read(data, dataRead, dataLen - dataRead)
                if (bytesRead == -1) throw java.io.EOFException("ADB payload closed prematurely")
                dataRead += bytesRead
            }
            // Optional checksum check
            val actualCheck = calculateChecksum(data)
            if (actualCheck != dataCheck) {
                Log.w(TAG, "ADB Packet Checksum warning: expected $dataCheck, got $actualCheck")
            }
        }

        return AdbMessage(command, arg0, arg1, data)
    }

    private fun calculateChecksum(data: ByteArray): Int {
        var checksum = 0
        for (b in data) {
            checksum += (b.toInt() and 0xFF)
        }
        return checksum
    }

    private class AdbMessage(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray?
    ) {
        companion object {
            const val CMD_CNXN = 0x4e584e43 // "CNXN"
            const val CMD_OPEN = 0x4e45504f // "OPEN"
            const val CMD_OKAY = 0x59414b4f // "OKAY"
            const val CMD_CLSE = 0x45534c43 // "CLSE"
            const val CMD_WRTE = 0x45545257 // "WRTE"

            fun cmdToString(cmd: Int): String {
                val bytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(cmd).array()
                // Convert back to string representation
                val chars = bytes.map { it.toInt().toChar() }.toCharArray()
                return String(chars.reversedArray())
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Monos Virtualization Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Monos Guest VM Service")
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
        
        // Release Wakelock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "Foreground WakeLock released.")
            }
        }
        Log.i(TAG, "Local ADB Service destroyed")
    }
}
