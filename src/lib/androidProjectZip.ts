import JSZip from 'jszip';

export interface AndroidFileRecord {
  path: string;
  category: 'Kotlin' | 'Test' | 'Config' | 'Resource' | 'Asset';
  content: string;
}

export const ANDROID_FILES: AndroidFileRecord[] = [
  {
    path: 'app/src/main/java/com/awaz/app/policy/PolicyEngine.kt',
    category: 'Kotlin',
    content: `package com.awaz.app.policy

import java.text.Normalizer
import java.util.Locale

/**
 * Pure Kotlin policy engine for AWAZ.
 * Evaluates untrusted screen content and requested agent actions.
 * Contains ZERO Android framework imports.
 */

data class PolicyRules(
    val deniedPackages: Set<String>,
    val deniedPackageFragments: Set<String>,
    val sensitiveTerms: Set<String>,
    val confirmationTerms: Set<String>
)

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

class PolicyEngine(
    private val rules: PolicyRules,
    private val logger: ((String) -> Unit)? = null
) {
    private val normalizedSensitiveTerms: List<List<String>> = rules.sensitiveTerms.map { term ->
        tokenize(term)
    }.filter { it.isNotEmpty() }

    private val normalizedConfirmationTerms: List<List<String>> = rules.confirmationTerms.map { term ->
        tokenize(term)
    }.filter { it.isNotEmpty() }

    fun evaluate(ctx: PolicyContext, action: AgentAction): Decision {
        val decision = doEvaluate(ctx, action)
        logger?.invoke("Decision for package '\${ctx.foregroundPackage}', label '\${action.targetLabel}': \$decision")
        return decision
    }

    private fun doEvaluate(ctx: PolicyContext, action: AgentAction): Decision {
        // 1. Action Limit: Maximum 12 actions per task
        if (ctx.actionsTakenCount >= 12) {
            return Decision.Block("STEP_LIMIT")
        }

        // 2. Minimum 300 ms between gestures
        if (ctx.lastActionTimestampMs > 0L &&
            action.timestampMs - ctx.lastActionTimestampMs < 300L
        ) {
            return Decision.Block("RATE_LIMIT_300MS")
        }

        // 3. Maximum 2 identical consecutive actions
        if (ctx.consecutiveIdenticalActionCount >= 2 &&
            isSameAction(ctx.lastAction, action)
        ) {
            return Decision.Block("CONSECUTIVE_ACTION_LIMIT")
        }

        // 4. Package Denylist & Financial heuristics applied to individual dot-separated package segments
        val lowerPkg = ctx.foregroundPackage.lowercase(Locale.ROOT)
        val isExplicitlyDenylisted = rules.deniedPackages.any { it.equals(ctx.foregroundPackage, ignoreCase = true) }

        val pkgSegments = lowerPkg.split('.')
        val isHeuristicFinancialPkg = pkgSegments.any { seg ->
            rules.deniedPackageFragments.any { frag -> seg.contains(frag.lowercase(Locale.ROOT)) }
        }

        if (isExplicitlyDenylisted || isHeuristicFinancialPkg) {
            return Decision.HandOffToHuman("BLOCKED_FINANCIAL_PACKAGE")
        }

        // 5. Password field detection
        val hasPasswordField = ctx.elements.any { it.isPassword }

        // 6. Sensitive terms matching (TOKEN-based matching, NFKC normalized)
        val matchedSensitiveTerm = findSensitiveTermOnScreen(ctx.elements)
        if (matchedSensitiveTerm != null) {
            return Decision.HandOffToHuman("SENSITIVE_TERM_\$matchedSensitiveTerm")
        }

        if (hasPasswordField) {
            return Decision.Block("PASSWORD_FIELD_DETECTED")
        }

        // 7. RequireConfirmation for high-impact actions
        val actionTypeNeedsConfirmation = when (action.type) {
            is AgentActionType.SendMessage -> true
            is AgentActionType.MakeCall -> true
            else -> false
        }

        val targetLabelNeedsConfirmation = action.targetLabel?.let { label ->
            containsTokenSequence(tokenize(label), normalizedConfirmationTerms)
        } ?: false

        if (actionTypeNeedsConfirmation || targetLabelNeedsConfirmation) {
            return Decision.RequireConfirmation("USER_CONFIRMATION_REQUIRED")
        }

        // 8. Normal, safe screen action
        return Decision.Allow
    }

    private fun findSensitiveTermOnScreen(elements: List<ScreenElement>): String? {
        for (element in elements) {
            val labelTokens = tokenize(element.label)
            for (termTokens in normalizedSensitiveTerms) {
                if (matchesTokens(labelTokens, termTokens)) {
                    return termTokens.joinToString("_")
                }
            }
        }
        return null
    }

    private fun isSameAction(a: AgentAction?, b: AgentAction): Boolean {
        if (a == null) return false
        if (a.type::class != b.type::class) return false
        if (a.targetElementId != b.targetElementId) return false
        return a.targetLabel == b.targetLabel
    }

    companion object {
        fun tokenize(input: String): List<String> {
            val normalized = Normalizer.normalize(input, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            return normalized.split(Regex("[^\\\\p{L}\\\\p{M}\\\\p{Nd}]+")).filter { it.isNotBlank() }
        }

        fun matchesTokens(labelTokens: List<String>, targetTokens: List<String>): Boolean {
            if (targetTokens.isEmpty()) return false
            if (targetTokens.size > labelTokens.size) return false

            val targetSize = targetTokens.size
            for (i in 0..(labelTokens.size - targetSize)) {
                var matches = true
                for (j in 0 until targetSize) {
                    if (labelTokens[i + j] != targetTokens[j]) {
                        matches = false
                        break
                    }
                }
                if (matches) return true
            }
            return false
        }

        fun containsTokenSequence(labelTokens: List<String>, sequenceList: List<List<String>>): Boolean {
            return sequenceList.any { seq -> matchesTokens(labelTokens, seq) }
        }
    }
}`
  },
  {
    path: 'app/src/main/java/com/awaz/app/policy/PolicyRulesLoader.kt',
    category: 'Kotlin',
    content: `package com.awaz.app.policy

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.InputStreamReader

object PolicyRulesLoader {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun loadFromAssets(context: Context): PolicyRules {
        val deniedPackages = runCatching {
            context.assets.open("policy/denylist.json").use { stream ->
                val text = InputStreamReader(stream).readText()
                json.decodeFromString<List<String>>(text).toSet()
            }
        }.getOrDefault(setOf("com.phonepe.app", "com.google.android.apps.nbu.paisa.user", "net.one97.paytm", "in.org.npci.upiapp"))

        val sensitiveTerms = runCatching {
            context.assets.open("policy/sensitive_terms.json").use { stream ->
                val text = InputStreamReader(stream).readText()
                json.decodeFromString<List<String>>(text).toSet()
            }
        }.getOrDefault(setOf("OTP", "ओटीपी", "PIN", "पिन", "CVV", "password", "पासवर्ड", "verification code", "UPI PIN", "एटीएम"))

        val deniedPackageFragments = setOf("bank", "upi", "wallet", "pay")
        val confirmationTerms = setOf(
            "send", "call", "share", "delete", "remove", "uninstall",
            "clear data", "grant", "permission", "factory reset", "reset",
            "purchase", "pay", "install", "buy",
            "भेजें", "भेजो", "कॉल", "फ़ोन", "शेयर", "हटाएं", "हटाओ", "मिटाएं",
            "अनइन्स्टॉल", "डेटा साफ़", "अनुमति", "रीसेट", "खरीदें", "खरीदो", "भुगतान", "पैसे",
            "bhejo", "bhej", "call karo", "phone karo", "share karo", "hatao",
            "delete karo", "uninstall karo", "clear karo", "permission do",
            "khareedo", "pay karo", "payment"
        )

        return PolicyRules(deniedPackages, deniedPackageFragments, sensitiveTerms, confirmationTerms)
    }
}`
  },
  {
    path: 'app/src/main/java/com/awaz/app/event/KeyHoldDetector.kt',
    category: 'Kotlin',
    content: `package com.awaz.app.event

class KeyHoldDetector(
    val targetKeyCode: Int,
    val requiredHoldDurationMs: Long = 800L,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onTrigger: () -> Unit
) {
    private var pressStartTimeMs: Long = 0L
    private var isPressed: Boolean = false
    private var hasTriggeredForCurrentPress: Boolean = false

    fun onKeyDown(keyCode: Int, isRepeat: Boolean): Boolean {
        if (keyCode != targetKeyCode) return false
        val now = clock()

        if (!isPressed) {
            isPressed = true
            pressStartTimeMs = now
            hasTriggeredForCurrentPress = false
            return false // Return false on initial ACTION_DOWN
        }

        if (!hasTriggeredForCurrentPress && (now - pressStartTimeMs >= requiredHoldDurationMs)) {
            hasTriggeredForCurrentPress = true
            onTrigger()
            return true
        }

        return hasTriggeredForCurrentPress
    }

    fun checkHoldThreshold(): Boolean {
        if (isPressed && !hasTriggeredForCurrentPress) {
            val now = clock()
            if (now - pressStartTimeMs >= requiredHoldDurationMs) {
                hasTriggeredForCurrentPress = true
                onTrigger()
                return true
            }
        }
        return false
    }

    fun onKeyUp(keyCode: Int): Boolean {
        if (keyCode != targetKeyCode) return false
        val shouldConsume = hasTriggeredForCurrentPress
        isPressed = false
        pressStartTimeMs = 0L
        hasTriggeredForCurrentPress = false
        return shouldConsume
    }

    fun isCurrentlyPressed(): Boolean = isPressed
    fun hasTriggered(): Boolean = hasTriggeredForCurrentPress
}`
  },
  {
    path: 'app/src/test/java/com/awaz/app/event/KeyHoldDetectorTest.kt',
    category: 'Test',
    content: `package com.awaz.app.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KeyHoldDetectorTest {
    private var simulatedTime: Long = 1000L
    private var triggerCount: Int = 0
    private lateinit var detector: KeyHoldDetector

    companion object {
        const val KEY_VOLUME_UP = 24
        const val KEY_VOLUME_DOWN = 25
    }

    @Before
    fun setUp() {
        simulatedTime = 1000L
        triggerCount = 0
        detector = KeyHoldDetector(
            targetKeyCode = KEY_VOLUME_UP,
            requiredHoldDurationMs = 800L,
            clock = { simulatedTime },
            onTrigger = { triggerCount++ }
        )
    }

    @Test
    fun testInitialDown_returnsFalseNotConsumed() {
        val consumed = detector.onKeyDown(KEY_VOLUME_UP, isRepeat = false)
        assertFalse("Initial ACTION_DOWN must NOT be consumed", consumed)
        assertEquals(0, triggerCount)
    }

    @Test
    fun testShortPress_notConsumedAndNoTrigger() {
        detector.onKeyDown(KEY_VOLUME_UP, isRepeat = false)
        simulatedTime = 1400L
        detector.checkHoldThreshold()
        val upConsumed = detector.onKeyUp(KEY_VOLUME_UP)
        assertFalse("Short press ACTION_UP must NOT be consumed", upConsumed)
        assertEquals(0, triggerCount)
    }

    @Test
    fun testLongPress800ms_firesTriggerOnceAndConsumesRemainder() {
        detector.onKeyDown(KEY_VOLUME_UP, isRepeat = false)
        simulatedTime = 1800L
        assertTrue("Threshold check at 800ms should fire trigger", detector.checkHoldThreshold())
        assertEquals(1, triggerCount)

        simulatedTime = 1850L
        assertTrue(detector.onKeyDown(KEY_VOLUME_UP, isRepeat = true))
        simulatedTime = 2000L
        assertTrue(detector.onKeyUp(KEY_VOLUME_UP))
    }
}`
  },
  {
    path: 'app/src/main/java/com/awaz/app/service/AccessibilityServiceHolder.kt',
    category: 'Kotlin',
    content: `package com.awaz.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AccessibilityServiceHolder {
    private val _service = MutableStateFlow<AwazAccessibilityService?>(null)
    val service: StateFlow<AwazAccessibilityService?> = _service.asStateFlow()

    fun set(service: AwazAccessibilityService?) {
        _service.value = service
    }
}`
  },
  {
    path: 'app/src/main/java/com/awaz/app/service/AwazAccessibilityService.kt',
    category: 'Kotlin',
    content: `package com.awaz.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.awaz.app.event.AwazEvent
import com.awaz.app.event.EventBus
import com.awaz.app.event.KeyHoldDetector
import com.awaz.app.overlay.ConfirmationOverlay
import com.awaz.app.overlay.ConfirmationResult
import com.awaz.app.policy.AgentAction
import com.awaz.app.policy.AgentActionType
import com.awaz.app.policy.Decision
import com.awaz.app.policy.PolicyContext
import com.awaz.app.policy.PolicyEngine
import com.awaz.app.policy.PolicyRulesLoader
import com.awaz.app.policy.ScreenElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class ScrollDirection { FORWARD, BACKWARD }

@Serializable
data class BoundsCenterDto(val x: Int, val y: Int)

@Serializable
data class ScreenElementDto(
    val id: Int,
    val label: String,
    val role: String,
    val clickable: Boolean,
    val enabled: Boolean,
    val checked: Boolean? = null,
    val isPassword: Boolean,
    val boundsCenter: BoundsCenterDto
)

@Serializable
data class ScreenSnapshot(
    val timestamp: Long,
    val foregroundPackage: String,
    val elements: List<ScreenElementDto>
) {
    fun toJson(): String = Json { prettyPrint = true }.encodeToString(this)
}

class AwazAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG_POLICY = "AWAZ_POLICY"
        private const val TAG = "AwazA11yService"
        private const val OWN_PACKAGE_NAME = "com.awaz.app"
    }

    private lateinit var policyEngine: PolicyEngine
    private val activeNodeMap = ConcurrentHashMap<Int, AccessibilityNodeInfo>()
    private val nextElementId = AtomicInteger(1)

    private var currentForegroundPackage: String = "unknown"
    private var actionsTakenCount: Int = 0
    private var lastAction: AgentAction? = null
    private var consecutiveIdenticalCount: Int = 0
    private var lastActionTimeMs: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var confirmationOverlay: ConfirmationOverlay? = null

    private val keyHoldDetector = KeyHoldDetector(
        targetKeyCode = KeyEvent.KEYCODE_VOLUME_UP,
        requiredHoldDurationMs = 800L,
        clock = { System.currentTimeMillis() },
        onTrigger = {
            Log.i(TAG, "Hardware trigger: Volume Up held for >= 800ms")
            EventBus.post(AwazEvent.HardwareTrigger)
        }
    )

    private val holdCheckRunnable = object : Runnable {
        override fun run() {
            if (keyHoldDetector.isCurrentlyPressed()) {
                keyHoldDetector.checkHoldThreshold()
                mainHandler.postDelayed(this, 50L)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val rules = PolicyRulesLoader.loadFromAssets(this)
        policyEngine = PolicyEngine(rules) { msg -> Log.i(TAG_POLICY, msg) }
        confirmationOverlay = ConfirmationOverlay(this)
        AccessibilityServiceHolder.set(this)

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = (AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS)
            description = getString(com.awaz.app.R.string.a11y_description)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.let { currentForegroundPackage = it.toString() }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityServiceHolder.set(null)
        confirmationOverlay?.dismiss()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        AccessibilityServiceHolder.set(null)
        confirmationOverlay?.dismiss()
        clearActiveNodes()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    val isRepeat = event.repeatCount > 0
                    val consumed = keyHoldDetector.onKeyDown(event.keyCode, isRepeat)
                    if (!isRepeat) {
                        mainHandler.removeCallbacks(holdCheckRunnable)
                        mainHandler.postDelayed(holdCheckRunnable, 50L)
                    }
                    return consumed
                }
                KeyEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(holdCheckRunnable)
                    return keyHoldDetector.onKeyUp(event.keyCode)
                }
            }
        }
        return super.onKeyEvent(event)
    }

    suspend fun snapshot(): Result<ScreenSnapshot> = withContext(Dispatchers.Default) {
        runCatching {
            var root: AccessibilityNodeInfo? = null
            var attempts = 0
            while (root == null && attempts < 5) {
                root = rootInActiveWindow
                if (root == null) {
                    attempts++
                    if (attempts < 5) delay(150L)
                }
            }
            if (root == null) throw IllegalStateException("SnapshotUnavailable: rootInActiveWindow null after 5 retries")

            val rootPkg = root.packageName?.toString() ?: currentForegroundPackage
            if (rootPkg == OWN_PACKAGE_NAME) {
                root.recycle()
                throw IllegalStateException("SnapshotUnavailable: foreground package is \$OWN_PACKAGE_NAME")
            }

            clearActiveNodes()
            val elementDtos = mutableListOf<ScreenElementDto>()
            try {
                traverseNode(root, elementDtos)
            } finally {
                root.recycle()
            }

            ScreenSnapshot(System.currentTimeMillis(), rootPkg, elementDtos)
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo, list: MutableList<ScreenElementDto>) {
        if (list.size >= 60) return
        if (node.isVisibleToUser) {
            val label = extractLabel(node)
            val isClickable = node.isClickable
            val isPassword = node.isPassword
            if (label.isNotBlank() || isClickable || isPassword) {
                val elementId = nextElementId.getAndIncrement()
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val dto = ScreenElementDto(
                    id = elementId,
                    label = label.take(60),
                    role = cleanClassName(node.className?.toString()),
                    clickable = isClickable,
                    enabled = node.isEnabled,
                    checked = if (node.isCheckable) node.isChecked else null,
                    isPassword = isPassword,
                    boundsCenter = BoundsCenterDto(rect.centerX(), rect.centerY())
                )
                list.add(dto)
                activeNodeMap[elementId] = AccessibilityNodeInfo.obtain(node)
            }
        }
        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (list.size >= 60) break
            val child = node.getChild(i) ?: continue
            try { traverseNode(child, list) } finally { child.recycle() }
        }
    }

    private fun extractLabel(node: AccessibilityNodeInfo): String {
        return node.text?.toString()?.trim()?.ifBlank { null }
            ?: node.contentDescription?.toString()?.trim()?.ifBlank { null }
            ?: node.hintText?.toString()?.trim()?.ifBlank { null }
            ?: ""
    }

    private fun cleanClassName(raw: String?): String = raw?.substringAfterLast('.') ?: "View"

    suspend fun clickById(id: Int): Result<Boolean> = withContext(Dispatchers.Default) {
        runCatching {
            val targetNode = activeNodeMap[id] ?: return@runCatching false
            val rect = Rect()
            targetNode.getBoundsInScreen(rect)
            val label = extractLabel(targetNode)
            val action = AgentAction(AgentActionType.Click, id, label, System.currentTimeMillis())

            val context = PolicyContext(
                foregroundPackage = currentForegroundPackage,
                elements = activeNodeMap.map { (elId, n) ->
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    ScreenElement(elId, extractLabel(n), cleanClassName(n.className?.toString()), n.isClickable, n.isEnabled, null, n.isPassword, r.centerX(), r.centerY())
                },
                actionsTakenCount = actionsTakenCount,
                lastAction = lastAction,
                consecutiveIdenticalActionCount = consecutiveIdenticalCount,
                lastActionTimestampMs = lastActionTimeMs
            )

            val decision = policyEngine.evaluate(context, action)
            Log.i(TAG_POLICY, "Evaluated action for package '\$currentForegroundPackage', target '\$label': \$decision")
            EventBus.post(AwazEvent.PolicyDecisionMade(System.currentTimeMillis(), label, currentForegroundPackage, decision))

            when (decision) {
                is Decision.Block -> false
                is Decision.HandOffToHuman -> {
                    EventBus.post(AwazEvent.ConfirmationRequested("HANDOFF_TO_HUMAN"))
                    false
                }
                is Decision.RequireConfirmation -> {
                    val overlay = confirmationOverlay ?: return@runCatching false
                    val result = overlay.show().await()
                    if (result == ConfirmationResult.CONFIRMED) {
                        recordActionTaken(action)
                        performClickSequence(targetNode, rect)
                    } else false
                }
                is Decision.Allow -> {
                    recordActionTaken(action)
                    performClickSequence(targetNode, rect)
                }
            }
        }
    }

    private fun performClickSequence(node: AccessibilityNodeInfo, bounds: Rect): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var current: AccessibilityNodeInfo? = node.parent
        var ancestorCount = 0
        while (current != null && ancestorCount < 3) {
            try {
                if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            } finally {
                val next = current?.parent
                current?.recycle()
                current = next
                ancestorCount++
            }
        }
        val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun scroll(direction: ScrollDirection): Result<Boolean> = runCatching {
        val root = rootInActiveWindow ?: return@runCatching false
        val action = if (direction == ScrollDirection.FORWARD) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val result = root.performAction(action)
        root.recycle()
        result
    }

    fun globalBack(): Result<Boolean> = runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
    fun globalHome(): Result<Boolean> = runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
    fun globalRecents(): Result<Boolean> = runCatching { performGlobalAction(GLOBAL_ACTION_RECENTS) }

    private fun recordActionTaken(action: AgentAction) {
        actionsTakenCount++
        if (lastAction?.targetElementId == action.targetElementId && lastAction?.type?.javaClass == action.type.javaClass) {
            consecutiveIdenticalCount++
        } else {
            consecutiveIdenticalCount = 1
        }
        lastAction = action
        lastActionTimeMs = action.timestampMs
    }

    private fun clearActiveNodes() {
        for (entry in activeNodeMap.values) entry.recycle()
        activeNodeMap.clear()
    }
}`
  },
  {
    path: 'app/src/main/java/com/awaz/app/service/GestureAutomator.kt',
    category: 'Kotlin',
    content: `package com.awaz.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class GestureAutomator(
    private val serviceProvider: () -> AccessibilityService?
) {
    constructor(service: AccessibilityService) : this({ service })

    private val service: AccessibilityService?
        get() = serviceProvider()

    fun tapNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 4) {
            try {
                if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            } finally {
                val next = current?.parent
                current?.recycle()
                current = next
                depth++
            }
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            return tapCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }
        return false
    }

    fun tapCoordinates(x: Float, y: Float, durationMs: Long = 50L): Boolean {
        val s = service ?: return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return s.dispatchGesture(gesture, null, null)
    }

    fun longPressNode(node: AccessibilityNodeInfo, durationMs: Long = 750L): Boolean {
        if (node.isLongClickable && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            return true
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            return longPressCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat(), durationMs)
        }
        return false
    }

    fun longPressCoordinates(x: Float, y: Float, durationMs: Long = 750L): Boolean {
        val s = service ?: return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return s.dispatchGesture(gesture, null, null)
    }

    fun scroll(direction: ScrollDirection, targetNode: AccessibilityNodeInfo? = null): Boolean {
        val action = when (direction) {
            ScrollDirection.FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollDirection.BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        if (targetNode != null && targetNode.performAction(action)) {
            return true
        }
        val root = service?.rootInActiveWindow
        if (root != null) {
            try {
                val scrollableNode = findFirstScrollableNode(root)
                if (scrollableNode != null) {
                    val result = scrollableNode.performAction(action)
                    scrollableNode.recycle()
                    if (result) return true
                }
            } finally {
                root.recycle()
            }
        }
        return performScrollSwipeFallback(direction, targetNode)
    }

    private fun performScrollSwipeFallback(direction: ScrollDirection, targetNode: AccessibilityNodeInfo?): Boolean {
        val bounds = Rect()
        targetNode?.getBoundsInScreen(bounds)
        val centerX = 540f
        val topY = 400f
        val bottomY = 1600f
        return when (direction) {
            ScrollDirection.FORWARD -> swipe(centerX, bottomY, centerX, topY, 300L)
            ScrollDirection.BACKWARD -> swipe(centerX, topY, centerX, bottomY, 300L)
        }
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300L): Boolean {
        val s = service ?: return false
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return s.dispatchGesture(gesture, null, null)
    }

    fun findFirstScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstScrollableNode(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    fun pressSystemButton(button: String): Boolean {
        return when (button.trim().uppercase(Locale.ROOT)) {
            "BACK" -> pressBack()
            "HOME" -> pressHome()
            "RECENTS" -> pressRecents()
            else -> false
        }
    }

    fun pressBack(): Boolean = service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
    fun pressHome(): Boolean = service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false
    fun pressRecents(): Boolean = service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) ?: false
}`
  },
  {
    path: 'app/src/main/java/com/awaz/app/overlay/ConfirmationOverlay.kt',
    category: 'Kotlin',
    content: `package com.awaz.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

enum class ConfirmationResult { CONFIRMED, DENIED, TIMEOUT }

class ConfirmationOverlay(private val serviceContext: Context) {
    private val windowManager: WindowManager = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeView: View? = null
    private var activeDeferred: CompletableDeferred<ConfirmationResult>? = null
    private var timeoutRunnable: Runnable? = null
    private var isShowing: Boolean = false

    @Synchronized
    fun show(): Deferred<ConfirmationResult> {
        if (isShowing && activeDeferred != null) return activeDeferred!!
        val deferred = CompletableDeferred<ConfirmationResult>()
        activeDeferred = deferred
        isShowing = true

        mainHandler.post {
            try {
                val layoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.CENTER }

                val rootLayout = FrameLayout(serviceContext).apply { setBackgroundColor(0xCC000000.toInt()) }
                val buttonsRow = LinearLayout(serviceContext).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }

                val density = serviceContext.resources.displayMetrics.density
                val buttonSizePx = (140 * density).toInt()

                val confirmButton = FrameLayout(serviceContext).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF10B981.toInt()) }
                    setOnClickListener { finishWithResult(ConfirmationResult.CONFIRMED) }
                }
                buttonsRow.addView(confirmButton, LinearLayout.LayoutParams(buttonSizePx, buttonSizePx).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, 24, 0, 24) })

                val denyButton = FrameLayout(serviceContext).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFEF4444.toInt()) }
                    setOnClickListener { finishWithResult(ConfirmationResult.DENIED) }
                }
                buttonsRow.addView(denyButton, LinearLayout.LayoutParams(buttonSizePx, buttonSizePx).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, 24, 0, 24) })

                rootLayout.addView(buttonsRow)
                timeoutRunnable = Runnable { finishWithResult(ConfirmationResult.TIMEOUT) }
                mainHandler.postDelayed(timeoutRunnable!!, 20_000L)

                windowManager.addView(rootLayout, layoutParams)
                activeView = rootLayout
            } catch (e: Exception) {
                finishWithResult(ConfirmationResult.DENIED)
            }
        }
        return deferred
    }

    @Synchronized
    private fun finishWithResult(result: ConfirmationResult) {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        activeView?.let { view ->
            mainHandler.post { runCatching { windowManager.removeView(view) } }
            activeView = null
        }
        isShowing = false
        activeDeferred?.complete(result)
        activeDeferred = null
    }

    @Synchronized
    fun dismiss() = finishWithResult(ConfirmationResult.DENIED)
}`
  },
  {
    path: 'app/src/main/java/com/awaz/app/ui/AwazState.kt',
    category: 'Kotlin',
    content: `package com.awaz.app.ui

import androidx.compose.ui.graphics.Color

sealed class AwazState {
    abstract val backgroundColor: Color
    abstract val buttonColor: Color
    abstract val iconColor: Color
    abstract val pulseEffect: Boolean
    abstract fun next(): AwazState

    object Idle : AwazState() {
        override val backgroundColor = Color(0xFF0F172A)
        override val buttonColor = Color(0xFF1E293B)
        override val iconColor = Color(0xFF94A3B8)
        override val pulseEffect = false
        override fun next() = Listening
    }

    object Listening : AwazState() {
        override val backgroundColor = Color(0xFF022C22)
        override val buttonColor = Color(0xFF059669)
        override val iconColor = Color(0xFFFFFFFF)
        override val pulseEffect = true
        override fun next() = Acting
    }

    object Acting : AwazState() {
        override val backgroundColor = Color(0xFF451A03)
        override val buttonColor = Color(0xFFD97706)
        override val iconColor = Color(0xFFFFFFFF)
        override val pulseEffect = true
        override fun next() = NeedsHelp
    }

    object NeedsHelp : AwazState() {
        override val backgroundColor = Color(0xFF450A0A)
        override val buttonColor = Color(0xFFDC2626)
        override val iconColor = Color(0xFFFFFFFF)
        override val pulseEffect = false
        override fun next() = Idle
    }
}`
  },
  {
    path: 'app/src/main/java/com/awaz/app/ui/NavigationStateManager.kt',
    category: 'Kotlin',
    content: `package com.awaz.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NavigationStateManager(
    initialState: AwazState = AwazState.Idle,
    private val serviceStatusProvider: () -> Boolean = { true }
) {
    private val _currentState = MutableStateFlow<AwazState>(initialState)
    val currentState: StateFlow<AwazState> = _currentState.asStateFlow()

    private val _isServiceActive = MutableStateFlow(serviceStatusProvider())
    val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun transitionTo(newState: AwazState) {
        _currentState.value = newState
    }

    fun cycleNextState() {
        _currentState.value = _currentState.value.next()
    }

    fun onServiceStarted() {
        _isServiceActive.value = true
        _lastError.value = null
        if (_currentState.value is AwazState.NeedsHelp) _currentState.value = AwazState.Idle
    }

    fun onServiceStopped() {
        _isServiceActive.value = false
        _currentState.value = AwazState.Idle
    }

    fun onServiceError(message: String) {
        _lastError.value = message
        _isServiceActive.value = false
        _currentState.value = AwazState.NeedsHelp
    }

    fun onVoiceSessionStarted() {
        if (_currentState.value !is AwazState.Acting) _currentState.value = AwazState.Listening
    }

    fun onVoiceSessionEnded() {
        if (_currentState.value is AwazState.Listening) _currentState.value = AwazState.Idle
    }

    fun onActionStarted() {
        _currentState.value = AwazState.Acting
    }

    fun onActionFinished() {
        if (_currentState.value is AwazState.Acting) _currentState.value = AwazState.Listening
    }

    fun onHandoffRequired(reason: String? = null) {
        _lastError.value = reason
        _currentState.value = AwazState.NeedsHelp
    }

    fun reset() {
        _lastError.value = null
        _isServiceActive.value = serviceStatusProvider()
        _currentState.value = AwazState.Idle
    }
}`
  },
  {
    path: 'app/src/main/AndroidManifest.xml',
    category: 'Config',
    content: `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.VIBRATE" />

    <application
        android:name=".AwazApplication"
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.AwazAccessibilityService"
            android:exported="true"
            android:label="@string/accessibility_service_label"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

        <service
            android:name=".service.VoiceForegroundService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="microphone" />

    </application>
</manifest>`
  },
  {
    path: 'app/build.gradle.kts',
    category: 'Config',
    content: `plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.awaz.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.awaz.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("standard") { dimension = "distribution" }
        create("a11yTool") { dimension = "distribution" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}`
  },
  {
    path: 'app/src/standard/res/xml/accessibility_service_config.xml',
    category: 'Resource',
    content: `<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/a11y_description"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="100"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:canRequestFilterKeyEvents="true"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows|flagRequestFilterKeyEvents"
    android:settingsActivity="com.awaz.app.ui.MainActivity" />`
  },
  {
    path: 'app/src/a11yTool/res/xml/accessibility_service_config.xml',
    category: 'Resource',
    content: `<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:description="@string/a11y_description"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="100"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:canRequestFilterKeyEvents="true"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows|flagRequestFilterKeyEvents"
    android:settingsActivity="com.awaz.app.ui.MainActivity"
    android:isAccessibilityTool="true"
    tools:targetApi="29" />`
  },
  {
    path: 'app/src/main/assets/policy/denylist.json',
    category: 'Asset',
    content: `[
  "com.phonepe.app",
  "com.google.android.apps.nbu.paisa.user",
  "net.one97.paytm",
  "in.org.npci.upiapp"
]`
  },
  {
    path: 'app/src/main/assets/policy/sensitive_terms.json',
    category: 'Asset',
    content: `[
  "OTP",
  "ओटीपी",
  "PIN",
  "पिन",
  "CVV",
  "password",
  "पासवर्ड",
  "verification code",
  "UPI PIN",
  "एटीएम"
]`
  },
  {
    path: 'app/src/main/java/com/awaz/app/live/Credential.kt',
    category: 'Kotlin',
    content: `package com.awaz.app.live

import com.awaz.app.BuildConfig

sealed class Credential {
    abstract val value: String

    data class ApiKey(override val value: String) : Credential() {
        override fun toString(): String = "ApiKey(REDACTED)"
    }

    data class Ephemeral(override val value: String) : Credential() {
        override fun toString(): String = "Ephemeral(REDACTED)"
    }
}

interface TokenProvider {
    suspend fun get(): Credential?
}

class DevTokenProvider(
    private val token: String = BuildConfig.GEMINI_DEV_TOKEN
) : TokenProvider {
    override suspend fun get(): Credential? {
        val trimmed = token.trim()
        return if (trimmed.isNotBlank()) Credential.ApiKey(trimmed) else null
    }
}

const val DEFAULT_GEMINI_LIVE_BASE_URL =
    "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

fun buildLiveUrl(base: String, c: Credential): String {
    val cleanBase = base.trim()
    val delimiter = if (cleanBase.contains("?")) "&" else "?"
    return when (c) {
        is Credential.ApiKey -> "$cleanBase\${delimiter}key=\${c.value}"
        is Credential.Ephemeral -> "$cleanBase\${delimiter}access_token=\${c.value}"
    }
}`
  },
  {
    path: 'app/src/main/java/com/awaz/app/live/LiveConfig.kt',
    category: 'Kotlin',
    content: `package com.awaz.app.live

import kotlinx.serialization.json.*

object LiveConfig {
    const val DEFAULT_MODEL = "models/gemini-3.8-live"
    const val DEFAULT_VOICE_NAME = "Puck"

    fun buildToolDeclarations(): JsonArray {
        return buildJsonArray {
            add(buildJsonObject {
                put("name", JsonPrimitive("get_screen_state"))
                put("description", JsonPrimitive("Inspects current foreground Android screen elements"))
            })
            add(buildJsonObject {
                put("name", JsonPrimitive("tap_element"))
                put("description", JsonPrimitive("Taps an element by ID"))
            })
            add(buildJsonObject {
                put("name", JsonPrimitive("scroll_screen"))
                put("description", JsonPrimitive("Scrolls FORWARD or BACKWARD"))
            })
            add(buildJsonObject {
                put("name", JsonPrimitive("press_system_button"))
                put("description", JsonPrimitive("Presses BACK, HOME, or RECENTS"))
            })
            add(buildJsonObject {
                put("name", JsonPrimitive("request_confirmation"))
                put("description", JsonPrimitive("Requests explicit confirmation via overlay"))
            })
            add(buildJsonObject {
                put("name", JsonPrimitive("request_human_help"))
                put("description", JsonPrimitive("Hands off to human on sensitive screens"))
            })
            add(buildJsonObject {
                put("name", JsonPrimitive("finish_task"))
                put("description", JsonPrimitive("Signals task completion"))
            })
        }
    }
}`
  },
  {
    path: 'app/src/main/java/com/awaz/app/live/LiveMessages.kt',
    category: 'Kotlin',
    content: `package com.awaz.app.live

import kotlinx.serialization.json.*
import java.util.Base64

data class LiveToolCall(val id: String, val name: String, val args: JsonObject)

sealed class LiveEvent {
    object SetupComplete : LiveEvent()
    data class AudioOut(val data: ByteArray) : LiveEvent()
    data class InputTranscript(val text: String) : LiveEvent()
    data class OutputTranscript(val text: String) : LiveEvent()
    object TurnComplete : LiveEvent()
    object Interrupted : LiveEvent()
    data class ToolCalls(val calls: List<LiveToolCall>) : LiveEvent()
    data class ToolCallCancelled(val callIds: List<String>) : LiveEvent()
    object GoAway : LiveEvent()
    data class ResumptionUpdate(val resumptionHandle: String) : LiveEvent()
    data class Unknown(val raw: String) : LiveEvent()
}`
  },
  {
    path: 'app/src/main/java/com/awaz/app/tools/ToolExecutor.kt',
    category: 'Kotlin',
    content: `package com.awaz.app.tools

import com.awaz.app.live.LiveToolCall
import kotlinx.serialization.json.JsonObject

data class ToolResult(
    val output: JsonObject,
    val isCompleted: Boolean = false,
    val needsHumanHelp: Boolean = false
)

interface ToolExecutor {
    suspend fun execute(call: LiveToolCall): ToolResult
}`
  }
];

export async function generateProjectZip(): Promise<Blob> {
  const zip = new JSZip();

  zip.file('settings.gradle.kts', `pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AWAZ"
include(":app")
`);

  zip.file('build.gradle.kts', `plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
`);

  for (const file of ANDROID_FILES) {
    zip.file(file.path, file.content);
  }

  zip.file('README.md', `# AWAZ - Native Android Foundation
Voice-First Accessibility Assistant for Elderly & Non-Literate Users.

## Build Flavors
- **standard**: Standard build without \`android:isAccessibilityTool\`.
- **a11yTool**: Build with \`android:isAccessibilityTool="true"\` (tools:targetApi="29").

## Run JVM Unit Tests
\`\`\`bash
./gradlew testStandardDebugUnitTest
\`\`\`
`);

  return await zip.generateAsync({ type: 'blob' });
}
