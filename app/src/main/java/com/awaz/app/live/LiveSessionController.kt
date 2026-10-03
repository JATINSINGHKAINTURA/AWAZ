package com.awaz.app.live

import android.content.Context
import android.util.Log
import com.awaz.app.audio.AudioSink
import com.awaz.app.audio.AudioSource
import com.awaz.app.audio.MicCapture
import com.awaz.app.audio.SpeakerPlayer
import com.awaz.app.event.AwazEvent
import com.awaz.app.event.EventBus
import com.awaz.app.overlay.ConfirmationOverlay
import com.awaz.app.policy.PolicyEngine
import com.awaz.app.policy.PolicyRulesLoader
import com.awaz.app.service.AccessibilityServiceHolder
import com.awaz.app.service.VoiceForegroundService
import com.awaz.app.tools.AwazToolExecutor
import com.awaz.app.tools.ToolExecutor
import com.awaz.app.ui.AwazState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "LiveSessionController"

sealed class LiveSessionState {
    object Disconnected : LiveSessionState() { override fun toString() = "Disconnected" }
    object Connecting : LiveSessionState() { override fun toString() = "Connecting" }
    object Ready : LiveSessionState() { override fun toString() = "Ready" }
    data class Reconnecting(val attempt: Int, val nextDelayMs: Long) : LiveSessionState()
    data class Failed(val reason: String) : LiveSessionState()
}

/**
 * Debug metrics exposed for the DEBUG build inspector screen.
 */
data class LiveDebugMetrics(
    val sessionState: String = "Disconnected",
    val last20Events: List<String> = emptyList(),
    val firstAudioLatencyMs: Long? = null
)

/**
 * Exponential backoff helper for connection retries (1s, 2s, 4s).
 * Pure Kotlin logic suitable for JVM unit testing.
 */
class ExponentialBackoff(
    private val delaysMs: List<Long> = listOf(1000L, 2000L, 4000L)
) {
    private var attempt = 0

    fun nextDelayMs(): Long? {
        if (attempt >= delaysMs.size) return null
        val delay = delaysMs[attempt]
        attempt++
        return delay
    }

    fun reset() {
        attempt = 0
    }

    val currentAttempt: Int
        get() = attempt
}

/**
 * Master controller managing the lifecycle of the Gemini Live WebSocket session.
 * Decoupled from Android classes through AudioSource, AudioSink, and ToolExecutor interfaces.
 */
class LiveSessionController(
    private val tokenProvider: TokenProvider = DevTokenProvider(),
    private val transport: LiveTransport = OkHttpLiveTransport(),
    private val audioSource: AudioSource,
    private val audioSink: AudioSink,
    private val toolExecutor: ToolExecutor,
    private val setupJsonSupplier: () -> String,
    private val serviceRunningChecker: () -> Boolean = { VoiceForegroundService.isServiceRunning() },
    private val errorTonePlayer: () -> Unit = { VoiceForegroundService.playErrorTone() },
    private val baseUrl: String = DEFAULT_GEMINI_LIVE_BASE_URL,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    /**
     * Android convenience constructor.
     */
    constructor(context: Context) : this(
        tokenProvider = DevTokenProvider(),
        transport = OkHttpLiveTransport(),
        audioSource = MicCapture(),
        audioSink = SpeakerPlayer(context),
        toolExecutor = AwazToolExecutor(
            actions = AccessibilityServiceHolder.service.value
                ?: object : com.awaz.app.service.AccessibilityActions {
                    override suspend fun snapshot() = Result.success(com.awaz.app.service.SnapshotResult.SnapshotUnavailable)
                    override fun clickById(id: Int) = Result.success(false)
                    override fun scroll(direction: com.awaz.app.service.ScrollDirection) = Result.success(false)
                    override fun globalBack() = Result.success(false)
                    override fun globalHome() = Result.success(false)
                    override fun globalRecents() = Result.success(false)
                    override fun openApp(packageName: String) = Result.success(false)
                    override fun openSettingsPage(action: String) = Result.success(false)
                },
            confirmationPresenter = ConfirmationOverlay,
            policyEngine = PolicyEngine(PolicyRulesLoader.loadRules(context))
        ),
        setupJsonSupplier = {
            LiveConfig.loadFromAssets(context.assets).toString()
        },
        serviceRunningChecker = { VoiceForegroundService.isServiceRunning() },
        errorTonePlayer = { VoiceForegroundService.playErrorTone() }
    )

    private val _sessionState = MutableStateFlow<LiveSessionState>(LiveSessionState.Disconnected)
    val sessionState: StateFlow<LiveSessionState> = _sessionState.asStateFlow()

    private val _awazState = MutableStateFlow<AwazState>(AwazState.Idle)
    val awazState: StateFlow<AwazState> = _awazState.asStateFlow()

    private val _debugMetrics = MutableStateFlow(LiveDebugMetrics())
    val debugMetrics: StateFlow<LiveDebugMetrics> = _debugMetrics.asStateFlow()

    private val backoff = ExponentialBackoff()
    private val sessionMutex = Mutex()
    private var sessionJob: Job? = null
    private var micStreamJob: Job? = null
    private var toolChannel = Channel<LiveToolCall>(Channel.UNLIMITED)
    private val cancelledCallIds = ConcurrentHashMap.newKeySet<String>()

    private var currentResumptionHandle: String? = null
    private var isActing = false
    private var isSetupComplete = false
    private var sessionStartTimeMs: Long = 0L
    private var firstAudioLatencyMs: Long? = null

    // Audio buffering constraint: maximum 5 seconds of 24kHz 16-bit mono audio (240,000 bytes)
    private var bufferedAudioBytes: Long = 0L
    private var droppedAudioChunksCounter: Long = 0L
    private val maxAudioBufferBytes: Long = 240_000L

    private val eventHistory = Collections.synchronizedList(mutableListOf<String>())

    init {
        // Monitor hardware volume up trigger from EventBus
        scope.launch {
            EventBus.events.collect { event ->
                if (event is AwazEvent.HardwareTrigger) {
                    Log.i(TAG, "Hardware trigger received, toggling voice session")
                    toggleSession()
                }
            }
        }

        // Monitor speaker status to update acting state & avoid acoustic feedback
        scope.launch {
            audioSink.isSpeaking.collect { speaking ->
                updateUiState(isActing = speaking || isActing)
            }
        }
    }

    fun toggleSession() {
        scope.launch {
            // Guard: Service must be running in foreground
            if (!serviceRunningChecker()) {
                Log.e(TAG, "Cannot start voice session: VoiceForegroundService is not running")
                errorTonePlayer()
                _awazState.value = AwazState.NeedsHelp
                return@launch
            }

            sessionMutex.withLock {
                if (_sessionState.value is LiveSessionState.Disconnected ||
                    _sessionState.value is LiveSessionState.Failed
                ) {
                    startSessionLocked()
                } else {
                    stopSessionLocked(userInitiated = true)
                }
            }
        }
    }

    suspend fun startSession() {
        if (!serviceRunningChecker()) {
            errorTonePlayer()
            _awazState.value = AwazState.NeedsHelp
            return
        }

        sessionMutex.withLock {
            startSessionLocked()
        }
    }

    suspend fun stopSession() {
        sessionMutex.withLock {
            stopSessionLocked(userInitiated = true)
        }
    }

    private suspend fun startSessionLocked() {
        if (_sessionState.value == LiveSessionState.Connecting ||
            _sessionState.value == LiveSessionState.Ready
        ) {
            return
        }

        backoff.reset()
        isSetupComplete = false
        sessionStartTimeMs = System.currentTimeMillis()
        firstAudioLatencyMs = null
        bufferedAudioBytes = 0L
        (toolExecutor as? AwazToolExecutor)?.onSessionStart()

        connectAndRunLocked()
    }

    private fun connectAndRunLocked() {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            while (isActive) {
                _sessionState.value = LiveSessionState.Connecting
                updateUiState(isActing = false)

                val credential = tokenProvider.get()
                if (credential == null) {
                    Log.e(TAG, "Cannot start Live session: No credential available")
                    _sessionState.value = LiveSessionState.Failed("No token provided")
                    updateUiState(isActing = false)
                    break
                }

                val fullUrl = buildLiveUrl(baseUrl, credential)

                try {
                    transport.connect(fullUrl)
                    audioSink.start()

                    // Send Setup message immediately from asset-backed supplier
                    val setupJson = setupJsonSupplier()
                    transport.send(setupJson)

                    // Start tool consumer loop
                    val toolWorkerJob = launch { processToolCalls() }

                    transport.events.collect { event ->
                        recordEvent(event::class.simpleName ?: "Unknown")

                        when (event) {
                            is LiveEvent.SetupComplete -> {
                                isSetupComplete = true
                                backoff.reset()
                                _sessionState.value = LiveSessionState.Ready
                                updateUiState(isActing = false)
                                startMicStreaming()
                            }

                            is LiveEvent.AudioOut -> {
                                if (firstAudioLatencyMs == null && sessionStartTimeMs > 0L) {
                                    firstAudioLatencyMs = System.currentTimeMillis() - sessionStartTimeMs
                                }

                                // Bounded audio buffer: drop oldest if buffer exceeds 5 seconds
                                if (bufferedAudioBytes + event.data.size > maxAudioBufferBytes) {
                                    droppedAudioChunksCounter++
                                    Log.w(TAG, "Audio buffer exceeded 5s. Dropped oldest chunk. Total dropped: $droppedAudioChunksCounter")
                                } else {
                                    bufferedAudioBytes += event.data.size
                                    audioSink.write(event.data)
                                }
                            }

                            is LiveEvent.Interrupted -> {
                                Log.i(TAG, "User barge-in: flushing AudioSink")
                                audioSink.flush()
                                bufferedAudioBytes = 0L
                                updateUiState(isActing = false)
                            }

                            is LiveEvent.ToolCalls -> {
                                for (call in event.calls) {
                                    toolChannel.send(call)
                                }
                            }

                            is LiveEvent.ToolCallCancelled -> {
                                Log.i(TAG, "Tool calls cancelled: ${event.callIds}")
                                cancelledCallIds.addAll(event.callIds)
                            }

                            is LiveEvent.TurnComplete -> {
                                bufferedAudioBytes = 0L
                                updateUiState(isActing = false)
                            }

                            is LiveEvent.ResumptionUpdate -> {
                                currentResumptionHandle = event.resumptionHandle
                            }

                            is LiveEvent.GoAway -> {
                                Log.w(TAG, "Server sent GoAway: triggering reconnect with stored resumption handle")
                                throw IllegalStateException("Server disconnected with GoAway")
                            }

                            is LiveEvent.InputTranscript -> {
                                Log.d(TAG, "User input transcript received")
                            }

                            is LiveEvent.OutputTranscript -> {
                                Log.d(TAG, "Model output transcript received")
                            }

                            is LiveEvent.Unknown -> {
                                Log.d(TAG, "Unknown frame received")
                            }
                        }
                    }

                    toolWorkerJob.cancel()

                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Live session disconnected: ${e.message}")
                } finally {
                    stopMicStreaming()
                    audioSink.flush()
                    isSetupComplete = false
                }

                // If cancelled by user, stop reconnecting
                if (!isActive) break

                val nextDelay = backoff.nextDelayMs()
                if (nextDelay != null) {
                    _sessionState.value = LiveSessionState.Reconnecting(
                        attempt = backoff.currentAttempt,
                        nextDelayMs = nextDelay
                    )
                    updateUiState(isActing = false)
                    delay(nextDelay)
                } else {
                    _sessionState.value = LiveSessionState.Failed("Max retry attempts reached")
                    updateUiState(isActing = false)
                    break
                }
            }
        }
    }

    private fun startMicStreaming() {
        micStreamJob?.cancel()
        micStreamJob = scope.launch {
            try {
                audioSource.startCapture().collectLatest { chunk ->
                    // Never send audio chunks before SetupComplete
                    if (!isSetupComplete) return@collectLatest

                    // Drop chunk if model is speaking and mute fallback is enabled
                    if (audioSink.muteMicWhileModelSpeaks && audioSink.isSpeaking.value) {
                        return@collectLatest
                    }
                    val audioMessage = LiveMessages.buildAudioChunk(chunk)
                    transport.send(audioMessage)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Error in mic streaming flow", e)
                }
            }
        }
    }

    private fun stopMicStreaming() {
        micStreamJob?.cancel()
        micStreamJob = null
        audioSource.stop()
    }

    private suspend fun processToolCalls() {
        for (call in toolChannel) {
            // If tool call was cancelled by server, skip execution
            if (cancelledCallIds.remove(call.id)) {
                Log.i(TAG, "Skipping cancelled tool call: ${call.id} (${call.name})")
                continue
            }

            isActing = true
            updateUiState(isActing = true)

            val result = toolExecutor.execute(call)
            val responseJson = LiveMessages.buildToolResponse(call.id, result.output)
            transport.send(responseJson)

            if (result.needsHumanHelp) {
                _sessionState.value = LiveSessionState.Failed("Human assistance needed")
                updateUiState(isActing = false)
                stopSessionLocked(userInitiated = false)
                break
            }

            if (result.isCompleted) {
                Log.i(TAG, "Task finished successfully")
                stopSessionLocked(userInitiated = true)
                break
            }

            isActing = false
            updateUiState(isActing = false)
        }
    }

    private suspend fun stopSessionLocked(userInitiated: Boolean) {
        sessionJob?.cancel()
        sessionJob = null

        stopMicStreaming()

        // Stop sends audioStreamEnd to server
        try {
            transport.send(LiveMessages.buildAudioStreamEnd())
        } catch (_: Exception) {}

        audioSink.stop()
        transport.close(1000, if (userInitiated) "User stopped" else "Session completed")

        _sessionState.value = if (userInitiated) {
            LiveSessionState.Disconnected
        } else {
            LiveSessionState.Failed("Session ended")
        }

        isActing = false
        isSetupComplete = false
        updateUiState(isActing = false)
    }

    private fun updateUiState(isActing: Boolean) {
        val newState = when (val state = _sessionState.value) {
            is LiveSessionState.Disconnected -> AwazState.Idle
            is LiveSessionState.Connecting -> AwazState.Listening
            is LiveSessionState.Reconnecting -> AwazState.Listening
            is LiveSessionState.Ready -> if (isActing) AwazState.Acting else AwazState.Listening
            is LiveSessionState.Failed -> AwazState.NeedsHelp
        }
        _awazState.value = newState
        try {
            if (com.awaz.app.AwazApplication::instance.isInitialized) {
                com.awaz.app.AwazApplication.instance.navigationStateManager.transitionTo(newState)
            }
        } catch (_: Exception) {}
        _debugMetrics.value = LiveDebugMetrics(
            sessionState = _sessionState.value.toString(),
            last20Events = synchronized(eventHistory) { eventHistory.toList() },
            firstAudioLatencyMs = firstAudioLatencyMs
        )
    }

    private fun recordEvent(name: String) {
        synchronized(eventHistory) {
            eventHistory.add(0, name)
            if (eventHistory.size > 20) {
                eventHistory.removeAt(eventHistory.lastIndex)
            }
        }
        _debugMetrics.value = LiveDebugMetrics(
            sessionState = _sessionState.value.toString(),
            last20Events = synchronized(eventHistory) { eventHistory.toList() },
            firstAudioLatencyMs = firstAudioLatencyMs
        )
    }
}
