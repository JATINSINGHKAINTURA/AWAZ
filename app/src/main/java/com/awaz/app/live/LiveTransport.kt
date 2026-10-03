package com.awaz.app.live

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "LiveTransport"

interface LiveTransport {
    val events: Flow<LiveEvent>
    val isConnected: Boolean
    suspend fun connect(url: String, headers: Map<String, String> = emptyMap())
    suspend fun send(text: String): Boolean
    suspend fun send(bytes: ByteArray): Boolean
    suspend fun close(code: Int = 1000, reason: String = "Normal Closure")
}

/**
 * Lossless WebSocket transport based on OkHttp with ping keep-alive and an UNLIMITED channel.
 * Never drops events via DROP_OLDEST or DROP_LATEST.
 * Strictly avoids logging URLs containing credentials, tokens, or audio bytes.
 */
class OkHttpLiveTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) : LiveTransport {

    // Lossless Channel with UNLIMITED capacity: guarantees zero dropped events in FIFO order
    private val eventChannel = Channel<LiveEvent>(Channel.UNLIMITED)
    override val events: Flow<LiveEvent> = eventChannel.receiveAsFlow()

    private var currentWebSocket: WebSocket? = null
    private val _isConnected = AtomicBoolean(false)
    override val isConnected: Boolean
        get() = _isConnected.get()

    override suspend fun connect(url: String, headers: Map<String, String>) {
        close(1000, "Reconnecting")

        val requestBuilder = Request.Builder().url(url)
        for ((key, value) in headers) {
            requestBuilder.addHeader(key, value)
        }
        val request = requestBuilder.build()

        currentWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _isConnected.set(true)
                Log.i(TAG, "Live WebSocket opened successfully")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val parsedEvents = LiveMessages.parseServerMessage(text)
                for (event in parsedEvents) {
                    eventChannel.trySend(event)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val byteArray = bytes.toByteArray()
                if (byteArray.isNotEmpty() && byteArray[0].toInt().toChar() == '{') {
                    val parsedEvents = LiveMessages.parseServerMessage(bytes.utf8())
                    for (event in parsedEvents) {
                        eventChannel.trySend(event)
                    }
                } else {
                    eventChannel.trySend(LiveEvent.AudioOut(byteArray))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Live WebSocket closing: code=$code")
                _isConnected.set(false)
                eventChannel.trySend(LiveEvent.GoAway)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Live WebSocket closed: code=$code")
                _isConnected.set(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Sanitize any credential or query parameter from log message
                val safeMessage = sanitizeMessage(t.message)
                Log.e(TAG, "Live WebSocket error: ${t.javaClass.simpleName}: $safeMessage")
                _isConnected.set(false)
                eventChannel.trySend(LiveEvent.GoAway)
            }
        })
    }

    override suspend fun send(text: String): Boolean {
        val ws = currentWebSocket ?: return false
        return ws.send(text)
    }

    override suspend fun send(bytes: ByteArray): Boolean {
        val ws = currentWebSocket ?: return false
        return ws.send(ByteString.of(bytes, 0, bytes.size))
    }

    override suspend fun close(code: Int, reason: String) {
        _isConnected.set(false)
        try {
            currentWebSocket?.close(code, reason)
        } catch (_: Exception) {}
        currentWebSocket = null
    }

    companion object {
        fun sanitizeMessage(message: String?): String {
            if (message == null) return "Unknown"
            return message
                .replace(Regex("key=[^&\\s]+"), "key=REDACTED")
                .replace(Regex("access_token=[^&\\s]+"), "access_token=REDACTED")
        }
    }
}
