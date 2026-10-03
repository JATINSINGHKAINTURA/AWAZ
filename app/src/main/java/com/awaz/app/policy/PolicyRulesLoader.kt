package com.awaz.app.policy

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.InputStreamReader

/**
 * Android-side loader that parses assets/policy/*.json using kotlinx-serialization-json
 * and constructs an immutable PolicyRules instance for PolicyEngine.
 */
object PolicyRulesLoader {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun loadRules(context: Context): PolicyRules = loadFromAssets(context)

    fun loadFromAssets(context: Context): PolicyRules {
        val deniedPackages = runCatching {
            context.assets.open("policy/denylist.json").use { stream ->
                val text = InputStreamReader(stream).readText()
                json.decodeFromString<List<String>>(text).toSet()
            }
        }.getOrDefault(
            setOf(
                "com.phonepe.app",
                "com.google.android.apps.nbu.paisa.user",
                "net.one97.paytm",
                "in.org.npci.upiapp"
            )
        )

        val sensitiveTerms = runCatching {
            context.assets.open("policy/sensitive_terms.json").use { stream ->
                val text = InputStreamReader(stream).readText()
                json.decodeFromString<List<String>>(text)
            }
        }.getOrDefault(
            listOf(
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
            )
        )

        val deniedPackageFragments = listOf("bank", "upi", "wallet", "pay")

        val confirmationTerms = listOf(
            // English
            "send", "call", "share", "delete", "remove", "uninstall",
            "clear data", "grant", "permission", "factory reset", "reset",
            "purchase", "pay", "install", "buy",
            // Hindi
            "भेजें", "भेजो", "कॉल", "फ़ोन", "शेयर", "हटाएं", "हटाओ", "मिटाएं",
            "अनइन्स्टॉल", "डेटा साफ़", "अनुमति", "रीसेट", "खरीदें", "खरीदो", "भुगतान", "पैसे",
            // Hinglish
            "bhejo", "bhej", "call karo", "phone karo", "share karo", "hatao",
            "delete karo", "uninstall karo", "clear karo", "permission do",
            "khareedo", "pay karo", "payment"
        )

        return PolicyRules(
            deniedPackages = deniedPackages,
            deniedPackageFragments = deniedPackageFragments,
            sensitiveTerms = sensitiveTerms,
            confirmationTerms = confirmationTerms
        )
    }
}
