package com.yokhan.vpn.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmartRouteSelectorTest {
    @Test
    fun neverRestoresHardDisabledRoutes() {
        val decision = selectSmartRoute(
            listOf(
                candidate("bridge", 0, fleet = 100, isHardDisabled = true),
                candidate("naive", 1, fleet = 40),
            ),
            SmartRouteContext(nowEpochSeconds = 1000),
        )

        assertEquals("naive", decision.selectedRouteId)
        assertEquals(listOf("naive"), decision.orderedRouteIds)
    }

    @Test
    fun returnsNoRouteWhenEveryCandidateIsDisabled() {
        val decision = selectSmartRoute(
            listOf(candidate("bridge", 0, isHardDisabled = true)),
            SmartRouteContext(nowEpochSeconds = 1000),
        )

        assertNull(decision.selectedRouteId)
    }

    @Test
    fun retainsWorkingRouteForMarginalImprovement() {
        val decision = selectSmartRoute(
            listOf(
                candidate("bridge", 0, local = 70),
                candidate("naive", 1, local = 80, successes = 2),
            ),
            SmartRouteContext(nowEpochSeconds = 1000, currentRouteId = "bridge"),
        )

        assertEquals("bridge", decision.selectedRouteId)
    }

    @Test
    fun switchesAfterMaterialImprovementAndTwoSuccessfulProbes() {
        val decision = selectSmartRoute(
            listOf(
                candidate("bridge", 0, local = 40),
                candidate("naive", 1, local = 90, successes = 2),
            ),
            SmartRouteContext(nowEpochSeconds = 1000, currentRouteId = "bridge"),
        )

        assertEquals("naive", decision.selectedRouteId)
    }

    @Test
    fun retainsPreviousKnownGoodDuringCooldown() {
        val decision = selectSmartRoute(
            listOf(
                candidate("bridge", 0, local = 40),
                candidate("naive", 1, local = 100, successes = 2),
            ),
            SmartRouteContext(
                nowEpochSeconds = 1000,
                previousKnownGoodRouteId = "bridge",
                previousKnownGoodRecordedAt = 900,
                lastSwitchAt = 900,
            ),
        )

        assertEquals("bridge", decision.selectedRouteId)
    }

    private fun candidate(
        id: String,
        order: Int,
        local: Int? = null,
        fleet: Int? = null,
        isHardDisabled: Boolean = false,
        successes: Int = 0,
    ) = SmartRouteCandidate(
        routeId = id,
        declaredOrder = order,
        localScore = local,
        fleetScore = fleet,
        isHardDisabled = isHardDisabled,
        successfulProbeCount = successes,
    )
}
