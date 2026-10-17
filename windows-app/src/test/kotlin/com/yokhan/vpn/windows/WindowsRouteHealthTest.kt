package com.yokhan.gates.windows

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsRouteHealthTest {
    @Test
    fun advisoryPolicyBuildsSelectorInsteadOfActiveUrltest() {
        val snapshot = parseWindowsRouteHealthPayload(
            """{"recommendation":{"quality":{"evaluation_mode":"advisory_only","auto_switch_enabled":false}}}""",
        )

        val outbound = buildWindowsAutoOutbound(
            listOf("bridge", "naive"),
            snapshot,
            "https://example.invalid/generate_204",
        )

        assertEquals("selector", outbound.get("type").asString)
        assertEquals("bridge", outbound.get("default").asString)
    }

    @Test
    fun unsignedRouteHealthCannotEnableActiveUrltest() {
        val snapshot = parseWindowsRouteHealthPayload(
            """{"recommendation":{"quality":{"evaluation_mode":"automatic","auto_switch_enabled":true}}}""",
        )

        val outbound = buildWindowsAutoOutbound(
            listOf("bridge", "naive"),
            snapshot,
            "https://example.invalid/generate_204",
        )

        assertEquals("selector", outbound.get("type").asString)
    }


    @Test
    fun ordersRoutesByServerHealthWithoutTreatingFleetHintAsHardDisable() {
        val snapshot = parseWindowsRouteHealthPayload(
            """
            {
              "schema": "gates-route-health-v1",
              "routes": {
                "bridge": {"status": "down", "score": 0},
                "relay": {"status": "degraded", "score": 30},
                "naive": {"status": "healthy", "score": 90},
                "hy2": {"status": "healthy", "score": 80}
              },
              "recommendation": {
                "preferred_route": "naive",
                "avoid": ["bridge"]
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf("naive", "hy2", "relay", "bridge"),
            orderWindowsRoutesByHealth(listOf("bridge", "relay", "naive", "hy2"), snapshot),
        )
    }

    @Test
    fun keepsDeclaredOrderWhenHealthIsUnavailable() {
        assertEquals(
            listOf("bridge", "relay", "naive", "hy2"),
            orderWindowsRoutesByHealth(
                listOf("bridge", "relay", "naive", "hy2"),
                WindowsRouteHealthSnapshot(),
            ),
        )
    }

    @Test
    fun returnsPreferredRealRouteForAutoTelemetry() {
        val snapshot = parseWindowsRouteHealthPayload(
            """
            {
              "routes": {
                "bridge": {"status": "degraded", "score": 20},
                "relay": {"status": "healthy", "score": 95}
              },
              "recommendation": {"preferred_route": "relay"}
            }
            """.trimIndent(),
        )

        assertEquals(
            "relay",
            preferredWindowsRouteForTelemetry(listOf("bridge", "relay"), snapshot),
        )
    }

    @Test
    fun usesPreferredRouteOnlyAsTieBreakerAfterScore() {
        val snapshot = parseWindowsRouteHealthPayload(
            """
            {
              "routes": {
                "bridge": {"status": "healthy", "score": 95},
                "naive": {"status": "healthy", "score": 60}
              },
              "recommendation": {"preferred_route": "naive"}
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf("bridge", "naive"),
            orderWindowsRoutesByHealth(listOf("bridge", "naive"), snapshot),
        )
    }

    @Test
    fun detectsLastActuallyUsedRouteFromRuntimeLog() {
        val logs = """
            outbound/vless[bridge]: outbound connection to www.youtube.com:443
            outbound/hysteria2[hy2]: outbound connection to www.youtube.com:443
        """.trimIndent()

        assertEquals("hy2", detectWindowsRuntimeRoute(logs))
    }

    @Test
    fun doesNotInventRouteWhenRuntimeLogHasNoEvidence() {
        assertEquals("", detectWindowsRuntimeRoute("sing-box started"))
    }

    @Test
    fun recognizesNlTcpHealthAndRuntimeEvidenceButNotDirectBypass() {
        val snapshot = parseWindowsRouteHealthPayload(
            """{"routes":{"nl-tcp":{"status":"healthy","score":94},"direct":{"status":"healthy","score":100}},"recommendation":{"preferred_route":"nl-tcp","avoid":["direct"]}}""",
        )

        assertEquals(94, snapshot.scoreFor("nl-tcp"))
        assertEquals("nl-tcp", snapshot.preferredRoute)
        assertEquals(emptySet(), snapshot.avoidRoutes)
        assertEquals("nl-tcp", detectWindowsRuntimeRoute("outbound/vless[nl-tcp]: outbound connection"))
    }
}
