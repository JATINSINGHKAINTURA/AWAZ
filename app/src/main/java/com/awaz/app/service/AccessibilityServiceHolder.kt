package com.awaz.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton holder exposing StateFlow<AwazAccessibilityService?> for Activity interaction.
 * Set in onServiceConnected and cleared in onUnbind and onDestroy.
 * Ensures no other static references to the service exist.
 */
object AccessibilityServiceHolder {
    private val _service = MutableStateFlow<AwazAccessibilityService?>(null)
    val service: StateFlow<AwazAccessibilityService?> = _service.asStateFlow()

    fun set(service: AwazAccessibilityService?) {
        _service.value = service
    }

    fun setService(service: AwazAccessibilityService?) {
        _service.value = service
    }

    fun clearService() {
        _service.value = null
    }
}
