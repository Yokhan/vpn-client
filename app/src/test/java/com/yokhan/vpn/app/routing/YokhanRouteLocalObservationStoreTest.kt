package com.yokhan.gates.app.routing

import com.yokhan.vpn.shared.domain.SmartRouteCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class YokhanRouteLocalObservationStoreTest {

    @Test
    fun scopesStorageOnlyByCoarseNetworkBucketAndManifestFingerprint() {
        val cellularScope = YokhanRouteObservationScope("cellular", "manifest-a")
        val wifiScope = YokhanRouteObservationScope("wifi", "manifest-a")
        val changedManifestScope = YokhanRouteObservationScope("cellular", "manifest-b")

        assertNotEquals(cellularScope.storageKey(), wifiScope.storageKey())
        assertNotEquals(cellularScope.storageKey(), changedManifestScope.storageKey())
        assertFalse(cellularScope.storageKey().contains("cellular"))
        assertFalse(cellularScope.storageKey().contains("manifest-a"))
    }

    @Test
    fun mapsFreshObservationsToSharedCandidatesAndContext() {
        val state = YokhanRouteLocalObservationState()
            .recordConnectionSuccess("bridge", localScore = 82, nowEpochSeconds = NOW)
            .recordConnectionSuccess("bridge", localScore = 90, nowEpochSeconds = NOW + 1)
            .recordRouteSwitch(NOW + 2)
            .recordQuarantine(
                routeId = "naive",
                quarantineUntilEpochSeconds = NOW + QUARANTINE_DURATION_SECONDS,
                nowEpochSeconds = NOW + 3,
                localScore = 10,
            )

        val candidates = state.toSmartRouteCandidates(
            candidates = listOf(candidate("bridge"), candidate("naive")),
            nowEpochSeconds = NOW + 4,
        )
        val context = state.toSmartRouteContext(
            nowEpochSeconds = NOW + 4,
            currentRouteId = "bridge",
        )

        assertEquals(90, candidates.first { it.routeId == "bridge" }.localScore)
        assertEquals(2, candidates.first { it.routeId == "bridge" }.successfulProbeCount)
        assertEquals(NOW + QUARANTINE_DURATION_SECONDS, candidates.first { it.routeId == "naive" }.quarantineUntilEpochSeconds)
        assertEquals("bridge", context.previousKnownGoodRouteId)
        assertEquals(NOW + 1, context.previousKnownGoodRecordedAt)
        assertEquals(NOW + 2, context.lastSwitchAt)
        assertEquals(LOCAL_OBSERVATION_TTL_SECONDS, context.previousKnownGoodMaxAgeSeconds)
    }

    @Test
    fun expiresPreviousKnownGoodAndLocalCandidateStateAfterTwentyFourHours() {
        val state = YokhanRouteLocalObservationState()
            .recordConnectionSuccess("bridge", localScore = 90, nowEpochSeconds = NOW)
            .recordQuarantine(
                routeId = "naive",
                quarantineUntilEpochSeconds = NOW + QUARANTINE_DURATION_SECONDS,
                nowEpochSeconds = NOW,
                localScore = 10,
            )

        val candidates = state.toSmartRouteCandidates(
            candidates = listOf(candidate("bridge"), candidate("naive")),
            nowEpochSeconds = NOW + LOCAL_OBSERVATION_TTL_SECONDS,
        )
        val context = state.toSmartRouteContext(NOW + LOCAL_OBSERVATION_TTL_SECONDS)

        assertEquals(null, candidates.first { it.routeId == "bridge" }.localScore)
        assertEquals(0, candidates.first { it.routeId == "naive" }.quarantineUntilEpochSeconds)
        assertEquals("", context.previousKnownGoodRouteId)
        assertEquals(0, context.previousKnownGoodRecordedAt)
    }

    @Test
    fun prunesExpiredStateWithoutRemovingFreshObservations() {
        val state = YokhanRouteLocalObservationState()
            .recordConnectionSuccess("bridge", localScore = 90, nowEpochSeconds = NOW)
            .recordConnectionSuccess(
                "naive",
                localScore = 70,
                nowEpochSeconds = NOW + LOCAL_OBSERVATION_TTL_SECONDS - 1,
            )

        val pruned = state.pruneExpired(NOW + LOCAL_OBSERVATION_TTL_SECONDS)

        assertFalse("bridge" in pruned.observations)
        assertTrue("naive" in pruned.observations)
        assertEquals("naive", pruned.previousKnownGoodRouteId)
    }

    private fun candidate(routeId: String): SmartRouteCandidate = SmartRouteCandidate(
        routeId = routeId,
        declaredOrder = 0,
    )

    private companion object {
        const val NOW = 1_000L
        const val QUARANTINE_DURATION_SECONDS = 10L * 60L
    }
}
