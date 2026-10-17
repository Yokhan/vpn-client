package com.yokhan.gates.shared.domain.routing

enum class FailoverState {
    Connected,
    Suspect,
    Evaluating,
    Switching,
    Verifying,
    Rollback,
    Offline,
}

enum class FailoverExecutionMode {
    Advisory,
    Automatic,
}

enum class FailoverActionType {
    EvaluateCandidate,
    RecommendSwitch,
    ApplySwitch,
    VerifyCandidate,
    RecordKnownGood,
    Rollback,
    Quarantine,
}

data class FailoverAction(
    val type: FailoverActionType,
    val routeId: String = "",
)

data class FailoverPolicy(
    val executionMode: FailoverExecutionMode = FailoverExecutionMode.Advisory,
    val softFailuresRequired: Int = DEFAULT_SOFT_FAILURES_REQUIRED,
    val switchCooldownSeconds: Long = DEFAULT_SWITCH_COOLDOWN_SECONDS,
)

data class FailoverSnapshot(
    val state: FailoverState = FailoverState.Offline,
    val currentRouteId: String = "",
    val candidateRouteId: String = "",
    val previousKnownGoodRouteId: String = "",
    val consecutiveSoftFailures: Int = 0,
    val lastSwitchAtEpochSeconds: Long = 0,
    val hasHardFailure: Boolean = false,
)

sealed interface FailoverEvent {
    data class TunnelConnected(val routeId: String) : FailoverEvent
    data object TunnelDisconnected : FailoverEvent
    data class QualityObserved(
        val routeId: String,
        val qualityClass: ConnectionQualityClass,
    ) : FailoverEvent
    data class CandidateSelected(val routeId: String, val nowEpochSeconds: Long) : FailoverEvent
    data class SwitchApplied(val routeId: String, val nowEpochSeconds: Long) : FailoverEvent
    data class VerifyPassed(val routeId: String) : FailoverEvent
    data class VerifyFailed(val routeId: String) : FailoverEvent
}

data class FailoverTransition(
    val snapshot: FailoverSnapshot,
    val actions: List<FailoverAction> = emptyList(),
)

fun reduceFailover(
    snapshot: FailoverSnapshot,
    event: FailoverEvent,
    policy: FailoverPolicy = FailoverPolicy(),
): FailoverTransition = when (event) {
    is FailoverEvent.TunnelConnected -> onTunnelConnected(snapshot, event)
    FailoverEvent.TunnelDisconnected -> FailoverTransition(FailoverSnapshot())
    is FailoverEvent.QualityObserved -> onQualityObserved(snapshot, event, policy)
    is FailoverEvent.CandidateSelected -> onCandidateSelected(snapshot, event, policy)
    is FailoverEvent.SwitchApplied -> onSwitchApplied(snapshot, event)
    is FailoverEvent.VerifyPassed -> onVerifyPassed(snapshot, event)
    is FailoverEvent.VerifyFailed -> onVerifyFailed(snapshot, event)
}

private fun onTunnelConnected(
    snapshot: FailoverSnapshot,
    event: FailoverEvent.TunnelConnected,
): FailoverTransition = FailoverTransition(
    snapshot.copy(
        state = FailoverState.Connected,
        currentRouteId = event.routeId,
        candidateRouteId = "",
        consecutiveSoftFailures = 0,
        hasHardFailure = false,
    ),
)

private fun onQualityObserved(
    snapshot: FailoverSnapshot,
    event: FailoverEvent.QualityObserved,
    policy: FailoverPolicy,
): FailoverTransition {
    if (event.routeId != snapshot.currentRouteId) return FailoverTransition(snapshot)
    return when (event.qualityClass) {
        ConnectionQualityClass.Healthy -> verifiedCurrentRoute(snapshot)
        ConnectionQualityClass.Down -> startEvaluation(snapshot.copy(hasHardFailure = true))
        ConnectionQualityClass.Degraded, ConnectionQualityClass.Stalled ->
            recordSoftFailure(snapshot, policy)
        ConnectionQualityClass.Unknown -> FailoverTransition(snapshot)
    }
}

private fun verifiedCurrentRoute(snapshot: FailoverSnapshot): FailoverTransition = FailoverTransition(
    snapshot.copy(
        state = FailoverState.Connected,
        previousKnownGoodRouteId = snapshot.currentRouteId,
        consecutiveSoftFailures = 0,
        hasHardFailure = false,
    ),
    listOf(FailoverAction(FailoverActionType.RecordKnownGood, snapshot.currentRouteId)),
)

private fun recordSoftFailure(
    snapshot: FailoverSnapshot,
    policy: FailoverPolicy,
): FailoverTransition {
    val count = snapshot.consecutiveSoftFailures + 1
    if (count < policy.softFailuresRequired.coerceAtLeast(1)) {
        return FailoverTransition(
            snapshot.copy(state = FailoverState.Suspect, consecutiveSoftFailures = count),
        )
    }
    return startEvaluation(
        snapshot.copy(consecutiveSoftFailures = count, hasHardFailure = false),
    )
}

private fun startEvaluation(snapshot: FailoverSnapshot): FailoverTransition = FailoverTransition(
    snapshot.copy(state = FailoverState.Evaluating),
    listOf(FailoverAction(FailoverActionType.EvaluateCandidate)),
)

private fun onCandidateSelected(
    snapshot: FailoverSnapshot,
    event: FailoverEvent.CandidateSelected,
    policy: FailoverPolicy,
): FailoverTransition {
    if (snapshot.state != FailoverState.Evaluating || event.routeId.isBlank()) {
        return FailoverTransition(snapshot)
    }
    val candidate = snapshot.copy(candidateRouteId = event.routeId)
    if (policy.executionMode == FailoverExecutionMode.Advisory ||
        isCooldownActive(snapshot, event.nowEpochSeconds, policy)
    ) {
        return FailoverTransition(
            candidate,
            listOf(FailoverAction(FailoverActionType.RecommendSwitch, event.routeId)),
        )
    }
    return FailoverTransition(
        candidate.copy(state = FailoverState.Switching),
        listOf(FailoverAction(FailoverActionType.ApplySwitch, event.routeId)),
    )
}

private fun isCooldownActive(
    snapshot: FailoverSnapshot,
    nowEpochSeconds: Long,
    policy: FailoverPolicy,
): Boolean {
    if (snapshot.hasHardFailure || snapshot.lastSwitchAtEpochSeconds <= 0) return false
    val age = nowEpochSeconds - snapshot.lastSwitchAtEpochSeconds
    return age in 0 until policy.switchCooldownSeconds.coerceAtLeast(0)
}

private fun onSwitchApplied(
    snapshot: FailoverSnapshot,
    event: FailoverEvent.SwitchApplied,
): FailoverTransition = FailoverTransition(
    snapshot.copy(
        state = FailoverState.Verifying,
        currentRouteId = event.routeId,
        lastSwitchAtEpochSeconds = event.nowEpochSeconds,
    ),
    listOf(FailoverAction(FailoverActionType.VerifyCandidate, event.routeId)),
)

private fun onVerifyPassed(
    snapshot: FailoverSnapshot,
    event: FailoverEvent.VerifyPassed,
): FailoverTransition = FailoverTransition(
    snapshot.copy(
        state = FailoverState.Connected,
        currentRouteId = event.routeId,
        candidateRouteId = "",
        previousKnownGoodRouteId = event.routeId,
        consecutiveSoftFailures = 0,
        hasHardFailure = false,
    ),
    listOf(FailoverAction(FailoverActionType.RecordKnownGood, event.routeId)),
)

private fun onVerifyFailed(
    snapshot: FailoverSnapshot,
    event: FailoverEvent.VerifyFailed,
): FailoverTransition {
    val quarantine = FailoverAction(FailoverActionType.Quarantine, event.routeId)
    if (snapshot.previousKnownGoodRouteId.isBlank()) {
        return FailoverTransition(
            snapshot.copy(state = FailoverState.Offline),
            listOf(quarantine),
        )
    }
    return FailoverTransition(
        snapshot.copy(state = FailoverState.Rollback),
        listOf(
            quarantine,
            FailoverAction(FailoverActionType.Rollback, snapshot.previousKnownGoodRouteId),
        ),
    )
}

private const val DEFAULT_SOFT_FAILURES_REQUIRED = 2
private const val DEFAULT_SWITCH_COOLDOWN_SECONDS = 10L * 60L
