package com.awaz.app.ui

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "NavigationStateManager"

/**
 * Standard error codes for AWAZ navigation and service failures.
 * Strictly short codes; never contains exception messages, URLs, screen text, or credentials.
 */
enum class NavigationErrorCode {
    SERVICE_ERROR,
    PERMISSION_DENIED,
    STALE_SCREEN,
    POLICY_BLOCKED,
    SENSITIVE_SCREEN,
    SESSION_FAILED,
    HANDOFF_REQUIRED,
    TIMEOUT
}

/**
 * Process-scoped state manager owned by AwazApplication.
 * Survives Activity destruction, recreation, and orientation changes.
 * Reflects the status of VoiceForegroundService and the live voice session.
 */
class NavigationStateManager(
    initialState: AwazState = AwazState.Idle,
    private val serviceStatusProvider: () -> Boolean = { false }
) {

    private val _currentState = MutableStateFlow<AwazState>(initialState)
    val currentState: StateFlow<AwazState> = _currentState.asStateFlow()

    private val _isServiceActive = MutableStateFlow(serviceStatusProvider())
    val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

    // Holds ONLY an error code constant, never raw messages or sensitive text
    private val _lastErrorCode = MutableStateFlow<NavigationErrorCode?>(null)
    val lastErrorCode: StateFlow<NavigationErrorCode?> = _lastErrorCode.asStateFlow()

    // String code representation for external consumers/tests
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun transitionTo(newState: AwazState) {
        val old = _currentState.value
        if (old != newState) {
            Log.d(TAG, "State transition: $old -> $newState")
            _currentState.value = newState
        }
    }

    fun cycleNextState() {
        val next = _currentState.value.next()
        Log.d(TAG, "Cycling state: ${_currentState.value} -> $next")
        _currentState.value = next
    }

    fun onServiceStarted() {
        _isServiceActive.value = true
        _lastErrorCode.value = null
        _lastError.value = null
        if (_currentState.value is AwazState.NeedsHelp) {
            _currentState.value = AwazState.Idle
        }
        Log.i(TAG, "VoiceForegroundService started -> active")
    }

    fun onServiceStopped() {
        _isServiceActive.value = false
        _currentState.value = AwazState.Idle
        Log.i(TAG, "VoiceForegroundService stopped -> Idle")
    }

    /**
     * Records a standardized error code and transitions to NeedsHelp.
     */
    fun onServiceError(code: NavigationErrorCode) {
        Log.e(TAG, "Service error code: $code")
        _lastErrorCode.value = code
        _lastError.value = code.name
        _isServiceActive.value = false
        _currentState.value = AwazState.NeedsHelp
    }

    fun onServiceError(codeStr: String) {
        val parsedCode = try {
            NavigationErrorCode.valueOf(codeStr)
        } catch (_: Exception) {
            NavigationErrorCode.SERVICE_ERROR
        }
        onServiceError(parsedCode)
    }

    fun onVoiceSessionStarted() {
        if (_currentState.value !is AwazState.Acting) {
            _currentState.value = AwazState.Listening
            Log.d(TAG, "Voice session active -> Listening")
        }
    }

    fun onVoiceSessionEnded() {
        if (_currentState.value is AwazState.Listening) {
            _currentState.value = AwazState.Idle
            Log.d(TAG, "Voice session ended -> Idle")
        }
    }

    fun onActionStarted() {
        _currentState.value = AwazState.Acting
        Log.d(TAG, "Action started -> Acting")
    }

    fun onActionFinished() {
        if (_currentState.value is AwazState.Acting) {
            _currentState.value = AwazState.Listening
            Log.d(TAG, "Action finished -> Listening")
        }
    }

    fun onHandoffRequired(code: NavigationErrorCode = NavigationErrorCode.HANDOFF_REQUIRED) {
        Log.w(TAG, "Safety handoff required: $code")
        _lastErrorCode.value = code
        _lastError.value = code.name
        _currentState.value = AwazState.NeedsHelp
    }

    fun reset() {
        _lastErrorCode.value = null
        _lastError.value = null
        _isServiceActive.value = serviceStatusProvider()
        _currentState.value = AwazState.Idle
        Log.d(TAG, "Reset to Idle state")
    }
}
