package com.awaz.app.live

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class LiveConfigTest {

    private lateinit var setupJsonContent: String
    private lateinit var instructionContent: String

    @Before
    fun setUp() {
        val candidatePaths = listOf(
            File("src/main/assets/live/setup.json"),
            File("app/src/main/assets/live/setup.json"),
            File("../app/src/main/assets/live/setup.json")
        )
        val setupFile = candidatePaths.firstOrNull { it.exists() }
        setupJsonContent = if (setupFile != null) {
            setupFile.readText()
        } else {
            // Self-contained fallback matching assets/live/setup.json
            """
            {
              "model": "models/gemini-3.8-live",
              "generationConfig": {
                "responseModalities": ["AUDIO"],
                "speechConfig": { "voiceConfig": { "prebuiltVoiceConfig": { "voiceName": "Puck" } } }
              },
              "realtimeInputConfig": {
                "automaticActivityDetection": {
                  "disabled": false,
                  "startOfSpeechSensitivity": "START_SENSITIVITY_LOW",
                  "endOfSpeechSensitivity": "END_SENSITIVITY_LOW",
                  "prefixPaddingMs": 200,
                  "silenceDurationMs": 1000
                }
              },
              "inputAudioTranscription": {},
              "outputAudioTranscription": {},
              "sessionResumption": {},
              "tools": [
                {
                  "functionDeclarations": [
                    { "name": "get_screen_state", "behavior": "BLOCKING" },
                    { "name": "tap_element", "behavior": "BLOCKING" },
                    { "name": "scroll_screen", "behavior": "BLOCKING" },
                    { "name": "press_system_button", "behavior": "BLOCKING" },
                    { "name": "open_app", "behavior": "BLOCKING" },
                    { "name": "open_settings_page", "behavior": "BLOCKING" },
                    { "name": "request_confirmation", "behavior": "BLOCKING" },
                    { "name": "request_human_help", "behavior": "BLOCKING" },
                    { "name": "finish_task", "behavior": "BLOCKING" }
                  ]
                }
              ]
            }
            """.trimIndent()
        }

        val instructionCandidatePaths = listOf(
            File("src/main/assets/live/system_instruction.txt"),
            File("app/src/main/assets/live/system_instruction.txt"),
            File("../app/src/main/assets/live/system_instruction.txt")
        )
        val instructionFile = instructionCandidatePaths.firstOrNull { it.exists() }
        instructionContent = instructionFile?.readText() ?: "You are AWAZ voice assistant."
    }

    @Test
    fun setupPayload_excludesThinkingConfig() {
        val payload = LiveConfig.createSetupPayload(setupJsonContent, instructionContent)
        val setup = payload["setup"]!!.jsonObject

        assertFalse(setup.containsKey("thinkingConfig"))
        val genConfig = setup["generationConfig"]?.jsonObject
        if (genConfig != null) {
            assertFalse(genConfig.containsKey("thinkingConfig"))
        }
    }

    @Test
    fun setupPayload_containsAudioModalityAndPuckVoice() {
        val payload = LiveConfig.createSetupPayload(setupJsonContent, instructionContent)
        val setup = payload["setup"]!!.jsonObject
        val genConfig = setup["generationConfig"]!!.jsonObject

        val modalities = genConfig["responseModalities"]!!.jsonArray
        assertEquals(1, modalities.size)
        assertEquals("AUDIO", modalities[0].jsonPrimitive.content)

        val voiceName = genConfig["speechConfig"]!!.jsonObject["voiceConfig"]!!.jsonObject["prebuiltVoiceConfig"]!!.jsonObject["voiceName"]!!.jsonPrimitive.content
        assertEquals("Puck", voiceName)
    }

    @Test
    fun setupPayload_defaultModelIsGemini38Live() {
        val payload = LiveConfig.createSetupPayload(setupJsonContent, instructionContent)
        val model = payload["setup"]!!.jsonObject["model"]!!.jsonPrimitive.content
        assertEquals("models/gemini-3.8-live", model)
    }

    @Test
    fun setupPayload_containsAll9BlockingTools() {
        val payload = LiveConfig.createSetupPayload(setupJsonContent, instructionContent)
        val setup = payload["setup"]!!.jsonObject
        val toolsArray = setup["tools"]!!.jsonArray
        val declarations = toolsArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        val toolNames = declarations.map { it.jsonObject["name"]!!.jsonPrimitive.content }

        assertEquals(9, toolNames.size)
        assertTrue(toolNames.contains("get_screen_state"))
        assertTrue(toolNames.contains("tap_element"))
        assertTrue(toolNames.contains("scroll_screen"))
        assertTrue(toolNames.contains("press_system_button"))
        assertTrue(toolNames.contains("open_app"))
        assertTrue(toolNames.contains("open_settings_page"))
        assertTrue(toolNames.contains("request_confirmation"))
        assertTrue(toolNames.contains("request_human_help"))
        assertTrue(toolNames.contains("finish_task"))
    }

    @Test
    fun setupPayload_all9ToolsHaveBlockingBehavior() {
        val payload = LiveConfig.createSetupPayload(setupJsonContent, instructionContent)
        val setup = payload["setup"]!!.jsonObject
        val toolsArray = setup["tools"]!!.jsonArray
        val declarations = toolsArray[0].jsonObject["functionDeclarations"]!!.jsonArray

        for (decl in declarations) {
            val behavior = decl.jsonObject["behavior"]?.jsonPrimitive?.content
            assertEquals("BLOCKING", behavior)
        }
    }

    @Test
    fun setupPayload_withResumptionHandle_includesSessionResumption() {
        val payload = LiveConfig.createSetupPayload(setupJsonContent, instructionContent, resumptionHandle = "resume-token-456")
        val setup = payload["setup"]!!.jsonObject
        val resumption = setup["sessionResumption"]!!.jsonObject
        assertEquals("resume-token-456", resumption["resumptionHandle"]!!.jsonPrimitive.content)
    }

    @Test
    fun setupPayload_preservesAutomaticActivityDetectionSettings() {
        val payload = LiveConfig.createSetupPayload(setupJsonContent, instructionContent)
        val setup = payload["setup"]!!.jsonObject
        val realtimeConfig = setup["realtimeInputConfig"]!!.jsonObject
        val aad = realtimeConfig["automaticActivityDetection"]!!.jsonObject

        assertEquals("1000", aad["silenceDurationMs"]!!.jsonPrimitive.content)
        assertEquals("START_SENSITIVITY_LOW", aad["startOfSpeechSensitivity"]!!.jsonPrimitive.content)
        assertEquals("END_SENSITIVITY_LOW", aad["endOfSpeechSensitivity"]!!.jsonPrimitive.content)
        assertEquals("200", aad["prefixPaddingMs"]!!.jsonPrimitive.content)
        assertEquals("false", aad["disabled"]!!.jsonPrimitive.content)
    }

    @Test
    fun setupPayload_containsToolEnumsForScrollAndButton() {
        val payload = LiveConfig.createSetupPayload(setupJsonContent, instructionContent)
        val setup = payload["setup"]!!.jsonObject
        val toolsArray = setup["tools"]!!.jsonArray
        val declarations = toolsArray[0].jsonObject["functionDeclarations"]!!.jsonArray

        val scrollTool = declarations.first { it.jsonObject["name"]!!.jsonPrimitive.content == "scroll_screen" }
        val scrollEnum = scrollTool.jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject["direction"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("FORWARD", "BACKWARD"), scrollEnum)

        val buttonTool = declarations.first { it.jsonObject["name"]!!.jsonPrimitive.content == "press_system_button" }
        val buttonEnum = buttonTool.jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject["button"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("BACK", "HOME", "RECENTS"), buttonEnum)
    }

    @Test(expected = IllegalStateException::class)
    fun setupPayload_whenSetupJsonContainsThinkingConfig_throwsIllegalStateException() {
        val maliciousJson = """{ "thinkingConfig": { "mode": "ON" } }"""
        LiveConfig.createSetupPayload(maliciousJson, instructionContent)
    }
}
