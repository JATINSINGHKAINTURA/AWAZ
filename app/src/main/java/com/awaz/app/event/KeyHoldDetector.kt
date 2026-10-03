package com.awaz.app.event

sealed class KeyAction {
    object None : KeyAction() {
        override fun toString(): String = "None"
    }

    object RaiseVolume : KeyAction() {
        override fun toString(): String = "RaiseVolume"
    }

    object Trigger : KeyAction() {
        override fun toString(): String = "Trigger"
    }
}

fun interface Clock {
    fun now(): Long
}

fun interface Cancellable {
    fun cancel()
}

fun interface Scheduler {
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
}

/**
 * Pure Kotlin key hold detector for hardware volume button.
 * Detects short press (< 800 ms) vs long hold (>= 800 ms).
 * Operates with an injectable Clock and Scheduler.
 */
class KeyHoldDetector(
    private val clock: Clock = Clock { System.currentTimeMillis() },
    private val scheduler: Scheduler,
    private val holdThresholdMs: Long = 800L
) {
    private var isPressed: Boolean = false
    private var pressStartTime: Long = 0L
    private var hasTriggered: Boolean = false
    private var scheduledTask: Cancellable? = null
    private var onTriggerCallback: (() -> Unit)? = null

    fun setOnTriggerCallback(callback: () -> Unit) {
        this.onTriggerCallback = callback
    }

    /**
     * Called on KeyEvent.ACTION_DOWN for KEYCODE_VOLUME_UP.
     * Starts the 800ms timer on the first DOWN event.
     * Repeat DOWN events do NOT restart timer or adjust volume.
     */
    fun onKeyDown(repeatCount: Int): KeyAction {
        if (repeatCount == 0 || !isPressed) {
            isPressed = true
            pressStartTime = clock.now()
            hasTriggered = false
            scheduledTask?.cancel()
            scheduledTask = scheduler.schedule(holdThresholdMs) {
                if (isPressed && !hasTriggered) {
                    hasTriggered = true
                    onTriggerCallback?.invoke()
                }
            }
        }
        return KeyAction.None
    }

    /**
     * Called on KeyEvent.ACTION_UP for KEYCODE_VOLUME_UP.
     * Short press (released before 800ms without trigger): returns KeyAction.RaiseVolume.
     * Long hold (already triggered): does NOT raise volume, returns KeyAction.None.
     */
    fun onKeyUp(): KeyAction {
        scheduledTask?.cancel()
        scheduledTask = null

        val wasPressed = isPressed
        isPressed = false

        if (!wasPressed) {
            return KeyAction.None
        }

        val elapsed = clock.now() - pressStartTime
        return if (!hasTriggered && elapsed < holdThresholdMs) {
            KeyAction.RaiseVolume
        } else {
            KeyAction.None
        }
    }

    /**
     * Called when the scheduler's timer expires while key is still held.
     * Returns KeyAction.Trigger once.
     */
    fun onScheduledTimeout(): KeyAction {
        if (isPressed && !hasTriggered) {
            hasTriggered = true
            onTriggerCallback?.invoke()
            return KeyAction.Trigger
        }
        return KeyAction.None
    }

    fun isCurrentlyPressed(): Boolean = isPressed
    fun hasTriggeredCurrentPress(): Boolean = hasTriggered
}
