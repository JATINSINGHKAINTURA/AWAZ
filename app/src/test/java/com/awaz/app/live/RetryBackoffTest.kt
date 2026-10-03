package com.awaz.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetryBackoffTest {

    @Test
    fun exponentialBackoff_firstAttempt_returns1000ms() {
        val backoff = ExponentialBackoff()
        val delay = backoff.nextDelayMs()
        assertEquals(1000L, delay)
    }

    @Test
    fun exponentialBackoff_secondAttempt_returns2000ms() {
        val backoff = ExponentialBackoff()
        backoff.nextDelayMs()
        val delay = backoff.nextDelayMs()
        assertEquals(2000L, delay)
    }

    @Test
    fun exponentialBackoff_thirdAttempt_returns4000ms() {
        val backoff = ExponentialBackoff()
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        val delay = backoff.nextDelayMs()
        assertEquals(4000L, delay)
    }

    @Test
    fun exponentialBackoff_fourthAttempt_returnsNull() {
        val backoff = ExponentialBackoff()
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        val delay = backoff.nextDelayMs()
        assertNull(delay)
    }

    @Test
    fun exponentialBackoff_reset_restoresInitialState() {
        val backoff = ExponentialBackoff()
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        backoff.reset()
        assertEquals(0, backoff.currentAttempt)
        assertEquals(1000L, backoff.nextDelayMs())
    }
}
