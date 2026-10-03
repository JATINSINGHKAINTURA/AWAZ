package com.awaz.app.live

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoHardcodedApiKeysTest {

    @Test
    fun scanSourceFiles_noHardcodedApiKeyFound() {
        val apiKeyPattern = Regex("AIza[0-9A-Za-z_-]{35}")

        // Check both possible working directory locations (module-level or root-level)
        val candidateDirs = listOf(
            File("src"),
            File("app/src"),
            File("../app/src")
        )

        val srcDir = candidateDirs.firstOrNull { it.exists() && it.isDirectory }
        if (srcDir == null) {
            // If neither directory path matches directly, search from current directory
            val fallbackDir = File(".").walkTopDown()
                .filter { it.isDirectory && (it.name == "src" || it.name == "app") }
                .firstOrNull()
            assertTrue("Source directory must exist for scanning", fallbackDir != null)
        }

        val targetDir = srcDir ?: File(".")
        val violations = mutableListOf<String>()

        targetDir.walkTopDown()
            .filter { it.isFile }
            .filter { it.extension in listOf("kt", "java", "xml", "json", "txt", "gradle", "kts", "properties") }
            .forEach { file ->
                // Exclude this test file itself if it mentions the pattern as a test literal
                if (file.name == "NoHardcodedApiKeysTest.kt") return@forEach

                val text = file.readText()
                if (apiKeyPattern.containsMatchIn(text)) {
                    violations.add(file.path)
                }
            }

        assertTrue(
            "Found hardcoded Gemini API key matching pattern AIza... in files: $violations",
            violations.isEmpty()
        )
    }
}
