package com.awaz.app.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface abstracting audio capture for speech streaming.
 */
interface AudioSource {
    fun startCapture(): Flow<ByteArray>
    fun stop()
}

/**
 * Interface abstracting audio output and playback for speech synthesis.
 */
interface AudioSink {
    val isSpeaking: StateFlow<Boolean>
    var muteMicWhileModelSpeaks: Boolean
    fun start()
    fun write(pcmChunk: ByteArray)
    fun flush()
    fun stop()
}
