package com.awaz.app.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolValidationTest {

    @Test
    fun validateTapElement_withMissingElementId_returnsFailure() {
        val args = buildJsonObject {}
        val result = ToolArgumentValidator.validateTapElement(args)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Missing required parameter") == true)
    }

    @Test
    fun validateTapElement_withNonIntegerElementId_returnsFailure() {
        val args = buildJsonObject {
            put("element_id", JsonPrimitive("not-a-number"))
        }
        val result = ToolArgumentValidator.validateTapElement(args)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("must be a valid integer") == true)
    }

    @Test
    fun validateTapElement_withNegativeElementId_returnsFailure() {
        val args = buildJsonObject {
            put("element_id", JsonPrimitive(-5))
        }
        val result = ToolArgumentValidator.validateTapElement(args)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("cannot be negative") == true)
    }

    @Test
    fun validateTapElement_withValidElementId_returnsSuccess() {
        val args = buildJsonObject {
            put("element_id", JsonPrimitive(42))
        }
        val result = ToolArgumentValidator.validateTapElement(args)
        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun validateScrollDirection_withMissingDirection_returnsFailure() {
        val args = buildJsonObject {}
        val result = ToolArgumentValidator.validateScrollDirection(args)
        assertTrue(result.isFailure)
    }

    @Test
    fun validateScrollDirection_withInvalidDirection_returnsFailure() {
        val args = buildJsonObject {
            put("direction", JsonPrimitive("SIDEWAYS"))
        }
        val result = ToolArgumentValidator.validateScrollDirection(args)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Invalid direction") == true)
    }

    @Test
    fun validateScrollDirection_withForward_returnsSuccess() {
        val args = buildJsonObject {
            put("direction", JsonPrimitive("forward"))
        }
        val result = ToolArgumentValidator.validateScrollDirection(args)
        assertTrue(result.isSuccess)
        assertEquals("FORWARD", result.getOrNull())
    }

    @Test
    fun validateScrollDirection_withBackward_returnsSuccess() {
        val args = buildJsonObject {
            put("direction", JsonPrimitive("BACKWARD"))
        }
        val result = ToolArgumentValidator.validateScrollDirection(args)
        assertTrue(result.isSuccess)
        assertEquals("BACKWARD", result.getOrNull())
    }

    @Test
    fun validateSystemButton_withMissingButton_returnsFailure() {
        val args = buildJsonObject {}
        val result = ToolArgumentValidator.validateSystemButton(args)
        assertTrue(result.isFailure)
    }

    @Test
    fun validateSystemButton_withInvalidButton_returnsFailure() {
        val args = buildJsonObject {
            put("button", JsonPrimitive("POWER"))
        }
        val result = ToolArgumentValidator.validateSystemButton(args)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Invalid button") == true)
    }

    @Test
    fun validateSystemButton_withBack_returnsSuccess() {
        val args = buildJsonObject {
            put("button", JsonPrimitive("back"))
        }
        val result = ToolArgumentValidator.validateSystemButton(args)
        assertTrue(result.isSuccess)
        assertEquals("BACK", result.getOrNull())
    }

    @Test
    fun validateSystemButton_withHome_returnsSuccess() {
        val args = buildJsonObject {
            put("button", JsonPrimitive("HOME"))
        }
        val result = ToolArgumentValidator.validateSystemButton(args)
        assertTrue(result.isSuccess)
        assertEquals("HOME", result.getOrNull())
    }

    @Test
    fun validateSystemButton_withRecents_returnsSuccess() {
        val args = buildJsonObject {
            put("button", JsonPrimitive("recents"))
        }
        val result = ToolArgumentValidator.validateSystemButton(args)
        assertTrue(result.isSuccess)
        assertEquals("RECENTS", result.getOrNull())
    }

    @Test
    fun validateReason_withMissingReason_returnsFailure() {
        val args = buildJsonObject {}
        val result = ToolArgumentValidator.validateReason(args)
        assertTrue(result.isFailure)
    }

    @Test
    fun validateReason_withBlankReason_returnsFailure() {
        val args = buildJsonObject {
            put("reason", JsonPrimitive("   "))
        }
        val result = ToolArgumentValidator.validateReason(args)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("cannot be blank") == true)
    }

    @Test
    fun validateReason_withValidReason_returnsSuccess() {
        val args = buildJsonObject {
            put("reason", JsonPrimitive("Payment confirmation"))
        }
        val result = ToolArgumentValidator.validateReason(args)
        assertTrue(result.isSuccess)
        assertEquals("Payment confirmation", result.getOrNull())
    }
}
