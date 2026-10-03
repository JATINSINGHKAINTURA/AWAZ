package com.awaz.app.live

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LosslessEventFlowTest {

    @Test
    fun transportEventFlow_delivers1000EventsInStrictOrderWithZeroDrops() = runBlocking {
        val fakeTransport = FakeTransport()
        val receivedEvents = mutableListOf<LiveEvent>()

        val collectJob = launch {
            fakeTransport.events.take(1000).toList(receivedEvents)
        }

        // Emit 1,000 distinct events rapidly without waiting for collector
        for (i in 1..1000) {
            val event = LiveEvent.InputTranscript("Event #$i")
            fakeTransport.emitEvent(event)
        }

        collectJob.join()

        assertEquals(1000, receivedEvents.size)
        for (i in 1..1000) {
            val expectedText = "Event #$i"
            val actualText = (receivedEvents[i - 1] as LiveEvent.InputTranscript).text
            assertEquals(expectedText, actualText)
        }
    }
}
