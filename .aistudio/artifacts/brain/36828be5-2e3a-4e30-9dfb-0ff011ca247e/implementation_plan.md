# Implementation Plan: AWAZ Native Android Foundation

Build a production-grade, native Android foundation for **AWAZ** (`com.awaz.app`) using Kotlin, Jetpack Compose, and Material 3 (minSdk 26). AWAZ is a voice-first assistant engineered for elderly, rural, and non-literate users with zero text in release UI builds (strictly high-contrast colors and iconography).

---

## 1. Project Architecture & Directory Layout

The app is structured as a standard modern Android Gradle project with dual product flavors (`standard` vs `a11yTool`):

```
android/
├── build.gradle.kts                         // Top-level build configuration
├── settings.gradle.kts                      // Root project & app module declaration
├── gradle.properties
└── app/
    ├── build.gradle.kts                     // Android plugins, Compose, flavors, JUnit dependencies
    ├── src/
    │   ├── main/
    │   │   ├── AndroidManifest.xml          // Permissions, Services, Activities
    │   │   ├── assets/
    │   │   │   └── policy/
    │   │   │       ├── denylist.json        // Seeded financial & sensitive package names
    │   │   │       └── sensitive_terms.json // Multilingual keywords (OTP, पिन, password, etc.)
    │   │   ├── java/com/awaz/app/
    │   │   │   ├── domain/
    │   │   │   │   ├── PolicyEngine.kt      // Pure Kotlin rules engine (zero Android framework imports)
    │   │   │   │   └── Models.kt            // PolicyContext, AgentAction, Decision, AwazState
    │   │   │   ├── service/
    │   │   │   │   ├── AwazAccessibilityService.kt // Screen snapshotting, gesture dispatcher, Volume Up hook
    │   │   │   │   ├── ConfirmationOverlay.kt      // TYPE_ACCESSIBILITY_OVERLAY green tick/red cross
    │   │   │   │   ├── VoiceForegroundService.kt   // Isolated microphone foreground service
    │   │   │   │   └── EventBus.kt                 // In-app Kotlin Coroutines SharedFlow event bus
    │   │   │   ├── ui/
    │   │   │   │   ├── MainActivity.kt             // Full-screen Compose host, debug screen switcher
    │   │   │   │   ├── MainButtonScreen.kt         // Giant mic button, 4-state color/icon transitions
    │   │   │   │   ├── DebugInspectorScreen.kt     // DEBUG build only: policy log, 3s screen dump, overlay test
    │   │   │   │   ├── FirstRunScreen.kt           // Icon-only guide with arrow to Accessibility Settings
    │   │   │   │   └── theme/
    │   │   │   │       ├── Color.kt                // High-contrast elderly palettes
    │   │   │   │       ├── Theme.kt                // Material 3 Dynamic / Accessible theme
    │   │   │   │       └── Shape.kt
    │   │   │   └── util/
    │   │   │       └── HapticFeedbackUtil.kt       // Distinct tactile patterns for each state change
    │   │   └── res/
    │   │       ├── drawable/                   // Vector icons: mic, listening, acting, warning, tick, cross, arrow
    │   │       ├── values/
    │   │       │   └── strings.xml             // App name only (zero user-facing text in release Compose)
    │   │       └── xml/
    │   ├── standard/
    │   │   └── res/xml/
    │   │       └── accessibility_service_config.xml // Default flavor (android:isAccessibilityTool omitted)
    │   ├── a11yTool/
    │   │   └── res/xml/
    │   │       └── accessibility_service_config.xml // a11yTool flavor (android:isAccessibilityTool="true")
    │   └── test/
    │       └── java/com/awaz/app/
    │           └── domain/
    │               └── PolicyEngineTest.kt     // 100% pure JUnit test suite verifying all security rules
```

---

## 2. Component Implementation Details

### A. Pure Kotlin Policy Engine (`PolicyEngine.kt`)
- **Strict Separation**: Zero `android.*` imports. Fully unit-testable on JVM without Robolectric or mocks.
- **Data Models**:
  - `PolicyContext`: `foregroundPackage: String`, `screenElements: List<ScreenElementInfo>`, `hasPasswordField: Boolean`, `actionsTakenCount: Int`, `lastActionTimestampMs: Long`, `consecutiveActionCount: Int`, `lastAction: AgentAction?`.
  - `AgentAction`: `Click(elementId, label)`, `Scroll(direction)`, `GlobalAction(type)`, `TypeText(text)`.
  - `Decision`:
    - `Allow`
    - `RequireConfirmation(code: ConfirmationCode, reason: String)`
    - `Block(code: BlockCode, reason: String)`
    - `HandOffToHuman(code: SafetyCode, reason: String)`
- **Rule Pipeline**:
  1. **Financial Package Check**: Matches exact denylist (`com.phonepe.app`, `com.google.android.apps.nbu.paisa.user`, `net.one97.paytm`, `in.org.npci.upiapp`) + regex heuristic (`bank`, `upi`, `wallet`, `pay`). Triggers `HandOffToHuman(FINANCIAL_APP)`.
  2. **Password & Sensitive Term Check**: If `hasPasswordField == true` or any screen label matches normalized sensitive terms (case-insensitive, Unicode `NFKD` normalized: `OTP`, `ओटीपी`, `PIN`, `पिन`, `CVV`, `password`, `पासवर्ड`, `verification code`, `UPI PIN`, `एटीएम`), triggers `HandOffToHuman(SENSITIVE_DATA_DETECTED)`.
  3. **High-Stakes Action Check**: Actions modifying system state, calling, sharing, deleting, uninstalling, clearing data, granting permissions, paying, purchasing, factory reset. Evaluated on action type AND element label in English, Hindi, and Hinglish (e.g., `Delete`, `हटाएं`, `भेजें`, `Send`, `Call`, `कॉल`, `साझा`, `Share`, `Install`, `खरीदें`). Triggers `RequireConfirmation`.
  4. **Rate Limits & Boundaries**:
     - Maximum 12 actions per task (`Block(STEP_LIMIT)`).
     - Maximum 2 identical consecutive actions (`Block(CONSECUTIVE_LOOP)`).
     - Minimum 300ms gesture throttle interval (`Block(RATE_LIMIT)`).
  5. Default fallback: `Allow`.

### B. Comprehensive JUnit Test Suite (`PolicyEngineTest.kt`)
Verification of core security invariants:
1. `testUpiPackageBlocked_returnsHandOffToHuman`: Verifies `com.phonepe.app` and `net.one97.paytm`.
2. `testDevanagariOtpScreen_returnsHandOffToHuman`: Element containing "ओटीपी दर्ज करें" returns `HandOffToHuman`.
3. `testPasswordField_returnsBlock`: Screen with `isPassword = true` blocked.
4. `testDeleteContact_returnsRequireConfirmation`: Label "Delete contact" returns `RequireConfirmation`.
5. `testHindiSendButton_returnsRequireConfirmation`: Label "भेजें" returns `RequireConfirmation`.
6. `testStepLimit_13thAction_returnsBlock`: Actions count = 12 attempting 13th action returns `Block(STEP_LIMIT)`.
7. `testConsecutiveActionLimit_returnsBlock`: Third identical action blocked.
8. `testGestureThrottling_under300ms_returnsBlock`: Fast repeated gestures blocked.
9. `testNormalSettingsScreen_returnsAllow`: Safe settings toggles return `Allow`.

### C. Accessibility Service (`AwazAccessibilityService.kt`)
- **Service Configuration**:
  - `canRetrieveWindowContent = true`
  - `canPerformGestures = true`
  - `flags = FLAG_REPORT_VIEW_IDS | FLAG_RETRIEVE_INTERACTIVE_WINDOWS | FLAG_REQUEST_FILTER_KEY_EVENTS`
- **Product Flavors**:
  - `standard`: standard accessibility config without `isAccessibilityTool`.
  - `a11yTool`: includes `android:isAccessibilityTool="true"`.
- **Core Methods**:
  - `snapshot()`: Traverses active window root node, extracts up to 60 visible interactive elements into `ScreenSnapshot` (id, label max 60 chars from text -> contentDescription -> hintText, role, clickable, enabled, checked, isPassword, boundsCenter). Safely recycles every `AccessibilityNodeInfo`.
  - `clickById(id)`: Evaluates `PolicyEngine.evaluate()` first. If allowed, attempts `node.performAction(ACTION_CLICK)`. If unhandled, traverses up to 3 ancestors. If still unhandled, dispatches `GestureDescription` click at `boundsCenter`.
  - `scroll(direction)`: Evaluates policy, dispatches forward/backward scroll or swipe gesture.
  - `performGlobalAction(...)`: Back, Home, Recents, Notifications.
  - `onKeyEvent`: Tracks `KEYCODE_VOLUME_UP`. If held for >= 800ms, posts `TriggerEvent` to `EventBus` and consumes event (`return true`); otherwise passes through.
  - Never calls `startForeground()` (strictly isolated).

### D. Confirmation Overlay (`ConfirmationOverlay.kt`)
- Full-screen `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY`.
- Pure iconography & high-contrast colors:
  - Giant Green Tick icon (Confirm).
  - Giant Red Cross icon (Deny).
- Independent 20-second countdown timer defaulting to `DENIED` on timeout.
- Returns `ConfirmationResult { CONFIRMED, DENIED, TIMEOUT }` via callback.

### E. Voice Foreground Service (`VoiceForegroundService.kt`)
- Distinct Android Service with `android:foregroundServiceType="microphone"`.
- Started and stopped exclusively from `MainActivity`.
- Manages low-importance ongoing notification (minimal icon-only notification).
- Contains permissions: `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `INTERNET`.

### F. Compose UI (Release vs Debug)
- **Zero Text in Release**:
  - `AwazState`:
    - `Idle`: Deep charcoal / midnight blue background, calm white microphone icon.
    - `Listening`: Vibrant electric amber / pulsing ring, active wave icon.
    - `Acting`: Calming emerald green background, gear / motion icon.
    - `NeedsHelp`: High-contrast amber-orange / safety red, hand / assistance icon.
  - Giant full-screen touch target button.
  - Custom haptic feedback patterns for each state change via `Vibrator` / `HapticFeedbackConstants`.
- **First-Run / Setup Experience**:
  - Checks `isAccessibilityServiceEnabled()`. If disabled, displays icon-only guide showing an animated arrow pointing to the Accessibility icon and a large gear button opening `Settings.ACTION_ACCESSIBILITY_SETTINGS`.
- **Debug Build Features (`BuildConfig.DEBUG`)**:
  - Secondary screen switchable in debug builds.
  - (a) Live text list of recent `PolicyEngine` evaluations and security decisions.
  - (b) "Dump Screen" button: counts down 3 seconds, captures foreground snapshot, formats as formatted JSON, displays in scrollable panel, and outputs to Logcat (`tag: AwazA11y`).
  - (c) "Test Confirmation" button: invokes `ConfirmationOverlay` directly to test interaction.

---

## 3. Verification & Acceptance Plan
1. **JUnit Unit Tests**: Run `PolicyEngineTest` to verify all denylists, Devanagari sensitive tokens, password detections, and rate throttles.
2. **Code Completeness**: Zero placeholders or TODOs. All Android classes, resources, XML descriptors, and Gradle scripts fully fleshed out.
3. **Flavors Verification**: Verify `standard` and `a11yTool` source sets and XML configurations.
4. **Applet Compilation**: Ensure workspace builds cleanly.
