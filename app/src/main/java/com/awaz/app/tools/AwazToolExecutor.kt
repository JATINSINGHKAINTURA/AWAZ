package com.awaz.app.tools

import com.awaz.app.event.Clock
import com.awaz.app.live.LiveToolCall
import com.awaz.app.overlay.ConfirmationPresenter
import com.awaz.app.overlay.ConfirmationResult
import com.awaz.app.policy.AgentAction
import com.awaz.app.policy.AgentActionType
import com.awaz.app.policy.Decision
import com.awaz.app.policy.PolicyContext
import com.awaz.app.policy.PolicyEngine
import com.awaz.app.service.AccessibilityActions
import com.awaz.app.service.ScrollDirection
import com.awaz.app.service.SnapshotResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Pure Kotlin, JVM-testable ToolExecutor for AWAZ.
 * Depends strictly on AccessibilityActions, ConfirmationPresenter, PolicyEngine, and Clock.
 * Contains ZERO Android framework imports.
 */
class AwazToolExecutor(
    private val actions: AccessibilityActions,
    private val confirmationPresenter: ConfirmationPresenter,
    private val policyEngine: PolicyEngine,
    private val clock: Clock = Clock { System.currentTimeMillis() },
    private val timeoutMs: Long = 10_000L
) : ToolExecutor {

    var stepCount: Int = 0
        private set
    private var lastActionTimestampMs: Long = 0L
    private var lastKnownPackage: String = "unknown"

    fun onSessionStart() {
        stepCount = 0
        lastActionTimestampMs = clock.now()
    }

    private fun checkInactivityAndIncrementStep() {
        val now = clock.now()
        if (lastActionTimestampMs > 0L && (now - lastActionTimestampMs) >= 5 * 60 * 1000L) {
            stepCount = 0
        }
        stepCount++
        lastActionTimestampMs = now
    }

    override suspend fun execute(call: LiveToolCall): ToolResult {
        return try {
            withTimeout(timeoutMs) {
                dispatchToolCall(call)
            }
        } catch (_: TimeoutCancellationException) {
            ToolResult.error("TOOL_TIMEOUT", "Tool execution exceeded timeout of ${timeoutMs}ms")
        }
    }

    private suspend fun dispatchToolCall(call: LiveToolCall): ToolResult {
        return when (call.name) {
            "get_screen_state" -> handleGetScreenState()
            "tap_element" -> handleTapElement(call)
            "scroll_screen" -> handleScrollScreen(call)
            "press_system_button" -> handlePressSystemButton(call)
            "open_app" -> handleOpenApp(call)
            "open_settings_page" -> handleOpenSettingsPage(call)
            "request_confirmation" -> handleRequestConfirmation(call)
            "request_human_help" -> handleRequestHumanHelp(call)
            "finish_task" -> handleFinishTask()
            else -> ToolResult.error("UNKNOWN_TOOL", "Tool '${call.name}' is not recognized")
        }
    }

    private suspend fun handleGetScreenState(): ToolResult {
        val snapshotResult = actions.snapshot()
        val snapshot = snapshotResult.getOrNull()
            ?: return ToolResult.error("SNAPSHOT_FAILED", "Failed to capture active window snapshot")

        checkInactivityAndIncrementStep()

        return when (snapshot) {
            is SnapshotResult.Success -> {
                lastKnownPackage = snapshot.foregroundPackage
                val output = buildJsonObject {
                    put("status", JsonPrimitive("SUCCESS"))
                    put("package", JsonPrimitive(snapshot.foregroundPackage))
                    put("elements", buildJsonArray {
                        for (el in snapshot.elements) {
                            add(buildJsonObject {
                                put("id", JsonPrimitive(el.id))
                                put("label", JsonPrimitive(el.label))
                                put("role", JsonPrimitive(el.role))
                                put("clickable", JsonPrimitive(el.clickable))
                                put("enabled", JsonPrimitive(el.enabled))
                                el.checked?.let { put("checked", JsonPrimitive(it)) }
                                put("bounds_center_x", JsonPrimitive(el.boundsCenterX))
                                put("bounds_center_y", JsonPrimitive(el.boundsCenterY))
                            })
                        }
                    })
                }
                ToolResult.success(output)
            }
            is SnapshotResult.Blocked -> {
                lastKnownPackage = snapshot.foregroundPackage
                // Blocked result contains strictly NO element labels
                val output = buildJsonObject {
                    put("status", JsonPrimitive("BLOCKED"))
                    put("package", JsonPrimitive(snapshot.foregroundPackage))
                    put("decision_code", JsonPrimitive(snapshot.decisionCode))
                }
                ToolResult(output = output, needsHumanHelp = true)
            }
            is SnapshotResult.SnapshotUnavailable -> {
                val output = buildJsonObject {
                    put("status", JsonPrimitive("UNAVAILABLE"))
                    put("message", JsonPrimitive("Active window not accessible"))
                }
                ToolResult.success(output)
            }
        }
    }

    private suspend fun handleTapElement(call: LiveToolCall): ToolResult {
        val validation = ToolArgumentValidator.validateTapElement(call.args)
        if (validation.isFailure) {
            val err = validation.exceptionOrNull()?.message ?: "Invalid element_id"
            return ToolResult.error("BAD_ARGS", err)
        }
        val elementId = validation.getOrThrow()

        // Policy evaluation
        val agentAction = AgentAction(
            type = AgentActionType.Click,
            targetElementId = elementId,
            timestampMs = clock.now()
        )
        val policyContext = PolicyContext(
            foregroundPackage = lastKnownPackage,
            actionsTakenCount = stepCount,
            lastActionTimestampMs = lastActionTimestampMs
        )
        val decision = policyEngine.evaluate(policyContext, agentAction)

        when (decision) {
            is Decision.Block -> {
                return ToolResult.error("POLICY_BLOCKED", "Action blocked by policy: ${decision.code}")
            }
            is Decision.HandOffToHuman -> {
                val output = buildJsonObject {
                    put("status", JsonPrimitive("HANDOFF_TO_HUMAN"))
                    put("code", JsonPrimitive(decision.code))
                }
                return ToolResult(output = output, needsHumanHelp = true)
            }
            is Decision.RequireConfirmation -> {
                // Policy mandates confirmation: force presenter even if model did not call request_confirmation
                val confResult = confirmationPresenter.confirm("Confirmation required by security policy")
                if (confResult != ConfirmationResult.CONFIRMED) {
                    val output = buildJsonObject {
                        put("success", JsonPrimitive(false))
                        put("status", JsonPrimitive(confResult.name))
                        put("reason", JsonPrimitive("Action cancelled or timed out by user"))
                    }
                    return ToolResult.success(output)
                }
            }
            is Decision.Allow -> {
                // Allowed to proceed
            }
        }

        checkInactivityAndIncrementStep()
        val clickResult = actions.clickById(elementId)
        if (clickResult.isFailure) {
            val ex = clickResult.exceptionOrNull()
            if (ex is com.awaz.app.service.StaleScreenException || ex?.message == "STALE_SCREEN") {
                val output = buildJsonObject {
                    put("ok", JsonPrimitive(false))
                    put("code", JsonPrimitive("STALE_SCREEN"))
                }
                return ToolResult.success(output)
            }
        }
        val success = clickResult.getOrDefault(false)

        val output = buildJsonObject {
            put("ok", JsonPrimitive(success))
            put("success", JsonPrimitive(success))
            put("element_id", JsonPrimitive(elementId))
        }
        return ToolResult.success(output)
    }

    private fun handleScrollScreen(call: LiveToolCall): ToolResult {
        val validation = ToolArgumentValidator.validateScrollDirection(call.args)
        if (validation.isFailure) {
            val err = validation.exceptionOrNull()?.message ?: "Invalid direction"
            return ToolResult.error("BAD_ARGS", err)
        }
        val directionStr = validation.getOrThrow()
        val scrollDirection = if (directionStr == "FORWARD") ScrollDirection.FORWARD else ScrollDirection.BACKWARD

        checkInactivityAndIncrementStep()
        val scrollResult = actions.scroll(scrollDirection)
        if (scrollResult.isFailure) {
            val ex = scrollResult.exceptionOrNull()
            if (ex is com.awaz.app.service.StaleScreenException || ex?.message == "STALE_SCREEN") {
                val output = buildJsonObject {
                    put("ok", JsonPrimitive(false))
                    put("code", JsonPrimitive("STALE_SCREEN"))
                }
                return ToolResult.success(output)
            }
        }
        val success = scrollResult.getOrDefault(false)

        val output = buildJsonObject {
            put("ok", JsonPrimitive(success))
            put("success", JsonPrimitive(success))
            put("direction", JsonPrimitive(directionStr))
        }
        return ToolResult.success(output)
    }

    private fun handlePressSystemButton(call: LiveToolCall): ToolResult {
        val validation = ToolArgumentValidator.validateSystemButton(call.args)
        if (validation.isFailure) {
            val err = validation.exceptionOrNull()?.message ?: "Invalid button"
            return ToolResult.error("BAD_ARGS", err)
        }
        val button = validation.getOrThrow()

        checkInactivityAndIncrementStep()
        val result = when (button) {
            "BACK" -> actions.globalBack()
            "HOME" -> actions.globalHome()
            "RECENTS" -> actions.globalRecents()
            else -> Result.success(false)
        }
        val success = result.getOrDefault(false)

        val output = buildJsonObject {
            put("success", JsonPrimitive(success))
            put("button", JsonPrimitive(button))
        }
        return ToolResult.success(output)
    }

    private fun handleOpenApp(call: LiveToolCall): ToolResult {
        val validation = ToolArgumentValidator.validateOpenApp(call.args)
        if (validation.isFailure) {
            val err = validation.exceptionOrNull()?.message ?: "Invalid package_name"
            return ToolResult.error("BAD_ARGS", err)
        }
        val packageName = validation.getOrThrow()

        checkInactivityAndIncrementStep()
        val result = actions.openApp(packageName)
        val success = result.getOrDefault(false)

        val output = buildJsonObject {
            put("success", JsonPrimitive(success))
            put("package_name", JsonPrimitive(packageName))
        }
        return ToolResult.success(output)
    }

    private fun handleOpenSettingsPage(call: LiveToolCall): ToolResult {
        val validation = ToolArgumentValidator.validateOpenSettingsPage(call.args)
        if (validation.isFailure) {
            val err = validation.exceptionOrNull()?.message ?: "Invalid action"
            return ToolResult.error("BAD_ARGS", err)
        }
        val actionName = validation.getOrThrow()

        checkInactivityAndIncrementStep()
        val result = actions.openSettingsPage(actionName)
        val success = result.getOrDefault(false)

        val output = buildJsonObject {
            put("success", JsonPrimitive(success))
            put("action", JsonPrimitive(actionName))
        }
        return ToolResult.success(output)
    }

    private suspend fun handleRequestConfirmation(call: LiveToolCall): ToolResult {
        val validation = ToolArgumentValidator.validateReason(call.args)
        if (validation.isFailure) {
            val err = validation.exceptionOrNull()?.message ?: "Invalid reason"
            return ToolResult.error("BAD_ARGS", err)
        }
        val reason = validation.getOrThrow()

        val confResult = confirmationPresenter.confirm(reason)
        val isConfirmed = confResult == ConfirmationResult.CONFIRMED

        val output = buildJsonObject {
            put("confirmed", JsonPrimitive(isConfirmed))
            put("status", JsonPrimitive(confResult.name))
            put("reason", JsonPrimitive(reason))
        }
        return ToolResult.success(output)
    }

    private fun handleRequestHumanHelp(call: LiveToolCall): ToolResult {
        val validation = ToolArgumentValidator.validateReason(call.args)
        if (validation.isFailure) {
            val err = validation.exceptionOrNull()?.message ?: "Invalid reason"
            return ToolResult.error("BAD_ARGS", err)
        }
        val reason = validation.getOrThrow()

        val output = buildJsonObject {
            put("status", JsonPrimitive("HUMAN_HELP_REQUESTED"))
            put("reason", JsonPrimitive(reason))
        }
        return ToolResult(output = output, needsHumanHelp = true)
    }

    private fun handleFinishTask(): ToolResult {
        stepCount = 0
        lastActionTimestampMs = clock.now()

        val output = buildJsonObject {
            put("status", JsonPrimitive("COMPLETED"))
        }
        return ToolResult(output = output, isCompleted = true)
    }
}
