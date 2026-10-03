package com.awaz.app.live

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenConfigTest {

    @Test
    fun resolveToken_inReleaseBuild_alwaysReturnsEmptyStringEvenIfPropertyProvided() {
        val result = TokenConfigResolver.resolveToken(
            isDebug = false,
            gradlePropertyToken = "AIzaSySecretTokenInRelease"
        )
        assertEquals("", result)
    }

    @Test
    fun resolveToken_inDebugBuild_whenPropertyProvided_returnsTrimmedToken() {
        val result = TokenConfigResolver.resolveToken(
            isDebug = true,
            gradlePropertyToken = "  valid-debug-token-123  "
        )
        assertEquals("valid-debug-token-123", result)
    }

    @Test
    fun resolveToken_inDebugBuild_whenPropertyMissing_returnsEmptyString() {
        val result = TokenConfigResolver.resolveToken(
            isDebug = true,
            gradlePropertyToken = null
        )
        assertEquals("", result)
    }

    @Test
    fun resolveToken_inDebugBuild_whenPropertyBlank_returnsEmptyString() {
        val result = TokenConfigResolver.resolveToken(
            isDebug = true,
            gradlePropertyToken = "    "
        )
        assertEquals("", result)
    }
}
