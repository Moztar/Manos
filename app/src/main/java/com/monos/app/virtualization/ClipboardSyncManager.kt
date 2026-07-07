package com.monos.app.virtualization

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.util.Log

private const val TAG = "ClipboardSyncManager"

// JNI Endpoint mapping Android clipboard text injection to guest X11 selections
external fun sendClipboardToX11Native(text: String)

class ClipboardSyncManager(private val context: Context) {

    private val clipboard: ClipboardManager by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    // Cache to prevent infinite sync feedback loops between ecosystems
    @Volatile
    private var lastSyncedText: String = ""

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        handleAndroidClipboardChange()
    }

    private var isListening = false

    /**
     * Start Clipboard Synchronization.
     */
    fun startSync() {
        if (!isListening) {
            clipboard.addPrimaryClipChangedListener(clipListener)
            isListening = true
            Log.i(TAG, "Clipboard synchronization listeners registered.")
        }
    }

    /**
     * Stop Clipboard Synchronization.
     */
    fun stopSync() {
        if (isListening) {
            clipboard.removePrimaryClipChangedListener(clipListener)
            isListening = false
            Log.i(TAG, "Clipboard synchronization listeners unregistered.")
        }
    }

    /**
     * Processes Android clipboard edits and transmits them to Ubuntu.
     */
    private fun handleAndroidClipboardChange() {
        try {
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount == 0) return

            val description = clipboard.primaryClipDescription ?: return
            
            // Filter: Sync plain text formats exclusively, ignore binary/URI assets
            if (description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) {
                
                val item = clip.getItemAt(0)
                val clipText = item.text?.toString() ?: ""

                if (clipText.isNotEmpty() && clipText != lastSyncedText) {
                    // Update state hash cache to sever loopback triggers
                    lastSyncedText = clipText
                    Log.i(TAG, "Android Clipboard copied. Syncing to virtual guest (Size: ${clipText.length} chars).")
                    
                    // Direct inject to virtual Xserver selection via JNI
                    sendClipboardToX11Native(clipText)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync Android clipboard change: ${e.message}", e)
        }
    }

    /**
     * Updates Android ClipboardManager with content received from Ubuntu guest daemon.
     * Prevents loopbacks using the lastSyncedText comparison.
     */
    fun updateClipboardFromUbuntu(text: String) {
        if (text.isNotEmpty() && text != lastSyncedText) {
            // Update cache before setting clip to block our own listener from firing feedback loops
            lastSyncedText = text
            Log.i(TAG, "Guest selection changed. Updating Android Clipboard (Size: ${text.length} chars).")
            
            try {
                val clipData = ClipData.newPlainText("Monos Guest Selection", text)
                clipboard.setPrimaryClip(clipData)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set Android primary clipboard: ${e.message}", e)
            }
        }
    }
}
