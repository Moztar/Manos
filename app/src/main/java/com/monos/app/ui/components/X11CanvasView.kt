package com.monos.app.ui.components

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.os.Build
import android.text.InputType
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.EditText
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.monos.app.ui.InputMode
import com.monos.app.ui.theme.BackgroundDark

private const val TAG = "X11CanvasView"

// External JNI/C++ functions declared on file-level package functions
external fun initNativeRenderer(surface: Any): Boolean
external fun drawFrameNative(pixelData: ByteArray, width: Int, height: Int)
external fun setTextureFilteringNative(useBilinear: Boolean)
external fun releaseNativeRenderer()
external fun sendPointerEventNative(x: Float, y: Float, button: Int, action: Int, isRelative: Boolean)

@SuppressLint("ClickableViewAccessibility")
@Composable
fun X11CanvasView(
    transformationMatrix: Matrix,
    useBilinear: Boolean,
    inputMode: InputMode,
    pointerSpeed: Float,
    modifier: Modifier = Modifier
) {
    // Apply texture filtering preference dynamically to JNI
    setTextureFilteringNative(useBilinear)

    // Instantiate custom Pointer Input Handler
    val pointerInputHandler = remember {
        PointerInputHandler { x, y, button, action, isRelative ->
            sendPointerEventNative(x, y, button, action, isRelative)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        AndroidView(
            factory = { context ->
                val frameLayout = FrameLayout(context)
                
                val surfaceView = SurfaceView(context).apply {
                    setWillNotDraw(false)
                    
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            Log.i(TAG, "X11 Canvas created. Initializing OpenGL context...")
                            val success = initNativeRenderer(holder.surface)
                            if (success) {
                                Log.i(TAG, "Native OpenGL EGL renderer bound successfully.")
                            } else {
                                Log.e(TAG, "Failed to initialize native EGL canvas.")
                            }
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            Log.d(TAG, "Surface resolution altered: ${width}x${height}")
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            Log.i(TAG, "Surface destroyed. Releasing EGL display resources...")
                            releaseNativeRenderer()
                        }
                    })
                }

                // System IME Soft Keyboard Blocker
                // Focus target to suppress native keyboards (like Gboard) when user touches the canvas
                val dummyFocusInput = EditText(context).apply {
                    // Suppress system keyboard display triggers
                    inputType = InputType.TYPE_NULL
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        showSoftInputOnFocus = false
                    }
                    
                    // Style invisible and position out of user bounds
                    layoutParams = FrameLayout.LayoutParams(1, 1)
                    alpha = 0f
                    isFocusable = true
                    isFocusableInTouchMode = true
                }

                // Set coordinates translation listener
                surfaceView.setOnTouchListener { _, event ->
                    // Request focus to intercept soft IME triggers
                    dummyFocusInput.requestFocus()
                    
                    if (inputMode == InputMode.TOUCH) {
                        val pts = floatArrayOf(event.x, event.y)
                        val invertedMatrix = Matrix()
                        
                        if (transformationMatrix.invert(invertedMatrix)) {
                            invertedMatrix.mapPoints(pts)
                            event.setLocation(pts[0], pts[1])
                        }
                    }
                    
                    // Dispatch to PointerInputHandler
                    pointerInputHandler.processTouchEvent(event, inputMode, pointerSpeed)
                }

                frameLayout.addView(surfaceView)
                frameLayout.addView(dummyFocusInput)
                frameLayout
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
