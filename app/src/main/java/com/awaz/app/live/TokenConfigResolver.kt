package com.awaz.app.live

/**
 * Pure Kotlin resolver logic matching Gradle build configuration:
 * GEMINI_DEV_TOKEN is exposed only in debug builds when the gradle property is non-empty.
 * In release builds or when the property is absent, an empty string is strictly enforced.
 */
object TokenConfigResolver {
    fun resolveToken(isDebug: Boolean, gradlePropertyToken: String?): String {
        if (!isDebug) return ""
        return gradlePropertyToken?.trim() ?: ""
    }
}
