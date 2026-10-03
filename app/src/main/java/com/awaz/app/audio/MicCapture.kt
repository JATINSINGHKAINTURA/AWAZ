package com.awaz.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "MicCapture"

/**
 * High-performance microphone capture on a dedicated priority thread.
 * Standard configuration: 16000 Hz, mono PCM 16-bit, VOICE_COMMUNICATION source.
 * Automatically attaches Acoustic Echo Canceler (AEC) and Noise Suppressor (NS) if hardware-supported.
 */
class MicCapture(
    private val sampleRate: Int = 16000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val chunkSizeMs: Int = 30 // 30ms chunks = 960 bytes at 16kHz 16-bit mono
) : AudioSource {

    private val isCapturing = AtomicBoolean(false)
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    /**
     * Starts continuous audio capture, returning a Cold Flow that emits raw PCM chunks.
     * Capturing stops automatically when the flow is cancelled or completed.
     */
    @SuppressLint("MissingPermission")
    override fun startCapture(): Flow<ByteArray> = callbackFlow {
        if (isCapturing.getAndSet(true)) {
            Log.w(TAG, "MicCapture is already active")
        }

        val bytesPerSample = 2 // 16-bit PCM
        val chunkSizeBytes = (sampleRate * (chunkSizeMs / 1000.0) * bytesPerSample).toInt()
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, chunkSizeBytes * 4)

        var audioRecord: AudioRecord? = null
        var captureThread: Thread? = null

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed state=${audioRecord.state}")
                close(IllegalStateException("AudioRecord initialization failed"))
                return@callbackFlow
            }

            // Enable AEC if supported
            val sessionId = audioRecord.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                        enabled = true
                        Log.i(TAG, "AcousticEchoCanceler enabled for session $sessionId")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enable AEC: ${e.message}")
                }
            }

            // Enable NS if supported
            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                        enabled = true
                        Log.i(TAG, "NoiseSuppressor enabled for session $sessionId")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enable NS: ${e.message}")
                }
            }

            audioRecord.startRecording()
            Log.i(TAG, "AudioRecord started at ${sampleRate}Hz, chunk size $chunkSizeBytes bytes")

            captureThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val buffer = ByteArray(chunkSizeBytes)

                while (isCapturing.get() && !Thread.currentThread().isInterrupted) {
                    val bytesRead = audioRecord.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        val chunk = buffer.copyOf(bytesRead)
                        val sendResult = trySend(chunk)
                        if (sendResult.isFailure) {
                            // Downstream buffer full, drop chunk to avoid latency build-up
                        }
                    } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION ||
                        bytesRead == AudioRecord.ERROR_BAD_VALUE
                    ) {
                        Log.e(TAG, "AudioRecord read error: $bytesRead")
                        break
                    }
                }
            }, "AwazMicCaptureThread").apply { start() }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting microphone capture", e)
            close(e)
        }

        awaitClose {
            Log.i(TAG, "Stopping MicCapture Flow")
            isCapturing.set(false)
            captureThread?.interrupt()

            try {
                echoCanceler?.release()
                echoCanceler = null
            } catch (_: Exception) {}

            try {
                noiseSuppressor?.release()
                noiseSuppressor = null
            } catch (_: Exception) {}

            try {
                if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord?.stop()
                }
                audioRecord?.release()
            } catch (_: Exception) {}
        }
    }

    override fun stop() {
        isCapturing.set(false)
    }
}
