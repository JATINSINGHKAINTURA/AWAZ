package com.awaz.app.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awaz.app.AwazApplication
import com.awaz.app.event.AwazEvent
import com.awaz.app.event.EventBus
import com.awaz.app.live.LiveDebugMetrics
import com.awaz.app.overlay.ConfirmationOverlay
import com.awaz.app.overlay.ConfirmationResult
import com.awaz.app.service.AccessibilityServiceHolder
import com.awaz.app.service.SnapshotResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "AwazDebugScreen"
private const val OWN_PACKAGE_NAME = "com.awaz.app"

/**
 * DEBUG-only screen for inspecting PolicyEngine decisions, dumping foreground screens,
 * and testing the ConfirmationOverlay.
 */
@Composable
fun DebugScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val connectedService by AccessibilityServiceHolder.service.collectAsState()

    val app = context.applicationContext as? AwazApplication
    val liveMetrics by (app?.sessionController?.debugMetrics ?: remember { MutableStateFlow(LiveDebugMetrics()) }).collectAsState()

    val recentDecisions = remember { mutableStateListOf<AwazEvent.PolicyDecisionMade>() }
    var dumpedJson by remember { mutableStateOf<String?>(null) }
    var dumpStatusMessage by remember { mutableStateOf<String?>(null) }
    var isDumpingCountdown by remember { mutableStateOf(false) }
    var countdownSeconds by remember { mutableStateOf(3) }
    var lastOverlayResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        recentDecisions.clear()
        recentDecisions.addAll(EventBus.recentDecisions)

        EventBus.events.collect { event ->
            if (event is AwazEvent.PolicyDecisionMade) {
                recentDecisions.add(0, event)
            }
        }
    }

    val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.BugReport,
                contentDescription = null,
                tint = Color(0xFFF59E0B)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "AWAZ Debug & Diagnostics",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Action Buttons Row: (b) Dump screen & (c) Test confirmation
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // (b) Dump Screen button (tester switches apps during 3s countdown)
            Button(
                onClick = {
                    if (isDumpingCountdown) return@Button
                    isDumpingCountdown = true
                    countdownSeconds = 3
                    dumpStatusMessage = "Switch apps now..."
                    dumpedJson = null

                    val handler = Handler(Looper.getMainLooper())
                    handler.postDelayed({ countdownSeconds = 2 }, 1000L)
                    handler.postDelayed({ countdownSeconds = 1 }, 2000L)
                    handler.postDelayed({
                        isDumpingCountdown = false
                        coroutineScope.launch {
                            val service = connectedService
                            if (service != null) {
                                val result = service.snapshot()
                                result.onSuccess { snapshotResult ->
                                    when (snapshotResult) {
                                        is SnapshotResult.Success -> {
                                            val json = snapshotResult.toJson()
                                            dumpedJson = json
                                            dumpStatusMessage = "Snapshot captured for ${snapshotResult.foregroundPackage}"
                                            Log.i(TAG, "===== SCREEN DUMP SNAPSHOT =====")
                                            Log.i(TAG, json)
                                            EventBus.post(AwazEvent.ScreenDumpGenerated(json, System.currentTimeMillis()))
                                        }
                                        is SnapshotResult.Blocked -> {
                                            val json = snapshotResult.toJson()
                                            dumpedJson = json
                                            dumpStatusMessage = "Blocked package: ${snapshotResult.foregroundPackage} (${snapshotResult.decisionCode})"
                                            Log.i(TAG, "===== BLOCKED SCREEN SNAPSHOT =====")
                                            Log.i(TAG, json)
                                            EventBus.post(AwazEvent.ScreenDumpGenerated(json, System.currentTimeMillis()))
                                        }
                                        is SnapshotResult.SnapshotUnavailable -> {
                                            dumpStatusMessage = "switch app (AWAZ is in the foreground, or window unavailable)"
                                            Log.w(TAG, dumpStatusMessage!!)
                                        }
                                    }
                                }.onFailure { error ->
                                    dumpStatusMessage = "Snapshot failed: ${error.message}"
                                    Log.w(TAG, dumpStatusMessage!!)
                                }
                            } else {
                                dumpStatusMessage = "AwazAccessibilityService is not connected"
                                Log.w(TAG, dumpStatusMessage!!)
                            }
                        }
                    }, 3000L)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isDumpingCountdown) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Wait ${countdownSeconds}s", color = Color.White, fontSize = 12.sp)
                } else {
                    Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Dump Screen", color = Color.White, fontSize = 12.sp)
                }
            }

            // (c) Test confirmation button (shows confirmation overlay via service)
            Button(
                onClick = {
                    lastOverlayResult = "Overlay showing..."
                    val service = connectedService
                    if (service != null) {
                        val overlay = ConfirmationOverlay(service)
                        coroutineScope.launch {
                            val result = overlay.show().await()
                            lastOverlayResult = "Overlay Result: $result"
                            Log.i(TAG, "Confirmation Overlay completed with result: $result")
                        }
                    } else {
                        lastOverlayResult = "Service not connected"
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Test Confirm", color = Color.White, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Live Session Diagnostics: Session State, First-Audio Latency, Last 20 Events
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LIVE SESSION DIAGNOSTICS",
                        color = Color(0xFF93C5FD),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "State: ${liveMetrics.sessionState}",
                        color = when (liveMetrics.sessionState) {
                            "Ready" -> Color(0xFF10B981)
                            "Connecting", "Reconnecting" -> Color(0xFFF59E0B)
                            "Failed" -> Color(0xFFEF4444)
                            else -> Color(0xFF94A3B8)
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "First-Audio Latency:",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                    Text(
                        text = liveMetrics.firstAudioLatencyMs?.let { "${it}ms" } ?: "N/A (awaiting turn)",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (liveMetrics.last20Events.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Last ${liveMetrics.last20Events.size} Events (newest first):",
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = liveMetrics.last20Events.take(10).joinToString(" → "),
                            color = Color(0xFFE2E8F0),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2
                        )
                    }
                }
            }
        }

        // Status banner
        dumpStatusMessage?.let { msg ->
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (msg.startsWith("switch app")) Color(0xFF78350F) else Color(0xFF1E293B),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (msg.startsWith("switch app")) {
                    Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(text = msg, color = Color(0xFFE2E8F0), fontSize = 12.sp)
            }
        }

        lastOverlayResult?.let { resultText ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = resultText,
                color = Color(0xFF6EE7B7),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Display Dumped JSON if available
        dumpedJson?.let { json ->
            Text(
                text = "Screen Snapshot JSON (written to Logcat):",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF020617)),
                shape = RoundedCornerShape(8.dp)
            ) {
                LazyColumn(modifier = Modifier.padding(8.dp)) {
                    item {
                        Text(
                            text = json,
                            color = Color(0xFFA5F3FC),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // (a) List recent PolicyEngine decisions as text
        Text(
            text = "Recent PolicyEngine Decisions (${recentDecisions.size}):",
            color = Color(0xFF94A3B8),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))

        if (recentDecisions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No policy decisions recorded yet.\nInteract with elements or trigger actions.",
                    color = Color(0xFF64748B),
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(recentDecisions) { decisionEvent ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = when (decisionEvent.decision) {
                                is com.awaz.app.policy.Decision.Allow -> Color(0xFF064E3B)
                                is com.awaz.app.policy.Decision.RequireConfirmation -> Color(0xFF78350F)
                                is com.awaz.app.policy.Decision.Block -> Color(0xFF450A0A)
                                is com.awaz.app.policy.Decision.HandOffToHuman -> Color(0xFF581C87)
                            }
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = decisionEvent.decision.javaClass.simpleName,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = dateFormat.format(Date(decisionEvent.timestamp)),
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 10.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Package: ${decisionEvent.pkg}",
                                color = Color(0xFFE2E8F0),
                                fontSize = 11.sp
                            )
                            decisionEvent.targetLabel?.let { lbl ->
                                Text(
                                    text = "Target: \"$lbl\"",
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
