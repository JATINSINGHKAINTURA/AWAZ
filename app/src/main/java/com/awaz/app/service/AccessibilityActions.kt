package com.awaz.app.service

/**
 * Thrown or returned when an action fails due to a stale screen state:
 * - No snapshot exists
 * - Window-state or foreground-package change occurred after snapshot
 * - Snapshot older than 15 seconds
 * - Node label or package mismatch
 */
class StaleScreenException(message: String = "STALE_SCREEN") : Exception(message)

/**
 * Interface abstracting accessibility service interactions.
 * Decouples tool execution from the Android framework for pure JVM unit testing.
 */
interface AccessibilityActions {
    suspend fun snapshot(): Result<SnapshotResult>
    fun clickById(id: Int): Result<Boolean>
    fun scroll(direction: ScrollDirection): Result<Boolean>
    fun globalBack(): Result<Boolean>
    fun globalHome(): Result<Boolean>
    fun globalRecents(): Result<Boolean>
    fun openApp(packageName: String): Result<Boolean>
    fun openSettingsPage(action: String): Result<Boolean>
}
