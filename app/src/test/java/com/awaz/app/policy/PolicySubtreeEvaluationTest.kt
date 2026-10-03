package com.awaz.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PolicySubtreeEvaluationTest {

    private lateinit var policyEngine: PolicyEngine

    @Before
    fun setUp() {
        val rules = PolicyRules(
            deniedPackages = setOf("com.phonepe.app", "net.one97.paytm"),
            deniedPackageFragments = listOf("bank", "upi", "wallet", "pay"),
            sensitiveTerms = listOf("OTP", "PIN", "password", "CVV"),
            confirmationTerms = listOf("Delete", "Send", "Pay", "Uninstall")
        )
        policyEngine = PolicyEngine(rules)
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

    @Test
    fun tap_childLabelledSettings_withClickableParentSubtreeContainingDelete_returnsRequireConfirmation() {
        val context = PolicyContext(foregroundPackage = "com.android.settings")

        // Original child node label: "Settings"
        val childAction = AgentAction(
            type = AgentActionType.Click,
            targetLabel = "Settings",
            timestampMs = 1000L
        )
        val childDecision = policyEngine.evaluate(context, childAction)
        assertEquals(Decision.Allow, childDecision)

        // Clickable ancestor subtree text includes "Delete account"
        val ancestorSubtreeAction = AgentAction(
            type = AgentActionType.Click,
            targetLabel = "Settings Delete account",
            timestampMs = 1000L
        )
        val subtreeDecision = policyEngine.evaluate(context, ancestorSubtreeAction)
        assertTrue(subtreeDecision is Decision.RequireConfirmation)

        // Stricter decision must be RequireConfirmation
        val finalDecision = stricterOf(childDecision, subtreeDecision)
        assertTrue(finalDecision is Decision.RequireConfirmation)
    }

    @Test
    fun tap_childLabelledSettings_withAncestorSubtreeContainingOTP_isBlocked() {
        val context = PolicyContext(foregroundPackage = "com.example.messaging")

        // Original child node label: "Settings"
        val childAction = AgentAction(
            type = AgentActionType.Click,
            targetLabel = "Settings",
            timestampMs = 1000L
        )
        val childDecision = policyEngine.evaluate(context, childAction)
        assertEquals(Decision.Allow, childDecision)

        // Clickable ancestor subtree text includes "Your OTP is 123456"
        val ancestorSubtreeAction = AgentAction(
            type = AgentActionType.Click,
            targetLabel = "Settings Your OTP is 123456",
            timestampMs = 1000L
        )
        val subtreeDecision = policyEngine.evaluate(context, ancestorSubtreeAction)
        assertTrue(subtreeDecision is Decision.Block)

        // Stricter decision must be Block
        val finalDecision = stricterOf(childDecision, subtreeDecision)
        assertTrue(finalDecision is Decision.Block)
    }

    @Test
    fun tap_childAndParentSubtreeBenign_returnsAllow() {
        val context = PolicyContext(foregroundPackage = "com.android.settings")

        val childAction = AgentAction(
            type = AgentActionType.Click,
            targetLabel = "Display",
            timestampMs = 1000L
        )
        val childDecision = policyEngine.evaluate(context, childAction)
        assertEquals(Decision.Allow, childDecision)

        val ancestorSubtreeAction = AgentAction(
            type = AgentActionType.Click,
            targetLabel = "Display Brightness level",
            timestampMs = 1000L
        )
        val subtreeDecision = policyEngine.evaluate(context, ancestorSubtreeAction)
        assertEquals(Decision.Allow, subtreeDecision)

        val finalDecision = stricterOf(childDecision, subtreeDecision)
        assertEquals(Decision.Allow, finalDecision)
    }
}
