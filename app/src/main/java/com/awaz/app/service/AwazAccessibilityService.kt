package com.awaz.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.awaz.app.BuildConfig
import com.awaz.app.event.AwazEvent
import com.awaz.app.event.Cancellable
import com.awaz.app.event.Clock
import com.awaz.app.event.EventBus
import com.awaz.app.event.KeyAction
import com.awaz.app.event.KeyHoldDetector
import com.awaz.app.event.Scheduler
import com.awaz.app.overlay.ConfirmationOverlay
import com.awaz.app.overlay.ConfirmationResult
import com.awaz.app.policy.AgentAction
import com.awaz.app.policy.AgentActionType
import com.awaz.app.policy.Decision
import com.awaz.app.policy.PolicyContext
import com.awaz.app.policy.PolicyEngine
import com.awaz.app.policy.PolicyRulesLoader
import com.awaz.app.policy.ScreenElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG_POLICY = "AWAZ_POLICY"
private const val TAG_SERVICE = "AwazA11yService"

enum class ScrollDirection {
    FORWARD, BACKWARD
}

sealed class SnapshotResult {
    @Serializable
    data class Success(
        val timestamp: Long,
        val foregroundPackage: String,
        val elements: List<ScreenElementSnapshot>
    ) : SnapshotResult() {
        fun toJson(): String = Json.encodeToString(this)
    }

    @Serializable
    data class Blocked(
        val timestamp: Long,
        val foregroundPackage: String,
        val decisionCode: String
    ) : SnapshotResult() {
        fun toJson(): String = Json.encodeToString(this)
    }

    object SnapshotUnavailable : SnapshotResult() {
        override fun toString(): String = "SnapshotUnavailable"
    }
}

@Serializable
data class ScreenElementSnapshot(
    val id: Int,
    val label: String,
    val role: String,
    val clickable: Boolean,
    val enabled: Boolean,
    val checked: Boolean? = null,
    val isPassword: Boolean,
    val boundsCenterX: Int,
    val boundsCenterY: Int
)

data class StoredElementSnapshot(
    val id: Int,
    val viewIdResourceName: String?,
    val label: String,
    val packageName: String,
    val bounds: Rect
)

class AwazAccessibilityService : AccessibilityService(), AccessibilityActions {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var policyEngine: PolicyEngine
    private lateinit var keyHoldDetector: KeyHoldDetector
    internal val gestureAutomator: GestureAutomator by lazy { GestureAutomator(this) }

    private val activeNodeMap = ConcurrentHashMap<Int, AccessibilityNodeInfo>()
    private val nextElementId = AtomicInteger(1)

    // Stale Screen Guard state tracking
    private val snapshotIdCounter = AtomicLong(1L)
    var currentSnapshotId: Long = 0L
        private set
    var currentSnapshotTimeMs: Long = 0L
        private set
    var lastWindowStateChangeTimeMs: Long = 0L
        private set
    var snapshotForegroundPackage: String = ""
        private set
    private val snapshotElementRecords = ConcurrentHashMap<Int, StoredElementSnapshot>()

    private var currentForegroundPackage: String = "unknown"
    private var actionsTakenCount: Int = 0
    private var lastAction: AgentAction? = null
    private var consecutiveIdenticalCount: Int = 0
    private var lastActionTimeMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        val rules = PolicyRulesLoader.loadRules(this)
        policyEngine = PolicyEngine(rules) { decisionMsg ->
            Log.i(TAG_POLICY, decisionMsg)
        }

        val clock = Clock { System.currentTimeMillis() }
        val scheduler = Scheduler { delayMs, task ->
            val runnable = Runnable { task() }
            mainHandler.postDelayed(runnable, delayMs)
            Cancellable { mainHandler.removeCallbacks(runnable) }
        }

        keyHoldDetector = KeyHoldDetector(clock, scheduler, holdThresholdMs = 800L)
        keyHoldDetector.setOnTriggerCallback {
            Log.i(TAG_SERVICE, "KeyHoldDetector triggered: Volume Up held >= 800ms")
            EventBus.post(AwazEvent.HardwareTrigger)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityServiceHolder.setService(this)
        Log.i(TAG_SERVICE, "AWAZ AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastWindowStateChangeTimeMs = System.currentTimeMillis()
        }
        event.packageName?.let {
            val pkg = it.toString()
            if (pkg != packageName) {
                currentForegroundPackage = pkg
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG_SERVICE, "AWAZ AccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityServiceHolder.clearService()
        ConfirmationOverlay.dismiss()
        clearActiveNodes()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        AccessibilityServiceHolder.clearService()
        ConfirmationOverlay.dismiss()
        clearActiveNodes()
        Log.i(TAG_SERVICE, "AWAZ AccessibilityService destroyed")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    keyHoldDetector.onKeyDown(event.repeatCount)
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    val action = keyHoldDetector.onKeyUp()
                    if (action is KeyAction.RaiseVolume) {
                        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        audioManager?.adjustStreamVolume(
                            AudioManager.USE_DEFAULT_STREAM_TYPE,
                            AudioManager.ADJUST_RAISE,
                            AudioManager.FLAG_SHOW_UI
                        )
                    }
                    return true
                }
            }
            return true
        }
        return super.onKeyEvent(event)
    }

    // =========================================================================
    // STALE SCREEN GUARD
    // =========================================================================

    fun isScreenStale(): Boolean {
        // (a) no snapshot exists
        if (currentSnapshotId == 0L || currentSnapshotTimeMs == 0L) return true
        // (b) a window-state change or foreground-package change happened after the snapshot
        if (lastWindowStateChangeTimeMs > currentSnapshotTimeMs) return true
        if (currentForegroundPackage != snapshotForegroundPackage) return true
        // (c) the snapshot is older than 15 seconds
        val now = System.currentTimeMillis()
        if (now - currentSnapshotTimeMs > 15_000L) return true
        return false
    }

    // =========================================================================
    // ACCESSIBILITY ACTIONS IMPLEMENTATION
    // =========================================================================

    /**
     * Privacy-first snapshot:
     * 1. Evaluates foreground package against PolicyEngine rules BEFORE reading node text.
     * 2. For denied packages: returns Blocked result containing ONLY package name and decision code.
     * 3. Excludes own windows (com.awaz.app).
     * 4. Updates monotonically increasing snapshotId and tracks capture timestamp.
     */
    override suspend fun snapshot(): Result<SnapshotResult> = runCatching {
        withContext(Dispatchers.Default) {
            val pkg = currentForegroundPackage

            // Policy check: verify package BEFORE reading any node text
            val packageDecision = policyEngine.evaluatePackage(pkg)
            if (packageDecision != null) {
                val code = if (packageDecision is Decision.HandOffToHuman) packageDecision.code else "BLOCKED_PACKAGE"
                val blockedResult = SnapshotResult.Blocked(
                    timestamp = System.currentTimeMillis(),
                    foregroundPackage = pkg,
                    decisionCode = code
                )
                if (BuildConfig.DEBUG) {
                    Log.d(TAG_SERVICE, "Snapshot Blocked (no labels traversed): ${blockedResult.toJson()}")
                }
                return@withContext blockedResult
            }

            var root: AccessibilityNodeInfo? = null
            for (attempt in 1..5) {
                root = rootInActiveWindow
                if (root != null) break
                delay(150L)
            }

            if (root == null) {
                return@withContext SnapshotResult.SnapshotUnavailable
            }

            clearActiveNodes()
            snapshotElementRecords.clear()
            val elements = mutableListOf<ScreenElementSnapshot>()

            try {
                val rootPkg = root.packageName?.toString() ?: pkg
                if (rootPkg == packageName) {
                    return@withContext SnapshotResult.SnapshotUnavailable
                }

                traverseNode(root, elements, rootPkg)

                val snapshotId = snapshotIdCounter.getAndIncrement()
                val now = System.currentTimeMillis()
                currentSnapshotId = snapshotId
                currentSnapshotTimeMs = now
                snapshotForegroundPackage = rootPkg

                val successResult = SnapshotResult.Success(
                    timestamp = now,
                    foregroundPackage = rootPkg,
                    elements = elements
                )

                if (BuildConfig.DEBUG) {
                    Log.i(TAG_SERVICE, "ScreenSnapshot captured (id=$snapshotId):\n${successResult.toJson()}")
                }

                successResult
            } finally {
                root.recycle()
            }
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo, list: MutableList<ScreenElementSnapshot>, rootPkg: String) {
        if (list.size >= 60) return

        if (node.isVisibleToUser) {
            val label = extractLabel(node)
            val isClickable = node.isClickable
            val isPassword = node.isPassword

            if (label.isNotBlank() || isClickable || isPassword) {
                val elementId = nextElementId.getAndIncrement()
                val rect = Rect()
                node.getBoundsInScreen(rect)

                val el = ScreenElementSnapshot(
                    id = elementId,
                    label = label.take(60),
                    role = cleanClassName(node.className?.toString()),
                    clickable = isClickable,
                    enabled = node.isEnabled,
                    checked = if (node.isCheckable) node.isChecked else null,
                    isPassword = isPassword,
                    boundsCenterX = rect.centerX(),
                    boundsCenterY = rect.centerY()
                )
                list.add(el)
                activeNodeMap[elementId] = AccessibilityNodeInfo.obtain(node)

                snapshotElementRecords[elementId] = StoredElementSnapshot(
                    id = elementId,
                    viewIdResourceName = node.viewIdResourceName,
                    label = label,
                    packageName = node.packageName?.toString() ?: rootPkg,
                    bounds = rect
                )
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (list.size >= 60) break
            val child = node.getChild(i) ?: continue
            try {
                traverseNode(child, list, rootPkg)
            } finally {
                child.recycle()
            }
        }
    }

    private fun extractLabel(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank()) return text
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank()) return desc
        val hint = node.hintText?.toString()?.trim()
        if (!hint.isNullOrBlank()) return hint
        return ""
    }

    private fun cleanClassName(raw: String?): String {
        if (raw == null) return "View"
        return raw.substringAfterLast('.')
    }

    /**
     * Executes clickById after:
     * 1. Stale Screen Guard checks (snapshot freshness, window change, package change, 15s expiry)
     * 2. Re-resolving the node to confirm label and package match snapshot
     * 3. Locating the actual node to be clicked (Tier 1 target or Tier 2 clickable ancestor up to 3 levels)
     * 4. Evaluating PolicyEngine on both original element and actual clickable node's subtree, taking the stricter decision
     * 5. Enforcing that Tier 3 coordinate taps are allowed only when package is unchanged and bounds match
     */
    override fun clickById(id: Int): Result<Boolean> = runCatching {
        // 1. Stale screen check
        if (isScreenStale()) {
            throw StaleScreenException("STALE_SCREEN")
        }

        val record = snapshotElementRecords[id] ?: throw StaleScreenException("STALE_SCREEN")
        val targetNode = activeNodeMap[id] ?: throw StaleScreenException("STALE_SCREEN")

        // 2. Re-resolve check: confirm label and package still match the snapshot
        val currentLabel = extractLabel(targetNode)
        val currentPkg = targetNode.packageName?.toString() ?: currentForegroundPackage
        if (currentLabel != record.label || currentPkg != record.packageName) {
            throw StaleScreenException("STALE_SCREEN")
        }

        // 3. Find the node that will ACTUALLY be clicked (target or clickable ancestor up to 3 levels)
        val (clickableNode, ancestorDepth) = findClickableTarget(targetNode)

        // 4. Policy evaluation: evaluate against original element and against actual node's full subtree
        val originalAction = AgentAction(
            type = AgentActionType.Click,
            targetElementId = id,
            targetLabel = currentLabel,
            timestampMs = System.currentTimeMillis()
        )
        val context = createPolicyContext()
        val decisionOriginal = policyEngine.evaluate(context, originalAction)

        val subtreeText = if (ancestorDepth > 0) collectSubtreeText(clickableNode) else currentLabel
        val subtreeAction = AgentAction(
            type = AgentActionType.Click,
            targetElementId = id,
            targetLabel = subtreeText,
            timestampMs = System.currentTimeMillis()
        )
        val decisionSubtree = policyEngine.evaluate(context, subtreeAction)

        // Stricter decision: Block > HandOffToHuman > RequireConfirmation > Allow
        val decision = stricterOf(decisionOriginal, decisionSubtree)

        EventBus.post(
            AwazEvent.PolicyDecisionMade(
                timestamp = System.currentTimeMillis(),
                targetLabel = subtreeText,
                pkg = currentForegroundPackage,
                decision = decision
            )
        )

        when (decision) {
            is Decision.Block -> false
            is Decision.HandOffToHuman -> {
                EventBus.post(AwazEvent.ConfirmationRequested("HANDOFF_TO_HUMAN"))
                false
            }
            is Decision.RequireConfirmation -> {
                ConfirmationOverlay.show(this) { result ->
                    if (result == ConfirmationResult.CONFIRMED) {
                        performClickExecution(clickableNode, targetNode, record)
                    }
                }
                true
            }
            is Decision.Allow -> {
                recordActionTaken(originalAction)
                performClickExecution(clickableNode, targetNode, record)
            }
        }
    }

    private fun findClickableTarget(node: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, Int> {
        if (node.isClickable) return Pair(node, 0)
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 1
        while (current != null && depth <= 3) {
            if (current.isClickable) {
                return Pair(current, depth)
            }
            val next = current.parent
            if (next == null) break
            current = next
            depth++
        }
        return Pair(node, 0)
    }

    private fun collectSubtreeText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun traverse(n: AccessibilityNodeInfo) {
            val label = extractLabel(n)
            if (label.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append(" ")
                sb.append(label)
            }
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                traverse(child)
                child.recycle()
            }
        }
        traverse(node)
        return sb.toString()
    }

    private fun stricterOf(d1: Decision, d2: Decision): Decision {
        fun score(d: Decision): Int = when (d) {
            is Decision.Block -> 4
            is Decision.HandOffToHuman -> 3
            is Decision.RequireConfirmation -> 2
            is Decision.Allow -> 1
        }
        return if (score(d1) >= score(d2)) d1 else d2
    }

    private fun performClickExecution(
        clickableNode: AccessibilityNodeInfo,
        originalNode: AccessibilityNodeInfo,
        record: StoredElementSnapshot
    ): Boolean {
        // Tier 1 & Tier 2: Try ACTION_CLICK on the chosen node
        if (clickableNode.isClickable && clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // Tier 3: Coordinate tap allowed ONLY when foreground package is unchanged and bounds still match
        val currentBounds = Rect()
        originalNode.getBoundsInScreen(currentBounds)
        if (currentForegroundPackage != record.packageName || currentBounds != record.bounds) {
            Log.w(TAG_SERVICE, "Tier 3 coordinate tap aborted: bounds or package mismatch")
            throw StaleScreenException("STALE_SCREEN")
        }

        return gestureAutomator.tapCoordinates(currentBounds.centerX().toFloat(), currentBounds.centerY().toFloat())
    }

    override fun scroll(direction: ScrollDirection): Result<Boolean> = runCatching {
        // 1. Stale Screen Guard
        if (isScreenStale()) {
            throw StaleScreenException("STALE_SCREEN")
        }

        // 2. PolicyEngine evaluation before acting
        val actionType = if (direction == ScrollDirection.FORWARD) {
            AgentActionType.ScrollForward
        } else {
            AgentActionType.ScrollBackward
        }
        val action = AgentAction(type = actionType, timestampMs = System.currentTimeMillis())
        val decision = policyEngine.evaluate(createPolicyContext(), action)

        when (decision) {
            is Decision.Block -> false
            is Decision.HandOffToHuman -> {
                EventBus.post(AwazEvent.ConfirmationRequested("HANDOFF_TO_HUMAN"))
                false
            }
            is Decision.RequireConfirmation -> {
                ConfirmationOverlay.show(this) { result ->
                    if (result == ConfirmationResult.CONFIRMED) {
                        gestureAutomator.scroll(direction)
                    }
                }
                true
            }
            is Decision.Allow -> {
                recordActionTaken(action)
                gestureAutomator.scroll(direction)
            }
        }
    }

    override fun globalBack(): Result<Boolean> = runCatching {
        val action = AgentAction(type = AgentActionType.GlobalBack, timestampMs = System.currentTimeMillis())
        val decision = policyEngine.evaluate(createPolicyContext(), action)
        if (decision is Decision.Block || decision is Decision.HandOffToHuman) return@runCatching false
        recordActionTaken(action)
        gestureAutomator.pressBack()
    }

    override fun globalHome(): Result<Boolean> = runCatching {
        val action = AgentAction(type = AgentActionType.GlobalHome, timestampMs = System.currentTimeMillis())
        val decision = policyEngine.evaluate(createPolicyContext(), action)
        if (decision is Decision.Block || decision is Decision.HandOffToHuman) return@runCatching false
        recordActionTaken(action)
        gestureAutomator.pressHome()
    }

    override fun globalRecents(): Result<Boolean> = runCatching {
        val action = AgentAction(type = AgentActionType.GlobalRecents, timestampMs = System.currentTimeMillis())
        val decision = policyEngine.evaluate(createPolicyContext(), action)
        if (decision is Decision.Block || decision is Decision.HandOffToHuman) return@runCatching false
        recordActionTaken(action)
        gestureAutomator.pressRecents()
    }

    override fun openApp(packageName: String): Result<Boolean> = runCatching {
        val packageDecision = policyEngine.evaluatePackage(packageName)
        if (packageDecision != null) {
            Log.w(TAG_POLICY, "Opening app $packageName blocked by policy: $packageDecision")
            return@runCatching false
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return@runCatching false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        true
    }

    override fun openSettingsPage(action: String): Result<Boolean> = runCatching {
        val intentAction = when (action.lowercase(java.util.Locale.ROOT)) {
            "wifi" -> android.provider.Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
            "accessibility" -> android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
            "display" -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> android.provider.Settings.ACTION_SOUND_SETTINGS
            else -> android.provider.Settings.ACTION_SETTINGS
        }
        val intent = Intent(intentAction).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        startActivity(intent)
        true
    }

    private fun createPolicyContext(): PolicyContext {
        return PolicyContext(
            foregroundPackage = currentForegroundPackage,
            elements = emptyList(),
            actionsTakenCount = actionsTakenCount,
            lastAction = lastAction,
            consecutiveIdenticalActionCount = consecutiveIdenticalCount,
            lastActionTimestampMs = lastActionTimeMs
        )
    }

    private fun recordActionTaken(action: AgentAction) {
        actionsTakenCount++
        if (lastAction?.targetElementId == action.targetElementId &&
            lastAction?.type?.javaClass == action.type.javaClass
        ) {
            consecutiveIdenticalCount++
        } else {
            consecutiveIdenticalCount = 1
        }
        lastAction = action
        lastActionTimeMs = action.timestampMs
    }

    private fun clearActiveNodes() {
        for (entry in activeNodeMap.values) {
            entry.recycle()
        }
        activeNodeMap.clear()
    }
}
