package com.awaz.app.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityRedactionTest {

    @Test
    fun connectionFailureMessage_withApiKey_redactsKeyParameter() {
        val rawErrorMessage = "Failed to connect to wss://generativelanguage.googleapis.com/ws/live?key=AIzaSySecretApiKey12345: 403 Forbidden"
        val sanitized = OkHttpLiveTransport.sanitizeMessage(rawErrorMessage)

        assertFalse(sanitized.contains("AIzaSySecretApiKey12345"))
        assertTrue(sanitized.contains("key=REDACTED"))
    }

    @Test
    fun connectionFailureMessage_withAccessToken_redactsTokenParameter() {
        val rawErrorMessage = "HTTP 401 Unauthorized for wss://generativelanguage.googleapis.com/ws/live?access_token=ya29.a0AfH6SMSecretToken99"
        val sanitized = OkHttpLiveTransport.sanitizeMessage(rawErrorMessage)

        assertFalse(sanitized.contains("ya29.a0AfH6SMSecretToken99"))
        assertTrue(sanitized.contains("access_token=REDACTED"))
    }

    @Test
    fun credentialToString_doesNotContainSecretKey() {
        val apiKey = "AIzaSyTest123456789"
        val cred = Credential.ApiKey(apiKey)
        assertFalse(cred.toString().contains(apiKey))
        assertTrue(cred.toString().contains("REDACTED"))
    }

    @Test
    fun credentialToString_doesNotContainEphemeralToken() {
        val token = "ephemeral_secret_token_abc"
        val cred = Credential.Ephemeral(token)
        assertFalse(cred.toString().contains(token))
        assertTrue(cred.toString().contains("REDACTED"))
    }
}
