package com.awaz.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SpeakerPlayer"

/**
 * AudioTrack playback engine configured for Gemini Live spoken responses.
 * Implements AudioSink interface for clean JVM testability.
 * Sample rate: 24000 Hz, mono PCM 16-bit, USAGE_VOICE_COMMUNICATION.
 * Configures MODE_IN_COMMUNICATION and loudspeaker routing with API level guards.
 * Restores AudioManager state on EVERY stop path, including errors.
 */
class SpeakerPlayer(
    private val context: Context,
    private val sampleRate: Int = 24000
) : AudioSink {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioTrack: AudioTrack? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private val isRunning = AtomicBoolean(false)
    private var playbackThread: Thread? = null

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    /**
     * Fallback toggle: if true, signals microphone to pause while model is speaking
     * to eliminate acoustic bleed on devices without hardware AEC.
     */
    override var muteMicWhileModelSpeaks: Boolean = false

    @Synchronized
    override fun start() {
        if (isRunning.getAndSet(true)) return

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                val speakerDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speakerDevice != null) {
                    audioManager.setCommunicationDevice(speakerDevice)
                }
            }

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBufSize, sampleRate * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            track.play()
            audioTrack = track

            playbackThread = Thread({
                while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                    try {
                        val chunk = audioQueue.take()
                        if (chunk.isNotEmpty()) {
                            _isSpeaking.value = true
                            var offset = 0
                            while (offset < chunk.size && isRunning.get()) {
                                val trackRef = audioTrack ?: break
                                val written = trackRef.write(chunk, offset, chunk.size - offset)
                                if (written <= 0) break
                                offset += written
                            }
                        }
                        if (audioQueue.isEmpty()) {
                            _isSpeaking.value = false
                        }
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                _isSpeaking.value = false
            }, "AwazSpeakerPlaybackThread").apply { start() }

            Log.i(TAG, "SpeakerPlayer initialized at ${sampleRate}Hz")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
            isRunning.set(false)
            restoreAudioRouting()
        }
    }

    override fun write(pcmChunk: ByteArray) {
        if (!isRunning.get() || pcmChunk.isEmpty()) return
        audioQueue.offer(pcmChunk)
    }

    @Synchronized
    override fun flush() {
        audioQueue.clear()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            Log.w(TAG, "Error flushing AudioTrack: ${e.message}")
        }
        _isSpeaking.value = false
        Log.i(TAG, "SpeakerPlayer flushed")
    }

    @Synchronized
    override fun stop() {
        if (!isRunning.getAndSet(false)) {
            restoreAudioRouting()
            return
        }

        audioQueue.clear()
        playbackThread?.interrupt()
        playbackThread = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null

        restoreAudioRouting()
        _isSpeaking.value = false
        Log.i(TAG, "SpeakerPlayer stopped")
    }

    private fun restoreAudioRouting() {
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error restoring AudioManager routing: ${e.message}")
        }
    }
}
