package com.awaz.app.live

import android.content.res.AssetManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.InputStream

/**
 * Loads Gemini Live session configuration from assets (assets/live/setup.json and assets/live/system_instruction.txt).
 * Does NOT hardcode the tool list, voice, or system instruction in Kotlin.
 * Strictly verifies absence of thinkingConfig.
 */
object LiveConfig {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Loads setup configuration directly from Android AssetManager.
     */
    fun loadFromAssets(
        assetManager: AssetManager,
        setupAssetPath: String = "live/setup.json",
        instructionAssetPath: String = "live/system_instruction.txt",
        resumptionHandle: String? = null
    ): JsonObject {
        val setupJson = assetManager.open(setupAssetPath).use { it.bufferedReader().readText() }
        val instructionText = assetManager.open(instructionAssetPath).use { it.bufferedReader().readText() }
        return createSetupPayload(setupJson, instructionText, resumptionHandle)
    }

    /**
     * Pure Kotlin parser and payload builder: merges runtime system instruction and resumption handle into setup.json.
     * Accessible on JVM for unit tests without Android AssetManager.
     */
    fun createSetupPayload(
        setupJsonContent: String,
        systemInstructionContent: String,
        resumptionHandle: String? = null
    ): JsonObject {
        val root = json.parseToJsonElement(setupJsonContent).jsonObject

        // Verify NO thinkingConfig in loaded asset
        if (root.containsKey("thinkingConfig") ||
            (root["generationConfig"] as? JsonObject)?.containsKey("thinkingConfig") == true
        ) {
            throw IllegalStateException("setup.json must NOT contain thinkingConfig")
        }

        val setupObj = buildJsonObject {
            // Copy all top-level keys from setup.json
            for ((key, value) in root) {
                if (key == "thinkingConfig") continue

                if (key == "sessionResumption" && !resumptionHandle.isNullOrBlank()) {
                    put("sessionResumption", buildJsonObject {
                        put("resumptionHandle", JsonPrimitive(resumptionHandle))
                    })
                } else {
                    put(key, value)
                }
            }

            // Inject systemInstruction loaded from asset file
            if (systemInstructionContent.isNotBlank()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("text", JsonPrimitive(systemInstructionContent.trim()))
                        })
                    })
                })
            }

            // Handle resumptionHandle if sessionResumption key was not present in setup.json
            if (!root.containsKey("sessionResumption") && !resumptionHandle.isNullOrBlank()) {
                put("sessionResumption", buildJsonObject {
                    put("resumptionHandle", JsonPrimitive(resumptionHandle))
                })
            }
        }

        // Wrap under "setup" property per Gemini Live API WebSocket schema
        return buildJsonObject {
            put("setup", setupObj)
        }
    }
}
