package com.awaz.app.live

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialTest {

    @Test
    fun apiKey_toString_isRedacted() {
        val cred = Credential.ApiKey("secret-api-key-12345")
        assertEquals("ApiKey(REDACTED)", cred.toString())
        assertFalse(cred.toString().contains("12345"))
    }

    @Test
    fun ephemeral_toString_isRedacted() {
        val cred = Credential.Ephemeral("ephemeral-token-67890")
        assertEquals("Ephemeral(REDACTED)", cred.toString())
        assertFalse(cred.toString().contains("67890"))
    }

    @Test
    fun buildLiveUrl_withApiKey_appendsKeyParam() {
        val base = "wss://generativelanguage.googleapis.com/ws/live"
        val cred = Credential.ApiKey("test-key")
        val result = buildLiveUrl(base, cred)
        assertEquals("wss://generativelanguage.googleapis.com/ws/live?key=test-key", result)
    }

    @Test
    fun buildLiveUrl_withApiKey_whenUrlHasQueryParams_appendsAmpersandKeyParam() {
        val base = "wss://generativelanguage.googleapis.com/ws/live?version=v1"
        val cred = Credential.ApiKey("test-key")
        val result = buildLiveUrl(base, cred)
        assertEquals("wss://generativelanguage.googleapis.com/ws/live?version=v1&key=test-key", result)
    }

    @Test
    fun buildLiveUrl_withEphemeral_appendsAccessTokenParam() {
        val base = "wss://generativelanguage.googleapis.com/ws/live"
        val cred = Credential.Ephemeral("oauth-token")
        val result = buildLiveUrl(base, cred)
        assertEquals("wss://generativelanguage.googleapis.com/ws/live?access_token=oauth-token", result)
    }

    @Test
    fun devTokenProvider_whenTokenIsBlank_returnsNull() = runBlocking {
        val provider = DevTokenProvider(token = "   ")
        val cred = provider.get()
        assertNull(cred)
    }

    @Test
    fun devTokenProvider_whenTokenIsProvided_returnsApiKeyCredential() = runBlocking {
        val provider = DevTokenProvider(token = "valid-token-xyz")
        val cred = provider.get()
        assertTrue(cred is Credential.ApiKey)
        assertEquals("valid-token-xyz", (cred as Credential.ApiKey).value)
    }
}
