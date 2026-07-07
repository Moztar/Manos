package com.monos.app.ui.components

import android.view.MotionEvent
import android.util.Log
import com.monos.app.ui.InputMode

private const val TAG = "PointerInputHandler"

/**
 * Custom pointer interaction engine translating Android hardware screen events
 * into X11 events via JNI calls.
 */
class PointerInputHandler(
    private val onSendEvent: (x: Float, y: Float, button: Int, action: Int, isRelative: Boolean) -> Unit
) {

    // Trackpad delta tracking state
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f
    private var isDragging = false
    private var pressTime = 0L

    // Multitouch gesture state
    private var lastScrollY = 0f
    private var isTwoFingerGesture = false
    private var isTwoFingerTapPossible = false

    // Configuration thresholds
    private val tapTimeout = 200L // Max duration for a tap gesture in ms
    private val moveThreshold = 15f // Distance threshold to differentiate tap vs drag
    private val scrollThreshold = 10f // Distance scroll scroll trigger threshold

    /**
     * Intercepts Android MotionEvents, processes inputs matching ViewModels parameters,
     * and maps coordinates to JNI mouse events.
     *
     * @param event The touch MotionEvent.
     * @param inputMode Configured input mode (TOUCH or TRACKPAD).
     * @param speedMultiplier Pointer speed multiplier.
     */
    fun processTouchEvent(
        event: MotionEvent,
        inputMode: InputMode,
        speedMultiplier: Float
    ): Boolean {
        val action = event.actionMasked
        val pointerCount = event.pointerCount

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                pressTime = System.currentTimeMillis()
                startX = event.x
                startY = event.y
                lastX = event.x
                lastY = event.y
                isDragging = false
                isTwoFingerGesture = false
                isTwoFingerTapPossible = false

                if (inputMode == InputMode.TOUCH) {
                    // Instantly position cursor and trigger left click hold down
                    onSendEvent(event.x, event.y, 1, MotionEvent.ACTION_DOWN, false)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Multi-finger activation (second pointer pressed)
                if (pointerCount == 2) {
                    isTwoFingerGesture = true
                    isTwoFingerTapPossible = true
                    // Compute initial mid-point for scroll gesture mapping
                    lastScrollY = (event.getY(0) + event.getY(1)) / 2f
                    Log.d(TAG, "Two-finger gesture activated.")
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTwoFingerGesture && pointerCount >= 2) {
                    // Process Two-Finger vertical swiping -> Scroll wheel (Button 4/5)
                    val currentScrollY = (event.getY(0) + event.getY(1)) / 2f
                    val deltaScrollY = currentScrollY - lastScrollY

                    if (Math.abs(deltaScrollY) > scrollThreshold) {
                        isTwoFingerTapPossible = false
                        // Negative delta -> scroll up (Button 4), Positive -> scroll down (Button 5)
                        val button = if (deltaScrollY > 0) 5 else 4
                        Log.d(TAG, "Scroll Wheel Triggered: Button $button")
                        
                        // Scroll click cycle (down immediately followed by up)
                        onSendEvent(0f, 0f, button, MotionEvent.ACTION_DOWN, true)
                        onSendEvent(0f, 0f, button, MotionEvent.ACTION_UP, true)
                        
                        lastScrollY = currentScrollY
                    }
                } else if (!isTwoFingerGesture) {
                    val dxRaw = event.x - lastX
                    val dyRaw = event.y - lastY
                    
                    // Verify if finger has drifted past drag threshold
                    if (Math.hypot((event.x - startX).toDouble(), (event.y - startY).toDouble()) > moveThreshold) {
                        isDragging = true
                    }

                    if (inputMode == InputMode.TRACKPAD) {
                        // Apply pointer speed scaling parameters: delta * speed
                        val dxNew = dxRaw * speedMultiplier
                        val dyNew = dyRaw * speedMultiplier
                        
                        // Send relative movement coordinates
                        onSendEvent(dxNew, dyNew, 0, MotionEvent.ACTION_MOVE, true)
                    } else {
                        // Absolute Touch: Map screen position directly
                        onSendEvent(event.x, event.y, 0, MotionEvent.ACTION_MOVE, false)
                    }

                    lastX = event.x
                    lastY = event.y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Handle second finger lift for two-finger tap checks
                if (actionIndexMatchesPointer(event, 1) && isTwoFingerTapPossible) {
                    val duration = System.currentTimeMillis() - pressTime
                    if (duration < tapTimeout) {
                        Log.i(TAG, "Two-Finger Tap Registered: Mouse Right Click (Button 3)")
                        // Send Right-click sequence
                        onSendEvent(0f, 0f, 3, MotionEvent.ACTION_DOWN, true)
                        onSendEvent(0f, 0f, 3, MotionEvent.ACTION_UP, true)
                        isTwoFingerTapPossible = false
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                val duration = System.currentTimeMillis() - pressTime
                
                if (inputMode == InputMode.TOUCH) {
                    // Absolute release left click
                    onSendEvent(event.x, event.y, 1, MotionEvent.ACTION_UP, false)
                } else if (inputMode == InputMode.TRACKPAD && !isDragging && !isTwoFingerGesture) {
                    // Verify if short drag duration counts as single finger tap -> Left Click (Button 1)
                    if (duration < tapTimeout) {
                        Log.i(TAG, "Single-Finger Tap Registered: Mouse Left Click (Button 1)")
                        onSendEvent(0f, 0f, 1, MotionEvent.ACTION_DOWN, true)
                        onSendEvent(0f, 0f, 1, MotionEvent.ACTION_UP, true)
                    }
                }
                
                isDragging = false
                isTwoFingerGesture = false
                isTwoFingerTapPossible = false
            }

            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                isTwoFingerGesture = false
                isTwoFingerTapPossible = false
            }
        }
        return true
    }

    private fun actionIndexMatchesPointer(event: MotionEvent, expectedIndex: Int): Boolean {
        val index = (event.action and MotionEvent.ACTION_POINTER_INDEX_MASK) shr MotionEvent.ACTION_POINTER_INDEX_SHIFT
        return index == expectedIndex
    }
}
