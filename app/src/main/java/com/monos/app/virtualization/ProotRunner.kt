package com.monos.app.virtualization

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ProotRunner"

class ProotRunner(private val context: Context) {

    companion object {
        init {
            System.loadLibrary("monos_native")
        }
    }

    private var activePid: Int = -1

    // JNI Declarations
    private external fun startPRootNative(
        cmdArgs: Array<String>,
        workingDir: String,
        envVars: Array<String>
    ): Int

    private external fun isProcessRunning(pid: Int): Boolean

    /**
     * Prepares filesystem directories and mounts for PRoot execution.
     */
    suspend fun prepareGuestEnvironment(rootfsPath: File): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Preparing guest VM mounts inside: ${rootfsPath.absolutePath}")
            
            // Check if rootfs exists
            if (!rootfsPath.exists() || !rootfsPath.isDirectory) {
                Log.e(TAG, "Rootfs target directory does not exist. Please download and extract first.")
                return@withContext false
            }

            // Set executable permission on binaries if necessary
            val usrBin = File(rootfsPath, "usr/bin")
            if (usrBin.exists()) {
                usrBin.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        file.setExecutable(true, false)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring guest environment: ${e.message}")
            false
        }
    }

    /**
     * Triggers the launch of the PRoot process in JNI.
     */
    suspend fun launchContainer(rootfsDir: File, commandToRun: String = "/bin/bash"): Boolean = withContext(Dispatchers.IO) {
        if (isContainerRunning()) {
            Log.w(TAG, "PRoot container is already active (PID: $activePid)")
            return@withContext true
        }

        try {
            val appFilesDir = context.filesDir.absolutePath
            val guestRoot = rootfsDir.absolutePath

            // Construct standard PRoot execution arguments:
            // -0: Mock root privileges
            // -r: Set guest rootfs path
            // -b: Mount bindings (/dev, /proc, /sys)
            // -w: Set guest working directory
            val argsList = mutableListOf(
                "$appFilesDir/bin/proot", // Path to the PRoot native binary
                "-0",
                "-r", guestRoot,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-w", "/root",
                commandToRun
            )

            // Setup default Linux terminal environment variables
            val envList = arrayOf(
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM=xterm-256color",
                "HOME=/root",
                "USER=root",
                "LANG=C.UTF-8"
            )

            Log.i(TAG, "Executing JNI PRoot with Args: $argsList")
            
            val pid = startPRootNative(
                argsList.toTypedArray(),
                guestRoot,
                envList
            )

            if (pid > 0) {
                activePid = pid
                Log.i(TAG, "PRoot VM container launched successfully under PID $pid")
                true
            } else {
                Log.e(TAG, "PRoot runner JNI returned error PID: $pid")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch PRoot virtualization: ${e.message}")
            false
        }
    }

    /**
     * Check container running state.
     */
    fun isContainerRunning(): Boolean {
        if (activePid <= 0) return false
        val running = isProcessRunning(activePid)
        if (!running) {
            activePid = -1
        }
        return running
    }

    /**
     * Terminates the active container process.
     */
    fun terminateContainer() {
        if (activePid > 0) {
            Log.i(TAG, "Sending SIGTERM to container process PID: $activePid")
            android.os.Process.sendSignal(activePid, 15) // SIGTERM
            activePid = -1
        }
    }
}
