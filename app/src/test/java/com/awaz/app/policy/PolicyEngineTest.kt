package com.awaz.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PolicyEngineTest {

    private lateinit var policyEngine: PolicyEngine
    private val loggedMessages = mutableListOf<String>()

    @Before
    fun setUp() {
        val rules = PolicyRules(
            deniedPackages = setOf(
                "com.phonepe.app",
                "com.google.android.apps.nbu.paisa.user",
                "net.one97.paytm",
                "in.org.npci.upiapp"
            ),
            deniedPackageFragments = listOf("bank", "upi", "wallet", "pay"),
            sensitiveTerms = listOf(
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
            ),
            confirmationTerms = listOf(
                "send", "call", "share", "delete", "remove", "uninstall",
                "clear data", "grant", "allow permission", "permission",
                "factory reset", "reset", "purchase", "pay", "install", "buy",
                "भेजें", "भेजो", "कॉल", "फ़ोन", "शेयर", "हटाएं", "हटाओ", "मिटाएं",
                "अनइन्स्टॉल", "डेटा साफ़", "अनुमति", "रीसेट", "खरीदें", "खरीदो", "भुगतान", "पैसे",
                "bhejo", "bhej", "call karo", "phone karo", "share karo", "hatao",
                "delete karo", "uninstall karo", "clear karo", "permission do",
                "khareedo", "pay karo", "payment"
            )
        )
        policyEngine = PolicyEngine(rules) { msg -> loggedMessages.add(msg) }
    }

    @Test
    fun testUpiPackageBlocked_returnsHandOffToHuman() {
        val ctx = PolicyContext(
            foregroundPackage = "in.org.npci.upiapp",
            elements = listOf(ScreenElement(id = 1, label = "Enter UPI PIN", clickable = true)),
            actionsTakenCount = 1
        )
        val action = AgentAction(
            type = AgentActionType.Click,
            targetElementId = 1,
            targetLabel = "Enter UPI PIN"
        )
        val decision = policyEngine.evaluate(ctx, action)
        assertTrue(decision is Decision.HandOffToHuman)
    }

    @Test
    fun testHdfcBankPayzapp_returnsHandOffToHuman() {
        val ctx = PolicyContext(
            foregroundPackage = "com.hdfcbank.payzapp",
            elements = listOf(ScreenElement(id = 1, label = "Proceed", clickable = true)),
            actionsTakenCount = 0
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 1)
        val decision = policyEngine.evaluate(ctx, action)
        assertTrue(decision is Decision.HandOffToHuman)
    }

    @Test
    fun testSamsungSpay_returnsHandOffToHuman() {
        val ctx = PolicyContext(
            foregroundPackage = "com.samsung.android.spay",
            elements = listOf(ScreenElement(id = 1, label = "Card", clickable = true)),
            actionsTakenCount = 0
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 1)
        val decision = policyEngine.evaluate(ctx, action)
        assertTrue(decision is Decision.HandOffToHuman)
    }

    @Test
    fun testGoogleMaps_returnsAllow() {
        val ctx = PolicyContext(
            foregroundPackage = "com.google.android.apps.maps",
            elements = listOf(ScreenElement(id = 1, label = "Directions", clickable = true)),
            actionsTakenCount = 0
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 1, targetLabel = "Directions")
        val decision = policyEngine.evaluate(ctx, action)
        assertEquals(Decision.Allow, decision)
    }

    @Test
    fun testWallpaperLivePicker_returnsAllow() {
        val ctx = PolicyContext(
            foregroundPackage = "com.android.wallpaper.livepicker",
            elements = listOf(ScreenElement(id = 1, label = "Apply", clickable = true)),
            actionsTakenCount = 0
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 1, targetLabel = "Apply")
        val decision = policyEngine.evaluate(ctx, action)
        assertEquals(Decision.Allow, decision)
    }

    @Test
    fun testAndroidVending_returnsAllow() {
        val ctx = PolicyContext(
            foregroundPackage = "com.android.vending",
            elements = listOf(ScreenElement(id = 1, label = "Top Charts", clickable = true)),
            actionsTakenCount = 0
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 1, targetLabel = "Top Charts")
        val decision = policyEngine.evaluate(ctx, action)
        assertEquals(Decision.Allow, decision)
    }

    @Test
    fun testAndroidSettings_returnsAllow() {
        val ctx = PolicyContext(
            foregroundPackage = "com.android.settings",
            elements = listOf(ScreenElement(id = 1, label = "Display", clickable = true)),
            actionsTakenCount = 0
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 1, targetLabel = "Display")
        val decision = policyEngine.evaluate(ctx, action)
        assertEquals(Decision.Allow, decision)
    }

    @Test
    fun testAndroidChrome_returnsAllow() {
        val ctx = PolicyContext(
            foregroundPackage = "com.android.chrome",
            elements = listOf(ScreenElement(id = 1, label = "New tab", clickable = true)),
            actionsTakenCount = 0
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 1, targetLabel = "New tab")
        val decision = policyEngine.evaluate(ctx, action)
        assertEquals(Decision.Allow, decision)
    }

    @Test
    fun testWhatsApp_returnsAllow() {
        val ctx = PolicyContext(
            foregroundPackage = "com.whatsapp",
            elements = listOf(ScreenElement(id = 1, label = "Chats", clickable = true)),
            actionsTakenCount = 0
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 1, targetLabel = "Chats")
        val decision = policyEngine.evaluate(ctx, action)
        assertEquals(Decision.Allow, decision)
    }

    @Test
    fun testGoogleDialer_returnsAllow() {
        val ctx = PolicyContext(
            foregroundPackage = "com.google.android.dialer",
            elements = listOf(ScreenElement(id = 1, label = "Favorites", clickable = true)),
            actionsTakenCount = 0
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 1, targetLabel = "Favorites")
        val decision = policyEngine.evaluate(ctx, action)
        assertEquals(Decision.Allow, decision)
    }

    @Test
    fun testDevanagariOtpScreen_returnsHandOffToHuman() {
        val ctx = PolicyContext(
            foregroundPackage = "com.example.app",
            elements = listOf(
                ScreenElement(id = 10, label = "कृपया अपना ओटीपी दर्ज करें", clickable = false),
                ScreenElement(id = 11, label = "पुष्टि करें", clickable = true)
            ),
            actionsTakenCount = 2
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 11, targetLabel = "पुष्टि करें")
        val decision = policyEngine.evaluate(ctx, action)
        assertTrue(decision is Decision.HandOffToHuman)
    }

    @Test
    fun testPasswordField_returnsBlock() {
        val ctx = PolicyContext(
            foregroundPackage = "com.example.notes",
            elements = listOf(
                ScreenElement(id = 5, label = "Enter Secret Key", isPassword = true),
                ScreenElement(id = 6, label = "Unlock", clickable = true)
            ),
            actionsTakenCount = 1
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 6, targetLabel = "Unlock")
        val decision = policyEngine.evaluate(ctx, action)
        assertTrue(decision is Decision.Block)
        assertEquals("PASSWORD_FIELD_DETECTED", (decision as Decision.Block).code)
    }

    @Test
    fun testPinTokenMatches_EnterPin_returnsHandOffToHuman() {
        val ctx = PolicyContext(
            foregroundPackage = "com.example.app",
            elements = listOf(ScreenElement(id = 1, label = "Enter PIN", clickable = true)),
            actionsTakenCount = 1
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 1, targetLabel = "Enter PIN")
        val decision = policyEngine.evaluate(ctx, action)
        assertTrue(decision is Decision.HandOffToHuman)
    }

    @Test
    fun testPinTokenMatches_DevanagariPin_returnsHandOffToHuman() {
        val ctx = PolicyContext(
            foregroundPackage = "com.example.app",
            elements = listOf(ScreenElement(id = 1, label = "पिन डालें", clickable = true)),
            actionsTakenCount = 1
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 1, targetLabel = "पिन डालें")
        val decision = policyEngine.evaluate(ctx, action)
        assertTrue(decision is Decision.HandOffToHuman)
    }

    @Test
    fun testShoppingNegativeToken_returnsAllow() {
        val ctx = PolicyContext(
            foregroundPackage = "com.example.store",
            elements = listOf(ScreenElement(id = 1, label = "Shopping Cart", clickable = true)),
            actionsTakenCount = 1
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 1, targetLabel = "Shopping Cart")
        val decision = policyEngine.evaluate(ctx, action)
        assertEquals(Decision.Allow, decision)
    }

    @Test
    fun testPinterestNegativeToken_returnsAllow() {
        val ctx = PolicyContext(
            foregroundPackage = "com.example.social",
            elements = listOf(ScreenElement(id = 1, label = "Pinterest Boards", clickable = true)),
            actionsTakenCount = 1
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 1, targetLabel = "Pinterest Boards")
        val decision = policyEngine.evaluate(ctx, action)
        assertEquals(Decision.Allow, decision)
    }

    @Test
    fun testMappingNegativeToken_returnsAllow() {
        val ctx = PolicyContext(
            foregroundPackage = "com.example.gis",
            elements = listOf(ScreenElement(id = 1, label = "Mapping Tools", clickable = true)),
            actionsTakenCount = 1
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 1, targetLabel = "Mapping Tools")
        val decision = policyEngine.evaluate(ctx, action)
        assertEquals(Decision.Allow, decision)
    }

    @Test
    fun testHotspotNegativeToken_returnsAllow() {
        val ctx = PolicyContext(
            foregroundPackage = "com.android.settings",
            elements = listOf(ScreenElement(id = 1, label = "Hotspot", clickable = true)),
            actionsTakenCount = 1
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 1, targetLabel = "Hotspot")
        val decision = policyEngine.evaluate(ctx, action)
        assertEquals(Decision.Allow, decision)
    }

    @Test
    fun testPersonalHotspotScreen_returnsAllow() {
        val ctx = PolicyContext(
            foregroundPackage = "com.android.settings",
            elements = listOf(ScreenElement(id = 1, label = "Personal hotspot", clickable = true)),
            actionsTakenCount = 1
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 1, targetLabel = "Personal hotspot")
        val decision = policyEngine.evaluate(ctx, action)
        assertEquals(Decision.Allow, decision)
    }

    @Test
    fun testWifiScreen_returnsAllow() {
        val ctx = PolicyContext(
            foregroundPackage = "com.android.settings",
            elements = listOf(ScreenElement(id = 1, label = "Wi-Fi", clickable = true)),
            actionsTakenCount = 1
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 1, targetLabel = "Wi-Fi")
        val decision = policyEngine.evaluate(ctx, action)
        assertEquals(Decision.Allow, decision)
    }

    @Test
    fun testDeleteContact_returnsRequireConfirmation() {
        val ctx = PolicyContext(
            foregroundPackage = "com.google.android.contacts",
            elements = listOf(ScreenElement(id = 20, label = "Delete contact", clickable = true)),
            actionsTakenCount = 3
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 20, targetLabel = "Delete contact")
        val decision = policyEngine.evaluate(ctx, action)
        assertTrue(decision is Decision.RequireConfirmation)
    }

    @Test
    fun testHindiSendButton_returnsRequireConfirmation() {
        val ctx = PolicyContext(
            foregroundPackage = "com.whatsapp",
            elements = listOf(ScreenElement(id = 30, label = "भेजें", clickable = true)),
            actionsTakenCount = 4
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 30, targetLabel = "भेजें")
        val decision = policyEngine.evaluate(ctx, action)
        assertTrue(decision is Decision.RequireConfirmation)
    }

    @Test
    fun testThirteenthAction_returnsBlockStepLimit() {
        val ctx = PolicyContext(
            foregroundPackage = "com.android.settings",
            elements = listOf(ScreenElement(id = 40, label = "Bluetooth", clickable = true)),
            actionsTakenCount = 12
        )
        val action = AgentAction(type = AgentActionType.Click, targetElementId = 40, targetLabel = "Bluetooth")
        val decision = policyEngine.evaluate(ctx, action)
        assertTrue(decision is Decision.Block)
        assertEquals("STEP_LIMIT", (decision as Decision.Block).code)
    }
}
