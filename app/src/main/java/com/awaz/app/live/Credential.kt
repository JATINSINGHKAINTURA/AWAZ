package com.awaz.app.live

import com.awaz.app.BuildConfig

/**
 * Encapsulates credentials for the Gemini Live API with automatic redaction in logs/toString.
 */
sealed class Credential {
    abstract val value: String

    data class ApiKey(override val value: String) : Credential() {
        override fun toString(): String = "ApiKey(REDACTED)"
    }

    data class Ephemeral(override val value: String) : Credential() {
        override fun toString(): String = "Ephemeral(REDACTED)"
    }
}

/**
 * Provides an active Credential for connecting to the Gemini Live WebSocket.
 */
interface TokenProvider {
    suspend fun get(): Credential?
}

/**
 * Reads developer token injected at build time from BuildConfig.GEMINI_DEV_TOKEN.
 */
class DevTokenProvider(
    private val token: String = BuildConfig.GEMINI_DEV_TOKEN
) : TokenProvider {
    override suspend fun get(): Credential? {
        val trimmed = token.trim()
        return if (trimmed.isNotBlank()) Credential.ApiKey(trimmed) else null
    }
}

const val DEFAULT_GEMINI_LIVE_BASE_URL =
    "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

/**
 * Builds the authenticated WebSocket URL for the Gemini Live API.
 * Never logs credentials or returns exposed tokens to unauthorized callers.
 */
fun buildLiveUrl(base: String, c: Credential): String {
    val cleanBase = base.trim()
    val delimiter = if (cleanBase.contains("?")) "&" else "?"
    return when (c) {
        is Credential.ApiKey -> "$cleanBase${delimiter}key=${c.value}"
        is Credential.Ephemeral -> "$cleanBase${delimiter}access_token=${c.value}"
    }
}
