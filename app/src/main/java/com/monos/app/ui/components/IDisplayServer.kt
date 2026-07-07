package com.monos.app.ui.components

interface IDisplayServer {
    fun initServer(surface: Any): Boolean
    fun drawFrame(pixelData: ByteArray, width: Int, height: Int)
    fun setTextureFiltering(useBilinear: Boolean)
    fun releaseServer()
}
