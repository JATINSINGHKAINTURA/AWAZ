package com.awaz.app.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

data class LiveToolCall(
    val id: String,
    val name: String,
    val args: JsonObject
)

sealed class LiveEvent {
    object SetupComplete : LiveEvent() {
        override fun toString(): String = "SetupComplete"
    }

    data class AudioOut(val data: ByteArray) : LiveEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AudioOut) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()

        override fun toString(): String = "AudioOut(${data.size} bytes)"
    }

    data class InputTranscript(val text: String) : LiveEvent()
    data class OutputTranscript(val text: String) : LiveEvent()

    object TurnComplete : LiveEvent() {
        override fun toString(): String = "TurnComplete"
    }

    object Interrupted : LiveEvent() {
        override fun toString(): String = "Interrupted"
    }

    data class ToolCalls(val calls: List<LiveToolCall>) : LiveEvent()
    data class ToolCallCancelled(val callIds: List<String>) : LiveEvent()

    object GoAway : LiveEvent() {
        override fun toString(): String = "GoAway"
    }

    data class ResumptionUpdate(val resumptionHandle: String) : LiveEvent()
    data class Unknown(val raw: String) : LiveEvent()
}

/**
 * Pure Kotlin message serialization and parsing for Gemini Live API WebSocket frames.
 * Uses kotlinx-serialization-json exclusively with zero org.json or android.* imports.
 */
object LiveMessages {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Builds client setup JSON message from asset-loaded template and instruction.
     */
    fun buildSetupMessage(
        setupJsonContent: String = """{"model":"models/gemini-3.8-live"}""",
        systemInstructionContent: String = "",
        resumptionHandle: String? = null
    ): String {
        val payload = LiveConfig.createSetupPayload(
            setupJsonContent = setupJsonContent,
            systemInstructionContent = systemInstructionContent,
            resumptionHandle = resumptionHandle
        )
        return payload.toString()
    }

    /**
     * Builds realtime PCM audio chunk message (16kHz 16-bit mono PCM base64 encoded).
     */
    fun buildAudioChunk(pcmData: ByteArray): String {
        val base64Data = Base64.getEncoder().encodeToString(pcmData)
        val payload = buildJsonObject {
            put("realtimeInput", buildJsonObject {
                put("mediaChunks", buildJsonArray {
                    add(buildJsonObject {
                        put("mimeType", JsonPrimitive("audio/pcm;rate=16000"))
                        put("data", JsonPrimitive(base64Data))
                    })
                })
            })
        }
        return payload.toString()
    }

    /**
     * Builds audio stream end / client turn completion message.
     */
    fun buildAudioStreamEnd(): String {
        val payload = buildJsonObject {
            put("clientContent", buildJsonObject {
                put("turnComplete", JsonPrimitive(true))
            })
        }
        return payload.toString()
    }

    /**
     * Builds tool execution response message.
     */
    fun buildToolResponse(
        callId: String,
        output: JsonObject
    ): String {
        val payload = buildJsonObject {
            put("toolResponse", buildJsonObject {
                put("functionResponses", buildJsonArray {
                    add(buildJsonObject {
                        put("id", JsonPrimitive(callId))
                        put("response", buildJsonObject {
                            put("output", output)
                        })
                    })
                })
            })
        }
        return payload.toString()
    }

    /**
     * Parses an incoming server JSON message string into a list of [LiveEvent]s.
     * Guaranteed never to throw an unhandled exception.
     */
    fun parseServerMessage(messageText: String): List<LiveEvent> {
        val trimmed = messageText.trim()
        if (trimmed.isEmpty()) return emptyList()

        return try {
            val root = json.parseToJsonElement(trimmed) as? JsonObject
                ?: return listOf(LiveEvent.Unknown(trimmed))

            val events = mutableListOf<LiveEvent>()

            // 1. SetupComplete
            if (root.containsKey("setupComplete")) {
                events.add(LiveEvent.SetupComplete)
            }

            // 2. ServerContent
            val serverContent = root["serverContent"] as? JsonObject
            if (serverContent != null) {
                // Interrupted
                if (serverContent["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
                    events.add(LiveEvent.Interrupted)
                }

                // Model Turn (Audio output & output text transcripts)
                val modelTurn = serverContent["modelTurn"] as? JsonObject
                val parts = modelTurn?.get("parts") as? JsonArray
                if (parts != null) {
                    for (partElement in parts) {
                        val part = partElement as? JsonObject ?: continue

                        // Audio inlineData
                        val inlineData = part["inlineData"] as? JsonObject
                        if (inlineData != null) {
                            val dataStr = inlineData["data"]?.jsonPrimitive?.content
                            if (!dataStr.isNullOrBlank()) {
                                try {
                                    val bytes = Base64.getDecoder().decode(dataStr)
                                    events.add(LiveEvent.AudioOut(bytes))
                                } catch (_: IllegalArgumentException) {
                                    // Invalid base64, skip
                                }
                            }
                        }

                        // Model text transcript
                        val text = part["text"]?.jsonPrimitive?.content
                        if (!text.isNullOrBlank()) {
                            events.add(LiveEvent.OutputTranscript(text))
                        }
                    }
                }

                // User turn transcript (if returned by server)
                val userTurn = serverContent["userTurn"] as? JsonObject
                val userParts = userTurn?.get("parts") as? JsonArray
                if (userParts != null) {
                    for (partElement in userParts) {
                        val part = partElement as? JsonObject ?: continue
                        val text = part["text"]?.jsonPrimitive?.content
                        if (!text.isNullOrBlank()) {
                            events.add(LiveEvent.InputTranscript(text))
                        }
                    }
                }

                // Turn complete
                if (serverContent["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                    events.add(LiveEvent.TurnComplete)
                }
            }

            // 3. User input transcript alternative field
            val inputTranscript = root["inputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: root["inputTranscript"]?.jsonPrimitive?.content
            if (!inputTranscript.isNullOrBlank()) {
                events.add(LiveEvent.InputTranscript(inputTranscript))
            }

            // 4. Tool Calls
            val toolCall = root["toolCall"] as? JsonObject
            val functionCalls = toolCall?.get("functionCalls") as? JsonArray
            if (functionCalls != null && functionCalls.isNotEmpty()) {
                val calls = mutableListOf<LiveToolCall>()
                for (fcElement in functionCalls) {
                    val fc = fcElement as? JsonObject ?: continue
                    val id = fc["id"]?.jsonPrimitive?.content ?: ""
                    val name = fc["name"]?.jsonPrimitive?.content ?: ""
                    val args = (fc["args"] as? JsonObject) ?: JsonObject(emptyMap())
                    if (name.isNotBlank()) {
                        calls.add(LiveToolCall(id = id, name = name, args = args))
                    }
                }
                if (calls.isNotEmpty()) {
                    events.add(LiveEvent.ToolCalls(calls))
                }
            }

            // 5. Tool Call Cancellation
            val toolCallCancellation = root["toolCallCancellation"] as? JsonObject
            val cancelledIdsArray = toolCallCancellation?.get("ids") as? JsonArray
            if (cancelledIdsArray != null) {
                val ids = cancelledIdsArray.mapNotNull { it.jsonPrimitive.content }
                events.add(LiveEvent.ToolCallCancelled(ids))
            }

            // 6. GoAway
            if (root.containsKey("goAway")) {
                events.add(LiveEvent.GoAway)
            }

            // 7. Resumption Update
            val resumption = root["sessionResumptionUpdate"] as? JsonObject
                ?: root["resumptionUpdate"] as? JsonObject
                ?: root["sessionResumption"] as? JsonObject
            val handle = resumption?.get("resumptionHandle")?.jsonPrimitive?.content
            if (!handle.isNullOrBlank()) {
                events.add(LiveEvent.ResumptionUpdate(handle))
            }

            if (events.isEmpty()) {
                listOf(LiveEvent.Unknown(trimmed))
            } else {
                events
            }
        } catch (_: Exception) {
            listOf(LiveEvent.Unknown(trimmed))
        }
    }
}
