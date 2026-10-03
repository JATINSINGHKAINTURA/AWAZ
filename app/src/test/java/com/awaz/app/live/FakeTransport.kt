package com.awaz.app.live

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Lossless Fake implementation of LiveTransport for JVM unit testing.
 * Uses an UNLIMITED channel to guarantee zero dropped events and FIFO arrival.
 */
class FakeTransport : LiveTransport {

    private val eventChannel = Channel<LiveEvent>(Channel.UNLIMITED)
    override val events: Flow<LiveEvent> = eventChannel.receiveAsFlow()

    private var _isConnected = false
    override val isConnected: Boolean
        get() = _isConnected

    val sentTexts = mutableListOf<String>()
    val sentBytes = mutableListOf<ByteArray>()
    var lastConnectedUrl: String? = null
    var closedCode: Int? = null
    var closedReason: String? = null

    override suspend fun connect(url: String, headers: Map<String, String>) {
        _isConnected = true
        lastConnectedUrl = url
    }

    override suspend fun send(text: String): Boolean {
        sentTexts.add(text)
        return true
    }

    override suspend fun send(bytes: ByteArray): Boolean {
        sentBytes.add(bytes)
        return true
    }

    override suspend fun close(code: Int, reason: String) {
        _isConnected = false
        closedCode = code
        closedReason = reason
    }

    fun emitEvent(event: LiveEvent) {
        eventChannel.trySend(event)
    }
}
