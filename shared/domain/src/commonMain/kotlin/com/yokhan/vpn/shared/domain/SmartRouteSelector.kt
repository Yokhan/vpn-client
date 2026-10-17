package com.yokhan.vpn.shared.domain

data class SmartRouteCandidate(
    val routeId: String,
    val declaredOrder: Int,
    val localScore: Int? = null,
    val fleetScore: Int? = null,
    val operatorScore: Int? = null,
    val isEnabled: Boolean = true,
    val isHardDisabled: Boolean = false,
    val hasHardFailure: Boolean = false,
    val quarantineUntilEpochSeconds: Long = 0,
    val successfulProbeCount: Int = 0,
)

data class SmartRouteContext(
    val nowEpochSeconds: Long,
    val currentRouteId: String = "",
    val previousKnownGoodRouteId: String = "",
    val previousKnownGoodRecordedAt: Long = 0,
    val previousKnownGoodMaxAgeSeconds: Long = DEFAULT_PREVIOUS_KNOWN_GOOD_MAX_AGE_SECONDS,
    val lastSwitchAt: Long = 0,
    val switchCooldownSeconds: Long = DEFAULT_SWITCH_COOLDOWN_SECONDS,
    val minimumSwitchImprovement: Int = DEFAULT_SWITCH_IMPROVEMENT,
)

data class SmartRouteDecision(
    val selectedRouteId: String?,
    val orderedRouteIds: List<String>,
    val probeRouteIds: List<String>,
    val reason: String,
)

fun selectSmartRoute(
    candidates: List<SmartRouteCandidate>,
    context: SmartRouteContext,
): SmartRouteDecision {
    val eligible = candidates
        .filter { it.isEnabled && !it.isHardDisabled }
        .filter { it.quarantineUntilEpochSeconds <= context.nowEpochSeconds }
        .distinctBy { it.routeId }
    if (eligible.isEmpty()) {
        return SmartRouteDecision(null, emptyList(), emptyList(), "no eligible routes")
    }

    val ranked = eligible.sortedWith(
        compareByDescending<SmartRouteCandidate>(::smartRouteScore)
            .thenBy { it.declaredOrder }
            .thenBy { it.routeId },
    )
    val anchor = stableAnchor(eligible, context)
    val selected = chooseSelectedRoute(anchor, ranked.first(), context)
    val ordered = listOf(selected.routeId) + ranked
        .map { it.routeId }
        .filterNot { it == selected.routeId }
    return SmartRouteDecision(
        selectedRouteId = selected.routeId,
        orderedRouteIds = ordered,
        probeRouteIds = ranked.take(MAX_PROBE_CANDIDATES).map { it.routeId },
        reason = selectionReason(anchor, selected, ranked.first(), context),
    )
}

fun smartRouteScore(candidate: SmartRouteCandidate): Int {
    val local = candidate.localScore.normalizedScore()
    val fleet = candidate.fleetScore.normalizedScore()
    val operator = candidate.operatorScore.normalizedScore()
    val declaration = (100 - candidate.declaredOrder.coerceAtLeast(0) * 5).coerceIn(0, 100)
    return (local * 55 + fleet * 30 + operator * 10 + declaration * 5) / 100
}

private fun stableAnchor(
    candidates: List<SmartRouteCandidate>,
    context: SmartRouteContext,
): SmartRouteCandidate? {
    candidates.firstOrNull { it.routeId == context.currentRouteId && !it.hasHardFailure }
        ?.let { return it }
    val isPreviousFresh = context.previousKnownGoodRecordedAt > 0 &&
        context.nowEpochSeconds - context.previousKnownGoodRecordedAt in
        0..context.previousKnownGoodMaxAgeSeconds
    if (!isPreviousFresh) return null
    return candidates.firstOrNull {
        it.routeId == context.previousKnownGoodRouteId && !it.hasHardFailure
    }
}

private fun chooseSelectedRoute(
    anchor: SmartRouteCandidate?,
    challenger: SmartRouteCandidate,
    context: SmartRouteContext,
): SmartRouteCandidate {
    if (anchor == null || anchor.routeId == challenger.routeId) return challenger
    val isCooldownActive = context.lastSwitchAt > 0 &&
        context.nowEpochSeconds - context.lastSwitchAt in 0 until context.switchCooldownSeconds
    if (isCooldownActive) return anchor
    val improvement = smartRouteScore(challenger) - smartRouteScore(anchor)
    if (improvement < context.minimumSwitchImprovement) return anchor
    if (challenger.successfulProbeCount < REQUIRED_CHALLENGER_SUCCESSES) return anchor
    return challenger
}

private fun selectionReason(
    anchor: SmartRouteCandidate?,
    selected: SmartRouteCandidate,
    challenger: SmartRouteCandidate,
    context: SmartRouteContext,
): String = when {
    anchor == null -> "best eligible route; local probe required"
    selected.routeId == challenger.routeId && selected.routeId != anchor.routeId ->
        "challenger exceeded hysteresis threshold"
    selected.routeId == anchor.routeId && context.lastSwitchAt > 0 &&
        context.nowEpochSeconds - context.lastSwitchAt in 0 until context.switchCooldownSeconds ->
        "previous route retained during cooldown"
    selected.routeId == anchor.routeId -> "working route retained by hysteresis"
    else -> "best eligible route"
}

private fun Int?.normalizedScore(): Int = this?.coerceIn(0, 100) ?: NEUTRAL_SCORE

private const val NEUTRAL_SCORE = 50
private const val MAX_PROBE_CANDIDATES = 2
private const val REQUIRED_CHALLENGER_SUCCESSES = 2
private const val DEFAULT_SWITCH_IMPROVEMENT = 15
private const val DEFAULT_SWITCH_COOLDOWN_SECONDS = 10L * 60L
private const val DEFAULT_PREVIOUS_KNOWN_GOOD_MAX_AGE_SECONDS = 24L * 60L * 60L
