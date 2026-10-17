package com.yokhan.gates.shared.domain.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FailoverStateMachineTest {
    @Test
    fun tunnelConnectionDoesNotBecomeKnownGoodWithoutQualityEvidence() {
        val transition = reduceFailover(
            FailoverSnapshot(),
            FailoverEvent.TunnelConnected("bridge"),
        )

        assertEquals(FailoverState.Connected, transition.snapshot.state)
        assertEquals("", transition.snapshot.previousKnownGoodRouteId)
        assertTrue(transition.actions.isEmpty())
    }

    @Test
    fun twoSoftFailuresStartEvaluation() {
        val connected = FailoverSnapshot(state = FailoverState.Connected, currentRouteId = "bridge")
        val suspect = reduceFailover(
            connected,
            FailoverEvent.QualityObserved("bridge", ConnectionQualityClass.Degraded),
        ).snapshot
        val evaluating = reduceFailover(
            suspect,
            FailoverEvent.QualityObserved("bridge", ConnectionQualityClass.Stalled),
        )

        assertEquals(FailoverState.Suspect, suspect.state)
        assertEquals(FailoverState.Evaluating, evaluating.snapshot.state)
        assertEquals(FailoverActionType.EvaluateCandidate, evaluating.actions.single().type)
    }

    @Test
    fun hardFailureStartsEvaluationImmediately() {
        val transition = reduceFailover(
            FailoverSnapshot(state = FailoverState.Connected, currentRouteId = "bridge"),
            FailoverEvent.QualityObserved("bridge", ConnectionQualityClass.Down),
        )

        assertEquals(FailoverState.Evaluating, transition.snapshot.state)
    }

    @Test
    fun advisoryModeNeverRequestsApplySwitch() {
        val transition = reduceFailover(
            FailoverSnapshot(state = FailoverState.Evaluating, currentRouteId = "bridge"),
            FailoverEvent.CandidateSelected("naive", nowEpochSeconds = 1_000),
        )

        assertEquals(FailoverState.Evaluating, transition.snapshot.state)
        assertEquals(FailoverActionType.RecommendSwitch, transition.actions.single().type)
        assertFalse(transition.actions.any { it.type == FailoverActionType.ApplySwitch })
    }

    @Test
    fun failedCandidateRequestsRollbackAndQuarantine() {
        val transition = reduceFailover(
            FailoverSnapshot(
                state = FailoverState.Verifying,
                currentRouteId = "naive",
                candidateRouteId = "naive",
                previousKnownGoodRouteId = "bridge",
            ),
            FailoverEvent.VerifyFailed("naive"),
        )

        assertEquals(FailoverState.Rollback, transition.snapshot.state)
        assertEquals(
            listOf(FailoverActionType.Quarantine, FailoverActionType.Rollback),
            transition.actions.map { it.type },
        )
    }

    @Test
    fun softFailureRespectsCooldownInAutomaticMode() {
        val transition = reduceFailover(
            FailoverSnapshot(
                state = FailoverState.Evaluating,
                currentRouteId = "bridge",
                lastSwitchAtEpochSeconds = 900,
            ),
            FailoverEvent.CandidateSelected("naive", nowEpochSeconds = 1_000),
            FailoverPolicy(executionMode = FailoverExecutionMode.Automatic),
        )

        assertEquals(FailoverState.Evaluating, transition.snapshot.state)
        assertEquals(FailoverActionType.RecommendSwitch, transition.actions.single().type)
    }

    @Test
    fun hardFailureBypassesCooldownInAutomaticMode() {
        val transition = reduceFailover(
            FailoverSnapshot(
                state = FailoverState.Evaluating,
                currentRouteId = "bridge",
                lastSwitchAtEpochSeconds = 900,
                hasHardFailure = true,
            ),
            FailoverEvent.CandidateSelected("naive", nowEpochSeconds = 1_000),
            FailoverPolicy(executionMode = FailoverExecutionMode.Automatic),
        )

        assertEquals(FailoverState.Switching, transition.snapshot.state)
        assertEquals(FailoverActionType.ApplySwitch, transition.actions.single().type)
    }

    @Test
    fun failedCandidateWithoutKnownGoodFailsOffline() {
        val transition = reduceFailover(
            FailoverSnapshot(
                state = FailoverState.Verifying,
                currentRouteId = "naive",
                candidateRouteId = "naive",
            ),
            FailoverEvent.VerifyFailed("naive"),
        )

        assertEquals(FailoverState.Offline, transition.snapshot.state)
        assertEquals(listOf(FailoverActionType.Quarantine), transition.actions.map { it.type })
    }

    @Test
    fun verifiedCandidateBecomesKnownGood() {
        val transition = reduceFailover(
            FailoverSnapshot(
                state = FailoverState.Verifying,
                currentRouteId = "naive",
                candidateRouteId = "naive",
                previousKnownGoodRouteId = "bridge",
            ),
            FailoverEvent.VerifyPassed("naive"),
        )

        assertEquals(FailoverState.Connected, transition.snapshot.state)
        assertEquals("naive", transition.snapshot.previousKnownGoodRouteId)
        assertEquals(FailoverActionType.RecordKnownGood, transition.actions.single().type)
    }

    @Test
    fun reducerIsDeterministicForSameSequence() {
        val events = listOf<FailoverEvent>(
            FailoverEvent.TunnelConnected("bridge"),
            FailoverEvent.QualityObserved("bridge", ConnectionQualityClass.Degraded),
            FailoverEvent.QualityObserved("bridge", ConnectionQualityClass.Stalled),
            FailoverEvent.CandidateSelected("naive", 1_000),
        )

        fun replay(): FailoverSnapshot = events.fold(FailoverSnapshot()) { snapshot, event ->
            reduceFailover(snapshot, event).snapshot
        }

        assertEquals(replay(), replay())
    }
}
