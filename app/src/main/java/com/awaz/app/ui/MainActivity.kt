package com.awaz.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.awaz.app.AwazApplication
import com.awaz.app.BuildConfig
import com.awaz.app.service.AccessibilityServiceHolder
import com.awaz.app.service.VoiceForegroundService
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AwazAppRoot()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            VoiceForegroundService.stop(this)
        }
    }
}

@Composable
fun AwazAppRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as? AwazApplication
    val navigationManager = app?.navigationStateManager ?: NavigationStateManager()

    val currentState by navigationManager.currentState.collectAsState()

    val connectedService by AccessibilityServiceHolder.service.collectAsState()
    var isAccessibilityEnabled by remember { mutableStateOf(checkAccessibilityServiceEnabled(context)) }
    var showDebugScreen by remember { mutableStateOf(false) }

    // Required permissions: RECORD_AUDIO and POST_NOTIFICATIONS on API 33+
    val permissionsToRequest = remember {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        list.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val recordAudioGranted = results[Manifest.permission.RECORD_AUDIO] == true
        if (recordAudioGranted) {
            startVoiceServiceSafely(
                context,
                onStateChange = { navigationManager.transitionTo(it) },
                onError = { navigationManager.onServiceError(it) },
                onServiceStarted = { navigationManager.onServiceStarted() }
            )
        } else {
            navigationManager.onServiceError(NavigationErrorCode.PERMISSION_DENIED)
        }
    }

    // Check permissions and start service
    LaunchedEffect(Unit) {
        val allGranted = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            startVoiceServiceSafely(
                context,
                onStateChange = { navigationManager.transitionTo(it) },
                onError = { navigationManager.onServiceError(it) },
                onServiceStarted = { navigationManager.onServiceStarted() }
            )
        } else {
            permissionLauncher.launch(permissionsToRequest)
        }
    }

    // Monitor accessibility service state
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = connectedService != null || checkAccessibilityServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        if (!isAccessibilityEnabled) {
            // First-run screen: icon-only guide to enable accessibility service
            FirstRunScreen(
                onOpenAccessibilitySettings = {
                    isAccessibilityEnabled = connectedService != null || checkAccessibilityServiceEnabled(context)
                }
            )
        } else if (showDebugScreen && BuildConfig.DEBUG) {
            // DEBUG screen only in debug builds
            DebugScreen(onBack = { showDebugScreen = false })
        } else {
            // Main Voice Button Screen: ZERO TEXT in release builds
            MainVoiceButtonScreen(
                currentState = currentState,
                onStateChange = {
                    if (app != null) {
                        app.sessionController.toggleSession()
                    } else {
                        navigationManager.cycleNextState()
                    }
                },
                onOpenDebug = {
                    if (BuildConfig.DEBUG) {
                        showDebugScreen = true
                    }
                }
            )
        }
    }
}

/**
 * Starts VoiceForegroundService wrapped in try/catch for SecurityException
 * and ForegroundServiceStartNotAllowedException, transitioning to NeedsHelp on error.
 */
private fun startVoiceServiceSafely(
    context: Context,
    onStateChange: (AwazState) -> Unit,
    onError: (NavigationErrorCode) -> Unit = {},
    onServiceStarted: () -> Unit = {}
) {
    try {
        VoiceForegroundService.start(context)
        onServiceStarted()
    } catch (e: SecurityException) {
        Log.e("MainActivity", "SecurityException starting VoiceForegroundService", e)
        onError(NavigationErrorCode.PERMISSION_DENIED)
    } catch (e: Throwable) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            e is android.app.ForegroundServiceStartNotAllowedException
        ) {
            Log.e("MainActivity", "ForegroundServiceStartNotAllowedException", e)
            onError(NavigationErrorCode.SERVICE_ERROR)
        } else {
            Log.e("MainActivity", "Unexpected error starting VoiceForegroundService", e)
            onError(NavigationErrorCode.SERVICE_ERROR)
        }
    }
}

@Composable
fun MainVoiceButtonScreen(
    currentState: AwazState,
    onStateChange: (AwazState) -> Unit,
    onOpenDebug: () -> Unit
) {
    val context = LocalContext.current

    val animatedBgColor by animateColorAsState(
        targetValue = currentState.backgroundColor,
        animationSpec = tween(durationMillis = 400),
        label = "BgColorAnimation"
    )

    val animatedBtnColor by animateColorAsState(
        targetValue = currentState.buttonColor,
        animationSpec = tween(durationMillis = 350),
        label = "BtnColorAnimation"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "PulseTransition")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (currentState.pulseEffect) 1.08f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(animatedBgColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                // Haptic feedback & state cycling
                triggerHapticFeedback(context)
                onStateChange(currentState.next())
            },
        contentAlignment = Alignment.Center
    ) {
        // Giant Central State Button: ZERO TEXT, icons only
        Box(
            modifier = Modifier
                .size(240.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(animatedBtnColor),
            contentAlignment = Alignment.Center
        ) {
            val stateIcon: ImageVector = when (currentState) {
                is AwazState.Idle -> Icons.Default.MicNone
                is AwazState.Listening -> Icons.Default.Mic
                is AwazState.Acting -> Icons.Default.Psychology
                is AwazState.NeedsHelp -> Icons.Default.Warning
            }

            Icon(
                imageVector = stateIcon,
                contentDescription = null,
                modifier = Modifier.size(130.dp),
                tint = currentState.iconColor
            )
        }

        // Floating debug button only in DEBUG builds
        if (BuildConfig.DEBUG) {
            FloatingActionButton(
                onClick = onOpenDebug,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp),
                containerColor = Color(0xFF334155),
                contentColor = Color(0xFFF59E0B)
            ) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = "Debug"
                )
            }
        }
    }
}

private fun triggerHapticFeedback(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator?.vibrate(
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        )
    } else {
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(50L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(50L)
        }
    }
}

private fun checkAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedServiceName = "${context.packageName}/com.awaz.app.service.AwazAccessibilityService"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServices)
    while (colonSplitter.hasNext()) {
        val componentName = colonSplitter.next()
        if (componentName.equals(expectedServiceName, ignoreCase = true)) {
            return true
        }
    }
    return false
}
