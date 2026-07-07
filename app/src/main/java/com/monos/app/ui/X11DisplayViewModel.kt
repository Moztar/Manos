package com.monos.app.ui

import android.graphics.Matrix
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DisplayMode { NATIVE, SCALED }
enum class FilteringMode { BILINEAR, NEAREST }
enum class ScaleMode { FIT, STRETCH }
enum class ScreenOrientation { PORTRAIT, LANDSCAPE }
enum class InputMode { TOUCH, TRACKPAD }

data class DisplayState(
    val resolutionWidth: Int = 1920,
    val resolutionHeight: Int = 1080,
    val displayMode: DisplayMode = DisplayMode.SCALED,
    val filteringMode: FilteringMode = FilteringMode.BILINEAR,
    val scaleMode: ScaleMode = ScaleMode.FIT,
    val orientation: ScreenOrientation = ScreenOrientation.LANDSCAPE,
    val inputMode: InputMode = InputMode.TOUCH,
    val pointerSpeed: Float = 1.0f,
    val keyboardVisible: Boolean = false,
    val keyboardOpacity: Float = 0.8f,
    val autoOpenSoftKeyboard: Boolean = false,
    val textPreviewContent: String = "",
    val macros: List<String> = listOf(
        "Esc", "Tab", "Ctrl", "Alt", "Shift", "Super", "Fn",
        "Home", "End", "PgUp", "PgDn", "Insert", "Delete", "PrtSc"
    )
)

class X11DisplayViewModel : ViewModel() {

    private val _displayState = MutableStateFlow(DisplayState())
    val displayState: StateFlow<DisplayState> = _displayState.asStateFlow()

    fun updateResolution(width: Int, height: Int) {
        _displayState.update { it.copy(resolutionWidth = width, resolutionHeight = height) }
    }

    fun toggleDisplayMode() {
        _displayState.update {
            it.copy(
                displayMode = if (it.displayMode == DisplayMode.NATIVE) DisplayMode.SCALED else DisplayMode.NATIVE
            )
        }
    }

    fun updateFilteringMode(mode: FilteringMode) {
        _displayState.update { it.copy(filteringMode = mode) }
    }

    fun updateScaleMode(mode: ScaleMode) {
        _displayState.update { it.copy(scaleMode = mode) }
    }

    fun updateOrientation(orientation: ScreenOrientation) {
        _displayState.update { it.copy(orientation = orientation) }
    }

    fun updateInputMode(mode: InputMode) {
        _displayState.update { it.copy(inputMode = mode) }
    }

    fun updatePointerSpeed(speed: Float) {
        _displayState.update { it.copy(pointerSpeed = speed) }
    }

    fun updateKeyboardVisibility(visible: Boolean) {
        _displayState.update { it.copy(keyboardVisible = visible) }
    }

    fun updateKeyboardOpacity(opacity: Float) {
        _displayState.update { it.copy(keyboardOpacity = opacity) }
    }

    fun updateAutoOpenSoftKeyboard(autoOpen: Boolean) {
        _displayState.update { 
            it.copy(
                autoOpenSoftKeyboard = autoOpen,
                keyboardVisible = if (autoOpen) true else it.keyboardVisible
            )
        }
    }

    fun updateTextPreviewContent(text: String) {
        _displayState.update { it.copy(textPreviewContent = text) }
    }

    fun updateMacroKey(index: Int, label: String) {
        _displayState.update { state ->
            val updatedMacros = state.macros.toMutableList()
            if (index in updatedMacros.indices) {
                updatedMacros[index] = label
            }
            state.copy(macros = updatedMacros)
        }
    }

    /**
     * Aspect Ratio Matrix Transformation Calculator
     * Computes the translation and scale matrices to draw X11 framebuffer on Android canvas.
     *
     * @param viewWidth Width of physical Android layout viewport.
     * @param viewHeight Height of physical Android layout viewport.
     * @param x11Width Width of virtual Xserver rootfs container display.
     * @param x11Height Height of virtual Xserver rootfs container display.
     * @return Matrix mapping virtual to host viewport.
     */
    fun calculateTransformationMatrix(
        viewWidth: Float,
        viewHeight: Float,
        x11Width: Float,
        x11Height: Float
    ): Matrix {
        val matrix = Matrix()
        val currentState = _displayState.value

        when (currentState.scaleMode) {
            ScaleMode.FIT -> {
                // Compute scale keeping aspect ratio intact (with letterboxing/pillarboxing)
                val scale = Math.min(viewWidth / x11Width, viewHeight / x11Height)
                val scaledWidth = x11Width * scale
                val scaledHeight = x11Height * scale
                
                // Centering offsets
                val dx = (viewWidth - scaledWidth) / 2f
                val dy = (viewHeight - scaledHeight) / 2f

                matrix.postScale(scale, scale)
                matrix.postTranslate(dx, dy)
            }
            ScaleMode.STRETCH -> {
                // Force coordinates stretch to fill 100% of host viewport boundaries
                val scaleX = viewWidth / x11Width
                val scaleY = viewHeight / x11Height
                matrix.postScale(scaleX, scaleY)
            }
        }
        return matrix
    }

    /**
     * Dynamically generates RandR resize/rotate xrandr argument configurations.
     */
    fun generateRandRArguments(): Array<String> {
        val state = _displayState.value
        val resStr = "${state.resolutionWidth}x${state.resolutionHeight}"
        
        // Generate command structure: xrandr --output default --mode WIDTHxHEIGHT
        return arrayOf(
            "xrandr",
            "--output",
            "default",
            "--mode",
            resStr,
            "--rate",
            "60"
        )
    }
}
