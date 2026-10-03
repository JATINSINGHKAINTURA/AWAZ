package com.awaz.app.live

import com.awaz.app.audio.AudioSink
import com.awaz.app.audio.AudioSource
import com.awaz.app.tools.ToolExecutor
import com.awaz.app.tools.ToolResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionControllerTest {

    private lateinit var fakeTransport: FakeTransport
    private lateinit var fakeAudioSource: FakeAudioSource
    private lateinit var fakeAudioSink: FakeAudioSink
    private lateinit var fakeToolExecutor: FakeToolExecutor
    private val tokenProvider = object : TokenProvider {
        override suspend fun get(): Credential = Credential.ApiKey("test-api-key")
    }

    @Before
    fun setUp() {
        fakeTransport = FakeTransport()
        fakeAudioSource = FakeAudioSource()
        fakeAudioSink = FakeAudioSink()
        fakeToolExecutor = FakeToolExecutor()
    }

    private fun createController(testScope: TestScope): LiveSessionController {
        return LiveSessionController(
            tokenProvider = tokenProvider,
            transport = fakeTransport,
            audioSource = fakeAudioSource,
            audioSink = fakeAudioSink,
            toolExecutor = fakeToolExecutor,
            setupJsonSupplier = { """{"setup":{"model":"models/gemini-3.8-live"}}""" },
            serviceRunningChecker = { true },
            errorTonePlayer = {},
            baseUrl = "wss://test.gemini.live",
            scope = testScope
        )
    }

    @Test
    fun reconnect_retriesWithBackoff1s2s4s_thenTransitionsToFailed() = runTest {
        val controller = createController(this)
        controller.startSession()
        runCurrent()

        // 1st disconnect (triggers GoAway)
        fakeTransport.emitEvent(LiveEvent.GoAway)
        runCurrent()
        assertTrue(controller.sessionState.value is LiveSessionState.Reconnecting)
        assertEquals(1, (controller.sessionState.value as LiveSessionState.Reconnecting).attempt)
        assertEquals(1000L, (controller.sessionState.value as LiveSessionState.Reconnecting).nextDelayMs)

        // Advance 1000ms: attempt 2
        advanceTimeBy(1001L)
        runCurrent()
        fakeTransport.emitEvent(LiveEvent.GoAway)
        runCurrent()
        assertTrue(controller.sessionState.value is LiveSessionState.Reconnecting)
        assertEquals(2, (controller.sessionState.value as LiveSessionState.Reconnecting).attempt)
        assertEquals(2000L, (controller.sessionState.value as LiveSessionState.Reconnecting).nextDelayMs)

        // Advance 2000ms: attempt 3
        advanceTimeBy(2001L)
        runCurrent()
        fakeTransport.emitEvent(LiveEvent.GoAway)
        runCurrent()
        assertTrue(controller.sessionState.value is LiveSessionState.Reconnecting)
        assertEquals(3, (controller.sessionState.value as LiveSessionState.Reconnecting).attempt)
        assertEquals(4000L, (controller.sessionState.value as LiveSessionState.Reconnecting).nextDelayMs)

        // Advance 4000ms: max retries reached -> Failed
        advanceTimeBy(4001L)
        runCurrent()
        fakeTransport.emitEvent(LiveEvent.GoAway)
        runCurrent()
        assertTrue(controller.sessionState.value is LiveSessionState.Failed)

        controller.stopSession()
    }

    @Test
    fun interruptedEvent_callsSpeakerPlayerFlushViaInterface() = runTest {
        val controller = createController(this)
        controller.startSession()
        runCurrent()

        fakeTransport.emitEvent(LiveEvent.SetupComplete)
        runCurrent()

        fakeTransport.emitEvent(LiveEvent.Interrupted)
        runCurrent()

        assertTrue(fakeAudioSink.flushCalled)
        controller.stopSession()
    }

    @Test
    fun toolCallCancelled_preventsQueuedCallFromExecuting() = runTest {
        val controller = createController(this)
        controller.startSession()
        runCurrent()

        fakeTransport.emitEvent(LiveEvent.SetupComplete)
        runCurrent()

        val call1 = LiveToolCall(id = "call-1", name = "tap_element", args = buildJsonObject {})
        val call2 = LiveToolCall(id = "call-2", name = "scroll_screen", args = buildJsonObject {})

        // Cancel call1 before it processes
        fakeTransport.emitEvent(LiveEvent.ToolCallCancelled(listOf("call-1")))
        fakeTransport.emitEvent(LiveEvent.ToolCalls(listOf(call1, call2)))
        runCurrent()

        // Only call2 should have executed; call1 was cancelled
        assertEquals(1, fakeToolExecutor.executedCalls.size)
        assertEquals("call-2", fakeToolExecutor.executedCalls[0].id)

        controller.stopSession()
    }

    @Test
    fun toolCalls_executeStrictlyInArrivalOrder() = runTest {
        val controller = createController(this)
        controller.startSession()
        runCurrent()

        fakeTransport.emitEvent(LiveEvent.SetupComplete)
        runCurrent()

        val callA = LiveToolCall(id = "call-A", name = "get_screen_state", args = buildJsonObject {})
        val callB = LiveToolCall(id = "call-B", name = "tap_element", args = buildJsonObject {})
        val callC = LiveToolCall(id = "call-C", name = "scroll_screen", args = buildJsonObject {})

        fakeTransport.emitEvent(LiveEvent.ToolCalls(listOf(callA, callB, callC)))
        runCurrent()

        assertEquals(3, fakeToolExecutor.executedCalls.size)
        assertEquals("call-A", fakeToolExecutor.executedCalls[0].id)
        assertEquals("call-B", fakeToolExecutor.executedCalls[1].id)
        assertEquals("call-C", fakeToolExecutor.executedCalls[2].id)

        controller.stopSession()
    }

    @Test
    fun goAwayEvent_triggersReconnectUsingStoredResumptionHandle() = runTest {
        val controller = createController(this)
        controller.startSession()
        runCurrent()

        // Store resumption handle
        fakeTransport.emitEvent(LiveEvent.ResumptionUpdate("saved-token-99"))
        runCurrent()

        // Server sends GoAway
        fakeTransport.emitEvent(LiveEvent.GoAway)
        runCurrent()

        // Controller should be in Reconnecting state and reconnect with backoff
        assertTrue(controller.sessionState.value is LiveSessionState.Reconnecting)

        controller.stopSession()
    }

    @Test
    fun audioChunks_areNotSentBeforeSetupComplete() = runTest {
        val controller = createController(this)
        controller.startSession()
        runCurrent()

        // Emit mic audio BEFORE SetupComplete
        val initialTextsCount = fakeTransport.sentTexts.size // Only setup sent
        fakeAudioSource.emitChunk(byteArrayOf(1, 2, 3))
        runCurrent()

        // No realtimeInput chunk should have been sent yet
        assertEquals(initialTextsCount, fakeTransport.sentTexts.size)

        // Now emit SetupComplete
        fakeTransport.emitEvent(LiveEvent.SetupComplete)
        runCurrent()

        // Emit chunk AFTER SetupComplete
        fakeAudioSource.emitChunk(byteArrayOf(4, 5, 6))
        runCurrent()

        // An audio message should now have been sent
        assertTrue(fakeTransport.sentTexts.any { it.contains("realtimeInput") })

        controller.stopSession()
    }

    @Test
    fun stopSession_sendsAudioStreamEnd() = runTest {
        val controller = createController(this)
        controller.startSession()
        runCurrent()

        fakeTransport.emitEvent(LiveEvent.SetupComplete)
        runCurrent()

        controller.stopSession()
        runCurrent()

        // Check that audioStreamEnd (turnComplete: true) was sent
        assertTrue(fakeTransport.sentTexts.any { it.contains("\"turnComplete\":true") })
    }

    // Fakes
    private class FakeAudioSource : AudioSource {
        private val _flow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        override fun startCapture(): Flow<ByteArray> = _flow.asSharedFlow()
        override fun stop() {}

        fun emitChunk(bytes: ByteArray) {
            _flow.tryEmit(bytes)
        }
    }

    private class FakeAudioSink : AudioSink {
        private val _isSpeaking = MutableStateFlow(false)
        override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()
        override var muteMicWhileModelSpeaks: Boolean = false
        var flushCalled = false
        var stopCalled = false
        val written = mutableListOf<ByteArray>()

        override fun start() {}
        override fun write(pcmChunk: ByteArray) { written.add(pcmChunk) }
        override fun flush() { flushCalled = true }
        override fun stop() { stopCalled = true }
    }

    private class FakeToolExecutor : ToolExecutor {
        val executedCalls = mutableListOf<LiveToolCall>()

        override suspend fun execute(call: LiveToolCall): ToolResult {
            executedCalls.add(call)
            return ToolResult.success(buildJsonObject { put("success", JsonPrimitive(true)) })
        }
    }
}
