package com.awaz.app.policy

import java.text.Normalizer
import java.util.Locale

/**
 * Pure Kotlin PolicyEngine for AWAZ.
 * Evaluates untrusted screen content and requested agent actions.
 * Contains ZERO Android framework imports.
 *
 * Package Heuristic:
 * Evaluated via [evaluatePackage]. The heuristic performs a case-insensitive
 * substring match inside each individual dot-separated segment of the package name
 * against the denied package fragments ("bank", "upi", "wallet", "pay").
 */

sealed class Decision {
    object Allow : Decision() {
        override fun toString(): String = "Allow"
    }

    data class RequireConfirmation(val code: String) : Decision()
    data class Block(val code: String) : Decision()
    data class HandOffToHuman(val code: String) : Decision()
}

sealed class AgentActionType {
    object Click : AgentActionType()
    object LongClick : AgentActionType()
    object ScrollForward : AgentActionType()
    object ScrollBackward : AgentActionType()
    object GlobalBack : AgentActionType()
    object GlobalHome : AgentActionType()
    object GlobalRecents : AgentActionType()
    data class SendMessage(val recipient: String, val text: String) : AgentActionType()
    data class MakeCall(val target: String) : AgentActionType()
    data class Custom(val name: String) : AgentActionType()
}

data class AgentAction(
    val type: AgentActionType,
    val targetElementId: Int? = null,
    val targetLabel: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

data class ScreenElement(
    val id: Int,
    val label: String,
    val role: String = "View",
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val isPassword: Boolean = false,
    val boundsCenterX: Int = 0,
    val boundsCenterY: Int = 0
)

data class PolicyContext(
    val foregroundPackage: String,
    val elements: List<ScreenElement> = emptyList(),
    val actionsTakenCount: Int = 0,
    val lastAction: AgentAction? = null,
    val consecutiveIdenticalActionCount: Int = 0,
    val lastActionTimestampMs: Long = 0L
)

data class PolicyRules(
    val deniedPackages: Set<String>,
    val deniedPackageFragments: List<String> = listOf("bank", "upi", "wallet", "pay"),
    val sensitiveTerms: List<String>,
    val confirmationTerms: List<String>
)

class PolicyEngine(
    private val rules: PolicyRules,
    private val logger: ((String) -> Unit)? = null
) {
    // Regex splitting on characters that are not letters, combining marks or digits.
    // Keeps Devanagari vowel signs/matras (\p{M}) inside words.
    private val tokenSplitRegex = Regex("[^\\p{L}\\p{M}\\p{Nd}]+")

    // Pre-normalized sensitive token phrases
    private val normalizedSensitivePhrases: List<List<String>> = rules.sensitiveTerms.map { term ->
        tokenize(term)
    }.filter { it.isNotEmpty() }

    // Pre-normalized confirmation token phrases
    private val normalizedConfirmationPhrases: List<List<String>> = rules.confirmationTerms.map { term ->
        tokenize(term)
    }.filter { it.isNotEmpty() }

    /**
     * Checks foreground package alone before any window nodes or labels are read.
     * Returns a Decision if blocked (HandOffToHuman), or null if the package is allowed.
     *
     * Heuristic KDoc:
     * The package heuristic is a case-insensitive substring match inside each
     * dot-separated package segment against the denied package fragments ("bank", "upi", "wallet", "pay").
     */
    fun evaluatePackage(packageName: String): Decision? {
        val lowerPkg = packageName.lowercase(Locale.ROOT)

        // 1. Exact denylist check
        if (rules.deniedPackages.any { it.equals(packageName, ignoreCase = true) }) {
            val decision = Decision.HandOffToHuman("BLOCKED_FINANCIAL_PACKAGE")
            logDecision("Package $packageName matched explicit denylist -> $decision")
            return decision
        }

        // 2. Segment-based heuristic check
        val segments = lowerPkg.split('.')
        for (segment in segments) {
            for (fragment in rules.deniedPackageFragments) {
                if (segment.contains(fragment.lowercase(Locale.ROOT))) {
                    val decision = Decision.HandOffToHuman("BLOCKED_FINANCIAL_PACKAGE")
                    logDecision("Package $packageName segment '$segment' matched fragment '$fragment' -> $decision")
                    return decision
                }
            }
        }

        return null
    }

    fun evaluate(ctx: PolicyContext, action: AgentAction): Decision {
        // 1. Check Package first (zero node reading required)
        val packageDecision = evaluatePackage(ctx.foregroundPackage)
        if (packageDecision != null) {
            return packageDecision
        }

        // 2. Action Limit: Maximum 12 actions per task
        if (ctx.actionsTakenCount >= 12) {
            val decision = Decision.Block("STEP_LIMIT")
            logDecision("Action limit exceeded (${ctx.actionsTakenCount}) -> $decision")
            return decision
        }

        // 3. Minimum 300 ms between gestures
        if (ctx.lastActionTimestampMs > 0L && action.timestampMs - ctx.lastActionTimestampMs < 300L) {
            val decision = Decision.Block("RATE_LIMIT_300MS")
            logDecision("Rate limit triggered (${action.timestampMs - ctx.lastActionTimestampMs}ms) -> $decision")
            return decision
        }

        // 4. Maximum 2 identical consecutive actions
        if (ctx.consecutiveIdenticalActionCount >= 2 && isSameAction(ctx.lastAction, action)) {
            val decision = Decision.Block("CONSECUTIVE_ACTION_LIMIT")
            logDecision("Consecutive identical action limit reached -> $decision")
            return decision
        }

        // 5. Password field detection on screen
        val hasPasswordField = ctx.elements.any { it.isPassword }

        // 6. Token-based sensitive term matching on screen element labels
        val matchedSensitiveTerm = findSensitiveTermOnScreen(ctx.elements)
        if (matchedSensitiveTerm != null) {
            val decision = Decision.HandOffToHuman("SENSITIVE_TERM_$matchedSensitiveTerm")
            logDecision("Screen matched sensitive term '$matchedSensitiveTerm' -> $decision")
            return decision
        }

        if (hasPasswordField) {
            val decision = Decision.Block("PASSWORD_FIELD_DETECTED")
            logDecision("Password field detected on screen -> $decision")
            return decision
        }

        // 7. RequireConfirmation for high-impact actions
        val actionTypeNeedsConfirmation = when (action.type) {
            is AgentActionType.SendMessage -> true
            is AgentActionType.MakeCall -> true
            else -> false
        }

        val targetLabelNeedsConfirmation = action.targetLabel?.let { label ->
            containsAnyPhrase(label, normalizedConfirmationPhrases)
        } ?: false

        if (actionTypeNeedsConfirmation || targetLabelNeedsConfirmation) {
            val decision = Decision.RequireConfirmation("USER_CONFIRMATION_REQUIRED")
            logDecision("Confirmation required for action (${action.targetLabel}) -> $decision")
            return decision
        }

        // 8. Normal, safe screen action
        logDecision("Action allowed on package ${ctx.foregroundPackage}")
        return Decision.Allow
    }

    private fun findSensitiveTermOnScreen(elements: List<ScreenElement>): String? {
        for (element in elements) {
            for (phraseTokens in normalizedSensitivePhrases) {
                if (matchesPhraseTokens(element.label, phraseTokens)) {
                    return phraseTokens.joinToString("_")
                }
            }
        }
        return null
    }

    private fun matchesPhraseTokens(text: String, phraseTokens: List<String>): Boolean {
        if (phraseTokens.isEmpty()) return false
        val textTokens = tokenize(text)
        if (textTokens.size < phraseTokens.size) return false

        for (i in 0..(textTokens.size - phraseTokens.size)) {
            var match = true
            for (j in phraseTokens.indices) {
                if (textTokens[i + j] != phraseTokens[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    private fun containsAnyPhrase(text: String, phrases: List<List<String>>): Boolean {
        return phrases.any { phrase -> matchesPhraseTokens(text, phrase) }
    }

    private fun tokenize(input: String): List<String> {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return normalized.split(tokenSplitRegex).filter { it.isNotBlank() }
    }

    private fun isSameAction(a: AgentAction?, b: AgentAction): Boolean {
        if (a == null) return false
        if (a.type::class != b.type::class) return false
        if (a.targetElementId != b.targetElementId) return false
        return a.targetLabel == b.targetLabel
    }

    private fun logDecision(message: String) {
        logger?.invoke(message)
    }
}
