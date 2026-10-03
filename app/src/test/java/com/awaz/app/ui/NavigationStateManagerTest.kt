package com.awaz.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NavigationStateManagerTest {

    private lateinit var manager: NavigationStateManager
    private var mockServiceRunning: Boolean = false

    @Before
    fun setUp() {
        mockServiceRunning = false
        manager = NavigationStateManager(
            initialState = AwazState.Idle,
            serviceStatusProvider = { mockServiceRunning }
        )
    }

    @Test
    fun initialState_defaultsToIdle() {
        assertEquals(AwazState.Idle, manager.currentState.value)
        assertFalse(manager.isServiceActive.value)
        assertNull(manager.lastError.value)
        assertNull(manager.lastErrorCode.value)
    }

    @Test
    fun transitionTo_updatesCurrentState() {
        manager.transitionTo(AwazState.Listening)
        assertEquals(AwazState.Listening, manager.currentState.value)

        manager.transitionTo(AwazState.Acting)
        assertEquals(AwazState.Acting, manager.currentState.value)

        manager.transitionTo(AwazState.NeedsHelp)
        assertEquals(AwazState.NeedsHelp, manager.currentState.value)

        manager.transitionTo(AwazState.Idle)
        assertEquals(AwazState.Idle, manager.currentState.value)
    }

    @Test
    fun cycleNextState_cyclesInExpectedOrder() {
        // Idle -> Listening
        manager.cycleNextState()
        assertEquals(AwazState.Listening, manager.currentState.value)

        // Listening -> Acting
        manager.cycleNextState()
        assertEquals(AwazState.Acting, manager.currentState.value)

        // Acting -> NeedsHelp
        manager.cycleNextState()
        assertEquals(AwazState.NeedsHelp, manager.currentState.value)

        // NeedsHelp -> Idle
        manager.cycleNextState()
        assertEquals(AwazState.Idle, manager.currentState.value)
    }

    @Test
    fun onServiceStarted_updatesIsServiceActiveAndClearsNeedsHelp() {
        manager.transitionTo(AwazState.NeedsHelp)
        manager.onServiceStarted()

        assertTrue(manager.isServiceActive.value)
        assertEquals(AwazState.Idle, manager.currentState.value)
        assertNull(manager.lastError.value)
        assertNull(manager.lastErrorCode.value)
    }

    @Test
    fun onServiceStopped_setsServiceInactiveAndRevertsToIdle() {
        manager.onServiceStarted()
        manager.transitionTo(AwazState.Listening)

        manager.onServiceStopped()
        assertFalse(manager.isServiceActive.value)
        assertEquals(AwazState.Idle, manager.currentState.value)
    }

    @Test
    fun onServiceError_transitionsToNeedsHelpAndSetsStrictErrorCode() {
        manager.onServiceStarted()
        manager.onServiceError(NavigationErrorCode.PERMISSION_DENIED)

        assertFalse(manager.isServiceActive.value)
        assertEquals(AwazState.NeedsHelp, manager.currentState.value)
        assertEquals("PERMISSION_DENIED", manager.lastError.value)
        assertEquals(NavigationErrorCode.PERMISSION_DENIED, manager.lastErrorCode.value)
    }

    @Test
    fun voiceSessionLifecycle_transitionsBetweenIdleAndListening() {
        manager.onVoiceSessionStarted()
        assertEquals(AwazState.Listening, manager.currentState.value)

        manager.onVoiceSessionEnded()
        assertEquals(AwazState.Idle, manager.currentState.value)
    }

    @Test
    fun actionLifecycle_transitionsBetweenListeningAndActing() {
        manager.onVoiceSessionStarted()
        assertEquals(AwazState.Listening, manager.currentState.value)

        manager.onActionStarted()
        assertEquals(AwazState.Acting, manager.currentState.value)

        manager.onActionFinished()
        assertEquals(AwazState.Listening, manager.currentState.value)
    }

    @Test
    fun onHandoffRequired_transitionsToNeedsHelpWithErrorCode() {
        manager.onVoiceSessionStarted()
        manager.onHandoffRequired(NavigationErrorCode.HANDOFF_REQUIRED)

        assertEquals(AwazState.NeedsHelp, manager.currentState.value)
        assertEquals("HANDOFF_REQUIRED", manager.lastError.value)
        assertEquals(NavigationErrorCode.HANDOFF_REQUIRED, manager.lastErrorCode.value)
    }

    @Test
    fun reset_restoresIdleAndClearsErrors() {
        manager.onServiceError(NavigationErrorCode.SERVICE_ERROR)
        assertEquals(AwazState.NeedsHelp, manager.currentState.value)

        manager.reset()
        assertEquals(AwazState.Idle, manager.currentState.value)
        assertNull(manager.lastError.value)
        assertNull(manager.lastErrorCode.value)
    }

    @Test
    fun stateChanges_madeWhileNoActivityExists_areVisibleWhenActivityAttaches() {
        // Step 1: Simulated background state change before any Activity exists/attaches
        // LiveSessionController or VoiceForegroundService triggers an action
        manager.onVoiceSessionStarted()
        manager.onActionStarted()

        // Verify manager is in Acting state in process memory
        assertEquals(AwazState.Acting, manager.currentState.value)

        // Step 2: Simulated Activity launch / attach reading StateFlow
        // (Just like MainActivity reads manager.currentState.collectAsState())
        val observedStateByNewlyAttachedActivity = manager.currentState.value
        assertEquals(AwazState.Acting, observedStateByNewlyAttachedActivity)

        // Step 3: Transition to NeedsHelp while Activity is backgrounded / recreated
        manager.onHandoffRequired(NavigationErrorCode.POLICY_BLOCKED)
        val observedStateAfterRecreation = manager.currentState.value
        assertEquals(AwazState.NeedsHelp, observedStateAfterRecreation)
        assertEquals(NavigationErrorCode.POLICY_BLOCKED, manager.lastErrorCode.value)
    }
}
