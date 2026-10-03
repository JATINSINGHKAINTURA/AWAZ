package com.awaz.app.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LiveMessagesTest {

    @Test
    fun buildSetupMessage_generatesValidJsonWithoutThinkingConfig() {
        val jsonStr = LiveMessages.buildSetupMessage()
        val root = Json.parseToJsonElement(jsonStr).jsonObject

        assertTrue(root.containsKey("setup"))
        val setup = root["setup"]!!.jsonObject
        assertFalse(setup.containsKey("thinkingConfig"))
    }

    @Test
    fun buildAudioChunk_producesValidRealtimeMediaChunk() {
        val dummyPcm = byteArrayOf(1, 2, 3, 4, 5)
        val jsonStr = LiveMessages.buildAudioChunk(dummyPcm)
        val root = Json.parseToJsonElement(jsonStr).jsonObject

        val realtimeInput = root["realtimeInput"]!!.jsonObject
        val mediaChunks = realtimeInput["mediaChunks"]!!.jsonArray
        assertEquals(1, mediaChunks.size)

        val firstChunk = mediaChunks[0].jsonObject
        assertEquals("audio/pcm;rate=16000", firstChunk["mimeType"]!!.jsonPrimitive.content)

        val decodedBytes = Base64.getDecoder().decode(firstChunk["data"]!!.jsonPrimitive.content)
        assertArrayEquals(dummyPcm, decodedBytes)
    }

    @Test
    fun buildAudioStreamEnd_producesClientTurnComplete() {
        val jsonStr = LiveMessages.buildAudioStreamEnd()
        val root = Json.parseToJsonElement(jsonStr).jsonObject

        val clientContent = root["clientContent"]!!.jsonObject
        assertEquals("true", clientContent["turnComplete"]!!.jsonPrimitive.content)
    }

    @Test
    fun buildToolResponse_formatsFunctionResponseWithIdAndOutput() {
        val outputObj = buildJsonObject {
            put("success", JsonPrimitive(true))
            put("element_id", JsonPrimitive(12))
        }
        val jsonStr = LiveMessages.buildToolResponse("call-42", outputObj)
        val root = Json.parseToJsonElement(jsonStr).jsonObject

        val toolResponse = root["toolResponse"]!!.jsonObject
        val functionResponses = toolResponse["functionResponses"]!!.jsonArray
        assertEquals(1, functionResponses.size)

        val firstResp = functionResponses[0].jsonObject
        assertEquals("call-42", firstResp["id"]!!.jsonPrimitive.content)
        val respOutput = firstResp["response"]!!.jsonObject["output"]!!.jsonObject
        assertEquals("true", respOutput["success"]!!.jsonPrimitive.content)
        assertEquals("12", respOutput["element_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun parseServerMessage_withSetupComplete_returnsSetupCompleteEvent() {
        val serverJson = """{ "setupComplete": {} }"""
        val events = LiveMessages.parseServerMessage(serverJson)
        assertEquals(1, events.size)
        assertTrue(events[0] is LiveEvent.SetupComplete)
    }

    @Test
    fun parseServerMessage_withAudioOut_returnsAudioOutEvent() {
        val pcm = byteArrayOf(10, 20, 30)
        val b64 = Base64.getEncoder().encodeToString(pcm)
        val serverJson = """
            {
              "serverContent": {
                "modelTurn": {
                  "parts": [
                    { "inlineData": { "mimeType": "audio/pcm;rate=24000", "data": "$b64" } }
                  ]
                }
              }
            }
        """.trimIndent()

        val events = LiveMessages.parseServerMessage(serverJson)
        assertEquals(1, events.size)
        assertTrue(events[0] is LiveEvent.AudioOut)
        assertArrayEquals(pcm, (events[0] as LiveEvent.AudioOut).data)
    }

    @Test
    fun parseServerMessage_withOutputTranscript_returnsOutputTranscriptEvent() {
        val serverJson = """
            {
              "serverContent": {
                "modelTurn": {
                  "parts": [
                    { "text": "Haan, main madad kar sakta hoon." }
                  ]
                }
              }
            }
        """.trimIndent()

        val events = LiveMessages.parseServerMessage(serverJson)
        assertEquals(1, events.size)
        assertTrue(events[0] is LiveEvent.OutputTranscript)
        assertEquals("Haan, main madad kar sakta hoon.", (events[0] as LiveEvent.OutputTranscript).text)
    }

    @Test
    fun parseServerMessage_withInputTranscript_returnsInputTranscriptEvent() {
        val serverJson = """
            {
              "inputTranscription": {
                "text": "Settings kholo"
              }
            }
        """.trimIndent()

        val events = LiveMessages.parseServerMessage(serverJson)
        assertEquals(1, events.size)
        assertTrue(events[0] is LiveEvent.InputTranscript)
        assertEquals("Settings kholo", (events[0] as LiveEvent.InputTranscript).text)
    }

    @Test
    fun parseServerMessage_withTurnComplete_returnsTurnCompleteEvent() {
        val serverJson = """
            {
              "serverContent": {
                "turnComplete": true
              }
            }
        """.trimIndent()

        val events = LiveMessages.parseServerMessage(serverJson)
        assertEquals(1, events.size)
        assertTrue(events[0] is LiveEvent.TurnComplete)
    }

    @Test
    fun parseServerMessage_withInterrupted_returnsInterruptedEvent() {
        val serverJson = """
            {
              "serverContent": {
                "interrupted": true
              }
            }
        """.trimIndent()

        val events = LiveMessages.parseServerMessage(serverJson)
        assertEquals(1, events.size)
        assertTrue(events[0] is LiveEvent.Interrupted)
    }

    @Test
    fun parseServerMessage_withToolCalls_returnsToolCallsEvent() {
        val serverJson = """
            {
              "toolCall": {
                "functionCalls": [
                  {
                    "id": "fn-1",
                    "name": "tap_element",
                    "args": { "element_id": 5 }
                  }
                ]
              }
            }
        """.trimIndent()

        val events = LiveMessages.parseServerMessage(serverJson)
        assertEquals(1, events.size)
        assertTrue(events[0] is LiveEvent.ToolCalls)

        val toolCalls = (events[0] as LiveEvent.ToolCalls).calls
        assertEquals(1, toolCalls.size)
        assertEquals("fn-1", toolCalls[0].id)
        assertEquals("tap_element", toolCalls[0].name)
        assertEquals("5", toolCalls[0].args["element_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun parseServerMessage_withToolCallCancellation_returnsToolCallCancelledEvent() {
        val serverJson = """
            {
              "toolCallCancellation": {
                "ids": ["fn-1", "fn-2"]
              }
            }
        """.trimIndent()

        val events = LiveMessages.parseServerMessage(serverJson)
        assertEquals(1, events.size)
        assertTrue(events[0] is LiveEvent.ToolCallCancelled)
        assertEquals(listOf("fn-1", "fn-2"), (events[0] as LiveEvent.ToolCallCancelled).callIds)
    }

    @Test
    fun parseServerMessage_withGoAway_returnsGoAwayEvent() {
        val serverJson = """{ "goAway": {} }"""
        val events = LiveMessages.parseServerMessage(serverJson)
        assertEquals(1, events.size)
        assertTrue(events[0] is LiveEvent.GoAway)
    }

    @Test
    fun parseServerMessage_withResumptionUpdate_returnsResumptionUpdateEvent() {
        val serverJson = """
            {
              "sessionResumptionUpdate": {
                "resumptionHandle": "handle-token-999"
              }
            }
        """.trimIndent()

        val events = LiveMessages.parseServerMessage(serverJson)
        assertEquals(1, events.size)
        assertTrue(events[0] is LiveEvent.ResumptionUpdate)
        assertEquals("handle-token-999", (events[0] as LiveEvent.ResumptionUpdate).resumptionHandle)
    }

    @Test
    fun parseServerMessage_withUnknownJson_returnsUnknownEvent() {
        val serverJson = """{ "unexpectedField": "test" }"""
        val events = LiveMessages.parseServerMessage(serverJson)
        assertEquals(1, events.size)
        assertTrue(events[0] is LiveEvent.Unknown)
    }

    @Test
    fun parseServerMessage_withMalformedJson_returnsUnknownEvent() {
        val malformed = "NOT_VALID_JSON"
        val events = LiveMessages.parseServerMessage(malformed)
        assertEquals(1, events.size)
        assertTrue(events[0] is LiveEvent.Unknown)
    }
}
