package com.awaz.app.overlay

/**
 * Interface abstracting user confirmation presentation (green checkmark / red cross overlay).
 * Allows pure JVM testing without Android WindowManager dependencies.
 */
interface ConfirmationPresenter {
    suspend fun confirm(summary: String): ConfirmationResult
}
