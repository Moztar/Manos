package com.monos.app.ui.components

import android.util.Log

class X11DisplayServer : IDisplayServer {
    
    override fun initServer(surface: Any): Boolean {
        Log.i("X11DisplayServer", "Initializing native EGL Direct Framebuffer Strategy...")
        return initNativeRenderer(surface)
    }

    override fun drawFrame(pixelData: ByteArray, width: Int, height: Int) {
        drawFrameNative(pixelData, width, height)
    }

    override fun setTextureFiltering(useBilinear: Boolean) {
        setTextureFilteringNative(useBilinear)
    }

    override fun releaseServer() {
        Log.i("X11DisplayServer", "Terminating native EGL renderer...")
        releaseNativeRenderer()
    }
}
