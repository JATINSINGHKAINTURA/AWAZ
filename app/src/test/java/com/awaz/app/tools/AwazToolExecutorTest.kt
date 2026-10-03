package com.awaz.app.tools

import com.awaz.app.event.Clock
import com.awaz.app.live.LiveToolCall
import com.awaz.app.overlay.ConfirmationPresenter
import com.awaz.app.overlay.ConfirmationResult
import com.awaz.app.policy.Decision
import com.awaz.app.policy.PolicyEngine
import com.awaz.app.policy.PolicyRules
import com.awaz.app.policy.ScreenElement
import com.awaz.app.service.AccessibilityActions
import com.awaz.app.service.ScrollDirection
import com.awaz.app.service.ScreenElementSnapshot
import com.awaz.app.service.SnapshotResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AwazToolExecutorTest {

    private lateinit var fakeActions: FakeAccessibilityActions
    private lateinit var fakePresenter: FakeConfirmationPresenter
    private lateinit var policyEngine: PolicyEngine
    private var simulatedTime: Long = 100_000L

    private val clock = Clock { simulatedTime }

    @Before
    fun setUp() {
        fakeActions = FakeAccessibilityActions(clock)
        fakePresenter = FakeConfirmationPresenter()
        val rules = PolicyRules(
            deniedPackages = setOf("com.phonepe.app", "net.one97.paytm"),
            deniedPackageFragments = listOf("bank", "upi", "wallet", "pay"),
            sensitiveTerms = listOf("OTP", "PIN", "password"),
            confirmationTerms = listOf("Delete", "Send", "Pay")
        )
        policyEngine = PolicyEngine(rules)
    }

    private fun createExecutor(timeoutMs: Long = 10_000L): AwazToolExecutor {
        return AwazToolExecutor(
            actions = fakeActions,
            confirmationPresenter = fakePresenter,
            policyEngine = policyEngine,
            clock = clock,
            timeoutMs = timeoutMs
        )
    }

    @Test
    fun execute_withUnknownTool_returnsUnknownToolError() = runBlocking {
        val executor = createExecutor()
        val call = LiveToolCall(id = "c1", name = "non_existent_tool", args = buildJsonObject {})
        val result = executor.execute(call)

        assertEquals("UNKNOWN_TOOL", result.output["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun execute_withBadArgs_returnsBadArgsError() = runBlocking {
        val executor = createExecutor()
        // Missing required element_id
        val call = LiveToolCall(id = "c2", name = "tap_element", args = buildJsonObject {})
        val result = executor.execute(call)

        assertEquals("BAD_ARGS", result.output["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun execute_withSlowAction_returnsToolTimeoutAfter10Seconds() = runBlocking {
        fakeActions.snapshotDelayMs = 200L
        val executor = createExecutor(timeoutMs = 50L) // Virtual/short timeout for test
        val call = LiveToolCall(id = "c3", name = "get_screen_state", args = buildJsonObject {})
        val result = executor.execute(call)

        assertEquals("TOOL_TIMEOUT", result.output["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun execute_requestConfirmation_withDeniedResult_returnsDeniedStatus() = runBlocking {
        fakePresenter.resultToReturn = ConfirmationResult.DENIED
        val executor = createExecutor()
        val call = LiveToolCall(
            id = "c4",
            name = "request_confirmation",
            args = buildJsonObject { put("reason", JsonPrimitive("Confirm transfer")) }
        )
        val result = executor.execute(call)

        assertEquals("false", result.output["confirmed"]?.jsonPrimitive?.content)
        assertEquals("DENIED", result.output["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun execute_requestConfirmation_withTimeoutResult_returnsTimeoutStatus() = runBlocking {
        fakePresenter.resultToReturn = ConfirmationResult.TIMEOUT
        val executor = createExecutor()
        val call = LiveToolCall(
            id = "c5",
            name = "request_confirmation",
            args = buildJsonObject { put("reason", JsonPrimitive("Confirm deletion")) }
        )
        val result = executor.execute(call)

        assertEquals("false", result.output["confirmed"]?.jsonPrimitive?.content)
        assertEquals("TIMEOUT", result.output["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun execute_getScreenState_whenPackageBlocked_returnsResultWithNoLabels() = runBlocking {
        fakeActions.snapshotResult = SnapshotResult.Blocked(
            timestamp = simulatedTime,
            foregroundPackage = "com.phonepe.app",
            decisionCode = "BLOCKED_FINANCIAL_PACKAGE"
        )
        val executor = createExecutor()
        val call = LiveToolCall(id = "c6", name = "get_screen_state", args = buildJsonObject {})
        val result = executor.execute(call)

        assertEquals("BLOCKED", result.output["status"]?.jsonPrimitive?.content)
        assertEquals("com.phonepe.app", result.output["package"]?.jsonPrimitive?.content)
        assertEquals("BLOCKED_FINANCIAL_PACKAGE", result.output["decision_code"]?.jsonPrimitive?.content)
        assertFalse(result.output.containsKey("elements"))
        assertNull(result.output["elements"])
    }

    @Test
    fun execute_tapElement_whenPolicyEngineRequiresConfirmation_forcesPresenterEvenWithoutExplicitRequest() = runBlocking {
        // High stakes label: "Delete"
        fakeActions.snapshotResult = SnapshotResult.Success(
            timestamp = simulatedTime,
            foregroundPackage = "com.android.contacts",
            elements = listOf(
                ScreenElementSnapshot(
                    id = 10,
                    label = "Delete contact",
                    role = "Button",
                    clickable = true,
                    enabled = true,
                    checked = null,
                    isPassword = false,
                    boundsCenterX = 100,
                    boundsCenterY = 100
                )
            )
        )
        fakePresenter.resultToReturn = ConfirmationResult.CONFIRMED

        val executor = createExecutor()
        // First get screen state to record package
        executor.execute(LiveToolCall("c0", "get_screen_state", buildJsonObject {}))

        // Next tap element
        val tapCall = LiveToolCall(
            id = "c7",
            name = "tap_element",
            args = buildJsonObject { put("element_id", JsonPrimitive(10)) }
        )
        val result = executor.execute(tapCall)

        // Verify presenter was called
        assertEquals(1, fakePresenter.confirmCallCount)
        assertEquals("true", result.output["success"]?.jsonPrimitive?.content)
    }

    @Test
    fun execute_tapElement_whenForcedConfirmationDenied_doesNotExecuteClick() = runBlocking {
        fakeActions.snapshotResult = SnapshotResult.Success(
            timestamp = simulatedTime,
            foregroundPackage = "com.android.contacts",
            elements = emptyList()
        )
        // Set presenter to DENY
        fakePresenter.resultToReturn = ConfirmationResult.DENIED

        val executor = createExecutor()
        executor.execute(LiveToolCall("c0", "get_screen_state", buildJsonObject {}))

        // Create a custom high stakes action triggering RequireConfirmation
        val rulesWithConfirmation = PolicyRules(
            deniedPackages = emptySet(),
            deniedPackageFragments = emptyList(),
            sensitiveTerms = emptyList(),
            confirmationTerms = listOf("delete")
        )
        val strictExecutor = AwazToolExecutor(
            actions = fakeActions,
            confirmationPresenter = fakePresenter,
            policyEngine = PolicyEngine(rulesWithConfirmation),
            clock = clock
        )

        val tapCall = LiveToolCall(
            id = "c8",
            name = "tap_element",
            args = buildJsonObject { put("element_id", JsonPrimitive(15)) }
        )
        val result = strictExecutor.execute(tapCall)

        assertFalse(fakeActions.clickedIds.contains(15))
    }

    @Test
    fun stepCounter_resetsOnFinishTask() = runBlocking {
        val executor = createExecutor()
        executor.execute(LiveToolCall("c1", "get_screen_state", buildJsonObject {}))
        assertEquals(1, executor.stepCount)

        executor.execute(LiveToolCall("c2", "finish_task", buildJsonObject {}))
        assertEquals(0, executor.stepCount)
    }

    @Test
    fun stepCounter_resetsOnSessionStart() = runBlocking {
        val executor = createExecutor()
        executor.execute(LiveToolCall("c1", "get_screen_state", buildJsonObject {}))
        assertEquals(1, executor.stepCount)

        executor.onSessionStart()
        assertEquals(0, executor.stepCount)
    }

    @Test
    fun stepCounter_resetsAfter5MinutesOfInactivity() = runBlocking {
        val executor = createExecutor()
        executor.execute(LiveToolCall("c1", "get_screen_state", buildJsonObject {}))
        assertEquals(1, executor.stepCount)

        // Advance simulated clock by 5 minutes + 1 second (301,000 ms)
        simulatedTime += 301_000L

        executor.execute(LiveToolCall("c2", "get_screen_state", buildJsonObject {}))
        // Should reset to 0 then increment to 1 for this new action
        assertEquals(1, executor.stepCount)
    }

    @Test
    fun staleScreen_staleAfterWindowChange() = runBlocking {
        fakeActions.currentLabels[1] = "Wi-Fi"
        val executor = createExecutor()

        // 1. Capture snapshot
        executor.execute(LiveToolCall("c1", "get_screen_state", buildJsonObject {}))

        // 2. Trigger window change after snapshot
        simulatedTime += 100L
        fakeActions.notifyWindowStateChanged()

        // 3. Attempt tap -> STALE_SCREEN
        val tapCall = LiveToolCall("c2", "tap_element", buildJsonObject { put("element_id", JsonPrimitive(1)) })
        val result = executor.execute(tapCall)

        assertEquals("false", result.output["ok"]?.jsonPrimitive?.content)
        assertEquals("STALE_SCREEN", result.output["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun staleScreen_staleAfter15Seconds() = runBlocking {
        fakeActions.currentLabels[1] = "Wi-Fi"
        val executor = createExecutor()

        // 1. Capture snapshot at t = 100,000
        executor.execute(LiveToolCall("c1", "get_screen_state", buildJsonObject {}))

        // 2. Advance clock by 15.1 seconds (> 15,000 ms)
        simulatedTime += 15_100L

        // 3. Attempt tap -> STALE_SCREEN
        val tapCall = LiveToolCall("c2", "tap_element", buildJsonObject { put("element_id", JsonPrimitive(1)) })
        val result = executor.execute(tapCall)

        assertEquals("false", result.output["ok"]?.jsonPrimitive?.content)
        assertEquals("STALE_SCREEN", result.output["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun staleScreen_labelMismatch() = runBlocking {
        fakeActions.currentLabels[1] = "Wi-Fi"
        val executor = createExecutor()

        // 1. Capture snapshot with label "Wi-Fi"
        executor.execute(LiveToolCall("c1", "get_screen_state", buildJsonObject {}))

        // 2. Label changes on screen before tap
        fakeActions.currentLabels[1] = "Bluetooth"

        // 3. Attempt tap -> STALE_SCREEN
        val tapCall = LiveToolCall("c2", "tap_element", buildJsonObject { put("element_id", JsonPrimitive(1)) })
        val result = executor.execute(tapCall)

        assertEquals("false", result.output["ok"]?.jsonPrimitive?.content)
        assertEquals("STALE_SCREEN", result.output["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun staleScreen_freshSnapshot_allowsTap() = runBlocking {
        fakeActions.currentLabels[1] = "Wi-Fi"
        val executor = createExecutor()

        // 1. Capture snapshot at t = 100,000
        executor.execute(LiveToolCall("c1", "get_screen_state", buildJsonObject {}))

        // 2. Tap within 2 seconds without window change or label mismatch
        simulatedTime += 2000L
        val tapCall = LiveToolCall("c2", "tap_element", buildJsonObject { put("element_id", JsonPrimitive(1)) })
        val result = executor.execute(tapCall)

        assertEquals("true", result.output["ok"]?.jsonPrimitive?.content)
        assertEquals(listOf(1), fakeActions.clickedIds)
    }

    // Test fakes
    private class FakeAccessibilityActions(private val clock: Clock) : AccessibilityActions {
        var snapshotResult: SnapshotResult = SnapshotResult.Success(100L, "com.android.settings", emptyList())
        var snapshotDelayMs: Long = 0L
        val clickedIds = mutableListOf<Int>()

        var snapshotIdCounter = 1L
        var lastSnapshotId: Long = 0L
        var lastSnapshotTimeMs: Long = 0L
        var lastWindowStateChangeTimeMs: Long = 0L
        var currentForegroundPackage: String = "com.android.settings"
        var snapshotForegroundPackage: String = ""

        val currentLabels = mutableMapOf<Int, String>()
        val snapshotLabels = mutableMapOf<Int, String>()

        fun notifyWindowStateChanged() {
            lastWindowStateChangeTimeMs = clock.now()
        }

        fun isStale(): Boolean {
            if (lastSnapshotId == 0L || lastSnapshotTimeMs == 0L) return true
            if (lastWindowStateChangeTimeMs > lastSnapshotTimeMs) return true
            if (currentForegroundPackage != snapshotForegroundPackage) return true
            if (clock.now() - lastSnapshotTimeMs > 15_000L) return true
            return false
        }

        override suspend fun snapshot(): Result<SnapshotResult> {
            if (snapshotDelayMs > 0) delay(snapshotDelayMs)
            lastSnapshotId = snapshotIdCounter++
            lastSnapshotTimeMs = clock.now()
            snapshotForegroundPackage = currentForegroundPackage
            snapshotLabels.clear()
            snapshotLabels.putAll(currentLabels)
            return Result.success(snapshotResult)
        }

        override fun clickById(id: Int): Result<Boolean> {
            if (isStale()) return Result.failure(com.awaz.app.service.StaleScreenException("STALE_SCREEN"))
            if (currentLabels.containsKey(id) && currentLabels[id] != snapshotLabels[id]) {
                return Result.failure(com.awaz.app.service.StaleScreenException("STALE_SCREEN"))
            }
            clickedIds.add(id)
            return Result.success(true)
        }

        override fun scroll(direction: ScrollDirection): Result<Boolean> {
            if (isStale()) return Result.failure(com.awaz.app.service.StaleScreenException("STALE_SCREEN"))
            return Result.success(true)
        }

        override fun globalBack(): Result<Boolean> = Result.success(true)
        override fun globalHome(): Result<Boolean> = Result.success(true)
        override fun globalRecents(): Result<Boolean> = Result.success(true)
        override fun openApp(packageName: String): Result<Boolean> = Result.success(true)
        override fun openSettingsPage(action: String): Result<Boolean> = Result.success(true)
    }

    private class FakeConfirmationPresenter : ConfirmationPresenter {
        var resultToReturn: ConfirmationResult = ConfirmationResult.CONFIRMED
        var confirmCallCount = 0

        override suspend fun confirm(summary: String): ConfirmationResult {
            confirmCallCount++
            return resultToReturn
        }
    }
}
