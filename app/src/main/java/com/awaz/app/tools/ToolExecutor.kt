package com.awaz.app.tools

import com.awaz.app.live.LiveToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ToolResult(
    val output: JsonObject,
    val isCompleted: Boolean = false,
    val needsHumanHelp: Boolean = false
) {
    companion object {
        fun success(data: JsonObject): ToolResult = ToolResult(output = data)

        fun error(code: String, message: String): ToolResult {
            val errObj = buildJsonObject {
                put("error", JsonPrimitive(code))
                put("message", JsonPrimitive(message))
            }
            return ToolResult(output = errObj)
        }
    }
}

interface ToolExecutor {
    suspend fun execute(call: LiveToolCall): ToolResult
}

/**
 * Pure Kotlin argument validator for tool calls.
 * Throws IllegalArgumentException on invalid arguments to be surfaced as BAD_ARGS.
 */
object ToolArgumentValidator {

    fun validateTapElement(args: JsonObject): Result<Int> {
        val elementIdPrimitive = args["element_id"]?.jsonPrimitive
            ?: return Result.failure(IllegalArgumentException("Missing required parameter: 'element_id'"))

        val elementId = elementIdPrimitive.intOrNull
            ?: return Result.failure(IllegalArgumentException("'element_id' must be a valid integer"))

        if (elementId < 0) {
            return Result.failure(IllegalArgumentException("'element_id' cannot be negative"))
        }

        return Result.success(elementId)
    }

    fun validateScrollDirection(args: JsonObject): Result<String> {
        val dir = args["direction"]?.jsonPrimitive?.content?.trim()?.uppercase()
            ?: return Result.failure(IllegalArgumentException("Missing required parameter: 'direction'"))

        if (dir != "FORWARD" && dir != "BACKWARD") {
            return Result.failure(IllegalArgumentException("Invalid direction '$dir'. Must be 'FORWARD' or 'BACKWARD'"))
        }

        return Result.success(dir)
    }

    fun validateSystemButton(args: JsonObject): Result<String> {
        val btn = args["button"]?.jsonPrimitive?.content?.trim()?.uppercase()
            ?: return Result.failure(IllegalArgumentException("Missing required parameter: 'button'"))

        if (btn != "BACK" && btn != "HOME" && btn != "RECENTS") {
            return Result.failure(IllegalArgumentException("Invalid button '$btn'. Must be 'BACK', 'HOME', or 'RECENTS'"))
        }

        return Result.success(btn)
    }

    fun validateOpenApp(args: JsonObject): Result<String> {
        val pkg = args["package_name"]?.jsonPrimitive?.content?.trim()
            ?: return Result.failure(IllegalArgumentException("Missing required parameter: 'package_name'"))

        if (pkg.isBlank()) {
            return Result.failure(IllegalArgumentException("'package_name' cannot be blank"))
        }

        return Result.success(pkg)
    }

    fun validateOpenSettingsPage(args: JsonObject): Result<String> {
        val action = args["action"]?.jsonPrimitive?.content?.trim()
            ?: return Result.failure(IllegalArgumentException("Missing required parameter: 'action'"))

        if (action.isBlank()) {
            return Result.failure(IllegalArgumentException("'action' cannot be blank"))
        }

        return Result.success(action)
    }

    fun validateReason(args: JsonObject): Result<String> {
        val reason = args["reason"]?.jsonPrimitive?.content?.trim()
            ?: return Result.failure(IllegalArgumentException("Missing required parameter: 'reason'"))

        if (reason.isEmpty()) {
            return Result.failure(IllegalArgumentException("'reason' cannot be blank"))
        }

        return Result.success(reason)
    }
}
