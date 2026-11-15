package com.yokhan.gates.app.routing

import kotlin.test.Test
import kotlin.test.assertEquals

class RuCompatibilityClassifierTest {

    private val emptyRules = RuCompatibilityClassifier.RuCompatibilityRules(
        exactDirect = setOf("ru.sberbankmobile"),
        prefixDirect = setOf("ru.yandex.", "com.tbank."),
    )

    @Test
    fun matchesExactPrefixDomainBrandAndRuStore() {
        val exact = RuCompatibilityClassifier.classify(
            packageName = "ru.sberbankmobile",
            rules = emptyRules,
            enableRuHeuristics = true,
            installerPackage = null,
        )
        assertEquals(RuCompatibilityRoute.Direct, exact.route)
        assertEquals(RuCompatibilityReason.ExactRule, exact.reason)

        val domainSegment = RuCompatibilityClassifier.classify(
            packageName = "com.wildberries.ru",
            rules = emptyRules,
            enableRuHeuristics = true,
            installerPackage = null,
        )
        assertEquals(RuCompatibilityRoute.Direct, domainSegment.route)
        assertEquals(RuCompatibilityReason.DomainSegmentRule, domainSegment.reason)

        val brand = RuCompatibilityClassifier.classify(
            packageName = "com.example.mirpay.wallet",
            rules = emptyRules,
            enableRuHeuristics = true,
            installerPackage = null,
        )
        assertEquals(RuCompatibilityRoute.Direct, brand.route)
        assertEquals(RuCompatibilityReason.BrandSignatureRule, brand.reason)

        val ruStore = RuCompatibilityClassifier.classify(
            packageName = "com.vendor.customapp",
            rules = emptyRules,
            enableRuHeuristics = true,
            installerPackage = RuCompatibilityClassifier.RUSTORE_INSTALLER_PACKAGE,
        )
        assertEquals(RuCompatibilityRoute.Direct, ruStore.route)
        assertEquals(RuCompatibilityReason.RuStoreInstaller, ruStore.reason)
    }

    @Test
    fun doesNotFalselyBypassSystemAndGlobalMessagingApps() {
        for (pkg in listOf("com.android.chrome", "com.google.android.gms", "org.telegram.messenger", "com.yokhan.gates.debug")) {
            val decision = RuCompatibilityClassifier.classify(
                packageName = pkg,
                rules = emptyRules,
                enableRuHeuristics = true,
                installerPackage = RuCompatibilityClassifier.RUSTORE_INSTALLER_PACKAGE,
            )
            assertEquals(RuCompatibilityRoute.Tunnel, decision.route, "Expected $pkg to stay in tunnel")
            assertEquals(RuCompatibilityReason.DefaultRoute, decision.reason)
        }
    }
}
