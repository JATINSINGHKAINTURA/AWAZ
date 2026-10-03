package com.awaz.app.ui

import androidx.compose.ui.graphics.Color

/**
 * High-contrast UI states for AWAZ:
 * - [Idle]: Microphone is idle, awaiting user trigger. Calm dark slate background.
 * - [Listening]: Voice session is active, capturing user voice. Green/Emerald pulsing glow.
 * - [Acting]: Tool execution or screen automation is in progress. Amber/Gold thinking pulse.
 * - [NeedsHelp]: Handoff to human, blocked app, or error. High-contrast Red/Crimson state.
 */
sealed class AwazState {
    abstract val backgroundColor: Color
    abstract val buttonColor: Color
    abstract val iconColor: Color
    abstract val pulseEffect: Boolean
    abstract fun next(): AwazState

    object Idle : AwazState() {
        override val backgroundColor: Color = Color(0xFF0F172A) // Slate 900
        override val buttonColor: Color = Color(0xFF1E293B)     // Slate 800
        override val iconColor: Color = Color(0xFF94A3B8)       // Slate 400
        override val pulseEffect: Boolean = false

        override fun next(): AwazState = Listening
        override fun toString(): String = "Idle"
    }

    object Listening : AwazState() {
        override val backgroundColor: Color = Color(0xFF022C22) // Emerald 950
        override val buttonColor: Color = Color(0xFF059669)     // Emerald 600
        override val iconColor: Color = Color(0xFFFFFFFF)       // White
        override val pulseEffect: Boolean = true

        override fun next(): AwazState = Acting
        override fun toString(): String = "Listening"
    }

    object Acting : AwazState() {
        override val backgroundColor: Color = Color(0xFF451A03) // Amber 950
        override val buttonColor: Color = Color(0xFFD97706)     // Amber 600
        override val iconColor: Color = Color(0xFFFFFFFF)       // White
        override val pulseEffect: Boolean = true

        override fun next(): AwazState = NeedsHelp
        override fun toString(): String = "Acting"
    }

    object NeedsHelp : AwazState() {
        override val backgroundColor: Color = Color(0xFF450A0A) // Red 950
        override val buttonColor: Color = Color(0xFFDC2626)     // Red 600
        override val iconColor: Color = Color(0xFFFFFFFF)       // White
        override val pulseEffect: Boolean = false

        override fun next(): AwazState = Idle
        override fun toString(): String = "NeedsHelp"
    }
}
